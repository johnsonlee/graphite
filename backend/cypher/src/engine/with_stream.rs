//! Stream total projection/filter chains across WITH boundaries.
//!
//! A final LIMIT bounds surviving rows, not the MATCH's inputs. Moving an arbitrary
//! expression across that boundary can hide a late error, so admission proves every
//! expression in the complete chain is total before any source is accessed.

use super::pipeline::{is_internal_key, item_names, project_row, QueryResult, Row};
use super::{matching::Matcher, scan::ScanPlan, Executor};
use crate::ast::{Clause, CmpOp, Expr, Literal, Pattern, ReturnItem, StrOp};
use crate::eval::Evaluator;
use crate::CypherResult;
use graphite_storage::node::TAG_CALL_SITE_NODE;
use std::collections::HashMap;

const MAX_EXPRESSION_DEPTH: usize = 32;
const MAX_EXPRESSION_NODES: usize = 256;

#[derive(Clone, Copy, PartialEq, Eq)]
enum Domain {
    CallSite,
    Other,
}

type Scope = HashMap<String, Domain>;

struct Projection<'a> {
    items: Option<&'a [ReturnItem]>,
    names: Vec<String>,
    predicate: Option<&'a Expr>,
}

pub(super) struct WithStreamPlan<'a> {
    patterns: &'a [Pattern],
    predicate: Option<&'a Expr>,
    projections: Vec<Projection<'a>>,
    output: Projection<'a>,
    skip: usize,
    limit: usize,
    certify_call_sites: bool,
}

impl<'a> WithStreamPlan<'a> {
    pub(super) fn build(clauses: &'a [Clause], rows: &[Row]) -> Option<Self> {
        if super::optimizations_disabled() || !matches!(rows, [seed] if seed.is_empty()) {
            return None;
        }
        let Clause::Match {
            patterns,
            optional: false,
            where_clause: None,
        } = clauses.first()?
        else {
            return None;
        };
        let [pattern] = patterns.as_slice() else {
            return None;
        };
        let [node] = pattern.nodes.as_slice() else {
            return None;
        };
        if pattern.path_variable.is_some() || !pattern.rels.is_empty() {
            return None;
        }
        let variable = node.variable.as_ref()?;
        let call_sites = node.labels.first().is_some_and(|label| {
            super::props::label_tags(label)
                .is_some_and(|tags| tags.as_slice() == [TAG_CALL_SITE_NODE])
        }) && node.labels.iter().skip(1).all(|label| {
            super::props::label_tags(label).is_some_and(|tags| tags.contains(&TAG_CALL_SITE_NODE))
        });
        // Multiple label checks inspect record tags even in a pure projection.
        // Other storage domains have no validity proof yet and remain eager.
        if node.labels.len() > 1 && !call_sites {
            return None;
        }
        let mut scope = Scope::from([(
            variable.clone(),
            if call_sites {
                Domain::CallSite
            } else {
                Domain::Other
            },
        )]);
        let mut remaining = MAX_EXPRESSION_NODES;
        for (key, value) in &node.properties {
            if !call_sites
                || !stored_property(key)
                || total(value, &Scope::new(), 0, &mut remaining).is_none()
            {
                return None;
            }
        }
        let mut i = 1;
        let predicate = if let Some(Clause::Where(expr)) = clauses.get(i) {
            total(expr, &scope, 0, &mut remaining)?;
            i += 1;
            Some(expr)
        } else {
            None
        };
        let mut projections = Vec::new();
        while let Some(Clause::With {
            distinct: false,
            items,
            where_clause,
        }) = clauses.get(i)
        {
            let projection = Projection::build(
                items.as_deref(),
                where_clause.as_ref(),
                &mut scope,
                &mut remaining,
            )?;
            projections.push(projection);
            i += 1;
        }
        if projections.is_empty() {
            return None;
        }
        // Explicit output columns are known even for LIMIT 0 or an empty match.
        // RETURN * keeps its row-dependent expansion on the ordinary pipeline.
        let Clause::Return {
            distinct: false,
            items: Some(items),
        } = clauses.get(i)?
        else {
            return None;
        };
        let output = Projection::build(Some(items), None, &mut scope, &mut remaining)?;
        i += 1;
        let skip = if let Some(Clause::Skip(expr)) = clauses.get(i) {
            i += 1;
            literal_count(expr)?
        } else {
            0
        };
        let Clause::Limit(expr) = clauses.get(i)? else {
            return None;
        };
        let limit = literal_count(expr)?;
        if i + 1 != clauses.len() {
            return None;
        }
        Some(Self {
            patterns,
            predicate,
            projections,
            output,
            skip,
            limit,
            certify_call_sites: call_sites,
        })
    }

    pub(super) fn run(
        &self,
        ex: &Executor,
        matcher: &Matcher,
        ev: &Evaluator,
    ) -> CypherResult<Option<QueryResult>> {
        ex.cancel.check()?;
        if self.certify_call_sites {
            for source in &ex.sources {
                if !source
                    .graph
                    .call_site_scalar_records_valid(|| ex.cancel.check())?
                {
                    return Ok(None);
                }
            }
        }
        let columns = self.output.names.clone();
        let mut out = Vec::new();
        if self.limit == 0 {
            return Ok(Some(QueryResult {
                columns,
                rows: out,
                compact: None,
                more: false,
            }));
        }
        let scan = ScanPlan::build(self.patterns, self.predicate);
        let mut skipped = 0;
        let mut consume = |row: Row| {
            let Some(row) = self.project(ex, ev, row)? else {
                return Ok(true);
            };
            if skipped < self.skip {
                skipped += 1;
                return Ok(true);
            }
            out.push(row);
            Ok(out.len() < self.limit)
        };
        ex.stream_match(
            matcher,
            ev,
            &Row::new(),
            self.patterns,
            self.predicate,
            &scan,
            &None,
            &mut consume,
        )?;
        Ok(Some(QueryResult {
            columns,
            rows: out,
            compact: None,
            more: false,
        }))
    }

    fn project(&self, ex: &Executor, ev: &Evaluator, mut row: Row) -> CypherResult<Option<Row>> {
        for projection in &self.projections {
            // The ordinary wildcard projector produces no rows when it has no
            // visible columns to expand, even if the row carries provenance.
            if projection.items.is_none() && row.keys().all(|key| is_internal_key(key)) {
                return Ok(None);
            }
            row = projection.project(ev, &row)?;
            if let Some(predicate) = projection.predicate {
                ex.tick()?;
                if ev.eval(predicate, &row)?.as_bool() != Some(true) {
                    return Ok(None);
                }
            }
        }
        Ok(Some(self.output.project(ev, &row)?))
    }
}

impl<'a> Projection<'a> {
    fn build(
        items: Option<&'a [ReturnItem]>,
        predicate: Option<&'a Expr>,
        scope: &mut Scope,
        remaining: &mut usize,
    ) -> Option<Self> {
        let names = items.map(item_names).unwrap_or_default();
        if let Some(items) = items {
            let mut projected = Scope::new();
            // Every item reads the pre-projection scope; aliases in the same WITH
            // do not become visible until all items have been evaluated.
            for (item, name) in items.iter().zip(&names) {
                projected.insert(name.clone(), total(&item.expr, scope, 0, remaining)?);
            }
            *scope = projected;
        }
        if predicate.is_some_and(|expr| total(expr, scope, 0, remaining).is_none()) {
            return None;
        }
        Some(Self {
            items,
            names,
            predicate,
        })
    }

    fn project(&self, ev: &Evaluator, row: &Row) -> CypherResult<Row> {
        let mut columns = Vec::new();
        project_row(
            ev,
            row,
            self.items,
            &self.names,
            None,
            false,
            false,
            &mut columns,
        )
    }
}

fn literal_count(expr: &Expr) -> Option<usize> {
    let Expr::Literal(Literal::Int(value)) = expr else {
        return None;
    };
    // eval_count applies the same JVM Int ceiling even to a Long literal.
    (*value >= 0).then(|| (*value).min(i32::MAX as i64) as usize)
}

fn stored_property(key: &str) -> bool {
    // These CallSite getters are total after complete framing/scalar SID proof.
    // Signatures/descriptors read additional unproved IDs; dynamic parameter/map
    // properties and other node kinds retain their original eager evaluation.
    matches!(
        key,
        "id" | "caller_class"
            | "caller_name"
            | "callee_class"
            | "callee_name"
            | "graphId"
            | "elementId"
            | "qualifiedId"
            | "line"
            | "ordinal"
    )
}

fn total(expr: &Expr, scope: &Scope, depth: usize, remaining: &mut usize) -> Option<Domain> {
    if depth >= MAX_EXPRESSION_DEPTH || *remaining == 0 {
        return None;
    }
    *remaining -= 1;
    match expr {
        Expr::Literal(_) | Expr::Parameter(_) => Some(Domain::Other),
        Expr::Variable(name) => Some(scope.get(name).copied().unwrap_or(Domain::Other)),
        Expr::Property { expr, key } => (stored_property(key)
            && total(expr, scope, depth + 1, remaining)? == Domain::CallSite)
            .then_some(Domain::Other),
        Expr::IsNull(expr) | Expr::IsNotNull(expr) => {
            total(expr, scope, depth + 1, remaining)?;
            Some(Domain::Other)
        }
        Expr::Comparison {
            op: CmpOp::Eq | CmpOp::Ne,
            left,
            right,
        }
        | Expr::StringOp {
            op: StrOp::Contains | StrOp::StartsWith | StrOp::EndsWith,
            left,
            right,
        }
        | Expr::In { left, right }
        | Expr::And(left, right)
        | Expr::Or(left, right)
        | Expr::Xor(left, right) => {
            total(left, scope, depth + 1, remaining)?;
            total(right, scope, depth + 1, remaining)?;
            Some(Domain::Other)
        }
        Expr::ListLiteral(items) => {
            for item in items {
                total(item, scope, depth + 1, remaining)?;
            }
            Some(Domain::Other)
        }
        Expr::MapLiteral(items) => {
            for (_, item) in items {
                total(item, scope, depth + 1, remaining)?;
            }
            Some(Domain::Other)
        }
        // Ordered mixed-domain comparisons can stringify graph values and read
        // declarations. Functions, arithmetic, NOT, regex and all other expressions
        // likewise retain their eager/error boundaries through fallback.
        _ => None,
    }
}

#[cfg(test)]
#[path = "with_stream_tests.rs"]
mod tests;

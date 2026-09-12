//! Clause pipeline: MATCH / WHERE / WITH / RETURN / UNWIND / ORDER BY / SKIP / LIMIT / UNION.

use super::matching::{has_unknown_label, Matcher};
use super::Executor;
use crate::ast::{Clause, Expr, Literal, OrderItem, Pattern, ReturnItem};
use crate::eval::{contains_aggregation, is_aggregation_name, Evaluator};
use crate::functions::aggregate;
use crate::ordering::compare_order_values;
use crate::parser::parse_cached;
use crate::render::to_cypher_string;
use crate::semantics::{to_int_for_skip_limit, value_key, Key};
use crate::value::Value;
use crate::{CypherError, CypherResult};
use indexmap::IndexMap;
use std::cmp::Ordering;
use std::sync::Arc;

pub type Row = IndexMap<String, Value>;

/// Hidden row key carrying cross-graph provenance (sorted unique graph ids as a list of strings).
pub const INTERNAL_PROVENANCE_KEY: &str = "\u{0}graphite.graphIds";
const ORDER_STASH_PREFIX: &str = "\u{0}order:";
const AGG_PLACEHOLDER_PREFIX: &str = "\u{0}agg:";

#[derive(Debug, Clone, Default)]
pub struct QueryResult {
    pub columns: Vec<String>,
    pub rows: Vec<Row>,
}

impl QueryResult {
    /// Provenance graph ids of a row (cross-graph mode), sorted.
    pub fn graph_ids(row: &Row) -> Vec<String> {
        match row.get(INTERNAL_PROVENANCE_KEY) {
            Some(Value::List(l)) => l
                .iter()
                .filter_map(|v| v.as_str().map(|s| s.to_string()))
                .collect(),
            _ => vec![],
        }
    }
}

#[inline]
pub fn is_internal_key(k: &str) -> bool {
    k.starts_with('\u{0}')
}

/// Record the graph id of a bound node/relationship value (cross-graph mode only).
pub fn add_provenance(row: &mut Row, ex: &Executor, v: &Value) {
    if !ex.cross {
        return;
    }
    let source = match v {
        Value::Node(n) => n.source,
        Value::Rel(e) => e.source,
        Value::Method(m) => m.source,
        Value::Path(p) => p.source,
        _ => return,
    };
    let gid = ex.sources[source as usize].id.clone();
    add_provenance_id(row, gid);
}

pub fn add_provenance_id(row: &mut Row, gid: Arc<str>) {
    let mut ids: Vec<Value> = match row.get(INTERNAL_PROVENANCE_KEY) {
        Some(Value::List(l)) => l.as_ref().clone(),
        _ => vec![],
    };
    if ids.iter().any(|x| x.as_str() == Some(&gid)) {
        return;
    }
    ids.push(Value::Str(gid));
    ids.sort_by(|a, b| a.as_str().cmp(&b.as_str()));
    row.insert(INTERNAL_PROVENANCE_KEY.to_string(), Value::list(ids));
}

fn merge_provenance(into: &mut Row, from: &Row) {
    if let Some(Value::List(l)) = from.get(INTERNAL_PROVENANCE_KEY) {
        for v in l.iter() {
            if let Value::Str(s) = v {
                add_provenance_id(into, s.clone());
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Entry points
// ---------------------------------------------------------------------------

impl Executor {
    /// Execute a query; `max_rows` injects/lowers a LIMIT per UNION segment (Kotlin `execute(cypher, maxRows)`).
    pub fn execute(&self, query: &str, max_rows: Option<usize>) -> CypherResult<QueryResult> {
        let clauses = parse_cached(query)?;
        self.execute_clauses(&clauses, max_rows)
    }

    pub fn execute_clauses(
        &self,
        clauses: &[Clause],
        max_rows: Option<usize>,
    ) -> CypherResult<QueryResult> {
        self.cancel.check()?;
        // Split on UNION markers.
        let mut segments: Vec<(Vec<Clause>, bool)> = vec![(Vec::new(), false)];
        for c in clauses {
            if let Clause::Union { all } = c {
                segments.push((Vec::new(), *all));
            } else {
                segments.last_mut().unwrap().0.push(c.clone());
            }
        }
        let mut result: Option<QueryResult> = None;
        let mut seen: std::collections::HashSet<Vec<Key>> = std::collections::HashSet::new();
        for (i, (mut seg, all)) in segments.into_iter().enumerate() {
            if let Some(max) = max_rows {
                inject_limit(&mut seg, max);
            }
            let r = self.run_segment(&seg)?;
            match &mut result {
                None => {
                    if i == 0 {
                        result = Some(r);
                    }
                }
                Some(acc) => {
                    if all {
                        acc.rows.extend(r.rows);
                    } else {
                        if acc.rows.len() > seen.len() {
                            // lazily seed from existing rows
                            let mut dedup = Vec::new();
                            for row in acc.rows.drain(..) {
                                let k = visible_key(&row);
                                if seen.insert(k) {
                                    dedup.push(row);
                                }
                            }
                            acc.rows = dedup;
                        }
                        for row in r.rows {
                            let k = visible_key(&row);
                            if seen.insert(k) {
                                acc.rows.push(row);
                            }
                        }
                    }
                    if acc.columns.is_empty() {
                        acc.columns = r.columns;
                    }
                }
            }
        }
        let mut res = result.unwrap_or_default();
        // Strip internal keys except provenance.
        for row in &mut res.rows {
            row.retain(|k, _| !is_internal_key(k) || k == INTERNAL_PROVENANCE_KEY);
        }
        Ok(res)
    }

    fn run_segment(&self, clauses: &[Clause]) -> CypherResult<QueryResult> {
        // Index-only shapes are answered before any row work begins.
        if !super::optimizations_disabled() {
            if let Some(r) = super::fastpath::count_star(self, clauses)? {
                return Ok(r);
            }
            if let Some(r) = super::fastpath::grouped_call_site_property(self, clauses)? {
                return Ok(r);
            }
            if let Some(r) = super::fastpath::distinct_string_property(self, clauses)? {
                return Ok(r);
            }
        }
        let ev = Evaluator::new(self, &self.params);
        let matcher = Matcher { ex: self, ev: &ev };
        let mut rows: Vec<Row> = vec![Row::new()];
        let mut columns: Vec<String> = Vec::new();
        let mut i = 0;
        while i < clauses.len() {
            self.cancel.check()?;
            match &clauses[i] {
                Clause::Match {
                    patterns,
                    optional,
                    where_clause,
                } => {
                    if !*optional {
                        if let Some(shape) = FusedShape::detect(&clauses[i..]) {
                            let out = self.run_fused(&matcher, &ev, rows, patterns, &shape)?;
                            return Ok(out);
                        }
                    }
                    let early_limit = compute_early_limit(&clauses[i..]);
                    rows = self.exec_match(
                        &matcher,
                        &ev,
                        rows,
                        patterns,
                        *optional,
                        where_clause.as_ref(),
                        early_limit,
                    )?;
                }
                Clause::Where(expr) => {
                    let mut out = Vec::with_capacity(rows.len());
                    for r in rows {
                        self.tick()?;
                        if ev.eval(expr, &r)?.as_bool() == Some(true) {
                            out.push(r);
                        }
                    }
                    rows = out;
                }
                Clause::Unwind { expr, variable } => {
                    let mut out = Vec::new();
                    for r in rows {
                        self.tick()?;
                        if let Value::List(l) = ev.eval(expr, &r)? {
                            for item in l.iter() {
                                let mut nr = r.clone();
                                nr.insert(variable.clone(), item.clone());
                                out.push(nr);
                            }
                        }
                    }
                    rows = out;
                }
                Clause::With {
                    distinct,
                    items,
                    where_clause,
                } => {
                    let order = next_order_by(&clauses[i + 1..]);
                    let (cols, projected) = project(&ev, rows, items.as_deref(), *distinct, order)?;
                    columns = cols;
                    rows = projected;
                    if let Some(w) = where_clause {
                        let mut out = Vec::with_capacity(rows.len());
                        for r in rows {
                            self.tick()?;
                            if ev.eval(w, &r)?.as_bool() == Some(true) {
                                out.push(r);
                            }
                        }
                        rows = out;
                    }
                }
                Clause::Return { distinct, items } => {
                    let order = next_order_by(&clauses[i + 1..]);
                    let (cols, projected) = project(&ev, rows, items.as_deref(), *distinct, order)?;
                    columns = cols;
                    rows = projected;
                }
                Clause::OrderBy(items) => {
                    rows = order_rows(&ev, rows, items)?;
                }
                Clause::Skip(expr) => {
                    let n = eval_count(&ev, expr, rows.first())?;
                    if n < 0 {
                        return Err(CypherError::Other(format!(
                            "Requested element count {n} is less than zero."
                        )));
                    }
                    rows = rows.into_iter().skip(n as usize).collect();
                }
                Clause::Limit(expr) => {
                    let n = eval_count(&ev, expr, rows.first())?;
                    if n < 0 {
                        return Err(CypherError::Other(format!(
                            "Requested element count {n} is less than zero."
                        )));
                    }
                    rows.truncate(n as usize);
                }
                Clause::Create(_) => {
                    return Err(CypherError::NotImplemented(
                        "CREATE is not supported — graph is immutable".into(),
                    ))
                }
                Clause::Delete { .. } => {
                    return Err(CypherError::NotImplemented(
                        "DELETE is not supported — graph is immutable".into(),
                    ))
                }
                Clause::Set => {
                    return Err(CypherError::NotImplemented(
                        "SET is not supported — graph is immutable".into(),
                    ))
                }
                Clause::Remove => {
                    return Err(CypherError::NotImplemented(
                        "REMOVE is not supported — graph is immutable".into(),
                    ))
                }
                Clause::Union { .. } => {
                    return Err(CypherError::NotImplemented(
                        "UNION is handled by CypherExecutor, not QueryPipeline".into(),
                    ))
                }
            }
            i += 1;
        }
        if columns.is_empty() {
            if let Some(first) = rows.first() {
                columns = first
                    .keys()
                    .filter(|k| !is_internal_key(k))
                    .cloned()
                    .collect();
            }
        }
        // Strip order stashes.
        for r in &mut rows {
            r.retain(|k, _| {
                !k.starts_with(ORDER_STASH_PREFIX) && !k.starts_with(AGG_PLACEHOLDER_PREFIX)
            });
        }
        Ok(QueryResult { columns, rows })
    }

    #[allow(clippy::too_many_arguments)]
    fn exec_match(
        &self,
        matcher: &Matcher,
        ev: &Evaluator,
        rows: Vec<Row>,
        patterns: &[Pattern],
        optional: bool,
        where_clause: Option<&Expr>,
        early_limit: Option<usize>,
    ) -> CypherResult<Vec<Row>> {
        let mut out: Vec<Row> = Vec::new();
        if has_unknown_label(patterns) && !optional {
            return Ok(out);
        }
        for r in rows {
            let mut produced = false;
            let mut stop = false;
            {
                let mut emit = |row: Row| -> CypherResult<bool> {
                    if let Some(w) = where_clause {
                        if ev.eval(w, &row)?.as_bool() != Some(true) {
                            return Ok(true);
                        }
                    }
                    produced = true;
                    out.push(row);
                    if let Some(l) = early_limit {
                        if out.len() >= l {
                            stop = true;
                            return Ok(false);
                        }
                    }
                    Ok(true)
                };
                if !(has_unknown_label(patterns)) {
                    matcher.match_patterns(&r, patterns, &mut emit)?;
                }
            }
            if optional && !produced {
                let mut nr = r.clone();
                for p in patterns {
                    for v in p.variables() {
                        if !nr.contains_key(v) {
                            nr.insert(v.to_string(), Value::Null);
                        }
                    }
                }
                out.push(nr);
            }
            if stop {
                break;
            }
        }
        Ok(out)
    }

    /// Fused MATCH [WHERE] RETURN [ORDER BY] [SKIP] [LIMIT] streaming execution.
    fn run_fused(
        &self,
        matcher: &Matcher,
        ev: &Evaluator,
        rows: Vec<Row>,
        patterns: &[Pattern],
        shape: &FusedShape,
    ) -> CypherResult<QueryResult> {
        if has_unknown_label(patterns) {
            // No candidates: projection over zero rows.
            let (cols, out) = project(
                ev,
                vec![],
                shape.items.as_deref(),
                shape.distinct,
                shape.order.as_deref(),
            )?;
            return Ok(finish_fused(ev, cols, out, shape)?);
        }
        let skip = match &shape.skip {
            Some(e) => Some(eval_count(ev, e, None)?),
            None => None,
        };
        let limit = match &shape.limit {
            Some(e) => Some(eval_count(ev, e, None)?),
            None => None,
        };
        if let Some(n) = skip.filter(|n| *n < 0).or(limit.filter(|n| *n < 0)) {
            return Err(CypherError::Other(format!(
                "Requested element count {n} is less than zero."
            )));
        }
        let budget: Option<usize> = match (skip, limit) {
            (_, None) => None,
            (Some(s), Some(l)) => Some((s + l) as usize),
            (None, Some(l)) => Some(l as usize),
        };
        let aggregated = match &shape.items {
            Some(items) => items.iter().any(|it| contains_aggregation(&it.expr)),
            None => false,
        };
        let items = shape.items.as_deref();
        let scan = super::scan::ScanPlan::build(patterns, shape.where_clause.as_ref());

        if aggregated {
            // Streaming group-by.
            let items = items.unwrap();
            let plan = AggPlan::new(items)?;
            let columns: Vec<String> = items
                .iter()
                .map(|it| {
                    it.alias
                        .clone()
                        .unwrap_or_else(|| to_cypher_string(&it.expr))
                })
                .collect();
            let mut groups: IndexMap<Vec<Key>, GroupAcc> = IndexMap::new();
            let mut consume = |row: Row| -> CypherResult<bool> {
                let mut keyv = Vec::with_capacity(plan.group_exprs.len());
                let mut vals = Vec::with_capacity(plan.group_exprs.len());
                for e in &plan.group_exprs {
                    let v = ev.eval(e, &row)?;
                    keyv.push(value_key(&v));
                    vals.push(v);
                }
                let acc = groups.entry(keyv).or_insert_with(|| GroupAcc {
                    first_row: row.clone(),
                    group_values: vals,
                    agg_inputs: vec![Vec::new(); plan.aggs.len()],
                });
                for (ai, agg) in plan.aggs.iter().enumerate() {
                    let v = match &agg.arg {
                        Some(e) => ev.eval(e, &row)?,
                        None => Value::map(
                            row.iter()
                                .filter(|(k, _)| !is_internal_key(k))
                                .map(|(k, v)| (k.clone(), v.clone()))
                                .collect(),
                        ),
                    };
                    acc.agg_inputs[ai].push(v);
                }
                merge_provenance(&mut acc.first_row, &row);
                Ok(true)
            };
            for r in rows {
                self.stream_match(
                    matcher,
                    ev,
                    &r,
                    patterns,
                    shape.where_clause.as_ref(),
                    &scan,
                    &mut consume,
                )?;
            }
            let out = finalize_groups(ev, &plan, items, groups, shape.order.as_deref())?;
            return finish_fused(ev, columns, out, shape);
        }

        // Non-aggregated: stream projection.
        let mut out: Vec<Row> = Vec::new();
        let mut columns: Vec<String> = match items {
            Some(items) => items
                .iter()
                .map(|it| {
                    it.alias
                        .clone()
                        .unwrap_or_else(|| to_cypher_string(&it.expr))
                })
                .collect(),
            None => Vec::new(),
        };
        // Index of each distinct key into `out`, not just the set of keys. Finding the
        // row to merge provenance into by scanning `out` is linear, and it runs once per
        // duplicate — quadratic on the shape that produces duplicates by the million.
        let mut seen: std::collections::HashMap<Vec<Key>, usize> = std::collections::HashMap::new();
        // A cross-graph DISTINCT cannot stop when it has enough rows. Each row carries the
        // set of graphs it was seen in, and a graph reached after the limit can still hold
        // a duplicate of a row already emitted — which belongs in that row's provenance.
        // The baseline keeps scanning for exactly this reason; stopping early produced
        // rows identical in every visible column but missing a contributing graph.
        let distinct_provenance = shape.distinct && self.cross;
        // Provenance completion runs as a targeted second pass where it can, so the
        // first pass may stop at the limit like any other.
        let targeted = distinct_provenance
            .then(|| provenance_property(items))
            .flatten();
        let needs_all = shape.order.is_some() || (distinct_provenance && targeted.is_none());
        let mut consume = |row: Row| -> CypherResult<bool> {
            let projected = project_row(
                ev,
                &row,
                items,
                shape.order.as_deref(),
                shape.distinct,
                &mut columns,
            )?;
            if shape.distinct {
                let k = visible_key(&projected);
                match seen.get(&k) {
                    Some(&at) => {
                        merge_provenance(&mut out[at], &projected);
                        return Ok(true);
                    }
                    None => {
                        // Past the limit the scan continues only to complete provenance,
                        // so further distinct rows are not collected.
                        if distinct_provenance && budget.is_some_and(|b| out.len() >= b) {
                            return Ok(true);
                        }
                        seen.insert(k, out.len());
                    }
                }
            }
            out.push(projected);
            if !needs_all {
                if let Some(b) = budget {
                    if out.len() >= b {
                        return Ok(false);
                    }
                }
            }
            Ok(true)
        };
        let seeds: Vec<Row> = rows.clone();
        for r in rows {
            let cont = self.stream_match(
                matcher,
                ev,
                &r,
                patterns,
                shape.where_clause.as_ref(),
                &scan,
                &mut consume,
            )?;
            if !cont {
                break;
            }
        }
        drop(consume);

        // Second pass: complete the provenance of the rows already chosen.
        //
        // A cross-graph DISTINCT row carries the graphs it was seen in, and a graph
        // reached after the limit can still hold a duplicate of a row already emitted.
        // Scanning on to find those costs as much as the query itself. Instead the rows
        // are now known, so their values become the filter: `<property> = one of these`
        // is a disjunction of equalities, which the pushdown answers by binary search in
        // each dictionary and an intersection with the original predicate. Nothing new is
        // collected -- only the provenance of what is already there is merged.
        if let Some((column, property, variable)) = targeted {
            if let Some(filter) = selected_value_filter(&out, &column, &property, &variable) {
                let combined = match shape.where_clause.clone() {
                    Some(w) => Expr::And(Box::new(w), Box::new(filter)),
                    None => filter,
                };
                let scan2 = super::scan::ScanPlan::build(patterns, Some(&combined));
                let mut merge = |row: Row| -> CypherResult<bool> {
                    let projected = project_row(
                        ev,
                        &row,
                        items,
                        shape.order.as_deref(),
                        shape.distinct,
                        &mut columns,
                    )?;
                    if let Some(&at) = seen.get(&visible_key(&projected)) {
                        merge_provenance(&mut out[at], &projected);
                    }
                    Ok(true)
                };
                for r in &seeds {
                    self.stream_match(
                        matcher,
                        ev,
                        r,
                        patterns,
                        Some(&combined),
                        &scan2,
                        &mut merge,
                    )?;
                }
            }
        }
        finish_fused(ev, columns, out, shape)
    }

    /// Enumerate matches of `patterns` from `row`, applying the WHERE filter (and scan pushdown).
    #[allow(clippy::too_many_arguments)]
    fn stream_match(
        &self,
        matcher: &Matcher,
        ev: &Evaluator,
        row: &Row,
        patterns: &[Pattern],
        where_clause: Option<&Expr>,
        scan: &Option<super::scan::ScanPlan>,
        consume: &mut dyn FnMut(Row) -> CypherResult<bool>,
    ) -> CypherResult<bool> {
        if let Some(plan) = scan {
            if row.is_empty() {
                return plan.run(self, ev, row, where_clause, consume);
            }
        }
        let mut emit = |r: Row| -> CypherResult<bool> {
            if let Some(w) = where_clause {
                if ev.eval(w, &r)?.as_bool() != Some(true) {
                    return Ok(true);
                }
            }
            consume(r)
        };
        matcher.match_patterns(row, patterns, &mut emit)
    }
}

fn finish_fused(
    ev: &Evaluator,
    columns: Vec<String>,
    mut rows: Vec<Row>,
    shape: &FusedShape,
) -> CypherResult<QueryResult> {
    if let Some(order) = &shape.order {
        rows = order_rows(ev, rows, order)?;
    }
    if let Some(e) = &shape.skip {
        let n = eval_count(ev, e, rows.first())?;
        if n < 0 {
            return Err(CypherError::Other(format!(
                "Requested element count {n} is less than zero."
            )));
        }
        rows = rows.into_iter().skip(n as usize).collect();
    }
    if let Some(e) = &shape.limit {
        let n = eval_count(ev, e, rows.first())?;
        if n < 0 {
            return Err(CypherError::Other(format!(
                "Requested element count {n} is less than zero."
            )));
        }
        rows.truncate(n as usize);
    }
    let mut columns = columns;
    if columns.is_empty() {
        if let Some(first) = rows.first() {
            columns = first
                .keys()
                .filter(|k| !is_internal_key(k))
                .cloned()
                .collect();
        }
    }
    for r in &mut rows {
        r.retain(|k, _| {
            !k.starts_with(ORDER_STASH_PREFIX) && !k.starts_with(AGG_PLACEHOLDER_PREFIX)
        });
    }
    Ok(QueryResult { columns, rows })
}

/// Shape: MATCH [WHERE] RETURN [ORDER BY] [SKIP] [LIMIT] <end>
pub struct FusedShape {
    pub where_clause: Option<Expr>,
    pub items: Option<Vec<ReturnItem>>,
    pub distinct: bool,
    pub order: Option<Vec<OrderItem>>,
    pub skip: Option<Expr>,
    pub limit: Option<Expr>,
}

impl FusedShape {
    fn detect(clauses: &[Clause]) -> Option<FusedShape> {
        let mut i = 1;
        let mut where_clause = None;
        if let Some(Clause::Where(e)) = clauses.get(i) {
            where_clause = Some(e.clone());
            i += 1;
        }
        let (distinct, items) = match clauses.get(i) {
            Some(Clause::Return { distinct, items }) => (*distinct, items.clone()),
            _ => return None,
        };
        i += 1;
        let mut order = None;
        if let Some(Clause::OrderBy(o)) = clauses.get(i) {
            order = Some(o.clone());
            i += 1;
        }
        let mut skip = None;
        if let Some(Clause::Skip(e)) = clauses.get(i) {
            skip = Some(e.clone());
            i += 1;
        }
        let mut limit = None;
        if let Some(Clause::Limit(e)) = clauses.get(i) {
            limit = Some(e.clone());
            i += 1;
        }
        if i != clauses.len() {
            return None;
        }
        // RETURN * in a fused shape depends on row keys; fine (handled by project_row).
        Some(FusedShape {
            where_clause,
            items,
            distinct,
            order,
            skip,
            limit,
        })
    }
}

// ---------------------------------------------------------------------------
// LIMIT injection and early limit
// ---------------------------------------------------------------------------

fn inject_limit(seg: &mut Vec<Clause>, max: usize) {
    let lit = Clause::Limit(Expr::Literal(Literal::Int(max as i64)));
    if let Some(pos) = seg.iter().rposition(|c| matches!(c, Clause::Limit(_))) {
        match &seg[pos] {
            Clause::Limit(Expr::Literal(Literal::Int(n))) => {
                if *n > max as i64 {
                    seg[pos] = lit;
                }
            }
            _ => seg.insert(pos, lit),
        }
    } else {
        seg.push(lit);
    }
}

fn compute_early_limit(clauses: &[Clause]) -> Option<usize> {
    // MATCH followed by RETURN (no aggregation/distinct) and a literal LIMIT.
    //
    // Any number of row-preserving WITH clauses may sit in between. A WITH that only
    // renames or projects emits exactly one row per input row, so a LIMIT after it bounds
    // the match just as tightly. One that filters, aggregates, de-duplicates, orders or
    // pages does not: fewer rows may come out than went in, and stopping the match at n
    // would return short. Those end the walk and the match stays unbounded.
    let mut i = 1;
    while let Some(Clause::With {
        distinct,
        items,
        where_clause,
    }) = clauses.get(i)
    {
        if *distinct || where_clause.is_some() {
            return None;
        }
        if let Some(items) = items {
            if items.iter().any(|it| contains_aggregation(&it.expr)) {
                return None;
            }
        }
        i += 1;
        // ORDER BY / SKIP / LIMIT attached to this WITH break the one-to-one mapping.
        if matches!(
            clauses.get(i),
            Some(Clause::OrderBy(_)) | Some(Clause::Skip(_)) | Some(Clause::Limit(_))
        ) {
            return None;
        }
    }
    match clauses.get(i) {
        Some(Clause::Return {
            distinct: false,
            items,
        }) => {
            if let Some(items) = items {
                if items.iter().any(|it| contains_aggregation(&it.expr)) {
                    return None;
                }
            }
        }
        _ => return None,
    }
    i += 1;
    match clauses.get(i) {
        Some(Clause::Limit(Expr::Literal(Literal::Int(n)))) if i + 1 == clauses.len() => {
            if *n < 0 {
                None
            } else {
                Some(*n as usize)
            }
        }
        _ => None,
    }
}

fn next_order_by(rest: &[Clause]) -> Option<&[OrderItem]> {
    match rest.first() {
        Some(Clause::OrderBy(items)) => Some(items.as_slice()),
        _ => None,
    }
}

fn eval_count(ev: &Evaluator, expr: &Expr, first: Option<&Row>) -> CypherResult<i64> {
    let empty = Row::new();
    let v = ev.eval(expr, first.unwrap_or(&empty))?;
    Ok(to_int_for_skip_limit(&v))
}

// ---------------------------------------------------------------------------
// Projection & aggregation
// ---------------------------------------------------------------------------

fn visible_key(row: &Row) -> Vec<Key> {
    row.iter()
        .filter(|(k, _)| !is_internal_key(k))
        .map(|(_, v)| value_key(v))
        .collect()
}

/// Order-by expressions that are not bare projected columns get pre-evaluated on the pre-projection row.
fn stash_order_values(
    ev: &Evaluator,
    pre: &Row,
    out: &mut Row,
    order: Option<&[OrderItem]>,
    columns: &[String],
) -> CypherResult<()> {
    if let Some(order) = order {
        for (i, item) in order.iter().enumerate() {
            let is_col = matches!(&item.expr, Expr::Variable(v) if columns.iter().any(|c| c == v));
            if !is_col {
                let v = ev.eval(&item.expr, pre)?;
                out.insert(format!("{ORDER_STASH_PREFIX}{i}"), v);
            }
        }
    }
    Ok(())
}

fn project_row(
    ev: &Evaluator,
    row: &Row,
    items: Option<&[ReturnItem]>,
    order: Option<&[OrderItem]>,
    distinct: bool,
    columns: &mut Vec<String>,
) -> CypherResult<Row> {
    let mut out = Row::new();
    match items {
        None => {
            for (k, v) in row.iter() {
                if !is_internal_key(k) {
                    out.insert(k.clone(), v.clone());
                }
            }
            if columns.is_empty() {
                *columns = out.keys().cloned().collect();
            }
        }
        Some(items) => {
            for it in items {
                let name = it
                    .alias
                    .clone()
                    .unwrap_or_else(|| to_cypher_string(&it.expr));
                let v = ev.eval(&it.expr, row)?;
                out.insert(name, v);
            }
        }
    }
    if let Some(p) = row.get(INTERNAL_PROVENANCE_KEY) {
        out.insert(INTERNAL_PROVENANCE_KEY.to_string(), p.clone());
    }
    if !distinct {
        stash_order_values(ev, row, &mut out, order, columns)?;
    }
    Ok(out)
}

struct AggCall {
    name: String,
    distinct: bool,
    arg: Option<Expr>,
}

struct AggPlan {
    /// Rewritten items with aggregation calls replaced by placeholders.
    rewritten: Vec<Expr>,
    aggs: Vec<AggCall>,
    group_exprs: Vec<Expr>,
}

impl AggPlan {
    fn new(items: &[ReturnItem]) -> CypherResult<AggPlan> {
        let mut aggs = Vec::new();
        let mut rewritten = Vec::new();
        let mut group_exprs = Vec::new();
        for it in items {
            if contains_aggregation(&it.expr) {
                // An aggregate is only a projection when it *is* the projected
                // expression. Nested inside a larger one — `count(*) * 2` — the
                // baseline evaluates the outer expression generically, and the
                // evaluator refuses the aggregate it finds there. Substituting the
                // aggregate's value instead would answer a query the baseline rejects.
                if let Some(name) = nested_aggregation_name(&it.expr) {
                    return Err(CypherError::Aggregation(name));
                }
                rewritten.push(rewrite_aggs(&it.expr, &mut aggs));
            } else {
                rewritten.push(it.expr.clone());
                group_exprs.push(it.expr.clone());
            }
        }
        Ok(AggPlan {
            rewritten,
            aggs,
            group_exprs,
        })
    }
}

/// The aggregate that would reach the generic evaluator, if this expression carries one
/// anywhere other than at its root.
fn nested_aggregation_name(e: &Expr) -> Option<String> {
    match e {
        // At the root an aggregate is a projection, not an error.
        Expr::CountStar => None,
        Expr::FunctionCall { name, .. } if is_aggregation_name(name) => None,
        other => first_aggregation_name(other),
    }
}

fn first_aggregation_name(e: &Expr) -> Option<String> {
    match e {
        Expr::CountStar => Some("count".to_string()),
        Expr::FunctionCall { name, args, .. } => {
            if is_aggregation_name(name) {
                Some(name.to_ascii_lowercase())
            } else {
                args.iter().find_map(first_aggregation_name)
            }
        }
        Expr::Property { expr, .. } => first_aggregation_name(expr),
        Expr::Binary { left, right, .. } => {
            first_aggregation_name(left).or_else(|| first_aggregation_name(right))
        }
        Expr::Unary { expr, .. } => first_aggregation_name(expr),
        Expr::Comparison { left, right, .. } => {
            first_aggregation_name(left).or_else(|| first_aggregation_name(right))
        }
        Expr::StringOp { left, right, .. } => {
            first_aggregation_name(left).or_else(|| first_aggregation_name(right))
        }
        _ => None,
    }
}

fn rewrite_aggs(e: &Expr, aggs: &mut Vec<AggCall>) -> Expr {
    match e {
        Expr::CountStar => {
            aggs.push(AggCall {
                name: "count".into(),
                distinct: false,
                arg: None,
            });
            Expr::Variable(format!("{AGG_PLACEHOLDER_PREFIX}{}", aggs.len() - 1))
        }
        Expr::FunctionCall {
            name,
            distinct,
            args,
        } if is_aggregation_name(name) => {
            let mut arg = args.first().cloned();
            let mut d = *distinct;
            if let Some(Expr::Distinct(inner)) = &arg {
                arg = Some((**inner).clone());
                d = true;
            }
            aggs.push(AggCall {
                name: name.clone(),
                distinct: d,
                arg,
            });
            Expr::Variable(format!("{AGG_PLACEHOLDER_PREFIX}{}", aggs.len() - 1))
        }
        Expr::FunctionCall {
            name,
            distinct,
            args,
        } => Expr::FunctionCall {
            name: name.clone(),
            distinct: *distinct,
            args: args.iter().map(|a| rewrite_aggs(a, aggs)).collect(),
        },
        Expr::Property { expr, key } => Expr::Property {
            expr: Box::new(rewrite_aggs(expr, aggs)),
            key: key.clone(),
        },
        Expr::Binary { op, left, right } => Expr::Binary {
            op: *op,
            left: Box::new(rewrite_aggs(left, aggs)),
            right: Box::new(rewrite_aggs(right, aggs)),
        },
        Expr::Comparison { op, left, right } => Expr::Comparison {
            op: *op,
            left: Box::new(rewrite_aggs(left, aggs)),
            right: Box::new(rewrite_aggs(right, aggs)),
        },
        Expr::Distinct(inner) => Expr::Distinct(Box::new(rewrite_aggs(inner, aggs))),
        Expr::PredicateFunction {
            name,
            variable,
            list,
            predicate,
        } => Expr::PredicateFunction {
            name: name.clone(),
            variable: variable.clone(),
            list: Box::new(rewrite_aggs(list, aggs)),
            predicate: predicate.as_ref().map(|p| Box::new(rewrite_aggs(p, aggs))),
        },
        other => other.clone(),
    }
}

struct GroupAcc {
    first_row: Row,
    group_values: Vec<Value>,
    agg_inputs: Vec<Vec<Value>>,
}

fn finalize_groups(
    ev: &Evaluator,
    plan: &AggPlan,
    items: &[ReturnItem],
    groups: IndexMap<Vec<Key>, GroupAcc>,
    order: Option<&[OrderItem]>,
) -> CypherResult<Vec<Row>> {
    let columns: Vec<String> = items
        .iter()
        .map(|it| {
            it.alias
                .clone()
                .unwrap_or_else(|| to_cypher_string(&it.expr))
        })
        .collect();
    let mut out = Vec::with_capacity(groups.len());
    let compute = |acc: GroupAcc| -> CypherResult<Row> {
        let mut tmp = acc.first_row.clone();
        for (ai, agg) in plan.aggs.iter().enumerate() {
            let mut inputs = acc.agg_inputs[ai].clone();
            if agg.distinct {
                let mut seen = std::collections::HashSet::new();
                inputs.retain(|v| seen.insert(value_key(v)));
            }
            let v = aggregate(&agg.name, &inputs)?;
            tmp.insert(format!("{AGG_PLACEHOLDER_PREFIX}{ai}"), v);
        }
        let mut row = Row::new();
        let mut gi = 0;
        for (idx, it) in items.iter().enumerate() {
            let v = if contains_aggregation(&it.expr) {
                ev.eval(&plan.rewritten[idx], &tmp)?
            } else {
                let v = acc.group_values[gi].clone();
                gi += 1;
                v
            };
            row.insert(columns[idx].clone(), v);
        }
        if let Some(p) = acc.first_row.get(INTERNAL_PROVENANCE_KEY) {
            row.insert(INTERNAL_PROVENANCE_KEY.to_string(), p.clone());
        }
        stash_order_values(ev, &acc.first_row, &mut row, order, &columns)?;
        Ok(row)
    };
    if groups.is_empty() && plan.group_exprs.is_empty() {
        let acc = GroupAcc {
            first_row: Row::new(),
            group_values: vec![],
            agg_inputs: vec![Vec::new(); plan.aggs.len()],
        };
        out.push(compute(acc)?);
        return Ok(out);
    }
    for (_, acc) in groups {
        out.push(compute(acc)?);
    }
    Ok(out)
}

/// Batch projection (`projectAndAggregate`).
fn project(
    ev: &Evaluator,
    rows: Vec<Row>,
    items: Option<&[ReturnItem]>,
    distinct: bool,
    order: Option<&[OrderItem]>,
) -> CypherResult<(Vec<String>, Vec<Row>)> {
    // RETURN * expansion.
    let expanded: Vec<ReturnItem>;
    let items: Option<&[ReturnItem]> = match items {
        None => {
            expanded = rows
                .first()
                .map(|r| {
                    r.keys()
                        .filter(|k| !is_internal_key(k))
                        .map(|k| ReturnItem {
                            expr: Expr::Variable(k.clone()),
                            alias: None,
                        })
                        .collect()
                })
                .unwrap_or_default();
            if expanded.is_empty() {
                return Ok((vec![], vec![]));
            }
            Some(&expanded)
        }
        Some(i) => Some(i),
    };
    let items = items.unwrap();
    let columns: Vec<String> = items
        .iter()
        .map(|it| {
            it.alias
                .clone()
                .unwrap_or_else(|| to_cypher_string(&it.expr))
        })
        .collect();
    let aggregated = items.iter().any(|it| contains_aggregation(&it.expr));
    let mut out: Vec<Row>;
    if aggregated {
        let plan = AggPlan::new(items)?;
        let mut groups: IndexMap<Vec<Key>, GroupAcc> = IndexMap::new();
        for row in rows {
            let mut keyv = Vec::with_capacity(plan.group_exprs.len());
            let mut vals = Vec::with_capacity(plan.group_exprs.len());
            for e in &plan.group_exprs {
                let v = ev.eval(e, &row)?;
                keyv.push(value_key(&v));
                vals.push(v);
            }
            let acc = groups.entry(keyv).or_insert_with(|| GroupAcc {
                first_row: row.clone(),
                group_values: vals,
                agg_inputs: vec![Vec::new(); plan.aggs.len()],
            });
            for (ai, agg) in plan.aggs.iter().enumerate() {
                let v = match &agg.arg {
                    Some(e) => ev.eval(e, &row)?,
                    None => Value::map(
                        row.iter()
                            .filter(|(k, _)| !is_internal_key(k))
                            .map(|(k, v)| (k.clone(), v.clone()))
                            .collect(),
                    ),
                };
                acc.agg_inputs[ai].push(v);
            }
            merge_provenance(&mut acc.first_row, &row);
        }
        out = finalize_groups(ev, &plan, items, groups, order)?;
    } else {
        out = Vec::with_capacity(rows.len());
        let mut cols = columns.clone();
        for row in &rows {
            out.push(project_row(
                ev,
                row,
                Some(items),
                order,
                distinct,
                &mut cols,
            )?);
        }
    }
    if distinct {
        let mut seen: IndexMap<Vec<Key>, usize> = IndexMap::new();
        let mut dedup: Vec<Row> = Vec::new();
        for r in out {
            let k = visible_key(&r);
            match seen.get(&k) {
                Some(&idx) => merge_provenance(&mut dedup[idx], &r),
                None => {
                    seen.insert(k, dedup.len());
                    dedup.push(r);
                }
            }
        }
        out = dedup;
    }
    Ok((columns, out))
}

fn order_rows(ev: &Evaluator, rows: Vec<Row>, items: &[OrderItem]) -> CypherResult<Vec<Row>> {
    // Precompute sort keys.
    let mut keyed: Vec<(Vec<Value>, Row)> = Vec::with_capacity(rows.len());
    for r in rows {
        let mut keys = Vec::with_capacity(items.len());
        for (i, it) in items.iter().enumerate() {
            let stash = format!("{ORDER_STASH_PREFIX}{i}");
            let v = match r.get(&stash) {
                Some(v) => v.clone(),
                None => ev.eval(&it.expr, &r)?,
            };
            keys.push(v);
        }
        keyed.push((keys, r));
    }
    keyed.sort_by(|a, b| {
        for (i, it) in items.iter().enumerate() {
            let c = compare_order_values(&a.0[i], &b.0[i]);
            let c = if it.descending { c.reverse() } else { c };
            if c != Ordering::Equal {
                return c;
            }
        }
        Ordering::Equal
    });
    Ok(keyed.into_iter().map(|(_, r)| r).collect())
}

/// The projected column a provenance second pass can filter on: the first item that is
/// a plain `<variable>.<property>`, with its output column name.
///
/// Anything else — a function, a literal, an expression — cannot be turned back into a
/// predicate over the graph, and those queries complete provenance by scanning instead.
fn provenance_property(items: Option<&[ReturnItem]>) -> Option<(String, String, String)> {
    for it in items? {
        if let Expr::Property { expr, key } = &it.expr {
            if let Expr::Variable(v) = expr.as_ref() {
                let column = it
                    .alias
                    .clone()
                    .unwrap_or_else(|| to_cypher_string(&it.expr));
                return Some((column, key.clone(), v.clone()));
            }
        }
    }
    None
}

/// `<variable>.<property> = v1 OR ... = vn` over the distinct values already selected.
///
/// `None` when a selected row has no string there: the filter would then exclude rows
/// whose provenance still needs completing, and a wrong answer is not worth the speed.
fn selected_value_filter(
    out: &[Row],
    column: &str,
    property: &str,
    variable: &str,
) -> Option<Expr> {
    let mut values: Vec<String> = Vec::with_capacity(out.len());
    let mut seen: std::collections::HashSet<&str> = std::collections::HashSet::new();
    for row in out {
        match row.get(column) {
            Some(Value::Str(s)) => {
                if seen.insert(s.as_ref()) {
                    values.push(s.to_string());
                }
            }
            _ => return None,
        }
    }
    let property_expr = Expr::Property {
        expr: Box::new(Expr::Variable(variable.to_string())),
        key: property.to_string(),
    };
    let mut iter = values.into_iter().map(|v| Expr::Comparison {
        op: crate::ast::CmpOp::Eq,
        left: Box::new(property_expr.clone()),
        right: Box::new(Expr::Literal(Literal::Str(v))),
    });
    let first = iter.next()?;
    Some(iter.fold(first, |acc, e| Expr::Or(Box::new(acc), Box::new(e))))
}

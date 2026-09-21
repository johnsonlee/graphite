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
    /// Rows as plain values, when the query took the compact path. `rows` is then empty.
    ///
    /// Only an executor built with `with_compact` produces this, and only the HTTP
    /// Cypher route builds one: every other consumer keeps reading `rows`.
    pub compact: Option<CompactRows>,
    /// Whether at least one more row exists beyond the ones returned. Only an executor
    /// built with `with_probe` can set it: it runs every trailing literal `LIMIT n` as
    /// `n + 1`, keeps `n` rows, and records whether the extra one arrived. Without the
    /// probe, or when a segment's LIMIT is not a literal, this stays `false`.
    pub more: bool,
}

/// Projected rows without the per-row map: one `Vec<Value>` per row in `columns`
/// order, and the id of the graph each row came from, for provenance.
#[derive(Debug, Clone, Default)]
pub struct CompactRows {
    pub values: Vec<Vec<Value>>,
    pub graph_ids: Vec<Arc<str>>,
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

pub(crate) fn merge_provenance(into: &mut Row, from: &Row) {
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
        let mut more = false;
        let mut seen: std::collections::HashSet<Vec<Key>> = std::collections::HashSet::new();
        for (i, (mut seg, all)) in segments.into_iter().enumerate() {
            if let Some(max) = max_rows {
                inject_limit(&mut seg, max);
            }
            let keep = if self.probe {
                probe_limit(&mut seg)
            } else {
                None
            };
            let mut r = self.run_segment(&seg)?;
            if let Some(n) = keep {
                more |= trim_probe(&mut r, n);
            }
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
        res.more = more;
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
            if let Some(r) = super::schema::schema_histogram(self, clauses)? {
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
                        // `MATCH [WHERE] WITH ...`: the same streaming scan, projection
                        // and group-by as the RETURN shape, with the clauses after the
                        // WITH left to the loop. Without this the match ran through the
                        // generic matcher -- every node decoded, the WHERE interpreted
                        // per node, every survivor kept as a row until the WITH -- and a
                        // grouped count over a fleet timed out where the same query
                        // written with RETURN answered in milliseconds.
                        if let Some((shape, with_where, consumed)) =
                            FusedShape::detect_with(&clauses[i..])
                        {
                            let budget = compute_early_limit(&clauses[i..]);
                            let (cols, projected) =
                                self.run_fused_with(&matcher, &ev, rows, patterns, &shape, budget)?;
                            columns = cols;
                            rows = projected;
                            if let Some(w) = with_where {
                                let mut out = Vec::with_capacity(rows.len());
                                for r in rows {
                                    self.tick()?;
                                    if ev.eval(w, &r)?.as_bool() == Some(true) {
                                        out.push(r);
                                    }
                                }
                                rows = out;
                            }
                            i += consumed;
                            continue;
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
        Ok(QueryResult {
            columns,
            rows,
            compact: None,
            more: false,
        })
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
            return finish_fused(ev, cols, out, shape);
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
        let hop = if scan.is_none() {
            super::hop::HopPlan::build(patterns, shape.where_clause.as_ref())
        } else {
            None
        };

        // The compact path: no ordering, no DISTINCT, a single empty seed (so nothing
        // precedes the MATCH), a pushed-down scan, and a RETURN of the scanned node's
        // own properties. Everything a row would carry is then already in hand.
        if !aggregated && self.compact && shape.order.is_none() && !shape.distinct {
            if let (Some(plan), Some(items), [seed]) = (&scan, items, rows.as_slice()) {
                if seed.is_empty() {
                    if let Some(keys) = simple_property_keys(items, plan.variable()) {
                        let columns = item_names(items);
                        let mut values: Vec<Vec<Value>> = Vec::new();
                        let mut graph_ids: Vec<Arc<str>> = Vec::new();
                        // A zero budget means no rows: the sink below only checks the
                        // budget after keeping a row, so it must not run at all.
                        if budget == Some(0) {
                            return Ok(QueryResult {
                                columns,
                                rows: Vec::new(),
                                compact: Some(CompactRows { values, graph_ids }),
                                more: false,
                            });
                        }
                        plan.run_nodes(
                            self,
                            ev,
                            shape.where_clause.as_ref(),
                            &keys,
                            &mut |v, g| {
                                values.push(v);
                                graph_ids.push(g);
                                Ok(budget.is_none_or(|b| values.len() < b))
                            },
                        )?;
                        let skipped = skip.unwrap_or(0).max(0) as usize;
                        if skipped > 0 {
                            let n = skipped.min(values.len());
                            values.drain(..n);
                            graph_ids.drain(..n);
                        }
                        return Ok(QueryResult {
                            columns,
                            rows: Vec::new(),
                            compact: Some(CompactRows { values, graph_ids }),
                            more: false,
                        });
                    }
                }
            }
        }

        let (columns, out) = self.fused_rows(
            matcher, ev, rows, patterns, shape, &scan, &hop, budget, aggregated,
        )?;
        finish_fused(ev, columns, out, shape)
    }

    /// Fused MATCH [WHERE] WITH: the rows the WITH projects, exactly as `project` would
    /// build them from the generic match -- order stashes, provenance and all -- so the
    /// loop can run the WITH's own WHERE and whatever follows over them. `budget`
    /// bounds the match the way `exec_match`'s early limit does, and is `None` unless
    /// the segment's LIMIT provably bounds it (see `compute_early_limit`).
    fn run_fused_with(
        &self,
        matcher: &Matcher,
        ev: &Evaluator,
        rows: Vec<Row>,
        patterns: &[Pattern],
        shape: &FusedShape,
        budget: Option<usize>,
    ) -> CypherResult<(Vec<String>, Vec<Row>)> {
        if has_unknown_label(patterns) {
            return project(
                ev,
                vec![],
                shape.items.as_deref(),
                shape.distinct,
                shape.order.as_deref(),
            );
        }
        let aggregated = match &shape.items {
            Some(items) => items.iter().any(|it| contains_aggregation(&it.expr)),
            None => false,
        };
        let scan = super::scan::ScanPlan::build(patterns, shape.where_clause.as_ref());
        let hop = if scan.is_none() {
            super::hop::HopPlan::build(patterns, shape.where_clause.as_ref())
        } else {
            None
        };
        self.fused_rows(
            matcher, ev, rows, patterns, shape, &scan, &hop, budget, aggregated,
        )
    }

    /// Stream the match into the projection: a group-by when the items aggregate, a
    /// row per match otherwise, stopping at `budget` rows where the shape allows it.
    /// Returns the columns and the rows before ORDER BY / SKIP / LIMIT are applied.
    #[allow(clippy::too_many_arguments)]
    fn fused_rows(
        &self,
        matcher: &Matcher,
        ev: &Evaluator,
        rows: Vec<Row>,
        patterns: &[Pattern],
        shape: &FusedShape,
        scan: &Option<super::scan::ScanPlan>,
        hop: &Option<super::hop::HopPlan>,
        budget: Option<usize>,
        aggregated: bool,
    ) -> CypherResult<(Vec<String>, Vec<Row>)> {
        let items = shape.items.as_deref();
        if aggregated {
            // Streaming group-by.
            let items = items.unwrap();
            let plan = AggPlan::new(items)?;
            let columns = item_names(items);
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
                        None => star_input(agg, &row),
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
                    scan,
                    hop,
                    &mut consume,
                )?;
            }
            let out = finalize_groups(ev, &plan, items, groups, shape.order.as_deref())?;
            return Ok((columns, out));
        }

        // Non-aggregated: stream projection.
        let mut out: Vec<Row> = Vec::new();
        let mut columns: Vec<String> = items.map(item_names).unwrap_or_default();
        // Index of each distinct key into `out`, not just the set of keys. Finding the
        // row to merge provenance into by scanning `out` is linear, and it runs once per
        // duplicate — quadratic on the shape that produces duplicates by the million.
        let mut seen: std::collections::HashMap<Vec<Key>, usize> = std::collections::HashMap::new();
        // A cross-graph DISTINCT cannot stop when it has enough rows. Each row carries the
        // set of graphs it was seen in, and a graph reached after the limit can still hold
        // a duplicate of a row already emitted — which belongs in that row's provenance.
        // The baseline keeps scanning for exactly this reason; stopping early produced
        // rows identical in every visible column but missing a contributing graph.
        let names = items.map(item_names).unwrap_or_default();
        let distinct_provenance = shape.distinct && self.cross;
        // Provenance completion runs as a targeted second pass where it can, so the
        // first pass may stop at the limit like any other.
        let targeted = distinct_provenance
            .then(|| provenance_property(items))
            .flatten();
        let needs_all = shape.order.is_some() || (distinct_provenance && targeted.is_none());
        // The baseline streams `MATCH .. WHERE .. RETURN DISTINCT .. ORDER BY .. LIMIT n`
        // through one pass that ranks each distinct row by sort values read from its
        // first match's bindings, so `ORDER BY n.x` sorts there even though the
        // projected row has no `n`. Its general pipeline, which every other DISTINCT
        // shape takes, sorts the projected rows and so leaves scan order untouched.
        let order_before_distinct = shape.distinct
            && shape.where_clause.is_some()
            && shape.order.is_some()
            && budget.is_some_and(|b| b > 0)
            && items.is_some_and(|items| {
                !items
                    .iter()
                    .any(|it| matches!(&it.expr, Expr::Variable(v) if v == "*"))
            });
        let mut consume = |row: Row| -> CypherResult<bool> {
            let projected = project_row(
                ev,
                &row,
                items,
                &names,
                shape.order.as_deref(),
                shape.distinct,
                order_before_distinct,
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
                        // so further distinct rows are not collected -- unless every
                        // row is needed anyway: with ORDER BY the winning values can
                        // occur after the budget and must still be collected.
                        if distinct_provenance
                            && !needs_all
                            && budget.is_some_and(|b| out.len() >= b)
                        {
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
        let mut cut_short = false;
        for r in rows {
            let cont = self.stream_match(
                matcher,
                ev,
                &r,
                patterns,
                shape.where_clause.as_ref(),
                scan,
                hop,
                &mut consume,
            )?;
            if !cont {
                cut_short = true;
                break;
            }
        }
        // `consume` borrowed `out` and `seen`; its last use is above.
        #[allow(clippy::drop_non_drop)]
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
        //
        // It is only needed when the first pass stopped early. A scan that ran to the end
        // has already merged every duplicate's graph into its row, so the second pass
        // would re-plan all sixty-four graphs to learn nothing -- which is what happened
        // on every DISTINCT query whose distinct values were fewer than its LIMIT, and
        // those are most of them.
        if let Some((column, property, variable)) = targeted.filter(|_| cut_short) {
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
                        &names,
                        shape.order.as_deref(),
                        shape.distinct,
                        order_before_distinct,
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
                        &None,
                        &mut merge,
                    )?;
                }
            }
        }
        Ok((columns, out))
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
        hop: &Option<super::hop::HopPlan>,
        consume: &mut dyn FnMut(Row) -> CypherResult<bool>,
    ) -> CypherResult<bool> {
        if let Some(plan) = scan {
            if row.is_empty() {
                return plan.run(self, ev, row, where_clause, consume);
            }
        }
        if let Some(plan) = hop {
            if row.is_empty() {
                return plan.run(self, ev, matcher, patterns, where_clause, consume);
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
    Ok(QueryResult {
        columns,
        rows,
        compact: None,
        more: false,
    })
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

    /// Shape: MATCH [WHERE] WITH <items> [WHERE], with anything at all after it.
    ///
    /// The WITH's own WHERE and the clauses that follow are the loop's, so the shape
    /// carries no SKIP or LIMIT, and only the ORDER BY that directly follows the WITH
    /// -- the one `project` stashes sort values for. Returns the shape, the WITH's
    /// WHERE, and the number of clauses the shape spans.
    fn detect_with(clauses: &[Clause]) -> Option<(FusedShape, Option<&Expr>, usize)> {
        let mut i = 1;
        let mut where_clause = None;
        if let Some(Clause::Where(e)) = clauses.get(i) {
            where_clause = Some(e.clone());
            i += 1;
        }
        let (distinct, items, with_where) = match clauses.get(i) {
            Some(Clause::With {
                distinct,
                items,
                where_clause,
            }) => (*distinct, items.clone(), where_clause.as_ref()),
            _ => return None,
        };
        i += 1;
        let order = next_order_by(&clauses[i..]).map(|o| o.to_vec());
        Some((
            FusedShape {
                where_clause,
                items,
                distinct,
                order,
                skip: None,
                limit: None,
            },
            with_where,
            i,
        ))
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

/// The probe behind `QueryResult::more`: raise a segment's trailing literal `LIMIT n` to
/// `n + 1` and return `n`. Every consumer of the limit (the early match bound, the
/// fast paths, the fused shape, the row pipeline) reads that literal, so one rewrite
/// reaches them all. A segment whose last clause is not a literal LIMIT is left alone
/// and returns `None`: with no limit nothing is cut, and a parameterised limit is not
/// probed.
fn probe_limit(seg: &mut [Clause]) -> Option<usize> {
    match seg.last_mut() {
        Some(Clause::Limit(Expr::Literal(Literal::Int(n)))) if *n >= 0 => {
            let keep = *n as usize;
            *n += 1;
            Some(keep)
        }
        _ => None,
    }
}

/// Keep `n` rows of a probed segment; true when the extra row had arrived.
fn trim_probe(r: &mut QueryResult, n: usize) -> bool {
    match &mut r.compact {
        Some(c) => {
            let extra = c.values.len() > n;
            c.values.truncate(n);
            c.graph_ids.truncate(n);
            extra
        }
        None => {
            let extra = r.rows.len() > n;
            r.rows.truncate(n);
            extra
        }
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

/// The property keys of a RETURN list that reads nothing but `<variable>.<key>` items.
fn simple_property_keys(items: &[ReturnItem], variable: &str) -> Option<Vec<String>> {
    items
        .iter()
        .map(|it| match &it.expr {
            Expr::Property { expr, key } => match expr.as_ref() {
                Expr::Variable(v) if v == variable => Some(key.clone()),
                _ => None,
            },
            _ => None,
        })
        .collect()
}

/// Column names for a RETURN list, rendered once instead of once per row.
///
/// An unaliased item is named by its own source text, which means rendering the
/// expression back to Cypher. That is a string build and an allocation per column, and
/// it does not depend on the row -- doing it inside the row loop cost a `LIMIT 200`
/// query a thousand redundant renderings.
fn item_names(items: &[ReturnItem]) -> Vec<String> {
    items
        .iter()
        .map(|it| {
            it.alias
                .clone()
                .unwrap_or_else(|| to_cypher_string(&it.expr))
        })
        .collect()
}

#[allow(clippy::too_many_arguments)]
fn project_row(
    ev: &Evaluator,
    row: &Row,
    items: Option<&[ReturnItem]>,
    names: &[String],
    order: Option<&[OrderItem]>,
    distinct: bool,
    order_before_distinct: bool,
    columns: &mut Vec<String>,
) -> CypherResult<Row> {
    // Sized up front: a row holds every column plus its provenance, and an `IndexMap`
    // that starts empty reallocates its table and its entries twice on the way there.
    let mut out = Row::with_capacity(names.len().max(row.len()) + 1);
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
            for (it, name) in items.iter().zip(names) {
                let v = ev.eval(&it.expr, row)?;
                out.insert(name.clone(), v);
            }
        }
    }
    if let Some(p) = row.get(INTERNAL_PROVENANCE_KEY) {
        out.insert(INTERNAL_PROVENANCE_KEY.to_string(), p.clone());
    }
    if !distinct || order_before_distinct {
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

/// The per-row input of an aggregate called on `*`.
///
/// `count(*)` only ever counts non-null inputs, so what the input *is* does not matter
/// -- and it was a copy of the whole row, kept alive per row until the group was
/// finalised. Over a million matching records that was gigabytes of maps built to be
/// counted and thrown away, and the reason `RETURN count(*)` over a broad predicate was
/// the one query shape that could take the server down. A constant counts the same.
/// Anything else called on `*` still sees the row, since it may look inside it.
fn star_input(agg: &AggCall, row: &Row) -> Value {
    if agg.name.eq_ignore_ascii_case("count") && !agg.distinct {
        return Value::Bool(true);
    }
    Value::map(
        row.iter()
            .filter(|(k, _)| !is_internal_key(k))
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect(),
    )
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
                    None => star_input(agg, &row),
                };
                acc.agg_inputs[ai].push(v);
            }
            merge_provenance(&mut acc.first_row, &row);
        }
        out = finalize_groups(ev, &plan, items, groups, order)?;
    } else {
        out = Vec::with_capacity(rows.len());
        let names = item_names(items);
        let mut cols = columns.clone();
        for row in &rows {
            out.push(project_row(
                ev,
                row,
                Some(items),
                &names,
                order,
                distinct,
                false,
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

#[cfg(test)]
mod probe_tests {
    use super::super::Source;
    use super::*;
    use crate::parser::parse;

    fn clauses(q: &str) -> Vec<Clause> {
        parse(q).unwrap()
    }

    fn trailing_limit(seg: &[Clause]) -> Option<i64> {
        match seg.last() {
            Some(Clause::Limit(Expr::Literal(Literal::Int(n)))) => Some(*n),
            _ => None,
        }
    }

    #[test]
    fn the_probe_raises_a_trailing_literal_limit_by_one_and_remembers_it() {
        let mut seg = clauses("MATCH (n) RETURN n LIMIT 5");
        assert_eq!(probe_limit(&mut seg), Some(5));
        assert_eq!(trailing_limit(&seg), Some(6));
        let mut zero = clauses("MATCH (n) RETURN n LIMIT 0");
        assert_eq!(probe_limit(&mut zero), Some(0));
        assert_eq!(trailing_limit(&zero), Some(1));
        let mut skipped = clauses("MATCH (n) RETURN n ORDER BY n.line SKIP 2 LIMIT 3");
        assert_eq!(probe_limit(&mut skipped), Some(3));
        assert_eq!(trailing_limit(&skipped), Some(4));
    }

    #[test]
    fn the_probe_leaves_other_segments_alone() {
        let mut none = clauses("MATCH (n) RETURN n");
        assert_eq!(probe_limit(&mut none), None);
        let mut expr = clauses("MATCH (n) RETURN n LIMIT 2 + 3");
        assert_eq!(probe_limit(&mut expr), None);
        assert!(matches!(expr.last(), Some(Clause::Limit(_))));
        let mut inner = clauses("MATCH (n) WITH n LIMIT 5 RETURN n");
        assert_eq!(probe_limit(&mut inner), None);
    }

    #[test]
    fn the_injected_cap_and_the_probe_compose() {
        // The HTTP route injects the API cap first, then probes: the cap becomes the
        // trailing literal, so the probe sees min(user limit, cap).
        let mut seg = clauses("MATCH (n) RETURN n LIMIT 5000");
        inject_limit(&mut seg, 1000);
        assert_eq!(probe_limit(&mut seg), Some(1000));
        assert_eq!(trailing_limit(&seg), Some(1001));
        let mut seg = clauses("MATCH (n) RETURN n");
        inject_limit(&mut seg, 1000);
        assert_eq!(probe_limit(&mut seg), Some(1000));
    }

    #[test]
    fn trimming_keeps_n_rows_and_reports_the_extra_one() {
        let row = |i: i64| {
            let mut r = Row::new();
            r.insert("x".into(), Value::Int(i));
            r
        };
        let mut r = QueryResult {
            columns: vec!["x".into()],
            rows: (0..4).map(row).collect(),
            compact: None,
            more: false,
        };
        assert!(trim_probe(&mut r, 3));
        assert_eq!(r.rows.len(), 3);
        assert!(!trim_probe(&mut r, 3));
        let mut c = QueryResult {
            columns: vec!["x".into()],
            rows: Vec::new(),
            compact: Some(CompactRows {
                values: (0..2).map(|i| vec![Value::Int(i)]).collect(),
                graph_ids: vec![Arc::from("g"), Arc::from("g")],
            }),
            more: false,
        };
        assert!(trim_probe(&mut c, 1));
        let compact = c.compact.as_ref().unwrap();
        assert_eq!(compact.values.len(), 1);
        assert_eq!(compact.graph_ids.len(), 1);
        assert!(!trim_probe(&mut c, 1));
        assert!(!trim_probe(&mut c, 0) || c.compact.as_ref().unwrap().values.is_empty());
    }

    /// Against a real graph (`GRAPHITE_INDEX_FIXTURE`): the probed rows are the rows a
    /// plain execution returns, and `more` says whether a LIMIT cut anything.
    #[test]
    fn probed_execution_returns_the_same_rows_and_knows_when_more_exist() {
        let Some(dir) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
            eprintln!("GRAPHITE_INDEX_FIXTURE unset; skipping");
            return;
        };
        let graph =
            Arc::new(graphite_storage::graph::Graph::load(std::path::Path::new(&dir)).unwrap());
        let sources = || {
            vec![Source {
                id: Arc::from("g"),
                graph: graph.clone(),
            }]
        };
        let plain = Executor::new(sources(), false);
        let probed = Executor::new(sources(), false).with_probe();
        let compact = Executor::new(sources(), false).with_compact().with_probe();
        let cases: &[(&str, Option<usize>, bool)] = &[
            ("MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 5", None, true),
            ("MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 0", None, true),
            ("MATCH (n:CallSiteNode) RETURN n.callee_name", Some(10), true),
            ("MATCH (n:CallSiteNode) RETURN count(n) AS c", Some(10), false),
            ("MATCH (n:CallSiteNode) RETURN DISTINCT n.callee_class AS c LIMIT 3", None, true),
            ("MATCH (n:CallSiteNode) RETURN n.callee_name AS x ORDER BY x SKIP 2 LIMIT 3", None, true),
            ("MATCH (n:CallSiteNode) WHERE n.callee_name = 'zzz_no_such' RETURN n LIMIT 5", None, false),
            ("MATCH (n:CallSiteNode) RETURN n.callee_class AS c LIMIT 2 UNION MATCH (n:CallSiteNode) RETURN n.callee_class AS c LIMIT 2", None, true),
            ("MATCH (n:CallSiteNode) WHERE n.callee_name = 'zzz_no_such' RETURN n", Some(10), false),
        ];
        for (q, cap, expect_more) in cases {
            let a = plain
                .execute(q, *cap)
                .unwrap_or_else(|e| panic!("{q}: {e}"));
            let b = probed
                .execute(q, *cap)
                .unwrap_or_else(|e| panic!("{q}: {e}"));
            assert!(!a.more, "{q}: a plain execution never reports more");
            assert_eq!(
                a.rows.len(),
                b.rows.len(),
                "{q}: the probe changes the row count"
            );
            assert_eq!(b.more, *expect_more, "{q}: more");
            let c = compact
                .execute(q, *cap)
                .unwrap_or_else(|e| panic!("{q}: {e}"));
            let returned = c
                .compact
                .as_ref()
                .map(|c| c.values.len())
                .unwrap_or(c.rows.len());
            assert_eq!(
                returned,
                a.rows.len(),
                "{q}: the compact probe changes the row count"
            );
            assert_eq!(c.more, *expect_more, "{q}: compact more");
        }
        // ORDER BY: the probe must not change which rows come first.
        let q = "MATCH (n:CallSiteNode) RETURN n.callee_name AS x ORDER BY x LIMIT 3";
        let a = plain.execute(q, None).unwrap();
        let b = probed.execute(q, None).unwrap();
        let names = |r: &QueryResult| -> Vec<String> {
            r.rows
                .iter()
                .map(|row| format!("{:?}", row.get("x")))
                .collect()
        };
        assert_eq!(names(&a), names(&b));
        assert!(b.more);
    }
}

#[cfg(test)]
mod with_prefix_tests {
    use super::super::{Executor, Source};
    use super::*;
    use crate::parser::parse;

    fn detect(q: &str) -> Option<(FusedShape, Option<Expr>, usize)> {
        let clauses = parse(q).unwrap();
        FusedShape::detect_with(&clauses).map(|(s, w, n)| (s, w.cloned(), n))
    }

    #[test]
    fn the_with_prefix_spans_match_where_and_with_and_keeps_the_with_order_by() {
        let (shape, with_where, n) = detect(
            "MATCH (n) WHERE n.x = 1 WITH n.a AS a, count(*) AS c RETURN a, c ORDER BY c LIMIT 5",
        )
        .expect("MATCH WHERE WITH is the prefix shape");
        assert_eq!(n, 3);
        assert!(shape.where_clause.is_some());
        assert!(with_where.is_none());
        // The ORDER BY belongs to the RETURN, not the WITH.
        assert!(shape.order.is_none());
        assert!(shape.skip.is_none() && shape.limit.is_none());
        assert!(!shape.distinct);
        assert_eq!(shape.items.as_ref().map(|i| i.len()), Some(2));

        let (shape, with_where, n) =
            detect("MATCH (n) WITH DISTINCT n.a AS a ORDER BY a LIMIT 5 RETURN a").unwrap();
        assert_eq!(n, 2);
        assert!(shape.where_clause.is_none());
        assert!(with_where.is_none());
        assert!(shape.distinct);
        assert!(
            shape.order.is_some(),
            "the ORDER BY after the WITH is the WITH's"
        );

        let (shape, with_where, n) = detect("MATCH (n) WITH n WHERE n.x = 1 RETURN n").unwrap();
        assert_eq!(n, 2);
        assert!(shape.where_clause.is_none());
        assert!(with_where.is_some());
        assert!(shape.items.is_some());

        let (shape, _, _) = detect("MATCH (n) WITH * RETURN *").unwrap();
        assert!(shape.items.is_none(), "WITH * projects the row as it is");
    }

    #[test]
    fn anything_but_a_with_after_the_match_is_not_the_prefix_shape() {
        for q in [
            "MATCH (n) RETURN n",
            "MATCH (n) WHERE n.x = 1 RETURN n LIMIT 5",
            "MATCH (n) UNWIND [1, 2] AS i WITH n, i RETURN n, i",
            "MATCH (n) MATCH (m) WITH n, m RETURN n, m",
        ] {
            assert!(detect(q).is_none(), "{q}");
        }
    }

    /// Against a real graph (`GRAPHITE_INDEX_FIXTURE`, the core jar in CI), as two
    /// sources in cross-graph mode: every WITH-prefixed shape produces the rows,
    /// columns and provenance the generic pipeline produces. The generic rows come
    /// from the same query with `UNWIND [1] AS one` between the match and the WITH,
    /// which keeps the row set and forces the row-by-row path.
    #[test]
    fn a_fused_with_prefix_answers_as_the_generic_pipeline_does() {
        let Some(dir) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
            eprintln!("GRAPHITE_INDEX_FIXTURE unset; skipping");
            return;
        };
        let graph =
            Arc::new(graphite_storage::graph::Graph::load(std::path::Path::new(&dir)).unwrap());
        let sources: Vec<Source> = ["a", "b"]
            .iter()
            .map(|id| Source {
                id: Arc::from(*id),
                graph: graph.clone(),
            })
            .collect();
        let ex = Executor::new(sources, true);
        let render = |r: &QueryResult| -> Vec<String> {
            let mut out = vec![format!("columns={:?}", r.columns)];
            for row in &r.rows {
                let cells: Vec<String> = r
                    .columns
                    .iter()
                    .map(|c| format!("{:?}", row.get(c)))
                    .collect();
                out.push(format!(
                    "{} | {:?}",
                    cells.join(" | "),
                    QueryResult::graph_ids(row)
                ));
            }
            out
        };
        // (match and where, the rest); the generic twin gets an UNWIND in between.
        let cases: &[(&str, &str, bool)] = &[
            // The reported shape: a grouped count per graph over a call-site prefix.
            (
                r#"MATCH (n {type: "CallSiteNode"}) WHERE n.callee_class STARTS WITH "java.util." AND NOT n.caller_class STARTS WITH "java.util.""#,
                r#"WITH n.graphId AS graphId, split(replace(n.callee_class, "java.util.", ""), ".")[0] AS provider, count(*) AS calls RETURN graphId, provider, calls ORDER BY graphId ASC, calls DESC LIMIT 160"#,
                true,
            ),
            // A label, no WHERE, a grouped count.
            (
                "MATCH (n:CallSiteNode)",
                "WITH n.callee_name AS m, count(*) AS k RETURN m, k ORDER BY k DESC, m LIMIT 5",
                true,
            ),
            // A row-preserving WITH under a LIMIT: the match stops early in scan order.
            (
                r#"MATCH (n:CallSiteNode) WHERE n.callee_class STARTS WITH "java.util.""#,
                "WITH n.callee_class AS c, n.callee_name AS m RETURN c, m LIMIT 50",
                true,
            ),
            // The WITH's own WHERE.
            (
                r#"MATCH (n:CallSiteNode) WHERE n.callee_class STARTS WITH "java.util.""#,
                r#"WITH n.callee_class AS c, n.callee_name AS m WHERE m STARTS WITH "get" RETURN c, m ORDER BY c, m LIMIT 50"#,
                true,
            ),
            // DISTINCT across graphs: both graphs end up in each row's provenance.
            (
                r#"MATCH (n:CallSiteNode) WHERE n.callee_class STARTS WITH "java.util.""#,
                "WITH DISTINCT n.callee_class AS c RETURN c ORDER BY c LIMIT 30",
                true,
            ),
            // An ORDER BY on the WITH over pre-projection expressions, then LIMIT.
            (
                r#"MATCH (n:CallSiteNode) WHERE n.callee_class STARTS WITH "java.util.""#,
                "WITH n ORDER BY n.callee_name DESC, n.callee_class, n.id LIMIT 10 RETURN n.callee_name AS m, n.graphId AS g",
                true,
            ),
            // WITH * with a WHERE, then a grouped RETURN.
            (
                r#"MATCH (n:CallSiteNode) WHERE n.callee_name = "get""#,
                r#"WITH * WHERE n.callee_class CONTAINS "Map" RETURN n.callee_class AS c, count(*) AS k ORDER BY k DESC, c LIMIT 15"#,
                true,
            ),
            // Aggregates without a group, one of them over the synthetic graphId.
            (
                r#"MATCH (n:CallSiteNode) WHERE n.callee_class STARTS WITH "java.util.""#,
                "WITH count(*) AS k, collect(DISTINCT n.graphId) AS gs RETURN k, size(gs) AS g",
                true,
            ),
            // An unknown label: no candidates, and a count of zero.
            ("MATCH (n:NoSuchLabel)", "WITH count(*) AS k RETURN k", true),
            ("MATCH (n:NoSuchLabel)", "WITH n.x AS x RETURN x", false),
        ];
        for (head, rest, expect_rows) in cases {
            let fused = format!("{head} {rest}");
            let generic = format!("{head} UNWIND [1] AS one {rest}");
            let a = ex
                .execute(&fused, Some(1000))
                .unwrap_or_else(|e| panic!("{fused}: {e}"));
            let b = ex
                .execute(&generic, Some(1000))
                .unwrap_or_else(|e| panic!("{generic}: {e}"));
            assert_eq!(!a.rows.is_empty(), *expect_rows, "{fused}: rows");
            assert_eq!(render(&a), render(&b), "{fused}");
        }
        // The reported shape's rows really do name their graph, and both graphs.
        let r = ex
            .execute(
                r#"MATCH (n {type: "CallSiteNode"}) WHERE n.callee_class STARTS WITH "java.util." WITH n.graphId AS g, count(*) AS c RETURN g, c ORDER BY g"#,
                None,
            )
            .unwrap();
        let ids: Vec<String> = r
            .rows
            .iter()
            .map(|row| format!("{:?}", row.get("g")))
            .collect();
        assert_eq!(ids, [r#"Some(Str("a"))"#, r#"Some(Str("b"))"#]);
        assert_eq!(QueryResult::graph_ids(&r.rows[0]), ["a"]);
        assert_eq!(QueryResult::graph_ids(&r.rows[1]), ["b"]);
    }
}

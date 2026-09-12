//! Aggregate fast paths that answer a query from indexes instead of row-by-row work.
//!
//! Each returns `None` unless the clause shape matches exactly, so the general
//! pipeline stays the single source of truth for everything else.

use super::matching::{resolve_node_class, NodeClass};
use super::pipeline::{add_provenance_id, QueryResult, Row, INTERNAL_PROVENANCE_KEY};
use super::Executor;
use crate::ast::{Clause, Expr, OrderItem, Pattern, ReturnItem};
use crate::render::to_cypher_string;
use crate::value::Value;
use crate::CypherResult;
use graphite_storage::node::{read_call_site_strings, StrId, TAG_CALL_SITE_NODE};
use indexmap::IndexMap;

/// `MATCH (n[:Label]) RETURN count(*)` / `count(n)` — answered from node counts.
pub fn count_star(ex: &Executor, clauses: &[Clause]) -> CypherResult<Option<QueryResult>> {
    // A trailing LIMIT is tolerated: `execute(query, max_rows)` appends one, and a
    // limit cannot change a single-row aggregate unless it is zero.
    let (patterns, items) = match match_shape_prefix(clauses)? {
        Some(v) => v,
        None => return Ok(None),
    };
    let (order, limit) = trailing_order_and_limit(&clauses[2..]);
    if order.is_some() {
        return Ok(None);
    }
    if limit == Some(0) {
        return Ok(None);
    }
    if items.len() != 1 {
        return Ok(None);
    }
    let item = &items[0];
    let variable = single_node_variable(&patterns[0]);
    let counts_rows = match &item.expr {
        Expr::CountStar => true,
        Expr::FunctionCall { name, args, .. } if name.eq_ignore_ascii_case("count") => {
            match (args.first(), &variable) {
                // count(n) counts non-null bindings, which for a bare scan is every node.
                (Some(Expr::Variable(v)), Some(var)) => v == var,
                _ => false,
            }
        }
        _ => false,
    };
    if !counts_rows {
        return Ok(None);
    }
    let tags = match resolve_node_class(&patterns[0].nodes[0].labels) {
        NodeClass::Tags(t) => t,
        // Method nodes are counted from metadata; an unknown label counts nothing.
        NodeClass::Method => {
            let per_source: Vec<i64> = ex
                .sources
                .iter()
                .map(|s| s.graph.method_count() as i64)
                .collect();
            return Ok(Some(count_result(ex, item, &per_source)));
        }
        NodeClass::None => {
            let per_source = vec![0i64; ex.sources.len()];
            return Ok(Some(count_result(ex, item, &per_source)));
        }
    };
    let per_source: Vec<i64> = ex
        .sources
        .iter()
        .map(|s| tags.iter().map(|&t| s.graph.count_by_tag(t) as i64).sum())
        .collect();
    Ok(Some(count_result(ex, item, &per_source)))
}

fn count_result(ex: &Executor, item: &ReturnItem, per_source: &[i64]) -> QueryResult {
    let column = item
        .alias
        .clone()
        .unwrap_or_else(|| to_cypher_string(&item.expr));
    let mut row = Row::new();
    row.insert(column.clone(), Value::Int(per_source.iter().sum()));
    if ex.cross {
        // Provenance names the graphs that actually contributed a row, so a label that
        // matches nothing leaves it empty.
        for (s, count) in ex.sources.iter().zip(per_source) {
            if *count > 0 {
                add_provenance_id(&mut row, s.id.clone());
            }
        }
        if !row.contains_key(INTERNAL_PROVENANCE_KEY) {
            row.insert(INTERNAL_PROVENANCE_KEY.to_string(), Value::list(vec![]));
        }
    }
    QueryResult {
        columns: vec![column],
        rows: vec![row],
    }
}

/// `MATCH (n:CallSiteNode) RETURN n.<prop>, count(*) ORDER BY count(*) DESC LIMIT k`
/// — grouped by reading raw string ids, with no record decoding.
pub fn grouped_call_site_property(
    ex: &Executor,
    clauses: &[Clause],
) -> CypherResult<Option<QueryResult>> {
    // Shape: MATCH, RETURN, then optional ORDER BY and LIMIT.
    let (patterns, items) = match match_shape_prefix(clauses)? {
        Some(v) => v,
        None => return Ok(None),
    };
    if items.len() != 2 {
        return Ok(None);
    }
    let variable = match single_node_variable(&patterns[0]) {
        Some(v) => v,
        None => return Ok(None),
    };
    let property = match &items[0].expr {
        Expr::Property { expr, key } => match expr.as_ref() {
            Expr::Variable(v) if *v == variable => key.clone(),
            _ => return Ok(None),
        },
        _ => return Ok(None),
    };
    let field = match CALL_SITE_FIELDS.iter().position(|f| *f == property) {
        Some(i) => i,
        None => return Ok(None),
    };
    if !matches!(&items[1].expr, Expr::CountStar) {
        return Ok(None);
    }
    if !matches!(
        resolve_node_class(&patterns[0].nodes[0].labels),
        NodeClass::Tags(ref t) if t.as_slice() == [TAG_CALL_SITE_NODE]
    ) {
        return Ok(None);
    }
    // Only "order by the count alias, descending" is accelerated. A bare `count(*)`
    // in ORDER BY is an inline aggregation, which must raise the usual error, so any
    // shape this does not recognise falls through to the general pipeline.
    let (order, limit) = trailing_order_and_limit(&clauses[2..]);
    let count_alias = items[1].alias.clone();
    let descending_by_count = match (order, &count_alias) {
        (None, _) => false,
        (Some(o), Some(alias)) => {
            o.len() == 1 && o[0].descending && matches!(&o[0].expr, Expr::Variable(v) if v == alias)
        }
        (Some(_), None) => return Ok(None),
    };
    if order.is_some() && !descending_by_count {
        return Ok(None);
    }

    let columns = vec![
        items[0]
            .alias
            .clone()
            .unwrap_or_else(|| to_cypher_string(&items[0].expr)),
        items[1]
            .alias
            .clone()
            .unwrap_or_else(|| to_cypher_string(&items[1].expr)),
    ];
    // Counting by string id keeps the whole aggregation in integers.
    let mut rows: Vec<Row> = Vec::new();
    for s in &ex.sources {
        let graph = &s.graph;
        let data = graph.nodedata();
        let mut counts: IndexMap<StrId, i64> = IndexMap::new();
        for &id in graph.ids_by_tag(TAG_CALL_SITE_NODE) {
            ex.tick()?;
            let offset = match graph.node_offset(id) {
                Some(o) => o,
                None => continue,
            };
            let cs = read_call_site_strings(data, offset);
            let key = [
                cs.caller_class,
                cs.caller_name,
                cs.callee_class,
                cs.callee_name,
            ][field];
            *counts.entry(key).or_insert(0) += 1;
        }
        let mut entries: Vec<(StrId, i64)> = counts.into_iter().collect();
        if descending_by_count {
            entries.sort_by(|a, b| b.1.cmp(&a.1));
        }
        for (key, count) in entries {
            let mut row = Row::new();
            row.insert(columns[0].clone(), Value::str(graph.str(key)));
            row.insert(columns[1].clone(), Value::Int(count));
            if ex.cross {
                add_provenance_id(&mut row, s.id.clone());
            }
            rows.push(row);
        }
    }
    // With several sources the per-source orders must be merged.
    if descending_by_count && ex.sources.len() > 1 {
        let count_column = columns[1].clone();
        rows.sort_by(|a, b| {
            let get = |r: &Row| match r.get(&count_column) {
                Some(Value::Int(i)) => *i,
                _ => 0,
            };
            get(b).cmp(&get(a))
        });
    }
    if let Some(l) = limit {
        rows.truncate(l);
    }
    Ok(Some(QueryResult { columns, rows }))
}

const CALL_SITE_FIELDS: [&str; 4] = ["caller_class", "caller_name", "callee_class", "callee_name"];

/// `MATCH (n:Label) RETURN DISTINCT n.<prop> [ORDER BY <same> [ASC]] [LIMIT k]`
/// for properties stored as a single string id, read straight from the record.
///
/// The dictionary is sorted, so ordering by the property is ordering by string id —
/// the values never have to be compared as strings.
pub fn distinct_string_property(
    ex: &Executor,
    clauses: &[Clause],
) -> CypherResult<Option<QueryResult>> {
    let patterns = match clauses.first() {
        Some(Clause::Match {
            patterns,
            optional: false,
            where_clause: None,
        }) => patterns,
        _ => return Ok(None),
    };
    if patterns.len() != 1 {
        return Ok(None);
    }
    let items = match clauses.get(1) {
        Some(Clause::Return {
            distinct: true,
            items: Some(items),
        }) if items.len() == 1 => items,
        _ => return Ok(None),
    };
    let variable = match single_node_variable(&patterns[0]) {
        Some(v) => v,
        None => return Ok(None),
    };
    let property = match &items[0].expr {
        Expr::Property { expr, key } => match expr.as_ref() {
            Expr::Variable(v) if *v == variable => key.clone(),
            _ => return Ok(None),
        },
        _ => return Ok(None),
    };
    let tag = match resolve_node_class(&patterns[0].nodes[0].labels) {
        NodeClass::Tags(t) if t.len() == 1 => t[0],
        _ => return Ok(None),
    };
    let field = match raw_string_field(tag, &property) {
        Some(f) => f,
        None => return Ok(None),
    };
    let (order, limit) = trailing_order_and_limit(&clauses[2..]);
    let column = items[0]
        .alias
        .clone()
        .unwrap_or_else(|| to_cypher_string(&items[0].expr));
    // `ORDER BY n.value` after a DISTINCT projection does not sort: the projected row
    // has no `n` binding, so the sort key is null for every row and the stable sort
    // leaves scan order untouched. Only an alias names a real projected column, so
    // anything else is handed to the general pipeline, which reproduces that exactly.
    let sorted = match order {
        None => false,
        Some(o) => {
            let orders_by_alias = o.len() == 1
                && !o[0].descending
                && match (&o[0].expr, &items[0].alias) {
                    (Expr::Variable(v), Some(alias)) => v == alias,
                    _ => false,
                };
            if !orders_by_alias {
                return Ok(None);
            }
            true
        }
    };

    let mut rows: Vec<Row> = Vec::new();
    for s in &ex.sources {
        let graph = &s.graph;
        let data = graph.nodedata();
        let mut seen: std::collections::HashSet<StrId> = std::collections::HashSet::new();
        let mut ids: Vec<StrId> = Vec::new();
        for &id in graph.ids_by_tag(tag) {
            ex.tick()?;
            let offset = match graph.node_offset(id) {
                Some(o) => o,
                None => continue,
            };
            let key = read_string_field(data, offset, field);
            if seen.insert(key) {
                ids.push(key);
                // Without ORDER BY, the first k distinct values in scan order suffice.
                if !sorted {
                    if let Some(l) = limit {
                        if ids.len() >= l && ex.sources.len() == 1 {
                            break;
                        }
                    }
                }
            }
        }
        if sorted {
            ids.sort_unstable();
        }
        for key in ids {
            let mut row = Row::new();
            row.insert(column.clone(), Value::str(graph.str(key)));
            if ex.cross {
                add_provenance_id(&mut row, s.id.clone());
            }
            rows.push(row);
        }
    }
    // Several sources produce several ordered runs, which must be merged and re-deduped.
    if ex.sources.len() > 1 {
        let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();
        let mut merged: Vec<Row> = Vec::new();
        for r in rows {
            let key = match r.get(&column) {
                Some(Value::Str(s)) => s.to_string(),
                _ => String::new(),
            };
            if seen.insert(key) {
                merged.push(r);
            }
        }
        rows = merged;
        if sorted {
            rows.sort_by(|a, b| {
                let get = |r: &Row| match r.get(&column) {
                    Some(Value::Str(s)) => s.to_string(),
                    _ => String::new(),
                };
                graphite_storage::strings::java_cmp(&get(a), &get(b))
            });
        }
    }
    if let Some(l) = limit {
        rows.truncate(l);
    }
    Ok(Some(QueryResult {
        columns: vec![column],
        rows,
    }))
}

/// Offset of a single-string-id property within a node record, when one exists.
///
/// Only fixed-layout records qualify: the id must sit at a constant offset with no
/// variable-length field in front of it.
fn raw_string_field(tag: u8, property: &str) -> Option<StringField> {
    use graphite_storage::node::*;
    let at = |i: usize| Some(StringField::Fixed(NODE_HEADER_BYTES + i * 4));
    match (tag, property) {
        (TAG_STRING_CONSTANT, "value") => at(0),
        (TAG_LOCAL_VARIABLE, "name") => at(0),
        (TAG_LOCAL_VARIABLE, "type") => at(1),
        (TAG_FIELD_NODE, "class") => at(0),
        (TAG_FIELD_NODE, "name") => at(1),
        (TAG_FIELD_NODE, "type") => at(2),
        (TAG_ANNOTATION_NODE, "name") => at(0),
        (TAG_ANNOTATION_NODE, "class") => at(1),
        (TAG_ANNOTATION_NODE, "member") => at(2),
        (TAG_RESOURCE_FILE_NODE, "path") => at(0),
        (TAG_RESOURCE_FILE_NODE, "source") => at(1),
        (TAG_RESOURCE_FILE_NODE, "format") => at(2),
        (TAG_CALL_SITE_NODE, p) => CALL_SITE_FIELDS
            .iter()
            .position(|f| *f == p)
            .map(StringField::CallSite),
        _ => None,
    }
}

#[derive(Clone, Copy)]
enum StringField {
    /// A string id at a constant byte offset from the record start.
    Fixed(usize),
    /// One of the four CallSite properties, which need the caller's arity to locate.
    CallSite(usize),
}

#[inline]
fn read_string_field(data: &[u8], offset: usize, field: StringField) -> StrId {
    match field {
        StringField::Fixed(delta) => {
            i32::from_be_bytes(data[offset + delta..offset + delta + 4].try_into().unwrap())
                as StrId
        }
        StringField::CallSite(i) => {
            let cs = read_call_site_strings(data, offset);
            [
                cs.caller_class,
                cs.caller_name,
                cs.callee_class,
                cs.callee_name,
            ][i]
        }
    }
}

/// A single unlabelled-or-labelled node pattern with no relationships.
fn single_node_variable(p: &Pattern) -> Option<String> {
    if !p.rels.is_empty() || p.nodes.len() != 1 || p.path_variable.is_some() {
        return None;
    }
    if !p.nodes[0].properties.is_empty() {
        return None;
    }
    p.nodes[0].variable.clone()
}

/// `MATCH` then `RETURN` at the head of the clause list.
#[allow(clippy::type_complexity)]
fn match_shape_prefix(clauses: &[Clause]) -> CypherResult<Option<(Vec<Pattern>, Vec<ReturnItem>)>> {
    let patterns = match clauses.first() {
        Some(Clause::Match {
            patterns,
            optional: false,
            where_clause: None,
        }) => patterns.clone(),
        _ => return Ok(None),
    };
    if patterns.len() != 1 {
        return Ok(None);
    }
    let items = match clauses.get(1) {
        Some(Clause::Return {
            distinct: false,
            items: Some(items),
        }) => items.clone(),
        _ => return Ok(None),
    };
    Ok(Some((patterns, items)))
}

/// Trailing `ORDER BY` and literal `LIMIT`, if the clause list ends with them.
fn trailing_order_and_limit(rest: &[Clause]) -> (Option<&[OrderItem]>, Option<usize>) {
    let mut i = 0;
    let mut order = None;
    if let Some(Clause::OrderBy(items)) = rest.first() {
        order = Some(items.as_slice());
        i += 1;
    }
    let mut limit = None;
    if let Some(Clause::Limit(Expr::Literal(crate::ast::Literal::Int(n)))) = rest.get(i) {
        if *n >= 0 {
            limit = Some(*n as usize);
        }
        i += 1;
    }
    if i != rest.len() {
        return (None, None);
    }
    (order, limit)
}

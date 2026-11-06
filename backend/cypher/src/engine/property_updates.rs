//! Native mutation clauses for the opt-in property store. Expression evaluation,
//! result projection and transaction handling stay in the production pipeline.
use super::{pipeline::Row, Executor};
use crate::{
    ast::{Expr, Pattern, RemoveItem, SetItem},
    eval::Evaluator,
    CypherError, CypherResult, Value,
};

pub fn set(ex: &Executor, rows: &[Row], items: &[SetItem]) -> CypherResult<()> {
    let graph = ex.property_graph.as_deref().expect("property mode");
    let ev = Evaluator::new(ex, &ex.params);
    for row in rows {
        for item in items {
            ex.tick()?;
            match item {
                SetItem::Property {
                    variable,
                    key,
                    value,
                } => {
                    let target = row.get(variable).ok_or_else(|| {
                        CypherError::runtime(format!("Variable `{variable}` not defined"))
                    })?;
                    if !target.is_null() {
                        graph.set_property(target, key, ev.eval(value, row)?)?;
                    }
                }
                SetItem::Map {
                    variable,
                    value,
                    merge,
                } => {
                    let target = row.get(variable).ok_or_else(|| {
                        CypherError::runtime(format!("Variable `{variable}` not defined"))
                    })?;
                    if !target.is_null() {
                        graph.set_properties(target, ev.eval(value, row)?, *merge)?;
                    }
                }
                SetItem::Labels { variable, labels } => {
                    let target = row.get(variable).ok_or_else(|| {
                        CypherError::runtime(format!("Variable `{variable}` not defined"))
                    })?;
                    graph.update_labels(target, labels, false)?;
                }
            }
        }
    }
    Ok(())
}
pub fn remove(ex: &Executor, rows: &[Row], items: &[RemoveItem]) -> CypherResult<()> {
    let graph = ex.property_graph.as_deref().expect("property mode");
    for row in rows {
        for item in items {
            ex.tick()?;
            match item {
                RemoveItem::Property { variable, key } => graph.set_property(
                    row.get(variable).ok_or_else(|| {
                        CypherError::runtime(format!("Variable `{variable}` not defined"))
                    })?,
                    key,
                    Value::Null,
                )?,
                RemoveItem::Labels { variable, labels } => graph.update_labels(
                    row.get(variable).ok_or_else(|| {
                        CypherError::runtime(format!("Variable `{variable}` not defined"))
                    })?,
                    labels,
                    true,
                )?,
            }
        }
    }
    Ok(())
}
pub fn delete(ex: &Executor, rows: &[Row], expressions: &[Expr], detach: bool) -> CypherResult<()> {
    let ev = Evaluator::new(ex, &ex.params);
    let mut targets = Vec::new();
    for row in rows {
        for expression in expressions {
            ex.tick()?;
            targets.push(ev.eval(expression, row)?);
        }
    }
    ex.property_graph
        .as_deref()
        .expect("property mode")
        .delete(&targets, detach)
}
pub fn merge(
    ex: &Executor,
    rows: Vec<Row>,
    pattern: &Pattern,
    on_create: &[SetItem],
    on_match: &[SetItem],
) -> CypherResult<Vec<Row>> {
    let ev = Evaluator::new(ex, &ex.params);
    let mut output = Vec::new();
    for row in rows {
        // Null equality does not identify an entity; MERGE must reject it rather
        // than create another entity every time it is repeated.
        for (key, value) in pattern
            .nodes
            .iter()
            .flat_map(|n| &n.properties)
            .chain(pattern.rels.iter().flat_map(|r| &r.properties))
        {
            if ev.eval(value, &row)?.is_null() {
                return Err(CypherError::runtime(format!(
                    "Cannot merge using null property value for `{key}`"
                )));
            }
        }
        let mut matched = Vec::new();
        super::property_matching::match_patterns(
            ex,
            &row,
            std::slice::from_ref(pattern),
            &mut |row| {
                matched.push(row);
                Ok(true)
            },
        )?;
        if matched.is_empty() {
            let created =
                super::property_matching::create(ex, vec![row], std::slice::from_ref(pattern))?;
            set(ex, &created, on_create)?;
            output.extend(created);
        } else {
            set(ex, &matched, on_match)?;
            output.extend(matched);
        }
    }
    Ok(output)
}

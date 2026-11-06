//! Property-store pattern enumeration, feeding the shared query pipeline.
use super::{pipeline::Row, Executor};
use crate::{
    ast::{Direction, NodePattern, Pattern, RelPattern},
    eval::Evaluator,
    property_graph::{PropertyGraph, PropertySnapshot},
    semantics::cypher_equals,
    value::{EdgeRef, NodeRef, PathValue, Value},
    CypherError, CypherResult,
};
use indexmap::IndexMap;
use std::{collections::HashSet, sync::Arc};

type Emit<'a> = dyn FnMut(Row) -> CypherResult<bool> + 'a;

pub fn match_patterns(
    ex: &Executor,
    row: &Row,
    patterns: &[Pattern],
    emit: &mut Emit<'_>,
) -> CypherResult<bool> {
    let graph = ex.property_graph.as_ref().expect("property graph mode");
    let snapshot = graph.snapshot();
    let walk = Walk {
        ex,
        ev: Evaluator::new(ex, &ex.params),
        snapshot: &snapshot,
    };
    walk.patterns(row, patterns, 0, &mut HashSet::new(), emit)
}
struct Walk<'a> {
    ex: &'a Executor,
    ev: Evaluator<'a>,
    snapshot: &'a PropertySnapshot,
}
impl Walk<'_> {
    fn node_matches(&self, id: u32, pattern: &NodePattern, row: &Row) -> CypherResult<bool> {
        let Some(node) = self.snapshot.nodes.iter().find(|n| n.id == id) else {
            return Ok(false);
        };
        if !pattern.labels.iter().all(|l| node.labels.contains(l)) {
            return Ok(false);
        }
        if let Some(variable) = &pattern.variable {
            if let Some(value) = row.get(variable) {
                if !matches!(value, Value::Node(n) if n.source == 0 && n.id == id) {
                    return Ok(false);
                }
            }
        }
        for (key, expr) in &pattern.properties {
            let expected = self.ev.eval(expr, row)?;
            let actual = node.properties.get(key).unwrap_or(&Value::Null);
            if cypher_equals(actual, &expected) != Some(true) {
                return Ok(false);
            }
        }
        Ok(true)
    }
    fn patterns(
        &self,
        row: &Row,
        patterns: &[Pattern],
        index: usize,
        used: &mut HashSet<u64>,
        emit: &mut Emit<'_>,
    ) -> CypherResult<bool> {
        if index == patterns.len() {
            return emit(row.clone());
        }
        let pattern = &patterns[index];
        for node in &self.snapshot.nodes {
            self.ex.tick()?;
            if !self.node_matches(node.id, &pattern.nodes[0], row)? {
                continue;
            }
            let mut row = row.clone();
            if let Some(variable) = &pattern.nodes[0].variable {
                row.insert(
                    variable.clone(),
                    Value::Node(NodeRef {
                        source: 0,
                        id: node.id,
                    }),
                );
            }
            let mut next = |row: Row, used: &mut HashSet<u64>| {
                self.patterns(&row, patterns, index + 1, used, emit)
            };
            if !self.hops(
                &row,
                pattern,
                0,
                node.id,
                used,
                &mut vec![node.id],
                &mut Vec::new(),
                &mut next,
            )? {
                return Ok(false);
            }
        }
        Ok(true)
    }
    #[allow(clippy::too_many_arguments)]
    fn hops(
        &self,
        row: &Row,
        pattern: &Pattern,
        hop: usize,
        current: u32,
        used: &mut HashSet<u64>,
        nodes: &mut Vec<u32>,
        edges: &mut Vec<(EdgeRef, i8)>,
        next: &mut dyn FnMut(Row, &mut HashSet<u64>) -> CypherResult<bool>,
    ) -> CypherResult<bool> {
        if hop == pattern.rels.len() {
            let mut row = row.clone();
            if let Some(variable) = &pattern.path_variable {
                row.insert(
                    variable.clone(),
                    Value::Path(Arc::new(PathValue {
                        source: 0,
                        nodes: nodes.clone(),
                        edges: edges.iter().map(|e| e.0.edge).collect(),
                        property_path: Some(Arc::new(crate::value::PropertyPathData {
                            relationship_ids: edges
                                .iter()
                                .map(|e| e.0.property_id.unwrap())
                                .collect(),
                            directions: edges.iter().map(|e| e.1).collect(),
                        })),
                    })),
                );
            }
            return next(row, used);
        }
        let relationship = &pattern.rels[hop];
        let minimum = if relationship.variable_length {
            relationship.min_hops.unwrap_or(1) as usize
        } else {
            1
        };
        let maximum = if relationship.variable_length {
            relationship
                .max_hops
                .map(|n| n as usize)
                .unwrap_or(self.snapshot.relationships.len())
        } else {
            1
        };
        self.walk_rel(
            row,
            pattern,
            hop,
            current,
            relationship,
            minimum,
            maximum,
            used,
            nodes,
            edges,
            &mut Vec::new(),
            next,
        )
    }
    #[allow(clippy::too_many_arguments)]
    fn walk_rel(
        &self,
        row: &Row,
        pattern: &Pattern,
        hop: usize,
        current: u32,
        relationship: &RelPattern,
        minimum: usize,
        maximum: usize,
        used: &mut HashSet<u64>,
        nodes: &mut Vec<u32>,
        edges: &mut Vec<(EdgeRef, i8)>,
        segment: &mut Vec<EdgeRef>,
        next: &mut dyn FnMut(Row, &mut HashSet<u64>) -> CypherResult<bool>,
    ) -> CypherResult<bool> {
        self.ex.tick()?;
        if segment.len() >= minimum && self.node_matches(current, &pattern.nodes[hop + 1], row)? {
            let mut row = row.clone();
            let mut binding_matches = true;
            if let Some(variable) = &relationship.variable {
                let value = if relationship.variable_length {
                    Value::list(segment.iter().copied().map(Value::Rel).collect())
                } else {
                    Value::Rel(segment[0])
                };
                binding_matches = row
                    .get(variable)
                    .is_none_or(|old| cypher_equals(old, &value) == Some(true));
                if binding_matches {
                    row.insert(variable.clone(), value);
                }
            }
            if let Some(variable) = &pattern.nodes[hop + 1].variable {
                row.insert(
                    variable.clone(),
                    Value::Node(NodeRef {
                        source: 0,
                        id: current,
                    }),
                );
            }
            if binding_matches
                && !self.hops(&row, pattern, hop + 1, current, used, nodes, edges, next)?
            {
                return Ok(false);
            }
        }
        if segment.len() >= maximum {
            return Ok(true);
        }
        for edge in &self.snapshot.relationships {
            if used.contains(&edge.id)
                || (!relationship.types.is_empty() && !relationship.types.contains(&edge.type_name))
            {
                continue;
            }
            let target = match relationship.direction {
                Direction::Outgoing if edge.from == current => edge.to,
                Direction::Incoming if edge.to == current => edge.from,
                Direction::Both if edge.from == current => edge.to,
                Direction::Both if edge.to == current => edge.from,
                _ => continue,
            };
            let mut matches = true;
            for (key, expr) in &relationship.properties {
                if cypher_equals(
                    edge.properties.get(key).unwrap_or(&Value::Null),
                    &self.ev.eval(expr, row)?,
                ) != Some(true)
                {
                    matches = false;
                    break;
                }
            }
            if !matches {
                continue;
            }
            let reference = edge.reference();
            used.insert(edge.id);
            nodes.push(target);
            edges.push((
                reference,
                if edge.from == current && relationship.direction != Direction::Incoming {
                    1
                } else {
                    -1
                },
            ));
            segment.push(reference);
            let cont = self.walk_rel(
                row,
                pattern,
                hop,
                target,
                relationship,
                minimum,
                maximum,
                used,
                nodes,
                edges,
                segment,
                next,
            )?;
            used.remove(&edge.id);
            nodes.pop();
            edges.pop();
            segment.pop();
            if !cont {
                return Ok(false);
            }
        }
        Ok(true)
    }
}
fn properties(
    ev: &Evaluator<'_>,
    entries: &[(String, crate::ast::Expr)],
    row: &Row,
) -> CypherResult<IndexMap<String, Value>> {
    entries
        .iter()
        .map(|(key, expression)| Ok((key.clone(), ev.eval(expression, row)?)))
        .collect()
}
pub fn create(ex: &Executor, rows: Vec<Row>, patterns: &[Pattern]) -> CypherResult<Vec<Row>> {
    let graph: &PropertyGraph = ex.property_graph.as_deref().expect("property graph mode");
    let ev = Evaluator::new(ex, &ex.params);
    let mut output = Vec::new();
    for mut row in rows {
        for pattern in patterns {
            let mut nodes = Vec::new();
            let mut edges = Vec::new();
            for node in &pattern.nodes {
                ex.tick()?;
                let reference = if let Some(value) = node.variable.as_ref().and_then(|v| row.get(v))
                {
                    match value {
                        Value::Node(reference)
                            if !pattern.rels.is_empty()
                                && node.labels.is_empty()
                                && node.properties.is_empty() =>
                        {
                            *reference
                        }
                        _ => {
                            return Err(CypherError::runtime(
                                "CREATE cannot redefine an existing variable",
                            ))
                        }
                    }
                } else {
                    graph.create_node(&node.labels, properties(&ev, &node.properties, &row)?)?
                };
                if let Some(variable) = &node.variable {
                    row.insert(variable.clone(), Value::Node(reference));
                }
                nodes.push(reference.id);
            }
            for (index, relationship) in pattern.rels.iter().enumerate() {
                if relationship.variable_length
                    || relationship.types.len() != 1
                    || relationship.direction == Direction::Both
                {
                    return Err(CypherError::runtime(
                        "CREATE requires exactly one relationship type and fixed length",
                    ));
                }
                if relationship
                    .variable
                    .as_ref()
                    .is_some_and(|v| row.contains_key(v))
                {
                    return Err(CypherError::runtime(
                        "CREATE cannot redefine an existing relationship",
                    ));
                }
                let (from, to) = if relationship.direction == Direction::Incoming {
                    (nodes[index + 1], nodes[index])
                } else {
                    (nodes[index], nodes[index + 1])
                };
                let reference = graph.create_relationship(
                    from,
                    to,
                    &relationship.types[0],
                    properties(&ev, &relationship.properties, &row)?,
                )?;
                if let Some(variable) = &relationship.variable {
                    row.insert(variable.clone(), Value::Rel(reference));
                }
                edges.push((
                    reference,
                    if relationship.direction == Direction::Incoming {
                        -1
                    } else {
                        1
                    },
                ));
            }
            if let Some(variable) = &pattern.path_variable {
                row.insert(
                    variable.clone(),
                    Value::Path(Arc::new(PathValue {
                        source: 0,
                        nodes,
                        property_path: Some(Arc::new(crate::value::PropertyPathData {
                            relationship_ids: edges
                                .iter()
                                .map(|e| e.0.property_id.unwrap())
                                .collect(),
                            directions: edges.iter().map(|e| e.1).collect(),
                        })),
                        edges: edges.into_iter().map(|e| e.0.edge).collect(),
                    })),
                );
            }
        }
        output.push(row);
    }
    Ok(output)
}

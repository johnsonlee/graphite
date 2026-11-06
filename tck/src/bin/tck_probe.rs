//! JSON-lines adapter for the official openCypher TCK correctness runner.
//!
//! Each line executes independently on an empty immutable graph. Arbitrary TCK graph
//! setup is intentionally handled as unsupported by the runner, never emulated with
//! Graphite program nodes. Errors expose native categories, not guessed TCK codes.
use graphite_cypher::engine::{CancelToken, Executor};
use graphite_cypher::property_graph::{PropertyGraph, PropertyRelationship, PropertySnapshot};
use graphite_cypher::{CypherError, Value};
use indexmap::IndexMap;
use serde_json::{json, Value as Json};
use std::collections::{BTreeMap, BTreeSet};
use std::io::{self, BufRead, Write};
use std::sync::Arc;

fn value_from_json(value: &Json) -> Result<Value, String> {
    Ok(match value {
        Json::Null => Value::Null,
        Json::Bool(value) => Value::Bool(*value),
        Json::Number(value) => {
            if let Some(value) = value.as_i64() {
                Value::Int(value)
            } else if value.is_f64() {
                Value::Float(value.as_f64().ok_or("invalid floating point parameter")?)
            } else {
                return Err("parameter number is not representable".into());
            }
        }
        Json::String(value) => Value::str(value.as_str()),
        Json::Array(values) => Value::list(
            values
                .iter()
                .map(value_from_json)
                .collect::<Result<_, _>>()?,
        ),
        Json::Object(values) if values.get("$tck").is_some_and(|tag| tag[0] == "float") => {
            let number = match values["$tck"][1].as_str() {
                Some("NaN") => f64::NAN,
                Some("Infinity") => f64::INFINITY,
                Some("-Infinity") => f64::NEG_INFINITY,
                _ => return Err("invalid tagged floating point parameter".into()),
            };
            Value::Float(number)
        }
        Json::Object(values) if values.get("$tck").is_some_and(|tag| tag[0] == "map") => {
            let raw = values["$tck"][1]
                .as_object()
                .ok_or("invalid escaped map parameter")?;
            Value::map(
                raw.iter()
                    .map(|(key, value)| Ok((key.clone(), value_from_json(value)?)))
                    .collect::<Result<_, String>>()?,
            )
        }
        Json::Object(values) => Value::map(
            values
                .iter()
                .map(|(key, value)| Ok((key.clone(), value_from_json(value)?)))
                .collect::<Result<_, String>>()?,
        ),
    })
}

#[cfg(test)]
fn value_to_json(value: &Value) -> Result<Json, String> {
    render(value, None, 0)
}

fn properties_to_json(
    properties: &IndexMap<String, Value>,
    graph: &PropertyGraph,
    depth: usize,
) -> Result<Json, String> {
    Ok(Json::Object(
        properties
            .iter()
            .map(|(key, value)| Ok((key.clone(), render(value, Some(graph), depth + 1)?)))
            .collect::<Result<_, String>>()?,
    ))
}

fn render_relationship(
    rel: &PropertyRelationship,
    graph: &PropertyGraph,
    depth: usize,
) -> Result<Json, String> {
    Ok(
        json!({"$tck":["relationship",rel.type_name,properties_to_json(&rel.properties, graph, depth+1)?]}),
    )
}

fn render(value: &Value, graph: Option<&PropertyGraph>, depth: usize) -> Result<Json, String> {
    if depth > 128 {
        return Err("TCK graph/value rendering recursion budget exceeded".into());
    }
    Ok(match value {
        Value::Null => Json::Null,
        Value::Temporal(t) => Json::String(t.to_string()),
        Value::Bool(value) => json!(value),
        Value::Int(value) => json!(value),
        Value::Float(value) if value.is_finite() => json!(value),
        Value::Float32(value) if value.is_finite() => json!(value),
        Value::Float(value) => {
            json!({"$tck":["float", if value.is_nan() { "NaN" } else if value.is_sign_positive() { "Infinity" } else { "-Infinity" }]})
        }
        Value::Float32(value) => {
            json!({"$tck":["float", if value.is_nan() { "NaN" } else if value.is_sign_positive() { "Infinity" } else { "-Infinity" }]})
        }
        Value::Str(value) => json!(value.as_ref()),
        Value::List(values) => Json::Array(
            values
                .iter()
                .map(|v| render(v, graph, depth + 1))
                .collect::<Result<_, _>>()?,
        ),
        Value::Map(values) => {
            let values: serde_json::Map<String, Json> = values
                .iter()
                .map(|(key, value)| Ok((key.clone(), render(value, graph, depth + 1)?)))
                .collect::<Result<_, String>>()?;
            if values.contains_key("$tck") {
                json!({"$tck":["map",values]})
            } else {
                Json::Object(values)
            }
        }
        Value::Node(reference) => {
            let graph = graph.ok_or("node result lacks its property graph")?;
            let node = graph.node(reference.id).ok_or("result node is missing")?;
            let mut labels = node.labels;
            labels.sort();
            labels.dedup();
            json!({"$tck":["node",labels,properties_to_json(&node.properties, graph, depth+1)?]})
        }
        Value::Rel(reference) => {
            let graph = graph.ok_or("relationship result lacks its property graph")?;
            let id = reference
                .property_id
                .ok_or("relationship lacks property identity")?
                .get();
            let rel = graph
                .relationship(u64::from(id))
                .ok_or("result relationship is missing")?;
            render_relationship(&rel, graph, depth + 1)?
        }
        Value::Path(path) => {
            let graph = graph.ok_or("path result lacks its property graph")?;
            let property = path
                .property_path
                .as_ref()
                .ok_or("path lacks property identity")?;
            if path.nodes.len() != property.relationship_ids.len() + 1
                || property.directions.len() != property.relationship_ids.len()
            {
                return Err("invalid native path framing".into());
            }
            let nodes = path
                .nodes
                .iter()
                .map(|id| {
                    render(
                        &Value::Node(graphite_cypher::value::NodeRef { source: 0, id: *id }),
                        Some(graph),
                        depth + 1,
                    )
                })
                .collect::<Result<Vec<_>, _>>()?;
            let relationships = property
                .relationship_ids
                .iter()
                .map(|id| {
                    let relationship = graph
                        .relationship(u64::from(id.get()))
                        .ok_or("path relationship is missing")?;
                    render_relationship(&relationship, graph, depth + 1)
                })
                .collect::<Result<Vec<_>, String>>()?;
            json!({"$tck":["path",nodes,relationships,property.directions]})
        }
        _ => return Err("TCK probe cannot encode this native value".into()),
    })
}

fn error_kind(error: &CypherError) -> &'static str {
    match error {
        CypherError::Parse { .. } | CypherError::Syntax { .. } => "parse",
        CypherError::Runtime(_) => "runtime",
        CypherError::Aggregation(_) => "aggregation",
        CypherError::NotImplemented(_) => "not_implemented",
        CypherError::Cancelled => "cancelled",
        CypherError::Timeout(_) => "timeout",
        CypherError::BudgetExceeded(_) => "budget_exceeded",
        CypherError::Other(_) => "other",
    }
}

#[cfg(test)]
fn zero_effects() -> Json {
    json!({"+nodes":0,"-nodes":0,"+relationships":0,"-relationships":0,"+labels":0,"-labels":0,"+properties":0,"-properties":0})
}

fn parameters(request: &Json) -> Result<IndexMap<String, Value>, String> {
    match request.get("parameters") {
        None => Ok(IndexMap::new()),
        Some(Json::Object(values)) => values
            .iter()
            .map(|(key, value)| Ok((key.clone(), value_from_json(value)?)))
            .collect(),
        _ => Err("parameters must be an object".into()),
    }
}

#[derive(Default)]
struct State {
    nodes: BTreeSet<u64>,
    relationships: BTreeSet<u64>,
    labels: BTreeSet<String>,
    properties: BTreeMap<String, usize>,
}
impl State {
    fn from_snapshot(snapshot: &PropertySnapshot, graph: &PropertyGraph) -> Result<Self, String> {
        let mut result = Self::default();
        for node in &snapshot.nodes {
            result.nodes.insert(u64::from(node.id));
            result.labels.extend(node.labels.iter().cloned());
            for (key, value) in &node.properties {
                *result
                    .properties
                    .entry(
                        serde_json::to_string(&json!([
                            node.id,
                            key,
                            render(value, Some(graph), 0)?
                        ]))
                        .unwrap(),
                    )
                    .or_default() += 1;
            }
        }
        for rel in &snapshot.relationships {
            result.relationships.insert(rel.id);
            for (key, value) in &rel.properties {
                *result
                    .properties
                    .entry(
                        serde_json::to_string(&json!([
                            rel.id,
                            key,
                            render(value, Some(graph), 0)?
                        ]))
                        .unwrap(),
                    )
                    .or_default() += 1;
            }
        }
        Ok(result)
    }
    fn difference(&self, after: &Self) -> Json {
        let created: usize = after
            .properties
            .iter()
            .map(|(k, n)| n.saturating_sub(*self.properties.get(k).unwrap_or(&0)))
            .sum();
        let deleted: usize = self
            .properties
            .iter()
            .map(|(k, n)| n.saturating_sub(*after.properties.get(k).unwrap_or(&0)))
            .sum();
        json!({"+nodes":after.nodes.difference(&self.nodes).count(),"-nodes":self.nodes.difference(&after.nodes).count(),"+relationships":after.relationships.difference(&self.relationships).count(),"-relationships":self.relationships.difference(&after.relationships).count(),"+labels":after.labels.difference(&self.labels).count(),"-labels":self.labels.difference(&after.labels).count(),"+properties":created,"-properties":deleted})
    }
}

fn execute_query(
    graph: &Arc<PropertyGraph>,
    query: &str,
    parameters: IndexMap<String, Value>,
) -> Result<Json, String> {
    let executor = Executor::property_graph(graph.clone())
        .with_params(parameters)
        .with_cancel(CancelToken::with_timeout(2_000));
    match executor.execute(query, None) {
        Ok(result) => {
            if result.rows.iter().any(|row| {
                result
                    .columns
                    .iter()
                    .any(|column| !row.contains_key(column))
            }) {
                return Ok(
                    json!({"columns":[],"rows":[],"error":"native result row lacks a declared column","errorKind":"invalid_result"}),
                );
            }
            let rows = result
                .rows
                .iter()
                .map(|row| {
                    result
                        .columns
                        .iter()
                        .map(|column| {
                            render(
                                row.get(column).ok_or("missing result column")?,
                                Some(graph),
                                0,
                            )
                        })
                        .collect::<Result<Vec<_>, String>>()
                })
                .collect::<Result<Vec<_>, String>>()?;
            Ok(json!({"columns":result.columns,"rows":rows,"error":null}))
        }
        Err(error) => {
            let mut result = json!({"columns":[],"rows":[],"error":error.to_string(),"errorKind":error_kind(&error)});
            if let Some(diagnostic) = error.diagnostic() {
                result["diagnostic"] = json!({"type":diagnostic.error_type,"phase":diagnostic.phase,"detail":diagnostic.detail});
            }
            Ok(result)
        }
    }
}

fn execute(request: &Json) -> Result<Json, String> {
    let query = request["query"].as_str().ok_or("query must be a string")?;
    if request["procedures"]
        .as_array()
        .is_some_and(|p| !p.is_empty())
    {
        return Err("production procedure registry adapter is not implemented".into());
    }
    let graph = Arc::new(PropertyGraph::new());
    let parameters = parameters(request)?;
    if let Some(setup) = request["setup"].as_array() {
        for query in setup {
            let mut result = execute_query(
                &graph,
                query.as_str().ok_or("setup query must be a string")?,
                parameters.clone(),
            )?;
            if !result["error"].is_null() {
                result["setupError"] = json!(true);
                return Ok(result);
            }
        }
    }
    let before = State::from_snapshot(&graph.snapshot(), &graph)?;
    let mut result = execute_query(&graph, query, parameters)?;
    result["sideEffects"] = before.difference(&State::from_snapshot(&graph.snapshot(), &graph)?);
    if result["error"].is_null() {
        let mut controls = Vec::new();
        if let Some(queries) = request["controls"].as_array() {
            for control in queries {
                controls.push(execute_query(
                    &graph,
                    control["query"]
                        .as_str()
                        .ok_or("control query must be a string")?,
                    self::parameters(control)?,
                )?);
            }
        }
        if !controls.is_empty() {
            result["controls"] = json!(controls);
        }
    }
    Ok(result)
}

fn main() -> io::Result<()> {
    let mut output = io::BufWriter::new(io::stdout().lock());
    for line in io::stdin().lock().lines() {
        let line = line?;
        let response = serde_json::from_str::<Json>(&line)
            .map_err(|error| error.to_string())
            .and_then(|request| execute(&request))
            .unwrap_or_else(|error| json!({"adapterError":error}));
        serde_json::to_writer(&mut output, &response)?;
        writeln!(output)?;
        output.flush()?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn official_setup_runs_on_production_store_and_requests_are_isolated() {
        let result =
            execute(&json!({"setup":["CREATE (:A {name:'x'})"],"query":"MATCH (n:A) RETURN n"}))
                .unwrap();
        assert_eq!(
            result["rows"],
            json!([[{"$tck":["node",["A"],{"name":"x"}]}]])
        );
        assert_eq!(result["sideEffects"], zero_effects());
        let isolated = execute(&json!({"query":"MATCH (n:A) RETURN count(n) AS total"})).unwrap();
        assert_eq!(isolated["rows"], json!([[0]]));
    }

    #[test]
    fn side_effects_use_unique_global_labels_and_exclude_setup() {
        let result = execute(&json!({"setup":["CREATE (:A)"],"query":"CREATE (:A),(:B)"})).unwrap();
        let mut expected = zero_effects();
        expected["+nodes"] = json!(2);
        expected["+labels"] = json!(1);
        assert_eq!(result["sideEffects"], expected);
    }

    #[test]
    fn paths_keep_backward_relationship_orientation() {
        let result = execute(&json!({"setup":["CREATE (:A)-[:R]->(:B)"],"query":"MATCH p=(b:B)<-[:R]-(a:A) RETURN p"})).unwrap();
        assert_eq!(result["error"], Json::Null);
        assert_eq!(result["rows"][0][0]["$tck"][3], json!([-1]));
        assert_eq!(
            result["rows"][0][0]["$tck"][1][0],
            json!({"$tck":["node",["B"],{}]})
        );
    }

    #[test]
    fn controls_observe_primary_mutations_without_polluting_primary_effects() {
        let result = execute(&json!({"query":"CREATE (:Created)","controls":[{"query":"MATCH (n:Created) RETURN count(n) AS total"}]})).unwrap();
        assert_eq!(result["controls"][0]["rows"], json!([[1]]));
        assert_eq!(result["sideEffects"]["+nodes"], 1);
        assert_eq!(result["sideEffects"]["+labels"], 1);
    }

    #[test]
    fn failed_queries_rollback_and_setup_errors_cannot_masquerade_as_primary_errors() {
        let result = execute(&json!({"query":"CREATE (:A) RETURN unknownFunction()"})).unwrap();
        assert!(!result["error"].is_null());
        assert_eq!(result["sideEffects"], zero_effects());
        let setup_error = execute(&json!({"setup":["RETURN ("],"query":"RETURN 1"})).unwrap();
        assert_eq!(setup_error["setupError"], true);
    }

    #[test]
    fn nonfinite_parameters_and_escaped_literal_maps_keep_native_types() {
        assert!(
            matches!(value_from_json(&json!({"$tck":["float","NaN"]})).unwrap(), Value::Float(n) if n.is_nan())
        );
        let native = value_from_json(&json!({"$tck":["map",{"$tck":["node",[],{}]}]})).unwrap();
        assert!(matches!(&native, Value::Map(_)));
        assert_eq!(
            value_to_json(&native).unwrap(),
            json!({"$tck":["map",{"$tck":["node",[],{}]}]})
        );
    }

    #[test]
    fn preserves_parameters_null_nested_values_and_order() {
        let request = json!({"query":"UNWIND [2, 1, 2] AS n RETURN n, $nested AS payload ORDER BY n", "parameters":{"nested":{"items":[null,true,"x",1.5]}}});
        assert_eq!(
            execute(&request).unwrap(),
            json!({"columns":["n","payload"],"rows":[[1,{"items":[null,true,"x",1.5]}],[2,{"items":[null,true,"x",1.5]}],[2,{"items":[null,true,"x",1.5]}]],"error":null,"sideEffects":zero_effects()})
        );
    }

    #[test]
    fn native_errors_and_adapter_errors_remain_distinct() {
        let response = execute(&json!({"query":"RETURN ("})).unwrap();
        assert_eq!(response["errorKind"], "parse");
        assert!(!response["error"].as_str().unwrap().is_empty());
        assert!(execute(&json!({"query":42})).is_err());
        assert_eq!(
            execute(&json!({"query":"RETURN 1 AS n"})).unwrap()["rows"],
            json!([[1]])
        );
    }

    #[test]
    fn out_of_range_integer_parameters_are_not_rounded_to_float() {
        let request: Json = serde_json::from_str(
            r#"{"query":"RETURN $n AS n","parameters":{"n":18446744073709551615}}"#,
        )
        .unwrap();
        assert!(execute(&request).unwrap_err().contains("not representable"));
    }

    #[test]
    fn nonfinite_values_cannot_silently_become_null() {
        assert_eq!(
            value_to_json(&Value::Float(f64::NAN)).unwrap(),
            json!({"$tck":["float","NaN"]})
        );
        assert_eq!(
            value_to_json(&Value::Float(f64::INFINITY)).unwrap(),
            json!({"$tck":["float","Infinity"]})
        );
        assert_eq!(value_to_json(&Value::Null).unwrap(), Json::Null);
    }
}

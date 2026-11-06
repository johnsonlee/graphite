//! JSON-lines adapter for the official openCypher TCK correctness runner.
//!
//! Each line executes independently on an empty immutable graph. Arbitrary TCK graph
//! setup is intentionally handled as unsupported by the runner, never emulated with
//! Graphite program nodes. Errors expose native categories, not guessed TCK codes.
use graphite_cypher::engine::{CancelToken, Executor};
use graphite_cypher::{CypherError, Value};
use indexmap::IndexMap;
use serde_json::{json, Value as Json};
use std::io::{self, BufRead, Write};

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
        Json::Object(values) => Value::map(
            values
                .iter()
                .map(|(key, value)| Ok((key.clone(), value_from_json(value)?)))
                .collect::<Result<_, String>>()?,
        ),
    })
}

fn value_to_json(value: &Value) -> Result<Json, String> {
    Ok(match value {
        Value::Null => Json::Null,
        Value::Temporal(t) => Json::String(t.to_string()),
        Value::Bool(value) => json!(value),
        Value::Int(value) => json!(value),
        Value::Float(value) if value.is_finite() => json!(value),
        Value::Float32(value) if value.is_finite() => json!(value),
        Value::Str(value) => json!(value.as_ref()),
        Value::List(values) => {
            Json::Array(values.iter().map(value_to_json).collect::<Result<_, _>>()?)
        }
        Value::Map(values) => Json::Object(
            values
                .iter()
                .map(|(key, value)| Ok((key.clone(), value_to_json(value)?)))
                .collect::<Result<_, String>>()?,
        ),
        _ => return Err("TCK probe cannot encode graph references or non-finite numbers".into()),
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

fn execute(request: &Json) -> Result<Json, String> {
    let query = request["query"].as_str().ok_or("query must be a string")?;
    let parameters = match request.get("parameters") {
        None => IndexMap::new(),
        Some(Json::Object(values)) => values
            .iter()
            .map(|(key, value)| Ok((key.clone(), value_from_json(value)?)))
            .collect::<Result<_, String>>()?,
        _ => return Err("parameters must be an object".into()),
    };
    let executor = Executor::new(vec![], false)
        .with_params(parameters)
        .with_cancel(CancelToken::with_timeout(2_000));
    match executor.execute(query, None) {
        Ok(result) => {
            let rows = result
                .rows
                .iter()
                .map(|row| {
                    result
                        .columns
                        .iter()
                        .map(|column| {
                            value_to_json(row.get(column).ok_or("missing result column")?)
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
    fn preserves_parameters_null_nested_values_and_order() {
        let request = json!({"query":"UNWIND [2, 1, 2] AS n RETURN n, $nested AS payload ORDER BY n", "parameters":{"nested":{"items":[null,true,"x",1.5]}}});
        assert_eq!(
            execute(&request).unwrap(),
            json!({"columns":["n","payload"],"rows":[[1,{"items":[null,true,"x",1.5]}],[2,{"items":[null,true,"x",1.5]}],[2,{"items":[null,true,"x",1.5]}]],"error":null})
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
        assert!(value_to_json(&Value::Float(f64::NAN)).is_err());
        assert!(value_to_json(&Value::Float(f64::INFINITY)).is_err());
        assert_eq!(value_to_json(&Value::Null).unwrap(), Json::Null);
    }
}

//! Typed procedure providers used by ordinary query execution.
use crate::{CypherError, CypherResult, Value};
use indexmap::IndexMap;
use std::{collections::BTreeMap, sync::Arc};

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ProcedureType {
    Any,
    Integer,
    Float,
    Number,
    String,
    Boolean,
    List(Box<ProcedureType>),
}
#[derive(Clone, Debug)]
pub struct ProcedureField {
    pub name: String,
    pub value_type: ProcedureType,
    pub nullable: bool,
}
#[derive(Clone, Debug)]
pub struct ProcedureSignature {
    pub name: String,
    pub inputs: Vec<ProcedureField>,
    pub outputs: Vec<ProcedureField>,
}
type Provider = dyn Fn(&[Value]) -> CypherResult<Vec<Vec<Value>>> + Send + Sync;
struct Procedure {
    signature: ProcedureSignature,
    provider: Arc<Provider>,
}
#[derive(Default)]
pub struct ProcedureRegistry {
    procedures: BTreeMap<String, Procedure>,
}

pub fn syntax(detail: &'static str, message: impl Into<String>) -> CypherError {
    CypherError::compilation("SyntaxError", detail, message)
}

impl ProcedureType {
    fn coerce(&self, value: &Value) -> Option<Value> {
        match (self, value) {
            (Self::Any, _) => Some(value.clone()),
            (Self::Integer, Value::Int(_))
            | (Self::Number, Value::Int(_) | Value::Float(_) | Value::Float32(_))
            | (Self::String, Value::Str(_))
            | (Self::Boolean, Value::Bool(_)) => Some(value.clone()),
            (Self::Float, Value::Float(_) | Value::Float32(_)) => Some(value.clone()),
            (Self::Float, Value::Int(n)) => Some(Value::Float(*n as f64)),
            (Self::List(element), Value::List(values)) => values
                .iter()
                .map(|value| element.coerce(value))
                .collect::<Option<Vec<_>>>()
                .map(Value::list),
            _ => None,
        }
    }
}
impl ProcedureField {
    pub fn coerce(&self, value: &Value) -> CypherResult<Value> {
        if value.is_null() {
            return if self.nullable {
                Ok(Value::Null)
            } else {
                Err(syntax(
                    "InvalidArgumentType",
                    format!("Procedure field {} is not nullable", self.name),
                ))
            };
        }
        self.value_type.coerce(value).ok_or_else(|| {
            syntax(
                "InvalidArgumentType",
                format!("Invalid argument type for procedure field {}", self.name),
            )
        })
    }
}
impl ProcedureRegistry {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn register<F>(&mut self, signature: ProcedureSignature, provider: F) -> CypherResult<()>
    where
        F: Fn(&[Value]) -> CypherResult<Vec<Vec<Value>>> + Send + Sync + 'static,
    {
        if self.procedures.contains_key(&signature.name) {
            return Err(CypherError::runtime("procedure already registered"));
        }
        let mut names = std::collections::BTreeSet::new();
        for output in &signature.outputs {
            if !names.insert(&output.name) {
                return Err(CypherError::runtime("duplicate procedure output"));
            }
        }
        self.procedures.insert(
            signature.name.clone(),
            Procedure {
                signature,
                provider: Arc::new(provider),
            },
        );
        Ok(())
    }
    pub fn signature(&self, name: &str) -> CypherResult<&ProcedureSignature> {
        self.procedures
            .get(name)
            .map(|p| &p.signature)
            .ok_or_else(|| {
                CypherError::compilation(
                    "ProcedureError",
                    "ProcedureNotFound",
                    format!("Unknown procedure: {name}"),
                )
            })
    }
    pub fn invoke(
        &self,
        name: &str,
        arguments: &[Value],
    ) -> CypherResult<Vec<IndexMap<String, Value>>> {
        let procedure = self.procedures.get(name).ok_or_else(|| {
            CypherError::compilation(
                "ProcedureError",
                "ProcedureNotFound",
                format!("Unknown procedure: {name}"),
            )
        })?;
        if arguments.len() != procedure.signature.inputs.len() {
            return Err(syntax(
                "InvalidNumberOfArguments",
                format!("Invalid argument count for procedure {name}"),
            ));
        }
        let arguments = procedure
            .signature
            .inputs
            .iter()
            .zip(arguments)
            .map(|(field, value)| {
                field.coerce(value).map_err(|_| {
                    CypherError::execution(
                        "TypeError",
                        "InvalidArgumentType",
                        format!("Invalid argument type for procedure field {}", field.name),
                    )
                })
            })
            .collect::<CypherResult<Vec<_>>>()?;
        let rows = (procedure.provider)(&arguments)?;
        if procedure.signature.outputs.is_empty() {
            return Ok(vec![IndexMap::new()]);
        }
        rows.into_iter()
            .map(|row| {
                if row.len() != procedure.signature.outputs.len() {
                    return Err(CypherError::runtime(
                        "procedure provider returned an invalid row width",
                    ));
                }
                procedure
                    .signature
                    .outputs
                    .iter()
                    .zip(row)
                    .map(|(field, value)| {
                        Ok((
                            field.name.clone(),
                            field.coerce(&value).map_err(|_| {
                                CypherError::execution(
                                    "TypeError",
                                    "InvalidArgumentType",
                                    format!(
                                        "Procedure provider returned an invalid type for {}",
                                        field.name
                                    ),
                                )
                            })?,
                        ))
                    })
                    .collect()
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::engine::Executor;
    use std::sync::atomic::{AtomicUsize, Ordering};

    fn registry(calls: Arc<AtomicUsize>) -> Arc<ProcedureRegistry> {
        let mut registry = ProcedureRegistry::new();
        registry
            .register(
                ProcedureSignature {
                    name: "app.echo".into(),
                    inputs: vec![ProcedureField {
                        name: "input".into(),
                        value_type: ProcedureType::Float,
                        nullable: true,
                    }],
                    outputs: vec![ProcedureField {
                        name: "value".into(),
                        value_type: ProcedureType::Float,
                        nullable: true,
                    }],
                },
                move |values| {
                    calls.fetch_add(1, Ordering::Relaxed);
                    Ok(vec![vec![values[0].clone()]])
                },
            )
            .unwrap();
        Arc::new(registry)
    }

    #[test]
    fn ordinary_executor_calls_registered_providers_with_native_coercion_and_yield_aliases() {
        let calls = Arc::new(AtomicUsize::new(0));
        let ex = Executor::new(vec![], false).with_procedures(registry(calls.clone()));
        let result = ex
            .execute(
                "UNWIND [2,1] AS n CALL app.echo(n) YIELD value AS out RETURN out ORDER BY out",
                None,
            )
            .unwrap();
        assert_eq!(result.columns, vec!["out"]);
        assert!(matches!(result.rows[0]["out"], Value::Float(1.0)));
        assert!(matches!(result.rows[1]["out"], Value::Float(2.0)));
        assert_eq!(calls.load(Ordering::Relaxed), 2);
        let standalone = ex.execute("CALL app.echo(3)", None).unwrap();
        assert_eq!(standalone.columns, vec!["value"]);
        assert!(matches!(standalone.rows[0]["value"], Value::Float(3.0)));
    }

    #[test]
    fn procedure_contract_errors_are_checked_before_provider_execution() {
        let calls = Arc::new(AtomicUsize::new(0));
        let ex = Executor::new(vec![], false).with_procedures(registry(calls.clone()));
        for (query, kind, detail) in [
            ("CALL app.echo(true)", "SyntaxError", "InvalidArgumentType"),
            (
                "CALL app.echo(1,2)",
                "SyntaxError",
                "InvalidNumberOfArguments",
            ),
            ("CALL app.echo", "ParameterMissing", "MissingParameter"),
            (
                "CALL app.echo(1) YIELD value AS n, value AS n RETURN n",
                "SyntaxError",
                "VariableAlreadyBound",
            ),
            (
                "CALL app.echo(1) YIELD * RETURN value",
                "SyntaxError",
                "UnexpectedSyntax",
            ),
            ("CALL app.missing()", "ProcedureError", "ProcedureNotFound"),
            (
                "MATCH (n) CALL app.echo(count(n)) YIELD value RETURN value",
                "SyntaxError",
                "InvalidAggregation",
            ),
        ] {
            let error = ex.execute(query, None).unwrap_err();
            let diagnostic = error.diagnostic().unwrap();
            assert_eq!(
                (diagnostic.error_type, diagnostic.phase, diagnostic.detail),
                (kind, "compile time", detail),
                "{query}"
            );
        }
        assert_eq!(calls.load(Ordering::Relaxed), 0);
    }

    #[test]
    fn parser_preserves_explicit_implicit_and_yield_shapes() {
        let clauses =
            crate::parser::parse("CALL app.echo($input) YIELD value AS out RETURN out").unwrap();
        let crate::ast::Clause::Call(call) = &clauses[0] else {
            panic!("CALL clause missing")
        };
        assert_eq!(call.name, "app.echo");
        assert_eq!(
            call.arguments,
            Some(vec![crate::ast::Expr::Parameter("input".into())])
        );
        assert_eq!(call.yields, Some(vec![("value".into(), "out".into())]));
        let clauses = crate::parser::parse("CALL app.echo").unwrap();
        assert!(matches!(&clauses[0],crate::ast::Clause::Call(call) if call.arguments.is_none()));
    }
}

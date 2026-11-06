//! Execute every official scenario or retain its precise unsupported contract.
use anyhow::{anyhow, bail, Context, Result};
use serde_json::{json, Map, Value};
use std::collections::{BTreeMap, BTreeSet};
use std::io::{BufRead, BufReader, Write};
use std::path::Path;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::mpsc::{self, Receiver};
use std::time::{Duration, Instant};

pub struct Run {
    pub results: Vec<Value>,
    pub infrastructure_errors: Vec<String>,
}

#[derive(Debug)]
pub struct Prepared {
    pub query: String,
    pub parameters: Value,
    pub expected: Value,
    pub ordered: bool,
}

fn table(step: &Value) -> Result<Vec<Vec<String>>, String> {
    step["argument"]["dataTable"]["rows"]
        .as_array()
        .ok_or("invalid TCK data table")?
        .iter()
        .map(|row| {
            row["cells"]
                .as_array()
                .ok_or("invalid TCK table row")?
                .iter()
                .map(|cell| {
                    cell["value"]
                        .as_str()
                        .map(str::to_owned)
                        .ok_or_else(|| "invalid TCK table cell".to_owned())
                })
                .collect()
        })
        .collect()
}

pub fn prepare(scenario: &Value) -> Result<Prepared, String> {
    let mut query = None;
    let mut parameters = Map::new();
    let mut expected = None;
    let mut ordered = false;
    let mut graph = false;
    let mut side_effects = false;
    for step in scenario["steps"]
        .as_array()
        .ok_or("scenario missing steps")?
    {
        let text = step["text"].as_str().ok_or("step missing text")?;
        match text {
            "an empty graph" | "any graph" => graph = true,
            "parameters are:" => {
                parameters.clear();
                for row in table(step)? {
                    if row.len() != 2 {
                        return Err("invalid TCK parameter row".to_owned());
                    }
                    parameters.insert(row[0].clone(), crate::values::value(&row[1])?);
                }
            }
            "executing query:" if query.is_none() => {
                query = Some(
                    step["argument"]["docString"]["content"]
                        .as_str()
                        .ok_or("missing query doc string")?
                        .to_owned(),
                )
            }
            "the result should be, in any order:"
            | "the result should be, in order:"
            | "the result should be (ignoring element order for lists):" => {
                let rows = table(step)?;
                let columns = rows.first().ok_or("result table missing header")?;
                let rows = rows
                    .iter()
                    .skip(1)
                    .map(|row| {
                        row.iter()
                            .map(|cell| crate::values::value(cell))
                            .collect::<Result<Vec<_>, String>>()
                    })
                    .collect::<Result<Vec<_>, String>>()?;
                expected = Some(json!({"columns":columns,"rows":rows}));
                if text == "the result should be (ignoring element order for lists):" {
                    expected.as_mut().unwrap()["ignoreListOrder"] = json!(true);
                }
                ordered = text.ends_with("in order:");
            }
            "the result should be empty" => expected = Some(json!({"empty":true})),
            "no side effects" => side_effects = true,
            _ if expected_error(text).is_some() => {
                expected = Some(json!({"error": expected_error(text).unwrap()}))
            }
            _ => return Err(format!("step adapter missing: {text}")),
        }
    }
    if !graph
        || query.is_none()
        || expected.is_none()
        || (!side_effects && expected.as_ref().unwrap()["error"].is_null())
    {
        return Err(
            "scenario lacks supported graph/query/result/no-side-effects contract".to_owned(),
        );
    }
    Ok(Prepared {
        query: query.unwrap(),
        parameters: Value::Object(parameters),
        expected: expected.unwrap(),
        ordered,
    })
}

fn expected_error(text: &str) -> Option<Value> {
    let rest = text.strip_prefix("a ")?;
    let (error_type, rest) = rest.split_once(" should be raised at ")?;
    let (phase, detail) = rest.split_once(": ")?;
    if !matches!(phase, "compile time" | "runtime" | "any time")
        || error_type.is_empty()
        || detail.is_empty()
    {
        return None;
    }
    Some(json!({"type": error_type, "phase": phase, "detail": detail}))
}

fn normalize_lists(value: &Value) -> Value {
    match value {
        Value::Array(values) => {
            let mut values: Vec<_> = values.iter().map(normalize_lists).collect();
            values.sort_by_key(crate::canonical);
            Value::Array(values)
        }
        Value::Object(values) => Value::Object(
            values
                .iter()
                .map(|(k, v)| (k.clone(), normalize_lists(v)))
                .collect(),
        ),
        _ => value.clone(),
    }
}

fn multiset(rows: &[Value]) -> BTreeMap<String, usize> {
    let mut result = BTreeMap::new();
    for row in rows {
        *result.entry(crate::canonical(row)).or_insert(0) += 1;
    }
    result
}

pub fn compare(actual: &Value, expected: &Value, ordered: bool) -> Option<String> {
    if !actual["adapterError"].is_null() {
        return Some(format!(
            "probe adapter error: {}",
            display(&actual["adapterError"])
        ));
    }
    if !expected["error"].is_null() {
        if actual["error"].is_null() {
            return Some("expected an engine error, query succeeded".into());
        }
        let observed = &actual["diagnostic"];
        if !observed.is_object() {
            return Some("engine error lacks a structured diagnostic".into());
        }
        let wanted = &expected["error"];
        let phase_matches = wanted["phase"] == observed["phase"]
            || (wanted["phase"] == "any time"
                && matches!(observed["phase"].as_str(), Some("compile time" | "runtime")));
        let detail_matches = wanted["detail"] == observed["detail"]
            || (wanted["detail"] == "*" && observed["detail"].is_string());
        return if wanted["type"] == observed["type"] && phase_matches && detail_matches {
            None
        } else {
            Some("structured error type/phase/detail mismatch".into())
        };
    }
    if !actual["error"].is_null() {
        return Some(format!("engine error: {}", display(&actual["error"])));
    }
    if !actual["columns"].is_array() || !actual["rows"].is_array() {
        return Some("invalid probe result shape".to_owned());
    }
    if expected["empty"] == true {
        return if actual["rows"].as_array().unwrap().is_empty() {
            None
        } else {
            Some("expected empty rows".to_owned())
        };
    }
    if actual["columns"] != expected["columns"] {
        return Some("column names/order mismatch".to_owned());
    }
    let normalized_actual;
    let normalized_expected;
    let (actual, expected) = if expected["ignoreListOrder"] == true {
        normalized_actual = json!({"rows": actual["rows"].as_array().unwrap().iter().map(|row| Value::Array(row.as_array().unwrap().iter().map(normalize_lists).collect())).collect::<Vec<_>>()});
        normalized_expected = json!({"rows": expected["rows"].as_array().unwrap().iter().map(|row| Value::Array(row.as_array().unwrap().iter().map(normalize_lists).collect())).collect::<Vec<_>>()});
        (&normalized_actual, &normalized_expected)
    } else {
        (actual, expected)
    };
    let equal = if ordered {
        crate::canonical(&actual["rows"]) == crate::canonical(&expected["rows"])
    } else {
        multiset(actual["rows"].as_array().unwrap())
            == multiset(expected["rows"].as_array().unwrap())
    };
    if equal {
        None
    } else {
        Some("full result mismatch".to_owned())
    }
}

fn display(value: &Value) -> String {
    value
        .as_str()
        .map(str::to_owned)
        .unwrap_or_else(|| value.to_string())
}

enum Response {
    Actual(Value),
    Unsupported(String),
}
struct Probe {
    child: Child,
    input: Option<ChildStdin>,
    lines: Receiver<Result<String, String>>,
}
impl Probe {
    fn new(binary: &Path) -> Result<Self> {
        let mut child = Command::new(binary)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .context("start TCK probe")?;
        let input = child.stdin.take();
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| anyhow!("probe stdout unavailable"))?;
        let (sender, lines) = mpsc::channel();
        std::thread::spawn(move || {
            for line in BufReader::new(stdout).lines() {
                if sender
                    .send(line.map_err(|error| error.to_string()))
                    .is_err()
                {
                    break;
                }
            }
        });
        Ok(Self {
            child,
            input,
            lines,
        })
    }
    fn execute(&mut self, query: &str, parameters: &Value) -> Result<Response> {
        self.execute_with_timeout(query, parameters, Duration::from_secs(15))
    }

    fn execute_with_timeout(
        &mut self,
        query: &str,
        parameters: &Value,
        timeout: Duration,
    ) -> Result<Response> {
        let input = self
            .input
            .as_mut()
            .ok_or_else(|| anyhow!("probe stdin closed"))?;
        writeln!(
            input,
            "{}",
            crate::canonical(&json!({"query":query,"parameters":parameters}))
        )?;
        input.flush()?;
        let line = self
            .lines
            .recv_timeout(timeout)
            .map_err(|error| match error {
                mpsc::RecvTimeoutError::Timeout => anyhow!("probe response timeout"),
                mpsc::RecvTimeoutError::Disconnected => {
                    anyhow!("probe exited unexpectedly: {:?}", self.child.try_wait())
                }
            })?
            .map_err(|error| anyhow!(error))?;
        let result: Value = serde_json::from_str(&line).context("invalid probe JSON")?;
        if let Some(error) = result.get("adapterError") {
            return Ok(Response::Unsupported(format!(
                "probe value adapter: {}",
                display(error)
            )));
        }
        validate_response(&result)?;
        Ok(Response::Actual(result))
    }
    fn close(&mut self) -> Result<()> {
        self.input.take();
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            if let Some(status) = self.child.try_wait()? {
                if status.success() {
                    return Ok(());
                }
                bail!("probe exited unsuccessfully: {status}");
            }
            if Instant::now() >= deadline {
                self.child.kill()?;
                let status = self.child.wait()?;
                bail!("probe exited unsuccessfully: {status}");
            }
            std::thread::sleep(Duration::from_millis(10));
        }
    }
}
impl Drop for Probe {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn validate_response(result: &Value) -> Result<()> {
    let columns = result["columns"]
        .as_array()
        .filter(|columns| columns.iter().all(Value::is_string))
        .ok_or_else(|| anyhow!("probe protocol: columns must be strings"))?;
    if !result["rows"].as_array().is_some_and(|rows| {
        rows.iter()
            .all(|row| row.as_array().is_some_and(|row| row.len() == columns.len()))
    }) {
        bail!("probe protocol: rows must match column width");
    }
    if result.get("error").is_none() {
        bail!("probe protocol: missing error field");
    }
    Ok(())
}

fn identity(scenario: &Value) -> Map<String, Value> {
    ["id", "feature", "name", "scenarioSha256"]
        .into_iter()
        .map(|key| (key.to_owned(), scenario[key].clone()))
        .collect()
}

pub fn run(root: &Path, binary: &Path) -> Result<Run> {
    let inventory = crate::corpus::scenarios(root)?;
    run_inventory(inventory, binary)
}

fn run_inventory(inventory: Vec<Value>, binary: &Path) -> Result<Run> {
    let mut results = Vec::new();
    let mut infrastructure_errors = Vec::new();
    let mut probe = match Probe::new(binary) {
        Ok(probe) => Some(probe),
        Err(error) => {
            infrastructure_errors.push(error.to_string());
            None
        }
    };
    if let Some(probe) = &mut probe {
        for scenario in &inventory {
            let mut result = Value::Object(identity(scenario));
            match prepare(scenario) {
                Err(reason) => {
                    result["status"] = json!("unsupported");
                    result["reason"] = json!(reason);
                    result["executed"] = json!(false);
                }
                Ok(prepared) => match probe.execute(&prepared.query, &prepared.parameters) {
                    Err(error) => {
                        infrastructure_errors.push(error.to_string());
                        break;
                    }
                    Ok(Response::Unsupported(reason)) => {
                        result["status"] = json!("unsupported");
                        result["reason"] = json!(reason);
                        result["executed"] = json!(true);
                    }
                    Ok(Response::Actual(actual)) => {
                        let reason = compare(&actual, &prepared.expected, prepared.ordered);
                        result["status"] =
                            json!(if reason.is_some() { "failed" } else { "passed" });
                        result["reason"] = json!(reason);
                        result["executed"] = json!(true);
                        result["query"] = json!(prepared.query);
                        result["parameters"] = prepared.parameters;
                        result["expected"] = prepared.expected;
                        result["actual"] = actual;
                        result["ordered"] = json!(prepared.ordered);
                    }
                },
            }
            results.push(result);
        }
        if let Err(error) = probe.close() {
            infrastructure_errors.push(error.to_string());
        }
    }
    for scenario in inventory.iter().skip(results.len()) {
        let mut result = Value::Object(identity(scenario));
        result["status"] = json!("not_run");
        result["reason"] = json!("probe infrastructure failure");
        result["executed"] = json!(false);
        results.push(result);
    }
    Ok(Run {
        results,
        infrastructure_errors,
    })
}

pub fn baseline_signature(results: &[Value]) -> Value {
    let mut signature = Map::new();
    for result in results {
        signature.insert(result["id"].as_str().unwrap().to_owned(), json!({"scenarioSha256":result["scenarioSha256"],"status":result["status"],"outcomeSha256":crate::digest(&json!({"reason":result["reason"],"actual":result["actual"]}))}));
    }
    Value::Object(signature)
}

pub fn baseline_changes(expected: &Value, actual: &Value) -> Vec<Value> {
    let keys: BTreeSet<&String> = expected
        .as_object()
        .unwrap()
        .keys()
        .chain(actual.as_object().unwrap().keys())
        .collect();
    keys.into_iter()
        .filter(|key| expected.get(*key) != actual.get(*key))
        .map(|key| json!({"id":key,"expected":expected.get(key),"actual":actual.get(key)}))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn expected_errors_require_native_type_phase_and_detail() {
        let expected = json!({"error":{"type":"SyntaxError","phase":"compile time","detail":"UnexpectedSyntax"}});
        let actual = json!({"columns":[],"rows":[],"error":"bad grammar","diagnostic":{"type":"SyntaxError","phase":"compile time","detail":"UnexpectedSyntax"}});
        assert_eq!(compare(&actual, &expected, false), None);
        for (key, value) in [
            ("type", "TypeError"),
            ("phase", "runtime"),
            ("detail", "UndefinedVariable"),
        ] {
            let mut wrong = actual.clone();
            wrong["diagnostic"][key] = json!(value);
            assert!(compare(&wrong, &expected, false).is_some());
        }
        assert!(compare(
            &json!({"columns":[],"rows":[],"error":"bad grammar"}),
            &expected,
            false
        )
        .is_some());
        assert!(compare(
            &json!({"columns":[],"rows":[],"error":null}),
            &expected,
            false
        )
        .is_some());
    }

    #[test]
    fn native_error_scenarios_do_not_require_a_success_result_or_no_side_effects_step() {
        let scenario = json!({"steps":[{"text":"an empty graph"},{"text":"executing query:","argument":{"docString":{"content":"RETURN ("}}},{"text":"a SyntaxError should be raised at compile time: UnexpectedSyntax"}]});
        let prepared = prepare(&scenario).unwrap();
        assert_eq!(prepared.query, "RETURN (");
        assert_eq!(
            prepared.expected["error"],
            json!({"type":"SyntaxError","phase":"compile time","detail":"UnexpectedSyntax"})
        );
    }

    #[test]
    fn list_order_ignoring_is_explicit_recursive_and_preserves_multiplicity() {
        let expected = json!({"columns":["v"],"rows":[[[1,2,2]]],"ignoreListOrder":true});
        assert_eq!(
            compare(
                &json!({"columns":["v"],"rows":[[[2,1,2]]],"error":null}),
                &expected,
                false
            ),
            None
        );
        assert!(compare(
            &json!({"columns":["v"],"rows":[[[1,2]]],"error":null}),
            &expected,
            false
        )
        .is_some());
        let strict = json!({"columns":["v"],"rows":[[[1,2,2]]]});
        assert!(compare(
            &json!({"columns":["v"],"rows":[[[2,1,2]]],"error":null}),
            &strict,
            false
        )
        .is_some());
    }

    #[test]
    fn unordered_comparison_preserves_multiplicity_and_types() {
        let expected = json!({"columns":["v"],"rows":[[null],[1],[1]]});
        assert_eq!(
            compare(
                &json!({"columns":["v"],"rows":[[1],[null],[1]],"error":null}),
                &expected,
                false
            ),
            None
        );
        for rows in [
            json!([[1], [null]]),
            json!([[1.0], [null], [1]]),
            json!([[true], [null], [1]]),
        ] {
            assert_eq!(
                compare(
                    &json!({"columns":["v"],"rows":rows,"error":null}),
                    &expected,
                    false
                ),
                Some("full result mismatch".to_owned())
            );
        }
        assert!(compare(
            &json!({"columns":["v"],"rows":[[1],[null],[1]],"error":null}),
            &expected,
            true
        )
        .is_some());
        assert!(compare(
            &json!({"columns":["wrong"],"rows":expected["rows"],"error":null}),
            &expected,
            false
        )
        .is_some());
    }
    #[test]
    fn errors_cannot_satisfy_empty_results_or_become_passes() {
        assert!(compare(
            &json!({"columns":[],"rows":[],"error":"boom"}),
            &json!({"empty":true}),
            false
        )
        .is_some());
        assert!(prepare(&json!({"steps":[{"text":"having executed:"}]}))
            .unwrap_err()
            .contains("step adapter missing"));
    }
    #[test]
    fn baseline_rejects_removal_new_pass_and_changed_failure() {
        let result = json!({"id":"case","scenarioSha256":"sha","status":"failed","reason":"mismatch","actual":{"rows":[[1]]}});
        let expected = baseline_signature(std::slice::from_ref(&result));
        assert!(baseline_changes(&expected, &expected).is_empty());
        assert!(!baseline_changes(&expected, &baseline_signature(&[])).is_empty());
        let mut changed = result.clone();
        changed["status"] = json!("passed");
        assert!(!baseline_changes(&expected, &baseline_signature(&[changed])).is_empty());
        let mut changed = result;
        changed["actual"] = json!({"rows":[[2]]});
        assert!(!baseline_changes(&expected, &baseline_signature(&[changed])).is_empty());
    }
    #[test]
    fn invalid_probe_protocol_is_fatal() {
        for result in [
            json!({"columns":["x"],"rows":[[]],"error":null}),
            json!({"columns":[1],"rows":[],"error":null}),
            json!({"columns":[],"rows":[]}),
        ] {
            assert!(validate_response(&result).is_err());
        }
    }

    #[cfg(unix)]
    fn scripted_probe(script: &str) -> (tempfile::TempDir, std::path::PathBuf) {
        use std::os::unix::fs::PermissionsExt;
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("probe");
        std::fs::write(&path, format!("#!/bin/sh\n{script}\n")).unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o700)).unwrap();
        (directory, path)
    }

    #[cfg(unix)]
    fn simple_scenario(id: &str) -> Value {
        json!({"id":id,"feature":"probe.feature","name":id,"scenarioSha256":"sha","steps":[
            {"text":"an empty graph"},
            {"text":"executing query:","argument":{"docString":{"content":"RETURN 1 AS value"}}},
            {"text":"the result should be, in order:","argument":{"dataTable":{"rows":[
                {"cells":[{"value":"value"}]}, {"cells":[{"value":"1"}]}
            ]}}},
            {"text":"no side effects"}
        ]})
    }

    #[test]
    #[cfg(unix)]
    fn malformed_child_json_and_row_schema_are_infrastructure_failures() {
        for (reply, message) in [
            ("not JSON", "invalid probe JSON"),
            (
                r#"{"columns":["value"],"rows":[[]],"error":null}"#,
                "rows must match column width",
            ),
        ] {
            let (_directory, binary) =
                scripted_probe(&format!("IFS= read -r request\nprintf '%s\\n' '{reply}'"));
            let run = run_inventory(
                vec![simple_scenario("first"), simple_scenario("second")],
                &binary,
            )
            .unwrap();
            assert!(
                run.infrastructure_errors
                    .iter()
                    .any(|error| error.contains(message)),
                "{:?}",
                run.infrastructure_errors
            );
            assert_eq!(
                run.results
                    .iter()
                    .map(|result| result["id"].as_str().unwrap())
                    .collect::<Vec<_>>(),
                vec!["first", "second"]
            );
            assert!(run
                .results
                .iter()
                .all(|result| result["status"] == "not_run" && result["executed"] == false));
        }
    }

    #[test]
    #[cfg(unix)]
    fn child_crash_keeps_completed_result_and_marks_remaining_inventory_not_run() {
        let (_directory, binary) = scripted_probe(
            r#"IFS= read -r request
printf '%s\n' '{"columns":["value"],"rows":[[1]],"error":null}'
IFS= read -r request
exit 7"#,
        );
        let run = run_inventory(
            vec![
                simple_scenario("first"),
                simple_scenario("second"),
                simple_scenario("third"),
            ],
            &binary,
        )
        .unwrap();
        assert_eq!(
            run.results
                .iter()
                .map(|result| result["status"].as_str().unwrap())
                .collect::<Vec<_>>(),
            vec!["passed", "not_run", "not_run"]
        );
        assert_eq!(run.results[0]["actual"]["rows"], json!([[1]]));
        assert_eq!(run.results[1]["id"], "second");
        assert_eq!(run.results[2]["id"], "third");
        assert!(run
            .infrastructure_errors
            .iter()
            .any(|error| error.contains("probe exited unexpectedly")));
        assert!(run
            .infrastructure_errors
            .iter()
            .any(|error| error.contains("probe exited unsuccessfully")));
    }

    #[test]
    #[cfg(unix)]
    fn nonzero_exit_after_last_response_still_records_infrastructure_failure() {
        let (_directory, binary) = scripted_probe(
            r#"IFS= read -r request
printf '%s\n' '{"columns":["value"],"rows":[[1]],"error":null}'
exit 9"#,
        );
        let run = run_inventory(vec![simple_scenario("first")], &binary).unwrap();
        assert_eq!(run.results[0]["status"], "passed");
        assert!(run
            .infrastructure_errors
            .iter()
            .any(|error| error.contains("probe exited unsuccessfully")));
    }

    #[test]
    #[cfg(unix)]
    fn silent_child_hits_response_deadline_and_is_closed() {
        let (_directory, binary) =
            scripted_probe("IFS= read -r request\nIFS= read -r request\nexit 0");
        let mut probe = Probe::new(&binary).unwrap();
        let error =
            match probe.execute_with_timeout("RETURN 1", &json!({}), Duration::from_millis(20)) {
                Err(error) => error,
                Ok(_) => panic!("silent probe unexpectedly replied"),
            };
        assert!(error.to_string().contains("probe response timeout"));
        probe.close().unwrap();
    }
}

//! `graphite build --fold <file>`: resolving the file's `select` rules.
//!
//! A `match` rule names call sites by their properties and the frontend applies it on its
//! own. A `select` rule names them by a Cypher query, which needs a graph to run on; the
//! frontend cannot build the graph it is folding and query it at the same time. The shell
//! therefore builds twice when the file has a `select` rule: once without rules into a
//! staging directory, where it runs every query with the Rust engine and reads the stable
//! key of each `CallSite` row (`caller_signature`, `callee_signature`, `ordinal`), and
//! once more with the keys written into the plan as `selected`, into the output the user
//! named. The frontend validates the file (`graphite.jar fold plan`), so an error in it
//! reads exactly as it does from `build --fold`, whatever the file was written in.

use crate::frontend::{self, Env, Frontend, Launch};
use graphite_cypher::engine::Executor;
use graphite_cypher::value::Value;
use graphite_storage::node::NodeKind;
use serde_json::{json, Value as J};
use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::process::Command;

/// The name of the resolved plan inside the staging directory.
pub const RESOLVED_PLAN: &str = "graph.folds.plan.json";

/// The stable key of one call site, as the fold file spells it.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct CallSiteKey {
    pub caller: String,
    pub callee: String,
    pub ordinal: i32,
}

impl CallSiteKey {
    fn to_json(&self) -> J {
        json!({
            "caller_signature": self.caller,
            "callee_signature": self.callee,
            "ordinal": self.ordinal,
        })
    }
}

/// The `--fold <file>` or `--fold=<file>` of a build command line, if any.
pub fn fold_file(args: &[OsString]) -> Option<PathBuf> {
    let mut iter = args.iter();
    while let Some(arg) = iter.next() {
        let text = arg.to_string_lossy();
        if text == "--fold" {
            return iter.next().map(PathBuf::from);
        }
        if let Some(value) = text.strip_prefix("--fold=") {
            return Some(PathBuf::from(value));
        }
    }
    None
}

/// The arguments without `--fold <file>`, `--fold=<file>` and `--fold-strict`: the first
/// build, which must see no rule at all.
pub fn without_fold(args: &[OsString]) -> Vec<OsString> {
    let mut out = Vec::with_capacity(args.len());
    let mut skip_value = false;
    for arg in args {
        if skip_value {
            skip_value = false;
            continue;
        }
        let text = arg.to_string_lossy();
        if text == "--fold" {
            skip_value = true;
            continue;
        }
        if text.starts_with("--fold=") || text == "--fold-strict" {
            continue;
        }
        out.push(arg.clone());
    }
    out
}

/// The arguments with the fold file replaced by `file`, in whichever form it was given.
pub fn with_fold_file(args: &[OsString], file: &Path) -> Vec<OsString> {
    let mut out = Vec::with_capacity(args.len());
    let mut replace_next = false;
    for arg in args {
        if replace_next {
            replace_next = false;
            out.push(file.as_os_str().to_os_string());
            continue;
        }
        let text = arg.to_string_lossy();
        if text == "--fold" {
            replace_next = true;
            out.push(arg.clone());
        } else if text.starts_with("--fold=") {
            let mut rewritten = OsString::from("--fold=");
            rewritten.push(file.as_os_str());
            out.push(rewritten);
        } else {
            out.push(arg.clone());
        }
    }
    out
}

/// The value of `-o`, `--output` or `--output=`, if any.
pub fn output_of(args: &[OsString]) -> Option<PathBuf> {
    let mut iter = args.iter();
    while let Some(arg) = iter.next() {
        let text = arg.to_string_lossy();
        if text == "-o" || text == "--output" {
            return iter.next().map(PathBuf::from);
        }
        if let Some(value) = text.strip_prefix("--output=") {
            return Some(PathBuf::from(value));
        }
    }
    None
}

/// The arguments with the output replaced by `output`, in whichever form it was given.
pub fn with_output(args: &[OsString], output: &Path) -> Vec<OsString> {
    let mut out = Vec::with_capacity(args.len());
    let mut replace_next = false;
    for arg in args {
        if replace_next {
            replace_next = false;
            out.push(output.as_os_str().to_os_string());
            continue;
        }
        let text = arg.to_string_lossy();
        if text == "-o" || text == "--output" {
            replace_next = true;
            out.push(arg.clone());
        } else if text.starts_with("--output=") {
            let mut rewritten = OsString::from("--output=");
            rewritten.push(output.as_os_str());
            out.push(rewritten);
        } else {
            out.push(arg.clone());
        }
    }
    out
}

/// Where the first build writes the graph built without rules: a sibling of the output,
/// named after it and this process, removed once the second build has run.
pub fn unfolded_dir_for(output: &Path) -> PathBuf {
    let name = output.file_name().unwrap_or_default().to_string_lossy();
    output.with_file_name(format!("{name}.unfolded-{}", std::process::id()))
}

/// The command that validates `file` and prints its rules as JSON: `fold plan <file>` on
/// the frontend. The JVM reads one small file; it needs no 8 GiB reservation.
pub fn plan_command(env: &Env, fe: &Frontend, file: &Path) -> Result<Command, String> {
    let mut cmd = match &fe.launch {
        Launch::Jar(jar) => {
            let mut c = Command::new(frontend::locate_java(env)?);
            c.arg("-jar").arg(jar);
            c
        }
        Launch::Executable(exe) => Command::new(exe),
    };
    cmd.arg("fold").arg("plan").arg(file);
    cmd.env("JAVA_TOOL_OPTIONS", "-Xmx256m");
    Ok(cmd)
}

/// Run `fold plan` and parse its JSON. `Err(code)` when the frontend rejected the file:
/// its message is already on stderr, and the build exits with the frontend's code.
pub fn plan(env: &Env, fe: &Frontend, file: &Path) -> Result<J, i32> {
    let mut cmd = plan_command(env, fe, file).map_err(|message| {
        eprintln!("Error: {message}");
        1
    })?;
    let output = cmd
        .stderr(std::process::Stdio::inherit())
        .output()
        .map_err(|e| {
            eprintln!(
                "Error: could not run the frontend to read {}: {e}",
                file.display()
            );
            1
        })?;
    if !output.status.success() {
        return Err(output.status.code().unwrap_or(1));
    }
    serde_json::from_slice(&output.stdout).map_err(|e| {
        eprintln!(
            "Error: the frontend's plan for {} is not JSON: {e}",
            file.display()
        );
        1
    })
}

/// The `folds` of a plan.
fn folds(plan: &J) -> &[J] {
    plan.get("folds")
        .and_then(J::as_array)
        .map(Vec::as_slice)
        .unwrap_or(&[])
}

/// The index and query of every `select` rule the frontend has not resolved.
pub fn selects(plan: &J) -> Vec<(usize, String)> {
    folds(plan)
        .iter()
        .enumerate()
        .filter(|(_, fold)| fold.get("selected").is_none_or(J::is_null))
        .filter_map(|(index, fold)| {
            fold.get("select")
                .and_then(J::as_str)
                .map(|query| (index, query.to_string()))
        })
        .collect()
}

/// The plan with `selected` filled in for the rules at the given indexes.
pub fn resolve_plan(mut plan: J, selections: &[(usize, Vec<CallSiteKey>)]) -> J {
    if let Some(folds) = plan.get_mut("folds").and_then(J::as_array_mut) {
        for (index, keys) in selections {
            if let Some(J::Object(fold)) = folds.get_mut(*index) {
                fold.insert(
                    "selected".to_string(),
                    J::Array(keys.iter().map(CallSiteKey::to_json).collect()),
                );
            }
        }
    }
    plan
}

/// The call sites `query` selects on the graph behind `executor`: every row must have one
/// column holding a `CallSite` node, and every node its ordinal. Duplicates are kept once,
/// in the order first seen.
pub fn select_sites(
    executor: &Executor,
    index: usize,
    query: &str,
) -> Result<Vec<CallSiteKey>, String> {
    let result = executor
        .execute(query, None)
        .map_err(|e| format!("select rule {index}: the query failed: {e}"))?;
    let column = match result.columns.as_slice() {
        [column] => column.clone(),
        columns => {
            return Err(format!(
                "select rule {index}: the query must return one column holding CallSite nodes (RETURN cs), \
                 it returns {}: {}",
                columns.len(),
                columns.join(", ")
            ))
        }
    };
    let mut keys = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for (row_index, row) in result.rows.iter().enumerate() {
        let value = row.get(&column).cloned().unwrap_or(Value::Null);
        let node = match &value {
            Value::Node(r) => executor.node(*r),
            _ => None,
        };
        let Some(node) = node else {
            return Err(format!(
                "select rule {index}: row {row_index} of '{column}' is not a node but {}; \
                 return the CallSite node itself (RETURN cs), not a property of it",
                describe(&value)
            ));
        };
        let Value::Node(r) = value else {
            unreachable!()
        };
        let strings = &executor.graph(r.source).strings;
        let NodeKind::CallSite {
            caller,
            callee,
            ordinal,
            ..
        } = &node.kind
        else {
            return Err(format!(
                "select rule {index}: row {row_index} of '{column}' is a {} node, not a CallSite",
                graphite_storage::node::tag_type_name(node.tag())
            ));
        };
        let Some(ordinal) = ordinal else {
            return Err(format!(
                "select rule {index}: the graph has no call-site ordinals (built by an older frontend); \
                 rebuild it with this release"
            ));
        };
        let key = CallSiteKey {
            caller: caller.signature(strings),
            callee: callee.signature(strings),
            ordinal: *ordinal,
        };
        if key.ordinal < 0 {
            // A derived call site is a function value's body resolved from another call; no
            // bytecode invoke carries it, so a plan naming it would fold nothing, silently.
            return Err(format!(
                "select rule {index}: row {row_index} of '{column}' is a derived call site \
                 ({} -> {} #{}), a function value's body reached from another call; no bytecode \
                 invoke carries it, select the call it was resolved from",
                key.caller, key.callee, key.ordinal
            ));
        }
        if seen.insert(key.clone()) {
            keys.push(key);
        }
    }
    Ok(keys)
}

fn describe(value: &Value) -> String {
    match value {
        Value::Null => "null".to_string(),
        Value::Bool(b) => format!("the boolean {b}"),
        Value::Int(i) => format!("the number {i}"),
        Value::Float(f) => format!("the number {f}"),
        Value::Float32(f) => format!("the number {f}"),
        Value::Str(s) => format!("the string {s:?}"),
        Value::List(_) => "a list".to_string(),
        Value::Map(_) => "a map".to_string(),
        Value::Node(_) => "a node".to_string(),
        Value::Rel(_) => "a relationship".to_string(),
        Value::Path(_) => "a path".to_string(),
        Value::Method(_) => "a method".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn os(args: &[&str]) -> Vec<OsString> {
        args.iter().map(OsString::from).collect()
    }

    fn text(args: &[OsString]) -> Vec<String> {
        args.iter()
            .map(|a| a.to_string_lossy().into_owned())
            .collect()
    }

    #[test]
    fn the_fold_file_is_found_in_either_form_and_removed_with_strict() {
        let spaced = os(&[
            "a.jar",
            "-o",
            "/g",
            "--fold",
            "f.yml",
            "--fold-strict",
            "--include",
            "x",
        ]);
        let joined = os(&["a.jar", "--fold=f.yml", "-o", "/g"]);
        assert_eq!(fold_file(&spaced), Some(PathBuf::from("f.yml")));
        assert_eq!(fold_file(&joined), Some(PathBuf::from("f.yml")));
        assert_eq!(fold_file(&os(&["a.jar", "-o", "/g"])), None);
        assert_eq!(
            text(&without_fold(&spaced)),
            ["a.jar", "-o", "/g", "--include", "x"]
        );
        assert_eq!(text(&without_fold(&joined)), ["a.jar", "-o", "/g"]);
        assert_eq!(
            text(&with_fold_file(&spaced, Path::new("/s/plan.json"))),
            [
                "a.jar",
                "-o",
                "/g",
                "--fold",
                "/s/plan.json",
                "--fold-strict",
                "--include",
                "x"
            ]
        );
        assert_eq!(
            text(&with_fold_file(&joined, Path::new("/s/plan.json"))),
            ["a.jar", "--fold=/s/plan.json", "-o", "/g"]
        );
    }

    #[test]
    fn the_output_is_found_and_redirected_in_every_form() {
        for form in [
            os(&["a.jar", "-o", "/out/app", "--fold", "f"]),
            os(&["a.jar", "--output", "/out/app"]),
            os(&["a.jar", "--output=/out/app"]),
        ] {
            assert_eq!(output_of(&form), Some(PathBuf::from("/out/app")));
            let redirected = with_output(&form, Path::new("/out/app.unfolded-1"));
            assert_eq!(
                output_of(&redirected),
                Some(PathBuf::from("/out/app.unfolded-1"))
            );
            assert_eq!(redirected.len(), form.len());
        }
        assert_eq!(output_of(&os(&["a.jar"])), None);
        let stage = unfolded_dir_for(Path::new("/out/app.graphite"));
        assert_eq!(stage.parent(), Some(Path::new("/out")));
        assert!(stage
            .file_name()
            .unwrap()
            .to_string_lossy()
            .starts_with("app.graphite.unfolded-"));
    }

    #[test]
    fn select_rules_are_listed_and_resolved_in_place() {
        let plan = json!({
            "version": 1,
            "folds": [
                {"match": {"CallSite": {"callee_name": "on"}}, "args": {}, "value": false, "frontend": "jvm"},
                {"select": "MATCH (cs:CallSite) RETURN cs", "selected": null, "value": 1, "frontend": "jvm"},
                {"select": "MATCH (cs:CallSite {callee_name: 'x'}) RETURN cs", "selected": [], "value": 2, "frontend": "jvm"}
            ]
        });
        assert_eq!(
            selects(&plan),
            vec![(1, "MATCH (cs:CallSite) RETURN cs".to_string())]
        );
        assert!(selects(&json!({"version": 1, "folds": [{"match": {}, "value": 1}]})).is_empty());
        assert!(selects(&json!({})).is_empty());
        let key = CallSiteKey {
            caller: "a.B.run()".into(),
            callee: "a.Flags.on(java.lang.String)".into(),
            ordinal: 2,
        };
        let resolved = resolve_plan(plan, &[(1, vec![key])]);
        assert_eq!(
            resolved["folds"][1]["selected"],
            json!([{"caller_signature": "a.B.run()", "callee_signature": "a.Flags.on(java.lang.String)", "ordinal": 2}])
        );
        assert_eq!(resolved["folds"][2]["selected"], json!([]));
        assert!(selects(&resolved).is_empty());
    }

    #[test]
    fn the_plan_command_runs_fold_plan_on_the_frontend() {
        let mut env = Env::default();
        env.vars
            .insert("GRAPHITE_JAVA".to_string(), OsString::from("/usr/bin/java"));
        let jar = Frontend {
            lang: "jvm",
            launch: Launch::Jar(PathBuf::from("/opt/graphite.jar")),
            found_via: "test",
        };
        let cmd = plan_command(&env, &jar, Path::new("folds.yml")).unwrap();
        assert_eq!(cmd.get_program(), "/usr/bin/java");
        let args: Vec<String> = cmd
            .get_args()
            .map(|a| a.to_string_lossy().into_owned())
            .collect();
        assert_eq!(
            args,
            ["-jar", "/opt/graphite.jar", "fold", "plan", "folds.yml"]
        );
        let exe = Frontend {
            lang: "jvm",
            launch: Launch::Executable(PathBuf::from("/usr/local/bin/graphite-frontend-jvm")),
            found_via: "test",
        };
        let cmd = plan_command(&env, &exe, Path::new("folds.yml")).unwrap();
        let args: Vec<String> = cmd
            .get_args()
            .map(|a| a.to_string_lossy().into_owned())
            .collect();
        assert_eq!(args, ["fold", "plan", "folds.yml"]);
    }

    /// Against the real fixture graph CI builds: every CallSite row yields its key, a
    /// scalar column is refused with a message that says what to return instead.
    #[test]
    fn select_sites_reads_call_site_keys_off_a_real_graph() {
        let Some(dir) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
            return;
        };
        let graph = graphite_storage::Graph::load(Path::new(&dir)).expect("fixture graph");
        let executor = Executor::single("fixture", std::sync::Arc::new(graph));
        let keys = select_sites(
            &executor,
            0,
            "MATCH (cs:CallSite) WHERE cs.callee_name = 'add' RETURN cs LIMIT 20",
        )
        .unwrap();
        assert!(!keys.is_empty());
        assert!(keys.iter().all(|k| k.callee.contains(".add(")), "{keys:?}");
        assert!(keys.iter().all(|k| k.ordinal >= 0), "{keys:?}");
        // A derived call site (a lambda body reached through a function value) is refused,
        // not written into the plan as a key the fold pass can never match.
        let derived = select_sites(
            &executor,
            6,
            "MATCH (cs:CallSite) WHERE cs.ordinal < 0 RETURN cs LIMIT 1",
        );
        match derived {
            Ok(keys) => assert!(
                keys.is_empty(),
                "the fixture has no derived call site: {keys:?}"
            ),
            Err(message) => assert!(message.contains("is a derived call site"), "{message}"),
        }
        let distinct: std::collections::HashSet<_> = keys.iter().collect();
        assert_eq!(distinct.len(), keys.len());
        let scalar = select_sites(
            &executor,
            3,
            "MATCH (cs:CallSite) RETURN cs.callee_name LIMIT 1",
        );
        assert!(scalar
            .unwrap_err()
            .contains("select rule 3: row 0 of 'cs.callee_name' is not a node but the string"));
        let two = select_sites(
            &executor,
            4,
            "MATCH (cs:CallSite) RETURN cs, cs.ordinal LIMIT 1",
        );
        assert!(two.unwrap_err().contains("it returns 2"));
        let constant = select_sites(&executor, 5, "MATCH (c:StringConstant) RETURN c LIMIT 1");
        assert!(constant
            .unwrap_err()
            .contains("is a StringConstant node, not a CallSite"));
    }
}

//! `graphite build --fold <file>`: resolving the file's `select` rules.
//!
//! A `match` rule names call sites by their properties and the frontend applies it on its
//! own. A `select` rule names them by a Cypher query, which needs a graph to run on; the
//! frontend cannot build the graph it is folding and query it at the same time. The shell
//! therefore builds twice when the file has a `select` rule: once without rules into a
//! staging directory, where it runs every query with the Rust engine and reads the stable
//! key of each `CallSite` row (`caller_signature`, `caller_descriptor`, `callee_signature`,
//! `callee_descriptor`, `ordinal`), and once more with the keys written into the plan as
//! `selected`, into the output the user named. The frontend validates the file
//! (`graphite.jar fold plan`), so an error in it reads exactly as it does from `build
//! --fold`, whatever the file was written in.
//!
//! The staging build links every call to its callee's body (`--interprocedural`), so a
//! query can follow a key through a helper's parameter and back out of its return; the
//! graph the user named is built without those edges. The plan carries the provenance of
//! the input (its SHA-256 and the frontend's version), which every resolved rule records:
//! a key names one invoke only in the bytecode it was read from, and the frontend hands a
//! rule resolved on another input back unresolved, so its query runs again.

use crate::frontend::{self, Env, Frontend, Launch};
use graphite_cypher::engine::Executor;
use graphite_cypher::value::Value;
use graphite_storage::node::NodeKind;
use serde_json::{json, Value as J};
use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::process::Command;

/// The name of the resolved plan inside the staging directory.
pub const RESOLVED_PLAN: &str = "graph.folds.plan.json";

/// The stable key of one call site, as the fold file spells it.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct CallSiteKey {
    pub caller: String,
    pub caller_descriptor: String,
    pub callee: String,
    pub callee_descriptor: String,
    pub ordinal: i32,
}

/// One call site a `select` rule folds: its key and, when its callee returns an erased
/// `java.lang.Object` (`Supplier.get`, `Function1.invoke`), the type the function values
/// it calls really return, which the frontend boxes the constant to.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Selected {
    pub key: CallSiteKey,
    pub result_type: Option<String>,
}

impl Selected {
    fn to_json(&self) -> J {
        let mut key = json!({
            "caller_signature": self.key.caller,
            "caller_descriptor": self.key.caller_descriptor,
            "callee_signature": self.key.callee,
            "callee_descriptor": self.key.callee_descriptor,
            "ordinal": self.key.ordinal,
        });
        if let Some(result_type) = &self.result_type {
            key["result_type"] = J::String(result_type.clone());
        }
        key
    }
}

/// Options of `graphite.jar build` that take a value, to tell the input apart from them.
const VALUE_OPTIONS: [&str; 7] = [
    "-o",
    "--output",
    "--include",
    "--exclude",
    "--android-sdk",
    "--lib-filter",
    "--fold",
];

/// The option that links calls to their callees' bodies, which the staging build adds.
pub const INTERPROCEDURAL: &str = "--interprocedural";

/// The options that decide which classes a build reads, and so which calls its graph has:
/// `fold plan` takes the same ones, so the plan's provenance names the build the keys are for.
const ANALYSIS_VALUE_OPTIONS: [&str; 4] =
    ["--include", "--exclude", "--lib-filter", "--android-sdk"];
const ANALYSIS_FLAGS: [&str; 1] = ["--include-libs"];

/// The analysis options of a build command line, as given (`--include a,b`, `--include=a,b`
/// and `--include-libs` alike), in order.
pub fn analysis_options(args: &[OsString]) -> Vec<OsString> {
    let mut out = Vec::new();
    let mut iter = args.iter();
    while let Some(arg) = iter.next() {
        let text = arg.to_string_lossy();
        if ANALYSIS_FLAGS.contains(&text.as_ref()) {
            out.push(arg.clone());
        } else if ANALYSIS_VALUE_OPTIONS.contains(&text.as_ref()) {
            out.push(arg.clone());
            if let Some(value) = iter.next() {
                out.push(value.clone());
            }
        } else if ANALYSIS_VALUE_OPTIONS
            .iter()
            .any(|option| text.starts_with(option) && text[option.len()..].starts_with('='))
        {
            out.push(arg.clone());
        } else if !text.contains('=') && VALUE_OPTIONS.contains(&text.as_ref()) {
            iter.next();
        }
    }
    out
}

/// The input of a build command line: its first argument that is neither an option nor
/// an option's value.
pub fn input_of(args: &[OsString]) -> Option<PathBuf> {
    let mut iter = args.iter();
    while let Some(arg) = iter.next() {
        let text = arg.to_string_lossy();
        if text.starts_with('-') {
            if !text.contains('=') && VALUE_OPTIONS.contains(&text.as_ref()) {
                iter.next();
            }
            continue;
        }
        return Some(PathBuf::from(arg));
    }
    None
}

/// The first build's arguments: no rules, the staging directory as output, and calls
/// linked to their callees' bodies, so a `select` query can cross a helper.
pub fn staging_args(args: &[OsString], stage: &Path) -> Vec<OsString> {
    let mut out = with_output(&without_fold(args), stage);
    if !out.iter().any(|a| a == INTERPROCEDURAL) {
        out.push(OsString::from(INTERPROCEDURAL));
    }
    out
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
/// the frontend, for a build of `input` when there is one. The JVM reads one small file
/// and hashes the input; it needs no 8 GiB reservation.
pub fn plan_command(
    env: &Env,
    fe: &Frontend,
    file: &Path,
    input: Option<&Path>,
    analysis: &[OsString],
) -> Result<Command, String> {
    let mut cmd = match &fe.launch {
        Launch::Jar(jar) => {
            let mut c = Command::new(frontend::locate_java(env)?);
            c.arg("-jar").arg(jar);
            c
        }
        Launch::Executable(exe) => Command::new(exe),
    };
    cmd.arg("fold").arg("plan").arg(file);
    if let Some(input) = input {
        cmd.arg("--input").arg(input);
        cmd.args(analysis);
    }
    cmd.env("JAVA_TOOL_OPTIONS", "-Xmx256m");
    Ok(cmd)
}

/// Run `fold plan` and parse its JSON. `Err(code)` when the frontend rejected the file:
/// its message is already on stderr, and the build exits with the frontend's code.
pub fn plan(
    env: &Env,
    fe: &Frontend,
    file: &Path,
    input: Option<&Path>,
    analysis: &[OsString],
) -> Result<J, i32> {
    let mut cmd = plan_command(env, fe, file, input, analysis).map_err(|message| {
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

/// The plan with `selected` filled in for the rules at the given indexes, each with the
/// plan's `provenance`, the input and frontend the keys were read off.
pub fn resolve_plan(mut plan: J, selections: &[(usize, Vec<Selected>)]) -> J {
    let provenance = plan.get("provenance").cloned().unwrap_or(J::Null);
    if let Some(folds) = plan.get_mut("folds").and_then(J::as_array_mut) {
        for (index, sites) in selections {
            if let Some(J::Object(fold)) = folds.get_mut(*index) {
                fold.insert(
                    "selected".to_string(),
                    J::Array(sites.iter().map(Selected::to_json).collect()),
                );
                fold.insert("provenance".to_string(), provenance.clone());
            }
        }
    }
    plan
}

/// The call sites `query` selects on the graph behind `executor`: every row must have one
/// column holding a `CallSite` node, and every node its ordinal. A derived call site (a
/// call on a function value resolved to the lambda body it holds) is no invoke of its own
/// and folds as the call it was resolved from, the `get`, `apply` or `invoke` on the
/// value. A derived row with no such call (a lambda body reached where the lambda is
/// created, or a method a function object implements) is no call at all and is skipped
/// with a note: a query naming a lambda body matches its creation too. Duplicates are kept
/// once, in the order first seen.
pub fn select_sites(
    executor: &Executor,
    index: usize,
    query: &str,
) -> Result<Vec<Selected>, String> {
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
    let mut sites: Vec<Selected> = Vec::new();
    let mut skipped = 0usize;
    // The calls the query named themselves, and, per call named only through the lambda
    // bodies it runs, which of those bodies the query selected.
    let mut named: HashSet<(u32, u32)> = HashSet::new();
    let mut through_bodies: BTreeMap<(u32, u32), BTreeSet<u32>> = BTreeMap::new();
    for (row_index, row) in result.rows.iter().enumerate() {
        let value = row.get(&column).cloned().unwrap_or(Value::Null);
        let Value::Node(r) = value else {
            return Err(format!(
                "select rule {index}: row {row_index} of '{column}' is not a node but {}; \
                 return the CallSite node itself (RETURN cs), not a property of it",
                describe(&value)
            ));
        };
        let graph = executor.graph(r.source);
        let site = selected_site(graph, r.id)
            .map_err(|why| format!("select rule {index}: row {row_index} of '{column}' {why}"))?;
        let Some(site) = site else {
            skipped += 1;
            continue;
        };
        match site.derived_from {
            None => {
                named.insert((r.source, r.id));
            }
            Some(origin) => {
                // The row covers the bodies beneath it: a derived site that is itself an
                // origin (`Function::apply` resolved on the way to the body) covers them all.
                through_bodies
                    .entry((r.source, origin))
                    .or_default()
                    .extend(leaves(graph, r.id));
            }
        }
        // A call's result type is read off the graph alone, so one key never carries two.
        if !sites.iter().any(|s| s.key == site.selected.key) {
            sites.push(site.selected);
        }
    }
    if skipped > 0 {
        eprintln!(
            "select rule {index}: skipped {skipped} derived row(s) that are no call: a lambda body \
             reached where the lambda is created, or a method a function object implements"
        );
    }
    // A call on a function value folds for every value it runs, so a query that selected
    // some of the bodies it runs, and not the call itself, asked for a narrower rewrite
    // than the call can carry: it is refused rather than widened to the other bodies.
    for ((source, origin), chosen) in &through_bodies {
        if named.contains(&(*source, *origin)) {
            continue;
        }
        let graph = executor.graph(*source);
        let unselected: Vec<String> = leaves(graph, *origin)
            .into_iter()
            .filter(|derived| !chosen.contains(derived))
            .filter_map(|derived| callee_of(graph, derived))
            .collect();
        if unselected.is_empty() {
            continue;
        }
        let (key, _) = key_of(graph, *origin).expect("an origin is a call site");
        return Err(format!(
            "select rule {index}: the query selected {} of the {} function values that the call \
             {} -> {} #{} runs, and folding that call would fold the other(s) too: {}. Select \
             every body the call runs, or the call itself, or a call that runs only the selected \
             body",
            chosen.len(),
            chosen.len() + unselected.len(),
            key.caller,
            key.callee,
            key.ordinal,
            unselected.join(", ")
        ));
    }
    Ok(sites)
}

/// The callee signature of call site `id`, for a message.
fn callee_of(graph: &graphite_storage::Graph, id: u32) -> Option<String> {
    match graph.node(id)?.kind {
        NodeKind::CallSite { callee, .. } => Some(callee.signature(graph.strings())),
        _ => None,
    }
}

/// A selected row: the key it folds as, and the call it was resolved from when the row
/// was a lambda body rather than a call.
struct Site {
    selected: Selected,
    derived_from: Option<u32>,
}

/// The call site a row's node `id` folds as, `None` for a derived call site that was
/// resolved from no call, or why it cannot be folded.
fn selected_site(graph: &graphite_storage::Graph, id: u32) -> Result<Option<Site>, String> {
    let node = graph
        .node(id)
        .ok_or_else(|| "is not a node of the graph".to_string())?;
    let NodeKind::CallSite { ordinal, .. } = &node.kind else {
        return Err(format!(
            "is a {} node, not a CallSite",
            graphite_storage::node::tag_type_name(node.tag())
        ));
    };
    let Some(ordinal) = ordinal else {
        return Err(
            "has no call-site ordinal: the graph was built by an older frontend; rebuild it with this release"
                .to_string(),
        );
    };
    // A derived call site has no bytecode invoke; it folds as the call it was resolved
    // from, if it was resolved from one. The origin may itself be derived (`Function::apply`
    // resolved on the way to the body), so the chain is followed to the bytecode call.
    let origin = match bytecode_origin(graph, id, *ordinal)? {
        Some(origin) => origin,
        None => return Ok(None),
    };
    let derived_from = (origin != id).then_some(origin);
    let (key, erased) = key_of(graph, origin).expect("an origin is a call site");
    let result_type = if erased {
        result_type_of(graph, origin, &key)?
    } else {
        None
    };
    Ok(Some(Site {
        selected: Selected { key, result_type },
        derived_from,
    }))
}

/// The bytecode call a call site `id` folds as: itself when its `ordinal` is not negative,
/// else the first call with a non-negative ordinal up its chain of origins; `None` when a
/// derived site in the chain was resolved from no call. A chain that loops is an error.
fn bytecode_origin(
    graph: &graphite_storage::Graph,
    id: u32,
    ordinal: i32,
) -> Result<Option<u32>, String> {
    let mut current = id;
    let mut current_ordinal = ordinal;
    let mut seen: HashSet<u32> = HashSet::new();
    while current_ordinal < 0 {
        if !seen.insert(current) {
            return Err(format!(
                "is a derived call site whose chain of origins loops back to node {current}"
            ));
        }
        let Some(origin) = graph.call_site_origin(current) else {
            return Ok(None);
        };
        let Some(NodeKind::CallSite { ordinal, .. }) = graph.node(origin).map(|n| n.kind) else {
            return Err(format!(
                "is a derived call site whose origin, node {origin}, is not a call site"
            ));
        };
        current_ordinal = ordinal.ok_or_else(|| {
            format!("is a derived call site whose origin, node {origin}, has no call-site ordinal")
        })?;
        current = origin;
    }
    Ok(Some(current))
}

/// The bodies beneath call site `id`: the derived call sites reached from it through
/// `call_sites_derived_from` that have no derived sites of their own, or `id` itself when
/// it has none. A derived site in between (`Function::apply` resolved on the way to the
/// body) is not a body.
fn leaves(graph: &graphite_storage::Graph, id: u32) -> BTreeSet<u32> {
    let mut out = BTreeSet::new();
    let mut seen: HashSet<u32> = HashSet::new();
    let mut stack = vec![id];
    while let Some(current) = stack.pop() {
        if !seen.insert(current) {
            continue;
        }
        let children: Vec<u32> = graph.call_sites_derived_from(current).collect();
        if children.is_empty() {
            out.insert(current);
        } else {
            stack.extend(children);
        }
    }
    out
}

/// The key of call site `id`, and whether its callee returns an erased `java.lang.Object`.
fn key_of(graph: &graphite_storage::Graph, id: u32) -> Option<(CallSiteKey, bool)> {
    let node = graph.node(id)?;
    let NodeKind::CallSite {
        caller,
        callee,
        ordinal,
        ..
    } = &node.kind
    else {
        return None;
    };
    let strings = graph.strings();
    let key = CallSiteKey {
        caller: caller.signature(strings),
        caller_descriptor: caller.descriptor(strings),
        callee: callee.signature(strings),
        callee_descriptor: callee.descriptor(strings),
        ordinal: (*ordinal)?,
    };
    let erased = strings.get(callee.return_type as usize) == "java.lang.Object";
    Some((key, erased))
}

/// What the function values a call on an erased callee runs really return: the return
/// type their bodies share, `None` when the call resolved to none, an error when they
/// differ, since one constant cannot be both.
fn result_type_of(
    graph: &graphite_storage::Graph,
    origin: u32,
    key: &CallSiteKey,
) -> Result<Option<String>, String> {
    let mut types: Vec<String> = Vec::new();
    for derived in leaves(graph, origin).into_iter().filter(|d| *d != origin) {
        if let Some(NodeKind::CallSite { callee, .. }) = graph.node(derived).map(|n| n.kind) {
            let name = graph.strings().get(callee.return_type as usize).to_string();
            if !types.contains(&name) {
                types.push(name);
            }
        }
    }
    match types.as_slice() {
        [] => Ok(None),
        [one] => Ok(Some(one.clone())),
        many => Err(format!(
            "is the call {} -> {} #{}, which runs function values returning {}; \
             one constant cannot stand for all of them",
            key.caller,
            key.callee,
            key.ordinal,
            many.join(" and ")
        )),
    }
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
    fn the_input_is_the_first_argument_that_is_no_option_and_staging_links_calls() {
        assert_eq!(
            input_of(&os(&[
                "-o",
                "/g",
                "--include",
                "x",
                "app.jar",
                "--fold",
                "f"
            ])),
            Some(PathBuf::from("app.jar"))
        );
        assert_eq!(
            input_of(&os(&["--output=/g", "-v", "--include-libs", "classes"])),
            Some(PathBuf::from("classes"))
        );
        assert_eq!(input_of(&os(&["-o", "/g"])), None);
        let staged = staging_args(
            &os(&["a.jar", "-o", "/g", "--fold", "f.yml", "--fold-strict"]),
            Path::new("/g.unfolded-1"),
        );
        assert_eq!(
            text(&staged),
            ["a.jar", "-o", "/g.unfolded-1", "--interprocedural"]
        );
        let already = staging_args(
            &os(&["a.jar", "--interprocedural", "-o", "/g"]),
            Path::new("/s"),
        );
        assert_eq!(text(&already), ["a.jar", "--interprocedural", "-o", "/s"]);
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
        let key = |callee: &str, descriptor: &str, ordinal| CallSiteKey {
            caller: "a.B.run()".into(),
            caller_descriptor: "()V".into(),
            callee: callee.into(),
            callee_descriptor: descriptor.into(),
            ordinal,
        };
        let mut plan = plan;
        let provenance = json!({"input_sha256": "ab".repeat(32), "frontend_version": "9.9.9"});
        plan["provenance"] = provenance.clone();
        let sites = vec![
            Selected {
                key: key("a.Flags.on(java.lang.String)", "(Ljava/lang/String;)Z", 2),
                result_type: None,
            },
            Selected {
                key: key(
                    "java.util.function.Supplier.get()",
                    "()Ljava/lang/Object;",
                    0,
                ),
                result_type: Some("java.lang.Boolean".into()),
            },
        ];
        let resolved = resolve_plan(plan, &[(1, sites)]);
        assert_eq!(
            resolved["folds"][1]["selected"],
            json!([
                {"caller_signature": "a.B.run()", "caller_descriptor": "()V",
                 "callee_signature": "a.Flags.on(java.lang.String)", "callee_descriptor": "(Ljava/lang/String;)Z",
                 "ordinal": 2},
                {"caller_signature": "a.B.run()", "caller_descriptor": "()V",
                 "callee_signature": "java.util.function.Supplier.get()", "callee_descriptor": "()Ljava/lang/Object;",
                 "ordinal": 0, "result_type": "java.lang.Boolean"}
            ])
        );
        assert_eq!(resolved["folds"][1]["provenance"], provenance);
        assert_eq!(resolved["folds"][2]["selected"], json!([]));
        assert!(resolved["folds"][2].get("provenance").is_none());
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
        let cmd = plan_command(&env, &jar, Path::new("folds.yml"), None, &[]).unwrap();
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
        let build: Vec<OsString> = [
            "app.jar",
            "-o",
            "out",
            "--include=a,b",
            "--fold",
            "folds.yml",
            "--exclude",
            "a.x",
            "--include-libs",
            "--lib-filter",
            "*.jar",
            "--android-sdk",
            "/sdk",
            "--interprocedural",
        ]
        .iter()
        .map(OsString::from)
        .collect();
        let cmd = plan_command(
            &env,
            &exe,
            Path::new("folds.yml"),
            Some(Path::new("app.jar")),
            &analysis_options(&build),
        )
        .unwrap();
        let args: Vec<String> = cmd
            .get_args()
            .map(|a| a.to_string_lossy().into_owned())
            .collect();
        // The plan is for a build with these options: the same ones, as given, so its
        // provenance names that build and a report of another build is run again.
        assert_eq!(
            args,
            [
                "fold",
                "plan",
                "folds.yml",
                "--input",
                "app.jar",
                "--include=a,b",
                "--exclude",
                "a.x",
                "--include-libs",
                "--lib-filter",
                "*.jar",
                "--android-sdk",
                "/sdk"
            ]
        );
        // Without an input there is no build to describe.
        let cmd = plan_command(
            &env,
            &exe,
            Path::new("folds.yml"),
            None,
            &analysis_options(&build),
        )
        .unwrap();
        assert_eq!(cmd.get_args().count(), 3);
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
        assert!(
            keys.iter().all(|k| k.key.callee.contains(".add(")),
            "{keys:?}"
        );
        assert!(keys.iter().all(|k| k.key.ordinal >= 0), "{keys:?}");
        assert!(
            keys.iter().all(|k| k.key.callee_descriptor.starts_with('(')
                && k.key.caller_descriptor.starts_with('(')),
            "{keys:?}"
        );
        // A derived call site folds as the call it was resolved from, never as itself: the
        // key written into the plan always names a bytecode invoke.
        let derived = select_sites(
            &executor,
            6,
            "MATCH (cs:CallSite) WHERE cs.ordinal < 0 RETURN cs LIMIT 20",
        );
        match derived {
            Ok(sites) => assert!(sites.iter().all(|s| s.key.ordinal >= 0), "{sites:?}"),
            Err(message) => assert!(message.contains("runs function values"), "{message}"),
        }
        let distinct: std::collections::HashSet<_> = keys.iter().map(|k| &k.key).collect();
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

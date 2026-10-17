//! Fixed JMH protocols: behavioral/heap failures gate; elapsed values only describe the run.
use crate::{
    plan,
    process::{self, ProcessSpec},
};
use anyhow::{ensure, Context, Result};
use clap::{Args, ValueEnum};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::io::Read;
use std::{
    collections::{BTreeMap, BTreeSet},
    ffi::OsString,
    fs,
    path::{Path, PathBuf},
};

#[derive(Clone, Debug, ValueEnum)]
pub enum Suite {
    FrontendBuildSave,
}
impl Suite {
    pub fn name(&self) -> &'static str {
        "frontend-build-save"
    }
}
#[derive(Debug, Args)]
pub struct JmhArgs {
    #[arg(long, value_enum, default_value = "frontend-build-save")]
    pub suite: Suite,
    #[arg(long)]
    pub base_jar: PathBuf,
    #[arg(long)]
    pub head_jar: PathBuf,
    #[arg(long)]
    pub fixtures: PathBuf,
    #[arg(long)]
    pub output: PathBuf,
    #[arg(long)]
    pub base_sha: String,
    #[arg(long)]
    pub head_sha: String,
    #[arg(long, default_value = "PR merge-base")]
    pub base_label: String,
}
fn command(label: &str, program: &str, args: Vec<OsString>, output: &Path) -> ProcessSpec {
    ProcessSpec {
        label: label.into(),
        program: program.into(),
        args,
        cwd: None,
        env: BTreeMap::new(),
        stdout: output.join(format!("{label}.stdout.log")),
        stderr: output.join(format!("{label}.stderr.log")),
        timeout: plan::JMH_TIMEOUT,
    }
}
fn prepare_guard(output: &Path) -> Result<PathBuf> {
    let classes = output.join("agent-classes");
    fs::create_dir_all(&classes)?;
    let source = output.join("HeapGuard.java");
    fs::write(&source, include_str!("../jvm/src/main/java/HeapGuard.java"))?;
    let compile = command(
        "compile-heap-agent",
        "javac",
        vec![
            "-J-Xmx512m".into(),
            "-d".into(),
            classes.as_os_str().into(),
            source.into_os_string(),
        ],
        output,
    );
    ensure!(
        process::run(&compile)?.success,
        "heap guard compilation failed"
    );
    let manifest = output.join("agent.mf");
    fs::write(&manifest, "Premain-Class: HeapGuard\n\n")?;
    let jar = output.join("heap-guard.jar");
    let pack = command(
        "package-heap-agent",
        "jar",
        vec![
            "-J-Xmx512m".into(),
            "cfm".into(),
            jar.as_os_str().into(),
            manifest.into_os_string(),
            "-C".into(),
            classes.into_os_string(),
            ".".into(),
        ],
        output,
    );
    ensure!(process::run(&pack)?.success, "heap guard packaging failed");
    Ok(jar)
}
fn quote(value: &str) -> String {
    format!("\"{}\"", value.replace('\\', "\\\\").replace('"', "\\\""))
}
fn heap_audit(directory: &Path) -> Result<usize> {
    let mut count = 0;
    for entry in fs::read_dir(directory)? {
        let path = entry?.path();
        if path.extension().and_then(|x| x.to_str()) != Some("receipt") {
            continue;
        }
        let text = fs::read_to_string(&path)?;
        let lines: Vec<_> = text.lines().collect();
        ensure!(
            lines.len() == 2 && lines[1] == "SHUTDOWN",
            "incomplete or failing JVM heap receipt: {}",
            path.display()
        );
        let fields: Vec<_> = lines[0].split('\t').collect();
        ensure!(
            fields.len() == 3 && fields[0] == "START",
            "invalid heap receipt"
        );
        let actual: u64 = fields[1].parse()?;
        let limit: u64 = fields[2].parse()?;
        ensure!(
            limit == plan::DEFAULT_HEAP_BYTES && actual == limit,
            "JAR JVM did not use the fixed 4 GiB heap"
        );
        count += 1;
    }
    ensure!(count >= 2, "missing launcher or JMH fork heap receipts");
    Ok(count)
}
const REAL64_BENCHMARK: &str =
    "io.johnsonlee.graphite.webgraph.Fixture64BuildBenchmark.buildAndSaveDistinctRealGraphs";
const WHOLE_JAR_BENCHMARK: &str =
    "io.johnsonlee.graphite.webgraph.WholeJarBuildSaveBenchmark.buildSaveAndValidateWholeJar";
const WHOLE_JAR_CORPORA: [&str; 3] = ["tika", "hive", "kotlin-compiler"];

fn require_frontend_coverage(rows: &BTreeMap<String, Value>) -> Result<&Value> {
    let mut whole = BTreeSet::new();
    let mut real64 = None;
    for row in rows.values() {
        match row["benchmark"].as_str() {
            Some(REAL64_BENCHMARK) => {
                ensure!(
                    real64.replace(row).is_none(),
                    "duplicate real64 build/save case"
                );
                ensure!(
                    row.get("params")
                        .is_none_or(|v| v.is_null() || v.as_object().is_some_and(|p| p.is_empty())),
                    "unexpected real64 parameters"
                );
            }
            Some(WHOLE_JAR_BENCHMARK) => {
                let params = row["params"]
                    .as_object()
                    .context("missing whole-JAR corpus parameter")?;
                let corpus = row["params"]["corpus"]
                    .as_str()
                    .context("missing whole-JAR corpus")?;
                ensure!(
                    params.len() == 1
                        && WHOLE_JAR_CORPORA.contains(&corpus)
                        && whole.insert(corpus),
                    "unknown or duplicated whole-JAR corpus"
                );
            }
            _ => anyhow::bail!("unexpected frontend benchmark case"),
        }
    }
    ensure!(
        whole.len() == WHOLE_JAR_CORPORA.len(),
        "missing complete whole-JAR correctness/OOM cases"
    );
    real64.context("missing real64 build/save case")
}

fn parse_jmh(path: &Path, expected: usize, iterations: usize) -> Result<BTreeMap<String, Value>> {
    let value: Value = serde_json::from_slice(&fs::read(path)?)?;
    let rows = value.as_array().context("JMH result must be an array")?;
    ensure!(
        rows.len() == expected,
        "expected {expected} JMH cases, got {}",
        rows.len()
    );
    let mut result = BTreeMap::new();
    for row in rows {
        ensure!(
            row["mode"] == "ss"
                && row["forks"] == 1
                && row["warmupIterations"] == 0
                && row["measurementIterations"] == iterations,
            "unexpected JMH execution protocol"
        );
        let jvm_args = row["jvmArgs"]
            .as_array()
            .context("missing actual JMH JVM arguments")?;
        for required in [
            "-Xmx4g",
            "-XX:+UseG1GC",
            "-XX:+ExitOnOutOfMemoryError",
            "-Dgraphite.correctness.only=true",
        ] {
            ensure!(
                jvm_args.iter().any(|arg| arg == required),
                "missing fixed JMH JVM policy: {required}"
            );
        }
        ensure!(
            row["primaryMetric"]["scoreUnit"] == "ms/op",
            "JMH units must be ms/op"
        );
        let raw = row["primaryMetric"]["rawData"]
            .as_array()
            .context("missing JMH raw samples")?;
        ensure!(raw.len() == 1, "expected exactly one JVM fork");
        let samples = raw[0].as_array().context("missing fork data")?;
        ensure!(
            samples.len() == iterations
                && samples
                    .iter()
                    .all(|x| x.as_f64().is_some_and(|v| v.is_finite() && v >= 0.0)),
            "missing or invalid JMH samples"
        );
        let name = row["benchmark"]
            .as_str()
            .context("missing benchmark name")?;
        let key = format!("{}:{}", name, row.get("params").unwrap_or(&Value::Null));
        ensure!(
            result.insert(key, row.clone()).is_none(),
            "duplicate JMH case"
        );
    }
    Ok(result)
}
fn value_ms(row: &Value) -> f64 {
    let samples = row["primaryMetric"]["rawData"][0].as_array().unwrap();
    samples.iter().map(|x| x.as_f64().unwrap()).sum::<f64>() / samples.len() as f64
}
pub(crate) fn cgroup_events() -> Result<Option<BTreeMap<String, u64>>> {
    if !cfg!(target_os = "linux") {
        return Ok(None);
    }
    let membership = fs::read_to_string("/proc/self/cgroup")?;
    let path = membership
        .lines()
        .find_map(|line| line.strip_prefix("0::"))
        .context("cgroup v2 required for host OOM evidence")?;
    let events = Path::new("/sys/fs/cgroup")
        .join(path.trim_start_matches('/'))
        .join("memory.events");
    let text = fs::read_to_string(events)?;
    let mut result = BTreeMap::new();
    for line in text.lines() {
        let p: Vec<_> = line.split_whitespace().collect();
        if p.len() == 2 {
            result.insert(p[0].into(), p[1].parse()?);
        }
    }
    ensure!(
        result.contains_key("oom") && result.contains_key("oom_kill"),
        "missing cgroup OOM counters"
    );
    Ok(Some(result))
}
fn source_inputs(root: &Path) -> Result<BTreeMap<String, String>> {
    let mut result = BTreeMap::new();
    for (_, name, expected_sha, expected_bytes) in plan::FIXTURES {
        let mut file = fs::File::open(root.join(name))?;
        ensure!(
            file.metadata()?.len() == *expected_bytes,
            "fixture size does not match fixed corpus: {name}"
        );
        let mut digest = Sha256::new();
        let mut buffer = [0u8; 65536];
        loop {
            let count = file.read(&mut buffer)?;
            if count == 0 {
                break;
            }
            digest.update(&buffer[..count]);
        }
        let actual: String = digest
            .finalize()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        ensure!(
            actual == *expected_sha,
            "fixture SHA256 does not match fixed corpus: {name}"
        );
        result.insert((*name).into(), actual);
    }
    Ok(result)
}
fn product_identity(jar: &Path) -> Result<String> {
    let identity = fs::read_to_string(jar.with_file_name("product-identity.txt"))?;
    let identity = identity.trim();
    ensure!(
        identity.len() == 64
            && identity
                .bytes()
                .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c)),
        "invalid prepared product identity"
    );
    Ok(identity.to_owned())
}

pub fn run(args: &JmhArgs) -> Result<Value> {
    fs::create_dir_all(&args.output)?;
    let output = args.output.canonicalize()?;
    // Each revision receives the same source JARs. No persisted graphs are reused.
    let fixtures = args.fixtures.canonicalize()?;
    let inputs = source_inputs(&fixtures)?;
    let guard = prepare_guard(&output)?;
    let before = cgroup_events()?;
    let mut checks = Vec::new();
    let mut artifacts = Vec::new();
    let mut results = Vec::new();
    let mut statuses = Vec::new();
    for (revision, jar) in [("base", &args.base_jar), ("head", &args.head_jar)] {
        let dir = output.join(revision);
        fs::create_dir_all(&dir)?;
        let receipts = dir.join("heap");
        fs::create_dir_all(&receipts)?;
        let result_path = dir.join("jmh.json");
        let execution = (|| -> Result<(Value, usize)> {
            let jar = jar
                .canonicalize()
                .context("frontend JMH artifact unavailable for this revision")?;
            let product_identity = product_identity(&jar)?;
            let props = vec![
                format!("-Dgraphite.benchmark.productIdentity={product_identity}"),
                "-Xmx4g".to_string(),
                "-XX:+UseG1GC".into(),
                "-Dgraphite.correctness.only=true".into(),
                "-XX:+ExitOnOutOfMemoryError".into(),
                format!(
                    "-Dgraphite.fixture64.output={}",
                    dir.join("graphs").display()
                ),
            ];
            let mut props = props;
            for (file, property) in [
                ("android.jar", "android"),
                ("tika.jar", "tika"),
                ("hive.jar", "hive"),
                ("kotlin-compiler.jar", "kotlin.compiler"),
            ] {
                let source = fixtures.join(file);
                ensure!(
                    source.is_file(),
                    "missing pinned source fixture {}",
                    source.display()
                );
                props.push(format!("-D{property}.jar.path={}", source.display()));
            }
            let argv: Vec<OsString> = vec![
                "-Xmx4g".into(),
                "-cp".into(),
                jar.into_os_string(),
                "org.openjdk.jmh.Main".into(),
                ".*(Fixture64BuildBenchmark\\.buildAndSaveDistinctRealGraphs|WholeJarBuildSaveBenchmark\\.buildSaveAndValidateWholeJar)$".into(),
                "-f".into(),
                "1".into(),
                "-wi".into(),
                "0".into(),
                "-i".into(),
                "1".into(),
                "-bm".into(),
                "ss".into(),
                "-bs".into(),
                "1".into(),
                "-to".into(),
                "7200s".into(),
                "-tu".into(),
                "ms".into(),
                "-foe".into(),
                "true".into(),
                "-rf".into(),
                "json".into(),
                "-rff".into(),
                result_path.as_os_str().into(),
                "-jvmArgs".into(),
                props
                    .iter()
                    .map(|p| quote(p))
                    .collect::<Vec<_>>()
                    .join(" ")
                    .into(),
            ];
            let mut spec = command("jmh", "java", argv, &dir);
            spec.env.insert(
                "JAVA_TOOL_OPTIONS".into(),
                format!(
                    "-Xmx4g -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError {}",
                    quote(&format!(
                        "-javaagent:{}=jar,{}",
                        guard.display(),
                        receipts.display()
                    ))
                )
                .into(),
            );
            let outcome = process::run(&spec)?;
            ensure!(outcome.success, "{}", outcome.reason);
            let receipts = heap_audit(&receipts)?;
            let parsed = parse_jmh(&result_path, 4, 1)?;
            // Single whole-JAR values stay only in raw diagnostics, never performance report rows.
            let row = require_frontend_coverage(&parsed)?.clone();
            validate_saved_graphs(&dir.join("graphs"))?;
            ensure!(
                source_inputs(&fixtures)? == inputs,
                "source fixture bytes changed during execution"
            );
            Ok((row, receipts))
        })();
        let detail = execution
            .as_ref()
            .map(|(_, n)| {
                format!(
                    "fresh real64 build/save plus whole Tika/Hive/Kotlin correctness/readback complete; {n} G1 JVM receipts under 4 GiB; ExitOnOutOfMemoryError rejects even recoverable production OOM"
                )
            })
            .unwrap_or_else(|e| format!("{e:#}"));
        let passed = execution.is_ok();
        {
            for kind in ["correctness", "heap", "coverage"] {
                checks.push(json!({"name":format!("{revision} frontend build/save {kind}"),"kind":kind,"passed":passed,"detail":detail}));
            }
        }
        statuses.push(json!({"revision":revision,"status":if passed {"COMPLETE"} else {"UNAVAILABLE"},"detail":detail}));
        artifacts.push(result_path.display().to_string());
        artifacts.push(dir.join("jmh.stdout.log").display().to_string());
        artifacts.push(dir.join("jmh.stderr.log").display().to_string());
        results.push(execution.ok().map(|(row, _)| row));
    }
    let after = cgroup_events()?;
    let host_ok = match (&before, &after) {
        (Some(a), Some(b)) => ["oom", "oom_kill", "oom_group_kill"]
            .iter()
            .all(|key| b.get(*key).unwrap_or(&0) == a.get(*key).unwrap_or(&0)),
        (None, None) => true,
        _ => false,
    };
    checks.push(json!({"name":"host OOM events","kind":"heap","passed":host_ok,"detail":format!("before={before:?}; after={after:?}")}));
    let timing = build_save_timing(results[0].as_ref(), results[1].as_ref(), host_ok);
    let report = json!({"schema":plan::SCHEMA,"suite":"frontend-build-save","baseSha":args.base_sha,"headSha":args.head_sha,"baseLabel":args.base_label,"baseStatus":if results[0].is_some(){"AVAILABLE"}else{"UNAVAILABLE"},"headStatus":if results[1].is_some(){"AVAILABLE"}else{"UNAVAILABLE"},"baseUnavailableReason":if results[0].is_some(){Value::Null}else{statuses[0]["detail"].clone()},"checks":checks,"timings":[timing],"artifacts":artifacts,"statuses":statuses,"sourceInputs":inputs});
    fs::write(
        output.join("report.json"),
        serde_json::to_vec_pretty(&report)?,
    )?;
    Ok(report)
}
fn build_save_timing(base: Option<&Value>, head: Option<&Value>, host_ok: bool) -> Value {
    let comparable = host_ok && base.is_some() && head.is_some();
    let unavailable_reason = if !host_ok {
        Some("Host OOM evidence invalidates timing comparison; see heap checks and raw logs")
    } else if !comparable {
        Some("base or head frontend build/save did not complete; see statuses and raw logs")
    } else {
        None
    };
    json!({"case":"real64-build-save","stage":"frontend-build-save","workload":"jar/4-corpora/64-distinct-shards",
        "method":"JMH SingleShotTime; one fork; informational only","baseMs":base.map(value_ms),"headMs":head.map(value_ms),
        "samplesBase":usize::from(base.is_some()),"samplesHead":usize::from(head.is_some()),
        "comparable":comparable,"unavailableReason":unavailable_reason})
}

fn validate_saved_graphs(root: &Path) -> Result<()> {
    let text = fs::read_to_string(root.join("graphs.tsv"))?;
    let rows: Vec<_> = text
        .lines()
        .filter(|line| !line.starts_with('#') && !line.trim().is_empty())
        .collect();
    ensure!(
        rows.len() == 64,
        "expected 64 saved graphs, found {}",
        rows.len()
    );
    let mut ids = BTreeSet::new();
    let mut paths = BTreeSet::new();
    for row in rows {
        let fields: Vec<_> = row.split('\t').collect();
        ensure!(fields.len() == 6, "malformed saved graph manifest");
        ensure!(ids.insert(fields[0]), "duplicate graph ID");
        let graph = Path::new(fields[1]).canonicalize()?;
        ensure!(
            graph.starts_with(root.canonicalize()?) && paths.insert(graph.clone()),
            "saved graph escaped root or reused path"
        );
        for name in [
            "forward.graph",
            "forward.properties",
            "graph.nodedata",
            "graph.metadata",
            "graph.strings",
            "graph.resources",
            "graph.callsite-string-index",
        ] {
            ensure!(
                graph.join(name).is_file(),
                "missing production file {}",
                graph.join(name).display()
            );
        }
    }
    ensure!(
        root.join("fixture-provenance.tsv").is_file(),
        "missing fixture provenance"
    );
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    static NEXT: AtomicUsize = AtomicUsize::new(0);
    fn temp() -> PathBuf {
        let path = std::env::temp_dir().join(format!(
            "graphite-jmh-test-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(&path).unwrap();
        path
    }
    #[test]
    fn quoted_paths_preserve_java_tokens() {
        assert_eq!(quote("/tmp/a b"), "\"/tmp/a b\"");
    }
    #[test]
    fn direct_jmh_entry_rejects_replaced_fixture_even_at_the_expected_size() {
        let root = temp();
        let (_, name, _, bytes) = plan::FIXTURES[0];
        let file = fs::File::create(root.join(name)).unwrap();
        file.set_len(bytes).unwrap();
        let failure = source_inputs(&root).unwrap_err().to_string();
        assert!(failure.contains("fixture SHA256 does not match fixed corpus"));
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn incomplete_jmh_cannot_publish_timings() {
        let root = temp();
        let file = root.join("jmh.json");
        let mut row = json!({"benchmark":"Fixture64BuildBenchmark.buildAndSaveDistinctRealGraphs","mode":"ss","forks":1,"warmupIterations":0,"measurementIterations":1,"jvmArgs":["-Xmx4g","-XX:+UseG1GC","-XX:+ExitOnOutOfMemoryError","-Dgraphite.correctness.only=true"],"primaryMetric":{"scoreUnit":"ms/op","rawData":[[1234.5]]}});
        fs::write(&file, serde_json::to_vec(&vec![row.clone()]).unwrap()).unwrap();
        assert!(parse_jmh(&file, 1, 1).is_ok());
        row["primaryMetric"]["rawData"] = json!([[]]);
        fs::write(&file, serde_json::to_vec(&vec![row.clone()]).unwrap()).unwrap();
        assert!(parse_jmh(&file, 1, 1).is_err());
        row["primaryMetric"]["rawData"] = json!([[1234.5]]);
        row["mode"] = json!("avgt");
        fs::write(&file, serde_json::to_vec(&vec![row]).unwrap()).unwrap();
        assert!(parse_jmh(&file, 1, 1).is_err());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn product_identity_requires_lowercase_sha256_and_cannot_inject_jvm_options() {
        let root = temp();
        let jar = root.join("webgraph-jmh.jar");
        assert!(product_identity(&jar).is_err());
        fs::write(
            root.join("product-identity.txt"),
            format!("{}\n", "a".repeat(64)),
        )
        .unwrap();
        assert_eq!(product_identity(&jar).unwrap(), "a".repeat(64));
        for invalid in [
            "a".repeat(40),
            "a".repeat(63),
            "a".repeat(65),
            "A".repeat(64),
            format!("{} -Xmx8g", "a".repeat(64)),
        ] {
            fs::write(root.join("product-identity.txt"), invalid).unwrap();
            assert!(product_identity(&jar).is_err());
        }
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn frontend_requires_whole_inputs_but_reports_only_real64_time() {
        let real64 = json!({"benchmark":REAL64_BENCHMARK,"primaryMetric":{"rawData":[[100.0]]}});
        let mut rows = BTreeMap::from([("real64".to_owned(), real64)]);
        assert!(require_frontend_coverage(&rows).is_err());
        for corpus in WHOLE_JAR_CORPORA {
            rows.insert(corpus.into(), json!({"benchmark":WHOLE_JAR_BENCHMARK,"params":{"corpus":corpus},"primaryMetric":{"rawData":[[999999.0]]}}));
        }
        let result = require_frontend_coverage(&rows).unwrap();
        let timing = build_save_timing(Some(result), Some(result), true);
        assert_eq!(timing["case"], "real64-build-save");
        assert_eq!(timing["baseMs"], 100.0);
        rows.get_mut("hive").unwrap()["params"]["corpus"] = json!("tika");
        assert!(require_frontend_coverage(&rows).is_err());
    }

    #[test]
    fn every_jvm_receipt_must_complete_under_policy() {
        let root = temp();
        let receipt = "START\t4294967296\t4294967296\nSHUTDOWN\n";
        fs::write(root.join("launcher.receipt"), receipt).unwrap();
        fs::write(root.join("fork.receipt"), receipt).unwrap();
        assert_eq!(heap_audit(&root).unwrap(), 2);
        fs::write(root.join("fork.receipt"), "START\t4294967296\t4294967296\n").unwrap();
        assert!(heap_audit(&root).is_err());
        fs::write(
            root.join("fork.receipt"),
            "START\t8589934592\t4294967296\nSHUTDOWN\n",
        )
        .unwrap();
        assert!(heap_audit(&root).is_err());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn host_oom_keeps_observations_but_suppresses_report_comparisons() {
        let base = json!({"primaryMetric":{"rawData":[[100.0]]}});
        let head = json!({"primaryMetric":{"rawData":[[50.0]]}});
        assert_eq!(
            build_save_timing(Some(&base), Some(&head), true)["comparable"],
            true
        );
        let timing = build_save_timing(Some(&base), Some(&head), false);
        assert_eq!(timing["comparable"], false);
        assert_eq!(timing["baseMs"], 100.0);
        assert_eq!(timing["headMs"], 50.0);
        assert!(timing["unavailableReason"]
            .as_str()
            .unwrap()
            .contains("Host OOM"));
        let mut suite = crate::report::failed_suite(
            "frontend-build-save",
            &"a".repeat(40),
            &"b".repeat(40),
            "Host OOM",
        );
        for check in suite["checks"].as_array_mut().unwrap() {
            check["passed"] = json!(check["kind"] != "heap");
        }
        suite["timings"] = json!([timing]);
        let root = temp();
        assert!(!crate::report::write_suite(&root, &suite).unwrap());
        let saved: Value =
            serde_json::from_slice(&fs::read(root.join("suite.json")).unwrap()).unwrap();
        assert!(saved["timings"][0]["deltaMs"].is_null());
        assert!(saved["timings"][0]["deltaPercent"].is_null());
        let markdown = fs::read_to_string(root.join("report.md")).unwrap();
        assert!(markdown.contains("100.000 | 50.000 | UNAVAILABLE | UNAVAILABLE"));
        assert!(markdown.contains("Host OOM evidence invalidates timing comparison"));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn cached_or_partial_graphs_do_not_complete_build() {
        let root = temp();
        fs::write(root.join("graphs.tsv"), "# no complete records\n").unwrap();
        assert!(validate_saved_graphs(&root).is_err());
        fs::remove_dir_all(root).unwrap();
    }
}

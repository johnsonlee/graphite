use super::*;
use std::{
    process::Command,
    sync::atomic::{AtomicUsize, Ordering},
};

struct Directory(PathBuf);

impl Directory {
    fn new() -> Self {
        static NEXT: AtomicUsize = AtomicUsize::new(0);
        let path = std::env::temp_dir().join(format!(
            "graphite-pipeline-{}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
}

impl Drop for Directory {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn git(path: &Path, args: &[&str]) -> String {
    let output = Command::new("git")
        .args([
            "-c",
            "user.name=Pipeline Test",
            "-c",
            "user.email=pipeline@example.invalid",
            "-c",
            "commit.gpgsign=false",
            "-c",
            "core.hooksPath=/dev/null",
        ])
        .args(args)
        .current_dir(path)
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "git {args:?}: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    String::from_utf8(output.stdout).unwrap().trim().to_owned()
}

fn repository(parent: &Path, name: &str) -> PathBuf {
    let path = parent.join(name);
    fs::create_dir(&path).unwrap();
    git(&path, &["init", "--quiet"]);
    fs::write(path.join("product.txt"), name).unwrap();
    git(&path, &["add", "product.txt"]);
    git(&path, &["commit", "--quiet", "-m", name]);
    path
}

fn arguments(directory: &Directory) -> RunArgs {
    RunArgs {
        base_source: repository(&directory.0, "base"),
        head_source: repository(&directory.0, "head"),
        harness_source: repository(&directory.0, "trusted"),
        output: directory.0.join("run"),
    }
}

fn read(path: &Path) -> Value {
    serde_json::from_slice(&fs::read(path).unwrap()).unwrap()
}

fn write(path: &Path, value: &Value) {
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    fs::write(path, serde_json::to_vec_pretty(value).unwrap()).unwrap();
}

fn passing_evidence(root: &Path) {
    write(
        &root.join("execution.json"),
        &json!({
            "schema":"graphite-benchmark-execution-v1", "errors":[]
        }),
    );
    write(
        &root.join("prepared/preparation.json"),
        &json!({
            "schema":"graphite-benchmark-preparation-v1", "passed":true
        }),
    );
    for name in crate::plan::REQUIRED_SUITES {
        let value = json!({
            "schema":crate::plan::SCHEMA,"suite":name,
            "baseSha":"a".repeat(40),"headSha":"b".repeat(40),
            "checks":[
                {"name":"results","kind":"correctness","passed":true,"detail":"validated"},
                {"name":"heap","kind":"heap","passed":true,"detail":"validated"},
                {"name":"coverage","kind":"coverage","passed":true,"detail":"validated"}
            ],
            "timings":[{"case":"real64","stage":"pressure","workload":"real64",
                "method":"report-only fixture","baseMs":1.0,"headMs":1000000.0,
                "samplesBase":20,"samplesHead":20,"comparable":true}],
            "artifacts":[]
        });
        assert!(report::write_suite(&root.join("results").join(name), &value).unwrap());
    }
}

#[test]
fn missing_modules_record_both_arms_and_suites_without_build_tools() {
    let directory = Directory::new();
    let args = arguments(&directory);
    let base = git(&args.base_source, &["rev-parse", "HEAD"]);
    let head = git(&args.head_source, &["rev-parse", "HEAD"]);
    assert_ne!(base, head);
    assert!(!run(&args).unwrap());

    let preparation = read(&args.output.join("prepared/preparation.json"));
    assert_eq!(preparation["passed"], false);
    assert_eq!(
        preparation["harnessRevision"],
        git(&args.harness_source, &["rev-parse", "HEAD"])
    );
    let builds = preparation["builds"].as_array().unwrap();
    assert_eq!(builds.len(), 2);
    for (build, role) in builds.iter().zip(["base", "head"]) {
        assert_eq!(build["role"], role);
        assert_eq!(build["passed"], false);
        assert!(build["error"]
            .as_str()
            .unwrap()
            .contains("webgraph module missing"));
    }
    for relative in ["results/frontend/suite.json", "results/native/suite.json"] {
        let suite = read(&args.output.join(relative));
        assert_eq!(suite["baseSha"], base);
        assert_eq!(suite["headSha"], head);
        assert!(!report::validate_suite(&suite).unwrap());
    }
    let raw_paths = [
        "prepared/preparation.json",
        "execution.json",
        "results/frontend/suite.json",
        "results/native/suite.json",
        "results/native/base-requests.json",
        "results/native/head-requests.json",
    ];
    let before: Vec<_> = raw_paths
        .iter()
        .map(|p| fs::read(args.output.join(p)).unwrap())
        .collect();
    assert!(!render(&args.output).unwrap());
    for (path, expected) in raw_paths.iter().zip(before) {
        assert_eq!(
            fs::read(args.output.join(path)).unwrap(),
            expected,
            "rerender changed {path}"
        );
    }
    let summary = read(&args.output.join("report/report.json"));
    assert_eq!(summary["passed"], false);
    assert_eq!(summary["suites"].as_array().unwrap().len(), 2);
    assert!(summary["errors"]
        .as_array()
        .unwrap()
        .iter()
        .any(|e| e.as_str().unwrap().contains("Preparation failed")));
}

#[test]
fn existing_output_is_rejected_without_overwriting_evidence() {
    let directory = Directory::new();
    let args = arguments(&directory);
    fs::create_dir(&args.output).unwrap();
    let marker = args.output.join("execution.json");
    fs::write(&marker, "original interrupted evidence").unwrap();
    assert!(format!("{:#}", run(&args).unwrap_err()).contains("fresh output directory"));
    assert_eq!(
        fs::read_to_string(marker).unwrap(),
        "original interrupted evidence"
    );
    assert!(!args.output.join("prepared").exists());
}

#[test]
fn evidence_write_failure_does_not_skip_native_suite() {
    let directory = Directory::new();
    let args = arguments(&directory);
    fs::create_dir_all(args.output.join("results")).unwrap();
    fs::write(args.output.join("results/frontend"), "blocked path").unwrap();
    let mut errors = Vec::new();
    execute(&args, &mut errors).unwrap();
    assert!(errors.iter().any(|e| e.contains("Saving suite evidence")));
    let native = read(&args.output.join("results/native/suite.json"));
    assert_eq!(native["suite"], "native-real64");
    assert!(!report::validate_suite(&native).unwrap());
    assert_eq!(
        fs::read_to_string(args.output.join("results/frontend")).unwrap(),
        "blocked path"
    );
}

#[test]
fn slower_observations_are_reported_without_failing_valid_gates() {
    let directory = Directory::new();
    passing_evidence(&directory.0);
    assert!(render(&directory.0).unwrap());
    let summary = read(&directory.0.join("report/report.json"));
    assert_eq!(summary["passed"], true);
    assert_eq!(summary["performanceAcceptance"], false);
    for suite in summary["suites"].as_array().unwrap() {
        assert_eq!(suite["timings"][0]["deltaMs"], 999999.0);
        assert_eq!(suite["timings"][0]["headMs"], 1000000.0);
    }
}

#[test]
fn preparation_failure_overrides_passing_suite_evidence() {
    let directory = Directory::new();
    passing_evidence(&directory.0);
    write(
        &directory.0.join("prepared/preparation.json"),
        &json!({
            "schema":"graphite-benchmark-preparation-v1", "passed":false
        }),
    );
    assert!(!render(&directory.0).unwrap());
    let summary = read(&directory.0.join("report/report.json"));
    assert!(summary["errors"]
        .as_array()
        .unwrap()
        .iter()
        .any(|e| e.as_str().unwrap().contains("Preparation failed")));
}

#[test]
fn interrupted_execution_cannot_turn_passing_suites_green_on_rerender() {
    let directory = Directory::new();
    passing_evidence(&directory.0);
    assert!(render(&directory.0).unwrap());
    fs::remove_file(directory.0.join("execution.json")).unwrap();
    assert!(!render(&directory.0).unwrap());
    let summary = read(&directory.0.join("report/report.json"));
    assert_eq!(summary["passed"], false);
    assert!(summary["errors"]
        .as_array()
        .unwrap()
        .iter()
        .any(|e| e.as_str().unwrap().contains("execution did not finish")));
}

#[test]
fn execution_errors_survive_rerender_even_when_all_suites_pass() {
    let directory = Directory::new();
    passing_evidence(&directory.0);
    let error = "Saving suite evidence: disk full after execution";
    write(
        &directory.0.join("execution.json"),
        &json!({
            "schema":"graphite-benchmark-execution-v1", "errors":[error]
        }),
    );
    for _ in 0..2 {
        assert!(!render(&directory.0).unwrap());
        let summary = read(&directory.0.join("report/report.json"));
        assert!(summary["errors"]
            .as_array()
            .unwrap()
            .contains(&json!(error)));
    }
}

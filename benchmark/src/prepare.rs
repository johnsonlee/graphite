//! Build both revisions with one reviewed frontend benchmark harness.
use crate::{
    plan::FIXTURES,
    process::{self, ProcessSpec},
};
use anyhow::{bail, ensure, Context, Result};
use clap::Args;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    ffi::OsString,
    fs,
    path::{Path, PathBuf},
    time::Duration,
};

#[derive(Debug, Args)]
pub struct PrepareArgs {
    /// Dedicated checkout: benchmark source files are installed into this tree.
    #[arg(long)]
    pub base_source: PathBuf,
    /// Dedicated PR checkout, also the owner of the shared benchmark harness.
    #[arg(long)]
    pub head_source: PathBuf,
    #[arg(long)]
    pub output: PathBuf,
}

const HARNESS: &[&str] = &[
    "Fixture64BuildBenchmark.kt",
    "Fixture64GraphPreparation.kt",
    "BenchmarkCorpus.kt",
];

fn module(source: &Path) -> Result<PathBuf> {
    for relative in ["frontend/jvm/webgraph", "graphite-webgraph"] {
        let directory = source.join(relative);
        if directory.join("build.gradle.kts").is_file() {
            return Ok(directory);
        }
    }
    bail!("webgraph module missing in {}", source.display())
}

fn hash(path: &Path) -> Result<String> {
    let bytes = fs::read(path).with_context(|| format!("read {}", path.display()))?;
    Ok(Sha256::digest(&bytes)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect())
}

fn one_file(directory: &Path, predicate: impl Fn(&str) -> bool) -> Result<PathBuf> {
    let mut files = Vec::new();
    for entry in fs::read_dir(directory)? {
        let entry = entry?;
        if entry.file_type()?.is_file() && predicate(&entry.file_name().to_string_lossy()) {
            files.push(entry.path());
        }
    }
    ensure!(
        files.len() == 1,
        "expected exactly one matching file in {}, found {}",
        directory.display(),
        files.len()
    );
    Ok(files.remove(0))
}

fn command(
    label: &str,
    program: PathBuf,
    args: Vec<OsString>,
    cwd: &Path,
    logs: &Path,
) -> Result<Value> {
    let mut env = BTreeMap::new();
    env.insert(
        "JAVA_TOOL_OPTIONS".into(),
        "-Xmx4g -XX:+ExitOnOutOfMemoryError -Dgraphite.correctness.only=true".into(),
    );
    env.insert("GRADLE_OPTS".into(), "-Dorg.gradle.daemon=false -Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process".into());
    for key in ["JAVA_OPTS", "JVM_OPTS"] {
        env.insert(key.into(), "-Xmx4g -XX:+ExitOnOutOfMemoryError".into());
    }
    for key in ["_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"] {
        env.insert(key.into(), "".into());
    }
    env.insert(
        "CARGO_TARGET_DIR".into(),
        cwd.join("target").into_os_string(),
    );
    println!("Preparing {label}");
    let result = process::run(&ProcessSpec {
        label: label.into(),
        program,
        args,
        cwd: Some(cwd.to_owned()),
        env,
        stdout: logs.join(format!("{label}.stdout.log")),
        stderr: logs.join(format!("{label}.stderr.log")),
        timeout: Duration::from_secs(3600),
    })?;
    let value = serde_json::to_value(&result)?;
    fs::create_dir_all(logs)?;
    fs::write(
        logs.join(format!("{label}.json")),
        serde_json::to_vec_pretty(&value)?,
    )?;
    ensure!(
        result.success,
        "{label} failed ({}); see {}",
        result.reason,
        logs.display()
    );
    Ok(value)
}

fn build(source: &Path, head: &Path, output: &Path, role: &str) -> Result<Value> {
    let target = output.join(role);
    fs::create_dir_all(&target)?;
    let revision = std::process::Command::new("git")
        .args(["rev-parse", "HEAD"])
        .current_dir(source)
        .env("GIT_NO_REPLACE_OBJECTS", "1")
        .output()?;
    ensure!(
        revision.status.success(),
        "cannot resolve {role} source commit"
    );
    let revision = String::from_utf8(revision.stdout)?.trim().to_owned();
    ensure!(
        revision.len() == 40 && revision.chars().all(|c| c.is_ascii_hexdigit()),
        "invalid {role} commit"
    );
    let source_module = module(source)?;
    let harness_relative = Path::new("src/jmh/kotlin/io/johnsonlee/graphite/webgraph");
    let owner_harness = module(head)?.join(harness_relative);
    let destination = source_module.join(harness_relative);
    fs::create_dir_all(&destination)?;
    let mut overlays = Vec::new();
    for name in HARNESS {
        let original = owner_harness.join(name);
        let installed = destination.join(name);
        ensure!(
            !original.is_symlink() && !installed.is_symlink(),
            "redirected harness file: {name}"
        );
        if original != installed {
            fs::copy(&original, &installed)?;
        }
        overlays.push(json!({"file":name,"sha256":hash(&original)?}));
    }
    let args = [
        ":webgraph:jmhJar",
        ":webgraph:prepareBenchmarkFixtures",
        "--no-daemon",
        "--max-workers=2",
        "-Dorg.gradle.jvmargs=-Xmx4g -XX:+ExitOnOutOfMemoryError",
    ]
    .into_iter()
    .map(OsString::from)
    .collect();
    let jvm = command("jvm-build", source.join("gradlew"), args, source, &target)?;
    let jar = one_file(&source_module.join("build/libs"), |name| {
        name.ends_with("-jmh.jar")
    })?;
    fs::copy(&jar, target.join("webgraph-jmh.jar"))?;
    let mut fixtures = Vec::new();
    for (prefix, output_name, expected_sha, expected_size) in FIXTURES {
        let path = one_file(&source_module.join("build/benchmark-fixtures"), |name| {
            name.starts_with(prefix) && name.ends_with(".jar")
        })?;
        let actual_sha = hash(&path)?;
        ensure!(
            actual_sha == *expected_sha && fs::metadata(&path)?.len() == *expected_size,
            "fixture bytes do not match the fixed corpus: {}",
            path.display()
        );
        let shared = output.join("fixtures").join(output_name);
        fs::create_dir_all(shared.parent().context("fixture parent")?)?;
        if shared.exists() {
            ensure!(hash(&shared)? == actual_sha, "base/head fixtures differ");
        } else {
            fs::copy(&path, &shared)?;
        }
        fixtures.push(json!({"path":shared,"sha256":actual_sha,"bytes":expected_size}));
    }
    let compiler = std::process::Command::new("rustc").arg("-vV").output()?;
    ensure!(compiler.status.success(), "rustc host discovery failed");
    let compiler_info = String::from_utf8(compiler.stdout)?;
    let host = compiler_info
        .lines()
        .find_map(|line| line.strip_prefix("host: "))
        .context("rustc did not report a host target")?;
    ensure!(
        host.chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'),
        "invalid Rust host target"
    );
    let native = command(
        "native-build",
        "cargo".into(),
        [
            "build",
            "--release",
            "--locked",
            "--target",
            host,
            "-p",
            "graphite-cli",
        ]
        .into_iter()
        .map(OsString::from)
        .collect(),
        source,
        &target,
    )?;
    fs::copy(
        source.join("target").join(host).join("release/graphite"),
        target.join("graphite"),
    )?;
    Ok(
        json!({"role":role,"revision":revision,"passed":true,"overlays":overlays,"fixtures":fixtures,
              "jvmBuild":jvm,"nativeBuild":native,"jmhJarSha256":hash(&target.join("webgraph-jmh.jar"))?,
              "nativeSha256":hash(&target.join("graphite"))?}),
    )
}

pub fn run(args: &PrepareArgs) -> Result<Value> {
    let base = args.base_source.canonicalize()?;
    let head = args.head_source.canonicalize()?;
    ensure!(
        base != head,
        "base and head must be distinct dedicated checkouts"
    );
    ensure!(
        !args.output.exists(),
        "prepare requires a fresh output directory"
    );
    fs::create_dir_all(&args.output)?;
    let output = args.output.canonicalize()?;
    let mut results = Vec::new();
    for (role, source) in [("base", &base), ("head", &head)] {
        results.push(match build(source, &head, &output, role) {
            Ok(result) => result,
            Err(error) => json!({"role":role,"passed":false,"error":format!("{error:#}")}),
        });
    }
    let report = json!({"schema":"graphite-benchmark-preparation-v1", "performanceAcceptance":false,
                       "passed":results.iter().all(|r| r["passed"] == true),"builds":results});
    fs::write(
        output.join("preparation.json"),
        serde_json::to_vec_pretty(&report)?,
    )?;
    Ok(report)
}

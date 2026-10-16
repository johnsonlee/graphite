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
    /// Dedicated PR checkout: benchmark source files are installed into this tree.
    #[arg(long)]
    pub head_source: PathBuf,
    /// Independent trusted checkout supplying identical policy and harness for both revisions.
    #[arg(long)]
    pub harness_source: PathBuf,
    #[arg(long)]
    pub output: PathBuf,
}

const HARNESS: &[&str] = &[
    "Fixture64BuildBenchmark.kt",
    "Fixture64GraphPreparation.kt",
    "BenchmarkCorpus.kt",
    "WholeJarBuildSaveBenchmark.kt",
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
        "-Xmx4g -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dgraphite.correctness.only=true".into(),
    );
    env.insert("GRADLE_OPTS".into(), "-Dorg.gradle.daemon=false -Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process".into());
    for key in ["JAVA_OPTS", "JVM_OPTS"] {
        env.insert(
            key.into(),
            "-Xmx4g -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError".into(),
        );
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

fn identity(source: &Path, expression: &str) -> Result<String> {
    let result = std::process::Command::new("git")
        .args(["rev-parse", "--verify", expression])
        .current_dir(source)
        .env("GIT_NO_REPLACE_OBJECTS", "1")
        .output()?;
    ensure!(
        result.status.success(),
        "cannot resolve {expression} in {}",
        source.display()
    );
    let value = String::from_utf8(result.stdout)?.trim().to_owned();
    ensure!(
        value.len() == 40 && value.bytes().all(|c| c.is_ascii_hexdigit()),
        "invalid Git identity"
    );
    Ok(value.to_ascii_lowercase())
}

fn distinct_checkouts(paths: &[&Path]) -> Result<()> {
    for (index, left) in paths.iter().enumerate() {
        for right in &paths[index + 1..] {
            ensure!(
                !left.starts_with(right) && !right.starts_with(left),
                "base, head and trusted harness must be distinct, non-nested checkouts"
            );
        }
    }
    Ok(())
}

// Reject redirected parent directories as well as redirected leaf files before any write.
fn safe_path(root: &Path, relative: &Path) -> Result<PathBuf> {
    let mut path = root.to_owned();
    for component in relative.components() {
        ensure!(
            matches!(component, std::path::Component::Normal(_)),
            "invalid overlay path"
        );
        path.push(component);
        ensure!(
            !path.is_symlink(),
            "redirected harness path: {}",
            path.display()
        );
    }
    Ok(path)
}

fn install_harness(source: &Path, trusted: &Path, target: &Path) -> Result<Vec<Value>> {
    let source_module = module(source)?;
    let trusted_module = module(trusted)?;
    let jmh_relative = Path::new("src/jmh/kotlin/io/johnsonlee/graphite/webgraph");
    let mut files: Vec<PathBuf> = HARNESS.iter().map(|name| jmh_relative.join(name)).collect();
    files.push(
        "src/benchmark/kotlin/io/johnsonlee/graphite/webgraph/LargeCorpusValidation.kt".into(),
    );
    let mut overlays = Vec::new();
    for relative in files {
        let original = safe_path(
            trusted,
            &trusted_module.strip_prefix(trusted)?.join(&relative),
        )?;
        let installed = safe_path(source, &source_module.strip_prefix(source)?.join(&relative))?;
        fs::create_dir_all(installed.parent().context("overlay parent")?)?;
        fs::copy(&original, &installed)?;
        overlays.push(json!({"file":relative,"sha256":hash(&original)?}));
    }
    let original = safe_path(trusted, Path::new("benchmark/jvm/whole-jar.init.gradle"))?;
    fs::copy(&original, target.join("whole-jar.init.gradle"))?;
    overlays.push(json!({"file":"benchmark/jvm/whole-jar.init.gradle","sha256":hash(&original)?}));
    Ok(overlays)
}

fn build(source: &Path, trusted: &Path, output: &Path, role: &str) -> Result<Value> {
    let target = output.join(role);
    fs::create_dir_all(&target)?;
    // Capture production source identity before installing any trusted harness files.
    let revision = identity(source, "HEAD^{commit}")?;
    let source_tree = identity(source, "HEAD^{tree}")?;
    fs::write(target.join("source-tree.txt"), format!("{source_tree}\n"))?;
    let source_module = module(source)?;
    let overlays = install_harness(source, trusted, &target)?;
    let mut args: Vec<OsString> = [
        ":webgraph:jmhJar",
        ":webgraph:prepareBenchmarkFixtures",
        "--no-daemon",
        "--max-workers=2",
        "-Dorg.gradle.jvmargs=-Xmx4g -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError",
    ]
    .into_iter()
    .map(OsString::from)
    .collect();
    args.extend([
        OsString::from("--init-script"),
        target.join("whole-jar.init.gradle").into_os_string(),
    ]);
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
        json!({"role":role,"revision":revision,"sourceTree":source_tree,"passed":true,"overlays":overlays,"fixtures":fixtures,
              "jvmBuild":jvm,"nativeBuild":native,"jmhJarSha256":hash(&target.join("webgraph-jmh.jar"))?,
              "nativeSha256":hash(&target.join("graphite"))?}),
    )
}

pub fn run(args: &PrepareArgs) -> Result<Value> {
    let base = args.base_source.canonicalize()?;
    let head = args.head_source.canonicalize()?;
    let trusted = args.harness_source.canonicalize()?;
    distinct_checkouts(&[&base, &head, &trusted])?;
    let harness_revision = identity(&trusted, "HEAD^{commit}")?;
    let harness_tree = identity(&trusted, "HEAD^{tree}")?;
    ensure!(
        !args.output.exists(),
        "prepare requires a fresh output directory"
    );
    let output_parent = args
        .output
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    ensure!(
        !output_parent.canonicalize()?.starts_with(&trusted),
        "output must not modify the trusted checkout"
    );
    fs::create_dir_all(&args.output)?;
    let output = args.output.canonicalize()?;
    ensure!(
        !output.starts_with(&trusted),
        "output must not modify the trusted checkout"
    );
    let mut results = Vec::new();
    for (role, source) in [("base", &base), ("head", &head)] {
        results.push(match build(source, &trusted, &output, role) {
            Ok(result) => result,
            Err(error) => json!({"role":role,"passed":false,"error":format!("{error:#}")}),
        });
    }
    let report = json!({"schema":"graphite-benchmark-preparation-v1", "performanceAcceptance":false,
                       "harnessRevision":harness_revision,"harnessTree":harness_tree,
                       "passed":results.iter().all(|r| r["passed"] == true),"builds":results});
    fs::write(
        output.join("preparation.json"),
        serde_json::to_vec_pretty(&report)?,
    )?;
    Ok(report)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temporary() -> PathBuf {
        let root = std::env::temp_dir().join(format!(
            "graphite-prepare-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&root).unwrap();
        root
    }

    fn fixture(root: &Path, contents: &str) {
        let module = root.join("frontend/jvm/webgraph");
        let jmh = module.join("src/jmh/kotlin/io/johnsonlee/graphite/webgraph");
        fs::create_dir_all(&jmh).unwrap();
        fs::write(module.join("build.gradle.kts"), "original build").unwrap();
        for name in HARNESS {
            fs::write(jmh.join(name), contents).unwrap();
        }
        let shared = module.join("src/benchmark/kotlin/io/johnsonlee/graphite/webgraph");
        fs::create_dir_all(&shared).unwrap();
        fs::write(shared.join("LargeCorpusValidation.kt"), contents).unwrap();
        fs::create_dir_all(root.join("benchmark/jvm")).unwrap();
        fs::write(root.join("benchmark/jvm/whole-jar.init.gradle"), contents).unwrap();
    }

    #[test]
    fn both_revisions_receive_trusted_policy_without_changing_build_files_or_trust() {
        let root = temporary();
        let trusted = root.join("trusted");
        fixture(&trusted, "reviewed policy");
        for role in ["base", "head"] {
            let source = root.join(role);
            let output = root.join(format!("{role}-output"));
            fixture(&source, "candidate policy must not survive");
            fs::create_dir(&output).unwrap();
            let overlays = install_harness(&source, &trusted, &output).unwrap();
            assert_eq!(overlays.len(), 6);
            for file in overlays.iter().take(5) {
                let relative = file["file"].as_str().unwrap();
                assert_eq!(
                    fs::read_to_string(module(&source).unwrap().join(relative)).unwrap(),
                    "reviewed policy"
                );
                assert_eq!(
                    fs::read_to_string(module(&trusted).unwrap().join(relative)).unwrap(),
                    "reviewed policy"
                );
            }
            assert_eq!(
                fs::read_to_string(output.join("whole-jar.init.gradle")).unwrap(),
                "reviewed policy"
            );
            assert_eq!(
                fs::read_to_string(module(&source).unwrap().join("build.gradle.kts")).unwrap(),
                "original build"
            );
        }
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn overlapping_checkouts_and_redirected_overlay_directories_are_rejected() {
        assert!(distinct_checkouts(&[
            Path::new("/base"),
            Path::new("/head"),
            Path::new("/trusted")
        ])
        .is_ok());
        assert!(distinct_checkouts(&[
            Path::new("/trusted/base"),
            Path::new("/head"),
            Path::new("/trusted")
        ])
        .is_err());
        assert!(distinct_checkouts(&[Path::new("/same"), Path::new("/same")]).is_err());
        let root = temporary();
        #[cfg(unix)]
        {
            std::os::unix::fs::symlink("/tmp", root.join("redirect")).unwrap();
            assert!(safe_path(&root, Path::new("redirect/new-file")).is_err());
        }
        assert!(safe_path(&root, Path::new("../escape")).is_err());
        fs::remove_dir_all(root).unwrap();
    }
}

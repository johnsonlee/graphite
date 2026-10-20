//! One owner for phase ordering, checkout identities and artifact paths.
use crate::{jmh, native, prepare, process, report};
use anyhow::{ensure, Context, Result};
use clap::Args;
use serde_json::{json, Value};
use std::{
    fs,
    path::{Path, PathBuf},
};

#[derive(Debug, Args)]
pub struct RunArgs {
    /// Dedicated product checkout; trusted validation sources are installed here.
    #[arg(long)]
    pub base_source: PathBuf,
    #[arg(long)]
    pub head_source: PathBuf,
    /// Independent trusted checkout supplying both arms' validation harness.
    #[arg(long)]
    pub harness_source: PathBuf,
    /// Fresh directory containing prepared/, results/ and report/.
    #[arg(long)]
    pub output: PathBuf,
}

fn suite(
    name: &str,
    base: &str,
    head: &str,
    output: &Path,
    run: impl FnOnce() -> Result<Value>,
) -> Result<()> {
    let result = if process::is_cancelled() {
        Err(anyhow::anyhow!("run cancelled"))
    } else {
        run()
    };
    let value = result
        .unwrap_or_else(|error| report::failed_suite(name, base, head, &format!("{error:#}")));
    report::write_suite(output, &value)?;
    Ok(())
}

fn execute(args: &RunArgs, errors: &mut Vec<String>) -> Result<()> {
    // Read committed identities before prepare installs any trusted sources.
    let base = prepare::identity(&args.base_source, "HEAD^{commit}")?;
    let head = prepare::identity(&args.head_source, "HEAD^{commit}")?;
    let prepared = args.output.join("prepared");
    let frontend = args.output.join("results/frontend");
    let native_output = args.output.join("results/native");
    ensure!(!process::is_cancelled(), "run cancelled");
    if let Err(error) = prepare::run(&prepare::PrepareArgs {
        base_source: args.base_source.clone(),
        head_source: args.head_source.clone(),
        harness_source: args.harness_source.clone(),
        output: prepared.clone(),
    }) {
        errors.push(format!("Preparation: {error:#}"));
    }
    // Evaluate both phases even when saving the first phase's evidence fails.
    for result in [
        suite("frontend-build-save", &base, &head, &frontend, || {
            jmh::run(&jmh::JmhArgs {
                base_jar: prepared.join("base/webgraph-jmh.jar"),
                head_jar: prepared.join("head/webgraph-jmh.jar"),
                fixtures: prepared.join("fixtures"),
                output: frontend.clone(),
                base_sha: base.clone(),
                head_sha: head.clone(),
                base_label: "PR merge-base".into(),
            })
        }),
        suite("native-real64", &base, &head, &native_output, || {
            native::run(&native::NativeArgs {
                base: prepared.join("base/graphite"),
                head: prepared.join("head/graphite"),
                base_manifest: frontend.join("base/graphs/graphs.tsv"),
                head_manifest: frontend.join("head/graphs/graphs.tsv"),
                output: native_output.clone(),
                base_sha: base.clone(),
                head_sha: head.clone(),
            })
        }),
    ] {
        if let Err(error) = result {
            errors.push(format!("Saving suite evidence: {error:#}"));
        }
    }
    ensure!(!process::is_cancelled(), "run cancelled");
    Ok(())
}

pub fn run(args: &RunArgs) -> Result<bool> {
    // Refuse stale evidence, and never create output inside the trusted checkout.
    let parent = args
        .output
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    let trusted = args.harness_source.canonicalize()?;
    ensure!(
        !parent.canonicalize()?.starts_with(&trusted),
        "output must not modify the trusted checkout"
    );
    fs::create_dir(&args.output).context("run requires a fresh output directory")?;
    let mut errors = Vec::new();
    if let Err(error) = execute(args, &mut errors) {
        errors.push(format!("Pipeline: {error:#}"));
    }
    fs::write(
        args.output.join("execution.json"),
        serde_json::to_vec_pretty(&json!({
            "schema": "graphite-benchmark-execution-v1", "errors": errors,
        }))?,
    )?;
    render(&args.output)
}

/// Also called by the workflow's always() step after a killed controller process.
pub fn render(root: &Path) -> Result<bool> {
    let mut errors = Vec::new();
    let evidence = (|| -> Result<()> {
        let execution: Value = serde_json::from_slice(
            &fs::read(root.join("execution.json"))
                .context("execution did not finish; run may have been interrupted")?,
        )?;
        ensure!(
            execution["schema"] == "graphite-benchmark-execution-v1",
            "invalid execution evidence"
        );
        for error in execution["errors"]
            .as_array()
            .context("missing execution errors")?
        {
            errors.push(
                error
                    .as_str()
                    .context("invalid execution error")?
                    .to_owned(),
            );
        }
        let preparation: Value = serde_json::from_slice(
            &fs::read(root.join("prepared/preparation.json"))
                .context("preparation evidence unavailable")?,
        )?;
        ensure!(
            preparation["schema"] == "graphite-benchmark-preparation-v1"
                && preparation["passed"] == true,
            "Preparation failed; see prepared/preparation.json"
        );
        Ok(())
    })();
    if let Err(error) = evidence {
        errors.push(format!("{error:#}"));
    }
    report::aggregate_with_errors(&root.join("results"), &root.join("report"), errors)
}

#[cfg(test)]
#[path = "pipeline_tests.rs"]
mod tests;

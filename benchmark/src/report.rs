//! Reports keep measurements separate from correctness and heap acceptance.
use crate::plan::SCHEMA;
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::Path,
};

fn sha(value: &Value, field: &str) -> Result<String> {
    let text = value[field]
        .as_str()
        .with_context(|| format!("missing {field}"))?;
    anyhow::ensure!(
        text.len() == 40 && text.bytes().all(|c| c.is_ascii_hexdigit()),
        "{field} must be the full Git revision"
    );
    Ok(text.to_lowercase())
}

pub fn validate_suite(value: &Value) -> Result<bool> {
    anyhow::ensure!(value["schema"] == SCHEMA, "unsupported suite report schema");
    anyhow::ensure!(
        value["suite"].as_str().is_some_and(|s| !s.is_empty()),
        "missing suite name"
    );
    sha(value, "baseSha")?;
    sha(value, "headSha")?;
    let checks = value["checks"].as_array().context("missing checks")?;
    let mut kinds = BTreeSet::new();
    let mut names = BTreeSet::new();
    let mut passed = true;
    for check in checks {
        let kind = check["kind"].as_str().context("check has no kind")?;
        anyhow::ensure!(
            ["correctness", "heap", "coverage"].contains(&kind),
            "invalid gate kind: {kind}"
        );
        kinds.insert(kind);
        let name = check["name"].as_str().context("check has no name")?;
        anyhow::ensure!(
            !name.is_empty() && names.insert(name),
            "empty or duplicate check: {name}"
        );
        anyhow::ensure!(
            check["detail"].is_string(),
            "check has no explanatory detail"
        );
        passed &= check["passed"]
            .as_bool()
            .context("check has no boolean result")?;
    }
    anyhow::ensure!(
        kinds.len() == 3,
        "suite must report correctness, heap and required coverage"
    );
    let timings = value["timings"]
        .as_array()
        .context("missing timing observations")?;
    let mut identities = BTreeSet::new();
    for timing in timings {
        let mut identity = Vec::new();
        for field in ["case", "stage", "workload", "method"] {
            let text = timing[field]
                .as_str()
                .with_context(|| format!("timing has no {field}"))?;
            anyhow::ensure!(!text.is_empty(), "empty timing {field}");
            identity.push(text);
        }
        anyhow::ensure!(identities.insert(identity), "duplicate timing case");
        let comparable = timing["comparable"]
            .as_bool()
            .context("timing comparability missing")?;
        for (metric, samples) in [("baseMs", "samplesBase"), ("headMs", "samplesHead")] {
            let count = timing[samples]
                .as_u64()
                .with_context(|| format!("invalid {samples}"))?;
            if let Some(ms) = timing[metric].as_f64() {
                anyhow::ensure!(
                    ms.is_finite() && ms >= 0.0 && count > 0,
                    "invalid {metric} or sample count"
                );
            } else {
                anyhow::ensure!(
                    timing[metric].is_null() && timing.get(metric).is_some(),
                    "invalid {metric}"
                );
                anyhow::ensure!(!comparable, "cannot compare a missing timing");
            }
        }
        if !comparable {
            anyhow::ensure!(
                timing["unavailableReason"]
                    .as_str()
                    .is_some_and(|s| !s.is_empty()),
                "incomparable timing needs a reason"
            );
        }
    }
    anyhow::ensure!(
        value["artifacts"]
            .as_array()
            .is_some_and(|a| a.iter().all(Value::is_string)),
        "invalid artifact paths"
    );
    Ok(passed)
}

pub fn failed_suite(suite: &str, base: &str, head: &str, error: &str) -> Value {
    json!({"schema": SCHEMA, "suite": suite, "baseSha": base, "headSha": head,
        "checks": [
            {"name":"runner", "kind":"correctness", "passed":false, "detail":error},
            {"name":"heap evidence", "kind":"heap", "passed":false, "detail":"Required execution did not complete"},
            {"name":"required coverage", "kind":"coverage", "passed":false, "detail":"Required execution did not complete"}],
        "timings":[], "artifacts":[]})
}

fn escape(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&#39;")
}

fn cell(text: &str) -> String {
    escape(text)
        .replace('|', "&#124;")
        .replace(['\r', '\n'], " ")
}

fn format_ms(value: Option<f64>) -> String {
    value
        .map(|v| format!("{v:.3}"))
        .unwrap_or_else(|| "UNAVAILABLE".into())
}

fn timing_columns(t: &Value) -> Vec<String> {
    let base = t["baseMs"].as_f64();
    let head = t["headMs"].as_f64();
    let comparable = t["comparable"] == true;
    let delta = if comparable {
        base.zip(head).map(|(b, h)| h - b)
    } else {
        None
    };
    let percent = if comparable {
        base.zip(head)
            .filter(|(b, _)| *b != 0.0)
            .map(|(b, h)| (h - b) / b * 100.0)
    } else {
        None
    };
    vec![
        t["case"].as_str().unwrap_or("").to_owned(),
        t["stage"].as_str().unwrap_or("").to_owned(),
        t["workload"].as_str().unwrap_or("").to_owned(),
        t["method"].as_str().unwrap_or("").to_owned(),
        format!("{}/{}", t["samplesBase"], t["samplesHead"]),
        format_ms(base),
        format_ms(head),
        format_ms(delta),
        percent.map(|v| format!("{v:+.2}%")).unwrap_or_else(|| {
            if comparable && base == Some(0.0) {
                "undefined (base zero)".into()
            } else {
                "UNAVAILABLE".into()
            }
        }),
        if comparable {
            "report only".into()
        } else {
            t["unavailableReason"]
                .as_str()
                .unwrap_or("UNAVAILABLE")
                .into()
        },
    ]
}

pub fn write_suite(output: &Path, value: &Value) -> Result<bool> {
    // Persist failure evidence even if validation detects a malformed adapter report.
    fs::create_dir_all(output)?;
    let mut value = value.clone();
    add_comparisons(&mut value);
    fs::write(
        output.join("suite.json"),
        format!("{}\n", serde_json::to_string_pretty(&value)?),
    )?;
    let (passed, errors) = match validate_suite(&value) {
        Ok(passed) => (passed, Vec::new()),
        Err(error) => (false, vec![format!("Invalid suite evidence: {error:#}")]),
    };
    write_rendered(output, &[value], &errors, passed)?;
    Ok(passed)
}

fn add_comparisons(value: &mut Value) {
    value["performanceAcceptance"] = json!(false);
    if let Some(timings) = value["timings"].as_array_mut() {
        for timing in timings {
            let pair = timing["baseMs"]
                .as_f64()
                .zip(timing["headMs"].as_f64())
                .filter(|_| timing["comparable"] == true);
            timing["deltaMs"] = pair
                .map(|(base, head)| json!(head - base))
                .unwrap_or(Value::Null);
            timing["deltaPercent"] = pair
                .filter(|(base, _)| *base != 0.0)
                .map(|(base, head)| json!((head - base) / base * 100.0))
                .unwrap_or(Value::Null);
        }
    }
}

fn write_rendered(output: &Path, suites: &[Value], errors: &[String], passed: bool) -> Result<()> {
    fs::create_dir_all(output)?;
    let status = if passed { "PASS" } else { "FAIL" };
    let mut md = format!("# Benchmark correctness and OOM: {status}\n\nTiming is report-only. No latency, throughput, CPU or RSS threshold affects acceptance. These GitHub runner observations do not establish performance regression acceptance. Missing observations are UNAVAILABLE.\n");
    let mut html = format!("<!doctype html><meta charset=\"utf-8\"><title>Benchmark report</title><style>body{{font-family:system-ui;margin:2rem}}table{{border-collapse:collapse;width:100%;margin:1rem 0}}td,th{{border:1px solid #ccc;padding:.5rem;text-align:left}}code{{overflow-wrap:anywhere}}</style><h1>Benchmark correctness and OOM: {status}</h1><p>Timing is report-only. No latency, throughput, CPU or RSS threshold affects acceptance. These GitHub runner observations do not establish performance regression acceptance. Missing observations are UNAVAILABLE.</p>");
    for error in errors {
        md.push_str(&format!("\n- {}\n", cell(error)));
        html.push_str(&format!("<p>{}</p>", escape(error)));
    }
    for suite in suites {
        let name = suite["suite"].as_str().unwrap_or("unknown");
        md.push_str(&format!("\n## {}\n\nBase: `{}`  \nHead: `{}`\n\n| Gate | Kind | Result | Detail |\n| --- | --- | --- | --- |\n", cell(name), cell(suite["baseSha"].as_str().unwrap_or("unknown")), cell(suite["headSha"].as_str().unwrap_or("unknown"))));
        html.push_str(&format!("<h2>{}</h2><p>Base: <code>{}</code><br>Head: <code>{}</code></p><table><tr><th>Gate</th><th>Kind</th><th>Result</th><th>Detail</th></tr>", escape(name), escape(suite["baseSha"].as_str().unwrap_or("unknown")), escape(suite["headSha"].as_str().unwrap_or("unknown"))));
        if let Some(checks) = suite["checks"].as_array() {
            for check in checks {
                let cols = [
                    check["name"].as_str().unwrap_or("?"),
                    check["kind"].as_str().unwrap_or("?"),
                    if check["passed"] == true {
                        "PASS"
                    } else {
                        "FAIL"
                    },
                    check["detail"].as_str().unwrap_or("?"),
                ];
                md.push_str(&format!(
                    "| {} |\n",
                    cols.iter().map(|s| cell(s)).collect::<Vec<_>>().join(" | ")
                ));
                html.push_str(&format!(
                    "<tr>{}</tr>",
                    cols.iter()
                        .map(|s| format!("<td>{}</td>", escape(s)))
                        .collect::<String>()
                ));
            }
        }
        html.push_str("</table>");
        for (role, status, reason) in [
            ("Base", "baseStatus", "baseUnavailableReason"),
            ("Head", "headStatus", "headUnavailableReason"),
        ] {
            if suite[status] == "UNAVAILABLE" {
                let detail = suite[reason]
                    .as_str()
                    .unwrap_or("Execution did not produce complete evidence");
                md.push_str(&format!("\n{role}: UNAVAILABLE — {}\n", cell(detail)));
                html.push_str(&format!("<p>{role}: UNAVAILABLE — {}</p>", escape(detail)));
            }
        }
        let headings = [
            "Case",
            "Stage",
            "Workload",
            "Method",
            "Samples base/head",
            "Base ms",
            "Head ms",
            "Delta ms",
            "Delta %",
            "Evidence",
        ];
        md.push_str(&format!(
            "\n| {} |\n| {} |\n",
            headings.join(" | "),
            vec!["---"; headings.len()].join(" | ")
        ));
        html.push_str(&format!(
            "<table><tr>{}</tr>",
            headings
                .iter()
                .map(|s| format!("<th>{s}</th>"))
                .collect::<String>()
        ));
        if let Some(timings) = suite["timings"].as_array() {
            for timing in timings {
                let cols = timing_columns(timing);
                md.push_str(&format!(
                    "| {} |\n",
                    cols.iter().map(|s| cell(s)).collect::<Vec<_>>().join(" | ")
                ));
                html.push_str(&format!(
                    "<tr>{}</tr>",
                    cols.iter()
                        .map(|s| format!("<td>{}</td>", escape(s)))
                        .collect::<String>()
                ));
            }
            if timings.is_empty() {
                md.push_str("\nTiming: UNAVAILABLE.\n");
                html.push_str("<caption>Timing: UNAVAILABLE</caption>");
            }
        }
        html.push_str("</table>");
        md.push_str("\nRaw artifacts are retained alongside the suite JSON.\n");
    }
    fs::write(output.join("report.md"), md)?;
    fs::write(output.join("report.html"), html)?;
    write_summary(output, suites, errors, passed)?;
    Ok(())
}

fn write_summary(output: &Path, suites: &[Value], errors: &[String], passed: bool) -> Result<()> {
    let mut text = format!("## Benchmark correctness and OOM: {}\n\nTiming differences are **report-only**; correctness, OOM and required coverage decide this gate. GitHub runner timings do not establish performance acceptance.\n", if passed { "PASS" } else { "FAIL" });
    if let Some(suite) = suites.first() {
        text.push_str(&format!(
            "\nBase: `{}`  \nHead: `{}`\n",
            cell(suite["baseSha"].as_str().unwrap_or("UNAVAILABLE")),
            cell(suite["headSha"].as_str().unwrap_or("UNAVAILABLE"))
        ));
    }
    for error in errors {
        text.push_str(&format!("\n- {}\n", cell(error)));
    }
    for suite in suites {
        text.push_str(&format!(
            "\n### {}\n\n",
            cell(suite["suite"].as_str().unwrap_or("unknown"))
        ));
        if let Some(checks) = suite["checks"].as_array() {
            for check in checks {
                text.push_str(&format!(
                    "- **{}** {}: {}\n",
                    if check["passed"] == true {
                        "PASS"
                    } else {
                        "FAIL"
                    },
                    cell(check["name"].as_str().unwrap_or("?")),
                    cell(check["detail"].as_str().unwrap_or("?"))
                ));
            }
        }
        text.push_str("\n| Case | Stage | Measurement | N base/head | Base ms | Head ms | Delta ms | Delta % |\n| --- | --- | --- | --- | ---: | ---: | ---: | ---: |\n");
        if let Some(timings) = suite["timings"].as_array() {
            for timing in timings {
                // The comment shows primary pressure observations; isolated samples stay in the full report.
                if timing["stage"] == "isolated" {
                    continue;
                }
                let cols = timing_columns(timing);
                let method = cols[3].split(';').next().unwrap_or(&cols[3]);
                text.push_str(&format!(
                    "| {} |\n",
                    [
                        cols[0].as_str(),
                        cols[1].as_str(),
                        method,
                        cols[4].as_str(),
                        cols[5].as_str(),
                        cols[6].as_str(),
                        cols[7].as_str(),
                        cols[8].as_str()
                    ]
                    .iter()
                    .map(|s| cell(s))
                    .collect::<Vec<_>>()
                    .join(" | ")
                ));
            }
        }
    }
    text.push_str("\nFull methods, comparability reasons, isolated observations, raw request bodies and logs are retained in the downloadable report/evidence artifacts. UNAVAILABLE never means zero; a zero baseline has no defined percentage change.\n");
    // Reserve room for trusted run/artifact links under GitHub's comment limit.
    if text.len() > 55_000 {
        text = format!("## Benchmark correctness and OOM: {}\n\nThe complete report exceeds the comment size limit. See the report artifact for all gate results and measurements. Timing remains report-only; no entries have been dropped from the full report.\n", if passed { "PASS" } else { "FAIL" });
    }
    fs::write(output.join("summary.md"), text)?;
    Ok(())
}

fn collect_suites(path: &Path, results: &mut Vec<Value>) -> Result<()> {
    anyhow::ensure!(
        !fs::symlink_metadata(path)?.file_type().is_symlink(),
        "report input must not be a symlink"
    );
    if path.is_dir() {
        let mut entries = fs::read_dir(path)?
            .map(|entry| entry.map(|e| e.path()))
            .collect::<std::io::Result<Vec<_>>>()?;
        entries.sort();
        for child in entries {
            if child.is_dir() || child.file_name().is_some_and(|s| s == "suite.json") {
                collect_suites(&child, results)?;
            }
        }
    } else {
        let content = fs::read(path)?;
        results.push(
            serde_json::from_slice(&content)
                .with_context(|| format!("parse {}", path.display()))?,
        );
    }
    Ok(())
}

/// Aggregate only fixed suite filenames, requiring every mandatory suite exactly once.
pub fn aggregate(input: &Path, output: &Path, required: &[&str]) -> Result<bool> {
    aggregate_count(input, output, required, 1, Vec::new())
}

pub(crate) fn aggregate_with_errors(
    input: &Path,
    output: &Path,
    errors: Vec<String>,
) -> Result<bool> {
    aggregate_count(input, output, crate::plan::REQUIRED_SUITES, 1, errors)
}

fn aggregate_count(
    input: &Path,
    output: &Path,
    required: &[&str],
    count: usize,
    mut errors: Vec<String>,
) -> Result<bool> {
    let mut suites = Vec::new();
    if let Err(error) = collect_suites(input, &mut suites) {
        errors.push(format!("Incomplete report inputs: {error:#}"));
    }
    let mut seen: BTreeMap<&str, BTreeSet<&str>> = BTreeMap::new();
    let mut head_revision = None;
    let mut passed = true;
    for suite in &suites {
        match validate_suite(suite) {
            Ok(ok) => passed &= ok,
            Err(error) => errors.push(format!("Invalid suite evidence: {error:#}")),
        }
        let name = suite["suite"].as_str().unwrap_or("");
        let base = suite["baseSha"].as_str().unwrap_or("");
        let head = suite["headSha"].as_str().unwrap_or("");
        if !seen.entry(base).or_default().insert(name) {
            errors.push(format!("Duplicate suite for base {base}: {name}"));
        }
        if !required.contains(&name) {
            errors.push(format!("Unexpected suite: {name}"));
        }
        if let Some(previous) = head_revision {
            if previous != head {
                errors.push("Suites use different head revisions".into());
            }
        } else {
            head_revision = Some(head);
        }
    }
    if seen.len() != count {
        errors.push(format!(
            "Expected {count} base/head comparison; found {}",
            seen.len()
        ));
    }
    for (base, names) in &seen {
        for name in required {
            if !names.contains(name) {
                errors.push(format!("Missing required suite for base {base}: {name}"));
            }
        }
    }
    if suites.is_empty() {
        errors.push("No suite evidence available".into());
    }
    passed &= errors.is_empty();
    let base_sha = suites
        .first()
        .and_then(|s| s["baseSha"].as_str())
        .map(str::to_owned);
    let head_sha = suites
        .first()
        .and_then(|s| s["headSha"].as_str())
        .map(str::to_owned);
    for suite in &mut suites {
        add_comparisons(suite);
    }
    fs::create_dir_all(output)?;
    fs::write(
        output.join("report.json"),
        format!(
            "{}\n",
            serde_json::to_string_pretty(
                &json!({"schema":"graphite-benchmark-report-v1", "baseSha":base_sha, "headSha":head_sha, "passed":passed, "performanceAcceptance":false, "requiredSuites":required, "errors":errors, "suites":suites})
            )?
        ),
    )?;
    write_rendered(output, &suites, &errors, passed)?;
    Ok(passed)
}

pub fn require_success(passed: bool) -> Result<()> {
    if !passed {
        bail!("correctness, heap or required coverage gate failed; see report artifacts");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);

    fn passing() -> Value {
        let mut value = failed_suite("native", &"a".repeat(40), &"b".repeat(40), "test");
        for check in value["checks"].as_array_mut().unwrap() {
            check["passed"] = json!(true);
        }
        value["timings"] = json!([{"case":"large query", "stage":"query", "workload":"64 real graphs", "method":"complete response wall time", "baseMs":1.0, "headMs":1000000.0, "samplesBase":1, "samplesHead":1, "comparable":true}]);
        value
    }

    fn directory() -> std::path::PathBuf {
        std::env::temp_dir().join(format!(
            "graphite-report-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ))
    }

    #[test]
    fn arbitrarily_slower_timing_does_not_fail_gate() {
        assert!(validate_suite(&passing()).unwrap());
    }

    #[test]
    fn correctness_failure_blocks_even_with_faster_timing() {
        let mut value = passing();
        value["timings"][0]["headMs"] = json!(0.01);
        value["checks"][0]["passed"] = json!(false);
        assert!(!validate_suite(&value).unwrap());
    }

    #[test]
    fn missing_heap_evidence_and_duplicate_checks_are_invalid() {
        let mut value = passing();
        value["checks"].as_array_mut().unwrap().remove(1);
        assert!(validate_suite(&value).is_err());
        let mut value = passing();
        let check = value["checks"][0].clone();
        value["checks"].as_array_mut().unwrap().push(check);
        assert!(validate_suite(&value).is_err());
    }

    #[test]
    fn missing_timing_never_displays_zero_or_comparison() {
        let mut value = passing();
        value["timings"][0]["headMs"] = Value::Null;
        assert!(validate_suite(&value).is_err());
        value["timings"][0]["comparable"] = json!(false);
        value["timings"][0]["unavailableReason"] = json!("execution failed");
        assert!(validate_suite(&value).is_ok());
        let columns = timing_columns(&value["timings"][0]);
        assert_eq!(columns[6], "UNAVAILABLE");
        assert_eq!(columns[7], "UNAVAILABLE");
    }

    #[test]
    fn zero_baseline_has_no_percentage() {
        let mut value = passing();
        value["timings"][0]["baseMs"] = json!(0);
        assert_eq!(
            timing_columns(&value["timings"][0])[8],
            "undefined (base zero)"
        );
    }

    #[test]
    fn missing_suite_and_mixed_revision_fail_aggregate() {
        let path = directory();
        write_suite(&path.join("input/native"), &passing()).unwrap();
        assert!(!aggregate_count(
            &path.join("input"),
            &path.join("output"),
            &["native", "methods"],
            1,
            Vec::new()
        )
        .unwrap());
        let mut other = passing();
        other["suite"] = json!("methods");
        other["headSha"] = json!("c".repeat(40));
        write_suite(&path.join("input/methods"), &other).unwrap();
        assert!(!aggregate_count(
            &path.join("input"),
            &path.join("output"),
            &["native", "methods"],
            1,
            Vec::new()
        )
        .unwrap());
        let report: Value =
            serde_json::from_slice(&fs::read(path.join("output/report.json")).unwrap()).unwrap();
        assert!(report["errors"]
            .as_array()
            .unwrap()
            .iter()
            .any(|e| e.as_str().unwrap().contains("different head")));
        fs::remove_dir_all(path).unwrap();
    }

    #[test]
    fn html_escapes_untrusted_case_and_failure_detail() {
        let path = directory();
        let mut value = passing();
        value["timings"][0]["case"] = json!("<script>alert(1)</script>");
        write_suite(&path, &value).unwrap();
        let html = fs::read_to_string(path.join("report.html")).unwrap();
        assert!(!html.contains("<script>"));
        assert!(html.contains("&lt;script&gt;"));
        fs::remove_dir_all(path).unwrap();
    }

    #[test]
    fn complete_pair_reports_real_deltas_without_performance_gate() {
        let path = directory();
        for name in crate::plan::REQUIRED_SUITES {
            let mut value = passing();
            value["suite"] = json!(name);
            write_suite(&path.join("input").join(name), &value).unwrap();
        }
        assert!(aggregate(
            &path.join("input"),
            &path.join("output"),
            crate::plan::REQUIRED_SUITES
        )
        .unwrap());
        let report: Value =
            serde_json::from_slice(&fs::read(path.join("output/report.json")).unwrap()).unwrap();
        assert_eq!(report["baseSha"], "a".repeat(40));
        assert_eq!(report["headSha"], "b".repeat(40));
        assert_eq!(report["performanceAcceptance"], false);
        assert_eq!(report["suites"][0]["timings"][0]["deltaMs"], 999999.0);
        assert_eq!(
            report["suites"][0]["timings"][0]["deltaPercent"],
            99999900.0
        );
        let summary = fs::read_to_string(path.join("output/summary.md")).unwrap();
        assert!(summary.contains("report-only"));
        assert!(summary.contains("1000000.000"));
        assert!(summary.len() < 55_000);
        fs::remove_dir_all(path).unwrap();
    }
}

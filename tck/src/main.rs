mod acquisition;
mod corpus;
mod runner;
mod values;

use anyhow::{Context, Result};
use clap::Parser;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{collections::BTreeMap, fs, path::PathBuf};

// Match the existing inventory's sorted, UTF-8 Python JSON encoding. In
// particular, keep integer/float types and Python's signed two-digit exponents.
fn canonical(value: &Value) -> String {
    match value {
        Value::Object(map) => {
            let sorted: BTreeMap<_, _> = map.iter().collect();
            format!(
                "{{{}}}",
                sorted
                    .into_iter()
                    .map(|(k, v)| format!("{}:{}", serde_json::to_string(k).unwrap(), canonical(v)))
                    .collect::<Vec<_>>()
                    .join(",")
            )
        }
        Value::Array(values) => format!(
            "[{}]",
            values.iter().map(canonical).collect::<Vec<_>>().join(",")
        ),
        Value::Number(n) if n.is_f64() => {
            let text = format!("{:?}", n.as_f64().unwrap());
            if let Some((mantissa, exponent)) = text.split_once('e') {
                let exponent: i32 = exponent.parse().unwrap();
                format!("{mantissa}e{exponent:+03}")
            } else {
                text
            }
        }
        _ => serde_json::to_string(value).unwrap(),
    }
}

fn digest(value: &Value) -> String {
    Sha256::digest(canonical(value).as_bytes())
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

fn all_scenarios_passed(results: &[Value]) -> bool {
    !results.is_empty()
        && results
            .iter()
            .all(|result| result["status"] == "passed" && result["executed"] == true)
}

fn inventory_matches_pin(results: &[Value], pin: &Value) -> bool {
    if pin["count"].as_u64() != Some(results.len() as u64) {
        return false;
    }
    let mut identities = BTreeMap::new();
    for result in results {
        let (Some(id), Some(hash)) = (result["id"].as_str(), result["scenarioSha256"].as_str())
        else {
            return false;
        };
        if identities.insert(id, hash).is_some() {
            return false;
        }
    }
    pin["sha256"].as_str() == Some(digest(&json!(identities)).as_str())
}

fn record_verified_baseline(
    path: &std::path::Path,
    signature: &Value,
    verified: bool,
) -> Result<bool> {
    if !verified {
        return Ok(false);
    }
    let sorted: Value = serde_json::from_str(&canonical(signature))?;
    fs::write(path, serde_json::to_string_pretty(&sorted)? + "\n")?;
    Ok(true)
}

#[derive(Parser)]
#[command(about = "Run pinned official openCypher TCK scenarios against Graphite")]
struct Args {
    #[arg(long, required_unless_present_any = ["prepare_only", "inventory_output"])]
    probe: Option<PathBuf>,
    #[arg(long, required_unless_present_any = ["prepare_only", "inventory_output"])]
    output: Option<PathBuf>,
    #[arg(long, default_value = env!("CARGO_MANIFEST_DIR"))]
    root: PathBuf,
    #[arg(long)]
    baseline: Option<PathBuf>,
    #[arg(long)]
    record_baseline: bool,
    #[arg(long)]
    prepare_only: bool,
    #[arg(long)]
    inventory_output: Option<PathBuf>,
}

fn execute(args: Args) -> Result<bool> {
    let upstream = acquisition::ensure_upstream(&args.root)?;
    if let Some(path) = args.inventory_output {
        fs::write(
            path,
            serde_json::to_string_pretty(&corpus::scenarios(&args.root)?)? + "\n",
        )?;
        return Ok(true);
    }
    if args.prepare_only {
        println!("Verified pinned official TCK cache");
        return Ok(true);
    }
    let run = runner::run(&args.root, args.probe.as_ref().unwrap())?;
    let mut counts: BTreeMap<String, usize> = BTreeMap::new();
    for result in &run.results {
        *counts
            .entry(
                result["status"]
                    .as_str()
                    .context("missing status")?
                    .to_owned(),
            )
            .or_default() += 1;
    }
    let signature = runner::baseline_signature(&run.results);
    let all_passed = all_scenarios_passed(&run.results);
    let inventory_verified = inventory_matches_pin(&run.results, &upstream["scenarioInventory"]);
    let baseline = args
        .baseline
        .unwrap_or_else(|| args.root.join("baseline.json"));
    let baseline_recorded = args.record_baseline
        && record_verified_baseline(
            &baseline,
            &signature,
            all_passed && inventory_verified && run.infrastructure_errors.is_empty(),
        )?;
    let changes = if args.record_baseline {
        vec![]
    } else {
        let expected: Value = serde_json::from_slice(&fs::read(&baseline)?)?;
        runner::baseline_changes(&expected, &signature)
    };
    let passed = all_passed
        && inventory_verified
        && changes.is_empty()
        && run.infrastructure_errors.is_empty();
    let report = json!({"upstream":upstream,"counts":counts,"total":run.results.len(),"baselineChanges":changes,"gatePassed":passed,"allScenariosPassed":all_passed,"inventoryVerified":inventory_verified,"baselineRecorded":baseline_recorded,"infrastructureErrors":run.infrastructure_errors,"scenarios":run.results,"dialect":"opencypher-m23","scope":"official openCypher M23 scenarios executed by the production engine in opencypher-m23 mode; unsupported is never pass"});
    let output = args.output.unwrap();
    fs::create_dir_all(&output)?;
    fs::write(
        output.join("report.json"),
        serde_json::to_string_pretty(&report)? + "\n",
    )?;
    let mut markdown = format!("# openCypher TCK compatibility inventory\n\nDialect: `opencypher-m23` (production executor).\n\nTotal: {}; {}\n\nGate requires every official scenario to execute and pass, no infrastructure errors, and the exact reviewed inventory and outcomes. This covers the pinned M23 suite, not every possible Cypher query.\n\n| Feature | Passed | Failed | Unsupported | Not run |\n|---|---:|---:|---:|---:|\n", run.results.len(), counts.iter().map(|(k,v)| format!("{k}: {v}")).collect::<Vec<_>>().join("; "));
    let mut features: BTreeMap<String, BTreeMap<String, usize>> = BTreeMap::new();
    for r in &run.results {
        *features
            .entry(r["feature"].as_str().context("missing feature")?.to_owned())
            .or_default()
            .entry(r["status"].as_str().unwrap().to_owned())
            .or_default() += 1;
    }
    for (feature, counts) in features {
        markdown += &format!(
            "| {feature} | {} | {} | {} | {} |\n",
            counts.get("passed").unwrap_or(&0),
            counts.get("failed").unwrap_or(&0),
            counts.get("unsupported").unwrap_or(&0),
            counts.get("not_run").unwrap_or(&0)
        );
    }
    if !run.infrastructure_errors.is_empty() {
        markdown += "\n## Infrastructure failures\n\n";
        for error in &run.infrastructure_errors {
            markdown += &format!("- {error}\n");
        }
    }
    fs::write(output.join("report.md"), markdown)?;
    println!(
        "{}",
        json!({"total":run.results.len(),"counts":counts,"baselineChanges":changes.len(),"gatePassed":passed,"inventoryVerified":inventory_verified,"infrastructureErrors":run.infrastructure_errors})
    );
    Ok(passed)
}

fn main() {
    match execute(Args::parse()) {
        Ok(true) => {}
        Ok(false) => std::process::exit(1),
        Err(error) => {
            eprintln!("TCK infrastructure error: {error:#}");
            std::process::exit(1);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn baseline_recording_requires_the_complete_pinned_inventory() {
        let complete = vec![
            json!({"id":"one","scenarioSha256":"first","status":"passed","executed":true}),
            json!({"id":"two","scenarioSha256":"second","status":"passed","executed":true}),
        ];
        let pin = json!({"count":2,"sha256":digest(&json!({"one":"first","two":"second"}))});
        assert!(inventory_matches_pin(&complete, &pin));
        let directory = tempfile::tempdir().unwrap();
        let baseline = directory.path().join("baseline.json");
        fs::write(&baseline, b"reviewed baseline").unwrap();
        for incomplete in [
            vec![complete[0].clone()],
            vec![complete[0].clone(), complete[0].clone()],
            vec![
                complete[0].clone(),
                json!({"id":"two","scenarioSha256":"changed","status":"passed","executed":true}),
            ],
        ] {
            assert!(all_scenarios_passed(&incomplete));
            assert!(!inventory_matches_pin(&incomplete, &pin));
            assert!(!record_verified_baseline(
                &baseline,
                &json!({}),
                all_scenarios_passed(&incomplete) && inventory_matches_pin(&incomplete, &pin)
            )
            .unwrap());
            assert_eq!(fs::read(&baseline).unwrap(), b"reviewed baseline");
        }
    }
    #[test]
    fn known_failures_or_unexecuted_cases_cannot_be_blessed_as_a_baseline() {
        let directory = tempfile::tempdir().unwrap();
        let baseline = directory.path().join("baseline.json");
        fs::write(&baseline, b"reviewed baseline").unwrap();
        for results in [
            vec![],
            vec![json!({"status":"failed","executed":true})],
            vec![json!({"status":"unsupported","executed":true})],
            vec![json!({"status":"not_run","executed":false})],
            vec![json!({"status":"passed","executed":false})],
            vec![
                json!({"status":"passed","executed":true}),
                json!({"status":"failed","executed":true}),
            ],
        ] {
            assert!(!record_verified_baseline(
                &baseline,
                &json!({}),
                all_scenarios_passed(&results)
            )
            .unwrap());
            assert_eq!(fs::read(&baseline).unwrap(), b"reviewed baseline");
        }
        let complete = vec![json!({"status":"passed","executed":true})];
        assert!(record_verified_baseline(
            &baseline,
            &json!({"verified":true}),
            all_scenarios_passed(&complete)
        )
        .unwrap());
        assert_eq!(
            serde_json::from_slice::<Value>(&fs::read(&baseline).unwrap()).unwrap(),
            json!({"verified":true})
        );
    }
    #[test]
    fn inventory_encoding_preserves_types_unicode_and_exponents() {
        assert_eq!(
            canonical(&json!({"z":[1,1.0,true,null,"中文"],"a":1e-7})),
            "{\"a\":1e-07,\"z\":[1,1.0,true,null,\"中文\"]}"
        );
        assert_eq!(canonical(&json!(1e16)), "1e+16");
        assert_eq!(canonical(&json!(-0.0)), "-0.0");
    }
}

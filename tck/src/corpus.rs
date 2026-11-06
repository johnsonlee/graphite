//! Expand the pinned official Gherkin corpus without dropping unsupported cases.
use anyhow::{ensure, Context, Result};
use gherkin::{Feature, Scenario, Step};
use serde_json::{json, Value};
use std::{fs, path::Path};
use walkdir::WalkDir;

fn interpolate(text: &str, variables: &[(String, String)]) -> String {
    variables
        .iter()
        .fold(text.to_owned(), |value, (key, replacement)| {
            value.replace(&format!("<{key}>"), replacement)
        })
}

// Use source indentation, rather than minimum-content dedentation: the official
// compiler removes the opening delimiter's indentation, preserving extra spaces.
fn docstring(step: &Step, source: &str) -> Result<Value> {
    let lines: Vec<_> = source.lines().collect();
    let (offset, opening) = lines
        .iter()
        .enumerate()
        .skip(step.position.line)
        .find(|(_, line)| {
            line.trim_start().starts_with("\"\"\"") || line.trim_start().starts_with("```")
        })
        .context("docstring opening delimiter missing")?;
    let indent = opening.len() - opening.trim_start().len();
    let delimiter = &opening.trim_start()[..3];
    let media_type = opening.trim_start()[3..].trim();
    let mut body = Vec::new();
    let mut closed = false;
    for line in lines.iter().skip(offset + 1) {
        if line.trim_start().starts_with(delimiter) {
            closed = true;
            break;
        }
        let remove = line
            .bytes()
            .take(indent)
            .take_while(|b| *b == b' ' || *b == b'\t')
            .count();
        let escaped: String = delimiter.chars().map(|c| format!("\\{c}")).collect();
        body.push(line[remove..].replace(&escaped, delimiter));
    }
    ensure!(closed, "docstring closing delimiter missing");
    let mut result = json!({"content": body.join("\n")});
    if !media_type.is_empty() {
        result["mediaType"] = json!(media_type);
    }
    Ok(result)
}

fn step_value(step: &Step, source: &str, variables: &[(String, String)]) -> Result<Value> {
    let mut result = json!({"text": interpolate(&step.value, variables)});
    if let Some(table) = &step.table {
        let rows: Vec<_> = table
            .rows
            .iter()
            .map(|row| {
                let cells: Vec<_> = row
                    .iter()
                    .map(|cell| json!({"value": interpolate(cell, variables)}))
                    .collect();
                json!({"cells": cells})
            })
            .collect();
        result["argument"] = json!({"dataTable": {"rows": rows}});
    } else if step.docstring.is_some() {
        let mut argument = docstring(step, source)?;
        for key in ["content", "mediaType"] {
            if let Some(value) = argument[key].as_str() {
                argument[key] = json!(interpolate(value, variables));
            }
        }
        result["argument"] = json!({"docString": argument});
    }
    Ok(result)
}

fn append_scenario(
    output: &mut Vec<Value>,
    feature: &str,
    source: &str,
    scenario: &Scenario,
    background: &[&Step],
) -> Result<()> {
    let mut variables = Vec::new();
    if scenario.examples.is_empty() {
        variables.push(Vec::new());
    } else {
        for examples in &scenario.examples {
            let Some(table) = &examples.table else {
                continue;
            };
            let Some(header) = table.rows.first() else {
                continue;
            };
            for row in table.rows.iter().skip(1) {
                ensure!(
                    row.len() == header.len(),
                    "Examples row width differs from header"
                );
                variables.push(
                    header
                        .iter()
                        .cloned()
                        .zip(row.iter().cloned())
                        .collect::<Vec<_>>(),
                );
            }
        }
    }
    for values in variables {
        let mut steps = Vec::new();
        if !scenario.steps.is_empty() {
            // The official compiler does not interpolate background placeholders.
            for step in background {
                steps.push(step_value(step, source, &[])?);
            }
            for step in &scenario.steps {
                steps.push(step_value(step, source, &values)?);
            }
        }
        let name = interpolate(&scenario.name, &values);
        let id = format!("{feature}::{:04}::{name}", output.len() + 1);
        let steps = json!(steps);
        output.push(json!({"id": id, "feature": feature, "name": name,
            "scenarioSha256": crate::digest(&steps), "steps": steps}));
    }
    Ok(())
}

// gherkin-rust rejects unknown table escapes, while the official compiler
// preserves their backslash. Escape only those backslashes in parser input so
// the parsed cell values remain identical; original source stays untouched.
fn parser_source(source: &str) -> String {
    let mut result = String::new();
    let mut delimiter: Option<&str> = None;
    for line in source.split_inclusive('\n') {
        let trimmed = line.trim_start();
        if trimmed.starts_with("\"\"\"") || trimmed.starts_with("```") {
            let current = if trimmed.starts_with("```") {
                "```"
            } else {
                "\"\"\""
            };
            if delimiter == Some(current) {
                delimiter = None;
            } else if delimiter.is_none() {
                delimiter = Some(current);
            }
            result.push_str(line);
        } else if delimiter.is_none() && trimmed.starts_with('|') {
            let mut chars = line.chars().peekable();
            while let Some(ch) = chars.next() {
                result.push(ch);
                if ch == '\\' {
                    if chars.peek().is_some_and(|c| matches!(c, 'n' | '|' | '\\')) {
                        result.push(chars.next().unwrap());
                    } else {
                        result.push('\\');
                    }
                }
            }
        } else {
            result.push_str(line);
        }
    }
    result
}

pub fn scenarios(root: &Path) -> Result<Vec<Value>> {
    let directory = root.join("upstream/tck/features");
    ensure!(directory.is_dir(), "TCK feature cache is missing");
    let mut files = Vec::new();
    for entry in WalkDir::new(&directory) {
        let entry = entry?;
        if entry.file_type().is_file() && entry.path().extension().is_some_and(|s| s == "feature") {
            files.push(entry.into_path());
        }
    }
    files.sort();
    let mut output = Vec::new();
    for path in files {
        let source = fs::read_to_string(&path)?;
        let feature = Feature::parse(parser_source(&source), Default::default())
            .with_context(|| format!("parse {}", path.display()))?;
        let relative = path
            .strip_prefix(&directory)?
            .to_str()
            .context("non UTF-8 feature path")?
            .replace('\\', "/");
        let background: Vec<_> = feature.background.iter().flat_map(|b| &b.steps).collect();
        let mut children = Vec::new();
        for scenario in &feature.scenarios {
            children.push((scenario.position.line, Some(scenario), None));
        }
        for rule in &feature.rules {
            children.push((rule.position.line, None, Some(rule)));
        }
        children.sort_by_key(|child| child.0);
        let mut compiled = Vec::new();
        for (_, scenario, rule) in children {
            if let Some(scenario) = scenario {
                append_scenario(&mut compiled, &relative, &source, scenario, &background)?;
            } else if let Some(rule) = rule {
                let mut background = background.clone();
                background.extend(rule.background.iter().flat_map(|b| &b.steps));
                for scenario in &rule.scenarios {
                    append_scenario(&mut compiled, &relative, &source, scenario, &background)?;
                }
            }
        }
        output.extend(compiled);
    }
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unknown_table_escapes_match_official_compiler_without_changing_known_escapes() {
        let source = r#"Feature: escaping
  Scenario: cells
    Given any graph
    Then values:
      | a | b | c | d |
      | '\x' | 'a\|b' | 'a\\b' | 'a\nb' |
"#;
        let parsed = Feature::parse(parser_source(source), Default::default()).unwrap();
        let rows = &parsed.scenarios[0].steps[1].table.as_ref().unwrap().rows;
        assert_eq!(rows[1], vec!["'\\x'", "'a|b'", "'a\\b'", "'a\nb'"]);
    }

    #[test]
    fn expands_background_outline_docstrings_and_tables_in_source_order() {
        let root = tempfile::tempdir().unwrap();
        let directory = root.path().join("upstream/tck/features");
        fs::create_dir_all(&directory).unwrap();
        fs::write(
            directory.join("example.feature"),
            r#"Feature: expansion
  Background:
    Given an empty graph
  Scenario Outline: return <v>
    When executing query:
      """
        RETURN <v> AS v
      """
    Then the result should be, in order:
      | v |
      | <v> |
    Examples:
      | v |
      | 1 |
      | 2 |
  Rule: later rule
    Background:
      Given some rule context
    Scenario: last
      Then no side effects
"#,
        )
        .unwrap();
        let result = scenarios(root.path()).unwrap();
        assert_eq!(result.len(), 3);
        assert_eq!(result[0]["id"], "example.feature::0001::return 1");
        assert_eq!(
            result[1]["steps"][1]["argument"]["docString"]["content"],
            "  RETURN 2 AS v"
        );
        assert_eq!(
            result[1]["steps"][2]["argument"]["dataTable"]["rows"][1]["cells"][0]["value"],
            "2"
        );
        assert_eq!(
            result[2]["steps"],
            json!([{"text":"an empty graph"},{"text":"some rule context"},{"text":"no side effects"}])
        );
        assert_eq!(
            result[0]["scenarioSha256"],
            crate::digest(&result[0]["steps"])
        );
    }
}

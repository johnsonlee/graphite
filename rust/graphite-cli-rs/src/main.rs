//! `graphite` — the command-line tool, currently the `query` subcommand only.
//!
//! The Kotlin CLI (`graphite-query`) also carries `build` and `serve`. `build` runs the
//! SootUp bytecode analysis, which this port does not implement at all; `serve` is the
//! Explorer, which ships here as its own `graphite-explore` binary. Only `query` is
//! reproduced, and it is reproduced byte for byte: output of all three formats, the
//! verbose lines, the error text and the exit codes are compared against the Kotlin
//! binary by `rust/bench/parity-cli.py`.

use clap::{Parser, Subcommand};
use graphite_cypher::context::GraphContext;
use graphite_cypher::engine::Executor;
use graphite_cypher::tostring::{java_to_string, raw_json};
use graphite_storage::Graph;
use serde_json::{Map, Value as J};
use std::path::PathBuf;
use std::sync::Arc;

/// Columns narrower than this are padded out to it, matching `MIN_COLUMN_WIDTH`.
const MIN_COLUMN_WIDTH: usize = 4;

#[derive(Parser, Debug)]
#[command(
    name = "graphite",
    about = "Build and query Graphite graphs",
    version
)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand, Debug)]
enum Command {
    /// Execute a Cypher query against a saved graph
    Query(QueryArgs),
}

#[derive(Parser, Debug)]
struct QueryArgs {
    /// Path to saved graph directory
    graph_dir: PathBuf,

    /// Cypher query string
    query: String,

    /// Output format: text, json, csv
    #[arg(long, short = 'f', default_value = "text")]
    format: String,

    /// Enable verbose output
    #[arg(long, short = 'v')]
    verbose: bool,
}

fn main() -> std::process::ExitCode {
    match Cli::parse().command {
        Command::Query(args) => match query(args) {
            Ok(()) => std::process::ExitCode::SUCCESS,
            Err(message) => {
                eprintln!("Error: {message}");
                std::process::ExitCode::FAILURE
            }
        },
    }
}

fn query(args: QueryArgs) -> Result<(), String> {
    if !args.graph_dir.is_dir() {
        return Err(format!("Not a directory: {}", args.graph_dir.display()));
    }
    if args.verbose {
        eprintln!("Loading graph from {}...", args.graph_dir.display());
    }
    let graph = Graph::load(&args.graph_dir).map_err(|e| e.to_string())?;
    if args.verbose {
        eprintln!("Executing: {}", args.query);
    }
    let executor = Executor::single("standalone", Arc::new(graph));
    // The Kotlin command calls `execute(query)` with no row cap, so neither does this.
    let result = executor.execute(&args.query, None).map_err(|e| e.to_string())?;
    let columns = &result.columns;
    let value = |row: &graphite_cypher::engine::Row, col: &str| {
        row.get(col).cloned().unwrap_or(graphite_cypher::value::Value::Null)
    };

    // An unrecognised format falls through to the table, as the `when` branch does.
    match args.format.to_lowercase().as_str() {
        "json" => {
            let rows: Vec<J> = result
                .rows
                .iter()
                .map(|row| {
                    let mut obj = Map::new();
                    for col in columns {
                        obj.insert(col.clone(), raw_json(&value(row, col), &executor));
                    }
                    J::Object(obj)
                })
                .collect();
            let mut out = Map::new();
            out.insert("columns".into(), J::Array(columns.iter().map(|c| J::String(c.clone())).collect()));
            out.insert("rowCount".into(), J::Number(result.rows.len().into()));
            // Gson writes the map in insertion order: columns, rows, rowCount.
            let mut ordered = Map::new();
            ordered.insert("columns".into(), out["columns"].clone());
            ordered.insert("rows".into(), J::Array(rows));
            ordered.insert("rowCount".into(), out["rowCount"].clone());
            println!("{}", graphite_cypher::gson::to_pretty(&J::Object(ordered)));
        }
        "csv" => {
            // A result with no columns prints nothing at all, not even a blank line.
            if !columns.is_empty() {
                println!("{}", columns.join(","));
                for row in &result.rows {
                    let cells: Vec<String> = columns
                        .iter()
                        .map(|col| match value(row, col) {
                            graphite_cypher::value::Value::Null => String::new(),
                            // Only strings are quoted. A node renders through
                            // `toString()` unquoted, commas and all — faithful to the
                            // baseline, which produces malformed CSV for those rows.
                            graphite_cypher::value::Value::Str(s) => {
                                format!("\"{}\"", s.replace('"', "\"\""))
                            }
                            other => java_to_string(&other, &executor),
                        })
                        .collect();
                    println!("{}", cells.join(","));
                }
            }
        }
        _ => {
            if columns.is_empty() {
                println!("(no results)");
                return Ok(());
            }
            let cell = |row: &graphite_cypher::engine::Row, col: &str| {
                java_to_string(&value(row, col), &executor)
            };
            // Width is measured in UTF-16 code units, because `String.length` and
            // `padEnd` on the other side count those, not characters.
            let width = |s: &str| s.encode_utf16().count();
            let widths: Vec<usize> = columns
                .iter()
                .map(|col| {
                    let data = result
                        .rows
                        .iter()
                        .map(|row| width(&cell(row, col)))
                        .max()
                        .unwrap_or(0);
                    width(col).max(data).max(MIN_COLUMN_WIDTH)
                })
                .collect();

            let pad = |s: &str, w: usize| {
                let mut out = s.to_string();
                for _ in width(s)..w {
                    out.push(' ');
                }
                out
            };
            let header: Vec<String> = columns
                .iter()
                .enumerate()
                .map(|(i, col)| pad(col, widths[i]))
                .collect();
            println!("{}", header.join(" | "));
            let rule: Vec<String> = widths.iter().map(|w| "-".repeat(*w)).collect();
            println!("{}", rule.join("-+-"));
            for row in &result.rows {
                let line: Vec<String> = columns
                    .iter()
                    .enumerate()
                    .map(|(i, col)| pad(&cell(row, col), widths[i]))
                    .collect();
                println!("{}", line.join(" | "));
            }
            println!("\n{} row(s)", result.rows.len());
        }
    }
    Ok(())
}

/// Silences the unused-import warning when the trait is only needed for method
/// resolution on `Executor`.
#[allow(dead_code)]
fn _context_marker(c: &dyn GraphContext) -> usize {
    c.source_count()
}

//! `graphite` — the command-line tool: `build`, `query`, `serve`, `frontend`.
//!
//! The command surface is the Kotlin `graphite.jar`'s, so a command line written for it
//! keeps working once the binary is swapped:
//!
//! - `build` is a shell over the JVM frontend (`graphite.jar build`, the SootUp
//!   analysis): every argument is passed through, and the frontend is found as
//!   `frontend.rs` describes.
//! - `query` is reproduced byte for byte: output of all three formats, the verbose lines,
//!   the error text and the exit codes are compared against the Kotlin binary by
//!   `backend/bench/parity-cli.py`.
//! - `serve` is the Explorer (`graphite_explore::serve`), MCP included at `/mcp`.
//! - `mcp` is the same Explorer as MCP tools over stdio (`graphite_explore::mcp`).
//! - `frontend list|describe|install` manages frontends.

mod build;
mod frontend;
mod install;

use clap::{Parser, Subcommand};
use graphite_cypher::context::GraphContext;
use graphite_cypher::engine::Executor;
use graphite_cypher::tostring::{java_to_string, raw_json};
use graphite_explore::serve::{serve, GraphArgs, ServeArgs, VERSION};
use graphite_storage::Graph;
use serde_json::{Map, Value as J};
use std::ffi::OsString;
use std::path::PathBuf;
use std::sync::Arc;

/// Row production is thousands of small, short-lived allocations per request -- a row
/// map, a key per column, a string per value -- and the system allocator's per-call
/// cost shows up directly in P50. jemalloc's thread-local caches make those close to
/// free, and it also returns memory to the OS on a schedule rather than on a whim,
/// which matters for a process holding sixty-four memory-mapped graphs.
#[cfg(not(target_env = "msvc"))]
#[global_allocator]
static GLOBAL: tikv_jemallocator::Jemalloc = tikv_jemallocator::Jemalloc;

/// Columns narrower than this are padded out to it, matching `MIN_COLUMN_WIDTH`.
const MIN_COLUMN_WIDTH: usize = 4;

#[derive(Parser, Debug)]
#[command(
    name = "graphite",
    about = "Build and query Graphite graphs",
    version = VERSION
)]
struct Cli {
    #[command(subcommand)]
    command: Option<Command>,
}

#[derive(Subcommand, Debug)]
enum Command {
    /// Build graph from JAR/WAR/APK/directory and save to disk (runs the JVM frontend)
    #[command(disable_help_flag = true, disable_version_flag = true)]
    Build(BuildArgs),
    /// Execute a Cypher query against a saved graph
    Query(QueryArgs),
    /// Serve one or more saved Graphite webgraphs over HTTP (with MCP at /mcp)
    Serve(ServeArgs),
    /// Serve the same graphs to an MCP client over stdio
    Mcp(McpArgs),
    /// List, describe or install the frontends that build graphs
    Frontend {
        #[command(subcommand)]
        command: FrontendCommand,
    },
}

/// `graphite mcp`: the graph selection of `serve`, no port.
#[derive(Parser, Debug)]
struct McpArgs {
    #[command(flatten)]
    graphs: GraphArgs,
}

/// Everything after `build` belongs to the frontend, `--help` included.
#[derive(Parser, Debug)]
struct BuildArgs {
    #[arg(trailing_var_arg = true, allow_hyphen_values = true, num_args = 0..)]
    args: Vec<OsString>,
}

#[derive(Subcommand, Debug)]
enum FrontendCommand {
    /// Show which frontends this CLI can run and where they were found
    List,
    /// Describe a frontend as JSON: kind, path, version, accepted inputs
    Describe {
        /// Frontend language: jvm
        lang: String,
    },
    /// Download a frontend from a GitHub release into ~/.graphite/frontends
    Install {
        /// Frontend language: jvm
        lang: String,
        /// Release version to install (default: this CLI's version)
        #[arg(long)]
        version: Option<String>,
        /// Install even when the release publishes no checksum
        #[arg(long)]
        skip_checksum: bool,
    },
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
    let env = frontend::Env::from_process();
    // As the Kotlin CLI: no subcommand prints the usage and exits 0.
    let Some(command) = Cli::parse().command else {
        use clap::CommandFactory;
        let _ = Cli::command().print_help();
        return std::process::ExitCode::SUCCESS;
    };
    let outcome = match command {
        Command::Build(args) => return exit_code(build::run(&env, &args.args)),
        Command::Query(args) => query(args),
        Command::Serve(args) => serve(args),
        Command::Mcp(args) => graphite_explore::mcp::run_stdio(args.graphs),
        Command::Frontend { command } => frontend_command(&env, command),
    };
    match outcome {
        Ok(()) => std::process::ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("Error: {message}");
            std::process::ExitCode::FAILURE
        }
    }
}

fn exit_code(code: i32) -> std::process::ExitCode {
    std::process::ExitCode::from(u8::try_from(code).unwrap_or(1))
}

fn frontend_command(env: &frontend::Env, command: FrontendCommand) -> Result<(), String> {
    match command {
        FrontendCommand::List => {
            match frontend::locate_jvm(env) {
                Some(fe) => {
                    let version = frontend_version(env, &fe).unwrap_or_else(|| "unknown".into());
                    println!(
                        "jvm\t{}\t{}\t(via {})",
                        version,
                        fe.path().display(),
                        fe.found_via
                    );
                }
                None => println!("jvm\tnot installed\t-\t(graphite frontend install jvm)"),
            }
            Ok(())
        }
        FrontendCommand::Describe { lang } => {
            require_jvm_lang(&lang)?;
            let fe = frontend::locate_jvm(env).ok_or_else(build::missing_frontend_message)?;
            let version = frontend_version(env, &fe);
            let text = serde_json::to_string_pretty(&frontend::describe(&fe, version))
                .map_err(|e| e.to_string())?;
            println!("{text}");
            Ok(())
        }
        FrontendCommand::Install {
            lang,
            version,
            skip_checksum,
        } => {
            require_jvm_lang(&lang)?;
            let version = match version {
                Some(v) => v.trim_start_matches('v').to_string(),
                None if option_env!("GRAPHITE_VERSION").is_some() => VERSION.to_string(),
                None => {
                    return Err(
                        "this is a development build with no release version; pass --version"
                            .into(),
                    )
                }
            };
            install::install_jvm(env, &version, skip_checksum).map(|_| ())
        }
    }
}

fn require_jvm_lang(lang: &str) -> Result<(), String> {
    if lang == "jvm" {
        Ok(())
    } else {
        Err(format!("unknown frontend '{lang}'; available: jvm"))
    }
}

/// The frontend's own version, by running it with `--version` (`graphite 2.4.8`).
fn frontend_version(env: &frontend::Env, fe: &frontend::Frontend) -> Option<String> {
    let mut cmd = match &fe.launch {
        frontend::Launch::Jar(jar) => {
            let mut c = std::process::Command::new(frontend::locate_java(env).ok()?);
            c.arg("-jar").arg(jar);
            c
        }
        frontend::Launch::Executable(exe) => std::process::Command::new(exe),
    };
    // A JVM started only to print its version needs no 8 GiB reservation.
    let out = cmd
        .arg("--version")
        .env("JAVA_TOOL_OPTIONS", "-Xmx256m")
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&out.stdout);
    text.lines()
        .find_map(|l| l.strip_prefix("graphite ").map(|v| v.trim().to_string()))
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
    let result = executor
        .execute(&args.query, None)
        .map_err(|e| e.to_string())?;
    let columns = &result.columns;
    let value = |row: &graphite_cypher::engine::Row, col: &str| {
        row.get(col)
            .cloned()
            .unwrap_or(graphite_cypher::value::Value::Null)
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
            out.insert(
                "columns".into(),
                J::Array(columns.iter().map(|c| J::String(c.clone())).collect()),
            );
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

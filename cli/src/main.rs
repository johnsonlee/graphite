//! `graphite` — the command-line tool: `build`, `query`, `serve`, `frontend`.
//!
//! The native CLI owns graph publication and server transports; language frontends
//! provide analysis and interchange import:
//!
//! - `build` is a shell over the JVM frontend (`graphite.jar build`, the SootUp
//!   analysis): shell options and archive output are resolved before forwarding, and the frontend is found as
//!   `frontend.rs` describes. A `--fold` file with `select` rules makes it build twice,
//!   resolving the queries on the first graph (`fold.rs`).
//! - `import` is the same shell over `graphite.jar import`: it persists the Graph IR
//!   another frontend wrote (`graphite-frontend-apple build --out app.graphite-ir`).
//! - `query` is reproduced byte for byte: output of all three formats, the verbose lines,
//!   the error text and the exit codes are compared against the Kotlin binary by
//!   `backend/bench/parity-cli.py`.
//! - `serve` is the Explorer (`graphite_explore::serve`), MCP included at `/mcp`.
//! - `mcp` is the same Explorer as MCP tools over stdio (`graphite_explore::mcp`).
//! - `frontend list|describe|install` manages frontends.

mod build;
mod build_args;
mod build_output;
mod build_ts;
mod fold;
mod frontend;
mod install;
mod pack;

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
    about = "Build, query and serve Graphite graphs",
    version = VERSION
)]
struct Cli {
    #[command(subcommand)]
    command: Option<Command>,
}

#[derive(Subcommand, Debug)]
enum Command {
    /// Build a .graphite archive with a JVM, JavaScript, or Apple frontend
    #[command(
        override_usage = "graphite build [--lang <LANG>] [FRONTEND_ARGS]... -o <OUTPUT>",
        after_help = "Build writes a .graphite archive and its .sha256 sidecar, appending .graphite to -o when needed. -o app and -o app.graphite both write app.graphite. Existing verified archives are rebuilt in place. A trailing directory separator is rejected; use graphite unpack for directory output. When appending the suffix, build reports the archive path and warns if the old directory still exists.\n\nUse --lang js (aliases: javascript, ts, typescript, es) for compiled JavaScript artifacts. A Swift package, .xcodeproj, or .xcworkspace selects apple (aliases: swift, ios, macos). Other inputs default to jvm (aliases: java, kotlin, android). Use --frontend <PATH> to select a JVM JAR/launcher or Apple executable explicitly; pair it with --lang jvm for JVM. Place --lang and --profile before the -- separator. Arguments after -- are frontend inputs.\n\n  graphite build app.jar -o app\n  graphite build --lang js dist -o app.graphite\n  graphite build --profile -o app.graphite -- app.jar\n\nThis is shell help. For options reported by the installed frontend, run graphite build --help (JVM) or graphite build --lang js --help. Direct frontend commands keep their own output format; use graphite unpack app.graphite app/ to obtain a graph directory."
    )]
    Build(BuildArgs),
    /// Import a Graph IR written by a language frontend (.graphite-ir) and save the graph
    #[command(
        disable_help_flag = true,
        disable_version_flag = true,
        override_usage = "graphite import <INPUT.graphite-ir> -o <OUTPUT> [FRONTEND_ARGS]...",
        after_help = "Requires the JVM frontend. Unlike build, import does not append an extension: -o app writes a graph directory, while -o app.graphite writes an archive and its .sha256 sidecar.\n\n  graphite import app.graphite-ir -o app.graphite\n  graphite import app.graphite-ir -o saved-graph\n\nThis is shell help (graphite help import). graphite import --help reports options from the installed JVM frontend."
    )]
    Import(BuildArgs),
    /// Execute a Cypher query against a saved graph
    Query(QueryArgs),
    /// Start the web UI, REST API and MCP over HTTP
    #[command(
        long_about = "Start an HTTP server for the web UI, REST API and MCP at /mcp. Use this for a browser or clients connecting to a running server. For a client-launched MCP subprocess over stdin/stdout instead, use graphite mcp.",
        after_help = "  graphite serve app.graphite\n  graphite serve --id app saved-graph/ --port 8080\n  graphite serve --data /data/graphs\n\nWeb UI: http://localhost:8080\nREST API: http://localhost:8080/api/graphs\nMCP over HTTP: http://localhost:8080/mcp\n\nA positional graph defaults to its basename (app.graphite -> app). Use --id to override it. --data can start an empty server and discovers .graphite files on startup. Both directories and archives are supported."
    )]
    Serve(ServeArgs),
    /// Run MCP over stdin/stdout for a client-launched subprocess
    #[command(
        long_about = "Run MCP over stdio (stdin/stdout). Configure an MCP client to launch this command as a subprocess. It loads graphs itself, needs no running serve process, and opens no HTTP port or web UI. For MCP over HTTP or a browser, use graphite serve instead.",
        after_help = "Client executable: graphite\nClient arguments: mcp app.graphite\n\n  graphite mcp app.graphite\n  graphite mcp --graph orders:orders.graphite --graph billing:billing.graphite\n\nThe client exchanges MCP messages over stdin/stdout; startup diagnostics go to stderr. A positional graph defaults to its basename (app.graphite -> app). Use --id to override it. Graph directories and archives are both supported."
    )]
    Mcp(McpArgs),
    /// List, describe or install the frontends that build graphs
    Frontend {
        #[command(subcommand)]
        command: FrontendCommand,
    },
    /// Pack a saved graph directory (or files) into one .graphite file
    Pack(pack::PackArgs),
    /// Unpack a .graphite file into a directory of the original files
    Unpack(pack::UnpackArgs),
    /// Verify archive CRC-32s and available manifest/sidecar SHA-256 digests
    #[command(
        after_help = "A missing manifest produces a warning; per-entry checks then use CRC-32 only. A missing .sha256 sidecar is reported but is not an error. Mismatching available checksums fail verification.\n\n  graphite verify app.graphite --verbose"
    )]
    Verify(pack::VerifyArgs),
    /// Describe a .graphite file as JSON: entries, sizes, fingerprint, file digest
    Info(pack::InfoArgs),
}

/// `graphite mcp`: the graph selection of `serve`, no port.
#[derive(Parser, Debug)]
struct McpArgs {
    #[command(flatten)]
    graphs: GraphArgs,
}

/// --lang selects a frontend anywhere before the -- separator; other arguments belong to it.
#[derive(Parser, Debug)]
struct BuildArgs {
    #[arg(last = true, value_name = "FRONTEND_ARGS")]
    args: Vec<OsString>,
}

#[derive(Subcommand, Debug)]
enum FrontendCommand {
    /// Show which frontends this CLI can run and where they were found
    List,
    /// Describe a frontend as JSON: kind, path, version, accepted inputs
    Describe {
        /// Language: jvm (java/kotlin/android), js (javascript/ts/typescript/es), apple (swift/ios/macos)
        lang: String,
    },
    /// Download a frontend from a GitHub release into ~/.graphite/frontends
    Install {
        /// Language: jvm (java/kotlin/android), js (javascript/ts/typescript/es), apple (swift/ios/macos)
        lang: String,
        /// Release version to install (default: this CLI's version)
        #[arg(long)]
        version: Option<String>,
        /// Allow a legacy JVM or Apple release with no published checksum
        #[arg(long)]
        skip_checksum: bool,
    },
}

#[derive(Parser, Debug)]
struct QueryArgs {
    /// Query compatibility: graphite or opencypher-m23
    #[arg(long, default_value = "graphite")]
    dialect: graphite_cypher::CypherDialect,
    /// Path to a saved graph: a directory or a .graphite file
    #[arg(value_name = "GRAPH")]
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

/// Clap owns the shell's build help. Give its last positional the original
/// frontend argv, including any frontend `--`, as opaque OS strings.
fn parse_cli(mut argv: Vec<OsString>) -> Result<Cli, clap::Error> {
    if argv.get(1).is_some_and(|s| s == "build" || s == "import") {
        argv.insert(2, "--".into());
    }
    Cli::try_parse_from(argv)
}

fn main() -> std::process::ExitCode {
    let env = frontend::Env::from_process();
    // As the Kotlin CLI: no subcommand prints the usage and exits 0.
    let Some(command) = parse_cli(std::env::args_os().collect())
        .unwrap_or_else(|e| e.exit())
        .command
    else {
        use clap::CommandFactory;
        let _ = Cli::command().print_help();
        return std::process::ExitCode::SUCCESS;
    };
    let outcome = match command {
        Command::Build(args) => return exit_code(build::run(&env, &args.args)),
        Command::Import(args) => return exit_code(build::run_import(&env, &args.args)),
        Command::Query(args) => query(args),
        Command::Serve(args) => serve(args),
        Command::Mcp(args) => graphite_explore::mcp::run_stdio(args.graphs),
        Command::Frontend { command } => frontend_command(&env, command),
        Command::Pack(args) => pack::pack(args),
        Command::Unpack(args) => pack::unpack(args),
        Command::Verify(args) => pack::verify(args),
        Command::Info(args) => pack::info(args),
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
            for lang in frontend::LANGS {
                let name = lang.name();
                let aliases = if lang == frontend::Lang::Js {
                    "; aliases: ts, es"
                } else {
                    ""
                };
                match frontend::locate(env, lang) {
                    Some(fe) => println!(
                        "{name}\t{}\t{}\t(via {}{aliases})",
                        frontend_version(env, &fe).unwrap_or_else(|| "unknown".into()),
                        fe.path().display(),
                        fe.found_via
                    ),
                    None => println!(
                        "{name}\tnot installed\t-\t(graphite frontend install {name}{aliases})"
                    ),
                }
            }
            Ok(())
        }
        FrontendCommand::Describe { lang } => {
            let lang = frontend::Lang::parse(&lang)?;
            let fe = frontend::locate(env, lang)
                .ok_or_else(|| build::missing_frontend_message_for(lang.name()))?;
            let version = frontend_version(env, &fe);
            let own = if lang == frontend::Lang::Apple {
                Some(frontend::own_description(&fe)?)
            } else {
                None
            };
            let text =
                serde_json::to_string_pretty(&frontend::describe(&fe, version, own.as_ref()))
                    .map_err(|e| e.to_string())?;
            println!("{text}");
            Ok(())
        }
        FrontendCommand::Install {
            lang,
            version,
            skip_checksum,
        } => {
            let lang = frontend::Lang::parse(&lang)?;
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
            install::install(env, lang, &version, skip_checksum).map(|_| ())
        }
    }
}

/// The frontend's own version: `--version` for the jar (`graphite 2.4.8`), `version` for
/// a protocol frontend (`0.1.0`).
fn frontend_version(env: &frontend::Env, fe: &frontend::Frontend) -> Option<String> {
    let mut cmd = fe.command(env).ok()?;
    // A JVM started only to print its version needs no 8 GiB reservation.
    let out = cmd
        .arg(if fe.lang == "apple" {
            "version"
        } else {
            "--version"
        })
        .env("JAVA_TOOL_OPTIONS", "-Xmx256m")
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&out.stdout);
    if fe.lang != "apple" {
        text.lines()
            .find_map(|l| l.strip_prefix("graphite ").map(|v| v.trim().to_string()))
    } else {
        text.lines()
            .next()
            .map(|l| l.trim().to_string())
            .filter(|l| !l.is_empty())
    }
}

fn query(args: QueryArgs) -> Result<(), String> {
    if !args.graph_dir.exists() {
        return Err(format!("No such graph: {}", args.graph_dir.display()));
    }
    if args.verbose {
        eprintln!("Loading graph from {}...", args.graph_dir.display());
    }
    let graph = Graph::load(&args.graph_dir).map_err(|e| e.to_string())?;
    if args.verbose {
        eprintln!("Executing: {}", args.query);
    }
    let executor = Executor::single("standalone", Arc::new(graph)).with_dialect(args.dialect);
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

#[cfg(test)]
mod cli_tests {
    use super::*;

    #[test]
    fn query_parser_exposes_public_dialect_and_rejects_invalid_values() {
        for (extra, expected) in [
            (vec![], graphite_cypher::CypherDialect::Graphite),
            (
                vec!["--dialect", "opencypher-m23"],
                graphite_cypher::CypherDialect::OpenCypherM23,
            ),
        ] {
            let mut values = vec!["graphite", "query", "graph.graphite", "RETURN 1"];
            values.extend(extra);
            let cli = parse_cli(values.into_iter().map(OsString::from).collect()).unwrap();
            let Some(Command::Query(query)) = cli.command else {
                panic!("expected query")
            };
            assert_eq!(query.dialect, expected);
        }
        assert!(parse_cli(
            [
                "graphite",
                "query",
                "graph.graphite",
                "RETURN 1",
                "--dialect",
                "unknown"
            ]
            .map(OsString::from)
            .into()
        )
        .is_err());
    }

    #[test]
    fn build_retains_the_separator_for_shell_option_detection() {
        for argv in [
            vec!["--", "--lang", "js"],
            vec!["app.js", "--", "--lang", "js"],
            vec!["app.js", "--lang", "js", "--", "-o"],
        ] {
            let cli = parse_cli(
                ["graphite", "build"]
                    .into_iter()
                    .chain(argv.clone())
                    .map(OsString::from)
                    .collect(),
            )
            .unwrap();
            let Some(Command::Build(build)) = cli.command else {
                panic!("expected build");
            };
            assert_eq!(
                build.args,
                argv.iter().map(OsString::from).collect::<Vec<_>>()
            );
        }
    }

    #[test]
    fn shell_help_describes_both_frontends_and_build_help_still_delegates() {
        let help = parse_cli(["graphite", "help", "build"].map(OsString::from).into()).unwrap_err();
        assert_eq!(help.kind(), clap::error::ErrorKind::DisplayHelp);
        let text = help.to_string();
        for expected in [
            "--lang",
            "--profile",
            "graphite build --help (JVM)",
            "graphite build --lang js --help",
            "after --",
        ] {
            assert!(text.contains(expected), "{text}");
        }

        for flag in ["-h", "--help"] {
            let cli = parse_cli(["graphite", "build", flag].map(OsString::from).into()).unwrap();
            let Some(Command::Build(build)) = cli.command else {
                panic!("expected build")
            };
            assert_eq!(build.args, [OsString::from(flag)]);
        }
    }

    #[cfg(unix)]
    #[test]
    fn build_parser_retains_a_non_unicode_frontend_option_value() {
        use std::os::unix::ffi::OsStringExt;
        let path = OsString::from_vec(vec![0xff]);
        let cli = parse_cli(vec![
            "graphite".into(),
            "build".into(),
            "-o".into(),
            path.clone(),
        ])
        .unwrap();
        let Some(Command::Build(build)) = cli.command else {
            panic!("expected build")
        };
        assert_eq!(build.args, [OsString::from("-o"), path]);
    }
    #[test]
    fn build_parser_preserves_the_language_selector_and_frontend_help() {
        let cli = parse_cli(
            ["graphite", "build", "--lang", "ts", "--help"]
                .map(OsString::from)
                .into(),
        )
        .unwrap();
        let Some(Command::Build(args)) = cli.command else {
            panic!("expected build");
        };
        assert_eq!(args.args, ["--lang", "ts", "--help"].map(OsString::from));
        assert_eq!(
            build_ts::select_language(&args.args).unwrap(),
            (frontend::Lang::Js, vec![OsString::from("--help")])
        );
    }
}

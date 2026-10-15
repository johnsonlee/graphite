use anyhow::Result;
use clap::{Args, Parser, Subcommand};
use graphite_benchmark::{jmh, native, plan, prepare, process, report};
use std::path::PathBuf;

#[derive(Parser)]
#[command(
    name = "graphite-benchmark",
    about = "Correctness and OOM gates with report-only base/head timings"
)]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    Prepare(prepare::PrepareArgs),
    Jvm(jmh::JmhArgs),
    Native(native::NativeArgs),
    Report(ReportArgs),
}

#[derive(Args)]
struct ReportArgs {
    #[arg(long)]
    input: PathBuf,
    #[arg(long)]
    output: PathBuf,
}

fn execute() -> Result<()> {
    process::install_signal_handlers()?;
    match Cli::parse().command {
        Commands::Prepare(args) => {
            let value = prepare::run(&args).unwrap_or_else(|error| serde_json::json!({
                "schema": "graphite-benchmark-preparation-v1", "passed": false, "error": format!("{error:#}")
            }));
            std::fs::create_dir_all(&args.output)?;
            std::fs::write(
                args.output.join("preparation.json"),
                format!("{}\n", serde_json::to_string_pretty(&value)?),
            )?;
            report::require_success(value["passed"] == true)
        }
        Commands::Jvm(args) => {
            let value = jmh::run(&args).unwrap_or_else(|error| {
                report::failed_suite(
                    args.suite.name(),
                    &args.base_sha,
                    &args.head_sha,
                    &format!("{error:#}"),
                )
            });
            report::require_success(report::write_suite(&args.output, &value)?)
        }
        Commands::Native(args) => {
            let value = native::run(&args).unwrap_or_else(|error| {
                report::failed_suite(
                    "native-real64",
                    &args.base_sha,
                    &args.head_sha,
                    &format!("{error:#}"),
                )
            });
            report::require_success(report::write_suite(&args.output, &value)?)
        }
        Commands::Report(args) => report::require_success(report::aggregate(
            &args.input,
            &args.output,
            plan::REQUIRED_SUITES,
        )?),
    }
}

fn main() {
    if let Err(error) = execute() {
        eprintln!("graphite-benchmark: {error:#}");
        std::process::exit(1);
    }
}

use anyhow::Result;
use clap::{Parser, Subcommand};
use graphite_benchmark::{pipeline, process, report};
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
    /// Build both revisions, run the fixed workload, and render its report.
    Run(pipeline::RunArgs),
    /// Re-render retained evidence, including after an interrupted run.
    Report {
        #[arg(long)]
        run: PathBuf,
    },
}

fn execute() -> Result<()> {
    process::install_signal_handlers()?;
    let passed = match Cli::parse().command {
        Commands::Run(args) => pipeline::run(&args)?,
        Commands::Report { run } => pipeline::render(&run)?,
    };
    report::require_success(passed)
}

fn main() {
    if let Err(error) = execute() {
        eprintln!("graphite-benchmark: {error:#}");
        std::process::exit(1);
    }
}

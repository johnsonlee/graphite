//! `graphite-explore` — serve one or more saved Graphite webgraphs over HTTP.
//!
//! The same command is `graphite serve`; this binary stays for the container image and
//! for anyone who scripted it.

use clap::Parser;
use graphite_explore::serve::{serve, ServeArgs, VERSION};

/// Row production is thousands of small, short-lived allocations per request -- a row
/// map, a key per column, a string per value -- and the system allocator's per-call
/// cost shows up directly in P50. jemalloc's thread-local caches make those close to
/// free, and it also returns memory to the OS on a schedule rather than on a whim,
/// which matters for a process holding sixty-four memory-mapped graphs.
#[cfg(not(target_env = "msvc"))]
#[global_allocator]
static GLOBAL: tikv_jemallocator::Jemalloc = tikv_jemallocator::Jemalloc;

#[derive(Parser, Debug)]
#[command(
    name = "graphite-explore",
    about = "Interactive web visualization for saved Graphite graphs",
    version = VERSION
)]
struct Cli {
    #[command(flatten)]
    serve: ServeArgs,
}

fn main() -> std::process::ExitCode {
    match serve(Cli::parse().serve) {
        Ok(()) => std::process::ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("Error: {e}");
            std::process::ExitCode::FAILURE
        }
    }
}

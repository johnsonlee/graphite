//! `graphite-explore` — serve one or more saved Graphite webgraphs over HTTP.

use clap::Parser;
use graphite_explore::guard::*;
use graphite_explore::registry::{GraphRegistry, LoadMode};
use graphite_explore::routes::{router, AppState};
use std::path::PathBuf;
use std::sync::Arc;

const VERSION: &str = env!("CARGO_PKG_VERSION");
const DEFAULT_PORT: u16 = 8080;

#[derive(Parser, Debug)]
#[command(
    name = "graphite-explore",
    about = "Interactive web visualization for saved Graphite graphs",
    version
)]
struct Cli {
    /// Optional saved graph directory for single-graph startup
    graph_dir: Option<PathBuf>,

    /// Data directory used to resolve relative graph paths and allow empty startup
    #[arg(long)]
    data: Option<PathBuf>,

    /// Initial graph mapping id:path. Repeat for multiple graphs.
    #[arg(long = "graph")]
    graphs: Vec<String>,

    /// Required graph id for the optional positional graph
    #[arg(long)]
    id: Option<String>,

    /// HTTP port
    #[arg(long, short = 'p', default_value_t = DEFAULT_PORT)]
    port: u16,

    /// Graph load mode: EAGER, MAPPED, AUTO. Defaults to MAPPED for multi-graph heap stability.
    #[arg(long = "load-mode", default_value = "MAPPED")]
    load_mode: String,

    /// Cypher file or directory used at startup to derive graph-to-graph calls.
    #[arg(long)]
    topology: Option<PathBuf>,

    /// Maximum number of Cypher queries executing at once
    #[arg(long = "max-concurrent-cypher", default_value_t = DEFAULT_MAX_CONCURRENT_CYPHER)]
    max_concurrent_cypher: usize,

    /// Deprecated and ignored; Cypher execution is limited by timeout instead
    ///
    /// Kept because the Kotlin binary still accepts it: a deployment that passes it
    /// today must keep starting after the swap. The value is parsed — so a malformed
    /// one is still rejected, as it is there — and then discarded. No default is
    /// declared, so `--help` does not advertise one for a setting that does nothing.
    #[arg(long = "cypher-work-budget")]
    #[allow(dead_code)]
    cypher_work_budget: Option<i64>,

    /// Maximum Cypher request timeout in milliseconds
    #[arg(long = "cypher-max-timeout-ms", default_value_t = DEFAULT_CYPHER_MAX_TIMEOUT_MILLIS)]
    cypher_max_timeout_ms: u64,

    /// Expose Prometheus performance metrics at /metrics
    #[arg(long)]
    metrics: bool,
}

fn main() -> std::process::ExitCode {
    let cli = Cli::parse();
    match run(cli) {
        Ok(()) => std::process::ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("Error: {e}");
            std::process::ExitCode::FAILURE
        }
    }
}

fn run(cli: Cli) -> Result<(), String> {
    if cli.max_concurrent_cypher == 0 || cli.cypher_max_timeout_ms == 0 {
        return Err("Cypher concurrency and maximum timeout must be positive".into());
    }
    let has_initial = cli.graph_dir.is_some() || !cli.graphs.is_empty();
    if !has_initial && cli.data.is_none() {
        return Err("--data is required when starting without an initial graph".into());
    }
    let load_mode = LoadMode::parse(&cli.load_mode)?;
    let root = cli
        .data
        .clone()
        .or_else(|| cli.graph_dir.as_ref().and_then(|d| d.parent().map(|p| p.to_path_buf())))
        .unwrap_or_else(|| PathBuf::from("."));
    let root = std::fs::canonicalize(&root).unwrap_or(root);
    std::fs::create_dir_all(&root).map_err(|e| e.to_string())?;

    let registry = Arc::new(GraphRegistry::new(root.clone(), load_mode));
    let mut ids: Vec<String> = Vec::new();
    if let Some(dir) = &cli.graph_dir {
        let id = cli
            .id
            .clone()
            .ok_or("--id is required when a positional graph directory is provided")?;
        let served = registry.load(&id, dir, Some(load_mode))?;
        eprintln!(
            "Loaded graph '{}' from {} using {} mode",
            served.id,
            served.path.display(),
            load_mode.name()
        );
        ids.push(served.id.clone());
    }
    for spec in &cli.graphs {
        let sep = spec
            .find(':')
            .filter(|i| *i > 0 && *i < spec.len() - 1)
            .ok_or_else(|| format!("Invalid --graph '{spec}'. Expected id:path."))?;
        let (id, path) = spec.split_at(sep);
        let path = &path[1..];
        if ids.iter().any(|i| i == id) {
            return Err(format!("Duplicate initial graph id: {id}"));
        }
        let served = registry.load(id, std::path::Path::new(path), Some(load_mode))?;
        eprintln!(
            "Loaded graph '{}' from {} using {} mode",
            served.id,
            served.path.display(),
            load_mode.name()
        );
        ids.push(served.id.clone());
    }
    // Topology rules are validated at startup even though relations are derived lazily.
    let topology_queries =
        graphite_explore::topology::load_topology_queries(cli.topology.as_deref())?;

    let guard = Arc::new(CypherGuard::new(
        cli.max_concurrent_cypher,
        cli.cypher_max_timeout_ms,
    ));
    let state = Arc::new(AppState::new(
        registry.clone(),
        guard.clone(),
        VERSION.to_string(),
        cli.metrics,
    ));

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .map_err(|e| e.to_string())?;
    runtime.block_on(async move {
        let listener = tokio::net::TcpListener::bind(("0.0.0.0", cli.port))
            .await
            .map_err(|e| e.to_string())?;
        let actual = listener.local_addr().map(|a| a.port()).unwrap_or(cli.port);
        eprintln!("Web UI: http://localhost:{actual}");
        if cli.metrics {
            eprintln!("Metrics: http://localhost:{actual}/metrics");
        }
        eprintln!("Data: {}", root.display());
        eprintln!("Loaded graphs: {}", registry.ids().join(", "));
        eprintln!(
            "Topology: {} graphs, {} relations",
            registry.ids().len(),
            0
        );
        let _ = topology_queries;
        eprintln!(
            "Cypher limits: {} concurrent, {}ms maximum timeout",
            cli.max_concurrent_cypher, cli.cypher_max_timeout_ms
        );
        eprintln!("Press Ctrl+C to stop");
        axum::serve(listener, router(state))
            .await
            .map_err(|e| e.to_string())
    })
}

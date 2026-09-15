//! The `serve` command: load graphs, build the topology, and run the HTTP server.

use crate::guard::*;
use crate::registry::{GraphRegistry, LoadMode};
use crate::routes::{router, AppState};
use clap::Args;
use std::path::{Path, PathBuf};
use std::sync::Arc;

/// The version the CLI reports and the server puts in `/api/version`: the release tag
/// when the build sets `GRAPHITE_VERSION`, else the crate version.
pub const VERSION: &str = match option_env!("GRAPHITE_VERSION") {
    Some(v) => v,
    None => env!("CARGO_PKG_VERSION"),
};
pub const DEFAULT_PORT: u16 = 8080;

/// Which graphs to open and how to run Cypher over them: the part of the `serve`
/// command line that `graphite mcp` shares. Names, defaults and help text match the
/// Kotlin `graphite.jar serve` so that an existing command line keeps working unchanged.
#[derive(Args, Debug, Clone)]
pub struct GraphArgs {
    /// Optional saved graph (directory or .graphite file) for single-graph startup
    pub graph_dir: Option<PathBuf>,

    /// Data directory: relative graph paths resolve under it, an empty startup is
    /// allowed, and every `*.graphite` file directly in it is served under its file
    /// name (`orders.graphite` as `orders`)
    #[arg(long)]
    pub data: Option<PathBuf>,

    /// Initial graph mapping id:path. Repeat for multiple graphs.
    #[arg(long = "graph")]
    pub graphs: Vec<String>,

    /// Required graph id for the optional positional graph
    #[arg(long)]
    pub id: Option<String>,

    /// Graph load mode: EAGER, MAPPED, AUTO. Defaults to MAPPED for multi-graph heap stability.
    #[arg(long = "load-mode", default_value = "MAPPED")]
    pub load_mode: String,

    /// Cypher file or directory used at startup to derive graph-to-graph calls.
    #[arg(long)]
    pub topology: Option<PathBuf>,

    /// Maximum number of Cypher queries executing at once
    #[arg(long = "max-concurrent-cypher", default_value_t = DEFAULT_MAX_CONCURRENT_CYPHER)]
    pub max_concurrent_cypher: usize,

    /// Deprecated and ignored; Cypher execution is limited by timeout instead
    ///
    /// Kept because the Kotlin binary still accepts it: a deployment that passes it
    /// today must keep starting after the swap. The value is parsed — so a malformed
    /// one is still rejected, as it is there — and then discarded. No default is
    /// declared, so `--help` does not advertise one for a setting that does nothing.
    #[arg(long = "cypher-work-budget")]
    #[allow(dead_code)]
    pub cypher_work_budget: Option<i64>,

    /// Maximum Cypher request timeout in milliseconds
    #[arg(long = "cypher-max-timeout-ms", default_value_t = DEFAULT_CYPHER_MAX_TIMEOUT_MILLIS)]
    pub cypher_max_timeout_ms: u64,
}

/// The `graphite serve` command line.
#[derive(Args, Debug, Clone)]
pub struct ServeArgs {
    #[command(flatten)]
    pub graphs: GraphArgs,

    /// HTTP port
    #[arg(long, short = 'p', default_value_t = DEFAULT_PORT)]
    pub port: u16,

    /// Expose Prometheus performance metrics at /metrics
    #[arg(long)]
    pub metrics: bool,

    /// Browser origin allowed to call /mcp besides loopback origins (repeat for more;
    /// '*' allows any origin). Requests without an Origin header are always accepted.
    #[arg(long = "mcp-allowed-origin", value_name = "ORIGIN")]
    pub mcp_allowed_origins: Vec<String>,

    /// Follow --data while serving: a `*.graphite` file that appears, changes or
    /// disappears is loaded, reloaded or unloaded without a restart
    #[arg(long, requires = "data")]
    pub watch: bool,

    /// Seconds between two scans of --data under --watch
    #[arg(long = "watch-interval", default_value_t = 5, value_name = "SECONDS")]
    pub watch_interval: u64,
}

/// Graphs opened and ready to serve: the shared state behind the HTTP API and the MCP
/// tools, plus what startup learned about them.
pub struct Opened {
    pub state: Arc<AppState>,
    pub registry: Arc<GraphRegistry>,
    pub root: PathBuf,
    pub topology_graphs: usize,
    pub topology_relations: usize,
}

/// A runtime sized as the server's: Cypher queries run on the worker that received
/// them (`block_in_place`) rather than on the blocking pool, so there are enough
/// workers that the concurrency guard can be full and every core still has one free
/// for the rest of the API.
pub fn runtime(max_concurrent_cypher: usize) -> Result<tokio::runtime::Runtime, String> {
    let cores = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(1);
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(cores + max_concurrent_cypher)
        .enable_all()
        .build()
        .map_err(|e| e.to_string())
}

/// Report on stderr what `open` loaded, as the server does at startup.
pub fn report_loaded(opened: &Opened, cli: &GraphArgs) {
    eprintln!("Data: {}", opened.root.display());
    eprintln!("Loaded graphs: {}", opened.registry.ids().join(", "));
    // A graph written before `graph.callsite-string-index` existed gets the same
    // index built in memory at load; say which, since it costs startup time and
    // memory that the file would not.
    let built: Vec<String> = opened
        .registry
        .list()
        .iter()
        .filter(|g| g.graph.call_site_index().is_some_and(|i| i.is_in_memory()))
        .map(|g| g.id.clone())
        .collect();
    if !built.is_empty() {
        eprintln!(
            "CallSite string index built in memory for {} graph(s) without graph.callsite-string-index: {}",
            built.len(),
            built.join(", ")
        );
    }
    eprintln!(
        "Topology: {} graphs, {} relations",
        opened.topology_graphs, opened.topology_relations
    );
    eprintln!(
        "Cypher limits: {} concurrent, {}ms maximum timeout",
        cli.max_concurrent_cypher, cli.cypher_max_timeout_ms
    );
}

/// Warn once when this is a debug build; every long-running mode calls it first.
pub fn warn_if_debug_build() {
    if cfg!(debug_assertions) {
        // A plain `cargo build` produces this binary. On the 64-graph corpus it runs
        // the backtest at P50 6.7 ms and P95 42 ms, against 1.0 ms and 3.9 ms for
        // `--release`: no faster than the Kotlin server it is meant to replace. Say so
        // before anyone benchmarks it.
        eprintln!(
            "WARNING: this is an unoptimized debug build (cargo build without --release); \
             its latency is 6-10x worse than a release build. Build with `cargo build --release`."
        );
    }
}

/// The `*.graphite` files directly under `data`, as (id, path) in file-name order; the
/// id is the file name without the extension and must be a valid graph id. Directories
/// and other files are not graphs here: a directory graph is named with `--graph`.
pub fn discover_graphs(data: &Path) -> Result<Vec<(String, PathBuf)>, String> {
    let entries = match std::fs::read_dir(data) {
        Ok(entries) => entries,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(e) => return Err(format!("Cannot read --data {}: {e}", data.display())),
    };
    let mut found = Vec::new();
    for entry in entries {
        let entry = entry.map_err(|e| format!("Cannot read --data {}: {e}", data.display()))?;
        let path = entry.path();
        let is_file = entry.file_type().map(|t| t.is_file()).unwrap_or(false)
            || (path.is_file() && !path.is_dir());
        if !is_file
            || path.extension().and_then(|e| e.to_str())
                != Some(graphite_storage::container::EXTENSION)
        {
            continue;
        }
        let stem = path
            .file_stem()
            .and_then(|s| s.to_str())
            .unwrap_or_default();
        let id = crate::registry::validate_graph_id(stem).map_err(|why| {
            format!(
                "{}: the file name is not usable as a graph id: {why}",
                path.display()
            )
        })?;
        found.push((id, path));
    }
    found.sort();
    Ok(found)
}

/// Open the graphs `cli` names and build the shared state, as the server does at
/// startup: every graph loaded, the topology rules validated and the topology built.
pub fn open(cli: &GraphArgs, metrics: bool) -> Result<Opened, String> {
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
        .or_else(|| {
            cli.graph_dir
                .as_ref()
                .and_then(|d| d.parent().map(|p| p.to_path_buf()))
        })
        .unwrap_or_else(|| PathBuf::from("."));
    let root = std::fs::canonicalize(&root).unwrap_or(root);
    std::fs::create_dir_all(&root).map_err(|e| e.to_string())?;

    let registry = Arc::new(GraphRegistry::new(root.clone(), load_mode));
    let mut ids: Vec<String> = Vec::new();
    if let Some(dir) = &cli.graph_dir {
        let id = cli
            .id
            .clone()
            .ok_or("--id is required when a positional graph is provided")?;
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
    // Every `*.graphite` file directly under --data is a graph, its file name the id.
    if cli.data.is_some() {
        let data = &root;
        let discovered = discover_graphs(data)?;
        for (id, path) in &discovered {
            if ids.iter().any(|i| i == id) {
                return Err(format!(
                    "Graph id '{id}' is both given with --graph and discovered as {} under --data",
                    path.display()
                ));
            }
            let served = registry.load(id, path, Some(load_mode))?;
            eprintln!(
                "Loaded graph '{}' from {} using {} mode",
                served.id,
                served.path.display(),
                load_mode.name()
            );
            ids.push(served.id.clone());
        }
        if !discovered.is_empty() {
            eprintln!(
                "Discovered {} graph(s) in {}",
                discovered.len(),
                data.display()
            );
        }
    }
    // Topology rules are validated at startup even though relations are derived lazily.
    let topology_queries = crate::topology::load_topology_queries(cli.topology.as_deref())?;

    let guard = Arc::new(CypherGuard::new(
        cli.max_concurrent_cypher,
        cli.cypher_max_timeout_ms,
    ));
    let mut app_state = AppState::new(
        registry.clone(),
        guard.clone(),
        VERSION.to_string(),
        metrics,
    );
    app_state.topology_queries = topology_queries;
    // Built before the server listens, as the Kotlin server builds it: a rule that
    // fails against the loaded graphs is a startup error, not a runtime surprise.
    let (topology_graphs, topology_relations) = app_state.rebuild_topology()?;
    Ok(Opened {
        state: Arc::new(app_state),
        registry,
        root,
        topology_graphs,
        topology_relations,
    })
}

/// Serve the graphs described by `cli`, blocking until the server stops. The HTTP API
/// also carries the MCP server at `/mcp` (see `crate::mcp`).
pub fn serve(cli: ServeArgs) -> Result<(), String> {
    warn_if_debug_build();
    if cli.watch && cli.watch_interval == 0 {
        return Err("--watch-interval must be positive".into());
    }
    let opened = open(&cli.graphs, cli.metrics)?;
    if cli.watch {
        let watcher = crate::watch::for_server(opened.state.clone(), &opened.root)?;
        crate::watch::spawn(watcher, std::time::Duration::from_secs(cli.watch_interval));
    }
    let runtime = runtime(cli.graphs.max_concurrent_cypher)?;
    runtime.block_on(async move {
        let listener = tokio::net::TcpListener::bind(("0.0.0.0", cli.port))
            .await
            .map_err(|e| e.to_string())?;
        let actual = listener.local_addr().map(|a| a.port()).unwrap_or(cli.port);
        eprintln!("Web UI: http://localhost:{actual}");
        eprintln!("MCP: http://localhost:{actual}/mcp");
        if cli.metrics {
            eprintln!("Metrics: http://localhost:{actual}/metrics");
        }
        report_loaded(&opened, &cli.graphs);
        eprintln!("Press Ctrl+C to stop");
        let api = router(opened.state.clone());
        let policy = crate::mcp::OriginPolicy::new(cli.mcp_allowed_origins.clone());
        let app = crate::routes::instrumented(
            crate::mcp::with_mcp_route(api, policy),
            opened.state.clone(),
        );
        axum::serve(listener, app).await.map_err(|e| e.to_string())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tempdir(name: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("graphite-discover-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn discovery_lists_graphite_files_by_name_and_nothing_else() {
        let root = tempdir("list");
        std::fs::write(root.join("orders.graphite"), b"").unwrap();
        std::fs::write(root.join("billing.graphite"), b"").unwrap();
        std::fs::write(root.join("notes.txt"), b"").unwrap();
        std::fs::write(root.join("orders.graphite.sha256"), b"").unwrap();
        std::fs::create_dir_all(root.join("legacy-dir.graphite")).unwrap();
        std::fs::create_dir_all(root.join("nested")).unwrap();
        std::fs::write(root.join("nested/deep.graphite"), b"").unwrap();
        let found = discover_graphs(&root).unwrap();
        assert_eq!(
            found,
            [
                ("billing".to_string(), root.join("billing.graphite")),
                ("orders".to_string(), root.join("orders.graphite")),
            ]
        );
        assert!(discover_graphs(&root.join("absent")).unwrap().is_empty());
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn a_file_name_that_is_not_a_graph_id_fails_discovery() {
        let root = tempdir("badname");
        std::fs::write(root.join("orders.graphite"), b"").unwrap();
        std::fs::write(root.join("bad name.graphite"), b"").unwrap();
        let err = discover_graphs(&root).unwrap_err();
        assert!(err.contains("bad name.graphite"), "{err}");
        assert!(err.contains("not usable as a graph id"), "{err}");
        std::fs::remove_dir_all(root).unwrap();
    }
}

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
/// The commit the binary was built from, when the build sets `GRAPHITE_COMMIT` (the
/// release workflow does); `unknown` otherwise. Reported by `graphite_build_info`.
pub const COMMIT: &str = match option_env!("GRAPHITE_COMMIT") {
    Some(c) => c,
    None => "unknown",
};
pub const DEFAULT_PORT: u16 = 8080;

/// Which graphs to open and how to run Cypher over them: the part of the `serve`
/// command line that `graphite mcp` shares. A positional graph defaults to its basename;
/// callers can supply --id to select a different registry name.
#[derive(Args, Debug, Clone)]
pub struct GraphArgs {
    /// Optional saved graph (directory or .graphite file) for single-graph startup
    #[arg(value_name = "GRAPH")]
    pub graph_dir: Option<PathBuf>,

    /// Data directory: relative graph paths resolve under it, an empty startup is
    /// allowed, and every `*.graphite` file directly in it is served under its file
    /// name (`orders.graphite` as `orders`)
    #[arg(long)]
    pub data: Option<PathBuf>,

    /// Initial graph mapping id:path (directory or .graphite file). Repeat for multiple graphs.
    #[arg(long = "graph", value_name = "ID:PATH")]
    pub graphs: Vec<String>,

    /// Graph id for the positional graph (defaults to its basename, without .graphite for archives)
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

/// Keep the server's established async worker capacity. Bounded query and graph
/// preparation jobs run on the blocking pool, so their CPU work does not prevent
/// HTTP/MCP control tasks from making progress on these workers.
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

/// Announce the background C4 warm-up `AppState::warm_c4` started, if any.
pub fn report_c4_warmup(graphs: usize) {
    if graphs > 0 {
        eprintln!("C4 views: inferring {graphs} graph(s) in the background");
    }
}

/// Reads the declared table of every graph the startup loads, on one thread beside the
/// loader, so the server is ready with its tables read.
///
/// `Graph::load` leaves the table unread (it is query-only state), and the planner's
/// declared-key questions are answered by the saver's summary (`graph.declared-keys`); but
/// the first request that renders or filters by a declared type parsed all 64 tables, 1.5–2.3
/// s on the hosted runner (the query-native gate's cold row for `feature-nodes`, run
/// 38661758869). Reading them after the server listens competed with the first requests on
/// the same cores and slowed a heavy first aggregate by 50% (run 38690860344, Attempt304).
/// Loading itself is one thread over 64 graphs (45 s of CPU for 45 s of wall), so the tables
/// are read on a second thread as each graph is registered and the loader waits for the last
/// one before readiness: about 20 ms of CPU a graph inside the loading boundary, where the
/// gate's 10% CPU tolerance holds it, and no wall of its own. Off under `GRAPHITE_NO_FASTPATH`.
pub struct DeclaredTableReader {
    sender: Option<std::sync::mpsc::Sender<Arc<graphite_storage::graph::Graph>>>,
    worker: Option<std::thread::JoinHandle<usize>>,
}

impl DeclaredTableReader {
    pub fn start() -> DeclaredTableReader {
        if graphite_cypher::engine::optimizations_disabled() {
            return DeclaredTableReader {
                sender: None,
                worker: None,
            };
        }
        let (sender, receiver) = std::sync::mpsc::channel::<Arc<graphite_storage::graph::Graph>>();
        let worker = std::thread::Builder::new()
            .name("declared-tables".into())
            .spawn(move || {
                receiver
                    .iter()
                    .filter(|graph| graph.declared_types().is_some())
                    .count()
            })
            .ok();
        DeclaredTableReader {
            sender: worker.is_some().then_some(sender),
            worker,
        }
    }

    /// Queue a loaded graph; without a worker the table is read here, inline.
    pub fn queue(&self, graph: &Arc<graphite_storage::graph::Graph>) {
        match &self.sender {
            Some(sender) if sender.send(graph.clone()).is_ok() => {}
            Some(_) => {
                graph.declared_types();
            }
            None => {}
        }
    }

    /// Wait for every queued table; the number of graphs whose table was present and readable.
    pub fn finish(mut self) -> usize {
        drop(self.sender.take());
        self.worker
            .take()
            .and_then(|worker| worker.join().ok())
            .unwrap_or(0)
    }
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

fn positional_graph_id(path: &Path) -> Result<String, String> {
    let name = if path.is_file()
        && path
            .extension()
            .is_some_and(|ext| ext == graphite_storage::container::EXTENSION)
    {
        path.file_stem()
    } else {
        path.file_name()
    };
    let raw = name.and_then(|name| name.to_str()).unwrap_or_default();
    crate::registry::validate_graph_id(raw).map_err(|why| {
        format!(
            "{}: cannot infer a graph id from the basename: {why}; pass --id explicitly",
            path.display()
        )
    })
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
    // Resolve a positional path from cwd before deriving its registry root. Passing a
    // relative path again to registry.load would otherwise prepend its parent twice.
    let positional = cli
        .graph_dir
        .as_ref()
        .map(|path| {
            if cli.data.is_none() {
                std::path::absolute(path)
                    .map_err(|e| format!("Cannot resolve graph {}: {e}", path.display()))
            } else {
                Ok(path.clone())
            }
        })
        .transpose()?;
    let root = cli
        .data
        .clone()
        .or_else(|| {
            positional
                .as_ref()
                .and_then(|d| d.parent().map(|p| p.to_path_buf()))
        })
        .unwrap_or_else(|| PathBuf::from("."));
    let root = std::fs::canonicalize(&root).unwrap_or(root);
    std::fs::create_dir_all(&root).map_err(|e| e.to_string())?;

    let registry = Arc::new(GraphRegistry::new(root.clone(), load_mode));
    let tables = DeclaredTableReader::start();
    let mut ids: Vec<String> = Vec::new();
    if let Some(dir) = &positional {
        let lexical = registry.resolve_path(dir);
        let resolved = std::fs::canonicalize(&lexical)
            .map_err(|e| format!("Cannot open graph {}: {e}", dir.display()))?;
        let id = match &cli.id {
            Some(id) => id.clone(),
            // Keep a symlink's chosen basename. Paths such as `..` have no basename
            // until resolved to the directory they identify.
            None => positional_graph_id(if lexical.file_name().is_some() {
                &lexical
            } else {
                &resolved
            })?,
        };
        let served = registry.load(&id, &resolved, Some(load_mode))?;
        tables.queue(&served.graph);
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
        tables.queue(&served.graph);
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
            tables.queue(&served.graph);
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
    let tables_read = tables.finish();
    if tables_read > 0 {
        eprintln!("Declared types: read {tables_read} graph table(s) during loading");
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
    let opened = open(&cli.graphs, cli.metrics)?;
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
        report_c4_warmup(opened.state.warm_c4());
        eprintln!("Press Ctrl+C to stop");
        let api = router(opened.state.clone());
        let policy = crate::mcp::OriginPolicy::new(cli.mcp_allowed_origins.clone());
        let mcp_metrics = cli.metrics.then(|| opened.state.mcp_metrics.clone());
        let app = crate::routes::instrumented(
            crate::mcp::with_mcp_route(api, policy, mcp_metrics, Some(opened.state.clone())),
            opened.state.clone(),
        );
        axum::serve(listener, app).await.map_err(|e| e.to_string())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(path: PathBuf) -> GraphArgs {
        GraphArgs {
            graph_dir: Some(path),
            data: None,
            graphs: vec![],
            id: None,
            load_mode: "MAPPED".into(),
            topology: None,
            max_concurrent_cypher: 2,
            cypher_work_budget: None,
            cypher_max_timeout_ms: 1_000,
        }
    }

    fn write_graph(path: &Path) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        let json = br#"{"version":1,"methods":[],"nodes":[{"id":0,"kind":"StringConstant","value":"served"}],"edges":[]}"#;
        graphite_storage::interchange::import_json(json.as_slice(), path).unwrap();
    }

    fn assert_served(opened: &Opened, id: &str) {
        assert_eq!(opened.registry.ids(), vec![id.to_owned()]);
        let graph = opened.registry.acquire(id).unwrap().unwrap().graph;
        let graphite_storage::NodeKind::StringConstant(value) = graph.node(0).unwrap().kind else {
            panic!("expected string")
        };
        assert_eq!(graph.str(value), "served");
    }

    #[test]
    fn positional_graph_defaults_use_resolved_basename_and_explicit_ids_win() {
        // A nested cwd-relative path exposes accidental root/path double joining.
        let relative = PathBuf::from(format!(".graphite-serve-defaults-{}", std::process::id()));
        let root = std::env::current_dir().unwrap().join(&relative);
        let source = root.join("graphs/app.v1");
        write_graph(&source);
        let archive = root.join("graphs/app.graphite");
        graphite_storage::container::pack(&source, &archive).unwrap();
        let named_directory = root.join("graphs/directory.graphite");
        write_graph(&named_directory);
        for (path, id) in [
            (relative.join("graphs/app.graphite"), "app"),
            (source.clone(), "app.v1"),
            (source.join("."), "app.v1"),
            (named_directory, "directory.graphite"),
        ] {
            let mut options = args(path);
            assert_served(&open(&options, false).unwrap(), id);
            options.id = Some("explicit".into());
            assert_served(&open(&options, false).unwrap(), "explicit");
        }
        let mut options = args(PathBuf::from("graphs/app.graphite"));
        options.data = Some(root.clone());
        assert_served(&open(&options, false).unwrap(), "app");
        options.graphs.push(format!("app:{}", source.display()));
        let error = open(&options, false).err().unwrap();
        assert_eq!(error, "Duplicate initial graph id: app");
        std::fs::remove_dir_all(root).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn positional_symlink_ids_use_alias_names_instead_of_targets() {
        let root = tempdir("positional-alias");
        let target = root.join("store/bad target name");
        write_graph(&target);
        let archive_target = root.join("store/revision-123.graphite");
        graphite_storage::container::pack(&target, &archive_target).unwrap();
        let bad_archive_target = root.join("store/bad archive.graphite");
        graphite_storage::container::pack(&target, &bad_archive_target).unwrap();
        for (alias, target, id) in [
            ("app.graphite", archive_target, "app"),
            ("valid.graphite", bad_archive_target, "valid"),
            ("directory.v1", target.clone(), "directory.v1"),
            ("directory.graphite", target, "directory.graphite"),
        ] {
            let alias = root.join(alias);
            std::os::unix::fs::symlink(target, &alias).unwrap();
            assert_served(&open(&args(alias), false).unwrap(), id);
        }
        write_graph(&root.join("other"));
        let mut options = args(root.join("app.graphite"));
        options.id = Some("app".into());
        options.graphs.push("other:other".into());
        let opened = open(&options, false).unwrap();
        assert_eq!(opened.registry.ids(), vec!["app", "other"]);
        let other = opened.registry.acquire("other").unwrap().unwrap();
        let registered = opened.registry.list();
        let other_path = &registered
            .iter()
            .find(|graph| graph.id == "other")
            .unwrap()
            .path;
        assert_eq!(other_path, &root.join("other").canonicalize().unwrap());
        let graphite_storage::NodeKind::StringConstant(value) = other.graph.node(0).unwrap().kind
        else {
            panic!("expected string")
        };
        assert_eq!(other.graph.str(value), "served");
        drop(opened);
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn positional_invalid_basename_requires_explicit_id() {
        let root = tempdir("positional-badname");
        let path = root.join("bad name");
        write_graph(&path);
        let mut options = args(path);
        assert!(open(&options, false)
            .err()
            .unwrap()
            .contains("pass --id explicitly"));
        options.id = Some("valid".into());
        assert_served(&open(&options, false).unwrap(), "valid");
        std::fs::remove_dir_all(root).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn positional_id_does_not_lossily_decode_non_unicode_basename() {
        use std::os::unix::ffi::OsStringExt;
        let path = PathBuf::from(std::ffi::OsString::from_vec(b"app-\xff".to_vec()));
        assert!(positional_graph_id(&path)
            .unwrap_err()
            .contains("pass --id explicitly"));
    }

    #[cfg(all(unix, not(target_vendor = "apple")))]
    #[test]
    fn positional_non_unicode_basename_requires_explicit_id() {
        use std::os::unix::ffi::OsStringExt;
        let root = tempdir("positional-nonunicode");
        let path = root.join(std::ffi::OsString::from_vec(b"app-\xff".to_vec()));
        write_graph(&path);
        let mut options = args(path);
        assert!(open(&options, false)
            .err()
            .unwrap()
            .contains("pass --id explicitly"));
        options.id = Some("valid".into());
        assert_served(&open(&options, false).unwrap(), "valid");
        std::fs::remove_dir_all(root).unwrap();
    }

    /// Against a real graph (`GRAPHITE_INDEX_FIXTURE`): the server is ready with its declared
    /// tables read, so the first request that needs a declared value parses nothing.
    #[test]
    fn open_reads_the_declared_tables_during_loading() {
        let Some(dir) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
            eprintln!("GRAPHITE_INDEX_FIXTURE unset; skipping");
            return;
        };
        let dir = PathBuf::from(dir);
        let args = GraphArgs {
            graph_dir: Some(dir.clone()),
            data: None,
            graphs: vec![],
            id: Some("fixture".into()),
            load_mode: "MAPPED".into(),
            topology: None,
            max_concurrent_cypher: 2,
            cypher_work_budget: None,
            cypher_max_timeout_ms: 1_000,
        };
        let opened = open(&args, false).unwrap();
        let graph = opened.registry.acquire("fixture").unwrap().unwrap().graph;
        assert!(
            graph.declared_types_initialized(),
            "ready means the tables are read"
        );
        assert!(graph.declared_types().is_some());
        // A reader without a worker reads inline, and finishing twice-queued work is harmless.
        let reader = DeclaredTableReader {
            sender: None,
            worker: None,
        };
        let fresh = Arc::new(graphite_storage::graph::Graph::load(&dir).unwrap());
        reader.queue(&fresh);
        assert_eq!(reader.finish(), 0);
        let reader = DeclaredTableReader::start();
        reader.queue(&fresh);
        assert_eq!(reader.finish(), 1);
        assert!(fresh.declared_types_initialized());
    }

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

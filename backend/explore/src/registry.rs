//! Multi-graph registry with lease-based hot reload.
//!
//! A graph being replaced or removed is *retired*, not dropped: in-flight queries keep
//! their lease and run to completion against the old snapshot, while new requests see
//! the replacement. The old graph is released when its last lease closes.

use graphite_cypher::engine::Source;
use graphite_storage::Graph;
use parking_lot::Mutex;
use serde_json::{json, Value as J};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LoadMode {
    Eager,
    Mapped,
    Auto,
}

impl LoadMode {
    pub fn parse(raw: &str) -> Result<LoadMode, String> {
        match raw.to_ascii_uppercase().as_str() {
            "EAGER" => Ok(LoadMode::Eager),
            "MAPPED" => Ok(LoadMode::Mapped),
            "AUTO" => Ok(LoadMode::Auto),
            other => Err(format!(
                "No enum constant io.johnsonlee.graphite.webgraph.GraphStore.LoadMode.{other}"
            )),
        }
    }
    pub fn name(self) -> &'static str {
        match self {
            LoadMode::Eager => "EAGER",
            LoadMode::Mapped => "MAPPED",
            LoadMode::Auto => "AUTO",
        }
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct GraphStats {
    pub nodes: i64,
    pub edges: i64,
    pub methods: i64,
    pub call_sites: i64,
}

impl GraphStats {
    pub fn of(g: &Graph) -> GraphStats {
        GraphStats {
            nodes: g.node_count() as i64,
            edges: g.edge_count() as i64,
            methods: g.method_count() as i64,
            call_sites: g.count_by_tag(graphite_storage::node::TAG_CALL_SITE_NODE) as i64,
        }
    }
    pub fn plus(self, o: GraphStats) -> GraphStats {
        GraphStats {
            nodes: self.nodes + o.nodes,
            edges: self.edges + o.edges,
            methods: self.methods + o.methods,
            call_sites: self.call_sites + o.call_sites,
        }
    }
    pub fn to_api_map(self) -> J {
        json!({
            "nodes": self.nodes,
            "edges": self.edges,
            "methods": self.methods,
            "callSites": self.call_sites,
        })
    }
}

pub struct ServedGraph {
    pub id: String,
    pub path: PathBuf,
    pub load_mode: LoadMode,
    pub loaded_at: String,
    pub stats: GraphStats,
    pub generation: u64,
    pub graph: Arc<Graph>,
    /// The content fingerprint (see `graphite_storage::container::fingerprint_of`),
    /// `None` when it could not be computed.
    pub fingerprint: Option<String>,
}

impl ServedGraph {
    pub fn to_api_map(&self) -> J {
        json!({
            "id": self.id,
            "path": self.path.display().to_string(),
            "loadMode": self.load_mode.name(),
            "loadedAt": self.loaded_at,
            "nodes": self.stats.nodes,
            "edges": self.stats.edges,
            "methods": self.stats.methods,
            "callSites": self.stats.call_sites,
        })
    }
}

/// A borrowed graph. Holding one keeps the snapshot alive across a replace.
#[derive(Clone)]
pub struct GraphLease {
    pub id: String,
    pub graph: Arc<Graph>,
    pub stats: GraphStats,
    /// The registry generation of the served graph (see `ServedGraph::generation`).
    pub generation: u64,
}

pub struct GraphRegistry {
    data_dir: PathBuf,
    default_mode: LoadMode,
    graphs: Mutex<BTreeMap<String, Arc<ServedGraph>>>,
    next_generation: AtomicU64,
}

/// `Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")` on the trimmed id.
pub fn validate_graph_id(raw: &str) -> Result<String, String> {
    let id = raw.trim();
    let bytes = id.as_bytes();
    let ok = !bytes.is_empty()
        && bytes.len() <= 128
        && bytes[0].is_ascii_alphanumeric()
        && bytes
            .iter()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-'));
    if ok {
        Ok(id.to_string())
    } else {
        Err(format!(
            "Invalid graph id '{raw}'. Use 1-128 chars: letters, digits, dot, underscore, or dash."
        ))
    }
}

impl GraphRegistry {
    pub fn new(data_dir: PathBuf, default_mode: LoadMode) -> GraphRegistry {
        GraphRegistry {
            data_dir,
            default_mode,
            graphs: Mutex::new(BTreeMap::new()),
            next_generation: AtomicU64::new(1),
        }
    }

    pub fn data_dir(&self) -> &Path {
        &self.data_dir
    }

    pub fn default_mode(&self) -> LoadMode {
        self.default_mode
    }

    pub fn resolve_path(&self, p: &Path) -> PathBuf {
        if p.is_absolute() {
            normalize(p)
        } else {
            normalize(&self.data_dir.join(p))
        }
    }

    pub fn load(
        &self,
        id: &str,
        path: &Path,
        mode: Option<LoadMode>,
    ) -> Result<Arc<ServedGraph>, String> {
        let id = validate_graph_id(id)?;
        let resolved = self.resolve_path(path);
        // A directory or a .graphite container; the message is the Kotlin server's,
        // byte for byte, because the differential harness compares error bodies.
        if !resolved.exists() {
            return Err(format!(
                "Graph path is not a directory: {}",
                resolved.display()
            ));
        }
        let mode = mode.unwrap_or(self.default_mode);
        // Load before taking the lock so a slow load never blocks readers.
        let graph = Graph::load(&resolved).map_err(|e| e.to_string())?;
        let stats = GraphStats::of(&graph);
        // Hashes every file once per load; what `graphite_graph_info` reports.
        let fingerprint = graphite_storage::container::fingerprint_of(&resolved).ok();
        let served = Arc::new(ServedGraph {
            id: id.clone(),
            path: resolved,
            load_mode: mode,
            loaded_at: now_iso8601(),
            stats,
            generation: self.next_generation.fetch_add(1, Ordering::SeqCst),
            graph: Arc::new(graph),
            fingerprint,
        });
        self.graphs.lock().insert(id, served.clone());
        Ok(served)
    }

    pub fn unload(&self, id: &str) -> Result<bool, String> {
        Ok(self.take(id)?.is_some())
    }

    /// Remove a graph and hand it back, so a caller can restore it if what follows
    /// the removal fails.
    pub fn take(&self, id: &str) -> Result<Option<Arc<ServedGraph>>, String> {
        let id = validate_graph_id(id)?;
        Ok(self.graphs.lock().remove(id.as_str()))
    }

    /// Put a removed graph back under its id, keeping its generation.
    pub fn restore(&self, served: Arc<ServedGraph>) {
        self.graphs.lock().insert(served.id.clone(), served);
    }

    pub fn describe(&self, id: &str) -> Result<Option<Arc<ServedGraph>>, String> {
        let id = validate_graph_id(id)?;
        Ok(self.graphs.lock().get(id.as_str()).cloned())
    }

    pub fn list(&self) -> Vec<Arc<ServedGraph>> {
        self.graphs.lock().values().cloned().collect()
    }

    pub fn ids(&self) -> Vec<String> {
        self.graphs.lock().keys().cloned().collect()
    }

    pub fn is_empty(&self) -> bool {
        self.graphs.lock().is_empty()
    }

    /// Lease one graph by id. `Ok(None)` means the id is valid but not loaded.
    pub fn acquire(&self, id: &str) -> Result<Option<GraphLease>, String> {
        let id = validate_graph_id(id)?;
        Ok(self.graphs.lock().get(id.as_str()).map(|s| GraphLease {
            id: s.id.clone(),
            graph: s.graph.clone(),
            stats: s.stats,
            generation: s.generation,
        }))
    }

    /// Lease every loaded graph, in sorted id order.
    pub fn acquire_all(&self) -> Vec<GraphLease> {
        self.graphs
            .lock()
            .values()
            .map(|s| GraphLease {
                id: s.id.clone(),
                graph: s.graph.clone(),
                stats: s.stats,
                generation: s.generation,
            })
            .collect()
    }

    /// Snapshot all query sources in sorted id order, with request-local ID Arcs.
    /// The graph Arc keeps a removed or replaced snapshot alive just like a lease.
    /// Public leases retain their String ids for API compatibility.
    pub(crate) fn acquire_all_sources(&self) -> Vec<Source> {
        self.graphs
            .lock()
            .iter()
            .map(|(id, served)| Source {
                id: Arc::from(id.as_str()),
                graph: served.graph.clone(),
            })
            .collect()
    }

    /// Lease the named graphs, preserving caller order and dropping duplicates.
    pub fn acquire_ids(&self, ids: &[String]) -> Result<Vec<GraphLease>, GraphAcquireError> {
        let map = self.graphs.lock();
        let mut seen = std::collections::HashSet::new();
        let mut out = Vec::with_capacity(ids.len());
        for raw in ids {
            let id = validate_graph_id(raw).map_err(GraphAcquireError::Invalid)?;
            if !seen.insert(id.clone()) {
                continue;
            }
            match map.get(id.as_str()) {
                Some(s) => out.push(GraphLease {
                    id: s.id.clone(),
                    graph: s.graph.clone(),
                    stats: s.stats,
                    generation: s.generation,
                }),
                None => return Err(GraphAcquireError::NotLoaded(id)),
            }
        }
        Ok(out)
    }

    pub fn totals(&self) -> GraphStats {
        self.graphs
            .lock()
            .values()
            .fold(GraphStats::default(), |acc, s| acc.plus(s.stats))
    }

    /// Map of id to generation, used to detect a stale topology snapshot.
    pub fn catalog_version(&self) -> BTreeMap<String, u64> {
        self.graphs
            .lock()
            .iter()
            .map(|(k, v)| (k.clone(), v.generation))
            .collect()
    }
}

pub enum GraphAcquireError {
    Invalid(String),
    NotLoaded(String),
}

/// Lexical path normalization (no filesystem access, so missing paths still normalize).
fn normalize(p: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for c in p.components() {
        match c {
            std::path::Component::ParentDir => {
                out.pop();
            }
            std::path::Component::CurDir => {}
            other => out.push(other.as_os_str()),
        }
    }
    out
}

/// `java.time.Instant.toString()` — ISO-8601 UTC, seconds precision when nanos are zero,
/// otherwise a fraction padded to a multiple of three digits.
pub fn now_iso8601() -> String {
    let now = chrono::Utc::now();
    format_instant(now)
}

pub fn format_instant(t: chrono::DateTime<chrono::Utc>) -> String {
    use chrono::Timelike;
    let nanos = t.nanosecond();
    if nanos == 0 {
        t.format("%Y-%m-%dT%H:%M:%SZ").to_string()
    } else if nanos.is_multiple_of(1_000_000) {
        t.format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string()
    } else if nanos.is_multiple_of(1_000) {
        t.format("%Y-%m-%dT%H:%M:%S%.6fZ").to_string()
    } else {
        t.format("%Y-%m-%dT%H:%M:%S%.9fZ").to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // These are persisted-graph correctness checks, not timing/allocation benchmarks.
    // Run with GRAPHITE_INDEX_FIXTURE, like the existing Explorer graph tests.
    fn fixture_registry() -> Option<(GraphRegistry, PathBuf)> {
        let Some(path) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
            eprintln!("GRAPHITE_INDEX_FIXTURE unset; skipping");
            return None;
        };
        let path = PathBuf::from(path);
        Some((GraphRegistry::new(path.clone(), LoadMode::Mapped), path))
    }

    #[test]
    fn query_sources_isolate_request_ids_and_preserve_sorted_snapshot_order() {
        let Some((registry, path)) = fixture_registry() else {
            return;
        };
        assert!(registry.acquire_all_sources().is_empty());
        registry.load("z", &path, None).unwrap();
        registry.load(" a ", &path, None).unwrap();
        let first = registry.acquire_all_sources();
        let second = registry.acquire_all_sources();
        assert_eq!(
            first.iter().map(|s| s.id.as_ref()).collect::<Vec<_>>(),
            vec!["a", "z"]
        );
        assert_eq!(registry.ids(), vec!["a".to_string(), "z".to_string()]);
        for (a, b) in first.iter().zip(&second) {
            assert_eq!(a.id, b.id);
            assert!(
                !Arc::ptr_eq(&a.id, &b.id),
                "Separate requests own separate ID counters"
            );
            assert!(Arc::ptr_eq(&a.graph, &b.graph));
            let served = registry.describe(&a.id).unwrap().unwrap();
            assert_eq!(served.id, a.id.as_ref());
            assert!(Arc::ptr_eq(&a.graph, &served.graph));
        }
        let ids = vec!["z".into(), " a ".into(), "z".into()];
        let leases = match registry.acquire_ids(&ids) {
            Ok(leases) => leases,
            Err(_) => panic!("Known IDs must be acquired"),
        };
        assert_eq!(
            leases.iter().map(|l| l.id.as_str()).collect::<Vec<_>>(),
            vec!["z", "a"]
        );
        // Existing field types remain usable without conversions by public callers.
        let _: &String = &leases[0].id;
        assert!(matches!(
            registry.acquire_ids(&["missing".into()]),
            Err(GraphAcquireError::NotLoaded(id)) if id == "missing"
        ));
        assert!(registry.acquire("missing").unwrap().is_none());
        assert!(registry.acquire("bad id").is_err());
        let cancel = graphite_cypher::engine::CancelToken::new();
        cancel.cancel();
        let executor = graphite_cypher::engine::Executor::new(second, true)
            .with_cancel(cancel)
            .with_compact()
            .with_probe();
        assert!(matches!(
            executor.execute("MATCH (n) RETURN n LIMIT 1", Some(1)),
            Err(graphite_cypher::CypherError::Cancelled)
        ));
    }

    #[test]
    fn compact_rows_share_their_request_source_id_without_sharing_across_requests() {
        use graphite_cypher::{engine::Executor, value::Value};

        let Some((registry, path)) = fixture_registry() else {
            return;
        };
        let served = registry.load("g", &path, None).unwrap();
        assert!(
            served.stats.call_sites >= 3,
            "Fixture needs three CallSites"
        );
        let first = registry.acquire_all_sources();
        let second = registry.acquire_all_sources();
        assert!(Arc::ptr_eq(&first[0].graph, &second[0].graph));
        assert!(!Arc::ptr_eq(&first[0].id, &second[0].id));
        let mut retained_ids = Vec::new();
        for sources in [first, second] {
            let source_id = sources[0].id.clone();
            let ex = Executor::new(sources, true).with_compact().with_probe();
            let result = ex
                .execute(
                    "MATCH (n:CallSiteNode) WHERE n.graphId = 'g' RETURN n.graphId AS graphId LIMIT 2",
                    Some(2),
                )
                .unwrap();
            assert_eq!(result.columns, vec!["graphId"]);
            assert!(result.more, "The third CallSite supplies the probe row");
            assert!(result.rows.is_empty());
            let compact = result
                .compact
                .as_ref()
                .expect("Compact projection required");
            assert_eq!(compact.values.len(), 2);
            assert_eq!(compact.graph_ids.len(), 2);
            for (values, provenance) in compact.values.iter().zip(&compact.graph_ids) {
                assert_eq!(values.len(), 1);
                let Value::Str(projected_id) = &values[0] else {
                    panic!("graphId must remain a string");
                };
                assert_eq!(projected_id.as_ref(), "g");
                assert_eq!(provenance.as_ref(), "g");
                assert!(Arc::ptr_eq(projected_id, &source_id));
                assert!(Arc::ptr_eq(provenance, &source_id));
            }
            retained_ids.push(compact.graph_ids[0].clone());
            drop(result);
            drop(ex);
            assert_eq!(source_id.as_ref(), "g");
        }
        assert_eq!(retained_ids[0], retained_ids[1]);
        assert!(!Arc::ptr_eq(&retained_ids[0], &retained_ids[1]));
    }

    #[test]
    fn shared_sources_keep_old_graphs_alive_across_reload_and_remove() {
        let Some((registry, path)) = fixture_registry() else {
            return;
        };
        let initial = registry.load("g", &path, None).unwrap();
        let generation = initial.generation;
        let old_graph = Arc::downgrade(&initial.graph);
        let old_lease = registry.acquire("g").unwrap().unwrap();
        let sources = registry.acquire_all_sources();
        drop(initial);
        let replacement = registry.load("g", &path, None).unwrap();
        assert!(replacement.generation > generation);
        assert_eq!(old_lease.generation, generation);
        assert!(Arc::ptr_eq(&old_lease.graph, &sources[0].graph));
        assert!(!Arc::ptr_eq(&replacement.graph, &sources[0].graph));
        assert_eq!(
            registry.catalog_version().get("g"),
            Some(&replacement.generation)
        );
        let fresh = registry.acquire_all_sources();
        assert!(Arc::ptr_eq(&fresh[0].graph, &replacement.graph));
        assert_eq!(fresh[0].id.as_ref(), "g");
        assert_eq!(sources[0].id.as_ref(), "g");
        drop(old_lease);
        assert!(
            old_graph.upgrade().is_some(),
            "Source holds the retired graph"
        );
        drop(sources);
        assert!(
            old_graph.upgrade().is_none(),
            "Retired graph releases with last source"
        );
        let removed = registry.take("g").unwrap().unwrap();
        assert!(registry.acquire_all_sources().is_empty());
        assert!(registry.acquire("g").unwrap().is_none());
        assert!(Arc::ptr_eq(&fresh[0].graph, &removed.graph));
        registry.restore(removed);
        let restored = registry.acquire_all_sources();
        assert_eq!(restored[0].id.as_ref(), "g");
        assert!(Arc::ptr_eq(&restored[0].graph, &fresh[0].graph));
        assert_eq!(
            registry.catalog_version().get("g"),
            Some(&replacement.generation)
        );
    }

    #[test]
    fn graph_ids_are_validated() {
        assert_eq!(validate_graph_id(" orders ").unwrap(), "orders");
        assert_eq!(validate_graph_id("a.b-c_1").unwrap(), "a.b-c_1");
        assert!(validate_graph_id("").is_err());
        assert!(validate_graph_id("-leading").is_err());
        assert!(validate_graph_id("has space").is_err());
        assert!(validate_graph_id(&"a".repeat(129)).is_err());
        assert_eq!(
            validate_graph_id("bad id").unwrap_err(),
            "Invalid graph id 'bad id'. Use 1-128 chars: letters, digits, dot, underscore, or dash."
        );
    }

    #[test]
    fn load_mode_parsing_matches_java_enum_errors() {
        assert_eq!(LoadMode::parse("mapped").unwrap(), LoadMode::Mapped);
        assert_eq!(
            LoadMode::parse("nope").unwrap_err(),
            "No enum constant io.johnsonlee.graphite.webgraph.GraphStore.LoadMode.NOPE"
        );
    }

    #[test]
    fn stats_add_componentwise() {
        let a = GraphStats {
            nodes: 1,
            edges: 2,
            methods: 3,
            call_sites: 4,
        };
        let b = GraphStats {
            nodes: 10,
            edges: 20,
            methods: 30,
            call_sites: 40,
        };
        assert_eq!(
            a.plus(b),
            GraphStats {
                nodes: 11,
                edges: 22,
                methods: 33,
                call_sites: 44
            }
        );
    }

    #[test]
    fn instant_formatting_trims_like_java() {
        use chrono::TimeZone;
        let t = chrono::Utc
            .with_ymd_and_hms(2026, 9, 9, 13, 25, 19)
            .unwrap();
        assert_eq!(format_instant(t), "2026-09-09T13:25:19Z");
        let t = t + chrono::Duration::milliseconds(123);
        assert_eq!(format_instant(t), "2026-09-09T13:25:19.123Z");
    }
}

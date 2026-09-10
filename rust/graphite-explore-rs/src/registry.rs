//! Multi-graph registry with lease-based hot reload.
//!
//! A graph being replaced or removed is *retired*, not dropped: in-flight queries keep
//! their lease and run to completion against the old snapshot, while new requests see
//! the replacement. The old graph is released when its last lease closes.

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

    pub fn load(&self, id: &str, path: &Path, mode: Option<LoadMode>) -> Result<Arc<ServedGraph>, String> {
        let id = validate_graph_id(id)?;
        let resolved = self.resolve_path(path);
        if !resolved.is_dir() {
            return Err(format!("Graph path is not a directory: {}", resolved.display()));
        }
        let mode = mode.unwrap_or(self.default_mode);
        // Load before taking the lock so a slow load never blocks readers.
        let graph = Graph::load(&resolved).map_err(|e| e.to_string())?;
        let stats = GraphStats::of(&graph);
        let served = Arc::new(ServedGraph {
            id: id.clone(),
            path: resolved,
            load_mode: mode,
            loaded_at: now_iso8601(),
            stats,
            generation: self.next_generation.fetch_add(1, Ordering::SeqCst),
            graph: Arc::new(graph),
        });
        self.graphs.lock().insert(id, served.clone());
        Ok(served)
    }

    pub fn unload(&self, id: &str) -> Result<bool, String> {
        let id = validate_graph_id(id)?;
        Ok(self.graphs.lock().remove(&id).is_some())
    }

    pub fn describe(&self, id: &str) -> Result<Option<Arc<ServedGraph>>, String> {
        let id = validate_graph_id(id)?;
        Ok(self.graphs.lock().get(&id).cloned())
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
        Ok(self.graphs.lock().get(&id).map(|s| GraphLease {
            id: s.id.clone(),
            graph: s.graph.clone(),
            stats: s.stats,
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
            match map.get(&id) {
                Some(s) => out.push(GraphLease {
                    id: s.id.clone(),
                    graph: s.graph.clone(),
                    stats: s.stats,
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
    } else if nanos % 1_000_000 == 0 {
        t.format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string()
    } else if nanos % 1_000 == 0 {
        t.format("%Y-%m-%dT%H:%M:%S%.6fZ").to_string()
    } else {
        t.format("%Y-%m-%dT%H:%M:%S%.9fZ").to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
        let a = GraphStats { nodes: 1, edges: 2, methods: 3, call_sites: 4 };
        let b = GraphStats { nodes: 10, edges: 20, methods: 30, call_sites: 40 };
        assert_eq!(
            a.plus(b),
            GraphStats { nodes: 11, edges: 22, methods: 33, call_sites: 44 }
        );
    }

    #[test]
    fn instant_formatting_trims_like_java() {
        use chrono::TimeZone;
        let t = chrono::Utc.with_ymd_and_hms(2026, 9, 9, 13, 25, 19).unwrap();
        assert_eq!(format_instant(t), "2026-09-09T13:25:19Z");
        let t = t + chrono::Duration::milliseconds(123);
        assert_eq!(format_instant(t), "2026-09-09T13:25:19.123Z");
    }
}

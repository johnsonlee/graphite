//! Query executor over one or more persisted graphs.

pub mod fastpath;
pub mod matching;
pub mod pipeline;
pub mod props;
pub mod scan;

use crate::context::GraphContext;
use crate::value::{EdgeRef, MethodRef, NodeRef, SourceIdx, Value};
use crate::{CypherError, CypherResult};
use graphite_storage::{Graph, Node};
use indexmap::IndexMap;
use parking_lot::Mutex;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Instant;

pub use pipeline::{QueryResult, Row, INTERNAL_PROVENANCE_KEY};

/// When set, index fast paths and scan pushdown are skipped and every query runs
/// through the general row-by-row pipeline. Exists so the gain from the query
/// strategy can be measured separately from the gain from the runtime.
pub fn optimizations_disabled() -> bool {
    static DISABLED: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *DISABLED.get_or_init(|| std::env::var("GRAPHITE_NO_FASTPATH").is_ok())
}

/// One graph participating in a query.
#[derive(Clone)]
pub struct Source {
    pub id: Arc<str>,
    pub graph: Arc<Graph>,
}

/// Cooperative cancellation + deadline.
#[derive(Default)]
pub struct CancelToken {
    cancelled: AtomicBool,
    /// 0 = none; 1 = cancelled; 2 = timeout
    reason: AtomicU64,
    timeout_ms: AtomicU64,
    deadline: Mutex<Option<Instant>>,
}

impl CancelToken {
    pub fn new() -> Arc<CancelToken> {
        Arc::new(CancelToken::default())
    }
    pub fn with_timeout(ms: u64) -> Arc<CancelToken> {
        let t = CancelToken::default();
        t.timeout_ms.store(ms, Ordering::Relaxed);
        *t.deadline.lock() = Some(Instant::now() + std::time::Duration::from_millis(ms));
        Arc::new(t)
    }
    pub fn cancel(&self) {
        if self
            .reason
            .compare_exchange(0, 1, Ordering::SeqCst, Ordering::SeqCst)
            .is_ok()
        {
            self.cancelled.store(true, Ordering::SeqCst);
        }
    }
    pub fn timeout(&self, ms: u64) {
        if self
            .reason
            .compare_exchange(0, 2, Ordering::SeqCst, Ordering::SeqCst)
            .is_ok()
        {
            self.timeout_ms.store(ms, Ordering::SeqCst);
            self.cancelled.store(true, Ordering::SeqCst);
        }
    }
    pub fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::Relaxed)
    }
    pub fn error(&self) -> Option<CypherError> {
        match self.reason.load(Ordering::SeqCst) {
            1 => Some(CypherError::Cancelled),
            2 => Some(CypherError::Timeout(self.timeout_ms.load(Ordering::SeqCst))),
            _ => None,
        }
    }
    #[inline]
    pub fn check(&self) -> CypherResult<()> {
        if self.cancelled.load(Ordering::Relaxed) {
            return Err(self.error().unwrap_or(CypherError::Cancelled));
        }
        if let Some(d) = *self.deadline.lock() {
            if Instant::now() >= d {
                self.timeout(self.timeout_ms.load(Ordering::Relaxed));
                return Err(self.error().unwrap());
            }
        }
        Ok(())
    }
}

/// Source of executor epochs; see `Executor::node`.
static EPOCH: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);

pub struct Executor {
    pub sources: Vec<Source>,
    /// Cross-graph (qualified) mode.
    pub cross: bool,
    pub params: IndexMap<String, Value>,
    pub cancel: Arc<CancelToken>,
    /// Kotlin `graphSourceScopeApplied`: sources were explicitly selected (affects nothing observable but kept).
    pub scoped: bool,
    /// Distinguishes this executor's cached node decodes from any earlier executor's
    /// on the same thread. Monotonic, so it never repeats the way an address can.
    epoch: u64,
    poll: AtomicU64,
}

impl Executor {
    pub fn single(id: impl Into<Arc<str>>, graph: Arc<Graph>) -> Executor {
        Executor::new(
            vec![Source {
                id: id.into(),
                graph,
            }],
            false,
        )
    }

    pub fn new(sources: Vec<Source>, cross: bool) -> Executor {
        Executor {
            sources,
            cross,
            params: IndexMap::new(),
            cancel: CancelToken::new(),
            scoped: false,
            epoch: EPOCH.fetch_add(1, Ordering::Relaxed),
            poll: AtomicU64::new(0),
        }
    }

    pub fn with_params(mut self, params: IndexMap<String, Value>) -> Self {
        self.params = params;
        self
    }

    pub fn with_cancel(mut self, cancel: Arc<CancelToken>) -> Self {
        self.cancel = cancel;
        self
    }

    #[inline]
    pub fn graph(&self, source: SourceIdx) -> &Graph {
        &self.sources[source as usize].graph
    }

    /// Decode a node with a one-entry cache: a projection reads several properties of
    /// the same node in a row, and decoding it once per property was most of the cost
    /// of a wide `RETURN`.
    ///
    /// The cache is per thread, not per executor. Row production is serial on one
    /// thread, and the planners on the other threads never read node properties, so a
    /// shared slot behind a mutex bought nothing and cost two lock round trips per
    /// property read. The executor's epoch is part of the key so a slot left behind by
    /// an earlier query on this thread can never answer for a different executor --
    /// an address could be reused by a later executor over a reloaded graph, an epoch
    /// cannot.
    pub fn node(&self, r: NodeRef) -> Option<Arc<Node>> {
        thread_local! {
            static NODE: std::cell::RefCell<Option<(u64, NodeRef, Arc<Node>)>> =
                const { std::cell::RefCell::new(None) };
        }
        let me = self.epoch;
        if let Some(hit) = NODE.with(|c| {
            c.borrow()
                .as_ref()
                .filter(|(owner, k, _)| *owner == me && *k == r)
                .map(|(_, _, n)| n.clone())
        }) {
            return Some(hit);
        }
        let n = Arc::new(self.graph(r.source).node(r.id)?);
        NODE.with(|c| *c.borrow_mut() = Some((me, r, n.clone())));
        Some(n)
    }

    /// Poll cancellation every 1024 calls.
    #[inline]
    pub fn tick(&self) -> CypherResult<()> {
        let c = self.poll.fetch_add(1, Ordering::Relaxed);
        if c & 1023 == 0 {
            self.cancel.check()
        } else {
            Ok(())
        }
    }

    pub fn element_id(&self, n: NodeRef) -> String {
        if self.cross {
            format!("{}:{}", self.sources[n.source as usize].id, n.id)
        } else {
            n.id.to_string()
        }
    }

    pub fn graph_id_opt(&self, source: SourceIdx) -> Option<&str> {
        if self.cross {
            Some(&self.sources[source as usize].id)
        } else {
            None
        }
    }
}

impl GraphContext for Executor {
    fn source_count(&self) -> usize {
        self.sources.len()
    }
    fn is_cross_graph(&self) -> bool {
        self.cross
    }
    fn graph_id(&self, source: SourceIdx) -> &str {
        &self.sources[source as usize].id
    }
    fn node_property(&self, node: NodeRef, key: &str) -> Value {
        if self.cross {
            match key {
                "graphId" => return Value::str(self.graph_id(node.source)),
                "elementId" | "qualifiedId" => return Value::str(self.element_id(node)),
                _ => {}
            }
        }
        match self.node(node) {
            Some(n) => props::node_property(self.graph(node.source), &n, key),
            None => Value::Null,
        }
    }
    fn node_properties(&self, node: NodeRef) -> IndexMap<String, Value> {
        let mut m = match self.node(node) {
            Some(n) => props::node_properties(self.graph(node.source), &n),
            None => IndexMap::new(),
        };
        if self.cross {
            m.insert("graphId".into(), Value::str(self.graph_id(node.source)));
            let eid = self.element_id(node);
            m.insert("elementId".into(), Value::str(eid.clone()));
            m.insert("qualifiedId".into(), Value::str(eid));
        }
        m
    }
    fn node_result_properties(&self, node: NodeRef) -> IndexMap<String, Value> {
        let mut m = self.node_display_properties(node);
        m.retain(|_, v| !v.is_null());
        m
    }
    fn node_display_properties(&self, node: NodeRef) -> IndexMap<String, Value> {
        let mut m = match self.node(node) {
            Some(n) => props::node_display_properties(self.graph(node.source), &n),
            None => IndexMap::new(),
        };
        if self.cross {
            m.insert("graphId".into(), Value::str(self.graph_id(node.source)));
            let eid = self.element_id(node);
            m.insert("elementId".into(), Value::str(eid.clone()));
            m.insert("qualifiedId".into(), Value::str(eid));
        }
        m
    }
    fn edge_comparison(&self, rel: EdgeRef) -> Option<(String, u32)> {
        self.graph(rel.source)
            .comparison(&rel.edge)
            .map(|c| (format!("{:?}", c.op), c.comparand))
    }
    fn node_labels(&self, node: NodeRef) -> Vec<&'static str> {
        match self.graph(node.source).node_tag(node.id) {
            Some(t) => props::node_labels(t),
            None => vec![],
        }
    }
    fn node_type_name(&self, node: NodeRef) -> &'static str {
        match self.graph(node.source).node_tag(node.id) {
            Some(t) => graphite_storage::node::tag_type_name(t),
            None => "Node",
        }
    }
    fn rel_property(&self, rel: EdgeRef, key: &str) -> Value {
        props::rel_property(
            self.graph(rel.source),
            rel,
            key,
            self.graph_id_opt(rel.source),
        )
    }
    fn rel_type(&self, rel: EdgeRef) -> &'static str {
        rel.edge.rel_type()
    }
    fn method_property(&self, m: MethodRef, key: &str) -> Value {
        let g = self.graph(m.source);
        match g.methods().get(m.index as usize) {
            Some(md) => props::method_property(g, md, key, self.graph_id_opt(m.source)),
            None => Value::Null,
        }
    }
    fn method_properties(&self, m: MethodRef) -> IndexMap<String, Value> {
        let g = self.graph(m.source);
        match g.methods().get(m.index as usize) {
            Some(md) => props::method_properties(g, md, self.graph_id_opt(m.source)),
            None => IndexMap::new(),
        }
    }
    fn method_signature(&self, m: MethodRef) -> String {
        let g = self.graph(m.source);
        g.methods()
            .get(m.index as usize)
            .map(|md| md.signature(&g.strings))
            .unwrap_or_default()
    }
    fn check_cancelled(&self) -> CypherResult<()> {
        self.tick()
    }
}

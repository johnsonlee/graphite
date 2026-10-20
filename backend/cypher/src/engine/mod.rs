//! Query executor over one or more persisted graphs.

pub mod fastpath;
pub mod hop;
mod id_candidate;
pub mod matching;
mod method_source_guard;
pub mod partition;
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
    /// Whether a result may come back as `QueryResult::compact` instead of rows. Off by
    /// default; only a consumer that reads the compact form asks for it.
    pub compact: bool,
    /// Probe one row past every trailing literal LIMIT; see `QueryResult::more`.
    pub probe: bool,
    /// Run aggregations once per partition of nodes their expressions cannot tell
    /// apart (see `partition`). On by default; off, every match is a row per node.
    pub partition: bool,
    /// Distinguishes this executor's cached node decodes from any earlier executor's
    /// on the same thread. Monotonic, so it never repeats the way an address can.
    epoch: u64,
    poll: AtomicU64,
    /// The first declared-type table this query needed and could not read. A table that fails
    /// to parse is absent for the whole process, so without this the query would answer as if
    /// the graph declared nothing; the query ends with this error instead (see
    /// `execute_clauses`). Reads that never consult the table leave it untouched.
    declared_failure: std::sync::Mutex<Option<String>>,
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
            compact: false,
            probe: false,
            partition: true,
            epoch: EPOCH.fetch_add(1, Ordering::Relaxed),
            poll: AtomicU64::new(0),
            declared_failure: std::sync::Mutex::new(None),
        }
    }

    /// Records that this query needed `g`'s declared table and the table is unreadable. Called
    /// after an access that read the state, so this never reads it for a graph left unread.
    pub(super) fn note_declared_failure(&self, g: &Graph) {
        if let Some(error) = g.declared_types_failure() {
            let mut slot = self
                .declared_failure
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            if slot.is_none() {
                *slot = Some(format!("declared types unreadable: {error}"));
            }
        }
    }

    /// Notes a result value whose rendering will read a declared table that cannot be read:
    /// a field, parameter or return node, or a method, of such a graph, at any nesting.
    pub(super) fn note_declared_result_value(&self, value: &Value) {
        // Rendering such a value reads the table, so reading the state here, for exactly these
        // values, costs nothing the caller would not pay; any other value leaves it unread.
        match value {
            Value::Node(node) => {
                let g = self.graph(node.source);
                if g.node_tag(node.id).is_some_and(|tag| {
                    matches!(
                        tag,
                        graphite_storage::node::TAG_FIELD_NODE
                            | graphite_storage::node::TAG_PARAMETER_NODE
                            | graphite_storage::node::TAG_RETURN_NODE
                    )
                }) && g.declared_types_error().is_some()
                {
                    self.note_declared_failure(g);
                }
            }
            Value::Method(method) => {
                let g = self.graph(method.source);
                if g.declared_types_error().is_some() {
                    self.note_declared_failure(g);
                }
            }
            Value::List(items) => items
                .iter()
                .for_each(|item| self.note_declared_result_value(item)),
            Value::Map(map) => map
                .values()
                .for_each(|item| self.note_declared_result_value(item)),
            _ => {}
        }
    }

    pub(super) fn take_declared_failure(&self) -> Option<String> {
        self.declared_failure
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .take()
    }

    /// `g`'s declared table for an access this query makes.
    pub(super) fn declared<'a>(
        &self,
        g: &'a Graph,
    ) -> Option<&'a graphite_storage::types::DeclaredTypes> {
        let table = g.declared_types();
        if table.is_none() {
            self.note_declared_failure(g);
        }
        table
    }

    /// Whether `tag`'s nodes carry declarations in `g`; a declaration-bearing tag whose table
    /// cannot be read is a failed access, any other tag never touches the table.
    pub(super) fn declared_tag_available(&self, g: &Graph, tag: u8) -> bool {
        let available = props::has_declared_types_for_tag(g, tag);
        if !available
            && matches!(
                tag,
                graphite_storage::node::TAG_FIELD_NODE
                    | graphite_storage::node::TAG_PARAMETER_NODE
                    | graphite_storage::node::TAG_RETURN_NODE
            )
        {
            self.note_declared_failure(g);
        }
        available
    }

    pub(super) fn declared_node_type_present(
        &self,
        g: &Graph,
        node: &graphite_storage::node::Node,
    ) -> bool {
        let present = props::has_declared_node_type(g, node);
        if !present && props::declaration_bearing(node) {
            self.note_declared_failure(g);
        }
        present
    }

    pub fn with_params(mut self, params: IndexMap<String, Value>) -> Self {
        self.params = params;
        self
    }

    pub fn with_cancel(mut self, cancel: Arc<CancelToken>) -> Self {
        self.cancel = cancel;
        self
    }

    /// Allow the compact result form; see `QueryResult::compact`.
    pub fn with_compact(mut self) -> Self {
        self.compact = true;
        self
    }

    /// Evaluate every match as its own row, never per partition. Exists so the gain
    /// from partitioning can be measured, and its exactness checked, against the
    /// row-per-node pipeline.
    pub fn without_partitioning(mut self) -> Self {
        self.partition = false;
        self
    }

    /// Report whether more rows exist beyond a LIMIT (`QueryResult::more`), at the cost
    /// of matching one row past every trailing literal limit.
    pub fn with_probe(mut self) -> Self {
        self.probe = true;
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
        if key == "value" {
            if let Some(value) = self.graph(node.source).int_constant_value(node.id) {
                return Value::Int(i64::from(value));
            }
        }
        if matches!(
            key,
            "caller_class" | "caller_name" | "callee_class" | "callee_name"
        ) {
            let graph = self.graph(node.source);
            if let Some(fields) = graph.call_site_scalar_strings(node.id) {
                let id = match key {
                    "caller_class" => fields.caller_class,
                    "caller_name" => fields.caller_name,
                    "callee_class" => fields.callee_class,
                    _ => fields.callee_name,
                };
                return Value::str(graph.str(id));
            }
        }
        match self.node(node) {
            Some(n) => {
                let g = self.graph(node.source);
                let value = props::node_property(g, &n, key);
                if matches!(key, "generic_type" | "type_info") && props::declaration_bearing(&n) {
                    self.note_declared_failure(g);
                }
                value
            }
            None => Value::Null,
        }
    }
    fn node_properties(&self, node: NodeRef) -> IndexMap<String, Value> {
        let mut m = match self.node(node) {
            Some(n) => {
                let g = self.graph(node.source);
                let m = props::node_properties(g, &n);
                if props::declaration_bearing(&n) {
                    self.note_declared_failure(g);
                }
                m
            }
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
    fn node_property_is_null(&self, node: NodeRef, key: &str) -> bool {
        if matches!(key, "generic_type" | "type_info") {
            return self.node(node).is_none_or(|n| {
                let g = self.graph(node.source);
                let null = props::node_property_is_null(g, &n, key);
                if props::declaration_bearing(&n) {
                    self.note_declared_failure(g);
                }
                null
            });
        }
        self.node_property(node, key).is_null()
    }
    fn node_keys(&self, node: NodeRef) -> Vec<String> {
        let mut keys = self.node(node).map_or_else(Vec::new, |n| {
            let g = self.graph(node.source);
            let keys = props::node_keys(g, &n);
            if props::declaration_bearing(&n) {
                self.note_declared_failure(g);
            }
            keys
        });
        if self.cross {
            keys.extend(["graphId", "elementId", "qualifiedId"].map(str::to_owned));
        }
        keys
    }
    fn node_result_properties(&self, node: NodeRef) -> IndexMap<String, Value> {
        let mut m = self.node_display_properties(node);
        m.retain(|_, v| !v.is_null());
        m
    }
    fn node_display_properties(&self, node: NodeRef) -> IndexMap<String, Value> {
        let mut m = match self.node(node) {
            Some(n) => {
                let g = self.graph(node.source);
                let m = props::node_display_properties(g, &n);
                if props::declaration_bearing(&n) {
                    self.note_declared_failure(g);
                }
                m
            }
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
            Some(md) => {
                let value = props::method_property(g, md, key, self.graph_id_opt(m.source));
                if props::GENERIC_METHOD_KEYS.contains(&key) {
                    self.note_declared_failure(g);
                }
                value
            }
            None => Value::Null,
        }
    }
    fn method_properties(&self, m: MethodRef) -> IndexMap<String, Value> {
        let g = self.graph(m.source);
        match g.methods().get(m.index as usize) {
            Some(md) => {
                let m = props::method_properties(g, md, self.graph_id_opt(m.source));
                self.note_declared_failure(g);
                m
            }
            None => IndexMap::new(),
        }
    }
    fn method_property_is_null(&self, method: MethodRef, key: &str) -> bool {
        let g = self.graph(method.source);
        g.methods().get(method.index as usize).is_none_or(|md| {
            let null = props::method_property_is_null(g, md, key, self.graph_id_opt(method.source));
            if props::GENERIC_METHOD_KEYS.contains(&key) {
                self.note_declared_failure(g);
            }
            null
        })
    }
    fn method_signature(&self, m: MethodRef) -> String {
        let g = self.graph(m.source);
        g.methods()
            .get(m.index as usize)
            .map(|md| md.signature(g.strings()))
            .unwrap_or_default()
    }
    fn check_cancelled(&self) -> CypherResult<()> {
        self.tick()
    }
}

#[cfg(test)]
mod int_constant_tests;

#[cfg(test)]
mod call_site_scalar_tests;

#[cfg(test)]
mod declared_access_tests;

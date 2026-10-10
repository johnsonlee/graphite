//! Assembled persisted graph: nodes, edges, metadata, strings.

use crate::buffered::{FileRange, ReadRange, ReadWindow};
use crate::bvgraph::{BvError, BvGraph};
use crate::container::Bytes;
use crate::io::{
    check_header, read_i32_at, read_i64_at, Cursor, MAGIC_NODEDATA, MAGIC_NODEOFFSETS,
    MAGIC_TYPEINDEX,
};
use crate::metadata::{
    BranchComparison, ClassOverview, Comparisons, Metadata, MetadataError, Resources,
};
use crate::node::{
    read_call_site_scalar_strings, read_call_site_strings, CallSiteStrings, MethodDesc, Node,
    NodeDecodeError, NodeId, StrId, NODE_HEADER_BYTES, TAG_CALL_SITE_NODE, TAG_COUNT,
    TAG_INT_CONSTANT,
};
use crate::source::{GraphSource, SourceError};
use crate::strings::{StringTable, StringTableError};
use std::path::{Path, PathBuf};

#[derive(Debug, thiserror::Error)]
pub enum GraphError {
    #[error("io error on {0}: {1}")]
    Io(String, std::io::Error),
    #[error(transparent)]
    Bv(#[from] BvError),
    #[error(transparent)]
    Strings(#[from] StringTableError),
    #[error(transparent)]
    Metadata(#[from] MetadataError),
    #[error(transparent)]
    Types(#[from] crate::types::TypeError),
    #[error("bad header in {0}")]
    BadHeader(&'static str),
    #[error("unsupported format version {0} in {1}")]
    BadVersion(u8, &'static str),
    #[error(transparent)]
    Node(#[from] NodeDecodeError),
    #[error("CallSite string index: {0}")]
    CallSiteIndex(String),
    #[error(transparent)]
    Container(#[from] crate::container::ContainerError),
}

impl From<SourceError> for GraphError {
    fn from(e: SourceError) -> GraphError {
        match e {
            SourceError::Io(path, e) => GraphError::Io(path, e),
            SourceError::Container(e) => GraphError::Container(e),
        }
    }
}

/// Edge families and sub-kinds.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum EdgeFamily {
    DataFlow,
    Call,
    Type,
    ControlFlow,
    Resource,
}

pub const DATAFLOW_KINDS: [&str; 9] = [
    "ASSIGN",
    "PARAMETER_PASS",
    "RETURN_VALUE",
    "FIELD_STORE",
    "FIELD_LOAD",
    "ARRAY_STORE",
    "ARRAY_LOAD",
    "CAST",
    "PHI",
];
pub const TYPE_KINDS: [&str; 2] = ["EXTENDS", "IMPLEMENTS"];
pub const CONTROL_FLOW_KINDS: [&str; 7] = [
    "SEQUENTIAL",
    "BRANCH_TRUE",
    "BRANCH_FALSE",
    "SWITCH_CASE",
    "SWITCH_DEFAULT",
    "EXCEPTION",
    "RETURN",
];
pub const RESOURCE_KINDS: [&str; 5] =
    ["OPENS", "LOADS", "BUNDLE_CANDIDATE", "LOOKUP", "ENUMERATES"];
pub const RESOURCE_REL_TYPES: [&str; 5] = [
    "RESOURCE_OPEN",
    "RESOURCE_LOAD",
    "RESOURCE_BUNDLE_CANDIDATE",
    "RESOURCE_LOOKUP",
    "RESOURCE_KEYS",
];

/// A decoded edge.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Edge {
    pub from: NodeId,
    pub to: NodeId,
    /// Raw 8-bit label.
    pub label: u8,
    /// Nodedata format version governing the label layout.
    pub v2: bool,
}

impl Edge {
    #[inline]
    pub fn family(&self) -> EdgeFamily {
        let f = if self.v2 {
            self.label & 0x3
        } else {
            self.label & 0x7
        };
        match f {
            0 => EdgeFamily::DataFlow,
            1 => EdgeFamily::Call,
            2 => EdgeFamily::Type,
            3 => EdgeFamily::ControlFlow,
            _ => EdgeFamily::Resource,
        }
    }

    /// Sub-kind ordinal (for non-Call families).
    #[inline]
    pub fn kind_ordinal(&self) -> usize {
        let shift = if self.v2 { 2 } else { 3 };
        ((self.label >> shift) & 0xF) as usize
    }

    #[inline]
    pub fn is_virtual(&self) -> bool {
        if self.v2 {
            (self.label >> 6) & 1 == 1
        } else {
            (self.label >> 3) & 1 == 1
        }
    }

    #[inline]
    pub fn is_dynamic(&self) -> bool {
        if self.v2 {
            (self.label >> 7) & 1 == 1
        } else {
            (self.label >> 4) & 1 == 1
        }
    }

    /// `kind.name` for DataFlow/Type/ControlFlow/Resource edges.
    pub fn kind_name(&self) -> Option<&'static str> {
        let o = self.kind_ordinal();
        match self.family() {
            EdgeFamily::DataFlow => DATAFLOW_KINDS.get(o).copied(),
            EdgeFamily::Type => TYPE_KINDS.get(o).copied(),
            EdgeFamily::ControlFlow => CONTROL_FLOW_KINDS.get(o).copied(),
            EdgeFamily::Resource => RESOURCE_KINDS.get(o).copied(),
            EdgeFamily::Call => None,
        }
    }

    /// Explorer REST `type` (`DataFlow`, `Call`, ...).
    pub fn rest_type(&self) -> &'static str {
        match self.family() {
            EdgeFamily::DataFlow => "DataFlow",
            EdgeFamily::Call => "Call",
            EdgeFamily::Type => "Type",
            EdgeFamily::ControlFlow => "ControlFlow",
            EdgeFamily::Resource => "Resource",
        }
    }

    /// Cypher relationship type (`DATAFLOW`, `CALL`, ...).
    pub fn rel_type(&self) -> &'static str {
        match self.family() {
            EdgeFamily::DataFlow => "DATAFLOW",
            EdgeFamily::Call => "CALL",
            EdgeFamily::Type => "TYPE",
            EdgeFamily::ControlFlow => "CONTROL_FLOW",
            EdgeFamily::Resource => RESOURCE_REL_TYPES
                .get(self.kind_ordinal())
                .copied()
                .unwrap_or("RESOURCE"),
        }
    }
}

/// Compressed sparse row adjacency with parallel label bytes.
pub struct Csr {
    pub offsets: Vec<u32>,
    pub targets: Vec<u32>,
    pub labels: Vec<u8>,
}

impl Csr {
    #[inline]
    pub fn neighbors(&self, node: usize) -> (&[u32], &[u8]) {
        if node + 1 >= self.offsets.len() {
            return (&[], &[]);
        }
        let s = self.offsets[node] as usize;
        let e = self.offsets[node + 1] as usize;
        (&self.targets[s..e], &self.labels[s..e])
    }

    #[inline]
    pub fn degree(&self, node: usize) -> usize {
        if node + 1 >= self.offsets.len() {
            0
        } else {
            (self.offsets[node + 1] - self.offsets[node]) as usize
        }
    }
}

/// One key-set partition: a representative node and its multiplicity.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct DeclaredKeyPartition {
    pub first: NodeId,
    pub count: usize,
}

pub struct Graph {
    pub dir: PathBuf,
    node_version: u8,
    strings: StringTable,
    nodedata: Bytes,
    node_count: usize,
    node_offsets: Bytes,
    /// number of entries in nodeoffsets (= maxNodeId + 1)
    node_capacity: usize,
    type_index: Vec<Vec<NodeId>>,
    pub forward: Csr,
    pub backward: Csr,
    comparisons: Comparisons,
    pub metadata: Metadata,
    pub class_overview: Option<ClassOverview>,
    pub resources: Option<Resources>,
    /// Optional, deduplicated declaration types; never changes erased identities.
    declared_types: Option<crate::types::DeclaredTypes>,
    // Field/Parameter/Return, each [unbound, bound]. Only ascending ID lists have
    // summaries: replacing an unsorted walk by first representatives changes order.
    declared_key_partitions: [Option<[DeclaredKeyPartition; 2]>; 3],
    /// Persisted CallSite string accelerator, absent when the graph was built without it.
    call_site_index: Option<crate::callsite_index::CallSiteStringIndex>,
    /// `CallSite.ordinal` per call site, from the `graph.callsite-ordinals` sidecar; empty
    /// for a graph written before it existed.
    call_site_ordinals: crate::node::CallSiteOrdinals,
    /// Whether each CallSite property name is itself a dictionary string, decided once:
    /// the planner asks it for every graph on every query, and the answer is a fact
    /// about the graph, not the query.
    property_names_in_dictionary: std::sync::OnceLock<[bool; 4]>,
    /// One lazily built column per entry of [`crate::columns::RAW_STRING_FIELDS`].
    string_columns: Vec<std::sync::OnceLock<crate::columns::StringColumn>>,
}

impl Graph {
    /// Load a graph from its directory or from a `.graphite` container file.
    pub fn load(path: &Path) -> Result<Graph, GraphError> {
        let dir = path.to_path_buf();
        let src = GraphSource::open(path)?;
        let io = |(path, e)| GraphError::Io(path, e);
        let shared_strings = crate::types::DeclaredTypes::needs_shared_strings(&src)?;
        // Parallel-ish: strings and bvgraph are the heavy ones.
        let (strings, bv) = rayon::join(
            || {
                if shared_strings {
                    StringTable::load_for_declared_types(&src)
                } else {
                    StringTable::load(&src)
                }
            },
            || BvGraph::load(&src, "forward"),
        );
        let strings = strings?;
        let bv = bv?;

        let (nodedata, summary_data) = src.require_buffered("graph.nodedata").map_err(io)?;
        if nodedata.len() < 8 {
            return Err(GraphError::BadHeader("graph.nodedata"));
        }
        let node_version = check_header(read_i32_at(&nodedata, 0), MAGIC_NODEDATA)
            .ok_or(GraphError::BadHeader("graph.nodedata"))?;
        if !(1..=3).contains(&node_version) {
            return Err(GraphError::BadVersion(node_version, "graph.nodedata"));
        }
        let node_count = read_i32_at(&nodedata, 4).max(0) as usize;

        let (node_offsets, summary_offsets) =
            src.require_buffered("graph.nodeoffsets").map_err(io)?;
        check_header(read_i32_at(&node_offsets, 0), MAGIC_NODEOFFSETS)
            .ok_or(GraphError::BadHeader("graph.nodeoffsets"))?;
        let node_capacity = read_i32_at(&node_offsets, 4).max(0) as usize;

        let type_index = load_type_index(&src.require("graph.typeindex").map_err(io)?)?;

        let labels = src.require("graph.labels").map_err(io)?;
        let forward = build_forward_csr(&bv, &labels);
        // The CSR owns its copied labels; release the source map before further loading.
        drop(labels);
        drop(bv);
        let backward = build_backward_csr(&forward);

        let comparisons = match src.bytes("graph.comparisons").map_err(io)? {
            Some(bytes) => Comparisons::parse(&bytes)?,
            None => Comparisons::empty(),
        };
        let metadata_bytes = src.require("graph.metadata").map_err(io)?;
        let metadata = Metadata::parse(&metadata_bytes)?;
        // Parsed metadata owns every value, including the optional ordinal binding.
        drop(metadata_bytes);
        let class_overview = match src.bytes("graph.classoverview").map_err(io)? {
            Some(bytes) => Some(ClassOverview::parse(&bytes)?),
            None => None,
        };
        let resources = match src.bytes("graph.resources").map_err(io)? {
            Some(bytes) => Some(Resources::parse(&bytes)?),
            None => None,
        };
        // The CallSite string accelerator the graph was built with. Its absence only
        // costs speed; a file that does not describe this graph is fatal, since
        // answering from a stale index would be wrong.
        let content_identity = src
            .bytes("graph.callsite-string-content.identity")
            .ok()
            .flatten()
            .and_then(|b| <[u8; 32]>::try_from(&b[..]).ok());
        let call_site_index = crate::callsite_index::CallSiteStringIndex::load_from(
            &src,
            strings.len(),
            content_identity.as_ref(),
        )
        .map_err(|e| GraphError::CallSiteIndex(e.to_string()))?;
        // The ordinal sidecar is optional, and one that is not the sidecar graph.metadata
        // binds (left behind by a writer that did not know it) is read as absent: the graph is
        // complete without it, as it is for a reader that predates it.
        let call_site_ordinals = src
            .bytes("graph.callsite-ordinals")
            .map_err(io)?
            .and_then(|bytes| {
                crate::node::CallSiteOrdinals::parse_bytes(
                    bytes,
                    metadata.call_site_ordinal_digest.as_ref(),
                )
            })
            .unwrap_or_default();

        let declared_types = crate::types::DeclaredTypes::load_with_strings(&src, &strings)?;
        let mut graph = Graph {
            dir,
            node_version,
            strings,
            nodedata,
            node_count,
            node_offsets,
            node_capacity,
            type_index,
            forward,
            backward,
            comparisons,
            metadata,
            class_overview,
            resources,
            declared_types,
            declared_key_partitions: [None; 3],
            call_site_index,
            call_site_ordinals,
            property_names_in_dictionary: std::sync::OnceLock::new(),
            string_columns: (0..crate::columns::RAW_STRING_FIELDS.len())
                .map(|_| std::sync::OnceLock::new())
                .collect(),
        };
        // New load work, completed before readiness: bind declaration-bearing
        // records once. The fixed-size summaries retain no per-node membership.
        graph.load_declared_key_partitions(summary_data, summary_offsets)?;
        if graph.call_site_index.is_none() && graph.count_by_tag(TAG_CALL_SITE_NODE) > 0 {
            // No persisted index: build the same structure in memory, as the Kotlin
            // server does for a graph written before the index existed.
            let mut ids: Vec<NodeId> = graph.ids_by_tag(TAG_CALL_SITE_NODE).to_vec();
            ids.sort_unstable();
            let data = &graph.nodedata;
            let mut call_sites = ids.iter().filter_map(|&id| {
                let off = graph.node_offset(id)?;
                let s = read_call_site_strings(data, off);
                Some((
                    id,
                    [s.caller_class, s.caller_name, s.callee_class, s.callee_name],
                ))
            });
            let strings = &graph.strings;
            let built = crate::callsite_index::CallSiteStringIndex::build(
                strings.len(),
                &|i| strings.get(i).to_string(),
                &mut call_sites,
            )
            .map_err(|e| GraphError::CallSiteIndex(e.to_string()))?;
            graph.call_site_index = Some(built);
        }
        Ok(graph)
    }

    /// Decoding inputs are immutable once loaded; derived indexes rely on them.
    pub fn strings(&self) -> &StringTable {
        &self.strings
    }

    pub fn node_version(&self) -> u8 {
        self.node_version
    }

    pub fn declared_types(&self) -> Option<&crate::types::DeclaredTypes> {
        self.declared_types.as_ref()
    }

    /// Mutations cannot leave derived key partitions stale, including unwinding
    /// from the updater: invalidate first and rebuild only after normal return.
    pub fn update_declared_types(
        &mut self,
        update: impl FnOnce(&mut Option<crate::types::MutableDeclaredTypes>),
    ) {
        self.declared_key_partitions = [None; 3];
        crate::types::DeclaredTypes::update_slot(&mut self.declared_types, update);
        self.rebuild_declared_key_partitions();
    }

    pub fn declared_key_partitions(&self, tag: u8) -> Option<&[DeclaredKeyPartition; 2]> {
        let index = usize::from(tag.checked_sub(crate::node::TAG_FIELD_NODE)?);
        self.declared_key_partitions.get(index)?.as_ref()
    }

    fn rebuild_declared_key_partitions(&mut self) {
        // In-memory mutation keeps its original mapped/owned inputs. File handles
        // and load buffers are deliberately not retained on Graph.
        let result = self.compute_declared_key_partitions(|id, tag| {
            Ok::<_, std::convert::Infallible>(self.declared_summary_node(id, tag))
        });
        self.declared_key_partitions = match result {
            Ok(partitions) => partitions,
            Err(never) => match never {},
        };
    }

    fn load_declared_key_partitions(
        &mut self,
        data: FileRange,
        offsets: FileRange,
    ) -> Result<(), GraphError> {
        // No buffers or reads are needed for a legacy graph or a table with no
        // member bindings. Required declared-table validation has already run.
        if self
            .declared_types
            .as_ref()
            .is_none_or(|table| table.field_count() == 0 && table.method_count() == 0)
        {
            return Ok(());
        }
        let mut reader = BufferedSummaryNodes {
            data: ReadWindow::new(data),
            offsets: ReadWindow::new(offsets),
            capacity: self.node_capacity,
        };
        self.declared_key_partitions = self.compute_declared_key_partitions(|id, tag| {
            reader
                .node(id, tag)
                .map_err(|(path, error)| GraphError::Io(path, error))
        })?;
        Ok(())
    }

    fn compute_declared_key_partitions<E>(
        &self,
        mut read_node: impl FnMut(NodeId, u8) -> Result<Option<Node>, E>,
    ) -> Result<[Option<[DeclaredKeyPartition; 2]>; 3], E> {
        use crate::node::{NodeKind, TAG_FIELD_NODE, TAG_PARAMETER_NODE, TAG_RETURN_NODE};
        let mut result = [None; 3];
        let Some(table) = self.declared_types.as_ref() else {
            return Ok(result);
        };
        for tag in [TAG_FIELD_NODE, TAG_PARAMETER_NODE, TAG_RETURN_NODE] {
            if (tag == TAG_FIELD_NODE && table.field_count() == 0)
                || (tag != TAG_FIELD_NODE && table.method_count() == 0)
            {
                continue;
            }
            let ids = self.ids_by_tag(tag);
            if ids.windows(2).any(|pair| pair[0] > pair[1]) {
                continue;
            }
            let mut partitions = [DeclaredKeyPartition::default(); 2];
            // One previous-method binding is sufficient for adjacent parameter
            // records. It is scratch for this load, never a retained method cache.
            let mut previous_method: Option<(MethodDesc, Option<usize>)> = None;
            let mut complete = true;
            for &id in ids {
                let Some(node) = read_node(id, tag)? else {
                    complete = false;
                    break;
                };
                // Summaries are optional accelerators. An undecodable binding
                // leaves the existing query path in charge of malformed input.
                // Do not allocate an unbounded temporary descriptor/key during
                // startup. Oversized identities use the unchanged query fallback.
                let mut key_bytes = 0usize;
                let mut valid_string = |id: StrId| {
                    if (id as usize) >= self.strings.len() {
                        return false;
                    }
                    key_bytes = key_bytes
                        .saturating_add(self.strings.get(id as usize).len().saturating_add(2));
                    key_bytes <= 1_000_000
                };
                let valid_binding = match &node.kind {
                    NodeKind::Field {
                        declaring_class,
                        name,
                        field_type,
                        ..
                    } => [*declaring_class, *name, *field_type]
                        .into_iter()
                        .all(&mut valid_string),
                    NodeKind::Parameter { method, .. } | NodeKind::Return { method, .. } => {
                        [method.declaring_class, method.name, method.return_type]
                            .into_iter()
                            .all(&mut valid_string)
                            && method
                                .parameter_types
                                .iter()
                                .copied()
                                .all(&mut valid_string)
                    }
                    _ => false,
                };
                if !valid_binding {
                    complete = false;
                    break;
                }
                let bound = match &node.kind {
                    NodeKind::Parameter { method, index, .. } => {
                        let count = match &previous_method {
                            Some((previous, count)) if previous == method => *count,
                            _ => {
                                let count = table
                                    .method(method, &self.strings)
                                    .map(|m| m.parameters.len());
                                previous_method = Some((method.clone(), count));
                                count
                            }
                        };
                        count.is_some_and(|count| usize::try_from(*index).is_ok_and(|i| i < count))
                    }
                    _ => table.node_type_id(&node, &self.strings).is_some(),
                };
                let partition = &mut partitions[usize::from(bound)];
                if partition.count == 0 {
                    partition.first = id;
                }
                partition.count += 1;
            }
            if complete {
                result[usize::from(tag - TAG_FIELD_NODE)] = Some(partitions);
            }
        }
        Ok(result)
    }

    fn declared_summary_node(&self, id: NodeId, tag: u8) -> Option<Node> {
        if id as usize >= self.node_capacity {
            return None;
        }
        let slot = (id as usize).checked_mul(8)?.checked_add(8)?;
        let offset = i64::from_be_bytes(
            self.node_offsets
                .get(slot..slot.checked_add(8)?)?
                .try_into()
                .ok()?,
        );
        let offset = usize::try_from(offset.checked_sub(1)?).ok()?;
        // Slice first: the cursor starts at zero, so malformed offsets cannot
        // overflow its arithmetic. Only these three flat layouts are decoded.
        decode_declared_summary_node(self.nodedata.get(offset..)?, id, tag)
    }

    /// The persisted CallSite string accelerator, when the graph directory carries one.
    #[inline]
    pub fn call_site_index(&self) -> Option<&crate::callsite_index::CallSiteStringIndex> {
        self.call_site_index.as_ref()
    }

    #[inline]
    pub fn str(&self, id: StrId) -> &str {
        self.strings.get(id as usize)
    }

    /// Number of node records.
    #[inline]
    pub fn node_count(&self) -> usize {
        self.node_count
    }

    /// Logical bytes of the mapped entry ranges retained by this graph: node records,
    /// node offsets, the persisted CallSite string index and the ordinal sidecar. A
    /// container entry counts only its range, not the whole shared map. This is neither
    /// unique physical mapping size nor resident memory. Adjacency, strings and the
    /// ordinal rank index are owned memory and are not counted here.
    pub fn mapped_bytes(&self) -> u64 {
        let index = self
            .call_site_index
            .as_ref()
            .map_or(0, |i| i.mapped_bytes());
        self.nodedata.len() as u64
            + self.node_offsets.len() as u64
            + index
            + self.call_site_ordinals.mapped_bytes()
    }

    /// maxNodeId + 1
    #[inline]
    pub fn node_capacity(&self) -> usize {
        self.node_capacity
    }

    #[inline]
    pub fn edge_count(&self) -> usize {
        self.forward.targets.len()
    }

    /// Byte offset of a node record in nodedata, if the node exists.
    #[inline]
    pub fn node_offset(&self, id: NodeId) -> Option<usize> {
        let i = id as usize;
        if i >= self.node_capacity {
            return None;
        }
        let stored = read_i64_at(&self.node_offsets, 8 + i * 8);
        if stored == 0 {
            None
        } else {
            Some((stored - 1) as usize)
        }
    }

    #[inline]
    pub fn has_node(&self, id: NodeId) -> bool {
        self.node_offset(id).is_some()
    }

    /// Node tag without decoding the record.
    #[inline]
    pub fn node_tag(&self, id: NodeId) -> Option<u8> {
        self.node_offset(id).map(|o| self.nodedata[o + 4])
    }

    /// The complete fixed IntConstant record, without constructing a Node.
    /// None also means another kind or a truncated record; callers retain their
    /// ordinary node-property fallback in those cases.
    pub fn int_constant_value(&self, id: NodeId) -> Option<i32> {
        read_int_constant_value(&self.nodedata, self.node_offset(id)?)
    }

    /// Selected scalar properties may avoid full Node allocation, but never accept
    /// a CallSite whose complete variable-length record is truncated.
    pub fn call_site_scalar_strings(&self, id: NodeId) -> Option<CallSiteStrings> {
        read_call_site_scalar_strings(&self.nodedata, self.node_offset(id)?)
    }

    pub fn node(&self, id: NodeId) -> Option<Node> {
        let off = self.node_offset(id)?;
        let mut node = Node::read(&self.nodedata, off, self.node_version).ok()?;
        if let crate::node::NodeKind::CallSite { ordinal, .. } = &mut node.kind {
            *ordinal = self.call_site_ordinals.get(id);
        }
        Some(node)
    }

    /// `CallSite.ordinal` of node `id`, from the sidecar; `None` for another node or a graph
    /// without the sidecar.
    #[inline]
    pub fn call_site_ordinal(&self, id: NodeId) -> Option<i32> {
        self.call_site_ordinals.get(id)
    }

    /// `CallSite.origin` of node `id`: the call site a derived one was resolved from, from
    /// the sidecar; `None` for any other node or a graph without the sidecar.
    #[inline]
    pub fn call_site_origin(&self, id: NodeId) -> Option<NodeId> {
        self.call_site_ordinals.origin(id)
    }

    /// The call sites derived from call site `origin`: the lambda bodies a call on a
    /// function value was resolved to.
    pub fn call_sites_derived_from(&self, origin: NodeId) -> impl Iterator<Item = NodeId> + '_ {
        self.call_site_ordinals.derived_from(origin)
    }

    /// Raw nodedata bytes (for zero-copy property probes).
    #[inline]
    pub fn nodedata(&self) -> &[u8] {
        &self.nodedata
    }

    #[inline]
    pub fn call_site_strings_at(&self, offset: usize) -> CallSiteStrings {
        read_call_site_strings(&self.nodedata, offset)
    }

    /// Whether the name of CallSite property `property` (in
    /// [`crate::node::CALL_SITE_PROPERTY_NAMES`] order) occurs in this graph's dictionary.
    ///
    /// An Annotation node can carry any dictionary string as a value-pair key, so this is
    /// what decides whether a CallSite property name could reach one. Four binary
    /// searches over a front-coded dictionary, done once per graph rather than per plan.
    pub fn property_name_in_dictionary(&self, property: usize) -> bool {
        self.property_names_in_dictionary.get_or_init(|| {
            std::array::from_fn(|i| {
                self.strings
                    .index_of(crate::node::CALL_SITE_PROPERTY_NAMES[i])
                    .is_some()
            })
        })[property]
    }

    /// The string column for slot `slot` of [`crate::columns::RAW_STRING_FIELDS`],
    /// built on first use from the tag's records.
    pub fn string_column(&self, slot: usize) -> &crate::columns::StringColumn {
        self.string_columns[slot].get_or_init(|| {
            let field = crate::columns::RAW_STRING_FIELDS[slot];
            crate::columns::StringColumn::build(
                &self.nodedata,
                self.ids_by_tag(field.tag),
                &|id| self.node_offset(id),
                field.offset,
            )
        })
    }

    pub fn call_site_strings(&self, id: NodeId) -> Option<CallSiteStrings> {
        let off = self.node_offset(id)?;
        if self.nodedata[off + 4] != TAG_CALL_SITE_NODE {
            return None;
        }
        Some(read_call_site_strings(&self.nodedata, off))
    }

    /// Node ids with the given tag, in nodedata write order.
    #[inline]
    pub fn ids_by_tag(&self, tag: u8) -> &[NodeId] {
        self.type_index
            .get(tag as usize)
            .map(|v| v.as_slice())
            .unwrap_or(&[])
    }

    pub fn count_by_tag(&self, tag: u8) -> usize {
        self.ids_by_tag(tag).len()
    }

    /// All node ids in typeindex order (tag 0..15).
    pub fn all_ids(&self) -> impl Iterator<Item = NodeId> + '_ {
        self.type_index.iter().flat_map(|v| v.iter().copied())
    }

    #[inline]
    pub fn outgoing(&self, id: NodeId) -> impl Iterator<Item = Edge> + '_ {
        let (t, l) = self.forward.neighbors(id as usize);
        let v2 = self.node_version < 3;
        t.iter().zip(l.iter()).map(move |(&to, &label)| Edge {
            from: id,
            to,
            label,
            v2,
        })
    }

    #[inline]
    pub fn incoming(&self, id: NodeId) -> impl Iterator<Item = Edge> + '_ {
        let (t, l) = self.backward.neighbors(id as usize);
        let v2 = self.node_version < 3;
        t.iter().zip(l.iter()).map(move |(&from, &label)| Edge {
            from,
            to: id,
            label,
            v2,
        })
    }

    #[inline]
    pub fn out_degree(&self, id: NodeId) -> usize {
        self.forward.degree(id as usize)
    }

    #[inline]
    pub fn in_degree(&self, id: NodeId) -> usize {
        self.backward.degree(id as usize)
    }

    pub fn comparison(&self, e: &Edge) -> Option<BranchComparison> {
        if e.family() != EdgeFamily::ControlFlow {
            return None;
        }
        self.comparisons.find(e.from, e.to)
    }

    pub fn methods(&self) -> &[MethodDesc] {
        &self.metadata.methods
    }

    pub fn method_count(&self) -> usize {
        self.metadata.methods.len()
    }

    pub fn supertypes(&self, class: &str) -> &[StrId] {
        self.strings
            .index_of(class)
            .and_then(|i| self.metadata.supertypes_index.get(&(i as u32)))
            .map(|&i| self.metadata.supertypes[i].1.as_slice())
            .unwrap_or(&[])
    }

    pub fn subtypes(&self, class: &str) -> &[StrId] {
        self.strings
            .index_of(class)
            .and_then(|i| self.metadata.subtypes_index.get(&(i as u32)))
            .map(|&i| self.metadata.subtypes[i].1.as_slice())
            .unwrap_or(&[])
    }

    /// `memberAnnotations(className, memberName)` → list of (fqn, [(attr, value)]).
    pub fn member_annotations(
        &self,
        class: &str,
        member: &str,
    ) -> &[(StrId, Vec<(StrId, crate::node::AnyValue)>)] {
        let key = format!("{class}#{member}");
        self.strings
            .index_of(&key)
            .and_then(|i| self.metadata.member_annotation_index.get(&(i as u32)))
            .map(|&i| self.metadata.member_annotations[i].1.as_slice())
            .unwrap_or(&[])
    }

    pub fn class_origin(&self, class: &str) -> Option<&str> {
        let i = self.strings.index_of(class)? as u32;
        self.metadata
            .class_origin_index
            .get(&i)
            .map(|&s| self.str(s))
    }

    pub fn enum_values(
        &self,
        enum_class: &str,
        enum_name: &str,
    ) -> Option<&[crate::node::AnyValue]> {
        let key = format!("{enum_class}#{enum_name}");
        let i = self.strings.index_of(&key)? as u32;
        self.metadata
            .enum_index
            .get(&i)
            .map(|&i| self.metadata.enum_values[i].1.as_slice())
    }
}

/// The largest supported flat binding record: Parameter with 256 type IDs.
/// This scratch and two fixed windows belong only to the synchronous load pass.
const SUMMARY_RECORD_BYTES: usize = 29 + 4 * 256;

struct BufferedSummaryNodes {
    data: ReadWindow<FileRange>,
    offsets: ReadWindow<FileRange>,
    capacity: usize,
}

impl BufferedSummaryNodes {
    fn node(&mut self, id: NodeId, tag: u8) -> Result<Option<Node>, crate::source::IoAt> {
        if id as usize >= self.capacity {
            return Ok(None);
        }
        let slot = u64::from(id) * 8 + 8;
        if slot
            .checked_add(8)
            .is_none_or(|end| end > self.offsets.source.len())
        {
            return Ok(None);
        }
        let mut offset = [0; 8];
        self.offsets
            .copy_prefix(slot, &mut offset)
            .map_err(|e| (self.offsets.source.name.clone(), e))?;
        let Some(offset) = i64::from_be_bytes(offset)
            .checked_sub(1)
            .and_then(|offset| u64::try_from(offset).ok())
            .filter(|&offset| offset <= self.data.source.len())
        else {
            return Ok(None);
        };
        let limit = match tag {
            crate::node::TAG_FIELD_NODE => 18,
            crate::node::TAG_PARAMETER_NODE => SUMMARY_RECORD_BYTES,
            crate::node::TAG_RETURN_NODE => 26 + 4 * 256,
            _ => return Ok(None),
        };
        let mut bytes = [0; SUMMARY_RECORD_BYTES];
        let len = self
            .data
            .copy_prefix(offset, &mut bytes[..limit])
            .map_err(|e| (self.data.source.name.clone(), e))?;
        Ok(decode_declared_summary_node(&bytes[..len], id, tag))
    }
}

/// Optional-summary decoder, deliberately bounded independently of file counts.
/// Declining an unsupported record keeps the normal query path authoritative.
fn decode_declared_summary_node(data: &[u8], id: NodeId, tag: u8) -> Option<Node> {
    use crate::node::{NodeKind, TAG_FIELD_NODE, TAG_PARAMETER_NODE, TAG_RETURN_NODE};
    fn method(c: &mut Cursor<'_>) -> Option<MethodDesc> {
        let declaring_class = c.u32().ok()?;
        let name = c.u32().ok()?;
        let count = usize::try_from(c.i32().ok()?).ok()?;
        // JVM descriptors have at most255 parameter slots. Higher counts need
        // no startup allocation; they simply cannot use this optional summary.
        if count > 256 || count > c.remaining().saturating_sub(4) / 4 {
            return None;
        }
        let parameter_types = (0..count)
            .map(|_| c.u32().ok())
            .collect::<Option<Vec<_>>>()?;
        let return_type = c.u32().ok()?;
        Some(MethodDesc {
            declaring_class,
            name,
            parameter_types,
            return_type,
        })
    }
    let mut c = Cursor::new(data);
    if c.u32().ok()? != id || c.u8().ok()? != tag {
        return None;
    }
    let kind = match tag {
        TAG_FIELD_NODE => NodeKind::Field {
            declaring_class: c.u32().ok()?,
            name: c.u32().ok()?,
            field_type: c.u32().ok()?,
            is_static: c.bool().ok()?,
        },
        TAG_PARAMETER_NODE => NodeKind::Parameter {
            index: c.i32().ok()?,
            param_type: c.u32().ok()?,
            method: method(&mut c)?,
        },
        TAG_RETURN_NODE => {
            let method = method(&mut c)?;
            let actual_type = if c.bool().ok()? {
                Some(c.u32().ok()?)
            } else {
                None
            };
            NodeKind::Return {
                method,
                actual_type,
            }
        }
        _ => return None,
    };
    Some(Node { id, kind })
}

fn load_type_index(data: &[u8]) -> Result<Vec<Vec<NodeId>>, GraphError> {
    if data.len() < 8 {
        return Err(GraphError::BadHeader("graph.typeindex"));
    }
    check_header(read_i32_at(data, 0), MAGIC_TYPEINDEX)
        .ok_or(GraphError::BadHeader("graph.typeindex"))?;
    let entries = read_i32_at(data, 4).max(0) as usize;
    let mut out: Vec<Vec<NodeId>> = vec![Vec::new(); TAG_COUNT];
    for i in 0..entries {
        let p = 8 + i * 13;
        if p + 13 > data.len() {
            return Err(GraphError::BadHeader("graph.typeindex"));
        }
        let tag = data[p] as usize;
        let count = read_i32_at(data, p + 1).max(0) as usize;
        let off = read_i64_at(data, p + 5).max(0) as usize;
        if tag >= TAG_COUNT {
            continue;
        }
        let end = off + count * 4;
        if end > data.len() {
            return Err(GraphError::BadHeader("graph.typeindex"));
        }
        let mut ids = Vec::with_capacity(count);
        for j in 0..count {
            ids.push(read_i32_at(data, off + j * 4) as u32);
        }
        out[tag] = ids;
    }
    Ok(out)
}

fn build_forward_csr(bv: &BvGraph, labels: &[u8]) -> Csr {
    let n = bv.num_nodes();
    let arcs = bv.num_arcs() as usize;
    let mut offsets = Vec::with_capacity(n + 1);
    let mut targets: Vec<u32> = Vec::with_capacity(arcs);
    let mut buf = Vec::new();
    offsets.push(0u32);
    for node in 0..n {
        bv.successors_into_with_prefix(node, &mut buf, &offsets, &targets);
        targets.extend_from_slice(&buf);
        offsets.push(targets.len() as u32);
    }
    let mut lab = Vec::with_capacity(targets.len());
    if labels.len() >= targets.len() {
        lab.extend_from_slice(&labels[..targets.len()]);
    } else {
        lab.extend_from_slice(labels);
        lab.resize(targets.len(), 0);
    }
    Csr {
        offsets,
        targets,
        labels: lab,
    }
}

fn build_backward_csr(fwd: &Csr) -> Csr {
    let n = fwd.offsets.len() - 1;
    let m = fwd.targets.len();
    let mut indeg = vec![0u32; n + 1];
    for &t in &fwd.targets {
        indeg[t as usize + 1] += 1;
    }
    for i in 0..n {
        indeg[i + 1] += indeg[i];
    }
    let offsets = indeg;
    let mut fill = offsets.clone();
    let mut targets = vec![0u32; m];
    let mut labels = vec![0u8; m];
    // Iterating sources ascending yields ascending predecessor lists.
    for from in 0..n {
        let s = fwd.offsets[from] as usize;
        let e = fwd.offsets[from + 1] as usize;
        for k in s..e {
            let to = fwd.targets[k] as usize;
            let p = fill[to] as usize;
            targets[p] = from as u32;
            labels[p] = fwd.labels[k];
            fill[to] += 1;
        }
    }
    Csr {
        offsets,
        targets,
        labels,
    }
}

#[allow(dead_code)]
const _: usize = NODE_HEADER_BYTES;

// Match Node::read's id/tag/value reads: do not trust the type index, add an id
// equality requirement, or use node_tag's unchecked indexing on a truncated record.
fn read_int_constant_value(data: &[u8], offset: usize) -> Option<i32> {
    let mut c = Cursor::at(data, offset);
    c.u32().ok()?;
    if c.u8().ok()? != TAG_INT_CONSTANT {
        return None;
    }
    c.i32().ok()
}

#[cfg(test)]
#[path = "int_constant_tests.rs"]
mod int_constant_tests;

#[cfg(test)]
mod declared_key_tests {
    use super::*;
    use crate::node::{NodeKind, TAG_FIELD_NODE, TAG_PARAMETER_NODE, TAG_RETURN_NODE};

    fn fixture() -> Option<Graph> {
        let path = std::env::var_os("GRAPHITE_TYPES_FIXTURE")?;
        Some(Graph::load(Path::new(&path)).unwrap())
    }

    fn buffered_records(
        root: &Path,
        data: &[u8],
        offsets: &[u8],
        capacity: usize,
    ) -> BufferedSummaryNodes {
        use std::fs::File;
        use std::sync::Arc;
        std::fs::create_dir_all(root).unwrap();
        let make = |name: &str, bytes: &[u8]| {
            let path = root.join(name);
            std::fs::write(&path, bytes).unwrap();
            FileRange::new(
                Arc::new(File::open(&path).unwrap()),
                0,
                bytes.len() as u64,
                path.display().to_string(),
            )
        };
        BufferedSummaryNodes {
            data: ReadWindow::new(make("graph.nodedata", data)),
            offsets: ReadWindow::new(make("graph.nodeoffsets", offsets)),
            capacity,
        }
    }

    #[test]
    fn buffered_summary_reads_largest_record_across_windows_and_declines_bad_offsets() {
        let root =
            std::env::temp_dir().join(format!("graphite-summary-window-{}", std::process::id()));
        let id = 8190u32; // This offset slot ends at the first window boundary.
        let mut record = id.to_be_bytes().to_vec();
        record.push(TAG_PARAMETER_NODE);
        for value in [0i32, 3, 1, 2, 256] {
            record.extend(value.to_be_bytes());
        }
        for _ in 0..256 {
            record.extend(3u32.to_be_bytes());
        }
        record.extend(4u32.to_be_bytes());
        assert_eq!(record.len(), SUMMARY_RECORD_BYTES);
        let start = 65536 - 7;
        let mut data = vec![0; start];
        data.extend(&record);
        let mut offsets = vec![0; 8 + (id as usize + 1) * 8];
        let slot = 8 + id as usize * 8;
        offsets[slot..slot + 8].copy_from_slice(&(start as i64 + 1).to_be_bytes());
        let expected = decode_declared_summary_node(&record, id, TAG_PARAMETER_NODE).unwrap();
        let mut reader = buffered_records(&root, &data, &offsets, id as usize + 1);
        assert_eq!(reader.node(id, TAG_PARAMETER_NODE).unwrap(), Some(expected));
        assert!(reader.node(id + 1, TAG_PARAMETER_NODE).unwrap().is_none());
        assert!(reader.node(0, TAG_FIELD_NODE).unwrap().is_none());
        drop(reader);
        for bad in [0, -1, i64::MIN, i64::MAX, data.len() as i64 + 1] {
            offsets[slot..slot + 8].copy_from_slice(&bad.to_be_bytes());
            let mut reader = buffered_records(&root, &data, &offsets, id as usize + 1);
            assert!(
                reader.node(id, TAG_PARAMETER_NODE).unwrap().is_none(),
                "offset {bad}"
            );
        }
        let mut reader = buffered_records(&root, &data, &offsets[..slot + 7], id as usize + 1);
        assert!(reader.node(id, TAG_PARAMETER_NODE).unwrap().is_none());
        drop(reader);
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn buffered_and_mapped_decoders_agree_for_every_flat_record_prefix() {
        use std::fs::File;
        use std::sync::Arc;
        let root =
            std::env::temp_dir().join(format!("graphite-summary-prefixes-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let id = 3u32;
        let mut cases = Vec::new();
        let mut field = id.to_be_bytes().to_vec();
        field.push(TAG_FIELD_NODE);
        for value in [1u32, 2, 3] {
            field.extend(value.to_be_bytes());
        }
        field.push(1);
        cases.push((TAG_FIELD_NODE, field, true));
        for count in [0i32, 2, 256, 257] {
            for (tag, actual) in [
                (TAG_PARAMETER_NODE, false),
                (TAG_RETURN_NODE, false),
                (TAG_RETURN_NODE, true),
            ] {
                let mut bytes = id.to_be_bytes().to_vec();
                bytes.push(tag);
                if tag == TAG_PARAMETER_NODE {
                    bytes.extend(0i32.to_be_bytes());
                    bytes.extend(3u32.to_be_bytes());
                }
                bytes.extend(1u32.to_be_bytes());
                bytes.extend(2u32.to_be_bytes());
                bytes.extend(count.to_be_bytes());
                for _ in 0..count {
                    bytes.extend(3u32.to_be_bytes());
                }
                bytes.extend(4u32.to_be_bytes());
                if tag == TAG_RETURN_NODE {
                    bytes.push(u8::from(actual));
                    if actual {
                        bytes.extend(5u32.to_be_bytes());
                    }
                }
                cases.push((tag, bytes, count <= 256));
            }
        }
        // Slots for both the correct and wrong ID point to this same record.
        let offsets_path = root.join("offsets");
        let mut offsets = vec![0; 8 + (id as usize + 2) * 8];
        for entry in [id, id + 1] {
            let slot = 8 + entry as usize * 8;
            offsets[slot..slot + 8].copy_from_slice(&1i64.to_be_bytes());
        }
        std::fs::write(&offsets_path, &offsets).unwrap();
        let offsets_file = Arc::new(File::open(&offsets_path).unwrap());
        for (case, (tag, bytes, valid)) in cases.into_iter().enumerate() {
            assert_eq!(
                decode_declared_summary_node(&bytes, id, tag).is_some(),
                valid
            );
            let data_path = root.join(format!("record-{case}"));
            let mut stored = vec![255; 19]; // Exercise entry-relative, nonzero starts too.
            stored.extend(&bytes);
            stored.extend([255; 11]); // Must not become part of a truncated record.
            std::fs::write(&data_path, stored).unwrap();
            let data_file = Arc::new(File::open(&data_path).unwrap());
            for end in 0..=bytes.len() {
                let mut reader = BufferedSummaryNodes {
                    data: ReadWindow::new(FileRange::new(
                        data_file.clone(),
                        19,
                        end as u64,
                        "record".into(),
                    )),
                    offsets: ReadWindow::new(FileRange::new(
                        offsets_file.clone(),
                        0,
                        offsets.len() as u64,
                        "offsets".into(),
                    )),
                    capacity: id as usize + 2,
                };
                for (requested_id, requested_tag) in [
                    (id, tag),
                    (id + 1, tag),
                    (
                        id,
                        if tag == TAG_FIELD_NODE {
                            TAG_RETURN_NODE
                        } else {
                            TAG_FIELD_NODE
                        },
                    ),
                    (id, crate::node::TAG_ANNOTATION_NODE),
                ] {
                    assert_eq!(
                        reader.node(requested_id, requested_tag).unwrap(),
                        decode_declared_summary_node(&bytes[..end], requested_id, requested_tag),
                        "case {case}, prefix {end}, id {requested_id}, tag {requested_tag}"
                    );
                }
            }
        }
        drop(offsets_file);
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn buffered_summary_reports_read_failure_with_its_source_name() {
        let root = std::env::temp_dir().join(format!(
            "graphite-summary-short-read-{}",
            std::process::id()
        ));
        let mut offsets = vec![0; 16];
        offsets[8..].copy_from_slice(&1i64.to_be_bytes());
        let mut reader = buffered_records(&root, &[0; 18], &offsets, 1);
        // No memory mapping in this fixture: shorten the already-open input so
        // the buffered reader observes a genuine read failure, not malformed bytes.
        std::fs::OpenOptions::new()
            .write(true)
            .open(root.join("graph.nodedata"))
            .unwrap()
            .set_len(0)
            .unwrap();
        let (path, error) = reader.node(0, TAG_FIELD_NODE).unwrap_err();
        assert!(path.ends_with("graph.nodedata"));
        assert_eq!(error.kind(), std::io::ErrorKind::UnexpectedEof);
        drop(reader);
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn load_summary_uses_temporary_readers_and_matches_mapped_mutation_path() {
        let Some(mut graph) = fixture() else {
            return;
        };
        let expected = graph.declared_key_partitions;
        let source = GraphSource::open(&graph.dir).unwrap();
        let (_, data) = source.require_buffered("graph.nodedata").unwrap();
        let (_, offsets) = source.require_buffered("graph.nodeoffsets").unwrap();
        let mapped_data = std::mem::replace(&mut graph.nodedata, Bytes::owned(Vec::new()));
        let mapped_offsets = std::mem::replace(&mut graph.node_offsets, Bytes::owned(Vec::new()));
        graph.declared_key_partitions = [None; 3];
        graph.load_declared_key_partitions(data, offsets).unwrap();
        assert_eq!(graph.declared_key_partitions, expected);
        graph.nodedata = mapped_data;
        graph.node_offsets = mapped_offsets;
        graph.update_declared_types(|_| {});
        assert_eq!(graph.declared_key_partitions, expected);
    }

    #[test]
    fn dual_java_wire_versions_directory_and_packed_summaries_match_all_bindings() {
        let fixtures = [
            std::env::var_os("GRAPHITE_TYPES_V1_FIXTURE"),
            std::env::var_os("GRAPHITE_TYPES_V2_FIXTURE")
                .or_else(|| std::env::var_os("GRAPHITE_TYPES_FIXTURE")),
        ];
        let [Some(v1), Some(v2)] = fixtures else {
            assert!(std::env::var_os("GRAPHITE_REQUIRE_DUAL_TYPES_FIXTURES").is_none(),
                "strict interoperability requires GRAPHITE_TYPES_V1_FIXTURE and GRAPHITE_TYPES_FIXTURE");
            eprintln!("dual_java_wire_versions_directory_and_packed_summaries_match_all_bindings: both Java fixture variables are required; skipped");
            return;
        };
        let root =
            std::env::temp_dir().join(format!("graphite-summary-dual-wire-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let mut graphs = Vec::new();
        for (version, path) in [(1u8, PathBuf::from(v1)), (2u8, PathBuf::from(v2))] {
            let mut header = [0; 4];
            std::io::Read::read_exact(
                &mut std::fs::File::open(path.join("graph.types")).unwrap(),
                &mut header,
            )
            .unwrap();
            assert_eq!(header, [0x47, 0x54, 0x59, version], "fixture wire version");
            let packed = root.join(format!("v{version}.graphite"));
            crate::container::pack(&path, &packed).unwrap();
            graphs.push(Graph::load(&path).unwrap());
            graphs.push(Graph::load(&packed).unwrap());
        }
        for phase in 0..3 {
            for graph in &mut graphs {
                match phase {
                    0 => assert!(graph.declared_types().is_some()),
                    1 => graph.update_declared_types(|table| {
                        let table = table.as_mut().unwrap();
                        let fields = table.fields.len();
                        let methods = table.methods.len();
                        table.fields.retain(|key, _| key.1.as_ref() != "first");
                        table.methods.retain(|key, _| key.1.as_ref() != "echo");
                        assert!(table.fields.len() < fields && table.methods.len() < methods);
                        assert!(!table.fields.is_empty() && !table.methods.is_empty());
                    }),
                    _ => graph.update_declared_types(|table| *table = None),
                }
                let expected = graph.declared_key_partitions;
                // Validate every representative/count against independent query
                // node decoding and binding, including partial external members.
                for tag in 0..TAG_COUNT as u8 {
                    let Some(partitions) = graph.declared_key_partitions(tag) else {
                        assert!(
                            ![TAG_FIELD_NODE, TAG_PARAMETER_NODE, TAG_RETURN_NODE].contains(&tag)
                                || phase == 2
                        );
                        continue;
                    };
                    let mut actual = [DeclaredKeyPartition::default(); 2];
                    for &id in graph.ids_by_tag(tag) {
                        let node = graph.node(id).unwrap();
                        let bound = graph
                            .declared_types()
                            .unwrap()
                            .node_type_id(&node, graph.strings())
                            .is_some();
                        let partition = &mut actual[usize::from(bound)];
                        if partition.count == 0 {
                            partition.first = id;
                        }
                        partition.count += 1;
                    }
                    assert_eq!(*partitions, actual, "phase {phase}, tag {tag}");
                }
                // All four forms also use exactly the same mutation supplier.
                graph.update_declared_types(|_| {});
                assert_eq!(graph.declared_key_partitions, expected);
                let source = GraphSource::open(&graph.dir).unwrap();
                let (_, data) = source.require_buffered("graph.nodedata").unwrap();
                let (_, offsets) = source.require_buffered("graph.nodeoffsets").unwrap();
                graph.declared_key_partitions = [None; 3];
                graph.load_declared_key_partitions(data, offsets).unwrap();
                assert_eq!(graph.declared_key_partitions, expected);
            }
            for graph in &graphs[1..] {
                assert_eq!(
                    graph.declared_key_partitions, graphs[0].declared_key_partitions,
                    "phase {phase}"
                );
            }
        }
        drop(graphs);
        std::fs::remove_dir_all(root).unwrap();
        eprintln!("dual Java GTY01/GTY02 directory+packed complete partitions, partial bindings and legacy mutation PASS");
    }

    #[test]
    fn summary_decoder_bounds_counts_and_never_decodes_other_tags() {
        let record = |count: i32, parameters: &[u32]| {
            let mut bytes = 7u32.to_be_bytes().to_vec();
            bytes.push(TAG_RETURN_NODE);
            for value in [1u32, 2u32] {
                bytes.extend(value.to_be_bytes());
            }
            bytes.extend(count.to_be_bytes());
            for value in parameters {
                bytes.extend(value.to_be_bytes());
            }
            bytes.extend(4u32.to_be_bytes());
            bytes.push(0);
            bytes
        };
        let valid = record(1, &[3]);
        assert_eq!(
            decode_declared_summary_node(&valid, 7, TAG_RETURN_NODE),
            Some(Node {
                id: 7,
                kind: NodeKind::Return {
                    method: MethodDesc {
                        declaring_class: 1,
                        name: 2,
                        parameter_types: vec![3],
                        return_type: 4,
                    },
                    actual_type: None
                },
            })
        );
        for count in [-1, 257, i32::MAX] {
            assert!(
                decode_declared_summary_node(&record(count, &[]), 7, TAG_RETURN_NODE).is_none()
            );
        }
        for end in 0..valid.len() {
            assert!(decode_declared_summary_node(&valid[..end], 7, TAG_RETURN_NODE).is_none());
        }
        assert!(decode_declared_summary_node(&valid, 8, TAG_RETURN_NODE).is_none());
        let mut wrong_tag = valid;
        wrong_tag[4] = crate::node::TAG_ANNOTATION_NODE;
        assert!(decode_declared_summary_node(&wrong_tag, 7, TAG_RETURN_NODE).is_none());
        assert!(
            decode_declared_summary_node(&wrong_tag, 7, crate::node::TAG_ANNOTATION_NODE).is_none()
        );
    }

    #[test]
    fn declaration_summary_declines_invalid_offsets_without_changing_query_decoder() {
        let Some(mut graph) = fixture() else {
            return;
        };
        let id = graph.ids_by_tag(TAG_FIELD_NODE)[0];
        let slot = 8 + id as usize * 8;
        let original = graph.node_offsets.to_vec();
        for offset in [0, -1, i64::MIN, i64::MAX] {
            let mut offsets = original.clone();
            offsets[slot..slot + 8].copy_from_slice(&offset.to_be_bytes());
            graph.node_offsets = Bytes::owned(offsets);
            graph.rebuild_declared_key_partitions();
            assert!(graph.declared_key_partitions(TAG_FIELD_NODE).is_none());
        }
        graph.node_offsets = Bytes::owned(original[..slot + 7].to_vec());
        graph.rebuild_declared_key_partitions();
        assert!(graph.declared_key_partitions(TAG_FIELD_NODE).is_none());
    }

    #[test]
    fn declaration_key_summary_tracks_exact_binding_updates_and_first_nodes() {
        let Some(mut graph) = fixture() else {
            return;
        };
        let field = graph.ids_by_tag(TAG_FIELD_NODE).iter().copied().find(|id| {
            matches!(graph.node(*id).unwrap().kind, NodeKind::Field { name, .. } if graph.str(name) == "first")
        }).unwrap();
        let before = *graph.declared_key_partitions(TAG_FIELD_NODE).unwrap();
        assert!(before[1].count > 1);
        let parameters = *graph.declared_key_partitions(TAG_PARAMETER_NODE).unwrap();
        let returns = *graph.declared_key_partitions(TAG_RETURN_NODE).unwrap();
        graph.update_declared_types(|table| {
            let table = table.as_mut().unwrap();
            table
                .fields
                .retain(|(_, name, _), _| name.as_ref() != "first");
            table
                .methods
                .retain(|(_, name, _), _| name.as_ref() != "echo");
        });
        let after = graph.declared_key_partitions(TAG_FIELD_NODE).unwrap();
        assert_eq!(after[1].count, before[1].count - 1);
        assert_eq!(after[0].count, before[0].count + 1);
        assert_eq!(
            after[0].first,
            if before[0].count == 0 {
                field
            } else {
                before[0].first.min(field)
            }
        );
        assert_eq!(
            graph.declared_key_partitions(TAG_PARAMETER_NODE).unwrap()[1].count,
            parameters[1].count - 1
        );
        assert_eq!(
            graph.declared_key_partitions(TAG_RETURN_NODE).unwrap()[1].count,
            returns[1].count - 1
        );
        for tag in [TAG_FIELD_NODE, TAG_PARAMETER_NODE, TAG_RETURN_NODE] {
            let summary = graph.declared_key_partitions(tag).unwrap();
            assert_eq!(
                summary.iter().map(|p| p.count).sum::<usize>(),
                graph.count_by_tag(tag)
            );
            for (bound, partition) in summary.iter().enumerate().filter(|(_, p)| p.count > 0) {
                let node = graph.node(partition.first).unwrap();
                assert_eq!(
                    graph
                        .declared_types()
                        .unwrap()
                        .node_type_id(&node, &graph.strings)
                        .is_some(),
                    bound == 1
                );
            }
        }
        graph.update_declared_types(|table| *table = None);
        assert!(graph.declared_types().is_none());
        assert!(graph.declared_key_partitions(TAG_FIELD_NODE).is_none());
    }

    #[test]
    fn declaration_key_summary_declines_unsorted_lists_and_unwound_updates() {
        let Some(mut graph) = fixture() else {
            return;
        };
        assert!(graph.type_index[TAG_FIELD_NODE as usize].len() > 1);
        graph.type_index[TAG_FIELD_NODE as usize].reverse();
        graph.rebuild_declared_key_partitions();
        assert!(graph.declared_key_partitions(TAG_FIELD_NODE).is_none());
        assert!(graph.declared_key_partitions(TAG_PARAMETER_NODE).is_some());
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            graph.update_declared_types(|table| {
                table.as_mut().unwrap().fields.clear();
                panic!("updater did not complete");
            });
        }));
        assert!(result.is_err());
        assert!(graph.declared_types().unwrap().field_count() == 0);
        for tag in [TAG_FIELD_NODE, TAG_PARAMETER_NODE, TAG_RETURN_NODE] {
            assert!(graph.declared_key_partitions(tag).is_none());
        }
    }
}

#[cfg(test)]
#[path = "csr_prefix_tests.rs"]
mod csr_prefix_tests;

#[cfg(test)]
#[path = "call_site_scalar_tests.rs"]
mod call_site_scalar_tests;

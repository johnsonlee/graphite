//! Assembled persisted graph: nodes, edges, metadata, strings.

use crate::bvgraph::{BvError, BvGraph};
use crate::io::{
    check_header, read_i32_at, read_i64_at, MAGIC_NODEDATA, MAGIC_NODEOFFSETS, MAGIC_TYPEINDEX,
};
use crate::metadata::{
    BranchComparison, ClassOverview, Comparisons, Metadata, MetadataError, Resources,
};
use crate::node::{
    read_call_site_strings, CallSiteStrings, MethodDesc, Node, NodeDecodeError, NodeId, StrId,
    NODE_HEADER_BYTES, TAG_CALL_SITE_NODE, TAG_COUNT,
};
use crate::strings::{StringTable, StringTableError};
use memmap2::Mmap;
use std::fs::File;
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
    #[error("bad header in {0}")]
    BadHeader(&'static str),
    #[error("unsupported format version {0} in {1}")]
    BadVersion(u8, &'static str),
    #[error(transparent)]
    Node(#[from] NodeDecodeError),
    #[error("CallSite string index: {0}")]
    CallSiteIndex(String),
}

fn mmap(path: &Path) -> Result<Mmap, GraphError> {
    let f = File::open(path).map_err(|e| GraphError::Io(path.display().to_string(), e))?;
    // SAFETY: read-only mapping of a file we do not modify.
    unsafe { Mmap::map(&f) }.map_err(|e| GraphError::Io(path.display().to_string(), e))
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

pub struct Graph {
    pub dir: PathBuf,
    pub node_version: u8,
    pub strings: StringTable,
    nodedata: Mmap,
    node_count: usize,
    node_offsets: Mmap,
    /// number of entries in nodeoffsets (= maxNodeId + 1)
    node_capacity: usize,
    type_index: Vec<Vec<NodeId>>,
    pub forward: Csr,
    pub backward: Csr,
    comparisons: Comparisons,
    pub metadata: Metadata,
    pub class_overview: Option<ClassOverview>,
    pub resources: Option<Resources>,
    /// Persisted CallSite string accelerator, absent when the graph was built without it.
    call_site_index: Option<crate::callsite_index::CallSiteStringIndex>,
    /// Whether each CallSite property name is itself a dictionary string, decided once:
    /// the planner asks it for every graph on every query, and the answer is a fact
    /// about the graph, not the query.
    property_names_in_dictionary: std::sync::OnceLock<[bool; 4]>,
}

impl Graph {
    pub fn load(dir: &Path) -> Result<Graph, GraphError> {
        let dir = dir.to_path_buf();
        // Parallel-ish: strings and bvgraph are the heavy ones.
        let (strings, bv) = rayon::join(
            || StringTable::load(&dir),
            || BvGraph::load(&dir.join("forward")),
        );
        let strings = strings?;
        let bv = bv?;

        let nodedata = mmap(&dir.join("graph.nodedata"))?;
        if nodedata.len() < 8 {
            return Err(GraphError::BadHeader("graph.nodedata"));
        }
        let node_version = check_header(read_i32_at(&nodedata, 0), MAGIC_NODEDATA)
            .ok_or(GraphError::BadHeader("graph.nodedata"))?;
        if !(1..=3).contains(&node_version) {
            return Err(GraphError::BadVersion(node_version, "graph.nodedata"));
        }
        let node_count = read_i32_at(&nodedata, 4).max(0) as usize;

        let node_offsets = mmap(&dir.join("graph.nodeoffsets"))?;
        check_header(read_i32_at(&node_offsets, 0), MAGIC_NODEOFFSETS)
            .ok_or(GraphError::BadHeader("graph.nodeoffsets"))?;
        let node_capacity = read_i32_at(&node_offsets, 4).max(0) as usize;

        let type_index = load_type_index(&mmap(&dir.join("graph.typeindex"))?)?;

        let labels = mmap(&dir.join("graph.labels"))?;
        let forward = build_forward_csr(&bv, &labels);
        drop(bv);
        let backward = build_backward_csr(&forward);

        let comparisons = match std::fs::read(dir.join("graph.comparisons")) {
            Ok(bytes) => Comparisons::parse(&bytes)?,
            Err(_) => Comparisons::empty(),
        };
        let metadata_bytes = std::fs::read(dir.join("graph.metadata"))
            .map_err(|e| GraphError::Io(dir.join("graph.metadata").display().to_string(), e))?;
        let metadata = Metadata::parse(&metadata_bytes)?;
        let class_overview = match std::fs::read(dir.join("graph.classoverview")) {
            Ok(bytes) => Some(ClassOverview::parse(&bytes)?),
            Err(_) => None,
        };
        let resources = match std::fs::read(dir.join("graph.resources")) {
            Ok(bytes) => Some(Resources::parse(&bytes)?),
            Err(_) => None,
        };
        // The CallSite string accelerator the graph was built with. Its absence only
        // costs speed; a file that does not describe this graph is fatal, since
        // answering from a stale index would be wrong.
        let content_identity = std::fs::read(dir.join("graph.callsite-string-content.identity"))
            .ok()
            .and_then(|b| <[u8; 32]>::try_from(b.as_slice()).ok());
        let call_site_index = crate::callsite_index::CallSiteStringIndex::load(
            &dir,
            strings.len(),
            content_identity.as_ref(),
        )
        .map_err(|e| GraphError::CallSiteIndex(e.to_string()))?;

        Ok(Graph {
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
            call_site_index,
            property_names_in_dictionary: std::sync::OnceLock::new(),
        })
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

    pub fn node(&self, id: NodeId) -> Option<Node> {
        let off = self.node_offset(id)?;
        Node::read(&self.nodedata, off, self.node_version).ok()
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
        bv.successors_into(node, &mut buf);
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

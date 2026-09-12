//! `graph.metadata`, `graph.comparisons`, `graph.classoverview`, `graph.resources`.

use crate::io::{
    check_header, Cursor, Truncated, MAGIC_CLASS_OVERVIEW, MAGIC_COMPARISONS, MAGIC_METADATA,
    MAGIC_RESOURCES,
};
use crate::node::{AnyValue, MethodDesc, NodeId, StrId};
use std::collections::HashMap;

#[derive(Debug, thiserror::Error)]
pub enum MetadataError {
    #[error(transparent)]
    Truncated(#[from] Truncated),
    #[error("bad header in {0}")]
    BadHeader(&'static str),
    #[error("unsupported format version {0} in {1}")]
    BadVersion(u8, &'static str),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ComparisonOp {
    Eq,
    Ne,
    Lt,
    Ge,
    Gt,
    Le,
}

impl ComparisonOp {
    pub fn from_ordinal(o: i32) -> ComparisonOp {
        match o {
            0 => ComparisonOp::Eq,
            1 => ComparisonOp::Ne,
            2 => ComparisonOp::Lt,
            3 => ComparisonOp::Ge,
            4 => ComparisonOp::Gt,
            _ => ComparisonOp::Le,
        }
    }
    pub fn name(self) -> &'static str {
        match self {
            ComparisonOp::Eq => "EQ",
            ComparisonOp::Ne => "NE",
            ComparisonOp::Lt => "LT",
            ComparisonOp::Ge => "GE",
            ComparisonOp::Gt => "GT",
            ComparisonOp::Le => "LE",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BranchComparison {
    pub op: ComparisonOp,
    pub comparand: NodeId,
}

#[derive(Debug, Clone)]
pub struct BranchScope {
    pub condition_node: NodeId,
    pub method: MethodDesc,
    pub comparison: BranchComparison,
    pub true_branch: Vec<NodeId>,
    pub false_branch: Vec<NodeId>,
}

#[derive(Debug, Default)]
pub struct Metadata {
    pub version: u8,
    /// Methods in file order (the Kotlin map preserves this order).
    pub methods: Vec<MethodDesc>,
    pub supertypes: Vec<(StrId, Vec<StrId>)>,
    pub subtypes: Vec<(StrId, Vec<StrId>)>,
    pub enum_values: Vec<(StrId, Vec<AnyValue>)>,
    pub class_origins: Vec<(StrId, StrId)>,
    pub artifact_dependencies: Vec<(StrId, Vec<(StrId, i32)>)>,
    /// key "$class#$member" -> [(annotation fqn, [(attr, value)])]
    pub member_annotations: Vec<(StrId, Vec<(StrId, Vec<(StrId, AnyValue)>)>)>,
    pub branch_scopes: Vec<BranchScope>,
    // Lookup maps
    pub supertypes_index: HashMap<StrId, usize>,
    pub subtypes_index: HashMap<StrId, usize>,
    pub enum_index: HashMap<StrId, usize>,
    pub class_origin_index: HashMap<StrId, StrId>,
    pub member_annotation_index: HashMap<StrId, usize>,
}

impl Metadata {
    pub fn parse(data: &[u8]) -> Result<Metadata, MetadataError> {
        let mut c = Cursor::new(data);
        let version = check_header(c.i32()?, MAGIC_METADATA)
            .ok_or(MetadataError::BadHeader("graph.metadata"))?;
        if !(1..=3).contains(&version) {
            return Err(MetadataError::BadVersion(version, "graph.metadata"));
        }
        let mut m = Metadata {
            version,
            ..Default::default()
        };
        let n = c.i32()?.max(0) as usize;
        m.methods.reserve(n);
        for _ in 0..n {
            m.methods.push(MethodDesc::read(&mut c)?);
        }
        let read_type_map = |c: &mut Cursor| -> Result<Vec<(StrId, Vec<StrId>)>, Truncated> {
            let n = c.i32()?.max(0) as usize;
            let mut out = Vec::with_capacity(n);
            for _ in 0..n {
                let t = c.u32()?;
                let k = c.i32()?.max(0) as usize;
                let mut v = Vec::with_capacity(k);
                for _ in 0..k {
                    v.push(c.u32()?);
                }
                out.push((t, v));
            }
            Ok(out)
        };
        m.supertypes = read_type_map(&mut c)?;
        m.subtypes = read_type_map(&mut c)?;
        let n = c.i32()?.max(0) as usize;
        for _ in 0..n {
            let key = c.u32()?;
            let k = c.i32()?.max(0) as usize;
            let mut v = Vec::with_capacity(k);
            for _ in 0..k {
                v.push(AnyValue::read(&mut c, version)?);
            }
            m.enum_values.push((key, v));
        }
        if version >= 3 {
            let n = c.i32()?.max(0) as usize;
            for _ in 0..n {
                m.class_origins.push((c.u32()?, c.u32()?));
            }
            let n = c.i32()?.max(0) as usize;
            for _ in 0..n {
                let from = c.u32()?;
                let k = c.i32()?.max(0) as usize;
                let mut deps = Vec::with_capacity(k);
                for _ in 0..k {
                    deps.push((c.u32()?, c.i32()?));
                }
                m.artifact_dependencies.push((from, deps));
            }
        }
        let n = c.i32()?.max(0) as usize;
        for _ in 0..n {
            let key = c.u32()?;
            let fqn_count = c.i32()?.max(0) as usize;
            let mut anns = Vec::with_capacity(fqn_count);
            for _ in 0..fqn_count {
                let fqn = c.u32()?;
                let kv_count = c.i32()?.max(0) as usize;
                let mut kv = Vec::with_capacity(kv_count);
                for _ in 0..kv_count {
                    let k = c.u32()?;
                    let v = AnyValue::read_annotation(&mut c, version)?;
                    kv.push((k, v));
                }
                anns.push((fqn, kv));
            }
            m.member_annotations.push((key, anns));
        }
        let n = c.i32()?.max(0) as usize;
        for _ in 0..n {
            let condition_node = c.u32()?;
            let method = MethodDesc::read(&mut c)?;
            let op = ComparisonOp::from_ordinal(c.i32()?);
            let comparand = c.u32()?;
            let tc = c.i32()?.max(0) as usize;
            let mut true_branch = Vec::with_capacity(tc);
            for _ in 0..tc {
                true_branch.push(c.u32()?);
            }
            let fc = c.i32()?.max(0) as usize;
            let mut false_branch = Vec::with_capacity(fc);
            for _ in 0..fc {
                false_branch.push(c.u32()?);
            }
            m.branch_scopes.push(BranchScope {
                condition_node,
                method,
                comparison: BranchComparison { op, comparand },
                true_branch,
                false_branch,
            });
        }
        for (i, (k, _)) in m.supertypes.iter().enumerate() {
            m.supertypes_index.insert(*k, i);
        }
        for (i, (k, _)) in m.subtypes.iter().enumerate() {
            m.subtypes_index.insert(*k, i);
        }
        for (i, (k, _)) in m.enum_values.iter().enumerate() {
            m.enum_index.insert(*k, i);
        }
        for (k, v) in &m.class_origins {
            m.class_origin_index.insert(*k, *v);
        }
        for (i, (k, _)) in m.member_annotations.iter().enumerate() {
            m.member_annotation_index.insert(*k, i);
        }
        Ok(m)
    }
}

/// `graph.comparisons`: sorted (key, comparison) where key = from<<32 | to.
pub struct Comparisons {
    keys: Vec<i64>,
    values: Vec<BranchComparison>,
}

impl Comparisons {
    pub fn parse(data: &[u8]) -> Result<Comparisons, MetadataError> {
        let mut c = Cursor::new(data);
        check_header(c.i32()?, MAGIC_COMPARISONS)
            .ok_or(MetadataError::BadHeader("graph.comparisons"))?;
        let n = c.i32()?.max(0) as usize;
        let mut keys = Vec::with_capacity(n);
        let mut values = Vec::with_capacity(n);
        for _ in 0..n {
            keys.push(c.i64()?);
            let op = ComparisonOp::from_ordinal(c.i32()?);
            let comparand = c.u32()?;
            values.push(BranchComparison { op, comparand });
        }
        // Keys are written ascending, but be robust.
        let mut idx: Vec<usize> = (0..n).collect();
        if !keys.windows(2).all(|w| w[0] <= w[1]) {
            idx.sort_by_key(|&i| keys[i]);
            let keys2 = idx.iter().map(|&i| keys[i]).collect();
            let values2 = idx.iter().map(|&i| values[i]).collect();
            return Ok(Comparisons {
                keys: keys2,
                values: values2,
            });
        }
        Ok(Comparisons { keys, values })
    }

    pub fn empty() -> Comparisons {
        Comparisons {
            keys: vec![],
            values: vec![],
        }
    }

    #[inline]
    pub fn find(&self, from: NodeId, to: NodeId) -> Option<BranchComparison> {
        if self.keys.is_empty() {
            return None;
        }
        let key = ((from as i64) << 32) | (to as i64 & 0xFFFF_FFFF);
        self.keys.binary_search(&key).ok().map(|i| self.values[i])
    }

    pub fn len(&self) -> usize {
        self.keys.len()
    }

    pub fn is_empty(&self) -> bool {
        self.keys.is_empty()
    }
}

#[derive(Debug, Clone)]
pub struct ClassOverview {
    pub call_site_count: i32,
    /// (className, count) sorted by count desc then name asc.
    pub classes: Vec<(StrId, i32)>,
    /// (caller, callee, count) sorted by caller asc then callee asc.
    pub edges: Vec<(StrId, StrId, i32)>,
}

impl ClassOverview {
    pub fn parse(data: &[u8]) -> Result<ClassOverview, MetadataError> {
        let mut c = Cursor::new(data);
        check_header(c.i32()?, MAGIC_CLASS_OVERVIEW)
            .ok_or(MetadataError::BadHeader("graph.classoverview"))?;
        let call_site_count = c.i32()?;
        let n = c.i32()?.max(0) as usize;
        let mut classes = Vec::with_capacity(n);
        for _ in 0..n {
            classes.push((c.u32()?, c.i32()?));
        }
        let n = c.i32()?.max(0) as usize;
        let mut edges = Vec::with_capacity(n);
        for _ in 0..n {
            edges.push((c.u32()?, c.u32()?, c.i32()?));
        }
        Ok(ClassOverview {
            call_site_count,
            classes,
            edges,
        })
    }
}

#[derive(Debug, Clone)]
pub struct Resource {
    pub path: String,
    pub source: String,
    pub content: Vec<u8>,
}

#[derive(Debug, Default)]
pub struct Resources {
    pub entries: Vec<Resource>,
    index: HashMap<String, usize>,
}

impl Resources {
    pub fn parse(data: &[u8]) -> Result<Resources, MetadataError> {
        let mut c = Cursor::new(data);
        let version = check_header(c.i32()?, MAGIC_RESOURCES)
            .ok_or(MetadataError::BadHeader("graph.resources"))?;
        if version != 1 {
            return Err(MetadataError::BadVersion(version, "graph.resources"));
        }
        let n = c.i32()?.max(0) as usize;
        let mut r = Resources::default();
        for _ in 0..n {
            let pl = c.i32()?.max(0) as usize;
            let path = String::from_utf8_lossy(c.bytes(pl)?).into_owned();
            let sl = c.i32()?.max(0) as usize;
            let source = String::from_utf8_lossy(c.bytes(sl)?).into_owned();
            let cl = c.i32()?.max(0) as usize;
            let content = c.bytes(cl)?.to_vec();
            if !r.index.contains_key(&path) {
                r.index.insert(path.clone(), r.entries.len());
                r.entries.push(Resource {
                    path,
                    source,
                    content,
                });
            }
        }
        Ok(r)
    }

    pub fn get(&self, path: &str) -> Option<&Resource> {
        self.index.get(path).map(|&i| &self.entries[i])
    }
}

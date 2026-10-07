//! Node model and `graph.nodedata` record decoding.

use crate::io::{Cursor, Truncated};
use crate::strings::StringTable;
use sha2::{Digest, Sha256};

/// Index into the graph string table.
pub type StrId = u32;
pub type NodeId = u32;

pub const TAG_INT_CONSTANT: u8 = 0;
pub const TAG_STRING_CONSTANT: u8 = 1;
pub const TAG_LONG_CONSTANT: u8 = 2;
pub const TAG_FLOAT_CONSTANT: u8 = 3;
pub const TAG_DOUBLE_CONSTANT: u8 = 4;
pub const TAG_BOOLEAN_CONSTANT: u8 = 5;
pub const TAG_NULL_CONSTANT: u8 = 6;
pub const TAG_ENUM_CONSTANT: u8 = 7;
pub const TAG_LOCAL_VARIABLE: u8 = 8;
pub const TAG_FIELD_NODE: u8 = 9;
pub const TAG_PARAMETER_NODE: u8 = 10;
pub const TAG_RETURN_NODE: u8 = 11;
pub const TAG_CALL_SITE_NODE: u8 = 12;
pub const TAG_ANNOTATION_NODE: u8 = 13;
pub const TAG_RESOURCE_VALUE_NODE: u8 = 14;
pub const TAG_RESOURCE_FILE_NODE: u8 = 15;

pub const TAG_COUNT: usize = 16;

/// Node record header: int32 id + tag byte.
pub const NODE_HEADER_BYTES: usize = 5;

#[derive(Debug, Clone, PartialEq)]
pub struct MethodDesc {
    pub declaring_class: StrId,
    pub name: StrId,
    pub parameter_types: Vec<StrId>,
    pub return_type: StrId,
}

impl MethodDesc {
    pub fn read(c: &mut Cursor) -> Result<Self, Truncated> {
        let declaring_class = c.u32()?;
        let name = c.u32()?;
        let n = c.i32()?.max(0) as usize;
        let mut parameter_types = Vec::with_capacity(n);
        for _ in 0..n {
            parameter_types.push(c.u32()?);
        }
        let return_type = c.u32()?;
        Ok(MethodDesc {
            declaring_class,
            name,
            parameter_types,
            return_type,
        })
    }

    /// `"$class.$name($p1,$p2)"` — the Kotlin `MethodDescriptor.signature`.
    pub fn signature(&self, s: &StringTable) -> String {
        let mut out = String::with_capacity(64);
        self.signature_into(s, &mut out);
        out
    }

    /// The JVM method descriptor, `(Ljava/lang/String;)Z` — the Kotlin
    /// `MethodDescriptor.descriptor`, built from the same type names. The signature leaves
    /// the return type out; the descriptor keeps it.
    pub fn descriptor(&self, s: &StringTable) -> String {
        let mut out = String::with_capacity(32);
        out.push('(');
        for p in &self.parameter_types {
            push_type_descriptor(s.get(*p as usize), &mut out);
        }
        out.push(')');
        push_type_descriptor(s.get(self.return_type as usize), &mut out);
        out
    }

    pub fn signature_into(&self, s: &StringTable, out: &mut String) {
        out.push_str(s.get(self.declaring_class as usize));
        out.push('.');
        out.push_str(s.get(self.name as usize));
        out.push('(');
        for (i, p) in self.parameter_types.iter().enumerate() {
            if i > 0 {
                out.push(',');
            }
            out.push_str(s.get(*p as usize));
        }
        out.push(')');
    }
}

/// One JVM field descriptor per type name: `int` is `I`, `java.lang.String[]` is
/// `[Ljava/lang/String;` — the Kotlin `jvmTypeDescriptor`.
pub fn push_type_descriptor(type_name: &str, out: &mut String) {
    let mut base = type_name;
    while let Some(element) = base.strip_suffix("[]") {
        out.push('[');
        base = element;
    }
    let primitive = match base {
        "boolean" => Some('Z'),
        "byte" => Some('B'),
        "char" => Some('C'),
        "short" => Some('S'),
        "int" => Some('I'),
        "long" => Some('J'),
        "float" => Some('F'),
        "double" => Some('D'),
        "void" => Some('V'),
        _ => None,
    };
    match primitive {
        Some(c) => out.push(c),
        None => {
            out.push('L');
            out.extend(base.chars().map(|c| if c == '.' { '/' } else { c }));
            out.push(';');
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum AnyValue {
    Int(i32),
    Long(i64),
    Str(StrId),
    Float(f32),
    Double(f64),
    Bool(bool),
    Null,
    EnumRef { enum_class: StrId, enum_name: StrId },
    List(Vec<AnyValue>),
}

impl AnyValue {
    /// `version` is threaded through for the nested values; it only matters to the
    /// annotation reader.
    #[allow(clippy::only_used_in_recursion)]
    pub fn read(c: &mut Cursor, version: u8) -> Result<Self, Truncated> {
        Ok(match c.u8()? {
            0 => AnyValue::Int(c.i32()?),
            1 => AnyValue::Long(c.i64()?),
            2 => AnyValue::Str(c.u32()?),
            3 => AnyValue::Float(c.f32()?),
            4 => AnyValue::Double(c.f64()?),
            5 => AnyValue::Bool(c.bool()?),
            6 => AnyValue::Null,
            7 => AnyValue::EnumRef {
                enum_class: c.u32()?,
                enum_name: c.u32()?,
            },
            8 => {
                let n = c.i32()?.max(0) as usize;
                let mut items = Vec::with_capacity(n);
                for _ in 0..n {
                    items.push(AnyValue::read(c, version)?);
                }
                AnyValue::List(items)
            }
            _ => AnyValue::Str(c.u32()?),
        })
    }

    /// Annotation attribute values: bare string index in format version 1.
    pub fn read_annotation(c: &mut Cursor, version: u8) -> Result<Self, Truncated> {
        if version <= 1 {
            let id = c.u32()?;
            Ok(AnyValue::Str(id))
        } else {
            AnyValue::read(c, version)
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum NodeKind {
    IntConstant(i32),
    StringConstant(StrId),
    LongConstant(i64),
    FloatConstant(f32),
    DoubleConstant(f64),
    BooleanConstant(bool),
    NullConstant,
    EnumConstant {
        enum_type: StrId,
        enum_name: StrId,
        args: Vec<AnyValue>,
    },
    LocalVariable {
        name: StrId,
        var_type: StrId,
        method: MethodDesc,
    },
    Field {
        declaring_class: StrId,
        name: StrId,
        field_type: StrId,
        is_static: bool,
    },
    Parameter {
        index: i32,
        param_type: StrId,
        method: MethodDesc,
    },
    Return {
        method: MethodDesc,
        actual_type: Option<StrId>,
    },
    CallSite {
        caller: MethodDesc,
        callee: MethodDesc,
        line: Option<i32>,
        receiver: Option<NodeId>,
        arguments: Vec<NodeId>,
        /// Which call of `callee` in `caller` this is, counted in statement order from
        /// `0`; a call the frontend derived rather than read from the bytecode counts
        /// from `-1` downwards. Not part of the record: `Graph::node` fills it from the
        /// `graph.callsite-ordinals` sidecar, and it is `None` for a graph without one.
        ordinal: Option<i32>,
    },
    Annotation {
        name: StrId,
        class_name: StrId,
        member_name: StrId,
        values: Vec<(StrId, AnyValue)>,
    },
    ResourceValue {
        path: StrId,
        key: StrId,
        value: AnyValue,
        format: StrId,
        profile: Option<StrId>,
    },
    ResourceFile {
        path: StrId,
        source: StrId,
        format: StrId,
        profile: Option<StrId>,
    },
}

#[derive(Debug, Clone, PartialEq)]
pub struct Node {
    pub id: NodeId,
    pub kind: NodeKind,
}

impl Node {
    pub fn tag(&self) -> u8 {
        match &self.kind {
            NodeKind::IntConstant(_) => TAG_INT_CONSTANT,
            NodeKind::StringConstant(_) => TAG_STRING_CONSTANT,
            NodeKind::LongConstant(_) => TAG_LONG_CONSTANT,
            NodeKind::FloatConstant(_) => TAG_FLOAT_CONSTANT,
            NodeKind::DoubleConstant(_) => TAG_DOUBLE_CONSTANT,
            NodeKind::BooleanConstant(_) => TAG_BOOLEAN_CONSTANT,
            NodeKind::NullConstant => TAG_NULL_CONSTANT,
            NodeKind::EnumConstant { .. } => TAG_ENUM_CONSTANT,
            NodeKind::LocalVariable { .. } => TAG_LOCAL_VARIABLE,
            NodeKind::Field { .. } => TAG_FIELD_NODE,
            NodeKind::Parameter { .. } => TAG_PARAMETER_NODE,
            NodeKind::Return { .. } => TAG_RETURN_NODE,
            NodeKind::CallSite { .. } => TAG_CALL_SITE_NODE,
            NodeKind::Annotation { .. } => TAG_ANNOTATION_NODE,
            NodeKind::ResourceValue { .. } => TAG_RESOURCE_VALUE_NODE,
            NodeKind::ResourceFile { .. } => TAG_RESOURCE_FILE_NODE,
        }
    }

    /// Kotlin class simple name of the node (`nodeTypeName`).
    pub fn type_name(&self) -> &'static str {
        tag_type_name(self.tag())
    }

    pub fn is_constant(&self) -> bool {
        self.tag() <= TAG_ENUM_CONSTANT
    }

    /// Decode one record starting at `pos` (the int32 id).
    pub fn read(data: &[u8], pos: usize, version: u8) -> Result<Node, NodeDecodeError> {
        let mut c = Cursor::at(data, pos);
        let id = c.u32()?;
        let tag = c.u8()?;
        let kind = match tag {
            TAG_INT_CONSTANT => NodeKind::IntConstant(c.i32()?),
            TAG_STRING_CONSTANT => NodeKind::StringConstant(c.u32()?),
            TAG_LONG_CONSTANT => NodeKind::LongConstant(c.i64()?),
            TAG_FLOAT_CONSTANT => NodeKind::FloatConstant(c.f32()?),
            TAG_DOUBLE_CONSTANT => NodeKind::DoubleConstant(c.f64()?),
            TAG_BOOLEAN_CONSTANT => NodeKind::BooleanConstant(c.bool()?),
            TAG_NULL_CONSTANT => NodeKind::NullConstant,
            TAG_ENUM_CONSTANT => {
                let enum_type = c.u32()?;
                let enum_name = c.u32()?;
                let n = c.i32()?.max(0) as usize;
                let mut args = Vec::with_capacity(n);
                for _ in 0..n {
                    args.push(AnyValue::read(&mut c, version)?);
                }
                NodeKind::EnumConstant {
                    enum_type,
                    enum_name,
                    args,
                }
            }
            TAG_LOCAL_VARIABLE => {
                let name = c.u32()?;
                let var_type = c.u32()?;
                let method = MethodDesc::read(&mut c)?;
                NodeKind::LocalVariable {
                    name,
                    var_type,
                    method,
                }
            }
            TAG_FIELD_NODE => NodeKind::Field {
                declaring_class: c.u32()?,
                name: c.u32()?,
                field_type: c.u32()?,
                is_static: c.bool()?,
            },
            TAG_PARAMETER_NODE => {
                let index = c.i32()?;
                let param_type = c.u32()?;
                let method = MethodDesc::read(&mut c)?;
                NodeKind::Parameter {
                    index,
                    param_type,
                    method,
                }
            }
            TAG_RETURN_NODE => {
                let method = MethodDesc::read(&mut c)?;
                let actual_type = if c.bool()? { Some(c.u32()?) } else { None };
                NodeKind::Return {
                    method,
                    actual_type,
                }
            }
            TAG_RESOURCE_FILE_NODE => {
                let path = c.u32()?;
                let source = c.u32()?;
                let format = c.u32()?;
                let profile = if c.bool()? { Some(c.u32()?) } else { None };
                NodeKind::ResourceFile {
                    path,
                    source,
                    format,
                    profile,
                }
            }
            TAG_RESOURCE_VALUE_NODE => {
                let path = c.u32()?;
                let key = c.u32()?;
                let value = AnyValue::read(&mut c, version)?;
                let format = c.u32()?;
                let profile = if c.bool()? { Some(c.u32()?) } else { None };
                NodeKind::ResourceValue {
                    path,
                    key,
                    value,
                    format,
                    profile,
                }
            }
            TAG_CALL_SITE_NODE => {
                let caller = MethodDesc::read(&mut c)?;
                let callee = MethodDesc::read(&mut c)?;
                let line = c.i32()?;
                let receiver = c.i32()?;
                let n = c.i32()?.max(0) as usize;
                let mut arguments = Vec::with_capacity(n);
                for _ in 0..n {
                    arguments.push(c.u32()?);
                }
                NodeKind::CallSite {
                    caller,
                    callee,
                    line: if line == -1 { None } else { Some(line) },
                    receiver: if receiver == -1 {
                        None
                    } else {
                        Some(receiver as u32)
                    },
                    arguments,
                    ordinal: None,
                }
            }
            TAG_ANNOTATION_NODE => {
                let name = c.u32()?;
                let class_name = c.u32()?;
                let member_name = c.u32()?;
                let n = c.i32()?.max(0) as usize;
                let mut values = Vec::with_capacity(n);
                for _ in 0..n {
                    let k = c.u32()?;
                    let v = AnyValue::read_annotation(&mut c, version)?;
                    values.push((k, v));
                }
                NodeKind::Annotation {
                    name,
                    class_name,
                    member_name,
                    values,
                }
            }
            other => return Err(NodeDecodeError::UnknownTag(other)),
        };
        Ok(Node { id, kind })
    }
}

#[derive(Debug, thiserror::Error)]
pub enum NodeDecodeError {
    #[error("Unknown node tag: {0}")]
    UnknownTag(u8),
    #[error(transparent)]
    Truncated(#[from] Truncated),
}

pub fn tag_type_name(tag: u8) -> &'static str {
    match tag {
        TAG_INT_CONSTANT => "IntConstant",
        TAG_STRING_CONSTANT => "StringConstant",
        TAG_LONG_CONSTANT => "LongConstant",
        TAG_FLOAT_CONSTANT => "FloatConstant",
        TAG_DOUBLE_CONSTANT => "DoubleConstant",
        TAG_BOOLEAN_CONSTANT => "BooleanConstant",
        TAG_NULL_CONSTANT => "NullConstant",
        TAG_ENUM_CONSTANT => "EnumConstant",
        TAG_LOCAL_VARIABLE => "LocalVariable",
        TAG_FIELD_NODE => "FieldNode",
        TAG_PARAMETER_NODE => "ParameterNode",
        TAG_RETURN_NODE => "ReturnNode",
        TAG_CALL_SITE_NODE => "CallSiteNode",
        TAG_ANNOTATION_NODE => "AnnotationNode",
        TAG_RESOURCE_VALUE_NODE => "ResourceValueNode",
        TAG_RESOURCE_FILE_NODE => "ResourceFileNode",
        _ => "Node",
    }
}

/// The CallSite properties the string accelerator indexes, in property order.
pub const CALL_SITE_PROPERTY_NAMES: [&str; 4] =
    ["caller_class", "caller_name", "callee_class", "callee_name"];

/// Raw CallSite string ids read straight from a record without decoding the rest.
/// Layout after the 5-byte header: caller{class,name,paramCount,params..,ret}, callee{...}.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CallSiteStrings {
    pub caller_class: StrId,
    pub caller_name: StrId,
    pub callee_class: StrId,
    pub callee_name: StrId,
}

#[inline]
pub fn read_call_site_strings(data: &[u8], record_pos: usize) -> CallSiteStrings {
    use crate::io::read_i32_at;
    let p = record_pos + NODE_HEADER_BYTES;
    let caller_class = read_i32_at(data, p) as u32;
    let caller_name = read_i32_at(data, p + 4) as u32;
    let caller_params = read_i32_at(data, p + 8).max(0) as usize;
    let callee = p + 16 + caller_params * 4;
    let callee_class = read_i32_at(data, callee) as u32;
    let callee_name = read_i32_at(data, callee + 4) as u32;
    CallSiteStrings {
        caller_class,
        caller_name,
        callee_class,
        callee_name,
    }
}

/// The `graph.callsite-ordinals` sidecar: the ordinal of every call site that has one, and
/// the call site a derived one was resolved from, by node id. Layout: `int32 header = "GRQ"
/// | 4`, a copy of the 32-byte binding digest, then the index the binding is the SHA-256 of
/// (`int32 count`, `int32 originCount`, the node id heading each block of 256 ordinal pairs,
/// and the SHA-256 of each 2048-byte block of the entries), then the entries: `count` pairs
/// of `int32 nodeId, int32 ordinal` ascending by node id, then `originCount` pairs of `int32
/// nodeId, int32 origin`, one per derived call site resolved from another, ascending by node
/// id. The origins sit apart so that reading an ordinal, on the decode path of every call
/// site, costs what it did before they existed, and the index lets the mapped Kotlin reader
/// prove a block of entries on its first touch rather than hash the whole file per mapping;
/// this reader holds the entries in memory and proves every block when it parses them. A
/// file of its own so that a reader which predates it reads the graph exactly as before; the
/// binding is also the last section of `graph.metadata`, which binds the sidecar to the graph
/// it describes: a writer that does not know the sidecar rewrites the metadata without it.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct CallSiteOrdinals {
    ids: Vec<u32>,
    ordinals: Vec<i32>,
    origin_ids: Vec<u32>,
    origins: Vec<u32>,
}

pub const MAGIC_CALL_SITE_ORDINALS: i32 = 0x47525100;
pub const CALL_SITE_ORDINALS_VERSION: u8 = 4;
pub const CALL_SITE_ORDINAL_ENTRY_BYTES: usize = 8;
/// Ordinal pairs per block head; the block digests cover the same bytes, 2048 per block.
pub const CALL_SITE_ORDINAL_BLOCK_ENTRIES: usize = 256;
pub const CALL_SITE_ORDINAL_BLOCK_BYTES: usize =
    CALL_SITE_ORDINAL_BLOCK_ENTRIES * CALL_SITE_ORDINAL_ENTRY_BYTES;
/// Header and the copy of the binding, before the index.
pub const CALL_SITE_ORDINALS_PREAMBLE_BYTES: usize = 4 + 32;

impl CallSiteOrdinals {
    pub fn empty() -> CallSiteOrdinals {
        CallSiteOrdinals::default()
    }

    /// Parse the sidecar bound by `binding`, the digest `graph.metadata` ends with; `None`
    /// when there is no binding or the bytes are not that sidecar (wrong header, cut short,
    /// longer than its counts, an index that does not hash to the binding, a block of entries
    /// that does not hash to its digest in the index, a head that is not the first id of its
    /// block), in which case the graph is read without ordinals, as a reader without the
    /// sidecar does.
    pub fn parse(data: &[u8], binding: Option<&[u8; 32]>) -> Option<CallSiteOrdinals> {
        let binding = binding?;
        let mut c = Cursor::new(data);
        let header = c.i32().ok()?;
        if header & !0xFF != MAGIC_CALL_SITE_ORDINALS
            || (header & 0xFF) as u8 != CALL_SITE_ORDINALS_VERSION
        {
            return None;
        }
        if c.bytes(32).ok()? != binding {
            return None;
        }
        let count = usize::try_from(c.i32().ok()?).ok()?;
        let origin_count = usize::try_from(c.i32().ok()?).ok()?;
        let heads = count.div_ceil(CALL_SITE_ORDINAL_BLOCK_ENTRIES);
        let entry_bytes = count
            .checked_add(origin_count)?
            .checked_mul(CALL_SITE_ORDINAL_ENTRY_BYTES)?;
        let blocks = entry_bytes.div_ceil(CALL_SITE_ORDINAL_BLOCK_BYTES);
        let digests_at = CALL_SITE_ORDINALS_PREAMBLE_BYTES
            .checked_add(8)?
            .checked_add(heads.checked_mul(4)?)?;
        let entries_at = digests_at.checked_add(blocks.checked_mul(32)?)?;
        if data.len() != entries_at.checked_add(entry_bytes)? {
            return None;
        }
        // The index is what the binding covers: the digest copied into the header says
        // nothing about the bytes behind it. The index then vouches for every block of
        // entries, each hashed before any ordinal is exposed.
        if Sha256::digest(&data[CALL_SITE_ORDINALS_PREAMBLE_BYTES..entries_at])[..] != binding[..] {
            return None;
        }
        let entries = &data[entries_at..];
        for (block, digest) in data[digests_at..entries_at].chunks(32).enumerate() {
            let from = block * CALL_SITE_ORDINAL_BLOCK_BYTES;
            let to = (from + CALL_SITE_ORDINAL_BLOCK_BYTES).min(entries.len());
            if Sha256::digest(&entries[from..to])[..] != digest[..] {
                return None;
            }
        }
        let mut c = Cursor::new(entries);
        let mut ids = Vec::with_capacity(count);
        let mut ordinals = Vec::with_capacity(count);
        for _ in 0..count {
            ids.push(c.u32().ok()?);
            ordinals.push(c.i32().ok()?);
        }
        let mut origin_ids = Vec::with_capacity(origin_count);
        let mut origins = Vec::with_capacity(origin_count);
        for _ in 0..origin_count {
            origin_ids.push(c.u32().ok()?);
            origins.push(c.u32().ok()?);
        }
        let mut h = Cursor::new(&data[CALL_SITE_ORDINALS_PREAMBLE_BYTES + 8..digests_at]);
        for block in 0..heads {
            if h.u32().ok()? != ids[block * CALL_SITE_ORDINAL_BLOCK_ENTRIES] {
                return None;
            }
        }
        Some(CallSiteOrdinals {
            ids,
            ordinals,
            origin_ids,
            origins,
        })
    }

    pub fn len(&self) -> usize {
        self.ids.len()
    }

    pub fn is_empty(&self) -> bool {
        self.ids.is_empty()
    }

    /// The ordinal of node `id`, `None` when the sidecar has none for it.
    pub fn get(&self, id: NodeId) -> Option<i32> {
        self.ids.binary_search(&id).ok().map(|i| self.ordinals[i])
    }

    /// The call sites derived from call site `origin`, in node id order.
    pub fn derived_from(&self, origin: NodeId) -> impl Iterator<Item = NodeId> + '_ {
        self.origin_ids
            .iter()
            .zip(&self.origins)
            .filter(move |(_, o)| **o == origin)
            .map(|(id, _)| *id)
    }

    /// The call site node `id` was derived from (a call on a function value resolved to a
    /// lambda body), `None` when it was not derived from one or the sidecar has no entry for it.
    pub fn origin(&self, id: NodeId) -> Option<NodeId> {
        let i = self.origin_ids.binary_search(&id).ok()?;
        Some(self.origins[i])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A CallSite record as the Kotlin writer lays it out: id, tag, caller and callee
    /// descriptors (class, name, parameter count, parameters, return type), line,
    /// receiver, argument count, arguments.
    fn call_site_record() -> Vec<u8> {
        let mut out = Vec::new();
        let i32 = |out: &mut Vec<u8>, v: i32| out.extend_from_slice(&v.to_be_bytes());
        i32(&mut out, 7);
        out.push(TAG_CALL_SITE_NODE);
        for desc in [[0, 1], [2, 3]] {
            for v in [desc[0], desc[1], 1, 4, 5] {
                i32(&mut out, v);
            }
        }
        for v in [-1, -1, 2, 11, 12] {
            i32(&mut out, v);
        }
        out
    }

    #[test]
    fn a_call_site_record_carries_no_ordinal_of_its_own() {
        let node = Node::read(&call_site_record(), 0, 3).unwrap();
        assert_eq!(node.id, 7);
        match &node.kind {
            NodeKind::CallSite {
                arguments, ordinal, ..
            } => {
                assert_eq!(arguments, &vec![11, 12]);
                assert_eq!(*ordinal, None);
            }
            other => panic!("not a call site: {other:?}"),
        }
    }

    /// The sidecar as the Kotlin writer lays it out: header, the binding's copy, the index
    /// (counts, block heads, block digests), then the entries.
    fn encoded(pairs: &[(i32, i32)], origins: &[(i32, i32)]) -> (Vec<u8>, [u8; 32]) {
        let i32 = |out: &mut Vec<u8>, v: i32| out.extend_from_slice(&v.to_be_bytes());
        let mut entries = Vec::new();
        for (id, value) in pairs.iter().chain(origins) {
            i32(&mut entries, *id);
            i32(&mut entries, *value);
        }
        let mut index = Vec::new();
        i32(&mut index, pairs.len() as i32);
        i32(&mut index, origins.len() as i32);
        for block in pairs.chunks(CALL_SITE_ORDINAL_BLOCK_ENTRIES) {
            i32(&mut index, block[0].0);
        }
        for block in entries.chunks(CALL_SITE_ORDINAL_BLOCK_BYTES) {
            index.extend_from_slice(&Sha256::digest(block));
        }
        let digest: [u8; 32] = Sha256::digest(&index).into();
        let mut bytes = Vec::new();
        i32(
            &mut bytes,
            MAGIC_CALL_SITE_ORDINALS | i32::from(CALL_SITE_ORDINALS_VERSION),
        );
        bytes.extend_from_slice(&digest);
        bytes.extend_from_slice(&index);
        bytes.extend_from_slice(&entries);
        (bytes, digest)
    }

    #[test]
    fn the_ordinal_sidecar_parses_and_answers_by_node_id() {
        // One derived call site, 9, resolved from 4.
        let (bytes, digest) = encoded(&[(4, 0), (9, -1), (12, 2)], &[(9, 4)]);
        let sidecar = CallSiteOrdinals::parse(&bytes, Some(&digest)).unwrap();
        assert_eq!(sidecar.len(), 3);
        assert_eq!(sidecar.get(4), Some(0));
        assert_eq!(sidecar.get(9), Some(-1));
        assert_eq!(sidecar.get(12), Some(2));
        assert_eq!(sidecar.get(5), None);
        assert_eq!(sidecar.origin(9), Some(4));
        assert_eq!(sidecar.origin(4), None);
        assert_eq!(sidecar.origin(5), None);
        assert_eq!(sidecar.derived_from(4).collect::<Vec<_>>(), vec![9]);
        assert_eq!(sidecar.derived_from(9).count(), 0);
        // No binding, another graph's binding: the sidecar describes another graph.
        assert!(CallSiteOrdinals::parse(&bytes, None).is_none());
        assert!(CallSiteOrdinals::parse(&bytes, Some(&[0u8; 32])).is_none());
        // Cut short, trailing bytes, wrong header, wrong version: not a sidecar.
        assert!(CallSiteOrdinals::parse(&bytes[..bytes.len() - 2], Some(&digest)).is_none());
        let mut longer = bytes.clone();
        longer.push(0);
        assert!(CallSiteOrdinals::parse(&longer, Some(&digest)).is_none());
        assert!(CallSiteOrdinals::parse(&[1, 2, 3, 4, 0, 0, 0, 0], Some(&digest)).is_none());
        let mut wrong = bytes.clone();
        wrong[3] = 1;
        assert!(CallSiteOrdinals::parse(&wrong, Some(&digest)).is_none());
        // An entry flipped behind an intact index: every block is hashed against the index,
        // and the index against the binding, not the header's copy.
        let mut tampered = bytes.clone();
        let last = tampered.len() - 1;
        tampered[last] ^= 1;
        assert!(CallSiteOrdinals::parse(&tampered, Some(&digest)).is_none());
        let head_at = CALL_SITE_ORDINALS_PREAMBLE_BYTES + 8;
        let mut other_head = bytes.clone();
        other_head[head_at + 3] = 5;
        assert!(CallSiteOrdinals::parse(&other_head, Some(&digest)).is_none());
        assert!(CallSiteOrdinals::empty().is_empty());
        // Blocks: 300 pairs span two heads and, with their origins, two blocks of entries.
        let pairs: Vec<(i32, i32)> = (0..300).map(|i| (i * 2, i)).collect();
        let origins: Vec<(i32, i32)> = (0..100).map(|i| (i * 6, i * 6 - 2)).collect();
        let (bytes, digest) = encoded(&pairs, &origins);
        let sidecar = CallSiteOrdinals::parse(&bytes, Some(&digest)).unwrap();
        assert_eq!(sidecar.len(), 300);
        assert_eq!(sidecar.get(598), Some(299));
        assert_eq!(sidecar.origin(594), Some(592));
        let entries_at = bytes.len() - 400 * CALL_SITE_ORDINAL_ENTRY_BYTES;
        let mut other_block = bytes.clone();
        other_block[entries_at + CALL_SITE_ORDINAL_BLOCK_BYTES + 7] ^= 1;
        assert!(CallSiteOrdinals::parse(&other_block, Some(&digest)).is_none());
    }
}

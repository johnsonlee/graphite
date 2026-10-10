//! Node model and `graph.nodedata` record decoding.

use crate::container::Bytes;
use crate::io::{Cursor, Truncated};
use crate::strings::StringTable;
use sha2::{Digest, Sha256};
use std::sync::Arc;

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

/// The four scalar string IDs, only after the complete CallSite framing is checked.
/// Counts retain Node::read's negative-as-zero behavior. No parameter/argument
/// vectors are constructed; invalid or other-kind records keep the caller's fallback.
pub fn read_call_site_scalar_strings(data: &[u8], record_pos: usize) -> Option<CallSiteStrings> {
    fn ids(c: &mut Cursor<'_>) -> Option<()> {
        let count = c.i32().ok()?.max(0) as usize;
        c.skip(count.checked_mul(4)?).ok()
    }
    fn method(c: &mut Cursor<'_>) -> Option<(StrId, StrId)> {
        let class = c.u32().ok()?;
        let name = c.u32().ok()?;
        ids(c)?;
        c.u32().ok()?; // return type
        Some((class, name))
    }
    let mut c = Cursor::new(data.get(record_pos..)?);
    c.u32().ok()?; // Like Node::read, do not require header id == lookup id.
    if c.u8().ok()? != TAG_CALL_SITE_NODE {
        return None;
    }
    let (caller_class, caller_name) = method(&mut c)?;
    let (callee_class, callee_name) = method(&mut c)?;
    c.i32().ok()?; // line
    c.i32().ok()?; // receiver
    ids(&mut c)?;
    Some(CallSiteStrings {
        caller_class,
        caller_name,
        callee_class,
        callee_name,
    })
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
/// this reader retains the bytes and proves every block when it parses them. A
/// file of its own so that a reader which predates it reads the graph exactly as before; the
/// binding is also the last section of `graph.metadata`, which binds the sidecar to the graph
/// it describes: a writer that does not know the sidecar rewrites the metadata without it.
#[derive(Debug, Default, Clone)]
pub struct CallSiteOrdinals {
    data: Option<Bytes>,
    layout: OrdinalLayout,
    ordinal_index: Option<Arc<OrdinalPresenceIndex>>,
}

#[derive(Debug, Default, Clone, Copy)]
struct OrdinalLayout {
    count: usize,
    origin_count: usize,
    entries_at: usize,
}

/// Optional rank index over strictly increasing ordinal IDs. Values remain in the
/// validated retained bytes; origins keep their original lookup path.
#[derive(Debug)]
struct OrdinalPresenceIndex {
    min: NodeId,
    bits: Vec<u64>,
    preceding: Vec<u32>,
}

impl OrdinalPresenceIndex {
    fn build(entries: &[[u8; CALL_SITE_ORDINAL_ENTRY_BYTES]]) -> Option<Arc<Self>> {
        let min = ordinal_entry_id(entries.first()?);
        let max = ordinal_entry_id(entries.last()?);
        let words = u64::from(max)
            .checked_sub(u64::from(min))?
            .checked_div(64)?
            .checked_add(1)?;
        // Include the index/Vec headers, owning Arc pointer and strong/weak counters, not just
        // array payload. The optional cache may use at most two bytes per pair.
        let budget = u64::try_from(entries.len()).ok()?.checked_mul(2)?;
        if Self::allocation_bytes(words, words)? > budget {
            return None;
        }
        if entries
            .windows(2)
            .any(|pair| ordinal_entry_id(&pair[0]) >= ordinal_entry_id(&pair[1]))
        {
            // The parser historically accepts duplicates and unsorted IDs. Preserve
            // their exact full-slice binary_search behavior instead of normalizing.
            return None;
        }
        let words = usize::try_from(words).ok()?;
        let mut bits = Vec::new();
        let mut preceding = Vec::new();
        bits.try_reserve_exact(words).ok()?;
        preceding.try_reserve_exact(words).ok()?;
        if Self::allocation_bytes(
            u64::try_from(bits.capacity()).ok()?,
            u64::try_from(preceding.capacity()).ok()?,
        )? > budget
        {
            return None;
        }
        bits.resize(words, 0u64);
        for entry in entries {
            let offset = ordinal_entry_id(entry) - min;
            bits[usize::try_from(offset / 64).ok()?] |= 1u64 << (offset % 64);
        }
        let mut rank = 0u32;
        for word in &bits {
            preceding.push(rank);
            rank = rank.checked_add(word.count_ones())?;
        }
        Some(Arc::new(Self {
            min,
            bits,
            preceding,
        }))
    }

    fn allocation_bytes(bit_words: u64, prefix_words: u64) -> Option<u64> {
        let headers = u64::try_from(std::mem::size_of::<Self>())
            .ok()?
            .checked_add(u64::try_from(2 * std::mem::size_of::<usize>()).ok()?)?
            .checked_add(u64::try_from(std::mem::size_of::<Arc<Self>>()).ok()?)?;
        headers
            .checked_add(bit_words.checked_mul(8)?)?
            .checked_add(prefix_words.checked_mul(4)?)
    }

    fn position(&self, id: NodeId) -> Option<usize> {
        let offset = id.checked_sub(self.min)?;
        let word_index = usize::try_from(offset / 64).ok()?;
        let word = *self.bits.get(word_index)?;
        let bit = 1u64 << (offset % 64);
        if word & bit == 0 {
            return None;
        }
        usize::try_from(self.preceding[word_index] + (word & (bit - 1)).count_ones()).ok()
    }
}

impl PartialEq for CallSiteOrdinals {
    fn eq(&self, other: &Self) -> bool {
        // Compare the four logical columns, not backing-map identity or index bytes.
        // A valid empty sidecar and Default describe the same empty columns.
        self.ordinal_entries() == other.ordinal_entries()
            && self.origin_entries() == other.origin_entries()
    }
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

    /// Logical bytes of the retained mapped entry, excluding owned data and the rank index.
    /// A container slice counts only its range, not the entire shared mapping or resident pages.
    pub(crate) fn mapped_bytes(&self) -> u64 {
        match self.data.as_ref() {
            Some(data @ Bytes::Mapped { .. }) => data.len() as u64,
            _ => 0,
        }
    }

    /// Parse the sidecar bound by `binding`, the digest `graph.metadata` ends with; `None`
    /// when there is no binding or the bytes are not that sidecar (wrong header, cut short,
    /// longer than its counts, an index that does not hash to the binding, a block of entries
    /// that does not hash to its digest in the index, a head that is not the first id of its
    /// block), in which case the graph is read without ordinals, as a reader without the
    /// sidecar does.
    pub fn parse(data: &[u8], binding: Option<&[u8; 32]>) -> Option<CallSiteOrdinals> {
        let layout = Self::validated_layout(data, binding)?;
        // This public borrowed-input API retains its independent snapshot semantics.
        Some(Self::from_validated(Bytes::owned(data.to_vec()), layout))
    }

    /// Retain the graph source's bytes after the same eager validation as `parse`.
    /// As with the graph's other mapped files, the backing file must remain immutable
    /// for the lifetime of the loaded graph and its leases. A readonly map is not a
    /// snapshot of later in-place writes; callers with borrowed bytes use `parse`.
    pub(crate) fn parse_bytes(data: Bytes, binding: Option<&[u8; 32]>) -> Option<Self> {
        let layout = Self::validated_layout(&data, binding)?;
        Some(Self::from_validated(data, layout))
    }

    fn from_validated(data: Bytes, layout: OrdinalLayout) -> Self {
        // Both entry points complete every layout/hash/head check before building
        // this optional cache. No validation is deferred to the lookup path.
        let entries = data[layout.entries_at..]
            .as_chunks::<CALL_SITE_ORDINAL_ENTRY_BYTES>()
            .0;
        let ordinal_index = OrdinalPresenceIndex::build(&entries[..layout.count]);
        Self {
            data: Some(data),
            layout,
            ordinal_index,
        }
    }

    fn validated_layout(data: &[u8], binding: Option<&[u8; 32]>) -> Option<OrdinalLayout> {
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
        let mut h = Cursor::new(&data[CALL_SITE_ORDINALS_PREAMBLE_BYTES + 8..digests_at]);
        for block in 0..heads {
            let mut first = Cursor::at(entries, block * CALL_SITE_ORDINAL_BLOCK_BYTES);
            if h.u32().ok()? != first.u32().ok()? {
                return None;
            }
        }
        Some(OrdinalLayout {
            count,
            origin_count,
            entries_at,
        })
    }

    fn entries(&self) -> &[[u8; CALL_SITE_ORDINAL_ENTRY_BYTES]] {
        self.data.as_ref().map_or(&[], |data| {
            // Exact length and checked arithmetic were established eagerly.
            data[self.layout.entries_at..]
                .as_chunks::<CALL_SITE_ORDINAL_ENTRY_BYTES>()
                .0
        })
    }

    fn ordinal_entries(&self) -> &[[u8; CALL_SITE_ORDINAL_ENTRY_BYTES]] {
        &self.entries()[..self.layout.count]
    }

    fn origin_entries(&self) -> &[[u8; CALL_SITE_ORDINAL_ENTRY_BYTES]] {
        &self.entries()[self.layout.count..self.layout.count + self.layout.origin_count]
    }

    pub fn len(&self) -> usize {
        self.layout.count
    }

    pub fn is_empty(&self) -> bool {
        self.layout.count == 0
    }

    /// The ordinal of node `id`, `None` when the sidecar has none for it.
    pub fn get(&self, id: NodeId) -> Option<i32> {
        let entries = self.ordinal_entries();
        let i = match &self.ordinal_index {
            Some(index) => index.position(id)?,
            None => entries
                .binary_search_by(|entry| ordinal_entry_id(entry).cmp(&id))
                .ok()?,
        };
        Some(i32::from_be_bytes(entries[i][4..8].try_into().unwrap()))
    }

    /// The call sites derived from call site `origin`, in node id order.
    pub fn derived_from(&self, origin: NodeId) -> impl Iterator<Item = NodeId> + '_ {
        self.origin_entries()
            .iter()
            .filter(move |entry| u32::from_be_bytes(entry[4..8].try_into().unwrap()) == origin)
            .map(ordinal_entry_id)
    }

    /// The call site node `id` was derived from (a call on a function value resolved to a
    /// lambda body), `None` when it was not derived from one or the sidecar has no entry for it.
    pub fn origin(&self, id: NodeId) -> Option<NodeId> {
        let entries = self.origin_entries();
        let i = entries
            .binary_search_by(|entry| ordinal_entry_id(entry).cmp(&id))
            .ok()?;
        Some(u32::from_be_bytes(entries[i][4..8].try_into().unwrap()))
    }
}

fn ordinal_entry_id(entry: &[u8; CALL_SITE_ORDINAL_ENTRY_BYTES]) -> NodeId {
    u32::from_be_bytes(entry[..4].try_into().unwrap())
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
    fn ordinal_bitmap_ranks_hits_and_holes_across_word_and_block_boundaries() {
        for count in [63u32, 64, 65, 255, 256, 257, 320] {
            let pairs: Vec<_> = (0..count).map(|id| (id as i32, -(id as i32) - 1)).collect();
            let (bytes, digest) = encoded(&pairs, &[]);
            let sidecar = CallSiteOrdinals::parse(&bytes, Some(&digest)).unwrap();
            assert!(sidecar.ordinal_index.is_some(), "dense count {count}");
            for id in 0..=count {
                assert_eq!(
                    sidecar.get(id),
                    (id < count).then_some(-(id as i32) - 1),
                    "count {count}, id {id}"
                );
            }
        }
        let pairs: Vec<_> = (0..321)
            .filter(|id| ![1, 63, 128, 255, 319].contains(id))
            .map(|id| (id, if id == 256 { i32::MIN } else { -id - 1 }))
            .collect();
        let (bytes, digest) = encoded(&pairs, &[(256, 64), (320, 64)]);
        let sidecar = CallSiteOrdinals::parse(&bytes, Some(&digest)).unwrap();
        assert!(sidecar.ordinal_index.is_some());
        for id in 0..=322 {
            assert_eq!(
                sidecar.get(id),
                pairs
                    .iter()
                    .find(|pair| pair.0 as u32 == id)
                    .map(|pair| pair.1)
            );
        }
        assert_eq!(sidecar.get(63), None);
        assert_eq!(sidecar.get(64), Some(-65));
        assert_eq!(sidecar.get(65), Some(-66));
        assert_eq!(sidecar.get(255), None);
        assert_eq!(sidecar.get(256), Some(i32::MIN));
        assert_eq!(sidecar.origin(256), Some(64));
        assert_eq!(sidecar.derived_from(64).collect::<Vec<_>>(), vec![256, 320]);
    }

    #[test]
    fn ordinal_bitmap_handles_high_unsigned_ids_and_respects_the_complete_cache_budget() {
        for min in [0u32, 17, u32::MAX - 319] {
            let pairs: Vec<_> = (0..320)
                .filter(|offset| *offset != 64)
                .map(|offset| ((min + offset) as i32, -(offset as i32) - 1))
                .collect();
            let (bytes, digest) = encoded(&pairs, &[]);
            let sidecar = CallSiteOrdinals::parse(&bytes, Some(&digest)).unwrap();
            let index = sidecar.ordinal_index.as_ref().unwrap();
            let cache_bytes = OrdinalPresenceIndex::allocation_bytes(
                index.bits.capacity() as u64,
                index.preceding.capacity() as u64,
            )
            .unwrap();
            assert!(cache_bytes <= 2 * pairs.len() as u64);
            assert_eq!(sidecar.get(min), Some(-1));
            assert_eq!(sidecar.get(min + 63), Some(-64));
            assert_eq!(sidecar.get(min + 64), None);
            assert_eq!(sidecar.get(min + 65), Some(-66));
            assert_eq!(sidecar.get(min + 319), Some(-320));
            if let Some(before) = min.checked_sub(1) {
                assert_eq!(sidecar.get(before), None);
            }
            if let Some(after) = min.checked_add(320) {
                assert_eq!(sidecar.get(after), None);
            }
        }
        for pairs in [
            vec![(0, -1), (-1, i32::MIN)],
            (0..128).map(|id| (id * 100_000, -id)).collect(),
            vec![(4, -7)],
        ] {
            let (bytes, digest) = encoded(&pairs, &[]);
            let sidecar = CallSiteOrdinals::parse(&bytes, Some(&digest)).unwrap();
            assert!(
                sidecar.ordinal_index.is_none(),
                "sparse/small cache must fall back"
            );
            for &(id, value) in &pairs {
                assert_eq!(sidecar.get(id as u32), Some(value));
            }
            assert_eq!(sidecar.get(3), None);
        }
        assert!(OrdinalPresenceIndex::allocation_bytes(u64::MAX, 1).is_none());
        assert!(OrdinalPresenceIndex::allocation_bytes(1, u64::MAX).is_none());
    }

    #[test]
    fn ordinal_bitmap_preserves_full_binary_search_for_duplicates_and_unsorted_ids() {
        let dense: Vec<_> = (0..320).map(|id| (id, -id - 1)).collect();
        let mut duplicates = dense.clone();
        duplicates[64] = (63, i32::MIN);
        let mut unsorted = dense;
        unsorted.swap(255, 256);
        for pairs in [duplicates, unsorted] {
            let (bytes, digest) = encoded(&pairs, &[(9, 4), (7, 4), (9, 12)]);
            let sidecar = CallSiteOrdinals::parse(&bytes, Some(&digest)).unwrap();
            assert!(sidecar.ordinal_index.is_none());
            let ids: Vec<_> = pairs.iter().map(|pair| pair.0 as u32).collect();
            for id in 0..=321 {
                assert_eq!(
                    sidecar.get(id),
                    ids.binary_search(&id).ok().map(|i| pairs[i].1)
                );
            }
            assert_lookup_matches_columns(&pairs, &[(9, 4), (7, 4), (9, 12)]);
        }
    }

    #[test]
    fn ordinal_bitmap_and_retained_bytes_are_shared_across_clone_and_mapping_drop() {
        let pairs: Vec<_> = (0..320).map(|id| (id, -id - 1)).collect();
        let (mut bytes, digest) = encoded(&pairs, &[(64, 0), (256, 0)]);
        let borrowed = CallSiteOrdinals::parse(&bytes, Some(&digest)).unwrap();
        let owned_bytes = Bytes::owned(bytes.clone());
        let owned = CallSiteOrdinals::parse_bytes(owned_bytes.clone(), Some(&digest)).unwrap();
        match (&owned_bytes, owned.data.as_ref().unwrap()) {
            (Bytes::Owned(before), Bytes::Owned(after)) => assert!(Arc::ptr_eq(before, after)),
            _ => panic!("owned bytes replaced"),
        }
        let start = 17;
        let end = start + bytes.len();
        let mut map = memmap2::MmapMut::map_anon(end + 13).unwrap();
        map[start..end].copy_from_slice(&bytes);
        let map = Arc::new(map.make_read_only().unwrap());
        let mapped = CallSiteOrdinals::parse_bytes(
            Bytes::Mapped {
                map: map.clone(),
                start,
                end,
            },
            Some(&digest),
        )
        .unwrap();
        match mapped.data.as_ref().unwrap() {
            Bytes::Mapped { map: retained, .. } => assert!(Arc::ptr_eq(&map, retained)),
            _ => panic!("mapped bytes replaced"),
        }
        let clone = mapped.clone();
        assert!(Arc::ptr_eq(
            mapped.ordinal_index.as_ref().unwrap(),
            clone.ordinal_index.as_ref().unwrap()
        ));
        bytes.fill(0);
        drop(bytes);
        drop(mapped);
        drop(map);
        drop(owned_bytes);
        assert_eq!(clone, borrowed);
        assert_eq!(clone, owned);
        assert_eq!(clone.get(64), Some(-65));
        assert_eq!(clone.get(256), Some(-257));
        assert_eq!(clone.get(320), None);
        assert_eq!(clone.origin(256), Some(0));
        assert_eq!(clone.derived_from(0).collect::<Vec<_>>(), vec![64, 256]);
    }

    #[test]
    fn mapped_ordinal_bytes_count_only_the_retained_entry_range() {
        assert_eq!(CallSiteOrdinals::default().mapped_bytes(), 0);
        for pairs in [Vec::new(), (0..320).map(|id| (id, -id - 1)).collect()] {
            let (bytes, digest) = encoded(&pairs, &[(256, 0)]);
            let borrowed = CallSiteOrdinals::parse(&bytes, Some(&digest)).unwrap();
            let owned =
                CallSiteOrdinals::parse_bytes(Bytes::owned(bytes.clone()), Some(&digest)).unwrap();
            assert_eq!(borrowed.mapped_bytes(), 0);
            assert_eq!(owned.mapped_bytes(), 0);

            let start = 17;
            let end = start + bytes.len();
            let mut map = memmap2::MmapMut::map_anon(end + 4096).unwrap();
            map[start..end].copy_from_slice(&bytes);
            let map = Arc::new(map.make_read_only().unwrap());
            let range = Bytes::Mapped { map, start, end };
            let mapped = CallSiteOrdinals::parse_bytes(range.clone(), Some(&digest)).unwrap();
            assert_eq!(mapped.mapped_bytes(), bytes.len() as u64);
            assert_eq!(mapped, borrowed);
            let clone = mapped.clone();
            drop(mapped);
            assert_eq!(clone.mapped_bytes(), bytes.len() as u64);
            assert_eq!(clone.get(0), pairs.first().map(|pair| pair.1));
            assert_eq!(clone.origin(256), Some(0));
            assert_eq!(clone.derived_from(0).collect::<Vec<_>>(), vec![256]);
            assert_eq!(
                CallSiteOrdinals::parse_bytes(range.clone(), Some(&[0u8; 32]))
                    .unwrap_or_default()
                    .mapped_bytes(),
                0
            );
            assert_eq!(
                CallSiteOrdinals::parse_bytes(range, None)
                    .unwrap_or_default()
                    .mapped_bytes(),
                0
            );
        }
        let (bytes, digest) = encoded(&[], &[]);
        let mut map = memmap2::MmapMut::map_anon(bytes.len()).unwrap();
        map.copy_from_slice(&bytes);
        let empty = CallSiteOrdinals::parse_bytes(
            Bytes::whole(map.make_read_only().unwrap()),
            Some(&digest),
        )
        .unwrap();
        assert_eq!(empty, CallSiteOrdinals::default());
        assert_eq!(empty.mapped_bytes(), bytes.len() as u64);
    }

    #[test]
    fn ordinal_bitmap_never_bypasses_late_block_or_rebound_head_validation() {
        let pairs: Vec<_> = (0..513).map(|id| (id, -id - 1)).collect();
        let origins: Vec<_> = (0..256).map(|id| (id + 1000, 4)).collect();
        let (bytes, digest) = encoded(&pairs, &origins);
        let valid = CallSiteOrdinals::parse(&bytes, Some(&digest)).unwrap();
        assert!(valid.ordinal_index.is_some());
        let entries_at =
            bytes.len() - (pairs.len() + origins.len()) * CALL_SITE_ORDINAL_ENTRY_BYTES;
        for offset in [
            entries_at + 512 * CALL_SITE_ORDINAL_ENTRY_BYTES + 7,
            bytes.len() - 1,
        ] {
            let mut corrupt = bytes.clone();
            corrupt[offset] ^= 1;
            assert_rejected_by_both(&corrupt, Some(&digest));
        }
        let mut head = bytes;
        let last_head = CALL_SITE_ORDINALS_PREAMBLE_BYTES + 8 + 2 * 4;
        head[last_head + 3] ^= 1;
        let rebound: [u8; 32] =
            Sha256::digest(&head[CALL_SITE_ORDINALS_PREAMBLE_BYTES..entries_at]).into();
        head[4..CALL_SITE_ORDINALS_PREAMBLE_BYTES].copy_from_slice(&rebound);
        assert_rejected_by_both(&head, Some(&rebound));
    }

    #[test]
    fn borrowed_ordinals_keep_an_independent_snapshot() {
        let (mut bytes, digest) = encoded(&[(4, -7), (12, i32::MIN)], &[(12, 4)]);
        let sidecar = CallSiteOrdinals::parse(&bytes, Some(&digest)).unwrap();
        bytes.fill(0);
        drop(bytes);
        let clone = sidecar.clone();
        drop(sidecar);
        assert_eq!(clone.len(), 2);
        assert_eq!(clone.get(4), Some(-7));
        assert_eq!(clone.get(12), Some(i32::MIN));
        assert_eq!(clone.get(5), None);
        assert_eq!(clone.origin(12), Some(4));
        assert_eq!(clone.derived_from(4).collect::<Vec<_>>(), vec![12]);
    }

    #[test]
    fn consuming_ordinals_retain_owned_and_offset_mapped_storage() {
        use std::sync::Arc;

        let (bytes, digest) = encoded(&[(4, -7), (12, 2)], &[(12, 4)]);
        let owned = Bytes::owned(bytes.clone());
        let sidecar = CallSiteOrdinals::parse_bytes(owned.clone(), Some(&digest)).unwrap();
        match (&owned, sidecar.data.as_ref().unwrap()) {
            (Bytes::Owned(before), Bytes::Owned(after)) => assert!(Arc::ptr_eq(before, after)),
            _ => panic!("consuming parser replaced owned storage"),
        }
        // A container entry need not begin at map offset zero. An anonymous readonly map
        // exercises the same range ownership without modifying a mapped file in a test.
        let start = 17;
        let end = start + bytes.len();
        let mut map = memmap2::MmapMut::map_anon(end + 13).unwrap();
        map[start..end].copy_from_slice(&bytes);
        let map = Arc::new(map.make_read_only().unwrap());
        let mapped = CallSiteOrdinals::parse_bytes(
            Bytes::Mapped {
                map: map.clone(),
                start,
                end,
            },
            Some(&digest),
        )
        .unwrap();
        match mapped.data.as_ref().unwrap() {
            Bytes::Mapped { map: retained, .. } => assert!(Arc::ptr_eq(&map, retained)),
            _ => panic!("consuming parser copied mapped storage"),
        }
        assert_eq!(mapped, sidecar);
        let clone = mapped.clone();
        drop(mapped);
        drop(map);
        drop(owned);
        assert_eq!(clone.get(4), Some(-7));
        assert_eq!(clone.origin(12), Some(4));
        assert_eq!(clone.derived_from(4).collect::<Vec<_>>(), vec![12]);
        assert_eq!(clone, sidecar);
    }

    #[test]
    fn ordinal_equality_compares_logical_entries_including_origins_only() {
        let (bytes, digest) = encoded(&[], &[]);
        let empty = CallSiteOrdinals::parse_bytes(Bytes::owned(bytes), Some(&digest)).unwrap();
        assert_eq!(empty, CallSiteOrdinals::default());
        assert!(empty.is_empty());
        assert_eq!(empty.len(), 0);
        assert_eq!(empty.get(0), None);
        assert_eq!(empty.origin(0), None);
        assert_eq!(empty.derived_from(0).count(), 0);

        let (bytes, digest) = encoded(&[], &[(7, 4), (9, 4), (9, 4), (15, 8)]);
        let origins = CallSiteOrdinals::parse(&bytes, Some(&digest)).unwrap();
        assert!(origins.is_empty());
        assert_eq!(origins.len(), 0);
        assert_eq!(origins.get(7), None);
        assert_eq!(origins.origin(7), Some(4));
        assert_eq!(origins.derived_from(4).collect::<Vec<_>>(), vec![7, 9, 9]);
        assert_ne!(origins, empty);
        assert_eq!(origins, origins.clone());
        let (bytes, digest) = encoded(&[], &[(7, 4), (9, 4), (9, 8), (15, 8)]);
        let changed = CallSiteOrdinals::parse(&bytes, Some(&digest)).unwrap();
        assert_ne!(origins, changed);
    }

    fn assert_lookup_matches_columns(pairs: &[(i32, i32)], origins: &[(i32, i32)]) {
        let (bytes, digest) = encoded(pairs, origins);
        let borrowed = CallSiteOrdinals::parse(&bytes, Some(&digest)).unwrap();
        let retained = CallSiteOrdinals::parse_bytes(Bytes::owned(bytes), Some(&digest)).unwrap();
        assert_eq!(borrowed, retained);
        let ids: Vec<u32> = pairs.iter().map(|p| p.0 as u32).collect();
        let origin_ids: Vec<u32> = origins.iter().map(|p| p.0 as u32).collect();
        // The old four-column implementation uses std binary_search, including its
        // behavior on duplicate or unsorted IDs; parsing has never rejected those.
        for id in (0..1600).chain([i32::MAX as u32, u32::MAX]) {
            let ordinal = ids.binary_search(&id).ok().map(|i| pairs[i].1);
            let origin = origin_ids
                .binary_search(&id)
                .ok()
                .map(|i| origins[i].1 as u32);
            assert_eq!(retained.get(id), ordinal, "ordinal for {id}");
            assert_eq!(retained.origin(id), origin, "origin for {id}");
            let derived: Vec<u32> = origins
                .iter()
                .filter(|p| p.1 as u32 == id)
                .map(|p| p.0 as u32)
                .collect();
            assert_eq!(retained.derived_from(id).collect::<Vec<_>>(), derived);
        }
    }

    #[test]
    fn retained_lookup_matches_columns_across_blocks_and_accepted_id_orders() {
        for count in [0, 1, 255, 256, 257, 511, 512, 513] {
            let pairs: Vec<_> = (0..count).map(|i| (i * 3, -i - 1)).collect();
            let origins: Vec<_> = (0..count).map(|i| (i * 3 + 1, (i % 3) * 3)).collect();
            assert_lookup_matches_columns(&pairs, &origins);
        }
        assert_lookup_matches_columns(
            &[(4, i32::MIN), (4, -1), (12, 2), (i32::MAX, 7), (-1, 8)],
            &[(9, 4), (9, 12), (15, 4), (-1, -1)],
        );
        assert_lookup_matches_columns(&[(12, -8), (4, 9), (4, -2)], &[(9, 4), (7, 4), (9, 4)]);
        let (bytes, digest) = encoded(&[], &[(9, 4), (7, 4), (9, 4)]);
        let sidecar = CallSiteOrdinals::parse(&bytes, Some(&digest)).unwrap();
        assert_eq!(sidecar.derived_from(4).collect::<Vec<_>>(), vec![9, 7, 9]);
    }

    fn assert_rejected_by_both(bytes: &[u8], binding: Option<&[u8; 32]>) {
        assert!(CallSiteOrdinals::parse(bytes, binding).is_none());
        assert!(CallSiteOrdinals::parse_bytes(Bytes::owned(bytes.to_vec()), binding).is_none());
    }

    #[test]
    fn retained_ordinals_eagerly_validate_all_blocks_heads_and_boundaries() {
        let pairs: Vec<_> = (0..257).map(|i| (i * 3, -i)).collect();
        let origins: Vec<_> = (0..256).map(|i| (i * 3 + 1, 4)).collect();
        let (bytes, digest) = encoded(&pairs, &origins);
        let entries_at = bytes.len() - 513 * CALL_SITE_ORDINAL_ENTRY_BYTES;
        // Corrupt the ordinal block, the mixed ordinal/origin block, and the final
        // partial origin block. Even corruption in an entry never queried must fail.
        for offset in [
            0,
            CALL_SITE_ORDINAL_BLOCK_BYTES,
            2 * CALL_SITE_ORDINAL_BLOCK_BYTES,
        ] {
            let mut corrupt = bytes.clone();
            corrupt[entries_at + offset + 7] ^= 1;
            assert_rejected_by_both(&corrupt, Some(&digest));
        }
        let mut head = bytes.clone();
        head[CALL_SITE_ORDINALS_PREAMBLE_BYTES + 8 + 3] ^= 1;
        assert_rejected_by_both(&head, Some(&digest));
        // Rebind the modified index, so rejection must come from comparing its head
        // with the actual first entry, not from the preceding index hash check.
        let rebound: [u8; 32] =
            Sha256::digest(&head[CALL_SITE_ORDINALS_PREAMBLE_BYTES..entries_at]).into();
        head[4..CALL_SITE_ORDINALS_PREAMBLE_BYTES].copy_from_slice(&rebound);
        assert_rejected_by_both(&head, Some(&rebound));
        for end in [
            0,
            3,
            35,
            39,
            43,
            entries_at - 1,
            entries_at,
            bytes.len() - 1,
        ] {
            assert_rejected_by_both(&bytes[..end], Some(&digest));
        }
        let mut trailing = bytes.clone();
        trailing.push(0);
        assert_rejected_by_both(&trailing, Some(&digest));
        for at in [
            CALL_SITE_ORDINALS_PREAMBLE_BYTES,
            CALL_SITE_ORDINALS_PREAMBLE_BYTES + 4,
        ] {
            for count in [-1i32, i32::MAX] {
                let mut bad_count = bytes.clone();
                bad_count[at..at + 4].copy_from_slice(&count.to_be_bytes());
                assert_rejected_by_both(&bad_count, Some(&digest));
            }
        }
        assert_rejected_by_both(&bytes, Some(&[0; 32]));
        assert_rejected_by_both(&bytes, None);
        let mut wrong_header = bytes.clone();
        wrong_header[0] ^= 1;
        assert_rejected_by_both(&wrong_header, Some(&digest));
        let mut old_version = bytes;
        old_version[3] = CALL_SITE_ORDINALS_VERSION - 1;
        assert_rejected_by_both(&old_version, Some(&digest));
        // Graph loading still treats an absent binding or unsupported sidecar as
        // absent; older node records continue to carry ordinal=None in the test above.
        let fallback = CallSiteOrdinals::parse_bytes(Bytes::owned(old_version), Some(&digest))
            .unwrap_or_default();
        assert_eq!(fallback, CallSiteOrdinals::empty());
        assert_eq!(fallback.get(4), None);
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

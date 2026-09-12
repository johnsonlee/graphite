//! Node model and `graph.nodedata` record decoding.

use crate::io::{Cursor, Truncated};
use crate::strings::StringTable;

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

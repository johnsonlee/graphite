//! Versioned, language-neutral frontend interchange and persisted graph writer.
//!
//! Import is intentionally separate from graph loading: existing readers and graph
//! files retain their format. Nodes use dense IDs and at most one edge per pair,
//! matching the simple directed WebGraph representation.
use crate::graph::{CONTROL_FLOW_KINDS, DATAFLOW_KINDS, TYPE_KINDS};
use crate::io::*;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::io::Read;
use std::path::Path;

#[derive(Debug, thiserror::Error)]
pub enum ImportError {
    #[error("invalid frontend interchange JSON: {0}")]
    Json(#[from] serde_json::Error),
    #[error("invalid frontend interchange: {0}")]
    Invalid(String),
    #[error("persisting frontend graph: {0}")]
    Io(#[from] std::io::Error),
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Method {
    declaring_class: String,
    name: String,
    parameter_types: Vec<String>,
    return_type: String,
}

#[derive(Debug, Deserialize, Serialize)]
struct Record {
    id: u32,
    #[serde(flatten)]
    node: InputNode,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(tag = "kind", deny_unknown_fields)]
enum InputNode {
    IntConstant {
        value: i32,
    },
    StringConstant {
        value: String,
    },
    DoubleConstant {
        value: f64,
    },
    BooleanConstant {
        value: bool,
    },
    NullConstant,
    #[serde(rename_all = "camelCase")]
    LocalVariable {
        name: String,
        var_type: String,
        method: Method,
    },
    #[serde(rename_all = "camelCase")]
    Field {
        declaring_class: String,
        name: String,
        field_type: String,
        is_static: bool,
    },
    #[serde(rename_all = "camelCase")]
    Parameter {
        index: i32,
        param_type: String,
        method: Method,
    },
    #[serde(rename_all = "camelCase")]
    Return {
        method: Method,
        actual_type: Option<String>,
    },
    CallSite {
        caller: Method,
        callee: Method,
        line: Option<i32>,
        receiver: Option<u32>,
        arguments: Vec<u32>,
    },
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct InputEdge {
    from: u32,
    to: u32,
    kind: String,
    #[serde(default)]
    is_virtual: bool,
    #[serde(default)]
    is_dynamic: bool,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct ClassOrigin {
    class_name: String,
    origin: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct InputGraph {
    version: u32,
    methods: Vec<Method>,
    nodes: Vec<Record>,
    edges: Vec<InputEdge>,
    #[serde(default)]
    class_origins: Vec<ClassOrigin>,
}

/// Validate a frontend JSON document, then create a new graph directory.
/// Existing destinations are never overwritten. Invalid input creates no files;
/// an I/O error removes the incomplete directory created by this invocation.
pub fn import_json(reader: impl Read, output: &Path) -> Result<(), ImportError> {
    let graph: InputGraph = serde_json::from_reader(reader)?;
    validate(&graph)?;
    let files = encode(&graph)?;
    std::fs::create_dir(output)?;
    let result = files
        .into_iter()
        .try_for_each(|(name, bytes)| std::fs::write(output.join(name), bytes));
    if result.is_err() {
        let _ = std::fs::remove_dir_all(output);
    }
    result.map_err(ImportError::Io)
}

fn invalid(message: impl Into<String>) -> ImportError {
    ImportError::Invalid(message.into())
}
fn validate(g: &InputGraph) -> Result<(), ImportError> {
    if g.version != 1 {
        return Err(invalid(format!("unsupported version {}", g.version)));
    }
    if g.nodes.len() > (i32::MAX as usize - 8) / 8
        || g.edges.len() > i32::MAX as usize
        || g.methods.len() > i32::MAX as usize
        || g.class_origins.len() > i32::MAX as usize
    {
        return Err(invalid("graph exceeds signed 32-bit format limits"));
    }
    let valid_id = |id: u32| (id as usize) < g.nodes.len();
    for (id, record) in g.nodes.iter().enumerate() {
        if record.id as usize != id {
            return Err(invalid("node IDs must be dense and ordered from zero"));
        }
        match &record.node {
            InputNode::DoubleConstant { value } if !value.is_finite() => {
                return Err(invalid("non-finite constant"))
            }
            InputNode::Parameter { index, method, .. }
                if *index < 0 || *index as usize >= method.parameter_types.len() =>
            {
                return Err(invalid("parameter index outside method signature"))
            }
            InputNode::CallSite {
                receiver,
                arguments,
                line,
                ..
            } => {
                if receiver.is_some_and(|id| !valid_id(id))
                    || arguments.iter().any(|&id| !valid_id(id))
                {
                    return Err(invalid("call site refers to missing node"));
                }
                if line.is_some_and(|n| n < 1) {
                    return Err(invalid("source line must be positive"));
                }
            }
            _ => {}
        }
    }
    let mut pairs = HashSet::new();
    for e in &g.edges {
        if !valid_id(e.from) || !valid_id(e.to) {
            return Err(invalid("edge refers to missing node"));
        }
        if !pairs.insert((e.from, e.to)) {
            return Err(invalid("duplicate edge endpoints"));
        }
        edge_label(e)?;
    }
    Ok(())
}

fn edge_label(e: &InputEdge) -> Result<u8, ImportError> {
    if e.kind == "CALL" {
        return Ok(1 | (u8::from(e.is_virtual) << 3) | (u8::from(e.is_dynamic) << 4));
    }
    if e.is_virtual || e.is_dynamic {
        return Err(invalid("call flags on non-CALL edge"));
    }
    for (family, kinds) in [
        (0, DATAFLOW_KINDS.as_slice()),
        (2, TYPE_KINDS.as_slice()),
        (3, CONTROL_FLOW_KINDS.as_slice()),
    ] {
        if let Some(kind) = kinds.iter().position(|&k| k == e.kind) {
            return Ok(family | ((kind as u8) << 3));
        }
    }
    Err(invalid(format!("unknown edge kind {}", e.kind)))
}

fn int(out: &mut Vec<u8>, n: usize) {
    out.extend((n as i32).to_be_bytes());
}
fn signed(out: &mut Vec<u8>, n: i32) {
    out.extend(n.to_be_bytes());
}
fn header(magic: i32, count: usize) -> Vec<u8> {
    let mut out = (magic | 3).to_be_bytes().to_vec();
    int(&mut out, count);
    out
}
fn strings_in(v: &serde_json::Value, out: &mut Vec<String>) {
    match v {
        serde_json::Value::String(s) => out.push(s.clone()),
        serde_json::Value::Array(a) => a.iter().for_each(|v| strings_in(v, out)),
        serde_json::Value::Object(o) => o.values().for_each(|v| strings_in(v, out)),
        _ => {}
    }
}
fn method(out: &mut Vec<u8>, m: &Method, dictionary: &HashMap<&str, usize>) {
    int(out, dictionary[m.declaring_class.as_str()]);
    int(out, dictionary[m.name.as_str()]);
    int(out, m.parameter_types.len());
    for p in &m.parameter_types {
        int(out, dictionary[p.as_str()]);
    }
    int(out, dictionary[m.return_type.as_str()]);
}

type EncodedFiles = Vec<(&'static str, Vec<u8>)>;
fn encode(g: &InputGraph) -> Result<EncodedFiles, ImportError> {
    let mut strings = Vec::new();
    strings_in(&serde_json::to_value(&g.nodes)?, &mut strings);
    strings_in(&serde_json::to_value(&g.methods)?, &mut strings);
    strings_in(&serde_json::to_value(&g.class_origins)?, &mut strings);
    strings.sort_by(|a, b| a.encode_utf16().cmp(b.encode_utf16()));
    strings.dedup();
    if strings.len() > i32::MAX as usize || strings.iter().any(|s| s.len() > i32::MAX as usize) {
        return Err(invalid("string table exceeds format limits"));
    }
    // The Java big-array serializer below emits one segment. Reject oversized
    // dictionaries explicitly rather than writing a graph its JVM reader cannot use.
    let utf16_units: u64 = strings
        .iter()
        .map(|s| {
            let n = s.encode_utf16().count() as u64;
            n + if n >= 0x8000 { 2 } else { 1 }
        })
        .sum();
    let utf8_bytes: u64 = strings.iter().map(|s| s.len() as u64).sum();
    if utf16_units > (1 << 27) || utf8_bytes > u32::MAX as u64 {
        return Err(invalid("string table exceeds single-segment format limit"));
    }
    let dictionary: HashMap<&str, usize> = strings
        .iter()
        .enumerate()
        .map(|(i, s)| (s.as_str(), i))
        .collect();
    let sid = |out: &mut Vec<u8>, s: &str| int(out, dictionary[s]);
    let mut data = header(MAGIC_NODEDATA, g.nodes.len());
    let mut offsets = header(MAGIC_NODEOFFSETS, g.nodes.len());
    let mut index = header(MAGIC_NODEINDEX, g.nodes.len());
    let mut tags = vec![Vec::new(); crate::node::TAG_COUNT];
    for record in &g.nodes {
        let start = data.len() as i64;
        offsets.extend((start + 1).to_be_bytes());
        let tag = match record.node {
            InputNode::IntConstant { .. } => 0,
            InputNode::StringConstant { .. } => 1,
            InputNode::DoubleConstant { .. } => 4,
            InputNode::BooleanConstant { .. } => 5,
            InputNode::NullConstant => 6,
            InputNode::LocalVariable { .. } => 8,
            InputNode::Field { .. } => 9,
            InputNode::Parameter { .. } => 10,
            InputNode::Return { .. } => 11,
            InputNode::CallSite { .. } => 12,
        };
        tags[tag as usize].push(record.id);
        index.extend(record.id.to_be_bytes());
        index.push(tag);
        index.extend(start.to_be_bytes());
        data.extend(record.id.to_be_bytes());
        data.push(tag);
        match &record.node {
            InputNode::IntConstant { value } => signed(&mut data, *value),
            InputNode::StringConstant { value } => sid(&mut data, value),
            InputNode::DoubleConstant { value } => data.extend(value.to_be_bytes()),
            InputNode::BooleanConstant { value } => data.push(u8::from(*value)),
            InputNode::NullConstant => {}
            InputNode::LocalVariable {
                name,
                var_type,
                method: m,
            } => {
                sid(&mut data, name);
                sid(&mut data, var_type);
                method(&mut data, m, &dictionary);
            }
            InputNode::Field {
                declaring_class,
                name,
                field_type,
                is_static,
            } => {
                sid(&mut data, declaring_class);
                sid(&mut data, name);
                sid(&mut data, field_type);
                data.push(u8::from(*is_static));
            }
            InputNode::Parameter {
                index,
                param_type,
                method: m,
            } => {
                signed(&mut data, *index);
                sid(&mut data, param_type);
                method(&mut data, m, &dictionary);
            }
            InputNode::Return {
                method: m,
                actual_type,
            } => {
                method(&mut data, m, &dictionary);
                data.push(u8::from(actual_type.is_some()));
                if let Some(t) = actual_type {
                    sid(&mut data, t);
                }
            }
            InputNode::CallSite {
                caller,
                callee,
                line,
                receiver,
                arguments,
            } => {
                method(&mut data, caller, &dictionary);
                method(&mut data, callee, &dictionary);
                signed(&mut data, line.unwrap_or(-1));
                signed(&mut data, receiver.map_or(-1, |n| n as i32));
                int(&mut data, arguments.len());
                for id in arguments {
                    data.extend(id.to_be_bytes());
                }
            }
        }
    }
    let mut type_index = header(MAGIC_TYPEINDEX, tags.len());
    let mut at = 8 + tags.len() * 13;
    for (tag, ids) in tags.iter().enumerate() {
        type_index.push(tag as u8);
        int(&mut type_index, ids.len());
        type_index.extend((at as i64).to_be_bytes());
        at += ids.len() * 4;
    }
    for ids in tags {
        for id in ids {
            type_index.extend(id.to_be_bytes());
        }
    }
    let mut metadata = header(MAGIC_METADATA, g.methods.len());
    for m in &g.methods {
        method(&mut metadata, m, &dictionary);
    }
    for _ in 0..3 {
        int(&mut metadata, 0);
    } // super/subtypes, enums
    int(&mut metadata, g.class_origins.len());
    for origin in &g.class_origins {
        sid(&mut metadata, &origin.class_name);
        sid(&mut metadata, &origin.origin);
    }
    for _ in 0..3 {
        int(&mut metadata, 0);
    } // artifact dependencies, annotations, branches
    let mut adjacency = vec![Vec::new(); g.nodes.len()];
    for e in &g.edges {
        adjacency[e.from as usize].push((e.to, edge_label(e)?));
    }
    let mut bits = Bits::default();
    let mut offset_bits = Bits::default();
    let mut labels = Vec::new();
    let mut previous = 0;
    for (from, targets) in adjacency.iter_mut().enumerate() {
        offset_bits.gamma(bits.len - previous);
        previous = bits.len;
        targets.sort_unstable();
        bits.gamma(targets.len() as u64);
        let mut last = 0;
        for (i, &(to, label)) in targets.iter().enumerate() {
            let residual = if i == 0 {
                let delta = to as i64 - from as i64;
                if delta >= 0 {
                    (delta * 2) as u64
                } else {
                    (-delta * 2 - 1) as u64
                }
            } else {
                u64::from(to - last - 1)
            };
            bits.gamma(residual);
            last = to;
            labels.push(label);
        }
    }
    offset_bits.gamma(bits.len - previous);
    let properties=format!("graphclass=it.unimi.dsi.webgraph.BVGraph\nversion=0\nnodes={}\narcs={}\nwindowsize=0\nmaxrefcount=0\nminintervallength=0\nzetak=3\ncompressionflags=OUTDEGREES_GAMMA | RESIDUALS_GAMMA | OFFSETS_GAMMA\n",g.nodes.len(),g.edges.len());
    // Mappable graph/labels files cannot be empty, even for a graph without arcs.
    if bits.bytes.is_empty() {
        bits.bytes.push(0);
    }
    if labels.is_empty() {
        labels.push(0);
    }
    Ok(vec![
        ("graph.nodedata", data),
        ("graph.nodeoffsets", offsets),
        ("graph.nodeindex", index),
        ("graph.typeindex", type_index),
        ("graph.metadata", metadata),
        ("graph.strings", encode_strings(&strings)),
        ("graph.labels", labels),
        ("forward.graph", bits.bytes),
        ("forward.offsets", offset_bits.bytes),
        ("forward.properties", properties.into_bytes()),
    ])
}

#[derive(Default)]
struct Bits {
    bytes: Vec<u8>,
    len: u64,
}
impl Bits {
    fn bit(&mut self, bit: bool) {
        if self.len.is_multiple_of(8) {
            self.bytes.push(0);
        }
        if bit {
            *self.bytes.last_mut().unwrap() |= 1 << (7 - self.len % 8);
        }
        self.len += 1;
    }
    fn gamma(&mut self, n: u64) {
        let value = n + 1;
        let width = 64 - value.leading_zeros();
        for _ in 1..width {
            self.bit(false);
        }
        for shift in (0..width).rev() {
            self.bit((value >> shift) & 1 == 1);
        }
    }
}

// Java Object Serialization descriptors match dsiutils FrontCodedStringList and
// fastutil CharArrayFrontCodedList (serialVersionUID 1). Ratio 1 stores complete
// UTF-16 strings, preserving Java comparison order and supplementary characters.
fn encode_strings(strings: &[String]) -> Vec<u8> {
    fn utf(out: &mut Vec<u8>, text: &str) {
        out.extend((text.len() as u16).to_be_bytes());
        out.extend(text.as_bytes());
    }
    fn class(out: &mut Vec<u8>, name: &str, uid: i64, fields: &[(u8, &str, Option<&str>)]) {
        out.push(0x72);
        utf(out, name);
        out.extend(uid.to_be_bytes());
        out.push(2);
        out.extend((fields.len() as u16).to_be_bytes());
        for &(kind, name, signature) in fields {
            out.push(kind);
            utf(out, name);
            if let Some(signature) = signature {
                out.push(0x74);
                utf(out, signature);
            }
        }
        out.extend([0x78, 0x70]);
    }
    let mut out = vec![0xac, 0xed, 0, 5, 0x73];
    class(
        &mut out,
        "it.unimi.dsi.util.FrontCodedStringList",
        1,
        &[
            (b'Z', "utf8", None),
            (
                b'L',
                "byteFrontCodedList",
                Some("Lit/unimi/dsi/fastutil/bytes/ByteArrayFrontCodedList;"),
            ),
            (
                b'L',
                "charFrontCodedList",
                Some("Lit/unimi/dsi/fastutil/chars/CharArrayFrontCodedList;"),
            ),
        ],
    );
    out.extend([0, 0x70, 0x73]);
    class(
        &mut out,
        "it.unimi.dsi.fastutil.chars.CharArrayFrontCodedList",
        1,
        &[
            (b'I', "n", None),
            (b'I', "ratio", None),
            (b'[', "array", Some("[[C")),
        ],
    );
    int(&mut out, strings.len());
    int(&mut out, 1);
    out.push(0x75);
    class(&mut out, "[[C", -7479776706070730294, &[]);
    int(&mut out, 1);
    let mut chars = Vec::new();
    for s in strings {
        let units: Vec<_> = s.encode_utf16().collect();
        let len = units.len();
        if len >= 0x8000 {
            chars.push(((len >> 16) as u16) | 0x8000);
        }
        chars.push(len as u16);
        chars.extend(units);
    }
    out.push(0x75);
    class(&mut out, "[C", -5753798564021173076, &[]);
    int(&mut out, chars.len());
    for c in chars {
        out.extend(c.to_be_bytes());
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Graph, NodeKind};
    use serde_json::json;
    use std::sync::atomic::{AtomicU64, Ordering};
    struct Temp(std::path::PathBuf);
    impl Temp {
        fn new() -> Self {
            static SEQ: AtomicU64 = AtomicU64::new(0);
            let p = std::env::temp_dir().join(format!(
                "graphite-interchange-{}-{}",
                std::process::id(),
                SEQ.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir(&p).unwrap();
            Self(p)
        }
    }
    impl Drop for Temp {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
    fn fixture() -> serde_json::Value {
        let method = json!({"declaringClass":"src/测试.ts", "name":"emit", "parameterTypes":["string"], "returnType":"string"});
        json!({"version":1,"methods":[method],"classOrigins":[{"className":"src/测试.ts","origin":"example"}],"nodes":[
            {"id":0,"kind":"StringConstant","value":"hello 🌍\u{0000}"},
            {"id":1,"kind":"Parameter","index":0,"paramType":"string","method":method},
            {"id":2,"kind":"Return","method":method,"actualType":"string"},
            {"id":3,"kind":"CallSite","caller":method,"callee":method,"line":17,"receiver":null,"arguments":[0]},
            {"id":4,"kind":"LocalVariable","name":"message","varType":"string","method":method},
            {"id":5,"kind":"Field","declaringClass":"src/测试.ts","name":"field","fieldType":"boolean","isStatic":true},
            {"id":6,"kind":"IntConstant","value":-42},
            {"id":7,"kind":"DoubleConstant","value":1.25},
            {"id":8,"kind":"BooleanConstant","value":true},
            {"id":9,"kind":"NullConstant"}],"edges":[
            {"from":0,"to":1,"kind":"PARAMETER_PASS"},
            {"from":1,"to":2,"kind":"ASSIGN"},
            {"from":2,"to":3,"kind":"RETURN_VALUE"},
            {"from":3,"to":3,"kind":"CALL","isDynamic":true},
            {"from":3,"to":0,"kind":"ASSIGN"},
            {"from":3,"to":4,"kind":"ASSIGN"}]})
    }
    fn assert_graph(g: &Graph) {
        assert_eq!(g.node_count(), 10);
        assert_eq!(g.forward.neighbors(3), (&[0, 3, 4][..], &[0, 17, 0][..]));
        assert_eq!(g.backward.neighbors(3), (&[2, 3][..], &[16, 17][..]));
        let NodeKind::StringConstant(s) = g.node(0).unwrap().kind else {
            panic!("string expected")
        };
        assert_eq!(g.str(s), "hello 🌍\u{0000}");
        let NodeKind::Parameter {
            index,
            param_type,
            method,
        } = g.node(1).unwrap().kind
        else {
            panic!("parameter expected")
        };
        assert_eq!(index, 0);
        assert_eq!(g.str(param_type), "string");
        assert_eq!(method.signature(&g.strings), "src/测试.ts.emit(string)");
        let NodeKind::CallSite {
            caller,
            callee,
            line,
            receiver,
            arguments,
            ordinal,
        } = g.node(3).unwrap().kind
        else {
            panic!("call expected")
        };
        assert_eq!(caller, callee);
        assert_eq!(line, Some(17));
        assert_eq!(receiver, None);
        assert_eq!(arguments, vec![0]);
        assert_eq!(ordinal, None);
        assert_eq!(
            g.strings.index_of("src/测试.ts"),
            Some(caller.declaring_class as usize)
        );
        assert_eq!(g.ids_by_tag(12), &[3]);
        assert_eq!(g.metadata.methods, vec![caller]);
        let (class, origin) = g.metadata.class_origins[0];
        assert_eq!(g.str(class), "src/测试.ts");
        assert_eq!(g.str(origin), "example");
        let NodeKind::Return {
            method: returned,
            actual_type,
        } = g.node(2).unwrap().kind
        else {
            panic!("return expected")
        };
        assert_eq!(returned, g.metadata.methods[0]);
        assert_eq!(actual_type.map(|id| g.str(id)), Some("string"));
        let NodeKind::LocalVariable {
            name,
            var_type,
            method: local,
        } = g.node(4).unwrap().kind
        else {
            panic!("local expected")
        };
        assert_eq!((g.str(name), g.str(var_type)), ("message", "string"));
        assert_eq!(local, returned);
        let NodeKind::Field {
            declaring_class,
            name,
            field_type,
            is_static,
        } = g.node(5).unwrap().kind
        else {
            panic!("field expected")
        };
        assert_eq!(
            (
                g.str(declaring_class),
                g.str(name),
                g.str(field_type),
                is_static
            ),
            ("src/测试.ts", "field", "boolean", true)
        );
        assert_eq!(g.node(6).unwrap().kind, NodeKind::IntConstant(-42));
        assert_eq!(g.node(7).unwrap().kind, NodeKind::DoubleConstant(1.25));
        assert_eq!(g.node(8).unwrap().kind, NodeKind::BooleanConstant(true));
        assert_eq!(g.node(9).unwrap().kind, NodeKind::NullConstant);
    }
    #[test]
    fn imports_real_storage_format_and_container_with_values_and_directed_edges() {
        let tmp = Temp::new();
        let dir = tmp.0.join("saved");
        import_json(fixture().to_string().as_bytes(), &dir).unwrap();
        assert_graph(&Graph::load(&dir).unwrap());
        let container = tmp.0.join("saved.graphite");
        crate::container::pack(&dir, &container).unwrap();
        assert_graph(&Graph::load(&container).unwrap());
    }
    #[test]
    fn invalid_input_never_publishes_a_graph_or_overwrites_existing_output() {
        let tmp = Temp::new();
        let mut cases = Vec::new();
        let mut g = fixture();
        g["version"] = json!(2);
        cases.push(g);
        let mut g = fixture();
        g["nodes"][1]["id"] = json!(10);
        cases.push(g);
        let mut g = fixture();
        g["nodes"][3]["arguments"] = json!([99]);
        cases.push(g);
        let mut g = fixture();
        g["edges"][0]["to"] = json!(99);
        cases.push(g);
        let mut g = fixture();
        g["edges"][0]["kind"] = json!("GUESS");
        cases.push(g);
        let mut g = fixture();
        let duplicate = g["edges"][0].clone();
        g["edges"].as_array_mut().unwrap().push(duplicate);
        cases.push(g);
        let mut g = fixture();
        g["nodes"][1]["index"] = json!(2);
        cases.push(g);
        let mut g = fixture();
        g["edges"][0]["isDynamic"] = json!(true);
        cases.push(g);
        for g in cases {
            let out = tmp.0.join("invalid");
            assert!(import_json(g.to_string().as_bytes(), &out).is_err());
            assert!(!out.exists());
        }
        let out = tmp.0.join("existing");
        std::fs::create_dir(&out).unwrap();
        std::fs::write(out.join("sentinel"), b"keep").unwrap();
        assert!(import_json(fixture().to_string().as_bytes(), &out).is_err());
        assert_eq!(std::fs::read(out.join("sentinel")).unwrap(), b"keep");
    }
    #[test]
    fn every_edge_kind_round_trips_through_the_existing_decoder() {
        use crate::{Edge, EdgeFamily};
        for (family, kinds) in [
            (EdgeFamily::DataFlow, DATAFLOW_KINDS.as_slice()),
            (EdgeFamily::Type, TYPE_KINDS.as_slice()),
            (EdgeFamily::ControlFlow, CONTROL_FLOW_KINDS.as_slice()),
        ] {
            for &kind in kinds {
                let input = InputEdge {
                    from: 2,
                    to: 1,
                    kind: kind.into(),
                    is_virtual: false,
                    is_dynamic: false,
                };
                let edge = Edge {
                    from: 2,
                    to: 1,
                    label: edge_label(&input).unwrap(),
                    v2: false,
                };
                assert_eq!(edge.family(), family);
                assert_eq!(edge.kind_name(), Some(kind));
            }
        }
        for is_virtual in [false, true] {
            for is_dynamic in [false, true] {
                let input = InputEdge {
                    from: 2,
                    to: 2,
                    kind: "CALL".into(),
                    is_virtual,
                    is_dynamic,
                };
                let edge = Edge {
                    from: 2,
                    to: 2,
                    label: edge_label(&input).unwrap(),
                    v2: false,
                };
                assert_eq!(edge.family(), EdgeFamily::Call);
                assert_eq!(edge.is_virtual(), is_virtual);
                assert_eq!(edge.is_dynamic(), is_dynamic);
            }
        }
    }

    #[test]
    fn empty_graph_and_long_unicode_dictionary_round_trip() {
        let tmp = Temp::new();
        let empty = tmp.0.join("empty");
        import_json(
            br#"{"version":1,"methods":[],"nodes":[],"edges":[]}"#.as_slice(),
            &empty,
        )
        .unwrap();
        assert_eq!(Graph::load(&empty).unwrap().node_count(), 0);
        let strings = vec![
            String::new(),
            "\0".into(),
            "a".repeat(40000),
            "🌍".into(),
            "\u{e000}".into(),
        ];
        let table =
            crate::strings::StringTable::from_serialized(&encode_strings(&strings)).unwrap();
        for (i, s) in strings.iter().enumerate() {
            assert_eq!(table.get(i), s);
            assert_eq!(table.index_of(s), Some(i));
        }
    }
}

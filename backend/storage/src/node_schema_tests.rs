//! The Rust codecs of the node record against its one definition,
//! `backend/storage/schema/node-records.txt`: the reader (`Node::read`), the readers at fixed
//! byte offsets (`RAW_STRING_FIELDS`) and the writer of the JSON importer. The JVM codecs are
//! held to the same file in `NodeRecordSchemaTest.kt`, so a field added on one side and not the
//! other fails on the side that was left behind.
use super::*;
use std::collections::BTreeMap;

const SCHEMA: &str = include_str!("../schema/node-records.txt");

struct Schema {
    /// tag -> (kind name, fields as (name, encoding)).
    kinds: BTreeMap<u8, (String, Vec<(String, String)>)>,
    /// value tag -> the encodings after the tag byte.
    values: BTreeMap<u8, Vec<String>>,
    strings: Vec<String>,
    golden: BTreeMap<u8, Vec<u8>>,
}

fn schema() -> Schema {
    let mut schema = Schema {
        kinds: BTreeMap::new(),
        values: BTreeMap::new(),
        strings: Vec::new(),
        golden: BTreeMap::new(),
    };
    for line in SCHEMA.lines().map(str::trim) {
        let mut words = line.split(' ');
        match words.next() {
            Some("kind") => {
                let tag = words.next().unwrap().parse().unwrap();
                let name = words.next().unwrap().to_string();
                let fields = words
                    .map(|field| {
                        let (name, encoding) = field.split_once(':').unwrap();
                        (name.to_string(), encoding.to_string())
                    })
                    .collect();
                assert!(schema.kinds.insert(tag, (name, fields)).is_none());
            }
            Some("value") => {
                let tag = words.next().unwrap().parse().unwrap();
                words.next().unwrap();
                assert!(schema
                    .values
                    .insert(tag, words.map(str::to_string).collect())
                    .is_none());
            }
            Some("string") => {
                let id: usize = words.next().unwrap().parse().unwrap();
                assert_eq!(
                    id,
                    schema.strings.len(),
                    "string ids are dense and in order"
                );
                schema.strings.push(words.collect::<Vec<_>>().join(" "));
            }
            Some("golden") => {
                let tag = words.next().unwrap().parse().unwrap();
                let hex = words.next().unwrap();
                let bytes = (0..hex.len())
                    .step_by(2)
                    .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
                    .collect();
                assert!(schema.golden.insert(tag, bytes).is_none());
            }
            _ => assert!(line.is_empty() || line.starts_with('#'), "{line}"),
        }
    }
    schema
}

/// Reads a record by the schema alone: the reference every hand-written codec is compared to.
struct Walker<'a> {
    schema: &'a Schema,
    data: &'a [u8],
    pos: usize,
    strings: &'a dyn Fn(u32) -> String,
}

impl Walker<'_> {
    fn take<const N: usize>(&mut self) -> [u8; N] {
        let bytes = self.data[self.pos..self.pos + N].try_into().unwrap();
        self.pos += N;
        bytes
    }
    fn i32(&mut self) -> i32 {
        i32::from_be_bytes(self.take())
    }
    fn sid(&mut self) -> String {
        let id = self.i32();
        (self.strings)(u32::try_from(id).expect("a string id"))
    }
    fn value(&mut self) -> String {
        let tag = self.take::<1>()[0];
        let encodings = self.schema.values[&tag].clone();
        let parts: Vec<String> = encodings.iter().map(|e| self.field(e)).collect();
        format!("#{tag}({})", parts.join(","))
    }
    fn list(&mut self, mut item: impl FnMut(&mut Self) -> String) -> String {
        let n = self.i32();
        assert!(n >= 0, "a count");
        let items: Vec<String> = (0..n).map(|_| item(self)).collect();
        format!("[{}]", items.join(","))
    }
    /// The rendering of one field: string ids resolved, so two string tables compare.
    fn field(&mut self, encoding: &str) -> String {
        match encoding {
            "i32" => self.i32().to_string(),
            "i64" => i64::from_be_bytes(self.take()).to_string(),
            "f32" => format!("{:?}", f32::from_be_bytes(self.take())),
            "f64" => format!("{:?}", f64::from_be_bytes(self.take())),
            "bool" => match self.take::<1>()[0] {
                0 => "false".to_string(),
                1 => "true".to_string(),
                other => panic!("bool {other}"),
            },
            "sid" => self.sid(),
            "sid?" => match self.field("bool").as_str() {
                "true" => self.sid(),
                _ => "-".to_string(),
            },
            "i32?" => match self.i32() {
                -1 => "-".to_string(),
                n => n.to_string(),
            },
            "ids" => self.list(|w| w.i32().to_string()),
            "method" => {
                let (class, name) = (self.sid(), self.sid());
                let parameters = self.list(Self::sid);
                format!("{class}.{name}{parameters}:{}", self.sid())
            }
            "value" => self.value(),
            "values" => self.list(Self::value),
            "pairs" => self.list(|w| format!("{}={}", w.sid(), w.value())),
            other => panic!("the schema names an encoding this test does not know: {other}"),
        }
    }
}

/// (id, tag, fields as name=rendering) of the record at the start of `data`, and its length.
fn walk(
    schema: &Schema,
    data: &[u8],
    strings: &dyn Fn(u32) -> String,
) -> (i32, u8, Vec<String>, usize) {
    let mut walker = Walker {
        schema,
        data,
        pos: 0,
        strings,
    };
    let id = walker.i32();
    let tag = walker.take::<1>()[0];
    let fields = schema.kinds[&tag]
        .1
        .iter()
        .map(|(name, encoding)| format!("{name}={}", walker.field(encoding)))
        .collect();
    (id, tag, fields, walker.pos)
}

#[test]
fn the_schema_names_every_kind_by_the_tag_the_reader_knows() {
    let schema = schema();
    assert_eq!(
        schema.kinds.keys().copied().collect::<Vec<u8>>(),
        (0..TAG_COUNT as u8).collect::<Vec<u8>>()
    );
    for (tag, (name, _)) in &schema.kinds {
        assert_eq!(
            tag_type_name(*tag).trim_end_matches("Node"),
            name,
            "tag {tag}"
        );
    }
    assert_eq!(
        schema.golden.keys().collect::<Vec<_>>(),
        schema.kinds.keys().collect::<Vec<_>>(),
        "one golden record per kind"
    );
    assert_eq!(
        schema.values.keys().copied().collect::<Vec<u8>>(),
        (0..=8).collect::<Vec<u8>>()
    );
}

#[test]
fn the_reader_decodes_the_golden_record_of_every_kind() {
    let schema = schema();
    let sid = |text: &str| -> StrId {
        schema
            .strings
            .iter()
            .position(|s| s == text)
            .unwrap_or_else(|| panic!("the schema's string table lacks {text}")) as StrId
    };
    let method = |name: &str, parameters: &[&str], returns: &str| MethodDesc {
        declaring_class: sid("sample.Owner"),
        name: sid(name),
        parameter_types: parameters.iter().map(|p| sid(p)).collect(),
        return_type: sid(returns),
    };
    let run = || method("run", &["int", "java.lang.String"], "java.lang.String");
    // The values the schema file spells out, and NodeRecordSchemaTest.kt builds as JVM nodes.
    let expected = [
        NodeKind::IntConstant(-42),
        NodeKind::StringConstant(sid("hello")),
        NodeKind::LongConstant(1_234_567_890_123),
        NodeKind::FloatConstant(1.5),
        NodeKind::DoubleConstant(-2.25),
        NodeKind::BooleanConstant(true),
        NodeKind::NullConstant,
        NodeKind::EnumConstant {
            enum_type: sid("sample.Color"),
            enum_name: sid("RED"),
            args: vec![
                AnyValue::Int(7),
                AnyValue::Str(sid("label")),
                AnyValue::Null,
            ],
        },
        NodeKind::LocalVariable {
            name: sid("tmp"),
            var_type: sid("int"),
            method: run(),
        },
        NodeKind::Field {
            declaring_class: sid("sample.Owner"),
            name: sid("count"),
            field_type: sid("int"),
            is_static: true,
        },
        NodeKind::Parameter {
            index: 1,
            param_type: sid("java.lang.String"),
            method: run(),
        },
        NodeKind::Return {
            method: run(),
            actual_type: Some(sid("java.lang.String")),
        },
        NodeKind::CallSite {
            caller: run(),
            callee: method("helper", &[], "void"),
            line: Some(17),
            receiver: None,
            arguments: vec![3, 5],
            ordinal: None,
        },
        NodeKind::Annotation {
            name: sid("sample.Tag"),
            class_name: sid("sample.Owner"),
            member_name: sid("run"),
            values: vec![
                (sid("value"), AnyValue::Str(sid("x"))),
                (
                    sid("weights"),
                    AnyValue::List(vec![AnyValue::Long(1), AnyValue::Long(2)]),
                ),
                (
                    sid("level"),
                    AnyValue::EnumRef {
                        enum_class: sid("sample.Color"),
                        enum_name: sid("RED"),
                    },
                ),
            ],
        },
        NodeKind::ResourceValue {
            path: sid("app.yml"),
            key: sid("mode"),
            value: AnyValue::Bool(true),
            format: sid("yaml"),
            profile: Some(sid("dev")),
        },
        NodeKind::ResourceFile {
            path: sid("app.yml"),
            source: sid("classpath"),
            format: sid("yaml"),
            profile: None,
        },
        NodeKind::ClassConstant(sid("Lsample/Owner;")),
    ];
    assert_eq!(expected.len(), TAG_COUNT);
    let text = |id: u32| schema.strings[id as usize].clone();
    for (tag, kind) in expected.into_iter().enumerate() {
        let bytes = &schema.golden[&(tag as u8)];
        let node = Node::read(bytes, 0, 3).unwrap();
        assert_eq!(node.id as usize, tag, "a golden record's id is its tag");
        assert_eq!(node.tag() as usize, tag);
        assert_eq!(node.kind, kind, "{}", schema.kinds[&(tag as u8)].0);
        // The layout the schema declares is the layout of the bytes: it reads the whole record.
        let (id, read_tag, _, length) = walk(&schema, bytes, &text);
        assert_eq!(
            (id as usize, read_tag as usize, length),
            (tag, tag, bytes.len())
        );
        // A record cut short anywhere is refused, never decoded as something shorter.
        for cut in 0..bytes.len() {
            assert!(
                Node::read(&bytes[..cut], 0, 3).is_err(),
                "kind {tag} cut at {cut}"
            );
        }
    }
}

#[test]
fn the_fixed_offset_readers_point_at_the_fields_the_schema_puts_there() {
    let schema = schema();
    let width = |encoding: &str| match encoding {
        "i32" | "sid" | "f32" | "i32?" => Some(4),
        "i64" | "f64" => Some(8),
        "bool" => Some(1),
        _ => None,
    };
    for raw in crate::columns::RAW_STRING_FIELDS {
        let (kind, fields) = &schema.kinds[&raw.tag];
        let mut offset = Some(0usize);
        let mut found = None;
        for (name, encoding) in fields {
            if name == raw.property {
                found = Some((offset, encoding.as_str()));
                break;
            }
            offset = offset.zip(width(encoding)).map(|(at, w)| at + w);
        }
        assert_eq!(
            found,
            Some((Some(raw.offset), "sid")),
            "{kind}.{}: a string id at a fixed offset of {}",
            raw.property,
            raw.offset
        );
    }
}

#[test]
fn the_importer_writes_the_records_the_schema_defines() {
    let schema = schema();
    let run = serde_json::json!({"declaringClass": "sample.Owner", "name": "run",
        "parameterTypes": ["int", "java.lang.String"], "returnType": "java.lang.String"});
    let helper = serde_json::json!({"declaringClass": "sample.Owner", "name": "helper",
        "parameterTypes": [], "returnType": "void"});
    // The importer's kinds, with the golden records' values; its ids must be dense.
    let nodes = serde_json::json!([
        {"id": 0, "kind": "IntConstant", "value": -42},
        {"id": 1, "kind": "StringConstant", "value": "hello"},
        {"id": 2, "kind": "DoubleConstant", "value": -2.25},
        {"id": 3, "kind": "BooleanConstant", "value": true},
        {"id": 4, "kind": "NullConstant"},
        {"id": 5, "kind": "ClassConstant", "value": "Lsample/Owner;"},
        {"id": 6, "kind": "LocalVariable", "name": "tmp", "varType": "int", "method": run},
        {"id": 7, "kind": "Field", "declaringClass": "sample.Owner", "name": "count",
         "fieldType": "int", "isStatic": true},
        {"id": 8, "kind": "Parameter", "index": 1, "paramType": "java.lang.String", "method": run},
        {"id": 9, "kind": "Return", "method": run, "actualType": "java.lang.String"},
        {"id": 10, "kind": "CallSite", "caller": run, "callee": helper, "line": 17,
         "receiver": null, "arguments": [3, 5]}
    ]);
    let graph =
        serde_json::json!({"version": 1, "methods": [run, helper], "nodes": nodes, "edges": []});
    let dir = std::env::temp_dir().join(format!("graphite-node-schema-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    crate::interchange::import_json(graph.to_string().as_bytes(), &dir).unwrap();
    let loaded = crate::Graph::load(&dir).unwrap();
    let data = std::fs::read(dir.join("graph.nodedata")).unwrap();
    let offsets = std::fs::read(dir.join("graph.nodeoffsets")).unwrap();
    let imported = |id: u32| loaded.strings().get(id as usize).to_string();
    let golden = |id: u32| schema.strings[id as usize].clone();
    let mut kinds = Vec::new();
    for id in 0..11usize {
        let stored = i64::from_be_bytes(offsets[8 + id * 8..16 + id * 8].try_into().unwrap());
        let (read_id, tag, fields, _) = walk(&schema, &data[(stored - 1) as usize..], &imported);
        assert_eq!(read_id as usize, id);
        let (_, golden_tag, golden_fields, _) = walk(&schema, &schema.golden[&tag], &golden);
        assert_eq!(tag, golden_tag);
        assert_eq!(fields, golden_fields, "{}", schema.kinds[&tag].0);
        kinds.push(tag);
    }
    assert_eq!(kinds, [0, 1, 4, 5, 6, 16, 8, 9, 10, 11, 12]);
    drop(loaded);
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn the_required_files_are_the_ones_the_schema_lists() {
    let listed: Vec<&str> = include_str!("../schema/graph-files.txt")
        .lines()
        .filter_map(|line| line.trim().strip_prefix("required "))
        .collect();
    assert_eq!(listed, crate::container::REQUIRED_ENTRIES);
    assert_eq!(listed, crate::manifest::REQUIRED_FILES);
    // An optional entry is never also required, and every required file is a known graph file.
    for name in &listed {
        assert!(!crate::container::OPTIONAL_ENTRIES.contains(name), "{name}");
        assert!(crate::container::is_known_graph_file(name), "{name}");
    }
}

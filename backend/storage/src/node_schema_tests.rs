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
    /// value tag -> (its name, the encodings after the tag byte).
    values: BTreeMap<u8, (String, Vec<String>)>,
    strings: Vec<String>,
    /// node id -> the record.
    golden: BTreeMap<u32, Vec<u8>>,
}

fn schema() -> Schema {
    parse(SCHEMA)
}

fn parse(text: &str) -> Schema {
    let mut schema = Schema {
        kinds: BTreeMap::new(),
        values: BTreeMap::new(),
        strings: Vec::new(),
        golden: BTreeMap::new(),
    };
    for line in text.lines().map(str::trim) {
        let mut words = line.split(' ').filter(|word| !word.is_empty());
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
                let name = words.next().unwrap().to_string();
                assert!(schema
                    .values
                    .insert(tag, (name, words.map(str::to_string).collect()))
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
                let id = words.next().unwrap().parse().unwrap();
                let hex = words.next().unwrap();
                let bytes = (0..hex.len())
                    .step_by(2)
                    .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
                    .collect();
                assert!(schema.golden.insert(id, bytes).is_none());
            }
            _ => assert!(line.is_empty() || line.starts_with('#'), "{line}"),
        }
    }
    schema
}

/// Reads a record by the schema alone and renders every field as `name=value`, the value
/// typed by its encoding (`7`, `7L`, `0.5f`, `0.5d`, `"text"`, `-` for an absent optional),
/// so a schema that names or types a field otherwise renders otherwise. [`describe`] renders
/// the same from a decoded node's own fields.
struct Walker<'a> {
    schema: &'a Schema,
    data: &'a [u8],
    pos: usize,
    strings: &'a dyn Fn(u32) -> String,
}

type Read<T> = Result<T, String>;

impl Walker<'_> {
    fn take<const N: usize>(&mut self) -> Read<[u8; N]> {
        let bytes = self
            .data
            .get(self.pos..self.pos + N)
            .ok_or_else(|| format!("the record ends inside a field at byte {}", self.pos))?;
        self.pos += N;
        Ok(bytes.try_into().unwrap())
    }
    fn i32(&mut self) -> Read<i32> {
        Ok(i32::from_be_bytes(self.take()?))
    }
    fn sid(&mut self) -> Read<String> {
        let id = u32::try_from(self.i32()?).map_err(|_| "a negative string id".to_string())?;
        Ok(quoted(&(self.strings)(id)))
    }
    fn value(&mut self) -> Read<String> {
        let tag = self.take::<1>()?[0];
        let (name, encodings) = self
            .schema
            .values
            .get(&tag)
            .cloned()
            .ok_or_else(|| format!("no value {tag}"))?;
        let parts: Read<Vec<String>> = encodings.iter().map(|e| self.field(e)).collect();
        Ok(format!("{name}({})", parts?.join(",")))
    }
    fn list(&mut self, mut item: impl FnMut(&mut Self) -> Read<String>) -> Read<String> {
        let n = self.i32()?;
        if !(0..=1024).contains(&n) {
            return Err(format!("a count of {n}"));
        }
        let items: Read<Vec<String>> = (0..n).map(|_| item(self)).collect();
        Ok(format!("[{}]", items?.join(",")))
    }
    fn field(&mut self, encoding: &str) -> Read<String> {
        Ok(match encoding {
            "i32" => self.i32()?.to_string(),
            "i64" => format!("{}L", i64::from_be_bytes(self.take()?)),
            "f32" => format!("{:?}f", f32::from_be_bytes(self.take()?)),
            "f64" => format!("{:?}d", f64::from_be_bytes(self.take()?)),
            "bool" => match self.take::<1>()?[0] {
                0 => "false".to_string(),
                1 => "true".to_string(),
                other => return Err(format!("bool {other}")),
            },
            "sid" => self.sid()?,
            "sid?" => match self.field("bool")?.as_str() {
                "true" => self.sid()?,
                _ => "-".to_string(),
            },
            "i32?" => match self.i32()? {
                -1 => "-".to_string(),
                n => n.to_string(),
            },
            "ids" => self.list(|w| Ok(w.i32()?.to_string()))?,
            "method" => {
                let (class, name) = (self.sid()?, self.sid()?);
                let parameters = self.list(Self::sid)?;
                format!("{class}.{name}{parameters}:{}", self.sid()?)
            }
            "value" => self.value()?,
            "values" => self.list(Self::value)?,
            "pairs" => self.list(|w| Ok(format!("{}={}", w.sid()?, w.value()?)))?,
            other => return Err(format!("the schema names an unknown encoding: {other}")),
        })
    }
}

/// `id=`, `kind=` and the fields of the record that is the whole of `data`.
fn walk(schema: &Schema, data: &[u8], strings: &dyn Fn(u32) -> String) -> Read<Vec<String>> {
    let mut walker = Walker {
        schema,
        data,
        pos: 0,
        strings,
    };
    let id = walker.i32()?;
    let tag = walker.take::<1>()?[0];
    let (kind, fields) = schema.kinds.get(&tag).ok_or(format!("no kind {tag}"))?;
    let mut rendered = vec![format!("id={id}"), format!("kind={kind}")];
    for (name, encoding) in fields {
        rendered.push(format!("{name}={}", walker.field(encoding)?));
    }
    if walker.pos != data.len() {
        return Err(format!(
            "{} bytes follow the fields the schema gives {kind}",
            data.len() - walker.pos
        ));
    }
    Ok(rendered)
}

fn quoted(text: &str) -> String {
    format!("\"{text}\"")
}

/// What a record of `node` holds, written from the reader's own fields and the names the
/// schema is expected to use for them.
fn describe(node: &Node, strings: &dyn Fn(u32) -> String) -> Vec<String> {
    let text = |id: &StrId| quoted(&strings(*id));
    let optional_text = |id: &Option<StrId>| id.as_ref().map_or("-".to_string(), text);
    let method = |m: &MethodDesc| {
        let parameters: Vec<String> = m.parameter_types.iter().map(text).collect();
        format!(
            "{}.{}[{}]:{}",
            text(&m.declaring_class),
            text(&m.name),
            parameters.join(","),
            text(&m.return_type)
        )
    };
    fn tagged(value: &AnyValue, text: &dyn Fn(&StrId) -> String) -> String {
        match value {
            AnyValue::Int(n) => format!("Int({n})"),
            AnyValue::Long(n) => format!("Long({n}L)"),
            AnyValue::Str(id) => format!("String({})", text(id)),
            AnyValue::Float(x) => format!("Float({x:?}f)"),
            AnyValue::Double(x) => format!("Double({x:?}d)"),
            AnyValue::Bool(b) => format!("Boolean({b})"),
            AnyValue::Null => "Null()".to_string(),
            AnyValue::EnumRef {
                enum_class,
                enum_name,
            } => format!("EnumRef({},{})", text(enum_class), text(enum_name)),
            AnyValue::List(items) => {
                let items: Vec<String> = items.iter().map(|item| tagged(item, text)).collect();
                format!("List([{}])", items.join(","))
            }
        }
    }
    let field = |name: &str, value: String| format!("{name}={value}");
    let (kind, fields) = match &node.kind {
        NodeKind::IntConstant(n) => ("IntConstant", vec![field("value", n.to_string())]),
        NodeKind::StringConstant(id) => ("StringConstant", vec![field("value", text(id))]),
        NodeKind::LongConstant(n) => ("LongConstant", vec![field("value", format!("{n}L"))]),
        NodeKind::FloatConstant(x) => ("FloatConstant", vec![field("value", format!("{x:?}f"))]),
        NodeKind::DoubleConstant(x) => ("DoubleConstant", vec![field("value", format!("{x:?}d"))]),
        NodeKind::BooleanConstant(b) => ("BooleanConstant", vec![field("value", b.to_string())]),
        NodeKind::NullConstant => ("NullConstant", vec![]),
        NodeKind::ClassConstant(id) => ("ClassConstant", vec![field("value", text(id))]),
        NodeKind::EnumConstant {
            enum_type,
            enum_name,
            args,
        } => {
            let args: Vec<String> = args.iter().map(|arg| tagged(arg, &text)).collect();
            (
                "EnumConstant",
                vec![
                    field("enum_type", text(enum_type)),
                    field("name", text(enum_name)),
                    field("args", format!("[{}]", args.join(","))),
                ],
            )
        }
        NodeKind::LocalVariable {
            name,
            var_type,
            method: m,
        } => (
            "LocalVariable",
            vec![
                field("name", text(name)),
                field("type", text(var_type)),
                field("method", method(m)),
            ],
        ),
        NodeKind::Field {
            declaring_class,
            name,
            field_type,
            is_static,
        } => (
            "Field",
            vec![
                field("class", text(declaring_class)),
                field("name", text(name)),
                field("type", text(field_type)),
                field("is_static", is_static.to_string()),
            ],
        ),
        NodeKind::Parameter {
            index,
            param_type,
            method: m,
        } => (
            "Parameter",
            vec![
                field("index", index.to_string()),
                field("type", text(param_type)),
                field("method", method(m)),
            ],
        ),
        NodeKind::Return {
            method: m,
            actual_type,
        } => (
            "Return",
            vec![
                field("method", method(m)),
                field("actual_type", optional_text(actual_type)),
            ],
        ),
        NodeKind::CallSite {
            caller,
            callee,
            line,
            receiver,
            arguments,
            ordinal: _,
        } => {
            let arguments: Vec<String> = arguments.iter().map(u32::to_string).collect();
            (
                "CallSite",
                vec![
                    field("caller", method(caller)),
                    field("callee", method(callee)),
                    field("line", line.map_or("-".to_string(), |n| n.to_string())),
                    field(
                        "receiver",
                        receiver.map_or("-".to_string(), |n| n.to_string()),
                    ),
                    field("arguments", format!("[{}]", arguments.join(","))),
                ],
            )
        }
        NodeKind::Annotation {
            name,
            class_name,
            member_name,
            values,
        } => {
            let values: Vec<String> = values
                .iter()
                .map(|(key, value)| format!("{}={}", text(key), tagged(value, &text)))
                .collect();
            (
                "Annotation",
                vec![
                    field("name", text(name)),
                    field("class", text(class_name)),
                    field("member", text(member_name)),
                    field("values", format!("[{}]", values.join(","))),
                ],
            )
        }
        NodeKind::ResourceValue {
            path,
            key,
            value,
            format,
            profile,
        } => (
            "ResourceValue",
            vec![
                field("path", text(path)),
                field("key", text(key)),
                field("value", tagged(value, &text)),
                field("format", text(format)),
                field("profile", optional_text(profile)),
            ],
        ),
        NodeKind::ResourceFile {
            path,
            source,
            format,
            profile,
        } => (
            "ResourceFile",
            vec![
                field("path", text(path)),
                field("source", text(source)),
                field("format", text(format)),
                field("profile", optional_text(profile)),
            ],
        ),
    };
    let mut rendered = vec![format!("id={}", node.id), format!("kind={kind}")];
    rendered.extend(fields);
    rendered
}

/// The golden records as the reader's nodes, by id: the values the schema file spells out, and
/// NodeRecordSchemaTest.kt builds as JVM nodes.
fn expected(schema: &Schema) -> Vec<NodeKind> {
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
    let helper = || method("helper", &[], "void");
    let red = || AnyValue::EnumRef {
        enum_class: sid("sample.Color"),
        enum_name: sid("RED"),
    };
    vec![
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
            callee: helper(),
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
                (sid("level"), red()),
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
        // 17 and up: the variants.
        NodeKind::IntConstant(-1),
        NodeKind::EnumConstant {
            enum_type: sid("sample.Color"),
            enum_name: sid("RED"),
            args: vec![
                AnyValue::Int(7),
                AnyValue::Long(2),
                AnyValue::Str(sid("label")),
                AnyValue::Float(0.5),
                AnyValue::Double(1.0e10),
                AnyValue::Bool(false),
                AnyValue::Null,
                red(),
                AnyValue::List(vec![
                    AnyValue::Float(1.5),
                    AnyValue::List(vec![]),
                    AnyValue::Double(-2.25),
                ]),
            ],
        },
        NodeKind::Return {
            method: helper(),
            actual_type: None,
        },
        NodeKind::CallSite {
            caller: helper(),
            callee: run(),
            line: None,
            receiver: Some(9),
            arguments: vec![],
            ordinal: None,
        },
        NodeKind::ResourceValue {
            path: sid("app.yml"),
            key: sid("count"),
            value: AnyValue::Double(2.5),
            format: sid("yaml"),
            profile: None,
        },
        NodeKind::ResourceFile {
            path: sid("app.yml"),
            source: sid("classpath"),
            format: sid("yaml"),
            profile: Some(sid("dev")),
        },
        NodeKind::Annotation {
            name: sid("sample.Tag"),
            class_name: sid("sample.Owner"),
            member_name: sid("count"),
            values: vec![
                (sid("level"), AnyValue::Float(0.25)),
                (sid("mode"), AnyValue::Double(0.003)),
                (
                    sid("weights"),
                    AnyValue::List(vec![AnyValue::Bool(false), AnyValue::Null]),
                ),
                (sid("label"), AnyValue::List(vec![])),
            ],
        },
        NodeKind::BooleanConstant(false),
        NodeKind::Annotation {
            name: sid("sample.Tag"),
            class_name: sid("sample.Owner"),
            member_name: sid("run"),
            values: vec![],
        },
        NodeKind::Field {
            declaring_class: sid("sample.Owner"),
            name: sid("tmp"),
            field_type: sid("java.lang.String"),
            is_static: false,
        },
    ]
}

/// The first golden record whose fields, read by the schema `text`, are not the ones the node
/// it encodes has; `None` when the schema describes every record.
fn disagreement(text: &str) -> Option<String> {
    let schema = parse(text);
    let strings = |id: u32| schema.strings[id as usize].clone();
    for (id, kind) in expected(&schema).into_iter().enumerate() {
        let node = Node {
            id: id as NodeId,
            kind,
        };
        let expected = describe(&node, &strings);
        let actual = walk(&schema, &schema.golden[&(id as u32)], &strings)
            .unwrap_or_else(|unreadable| vec![format!("unreadable: {unreadable}")]);
        if actual != expected {
            return Some(format!(
                "golden {id}: the schema reads {actual:?}, the node is {expected:?}"
            ));
        }
    }
    None
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
    // Golden ids are dense, and the first record of a kind carries its tag as its id.
    assert_eq!(
        schema.golden.keys().copied().collect::<Vec<u32>>(),
        (0..schema.golden.len() as u32).collect::<Vec<u32>>()
    );
    for tag in schema.kinds.keys() {
        assert_eq!(schema.golden[&u32::from(*tag)][4], *tag);
    }
    let names: Vec<&str> = schema
        .values
        .values()
        .map(|(name, _)| name.as_str())
        .collect();
    assert_eq!(
        names,
        ["Int", "Long", "String", "Float", "Double", "Boolean", "Null", "EnumRef", "List"]
    );
    assert_eq!(
        schema.values.keys().copied().collect::<Vec<u8>>(),
        (0..=8).collect::<Vec<u8>>()
    );
}

#[test]
fn the_reader_decodes_every_golden_record() {
    let schema = schema();
    let expected = expected(&schema);
    assert_eq!(expected.len(), schema.golden.len());
    for (id, kind) in expected.into_iter().enumerate() {
        let bytes = &schema.golden[&(id as u32)];
        let node = Node::read(bytes, 0, 3).unwrap();
        assert_eq!(node.id as usize, id);
        assert_eq!(node.tag(), bytes[4]);
        assert_eq!(node.kind, kind, "golden {id}");
        // A record cut short anywhere is refused, never decoded as something shorter.
        for cut in 0..bytes.len() {
            assert!(
                Node::read(&bytes[..cut], 0, 3).is_err(),
                "golden {id} cut at {cut}"
            );
        }
    }
}

#[test]
fn the_schemas_fields_are_the_readers_by_name_type_and_value() {
    assert_eq!(disagreement(SCHEMA), None);
    // The golden records reach every tagged value and both cases of every optional field, or
    // a codec could get one wrong unseen.
    let schema = schema();
    let strings = |id: u32| schema.strings[id as usize].clone();
    let rendered: Vec<Vec<String>> = expected(&schema)
        .into_iter()
        .enumerate()
        .map(|(id, kind)| {
            let node = Node {
                id: id as NodeId,
                kind,
            };
            describe(&node, &strings)
        })
        .collect();
    for (name, _) in schema.values.values() {
        assert!(
            rendered
                .iter()
                .flatten()
                .any(|field| field.contains(&format!("{name}("))),
            "no golden record holds a tagged {name}"
        );
    }
    for (kind, fields) in schema.kinds.values() {
        for (name, _) in fields
            .iter()
            .filter(|(_, encoding)| encoding.ends_with('?'))
        {
            let absent = format!("{name}=-");
            let cases: std::collections::BTreeSet<bool> = rendered
                .iter()
                .filter(|row| row[1] == format!("kind={kind}"))
                .map(|row| row.contains(&absent))
                .collect();
            assert_eq!(
                cases.len(),
                2,
                "{kind}.{name}: an absent and a present case"
            );
        }
    }
}

#[test]
fn a_schema_that_names_or_types_a_field_otherwise_is_caught() {
    // Review of #229: these left every schema test green, because only a record's length was
    // compared and the importer's records were read through the same altered schema.
    let mutations = [
        // The same width, another type.
        ("LongConstant value:i64", "LongConstant value:f64"),
        ("DoubleConstant value:f64", "DoubleConstant value:i64"),
        ("FloatConstant value:f32", "FloatConstant value:i32"),
        ("StringConstant value:sid", "StringConstant value:i32"),
        ("IntConstant value:i32", "IntConstant value:i32?"),
        // The same encoding, the names exchanged.
        ("line:i32? receiver:i32?", "receiver:i32? line:i32?"),
        (
            "name:sid class:sid member:sid",
            "class:sid name:sid member:sid",
        ),
        (
            "path:sid source:sid format:sid",
            "path:sid format:sid source:sid",
        ),
        ("is_static:bool", "static:bool"),
        // The tagged values no record used to hold, and a name exchanged for another.
        ("value 3 Float f32", "value 3 Float i32"),
        ("value 4 Double f64", "value 4 Double i64"),
        ("value 1 Long i64", "value 1 Long f64"),
        ("value 5 Boolean bool", "value 5 Flag bool"),
        ("value 7 EnumRef sid sid", "value 7 EnumRef sid"),
        // A field more, a field less.
        (
            "ClassConstant value:sid",
            "ClassConstant value:sid flags:i32",
        ),
        ("actual_type:sid?", ""),
    ];
    for (from, to) in mutations {
        assert_eq!(SCHEMA.matches(from).count(), 1, "{from}");
        assert!(
            disagreement(&SCHEMA.replace(from, to)).is_some(),
            "a schema with `{to}` for `{from}` still describes every golden record"
        );
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
    // The importer's kinds, with the golden records' values; its ids must be dense. The last
    // three are the variants: no actual type, a receiver and no line, an instance field.
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
         "receiver": null, "arguments": [3, 5]},
        {"id": 11, "kind": "Return", "method": helper},
        {"id": 12, "kind": "CallSite", "caller": helper, "callee": run, "receiver": 9,
         "arguments": []},
        {"id": 13, "kind": "Field", "declaringClass": "sample.Owner", "name": "tmp",
         "fieldType": "java.lang.String", "isStatic": false}
    ]);
    // The golden record each imported node must equal, field for field.
    let golden_ids = [0, 1, 4, 5, 6, 16, 8, 9, 10, 11, 12, 19, 20, 26];
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
    let start = |id: usize| {
        (i64::from_be_bytes(offsets[8 + id * 8..16 + id * 8].try_into().unwrap()) - 1) as usize
    };
    let expected = expected(&schema);
    for (id, golden_id) in golden_ids.into_iter().enumerate() {
        // What the node must hold comes from the typed expectation, not from reading the
        // golden bytes through the schema under test; only the id differs.
        let mut wanted = describe(
            &Node {
                id: golden_id as NodeId,
                kind: expected[golden_id].clone(),
            },
            &golden,
        );
        wanted[0] = format!("id={id}");
        let end = if id + 1 < golden_ids.len() {
            start(id + 1)
        } else {
            data.len()
        };
        let record = &data[start(id)..end];
        assert_eq!(
            walk(&schema, record, &imported).unwrap(),
            wanted,
            "node {id}"
        );
        // And the reader agrees with the schema about the importer's bytes.
        let node = Node::read(record, 0, 3).unwrap();
        assert_eq!(describe(&node, &imported), wanted, "node {id}");
    }
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

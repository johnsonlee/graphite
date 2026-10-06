//! Correctness fixtures deliberately describe a language unknown to the codec.
//! No language-specific code or registry is installed to interpret these records.
use graphite_schema::*;
use std::collections::BTreeMap;

const NS: &str = "https://example.test/future-language";
const NODES: u32 = 10;
const SCOPES: u32 = 20;
const EDGES: u32 = 30;
const DATA: u32 = 40;
const SCOPE: u32 = 100;
const ENVELOPE: u32 = 101;
const PRIMITIVES: u32 = 102;

fn q(name: &str) -> QualifiedName {
    QualifiedName::new(NS, name)
}
fn field(name: &str, descriptor: Descriptor, required: bool, nullable: bool) -> Field {
    Field {
        name: q(name),
        descriptor,
        required,
        nullable,
        role: None,
    }
}
fn definition(name: &str, category: &str, fields: Vec<Field>) -> Definition {
    Definition {
        name: q(name),
        revision: "unreleased-42".into(),
        category: q(category),
        fields,
    }
}
fn reference(table: u32, row: u32) -> Reference {
    Reference { table, row }
}
fn r(table: u32, row: u32) -> Value {
    Value::Ref(reference(table, row))
}
fn envelope() -> Record {
    Record {
        layout: ENVELOPE,
        fields: vec![
            Some(Value::List(vec![
                Value::List(vec![r(NODES, 0), r(SCOPES, 1), r(NODES, 0)]),
                Value::List(vec![]),
            ])),
            Some(Value::String(2)),
            Some(Value::Float64(0x7ff8_0000_0000_0042)),
        ],
    }
}
fn fixture(label: &str) -> Document {
    let mut doc = Document::default();
    doc.strings.insert(1, "T".into());
    doc.strings.insert(2, format!("{label}:payload\0λ"));
    doc.profiles.push(Profile {
        name: q("future-profile"),
        revision: "2042-preview".into(),
    });
    for kind in 0..17 {
        doc.strings
            .insert(100 + kind, format!("{label}:declaration-{kind}"));
        doc.definitions.insert(
            1 + kind,
            definition(
                &format!("node-{kind}"),
                "node",
                vec![
                    field("name", Descriptor::String, true, false),
                    field("scope", Descriptor::Ref(Some(SCOPES)), true, false),
                    field("extension", Descriptor::Record, true, false),
                    field("optional", Descriptor::String, false, true),
                ],
            ),
        );
    }
    doc.definitions.insert(
        SCOPE,
        definition(
            "scope",
            "binding",
            vec![
                field("name", Descriptor::String, true, false),
                field("owner", Descriptor::Ref(Some(NODES)), true, false),
                field("parent", Descriptor::Ref(Some(SCOPES)), false, false),
            ],
        ),
    );
    let mut links = field(
        "links",
        Descriptor::List(Box::new(Descriptor::List(Box::new(Descriptor::Ref(None))))),
        true,
        false,
    );
    links.role = Some(q("ordered-operands"));
    doc.definitions.insert(
        ENVELOPE,
        definition(
            "future-nested-expression",
            "expression",
            vec![
                links,
                field("tag", Descriptor::String, true, false),
                field("nan", Descriptor::Float64, true, false),
            ],
        ),
    );
    doc.definitions.insert(
        PRIMITIVES,
        definition(
            "future-literal",
            "expression",
            vec![
                field("null", Descriptor::Null, true, true),
                field("bool", Descriptor::Bool, true, false),
                field("signed", Descriptor::Int64, true, false),
                field("unsigned", Descriptor::UInt64, true, false),
                field("float", Descriptor::Float64, true, false),
                field("string", Descriptor::String, true, false),
                field("bytes", Descriptor::Bytes, true, false),
                field(
                    "floats",
                    Descriptor::List(Box::new(Descriptor::Float64)),
                    true,
                    false,
                ),
                field("record", Descriptor::Record, true, false),
            ],
        ),
    );
    for predicate in 0..257 {
        doc.definitions.insert(
            1000 + predicate,
            definition(
                &format!("predicate-{predicate}"),
                "edge",
                vec![
                    field("source", Descriptor::Ref(Some(NODES)), true, false),
                    field("target", Descriptor::Ref(Some(NODES)), true, false),
                    field("ordinal", Descriptor::UInt64, true, false),
                ],
            ),
        );
    }
    let mut nodes = BTreeMap::new();
    for kind in 0..17 {
        nodes.insert(
            kind,
            Record {
                layout: 1 + kind,
                fields: vec![
                    Some(Value::String(100 + kind)),
                    Some(r(SCOPES, kind % 2)),
                    Some(Value::Record(Box::new(envelope()))),
                    match kind {
                        0 => None,
                        1 => Some(Value::Null),
                        _ => Some(Value::String(2)),
                    },
                ],
            },
        );
    }
    let scopes = BTreeMap::from([
        (
            0,
            Record {
                layout: SCOPE,
                fields: vec![Some(Value::String(1)), Some(r(NODES, 0)), None],
            },
        ),
        (
            1,
            Record {
                layout: SCOPE,
                fields: vec![
                    Some(Value::String(1)),
                    Some(r(NODES, 1)),
                    Some(r(SCOPES, 0)),
                ],
            },
        ),
    ]);
    let mut edges = BTreeMap::new();
    for predicate in 0..257 {
        edges.insert(
            predicate,
            Record {
                layout: 1000 + predicate,
                fields: vec![
                    Some(r(NODES, 0)),
                    Some(r(NODES, 1)),
                    Some(Value::UInt64(predicate.into())),
                ],
            },
        );
    }
    // Two separately addressable edges have identical endpoints AND predicate.
    edges.insert(
        257,
        Record {
            layout: 1000,
            fields: vec![
                Some(r(NODES, 0)),
                Some(r(NODES, 1)),
                Some(Value::UInt64(257)),
            ],
        },
    );
    let data = BTreeMap::from([(
        0,
        Record {
            layout: PRIMITIVES,
            fields: vec![
                Some(Value::Null),
                Some(Value::Bool(true)),
                Some(Value::Int64(i64::MIN)),
                Some(Value::UInt64(u64::MAX)),
                Some(Value::Float64((-0.0f64).to_bits())),
                Some(Value::String(2)),
                Some(Value::Bytes(vec![0, 255, 128, 1])),
                Some(Value::List(vec![
                    Value::Float64(f64::INFINITY.to_bits()),
                    Value::Float64(0x7ff0_0000_0000_0001),
                    Value::Float64(0),
                ])),
                Some(Value::Record(Box::new(envelope()))),
            ],
        },
    )]);
    for (id, name, rows) in [
        (NODES, "nodes", nodes),
        (SCOPES, "scopes", scopes),
        (EDGES, "edges", edges),
        (DATA, "data", data),
    ] {
        doc.tables.insert(
            id,
            Table {
                name: q(name),
                rows,
            },
        );
    }
    doc
}

fn value<'a>(doc: &'a Document, record: &'a Record, name: &str) -> &'a Value {
    doc.field(record, &q(name))
        .expect("registered populated field")
}
fn string<'a>(doc: &'a Document, value: &Value) -> &'a str {
    let Value::String(id) = value else {
        panic!("expected string reference")
    };
    &doc.strings[id]
}
fn referenced<'a>(doc: &'a Document, value: &Value) -> &'a Record {
    let Value::Ref(target) = value else {
        panic!("expected explicit reference")
    };
    doc.record(*target).expect("reference resolves")
}

#[test]
fn unfamiliar_language_round_trips_without_kind_or_predicate_tag_limits() {
    let original = fixture("first");
    let limits = Limits::default();
    original.validate(&limits).unwrap();
    let encoded = encode(&original, &limits).unwrap();
    let restored = decode(&encoded, &limits).unwrap();
    assert_eq!(
        restored, original,
        "every definition, profile, field, bit pattern and row must survive"
    );
    assert_eq!(
        restored
            .definitions
            .values()
            .filter(|d| d.category == q("node"))
            .count(),
        17
    );
    assert_eq!(
        restored
            .definitions
            .values()
            .filter(|d| d.category == q("edge"))
            .count(),
        257
    );
    assert_eq!(restored.tables[&NODES].rows[&0].fields[3], None);
    assert_eq!(
        restored.tables[&NODES].rows[&1].fields[3],
        Some(Value::Null)
    );
    let edges = &restored.tables[&EDGES].rows;
    assert_eq!(edges[&0].layout, edges[&257].layout);
    assert_eq!(edges[&0].fields[..2], edges[&257].fields[..2]);
    assert_eq!(value(&restored, &edges[&0], "ordinal"), &Value::UInt64(0));
    assert_eq!(
        value(&restored, &edges[&257], "ordinal"),
        &Value::UInt64(257)
    );
    for scope in restored.tables[&SCOPES].rows.values() {
        assert_eq!(string(&restored, value(&restored, scope, "name")), "T");
        let owner = referenced(&restored, value(&restored, scope, "owner"));
        assert_eq!(
            referenced(&restored, value(&restored, owner, "scope")),
            scope,
            "owner/parameter cycle retains the actual binding"
        );
    }
    assert_ne!(
        restored.tables[&SCOPES].rows[&0],
        restored.tables[&SCOPES].rows[&1]
    );
    assert_eq!(
        encode(&restored, &limits).unwrap(),
        encoded,
        "canonical ordering is deterministic"
    );
}

#[test]
fn remapping_visits_nested_records_lists_descriptors_and_cycles() {
    let doc = fixture("remap");
    let limits = Limits::default();
    let map = IdMap {
        strings: doc.strings.keys().map(|id| (*id, id + 5000)).collect(),
        definitions: doc.definitions.keys().map(|id| (*id, id + 6000)).collect(),
        tables: doc.tables.keys().map(|id| (*id, id + 7000)).collect(),
        rows: doc
            .tables
            .iter()
            .flat_map(|(table, data)| {
                data.rows
                    .keys()
                    .map(move |row| (reference(*table, *row), row + 8000))
            })
            .collect(),
    };
    let moved = doc.remap(&map, &limits).unwrap();
    assert_eq!(doc, fixture("remap"), "remap must leave the source intact");
    let node = moved.record(reference(NODES + 7000, 8000)).unwrap();
    assert_eq!(node.layout, 6001);
    assert_eq!(node.fields[0], Some(Value::String(5100)));
    assert_eq!(node.fields[1], Some(r(SCOPES + 7000, 8000)));
    assert_eq!(
        moved.definitions[&6001].fields[1].descriptor,
        Descriptor::Ref(Some(SCOPES + 7000))
    );
    let Value::Record(nested) = value(&moved, node, "extension") else {
        panic!("nested record lost")
    };
    assert_eq!(nested.layout, ENVELOPE + 6000);
    assert_eq!(
        nested.fields[0],
        Some(Value::List(vec![
            Value::List(vec![
                r(NODES + 7000, 8000),
                r(SCOPES + 7000, 8001),
                r(NODES + 7000, 8000)
            ]),
            Value::List(vec![])
        ]))
    );
    assert_eq!(nested.fields[1], Some(Value::String(5002)));
    let scope = referenced(&moved, value(&moved, node, "scope"));
    assert_eq!(referenced(&moved, value(&moved, scope, "owner")), node);
    assert_eq!(
        decode(&encode(&moved, &limits).unwrap(), &limits).unwrap(),
        moved
    );
}

#[test]
fn invalid_documents_cannot_be_published() {
    let valid = fixture("invalid");
    let limits = Limits::default();
    type Mutation = (&'static str, Box<dyn Fn(&mut Document)>);
    let mutations: Vec<Mutation> = vec![
        (
            "missing string",
            Box::new(|d| {
                d.strings.remove(&2);
            }),
        ),
        (
            "missing layout",
            Box::new(|d| {
                d.definitions.remove(&ENVELOPE);
            }),
        ),
        (
            "missing table",
            Box::new(|d| {
                d.tables.remove(&SCOPES);
            }),
        ),
        (
            "missing row",
            Box::new(|d| {
                d.tables.get_mut(&SCOPES).unwrap().rows.remove(&1);
            }),
        ),
        (
            "wrong reference table",
            Box::new(|d| {
                d.tables
                    .get_mut(&NODES)
                    .unwrap()
                    .rows
                    .get_mut(&0)
                    .unwrap()
                    .fields[1] = Some(r(NODES, 0));
            }),
        ),
        (
            "missing required value",
            Box::new(|d| {
                d.tables
                    .get_mut(&NODES)
                    .unwrap()
                    .rows
                    .get_mut(&0)
                    .unwrap()
                    .fields[0] = None;
            }),
        ),
        (
            "null forbidden",
            Box::new(|d| {
                d.tables
                    .get_mut(&NODES)
                    .unwrap()
                    .rows
                    .get_mut(&0)
                    .unwrap()
                    .fields[0] = Some(Value::Null);
            }),
        ),
        (
            "wrong primitive",
            Box::new(|d| {
                d.tables
                    .get_mut(&NODES)
                    .unwrap()
                    .rows
                    .get_mut(&0)
                    .unwrap()
                    .fields[0] = Some(Value::UInt64(100));
            }),
        ),
        (
            "short record",
            Box::new(|d| {
                d.tables
                    .get_mut(&NODES)
                    .unwrap()
                    .rows
                    .get_mut(&0)
                    .unwrap()
                    .fields
                    .pop();
            }),
        ),
        (
            "long record",
            Box::new(|d| {
                d.tables
                    .get_mut(&NODES)
                    .unwrap()
                    .rows
                    .get_mut(&0)
                    .unwrap()
                    .fields
                    .push(None);
            }),
        ),
        (
            "nested list element",
            Box::new(|d| {
                let node = d.tables.get_mut(&NODES).unwrap().rows.get_mut(&0).unwrap();
                let Some(Value::Record(nested)) = &mut node.fields[2] else {
                    unreachable!()
                };
                nested.fields[0] = Some(Value::List(vec![Value::Bool(false)]));
            }),
        ),
    ];
    for (case, mutate) in mutations {
        let mut doc = valid.clone();
        mutate(&mut doc);
        assert!(
            matches!(doc.validate(&limits), Err(Error::Invalid(_))),
            "validator accepted {case}"
        );
        assert!(
            matches!(encode(&doc, &limits), Err(Error::Invalid(_))),
            "writer accepted {case}"
        );
    }
}

#[test]
fn conflicting_definition_revisions_and_id_collisions_are_rejected() {
    let doc = fixture("left");
    let limits = Limits::default();
    let mut conflict = fixture("right");
    conflict.definitions.get_mut(&1).unwrap().fields[0].role =
        Some(q("incompatible-name-semantics"));
    assert!(matches!(
        doc.merge(&conflict, &limits),
        Err(Error::Invalid(_))
    ));
    let mut duplicate_definition = doc.clone();
    duplicate_definition
        .definitions
        .insert(5000, conflict.definitions[&1].clone());
    assert!(matches!(
        duplicate_definition.validate(&limits),
        Err(Error::Invalid(_))
    ));
    for mapping in [
        IdMap {
            strings: BTreeMap::from([(1, 2)]),
            ..IdMap::default()
        },
        IdMap {
            definitions: BTreeMap::from([(1, 2)]),
            ..IdMap::default()
        },
        IdMap {
            tables: BTreeMap::from([(NODES, SCOPES)]),
            ..IdMap::default()
        },
        IdMap {
            rows: BTreeMap::from([(reference(NODES, 0), 1)]),
            ..IdMap::default()
        },
    ] {
        assert!(
            matches!(doc.remap(&mapping, &limits), Err(Error::Invalid(_))),
            "colliding remap discarded a distinct source entity"
        );
    }
}

#[test]
fn checksum_truncation_and_resource_limits_fail_closed() {
    let doc = fixture("limits");
    let limits = Limits::default();
    let encoded = encode(&doc, &limits).unwrap();
    let mut future = encoded.clone();
    let unknown_version = WIRE_VERSION.checked_add(1).unwrap();
    future[8..12].copy_from_slice(&unknown_version.to_le_bytes());
    assert!(
        matches!(decode(&future, &limits), Err(Error::UnsupportedVersion(version)) if version == unknown_version)
    );
    let mut corrupt = encoded.clone();
    let last = corrupt.len() - 1;
    corrupt[last] ^= 0x80;
    assert!(matches!(decode(&corrupt, &limits), Err(Error::Checksum)));
    for end in [0, 1, 4, 8, encoded.len() / 2, encoded.len() - 1] {
        assert!(
            decode(&encoded[..end], &limits).is_err(),
            "accepted truncated data at {end}"
        );
    }
    for constrained in [
        Limits {
            max_bytes: encoded.len() - 1,
            ..Limits::default()
        },
        Limits {
            max_items: 8,
            ..Limits::default()
        },
        Limits {
            max_depth: 1,
            ..Limits::default()
        },
    ] {
        assert!(matches!(encode(&doc, &constrained), Err(Error::Limit(_))));
        assert!(matches!(
            decode(&encoded, &constrained),
            Err(Error::Limit(_))
        ));
    }
}

// This oracle addresses targets by fixture declaration identity rather than wire
// IDs. It detects cross-graph capture even when all reference counts are correct.
fn logical_key(doc: &Document, target: Reference) -> String {
    let record = doc.record(target).unwrap();
    let table = &doc.tables[&target.table].name.name;
    match table.as_str() {
        "nodes" => format!("node:{}", string(doc, value(doc, record, "name"))),
        "scopes" => {
            let owner = referenced(doc, value(doc, record, "owner"));
            format!(
                "scope:{}:{}",
                string(doc, value(doc, owner, "name")),
                string(doc, value(doc, record, "name"))
            )
        }
        "edges" => {
            let source = referenced(doc, value(doc, record, "source"));
            format!(
                "edge:{}:{:?}",
                string(doc, value(doc, source, "name")),
                value(doc, record, "ordinal")
            )
        }
        "data" => format!("data:{}", string(doc, value(doc, record, "string"))),
        _ => panic!("unexpected fixture table"),
    }
}
fn logical_value(doc: &Document, value: &Value) -> String {
    match value {
        Value::String(id) => format!("String({:?})", doc.strings[id]),
        Value::Ref(target) => format!("Ref({:?})", logical_key(doc, *target)),
        Value::List(values) => format!(
            "List({:?})",
            values
                .iter()
                .map(|v| logical_value(doc, v))
                .collect::<Vec<_>>()
        ),
        Value::Record(record) => logical_record(doc, record),
        _ => format!("{value:?}"),
    }
}
fn logical_record(doc: &Document, record: &Record) -> String {
    let layout = &doc.definitions[&record.layout];
    let values: Vec<_> = record
        .fields
        .iter()
        .map(|v| v.as_ref().map(|v| logical_value(doc, v)))
        .collect();
    format!("{:?}@{}({values:?})", layout.name, layout.revision)
}
fn snapshot(doc: &Document) -> Vec<(String, String)> {
    let mut rows: Vec<_> = doc
        .tables
        .iter()
        .flat_map(|(table, data)| {
            data.rows.iter().map(move |(row, record)| {
                (
                    logical_key(doc, reference(*table, *row)),
                    logical_record(doc, record),
                )
            })
        })
        .collect();
    rows.sort();
    rows
}

#[test]
fn merging_overlapping_ids_preserves_each_logical_target_and_binding() {
    let left = fixture("left");
    let right = fixture("right");
    let limits = Limits::default();
    // Every raw ID overlaps, including the two unrelated declarations named T.
    assert_eq!(
        left.tables.keys().collect::<Vec<_>>(),
        right.tables.keys().collect::<Vec<_>>()
    );
    let mut expected = snapshot(&left);
    expected.extend(snapshot(&right));
    expected.sort();
    let merged = left.merge(&right, &limits).unwrap();
    let restored = decode(&encode(&merged, &limits).unwrap(), &limits).unwrap();
    assert_eq!(
        snapshot(&restored),
        expected,
        "all nested targets and independent scope owners must survive merging"
    );
    assert_eq!(
        restored.definitions, left.definitions,
        "shared immutable layouts should unify"
    );
    assert_eq!(restored.profiles, left.profiles);
    let unknown_kind = q("node-16");
    let matches: Vec<_> = restored.records_of(&unknown_kind).collect();
    let mut names: Vec<_> = matches
        .iter()
        .map(|(_, record)| string(&restored, value(&restored, record, "name")))
        .collect();
    names.sort();
    assert_eq!(
        names,
        ["left:declaration-16", "right:declaration-16"],
        "unknown-kind generic query returns concrete values"
    );
    assert_eq!(left, fixture("left"));
    assert_eq!(right, fixture("right"));
}

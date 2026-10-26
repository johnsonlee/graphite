use super::super::{assign_test_ids, Graph, ScanPlan};
use super::*;
use crate::ast::{Clause, Expr, Literal};
use crate::engine::Source;
use crate::materialize::materialize;
use crate::value::Value;
use graphite_storage::node::AnyValue;
use std::sync::Arc;

fn pred(property: &str, literal: &str) -> StringPredicate {
    StringPredicate {
        property: property.to_owned().into(),
        shape: LeafShape::Text,
        op: PushOp::Contains,
        literal: literal.into(),
        transform: Transform::None,
        regex: None,
        via_to_string: true,
        from_keys: false,
        trigrams: Arc::new(None),
        signature: 0,
        test: 0,
    }
}

#[test]
fn only_dictionary_contained_atoms_can_be_proved_absent() {
    for text in [
        " beta",
        "  beta",
        "a,b",
        "a.b",
        "[x",
        "x]",
        "{x",
        "x}",
        "a=b",
        "(x",
        "x)",
        "null",
        "true",
        "false",
        "123",
        "NaN",
        "Infinity",
        "EnumValueReference",
        "enumClass",
        "enumName",
        "",
    ] {
        assert!(!dictionary_settles(&pred("values", text)), "{text:?}");
    }
    for text in [
        "MissingAtom",
        "βeta",
        "alpha\tbeta",
        "option comparison",
        "x ",
    ] {
        assert!(dictionary_settles(&pred("values", text)), "{text:?}");
    }
    let mut p = pred("values", "MissingAtom");
    p.transform = Transform::Lowercase;
    assert!(!dictionary_settles(&p));
    p.transform = Transform::None;
    p.op = PushOp::Regex;
    assert!(!dictionary_settles(&p));
    let mut p = pred("type", "Annotation");
    assert!(!dictionary_settles(&p));
    p.from_keys = true;
    assert!(
        !dictionary_settles(&p),
        "a null dynamic type falls back to the type name"
    );
    assert!(!dictionary_settles(&pred("graphId", "MissingAtom")));
}

// A tiny persisted graph solely for deterministic correctness/path coverage.
// No timings of this synthetic fixture are performance evidence.
struct Fixture(std::path::PathBuf);
impl Drop for Fixture {
    fn drop(&mut self) {
        std::fs::remove_dir_all(&self.0).unwrap();
    }
}

fn i32_to(out: &mut Vec<u8>, v: i32) {
    out.extend_from_slice(&v.to_be_bytes());
}
fn utf(out: &mut Vec<u8>, s: &str) {
    out.extend_from_slice(&(s.len() as u16).to_be_bytes());
    out.extend_from_slice(s.as_bytes());
}

// Java serialization of the minimal UTF-8 front-coded string-table shape.
// Ratio one makes each entry a full string, avoiding unrelated compression logic.
fn class(out: &mut Vec<u8>, name: &str, fields: &[(u8, &str)]) {
    out.push(0x72);
    utf(out, name);
    out.extend_from_slice(&0i64.to_be_bytes());
    out.push(2);
    out.extend_from_slice(&(fields.len() as u16).to_be_bytes());
    for &(kind, name) in fields {
        out.push(kind);
        utf(out, name);
        if kind == b'L' || kind == b'[' {
            out.push(0x74);
            utf(out, "Ljava/lang/Object;");
        }
    }
    out.extend_from_slice(&[0x78, 0x70]);
}

fn strings(values: &[&str]) -> Vec<u8> {
    let mut out = vec![0xac, 0xed, 0, 5, 0x73];
    class(
        &mut out,
        "Strings",
        &[(b'Z', "utf8"), (b'L', "byteFrontCodedList")],
    );
    out.extend_from_slice(&[1, 0x73]);
    class(
        &mut out,
        "List",
        &[(b'I', "n"), (b'I', "ratio"), (b'[', "array")],
    );
    i32_to(&mut out, values.len() as i32);
    i32_to(&mut out, 1);
    out.push(0x75);
    class(&mut out, "[B", &[]);
    i32_to(
        &mut out,
        values.iter().map(|s| 1 + s.len()).sum::<usize>() as i32,
    );
    for value in values {
        assert!(value.len() < 128);
        out.push(value.len() as u8);
        out.extend_from_slice(value.as_bytes());
    }
    out
}

fn value(out: &mut Vec<u8>, v: &AnyValue, ids: &[u32]) {
    match v {
        AnyValue::Str(id) => {
            out.push(2);
            i32_to(out, ids[*id as usize] as i32);
        }
        AnyValue::Int(v) => {
            out.push(0);
            i32_to(out, *v);
        }
        AnyValue::Bool(v) => {
            out.extend_from_slice(&[5, u8::from(*v)]);
        }
        AnyValue::Null => out.push(6),
        AnyValue::EnumRef {
            enum_class,
            enum_name,
        } => {
            out.push(7);
            i32_to(out, ids[*enum_class as usize] as i32);
            i32_to(out, ids[*enum_name as usize] as i32);
        }
        AnyValue::List(vs) => {
            out.push(8);
            i32_to(out, vs.len() as i32);
            for v in vs {
                value(out, v, ids);
            }
        }
        _ => panic!("fixture value not implemented"),
    }
}

fn fixture() -> (Fixture, Arc<Graph>) {
    fixture_with_callsite(false)
}

fn fixture_with_callsite(with_callsite: bool) -> (Fixture, Arc<Graph>) {
    fixture_with_annotation_needle(with_callsite, "NoCallSiteNeedle")
}

fn fixture_with_annotation_needle(with_callsite: bool, needle: &str) -> (Fixture, Arc<Graph>) {
    static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
    let fixture = Fixture(std::env::temp_dir().join(format!(
        "graphite-annotation-proof-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
    )));
    std::fs::create_dir(&fixture.0).unwrap();
    let names = [
        "Marker",
        "Owner",
        "member",
        "alpha",
        "beta",
        "x",
        "y",
        "values",
        "nested",
        "Choice",
        "READY",
        "flag",
        "number",
        "empty",
        "Other",
        "nestedBeta",
        "caller_class",
        needle,
        "phrase",
        "option comparison",
        "type",
        "graphId",
        "AnnotationIdentity",
    ];
    let mut sorted = names.to_vec();
    sorted.sort_unstable();
    let ids: Vec<u32> = names
        .iter()
        .map(|name| sorted.binary_search(name).unwrap() as u32)
        .collect();
    let mut data = Vec::new();
    i32_to(&mut data, 0x47524e02);
    let nodes = if with_callsite { 3 } else { 2 };
    i32_to(&mut data, nodes);
    let mut offsets = Vec::new();
    i32_to(&mut offsets, 0x47524c01);
    i32_to(&mut offsets, nodes);
    let attrs = vec![
        (3, AnyValue::Str(5)),
        (4, AnyValue::Str(6)),
        (7, AnyValue::Str(5)),
        (
            8,
            AnyValue::List(vec![
                AnyValue::Str(5),
                AnyValue::List(vec![AnyValue::Str(5), AnyValue::Str(15)]),
                AnyValue::EnumRef {
                    enum_class: 9,
                    enum_name: 10,
                },
                AnyValue::Null,
            ]),
        ),
        (11, AnyValue::Bool(true)),
        (12, AnyValue::Int(123)),
        (13, AnyValue::Null),
        (16, AnyValue::Str(17)),
        (18, AnyValue::Str(19)),
        (20, AnyValue::Null),
        (21, AnyValue::Str(22)),
    ];
    for id in 0..2 {
        offsets.extend_from_slice(&(data.len() as i64 + 1).to_be_bytes());
        i32_to(&mut data, id);
        data.push(TAG_ANNOTATION_NODE);
        i32_to(&mut data, ids[if id == 0 { 0 } else { 14 }] as i32);
        i32_to(&mut data, ids[1] as i32);
        i32_to(&mut data, ids[2] as i32);
        i32_to(&mut data, if id == 0 { attrs.len() as i32 } else { 0 });
        if id == 0 {
            for (key, v) in &attrs {
                i32_to(&mut data, ids[*key as usize] as i32);
                value(&mut data, v, &ids);
            }
        }
    }
    if with_callsite {
        offsets.extend_from_slice(&(data.len() as i64 + 1).to_be_bytes());
        i32_to(&mut data, 2);
        data.push(graphite_storage::node::TAG_CALL_SITE_NODE);
        for _ in 0..2 {
            // Owner.member():Owner, unrelated to the annotation-only needle.
            for word in [ids[1] as i32, ids[2] as i32, 0, ids[1] as i32] {
                i32_to(&mut data, word);
            }
        }
        for word in [-1, -1, 0] {
            i32_to(&mut data, word);
        }
    }
    let mut types = Vec::new();
    i32_to(&mut types, 0x47525401);
    i32_to(&mut types, if with_callsite { 2 } else { 1 });
    types.push(TAG_ANNOTATION_NODE);
    i32_to(&mut types, 2);
    types.extend_from_slice(&(if with_callsite { 34i64 } else { 21i64 }).to_be_bytes());
    if with_callsite {
        types.push(graphite_storage::node::TAG_CALL_SITE_NODE);
        i32_to(&mut types, 1);
        types.extend_from_slice(&42i64.to_be_bytes());
    }
    i32_to(&mut types, 0);
    i32_to(&mut types, 1);
    if with_callsite {
        i32_to(&mut types, 2);
    }
    let mut metadata = Vec::new();
    i32_to(&mut metadata, 0x47524d02);
    metadata.extend_from_slice(&[0; 24]);
    for (name, bytes) in [
        ("graph.strings", strings(&sorted)),
        ("graph.nodedata", data),
        ("graph.nodeoffsets", offsets),
        ("graph.typeindex", types),
        ("graph.metadata", metadata),
        ("graph.labels", vec![]),
        (
            "forward.properties",
            format!("nodes={nodes}\narcs=0\n").into_bytes(),
        ),
        // Gamma-coded zero degrees: 1,1; offset deltas: 0,1,1 => 1,010,010.
        ("forward.graph", vec![0xe0]),
        ("forward.offsets", vec![0xa4, 0x80]),
    ] {
        std::fs::write(fixture.0.join(name), bytes).unwrap();
    }
    let graph = Arc::new(Graph::load(&fixture.0).unwrap());
    assert_eq!(graph.node_count(), nodes as usize);
    (fixture, graph)
}

#[test]
fn annotation_absence_respects_boolean_structure_synthetic_identity_and_cancel() {
    let (_fixture, graph) = fixture();
    let ex = Executor::new(
        vec![Source {
            id: Arc::from("GraphOnly"),
            graph,
        }],
        true,
    );
    let leaf = |property, text| PredTree::Leaf(pred(property, text));
    let check = |mut tree: PredTree| {
        let tests = assign_test_ids(&mut tree);
        cannot_match(&ex, 0, &tree, &mut DictionaryMemo::new(tests)).unwrap()
    };
    assert!(check(leaf("values", "MissingAtom")));
    assert!(!check(leaf("values", "beta")));
    assert!(!check(leaf("values", " beta")));
    assert!(check(PredTree::And(vec![
        leaf("values", "MissingAtom"),
        leaf("values", "beta")
    ])));
    assert!(!check(PredTree::Or(vec![
        leaf("values", "MissingAtom"),
        leaf("values", "beta")
    ])));
    assert!(!check(leaf("graphId", "GraphOnly")));
    assert!(check(leaf("graphId", "MissingAtom")));
    assert!(!check(leaf("elementId", "0")));
    ex.cancel.cancel();
    assert!(matches!(
        cannot_match(
            &ex,
            0,
            &leaf("values", "MissingAtom"),
            &mut DictionaryMemo::new(1)
        ),
        Err(crate::CypherError::Cancelled)
    ));
}

#[test]
fn coalesce_fallback_must_survive_annotation_key_absence_pruning() {
    let (_fixture, graph) = fixture();
    let ex = Executor::new(
        vec![Source {
            id: Arc::from("first"),
            graph,
        }],
        true,
    );
    let query = "MATCH (n:Annotation) WHERE coalesce(n.absent_attribute, 'Marker') CONTAINS 'Marker' OR n.caller_class CONTAINS 'MissingAtom' RETURN n.id AS id, n.name AS name, n";
    let clauses = crate::parser::parse(query).unwrap();
    let property = |key: &str| Expr::Property {
        expr: Box::new(Expr::Variable("n".into())),
        key: key.into(),
    };
    let text = |value: &str| Expr::Literal(Literal::Str(value.into()));
    let contains = |left: Expr, value: &str| Expr::StringOp {
        op: crate::ast::StrOp::Contains,
        left: Box::new(left),
        right: Box::new(text(value)),
    };
    assert_eq!(
        clauses[1],
        Clause::Where(Expr::Or(
            Box::new(contains(
                Expr::FunctionCall {
                    name: "coalesce".into(),
                    distinct: false,
                    args: vec![property("absent_attribute"), text("Marker")]
                },
                "Marker"
            )),
            Box::new(contains(property("caller_class"), "MissingAtom")),
        ))
    );
    let Clause::Match { patterns, .. } = &clauses[0] else {
        unreachable!()
    };
    let Clause::Where(predicate) = &clauses[1] else {
        unreachable!()
    };
    assert!(
        ScanPlan::build(patterns, Some(predicate)).is_none(),
        "the matching fallback cannot be pushed away"
    );
    let actual = ex.execute_clauses(&clauses, None).unwrap();
    let expected = ex.execute_clauses(&generic_oracle(&clauses), None).unwrap();
    let ids = |result: &crate::engine::QueryResult| {
        result
            .rows
            .iter()
            .map(|row| match row.get("id").unwrap() {
                Value::Int(id) => *id,
                other => panic!("unexpected id: {other:?}"),
            })
            .collect::<Vec<_>>()
    };
    assert_eq!(
        ids(&expected),
        vec![0, 1],
        "fallback matches both annotations"
    );
    assert_eq!(
        ids(&actual),
        vec![0, 1],
        "key absence cannot erase a matching fallback"
    );
    assert_eq!(format!("{:?}", actual.rows), format!("{:?}", expected.rows));
    for (a, e) in actual.rows.iter().zip(&expected.rows) {
        assert_eq!(
            materialize(a.get("n").unwrap(), &ex),
            materialize(e.get("n").unwrap(), &ex)
        );
    }
}

#[test]
fn coalesce_pushdown_preserves_safe_empty_fallbacks_and_rejects_matching_alternatives() {
    let (_fixture, graph) = fixture();
    let ex = Executor::new(
        vec![Source {
            id: Arc::from("first"),
            graph,
        }],
        true,
    );
    let cases = [
        (
            "coalesce(n.absent_attribute, '') CONTAINS 'Marker'",
            true,
            vec![],
        ),
        (
            "coalesce(coalesce(n.absent_attribute, ''), null) CONTAINS 'Marker'",
            true,
            vec![],
        ),
        (
            "coalesce(coalesce(n.absent_attribute, 'Marker'), null) CONTAINS 'Marker'",
            false,
            vec![0, 1],
        ),
        (
            "coalesce(coalesce(n.absent_attribute, ''), null) CONTAINS ''",
            false,
            vec![0, 1],
        ),
        (
            "coalesce(toString(n.number), '') CONTAINS '123'",
            true,
            vec![0],
        ),
        (
            "toLower(coalesce(n.name, '')) CONTAINS 'marker'",
            true,
            vec![0],
        ),
        (
            "coalesce(n.name, null, '', null) CONTAINS 'Marker'",
            true,
            vec![0],
        ),
        ("coalesce(n.name, '') STARTS WITH 'Mar'", true, vec![0]),
        ("coalesce(n.name, '') ENDS WITH 'ker'", true, vec![0]),
        ("coalesce(n.name, '') = 'Marker'", true, vec![0]),
        (
            "coalesce(n.absent_attribute, null) CONTAINS ''",
            true,
            vec![],
        ),
        (
            "coalesce(n.absent_attribute, '') =~ '.*Marker.*'",
            true,
            vec![],
        ),
        (
            "coalesce(n.absent_attribute, 'Marker') =~ '.*Marker.*'",
            false,
            vec![0, 1],
        ),
        (
            "coalesce(n.absent_attribute, '') =~ '.*'",
            false,
            vec![0, 1],
        ),
        (
            "coalesce(n.absent_attribute, '') CONTAINS ''",
            false,
            vec![0, 1],
        ),
        (
            "coalesce(n.absent_attribute, '') STARTS WITH ''",
            false,
            vec![0, 1],
        ),
        (
            "coalesce(n.absent_attribute, '') ENDS WITH ''",
            false,
            vec![0, 1],
        ),
        ("coalesce(n.absent_attribute, '') = ''", false, vec![0, 1]),
        (
            "coalesce(n.absent_attribute, n.name) CONTAINS 'Marker'",
            false,
            vec![0],
        ),
        (
            "coalesce(n.absent_attribute, null, 'Marker') CONTAINS 'Marker'",
            false,
            vec![0, 1],
        ),
        (
            "toLower(coalesce(toString(n.absent_attribute), 'Marker')) CONTAINS 'marker'",
            false,
            vec![0, 1],
        ),
        (
            "toString(coalesce(n.absent_attribute, 123)) CONTAINS '123'",
            false,
            vec![0, 1],
        ),
        ("coalesce(n.absent_attribute, 123) = 123", false, vec![0, 1]),
        (
            "'Marker' IN coalesce(n.absent_attribute, ['Marker'])",
            false,
            vec![0, 1],
        ),
        ("coalesce() CONTAINS 'Marker'", false, vec![]),
    ];
    for (filter, pushed, ids) in cases {
        let query =
            format!("MATCH (n:Annotation) WHERE {filter} RETURN n.id AS id, n.name AS name, n");
        let clauses = crate::parser::parse(&query).unwrap();
        let Clause::Match { patterns, .. } = &clauses[0] else {
            unreachable!()
        };
        let Clause::Where(predicate) = &clauses[1] else {
            unreachable!()
        };
        let plan = ScanPlan::build(patterns, Some(predicate));
        assert_eq!(plan.is_some(), pushed, "pushdown: {filter}");
        if let Some(plan) = plan {
            let mut leaves = Vec::new();
            plan.tree.leaves(&mut leaves);
            assert_eq!(leaves.len(), 1, "one property predicate: {filter}");
            let leaf = &leaves[0];
            assert_eq!(leaf.shape, LeafShape::Text);
            let expected_property = if filter.contains("n.number") {
                "number"
            } else if filter.contains("n.name") {
                "name"
            } else {
                "absent_attribute"
            };
            assert_eq!(leaf.prop(), expected_property, "{filter}");
            let expected_op = if filter.contains("=~") {
                PushOp::Regex
            } else if filter.contains("STARTS WITH") {
                PushOp::StartsWith
            } else if filter.contains("ENDS WITH") {
                PushOp::EndsWith
            } else if filter.contains("CONTAINS") {
                PushOp::Contains
            } else {
                PushOp::Equals
            };
            assert_eq!(leaf.op, expected_op, "{filter}");
            let expected_literal = if filter.contains("'123'") {
                "123"
            } else if filter.contains("'marker'") {
                "marker"
            } else if filter.contains("'Mar'") {
                "Mar"
            } else if filter.contains("'ker'") {
                "ker"
            } else if filter.ends_with("CONTAINS ''") {
                ""
            } else {
                "Marker"
            };
            assert_eq!(leaf.literal, expected_literal, "{filter}");
            assert_eq!(leaf.via_to_string, filter.contains("toString"), "{filter}");
            assert!(
                matches!(leaf.transform, Transform::Lowercase) == filter.starts_with("toLower"),
                "{filter}"
            );
        }
        let actual = ex.execute_clauses(&clauses, None).unwrap();
        let expected = ex.execute_clauses(&generic_oracle(&clauses), None).unwrap();
        assert_eq!(actual.columns, expected.columns, "{filter}");
        assert_eq!(
            format!("{:?}", actual.rows),
            format!("{:?}", expected.rows),
            "typed rows: {filter}"
        );
        assert_eq!(
            actual
                .rows
                .iter()
                .map(|row| match row.get("id").unwrap() {
                    Value::Int(id) => *id,
                    other => panic!("unexpected id: {other:?}"),
                })
                .collect::<Vec<_>>(),
            ids,
            "{filter}"
        );
        for (a, e) in actual.rows.iter().zip(&expected.rows) {
            assert_eq!(
                a.get("name").unwrap().as_str(),
                Some(if matches!(a.get("id"), Some(Value::Int(0))) {
                    "Marker"
                } else {
                    "Other"
                })
            );
            assert_eq!(
                materialize(a.get("n").unwrap(), &ex),
                materialize(e.get("n").unwrap(), &ex),
                "{filter}"
            );
        }
    }
}

#[test]
fn absent_dynamic_keys_are_proved_before_consuming_dictionary_memo() {
    let (_fixture, graph) = fixture_with_callsite(true);
    let ex = Executor::new(
        vec![Source {
            id: Arc::from("first"),
            graph: graph.clone(),
        }],
        true,
    );
    let leaf = |property, literal| PredTree::Leaf(pred(property, literal));
    let cases = [
        leaf("missing_attribute", "Marker"),
        leaf("missing_attribute", ""),
        {
            let mut p = pred("missing_attribute", "123");
            p.shape = LeafShape::Numeric;
            PredTree::Leaf(p)
        },
        {
            let mut p = pred("missing_attribute", "Marker");
            p.shape = LeafShape::Member;
            PredTree::Leaf(p)
        },
        PredTree::And(vec![
            leaf("name", "MissingAtom"),
            leaf("missing_attribute", "Marker"),
        ]),
        PredTree::Or(vec![
            leaf("missing_attribute", "Marker"),
            leaf("another_missing_attribute", "Owner"),
        ]),
        PredTree::Or(vec![
            PredTree::And(vec![
                leaf("name", "MissingAtom"),
                leaf("missing_attribute", "Marker"),
            ]),
            leaf("another_missing_attribute", "Owner"),
        ]),
    ];
    for tree in cases {
        // Every leaf uses test slot zero. A literal arena proof would access
        // that slot and panic: success proves the cheap key preflight alone
        // settled the tree, independent of timings and dictionary contents.
        assert!(cannot_match(&ex, 0, &tree, &mut DictionaryMemo::new(0)).unwrap());
    }
    for key in [
        "id",
        "name",
        "class",
        "member",
        "values",
        "type",
        "graphId",
        "elementId",
        "qualifiedId",
        "caller_class",
    ] {
        assert!(
            !keys_cannot_match(&ex, 0, &leaf(key, "MissingAtom")).unwrap(),
            "built-in or present key: {key}"
        );
    }
    let mut expanded = pred("missing_attribute", "NoCallSiteNeedle");
    expanded.from_keys = true;
    let expanded = PredTree::Leaf(expanded);
    assert!(!keys_cannot_match(&ex, 0, &expanded).unwrap());
    assert!(
        !cannot_match(&ex, 0, &expanded, &mut DictionaryMemo::new(1)).unwrap(),
        "an expanded keys leaf may match another dynamic attribute"
    );
    assert!(!cannot_match(
        &ex,
        0,
        &leaf("caller_class", "NoCallSiteNeedle"),
        &mut DictionaryMemo::new(1)
    )
    .unwrap());
    let local = Executor::new(
        vec![Source {
            id: Arc::from("first"),
            graph,
        }],
        false,
    );
    assert!(!keys_cannot_match(&local, 0, &leaf("graphId", "AnnotationIdentity")).unwrap());
    assert!(
        cannot_match(
            &local,
            0,
            &leaf("elementId", "MissingAtom"),
            &mut DictionaryMemo::new(0)
        )
        .unwrap(),
        "non-cross identities are ordinary dynamic keys"
    );
    ex.cancel.cancel();
    assert!(matches!(
        cannot_match(
            &ex,
            0,
            &leaf("missing_attribute", "Marker"),
            &mut DictionaryMemo::new(0)
        ),
        Err(crate::CypherError::Cancelled)
    ));
}

#[test]
fn every_eligible_substring_of_rendered_annotations_is_dictionary_backed() {
    let (_fixture, graph) = fixture();
    let node = graph.node(0).unwrap();
    let rendered = crate::engine::props::node_property(&graph, &node, "values");
    let text = rendered.as_str().unwrap();
    let boundaries: Vec<_> = text
        .char_indices()
        .map(|(index, _)| index)
        .chain([text.len()])
        .collect();
    let mut checked = 0;
    for (i, &start) in boundaries.iter().enumerate() {
        for &end in &boundaries[i + 1..] {
            let literal = &text[start..end];
            if dictionary_settles(&pred("values", literal)) {
                assert!(
                    (0..graph.strings().len()).any(|id| graph.strings().get(id).contains(literal)),
                    "rendered-only literal cannot be pruned: {literal:?}"
                );
                checked += 1;
            }
        }
    }
    assert!(
        checked > 100,
        "exercise interior spaces, map/list keys and enum atoms"
    );
}

#[test]
fn non_cross_graph_annotation_identity_names_are_dynamic_not_synthetic() {
    let (_fixture, graph) = fixture();
    let ex = Executor::new(
        vec![Source {
            id: Arc::from("first"),
            graph,
        }],
        false,
    );
    assert!(!cannot_match(
        &ex,
        0,
        &PredTree::Leaf(pred("graphId", "AnnotationIdentity")),
        &mut DictionaryMemo::new(1)
    )
    .unwrap());
    let clauses = crate::parser::parse("MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'AnnotationIdentity') RETURN n.id AS id, n.graphId AS gid, n").unwrap();
    let actual = ex.execute_clauses(&clauses, None).unwrap();
    let expected = ex.execute_clauses(&generic_oracle(&clauses), None).unwrap();
    assert_eq!(format!("{:?}", actual.rows), format!("{:?}", expected.rows));
    assert_eq!(actual.rows.len(), 1);
    assert!(matches!(actual.rows[0].get("id"), Some(Value::Int(0))));
    assert_eq!(
        actual.rows[0].get("gid").unwrap().as_str(),
        Some("AnnotationIdentity")
    );
    assert_eq!(
        materialize(actual.rows[0].get("n").unwrap(), &ex),
        materialize(expected.rows[0].get("n").unwrap(), &ex)
    );
}

fn generic_oracle(clauses: &[Clause]) -> Vec<Clause> {
    let mut generic = clauses.to_vec();
    let Clause::Where(predicate) = &mut generic[1] else {
        panic!("WHERE required")
    };
    if let Expr::PredicateFunction {
        predicate: Some(inner),
        ..
    } = predicate
    {
        **inner = Expr::Or(inner.clone(), Box::new(Expr::Literal(Literal::Bool(false))));
    } else {
        *predicate = Expr::Or(
            Box::new(predicate.clone()),
            Box::new(Expr::Literal(Literal::Bool(false))),
        );
    }
    let Clause::Match { patterns, .. } = &generic[0] else {
        panic!("MATCH required")
    };
    let Clause::Where(predicate) = &generic[1] else {
        unreachable!()
    };
    assert!(
        ScanPlan::build(patterns, Some(predicate)).is_none(),
        "oracle must bypass scan pruning"
    );
    generic
}

#[test]
fn callsite_bitmap_must_not_discard_annotation_only_property_matches() {
    let (_fixture, graph) = fixture_with_callsite(true);
    let trigrams = graphite_storage::callsite_index::literal_trigrams("NoCallSiteNeedle").unwrap();
    assert!(!graph.call_site_index().unwrap().may_contain_all(&trigrams));
    let ex = Executor::new(
        vec![Source {
            id: Arc::from("first"),
            graph,
        }],
        true,
    );
    for property in ["n.caller_class", "toString(n.caller_class)"] {
        let text = format!("MATCH (n) WHERE {property} CONTAINS 'NoCallSiteNeedle' RETURN n.id AS id, n.name AS name, n");
        let clauses = crate::parser::parse(&text).unwrap();
        let actual = ex.execute_clauses(&clauses, None).unwrap();
        let expected = ex.execute_clauses(&generic_oracle(&clauses), None).unwrap();
        assert_eq!(
            format!("{:?}", actual.rows),
            format!("{:?}", expected.rows),
            "{text}"
        );
        assert_eq!(actual.rows.len(), 1, "{text}");
        assert!(matches!(actual.rows[0].get("id"), Some(Value::Int(0))));
        assert_eq!(actual.rows[0].get("name").unwrap().as_str(), Some("Marker"));
        assert_eq!(
            materialize(actual.rows[0].get("n").unwrap(), &ex),
            materialize(expected.rows[0].get("n").unwrap(), &ex)
        );
    }
}

#[test]
fn absent_annotations_restore_callsite_batch_route_without_filtering_possible_matches() {
    let (_fixture, graph) = fixture_with_callsite(true);
    let ex = Executor::new(
        ["first", "second"]
            .into_iter()
            .map(|id| Source {
                id: Arc::from(id),
                graph: graph.clone(),
            })
            .collect(),
        true,
    );
    let cases = [
        ("MATCH (n) WHERE n.caller_class CONTAINS 'MissingAtom' RETURN n.id AS id", true, vec![]),
        ("MATCH (n) WHERE n.caller_class CONTAINS 'NoCallSiteNeedle' RETURN n.id AS id", false, vec![0, 0]),
        ("MATCH (n) WHERE n.caller_class CONTAINS 'Owner' OR n.caller_class CONTAINS 'NoCallSiteNeedle' RETURN n.id AS id LIMIT 1", false, vec![0]),
        ("MATCH (n:CallSite) WHERE n.caller_class CONTAINS 'Owner' RETURN n.id AS id", true, vec![2, 2]),
        ("MATCH (n) WHERE n.callee_class CONTAINS 'Owner' RETURN n.id AS id", true, vec![2, 2]),
        ("MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'MissingAtom') RETURN n.id AS id", false, vec![]),
    ];
    for (query, expected_batch, ids) in cases {
        let clauses = crate::parser::parse(query).unwrap();
        let Clause::Match { patterns, .. } = &clauses[0] else {
            unreachable!()
        };
        let Clause::Where(predicate) = &clauses[1] else {
            unreachable!()
        };
        let plan = ScanPlan::build(patterns, Some(predicate)).expect("pushable test clause");
        assert_eq!(
            plan.can_batch_callsite_sources(&ex, &[0, 1]).unwrap(),
            expected_batch,
            "route: {query}"
        );
        let actual = ex.execute_clauses(&clauses, None).unwrap();
        let expected = ex.execute_clauses(&generic_oracle(&clauses), None).unwrap();
        assert_eq!(actual.columns, expected.columns, "{query}");
        assert_eq!(
            format!("{:?}", actual.rows),
            format!("{:?}", expected.rows),
            "typed ordered rows: {query}"
        );
        assert_eq!(
            actual
                .rows
                .iter()
                .map(|row| match row.get("id").unwrap() {
                    Value::Int(id) => *id,
                    other => panic!("unexpected id: {other:?}"),
                })
                .collect::<Vec<_>>(),
            ids,
            "{query}"
        );
    }
    let clauses =
        crate::parser::parse("MATCH (n) WHERE n.caller_class CONTAINS 'MissingAtom' RETURN n")
            .unwrap();
    let Clause::Match { patterns, .. } = &clauses[0] else {
        unreachable!()
    };
    let Clause::Where(predicate) = &clauses[1] else {
        unreachable!()
    };
    let plan = ScanPlan::build(patterns, Some(predicate)).unwrap();
    ex.cancel.cancel();
    assert!(matches!(
        plan.can_batch_callsite_sources(&ex, &[0, 1]),
        Err(crate::CypherError::Cancelled)
    ));
}

#[test]
fn later_annotation_match_prevents_batching_after_an_absent_first_source() {
    let (_first_fixture, first) = fixture_with_annotation_needle(true, "UnrelatedAnnotation");
    let (_second_fixture, second) = fixture_with_callsite(true);
    let ex = Executor::new(
        vec![
            Source {
                id: Arc::from("first"),
                graph: first,
            },
            Source {
                id: Arc::from("second"),
                graph: second,
            },
        ],
        true,
    );
    for suffix in ["", " LIMIT 1"] {
        let text = format!("MATCH (n) WHERE n.caller_class CONTAINS 'NoCallSiteNeedle' RETURN n.id AS id, n.graphId AS gid, n.caller_class AS caller, n{suffix}");
        let clauses = crate::parser::parse(&text).unwrap();
        let Clause::Match { patterns, .. } = &clauses[0] else {
            unreachable!()
        };
        let Clause::Where(predicate) = &clauses[1] else {
            unreachable!()
        };
        let plan = ScanPlan::build(patterns, Some(predicate)).unwrap();
        assert!(
            plan.can_batch_callsite_sources(&ex, &[0]).unwrap(),
            "first source proves absence"
        );
        assert!(
            !plan.can_batch_callsite_sources(&ex, &[1]).unwrap(),
            "second source may match"
        );
        assert!(
            !plan.can_batch_callsite_sources(&ex, &[0, 1]).unwrap(),
            "a later possible match requires merged execution"
        );
        let actual = ex.execute_clauses(&clauses, None).unwrap();
        let expected = ex.execute_clauses(&generic_oracle(&clauses), None).unwrap();
        assert_eq!(actual.columns, expected.columns, "{text}");
        assert_eq!(
            format!("{:?}", actual.rows),
            format!("{:?}", expected.rows),
            "typed rows/provenance: {text}"
        );
        assert_eq!(actual.rows.len(), 1, "{text}");
        let row = &actual.rows[0];
        assert!(matches!(row.get("id"), Some(Value::Int(0))));
        assert_eq!(row.get("gid").unwrap().as_str(), Some("second"));
        assert_eq!(
            row.get("caller").unwrap().as_str(),
            Some("NoCallSiteNeedle")
        );
        assert_eq!(crate::engine::QueryResult::graph_ids(row), vec!["second"]);
        assert_eq!(
            materialize(row.get("n").unwrap(), &ex),
            materialize(expected.rows[0].get("n").unwrap(), &ex)
        );
    }
}

#[test]
fn mixed_annotation_callsite_results_preserve_node_order_before_limit() {
    let (_fixture, graph) = fixture_with_callsite(true);
    let ex = Executor::new(
        vec![Source {
            id: Arc::from("first"),
            graph,
        }],
        true,
    );
    for (suffix, ids) in [("", vec![0, 2]), (" LIMIT 1", vec![0])] {
        let text = format!("MATCH (n) WHERE n.caller_class CONTAINS 'Owner' OR n.caller_class CONTAINS 'NoCallSiteNeedle' RETURN n.id AS id, n.type AS type, n.caller_class AS caller, n{suffix}");
        let clauses = crate::parser::parse(&text).unwrap();
        let Clause::Match { patterns, .. } = &clauses[0] else {
            unreachable!()
        };
        let Clause::Where(predicate) = &clauses[1] else {
            unreachable!()
        };
        assert!(
            ScanPlan::build(patterns, Some(predicate)).is_some(),
            "optimized path: {text}"
        );
        let actual = ex.execute_clauses(&clauses, None).unwrap();
        let expected = ex.execute_clauses(&generic_oracle(&clauses), None).unwrap();
        assert_eq!(actual.columns, expected.columns, "{text}");
        assert_eq!(
            format!("{:?}", actual.rows),
            format!("{:?}", expected.rows),
            "typed rows and order: {text}"
        );
        assert_eq!(
            actual
                .rows
                .iter()
                .map(|row| match row.get("id").unwrap() {
                    Value::Int(id) => *id,
                    other => panic!("unexpected id: {other:?}"),
                })
                .collect::<Vec<_>>(),
            ids,
            "{text}"
        );
        for (a, e) in actual.rows.iter().zip(&expected.rows) {
            let annotation = matches!(a.get("id"), Some(Value::Int(0)));
            assert_eq!(
                a.get("type").unwrap().as_str(),
                Some(if annotation {
                    "AnnotationNode"
                } else {
                    "CallSiteNode"
                })
            );
            assert_eq!(
                a.get("caller").unwrap().as_str(),
                Some(if annotation {
                    "NoCallSiteNeedle"
                } else {
                    "Owner"
                })
            );
            assert_eq!(
                materialize(a.get("n").unwrap(), &ex),
                materialize(e.get("n").unwrap(), &ex),
                "{text}"
            );
        }
    }
}

#[test]
fn rendered_annotation_boundaries_and_values_match_independent_generic_results() {
    let (_fixture, graph) = fixture();
    assert!((0..graph.strings().len()).all(|id| !graph.strings().get(id).contains(" beta")));
    let ex = Executor::new(
        ["first", "GraphOnly"]
            .into_iter()
            .map(|id| Source {
                id: Arc::from(id),
                graph: graph.clone(),
            })
            .collect(),
        true,
    );
    let cases = [
        ("toString(n.values) CONTAINS ' beta'", vec![0]),
        ("toString(n.values) CONTAINS 'MissingAtom'", vec![]),
        ("toString(n.absent_attribute) CONTAINS 'Marker'", vec![]),
        ("toString(n.absent_attribute) CONTAINS ''", vec![]),
        ("toString(n.absent_attribute) CONTAINS 'null'", vec![]),
        ("n.absent_attribute = 123", vec![]),
        ("'Marker' IN n.absent_attribute", vec![]),
        (
            "toString(n.absent_attribute) CONTAINS 'Marker' OR n.name CONTAINS 'Marker'",
            vec![0],
        ),
        (
            "n.name CONTAINS 'Marker' AND toString(n.absent_attribute) CONTAINS 'Marker'",
            vec![],
        ),
        ("toString(n.values) CONTAINS 'beta'", vec![0]),
        ("toString(n.values) CONTAINS 'option comparison'", vec![0]),
        ("toString(n.values) CONTAINS 'absent phrase'", vec![]),
        ("toString(n.values) CONTAINS 'EnumValueReference'", vec![0]),
        ("toString(n.values) CONTAINS 'enumName'", vec![0]),
        ("toString(n.values) CONTAINS 'null'", vec![0]),
        ("toString(n.values) CONTAINS '123'", vec![0]),
        ("toString(n.flag) CONTAINS 'true'", vec![0]),
        ("toString(n.nested) CONTAINS ' nestedBeta'", vec![0]),
        ("toString(n.nested) CONTAINS 'Choice.READY'", vec![0]),
        ("toLower(n.name) CONTAINS 'marker'", vec![0]),
        (
            "toString(n.caller_class) CONTAINS 'NoCallSiteNeedle'",
            vec![0],
        ),
        ("n.caller_class CONTAINS 'NoCallSiteNeedle'", vec![0]),
        ("toString(n.type) CONTAINS 'AnnotationNode'", vec![0, 1]),
        (
            "toString(n.values) CONTAINS 'MissingAtom' OR n.name CONTAINS 'Marker'",
            vec![0],
        ),
        (
            "toString(n.values) CONTAINS 'MissingAtom' AND n.name CONTAINS 'Marker'",
            vec![],
        ),
        (
            "any(k IN keys(n) WHERE toString(n[k]) CONTAINS ' beta')",
            vec![0],
        ),
        (
            "any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'MissingAtom')",
            vec![],
        ),
        (
            "any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'AnnotationNode')",
            vec![0],
        ),
        (
            "any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'GraphOnly')",
            vec![0, 1],
        ),
        (
            "any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'GraphOnly:0')",
            vec![0],
        ),
    ];
    for (filter, ids) in cases {
        let text = format!("MATCH (n) WHERE {filter} RETURN n.id AS id, n.name AS name, n.graphId AS gid, n.values AS rendered, n");
        let clauses = crate::parser::parse(&text).unwrap();
        let Clause::Match { patterns, .. } = &clauses[0] else {
            unreachable!()
        };
        let Clause::Where(predicate) = &clauses[1] else {
            unreachable!()
        };
        assert!(
            ScanPlan::build(patterns, Some(predicate)).is_some(),
            "optimized path: {text}"
        );
        let actual = ex.execute_clauses(&clauses, None).unwrap();
        let expected = ex.execute_clauses(&generic_oracle(&clauses), None).unwrap();
        assert_eq!(actual.columns, expected.columns, "{text}");
        assert_eq!(
            format!("{:?}", actual.rows),
            format!("{:?}", expected.rows),
            "typed rows/provenance: {text}"
        );
        let expected_ids: Vec<_> = if filter.contains("GraphOnly") {
            ids.clone()
        } else {
            ids.iter().chain(&ids).copied().collect()
        };
        assert_eq!(
            actual
                .rows
                .iter()
                .map(|row| match row.get("id").unwrap() {
                    Value::Int(id) => *id,
                    other => panic!("{other:?}"),
                })
                .collect::<Vec<_>>(),
            expected_ids,
            "{text}"
        );
        for (a, e) in actual.rows.iter().zip(&expected.rows) {
            assert_eq!(
                materialize(a.get("n").unwrap(), &ex),
                materialize(e.get("n").unwrap(), &ex),
                "{text}"
            );
            let id = a.get("id").unwrap();
            assert_eq!(
                a.get("name").unwrap().as_str(),
                Some(if matches!(id, Value::Int(0)) {
                    "Marker"
                } else {
                    "Other"
                })
            );
            if filter.contains("GraphOnly") {
                assert_eq!(a.get("gid").unwrap().as_str(), Some("GraphOnly"));
            }
        }
        if filter.contains("' beta'") {
            assert!(actual.rows[0]
                .get("rendered")
                .unwrap()
                .as_str()
                .unwrap()
                .starts_with("{alpha=x, beta=y, values=x, nested="));
        }
    }
}

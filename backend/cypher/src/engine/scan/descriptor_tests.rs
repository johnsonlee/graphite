use super::*;
use crate::ast::Clause;
use crate::engine::Source;
use std::sync::Arc;

#[test]
fn descriptor_formatting_keeps_return_types_primitives_arrays_and_nested_classes() {
    let strings = [
        "NoJavaOwner",
        "method",
        "boolean",
        "byte",
        "char",
        "double",
        "float",
        "int",
        "long",
        "short",
        "java.lang.String[][]",
        "pkg.Outer$Inner[]",
        "void",
    ];
    let key = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
    let mut text = String::from("discard old buffer");
    SignatureMemo::write_text(&key, true, |id| strings[id as usize], &mut text);
    assert_eq!(text, "(ZBCDFIJS[[Ljava/lang/String;[Lpkg/Outer$Inner;)V");
    SignatureMemo::write_text(&[0, 1, 10], true, |id| strings[id as usize], &mut text);
    assert_eq!(text, "()[[Ljava/lang/String;");
    SignatureMemo::write_text(&[0, 1, 10], false, |id| strings[id as usize], &mut text);
    assert_eq!(text, "NoJavaOwner.method()");
    SignatureMemo::write_text(
        &[0, 1, 7, 11, 10],
        false,
        |id| strings[id as usize],
        &mut text,
    );
    assert_eq!(text, "NoJavaOwner.method(int,pkg.Outer$Inner[])");
}

#[test]
fn keys_expansion_includes_both_descriptors_and_keeps_callsite_raw() {
    let clauses = crate::parser::parse(
        "MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'java') RETURN n",
    )
    .unwrap();
    let [Clause::Match { patterns, .. }, Clause::Where(filter), ..] = clauses.as_slice() else {
        panic!("MATCH WHERE required")
    };
    let plan = ScanPlan::build(patterns, Some(filter)).unwrap();
    let mut leaves = Vec::new();
    plan.tree.leaves(&mut leaves);
    for property in ["caller_descriptor", "callee_descriptor"] {
        let matching: Vec<_> = leaves.iter().filter(|p| p.prop() == property).collect();
        assert_eq!(matching.len(), 1);
        let p = matching[0];
        assert_eq!(p.literal, "java");
        assert_eq!(p.op, PushOp::Contains);
        assert!(p.from_keys && p.via_to_string);
        assert!(ALL_KEYS.contains(&property));
        assert_eq!(leaf_exposure(TAG_CALL_SITE_NODE, p), Exposure::Raw);
    }
    let TagPlan::CallSite(tree) = tag_plan(&plan.tree, TAG_CALL_SITE_NODE, &|_| false, &|_| {
        Fold::Known(false)
    }) else {
        panic!("descriptors must not force decoding every CallSite")
    };
    assert!(needs_raw_sweep(&tree));
    let mut callsite_leaves = Vec::new();
    tree.leaves(&mut callsite_leaves);
    assert_eq!(callsite_leaves.len(), 8);
}

#[test]
fn real_callsite_return_descriptor_only_match_is_not_omitted() {
    let Some(directory) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        eprintln!("GRAPHITE_INDEX_FIXTURE unset; skipping real descriptor-only query");
        return;
    };
    let graph = Arc::new(Graph::load(std::path::Path::new(&directory)).unwrap());
    let only_descriptor = graph.ids_by_tag(TAG_CALL_SITE_NODE).iter().find_map(|&id| {
        let node = graph.node(id).unwrap();
        let properties = crate::engine::props::node_properties(&graph, &node);
        let has_java = |property: &str| {
            properties
                .get(property)
                .and_then(Value::as_str)
                .is_some_and(|text| text.contains("java"))
        };
        ((has_java("caller_descriptor") || has_java("callee_descriptor"))
            && [
                "caller_class",
                "caller_name",
                "callee_class",
                "callee_name",
                "caller_signature",
                "callee_signature",
            ]
            .iter()
            .all(|property| !has_java(property)))
        .then_some((id, properties))
    });
    let (id, properties) = only_descriptor.expect("fixture needs a java return-type-only CallSite");
    for property in properties.keys() {
        assert!(
            ALL_KEYS.contains(&property.as_str()),
            "missing map key {property}"
        );
    }
    let ex = Executor::new(
        vec![Source {
            id: Arc::from("real"),
            graph,
        }],
        true,
    );
    for inner in [
        "toString(n[k]) CONTAINS 'java'",
        "toString(n[k]) CONTAINS 'absentDescriptorProbe87429' OR toString(n[k]) CONTAINS 'java'",
    ] {
        let query = format!("MATCH (n:CallSite) WHERE n.id >= {id} AND n.id <= {id} AND any(k IN keys(n) WHERE {inner}) RETURN n.id AS id, n.caller_descriptor AS caller, n.callee_descriptor AS callee");
        let clauses = crate::parser::parse(&query).unwrap();
        let [Clause::Match { patterns, .. }, Clause::Where(filter), ..] = clauses.as_slice() else {
            panic!("MATCH WHERE required")
        };
        assert!(crate::engine::id_candidate::IdCandidatePlan::build(
            patterns,
            Some(filter),
            &Row::new(),
        )
        .is_none());
        let plan = ScanPlan::build(patterns, Some(filter)).expect("must exercise string scan");
        let TagPlan::CallSite(tree) = tag_plan(&plan.tree, TAG_CALL_SITE_NODE, &|_| false, &|_| {
            Fold::Known(false)
        }) else {
            panic!("must use raw CallSite candidates before checking the id bounds")
        };
        let mut leaves = Vec::new();
        tree.leaves(&mut leaves);
        for property in ["caller_descriptor", "callee_descriptor"] {
            assert!(leaves
                .iter()
                .any(|p| p.prop() == property && p.literal == "java"));
        }
        assert!(needs_raw_sweep(&tree));
        let result = ex.execute(&query, None).unwrap();
        assert_eq!(result.rows.len(), 1, "descriptor-only node {id}: {query}");
        assert!(matches!(result.rows[0].get("id"), Some(Value::Int(value)) if *value == id as i64));
        assert_eq!(
            result.rows[0].get("caller").unwrap().as_str(),
            properties.get("caller_descriptor").unwrap().as_str()
        );
        assert_eq!(
            result.rows[0].get("callee").unwrap().as_str(),
            properties.get("callee_descriptor").unwrap().as_str()
        );
    }
}

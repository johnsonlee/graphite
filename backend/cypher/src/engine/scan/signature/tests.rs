use super::super::{LeafShape, ScanPlan};
use super::*;
use crate::ast::{Clause, Expr, Literal};
use crate::engine::{Executor, Source};
use crate::materialize::materialize;
use crate::CypherError;
use graphite_storage::node::TAG_ANNOTATION_NODE;
use std::cell::Cell;
use std::sync::Arc;

fn predicate(property: &'static str, literal: &str, test: usize) -> StringPredicate {
    StringPredicate {
        property: property.into(),
        shape: LeafShape::Text,
        op: PushOp::Contains,
        literal: literal.into(),
        transform: Transform::None,
        regex: None,
        via_to_string: true,
        from_keys: true,
        trigrams: Arc::new(None),
        signature: 0,
        test,
    }
}

fn leaf(property: &'static str, literal: &str, test: usize) -> PredTree {
    PredTree::Leaf(predicate(property, literal, test))
}

fn tree_shape(tree: &PredTree) -> String {
    match tree {
        PredTree::Leaf(p) => format!("{}:{}:{}", p.prop(), p.literal, p.test),
        PredTree::Or(children) => format!(
            "OR({})",
            children
                .iter()
                .map(tree_shape)
                .collect::<Vec<_>>()
                .join(",")
        ),
        PredTree::And(children) => format!(
            "AND({})",
            children
                .iter()
                .map(tree_shape)
                .collect::<Vec<_>>()
                .join(",")
        ),
    }
}

#[test]
fn dictionary_memo_shares_positive_and_negative_proofs_across_tags() {
    let mut memo = DictionaryMemo::new(3);
    let reads = Cell::new(0);
    for expected in [false, true] {
        let test = usize::from(expected);
        assert_eq!(
            memo.get_or_try_insert(test, || {
                reads.set(reads.get() + 1);
                Ok(expected)
            })
            .unwrap(),
            expected
        );
        assert_eq!(
            memo.get_or_try_insert(test, || panic!("cached test must not rescan"))
                .unwrap(),
            expected
        );
    }
    assert_eq!(reads.get(), 2);
    assert_eq!(memo.absent, vec![Some(false), Some(true), None]);
}

#[test]
fn dictionary_memo_propagates_failures_without_caching_partial_proofs() {
    let mut memo = DictionaryMemo::new(1);
    assert!(matches!(
        memo.get_or_try_insert(0, || Err(CypherError::Cancelled)),
        Err(CypherError::Cancelled)
    ));
    assert_eq!(memo.absent, vec![None]);
    assert!(matches!(
        memo.get_or_try_insert(0, || Err(CypherError::Timeout(17))),
        Err(CypherError::Timeout(17))
    ));
    assert_eq!(memo.absent, vec![None]);
    assert!(memo.get_or_try_insert(0, || Ok(true)).unwrap());
    assert_eq!(memo.absent, vec![Some(true)]);
}

#[test]
fn terms_are_shared_by_test_across_properties_tags_and_nested_trees() {
    let tree = PredTree::Or(vec![
        leaf("caller_signature", "Needle", 4),
        PredTree::And(vec![
            leaf("callee_signature", "Needle", 4),
            leaf("callee_signature", "Other", 5),
        ]),
        leaf("caller_name", "Normal", 6),
    ]);
    let local = leaf("method", "Needle", 4);
    let mut terms = Vec::new();
    collect_terms(&tree, TAG_CALL_SITE_NODE, &mut terms);
    collect_terms(&local, TAG_LOCAL_VARIABLE, &mut terms);
    assert_eq!(
        terms
            .iter()
            .map(|p| (p.test, p.literal.as_str()))
            .collect::<Vec<_>>(),
        vec![(4, "Needle"), (5, "Other")]
    );
}

#[test]
fn only_untransformed_nonempty_contains_or_prefix_without_signature_separators_is_eligible() {
    for (tag, property) in [
        (TAG_CALL_SITE_NODE, "caller_signature"),
        (TAG_CALL_SITE_NODE, "callee_signature"),
        (TAG_LOCAL_VARIABLE, "method"),
        (TAG_PARAMETER_NODE, "method"),
        (TAG_RETURN_NODE, "method"),
    ] {
        for literal in ["Missing", "类型", "[]", "$nested", "a b", ":"] {
            assert_eq!(
                component_literal(&predicate(property, literal, 0), tag),
                Some(literal)
            );
            let mut prefix = predicate(property, literal, 0);
            prefix.op = PushOp::StartsWith;
            assert_eq!(component_literal(&prefix, tag), Some(literal));
        }
        for literal in [
            "",
            ".",
            "(",
            ",",
            ")",
            "Class.method",
            "method(Type",
            "A,B",
            "Type)",
        ] {
            assert_eq!(
                component_literal(&predicate(property, literal, 0), tag),
                None
            );
        }
        for op in [PushOp::Equals, PushOp::EndsWith, PushOp::Regex] {
            let mut p = predicate(property, "Missing", 0);
            p.op = op;
            assert_eq!(component_literal(&p, tag), None);
        }
        for shape in [LeafShape::Numeric, LeafShape::Member] {
            let mut p = predicate(property, "Missing", 0);
            p.shape = shape;
            assert_eq!(component_literal(&p, tag), None);
        }
        let mut p = predicate(property, "missing", 0);
        p.transform = Transform::Lowercase;
        assert_eq!(component_literal(&p, tag), None);
    }
}

#[test]
fn actual_type_is_a_complete_dictionary_string_only_on_return_nodes() {
    for literal in ["java.lang.String[]", "int", "类型", "(", "L"] {
        for op in [PushOp::Contains, PushOp::StartsWith] {
            let mut p = predicate("actual_type", literal, 0);
            p.op = op;
            assert_eq!(component_literal(&p, TAG_RETURN_NODE), Some(literal));
            for tag in [
                TAG_CALL_SITE_NODE,
                TAG_LOCAL_VARIABLE,
                TAG_PARAMETER_NODE,
                TAG_ANNOTATION_NODE,
            ] {
                assert_eq!(component_literal(&p, tag), None);
            }
        }
    }
    assert_eq!(
        component_literal(&predicate("actual_type", "", 0), TAG_RETURN_NODE),
        None
    );
    let tree = leaf("actual_type", "Missing", 0);
    assert!(prune_tree(&tree, TAG_RETURN_NODE, &HashSet::from([0])).is_none());
}

#[test]
fn descriptor_proof_keeps_wrappers_primitive_sequences_and_transformed_components() {
    for property in ["caller_descriptor", "callee_descriptor"] {
        for literal in [
            "Object",
            "java",
            "SalesRank",
            "OptionComparison",
            "类型",
            "a b",
        ] {
            assert_eq!(
                component_literal(&predicate(property, literal, 0), TAG_CALL_SITE_NODE),
                Some(literal)
            );
        }
        for literal in [
            "",
            "LThing",
            "Local",
            "II",
            "BIZ",
            "V",
            "[I",
            "()",
            "Foo;",
            "java/lang",
            "java.lang",
        ] {
            let p = predicate(property, literal, 0);
            assert_eq!(component_literal(&p, TAG_CALL_SITE_NODE), None);
            assert!(
                prune_tree(&PredTree::Leaf(p), TAG_CALL_SITE_NODE, &HashSet::from([0])).is_some()
            );
        }
    }
}

#[test]
fn every_eligible_substring_of_composed_descriptors_occurs_in_a_dictionary_component() {
    // Exhaustive substrings of primitive, reference, array and Unicode examples:
    // boundaries and the synthesized primitive/L codes cannot create a false proof.
    let types = [
        "int",
        "boolean",
        "void",
        "long",
        "byte[]",
        "int[][]",
        "Foo",
        "LThing",
        "java.lang.String",
        "pkg.类型[]",
    ];
    for first in types {
        for second in types {
            let mut descriptor = String::from("(");
            graphite_storage::node::push_type_descriptor(first, &mut descriptor);
            graphite_storage::node::push_type_descriptor(second, &mut descriptor);
            descriptor.push(')');
            graphite_storage::node::push_type_descriptor(second, &mut descriptor);
            let bounds: Vec<_> = descriptor
                .char_indices()
                .map(|(i, _)| i)
                .chain(std::iter::once(descriptor.len()))
                .collect();
            for (i, &start) in bounds.iter().enumerate() {
                for &end in &bounds[i + 1..] {
                    let literal = &descriptor[start..end];
                    if component_literal(
                        &predicate("callee_descriptor", literal, 0),
                        TAG_CALL_SITE_NODE,
                    )
                    .is_some()
                    {
                        assert!(
                            first.contains(literal) || second.contains(literal),
                            "{literal:?} synthesized from {first:?}, {second:?}: {descriptor}"
                        );
                    }
                }
            }
        }
    }
}

#[test]
fn pruning_preserves_normal_dynamic_and_synthetic_properties_with_the_same_test() {
    let absent = HashSet::from([3]);
    for (tag, property) in [
        (TAG_CALL_SITE_NODE, "caller_name"),
        (TAG_LOCAL_VARIABLE, "name"),
        (TAG_ANNOTATION_NODE, "method"),
        (TAG_ANNOTATION_NODE, "caller_signature"),
        (TAG_ANNOTATION_NODE, "custom_attribute"),
        (TAG_CALL_SITE_NODE, "graphId"),
        (TAG_CALL_SITE_NODE, "elementId"),
        (TAG_CALL_SITE_NODE, "qualifiedId"),
        (TAG_CALL_SITE_NODE, "method"),
        (TAG_LOCAL_VARIABLE, "callee_signature"),
    ] {
        let original = leaf(property, "Missing", 3);
        let kept = prune_tree(&original, tag, &absent).expect("outside signature proof");
        assert_eq!(tree_shape(&kept), tree_shape(&original));
    }
    for literal in ["", "Class.method", "method(Type", "A,B", "Type)"] {
        let original = leaf("caller_signature", literal, 3);
        let kept = prune_tree(&original, TAG_CALL_SITE_NODE, &absent).unwrap();
        assert_eq!(tree_shape(&kept), tree_shape(&original));
    }
}

#[test]
fn pruning_drops_false_or_branches_but_invalidates_their_whole_and_branch() {
    let absent = HashSet::from([0]);
    let missing = leaf("caller_signature", "Missing", 0);
    let normal = leaf("caller_name", "Missing", 0);
    let present = leaf("callee_signature", "Present", 1);
    let nested = PredTree::Or(vec![
        PredTree::And(vec![missing.clone(), normal.clone()]),
        PredTree::And(vec![
            PredTree::Or(vec![missing.clone(), present.clone()]),
            normal.clone(),
        ]),
        present.clone(),
    ]);
    let result = prune_tree(&nested, TAG_CALL_SITE_NODE, &absent).unwrap();
    assert_eq!(
        tree_shape(&result),
        "OR(AND(callee_signature:Present:1,caller_name:Missing:0),callee_signature:Present:1)"
    );
    assert!(prune_tree(
        &PredTree::Or(vec![missing.clone()]),
        TAG_CALL_SITE_NODE,
        &absent
    )
    .is_none());
    assert!(prune_tree(
        &PredTree::And(vec![normal.clone(), missing.clone()]),
        TAG_CALL_SITE_NODE,
        &absent
    )
    .is_none());
    let result = prune_tree(
        &PredTree::Or(vec![missing, normal]),
        TAG_CALL_SITE_NODE,
        &absent,
    )
    .unwrap();
    assert_eq!(tree_shape(&result), "caller_name:Missing:0");
}

#[test]
fn real_multi_graph_keys_search_matches_generic_rows_order_and_materialized_properties() {
    let Some(dir) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        eprintln!("GRAPHITE_INDEX_FIXTURE unset; skipping signature differential executor test");
        return;
    };
    let graph = Arc::new(Graph::load(std::path::Path::new(&dir)).unwrap());
    let graph_only = "SignatureProofGraphOnly87429";
    assert!((0..graph.strings().len()).all(|id| !graph.strings().get(id).contains(graph_only)));
    let ex = Executor::new(
        ["first", graph_only]
            .into_iter()
            .map(|id| Source {
                id: Arc::from(id),
                graph: graph.clone(),
            })
            .collect(),
        true,
    )
    .with_probe();
    let cases = [
        "toString(n[k]) CONTAINS 'SignatureProofAbsent87429'",
        "toString(n[k]) CONTAINS 'option comparison' OR toString(n[k]) CONTAINS 'OptionComparison' OR toString(n[k]) CONTAINS 'popularBadge' OR toString(n[k]) CONTAINS 'SalesRank' OR toString(n[k]) CONTAINS 'OptionTable'",
        "toString(n[k]) CONTAINS 'SignatureProofAbsent87429' OR toString(n[k]) CONTAINS 'java'",
        "toString(n[k]) CONTAINS 'SignatureProofAbsent87429' OR toString(n[k]) CONTAINS 'java.'",
        "toString(n[k]) CONTAINS '' OR toString(n[k]) CONTAINS 'SignatureProofAbsent87429'",
        "toString(n[k]) CONTAINS 'SignatureProofGraphOnly87429'",
        "toString(n[k]) STARTS WITH 'SignatureProofAbsent87429'",
        "toString(n[k]) STARTS WITH 'java'",
    ];
    for (case, inner) in cases.into_iter().enumerate() {
        let query = format!("MATCH (n) WHERE any(k IN keys(n) WHERE {inner}) RETURN labels(n) AS labels, n LIMIT 200");
        let original = crate::parser::parse(&query).unwrap();
        let mut generic = original.clone();
        let [Clause::Match { patterns, .. }, Clause::Where(where_clause), ..] = original.as_slice()
        else {
            panic!("plain MATCH WHERE expected: {original:?}")
        };
        assert!(
            ScanPlan::build(patterns, Some(where_clause)).is_some(),
            "{query}"
        );
        let Clause::Where(Expr::PredicateFunction {
            name,
            variable,
            list,
            predicate,
        }) = &mut generic[1]
        else {
            panic!("ANY predicate expected")
        };
        assert_eq!(name.to_lowercase(), "any");
        assert_eq!(variable, "k");
        assert_eq!(
            list.as_ref(),
            &Expr::FunctionCall {
                name: "keys".into(),
                distinct: false,
                args: vec![Expr::Variable("n".into())],
            }
        );
        let inner = predicate.take().expect("ANY filter");
        *predicate = Some(Box::new(Expr::Or(
            inner,
            Box::new(Expr::Literal(Literal::Bool(false))),
        )));
        let Clause::Where(generic_where) = &generic[1] else {
            unreachable!()
        };
        assert!(
            ScanPlan::build(patterns, Some(generic_where)).is_none(),
            "oracle must bypass scan optimization"
        );
        assert_eq!(original[0], generic[0]);
        assert_eq!(original[2..], generic[2..]);
        let actual = ex.execute_clauses(&original, None).unwrap();
        let expected = ex.execute_clauses(&generic, None).unwrap();
        assert_eq!(actual.columns, expected.columns, "columns: {query}");
        assert_eq!(actual.rows.len(), expected.rows.len(), "row count: {query}");
        assert_eq!(actual.more, expected.more, "probe: {query}");
        assert_eq!(
            format!("{:?}", actual.compact),
            format!("{:?}", expected.compact),
            "compact result: {query}"
        );
        for (index, (actual_row, expected_row)) in
            actual.rows.iter().zip(&expected.rows).enumerate()
        {
            if format!("{actual_row:?}") != format!("{expected_row:?}") {
                if let Some(crate::value::Value::Node(node)) = expected_row.get("n") {
                    eprintln!(
                        "expected node's full query-visible properties: {:?}",
                        crate::engine::props::node_properties(&graph, &ex.node(*node).unwrap())
                    );
                }
            }
            assert_eq!(
                format!("{actual_row:?}"), format!("{expected_row:?}"),
                "first differing typed row {index}, including provenance: {query}; actual node: {:?}; expected node: {:?}",
                materialize(actual_row.get("n").unwrap(), &ex),
                materialize(expected_row.get("n").unwrap(), &ex)
            );
        }
        let bodies = |result: &crate::engine::QueryResult| {
            result
                .rows
                .iter()
                .map(|row| {
                    row.iter()
                        .map(|(key, value)| (key.clone(), materialize(value, &ex)))
                        .collect::<Vec<_>>()
                })
                .collect::<Vec<_>>()
        };
        for (index, (actual_body, expected_body)) in
            bodies(&actual).iter().zip(bodies(&expected)).enumerate()
        {
            assert_eq!(
                actual_body, &expected_body,
                "first differing materialized row {index}: {query}"
            );
        }
        if case == 0 {
            assert!(
                actual.rows.is_empty(),
                "absent term must exhaust both graphs"
            );
        }
        if query.contains(graph_only) {
            assert!(
                !actual.rows.is_empty(),
                "synthetic graph identity still matches"
            );
            for row in &actual.rows {
                assert_eq!(crate::engine::QueryResult::graph_ids(row), vec![graph_only]);
            }
        }
    }
}

#[test]
fn real_multi_graph_callsite_and_return_scans_match_independent_generic_execution() {
    let Some(dir) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        eprintln!("GRAPHITE_INDEX_FIXTURE unset; skipping composed-path differential test");
        return;
    };
    let graph = Arc::new(Graph::load(std::path::Path::new(&dir)).unwrap());
    let ex = Executor::new(
        ["first", "second"]
            .into_iter()
            .map(|id| Source {
                id: Arc::from(id),
                graph: graph.clone(),
            })
            .collect(),
        true,
    )
    .with_probe();
    let cases = [
        ("CallSite", "n.callee_signature CONTAINS 'SignatureProofAbsent87429'", true),
        ("CallSite", "n.caller_signature STARTS WITH 'SignatureProofAbsent87429'", true),
        ("CallSite", "n.callee_signature CONTAINS 'java'", false),
        ("CallSite", "n.callee_signature STARTS WITH 'java'", false),
        ("CallSite", "n.callee_descriptor CONTAINS 'SignatureProofAbsent87429' OR n.callee_descriptor CONTAINS 'java'", false),
        ("Return", "n.actual_type CONTAINS 'SignatureProofAbsent87429'", true),
        ("Return", "n.actual_type STARTS WITH 'java'", false),
    ];
    for (label, filter, absent) in cases {
        let query = format!("MATCH (n:{label}) WHERE {filter} RETURN n LIMIT 200");
        let original = crate::parser::parse(&query).unwrap();
        let [Clause::Match { patterns, .. }, Clause::Where(where_clause), ..] = original.as_slice()
        else {
            panic!("MATCH WHERE expected");
        };
        let plan = ScanPlan::build(patterns, Some(where_clause)).expect("optimized plan");
        let mut memo = DictionaryMemo::new(plan.tests);
        if label == "CallSite" {
            assert_eq!(plan.tags, vec![TAG_CALL_SITE_NODE]);
            let (tree, _) = plan.call_site.as_ref().expect("pure CallSite path");
            assert_eq!(
                prune_callsite(tree, &graph, &CancelToken::default(), &mut memo)
                    .unwrap()
                    .is_none(),
                absent,
                "{query}"
            );
        } else {
            assert_eq!(plan.tags, vec![TAG_RETURN_NODE]);
            if absent {
                let pruned =
                    prune_plans(&plan.tag_plans, &graph, &CancelToken::default(), &mut memo)
                        .unwrap()
                        .expect("return proof");
                assert!(matches!(pruned[TAG_RETURN_NODE as usize], TagPlan::Skip));
            }
        }
        let mut generic = original.clone();
        generic[1] = Clause::Where(Expr::Or(
            Box::new(where_clause.clone()),
            Box::new(Expr::Literal(Literal::Bool(false))),
        ));
        let Clause::Where(generic_where) = &generic[1] else {
            unreachable!()
        };
        assert!(
            ScanPlan::build(patterns, Some(generic_where)).is_none(),
            "oracle bypass: {query}"
        );
        let actual = ex.execute_clauses(&original, None).unwrap();
        let expected = ex.execute_clauses(&generic, None).unwrap();
        assert_eq!(actual.columns, expected.columns, "{query}");
        assert_eq!(actual.more, expected.more, "{query}");
        assert_eq!(
            format!("{:?}", actual.rows),
            format!("{:?}", expected.rows),
            "typed rows and provenance: {query}"
        );
        let bodies = |result: &crate::engine::QueryResult| {
            result
                .rows
                .iter()
                .map(|row| materialize(row.get("n").unwrap(), &ex))
                .collect::<Vec<_>>()
        };
        assert_eq!(
            bodies(&actual),
            bodies(&expected),
            "materialized rows: {query}"
        );
        if absent {
            assert!(actual.rows.is_empty(), "{query}");
        } else if label == "CallSite" {
            assert!(
                !actual.rows.is_empty(),
                "fixture needs java CallSites: {query}"
            );
        }
    }
    let mut memo = DictionaryMemo::new(1);
    let p = predicate("callee_signature", "SignatureProofAbsent87429", 0);
    assert!(memo.absent(&graph, &p, &CancelToken::default()).unwrap());
    let cancel = CancelToken::default();
    cancel.cancel();
    assert!(matches!(
        memo.absent(&graph, &p, &cancel),
        Err(CypherError::Cancelled)
    ));
}

use super::*;
use crate::engine::{CancelToken, QueryResult, Source};
use graphite_storage::MethodDesc;
use std::sync::atomic::Ordering;
use std::sync::Arc;

const PRESENCE: &str = "MATCH (m:Method) RETURN m.graphId AS graphId, \
    m.generic_return_type IS NOT NULL AS returnRendered, \
    m.return_type_info IS NOT NULL AS returnStructured, \
    m.generic_parameter_types IS NOT NULL AS parametersRendered, \
    m.parameter_type_info IS NOT NULL AS parametersStructured, \
    m.type_parameters IS NOT NULL AS formalParameters, count(*) AS members";

fn fixture() -> Option<Graph> {
    let Some(path) = std::env::var_os("GRAPHITE_TYPES_FIXTURE") else {
        assert!(std::env::var_os("GRAPHITE_REQUIRE_DUAL_TYPES_FIXTURES").is_none());
        return None;
    };
    Some(Graph::load(std::path::Path::new(&path)).unwrap())
}

fn identities(graph: &Graph) -> (MethodDesc, MethodDesc) {
    let table = graph.declared_types().unwrap();
    let bound = graph
        .methods()
        .iter()
        .find(|method| table.method(method, graph.strings()).is_some())
        .unwrap()
        .clone();
    let mut unbound = bound.clone();
    unbound.name = bound.declaring_class;
    assert!(table.method(&unbound, graph.strings()).is_none());
    (bound, unbound)
}

fn source(id: &str, graph: Graph) -> Source {
    Source {
        id: Arc::from(id),
        graph: Arc::new(graph),
    }
}

fn rendered(result: &QueryResult) -> (Vec<String>, Vec<String>) {
    (
        result.columns.clone(),
        result
            .rows
            .iter()
            .map(|row| {
                format!(
                    "{:?}|{:?}",
                    result
                        .columns
                        .iter()
                        .map(|key| row.get(key))
                        .collect::<Vec<_>>(),
                    QueryResult::graph_ids(row)
                )
            })
            .collect(),
    )
}

fn parity(sources: &[Source], query: &str) {
    let fast = Executor::new(sources.to_vec(), true)
        .execute(query, None)
        .unwrap();
    let plain = Executor::new(sources.to_vec(), true)
        .without_partitioning()
        .execute(query, None)
        .unwrap();
    assert_eq!(rendered(&fast), rendered(&plain), "{query}");
}

#[test]
fn method_dependencies_allow_only_unshadowed_generic_presence() {
    let context = TagCtx {
        tag: Some(METHOD_TAG),
        keys: &[],
        declared_keys: true,
        columns: &[],
    };
    let mut scope = Scope {
        cross: true,
        nodes: vec![("m", &context)],
        rel: None,
        bound: Vec::new(),
    };
    let classify = |text: &str, scope: &Scope| {
        let clauses = crate::parser::parse(&format!("RETURN {text}")).unwrap();
        let Clause::Return {
            items: Some(items), ..
        } = &clauses[0]
        else {
            panic!("RETURN")
        };
        dep(&items[0].expr, scope)
    };
    for key in super::super::props::GENERIC_METHOD_KEYS {
        assert_eq!(classify(&format!("m.{key} IS NULL"), &scope), Dep::KEYS);
        assert_eq!(classify(&format!("m.{key} IS NOT NULL"), &scope), Dep::KEYS);
        assert_eq!(classify(&format!("m.{key}"), &scope), Dep::CONTENT);
        assert_eq!(
            classify(&format!("m['{key}'] IS NULL"), &scope),
            Dep::CONTENT
        );
    }
    assert_eq!(classify("m.graphId", &scope), Dep::SOURCE);
    assert_eq!(classify("keys(m)", &scope), Dep::CONTENT);
    scope.bound.push(("m".into(), Dep::CONTENT));
    assert_eq!(classify("m.graphId", &scope), Dep::CONTENT);
    assert_eq!(
        classify("m.generic_return_type IS NULL", &scope),
        Dep::CONTENT
    );
}

#[test]
fn method_presence_partitions_preserve_full_partial_absent_sources_and_aggregates() {
    let Some(mut full) = fixture() else { return };
    let (bound, unbound) = identities(&full);
    full.metadata.methods = vec![bound.clone(), bound.clone()];
    let mut partial = fixture().unwrap();
    partial.metadata.methods = vec![unbound.clone(), bound.clone(), bound.clone()];
    let mut absent = fixture().unwrap();
    absent.metadata.methods = vec![bound, unbound];
    absent.update_declared_types(|table| *table = None);
    let mut empty = fixture().unwrap();
    empty.metadata.methods.clear();
    let sources = vec![
        source("full", full),
        source("partial", partial),
        source("absent", absent),
        source("empty", empty),
    ];
    for query in [
        PRESENCE,
        "MATCH (m:Method) RETURN m.generic_return_type IS NULL AS missing, count(*) AS n",
        "MATCH (m:Method) RETURN m.type_parameters IS NULL AS missing, count(m) AS n, sum(2) AS s, avg(3) AS a ORDER BY n DESC LIMIT 2",
        "MATCH (m:Method) RETURN DISTINCT m.return_type_info IS NOT NULL AS present",
        "MATCH (m:Method) WHERE m.parameter_type_info IS NOT NULL RETURN m.graphId AS g, count(*) AS n",
        "MATCH (m:Method) WITH m.type_parameters IS NULL AS missing WHERE missing RETURN missing, count(*) AS n",
        "MATCH (m:Method) UNWIND [1, 2] AS x RETURN m.type_parameters IS NULL AS missing, x, count(*) AS n ORDER BY x DESC, n DESC",
        "MATCH (m:Method) RETURN CASE WHEN m.generic_return_type IS NULL THEN 'missing' ELSE 'present' END AS state, count(*) AS n",
        "MATCH (m:Method) RETURN graphId(m) AS graph, labels(m) AS labels, m.type_parameters IS NULL AS missing, count(*) AS n",
    ] {
        let ex = Executor::new(sources.clone(), true);
        let clauses = crate::parser::parse(query).unwrap();
        let (plan, _) = PartitionPlan::build(&ex, &clauses).expect(query);
        assert!(matches!(plan.shape, Shape::Method { .. }), "{query}");
        parity(&sources, query);
    }
    let ex = Executor::new(sources, true);
    let result = ex.execute(PRESENCE, None).unwrap();
    let expected = [
        ("full", true, 2),
        ("partial", false, 1),
        ("partial", true, 2),
        ("absent", false, 2),
    ];
    assert_eq!(result.rows.len(), expected.len());
    for (row, (graph, present, count)) in result.rows.iter().zip(expected) {
        assert_eq!(row.get("graphId").and_then(Value::as_str), Some(graph));
        for key in [
            "returnRendered",
            "returnStructured",
            "parametersRendered",
            "parametersStructured",
            "formalParameters",
        ] {
            assert_eq!(
                row.get(key).and_then(Value::as_bool),
                Some(present),
                "{graph}: {key}"
            );
        }
        assert!(matches!(row.get("members"), Some(Value::Int(n)) if *n == count));
        assert_eq!(QueryResult::graph_ids(row), vec![graph.to_owned()]);
    }
    let clauses = crate::parser::parse(PRESENCE).unwrap();
    let (plan, _) = PartitionPlan::build(&ex, &clauses).expect("method presence weighted plan");
    let rows = plan.rows(&ex, &Evaluator::new(&ex, &ex.params)).unwrap();
    assert_eq!(rows.len(), 4);
    assert_eq!(rows.iter().map(row_weight).sum::<i64>(), 7);
}

#[test]
fn method_presence_keeps_content_alias_shadow_and_error_paths_conservative() {
    let Some(mut graph) = fixture() else { return };
    let (bound, unbound) = identities(&graph);
    graph.metadata.methods = vec![unbound, bound];
    let sources = vec![source("one", graph)];
    for query in [
        "MATCH (m:Method) WITH m AS alias RETURN alias.type_parameters IS NULL AS missing, count(*) AS n",
        "MATCH (m:Method) UNWIND [null, {type_parameters: []}] AS m RETURN m.type_parameters IS NULL AS missing, count(*) AS n",
        "MATCH (m:Method) UNWIND [{graphId: 'shadow'}] AS m RETURN m.graphId AS g, count(*) AS n",
        "MATCH (m:Method) RETURN m.name AS name, m.type_parameters IS NULL AS missing, count(*) AS n",
        "MATCH (m:Method) RETURN m.generic_return_type AS t, count(*) AS n",
        "MATCH (m:Method) RETURN m['type_parameters'] IS NULL AS missing, count(*) AS n",
        "MATCH (m:Method) RETURN keys(m) AS keys, count(*) AS n",
        "MATCH (m:Method) WITH {type_parameters: []} AS m RETURN m.type_parameters IS NULL AS missing, count(*) AS n",
        "MATCH (m:Method) RETURN [m IN [null, {type_parameters: []}] | m.type_parameters IS NULL] AS flags, count(*) AS n",
    ] {
        let ex = Executor::new(sources.clone(), true);
        let clauses = crate::parser::parse(query).unwrap();
        assert!(PartitionPlan::build(&ex, &clauses).is_none(), "{query}");
        parity(&sources, query);
    }
    let query = "MATCH (m:Method) RETURN m.type_parameters IS NULL AS missing, unknownFunction(m) AS bad, count(*) AS n";
    for enabled in [false, true] {
        let mut ex = Executor::new(sources.clone(), true);
        ex.partition = enabled;
        assert!(ex.execute(query, None).is_err());
    }
}

#[test]
fn method_presence_recomputes_after_metadata_and_declaration_mutation_without_type_reads() {
    let Some(mut graph) = fixture() else { return };
    let (bound, unbound) = identities(&graph);
    graph.metadata.methods = vec![bound.clone(), bound.clone()];
    let mut ex = Executor::new(vec![source("mutable", graph)], true);
    let clauses = crate::parser::parse(PRESENCE).unwrap();
    let (plan, _) = PartitionPlan::build(&ex, &clauses).unwrap();
    let rows = plan.rows(&ex, &Evaluator::new(&ex, &ex.params)).unwrap();
    assert_eq!(rows.len(), 1);
    assert_eq!(row_weight(&rows[0]), 2);
    let graph = Arc::get_mut(&mut ex.sources[0].graph).unwrap();
    graph.metadata.methods = vec![unbound.clone(), bound, unbound];
    // Presence cannot consult expression backing: that would fail here. Keep
    // member identities, but remove the deliberately unavailable type values.
    graph.update_declared_types(|table| table.as_mut().unwrap().types.clear());
    let rows = plan.rows(&ex, &Evaluator::new(&ex, &ex.params)).unwrap();
    assert_eq!(rows.len(), 2);
    assert_eq!(rows.iter().map(row_weight).collect::<Vec<_>>(), vec![2, 1]);
    parity(&ex.sources, PRESENCE);
    Arc::get_mut(&mut ex.sources[0].graph)
        .unwrap()
        .update_declared_types(|table| *table = None);
    let rows = plan.rows(&ex, &Evaluator::new(&ex, &ex.params)).unwrap();
    assert_eq!(rows.len(), 1);
    assert_eq!(row_weight(&rows[0]), 3);
    parity(&ex.sources, PRESENCE);
}

#[test]
fn method_presence_scan_charges_once_per_method_even_without_declarations_and_cancels() {
    let Some(mut graph) = fixture() else { return };
    let (bound, _) = identities(&graph);
    graph.metadata.methods = vec![bound; 4097];
    graph.update_declared_types(|table| *table = None);
    let ex = Executor::new(vec![source("absent", graph)], true);
    let clauses = crate::parser::parse(PRESENCE).unwrap();
    let (plan, _) = PartitionPlan::build(&ex, &clauses).unwrap();
    ex.poll.store(0, Ordering::Relaxed);
    let rows = plan.rows(&ex, &Evaluator::new(&ex, &ex.params)).unwrap();
    assert_eq!(ex.poll.load(Ordering::Relaxed), 4097);
    assert_eq!(rows.len(), 1);
    assert_eq!(row_weight(&rows[0]), 4097);
    let token = CancelToken::new();
    token.cancel();
    let cancelled = Executor::new(ex.sources.clone(), true).with_cancel(token);
    assert!(plan
        .rows(&cancelled, &Evaluator::new(&cancelled, &cancelled.params))
        .is_err());
}

#[test]
fn method_presence_supports_single_graph_mode_and_empty_sources() {
    let Some(mut graph) = fixture() else { return };
    let (bound, unbound) = identities(&graph);
    graph.metadata.methods = vec![unbound, bound];
    let sources = vec![source("single", graph)];
    let query = "MATCH (m:Method) RETURN m.graphId AS propertyGraph, graphId(m) AS functionGraph, labels(m) AS labels, m.type_parameters IS NULL AS missing, count(*) AS n";
    let ex = Executor::new(sources.clone(), false);
    let clauses = crate::parser::parse(query).unwrap();
    let (plan, _) = PartitionPlan::build(&ex, &clauses).unwrap();
    assert!(matches!(plan.shape, Shape::Method { .. }));
    let fast = ex.execute(query, None).unwrap();
    let slow = Executor::new(sources, false)
        .without_partitioning()
        .execute(query, None)
        .unwrap();
    assert_eq!(rendered(&fast), rendered(&slow));
    assert_eq!(fast.rows.len(), 2);
    for (row, missing) in fast.rows.iter().zip([true, false]) {
        assert!(matches!(row.get("propertyGraph"), Some(Value::Null)));
        assert!(matches!(row.get("functionGraph"), Some(Value::Null)));
        assert_eq!(row.get("missing").and_then(Value::as_bool), Some(missing));
        let Some(Value::List(labels)) = row.get("labels") else {
            panic!("Method labels")
        };
        assert_eq!(labels.len(), 1);
        assert_eq!(labels[0].as_str(), Some("Method"));
        assert!(matches!(row.get("n"), Some(Value::Int(1))));
        assert!(QueryResult::graph_ids(row).is_empty());
    }
    let empty = Executor::new(Vec::new(), true);
    assert!(PartitionPlan::build(&empty, &clauses).is_none());
    assert_eq!(
        rendered(&empty.execute(query, None).unwrap()),
        rendered(
            &Executor::new(Vec::new(), true)
                .without_partitioning()
                .execute(query, None)
                .unwrap()
        )
    );
}

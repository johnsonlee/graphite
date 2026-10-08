use super::*;
use crate::ast::Clause;
use crate::engine::{CancelToken, Source};
use crate::materialize::materialize;
use crate::parser::parse;
use crate::CypherError;
use graphite_storage::node::{TAG_BOOLEAN_CONSTANT, TAG_CALL_SITE_NODE, TAG_INT_CONSTANT};
use graphite_storage::Graph;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

fn fixture() -> Option<(PathBuf, Arc<Graph>)> {
    let Some(dir) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        eprintln!("GRAPHITE_INDEX_FIXTURE unset; skipping real ID candidate tests");
        return None;
    };
    let path = PathBuf::from(dir);
    Some((path.clone(), Arc::new(Graph::load(&path).unwrap())))
}

fn plain_match_where(clauses: &[Clause]) -> (&[Pattern], &Expr) {
    let [Clause::Match {
        patterns,
        optional: false,
        where_clause: None,
    }, Clause::Where(predicate), tail @ ..] = clauses
    else {
        panic!("plain MATCH must parse as non-optional Match without inline predicate followed by a separate Where: {clauses:?}")
    };
    assert!(
        matches!(tail.first(), Some(Clause::Return { .. })),
        "RETURN must follow the standalone WHERE"
    );
    assert_eq!(
        clauses
            .iter()
            .filter(|clause| matches!(clause, Clause::Where(_)))
            .count(),
        1
    );
    (patterns, predicate)
}

fn eligible(query: &str, seed: &Row) -> Option<u32> {
    let clauses = parse(query).unwrap();
    let (patterns, predicate) = plain_match_where(&clauses);
    IdCandidatePlan::build(patterns, Some(predicate), seed).map(|plan| plan.wanted)
}

// AND true deliberately disables the new exact-equality plan without changing values,
// short-circuit errors or provenance. The old matcher remains the executor oracle.
fn compare_generic(ex: &Executor, query: &str, limit: Option<usize>) -> super::super::QueryResult {
    let clauses = parse(query).unwrap();
    let (_, original_predicate) = plain_match_where(&clauses);
    let mut generic = clauses.clone();
    let Clause::Where(clause) = &mut generic[1] else {
        panic!("standalone WHERE expected")
    };
    *clause = Expr::And(
        Box::new(clause.clone()),
        Box::new(Expr::Literal(Literal::Bool(true))),
    );
    let (patterns, predicate) = plain_match_where(&generic);
    let Expr::And(left, right) = predicate else {
        panic!("oracle predicate must be AND")
    };
    assert_eq!(left.as_ref(), original_predicate);
    assert_eq!(right.as_ref(), &Expr::Literal(Literal::Bool(true)));
    assert_eq!(generic[0], clauses[0]);
    assert_eq!(generic[2..], clauses[2..]);
    assert!(
        IdCandidatePlan::build(patterns, Some(predicate), &Row::new()).is_none(),
        "oracle must use the original generic candidate enumerator"
    );
    let actual = ex.execute_clauses(&clauses, limit).unwrap();
    let expected = ex.execute_clauses(&generic, limit).unwrap();
    assert_eq!(
        format!("{actual:?}"),
        format!("{expected:?}"),
        "complete typed rows, provenance and probe: {query}"
    );
    let bodies = |result: &super::super::QueryResult| {
        result
            .rows
            .iter()
            .map(|row| {
                row.iter()
                    .map(|(key, value)| (key.clone(), materialize(value, ex)))
                    .collect::<Vec<_>>()
            })
            .collect::<Vec<_>>()
    };
    assert_eq!(
        bodies(&actual),
        bodies(&expected),
        "all materialized node fields: {query}"
    );
    actual
}

fn multi(graph: Arc<Graph>) -> Executor {
    Executor::new(
        vec![
            Source {
                id: Arc::from("a"),
                graph: graph.clone(),
            },
            Source {
                id: Arc::from("z"),
                graph,
            },
        ],
        true,
    )
    .with_probe()
    .with_compact()
}

#[test]
fn id_candidates_accept_only_exact_literal_function_equality() {
    let clauses = parse("MATCH (n) WHERE id(n) = 4101 RETURN n").unwrap();
    let (patterns, predicate) = plain_match_where(&clauses);
    assert_eq!(patterns.len(), 1);
    assert_eq!(patterns[0].nodes[0].variable.as_deref(), Some("n"));
    assert_eq!(
        predicate,
        &Expr::Comparison {
            op: CmpOp::Eq,
            left: Box::new(Expr::FunctionCall {
                name: "id".into(),
                distinct: false,
                args: vec![Expr::Variable("n".into())]
            }),
            right: Box::new(Expr::Literal(Literal::Int(4101))),
        }
    );
    for (predicate, wanted) in [
        ("id(n) = 0", 0),
        ("4294967295 = ID(n)", u32::MAX),
        ("id(n) = 4101", 4101),
    ] {
        assert_eq!(
            eligible(
                &format!("MATCH (n) WHERE {predicate} RETURN n"),
                &Row::new()
            ),
            Some(wanted)
        );
    }
    for predicate in [
        "id(n) = -1",
        "id(n) = 4294967296",
        "id(n) = 1.0",
        "id(n) = '1'",
        "id(n) = $id",
        "id(n, unknown()) = 1",
        "id(DISTINCT n) = 1",
        "id(n) = 1 OR true",
        "id(n) = 1 AND true",
        "n.id = 1",
        "id(other) = 1",
        "id(n) <> 1",
    ] {
        assert_eq!(
            eligible(
                &format!("MATCH (n) WHERE {predicate} RETURN n"),
                &Row::new()
            ),
            None,
            "{predicate}"
        );
    }
    for pattern in [
        "(n:CallSite)",
        "(n {id: 1})",
        "p = (n)",
        "(n)-[]->(m)",
        "(n), (m)",
    ] {
        assert_eq!(
            eligible(
                &format!("MATCH {pattern} WHERE id(n) = 1 RETURN n"),
                &Row::new()
            ),
            None,
            "{pattern}"
        );
    }
    let mut seed = Row::new();
    seed.insert("earlier".into(), Value::Int(1));
    assert_eq!(eligible("MATCH (n) WHERE id(n) = 1 RETURN n", &seed), None);
}

#[test]
fn id_candidates_real_multigraph_rows_provenance_and_probe_match_generic() {
    let Some((_, graph)) = fixture() else { return };
    let wanted = graph.ids_by_tag(TAG_CALL_SITE_NODE)[0];
    let ex = multi(graph);
    for predicate in [format!("id(n) = {wanted}"), format!("{wanted} = id(n)")] {
        for suffix in [
            "RETURN n",
            "RETURN DISTINCT n",
            "RETURN n, n.graphId, id(n)",
            "RETURN n SKIP 1 LIMIT 1",
            "RETURN n LIMIT 0",
            "RETURN n LIMIT 1",
        ] {
            compare_generic(&ex, &format!("MATCH (n) WHERE {predicate} {suffix}"), None);
        }
    }
    let query = format!("MATCH (n) WHERE id(n) = {wanted} RETURN n");
    let full = compare_generic(&ex, &query, None);
    assert_eq!(full.rows.len(), 2);
    assert!(!full.more);
    for (source, row) in full.rows.iter().enumerate() {
        assert!(
            matches!(row["n"], Value::Node(NodeRef { source: s, id }) if s == source as SourceIdx && id == wanted)
        );
        let body = materialize(&row["n"], &ex);
        let gid = if source == 0 { "a" } else { "z" };
        assert_eq!(body["graphId"], gid);
        assert_eq!(body["elementId"], format!("{gid}:{wanted}"));
        assert_eq!(body["qualifiedId"], format!("{gid}:{wanted}"));
        assert_eq!(
            row[super::super::INTERNAL_PROVENANCE_KEY]
                .as_list()
                .unwrap()[0]
                .as_str(),
            Some(gid)
        );
    }
    let limited = compare_generic(&ex, &query, Some(1));
    assert_eq!(limited.rows.len(), 1);
    assert!(limited.more); // HTTP total is therefore {value:2, relation:"gte"}.
    let empty = compare_generic(&ex, &query, Some(0));
    assert!(empty.rows.is_empty());
    assert!(empty.more);
}

struct Variant(PathBuf);
impl Drop for Variant {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn variant(dir: &Path, lists: &[(u8, Vec<u32>)]) -> (Variant, Arc<Graph>) {
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let variant = Variant(std::env::temp_dir().join(format!(
        "graphite-id-filter-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    )));
    std::fs::create_dir(&variant.0).unwrap();
    for entry in std::fs::read_dir(dir).unwrap() {
        let entry = entry.unwrap();
        if entry.file_type().unwrap().is_file() {
            std::fs::copy(entry.path(), variant.0.join(entry.file_name())).unwrap();
        }
    }
    let original = std::fs::read(dir.join("graph.typeindex")).unwrap();
    let mut bytes = original[..4].to_vec();
    bytes.extend_from_slice(&(lists.len() as i32).to_be_bytes());
    let mut offset = 8 + lists.len() * 13;
    for (tag, ids) in lists {
        bytes.push(*tag);
        bytes.extend_from_slice(&(ids.len() as i32).to_be_bytes());
        bytes.extend_from_slice(&(offset as i64).to_be_bytes());
        offset += ids.len() * 4;
    }
    for (_, ids) in lists {
        for id in ids {
            bytes.extend_from_slice(&id.to_be_bytes());
        }
    }
    std::fs::write(variant.0.join("graph.typeindex"), bytes).unwrap();
    let graph = Arc::new(Graph::load(&variant.0).unwrap());
    (variant, graph)
}

#[test]
fn id_candidates_preserve_unsorted_duplicates_phantoms_and_omissions() {
    let Some((dir, original)) = fixture() else {
        return;
    };
    let wanted = original.ids_by_tag(TAG_CALL_SITE_NODE)[0];
    assert!(wanted < u32::MAX - 2);
    let lists = [
        (
            TAG_INT_CONSTANT,
            vec![wanted + 2, wanted, u32::MAX, wanted, 0],
        ),
        (
            TAG_BOOLEAN_CONSTANT,
            vec![wanted, wanted + 1, u32::MAX, wanted],
        ),
    ];
    let (_copy, graph) = variant(&dir, &lists);
    let ex = multi(graph);
    for (id, per_graph) in [(wanted, 4), (u32::MAX, 2)] {
        let query = format!("MATCH (n) WHERE id(n) = {id} RETURN n");
        let full = compare_generic(&ex, &query, None);
        assert_eq!(full.rows.len(), per_graph * 2);
        for limit in [0, 1, per_graph, per_graph + 1] {
            compare_generic(&ex, &query, Some(limit));
        }
    }
    let omitted = (0..original.node_capacity() as u32)
        .find(|id| original.has_node(*id) && lists.iter().all(|(_, ids)| !ids.contains(id)))
        .unwrap();
    let missing = compare_generic(
        &ex,
        &format!("MATCH (n) WHERE id(n) = {omitted} RETURN n"),
        None,
    );
    assert!(missing.rows.is_empty());
    assert!(!missing.more);
    let mixed = Executor::new(
        vec![
            Source {
                id: Arc::from("a"),
                graph: ex.sources[0].graph.clone(),
            },
            Source {
                id: Arc::from("z"),
                graph: original,
            },
        ],
        true,
    )
    .with_probe();
    let one = compare_generic(
        &mixed,
        &format!("MATCH (n) WHERE id(n) = {omitted} RETURN n"),
        None,
    );
    assert_eq!(one.rows.len(), 1);
    assert!(matches!(one.rows[0]["n"], Value::Node(NodeRef { source: 1, id }) if id == omitted));
}

#[test]
fn id_candidates_filter_before_row_construction_and_keep_polling_and_stop() {
    let Some((dir, original)) = fixture() else {
        return;
    };
    let wanted = original.ids_by_tag(TAG_CALL_SITE_NODE)[0];
    let (_copy, graph) = variant(
        &dir,
        &[(
            TAG_INT_CONSTANT,
            vec![wanted + 1, wanted, wanted + 2, wanted],
        )],
    );
    let ex = multi(graph);
    let plan = IdCandidatePlan {
        variable: "n",
        wanted,
    };
    let mut row_constructor_inputs = Vec::new();
    assert!(plan
        .candidates(&ex, &mut |source, id| {
            row_constructor_inputs.push((source, id));
            Ok(true)
        })
        .unwrap());
    assert_eq!(
        row_constructor_inputs,
        [(0, wanted), (0, wanted), (1, wanted), (1, wanted)]
    );
    assert_eq!(
        ex.poll.load(Ordering::Relaxed),
        8,
        "every ID is polled, including all four rejected candidates"
    );
    let before = ex.poll.load(Ordering::Relaxed);
    let mut emitted = 0;
    assert!(!plan
        .run(&ex, &mut |_| {
            emitted += 1;
            Ok(false)
        })
        .unwrap());
    assert_eq!(emitted, 1);
    assert_eq!(
        ex.poll.load(Ordering::Relaxed) - before,
        2,
        "downstream stop prevents further work"
    );
    let mid = multi(ex.sources[0].graph.clone());
    let mut seen = Vec::new();
    assert_eq!(
        plan.candidates(&mid, &mut |source, id| {
            seen.push((source, id));
            mid.cancel.cancel();
            Ok(true)
        }),
        Err(CypherError::Cancelled)
    );
    assert!(!seen.is_empty());
    assert!(
        seen.iter().all(|(source, _)| *source == 0),
        "the next graph checks cancellation before emitting"
    );
    ex.cancel.cancel();
    assert_eq!(
        plan.candidates(&ex, &mut |_, _| panic!("cancelled before candidates")),
        Err(CypherError::Cancelled)
    );
}

#[test]
fn id_candidates_fallback_errors_parameters_and_deadline_remain_observable() {
    let Some((_, graph)) = fixture() else { return };
    let wanted = graph.ids_by_tag(TAG_CALL_SITE_NODE)[0];
    let ex = multi(graph.clone()).with_params([("id".into(), Value::Int(wanted as i64))].into());
    for predicate in [
        format!("id(n) = {wanted}.0"),
        "id(n) = $id".into(),
        "id(n) = -1".into(),
        format!("id(DISTINCT n) = {wanted}"),
        format!("id(n) = {wanted} OR false"),
    ] {
        compare_generic(&ex, &format!("MATCH (n) WHERE {predicate} RETURN n"), None);
    }
    for query in [
        format!("MATCH (n) WHERE id(n, unknown()) = {wanted} RETURN n"),
        format!("MATCH (n) WHERE id(n) = {wanted} AND unknown() RETURN n"),
        format!("MATCH (n) WHERE id(n) = {wanted} RETURN unknown()"),
    ] {
        assert!(
            matches!(ex.execute(&query, None), Err(CypherError::Runtime(message)) if message == "Unknown function: unknown")
        );
    }
    let cancel = CancelToken::new();
    cancel.timeout(7);
    let timed = multi(graph).with_cancel(cancel);
    assert!(matches!(
        timed.execute(&format!("MATCH (n) WHERE id(n) = {wanted} RETURN n"), None),
        Err(CypherError::Timeout(7))
    ));
}

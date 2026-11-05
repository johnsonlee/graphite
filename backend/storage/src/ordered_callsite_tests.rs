//! Proof correctness only; real fixture execution here is never a performance sample.
use super::*;

fn fixture() -> Option<Graph> {
    let Some(path) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        assert!(std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none());
        return None;
    };
    Some(Graph::load(Path::new(&path)).unwrap())
}

#[test]
fn ordered_graph_proof_is_cancelled_without_poisoning_and_reuses_immutable_success() {
    let Some(graph) = fixture() else { return };
    assert!(graph.count_by_tag(TAG_CALL_SITE_NODE) > 1024);
    let mut calls = 0;
    let result = graph.validated_ordered_call_site(0, || {
        calls += 1;
        if calls == 3 {
            Err("cancelled")
        } else {
            Ok(())
        }
    });
    assert!(matches!(result, Err("cancelled")));
    assert!(graph.ordered_call_site_valid[0].get().is_none());
    assert!(graph
        .validated_ordered_call_site(0, || Ok::<_, ()>(()))
        .unwrap()
        .is_some());
    assert_eq!(graph.ordered_call_site_valid[0].get(), Some(&true));
    calls = 0;
    assert!(graph
        .validated_ordered_call_site(0, || {
            calls += 1;
            Ok::<_, ()>(())
        })
        .unwrap()
        .is_some());
    assert_eq!(calls, 1);
    assert!(matches!(
        graph.validated_ordered_call_site(0, || Err("hot cancelled")),
        Err("hot cancelled")
    ));
}

#[test]
fn ordered_graph_proof_refuses_duplicates_phantoms_omissions_unsorted_and_truncated_nodes() {
    let Some(mut graph) = fixture() else { return };
    let original = graph.ids_by_tag(TAG_CALL_SITE_NODE).to_vec();
    assert!(original.len() > 2);
    let mut duplicate = original.clone();
    duplicate[1] = duplicate[0];
    let mut phantom = original.clone();
    *phantom.last_mut().unwrap() = u32::MAX;
    let mut omitted = original.clone();
    omitted.pop();
    let mut unsorted = original.clone();
    unsorted.swap(0, 1);
    let mut shifted = original.clone();
    shifted.remove(0);
    shifted.push(u32::MAX);
    for ids in [duplicate, phantom, omitted, unsorted, shifted] {
        graph.type_index[TAG_CALL_SITE_NODE as usize] = ids;
        graph.ordered_call_site_valid[0] = std::sync::OnceLock::new();
        assert!(graph
            .validated_ordered_call_site(0, || Ok::<_, ()>(()))
            .unwrap()
            .is_none());
        assert_eq!(graph.ordered_call_site_valid[0].get(), Some(&false));
    }
    graph.type_index[TAG_CALL_SITE_NODE as usize] = original.clone();
    graph.ordered_call_site_valid[0] = std::sync::OnceLock::new();
    let end = graph.node_offset(original[0]).unwrap() + 6;
    graph.nodedata = Bytes::owned(graph.nodedata[..end].to_vec());
    assert!(graph
        .validated_ordered_call_site(0, || Ok::<_, ()>(()))
        .unwrap()
        .is_none());
}

#[test]
fn ordered_graph_proof_checks_unordered_projection_string_ids_too() {
    let Some(mut graph) = fixture() else { return };
    let id = *graph.ids_by_tag(TAG_CALL_SITE_NODE).last().unwrap();
    let offset = graph.node_offset(id).unwrap();
    let mut data = graph.nodedata.to_vec();
    // caller_name is not the sort property's caller_class. A late invalid ID
    // must force fallback even if an ordered LIMIT would never project this row.
    data[offset + NODE_HEADER_BYTES + 4..offset + NODE_HEADER_BYTES + 8]
        .copy_from_slice(&u32::MAX.to_be_bytes());
    graph.nodedata = Bytes::owned(data);
    let fields = graph.call_site_scalar_strings(id).unwrap();
    assert_eq!(fields.caller_name, u32::MAX);
    assert!(std::panic::catch_unwind(std::panic::AssertUnwindSafe(
        || graph.str(fields.caller_name)
    ))
    .is_err());
    assert!(graph
        .validated_ordered_call_site(0, || Ok::<_, ()>(()))
        .unwrap()
        .is_none());
    assert_eq!(graph.ordered_call_site_valid[0].get(), Some(&false));
}

#[test]
fn empty_callsite_domain_does_not_validate_or_read_an_unrelated_index() {
    let Some(mut graph) = fixture() else { return };
    graph.type_index[TAG_CALL_SITE_NODE as usize].clear();
    assert_eq!(graph.count_by_tag(TAG_CALL_SITE_NODE), 0);
    // Domain consumers must skip this source, not enumerate its leftover CSR.
    assert!(graph
        .validated_ordered_call_site(0, || Ok::<_, ()>(()))
        .unwrap()
        .is_none());
}

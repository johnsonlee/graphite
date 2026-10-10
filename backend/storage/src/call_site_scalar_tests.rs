use super::*;
use crate::node::NodeKind;

fn record(counts: [i32; 3]) -> Vec<u8> {
    let mut data = 99u32.to_be_bytes().to_vec();
    data.push(TAG_CALL_SITE_NODE);
    for (class, name, count) in [(0u32, 1u32, counts[0]), (2, 3, counts[1])] {
        data.extend(class.to_be_bytes());
        data.extend(name.to_be_bytes());
        data.extend(count.to_be_bytes());
        for _ in 0..count.max(0) {
            data.extend(4u32.to_be_bytes());
        }
        data.extend(4u32.to_be_bytes());
    }
    data.extend((-1i32).to_be_bytes());
    data.extend((-1i32).to_be_bytes());
    data.extend(counts[2].to_be_bytes());
    for _ in 0..counts[2].max(0) {
        data.extend(17u32.to_be_bytes());
    }
    data
}

fn fields(node: Node) -> CallSiteStrings {
    let NodeKind::CallSite { caller, callee, .. } = node.kind else {
        panic!("expected CallSite")
    };
    CallSiteStrings {
        caller_class: caller.declaring_class,
        caller_name: caller.name,
        callee_class: callee.declaring_class,
        callee_name: callee.name,
    }
}

#[test]
fn call_site_scalar_complete_framing_matches_all_versions_and_negative_counts() {
    for version in 1..=3 {
        for counts in [[0, 0, 0], [2, 3, 4], [-1, -7, -2], [0, 1, -1]] {
            let mut data = vec![0x55; 7];
            data.extend(record(counts));
            data.extend([0xfe, 0xdc]); // Full decoder allows trailing data.
            let expected = fields(Node::read(&data, 7, version).unwrap());
            assert_eq!(
                expected,
                CallSiteStrings {
                    caller_class: 0,
                    caller_name: 1,
                    callee_class: 2,
                    callee_name: 3,
                }
            );
            assert_eq!(read_call_site_scalar_strings(&data, 7), Some(expected));
        }
    }
}

#[test]
fn call_site_scalar_rejects_every_truncated_prefix_including_unselected_tail() {
    for counts in [[0, 0, 0], [2, 3, 4]] {
        let data = record(counts);
        for version in 1..=3 {
            for end in 0..data.len() {
                assert!(Node::read(&data[..end], 0, version).is_err());
                assert_eq!(
                    read_call_site_scalar_strings(&data[..end], 0),
                    None,
                    "end={end}"
                );
            }
        }
    }
    let data = record([0, 0, 0]);
    assert_eq!(read_call_site_scalar_strings(&data, usize::MAX), None);
    assert_eq!(read_call_site_scalar_strings(&data, data.len()), None);
}

#[test]
fn call_site_scalar_wrong_kind_and_excessive_counts_do_not_accept_prefix() {
    let data = record([0, 0, 0]);
    for tag in 0..=u8::MAX {
        if tag == TAG_CALL_SITE_NODE {
            continue;
        }
        let mut other = data.clone();
        other[4] = tag;
        assert_eq!(read_call_site_scalar_strings(&other, 0), None);
    }
    // Caller count, callee count and argument count in an otherwise zero-arity record.
    for offset in [13, 29, 45] {
        let mut invalid = data.clone();
        invalid[offset..offset + 4].copy_from_slice(&i32::MAX.to_be_bytes());
        assert_eq!(read_call_site_scalar_strings(&invalid, 0), None);
        // Do not ask the original Vec decoder to reserve an adversarial multi-GB vector.
    }
}

// Same independent ratio-one Java serialization layout as the shared-string tests.
fn utf(out: &mut Vec<u8>, value: &str) {
    out.extend((value.len() as u16).to_be_bytes());
    out.extend(value.as_bytes());
}
fn descriptor(out: &mut Vec<u8>, name: &str, fields: &[(u8, &str)]) {
    out.push(0x72);
    utf(out, name);
    out.extend(0i64.to_be_bytes());
    out.push(2);
    out.extend((fields.len() as u16).to_be_bytes());
    for (kind, name) in fields {
        out.push(*kind);
        utf(out, name);
        if *kind == b'L' || *kind == b'[' {
            out.push(0x74);
            utf(out, "Ljava/lang/Object;");
        }
    }
    out.extend([0x78, 0x70]);
}
fn strings(values: &[&str]) -> StringTable {
    let units: Vec<Vec<u16>> = values
        .iter()
        .map(|value| value.encode_utf16().collect())
        .collect();
    let mut out = vec![0xac, 0xed, 0, 5, 0x73];
    descriptor(
        &mut out,
        "it.unimi.dsi.util.FrontCodedStringList",
        &[(b'Z', "utf8"), (b'L', "charFrontCodedList")],
    );
    out.extend([0, 0x73]);
    descriptor(
        &mut out,
        "it.unimi.dsi.fastutil.chars.CharArrayFrontCodedList",
        &[(b'I', "n"), (b'I', "ratio"), (b'[', "array")],
    );
    out.extend((units.len() as i32).to_be_bytes());
    out.extend(1i32.to_be_bytes());
    out.push(0x75);
    descriptor(&mut out, "[C", &[]);
    out.extend((units.iter().map(|s| s.len() + 1).sum::<usize>() as i32).to_be_bytes());
    for value in units {
        out.extend((value.len() as u16).to_be_bytes());
        for unit in value {
            out.extend(unit.to_be_bytes());
        }
    }
    StringTable::from_serialized_for_declared_types(&out).unwrap()
}

#[test]
fn call_site_scalar_graph_holes_header_ids_and_same_sids_use_each_dictionary() {
    let Some(dir) = std::env::var_os("GRAPHITE_TYPES_FIXTURE")
        .or_else(|| std::env::var_os("GRAPHITE_INDEX_FIXTURE"))
    else {
        assert!(std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none());
        eprintln!("No graph fixture configured; scalar Graph integration requires one");
        return;
    };
    let mut graph = Graph::load(Path::new(&dir)).unwrap();
    graph.node_capacity = 3;
    let mut offsets = vec![0; 8];
    for stored in [0i64, 1, 0] {
        offsets.extend(stored.to_be_bytes());
    }
    graph.node_offsets = Bytes::owned(offsets);
    graph.nodedata = Bytes::owned(record([2, 1, 2]));
    for values in [
        ["", "调用🙂", "é", "method", "void"],
        ["other", "", "类", "🚀", "void"],
    ] {
        graph.strings = strings(&values);
        let selected = graph.call_site_scalar_strings(1).unwrap();
        assert_eq!(selected, fields(graph.node(1).unwrap()));
        assert_eq!(graph.node(1).unwrap().id, 99);
        for (id, expected) in [
            selected.caller_class,
            selected.caller_name,
            selected.callee_class,
            selected.callee_name,
        ]
        .into_iter()
        .zip(values)
        {
            assert_eq!(graph.str(id), expected);
        }
        for id in [0, 2, 3, u32::MAX] {
            assert_eq!(graph.call_site_scalar_strings(id), None);
            assert!(graph.node(id).is_none());
        }
    }
}

use super::*;
use crate::node::{NodeKind, TAG_LONG_CONSTANT};

fn record(id: u32, value: i32) -> Vec<u8> {
    let mut bytes = id.to_be_bytes().to_vec();
    bytes.push(TAG_INT_CONSTANT);
    bytes.extend(value.to_be_bytes());
    bytes
}

#[test]
fn int_scalar_complete_record_matches_full_decode_for_all_versions_and_boundaries() {
    for version in 1..=3 {
        for value in [i32::MIN, -1, 0, 1, i32::MAX] {
            let mut bytes = vec![0x55; 7];
            bytes.extend(record(123, value));
            bytes.extend([0xfe, 0xdc]); // Node::read does not reject trailing bytes.
            assert_eq!(read_int_constant_value(&bytes, 7), Some(value));
            assert_eq!(
                Node::read(&bytes, 7, version).unwrap(),
                Node {
                    id: 123,
                    kind: NodeKind::IntConstant(value)
                }
            );
        }
    }
}

#[test]
fn int_scalar_rejects_every_truncated_prefix_and_non_int_tag() {
    let bytes = record(9, -17);
    for len in 0..9 {
        assert_eq!(read_int_constant_value(&bytes[..len], 0), None, "len={len}");
        assert!(Node::read(&bytes[..len], 0, 3).is_err(), "len={len}");
    }
    assert_eq!(read_int_constant_value(&bytes, bytes.len()), None);
    for tag in 1..=u8::MAX {
        let mut other = bytes.clone();
        other[4] = tag;
        assert_eq!(read_int_constant_value(&other, 0), None, "tag={tag}");
    }
    let mut long = 9u32.to_be_bytes().to_vec();
    long.push(TAG_LONG_CONSTANT);
    long.extend(i64::MIN.to_be_bytes());
    assert_eq!(read_int_constant_value(&long, 0), None);
    assert_eq!(
        Node::read(&long, 0, 3).unwrap().kind,
        NodeKind::LongConstant(i64::MIN)
    );
}

#[test]
fn int_scalar_graph_lookup_preserves_holes_header_id_and_truncation() {
    let Some(dir) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        eprintln!("GRAPHITE_INDEX_FIXTURE unset; skipping graph lookup test");
        return;
    };
    let mut graph = Graph::load(Path::new(&dir)).unwrap();
    // The real fixture supplies ancillary metadata; these owned bytes exercise
    // only Graph's id-offset lookup, without modifying any mapped fixture file.
    graph.node_capacity = 3;
    let mut offsets = vec![0; 8];
    for stored in [0i64, 1, 0] {
        offsets.extend(stored.to_be_bytes());
    }
    graph.node_offsets = Bytes::owned(offsets);
    graph.nodedata = Bytes::owned(record(99, -7));
    assert_eq!(graph.int_constant_value(1), Some(-7));
    let full = graph.node(1).unwrap();
    assert_eq!(full.id, 99); // Existing reader does not require header id == lookup id.
    assert_eq!(full.kind, NodeKind::IntConstant(-7));
    for id in [0, 2, 3, u32::MAX] {
        assert_eq!(graph.int_constant_value(id), None);
        assert!(graph.node(id).is_none());
    }
    for len in 0..9 {
        graph.nodedata = Bytes::owned(record(99, -7)[..len].to_vec());
        assert_eq!(graph.int_constant_value(1), None, "len={len}");
        assert!(graph.node(1).is_none(), "len={len}");
    }
}

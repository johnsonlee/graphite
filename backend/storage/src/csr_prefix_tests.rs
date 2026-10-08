use super::*;
use crate::bvgraph::prefix_tests::mixed_fixture;

#[test]
fn ascending_csr_preserves_complete_order_offsets_duplicates_holes_and_labels() {
    let (bv, expected) = mixed_fixture();
    let labels: Vec<u8> = (1..=44).collect();
    let actual = build_forward_csr(&bv, &labels);
    assert_eq!(
        actual.offsets,
        vec![0, 6, 12, 17, 19, 23, 29, 35, 41, 41, 42, 44, 44, 44, 44, 44, 44]
    );
    assert_eq!(actual.targets, expected.concat());
    assert_eq!(actual.labels, labels);
    let mut start = 0;
    for (node, row) in expected.iter().enumerate() {
        assert_eq!(
            actual.neighbors(node),
            (row.as_slice(), &labels[start..start + row.len()])
        );
        assert_eq!(actual.degree(node), row.len());
        assert_eq!(bv.successors(node), *row);
        start += row.len();
    }
    let reverse = build_backward_csr(&actual);
    assert_eq!(reverse.neighbors(3), (&[9, 10, 10][..], &[42, 43, 44][..]));
    assert_eq!(reverse.neighbors(14), (&[5][..], &[29][..]));
    assert_eq!(reverse.neighbors(15), (&[][..], &[][..]));
}

#[test]
fn short_and_excess_labels_keep_existing_padding_and_truncation_without_cross_load_state() {
    let (first, expected) = mixed_fixture();
    let short = build_forward_csr(&first, &[7, 8]);
    let mut expected_labels = vec![7, 8];
    expected_labels.resize(44, 0);
    assert_eq!(short.labels, expected_labels);
    let (second, _) = mixed_fixture();
    let long = build_forward_csr(&second, &[9; 50]);
    assert_eq!(long.labels, vec![9; 44]);
    assert_eq!(short.targets, expected.concat());
    assert_eq!(short.targets, long.targets);
    assert_eq!(short.offsets, long.offsets);
    assert_eq!(build_forward_csr(&first, &[]).labels, vec![0; 44]);
}

fn assert_fixture_csr(path: &Path) -> Graph {
    let source = GraphSource::open(path).unwrap();
    let bv = BvGraph::load(&source, "forward").unwrap();
    let mut offsets = vec![0];
    let mut targets = Vec::new();
    for node in 0..bv.num_nodes() {
        targets.extend(bv.successors(node)); // Unchanged recursive random-access path.
        offsets.push(targets.len() as u32);
    }
    let labels = source.require("graph.labels").unwrap();
    assert_eq!(targets.len() as u64, bv.num_arcs());
    assert_eq!(labels.len(), targets.len());
    let graph = Graph::load(path).unwrap();
    assert_eq!(graph.forward.offsets, offsets);
    assert_eq!(graph.forward.targets, targets);
    assert_eq!(graph.forward.labels, &labels[..]);
    graph
}

#[test]
fn both_java_wire_versions_and_packed_sources_preserve_every_csr_edge_and_label() {
    let paths = [
        std::env::var_os("GRAPHITE_TYPES_V1_FIXTURE"),
        std::env::var_os("GRAPHITE_TYPES_FIXTURE"),
    ];
    if paths.iter().any(Option::is_none) {
        assert!(std::env::var_os("GRAPHITE_REQUIRE_DUAL_TYPES_FIXTURES").is_none());
        return;
    }
    let root =
        std::env::temp_dir().join(format!("graphite-prefix-fixtures-{}", std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    for (version, path) in paths.into_iter().enumerate() {
        let path = PathBuf::from(path.unwrap());
        let expected = assert_fixture_csr(&path);
        let packed = root.join(format!("v{version}.graphite"));
        crate::container::pack(&path, &packed).unwrap();
        let actual = assert_fixture_csr(&packed);
        assert_eq!(expected.forward.offsets, actual.forward.offsets);
        assert_eq!(expected.forward.targets, actual.forward.targets);
        assert_eq!(expected.forward.labels, actual.forward.labels);
        assert_eq!(expected.backward.offsets, actual.backward.offsets);
        assert_eq!(expected.backward.targets, actual.backward.targets);
        assert_eq!(expected.backward.labels, actual.backward.labels);
        assert_eq!(expected.declared_types(), actual.declared_types());
        assert_eq!(expected.node_capacity(), actual.node_capacity());
        for id in 0..expected.node_capacity() as u32 {
            assert_eq!(
                expected.node(id).map(|n| n.kind),
                actual.node(id).map(|n| n.kind)
            );
        }
    }
    std::fs::remove_dir_all(root).unwrap();
}

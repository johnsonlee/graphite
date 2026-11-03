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
    assert_inverse(&short, &build_backward_csr(&short));
    assert_inverse(&long, &build_backward_csr(&long));
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
        std::env::var_os("GRAPHITE_TYPES_V2_FIXTURE")
            .or_else(|| std::env::var_os("GRAPHITE_TYPES_FIXTURE")),
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

/// A stable sort by destination transposes the edge list without sharing the
/// degree counting, prefix sums or cursors used by the production constructor.
fn assert_inverse(fwd: &Csr, reverse: &Csr) {
    let mut edges = Vec::new();
    for (from, bounds) in fwd.offsets.windows(2).enumerate() {
        for k in bounds[0] as usize..bounds[1] as usize {
            edges.push((fwd.targets[k] as usize, from as u32, fwd.labels[k]));
        }
    }
    edges.sort_by_key(|&(to, _, _)| to);
    assert_eq!(reverse.offsets.len(), fwd.offsets.len());
    assert_eq!(reverse.offsets[0], 0);
    assert_eq!(reverse.targets.len(), edges.len());
    assert_eq!(reverse.labels.len(), edges.len());
    let mut k = 0;
    for (to, &end) in reverse.offsets.iter().skip(1).enumerate() {
        while edges.get(k).is_some_and(|edge| edge.0 == to) {
            assert_eq!(
                (reverse.targets[k], reverse.labels[k]),
                (edges[k].1, edges[k].2)
            );
            k += 1;
        }
        assert_eq!(end as usize, k, "end offset for destination {to}");
    }
    assert_eq!(k, edges.len());
}

#[test]
fn reverse_csr_keeps_sparse_offsets_duplicates_self_edges_and_label_order() {
    let mut offsets = vec![0];
    let mut targets = Vec::new();
    let mut labels = Vec::new();
    for from in 0..32 {
        let row: &[(u32, u8)] = match from {
            8 => &[(2, 90), (8, 17), (17, 3), (17, 201)],
            10 => &[(2, 32), (24, 255)],
            19 => &[(17, 70)],
            _ => &[],
        };
        for &(to, label) in row {
            targets.push(to);
            labels.push(label);
        }
        offsets.push(targets.len() as u32);
    }
    let fwd = Csr {
        offsets,
        targets,
        labels,
    };
    let reverse = build_backward_csr(&fwd);
    assert_inverse(&fwd, &reverse);
    assert_eq!(reverse.neighbors(17), (&[8, 8, 19][..], &[3, 201, 70][..]));
    assert_eq!(reverse.neighbors(8), (&[8][..], &[17][..]));
}

#[test]
fn reverse_csr_handles_empty_graphs_and_edges_at_both_capacity_bounds() {
    for n in [0, 1, 19] {
        let empty = Csr {
            offsets: vec![0; n + 1],
            targets: vec![],
            labels: vec![],
        };
        assert_inverse(&empty, &build_backward_csr(&empty));
    }
    let fwd = Csr {
        offsets: vec![0, 1, 1, 2],
        targets: vec![2, 0],
        labels: vec![7, 9],
    };
    assert_inverse(&fwd, &build_backward_csr(&fwd));
}

#[test]
fn reverse_csr_does_not_hide_invalid_targets_or_missing_labels() {
    for (to, labels) in [(1, vec![7]), (0, vec![])] {
        let invalid = Csr {
            offsets: vec![0, 1],
            targets: vec![to],
            labels,
        };
        assert!(std::panic::catch_unwind(|| build_backward_csr(&invalid)).is_err());
    }
}

#[test]
fn persisted_reverse_csr_matches_stable_forward_edge_inversion() {
    let Some(path) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        return;
    };
    let graph = Graph::load(Path::new(&path)).unwrap();
    assert_inverse(&graph.forward, &graph.backward);
}

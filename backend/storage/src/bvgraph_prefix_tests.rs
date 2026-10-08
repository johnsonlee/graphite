use super::*;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Arc;

#[derive(Clone, Copy)]
enum Token {
    Gamma(u64),
    Unary(u64),
}
use Token::{Gamma as G, Unary as U};

#[derive(Default)]
struct Writer {
    bytes: Vec<u8>,
    bits: u64,
}

impl Writer {
    fn bit(&mut self, value: bool) {
        if self.bits.is_multiple_of(8) {
            self.bytes.push(0);
        }
        if value {
            let index = (self.bits / 8) as usize;
            self.bytes[index] |= 1 << (7 - self.bits % 8);
        }
        self.bits += 1;
    }

    fn unary(&mut self, value: u64) {
        for _ in 0..value {
            self.bit(false);
        }
        self.bit(true);
    }

    fn token(&mut self, token: Token) {
        match token {
            U(value) => self.unary(value),
            G(value) => {
                let positive = value + 1;
                let width = 63 - positive.leading_zeros();
                self.unary(u64::from(width));
                for bit in (0..width).rev() {
                    self.bit(positive & (1 << bit) != 0);
                }
            }
        }
    }
}

fn encoded(window: usize, minimum_interval: usize, rows: &[Vec<Token>]) -> BvGraph {
    let arcs: u64 = rows
        .iter()
        .map(|row| match row[0] {
            G(degree) => degree,
            _ => panic!("row must start with gamma outdegree"),
        })
        .sum();
    let props = BvProperties::parse(&format!(
        "nodes={}\narcs={arcs}\nwindowsize={window}\nminintervallength={minimum_interval}\ncompressionflags=RESIDUALS_GAMMA\n",
        rows.len()
    ))
    .unwrap();
    let mut writer = Writer::default();
    let mut offsets = vec![0];
    for row in rows {
        for token in row {
            writer.token(*token);
        }
        offsets.push(writer.bits);
    }
    BvGraph {
        props,
        graph: Bytes::Owned(Arc::new(writer.bytes)),
        offsets,
    }
}

/// Tokens and complete expected rows are independent of the production decoder.
pub(crate) fn mixed_fixture() -> (BvGraph, Vec<Vec<u32>>) {
    let mut rows = vec![
        vec![G(6), U(0), G(0), G(4), G(1), G(1), G(1), G(1), G(1)],
        vec![G(6), U(1), G(0)], // Entire predecessor, zero explicit blocks.
        vec![G(5), U(1), G(2), G(0), G(0)], // First copy block0; skip1, implicit rest.
        vec![G(2), U(1), G(1), G(2)], // Odd block count copies2, skips remainder.
        vec![G(4), U(3), G(2), G(1), G(1)], // Copy1, skip2, implicit remainder.
        vec![G(6), U(1), G(1), G(1), G(2), G(0), G(0), G(1), G(0), G(18)],
        vec![G(6), U(5), G(0)], // Exactly windowsize5.
        vec![G(6), U(7), G(0)], // Accepted older reference: recursive fallback.
        vec![G(0)],
        vec![G(1), U(1), G(0), G(0), G(11)], // Empty reference, residual3.
        vec![G(2), U(1), G(0), G(0), G(13)], // Copied/residual duplicate3.
    ];
    rows.resize(16, vec![G(0)]); // Zero-degree holes and target capacity through15.
    let mut expected = vec![
        vec![2, 4, 6, 8, 10, 12],
        vec![2, 4, 6, 8, 10, 12],
        vec![4, 6, 8, 10, 12],
        vec![4, 6],
        vec![2, 8, 10, 12],
        vec![2, 5, 6, 9, 10, 14],
        vec![2, 4, 6, 8, 10, 12],
        vec![2, 4, 6, 8, 10, 12],
        vec![],
        vec![3],
        vec![3, 3],
    ];
    expected.resize(16, vec![]);
    (encoded(5, 2, &rows), expected)
}

fn prefix(rows: &[Vec<u32>]) -> (Vec<u32>, Vec<u32>) {
    let mut offsets = vec![0];
    let mut targets = Vec::new();
    for row in rows {
        targets.extend_from_slice(row);
        offsets.push(targets.len() as u32);
    }
    (offsets, targets)
}

#[test]
fn sequential_prefix_matches_independent_rows_and_leaves_random_access_unchanged() {
    let (bv, expected) = mixed_fixture();
    let mut offsets = vec![0];
    let mut targets = Vec::new();
    let mut out = vec![999];
    for (node, row) in expected.iter().enumerate() {
        assert_eq!(bv.successors(node), *row);
        bv.successors_into_with_prefix(node, &mut out, &offsets, &targets);
        assert_eq!(&out, row, "node={node}");
        targets.extend_from_slice(&out);
        offsets.push(targets.len() as u32);
    }
    for (node, row) in expected.iter().enumerate().rev() {
        assert_eq!(bv.successors(node), *row);
    }
    bv.successors_into_with_prefix(expected.len(), &mut out, &offsets, &targets);
    assert!(out.is_empty());
}

#[test]
fn zero_window_uses_no_reference_bits() {
    let bv = encoded(
        0,
        0,
        &[
            vec![G(2), G(2), G(1)],
            vec![G(0)],
            vec![G(1), G(3)],
            vec![G(0)],
        ],
    );
    let expected = [vec![1, 3], vec![], vec![0], vec![]];
    for (node, row) in expected.iter().enumerate() {
        let (offsets, targets) = prefix(&expected[..node]);
        let mut actual = Vec::new();
        bv.successors_into_with_prefix(node, &mut actual, &offsets, &targets);
        assert_eq!(&actual, row);
        assert_eq!(bv.successors(node), *row);
    }
}

#[test]
fn completed_nonempty_and_empty_rows_are_used_without_redecoding_them() {
    // Private owned fixture manipulation proves the path, not a supported mutable-file model.
    for node in [1, 9] {
        let (mut bv, expected) = mixed_fixture();
        let (offsets, targets) = prefix(&expected[..node]);
        let predecessor = node - 1;
        bv.offsets[predecessor] = bv.offsets[if node == 1 { 8 } else { 0 }];
        assert_ne!(bv.successors(predecessor), expected[predecessor]);
        let mut actual = Vec::new();
        bv.successors_into_with_prefix(node, &mut actual, &offsets, &targets);
        assert_eq!(actual, expected[node]);
    }
}

#[test]
fn unusable_prefix_falls_back_and_empty_predecessor_is_not_unavailable() {
    let (bv, expected) = mixed_fixture();
    for (offsets, targets) in [
        (vec![], vec![]),
        (vec![0, 99], vec![2]),
        (vec![1, 0], vec![2]),
    ] {
        let mut actual = Vec::new();
        bv.successors_into_with_prefix(1, &mut actual, &offsets, &targets);
        assert_eq!(actual, expected[1]);
    }
    let (offsets, targets) = prefix(&expected[..9]);
    let rows = CompletedCsr {
        offsets: &offsets,
        targets: &targets,
    };
    assert_eq!(rows.referenced_row(9, 1, 5), Some(&[][..]));
    assert_eq!(rows.referenced_row(9, 0, 5), None);
    assert_eq!(rows.referenced_row(9, 6, 5), None);
    assert_eq!(rows.referenced_row(9, 10, 20), None);
}

#[test]
fn prefix_length_guard_does_not_trust_wrapped_u32_offsets() {
    assert!(CompletedCsr::matches_node(1, 2, u32::MAX as usize));
    assert!(!CompletedCsr::matches_node(1, 1, 0));
    assert!(!CompletedCsr::matches_node(usize::MAX, 0, 0));
    if let Ok(too_many_targets) = usize::try_from(u64::from(u32::MAX) + 1) {
        assert!(!CompletedCsr::matches_node(1, 2, too_many_targets));
    }
}

fn panics(bv: &BvGraph, node: usize, sequential: bool) -> bool {
    catch_unwind(AssertUnwindSafe(|| {
        let mut out = Vec::new();
        if sequential {
            bv.successors_into_with_prefix(node, &mut out, &vec![0; node + 1], &[]);
        } else {
            bv.successors_into(node, &mut out);
        }
    }))
    .is_err()
}

#[test]
fn malformed_references_and_truncated_stream_keep_inherited_behavior() {
    let first = encoded(5, 0, &[vec![G(1), U(1), G(0)]]);
    let later = encoded(5, 0, &[vec![G(0)], vec![G(1), U(2), G(0)]]);
    assert!(panics(&first, 0, false));
    assert!(panics(&first, 0, true));
    assert!(panics(&later, 1, false));
    assert!(panics(&later, 1, true));
    let mut truncated = encoded(5, 0, &[vec![G(1), U(0), G(0)]]);
    truncated.graph = Bytes::Owned(Arc::new(vec![0b0100_0000]));
    assert!(panics(&truncated, 0, false));
    assert!(panics(&truncated, 0, true));
    // The inherited reader tolerates an empty stream as zero outdegree; do not harden it here.
    truncated.graph = Bytes::Owned(Arc::new(vec![]));
    assert_eq!(truncated.successors(0), Vec::<u32>::new());
    let mut out = vec![123];
    truncated.successors_into_with_prefix(0, &mut out, &[0], &[]);
    assert!(out.is_empty());
}

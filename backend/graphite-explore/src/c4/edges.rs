//! Transitive reduction shared by the diagram planner and the component selector.

use super::constants::reduction_limits::{
    HIERARCHY_REDUCTION_KINDS, MAX_TRANSITIVE_REDUCTION_EDGES,
};

/// A directed, kinded, weighted edge between two element ids.
pub trait DirectedEdge {
    fn from(&self) -> &str;
    fn to(&self) -> &str;
    fn kind(&self) -> &str;
    /// `None` reads as a weight of 1, as the baseline's `weight ?: 1`.
    fn weight(&self) -> Option<i64>;
}

/// Drop a direct edge that another path already carries.
///
/// For a hierarchy edge plain reachability settles it. For an evidence-bearing edge the
/// alternate path must be at least as strong at its narrowest point, so a heavy direct
/// dependency is not hidden behind a thin indirect one. Above
/// `MAX_TRANSITIVE_REDUCTION_EDGES` reducible edges the list is returned as is.
pub fn reduce_transitive<T: DirectedEdge>(edges: Vec<T>, preserve_runtime: bool) -> Vec<T> {
    let reducible: Vec<usize> = (0..edges.len())
        .filter(|&i| !(preserve_runtime && edges[i].kind() == "runs-on"))
        .collect();
    if reducible.len() > MAX_TRANSITIVE_REDUCTION_EDGES {
        return edges;
    }
    let weight = |e: &T| e.weight().unwrap_or(1).max(1);
    // Reachability from `source`, ignoring the edge under test.
    let has_path = |source: &str, destination: &str, omit: usize| -> bool {
        let mut queue = std::collections::VecDeque::from([source.to_string()]);
        let mut visited = std::collections::HashSet::new();
        while let Some(current) = queue.pop_front() {
            if !visited.insert(current.clone()) {
                continue;
            }
            for &i in &reducible {
                if i == omit || edges[i].from() != current {
                    continue;
                }
                if edges[i].to() == destination {
                    return true;
                }
                if !visited.contains(edges[i].to()) {
                    queue.push_back(edges[i].to().to_string());
                }
            }
        }
        false
    };
    // Widest-path capacity, so an alternate route is only "as good" if its bottleneck is.
    let capacity = |source: &str, destination: &str, omit: usize| -> i64 {
        let mut queue = std::collections::VecDeque::from([(source.to_string(), i64::MAX)]);
        let mut best: std::collections::HashMap<String, i64> = std::collections::HashMap::new();
        while let Some((current, cap)) = queue.pop_front() {
            if best.get(&current).is_some_and(|&b| b >= cap) {
                continue;
            }
            best.insert(current.clone(), cap);
            for &i in &reducible {
                if i == omit || edges[i].from() != current {
                    continue;
                }
                let next_cap = cap.min(weight(&edges[i]));
                if edges[i].to() == destination {
                    return next_cap;
                }
                if best.get(edges[i].to()).is_none_or(|&b| b < next_cap) {
                    queue.push_back((edges[i].to().to_string(), next_cap));
                }
            }
        }
        -1
    };
    let redundant: std::collections::HashSet<usize> = reducible
        .iter()
        .copied()
        .filter(|&i| {
            let e = &edges[i];
            if HIERARCHY_REDUCTION_KINDS.contains(&e.kind()) {
                has_path(e.from(), e.to(), i)
            } else {
                capacity(e.from(), e.to(), i) >= weight(e)
            }
        })
        .collect();
    edges
        .into_iter()
        .enumerate()
        .filter(|(i, _)| !redundant.contains(i))
        .map(|(_, e)| e)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    struct E(&'static str, &'static str, &'static str, i64);
    impl DirectedEdge for E {
        fn from(&self) -> &str {
            self.0
        }
        fn to(&self) -> &str {
            self.1
        }
        fn kind(&self) -> &str {
            self.2
        }
        fn weight(&self) -> Option<i64> {
            Some(self.3)
        }
    }

    fn pairs(edges: &[E]) -> Vec<(&str, &str)> {
        edges.iter().map(|e| (e.0, e.1)).collect()
    }

    #[test]
    fn a_thin_indirect_path_does_not_hide_a_heavy_direct_edge() {
        let kept = reduce_transitive(
            vec![
                E("a", "b", "uses", 9),
                E("b", "c", "uses", 1),
                E("a", "c", "uses", 5),
            ],
            false,
        );
        assert_eq!(pairs(&kept), vec![("a", "b"), ("b", "c"), ("a", "c")]);
    }

    #[test]
    fn a_strong_indirect_path_removes_the_direct_edge() {
        let kept = reduce_transitive(
            vec![
                E("a", "b", "uses", 9),
                E("b", "c", "uses", 7),
                E("a", "c", "uses", 5),
            ],
            false,
        );
        assert_eq!(pairs(&kept), vec![("a", "b"), ("b", "c")]);
    }

    #[test]
    fn hierarchy_edges_reduce_on_reachability_alone() {
        let kept = reduce_transitive(
            vec![
                E("a", "b", "runs-on", 1),
                E("b", "c", "runs-on", 1),
                E("a", "c", "runs-on", 100),
            ],
            false,
        );
        assert_eq!(pairs(&kept), vec![("a", "b"), ("b", "c")]);
        let kept = reduce_transitive(
            vec![
                E("a", "b", "runs-on", 1),
                E("b", "c", "runs-on", 1),
                E("a", "c", "runs-on", 100),
            ],
            true,
        );
        assert_eq!(kept.len(), 3);
    }
}

//! Graph-to-graph topology derived from Cypher rules.

use crate::registry::{format_instant, GraphStats};
use indexmap::{IndexMap, IndexSet};
use serde_json::{json, Value as J};
use std::path::Path;

pub const MAX_TOPOLOGY_ROWS: usize = 100_000;
pub const MAX_EDGE_DETAILS: usize = 100;
pub const DEFAULT_TOPOLOGY_PROTOCOL: &str = "call";

#[derive(Debug, Clone)]
pub struct TopologyQuery {
    pub name: String,
    pub cypher: String,
}

/// Load `.cypher` rules from a file or directory. A directory is scanned non-recursively.
pub fn load_topology_queries(path: Option<&Path>) -> Result<Vec<TopologyQuery>, String> {
    let path = match path {
        Some(p) => p,
        None => return Ok(Vec::new()),
    };
    let absolute = path
        .canonicalize()
        .map_err(|_| format!("Topology query path does not exist: {}", path.display()))?;
    let mut files: Vec<std::path::PathBuf> = if absolute.is_file() {
        vec![absolute.clone()]
    } else if absolute.is_dir() {
        let mut fs: Vec<std::path::PathBuf> = std::fs::read_dir(&absolute)
            .map_err(|e| e.to_string())?
            .filter_map(|e| e.ok().map(|e| e.path()))
            .filter(|p| p.is_file() && p.to_string_lossy().ends_with(".cypher"))
            .collect();
        fs.sort();
        fs
    } else {
        Vec::new()
    };
    files.retain(|f| f.is_file());
    if files.is_empty() {
        return Err(format!(
            "No .cypher topology queries found at: {}",
            absolute.display()
        ));
    }
    let mut out = Vec::with_capacity(files.len());
    for f in files {
        let text = std::fs::read_to_string(&f).map_err(|e| e.to_string())?;
        let cypher = text.trim().to_string();
        if cypher.is_empty() {
            return Err(format!("Topology query is empty: {}", f.display()));
        }
        out.push(TopologyQuery {
            name: f
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_default(),
            cypher,
        });
    }
    Ok(out)
}

#[derive(Debug, Clone)]
pub struct TopologyNode {
    pub id: String,
    pub stats: GraphStats,
}

#[derive(Debug, Clone)]
pub struct TopologyEdge {
    pub from: String,
    pub to: String,
    pub protocol: String,
    pub weight: i64,
    pub operations: Vec<String>,
    pub evidence: Vec<String>,
}

#[derive(Debug, Clone)]
pub struct TopologyGraph {
    pub nodes: Vec<TopologyNode>,
    pub edges: Vec<TopologyEdge>,
    pub built_at: String,
    pub rules: Vec<String>,
    pub matched_rows: usize,
}

impl TopologyGraph {
    /// Graph nodes with no relations, used when no rules are configured.
    pub fn nodes_only(stats: &IndexMap<String, GraphStats>) -> TopologyGraph {
        let mut ids: Vec<String> = stats.keys().cloned().collect();
        ids.sort();
        TopologyGraph {
            nodes: ids
                .into_iter()
                .map(|id| TopologyNode {
                    stats: stats[&id],
                    id,
                })
                .collect(),
            edges: Vec::new(),
            built_at: crate::registry::now_iso8601(),
            rules: Vec::new(),
            matched_rows: 0,
        }
    }

    pub fn to_api_map(&self, stale: bool) -> J {
        json!({
            "nodes": self.nodes.iter().map(|n| json!({
                "id": n.id,
                "graphId": n.id,
                "type": "Graph",
                "label": n.id,
                "nodes": n.stats.nodes,
                "edges": n.stats.edges,
                "methods": n.stats.methods,
                "callSites": n.stats.call_sites,
            })).collect::<Vec<_>>(),
            "edges": self.edges.iter().map(|e| json!({
                "from": e.from,
                "to": e.to,
                "type": "TopologyCall",
                "protocol": e.protocol,
                "weight": e.weight,
                "operations": e.operations,
                "evidence": e.evidence,
            })).collect::<Vec<_>>(),
            "graphCount": self.nodes.len(),
            "relationCount": self.edges.len(),
            "matchedRows": self.matched_rows,
            "builtAt": self.built_at,
            "rules": self.rules,
            "stale": stale,
        })
    }
}

/// Rows returned by a topology rule.
pub struct QueryRows {
    pub columns: Vec<String>,
    pub rows: Vec<IndexMap<String, J>>,
}

fn optional_text(v: Option<&J>) -> Option<String> {
    let s = match v {
        Some(J::String(s)) => s.clone(),
        Some(J::Number(n)) => n.to_string(),
        Some(J::Bool(b)) => b.to_string(),
        _ => return None,
    };
    let t = s.trim();
    if t.is_empty() {
        None
    } else {
        Some(t.to_string())
    }
}

fn optional_weight(query: &str, v: Option<&J>) -> Result<i64, String> {
    match v {
        None | Some(J::Null) => Ok(1),
        Some(J::Number(n)) => {
            let d = n.as_f64().unwrap_or(0.0);
            let l = d as i64;
            if l > 0 && (l as f64) == d {
                Ok(l)
            } else {
                Err(format!(
                    "Topology query '{query}' returned a non-positive or fractional weight: {n}"
                ))
            }
        }
        Some(other) => {
            let text = optional_text(Some(other)).unwrap_or_default();
            match text.parse::<i64>() {
                Ok(l) if l > 0 => Ok(l),
                Ok(l) => Err(format!(
                    "Topology query '{query}' returned a non-positive weight: {l}"
                )),
                Err(_) => Err(format!(
                    "Topology query '{query}' returned an invalid weight: {text}"
                )),
            }
        }
    }
}

fn add_bounded(set: &mut IndexSet<String>, v: String) {
    if set.len() < MAX_EDGE_DETAILS {
        set.insert(v);
    }
}

/// Aggregate rule rows into a topology graph. `execute` runs one rule and returns its rows.
pub fn build(
    stats: &IndexMap<String, GraphStats>,
    queries: &[TopologyQuery],
    mut execute: impl FnMut(&str, usize) -> Result<QueryRows, String>,
) -> Result<TopologyGraph, String> {
    if queries.is_empty() {
        return Ok(TopologyGraph::nodes_only(stats));
    }
    let loaded: IndexSet<&String> = stats.keys().collect();
    struct Agg {
        weight: i64,
        operations: IndexSet<String>,
        evidence: IndexSet<String>,
    }
    let mut aggregates: IndexMap<(String, String, String), Agg> = IndexMap::new();
    let mut matched_rows = 0usize;
    for q in queries {
        let remaining = MAX_TOPOLOGY_ROWS - matched_rows;
        let result = execute(&q.cypher, remaining + 1)?;
        if !result.columns.iter().any(|c| c == "source")
            || !result.columns.iter().any(|c| c == "target")
        {
            return Err(format!(
                "Topology query '{}' must return 'source' and 'target'",
                q.name
            ));
        }
        if result.rows.len() > remaining {
            return Err(format!(
                "Topology queries exceeded the combined {MAX_TOPOLOGY_ROWS} row limit at '{}'",
                q.name
            ));
        }
        matched_rows += result.rows.len();
        for row in &result.rows {
            let source = required_graph_id(&q.name, row, "source", &loaded)?;
            let target = required_graph_id(&q.name, row, "target", &loaded)?;
            if source == target {
                continue;
            }
            let protocol = optional_text(row.get("protocol"))
                .unwrap_or_else(|| DEFAULT_TOPOLOGY_PROTOCOL.to_string());
            let weight = optional_weight(&q.name, row.get("weight"))?;
            let entry = aggregates
                .entry((source, target, protocol))
                .or_insert_with(|| Agg {
                    weight: 0,
                    operations: IndexSet::new(),
                    evidence: IndexSet::new(),
                });
            entry.weight = entry
                .weight
                .checked_add(weight)
                .ok_or_else(|| "long overflow".to_string())?;
            if let Some(op) = optional_text(row.get("operation")) {
                add_bounded(&mut entry.operations, op);
            }
            if let Some(ev) = optional_text(row.get("evidence")) {
                add_bounded(&mut entry.evidence, ev);
            }
        }
    }
    let mut ids: Vec<&String> = stats.keys().collect();
    ids.sort();
    let mut edges: Vec<TopologyEdge> = aggregates
        .into_iter()
        .map(|((from, to, protocol), a)| {
            let mut operations: Vec<String> = a.operations.into_iter().collect();
            operations.sort();
            let mut evidence: Vec<String> = a.evidence.into_iter().collect();
            evidence.sort();
            TopologyEdge {
                from,
                to,
                protocol,
                weight: a.weight,
                operations,
                evidence,
            }
        })
        .collect();
    edges.sort_by(|a, b| {
        a.from
            .cmp(&b.from)
            .then(a.to.cmp(&b.to))
            .then(a.protocol.cmp(&b.protocol))
    });
    Ok(TopologyGraph {
        nodes: ids
            .into_iter()
            .map(|id| TopologyNode {
                id: id.clone(),
                stats: stats[id],
            })
            .collect(),
        edges,
        built_at: crate::registry::now_iso8601(),
        rules: queries.iter().map(|q| q.name.clone()).collect(),
        matched_rows,
    })
}

fn required_graph_id(
    query: &str,
    row: &IndexMap<String, J>,
    column: &str,
    loaded: &IndexSet<&String>,
) -> Result<String, String> {
    let id = optional_text(row.get(column))
        .ok_or_else(|| format!("Topology query '{query}' returned a blank '{column}'"))?;
    if !loaded.iter().any(|l| **l == id) {
        return Err(format!(
            "Topology query '{query}' returned unknown graph '{id}' in '{column}'"
        ));
    }
    Ok(id)
}

#[allow(dead_code)]
fn _instant_marker(t: chrono::DateTime<chrono::Utc>) -> String {
    format_instant(t)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn stats() -> IndexMap<String, GraphStats> {
        let mut m = IndexMap::new();
        m.insert("orders".to_string(), GraphStats::default());
        m.insert("billing".to_string(), GraphStats::default());
        m
    }

    fn rows(pairs: Vec<Vec<(&str, J)>>) -> QueryRows {
        QueryRows {
            columns: vec!["source".into(), "target".into(), "weight".into()],
            rows: pairs
                .into_iter()
                .map(|p| p.into_iter().map(|(k, v)| (k.to_string(), v)).collect())
                .collect(),
        }
    }

    #[test]
    fn no_rules_yields_sorted_nodes_only() {
        let t = TopologyGraph::nodes_only(&stats());
        assert_eq!(t.nodes.iter().map(|n| n.id.as_str()).collect::<Vec<_>>(), vec!["billing", "orders"]);
        assert!(t.edges.is_empty());
    }

    #[test]
    fn weights_are_summed_per_edge_key() {
        let q = vec![TopologyQuery { name: "r.cypher".into(), cypher: "x".into() }];
        let t = build(&stats(), &q, |_, _| {
            Ok(rows(vec![
                vec![("source", json!("orders")), ("target", json!("billing")), ("weight", json!(2))],
                vec![("source", json!("orders")), ("target", json!("billing")), ("weight", json!(2))],
            ]))
        })
        .unwrap();
        assert_eq!(t.edges.len(), 1);
        assert_eq!(t.edges[0].weight, 4);
        assert_eq!(t.matched_rows, 2);
    }

    #[test]
    fn self_relations_are_dropped() {
        let q = vec![TopologyQuery { name: "r.cypher".into(), cypher: "x".into() }];
        let t = build(&stats(), &q, |_, _| {
            Ok(rows(vec![vec![
                ("source", json!("orders")),
                ("target", json!("orders")),
            ]]))
        })
        .unwrap();
        assert!(t.edges.is_empty());
    }

    #[test]
    fn missing_columns_and_bad_values_are_rejected() {
        let q = vec![TopologyQuery { name: "bad.cypher".into(), cypher: "x".into() }];
        let e = build(&stats(), &q, |_, _| {
            Ok(QueryRows { columns: vec!["source".into()], rows: vec![] })
        })
        .unwrap_err();
        assert_eq!(e, "Topology query 'bad.cypher' must return 'source' and 'target'");

        let e = build(&stats(), &q, |_, _| {
            Ok(rows(vec![vec![
                ("source", json!("orders")),
                ("target", json!("missing")),
            ]]))
        })
        .unwrap_err();
        assert!(e.contains("unknown graph 'missing'"), "{e}");

        let e = build(&stats(), &q, |_, _| {
            Ok(rows(vec![vec![
                ("source", json!("orders")),
                ("target", json!("billing")),
                ("weight", json!(0.5)),
            ]]))
        })
        .unwrap_err();
        assert!(e.contains("fractional weight"), "{e}");

        let e = build(&stats(), &q, |_, _| {
            Ok(rows(vec![vec![("source", json!("")), ("target", json!("billing"))]]))
        })
        .unwrap_err();
        assert!(e.contains("blank 'source'"), "{e}");
    }

    #[test]
    fn string_weights_are_accepted() {
        let q = vec![TopologyQuery { name: "r.cypher".into(), cypher: "x".into() }];
        let t = build(&stats(), &q, |_, _| {
            Ok(rows(vec![vec![
                ("source", json!("orders")),
                ("target", json!("billing")),
                ("weight", json!("3")),
            ]]))
        })
        .unwrap();
        assert_eq!(t.edges[0].weight, 3);
    }

    #[test]
    fn missing_path_is_reported() {
        let e = load_topology_queries(Some(Path::new("/definitely/not/here"))).unwrap_err();
        assert!(e.starts_with("Topology query path does not exist:"), "{e}");
        assert!(load_topology_queries(None).unwrap().is_empty());
    }
}

//! Stage graph changes and their derived topology before publishing one catalog snapshot.
use crate::guard::CancelOnDrop;
use crate::registry::{validate_graph_id, GraphStats, LoadMode, ServedGraph};
use crate::routes::AppState;
use graphite_cypher::engine::{CancelToken, Executor, Source};
use graphite_cypher::materialize::materialize;
use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::Arc;

/// One complete topology rebuild shares 10M evaluator/scan ticks and explicit
/// storage/materialization checkpoints across every rule. Its deadline is the server
/// Cypher maximum, so maintenance cannot outlive the query cancellation policy.
/// These limits apply only to topology construction, never to ordinary queries or
/// to the preceding file load/fingerprint/declared-table preparation.
pub(crate) const DEFAULT_TOPOLOGY_WORK_LIMIT: u64 = 10_000_000;
type Catalog = BTreeMap<String, Arc<ServedGraph>>;

pub(crate) struct ServedTopology {
    pub graph: crate::topology::TopologyGraph,
    pub catalog: BTreeMap<String, u64>,
}

pub(crate) enum GraphUpdate {
    Load {
        id: String,
        path: PathBuf,
        mode: Option<LoadMode>,
    },
    Unload {
        id: String,
    },
}

#[cfg(test)]
#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum UpdatePhase {
    Prepared,
    BeforeCommit,
}
#[cfg(test)]
pub(crate) type UpdateHook = Arc<dyn Fn(&str, UpdatePhase) + Send + Sync>;

impl AppState {
    /// Startup uses the same snapshot validation and finite topology limits as updates.
    pub fn rebuild_topology(&self) -> Result<(usize, usize), String> {
        let snapshot = self.registry.snapshot();
        let topology = self.build_topology_snapshot(&snapshot, &CancelToken::new())?;
        let summary = (topology.graph.nodes.len(), topology.graph.edges.len());
        self.registry
            .commit_snapshot(&snapshot, snapshot.clone(), || {
                Ok(self.topology.write().replace(topology))
            })?;
        Ok(summary)
    }

    fn build_topology_snapshot(
        &self,
        snapshot: &Catalog,
        request_cancel: &Arc<CancelToken>,
    ) -> Result<ServedTopology, String> {
        let cancel = CancelToken::child_with_limits(
            request_cancel.clone(),
            self.guard.max_timeout_millis,
            self.topology_work_limit,
        );
        cancel.check().map_err(|error| error.to_string())?;
        let stats = snapshot
            .iter()
            .map(|(id, served)| (id.clone(), served.stats))
            .collect();
        let sources = snapshot
            .iter()
            .map(|(id, served)| Source {
                id: Arc::from(id.as_str()),
                graph: served.graph.clone(),
            })
            .collect();
        let executor = Executor::new(sources, true).with_cancel(cancel.clone());
        let graph = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            crate::topology::build(&stats, &self.topology_queries, |cypher, max_rows| {
                let result = executor
                    .execute(cypher, Some(max_rows))
                    .map_err(|error| error.to_string())?;
                let mut rows = Vec::with_capacity(result.rows.len());
                for row in &result.rows {
                    cancel.check().map_err(|error| error.to_string())?;
                    rows.push(
                        row.iter()
                            .filter(|(key, _)| !key.starts_with('$'))
                            .map(|(key, value)| (key.clone(), materialize(value, &executor)))
                            .collect(),
                    );
                }
                Ok(crate::topology::QueryRows {
                    columns: result.columns,
                    rows,
                })
            })
        }))
        .map_err(|_| "Topology query execution failed".to_string())??;
        cancel.check().map_err(|error| error.to_string())?;
        Ok(ServedTopology {
            graph,
            catalog: snapshot
                .iter()
                .map(|(id, graph)| (id.clone(), graph.generation))
                .collect(),
        })
    }

    /// The permit and the graph leases live in the blocking job even if the HTTP
    /// future disappears. Dropping that future cancels work and prevents publication.
    pub(crate) async fn update_graph(
        self: Arc<Self>,
        update: GraphUpdate,
    ) -> Result<Option<Arc<ServedGraph>>, String> {
        let permit = self
            .graph_updates
            .clone()
            .acquire_owned()
            .await
            .map_err(|error| error.to_string())?;
        let cancel = CancelToken::new();
        let _cancel_on_drop = CancelOnDrop(cancel.clone());
        tokio::task::spawn_blocking(move || {
            let _permit = permit;
            let result = self.update_graph_blocking(update, &cancel)?;
            self.prune_c4();
            if let Some(graph) = &result {
                if self
                    .registry
                    .describe(&graph.id)?
                    .is_some_and(|current| current.generation == graph.generation)
                {
                    self.warm_c4_graphs([graph.id.clone()]);
                }
            }
            Ok(result)
        })
        .await
        .map_err(|_| "Graph update failed".to_string())?
    }

    fn update_graph_blocking(
        &self,
        update: GraphUpdate,
        cancel: &Arc<CancelToken>,
    ) -> Result<Option<Arc<ServedGraph>>, String> {
        cancel.check().map_err(|error| error.to_string())?;
        let expected = self.registry.snapshot();
        let mut next = expected.clone();
        let (id, served) = match update {
            GraphUpdate::Load { id, path, mode } => {
                let graph = self.registry.prepare(&id, &path, mode)?;
                if !graphite_cypher::engine::optimizations_disabled() {
                    graph.graph.declared_types();
                    if let Some(error) = graph.graph.declared_types_failure() {
                        return Err(error.to_string());
                    }
                }
                next.insert(graph.id.clone(), graph.clone());
                (graph.id.clone(), Some(graph))
            }
            GraphUpdate::Unload { id } => {
                let id = validate_graph_id(&id)?;
                let removed = next.remove(&id);
                if removed.is_none() {
                    return Ok(None);
                }
                (id, removed)
            }
        };
        #[cfg(test)]
        if let Some(hook) = &self.graph_update_hook {
            hook(&id, UpdatePhase::Prepared);
        }
        #[cfg(not(test))]
        let _ = &id;
        cancel.check().map_err(|error| error.to_string())?;
        let topology = self.build_topology_snapshot(&next, cancel)?;
        #[cfg(test)]
        if let Some(hook) = &self.graph_update_hook {
            hook(&id, UpdatePhase::BeforeCommit);
        }
        self.registry.commit_snapshot(&expected, next, || {
            cancel.check().map_err(|error| error.to_string())?;
            Ok(self.topology.write().replace(topology))
        })?;
        Ok(served)
    }

    /// Always take the catalog lock before the topology lock, just as a publisher
    /// does. A reader sees one consistent pair and never the middle of a commit.
    pub(crate) fn topology_api_map(&self) -> serde_json::Value {
        self.registry.read_catalog(|snapshot| {
            let current = self.topology.read();
            match current.as_ref() {
                Some(topology) => topology.graph.to_api_map(
                    topology.catalog
                        != snapshot
                            .iter()
                            .map(|(id, graph)| (id.clone(), graph.generation))
                            .collect(),
                ),
                None => {
                    let stats: indexmap::IndexMap<String, GraphStats> = snapshot
                        .iter()
                        .map(|(id, graph)| (id.clone(), graph.stats))
                        .collect();
                    crate::topology::TopologyGraph::nodes_only(&stats).to_api_map(false)
                }
            }
        })
    }
}

#[cfg(test)]
#[path = "graph_updates_tests.rs"]
mod tests;

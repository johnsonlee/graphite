//! An opt-in mutable property graph. Persisted program graphs remain immutable.
//!
//! This store uses the same executor, expressions and query pipeline as persisted
//! graphs. Relationship identities are independent of endpoints and type, so
//! parallel relationships and relationship-isomorphic paths remain distinct.
use crate::value::{EdgeRef, NodeRef, Value};
use graphite_storage::{Edge, NodeId};
use indexmap::IndexMap;
use parking_lot::{Mutex, RwLock};

#[derive(Clone, Debug)]
pub struct PropertyNode {
    pub id: NodeId,
    pub labels: Vec<String>,
    pub properties: IndexMap<String, Value>,
}
#[derive(Clone, Debug)]
pub struct PropertyRelationship {
    pub id: u64,
    pub from: NodeId,
    pub to: NodeId,
    pub type_name: String,
    pub properties: IndexMap<String, Value>,
}
impl PropertyRelationship {
    pub(crate) fn reference(&self) -> EdgeRef {
        EdgeRef {
            source: 0,
            edge: Edge {
                from: self.from,
                to: self.to,
                label: 0,
                v2: false,
            },
            property_id: Some(
                std::num::NonZeroU32::new(self.id as u32)
                    .expect("allocated property relationship identity"),
            ),
        }
    }
}
#[derive(Clone, Debug, Default)]
pub struct PropertySnapshot {
    pub nodes: Vec<PropertyNode>,
    pub relationships: Vec<PropertyRelationship>,
}
#[derive(Clone, Default)]
pub(crate) struct State {
    nodes: IndexMap<NodeId, PropertyNode>,
    relationships: IndexMap<u64, PropertyRelationship>,
    next_node: NodeId,
    next_relationship: u64,
}
#[derive(Default)]
pub struct PropertyGraph {
    transaction: Mutex<()>,
    state: RwLock<State>,
}
impl PropertyGraph {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn snapshot(&self) -> PropertySnapshot {
        let state = self.state.read();
        PropertySnapshot {
            nodes: state.nodes.values().cloned().collect(),
            relationships: state.relationships.values().cloned().collect(),
        }
    }
    pub fn node(&self, id: NodeId) -> Option<PropertyNode> {
        self.state.read().nodes.get(&id).cloned()
    }
    pub fn relationship(&self, id: u64) -> Option<PropertyRelationship> {
        self.state.read().relationships.get(&id).cloned()
    }
    pub(crate) fn begin_transaction(&self) -> PropertyTransaction<'_> {
        let guard = self.transaction.lock();
        let checkpoint = self.state.read().clone();
        PropertyTransaction {
            graph: self,
            checkpoint: Some(checkpoint),
            _guard: guard,
        }
    }
    pub(crate) fn create_node(
        &self,
        labels: &[String],
        mut properties: IndexMap<String, Value>,
    ) -> crate::CypherResult<NodeRef> {
        for value in properties.values() {
            validate_property(value)?;
        }
        properties.retain(|_, v| !v.is_null());
        let mut state = self.state.write();
        let id = state.next_node;
        state.next_node = id
            .checked_add(1)
            .ok_or_else(|| crate::CypherError::runtime("property graph node identity exhausted"))?;
        let mut unique = Vec::new();
        for label in labels {
            if !unique.contains(label) {
                unique.push(label.clone());
            }
        }
        state.nodes.insert(
            id,
            PropertyNode {
                id,
                labels: unique,
                properties,
            },
        );
        Ok(NodeRef { source: 0, id })
    }
    pub(crate) fn create_relationship(
        &self,
        from: NodeId,
        to: NodeId,
        type_name: &str,
        mut properties: IndexMap<String, Value>,
    ) -> crate::CypherResult<EdgeRef> {
        for value in properties.values() {
            validate_property(value)?;
        }
        properties.retain(|_, v| !v.is_null());
        let mut state = self.state.write();
        if !state.nodes.contains_key(&from) || !state.nodes.contains_key(&to) {
            return Err(crate::CypherError::runtime(
                "relationship endpoint is missing",
            ));
        }
        let id = state
            .next_relationship
            .checked_add(1)
            .filter(|id| *id <= u64::from(u32::MAX))
            .ok_or_else(|| {
                crate::CypherError::runtime("property graph relationship identity exhausted")
            })?;
        state.next_relationship = id;
        let relationship = PropertyRelationship {
            id,
            from,
            to,
            type_name: type_name.to_owned(),
            properties,
        };
        let reference = relationship.reference();
        state.relationships.insert(id, relationship);
        Ok(reference)
    }
}

/// Roll back both reported query errors and unwinding panics. Holding the session
/// lock prevents rollback from overwriting another concurrent transaction.
pub(crate) struct PropertyTransaction<'a> {
    graph: &'a PropertyGraph,
    checkpoint: Option<State>,
    _guard: parking_lot::MutexGuard<'a, ()>,
}
impl PropertyTransaction<'_> {
    pub fn commit(&mut self) {
        self.checkpoint = None;
    }
}
impl Drop for PropertyTransaction<'_> {
    fn drop(&mut self) {
        if let Some(state) = self.checkpoint.take() {
            *self.graph.state.write() = state;
        }
    }
}

/// Property values never contain graph references or recursively nested containers.
/// Besides matching property graph semantics, this prevents graph rendering from
/// recursively following a node property back to the same node.
pub(crate) fn validate_property(value: &Value) -> crate::CypherResult<()> {
    let invalid = || {
        crate::CypherError::runtime("Property values must be primitive values or homogeneous lists")
    };
    match value {
        Value::Map(_) | Value::Node(_) | Value::Rel(_) | Value::Path(_) | Value::Method(_) => {
            Err(invalid())
        }
        Value::List(values) => {
            let mut family = None;
            for value in values.iter() {
                if matches!(value, Value::Null | Value::List(_)) {
                    return Err(invalid());
                }
                validate_property(value)?;
                let current = match value {
                    Value::Bool(_) => 1,
                    Value::Int(_) | Value::Float(_) | Value::Float32(_) => 2,
                    Value::Str(_) => 3,
                    _ => 4,
                };
                if family.is_some_and(|previous| previous != current) {
                    return Err(invalid());
                }
                family = Some(current);
            }
            Ok(())
        }
        _ => Ok(()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn transaction_unwind_restores_state_and_releases_the_session() {
        let graph = PropertyGraph::new();
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _transaction = graph.begin_transaction();
            graph
                .create_node(&["Temporary".to_owned()], IndexMap::new())
                .unwrap();
            panic!("rollback");
        }));
        assert!(result.is_err());
        assert!(graph.snapshot().nodes.is_empty());
        let mut transaction = graph.begin_transaction();
        let node = graph
            .create_node(&["Committed".to_owned()], IndexMap::new())
            .unwrap();
        assert_eq!(node.id, 0);
        transaction.commit();
        drop(transaction);
        assert_eq!(graph.snapshot().nodes[0].labels, vec!["Committed"]);
    }
}

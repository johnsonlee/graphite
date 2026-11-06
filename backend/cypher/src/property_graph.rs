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
    deleted_relationship_types: IndexMap<u64, String>,
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
    /// Relationship type is identity metadata and remains available after DELETE;
    /// deleted property payloads are never retained or exposed as live data.
    pub fn relationship_type(&self, id: u64) -> Option<String> {
        let state = self.state.read();
        state
            .relationships
            .get(&id)
            .map(|r| r.type_name.clone())
            .or_else(|| state.deleted_relationship_types.get(&id).cloned())
    }

    pub fn ensure_live_entity(&self, value: &Value) -> crate::CypherResult<()> {
        let state = self.state.read();
        let live = match value {
            Value::Node(node) => node.source == 0 && state.nodes.contains_key(&node.id),
            Value::Rel(rel) => {
                rel.source == 0
                    && rel
                        .property_id
                        .is_some_and(|id| state.relationships.contains_key(&u64::from(id.get())))
            }
            _ => true,
        };
        if live {
            Ok(())
        } else {
            Err(crate::CypherError::execution(
                "EntityNotFound",
                "DeletedEntityAccess",
                "Graph entity has been deleted or belongs to another graph",
            ))
        }
    }
    pub(crate) fn begin_transaction(
        &self,
        mut check: impl FnMut() -> crate::CypherResult<()>,
    ) -> crate::CypherResult<PropertyTransaction<'_>> {
        // A long-running query must not keep queued cancellation/deadlines from
        // being observed. Keep the same exclusive lock through commit/rollback.
        let guard = loop {
            check()?;
            if let Some(guard) = self
                .transaction
                .try_lock_for(std::time::Duration::from_millis(10))
            {
                break guard;
            }
        };
        check()?;
        let checkpoint = self.state.read().clone();
        check()?;
        Ok(PropertyTransaction {
            graph: self,
            checkpoint: Some(checkpoint),
            _guard: guard,
        })
    }
    pub(crate) fn set_property(
        &self,
        target: &Value,
        key: &str,
        value: Value,
    ) -> crate::CypherResult<()> {
        if target.is_null() {
            return Ok(());
        }
        validate_property(&value)?;
        let mut state = self.state.write();
        let properties = entity_properties(&mut state, target)?;
        if value.is_null() {
            properties.shift_remove(key);
        } else {
            properties.insert(key.to_owned(), value);
        }
        Ok(())
    }
    pub(crate) fn set_properties(
        &self,
        target: &Value,
        values: Value,
        merge: bool,
    ) -> crate::CypherResult<()> {
        if target.is_null() {
            return Ok(());
        }
        let values = match values {
            Value::Map(values) => (*values).clone(),
            Value::Node(node) => {
                self.node(node.id)
                    .ok_or_else(|| crate::CypherError::runtime("Graph entity has been deleted"))?
                    .properties
            }
            Value::Rel(rel) if rel.property_id.is_some() => {
                self.relationship(u64::from(rel.property_id.unwrap().get()))
                    .ok_or_else(|| crate::CypherError::runtime("Graph entity has been deleted"))?
                    .properties
            }
            _ => {
                return Err(crate::CypherError::runtime(
                    "SET map assignment requires a map, node or relationship",
                ))
            }
        };
        for value in values.values() {
            validate_property(value)?;
        }
        let mut state = self.state.write();
        let properties = entity_properties(&mut state, target)?;
        if !merge {
            properties.clear();
        }
        for (key, value) in values.iter() {
            if value.is_null() {
                properties.shift_remove(key);
            } else {
                properties.insert(key.clone(), value.clone());
            }
        }
        Ok(())
    }
    pub(crate) fn update_labels(
        &self,
        target: &Value,
        labels: &[String],
        remove: bool,
    ) -> crate::CypherResult<()> {
        if target.is_null() {
            return Ok(());
        }
        let Value::Node(node) = target else {
            return Err(crate::CypherError::runtime(
                "Labels can only be updated on nodes",
            ));
        };
        let mut state = self.state.write();
        let node = state
            .nodes
            .get_mut(&node.id)
            .ok_or_else(|| crate::CypherError::runtime("Graph entity has been deleted"))?;
        for label in labels {
            if remove {
                node.labels.retain(|old| old != label);
            } else if !node.labels.contains(label) {
                node.labels.push(label.clone());
            }
        }
        Ok(())
    }
    pub(crate) fn delete(&self, targets: &[Value], detach: bool) -> crate::CypherResult<()> {
        let mut nodes = std::collections::HashSet::new();
        let mut relationships = std::collections::HashSet::new();
        for target in targets {
            match target {
                Value::Null => {}
                Value::Node(node) => {
                    nodes.insert(node.id);
                }
                Value::Rel(rel) if rel.property_id.is_some() => {
                    relationships.insert(u64::from(rel.property_id.unwrap().get()));
                }
                Value::Path(path) if path.property_path.is_some() => {
                    nodes.extend(path.nodes.iter().copied());
                    relationships.extend(
                        path.property_path
                            .as_ref()
                            .unwrap()
                            .relationship_ids
                            .iter()
                            .map(|id| u64::from(id.get())),
                    );
                }
                _ => {
                    return Err(crate::CypherError::runtime(
                        "DELETE requires a node, relationship or path",
                    ))
                }
            }
        }
        let mut state = self.state.write();
        for rel in state.relationships.values() {
            if nodes.contains(&rel.from) || nodes.contains(&rel.to) {
                if detach {
                    relationships.insert(rel.id);
                } else if !relationships.contains(&rel.id) {
                    return Err(crate::CypherError::execution(
                        "ConstraintVerificationFailed",
                        "DeleteConnectedNode",
                        "Cannot delete node, because it still has relationships",
                    ));
                }
            }
        }
        for id in relationships {
            if let Some(relationship) = state.relationships.shift_remove(&id) {
                state
                    .deleted_relationship_types
                    .insert(id, relationship.type_name);
            }
        }
        for id in nodes {
            state.nodes.shift_remove(&id);
        }
        Ok(())
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

fn entity_properties<'a>(
    state: &'a mut State,
    target: &Value,
) -> crate::CypherResult<&'a mut IndexMap<String, Value>> {
    match target {
        Value::Node(node) => state
            .nodes
            .get_mut(&node.id)
            .map(|node| &mut node.properties),
        Value::Rel(rel) if rel.property_id.is_some() => state
            .relationships
            .get_mut(&u64::from(rel.property_id.unwrap().get()))
            .map(|rel| &mut rel.properties),
        _ => {
            return Err(crate::CypherError::runtime(
                "Properties can only be updated on nodes or relationships",
            ))
        }
    }
    .ok_or_else(|| crate::CypherError::runtime("Graph entity has been deleted"))
}

/// Property values never contain graph references or recursively nested containers.
/// Besides matching property graph semantics, this prevents graph rendering from
/// recursively following a node property back to the same node.
pub(crate) fn validate_property(value: &Value) -> crate::CypherResult<()> {
    let invalid = || {
        crate::CypherError::execution(
            "TypeError",
            "InvalidPropertyType",
            "Property values must be primitive values or homogeneous lists",
        )
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
            let _transaction = graph.begin_transaction(|| Ok(())).unwrap();
            graph
                .create_node(&["Temporary".to_owned()], IndexMap::new())
                .unwrap();
            panic!("rollback");
        }));
        assert!(result.is_err());
        assert!(graph.snapshot().nodes.is_empty());
        let mut transaction = graph.begin_transaction(|| Ok(())).unwrap();
        let node = graph
            .create_node(&["Committed".to_owned()], IndexMap::new())
            .unwrap();
        assert_eq!(node.id, 0);
        transaction.commit();
        drop(transaction);
        assert_eq!(graph.snapshot().nodes[0].labels, vec!["Committed"]);
    }
}

//! Negative proofs for composed method properties and dictionary-backed types.
//!
//! The CallSite trigram index covers only four class/name fields, not parameter
//! types or methods referenced only by locals. Only the complete dictionary can
//! establish absence here. Dynamic values and synthetic graph identities are not
//! covered by this proof.

use super::{Graph, PredTree, PushOp, StringPredicate, TagPlan, Transform};
use crate::engine::CancelToken;
use crate::CypherResult;
use graphite_storage::node::{
    TAG_CALL_SITE_NODE, TAG_LOCAL_VARIABLE, TAG_PARAMETER_NODE, TAG_RETURN_NODE,
};
use std::collections::HashSet;

#[cfg(test)]
mod tests;

/// Only substrings that must lie wholly inside one original dictionary component.
/// Signatures insert '.', '(', ',' and ')'. JVM descriptors also rewrite dots,
/// wrap object names in L...;, prefix arrays with [, and concatenate primitive
/// codes. A descriptor literal with no wrapper/separator or L, and at least one
/// non-primitive-code character, cannot be formed across component boundaries.
/// A prefix match implies substring presence too, so the same proof applies to
/// STARTS WITH. Return actual_type is stored verbatim in the dictionary and needs
/// no separator restrictions. Other operators, transforms and ambiguous literals
/// retain their existing path.
fn component_literal(p: &StringPredicate, tag: u8) -> Option<&str> {
    if p.shape != super::LeafShape::Text
        || !matches!(p.op, PushOp::Contains | PushOp::StartsWith)
        || p.transform != Transform::None
        || p.literal.is_empty()
    {
        return None;
    }
    let safe = match tag {
        TAG_CALL_SITE_NODE if matches!(p.prop(), "caller_descriptor" | "callee_descriptor") => {
            !p.literal.contains(['.', '(', ')', '/', ';', '[', ']', 'L'])
                && !p.literal.bytes().all(|b| b"BCDFIJSZV".contains(&b))
        }
        TAG_CALL_SITE_NODE if matches!(p.prop(), "caller_signature" | "callee_signature") => {
            !p.literal.contains(['.', '(', ',', ')'])
        }
        TAG_RETURN_NODE if p.prop() == "actual_type" => true,
        TAG_LOCAL_VARIABLE | TAG_PARAMETER_NODE | TAG_RETURN_NODE => {
            p.prop() == "method" && !p.literal.contains(['.', '(', ',', ')'])
        }
        _ => false,
    };
    safe.then_some(p.literal.as_str())
}

fn collect_terms<'a>(tree: &'a PredTree, tag: u8, terms: &mut Vec<&'a StringPredicate>) {
    match tree {
        PredTree::Leaf(p) => {
            if component_literal(p, tag).is_some()
                && !terms.iter().any(|other| other.test == p.test)
            {
                terms.push(p);
            }
        }
        PredTree::Or(children) | PredTree::And(children) => {
            for child in children {
                collect_terms(child, tag, terms);
            }
        }
    }
}

/// One conservative arena search per distinct test, shared across properties
/// and tags within one graph. A memo must never be reused for a different graph.
pub(super) struct DictionaryMemo {
    absent: Vec<Option<bool>>,
}

impl DictionaryMemo {
    pub(super) fn new(tests: usize) -> Self {
        Self {
            absent: vec![None; tests],
        }
    }

    fn get_or_try_insert(
        &mut self,
        test: usize,
        check_absent: impl FnOnce() -> CypherResult<bool>,
    ) -> CypherResult<bool> {
        if let Some(absent) = self.absent[test] {
            return Ok(absent);
        }
        let absent = check_absent()?;
        self.absent[test] = Some(absent);
        Ok(absent)
    }

    pub(super) fn absent(
        &mut self,
        graph: &Graph,
        p: &StringPredicate,
        cancel: &CancelToken,
    ) -> CypherResult<bool> {
        cancel.check()?;
        let absent = self.get_or_try_insert(p.test, || {
            graph
                .strings()
                .try_any_contains(&p.literal, || cancel.check())
                .map(|found| !found)
        })?;
        cancel.check()?;
        Ok(absent)
    }
}

/// None is false, not unknown: OR drops a false branch, AND becomes false.
fn prune_tree(tree: &PredTree, tag: u8, absent: &HashSet<usize>) -> Option<PredTree> {
    match tree {
        PredTree::Leaf(p) => {
            if component_literal(p, tag).is_some() && absent.contains(&p.test) {
                None
            } else {
                Some(tree.clone())
            }
        }
        PredTree::Or(children) => {
            let mut kept: Vec<_> = children
                .iter()
                .filter_map(|child| prune_tree(child, tag, absent))
                .collect();
            match kept.len() {
                0 => None,
                1 => kept.pop(),
                _ => Some(PredTree::Or(kept)),
            }
        }
        PredTree::And(children) => children
            .iter()
            .map(|child| prune_tree(child, tag, absent))
            .collect::<Option<Vec<_>>>()
            .map(PredTree::And),
    }
}

pub(super) fn prune_plans(
    plans: &[TagPlan],
    graph: &Graph,
    cancel: &CancelToken,
    memo: &mut DictionaryMemo,
) -> CypherResult<Option<Vec<TagPlan>>> {
    cancel.check()?;
    let mut terms = Vec::new();
    for (tag, plan) in plans.iter().enumerate() {
        if graph.ids_by_tag(tag as u8).is_empty() {
            continue;
        }
        if let TagPlan::CallSite(tree) | TagPlan::Column(tree) = plan {
            collect_terms(tree, tag as u8, &mut terms);
        }
    }
    if terms.is_empty() {
        return Ok(None);
    }
    let mut absent = HashSet::new();
    for term in terms {
        if memo.absent(graph, term, cancel)? {
            absent.insert(term.test);
        }
    }
    if absent.is_empty() {
        return Ok(None);
    }
    Ok(Some(
        plans
            .iter()
            .enumerate()
            .map(|(tag, plan)| match plan {
                TagPlan::CallSite(tree) => prune_tree(tree, tag as u8, &absent)
                    .map(TagPlan::CallSite)
                    .unwrap_or(TagPlan::Skip),
                TagPlan::Column(tree) => prune_tree(tree, tag as u8, &absent)
                    .map(TagPlan::Column)
                    .unwrap_or(TagPlan::Skip),
                _ => plan.clone(),
            })
            .collect(),
    ))
}

/// `None` proves the complete CallSite predicate false for this graph.
pub(super) fn prune_callsite(
    tree: &PredTree,
    graph: &Graph,
    cancel: &CancelToken,
    memo: &mut DictionaryMemo,
) -> CypherResult<Option<PredTree>> {
    cancel.check()?;
    let mut terms = Vec::new();
    collect_terms(tree, TAG_CALL_SITE_NODE, &mut terms);
    let mut absent = HashSet::new();
    for term in terms {
        if memo.absent(graph, term, cancel)? {
            absent.insert(term.test);
        }
    }
    Ok(prune_tree(tree, TAG_CALL_SITE_NODE, &absent))
}

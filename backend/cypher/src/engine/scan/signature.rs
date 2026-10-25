//! Negative proofs for composed method signatures in broad property searches.
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
/// Other operators, transforms and ambiguous literals retain their existing path.
fn component_literal(p: &StringPredicate, tag: u8) -> Option<&str> {
    if p.shape != super::LeafShape::Text
        || p.op != PushOp::Contains
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
        TAG_LOCAL_VARIABLE | TAG_PARAMETER_NODE | TAG_RETURN_NODE => {
            p.prop() == "method" && !p.literal.contains(['.', '(', ',', ')'])
        }
        _ => false,
    };
    safe.then_some(p.literal.as_str())
}

fn collect_terms<'a>(tree: &'a PredTree, tag: u8, terms: &mut Vec<(usize, &'a str)>) {
    match tree {
        PredTree::Leaf(p) => {
            if let Some(text) = component_literal(p, tag) {
                if !terms.iter().any(|(id, _)| *id == p.test) {
                    terms.push((p.test, text));
                }
            }
        }
        PredTree::Or(children) | PredTree::And(children) => {
            for child in children {
                collect_terms(child, tag, terms);
            }
        }
    }
}

/// One dictionary pass for every distinct eligible test. Stop testing a term
/// after its first occurrence; stop reading the dictionary when all are found.
/// Memory is proportional to the query's terms, not the graph's string count.
fn absent_terms<'a>(
    mut pending: Vec<(usize, &str)>,
    strings: impl IntoIterator<Item = &'a str>,
    cancel: &CancelToken,
) -> CypherResult<HashSet<usize>> {
    cancel.check()?;
    if !pending.is_empty() {
        for (i, text) in strings.into_iter().enumerate() {
            if i % 1024 == 0 {
                cancel.check()?;
            }
            pending.retain(|(_, literal)| !text.contains(literal));
            if pending.is_empty() {
                break;
            }
        }
    }
    cancel.check()?;
    Ok(pending.into_iter().map(|(id, _)| id).collect())
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
) -> CypherResult<Option<Vec<TagPlan>>> {
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
    let absent = absent_terms(
        terms,
        (0..graph.strings.len()).map(|id| graph.strings.get(id)),
        cancel,
    )?;
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

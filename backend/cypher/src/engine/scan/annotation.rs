//! Conservative dictionary absence proofs for dynamically rendered annotations.

use super::signature::DictionaryMemo;
use super::{
    boolean_text_can_satisfy, is_synthetic_key, number_text_can_satisfy, synthetic_truth, Executor,
    Fold, LeafShape, PredTree, PushOp, SourceIdx, StringPredicate, Transform,
};
use crate::CypherResult;
use graphite_storage::node::{tag_type_name, TAG_ANNOTATION_NODE};

#[cfg(test)]
mod tests;

// Map/list rendering inserts `, `, and enums insert both punctuation and fixed
// words. A leading space can join an atom WITHOUT a comma in the literal:
// `{alpha=x, beta=y}` contains ` beta`, even if no dictionary string does.
// Reject leading spaces as well as punctuation. An inserted space later inside a
// literal would require its preceding comma inside the literal too, which this
// punctuation guard excludes. Interior/trailing dictionary spaces remain safe.
const SEPARATORS: [char; 9] = ['{', '}', '=', ',', '[', ']', '(', ')', '.'];
const FIXED_ATOMS: [&str; 4] = ["null", "EnumValueReference", "enumClass", "enumName"];

fn dictionary_settles(p: &StringPredicate) -> bool {
    p.shape == LeafShape::Text
        && matches!(
            p.op,
            PushOp::Contains | PushOp::Equals | PushOp::StartsWith | PushOp::EndsWith
        )
        && p.transform == Transform::None
        && p.regex.is_none()
        && !is_synthetic_key(p.prop())
        && !p.literal.is_empty()
        && !p.literal.contains(SEPARATORS)
        && !p.literal.starts_with(' ')
        && !number_text_can_satisfy(p)
        && !boolean_text_can_satisfy(p)
        && !FIXED_ATOMS.iter().any(|atom| atom.contains(&p.literal))
        // An annotation may carry `type: null`: keys() then includes `type`,
        // while n[k] falls back to the built-in type name on that null value.
        && (p.prop() != "type" || !tag_type_name(TAG_ANNOTATION_NODE).contains(&p.literal))
}

/// A direct dynamic property cannot exist when its key is not in the sorted
/// dictionary. This logarithmic key lookup precedes any whole-arena literal
/// search. ANY(keys(n)) leaves stand for open-ended attributes, not their finite
/// expansion's property names; built-ins and cross-graph identities need no
/// dictionary key at all. Outside cross mode the identity names are dynamic.
fn keys_cannot_match(ex: &Executor, source: SourceIdx, tree: &PredTree) -> CypherResult<bool> {
    ex.cancel.check()?;
    let absent = match tree {
        PredTree::Leaf(p) => {
            if p.from_keys
                || matches!(
                    p.prop(),
                    "id" | "name" | "class" | "member" | "values" | "type"
                )
                || (ex.cross && is_synthetic_key(p.prop()))
            {
                false
            } else {
                ex.graph(source).strings().index_of(p.prop()).is_none()
            }
        }
        PredTree::Or(children) => {
            for child in children {
                if !keys_cannot_match(ex, source, child)? {
                    return Ok(false);
                }
            }
            true
        }
        PredTree::And(children) => {
            for child in children {
                if keys_cannot_match(ex, source, child)? {
                    return Ok(true);
                }
            }
            false
        }
    };
    ex.cancel.check()?;
    Ok(absent)
}

/// `true` proves the whole tree false for every annotation in this source;
/// `false` means unknown, never that any particular annotation matches.
pub(super) fn cannot_match(
    ex: &Executor,
    source: SourceIdx,
    tree: &PredTree,
    memo: &mut DictionaryMemo,
) -> CypherResult<bool> {
    ex.cancel.check()?;
    // Check the complete subtree first: an absent key later in a conjunction
    // should avoid the arena search of an earlier, dictionary-present key.
    if keys_cannot_match(ex, source, tree)? {
        return Ok(true);
    }
    match tree {
        // Outside cross-graph mode these names may be ordinary annotation keys;
        // the executor only replaces them with graph identity when cross=true.
        PredTree::Leaf(p) if ex.cross && is_synthetic_key(p.prop()) => {
            Ok(matches!(synthetic_truth(ex, source, p), Fold::Known(false)))
        }
        PredTree::Leaf(p) => {
            Ok(dictionary_settles(p) && memo.absent(ex.graph(source), p, &ex.cancel)?)
        }
        PredTree::Or(children) => {
            for child in children {
                if !cannot_match(ex, source, child, memo)? {
                    return Ok(false);
                }
            }
            Ok(true)
        }
        PredTree::And(children) => {
            for child in children {
                if cannot_match(ex, source, child, memo)? {
                    return Ok(true);
                }
            }
            Ok(false)
        }
    }
}

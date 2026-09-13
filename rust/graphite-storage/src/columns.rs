//! Per-(node type, property) string columns for the raw string properties that are not
//! CallSite's — `StringConstant.value`, `Field.name`, `LocalVariable.type` and so on.
//!
//! A broad query on one of these used to decode every node of every type in every graph
//! to find the property's value, because only CallSite records had a raw path. The
//! Kotlin server reads such a property straight out of the record at a fixed offset and
//! keeps, per type and property, the string id of every node alongside the node ids, so
//! that "which nodes carry a matching string" is a walk over one flat array. This is the
//! same structure: built on first use from one pass over the type's records, and kept
//! for the life of the graph.
//!
//! The columns also carry what the CallSite index carries for its own strings: a
//! lowercase trigram index over the distinct strings of the column, so a `CONTAINS`
//! narrows to a handful of candidates before any string is decoded.

use crate::io::read_i32_at;
use crate::node::{
    NODE_HEADER_BYTES, TAG_ANNOTATION_NODE, TAG_ENUM_CONSTANT, TAG_FIELD_NODE, TAG_LOCAL_VARIABLE,
    TAG_PARAMETER_NODE, TAG_RESOURCE_FILE_NODE, TAG_RESOURCE_VALUE_NODE, TAG_STRING_CONSTANT,
};
use crate::strings::StringTable;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

/// A string-valued property that sits at a fixed offset in every record of its tag.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RawStringField {
    pub tag: u8,
    pub property: &'static str,
    /// Byte offset of the big-endian string id after the 5-byte record header.
    pub offset: usize,
}

/// Every raw string field other than CallSite's four, which the CallSite index owns.
/// Offsets follow the record layouts `Node::read` decodes.
pub const RAW_STRING_FIELDS: &[RawStringField] = &[
    RawStringField {
        tag: TAG_STRING_CONSTANT,
        property: "value",
        offset: 0,
    },
    RawStringField {
        tag: TAG_ENUM_CONSTANT,
        property: "enum_type",
        offset: 0,
    },
    RawStringField {
        tag: TAG_ENUM_CONSTANT,
        property: "name",
        offset: 4,
    },
    RawStringField {
        tag: TAG_LOCAL_VARIABLE,
        property: "name",
        offset: 0,
    },
    RawStringField {
        tag: TAG_LOCAL_VARIABLE,
        property: "type",
        offset: 4,
    },
    RawStringField {
        tag: TAG_FIELD_NODE,
        property: "class",
        offset: 0,
    },
    RawStringField {
        tag: TAG_FIELD_NODE,
        property: "name",
        offset: 4,
    },
    RawStringField {
        tag: TAG_FIELD_NODE,
        property: "type",
        offset: 8,
    },
    RawStringField {
        tag: TAG_PARAMETER_NODE,
        property: "type",
        offset: 4,
    },
    RawStringField {
        tag: TAG_RESOURCE_FILE_NODE,
        property: "path",
        offset: 0,
    },
    RawStringField {
        tag: TAG_RESOURCE_FILE_NODE,
        property: "source",
        offset: 4,
    },
    RawStringField {
        tag: TAG_RESOURCE_FILE_NODE,
        property: "format",
        offset: 8,
    },
    RawStringField {
        tag: TAG_RESOURCE_VALUE_NODE,
        property: "path",
        offset: 0,
    },
    RawStringField {
        tag: TAG_RESOURCE_VALUE_NODE,
        property: "key",
        offset: 4,
    },
    RawStringField {
        tag: TAG_ANNOTATION_NODE,
        property: "name",
        offset: 0,
    },
    RawStringField {
        tag: TAG_ANNOTATION_NODE,
        property: "class",
        offset: 4,
    },
    RawStringField {
        tag: TAG_ANNOTATION_NODE,
        property: "member",
        offset: 8,
    },
];

/// The slot of `(tag, property)` in [`RAW_STRING_FIELDS`], if it is a raw string field.
pub fn raw_string_field(tag: u8, property: &str) -> Option<usize> {
    RAW_STRING_FIELDS
        .iter()
        .position(|f| f.tag == tag && f.property == property)
}

/// A distinct-strings trigram index is not built past this many postings; the column
/// then resolves a term by scanning its distinct strings, which is still only the
/// strings this property uses, never the whole dictionary.
const MAX_TRIGRAM_POSTINGS: usize = 4_000_000;
/// Resolved terms remembered per column, as the Kotlin column index remembers them.
const MAX_MATCH_CACHE_ENTRIES: usize = 32;
const MAX_MATCH_CACHE_IDS: usize = 512 * 1024;

/// One type's records projected to one string property: node ids ascending, the string
/// id each carries, and the distinct string ids sorted.
pub struct StringColumn {
    node_ids: Vec<u32>,
    string_ids: Vec<u32>,
    unique: Vec<u32>,
    trigrams: OnceLock<Option<HashMap<i32, Vec<u32>>>>,
    /// `(op, transform, literal)` to the sorted string ids that matched, bounded.
    cache: Mutex<MatchCache>,
}

struct MatchCache {
    entries: Vec<((u8, u8, String), std::sync::Arc<Vec<u32>>)>,
    ids: usize,
}

impl StringColumn {
    /// Build from the records of `tag`, reading the string id at `offset` in each.
    pub fn build(
        data: &[u8],
        ids: &[u32],
        node_offset: &dyn Fn(u32) -> Option<usize>,
        field_offset: usize,
    ) -> StringColumn {
        let mut node_ids: Vec<u32> = ids.to_vec();
        node_ids.sort_unstable();
        let mut string_ids = Vec::with_capacity(node_ids.len());
        node_ids.retain(|&id| match node_offset(id) {
            Some(off) => {
                string_ids.push(read_i32_at(data, off + NODE_HEADER_BYTES + field_offset) as u32);
                true
            }
            None => false,
        });
        let mut unique = string_ids.clone();
        unique.sort_unstable();
        unique.dedup();
        StringColumn {
            node_ids,
            string_ids,
            unique,
            trigrams: OnceLock::new(),
            cache: Mutex::new(MatchCache {
                entries: Vec::new(),
                ids: 0,
            }),
        }
    }

    pub fn len(&self) -> usize {
        self.node_ids.len()
    }

    pub fn is_empty(&self) -> bool {
        self.node_ids.is_empty()
    }

    /// Node ids, ascending.
    pub fn node_ids(&self) -> &[u32] {
        &self.node_ids
    }

    /// The string id of each node in [`Self::node_ids`] order.
    pub fn string_ids(&self) -> &[u32] {
        &self.string_ids
    }

    /// The distinct string ids this column uses, ascending.
    pub fn unique(&self) -> &[u32] {
        &self.unique
    }

    /// Distinct string ids among `unique` whose lowercase form holds every one of the
    /// given trigrams, or `None` when the column has no trigram index. A superset of
    /// the strings containing the term, to be verified by the caller.
    pub fn trigram_candidates(&self, strings: &StringTable, trigrams: &[i32]) -> Option<Vec<u32>> {
        let index = self.trigram_index(strings).as_ref()?;
        let mut lists: Vec<&Vec<u32>> = Vec::with_capacity(trigrams.len());
        for t in trigrams {
            lists.push(index.get(t)?);
        }
        lists.sort_by_key(|l| l.len());
        let mut candidates: Vec<u32> = lists[0].clone();
        for list in &lists[1..] {
            if candidates.len() <= 32 {
                break;
            }
            candidates.retain(|c| list.binary_search(c).is_ok());
        }
        Some(candidates)
    }

    fn trigram_index(&self, strings: &StringTable) -> &Option<HashMap<i32, Vec<u32>>> {
        self.trigrams.get_or_init(|| {
            let mut index: HashMap<i32, Vec<u32>> = HashMap::new();
            let mut postings = 0usize;
            let mut seen: Vec<i32> = Vec::new();
            for &sid in &self.unique {
                let lowered: String = strings
                    .get(sid as usize)
                    .chars()
                    .flat_map(|c| c.to_lowercase())
                    .collect();
                let units: Vec<u16> = lowered.encode_utf16().collect();
                if units.len() < crate::callsite_index::MIN_TRIGRAM_LENGTH {
                    continue;
                }
                seen.clear();
                for position in 0..=units.len() - crate::callsite_index::MIN_TRIGRAM_LENGTH {
                    let hash = crate::callsite_index::trigram_hash_units(&units, position);
                    if seen.contains(&hash) {
                        continue;
                    }
                    seen.push(hash);
                    index.entry(hash).or_default().push(sid);
                    postings += 1;
                    if postings > MAX_TRIGRAM_POSTINGS {
                        return None;
                    }
                }
            }
            // Distinct ids were visited ascending, so every posting list already is.
            Some(index)
        })
    }

    /// A remembered resolution of `(op, transform, literal)` on this column.
    pub fn cached(&self, key: &(u8, u8, String)) -> Option<std::sync::Arc<Vec<u32>>> {
        let cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        cache
            .entries
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.clone())
    }

    /// Remember a resolution; the oldest entries go when the bound is reached.
    pub fn remember(&self, key: (u8, u8, String), ids: std::sync::Arc<Vec<u32>>) {
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        if ids.len() > MAX_MATCH_CACHE_IDS {
            return;
        }
        cache.ids += ids.len();
        cache.entries.push((key, ids));
        while cache.entries.len() > MAX_MATCH_CACHE_ENTRIES || cache.ids > MAX_MATCH_CACHE_IDS {
            let (_, gone) = cache.entries.remove(0);
            cache.ids -= gone.len();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Records laid out like the writer's: 5-byte header, then the string id.
    fn records(string_ids: &[u32]) -> (Vec<u8>, Vec<u32>, Vec<usize>) {
        let mut data = Vec::new();
        let mut ids = Vec::new();
        let mut offsets = Vec::new();
        for (i, &sid) in string_ids.iter().enumerate() {
            offsets.push(data.len());
            data.extend_from_slice(&(i as u32).to_be_bytes());
            data.push(TAG_STRING_CONSTANT);
            data.extend_from_slice(&(sid as i32).to_be_bytes());
            ids.push(i as u32);
        }
        (data, ids, offsets)
    }

    #[test]
    fn column_projects_records_and_dedups_strings() {
        let (data, ids, offsets) = records(&[3, 1, 3, 2, 1]);
        let col = StringColumn::build(&data, &ids, &|id| offsets.get(id as usize).copied(), 0);
        assert_eq!(col.node_ids(), &[0, 1, 2, 3, 4]);
        assert_eq!(col.string_ids(), &[3, 1, 3, 2, 1]);
        assert_eq!(col.unique(), &[1, 2, 3]);
        assert_eq!(raw_string_field(TAG_STRING_CONSTANT, "value"), Some(0));
        assert_eq!(raw_string_field(TAG_STRING_CONSTANT, "name"), None);
        assert_eq!(raw_string_field(TAG_FIELD_NODE, "type"), Some(7));
    }

    #[test]
    fn match_cache_is_bounded_and_keyed() {
        let (data, ids, offsets) = records(&[0]);
        let col = StringColumn::build(&data, &ids, &|id| offsets.get(id as usize).copied(), 0);
        let key = (1u8, 0u8, "abc".to_string());
        assert!(col.cached(&key).is_none());
        col.remember(key.clone(), std::sync::Arc::new(vec![7]));
        assert_eq!(col.cached(&key).map(|v| v.to_vec()), Some(vec![7]));
        for i in 0..MAX_MATCH_CACHE_ENTRIES + 5 {
            col.remember((1, 0, format!("k{i}")), std::sync::Arc::new(vec![i as u32]));
        }
        // The first entry has aged out.
        assert!(col.cached(&key).is_none());
        assert!(col
            .cached(&(1, 0, format!("k{}", MAX_MATCH_CACHE_ENTRIES + 4)))
            .is_some());
    }
}

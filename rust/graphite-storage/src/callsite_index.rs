//! Reader for `graph.callsite-string-index`, the persisted CallSite string accelerator.
//!
//! The Kotlin server writes this file alongside the graph and uses it to answer the
//! broad "find anything mentioning X" queries that dominate Explorer latency. Two
//! structures live in it, and both are needed to beat a full scan:
//!
//! * **Four property CSRs** — for `caller_class`, `caller_name`, `callee_class` and
//!   `callee_name`, a sorted list of the string ids that property actually uses, and
//!   for each one the CallSite node ids carrying it. This turns "which nodes hold this
//!   string" into a binary search plus a slice, instead of a sweep over every record.
//!
//! * **A lowercase trigram index** — a 64-bit signature per string, plus postings
//!   mapping each trigram hash to the string ids containing it. This turns "which
//!   strings match this literal" into an intersection over a few short posting lists,
//!   instead of a pass over the whole dictionary.
//!
//! Everything is read straight out of the mapping: the file is big-endian, exactly as
//! `java.io.DataOutput` wrote it, so accessors byte-swap on read and nothing is copied
//! at load time.

use crate::io::{read_i32_at, read_i64_at};
use memmap2::Mmap;
use std::path::Path;

pub const MAGIC: i32 = 0x4752_4353;
pub const VERSION: i32 = 2;
pub const CONTENT_IDENTITY_BYTES: usize = 32;
pub const PROPERTY_COUNT: usize = 4;
/// Literals shorter than this have no trigram and must fall back to a dictionary pass.
pub const MIN_TRIGRAM_LENGTH: usize = 3;

const STRING_HASH_FACTOR: i32 = 31;
const SIGNATURE_MASK: i32 = 63;
const HEADER_BYTES: usize = 4 * 4 + CONTENT_IDENTITY_BYTES + PROPERTY_COUNT * 4 + 4 + 8;

#[derive(Debug, thiserror::Error)]
pub enum IndexError {
    #[error("{0}: {1}")]
    Io(String, #[source] std::io::Error),
    #[error("not a CallSite string index: {0}")]
    BadHeader(String),
    #[error("CallSite string index does not describe this graph: {0}")]
    Mismatch(String),
}

/// A big-endian `i32` array inside the mapping.
#[derive(Clone, Copy)]
struct I32Array {
    start: usize,
    len: usize,
}

impl I32Array {
    #[inline]
    fn get(&self, data: &[u8], i: usize) -> i32 {
        read_i32_at(data, self.start + i * 4)
    }
}

/// One property's CSR: `used_string_ids` ascending, `posting_ends` its exclusive ends
/// into `posting_node_ids`.
#[derive(Clone, Copy)]
struct PropertyCsr {
    used_string_ids: I32Array,
    posting_ends: I32Array,
    posting_node_ids: I32Array,
}

/// Which trigrams a graph holds, and where each one's postings lie.
struct TrigramTable {
    /// The 16-bit-folded presence bitmap: a cheap first answer for a miss.
    bits: Vec<u64>,
    /// Trigram to `[start, end)` in the posting array.
    runs: std::collections::HashMap<i32, (u32, u32)>,
}

/// Bits in the per-graph trigram filter. A trigram hash is folded to this many bits, so
/// collisions only ever cause a graph to be examined that need not be.
const TRIGRAM_FILTER_BITS: usize = 1 << 16;

pub struct CallSiteStringIndex {
    map: Mmap,
    /// Which trigrams occur anywhere in this graph, built on first use.
    trigram_filter: std::sync::OnceLock<TrigramTable>,
    string_count: usize,
    call_site_count: usize,
    properties: [PropertyCsr; PROPERTY_COUNT],
    signatures: usize,
    trigram_postings: usize,
    trigram_posting_count: usize,
}

impl CallSiteStringIndex {
    /// Load the index for a graph directory, or `None` when the file is absent.
    ///
    /// A file that does not match the graph is a hard error rather than a silent
    /// fallback: it means the directory is inconsistent, and answering from a stale
    /// index would produce wrong results.
    pub fn load(
        dir: &Path,
        string_count: usize,
        content_identity: Option<&[u8; CONTENT_IDENTITY_BYTES]>,
    ) -> Result<Option<CallSiteStringIndex>, IndexError> {
        let path = dir.join("graph.callsite-string-index");
        if !path.exists() {
            return Ok(None);
        }
        let file = std::fs::File::open(&path)
            .map_err(|e| IndexError::Io(path.display().to_string(), e))?;
        // SAFETY: read-only mapping of a file we do not modify.
        let map = unsafe { Mmap::map(&file) }
            .map_err(|e| IndexError::Io(path.display().to_string(), e))?;
        let name = || path.display().to_string();
        if map.len() < HEADER_BYTES {
            return Err(IndexError::BadHeader(name()));
        }
        if read_i32_at(&map, 0) != MAGIC || read_i32_at(&map, 4) != VERSION {
            return Err(IndexError::BadHeader(name()));
        }
        let strings = read_i32_at(&map, 8);
        let call_sites = read_i32_at(&map, 12);
        if strings < 0 || call_sites < 0 {
            return Err(IndexError::BadHeader(name()));
        }
        let (strings, call_sites) = (strings as usize, call_sites as usize);
        if strings != string_count {
            return Err(IndexError::Mismatch(name()));
        }
        if let Some(expected) = content_identity {
            if &map[16..16 + CONTENT_IDENTITY_BYTES] != expected.as_slice() {
                return Err(IndexError::Mismatch(name()));
            }
        }
        let mut off = 16 + CONTENT_IDENTITY_BYTES;
        let mut unique = [0usize; PROPERTY_COUNT];
        for slot in unique.iter_mut() {
            let n = read_i32_at(&map, off);
            if n < 0 || n as usize > strings {
                return Err(IndexError::BadHeader(name()));
            }
            *slot = n as usize;
            off += 4;
        }
        let trigram_posting_count = read_i32_at(&map, off);
        if trigram_posting_count < 0 {
            return Err(IndexError::BadHeader(name()));
        }
        let trigram_posting_count = trigram_posting_count as usize;
        off += 4 + 8; // trigram posting count, then the retained-bytes estimate

        let mut cursor = off;
        let mut take_i32 = |len: usize| {
            let a = I32Array { start: cursor, len };
            cursor += len * 4;
            a
        };
        let properties: [PropertyCsr; PROPERTY_COUNT] = std::array::from_fn(|i| PropertyCsr {
            used_string_ids: take_i32(unique[i]),
            posting_ends: take_i32(unique[i]),
            posting_node_ids: take_i32(call_sites),
        });
        let signatures = cursor;
        cursor += strings * 8;
        let trigram_postings = cursor;
        cursor += trigram_posting_count * 8;
        // The trailing CRC-64 is not verified: it would mean reading the whole file at
        // load, and every access below is bounds-checked against the header anyway.
        if cursor + 8 != map.len() {
            return Err(IndexError::BadHeader(name()));
        }
        Ok(Some(CallSiteStringIndex {
            map,
            trigram_filter: std::sync::OnceLock::new(),
            string_count: strings,
            call_site_count: call_sites,
            properties,
            signatures,
            trigram_postings,
            trigram_posting_count,
        }))
    }

    pub fn string_count(&self) -> usize {
        self.string_count
    }

    pub fn call_site_count(&self) -> usize {
        self.call_site_count
    }

    /// The 64-bit lowercase-trigram signature of a string, or 0 when it is used by no
    /// CallSite property (and so can never be a match).
    #[inline]
    pub fn signature(&self, string_id: usize) -> u64 {
        if string_id >= self.string_count {
            return 0;
        }
        read_i64_at(&self.map, self.signatures + string_id * 8) as u64
    }

    /// Whether every one of these trigrams occurs somewhere in this graph.
    ///
    /// A string containing a term contains *all* of the term's trigrams, so one trigram
    /// missing from the whole graph means no string in it can match — and the graph can
    /// be skipped without a single posting lookup. That is the common case across many
    /// graphs, where a term names something only a few of them know about.
    ///
    /// The filter over-approximates: hashes are folded to 16 bits, so a collision costs
    /// a wasted probe and never a missed match.
    pub fn may_contain_all(&self, trigrams: &[i32]) -> bool {
        let table = self.trigram_table();
        trigrams.iter().all(|t| {
            let bit = (*t as u32 as u16) as usize;
            // The bitmap answers most misses from two cache lines; the table settles
            // the collisions exactly, so a graph is never planned for a trigram it
            // does not hold.
            table.bits[bit >> 6] >> (bit & 63) & 1 == 1 && table.runs.contains_key(t)
        })
    }

    /// Built on first use from one pass over the trigram postings, which are sorted by
    /// trigram: each distinct trigram is one contiguous run, recorded once.
    fn trigram_table(&self) -> &TrigramTable {
        self.trigram_filter.get_or_init(|| {
            let mut bits = vec![0u64; TRIGRAM_FILTER_BITS / 64];
            let mut runs: std::collections::HashMap<i32, (u32, u32)> =
                std::collections::HashMap::new();
            let mut previous: Option<i32> = None;
            let mut start = 0usize;
            for i in 0..self.trigram_posting_count {
                let raw = read_i64_at(&self.map, self.trigram_postings + i * 8);
                let trigram = (raw >> 32) as i32;
                if previous == Some(trigram) {
                    continue;
                }
                if let Some(p) = previous {
                    runs.insert(p, (start as u32, i as u32));
                }
                previous = Some(trigram);
                start = i;
                let bit = (trigram as u32 as u16) as usize;
                bits[bit >> 6] |= 1u64 << (bit & 63);
            }
            if let Some(p) = previous {
                runs.insert(p, (start as u32, self.trigram_posting_count as u32));
            }
            TrigramTable { bits, runs }
        })
    }

    /// String ids whose lowercase form contains the given trigram hash.
    ///
    /// Postings are `(trigram << 32) | stringId` sorted ascending, so one trigram's
    /// ids are a contiguous run found by binary search — and are themselves ascending.
    pub fn trigram_string_ids(&self, trigram: i32) -> TrigramPostings<'_> {
        // One hash lookup. This was two binary searches over the whole posting array
        // -- millions of entries, so forty-odd dependent cache misses -- and a broad
        // query sizes every trigram of every literal on every graph it plans, which
        // for a six-literal conjunction over sixty-four graphs was most of the plan.
        let (start, end) = self
            .trigram_table()
            .runs
            .get(&trigram)
            .map(|&(s, e)| (s as usize, e as usize))
            .unwrap_or((0, 0));
        TrigramPostings {
            index: self,
            start,
            end,
        }
    }

    /// Where a trigram's run would begin, by binary search; kept for the tests, which
    /// check the table against it.
    #[cfg(test)]
    fn trigram_run_by_search(&self, trigram: i32) -> (usize, usize) {
        // String ids are non-negative, so a trigram's run spans `[key(t, 0), key(t+1, 0))`.
        (
            self.posting_lower_bound(key(trigram, 0)),
            self.posting_lower_bound(key(trigram.wrapping_add(1), 0)),
        )
    }

    #[cfg(test)]
    fn posting_lower_bound(&self, target: i64) -> usize {
        let (mut lo, mut hi) = (0usize, self.trigram_posting_count);
        while lo < hi {
            let mid = (lo + hi) / 2;
            if read_i64_at(&self.map, self.trigram_postings + mid * 8) < target {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        lo
    }

    /// CallSite node ids that carry `string_id` in the given property, ascending.
    pub fn postings(&self, property: usize, string_id: usize) -> Option<NodePostings<'_>> {
        let csr = self.properties.get(property)?;
        let row = binary_search_i32(&self.map, &csr.used_string_ids, string_id as i32)?;
        let start = if row == 0 {
            0
        } else {
            csr.posting_ends.get(&self.map, row - 1) as usize
        };
        let end = csr.posting_ends.get(&self.map, row) as usize;
        Some(NodePostings {
            index: self,
            array: csr.posting_node_ids,
            pos: start,
            end,
        })
    }

    /// The posting range of each of several ascending string ids in one property.
    ///
    /// Found by walking the property's used-id list once, galloping from the previous
    /// hit: both sides are ascending. A plan used to look each id up by binary search
    /// twice -- once to size the postings and again to read them -- and for a dense
    /// term that was several hundred searches into a mapped array per graph before
    /// the first row. An id the property never uses gets the empty range.
    pub fn posting_ranges(&self, property: usize, string_ids: &[u32]) -> Vec<(u32, u32)> {
        let Some(csr) = self.properties.get(property) else {
            return vec![(0, 0); string_ids.len()];
        };
        let n = csr.used_string_ids.len;
        let used = |i: usize| csr.used_string_ids.get(&self.map, i);
        let mut out = Vec::with_capacity(string_ids.len());
        let mut pos = 0usize;
        for &sid in string_ids {
            let target = sid as i32;
            let mut step = 1usize;
            let mut lo = pos;
            while lo < n && used(lo) < target {
                pos = lo;
                lo += step;
                step *= 2;
            }
            let mut hi = lo.min(n);
            lo = pos;
            while lo < hi {
                let mid = (lo + hi) / 2;
                if used(mid) < target {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            pos = lo;
            if lo < n && used(lo) == target {
                let start = if lo == 0 {
                    0
                } else {
                    csr.posting_ends.get(&self.map, lo - 1) as u32
                };
                out.push((start, csr.posting_ends.get(&self.map, lo) as u32));
            } else {
                out.push((0, 0));
            }
        }
        out
    }

    /// The postings in one property between two offsets from `posting_ranges`.
    pub fn postings_in(&self, property: usize, start: u32, end: u32) -> NodePostings<'_> {
        let array = self.properties[property].posting_node_ids;
        NodePostings {
            index: self,
            array,
            pos: start as usize,
            end: end as usize,
        }
    }

    /// How many CallSite nodes carry `string_id` in the given property.
    pub fn posting_len(&self, property: usize, string_id: usize) -> usize {
        match self.postings(property, string_id) {
            Some(p) => p.len(),
            None => 0,
        }
    }
}

/// Ascending string ids for one trigram.
pub struct TrigramPostings<'a> {
    index: &'a CallSiteStringIndex,
    start: usize,
    end: usize,
}

impl TrigramPostings<'_> {
    pub fn len(&self) -> usize {
        self.end - self.start
    }
    pub fn is_empty(&self) -> bool {
        self.start == self.end
    }
    #[inline]
    pub fn get(&self, i: usize) -> u32 {
        let raw = read_i64_at(
            &self.index.map,
            self.index.trigram_postings + (self.start + i) * 8,
        );
        raw as u32
    }
    pub fn iter(&self) -> impl Iterator<Item = u32> + '_ {
        (0..self.len()).map(|i| self.get(i))
    }
    /// Keep only the ids of `candidates` that this run also holds. Both are ascending,
    /// so the run is walked once, galloping forward from the last match: each candidate
    /// costs a few reads near where the previous one landed, instead of a full binary
    /// search from the top -- a dozen dependent cache misses into a mapped array, for
    /// every one of hundreds of candidates, on every graph a term is planned on.
    pub fn intersect_into(&self, candidates: &mut Vec<u32>) {
        let n = self.len();
        let mut pos = 0usize;
        candidates.retain(|&id| {
            // Gallop past everything below `id`, then binary search the last stride.
            let mut step = 1usize;
            let mut lo = pos;
            while lo < n && self.get(lo) < id {
                pos = lo;
                lo += step;
                step *= 2;
            }
            let mut hi = lo.min(n);
            lo = pos;
            while lo < hi {
                let mid = (lo + hi) / 2;
                if self.get(mid) < id {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            pos = lo;
            lo < n && self.get(lo) == id
        });
    }

    /// Postings for one trigram are ascending, so membership is a binary search.
    pub fn contains(&self, id: u32) -> bool {
        let (mut lo, mut hi) = (0usize, self.len());
        while lo < hi {
            let mid = (lo + hi) / 2;
            match self.get(mid).cmp(&id) {
                std::cmp::Ordering::Less => lo = mid + 1,
                std::cmp::Ordering::Greater => hi = mid,
                std::cmp::Ordering::Equal => return true,
            }
        }
        false
    }
}

/// Ascending CallSite node ids for one (property, string) pair.
pub struct NodePostings<'a> {
    index: &'a CallSiteStringIndex,
    array: I32Array,
    pos: usize,
    end: usize,
}

impl NodePostings<'_> {
    pub fn len(&self) -> usize {
        self.end - self.pos
    }
    pub fn is_empty(&self) -> bool {
        self.pos == self.end
    }
}

impl Iterator for NodePostings<'_> {
    type Item = u32;
    #[inline]
    fn next(&mut self) -> Option<u32> {
        if self.pos >= self.end {
            return None;
        }
        let v = self.array.get(&self.index.map, self.pos) as u32;
        self.pos += 1;
        Some(v)
    }
}

fn binary_search_i32(data: &[u8], array: &I32Array, needle: i32) -> Option<usize> {
    let (mut lo, mut hi) = (0usize, array.len);
    while lo < hi {
        let mid = (lo + hi) / 2;
        match array.get(data, mid).cmp(&needle) {
            std::cmp::Ordering::Less => lo = mid + 1,
            std::cmp::Ordering::Greater => hi = mid,
            std::cmp::Ordering::Equal => return Some(mid),
        }
    }
    None
}

#[inline]
/// Only the tests build keys now: the reader looks runs up in the trigram table.
#[cfg(test)]
fn key(trigram: i32, string_id: i32) -> i64 {
    ((trigram as i64) << 32) | (string_id as i64 & 0xFFFF_FFFF)
}

/// Trigram hash at `position`, over UTF-16 code units, matching the writer exactly.
#[inline]
fn trigram_hash(units: &[u16], position: usize) -> i32 {
    ((units[position] as i32)
        .wrapping_mul(STRING_HASH_FACTOR)
        .wrapping_add(units[position + 1] as i32))
    .wrapping_mul(STRING_HASH_FACTOR)
    .wrapping_add(units[position + 2] as i32)
}

/// The distinct trigram hashes of a literal, lowercased the way the writer lowercases.
///
/// The writer indexes `stringTable.get(id).lowercase()`, so a literal must be
/// lowercased the same way before its trigrams can be looked up. That makes the
/// resulting string ids a *superset* filter for both case-sensitive and
/// case-insensitive predicates, since `s.contains(t)` implies
/// `lower(s).contains(lower(t))`. Callers re-check the real predicate.
pub fn literal_trigrams(literal: &str) -> Option<Vec<i32>> {
    let lowered: String = literal.chars().flat_map(|c| c.to_lowercase()).collect();
    let units: Vec<u16> = lowered.encode_utf16().collect();
    if units.len() < MIN_TRIGRAM_LENGTH {
        return None;
    }
    let mut out = Vec::with_capacity(units.len() - 2);
    for position in 0..=units.len() - MIN_TRIGRAM_LENGTH {
        out.push(trigram_hash(&units, position));
    }
    out.sort_unstable();
    out.dedup();
    Some(out)
}

/// The 64-bit signature of a literal, for the cheap "cannot match" bloom test.
pub fn literal_signature(literal: &str) -> u64 {
    let lowered: String = literal.chars().flat_map(|c| c.to_lowercase()).collect();
    let units: Vec<u16> = lowered.encode_utf16().collect();
    if units.len() < MIN_TRIGRAM_LENGTH {
        return 0;
    }
    let mut signature = 0u64;
    for position in 0..=units.len() - MIN_TRIGRAM_LENGTH {
        let hash = trigram_hash(&units, position);
        let mixed = hash ^ ((hash as u32) >> 11) as i32 ^ (hash << 7);
        signature |= 1u64 << (hash & SIGNATURE_MASK);
        signature |= 1u64 << (mixed & SIGNATURE_MASK);
    }
    signature
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    /// The `DataOutput` layout `MappedCallSiteStringIndex.writePersistent` produces.
    struct Builder {
        strings: usize,
        call_sites: usize,
        identity: [u8; CONTENT_IDENTITY_BYTES],
        /// Per property: `(string_id, node_ids)` in ascending string-id order.
        properties: [Vec<(i32, Vec<i32>)>; PROPERTY_COUNT],
        signatures: Vec<i64>,
        postings: Vec<i64>,
    }

    impl Builder {
        fn new(strings: usize, call_sites: usize) -> Builder {
            Builder {
                strings,
                call_sites,
                identity: [7u8; CONTENT_IDENTITY_BYTES],
                properties: Default::default(),
                signatures: vec![0; strings],
                postings: Vec::new(),
            }
        }

        fn write(&self, path: &Path) {
            let mut out: Vec<u8> = Vec::new();
            let i32be = |v: i32, out: &mut Vec<u8>| out.extend_from_slice(&v.to_be_bytes());
            i32be(MAGIC, &mut out);
            i32be(VERSION, &mut out);
            i32be(self.strings as i32, &mut out);
            i32be(self.call_sites as i32, &mut out);
            out.extend_from_slice(&self.identity);
            for p in &self.properties {
                i32be(p.len() as i32, &mut out);
            }
            i32be(self.postings.len() as i32, &mut out);
            out.extend_from_slice(&0i64.to_be_bytes()); // retained-bytes estimate
            for p in &self.properties {
                for (string_id, _) in p {
                    i32be(*string_id, &mut out);
                }
                let mut end = 0i32;
                for (_, nodes) in p {
                    end += nodes.len() as i32;
                    i32be(end, &mut out);
                }
                for (_, nodes) in p {
                    for n in nodes {
                        i32be(*n, &mut out);
                    }
                }
            }
            for s in &self.signatures {
                out.extend_from_slice(&s.to_be_bytes());
            }
            for p in &self.postings {
                out.extend_from_slice(&p.to_be_bytes());
            }
            out.extend_from_slice(&0i64.to_be_bytes()); // trailing checksum, not verified
            std::fs::File::create(path)
                .unwrap()
                .write_all(&out)
                .unwrap();
        }
    }

    /// Two strings, four properties, one trigram posting list — enough to exercise
    /// every accessor against a layout written exactly the way Kotlin writes it.
    fn fixture(dir: &Path) -> Builder {
        let mut b = Builder::new(3, 4);
        // caller_class: string 0 on nodes 0 and 2, string 1 on nodes 1 and 3.
        b.properties[0] = vec![(0, vec![0, 2]), (1, vec![1, 3])];
        b.properties[1] = vec![(2, vec![0, 1, 2, 3])];
        b.properties[2] = vec![(0, vec![0, 1, 2, 3])];
        b.properties[3] = vec![(1, vec![0, 1, 2, 3])];
        b.signatures = vec![0b1011, 0b0100, 0];
        // `(trigram << 32) | stringId`, ascending.
        b.postings = vec![key(10, 0), key(10, 2), key(11, 1)];
        b.write(&dir.join("graph.callsite-string-index"));
        b
    }

    fn tempdir(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("graphite-csidx-{name}"));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn absent_index_is_not_an_error() {
        let dir = tempdir("absent");
        assert!(CallSiteStringIndex::load(&dir, 3, None).unwrap().is_none());
    }

    #[test]
    fn reads_postings_and_trigrams() {
        let dir = tempdir("read");
        let b = fixture(&dir);
        let idx = CallSiteStringIndex::load(&dir, 3, Some(&b.identity))
            .unwrap()
            .expect("index");
        assert_eq!(idx.string_count(), 3);
        assert_eq!(idx.call_site_count(), 4);

        assert_eq!(idx.postings(0, 0).unwrap().collect::<Vec<_>>(), vec![0, 2]);
        assert_eq!(idx.postings(0, 1).unwrap().collect::<Vec<_>>(), vec![1, 3]);
        assert_eq!(idx.posting_len(0, 1), 2);
        assert_eq!(idx.posting_len(1, 2), 4);
        // A string the property never uses has no postings at all.
        assert!(idx.postings(0, 2).is_none());
        assert_eq!(idx.posting_len(0, 2), 0);
        // So does a property index that does not exist.
        assert!(idx.postings(PROPERTY_COUNT, 0).is_none());

        assert_eq!(idx.signature(0), 0b1011);
        assert_eq!(idx.signature(1), 0b0100);
        // Out of range reads as "matches nothing" rather than panicking.
        assert_eq!(idx.signature(99), 0);

        let t10 = idx.trigram_string_ids(10);
        assert_eq!(t10.len(), 2);
        assert!(!t10.is_empty());
        assert_eq!(t10.iter().collect::<Vec<_>>(), vec![0, 2]);
        assert!(t10.contains(0) && t10.contains(2));
        assert!(!t10.contains(1));
        assert_eq!(
            idx.trigram_string_ids(11).iter().collect::<Vec<_>>(),
            vec![1]
        );
        assert!(idx.trigram_string_ids(12).is_empty());
    }

    #[test]
    fn rejects_a_file_describing_another_graph() {
        let dir = tempdir("mismatch");
        let b = fixture(&dir);
        // A different dictionary size, and a different content identity, each on its own.
        assert!(matches!(
            CallSiteStringIndex::load(&dir, 4, None),
            Err(IndexError::Mismatch(_))
        ));
        let mut other = b.identity;
        other[0] ^= 0xFF;
        assert!(matches!(
            CallSiteStringIndex::load(&dir, 3, Some(&other)),
            Err(IndexError::Mismatch(_))
        ));
    }

    #[test]
    fn rejects_a_file_that_is_not_an_index() {
        let dir = tempdir("garbage");
        let path = dir.join("graph.callsite-string-index");
        std::fs::write(&path, b"not an index at all, but long enough to look like one\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0").unwrap();
        assert!(matches!(
            CallSiteStringIndex::load(&dir, 3, None),
            Err(IndexError::BadHeader(_))
        ));
        // Truncation is caught too: the arrays no longer reach the trailing checksum.
        let mut b = Builder::new(3, 4);
        b.properties[0] = vec![(0, vec![0, 1, 2, 3])];
        b.postings = vec![key(1, 0)];
        b.write(&path);
        let bytes = std::fs::read(&path).unwrap();
        std::fs::write(&path, &bytes[..bytes.len() - 4]).unwrap();
        assert!(matches!(
            CallSiteStringIndex::load(&dir, 3, None),
            Err(IndexError::BadHeader(_))
        ));
    }

    #[test]
    fn literal_trigrams_match_the_writers_hash() {
        // Hand-computed with the writer's own `(a * 31 + b) * 31 + c` over lowercase.
        let h = |a: char, b: char, c: char| ((a as i32) * 31 + b as i32) * 31 + c as i32;
        assert_eq!(literal_trigrams("abc"), Some(vec![h('a', 'b', 'c')]));
        // Case folds to the same trigrams the writer stored.
        assert_eq!(literal_trigrams("ABC"), literal_trigrams("abc"));
        let mut abcd = vec![h('a', 'b', 'c'), h('b', 'c', 'd')];
        abcd.sort_unstable();
        assert_eq!(literal_trigrams("abcd"), Some(abcd));
        // Repeats collapse: postings carry each trigram once per string.
        assert_eq!(literal_trigrams("aaaa"), Some(vec![h('a', 'a', 'a')]));
        // Shorter than a trigram: the index cannot answer.
        assert_eq!(literal_trigrams("ab"), None);
        assert_eq!(literal_trigrams(""), None);
    }

    #[test]
    fn literal_signature_sets_a_bit_per_trigram() {
        assert_eq!(literal_signature("ab"), 0);
        let one = literal_signature("abc");
        assert_ne!(one, 0);
        assert_eq!(
            one.count_ones() <= 2,
            true,
            "one trigram sets at most two bits"
        );
        assert_eq!(literal_signature("ABC"), one);
        // A longer literal's signature covers its prefix's bits.
        assert_eq!(literal_signature("abcd") & one, one);
    }
    #[test]
    fn posting_ranges_agree_with_single_lookups() {
        let dir = tempdir("ranges");
        let b = fixture(&dir);
        let idx = CallSiteStringIndex::load(&dir, 3, Some(&b.identity))
            .unwrap()
            .expect("index");
        for property in 0..PROPERTY_COUNT {
            let ids: Vec<u32> = vec![0, 1, 2, 7];
            let ranges = idx.posting_ranges(property, &ids);
            for (&sid, &(start, end)) in ids.iter().zip(&ranges) {
                let via_range: Vec<u32> = idx.postings_in(property, start, end).collect();
                let direct: Vec<u32> = idx
                    .postings(property, sid as usize)
                    .map(|p| p.collect())
                    .unwrap_or_default();
                assert_eq!(via_range, direct, "property {property} string {sid}");
            }
        }
        assert_eq!(idx.posting_ranges(PROPERTY_COUNT, &[0, 1]), vec![(0, 0), (0, 0)]);
    }

    #[test]
    fn galloping_intersection_agrees_with_membership() {
        let dir = tempdir("gallop");
        // Each property's postings must cover all four call sites, as the writer's do.
        let mut b = Builder::new(3, 4);
        b.properties[0] = vec![(0, vec![0, 1, 2, 3])];
        b.properties[1] = vec![(1, vec![0, 1, 2, 3])];
        b.properties[2] = vec![(2, vec![0, 1, 2, 3])];
        b.properties[3] = vec![(0, vec![0, 1, 2, 3])];
        b.signatures = vec![0, 0, 0];
        // One trigram over a run of ids with gaps, and an empty neighbour.
        let ids = [0u32, 1, 2, 5, 8, 9, 13, 21, 34, 55];
        b.postings = ids.iter().map(|&i| key(7, i as i32)).collect();
        b.write(&dir.join("graph.callsite-string-index"));
        let idx = CallSiteStringIndex::load(&dir, 3, Some(&b.identity))
            .unwrap()
            .expect("index");
        let run = idx.trigram_string_ids(7);
        for probe in [
            vec![],
            vec![0],
            vec![55],
            vec![56],
            vec![3, 4, 5, 6, 7, 8],
            (0..60).collect::<Vec<u32>>(),
            vec![0, 13, 34, 55, 89],
        ] {
            let mut got = probe.clone();
            run.intersect_into(&mut got);
            let want: Vec<u32> = probe.iter().copied().filter(|&i| run.contains(i)).collect();
            assert_eq!(got, want, "probe {probe:?}");
        }
        let mut none = vec![1, 2, 3];
        idx.trigram_string_ids(8).intersect_into(&mut none);
        assert!(none.is_empty());
    }

    #[test]
    fn trigram_table_matches_binary_search_for_every_trigram_and_for_absent_ones() {
        let dir = tempdir("table");
        let b = fixture(&dir);
        let idx = CallSiteStringIndex::load(&dir, 3, Some(&b.identity))
            .unwrap()
            .expect("index");
        let present: Vec<i32> = (0..idx.trigram_posting_count)
            .map(|i| (read_i64_at(&idx.map, idx.trigram_postings + i * 8) >> 32) as i32)
            .collect();
        for t in present.iter().copied().chain([i32::MIN, -1, 0, 1]) {
            let (lo, hi) = idx.trigram_run_by_search(t);
            let run = idx.trigram_string_ids(t);
            assert_eq!((run.start, run.end), (lo, hi), "trigram {t}");
            assert_eq!(idx.may_contain_all(&[t]), lo < hi, "presence of {t}");
        }
        // The search cannot bound `i32::MAX`: its upper key wraps to the smallest key of
        // all. The table has no such edge, and is what the reader now uses.
        assert!(idx.trigram_string_ids(i32::MAX).is_empty());
        assert!(!idx.may_contain_all(&[i32::MAX]));
    }

}

//! `graph.strings`: a `FrontCodedStringList` persisted through Java serialization.
//! Decoded eagerly into a compact arena for zero-copy lookups.

use crate::javaser::{read_object, JavaSerError, Value};
use crate::source::GraphSource;
use sha2::{Digest, Sha256};
use std::sync::Arc;

const SEARCH_CHUNK_BYTES: usize = 1024 * 1024;

#[derive(Debug, thiserror::Error)]
pub enum StringTableError {
    #[error("io error on {0}: {1}")]
    Io(String, std::io::Error),
    #[error("java serialization: {0}")]
    Ser(#[from] JavaSerError),
    #[error("unexpected string table layout: {0}")]
    Layout(String),
}

#[derive(Clone, Debug)]
pub struct StringTable {
    backing: Arc<StringBacking>,
    identity: Option<[u8; 32]>,
    /// SHA-256 of the serialized bytes this table was decoded from, bound at decode or by
    /// [`Self::bind_serialized`] when a GTY03–GTY05 table is first read.
    serialized_digest: std::sync::OnceLock<[u8; 32]>,
}

#[derive(Debug)]
struct StringBacking {
    arena: Vec<u8>,
    /// `offsets[i]..offsets[i+1]` is string i (UTF-8).
    offsets: Vec<u32>,
    /// IDs whose text the decode could only render lossily (an unpaired UTF-16 surrogate, or
    /// invalid UTF-8 in a table decoded without verification). Recorded on every decode, so a
    /// table the graph loaded for ordinary reads and bound to its bytes later refuses them too.
    invalid_text: Vec<usize>,
    /// Whether the dictionary is strictly ascending in Java order, decided once on first
    /// use: the writer always sorts and deduplicates, and a reader may then equate IDs
    /// with texts and binary-search by text.
    canonical: std::sync::OnceLock<bool>,
}

impl StringTable {
    pub fn load(src: &GraphSource) -> Result<Self, StringTableError> {
        Self::load_mode(src, false)
    }

    /// Bind GTY03–GTY05 to the exact serialized bytes used by this decode, not a sidecar.
    pub fn load_for_declared_types(src: &GraphSource) -> Result<Self, StringTableError> {
        Self::load_mode(src, true)
    }

    fn load_mode(src: &GraphSource, verified: bool) -> Result<Self, StringTableError> {
        let data = src
            .require("graph.strings")
            .map_err(|(path, e)| StringTableError::Io(path, e))?;
        let mut table = Self::decode_serialized(&data, verified)?;
        if let Ok(Some(bytes)) = src.bytes("graph.strings.identity") {
            if bytes.len() == 32 {
                table.identity = Some(bytes[..].try_into().unwrap());
            }
        }
        Ok(table)
    }

    pub fn from_serialized(data: &[u8]) -> Result<Self, StringTableError> {
        Self::decode_serialized(data, false)
    }

    pub fn from_serialized_for_declared_types(data: &[u8]) -> Result<Self, StringTableError> {
        Self::decode_serialized(data, true)
    }

    fn decode_serialized(data: &[u8], verified: bool) -> Result<Self, StringTableError> {
        let root = read_object(data)?;
        let utf8 = matches!(root.field("utf8"), Some(Value::Bool(true)));
        let inner = if utf8 {
            root.field("byteFrontCodedList")
        } else {
            root.field("charFrontCodedList")
                .or_else(|| root.field("list"))
        }
        .ok_or_else(|| StringTableError::Layout("missing front-coded list field".into()))?;
        let n = match inner.field("n") {
            Some(Value::Int(n)) => *n as usize,
            _ => return Err(StringTableError::Layout("missing n".into())),
        };
        let ratio = match inner.field("ratio") {
            Some(Value::Int(r)) => *r as usize,
            _ => return Err(StringTableError::Layout("missing ratio".into())),
        };
        let mut arena = Vec::new();
        let mut invalid_text = Vec::new();
        let mut offsets = Vec::with_capacity(n + 1);
        offsets.push(0u32);
        if utf8 {
            let mut segs: Vec<&[u8]> = Vec::new();
            match inner.field("array") {
                Some(Value::ObjArray(items)) => {
                    for it in items {
                        match it {
                            Value::ByteArray(b) => segs.push(b),
                            _ => return Err(StringTableError::Layout("bad byte segment".into())),
                        }
                    }
                }
                Some(Value::ByteArray(b)) => segs.push(b),
                _ => return Err(StringTableError::Layout("missing array".into())),
            }
            let flat: Vec<u8> = segs.concat();
            let mut invalid_utf8 = false;
            decode_front_coded(
                &flat,
                n,
                ratio,
                |v| v as usize,
                |_| 1,
                &mut |bytes: &[u8]| {
                    match std::str::from_utf8(bytes) {
                        Ok(text) => arena.extend_from_slice(text.as_bytes()),
                        Err(_) => {
                            // A verified table refuses invalid UTF-8 outright; an ordinary load
                            // keeps a lossy rendering and marks the ID, never unchecked bytes.
                            invalid_utf8 = true;
                            invalid_text.push(offsets.len() - 1);
                            arena.extend_from_slice(String::from_utf8_lossy(bytes).as_bytes());
                        }
                    }
                    offsets.push(arena.len() as u32);
                },
            );
            if verified && invalid_utf8 {
                return Err(StringTableError::Layout(
                    "invalid UTF-8 in shared string table".into(),
                ));
            }
        } else {
            let mut segs: Vec<&[u16]> = Vec::new();
            match inner.field("array") {
                Some(Value::ObjArray(items)) => {
                    for it in items {
                        match it {
                            Value::CharArray(c) => segs.push(c),
                            _ => return Err(StringTableError::Layout("bad char segment".into())),
                        }
                    }
                }
                Some(Value::CharArray(c)) => segs.push(c),
                _ => return Err(StringTableError::Layout("missing array".into())),
            }
            let flat: Vec<u16> = segs.concat();
            let mut buf: Vec<u16> = Vec::new();
            let mut prev: Vec<u16> = Vec::new();
            // Front coding over UTF-16 units.
            let mut pos = 0usize;
            let read_int = |a: &[u16], pos: &mut usize| -> usize {
                let c0 = a[*pos];
                if c0 < 0x8000 {
                    *pos += 1;
                    c0 as usize
                } else {
                    let v = (((c0 & 0x7FFF) as usize) << 16) | a[*pos + 1] as usize;
                    *pos += 2;
                    v
                }
            };
            for i in 0..n {
                if i % ratio == 0 {
                    let len = read_int(&flat, &mut pos);
                    buf.clear();
                    buf.extend_from_slice(&flat[pos..pos + len]);
                    pos += len;
                } else {
                    // Non-leader: suffix length first, then common prefix length.
                    let len = read_int(&flat, &mut pos);
                    let common = read_int(&flat, &mut pos);
                    buf.clear();
                    buf.extend_from_slice(&prev[..common]);
                    buf.extend_from_slice(&flat[pos..pos + len]);
                    pos += len;
                }
                if char::decode_utf16(buf.iter().copied()).any(|unit| unit.is_err()) {
                    invalid_text.push(i);
                }
                let s = String::from_utf16_lossy(&buf);
                arena.extend_from_slice(s.as_bytes());
                offsets.push(arena.len() as u32);
                std::mem::swap(&mut prev, &mut buf);
            }
        }
        arena.shrink_to_fit();
        Ok(StringTable {
            backing: Arc::new(StringBacking {
                arena,
                offsets,
                invalid_text,
                canonical: std::sync::OnceLock::new(),
            }),
            identity: None,
            serialized_digest: {
                let digest = std::sync::OnceLock::new();
                if verified {
                    let _ = digest.set(Sha256::digest(data).into());
                }
                digest
            },
        })
    }

    #[inline]
    pub fn len(&self) -> usize {
        self.backing.offsets.len() - 1
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    #[inline]
    pub fn get(&self, i: usize) -> &str {
        let s = self.backing.offsets[i] as usize;
        let e = self.backing.offsets[i + 1] as usize;
        // SAFETY: arena is built from valid UTF-8 strings at these boundaries.
        unsafe { std::str::from_utf8_unchecked(&self.backing.arena[s..e]) }
    }

    /// Available only when the same serialized input was fully hashed and decoded.
    pub fn serialized_digest(&self) -> Option<&[u8; 32]> {
        self.serialized_digest.get()
    }

    /// Binds this table to `data`, the serialized bytes it was decoded from, for a table
    /// whose declared types are read after the load; a digest already bound stays.
    pub fn bind_serialized(&self, data: &[u8]) {
        let _ = self.serialized_digest.set(Sha256::digest(data).into());
    }

    pub fn strict_get(&self, i: usize) -> Result<&str, StringTableError> {
        if self.serialized_digest.get().is_none() {
            return Err(StringTableError::Layout(
                "unverified shared string table".into(),
            ));
        }
        if i >= self.len() || self.backing.invalid_text.binary_search(&i).is_ok() {
            return Err(StringTableError::Layout(
                "invalid shared string ID or lossy UTF-16/UTF-8 text".into(),
            ));
        }
        // Lossy IDs were marked when the table was decoded, verified or not; a table bound to
        // its bytes after an ordinary load rejects the same referenced IDs a verified load does.
        Ok(self.get(i))
    }

    pub fn identity(&self) -> Option<&[u8; 32]> {
        self.identity.as_ref()
    }

    /// Conservative substring presence in the complete dictionary, without
    /// allocating or visiting individual strings. Matches crossing string
    /// boundaries are allowed: only `false` proves absence from every string.
    ///
    /// Check cancellation between bounded windows, including before and after
    /// a successful match. Windows overlap by `needle.len() - 1`, so a match
    /// spanning a window boundary cannot be missed. Very long needles increase
    /// the window size proportionally, but never cause an arena-sized copy.
    pub fn try_any_contains<E>(
        &self,
        needle: &str,
        mut check: impl FnMut() -> Result<(), E>,
    ) -> Result<bool, E> {
        check()?;
        let found = if needle.is_empty() {
            !self.is_empty()
        } else if needle.len() > self.backing.arena.len() {
            false
        } else {
            let finder = memchr::memmem::Finder::new(needle.as_bytes());
            let chunk = SEARCH_CHUNK_BYTES.max(needle.len());
            let mut found = false;
            for start in (0..self.backing.arena.len()).step_by(chunk) {
                check()?;
                let end = start
                    .saturating_add(chunk)
                    .saturating_add(needle.len() - 1)
                    .min(self.backing.arena.len());
                if finder.find(&self.backing.arena[start..end]).is_some() {
                    found = true;
                    break;
                }
            }
            found
        };
        check()?;
        Ok(found)
    }

    /// SHA-256 over count + each string (length-prefixed), as the Kotlin writer computes it.
    pub fn compute_identity(&self) -> [u8; 32] {
        let mut d = Sha256::new();
        d.update((self.len() as u32).to_be_bytes());
        for i in 0..self.len() {
            let s = self.get(i).as_bytes();
            d.update((s.len() as u32).to_be_bytes());
            d.update(s);
        }
        d.finalize().into()
    }

    /// A strictly ascending dictionary (the writer's `toSortedSet()`): two IDs are equal
    /// exactly when their texts are, and [`Self::index_of`] finds every text it holds.
    pub fn canonical_dictionary(&self) -> bool {
        *self.backing.canonical.get_or_init(|| {
            (1..self.len())
                .all(|i| java_cmp(self.get(i - 1), self.get(i)) == std::cmp::Ordering::Less)
        })
    }

    /// Binary search (strings are sorted by Java `String.compareTo`, i.e. UTF-16 order).
    pub fn index_of(&self, s: &str) -> Option<usize> {
        let (mut lo, mut hi) = (0usize, self.len());
        while lo < hi {
            let mid = (lo + hi) / 2;
            match java_cmp(self.get(mid), s) {
                std::cmp::Ordering::Less => lo = mid + 1,
                std::cmp::Ordering::Greater => hi = mid,
                std::cmp::Ordering::Equal => return Some(mid),
            }
        }
        None
    }
}

/// Compare like Java `String.compareTo` (UTF-16 code unit order).
pub fn java_cmp(a: &str, b: &str) -> std::cmp::Ordering {
    a.encode_utf16().cmp(b.encode_utf16())
}

fn decode_front_coded(
    flat: &[u8],
    n: usize,
    ratio: usize,
    _id: impl Fn(u8) -> usize,
    _one: impl Fn(u8) -> usize,
    emit: &mut dyn FnMut(&[u8]),
) {
    // ByteArrayFrontCodedList: lengths coded as vByte (7 bits per byte, MSB continuation).
    let mut pos = 0usize;
    let read_int = |a: &[u8], pos: &mut usize| -> usize {
        let mut v = 0usize;
        loop {
            let b = a[*pos];
            *pos += 1;
            v = (v << 7) | (b & 0x7F) as usize;
            if b & 0x80 == 0 {
                return v;
            }
        }
    };
    let mut prev: Vec<u8> = Vec::new();
    let mut buf: Vec<u8> = Vec::new();
    for i in 0..n {
        if i % ratio == 0 {
            let len = read_int(flat, &mut pos);
            buf.clear();
            buf.extend_from_slice(&flat[pos..pos + len]);
            pos += len;
        } else {
            let len = read_int(flat, &mut pos);
            let common = read_int(flat, &mut pos);
            buf.clear();
            buf.extend_from_slice(&prev[..common]);
            buf.extend_from_slice(&flat[pos..pos + len]);
            pos += len;
        }
        emit(&buf);
        std::mem::swap(&mut prev, &mut buf);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn table(strings: &[&str]) -> StringTable {
        let mut arena = Vec::new();
        let mut offsets = vec![0];
        for string in strings {
            arena.extend_from_slice(string.as_bytes());
            offsets.push(arena.len() as u32);
        }
        StringTable {
            backing: Arc::new(StringBacking {
                arena,
                offsets,
                invalid_text: Vec::new(),
                canonical: std::sync::OnceLock::new(),
            }),
            identity: None,
            serialized_digest: std::sync::OnceLock::new(),
        }
    }

    fn contains(table: &StringTable, needle: &str) -> bool {
        table.try_any_contains(needle, || Ok::<_, ()>(())).unwrap()
    }

    #[test]
    fn arena_search_has_no_false_negatives_at_chunk_or_string_boundaries() {
        let padding = "x".repeat(SEARCH_CHUNK_BYTES - 2);
        let text = format!("{padding}类型needle尾");
        let strings = table(&[&text, "alpha", "beta"]);
        for needle in ["类型", "类型needle尾", "needle", "尾", "alpha", "beta"] {
            assert!(contains(&strings, needle), "{needle:?}");
        }
        assert!(!contains(&strings, "missing"));
        // Concatenated-arena matches are intentionally conservative: callers
        // must still evaluate the original predicate when this returns true.
        assert!(contains(&strings, "alphabeta"));
    }

    #[test]
    fn arena_search_handles_empty_tables_strings_and_long_needles() {
        assert!(!contains(&table(&[]), ""));
        assert!(contains(&table(&[""]), ""));
        assert!(!contains(&table(&[""]), "x"));
        assert!(!contains(&table(&["short"]), "longer than arena"));
        let needle = format!("{}z", "a".repeat(SEARCH_CHUNK_BYTES + 13));
        let padding = "x".repeat(needle.len() - 2);
        let text = format!("{padding}{needle}suffix");
        assert!(contains(&table(&[&text]), &needle));
        assert!(!contains(&table(&[&text]), &format!("{needle}absent")));
    }

    #[test]
    fn arena_search_checks_cancellation_before_during_and_after_search() {
        let text = "x".repeat(SEARCH_CHUNK_BYTES * 3);
        let strings = table(&[&text]);
        for stop in [1, 2, 3, 4, 5] {
            let mut checks = 0;
            let result = strings.try_any_contains("absent", || {
                checks += 1;
                if checks == stop {
                    Err(stop)
                } else {
                    Ok(())
                }
            });
            assert_eq!(result, Err(stop));
            assert_eq!(checks, stop);
        }
        let mut checks = 0;
        assert_eq!(
            strings.try_any_contains("x", || {
                checks += 1;
                if checks == 3 {
                    Err("after match")
                } else {
                    Ok(())
                }
            }),
            Err("after match")
        );
        assert_eq!(checks, 3);
        let mut checks = 0;
        assert_eq!(
            table(&[]).try_any_contains("", || {
                checks += 1;
                if checks == 2 {
                    Err("after empty")
                } else {
                    Ok(())
                }
            }),
            Err("after empty")
        );
    }
}

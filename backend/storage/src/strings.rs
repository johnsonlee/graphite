//! `graph.strings`: a `FrontCodedStringList` persisted through Java serialization.
//! Decoded eagerly into a compact arena for zero-copy lookups.

use crate::javaser::{read_object, JavaSerError, Value};
use sha2::{Digest, Sha256};
use std::path::Path;

#[derive(Debug, thiserror::Error)]
pub enum StringTableError {
    #[error("io error on {0}: {1}")]
    Io(String, std::io::Error),
    #[error("java serialization: {0}")]
    Ser(#[from] JavaSerError),
    #[error("unexpected string table layout: {0}")]
    Layout(String),
}

pub struct StringTable {
    arena: Vec<u8>,
    /// `offsets[i]..offsets[i+1]` is string i (UTF-8).
    offsets: Vec<u32>,
    identity: Option<[u8; 32]>,
}

impl StringTable {
    pub fn load(dir: &Path) -> Result<Self, StringTableError> {
        let path = dir.join("graph.strings");
        let data = std::fs::read(&path)
            .map_err(|e| StringTableError::Io(path.display().to_string(), e))?;
        let mut table = Self::from_serialized(&data)?;
        let id_path = dir.join("graph.strings.identity");
        if let Ok(bytes) = std::fs::read(&id_path) {
            if bytes.len() == 32 {
                table.identity = Some(bytes.try_into().unwrap());
            }
        }
        Ok(table)
    }

    pub fn from_serialized(data: &[u8]) -> Result<Self, StringTableError> {
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
            decode_front_coded(
                &flat,
                n,
                ratio,
                |v| v as usize,
                |_| 1,
                &mut |bytes: &[u8]| {
                    arena.extend_from_slice(bytes);
                    offsets.push(arena.len() as u32);
                },
            );
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
                let s = String::from_utf16_lossy(&buf);
                arena.extend_from_slice(s.as_bytes());
                offsets.push(arena.len() as u32);
                std::mem::swap(&mut prev, &mut buf);
            }
        }
        arena.shrink_to_fit();
        Ok(StringTable {
            arena,
            offsets,
            identity: None,
        })
    }

    #[inline]
    pub fn len(&self) -> usize {
        self.offsets.len() - 1
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    #[inline]
    pub fn get(&self, i: usize) -> &str {
        let s = self.offsets[i] as usize;
        let e = self.offsets[i + 1] as usize;
        // SAFETY: arena is built from valid UTF-8 strings at these boundaries.
        unsafe { std::str::from_utf8_unchecked(&self.arena[s..e]) }
    }

    pub fn identity(&self) -> Option<&[u8; 32]> {
        self.identity.as_ref()
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

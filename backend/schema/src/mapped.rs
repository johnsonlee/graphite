//! Indexed schema storage. Bulk strings, row bodies and their directories stay
//! in the caller's mapping; only the bounded registry is materialized at open.
use crate::{codec, *};
use memmap2::Mmap;
use sha2::{Digest, Sha256};
use std::{fs::File, path::Path};

pub const MAPPED_VERSION: u32 = 2;
const HEADER: usize = 80;
const STRING_ENTRY: usize = 56;
const ROW_ENTRY: usize = 64;

/// Separate limits for the small registry, one decoded record, and mapped data.
/// File size is not a heap allocation budget. No per-row heap index is built.
#[derive(Clone, Debug)]
pub struct MappedLimits {
    pub metadata: Limits,
    pub record: Limits,
    pub max_file_bytes: u64,
    pub max_strings: u64,
    pub max_rows: u64,
}
impl Default for MappedLimits {
    fn default() -> Self {
        Self {
            metadata: Limits {
                max_bytes: 16 * 1024 * 1024,
                ..Limits::default()
            },
            record: Limits::default(),
            max_file_bytes: u64::MAX,
            max_strings: u32::MAX as u64 + 1,
            max_rows: u64::MAX,
        }
    }
}

/// A schema view owning or borrowing its backing bytes. Pass a mapped file or
/// an existing mapped container entry to avoid copying bulk data.
///
/// Opening validates registry and directory structure, not payload contents.
/// `record` and `string` verify only the selected payload. Use `verify_all` for
/// a complete integrity/structural check, with bounded temporary record memory.
pub struct MappedDocument<B> {
    bytes: B,
    metadata: Document,
    limits: MappedLimits,
    strings_at: usize,
    rows_at: usize,
    strings: usize,
    rows: usize,
}

fn invalid(message: &str) -> Error {
    Error::Invalid(message.into())
}
fn range(bytes: &[u8], offset: u64, length: u64) -> Result<&[u8]> {
    let start = usize::try_from(offset).map_err(|_| Error::Truncated)?;
    let len = usize::try_from(length).map_err(|_| Error::Truncated)?;
    let end = start.checked_add(len).ok_or(Error::Truncated)?;
    bytes.get(start..end).ok_or(Error::Truncated)
}
fn u32_at(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap())
}
fn u64_at(bytes: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(bytes[at..at + 8].try_into().unwrap())
}
fn add_entries(at: usize, count: usize, width: usize) -> Result<usize> {
    at.checked_add(count.checked_mul(width).ok_or(Error::Truncated)?)
        .ok_or(Error::Truncated)
}
fn checked_digest(bytes: &[u8], digest: &[u8]) -> Result<()> {
    if Sha256::digest(bytes).as_slice() != digest {
        return Err(Error::Checksum);
    }
    Ok(())
}

impl<B: AsRef<[u8]>> MappedDocument<B> {
    /// Borrow or own a mapped byte range. This never decodes bulk strings/rows.
    /// Directory validation is linear in directory entries with constant space.
    pub fn from_bytes(bytes: B, limits: MappedLimits) -> Result<Self> {
        Document::default().validate(&limits.record)?;
        let data = bytes.as_ref();
        let header = data.get(..HEADER).ok_or(Error::Truncated)?;
        if &header[..8] != b"GSCHEMA\0" {
            return Err(invalid("magic"));
        }
        let version = u32_at(header, 8);
        if version != MAPPED_VERSION {
            return Err(Error::UnsupportedVersion(version));
        }
        if u32_at(header, 12) != 0 {
            return Err(invalid("reserved header bits"));
        }
        if u64_at(header, 16) != data.len() as u64 {
            return Err(invalid("file length"));
        }
        if data.len() as u64 > limits.max_file_bytes {
            return Err(Error::Limit("mapped file bytes"));
        }
        let metadata_len = u64_at(header, 24);
        if metadata_len > limits.metadata.max_bytes as u64 {
            return Err(Error::Limit("metadata bytes"));
        }
        let strings = u64_at(header, 32);
        let rows = u64_at(header, 40);
        if strings > limits.max_strings {
            return Err(Error::Limit("string count"));
        }
        if rows > limits.max_rows {
            return Err(Error::Limit("row count"));
        }
        let strings = usize::try_from(strings).map_err(|_| Error::Truncated)?;
        let rows = usize::try_from(rows).map_err(|_| Error::Truncated)?;
        let metadata_bytes = range(data, HEADER as u64, metadata_len)?;
        let strings_at = HEADER
            .checked_add(metadata_bytes.len())
            .ok_or(Error::Truncated)?;
        let rows_at = add_entries(strings_at, strings, STRING_ENTRY)?;
        let payload_at = add_entries(rows_at, rows, ROW_ENTRY)?;
        if payload_at > data.len() {
            return Err(Error::Truncated);
        }
        if index_digest(data, payload_at).as_slice() != &header[48..80] {
            return Err(Error::Checksum);
        }
        let metadata = decode(metadata_bytes, &limits.metadata)?;
        if !metadata.strings.is_empty()
            || metadata.tables.values().any(|table| !table.rows.is_empty())
        {
            return Err(invalid("bulk data in metadata"));
        }
        let view = Self {
            bytes,
            metadata,
            limits,
            strings_at,
            rows_at,
            strings,
            rows,
        };
        view.validate_directories(payload_at)?;
        Ok(view)
    }

    /// Only definitions, profiles and table names; bulk dictionaries/rows remain mapped.
    pub fn metadata(&self) -> &Document {
        &self.metadata
    }
    pub fn string_count(&self) -> usize {
        self.strings
    }
    pub fn record_count(&self) -> usize {
        self.rows
    }

    /// Enumerate persisted dictionary IDs without loading their strings.
    pub fn string_ids(&self) -> impl ExactSizeIterator<Item = StringId> + '_ {
        (0..self.strings).map(|i| u32_at(self.string_entry(i), 0))
    }

    /// Enumerate row addresses and layouts directly from the mapped directory.
    /// This does not decode any row bodies, including for unfamiliar layouts.
    pub fn records(&self) -> impl ExactSizeIterator<Item = (Reference, LayoutId)> + '_ {
        (0..self.rows).map(|i| {
            let entry = self.row_entry(i);
            (
                Reference {
                    table: u32_at(entry, 0),
                    row: u32_at(entry, 4),
                },
                u32_at(entry, 8),
            )
        })
    }

    fn string_entry(&self, index: usize) -> &[u8] {
        let start = self.strings_at + index * STRING_ENTRY;
        &self.bytes.as_ref()[start..start + STRING_ENTRY]
    }
    fn row_entry(&self, index: usize) -> &[u8] {
        let start = self.rows_at + index * ROW_ENTRY;
        &self.bytes.as_ref()[start..start + ROW_ENTRY]
    }
    fn find_string(&self, id: StringId) -> Option<&[u8]> {
        let mut low = 0;
        let mut high = self.strings;
        while low < high {
            let mid = low + (high - low) / 2;
            let entry = self.string_entry(mid);
            match u32_at(entry, 0).cmp(&id) {
                std::cmp::Ordering::Less => low = mid + 1,
                std::cmp::Ordering::Greater => high = mid,
                std::cmp::Ordering::Equal => return Some(entry),
            }
        }
        None
    }
    fn find_row(&self, reference: Reference) -> Option<&[u8]> {
        let mut low = 0;
        let mut high = self.rows;
        while low < high {
            let mid = low + (high - low) / 2;
            let entry = self.row_entry(mid);
            let key = Reference {
                table: u32_at(entry, 0),
                row: u32_at(entry, 4),
            };
            match key.cmp(&reference) {
                std::cmp::Ordering::Less => low = mid + 1,
                std::cmp::Ordering::Greater => high = mid,
                std::cmp::Ordering::Equal => return Some(entry),
            }
        }
        None
    }
    fn validate_directories(&self, payload_at: usize) -> Result<()> {
        let mut next = payload_at as u64;
        let mut previous_string = None;
        for i in 0..self.strings {
            let entry = self.string_entry(i);
            let id = u32_at(entry, 0);
            if previous_string.is_some_and(|last| last >= id) {
                return Err(invalid("duplicate or unsorted string ID"));
            }
            previous_string = Some(id);
            if u32_at(entry, 4) != 0 {
                return Err(invalid("string reserved bits"));
            }
            self.validate_extent(entry, 8, &mut next)?;
        }
        let mut previous_row = None;
        for i in 0..self.rows {
            let entry = self.row_entry(i);
            let key = (u32_at(entry, 0), u32_at(entry, 4));
            if previous_row.is_some_and(|last| last >= key) {
                return Err(invalid("duplicate or unsorted row ID"));
            }
            previous_row = Some(key);
            if !self.metadata.tables.contains_key(&key.0) {
                return Err(invalid("row table missing"));
            }
            if !self.metadata.definitions.contains_key(&u32_at(entry, 8)) {
                return Err(invalid("row layout missing"));
            }
            if u32_at(entry, 12) != 0 {
                return Err(invalid("row reserved bits"));
            }
            self.validate_extent(entry, 16, &mut next)?;
        }
        if next != self.bytes.as_ref().len() as u64 {
            return Err(invalid("trailing payload bytes"));
        }
        Ok(())
    }
    fn validate_extent(&self, entry: &[u8], at: usize, next: &mut u64) -> Result<()> {
        let offset = u64_at(entry, at);
        let length = u64_at(entry, at + 8);
        if offset != *next {
            return Err(invalid("noncontiguous payload range"));
        }
        range(self.bytes.as_ref(), offset, length)?;
        *next = offset.checked_add(length).ok_or(Error::Truncated)?;
        Ok(())
    }

    /// Borrow a UTF-8 dictionary value directly from the backing mapping.
    pub fn string(&self, id: StringId) -> Result<Option<&str>> {
        let Some(entry) = self.find_string(id) else {
            return Ok(None);
        };
        let data = range(self.bytes.as_ref(), u64_at(entry, 8), u64_at(entry, 16))?;
        checked_digest(data, &entry[24..56])?;
        Ok(Some(
            std::str::from_utf8(data).map_err(|_| invalid("UTF-8"))?,
        ))
    }

    /// Decode just this row (including its inline values), using the same path
    /// for all layouts. References are checked but their target bodies are not read.
    pub fn record(&self, reference: Reference) -> Result<Option<Record>> {
        let Some(entry) = self.find_row(reference) else {
            return Ok(None);
        };
        let length = u64_at(entry, 24);
        if length > self.limits.record.max_bytes as u64 {
            return Err(Error::Limit("record bytes"));
        }
        let data = range(self.bytes.as_ref(), u64_at(entry, 16), length)?;
        checked_digest(data, &entry[32..64])?;
        let record =
            codec::decode_record(data, u32_at(entry, 8), &self.metadata, &self.limits.record)?;
        self.validate_references(&record)?;
        Ok(Some(record))
    }
    fn validate_references(&self, record: &Record) -> Result<()> {
        for value in record.fields.iter().flatten() {
            self.validate_value_references(value)?;
        }
        Ok(())
    }
    fn validate_value_references(&self, value: &Value) -> Result<()> {
        match value {
            Value::String(id) if self.find_string(*id).is_none() => {
                Err(invalid("string reference missing"))
            }
            Value::Ref(reference) if self.find_row(*reference).is_none() => {
                Err(invalid("row reference missing"))
            }
            Value::List(values) => {
                for value in values {
                    self.validate_value_references(value)?;
                }
                Ok(())
            }
            Value::Record(record) => self.validate_references(record),
            _ => Ok(()),
        }
    }

    /// Explicit full payload verification. No complete Document is constructed;
    /// each decoded row is dropped before the next one is read.
    pub fn verify_all(&self) -> Result<()> {
        for i in 0..self.strings {
            self.string(u32_at(self.string_entry(i), 0))?;
        }
        for i in 0..self.rows {
            let entry = self.row_entry(i);
            self.record(Reference {
                table: u32_at(entry, 0),
                row: u32_at(entry, 4),
            })?;
        }
        Ok(())
    }
}

impl MappedDocument<Mmap> {
    /// Map a standalone schema file. Container callers can instead pass their
    /// existing mapped entry to `from_bytes`, sharing the container's mapping.
    ///
    /// # Safety
    /// The backing file must not be modified or truncated while this view lives,
    /// including by another process. Publish replacements with atomic rename.
    pub unsafe fn open(path: impl AsRef<Path>, limits: MappedLimits) -> Result<Self> {
        let file = File::open(path)?;
        // SAFETY: the caller guarantees the backing file remains immutable.
        let map = unsafe { Mmap::map(&file)? };
        Self::from_bytes(map, limits)
    }
}

/// Encode an indexed file for mapped access. The input Document and output Vec
/// are owned in memory; large-scale streaming construction belongs in the indexer.
/// This does not change the v1 interchange `encode`/`decode` APIs.
pub fn encode_mapped(document: &Document, limits: &Limits) -> Result<Vec<u8>> {
    document.validate(limits)?;
    let metadata = Document {
        definitions: document.definitions.clone(),
        profiles: document.profiles.clone(),
        tables: document
            .tables
            .iter()
            .map(|(id, table)| {
                (
                    *id,
                    Table {
                        name: table.name.clone(),
                        rows: Default::default(),
                    },
                )
            })
            .collect(),
        ..Document::default()
    };
    let metadata = encode(&metadata, limits)?;
    let rows = document.tables.values().try_fold(0usize, |n, table| {
        n.checked_add(table.rows.len()).ok_or(Error::Limit("rows"))
    })?;
    let strings_at = HEADER
        .checked_add(metadata.len())
        .ok_or(Error::Limit("bytes"))?;
    let rows_at = add_entries(strings_at, document.strings.len(), STRING_ENTRY)?;
    let payload_at = add_entries(rows_at, rows, ROW_ENTRY)?;
    if payload_at > limits.max_bytes {
        return Err(Error::Limit("bytes"));
    }
    let mut out = vec![0; payload_at];
    out[..8].copy_from_slice(b"GSCHEMA\0");
    out[8..12].copy_from_slice(&MAPPED_VERSION.to_le_bytes());
    out[24..32].copy_from_slice(&(metadata.len() as u64).to_le_bytes());
    out[32..40].copy_from_slice(&(document.strings.len() as u64).to_le_bytes());
    out[40..48].copy_from_slice(&(rows as u64).to_le_bytes());
    out[HEADER..strings_at].copy_from_slice(&metadata);
    for (i, (id, value)) in document.strings.iter().enumerate() {
        let at = strings_at + i * STRING_ENTRY;
        out[at..at + 4].copy_from_slice(&id.to_le_bytes());
        append_payload(&mut out, at + 8, value.as_bytes(), limits)?;
    }
    let mut i = 0;
    for (table_id, table) in &document.tables {
        for (row_id, record) in &table.rows {
            let at = rows_at + i * ROW_ENTRY;
            out[at..at + 4].copy_from_slice(&table_id.to_le_bytes());
            out[at + 4..at + 8].copy_from_slice(&row_id.to_le_bytes());
            out[at + 8..at + 12].copy_from_slice(&record.layout.to_le_bytes());
            let body = codec::encode_record(record, document, limits)?;
            append_payload(&mut out, at + 16, &body, limits)?;
            i += 1;
        }
    }
    let len = out.len() as u64;
    out[16..24].copy_from_slice(&len.to_le_bytes());
    let digest = index_digest(&out, payload_at);
    out[48..80].copy_from_slice(&digest);
    Ok(out)
}
fn index_digest(data: &[u8], payload_at: usize) -> Vec<u8> {
    let mut hash = Sha256::new();
    hash.update(&data[..48]);
    hash.update(&data[HEADER..payload_at]);
    hash.finalize().to_vec()
}
fn append_payload(out: &mut Vec<u8>, at: usize, data: &[u8], limits: &Limits) -> Result<()> {
    let offset = out.len();
    let end = offset
        .checked_add(data.len())
        .ok_or(Error::Limit("bytes"))?;
    if end > limits.max_bytes {
        return Err(Error::Limit("bytes"));
    }
    out[at..at + 8].copy_from_slice(&(offset as u64).to_le_bytes());
    out[at + 8..at + 16].copy_from_slice(&(data.len() as u64).to_le_bytes());
    out[at + 16..at + 48].copy_from_slice(&Sha256::digest(data));
    out.extend_from_slice(data);
    Ok(())
}

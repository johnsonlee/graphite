//! GSCHEMA/1: a bounded, self-describing logical-record container.
//!
//! The encoding is intentionally separate from production graph storage. It is
//! an interchange contract for frontends and the future indexer, not a v4 graph.

use crate::*;
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;

pub const WIRE_VERSION: u32 = 1;
const MAGIC: &[u8; 8] = b"GSCHEMA\0";
const HEADER_LEN: usize = 52;
const SECTION_COUNT: u32 = 4;
const DIRECTORY_LEN: usize = 4 + SECTION_COUNT as usize * 44;

/// Encode a validated document deterministically. All numbers are little-endian.
/// IDs and float bit patterns are preserved; there is no semantic normalization.
pub fn encode(document: &Document, limits: &Limits) -> Result<Vec<u8>> {
    document.validate(limits)?;
    let mut sections = Vec::new();
    let mut strings = Sink::new(limits);
    strings.count(document.strings.len())?;
    for (id, value) in &document.strings {
        strings.u32(*id)?;
        strings.text(value)?;
    }
    sections.push(strings.data);
    let mut profiles = Sink::new(limits);
    profiles.count(document.profiles.len())?;
    for profile in &document.profiles {
        profiles.name(&profile.name)?;
        profiles.text(&profile.revision)?;
    }
    sections.push(profiles.data);
    let mut definitions = Sink::new(limits);
    definitions.count(document.definitions.len())?;
    for (id, definition) in &document.definitions {
        definitions.u32(*id)?;
        definitions.name(&definition.name)?;
        definitions.text(&definition.revision)?;
        definitions.name(&definition.category)?;
        definitions.count(definition.fields.len())?;
        for field in &definition.fields {
            definitions.name(&field.name)?;
            definitions.descriptor(&field.descriptor)?;
            definitions.u8(u8::from(field.required))?;
            definitions.u8(u8::from(field.nullable))?;
            definitions.u8(u8::from(field.role.is_some()))?;
            if let Some(role) = &field.role {
                definitions.name(role)?;
            }
        }
    }
    sections.push(definitions.data);
    let mut tables = Sink::new(limits);
    tables.count(document.tables.len())?;
    for (id, table) in &document.tables {
        tables.u32(*id)?;
        tables.name(&table.name)?;
        let mut groups: BTreeMap<LayoutId, Vec<(RowId, &Record)>> = BTreeMap::new();
        for (row, record) in &table.rows {
            groups
                .entry(record.layout)
                .or_default()
                .push((*row, record));
        }
        tables.count(groups.len())?;
        for (layout, rows) in groups {
            tables.u32(layout)?;
            tables.count(rows.len())?;
            for (id, record) in rows {
                tables.u32(id)?;
                let mut row = Sink::new(limits);
                row.fields(record, document)?;
                tables.sized(&row.data)?;
            }
        }
    }
    sections.push(tables.data);
    let payload_len = sections.iter().try_fold(DIRECTORY_LEN, |n, section| {
        n.checked_add(section.len()).ok_or(Error::Limit("bytes"))
    })?;
    if payload_len
        .checked_add(HEADER_LEN)
        .ok_or(Error::Limit("bytes"))?
        > limits.max_bytes
    {
        return Err(Error::Limit("bytes"));
    }
    let mut payload = Sink::new(limits);
    payload.u32(SECTION_COUNT)?;
    for (index, section) in sections.iter().enumerate() {
        payload.u32(index as u32 + 1)?;
        payload.u64(section.len() as u64)?;
        payload.bytes(&Sha256::digest(section))?;
    }
    for section in sections {
        payload.bytes(&section)?;
    }
    let mut output = Sink::new(limits);
    output.bytes(MAGIC)?;
    output.u32(WIRE_VERSION)?;
    output.u64(payload.data.len() as u64)?;
    output.bytes(&Sha256::digest(&payload.data))?;
    output.bytes(&payload.data)?;
    Ok(output.data)
}

/// Decode and validate the entire container, including unknown definitions and
/// all nested references. Unsupported physical encodings fail explicitly.
pub fn decode(bytes: &[u8], limits: &Limits) -> Result<Document> {
    // Validate the budget itself before allowing recursive descriptor decoding.
    Document::default().validate(limits)?;
    if bytes.len() > limits.max_bytes {
        return Err(Error::Limit("bytes"));
    }
    let mut budget = Budget { items: 0, limits };
    let mut header = Input::new(bytes);
    if header.take(8)? != MAGIC {
        return Err(invalid("magic"));
    }
    let version = header.u32()?;
    if version != WIRE_VERSION {
        return Err(Error::UnsupportedVersion(version));
    }
    let payload_len = header.length()?;
    let digest = header.take(32)?;
    let payload = header.take(payload_len)?;
    header.end()?;
    if Sha256::digest(payload).as_slice() != digest {
        return Err(Error::Checksum);
    }
    let mut directory = Input::new(payload);
    if directory.u32()? != SECTION_COUNT {
        return Err(invalid("section count"));
    }
    let mut entries = Vec::new();
    for kind in 1..=SECTION_COUNT {
        if directory.u32()? != kind {
            return Err(invalid("section order or kind"));
        }
        entries.push((directory.length()?, directory.take(32)?));
    }
    let mut sections = Vec::new();
    for (length, digest) in entries {
        let data = directory.take(length)?;
        if Sha256::digest(data).as_slice() != digest {
            return Err(Error::Checksum);
        }
        sections.push(data);
    }
    directory.end()?;
    let mut document = Document::default();
    let mut input = Input::new(sections[0]);
    for _ in 0..input.count(&mut budget)? {
        let id = input.u32()?;
        let text = input.text()?;
        insert_unique(&mut document.strings, id, text, "string ID")?;
    }
    input.end()?;
    let mut input = Input::new(sections[1]);
    for _ in 0..input.count(&mut budget)? {
        document.profiles.push(Profile {
            name: input.name()?,
            revision: input.text()?,
        });
    }
    input.end()?;
    let mut input = Input::new(sections[2]);
    for _ in 0..input.count(&mut budget)? {
        let id = input.u32()?;
        let name = input.name()?;
        let revision = input.text()?;
        let category = input.name()?;
        let mut fields = Vec::new();
        for _ in 0..input.count(&mut budget)? {
            fields.push(Field {
                name: input.name()?,
                descriptor: input.descriptor(&mut budget, 0)?,
                required: input.boolean()?,
                nullable: input.boolean()?,
                role: if input.boolean()? {
                    Some(input.name()?)
                } else {
                    None
                },
            });
        }
        insert_unique(
            &mut document.definitions,
            id,
            Definition {
                name,
                revision,
                category,
                fields,
            },
            "layout ID",
        )?;
    }
    input.end()?;
    let mut input = Input::new(sections[3]);
    for _ in 0..input.count(&mut budget)? {
        let id = input.u32()?;
        let name = input.name()?;
        let mut rows = BTreeMap::new();
        let mut previous_layout = None;
        for _ in 0..input.count(&mut budget)? {
            let layout = input.u32()?;
            if previous_layout.is_some_and(|previous| previous >= layout) {
                return Err(invalid("duplicate or unsorted layout group"));
            }
            previous_layout = Some(layout);
            let count = input.count(&mut budget)?;
            if count == 0 {
                return Err(invalid("empty layout group"));
            }
            let mut previous_row = None;
            for _ in 0..count {
                let row_id = input.u32()?;
                if previous_row.is_some_and(|previous| previous >= row_id) {
                    return Err(invalid("duplicate or unsorted row ID in group"));
                }
                previous_row = Some(row_id);
                let data = input.sized()?;
                let mut row = Input::new(data);
                let record = row.record(layout, &document, &mut budget, 0)?;
                row.end()?;
                insert_unique(&mut rows, row_id, record, "row ID")?;
            }
        }
        insert_unique(&mut document.tables, id, Table { name, rows }, "table ID")?;
    }
    input.end()?;
    document.validate(limits)?;
    Ok(document)
}

fn invalid(message: &str) -> Error {
    Error::Invalid(message.into())
}

fn insert_unique<K: Ord, V>(map: &mut BTreeMap<K, V>, key: K, value: V, what: &str) -> Result<()> {
    if map.insert(key, value).is_some() {
        return Err(invalid(&format!("duplicate {what}")));
    }
    Ok(())
}

struct Sink<'a> {
    data: Vec<u8>,
    limits: &'a Limits,
}
impl<'a> Sink<'a> {
    fn new(limits: &'a Limits) -> Self {
        Self {
            data: Vec::new(),
            limits,
        }
    }
    fn bytes(&mut self, value: &[u8]) -> Result<()> {
        if self
            .data
            .len()
            .checked_add(value.len())
            .ok_or(Error::Limit("bytes"))?
            > self.limits.max_bytes
        {
            return Err(Error::Limit("bytes"));
        }
        self.data.extend_from_slice(value);
        Ok(())
    }
    fn u8(&mut self, value: u8) -> Result<()> {
        self.bytes(&[value])
    }
    fn u32(&mut self, value: u32) -> Result<()> {
        self.bytes(&value.to_le_bytes())
    }
    fn u64(&mut self, value: u64) -> Result<()> {
        self.bytes(&value.to_le_bytes())
    }
    fn count(&mut self, value: usize) -> Result<()> {
        self.u32(u32::try_from(value).map_err(|_| Error::Limit("u32 count"))?)
    }
    fn sized(&mut self, value: &[u8]) -> Result<()> {
        self.u64(value.len() as u64)?;
        self.bytes(value)
    }
    fn text(&mut self, value: &str) -> Result<()> {
        self.sized(value.as_bytes())
    }
    fn name(&mut self, value: &QualifiedName) -> Result<()> {
        self.text(&value.namespace)?;
        self.text(&value.name)
    }
    fn descriptor(&mut self, descriptor: &Descriptor) -> Result<()> {
        match descriptor {
            Descriptor::Null => self.u8(0),
            Descriptor::Bool => self.u8(1),
            Descriptor::Int64 => self.u8(2),
            Descriptor::UInt64 => self.u8(3),
            Descriptor::Float64 => self.u8(4),
            Descriptor::String => self.u8(5),
            Descriptor::Bytes => self.u8(6),
            Descriptor::Ref(target) => {
                self.u8(7)?;
                self.u8(u8::from(target.is_some()))?;
                if let Some(table) = target {
                    self.u32(*table)?;
                }
                Ok(())
            }
            Descriptor::List(element) => {
                self.u8(8)?;
                self.descriptor(element)
            }
            Descriptor::Record => self.u8(9),
        }
    }
    fn fields(&mut self, record: &Record, document: &Document) -> Result<()> {
        let definition = &document.definitions[&record.layout];
        let width = definition.fields.len().div_ceil(8);
        let mut present = vec![0u8; width];
        let mut nulls = vec![0u8; width];
        for (index, value) in record.fields.iter().enumerate() {
            if value.is_some() {
                present[index / 8] |= 1 << (index % 8);
            }
            if matches!(value, Some(Value::Null)) {
                nulls[index / 8] |= 1 << (index % 8);
            }
        }
        self.bytes(&present)?;
        self.bytes(&nulls)?;
        for (field, value) in definition.fields.iter().zip(&record.fields) {
            if let Some(value) = value {
                if !matches!(value, Value::Null) {
                    self.value(value, &field.descriptor, document)?;
                }
            }
        }
        Ok(())
    }
    fn value(&mut self, value: &Value, descriptor: &Descriptor, document: &Document) -> Result<()> {
        match (value, descriptor) {
            (Value::Null, Descriptor::Null) => Ok(()),
            (Value::Bool(value), _) => self.u8(u8::from(*value)),
            (Value::Int64(value), _) => self.bytes(&value.to_le_bytes()),
            (Value::UInt64(value), _) | (Value::Float64(value), _) => self.u64(*value),
            (Value::String(value), _) => self.u32(*value),
            (Value::Bytes(value), _) => self.sized(value),
            (Value::Ref(value), Descriptor::Ref(target)) => {
                if target.is_none() {
                    self.u32(value.table)?;
                }
                self.u32(value.row)
            }
            (Value::List(values), Descriptor::List(element)) => {
                self.count(values.len())?;
                for value in values {
                    self.value(value, element, document)?;
                }
                Ok(())
            }
            (Value::Record(record), Descriptor::Record) => {
                self.u32(record.layout)?;
                self.fields(record, document)
            }
            _ => Err(invalid("value does not match descriptor")),
        }
    }
}

struct Budget<'a> {
    items: usize,
    limits: &'a Limits,
}
impl Budget<'_> {
    fn items(&mut self, n: usize) -> Result<()> {
        self.items = self.items.checked_add(n).ok_or(Error::Limit("items"))?;
        if self.items > self.limits.max_items {
            return Err(Error::Limit("items"));
        }
        Ok(())
    }
    fn depth(&self, depth: usize) -> Result<()> {
        if depth > self.limits.max_depth {
            return Err(Error::Limit("depth"));
        }
        Ok(())
    }
}

struct Input<'a> {
    data: &'a [u8],
    pos: usize,
}
impl<'a> Input<'a> {
    fn new(data: &'a [u8]) -> Self {
        Self { data, pos: 0 }
    }
    fn take(&mut self, length: usize) -> Result<&'a [u8]> {
        let end = self.pos.checked_add(length).ok_or(Error::Truncated)?;
        let data = self.data.get(self.pos..end).ok_or(Error::Truncated)?;
        self.pos = end;
        Ok(data)
    }
    fn end(&self) -> Result<()> {
        if self.pos != self.data.len() {
            return Err(invalid("trailing bytes"));
        }
        Ok(())
    }
    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }
    fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_le_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn u64(&mut self) -> Result<u64> {
        Ok(u64::from_le_bytes(self.take(8)?.try_into().unwrap()))
    }
    fn length(&mut self) -> Result<usize> {
        usize::try_from(self.u64()?).map_err(|_| Error::Limit("length"))
    }
    fn count(&mut self, budget: &mut Budget<'_>) -> Result<usize> {
        let count = self.u32()? as usize;
        budget.items(count)?;
        Ok(count)
    }
    fn boolean(&mut self) -> Result<bool> {
        match self.u8()? {
            0 => Ok(false),
            1 => Ok(true),
            _ => Err(invalid("boolean")),
        }
    }
    fn sized(&mut self) -> Result<&'a [u8]> {
        let n = self.length()?;
        self.take(n)
    }
    fn text(&mut self) -> Result<String> {
        String::from_utf8(self.sized()?.to_vec()).map_err(|_| invalid("UTF-8"))
    }
    fn name(&mut self) -> Result<QualifiedName> {
        Ok(QualifiedName {
            namespace: self.text()?,
            name: self.text()?,
        })
    }
    fn descriptor(&mut self, budget: &mut Budget<'_>, depth: usize) -> Result<Descriptor> {
        budget.depth(depth)?;
        budget.items(1)?;
        Ok(match self.u8()? {
            0 => Descriptor::Null,
            1 => Descriptor::Bool,
            2 => Descriptor::Int64,
            3 => Descriptor::UInt64,
            4 => Descriptor::Float64,
            5 => Descriptor::String,
            6 => Descriptor::Bytes,
            7 => Descriptor::Ref(if self.boolean()? {
                Some(self.u32()?)
            } else {
                None
            }),
            8 => Descriptor::List(Box::new(self.descriptor(budget, depth + 1)?)),
            9 => Descriptor::Record,
            _ => return Err(invalid("descriptor tag")),
        })
    }
    fn record(
        &mut self,
        layout: LayoutId,
        document: &Document,
        budget: &mut Budget<'_>,
        depth: usize,
    ) -> Result<Record> {
        budget.depth(depth)?;
        let definition = document
            .definitions
            .get(&layout)
            .ok_or_else(|| invalid("unknown layout ID"))?;
        budget.items(definition.fields.len())?;
        let width = definition.fields.len().div_ceil(8);
        let present = self.take(width)?;
        let nulls = self.take(width)?;
        if !definition.fields.len().is_multiple_of(8) {
            let used = definition.fields.len() % 8;
            if (present[width - 1] | nulls[width - 1]) >> used != 0 {
                return Err(invalid("bitmap padding"));
            }
        }
        let mut fields = Vec::new();
        for (index, field) in definition.fields.iter().enumerate() {
            let mask = 1 << (index % 8);
            let is_present = present[index / 8] & mask != 0;
            let is_null = nulls[index / 8] & mask != 0;
            if is_null && !is_present {
                return Err(invalid("null bit without presence"));
            }
            fields.push(if !is_present {
                None
            } else if is_null {
                Some(Value::Null)
            } else {
                if field.descriptor == Descriptor::Null {
                    return Err(invalid("Null field without null bit"));
                }
                Some(self.value(&field.descriptor, document, budget, depth + 1)?)
            });
        }
        Ok(Record { layout, fields })
    }
    fn value(
        &mut self,
        descriptor: &Descriptor,
        document: &Document,
        budget: &mut Budget<'_>,
        depth: usize,
    ) -> Result<Value> {
        budget.depth(depth)?;
        budget.items(1)?;
        Ok(match descriptor {
            Descriptor::Null => Value::Null,
            Descriptor::Bool => Value::Bool(self.boolean()?),
            Descriptor::Int64 => Value::Int64(self.u64()? as i64),
            Descriptor::UInt64 => Value::UInt64(self.u64()?),
            Descriptor::Float64 => Value::Float64(self.u64()?),
            Descriptor::String => Value::String(self.u32()?),
            Descriptor::Bytes => Value::Bytes(self.sized()?.to_vec()),
            Descriptor::Ref(target) => Value::Ref(Reference {
                table: match target {
                    Some(table) => *table,
                    None => self.u32()?,
                },
                row: self.u32()?,
            }),
            Descriptor::List(element) => {
                let count = self.count(budget)?;
                let mut values = Vec::new();
                for _ in 0..count {
                    values.push(self.value(element, document, budget, depth + 1)?);
                }
                Value::List(values)
            }
            Descriptor::Record => {
                let layout = self.u32()?;
                Value::Record(Box::new(self.record(
                    layout,
                    document,
                    budget,
                    depth + 1,
                )?))
            }
        })
    }
}

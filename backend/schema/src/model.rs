use crate::{Error, Result};
use std::collections::{BTreeMap, BTreeSet};

pub type LayoutId = u32;
pub type TableId = u32;
pub type RowId = u32;
pub type StringId = u32;

#[derive(Clone, Debug, Eq, PartialEq, Ord, PartialOrd)]
pub struct QualifiedName {
    pub namespace: String,
    pub name: String,
}

impl QualifiedName {
    pub fn new(namespace: impl Into<String>, name: impl Into<String>) -> Self {
        Self {
            namespace: namespace.into(),
            name: name.into(),
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Definition {
    pub name: QualifiedName,
    pub revision: String,
    pub category: QualifiedName,
    pub fields: Vec<Field>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Field {
    pub name: QualifiedName,
    pub descriptor: Descriptor,
    pub required: bool,
    pub nullable: bool,
    pub role: Option<QualifiedName>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Descriptor {
    Null,
    Bool,
    Int64,
    UInt64,
    Float64,
    String,
    Bytes,
    Ref(Option<TableId>),
    List(Box<Descriptor>),
    Record,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Value {
    Null,
    Bool(bool),
    Int64(i64),
    UInt64(u64),
    /// IEEE 754 bits, preserving signed zero and NaN payloads exactly.
    Float64(u64),
    String(StringId),
    Bytes(Vec<u8>),
    Ref(Reference),
    List(Vec<Value>),
    Record(Box<Record>),
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Record {
    pub layout: LayoutId,
    /// None is absent; Some(Value::Null) is explicitly present and null.
    pub fields: Vec<Option<Value>>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd)]
pub struct Reference {
    pub table: TableId,
    pub row: RowId,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Table {
    pub name: QualifiedName,
    pub rows: BTreeMap<RowId, Record>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Profile {
    pub name: QualifiedName,
    pub revision: String,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct Document {
    pub strings: BTreeMap<StringId, String>,
    pub definitions: BTreeMap<LayoutId, Definition>,
    pub profiles: Vec<Profile>,
    pub tables: BTreeMap<TableId, Table>,
}

#[derive(Clone, Debug)]
pub struct Limits {
    pub max_bytes: usize,
    pub max_items: usize,
    pub max_depth: usize,
}

impl Default for Limits {
    fn default() -> Self {
        Self {
            max_bytes: 64 * 1024 * 1024,
            max_items: 1_000_000,
            max_depth: 64,
        }
    }
}

/// All mappings are total, with no unused source entries and no destination
/// collisions. Row IDs are unique within their destination table.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct IdMap {
    pub strings: BTreeMap<StringId, StringId>,
    pub definitions: BTreeMap<LayoutId, LayoutId>,
    pub tables: BTreeMap<TableId, TableId>,
    pub rows: BTreeMap<Reference, RowId>,
}

fn invalid(message: impl Into<String>) -> Error {
    Error::Invalid(message.into())
}

struct Budget<'a> {
    limits: &'a Limits,
    bytes: usize,
    items: usize,
}

impl<'a> Budget<'a> {
    fn new(limits: &'a Limits) -> Result<Self> {
        if limits.max_depth > 256 {
            return Err(Error::Limit("maximum supported depth is 256"));
        }
        Ok(Self {
            limits,
            bytes: 0,
            items: 0,
        })
    }
    fn items(&mut self, count: usize) -> Result<()> {
        self.items = self.items.checked_add(count).ok_or(Error::Limit("items"))?;
        if self.items > self.limits.max_items {
            return Err(Error::Limit("items"));
        }
        Ok(())
    }
    fn bytes(&mut self, count: usize) -> Result<()> {
        self.bytes = self.bytes.checked_add(count).ok_or(Error::Limit("bytes"))?;
        if self.bytes > self.limits.max_bytes {
            return Err(Error::Limit("bytes"));
        }
        Ok(())
    }
    fn text(&mut self, text: &str) -> Result<()> {
        self.bytes(text.len())
    }
    fn name(&mut self, name: &QualifiedName) -> Result<()> {
        if name.namespace.is_empty() || name.name.is_empty() {
            return Err(invalid(
                "qualified names must have nonempty namespace and name",
            ));
        }
        self.text(&name.namespace)?;
        self.text(&name.name)
    }
    fn revision(&mut self, revision: &str) -> Result<()> {
        if revision.is_empty() {
            return Err(invalid("empty revision"));
        }
        self.text(revision)
    }
    fn depth(&self, depth: usize) -> Result<()> {
        if depth > self.limits.max_depth {
            return Err(Error::Limit("depth"));
        }
        Ok(())
    }
}

impl Document {
    /// Validate structural facts only, without interpreting any vocabulary.
    /// Reference cycles are legal; inline nesting is bounded.
    pub fn validate(&self, limits: &Limits) -> Result<()> {
        let mut budget = Budget::new(limits)?;
        budget.items(self.strings.len())?;
        for string in self.strings.values() {
            budget.text(string)?;
        }
        budget.items(self.tables.len())?;
        let mut table_names = BTreeSet::new();
        for table in self.tables.values() {
            budget.name(&table.name)?;
            if !table_names.insert(&table.name) {
                return Err(invalid("duplicate table name"));
            }
        }
        budget.items(self.definitions.len())?;
        let mut identities = BTreeSet::new();
        for definition in self.definitions.values() {
            budget.name(&definition.name)?;
            budget.name(&definition.category)?;
            budget.revision(&definition.revision)?;
            if !identities.insert((&definition.name, &definition.revision)) {
                return Err(invalid("duplicate definition identity"));
            }
            budget.items(definition.fields.len())?;
            let mut fields = BTreeSet::new();
            for field in &definition.fields {
                budget.name(&field.name)?;
                if !fields.insert(&field.name) {
                    return Err(invalid("duplicate field name"));
                }
                if let Some(role) = &field.role {
                    budget.name(role)?;
                }
                self.validate_descriptor(&field.descriptor, 0, &mut budget)?;
            }
        }
        budget.items(self.profiles.len())?;
        let mut profiles = BTreeSet::new();
        for profile in &self.profiles {
            budget.name(&profile.name)?;
            budget.revision(&profile.revision)?;
            if !profiles.insert((&profile.name, &profile.revision)) {
                return Err(invalid("duplicate profile identity"));
            }
        }
        for table in self.tables.values() {
            // The wire groups rows by layout; count these directory entries too.
            budget.items(
                table
                    .rows
                    .values()
                    .map(|row| row.layout)
                    .collect::<BTreeSet<_>>()
                    .len(),
            )?;
            budget.items(table.rows.len())?;
            for record in table.rows.values() {
                self.validate_record(record, 0, &mut budget)?;
            }
        }
        Ok(())
    }

    fn validate_descriptor(
        &self,
        descriptor: &Descriptor,
        depth: usize,
        budget: &mut Budget<'_>,
    ) -> Result<()> {
        budget.depth(depth)?;
        budget.items(1)?;
        match descriptor {
            Descriptor::Ref(Some(table)) if !self.tables.contains_key(table) => {
                Err(invalid("descriptor references missing table"))
            }
            Descriptor::List(inner) => self.validate_descriptor(inner, depth + 1, budget),
            _ => Ok(()),
        }
    }

    fn validate_record(
        &self,
        record: &Record,
        depth: usize,
        budget: &mut Budget<'_>,
    ) -> Result<()> {
        budget.depth(depth)?;
        let definition = self
            .definitions
            .get(&record.layout)
            .ok_or_else(|| invalid("record references missing layout"))?;
        if record.fields.len() != definition.fields.len() {
            return Err(invalid("record field arity differs from layout"));
        }
        budget.items(record.fields.len())?;
        for (value, field) in record.fields.iter().zip(&definition.fields) {
            match value {
                None if field.required => return Err(invalid("required field absent")),
                None => {}
                Some(Value::Null) if !field.nullable => {
                    return Err(invalid("null in nonnullable field"))
                }
                Some(Value::Null) => {}
                Some(value) => self.validate_value(value, &field.descriptor, depth + 1, budget)?,
            }
        }
        Ok(())
    }

    fn validate_value(
        &self,
        value: &Value,
        descriptor: &Descriptor,
        depth: usize,
        budget: &mut Budget<'_>,
    ) -> Result<()> {
        budget.depth(depth)?;
        budget.items(1)?;
        match (value, descriptor) {
            (Value::Null, Descriptor::Null)
            | (Value::Bool(_), Descriptor::Bool)
            | (Value::Int64(_), Descriptor::Int64)
            | (Value::UInt64(_), Descriptor::UInt64)
            | (Value::Float64(_), Descriptor::Float64) => Ok(()),
            (Value::String(id), Descriptor::String) => {
                if !self.strings.contains_key(id) {
                    return Err(invalid("missing string reference"));
                }
                Ok(())
            }
            (Value::Bytes(bytes), Descriptor::Bytes) => budget.bytes(bytes.len()),
            (Value::Ref(reference), Descriptor::Ref(target)) => {
                if target.is_some_and(|table| table != reference.table) {
                    return Err(invalid("reference targets wrong table"));
                }
                if self.record(*reference).is_none() {
                    return Err(invalid("dangling row reference"));
                }
                Ok(())
            }
            (Value::List(values), Descriptor::List(inner)) => {
                budget.items(values.len())?;
                for value in values {
                    self.validate_value(value, inner, depth + 1, budget)?;
                }
                Ok(())
            }
            (Value::Record(record), Descriptor::Record) => {
                self.validate_record(record, depth + 1, budget)
            }
            _ => Err(invalid("value does not match descriptor")),
        }
    }

    pub fn record(&self, reference: Reference) -> Option<&Record> {
        self.tables.get(&reference.table)?.rows.get(&reference.row)
    }

    pub fn field<'a>(&self, record: &'a Record, name: &QualifiedName) -> Option<&'a Value> {
        let definition = self.definitions.get(&record.layout)?;
        let index = definition
            .fields
            .iter()
            .position(|field| &field.name == name)?;
        record.fields.get(index)?.as_ref()
    }

    /// Top-level records with the given definition name, across revisions.
    pub fn records_of<'a>(
        &'a self,
        name: &'a QualifiedName,
    ) -> impl Iterator<Item = (Reference, &'a Record)> + 'a {
        self.tables.iter().flat_map(move |(&table, rows)| {
            rows.rows.iter().filter_map(move |(&row, record)| {
                self.definitions
                    .get(&record.layout)
                    .filter(|definition| &definition.name == name)
                    .map(|_| (Reference { table, row }, record))
            })
        })
    }

    pub fn remap(&self, ids: &IdMap, limits: &Limits) -> Result<Document> {
        self.validate(limits)?;
        check_map(&self.strings, &ids.strings)?;
        check_map(&self.definitions, &ids.definitions)?;
        check_map(&self.tables, &ids.tables)?;
        let row_count: usize = self.tables.values().map(|table| table.rows.len()).sum();
        if ids.rows.len() != row_count {
            return Err(invalid("row mapping must be total with no extra keys"));
        }
        for (&table, data) in &self.tables {
            let mut targets = BTreeSet::new();
            for &row in data.rows.keys() {
                let target = ids
                    .rows
                    .get(&Reference { table, row })
                    .ok_or_else(|| invalid("missing row mapping"))?;
                if !targets.insert(target) {
                    return Err(invalid("colliding row mapping"));
                }
            }
        }
        let mapped = self.remap_unchecked(ids);
        mapped.validate(limits)?;
        Ok(mapped)
    }

    fn remap_unchecked(&self, ids: &IdMap) -> Document {
        Document {
            strings: self
                .strings
                .iter()
                .map(|(id, string)| (ids.strings[id], string.clone()))
                .collect(),
            definitions: self
                .definitions
                .iter()
                .map(|(id, definition)| {
                    (
                        ids.definitions[id],
                        remap_definition(definition, &ids.tables),
                    )
                })
                .collect(),
            profiles: self.profiles.clone(),
            tables: self
                .tables
                .iter()
                .map(|(&table, data)| {
                    (
                        ids.tables[&table],
                        Table {
                            name: data.name.clone(),
                            rows: data
                                .rows
                                .iter()
                                .map(|(&row, record)| {
                                    (
                                        ids.rows[&Reference { table, row }],
                                        remap_record(record, ids),
                                    )
                                })
                                .collect(),
                        },
                    )
                })
                .collect(),
        }
    }

    /// Merge records without coalescing row identities. Matching table names and
    /// compatible definition identities are shared; all row references are rewritten.
    pub fn merge(&self, other: &Document, limits: &Limits) -> Result<Document> {
        self.validate(limits)?;
        other.validate(limits)?;
        let mut result = self.clone();
        let mut ids = IdMap::default();
        let mut table_ids = IdAllocator::new(&self.tables);
        let mut string_ids = IdAllocator::new(&self.strings);
        let mut definition_ids = IdAllocator::new(&self.definitions);
        let mut table_names: BTreeMap<_, _> = self
            .tables
            .iter()
            .map(|(&id, table)| (&table.name, id))
            .collect();
        let mut string_values = BTreeMap::new();
        for (&id, value) in &self.strings {
            string_values.entry(value).or_insert(id);
        }
        let mut definition_names: BTreeMap<_, _> = self
            .definitions
            .iter()
            .map(|(&id, definition)| ((&definition.name, &definition.revision), id))
            .collect();
        let mut profiles: BTreeSet<_> = self
            .profiles
            .iter()
            .map(|profile| (&profile.name, &profile.revision))
            .collect();
        for (&id, table) in &other.tables {
            let target = match table_names.get(&table.name) {
                Some(&target) => target,
                None => {
                    let target = table_ids.allocate()?;
                    result.tables.insert(
                        target,
                        Table {
                            name: table.name.clone(),
                            rows: BTreeMap::new(),
                        },
                    );
                    table_names.insert(&table.name, target);
                    target
                }
            };
            ids.tables.insert(id, target);
        }
        for (&id, string) in &other.strings {
            let target = match string_values.get(string) {
                Some(&target) => target,
                None => {
                    let target = string_ids.allocate()?;
                    result.strings.insert(target, string.clone());
                    string_values.insert(string, target);
                    target
                }
            };
            ids.strings.insert(id, target);
        }
        for (&id, definition) in &other.definitions {
            let mapped = remap_definition(definition, &ids.tables);
            let identity = (&definition.name, &definition.revision);
            let target = if let Some(&id) = definition_names.get(&identity) {
                if result.definitions[&id] != mapped {
                    return Err(invalid("conflicting definition identity"));
                }
                id
            } else {
                let target = definition_ids.allocate()?;
                result.definitions.insert(target, mapped);
                definition_names.insert(identity, target);
                target
            };
            ids.definitions.insert(id, target);
        }
        for profile in &other.profiles {
            if profiles.insert((&profile.name, &profile.revision)) {
                result.profiles.push(profile.clone());
            }
        }
        // Reserve every row before copying values, so forward references and cycles work.
        for (&table, data) in &other.tables {
            let destination = &result.tables[&ids.tables[&table]].rows;
            let mut row_ids = IdAllocator::new(destination);
            for &row in data.rows.keys() {
                ids.rows
                    .insert(Reference { table, row }, row_ids.allocate()?);
            }
        }
        for (&table, data) in &other.tables {
            let destination = result
                .tables
                .get_mut(&ids.tables[&table])
                .expect("mapped table");
            for (&row, record) in &data.rows {
                destination.rows.insert(
                    ids.rows[&Reference { table, row }],
                    remap_record(record, &ids),
                );
            }
        }
        result.validate(limits)?;
        Ok(result)
    }
}

/// Append above the original maximum, then scan lower holes once. Borrow only
/// the original namespace: monotonically allocated IDs cannot collide with each
/// other, and `append_start` excludes the already allocated upper range on wrap.
struct IdAllocator<'a, T> {
    occupied: std::iter::Peekable<std::iter::Copied<std::collections::btree_map::Keys<'a, u32, T>>>,
    next: u64,
    append_start: u64,
    wrapped: bool,
    #[cfg(test)]
    inspected_source_keys: usize,
}

impl<'a, T> IdAllocator<'a, T> {
    fn new(map: &'a BTreeMap<u32, T>) -> Self {
        let next = map.last_key_value().map_or(0, |(&id, _)| u64::from(id) + 1);
        Self {
            occupied: map.keys().copied().peekable(),
            next,
            append_start: next,
            wrapped: false,
            #[cfg(test)]
            inspected_source_keys: 0,
        }
    }

    fn allocate(&mut self) -> Result<u32> {
        if self.next > u64::from(u32::MAX) && !self.wrapped {
            self.next = 0;
            self.wrapped = true;
        }
        if self.wrapped {
            while self.next < self.append_start {
                if self.occupied.peek().copied().map(u64::from) != Some(self.next) {
                    break;
                }
                self.occupied.next();
                #[cfg(test)]
                {
                    self.inspected_source_keys += 1;
                }
                self.next += 1;
            }
            if self.next >= self.append_start {
                return Err(invalid("ID space exhausted"));
            }
        }
        let id = u32::try_from(self.next).map_err(|_| invalid("ID space exhausted"))?;
        self.next += 1;
        Ok(id)
    }
}

fn check_map<T>(source: &BTreeMap<u32, T>, mapping: &BTreeMap<u32, u32>) -> Result<()> {
    if source.len() != mapping.len() || !source.keys().eq(mapping.keys()) {
        return Err(invalid("ID mapping must be total with no extra keys"));
    }
    if mapping.values().collect::<BTreeSet<_>>().len() != mapping.len() {
        return Err(invalid("colliding ID mapping"));
    }
    Ok(())
}

fn remap_descriptor(descriptor: &Descriptor, tables: &BTreeMap<u32, u32>) -> Descriptor {
    match descriptor {
        Descriptor::Ref(Some(table)) => Descriptor::Ref(Some(tables[table])),
        Descriptor::List(inner) => Descriptor::List(Box::new(remap_descriptor(inner, tables))),
        other => other.clone(),
    }
}

fn remap_definition(definition: &Definition, tables: &BTreeMap<u32, u32>) -> Definition {
    let mut definition = definition.clone();
    for field in &mut definition.fields {
        field.descriptor = remap_descriptor(&field.descriptor, tables);
    }
    definition
}

fn remap_record(record: &Record, ids: &IdMap) -> Record {
    Record {
        layout: ids.definitions[&record.layout],
        fields: record
            .fields
            .iter()
            .map(|field| field.as_ref().map(|value| remap_value(value, ids)))
            .collect(),
    }
}

fn remap_value(value: &Value, ids: &IdMap) -> Value {
    match value {
        Value::String(id) => Value::String(ids.strings[id]),
        Value::Ref(reference) => Value::Ref(Reference {
            table: ids.tables[&reference.table],
            row: ids.rows[reference],
        }),
        Value::List(values) => {
            Value::List(values.iter().map(|value| remap_value(value, ids)).collect())
        }
        Value::Record(record) => Value::Record(Box::new(remap_record(record, ids))),
        other => other.clone(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn name(value: &str) -> QualifiedName {
        QualifiedName::new("test.unknown", value)
    }

    fn fixture(table: u32, layout: u32) -> Document {
        let definition = Definition {
            name: name("Extension"),
            revision: "v1".into(),
            category: name("record"),
            fields: vec![
                Field {
                    name: name("references"),
                    descriptor: Descriptor::List(Box::new(Descriptor::Ref(Some(table)))),
                    required: true,
                    nullable: false,
                    role: None,
                },
                Field {
                    name: name("nested"),
                    descriptor: Descriptor::Record,
                    required: false,
                    nullable: true,
                    role: Some(name("opaque-role")),
                },
                Field {
                    name: name("text"),
                    descriptor: Descriptor::String,
                    required: false,
                    nullable: true,
                    role: None,
                },
            ],
        };
        let nested = Record {
            layout,
            fields: vec![
                Some(Value::List(vec![Value::Ref(Reference { table, row: 7 })])),
                None,
                Some(Value::String(2)),
            ],
        };
        Document {
            strings: BTreeMap::from([(2, "uninterpreted".into())]),
            definitions: BTreeMap::from([(layout, definition)]),
            profiles: vec![Profile {
                name: name("profile"),
                revision: "v1".into(),
            }],
            tables: BTreeMap::from([(
                table,
                Table {
                    name: name("records"),
                    rows: BTreeMap::from([(
                        7,
                        Record {
                            layout,
                            fields: vec![
                                Some(Value::List(vec![
                                    Value::Ref(Reference { table, row: 7 }),
                                    Value::Ref(Reference { table, row: 7 }),
                                ])),
                                Some(Value::Record(Box::new(nested))),
                                Some(Value::Null),
                            ],
                        },
                    )]),
                },
            )]),
        }
    }

    #[test]
    fn cyclic_references_and_nested_remapping_preserve_every_distinction() {
        let source = fixture(3, 9);
        let limits = Limits::default();
        source.validate(&limits).unwrap();
        let map = IdMap {
            strings: BTreeMap::from([(2, 80)]),
            definitions: BTreeMap::from([(9, 70)]),
            tables: BTreeMap::from([(3, 60)]),
            rows: BTreeMap::from([(Reference { table: 3, row: 7 }, 50)]),
        };
        let mapped = source.remap(&map, &limits).unwrap();
        assert_eq!(
            mapped.definitions[&70].fields[0].descriptor,
            Descriptor::List(Box::new(Descriptor::Ref(Some(60))))
        );
        let record = mapped.record(Reference { table: 60, row: 50 }).unwrap();
        assert_eq!(
            mapped.field(record, &name("references")),
            Some(&Value::List(vec![
                Value::Ref(Reference { table: 60, row: 50 }),
                Value::Ref(Reference { table: 60, row: 50 })
            ]))
        );
        let Some(Value::Record(nested)) = mapped.field(record, &name("nested")) else {
            panic!("nested record lost")
        };
        assert_eq!(nested.layout, 70);
        assert_eq!(nested.fields[1], None);
        assert_eq!(nested.fields[2], Some(Value::String(80)));
        assert_eq!(record.fields[2], Some(Value::Null));
        assert_eq!(mapped.records_of(&name("Extension")).count(), 1);
        let inverse = IdMap {
            strings: BTreeMap::from([(80, 2)]),
            definitions: BTreeMap::from([(70, 9)]),
            tables: BTreeMap::from([(60, 3)]),
            rows: BTreeMap::from([(Reference { table: 60, row: 50 }, 7)]),
        };
        assert_eq!(mapped.remap(&inverse, &limits).unwrap(), source);
    }

    #[test]
    fn merge_unifies_names_instead_of_local_ids_but_never_rows() {
        let left = fixture(3, 9);
        let right = fixture(23, 29);
        let merged = left.merge(&right, &Limits::default()).unwrap();
        assert_eq!(merged.definitions.len(), 1);
        assert_eq!(merged.tables.len(), 1);
        assert_eq!(merged.strings.len(), 1);
        assert_eq!(merged.profiles.len(), 1);
        let rows = &merged.tables[&3].rows;
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[&7], left.tables[&3].rows[&7]);
        assert_eq!(
            rows[&8].fields[0],
            Some(Value::List(vec![
                Value::Ref(Reference { table: 3, row: 8 }),
                Value::Ref(Reference { table: 3, row: 8 })
            ]))
        );
        let mut conflict = right;
        conflict.definitions.get_mut(&29).unwrap().fields[1].role = Some(name("different-role"));
        assert!(matches!(
            left.merge(&conflict, &Limits::default()),
            Err(Error::Invalid(_))
        ));
    }

    #[test]
    fn hole_allocator_visits_source_prefix_once_across_many_allocations() {
        let mut occupied: BTreeMap<_, _> = (0..4096).map(|id| (id, ())).collect();
        occupied.insert(u32::MAX, ());
        let mut allocator = IdAllocator::new(&occupied);
        for expected in 4096..8192 {
            assert_eq!(allocator.allocate().unwrap(), expected);
            assert_eq!(allocator.inspected_source_keys, 4096);
        }
        assert_eq!(allocator.occupied.peek(), Some(&u32::MAX));
    }

    #[test]
    fn allocator_appends_then_wraps_without_reusing_allocated_ids() {
        let occupied = BTreeMap::from([(0, ()), (4, ()), (u32::MAX - 1, ())]);
        let mut allocator = IdAllocator::new(&occupied);
        assert_eq!(allocator.allocate().unwrap(), u32::MAX);
        assert_eq!(allocator.inspected_source_keys, 0);
        assert_eq!(allocator.allocate().unwrap(), 1);
        assert_eq!(allocator.allocate().unwrap(), 2);
        assert_eq!(allocator.allocate().unwrap(), 3);
        assert_eq!(allocator.allocate().unwrap(), 5);
        assert_eq!(allocator.inspected_source_keys, 2);
        // At the upper boundary, both the final original ID and the appended
        // ID are unavailable. Exercise exhaustion without constructing 2^32 rows.
        allocator.next = u64::from(u32::MAX - 1);
        assert!(matches!(allocator.allocate(), Err(Error::Invalid(_))));
        assert_eq!(allocator.inspected_source_keys, 3);
        assert!(allocator.allocate().is_err());
        assert_eq!(allocator.inspected_source_keys, 3);
    }

    #[test]
    fn merge_reuses_holes_when_external_ids_reach_u32_max() {
        let source = fixture(3, 9);
        let maximum = IdMap {
            strings: BTreeMap::from([(2, u32::MAX)]),
            definitions: BTreeMap::from([(9, u32::MAX)]),
            tables: BTreeMap::from([(3, u32::MAX)]),
            rows: BTreeMap::from([(Reference { table: 3, row: 7 }, u32::MAX)]),
        };
        let left = source.remap(&maximum, &Limits::default()).unwrap();
        let merged = left.merge(&source, &Limits::default()).unwrap();
        assert_eq!(
            merged.tables[&u32::MAX].rows[&0].fields[0],
            Some(Value::List(vec![
                Value::Ref(Reference {
                    table: u32::MAX,
                    row: 0
                }),
                Value::Ref(Reference {
                    table: u32::MAX,
                    row: 0
                })
            ]))
        );
        let mut distinct = source;
        distinct.strings.insert(2, "different".into());
        distinct.tables.get_mut(&3).unwrap().name = name("other-table");
        distinct.definitions.get_mut(&9).unwrap().name = name("OtherExtension");
        let merged = left.merge(&distinct, &Limits::default()).unwrap();
        assert_eq!(merged.tables[&0].name, name("other-table"));
        assert_eq!(merged.definitions[&0].name, name("OtherExtension"));
        assert_eq!(merged.strings[&0], "different");
        assert_eq!(merged.tables[&0].rows[&0].layout, 0);
    }

    #[test]
    fn validation_rejects_dangling_and_mistyped_data() {
        let limits = Limits::default();
        let mut graph = fixture(3, 9);
        graph
            .tables
            .get_mut(&3)
            .unwrap()
            .rows
            .get_mut(&7)
            .unwrap()
            .fields[0] = Some(Value::List(vec![Value::Ref(Reference {
            table: 3,
            row: 8,
        })]));
        assert!(matches!(graph.validate(&limits), Err(Error::Invalid(_))));
        graph
            .tables
            .get_mut(&3)
            .unwrap()
            .rows
            .get_mut(&7)
            .unwrap()
            .fields[0] = Some(Value::UInt64(3));
        assert!(matches!(graph.validate(&limits), Err(Error::Invalid(_))));
        graph
            .tables
            .get_mut(&3)
            .unwrap()
            .rows
            .get_mut(&7)
            .unwrap()
            .fields[0] = None;
        assert!(matches!(graph.validate(&limits), Err(Error::Invalid(_))));
        graph
            .tables
            .get_mut(&3)
            .unwrap()
            .rows
            .get_mut(&7)
            .unwrap()
            .fields[0] = Some(Value::Null);
        assert!(matches!(graph.validate(&limits), Err(Error::Invalid(_))));
    }

    #[test]
    fn budgets_are_aggregate_and_depth_is_bounded() {
        let graph = fixture(3, 9);
        assert!(matches!(
            graph.validate(&Limits {
                max_bytes: 20,
                ..Limits::default()
            }),
            Err(Error::Limit("bytes"))
        ));
        assert!(matches!(
            graph.validate(&Limits {
                max_items: 10,
                ..Limits::default()
            }),
            Err(Error::Limit("items"))
        ));
        assert!(matches!(
            graph.validate(&Limits {
                max_depth: 1,
                ..Limits::default()
            }),
            Err(Error::Limit("depth"))
        ));
        assert!(matches!(
            graph.validate(&Limits {
                max_depth: 257,
                ..Limits::default()
            }),
            Err(Error::Limit(_))
        ));
    }

    #[test]
    fn item_and_depth_limits_match_codec_at_the_boundary() {
        let graph = fixture(3, 9);
        let minimum_items = (0..1000)
            .find(|&max_items| {
                graph
                    .validate(&Limits {
                        max_items,
                        ..Limits::default()
                    })
                    .is_ok()
            })
            .unwrap();
        let minimum_depth = (0..64)
            .find(|&max_depth| {
                graph
                    .validate(&Limits {
                        max_depth,
                        ..Limits::default()
                    })
                    .is_ok()
            })
            .unwrap();
        let limits = Limits {
            max_items: minimum_items,
            max_depth: minimum_depth,
            ..Limits::default()
        };
        let bytes = crate::encode(&graph, &limits).unwrap();
        assert_eq!(crate::decode(&bytes, &limits).unwrap(), graph);
        assert!(matches!(
            crate::decode(
                &bytes,
                &Limits {
                    max_items: minimum_items - 1,
                    ..limits.clone()
                }
            ),
            Err(Error::Limit("items"))
        ));
        assert!(matches!(
            crate::decode(
                &bytes,
                &Limits {
                    max_depth: minimum_depth - 1,
                    ..limits
                }
            ),
            Err(Error::Limit("depth"))
        ));
    }

    #[test]
    fn remapping_rejects_missing_extra_and_colliding_keys() {
        let graph = fixture(3, 9);
        assert!(graph.remap(&IdMap::default(), &Limits::default()).is_err());
        let mut graph = graph;
        graph.strings.insert(8, "other".into());
        let map = IdMap {
            strings: BTreeMap::from([(2, 2), (8, 2)]),
            definitions: BTreeMap::from([(9, 9)]),
            tables: BTreeMap::from([(3, 3)]),
            rows: BTreeMap::from([(Reference { table: 3, row: 7 }, 7)]),
        };
        assert!(graph.remap(&map, &Limits::default()).is_err());
    }
}

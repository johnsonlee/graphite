//! Loaded declarations use graph-local text IDs; mutation explicitly owns text.
use super::raw::{self, RawSignature, Signatures};
use super::TypeError;
use crate::strings::StringTable;
use hashbrown::HashTable;
use indexmap::{Equivalent, IndexMap};
use std::borrow::Cow;
use std::collections::hash_map::RandomState;
use std::collections::HashMap;
use std::hash::{BuildHasher, Hash, Hasher};
use std::num::NonZeroU32;
use std::ops::Range;
use std::sync::Arc;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TypeExpr<T = Arc<str>, S = T> {
    pub kind: T,
    pub name: T,
    pub scope: S,
    pub owner: Option<usize>,
    pub component: Option<usize>,
    pub variance: T,
    pub arguments: Vec<usize>,
}
/// Immutable wire rows retain only IDs and an exact-sized argument slice. The public
/// owning mutation model deliberately keeps usize references and growable vectors.
#[derive(Debug)]
pub(super) struct StoredType {
    pub(super) kind: usize,
    pub(super) name: usize,
    pub(super) scope: u64,
    owner: Option<NonZeroU32>,
    component: Option<NonZeroU32>,
    pub(super) variance: usize,
    pub(super) arguments: Box<[usize]>,
}
impl TryFrom<TypeExpr<usize, u64>> for StoredType {
    type Error = TypeError;

    fn try_from(value: TypeExpr<usize, u64>) -> Result<Self, Self::Error> {
        Ok(Self {
            kind: value.kind,
            name: value.name,
            scope: value.scope,
            owner: Self::pack(value.owner)?,
            component: Self::pack(value.component)?,
            variance: value.variance,
            arguments: value.arguments.into_boxed_slice(),
        })
    }
}
impl StoredType {
    fn pack(id: Option<usize>) -> Result<Option<NonZeroU32>, TypeError> {
        id.map(|id| {
            u32::try_from(id)
                .ok()
                .filter(|id| *id <= i32::MAX as u32)
                .and_then(|id| id.checked_add(1))
                .and_then(NonZeroU32::new)
                .ok_or_else(|| TypeError("type reference exceeds signed wire ID range".into()))
        })
        .transpose()
    }

    pub(super) fn owner(&self) -> Option<usize> {
        self.owner.map(|id| {
            usize::try_from(id.get() - 1).expect("wire reference fits the target pointer width")
        })
    }

    pub(super) fn component(&self) -> Option<usize> {
        self.component.map(|id| {
            usize::try_from(id.get() - 1).expect("wire reference fits the target pointer width")
        })
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TypeParameter<T = Arc<str>, S = T> {
    pub name: T,
    pub scope: S,
    pub bounds: Vec<usize>,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MethodTypes<T = Arc<str>, S = T> {
    pub parameters: Vec<usize>,
    pub returns: usize,
    pub type_parameters: Vec<TypeParameter<T, S>>,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ClassTypes<T = Arc<str>, S = T> {
    pub type_parameters: Vec<TypeParameter<T, S>>,
    pub superclass: Option<usize>,
    pub interfaces: Vec<usize>,
}
pub type MemberKey = (Arc<str>, Arc<str>, Arc<str>);

/// An explicitly mutable, owning declaration model. Loaded graphs do not keep it.
#[derive(Clone, Default, Debug, PartialEq, Eq)]
pub struct MutableDeclaredTypes {
    pub types: Vec<TypeExpr>,
    pub fields: IndexMap<MemberKey, usize>,
    pub methods: IndexMap<MemberKey, MethodTypes>,
    pub classes: IndexMap<Arc<str>, ClassTypes>,
}

pub(super) trait Texts {
    fn text(&self, id: usize) -> &str;
}
#[derive(Debug)]
pub(super) enum TextStore {
    Shared(StringTable),
    Owned(Vec<Arc<str>>),
}
impl Default for TextStore {
    fn default() -> Self {
        Self::Owned(Vec::new())
    }
}
impl Texts for TextStore {
    fn text(&self, id: usize) -> &str {
        match self {
            Self::Shared(s) => s.get(id),
            Self::Owned(s) => &s[id],
        }
    }
}
impl TextStore {
    fn shared(&self) -> Option<&StringTable> {
        match self {
            Self::Shared(s) => Some(s),
            Self::Owned(_) => None,
        }
    }
}

/// The byte length of the erased descriptor `text` starts with, `None` when it starts with none.
fn raw_descriptor_length(text: &str) -> Option<usize> {
    let bytes = text.as_bytes();
    let mut position = 0;
    while bytes.get(position) == Some(&b'[') {
        position += 1;
    }
    match bytes.get(position)? {
        b'L' => Some(position + text[position..].find(';')? + 1),
        b'Z' | b'B' | b'C' | b'S' | b'I' | b'J' | b'F' | b'D' | b'V' => Some(position + 1),
        _ => None,
    }
}

/// Narrow a dictionary ID, a type row or an entry position for storage: the wire carries
/// every one of them as 32 bits, so a loaded table never holds a wider value.
fn narrow(id: usize) -> u32 {
    u32::try_from(id).expect("wire ID fits 32 bits")
}
#[derive(Debug, PartialEq, Eq)]
struct StoredKey<const N: usize> {
    ids: [u32; N],
}
impl<const N: usize> StoredKey<N> {
    fn new(ids: [usize; N]) -> Self {
        Self {
            ids: ids.map(narrow),
        }
    }
    fn ids(&self) -> [usize; N] {
        self.ids.map(|id| id as usize)
    }
}
#[derive(Clone, Copy)]
enum Descriptors<'a> {
    Legacy,
    Fields(&'a [StoredType]),
    Methods(&'a [StoredType], &'a Signatures),
}
#[derive(Clone, Copy)]
struct KeyContext<'a> {
    texts: &'a dyn Texts,
    descriptors: Descriptors<'a>,
}
impl<'a> KeyContext<'a> {
    fn legacy(texts: &'a dyn Texts) -> Self {
        Self {
            texts,
            descriptors: Descriptors::Legacy,
        }
    }
    fn text(self, position: usize, id: usize) -> Cow<'a, str> {
        if position == 2 {
            match self.descriptors {
                Descriptors::Fields(types) => {
                    return Cow::Owned(raw::type_descriptor(types, self.texts, id))
                }
                Descriptors::Methods(types, signatures) => {
                    return Cow::Owned(raw::signature_descriptor(
                        types,
                        self.texts,
                        signatures.get(id),
                    ))
                }
                Descriptors::Legacy => {}
            }
        }
        Cow::Borrowed(self.texts.text(id))
    }
    fn equals(self, position: usize, id: usize, value: &str) -> bool {
        if position == 2 {
            match self.descriptors {
                Descriptors::Fields(types) => {
                    return raw::type_bytes(types, self.texts, id).eq(value.bytes())
                }
                Descriptors::Methods(types, signatures) => {
                    return raw::signature_bytes(types, self.texts, signatures.get(id))
                        .eq(value.bytes())
                }
                Descriptors::Legacy => {}
            }
        }
        self.texts.text(id) == value
    }
    fn equal_keys<const N: usize>(self, first: [usize; N], second: [usize; N]) -> bool {
        first
            .into_iter()
            .zip(second)
            .enumerate()
            .all(|(position, (a, b))| {
                if a == b {
                    return true;
                }
                if position == 2 {
                    match self.descriptors {
                        Descriptors::Fields(types) => {
                            return raw::type_bytes(types, self.texts, a)
                                .eq(raw::type_bytes(types, self.texts, b))
                        }
                        Descriptors::Methods(types, signatures) => {
                            return raw::signature_bytes(types, self.texts, signatures.get(a))
                                .eq(raw::signature_bytes(types, self.texts, signatures.get(b)))
                        }
                        Descriptors::Legacy => {}
                    }
                }
                self.texts.text(a) == self.texts.text(b)
            })
    }
    fn hash<const N: usize>(self, ids: [usize; N], state: &RandomState) -> u64 {
        let mut hash = state.build_hasher();
        N.hash(&mut hash);
        for (position, id) in ids.into_iter().enumerate() {
            if position == 2 {
                match self.descriptors {
                    Descriptors::Fields(types) => {
                        raw::hash_bytes(&mut hash, raw::type_bytes(types, self.texts, id));
                        continue;
                    }
                    Descriptors::Methods(types, signatures) => {
                        raw::hash_bytes(
                            &mut hash,
                            raw::signature_bytes(types, self.texts, signatures.get(id)),
                        );
                        continue;
                    }
                    Descriptors::Legacy => {}
                }
            }
            raw::hash_text(&mut hash, self.texts.text(id));
        }
        hash.finish()
    }
    fn values<const N: usize>(self, ids: [usize; N]) -> [Cow<'a, str>; N] {
        std::array::from_fn(|index| self.text(index, ids[index]))
    }
}
struct Lookup<'a, const N: usize> {
    fingerprint: u64,
    values: [&'a str; N],
    context: KeyContext<'a>,
}
impl<const N: usize> Equivalent<StoredKey<N>> for Lookup<'_, N> {
    fn equivalent(&self, key: &StoredKey<N>) -> bool {
        self.values
            .iter()
            .zip(key.ids())
            .enumerate()
            .all(|(position, (value, id))| self.context.equals(position, id, value))
    }
}

/// File-ordered entries with an index containing only entry positions. Hashes are
/// computed from complete text values and recomputed only when the index grows.
#[derive(Debug)]
struct MemberIndex<const N: usize, V> {
    entries: Vec<(StoredKey<N>, V)>,
    positions: HashTable<u32>,
}
impl<const N: usize, V> Default for MemberIndex<N, V> {
    fn default() -> Self {
        Self {
            entries: Vec::new(),
            positions: HashTable::new(),
        }
    }
}
impl<const N: usize, V> MemberIndex<N, V> {
    fn reserve_initial(&mut self, count: usize) -> Result<(), String> {
        if !self.entries.is_empty() {
            return Err("initial member capacity requested after insertion".into());
        }
        self.entries
            .try_reserve_exact(count)
            .map_err(|error| error.to_string())?;
        self.positions
            .try_reserve(count, |_| unreachable!("initial index is empty"))
            .map_err(|error| error.to_string())
    }
    fn len(&self) -> usize {
        self.entries.len()
    }
    fn get_index(&self, index: usize) -> Option<(&StoredKey<N>, &V)> {
        self.entries.get(index).map(|(key, value)| (key, value))
    }
    fn iter(&self) -> impl ExactSizeIterator<Item = (&StoredKey<N>, &V)> {
        self.entries.iter().map(|(key, value)| (key, value))
    }
    fn get(&self, key: &Lookup<'_, N>) -> Option<&V> {
        self.positions
            .find(key.fingerprint, |index| {
                key.equivalent(&self.entries[*index as usize].0)
            })
            .map(|index| &self.entries[*index as usize].1)
    }
    fn contains_ids(&self, ids: [usize; N], fingerprint: u64, context: KeyContext<'_>) -> bool {
        self.positions
            .find(fingerprint, |index| {
                context.equal_keys(ids, self.entries[*index as usize].0.ids())
            })
            .is_some()
    }
    fn contains_key(&self, key: &Lookup<'_, N>) -> bool {
        self.get(key).is_some()
    }
    fn insert_unique(
        &mut self,
        ids: [usize; N],
        value: V,
        fingerprint: u64,
        rehash: impl Fn(&StoredKey<N>) -> u64,
    ) {
        let index = narrow(self.entries.len());
        self.entries.push((StoredKey::new(ids), value));
        let entries = &self.entries;
        self.positions.insert_unique(fingerprint, index, |index| {
            rehash(&entries[*index as usize].0)
        });
    }
    #[cfg(test)]
    fn values(&self) -> impl ExactSizeIterator<Item = &V> {
        self.entries.iter().map(|(_, value)| value)
    }
}

/// Hashing and equality of shared-dictionary member keys, borrowing only what they need so
/// an index can be grown while the table's other fields are read.
#[derive(Clone, Copy)]
struct IdKeys<'a> {
    fingerprints: &'a RandomState,
    canonical: &'a [u32],
    descriptorless: bool,
    method: bool,
}
impl IdKeys<'_> {
    fn canonical_id(&self, position: usize, id: usize) -> usize {
        if position == 2 && self.descriptorless && !self.method {
            self.canonical[id] as usize
        } else {
            id
        }
    }
    fn fingerprint<const N: usize>(&self, ids: [usize; N]) -> u64 {
        let mut hash = self.fingerprints.build_hasher();
        N.hash(&mut hash);
        for (position, id) in ids.into_iter().enumerate() {
            self.canonical_id(position, id).hash(&mut hash);
        }
        hash.finish()
    }
    fn equal<const N: usize>(&self, a: [usize; N], b: [usize; N]) -> bool {
        (0..N).all(|position| {
            self.canonical_id(position, a[position]) == self.canonical_id(position, b[position])
        })
    }
}

/// Used only by the owning mutation model, never by the compact load path.
pub(super) struct BorrowedMemberKey<'a>(pub &'a str, pub &'a str, pub &'a str);
impl Hash for BorrowedMemberKey<'_> {
    fn hash<H: Hasher>(&self, state: &mut H) {
        (self.0, self.1, self.2).hash(state);
    }
}
impl Equivalent<MemberKey> for BorrowedMemberKey<'_> {
    fn equivalent(&self, key: &MemberKey) -> bool {
        self.0 == key.0.as_ref() && self.1 == key.1.as_ref() && self.2 == key.2.as_ref()
    }
}

#[derive(Debug, Default)]
pub(super) struct CompactTable {
    pub(super) texts: TextStore,
    // GTY04+ stores enum codes instead of string IDs and packs each scope as
    // (declaration row << 3) | scope tag. Legacy tables retain string IDs.
    pub(super) structural: bool,
    pub(super) descriptorless: bool,
    /// A shared-dictionary table (GTY03+) keys its members by string ID: `graph.strings`
    /// is a sorted set, so two IDs are equal exactly when their texts are, and the nodes
    /// of the graph carry the same IDs. Binding a node then costs three integer probes
    /// instead of a descriptor string, three text hashes and a text comparison per row,
    /// which is what every `keys(n)`, `RETURN n` and load-time summary paid before.
    pub(super) shared_ids: bool,
    pub(super) signatures: Signatures,
    pub(super) types: Vec<StoredType>,
    // Private keys: inserts and lookups compare full actual text values, through the
    // IDs that stand for them in a shared-dictionary table.
    fields: MemberIndex<3, u32>,
    methods: MemberIndex<3, StoredMethod>,
    method_parameters: Vec<u32>,
    method_formals: Vec<TypeParameter<usize, u64>>,
    classes: MemberIndex<1, ClassTypes<usize, u64>>,
    fingerprints: RandomState,
    text_summary: Option<super::text_summary::TextSummary>,
    /// Per type row, the first row spelling the same erased descriptor (`u32::MAX` for
    /// a row no member or signature uses as an erased type). A writer may store one
    /// erased type at several IDs; keys compare the canonical row, as text would.
    raw_canonical: Vec<u32>,
    raw_text_index: HashTable<u32>,
    /// Signature rows by their canonical parameter and return rows, for binding a
    /// method descriptor without rendering it.
    signature_rows: HashTable<u32>,
    /// Canonical erased row by the string ID of its Java type name (`java.util.List`,
    /// `int[]`), the spelling the graph's nodes use.
    raw_by_name: HashMap<u32, u32>,
}
#[derive(Debug)]
pub(super) struct StoredMethod {
    parameters: Range<u32>,
    returns: u32,
    type_parameters: Range<u32>,
}
impl StoredMethod {
    fn parameters(&self) -> Range<usize> {
        self.parameters.start as usize..self.parameters.end as usize
    }
    fn returns(&self) -> usize {
        self.returns as usize
    }
    fn formals(&self) -> Range<usize> {
        self.type_parameters.start as usize..self.type_parameters.end as usize
    }
}
impl CompactTable {
    /// Only validated immutable tables publish a negative summary. Each atom is
    /// visited once; generated scope bytes are streamed across descriptor joins.
    pub(super) fn finish_text_summary(&mut self) {
        use super::text_summary::Builder;
        self.text_summary = None;
        let mut builder = Builder::new();
        for t in &self.types {
            for text in [
                self.kind(t.kind),
                self.name(t.name),
                self.variance(t.variance),
            ] {
                builder.begin_text();
                if !builder.add_bytes(text.bytes()) {
                    return;
                }
            }
            builder.begin_text();
            if !self.add_scope_summary(t.scope, &mut builder) {
                return;
            }
        }
        self.text_summary = Some(builder.finish());
    }
    fn add_scope_summary(&self, scope: u64, builder: &mut super::text_summary::Builder) -> bool {
        if !self.structural {
            return builder.add_bytes(self.texts.text(scope as usize).bytes());
        }
        let tag = scope & 7;
        if tag == 0 {
            return true;
        }
        let row = (scope >> 3) as usize;
        if tag >= 3 && !builder.add_bytes(b"unresolved:".iter().copied()) {
            return false;
        }
        if tag == 1 || tag == 3 {
            let (key, _) = self.classes.get_index(row).expect("validated class scope");
            builder.add_bytes(b"class:".iter().copied())
                && builder.add_bytes(self.texts.text(key.ids()[0]).bytes())
        } else {
            let (key, _) = self.methods.get_index(row).expect("validated method scope");
            if !(builder.add_bytes(b"method:".iter().copied())
                && builder.add_bytes(self.texts.text(key.ids()[0]).bytes())
                && builder.add_bytes(b"#".iter().copied())
                && builder.add_bytes(self.texts.text(key.ids()[1]).bytes()))
            {
                return false;
            }
            if self.descriptorless {
                builder.add_bytes(raw::signature_bytes(
                    &self.types,
                    &self.texts,
                    self.signatures.get(key.ids()[2]),
                ))
            } else {
                builder.add_bytes(self.texts.text(key.ids()[2]).bytes())
            }
        }
    }
    fn kind(&self, id: usize) -> &str {
        if self.structural {
            ["class", "primitive", "array", "variable", "wildcard"][id]
        } else {
            self.texts.text(id)
        }
    }
    fn variance(&self, id: usize) -> &str {
        if self.structural {
            ["", "extends", "super", "unbounded"][id]
        } else {
            self.texts.text(id)
        }
    }
    fn name(&self, id: usize) -> &str {
        if self.structural && id == usize::MAX {
            ""
        } else {
            self.texts.text(id)
        }
    }
    fn scope_length(&self, scope: u64) -> usize {
        if !self.structural {
            return self.texts.text(scope as usize).len();
        }
        let tag = scope & 7;
        if tag == 0 {
            return 0;
        }
        let row = (scope >> 3) as usize;
        let prefix = if tag >= 3 { "unresolved:".len() } else { 0 };
        if tag == 1 || tag == 3 {
            let (key, _) = self
                .classes
                .get_index(row)
                .expect("validated class scope row");
            prefix + "class:".len() + self.texts.text(key.ids()[0]).len()
        } else {
            let (key, _) = self
                .methods
                .get_index(row)
                .expect("validated method scope row");
            let descriptor_length = if self.descriptorless {
                raw::signature_descriptor_length(
                    &self.types,
                    &self.texts,
                    self.signatures.get(key.ids()[2]),
                )
            } else {
                self.texts.text(key.ids()[2]).len()
            };
            (prefix + "method:#".len())
                .saturating_add(self.texts.text(key.ids()[0]).len())
                .saturating_add(self.texts.text(key.ids()[1]).len())
                .saturating_add(descriptor_length)
        }
    }
    fn scope(&self, scope: u64) -> Cow<'_, str> {
        if !self.structural {
            return Cow::Borrowed(self.texts.text(scope as usize));
        }
        let tag = scope & 7;
        if tag == 0 {
            return Cow::Borrowed("");
        }
        let row = (scope >> 3) as usize;
        let prefix = if tag >= 3 { "unresolved:" } else { "" };
        if tag == 1 || tag == 3 {
            let (key, _) = self
                .classes
                .get_index(row)
                .expect("validated class scope row");
            Cow::Owned(format!("{prefix}class:{}", self.texts.text(key.ids()[0])))
        } else {
            let (key, _) = self
                .methods
                .get_index(row)
                .expect("validated method scope row");
            Cow::Owned(format!(
                "{prefix}method:{}#{}{}",
                self.texts.text(key.ids()[0]),
                self.texts.text(key.ids()[1]),
                self.key_context(true).text(2, key.ids()[2])
            ))
        }
    }
    pub(super) fn validate_scopes(&self) -> Result<(), super::TypeError> {
        if !self.structural {
            return Ok(());
        }
        for scope in self
            .types
            .iter()
            .map(|t| t.scope)
            .chain(self.method_formals.iter().map(|p| p.scope))
            .chain(
                self.classes
                    .iter()
                    .flat_map(|(_, c)| c.type_parameters.iter().map(|p| p.scope)),
            )
        {
            let tag = scope & 7;
            let row = scope >> 3;
            let valid = match tag {
                0 => scope == 0,
                1 | 3 => row < self.classes.len() as u64,
                2 | 4 => row < self.methods.len() as u64,
                _ => false,
            };
            if !valid {
                return Err(super::TypeError(
                    "invalid declaration scope reference".into(),
                ));
            }
        }
        Ok(())
    }
    pub(super) fn reserve_fields(&mut self, n: usize) -> Result<(), String> {
        self.fields.reserve_initial(n)
    }
    pub(super) fn reserve_methods(&mut self, n: usize) -> Result<(), String> {
        self.methods.reserve_initial(n)
    }
    pub(super) fn reserve_classes(&mut self, n: usize) -> Result<(), String> {
        self.classes.reserve_initial(n)
    }
    fn insert<const N: usize, V>(
        map: &mut MemberIndex<N, V>,
        state: &RandomState,
        texts: &dyn Texts,
        ids: [usize; N],
        value: V,
    ) -> bool {
        let fingerprint = KeyContext::legacy(texts).hash(ids, state);
        Self::insert_fingerprinted(map, texts, ids, value, fingerprint, |key| {
            KeyContext::legacy(texts).hash(key.ids(), state)
        })
    }
    fn insert_fingerprinted<const N: usize, V>(
        map: &mut MemberIndex<N, V>,
        texts: &dyn Texts,
        ids: [usize; N],
        value: V,
        fingerprint: u64,
        rehash: impl Fn(&StoredKey<N>) -> u64,
    ) -> bool {
        let lookup = Lookup {
            fingerprint,
            values: ids.map(|id| texts.text(id)),
            context: KeyContext::legacy(texts),
        };
        if map.contains_key(&lookup) {
            return false;
        }
        map.insert_unique(ids, value, fingerprint, rehash);
        true
    }
    pub(super) fn insert_field(
        &mut self,
        texts: &dyn Texts,
        ids: [usize; 3],
        value: usize,
    ) -> bool {
        if self.shared_ids {
            if self.descriptorless {
                self.canonical_raw(texts, ids[2]);
            }
            let fingerprint = self.id_fingerprint(ids, false);
            if self.field_by_ids(ids[0], ids[1], ids[2]).is_some() {
                return false;
            }
            let keys = IdKeys {
                fingerprints: &self.fingerprints,
                canonical: &self.raw_canonical,
                descriptorless: self.descriptorless,
                method: false,
            };
            self.fields
                .insert_unique(ids, narrow(value), fingerprint, |key| {
                    keys.fingerprint(key.ids())
                });
            return true;
        }
        let context = KeyContext {
            texts,
            descriptors: if self.descriptorless {
                Descriptors::Fields(&self.types)
            } else {
                Descriptors::Legacy
            },
        };
        let fingerprint = context.hash(ids, &self.fingerprints);
        if self.fields.contains_ids(ids, fingerprint, context) {
            return false;
        }
        self.fields
            .insert_unique(ids, narrow(value), fingerprint, |key| {
                context.hash(key.ids(), &self.fingerprints)
            });
        true
    }
    pub(super) fn insert_method(
        &mut self,
        texts: &dyn Texts,
        ids: [usize; 3],
        value: MethodTypes<usize, u64>,
    ) -> bool {
        if self.shared_ids {
            let fingerprint = self.id_fingerprint(ids, true);
            if self.method_by_ids(ids[0], ids[1], ids[2]).is_some() {
                return false;
            }
            let stored = self.store_method(value);
            let keys = IdKeys {
                fingerprints: &self.fingerprints,
                canonical: &self.raw_canonical,
                descriptorless: self.descriptorless,
                method: true,
            };
            self.methods
                .insert_unique(ids, stored, fingerprint, |key| keys.fingerprint(key.ids()));
            return true;
        }
        let context = KeyContext {
            texts,
            descriptors: if self.descriptorless {
                Descriptors::Methods(&self.types, &self.signatures)
            } else {
                Descriptors::Legacy
            },
        };
        let fingerprint = context.hash(ids, &self.fingerprints);
        if self.methods.contains_ids(ids, fingerprint, context) {
            return false;
        }
        let stored = self.store_method(value);
        let context = KeyContext {
            texts,
            descriptors: if self.descriptorless {
                Descriptors::Methods(&self.types, &self.signatures)
            } else {
                Descriptors::Legacy
            },
        };
        let fingerprints = &self.fingerprints;
        self.methods.insert_unique(ids, stored, fingerprint, |key| {
            context.hash(key.ids(), fingerprints)
        });
        true
    }
    fn store_method(&mut self, value: MethodTypes<usize, u64>) -> StoredMethod {
        let parameter_start = self.method_parameters.len();
        self.method_parameters
            .extend(value.parameters.into_iter().map(narrow));
        let formal_start = self.method_formals.len();
        self.method_formals.extend(value.type_parameters);
        StoredMethod {
            parameters: narrow(parameter_start)..narrow(self.method_parameters.len()),
            returns: narrow(value.returns),
            type_parameters: narrow(formal_start)..narrow(self.method_formals.len()),
        }
    }
    pub(super) fn finish_method_buffers(&mut self) {
        self.method_parameters.shrink_to_fit();
        self.method_formals.shrink_to_fit();
    }
    pub(super) fn insert_class(
        &mut self,
        texts: &dyn Texts,
        id: usize,
        value: ClassTypes<usize, u64>,
    ) -> bool {
        if self.shared_ids {
            let fingerprint = self.id_fingerprint([id], false);
            if self.class_by_id(id).is_some() {
                return false;
            }
            let keys = IdKeys {
                fingerprints: &self.fingerprints,
                canonical: &self.raw_canonical,
                descriptorless: self.descriptorless,
                method: false,
            };
            self.classes
                .insert_unique([id], value, fingerprint, |key| keys.fingerprint(key.ids()));
            return true;
        }
        Self::insert(&mut self.classes, &self.fingerprints, texts, [id], value)
    }
    pub(super) fn class_by_id(&self, id: usize) -> Option<&ClassTypes<usize, u64>> {
        let fingerprint = self.id_fingerprint([id], false);
        self.classes
            .positions
            .find(fingerprint, |&index| {
                self.classes.entries[index as usize].0.ids() == [id]
            })
            .map(|&index| &self.classes.entries[index as usize].1)
    }
    fn lookup<'a, const N: usize>(&'a self, values: [&'a str; N], method: bool) -> Lookup<'a, N> {
        Lookup {
            fingerprint: {
                let mut hash = self.fingerprints.build_hasher();
                N.hash(&mut hash);
                for value in values {
                    raw::hash_text(&mut hash, value);
                }
                hash.finish()
            },
            values,
            context: self.key_context(method),
        }
    }

    // ---- Shared-dictionary member keys -----------------------------------------------
    //
    // The erased position of a key is a raw type row (fields) or a signature row
    // (methods). Signature rows are unique by text, which the parser enforces; raw rows
    // are not, so a key hashes and compares its field's canonical raw row.

    const NOT_RAW: u32 = u32::MAX;

    fn id_keys(&self, method: bool) -> IdKeys<'_> {
        IdKeys {
            fingerprints: &self.fingerprints,
            canonical: &self.raw_canonical,
            descriptorless: self.descriptorless,
            method,
        }
    }
    fn id_fingerprint<const N: usize>(&self, ids: [usize; N], method: bool) -> u64 {
        self.id_keys(method).fingerprint(ids)
    }
    fn ids_equal<const N: usize>(&self, a: [usize; N], b: [usize; N], method: bool) -> bool {
        self.id_keys(method).equal(a, b)
    }
    /// The first row rendering the same erased descriptor as `row`, assigning it on first sight.
    pub(super) fn canonical_raw(&mut self, texts: &dyn Texts, row: usize) -> usize {
        if self.raw_canonical.len() < self.types.len() {
            self.raw_canonical.resize(self.types.len(), Self::NOT_RAW);
        }
        if self.raw_canonical[row] != Self::NOT_RAW {
            return self.raw_canonical[row] as usize;
        }
        let types = &self.types;
        let mut hash = self.fingerprints.build_hasher();
        raw::hash_bytes(&mut hash, raw::type_bytes(types, texts, row));
        let fingerprint = hash.finish();
        let canonical = match self.raw_text_index.find(fingerprint, |&other| {
            raw::type_bytes(types, texts, other as usize).eq(raw::type_bytes(types, texts, row))
        }) {
            Some(&other) => other as usize,
            None => {
                let fingerprints = &self.fingerprints;
                self.raw_text_index
                    .insert_unique(fingerprint, narrow(row), |&other| {
                        let mut hash = fingerprints.build_hasher();
                        raw::hash_bytes(&mut hash, raw::type_bytes(types, texts, other as usize));
                        hash.finish()
                    });
                row
            }
        };
        self.raw_canonical[row] = canonical as u32;
        canonical
    }
    fn signature_fingerprint(&self, rows: impl Iterator<Item = usize>, returns: usize) -> u64 {
        let mut hash = self.fingerprints.build_hasher();
        for row in rows {
            row.hash(&mut hash);
        }
        usize::MAX.hash(&mut hash);
        returns.hash(&mut hash);
        hash.finish()
    }
    /// Index the parsed signatures by canonical rows; must run before members are inserted.
    pub(super) fn index_signature_rows(&mut self, texts: &dyn Texts) {
        for index in 0..self.signatures.len() {
            let signature = self.signatures.get(index);
            let rows: Vec<usize> = signature
                .parameters
                .iter()
                .map(|&row| row as usize)
                .chain(std::iter::once(signature.returns))
                .collect();
            for row in rows {
                self.canonical_raw(texts, row);
            }
        }
        let signatures = &self.signatures;
        let canonical = &self.raw_canonical;
        let canon = |row: usize| canonical[row] as usize;
        let fingerprints = &self.fingerprints;
        let fingerprint = |signature: RawSignature<'_>| {
            let mut hash = fingerprints.build_hasher();
            for &row in signature.parameters {
                canon(row as usize).hash(&mut hash);
            }
            usize::MAX.hash(&mut hash);
            canon(signature.returns).hash(&mut hash);
            hash.finish()
        };
        self.signature_rows = HashTable::with_capacity(signatures.len());
        for index in 0..signatures.len() {
            // Text uniqueness was enforced by the parser; equal canonical rows would be
            // equal text, so the entry is unique.
            self.signature_rows.insert_unique(
                fingerprint(signatures.get(index)),
                narrow(index),
                |&other| fingerprint(signatures.get(other as usize)),
            );
        }
    }
    /// Map the graph's spelling of each erased type (its Java type name) to the canonical
    /// raw row. Runs once the members are known, over the rows they reference.
    pub(super) fn finish_id_index(&mut self) {
        let Some(strings) = self.texts.shared() else {
            return;
        };
        let mut by_name = HashMap::new();
        for row in 0..self.raw_canonical.len() {
            if self.raw_canonical[row] != row as u32 {
                continue;
            }
            let mut dimensions = 0usize;
            let mut base = row;
            while self.types[base].kind == 2 {
                dimensions += 1;
                base = self.types[base].component().expect("validated array");
            }
            let t = &self.types[base];
            let name = self.texts.text(t.name);
            // A class spelled like a primitive can never be the type a node names:
            // `push_type_descriptor` reads that word as the primitive.
            if t.kind == 0 && raw::primitive_descriptor(name).is_some() {
                continue;
            }
            let sid = if dimensions == 0 {
                Some(t.name)
            } else {
                let mut spelled = String::with_capacity(name.len() + 2 * dimensions);
                spelled.push_str(name);
                for _ in 0..dimensions {
                    spelled.push_str("[]");
                }
                strings.index_of(&spelled)
            };
            if let Some(sid) = sid {
                by_name.entry(narrow(sid)).or_insert(narrow(row));
            }
        }
        self.raw_by_name = by_name;
    }
    /// The canonical erased row of the type a node spells by the string `name_sid`.
    pub(super) fn raw_row_of_type_name(&self, name_sid: usize) -> Option<usize> {
        u32::try_from(name_sid)
            .ok()
            .and_then(|sid| self.raw_by_name.get(&sid))
            .map(|&row| row as usize)
    }
    pub(super) fn field_by_ids(&self, owner: usize, name: usize, raw: usize) -> Option<usize> {
        let ids = [owner, name, raw];
        let fingerprint = self.id_fingerprint(ids, false);
        self.fields
            .positions
            .find(fingerprint, |&index| {
                self.ids_equal(ids, self.fields.entries[index as usize].0.ids(), false)
            })
            .map(|&index| self.fields.entries[index as usize].1 as usize)
    }
    /// The signature row of a method whose erased parameter and return types are the rows given.
    pub(super) fn signature_row_of(
        &self,
        parameters: impl Iterator<Item = usize> + Clone,
        returns: usize,
    ) -> Option<usize> {
        let fingerprint = self.signature_fingerprint(parameters.clone(), returns);
        let canonical = &self.raw_canonical;
        self.signature_rows
            .find(fingerprint, |&index| {
                let signature = self.signatures.get(index as usize);
                canonical[signature.returns] as usize == returns
                    && signature.parameters.len() == parameters.clone().count()
                    && signature
                        .parameters
                        .iter()
                        .zip(parameters.clone())
                        .all(|(&row, expected)| canonical[row as usize] as usize == expected)
            })
            .map(|&index| index as usize)
    }
    pub(super) fn method_by_ids(
        &self,
        owner: usize,
        name: usize,
        signature: usize,
    ) -> Option<&StoredMethod> {
        let ids = [owner, name, signature];
        let fingerprint = self.id_fingerprint(ids, true);
        self.methods
            .positions
            .find(fingerprint, |&index| {
                self.ids_equal(ids, self.methods.entries[index as usize].0.ids(), true)
            })
            .map(|&index| &self.methods.entries[index as usize].1)
    }
    /// Text lookups on a shared-dictionary table go through the IDs the texts stand for.
    fn ids_of_texts<const N: usize>(&self, values: [&str; N], method: bool) -> Option<[usize; N]> {
        let strings = self.texts.shared()?;
        let mut ids = [0usize; N];
        for (position, value) in values.into_iter().enumerate() {
            ids[position] = if position == 2 && self.descriptorless {
                if method {
                    self.signature_row_of_text(value)?
                } else {
                    self.raw_row_of_text(value)?
                }
            } else {
                strings.index_of(value)?
            };
        }
        Some(ids)
    }
    fn raw_row_of_text(&self, descriptor: &str) -> Option<usize> {
        let mut hash = self.fingerprints.build_hasher();
        raw::hash_text(&mut hash, descriptor);
        let fingerprint = hash.finish();
        self.raw_text_index
            .find(fingerprint, |&row| {
                raw::type_bytes(&self.types, &self.texts, row as usize).eq(descriptor.bytes())
            })
            .map(|&row| row as usize)
    }
    fn signature_row_of_text(&self, descriptor: &str) -> Option<usize> {
        // Parse the descriptor into its erased types and bind each to its canonical row.
        let rest = descriptor.strip_prefix('(')?;
        let close = rest.find(')')?;
        let (parameters, returns) = (&rest[..close], &rest[close + 1..]);
        let mut rows = Vec::new();
        let mut cursor = parameters;
        while !cursor.is_empty() {
            let length = raw_descriptor_length(cursor)?;
            rows.push(self.raw_row_of_text(&cursor[..length])?);
            cursor = &cursor[length..];
        }
        if raw_descriptor_length(returns)? != returns.len() {
            return None;
        }
        let returns = self.raw_row_of_text(returns)?;
        self.signature_row_of(rows.iter().copied(), returns)
    }
    fn key_context(&self, method: bool) -> KeyContext<'_> {
        KeyContext {
            texts: &self.texts,
            descriptors: if !self.descriptorless {
                Descriptors::Legacy
            } else if method {
                Descriptors::Methods(&self.types, &self.signatures)
            } else {
                Descriptors::Fields(&self.types)
            },
        }
    }
}

#[derive(Debug)]
pub(super) enum Storage {
    Compact(Box<CompactTable>),
    Owned(Box<MutableDeclaredTypes>),
}
pub struct DeclaredTypes {
    pub(super) storage: Storage,
}
impl Default for DeclaredTypes {
    fn default() -> Self {
        Self::from(MutableDeclaredTypes::default())
    }
}
impl From<MutableDeclaredTypes> for DeclaredTypes {
    fn from(value: MutableDeclaredTypes) -> Self {
        Self {
            storage: Storage::Owned(Box::new(value)),
        }
    }
}

#[derive(Debug, PartialEq, Eq)]
pub struct TypeView<'a> {
    pub kind: &'a str,
    pub name: &'a str,
    pub scope: Cow<'a, str>,
    pub owner: Option<usize>,
    pub component: Option<usize>,
    pub variance: &'a str,
    pub arguments: &'a [usize],
}
#[derive(Clone, Copy)]
pub enum Formals<'a> {
    // The compact backing remains private through these borrowed views.
    #[doc(hidden)]
    Compact(&'a [TypeParameter<usize, u64>], &'a DeclaredTypes),
    #[doc(hidden)]
    Owned(&'a [TypeParameter]),
}
#[derive(Debug, PartialEq, Eq)]
pub struct FormalView<'a> {
    pub name: &'a str,
    pub scope: Cow<'a, str>,
    pub bounds: &'a [usize],
}
impl<'a> Formals<'a> {
    pub fn iter(self) -> impl ExactSizeIterator<Item = FormalView<'a>> {
        let len = match self {
            Self::Compact(p, _) => p.len(),
            Self::Owned(p) => p.len(),
        };
        (0..len).map(move |i| match self {
            Self::Compact(p, table) => FormalView {
                name: table.text(p[i].name),
                scope: table.scope(p[i].scope),
                bounds: &p[i].bounds,
            },
            Self::Owned(p) => FormalView {
                name: &p[i].name,
                scope: Cow::Borrowed(&p[i].scope),
                bounds: &p[i].bounds,
            },
        })
    }
}
#[derive(Debug, PartialEq, Eq)]
pub struct MethodView<'a> {
    pub parameters: Params<'a>,
    pub returns: usize,
    pub type_parameters: Formals<'a>,
}

/// The parameter type rows of a method: 32-bit rows borrowed from a compact table, or
/// the rows of an owning model.
#[derive(Clone, Copy)]
pub enum Params<'a> {
    #[doc(hidden)]
    Narrow(&'a [u32]),
    #[doc(hidden)]
    Wide(&'a [usize]),
}
impl<'a> Params<'a> {
    pub fn len(self) -> usize {
        match self {
            Self::Narrow(rows) => rows.len(),
            Self::Wide(rows) => rows.len(),
        }
    }
    pub fn is_empty(self) -> bool {
        self.len() == 0
    }
    pub fn get(self, index: usize) -> Option<usize> {
        match self {
            Self::Narrow(rows) => rows.get(index).map(|&row| row as usize),
            Self::Wide(rows) => rows.get(index).copied(),
        }
    }
    pub fn iter(self) -> impl ExactSizeIterator<Item = usize> + 'a {
        (0..self.len()).map(move |index| self.get(index).expect("index below len"))
    }
    pub fn to_vec(self) -> Vec<usize> {
        self.iter().collect()
    }
}
impl std::fmt::Debug for Params<'_> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_list().entries(self.iter()).finish()
    }
}
impl PartialEq for Params<'_> {
    fn eq(&self, other: &Self) -> bool {
        self.iter().eq(other.iter())
    }
}
impl Eq for Params<'_> {}
impl<const N: usize> PartialEq<[usize; N]> for Params<'_> {
    fn eq(&self, other: &[usize; N]) -> bool {
        self.iter().eq(other.iter().copied())
    }
}
impl PartialEq<Vec<usize>> for Params<'_> {
    fn eq(&self, other: &Vec<usize>) -> bool {
        self.iter().eq(other.iter().copied())
    }
}
impl PartialEq<&[usize]> for Params<'_> {
    fn eq(&self, other: &&[usize]) -> bool {
        self.iter().eq(other.iter().copied())
    }
}

impl std::fmt::Debug for Formals<'_> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_list().entries(self.iter()).finish()
    }
}
impl PartialEq for Formals<'_> {
    fn eq(&self, other: &Self) -> bool {
        self.iter().eq(other.iter())
    }
}
impl Eq for Formals<'_> {}
#[derive(Debug, PartialEq, Eq)]
pub struct ClassView<'a> {
    pub type_parameters: Formals<'a>,
    pub superclass: Option<usize>,
    pub interfaces: &'a [usize],
}
impl std::fmt::Debug for DeclaredTypes {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DeclaredTypes")
            .field("type_count", &self.type_count())
            .field("field_count", &self.field_count())
            .field("method_count", &self.method_count())
            .field("class_count", &self.class_count())
            .field(
                "first_types",
                &(0..self.type_count().min(8))
                    .map(|id| self.type_expr(id))
                    .collect::<Vec<_>>(),
            )
            .finish()
    }
}
impl DeclaredTypes {
    /// Negative-only test for plain CONTAINS on rendered generic_type/type_info.
    /// Mutable/custom tables have no validated summary and always answer MAYBE.
    pub fn generic_text_may_contain(&self, literal: &str) -> bool {
        match &self.storage {
            Storage::Compact(table) => table
                .text_summary
                .as_ref()
                .is_none_or(|summary| summary.may_contain(literal)),
            Storage::Owned(_) => true,
        }
    }
    fn text(&self, id: usize) -> &str {
        match &self.storage {
            Storage::Compact(t) => t.texts.text(id),
            Storage::Owned(_) => unreachable!("owned views do not use IDs"),
        }
    }
    fn scope(&self, scope: u64) -> Cow<'_, str> {
        match &self.storage {
            Storage::Compact(t) => t.scope(scope),
            Storage::Owned(_) => unreachable!("owned views do not use scope IDs"),
        }
    }
    pub fn type_count(&self) -> usize {
        match &self.storage {
            Storage::Compact(t) => t.types.len(),
            Storage::Owned(t) => t.types.len(),
        }
    }
    pub fn field_count(&self) -> usize {
        match &self.storage {
            Storage::Compact(t) => t.fields.len(),
            Storage::Owned(t) => t.fields.len(),
        }
    }
    pub fn method_count(&self) -> usize {
        match &self.storage {
            Storage::Compact(t) => t.methods.len(),
            Storage::Owned(t) => t.methods.len(),
        }
    }
    pub fn class_count(&self) -> usize {
        match &self.storage {
            Storage::Compact(t) => t.classes.len(),
            Storage::Owned(t) => t.classes.len(),
        }
    }
    pub(super) fn scope_length(&self, id: usize) -> usize {
        match &self.storage {
            Storage::Compact(t) => t.scope_length(t.types[id].scope),
            Storage::Owned(t) => t.types[id].scope.len(),
        }
    }
    pub(super) fn type_expr_without_scope(&self, id: usize) -> TypeView<'_> {
        self.type_view(id, false)
    }
    pub fn type_expr(&self, id: usize) -> TypeView<'_> {
        self.type_view(id, true)
    }
    fn type_view(&self, id: usize, include_scope: bool) -> TypeView<'_> {
        match &self.storage {
            Storage::Compact(table) => {
                let t = &table.types[id];
                TypeView {
                    kind: table.kind(t.kind),
                    name: table.name(t.name),
                    scope: if include_scope {
                        table.scope(t.scope)
                    } else {
                        Cow::Borrowed("")
                    },
                    owner: t.owner(),
                    component: t.component(),
                    variance: table.variance(t.variance),
                    arguments: &t.arguments,
                }
            }
            Storage::Owned(table) => {
                let t = &table.types[id];
                TypeView {
                    kind: &t.kind,
                    name: &t.name,
                    scope: Cow::Borrowed(&t.scope),
                    owner: t.owner,
                    component: t.component,
                    variance: &t.variance,
                    arguments: &t.arguments,
                }
            }
        }
    }
    pub fn field_type(&self, owner: &str, name: &str, descriptor: &str) -> Option<usize> {
        match &self.storage {
            Storage::Compact(t) if t.shared_ids => {
                let [owner, name, raw] = t.ids_of_texts([owner, name, descriptor], false)?;
                t.field_by_ids(owner, name, raw)
            }
            Storage::Compact(t) => t
                .fields
                .get(&t.lookup([owner, name, descriptor], false))
                .map(|&row| row as usize),
            Storage::Owned(t) => t
                .fields
                .get(&BorrowedMemberKey(owner, name, descriptor))
                .copied(),
        }
    }
    /// The declared type of a field the graph spells by string IDs: its class, name and
    /// Java type name (`int[]`, `java.util.List`), as a `FieldNode` carries them.
    pub fn field_type_by_ids(
        &self,
        owner: usize,
        name: usize,
        type_name: usize,
    ) -> Option<Option<usize>> {
        match &self.storage {
            Storage::Compact(t) if t.shared_ids && t.descriptorless => {
                let Some(raw) = t.raw_row_of_type_name(type_name) else {
                    return Some(None);
                };
                Some(t.field_by_ids(owner, name, raw))
            }
            _ => None,
        }
    }
    /// The declared types of a method the graph spells by string IDs. `None` means the table
    /// cannot answer by ID and the caller must spell the descriptor; `Some(None)` is an unbound method.
    pub fn method_types_by_ids(
        &self,
        owner: usize,
        name: usize,
        parameter_types: &[u32],
        return_type: usize,
    ) -> Option<Option<MethodView<'_>>> {
        let Storage::Compact(t) = &self.storage else {
            return None;
        };
        if !(t.shared_ids && t.descriptorless) {
            return None;
        }
        let mut rows = [0usize; 16];
        let mut spilled = Vec::new();
        let count = parameter_types.len();
        for (index, &sid) in parameter_types.iter().enumerate() {
            let Some(row) = t.raw_row_of_type_name(sid as usize) else {
                return Some(None);
            };
            if count <= rows.len() {
                rows[index] = row;
            } else {
                spilled.push(row);
            }
        }
        let Some(returns) = t.raw_row_of_type_name(return_type) else {
            return Some(None);
        };
        let rows: &[usize] = if count <= rows.len() {
            &rows[..count]
        } else {
            &spilled
        };
        let Some(signature) = t.signature_row_of(rows.iter().copied(), returns) else {
            return Some(None);
        };
        let Some(m) = t.method_by_ids(owner, name, signature) else {
            return Some(None);
        };
        Some(Some(MethodView {
            parameters: Params::Narrow(&t.method_parameters[m.parameters()]),
            returns: m.returns(),
            type_parameters: Formals::Compact(&t.method_formals[m.formals()], self),
        }))
    }
    pub fn method_types(
        &self,
        owner: &str,
        name: &str,
        descriptor: &str,
    ) -> Option<MethodView<'_>> {
        match &self.storage {
            Storage::Compact(t) => {
                let m = if t.shared_ids {
                    let [owner, name, signature] =
                        t.ids_of_texts([owner, name, descriptor], true)?;
                    t.method_by_ids(owner, name, signature)?
                } else {
                    t.methods.get(&t.lookup([owner, name, descriptor], true))?
                };
                Some(MethodView {
                    parameters: Params::Narrow(&t.method_parameters[m.parameters()]),
                    returns: m.returns(),
                    type_parameters: Formals::Compact(&t.method_formals[m.formals()], self),
                })
            }
            Storage::Owned(t) => {
                let m = t.methods.get(&BorrowedMemberKey(owner, name, descriptor))?;
                Some(MethodView {
                    parameters: Params::Wide(&m.parameters),
                    returns: m.returns,
                    type_parameters: Formals::Owned(&m.type_parameters),
                })
            }
        }
    }
    fn class_types(&self, name: &str) -> Option<ClassView<'_>> {
        match &self.storage {
            Storage::Compact(t) => {
                let c = if t.shared_ids {
                    let [id] = t.ids_of_texts([name], false)?;
                    t.class_by_id(id)?
                } else {
                    t.classes.get(&t.lookup([name], false))?
                };
                Some(ClassView {
                    type_parameters: Formals::Compact(&c.type_parameters, self),
                    superclass: c.superclass,
                    interfaces: &c.interfaces,
                })
            }
            Storage::Owned(t) => {
                let c = t.classes.get(name)?;
                Some(ClassView {
                    type_parameters: Formals::Owned(&c.type_parameters),
                    superclass: c.superclass,
                    interfaces: &c.interfaces,
                })
            }
        }
    }
    pub fn field_entries(&self) -> impl ExactSizeIterator<Item = ([Cow<'_, str>; 3], usize)> {
        (0..self.field_count()).map(move |i| match &self.storage {
            Storage::Compact(t) => {
                let (key, value) = t.fields.get_index(i).unwrap();
                (t.key_context(false).values(key.ids()), *value as usize)
            }
            Storage::Owned(t) => {
                let (key, value) = t.fields.get_index(i).unwrap();
                (
                    [
                        Cow::Borrowed(key.0.as_ref()),
                        Cow::Borrowed(key.1.as_ref()),
                        Cow::Borrowed(key.2.as_ref()),
                    ],
                    *value,
                )
            }
        })
    }
    pub fn method_entries(
        &self,
    ) -> impl ExactSizeIterator<Item = ([Cow<'_, str>; 3], MethodView<'_>)> {
        (0..self.method_count()).map(move |i| match &self.storage {
            Storage::Compact(t) => {
                let (key, v) = t.methods.get_index(i).unwrap();
                (
                    t.key_context(true).values(key.ids()),
                    MethodView {
                        parameters: Params::Narrow(&t.method_parameters[v.parameters()]),
                        returns: v.returns(),
                        type_parameters: Formals::Compact(&t.method_formals[v.formals()], self),
                    },
                )
            }
            Storage::Owned(t) => {
                let (key, v) = t.methods.get_index(i).unwrap();
                (
                    [
                        Cow::Borrowed(key.0.as_ref()),
                        Cow::Borrowed(key.1.as_ref()),
                        Cow::Borrowed(key.2.as_ref()),
                    ],
                    MethodView {
                        parameters: Params::Wide(&v.parameters),
                        returns: v.returns,
                        type_parameters: Formals::Owned(&v.type_parameters),
                    },
                )
            }
        })
    }
    pub fn class_entries(&self) -> impl ExactSizeIterator<Item = (&str, ClassView<'_>)> {
        (0..self.class_count()).map(move |i| match &self.storage {
            Storage::Compact(t) => {
                let (key, v) = t.classes.get_index(i).unwrap();
                (
                    t.texts.text(key.ids()[0]),
                    ClassView {
                        type_parameters: Formals::Compact(&v.type_parameters, self),
                        superclass: v.superclass,
                        interfaces: &v.interfaces,
                    },
                )
            }
            Storage::Owned(t) => {
                let (key, v) = t.classes.get_index(i).unwrap();
                (
                    key.as_ref(),
                    ClassView {
                        type_parameters: Formals::Owned(&v.type_parameters),
                        superclass: v.superclass,
                        interfaces: &v.interfaces,
                    },
                )
            }
        })
    }
    pub(crate) fn update_slot(
        slot: &mut Option<Self>,
        update: impl FnOnce(&mut Option<MutableDeclaredTypes>),
    ) {
        if let Some(table) = slot.as_mut() {
            table.ensure_owned();
        }
        let owned = slot.take().map(Self::into_mutable);
        struct Restore<'a> {
            slot: &'a mut Option<DeclaredTypes>,
            owned: Option<MutableDeclaredTypes>,
        }
        impl Drop for Restore<'_> {
            fn drop(&mut self) {
                *self.slot = self.owned.take().map(Into::into);
            }
        }
        let mut restore = Restore { slot, owned };
        update(&mut restore.owned);
    }
    pub(crate) fn ensure_owned(&mut self) {
        if matches!(self.storage, Storage::Compact(_)) {
            let owned = self.to_mutable();
            self.storage = Storage::Owned(Box::new(owned));
        }
    }
    /// Explicit mutation boundary; text is materialized only when requested.
    pub fn into_mutable(self) -> MutableDeclaredTypes {
        match self.storage {
            Storage::Owned(t) => *t,
            storage => Self { storage }.to_mutable(),
        }
    }
    pub fn to_mutable(&self) -> MutableDeclaredTypes {
        if let Storage::Owned(t) = &self.storage {
            return (**t).clone();
        }
        let mut strings: HashMap<Arc<str>, ()> = HashMap::new();
        let mut text = |value: &str| {
            if let Some((stored, ())) = strings.get_key_value(value) {
                return stored.clone();
            }
            let stored: Arc<str> = Arc::from(value);
            strings.insert(stored.clone(), ());
            stored
        };
        fn formals(p: Formals<'_>, text: &mut impl FnMut(&str) -> Arc<str>) -> Vec<TypeParameter> {
            p.iter()
                .map(|p| TypeParameter {
                    name: text(p.name),
                    scope: text(&p.scope),
                    bounds: p.bounds.to_vec(),
                })
                .collect()
        }
        MutableDeclaredTypes {
            types: (0..self.type_count())
                .map(|id| {
                    let v = self.type_expr(id);
                    TypeExpr {
                        kind: text(v.kind),
                        name: text(v.name),
                        scope: text(&v.scope),
                        owner: v.owner,
                        component: v.component,
                        variance: text(v.variance),
                        arguments: v.arguments.to_vec(),
                    }
                })
                .collect(),
            fields: self
                .field_entries()
                .map(|(k, v)| ((text(&k[0]), text(&k[1]), text(&k[2])), v))
                .collect(),
            methods: self
                .method_entries()
                .map(|(k, v)| {
                    (
                        (text(&k[0]), text(&k[1]), text(&k[2])),
                        MethodTypes {
                            parameters: v.parameters.to_vec(),
                            returns: v.returns,
                            type_parameters: formals(v.type_parameters, &mut text),
                        },
                    )
                })
                .collect(),
            classes: self
                .class_entries()
                .map(|(k, v)| {
                    (
                        text(k),
                        ClassTypes {
                            type_parameters: formals(v.type_parameters, &mut text),
                            superclass: v.superclass,
                            interfaces: v.interfaces.to_vec(),
                        },
                    )
                })
                .collect(),
        }
    }
}
impl PartialEq for DeclaredTypes {
    fn eq(&self, other: &Self) -> bool {
        self.type_count() == other.type_count()
            && (0..self.type_count()).all(|id| self.type_expr(id) == other.type_expr(id))
            && self.field_count() == other.field_count()
            && self.method_count() == other.method_count()
            && self.class_count() == other.class_count()
            && self.field_entries().all(|([owner, name, descriptor], id)| {
                other.field_type(&owner, &name, &descriptor) == Some(id)
            })
            && self
                .method_entries()
                .all(|([owner, name, descriptor], method)| {
                    other.method_types(&owner, &name, &descriptor) == Some(method)
                })
            && self
                .class_entries()
                .all(|(name, class)| other.class_types(name) == Some(class))
    }
}
impl Eq for DeclaredTypes {}

// Compatibility helpers for the independent owned test encoders. Each parse/load
// still exercises the real compact implementation, then exposes all raw values.
#[cfg(test)]
impl MutableDeclaredTypes {
    pub fn parse(bytes: &[u8], metadata: &[u8]) -> Result<Self, TypeError> {
        DeclaredTypes::parse(bytes, metadata).map(|v| v.into_mutable())
    }
    pub fn parse_with_strings(
        bytes: &[u8],
        metadata: &[u8],
        strings: &StringTable,
    ) -> Result<Self, TypeError> {
        DeclaredTypes::parse_with_strings(bytes, metadata, strings).map(|v| v.into_mutable())
    }
    pub fn load(source: &crate::source::GraphSource) -> Result<Option<Self>, TypeError> {
        DeclaredTypes::load(source).map(|v| v.map(DeclaredTypes::into_mutable))
    }
    pub fn load_with_strings(
        source: &crate::source::GraphSource,
        strings: &StringTable,
    ) -> Result<Option<Self>, TypeError> {
        DeclaredTypes::load_with_strings(source, strings)
            .map(|v| v.map(DeclaredTypes::into_mutable))
    }
    pub fn validate(&self) -> Result<(), TypeError> {
        DeclaredTypes::from(self.clone()).validate()
    }
    pub fn render(&self, id: usize) -> String {
        DeclaredTypes::from(self.clone()).render(id)
    }
    pub fn field_type(&self, owner: &str, name: &str, descriptor: &str) -> Option<usize> {
        self.fields
            .get(&BorrowedMemberKey(owner, name, descriptor))
            .copied()
    }
    pub fn method_types(&self, owner: &str, name: &str, descriptor: &str) -> Option<&MethodTypes> {
        self.methods
            .get(&BorrowedMemberKey(owner, name, descriptor))
    }
}

#[cfg(test)]
mod compact_tests {
    use super::*;

    fn stored_row(owner: Option<usize>, component: Option<usize>) -> TypeExpr<usize, u64> {
        TypeExpr {
            kind: 0,
            name: 1,
            scope: 2,
            owner,
            component,
            variance: 2,
            arguments: vec![0, 3, 1],
        }
    }

    #[test]
    fn packed_optional_ids_distinguish_absence_zero_and_maximum_wire_value() {
        let maximum = usize::try_from(i32::MAX).unwrap();
        for (owner, component) in [
            (None, None),
            (Some(0), None),
            (None, Some(0)),
            (Some(maximum), Some(0)),
            (Some(0), Some(maximum)),
        ] {
            let row = StoredType::try_from(stored_row(owner, component)).unwrap();
            assert_eq!(row.owner(), owner);
            assert_eq!(row.component(), component);
            assert_eq!(row.arguments.as_ref(), [0, 3, 1]);
            assert_eq!((row.kind, row.name, row.scope, row.variance), (0, 1, 2, 2));
        }
    }

    #[test]
    fn packed_optional_ids_reject_oversized_values_without_truncation() {
        let first_invalid = usize::try_from(i32::MAX).unwrap() + 1;
        for invalid in [first_invalid, usize::MAX] {
            for (owner, component) in [(Some(invalid), None), (None, Some(invalid))] {
                let error = StoredType::try_from(stored_row(owner, component)).unwrap_err();
                assert_eq!(error.0, "type reference exceeds signed wire ID range");
            }
        }
    }

    #[test]
    fn packed_views_borrow_arguments_and_owning_mutation_keeps_original_types() {
        let mut table = CompactTable {
            texts: TextStore::Owned(
                ["class", "Outer", "", "Outer$Inner", "array", "String"]
                    .into_iter()
                    .map(Arc::from)
                    .collect(),
            ),
            ..Default::default()
        };
        for (kind, name, owner, component, arguments) in [
            (0, 1, None, None, vec![]),
            (0, 3, Some(0), None, vec![3]),
            (4, 2, None, Some(1), vec![]),
            (0, 5, None, None, vec![]),
        ] {
            table.types.push(
                StoredType::try_from(TypeExpr {
                    kind,
                    name,
                    scope: 2,
                    owner,
                    component,
                    variance: 2,
                    arguments,
                })
                .unwrap(),
            );
        }
        let declarations = DeclaredTypes {
            storage: Storage::Compact(Box::new(table)),
        };
        declarations.validate().unwrap();
        let view = declarations.type_expr(1);
        assert_eq!(view.owner, Some(0));
        assert_eq!(view.component, None);
        assert_eq!(view.arguments, [3]);
        assert_eq!(
            view.arguments.as_ptr(),
            declarations.type_expr(1).arguments.as_ptr()
        );
        assert_eq!(declarations.type_expr(2).component, Some(1));
        assert_eq!(declarations.render(2), "Outer.Inner<String>[]");
        let mut owned = declarations.to_mutable();
        assert_eq!(declarations, DeclaredTypes::from(owned.clone()));
        owned.types[1].arguments[0] = 0;
        owned.types[1].owner = None;
        assert_eq!(owned.render(2), "Outer$Inner<Outer>[]");
        assert_eq!(declarations.render(2), "Outer.Inner<String>[]");
        assert_eq!(declarations.into_mutable().types[1].arguments, [3]);
    }

    #[test]
    #[cfg(target_pointer_width = "64")]
    fn packed_row_inline_layout_is_smaller_without_a_process_memory_claim() {
        assert_eq!(std::mem::size_of::<TypeExpr<usize, u64>>(), 88);
        assert_eq!(std::mem::size_of::<StoredType>(), 56);
    }

    #[test]
    fn method_arena_ranges_preserve_empty_parameters_formals_and_owning_mutation() {
        let texts = TextStore::Owned(
            [
                "Owner", "empty", "()V", "generic", "(T)T", "T", "scope", "pair", "(TT)T",
            ]
            .into_iter()
            .map(Arc::from)
            .collect(),
        );
        let mut table = CompactTable::default();
        for (ids, parameters, type_parameters) in [
            ([0, 1, 2], vec![], vec![]),
            (
                [0, 3, 4],
                vec![2],
                vec![TypeParameter {
                    name: 5,
                    scope: 6,
                    bounds: vec![1, 0],
                }],
            ),
            ([0, 7, 8], vec![1, 2], vec![]),
        ] {
            assert!(table.insert_method(
                &texts,
                ids,
                MethodTypes {
                    parameters,
                    returns: 0,
                    type_parameters
                }
            ));
        }
        // A duplicate must leave the existing method and subsequent ranges intact.
        assert!(!table.insert_method(
            &texts,
            [0, 3, 4],
            MethodTypes {
                parameters: vec![99; 100],
                returns: 99,
                type_parameters: vec![],
            }
        ));
        table.finish_method_buffers();
        table.texts = texts;
        let declarations = DeclaredTypes {
            storage: Storage::Compact(Box::new(table)),
        };
        let empty = declarations.method_types("Owner", "empty", "()V").unwrap();
        assert!(empty.parameters.is_empty());
        assert_eq!(empty.type_parameters.iter().count(), 0);
        let generic = declarations
            .method_types("Owner", "generic", "(T)T")
            .unwrap();
        assert_eq!(generic.parameters, [2]);
        assert_eq!(generic.returns, 0);
        assert_eq!(
            generic.type_parameters.iter().collect::<Vec<_>>(),
            vec![FormalView {
                name: "T",
                scope: "scope".into(),
                bounds: &[1, 0],
            }]
        );
        let pair = declarations.method_types("Owner", "pair", "(TT)T").unwrap();
        assert_eq!(pair.parameters, [1, 2]);
        assert_eq!(pair.type_parameters.iter().count(), 0);
        assert_eq!(
            declarations
                .method_entries()
                .map(|([_, name, _], method)| (name, method.parameters.to_vec()))
                .collect::<Vec<_>>(),
            vec![
                ("empty".into(), vec![]),
                ("generic".into(), vec![2]),
                ("pair".into(), vec![1, 2])
            ]
        );
        let mut owned = declarations.to_mutable();
        assert_eq!(declarations, DeclaredTypes::from(owned.clone()));
        owned
            .methods
            .get_mut(&BorrowedMemberKey("Owner", "generic", "(T)T"))
            .unwrap()
            .parameters
            .push(1);
        assert_eq!(
            declarations
                .method_types("Owner", "generic", "(T)T")
                .unwrap()
                .parameters,
            [2]
        );
        assert_eq!(
            owned
                .method_types("Owner", "generic", "(T)T")
                .unwrap()
                .parameters,
            [2, 1]
        );
    }

    #[test]
    fn method_arena_ranges_survive_growth_and_duplicate_text_ids() {
        let texts = TextStore::Owned(
            (0..300)
                .map(|i| Arc::from(format!("name-{i}")))
                .chain([Arc::from("name-0")])
                .collect(),
        );
        let mut table = CompactTable::default();
        for i in 0..256 {
            assert!(table.insert_method(
                &texts,
                [i, 298, 299],
                MethodTypes {
                    parameters: vec![i; i % 7],
                    returns: i,
                    type_parameters: vec![TypeParameter {
                        name: i,
                        scope: 298,
                        bounds: vec![i, i + 1],
                    }],
                }
            ));
        }
        assert!(!table.insert_method(
            &texts,
            [300, 298, 299],
            MethodTypes {
                parameters: vec![],
                returns: 999,
                type_parameters: vec![],
            }
        ));
        table.finish_method_buffers();
        table.texts = texts;
        let declarations = DeclaredTypes {
            storage: Storage::Compact(Box::new(table)),
        };
        assert_eq!(declarations.method_count(), 256);
        for i in 0..256 {
            let name = format!("name-{i}");
            let method = declarations
                .method_types(&name, "name-298", "name-299")
                .unwrap();
            assert_eq!(method.parameters, vec![i; i % 7]);
            assert_eq!(method.returns, i);
            assert_eq!(
                method.type_parameters.iter().collect::<Vec<_>>(),
                vec![FormalView {
                    name: &name,
                    scope: "name-298".into(),
                    bounds: &[i, i + 1],
                }]
            );
        }
        assert_eq!(declarations, DeclaredTypes::from(declarations.to_mutable()));
    }

    #[test]
    fn compact_fingerprint_collisions_keep_full_text_equality_across_resizes() {
        let texts = TextStore::Owned(
            (0..260)
                .map(|i| Arc::from(format!("text-{i}")))
                .chain([Arc::from("text-0")])
                .collect(),
        );
        let mut map = MemberIndex::default();
        for i in 0..256 {
            assert!(CompactTable::insert_fingerprinted(
                &mut map,
                &texts,
                [i, i + 1, i + 2],
                i,
                7,
                |_| 7
            ));
        }
        assert!(!CompactTable::insert_fingerprinted(
            &mut map,
            &texts,
            [260, 1, 2],
            999,
            7,
            |_| 7
        ));
        for i in 0..256 {
            let key = Lookup {
                fingerprint: 7,
                values: [texts.text(i), texts.text(i + 1), texts.text(i + 2)],
                context: KeyContext::legacy(&texts),
            };
            assert_eq!(map.get(&key), Some(&i));
        }
        assert!(map
            .get(&Lookup {
                fingerprint: 7,
                values: ["text-0", "text-2", "text-1"],
                context: KeyContext::legacy(&texts)
            })
            .is_none());
        assert_eq!(
            map.values().copied().collect::<Vec<_>>(),
            (0..256).collect::<Vec<_>>()
        );
        let mut classes = MemberIndex::default();
        assert!(CompactTable::insert_fingerprinted(
            &mut classes,
            &texts,
            [0],
            1,
            7,
            |_| 7
        ));
        assert!(!CompactTable::insert_fingerprinted(
            &mut classes,
            &texts,
            [260],
            2,
            7,
            |_| 7
        ));
    }

    #[test]
    fn compact_semantic_equality_preserves_membership_and_ordered_views() {
        let bytes = crate::types::tests::fixture();
        let left = DeclaredTypes::parse(&bytes, b"metadata").unwrap();
        let right = DeclaredTypes::parse(&bytes, b"metadata").unwrap();
        assert_eq!(left, right);
        let mut reversed = right.into_mutable();
        reversed.fields.reverse();
        reversed.methods.reverse();
        reversed.classes.reverse();
        assert_eq!(left.to_mutable(), reversed);
        let mut reordered = DeclaredTypes::from(reversed);
        assert_eq!(left, reordered);
        assert_ne!(
            left.field_entries().collect::<Vec<_>>(),
            reordered.field_entries().collect::<Vec<_>>()
        );
        assert_eq!(
            left.method_entries()
                .collect::<Vec<_>>()
                .into_iter()
                .rev()
                .collect::<Vec<_>>(),
            reordered.method_entries().collect::<Vec<_>>()
        );
        assert_eq!(
            left.class_entries()
                .collect::<Vec<_>>()
                .into_iter()
                .rev()
                .collect::<Vec<_>>(),
            reordered.class_entries().collect::<Vec<_>>()
        );
        let Storage::Owned(table) = &mut reordered.storage else {
            unreachable!()
        };
        *table.fields.values_mut().next().unwrap() = left.type_count();
        assert_ne!(left, reordered);
        assert!(format!("{left:?}").contains("java.util.List"));
        assert!(!format!("{left:?}").contains("fingerprint"));
    }

    #[test]
    fn compact_updates_materialize_once_keep_new_text_and_partial_panics() {
        let mut slot =
            Some(DeclaredTypes::parse(&crate::types::tests::fixture(), b"metadata").unwrap());
        let mut address = std::ptr::null();
        DeclaredTypes::update_slot(&mut slot, |table| {
            let table = table.as_mut().unwrap();
            table.types[0].name = Arc::from("new.类型🚀");
            table.fields.insert(
                ("NewOwner".into(), "newField".into(), "Lnew/类型🚀;".into()),
                0,
            );
            address = table.types.as_ptr();
        });
        assert_eq!(
            slot.as_ref()
                .unwrap()
                .field_type("NewOwner", "newField", "Lnew/类型🚀;"),
            Some(0)
        );
        assert!(slot.as_ref().unwrap().render(0).starts_with("new.类型🚀<"));
        DeclaredTypes::update_slot(&mut slot, |table| {
            assert_eq!(table.as_ref().unwrap().types.as_ptr(), address)
        });
        let panic = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            DeclaredTypes::update_slot(&mut slot, |table| {
                let t = table.as_mut().unwrap();
                assert_eq!(t.types.as_ptr(), address);
                t.fields.clear();
                panic!("partial update");
            });
        }));
        assert!(panic.is_err());
        assert_eq!(slot.as_ref().unwrap().field_count(), 0);
        DeclaredTypes::update_slot(&mut slot, |table| *table = None);
        assert!(slot.is_none());
    }
}

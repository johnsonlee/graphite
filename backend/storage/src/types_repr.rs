//! Loaded declarations use graph-local text IDs; mutation explicitly owns text.
#[cfg(test)]
use super::TypeError;
use crate::strings::StringTable;
use hashbrown::HashTable;
use indexmap::{Equivalent, IndexMap};
use std::collections::hash_map::RandomState;
use std::collections::HashMap;
use std::hash::{BuildHasher, Hash, Hasher};
use std::ops::Range;
use std::sync::Arc;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TypeExpr<T = Arc<str>> {
    pub kind: T,
    pub name: T,
    pub scope: T,
    pub owner: Option<usize>,
    pub component: Option<usize>,
    pub variance: T,
    pub arguments: Vec<usize>,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TypeParameter<T = Arc<str>> {
    pub name: T,
    pub scope: T,
    pub bounds: Vec<usize>,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MethodTypes<T = Arc<str>> {
    pub parameters: Vec<usize>,
    pub returns: usize,
    pub type_parameters: Vec<TypeParameter<T>>,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ClassTypes<T = Arc<str>> {
    pub type_parameters: Vec<TypeParameter<T>>,
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

#[derive(Debug, PartialEq, Eq)]
struct StoredKey<const N: usize> {
    ids: [usize; N],
}
struct Lookup<'a, const N: usize> {
    fingerprint: u64,
    values: [&'a str; N],
    texts: &'a dyn Texts,
}
impl<const N: usize> Equivalent<StoredKey<N>> for Lookup<'_, N> {
    fn equivalent(&self, key: &StoredKey<N>) -> bool {
        self.values
            .iter()
            .zip(key.ids)
            .all(|(value, id)| *value == self.texts.text(id))
    }
}

/// File-ordered entries with an index containing only entry positions. Hashes are
/// computed from complete text values and recomputed only when the index grows.
#[derive(Debug)]
struct MemberIndex<const N: usize, V> {
    entries: Vec<(StoredKey<N>, V)>,
    positions: HashTable<usize>,
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
                key.equivalent(&self.entries[*index].0)
            })
            .map(|index| &self.entries[*index].1)
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
        let index = self.entries.len();
        self.entries.push((StoredKey { ids }, value));
        let entries = &self.entries;
        self.positions
            .insert_unique(fingerprint, index, |index| rehash(&entries[*index].0));
    }
    #[cfg(test)]
    fn values(&self) -> impl ExactSizeIterator<Item = &V> {
        self.entries.iter().map(|(_, value)| value)
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
    pub(super) types: Vec<TypeExpr<usize>>,
    // Private keys: all inserts and lookups compare full actual text values.
    fields: MemberIndex<3, usize>,
    methods: MemberIndex<3, StoredMethod>,
    method_parameters: Vec<usize>,
    method_formals: Vec<TypeParameter<usize>>,
    classes: MemberIndex<1, ClassTypes<usize>>,
    fingerprints: RandomState,
}
#[derive(Debug)]
struct StoredMethod {
    parameters: Range<usize>,
    returns: usize,
    type_parameters: Range<usize>,
}
impl CompactTable {
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
        let values = ids.map(|id| texts.text(id));
        let fingerprint = state.hash_one(values);
        Self::insert_fingerprinted(map, texts, ids, value, fingerprint, |key| {
            state.hash_one(key.ids.map(|id| texts.text(id)))
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
            texts,
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
        Self::insert(&mut self.fields, &self.fingerprints, texts, ids, value)
    }
    pub(super) fn insert_method(
        &mut self,
        texts: &dyn Texts,
        ids: [usize; 3],
        value: MethodTypes<usize>,
    ) -> bool {
        let values = ids.map(|id| texts.text(id));
        let fingerprint = self.fingerprints.hash_one(values);
        let lookup = Lookup {
            fingerprint,
            values,
            texts,
        };
        if self.methods.contains_key(&lookup) {
            return false;
        }
        let parameter_start = self.method_parameters.len();
        self.method_parameters.extend(value.parameters);
        let formal_start = self.method_formals.len();
        self.method_formals.extend(value.type_parameters);
        self.methods.insert_unique(
            ids,
            StoredMethod {
                parameters: parameter_start..self.method_parameters.len(),
                returns: value.returns,
                type_parameters: formal_start..self.method_formals.len(),
            },
            fingerprint,
            |key| self.fingerprints.hash_one(key.ids.map(|id| texts.text(id))),
        );
        true
    }
    pub(super) fn finish_method_buffers(&mut self) {
        self.method_parameters.shrink_to_fit();
        self.method_formals.shrink_to_fit();
    }
    pub(super) fn insert_class(
        &mut self,
        texts: &dyn Texts,
        id: usize,
        value: ClassTypes<usize>,
    ) -> bool {
        Self::insert(&mut self.classes, &self.fingerprints, texts, [id], value)
    }
    fn lookup<'a, const N: usize>(&'a self, values: [&'a str; N]) -> Lookup<'a, N> {
        Lookup {
            fingerprint: self.fingerprints.hash_one(values),
            values,
            texts: &self.texts,
        }
    }
}

#[derive(Debug)]
pub(super) enum Storage {
    Compact(CompactTable),
    Owned(MutableDeclaredTypes),
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
            storage: Storage::Owned(value),
        }
    }
}

#[derive(Debug, PartialEq, Eq)]
pub struct TypeView<'a> {
    pub kind: &'a str,
    pub name: &'a str,
    pub scope: &'a str,
    pub owner: Option<usize>,
    pub component: Option<usize>,
    pub variance: &'a str,
    pub arguments: &'a [usize],
}
#[derive(Clone, Copy)]
pub enum Formals<'a> {
    // The compact backing remains private through these borrowed views.
    #[doc(hidden)]
    Compact(&'a [TypeParameter<usize>], &'a DeclaredTypes),
    #[doc(hidden)]
    Owned(&'a [TypeParameter]),
}
#[derive(Debug, PartialEq, Eq)]
pub struct FormalView<'a> {
    pub name: &'a str,
    pub scope: &'a str,
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
                scope: table.text(p[i].scope),
                bounds: &p[i].bounds,
            },
            Self::Owned(p) => FormalView {
                name: &p[i].name,
                scope: &p[i].scope,
                bounds: &p[i].bounds,
            },
        })
    }
}
#[derive(Debug, PartialEq, Eq)]
pub struct MethodView<'a> {
    pub parameters: &'a [usize],
    pub returns: usize,
    pub type_parameters: Formals<'a>,
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
    fn text(&self, id: usize) -> &str {
        match &self.storage {
            Storage::Compact(t) => t.texts.text(id),
            Storage::Owned(_) => unreachable!("owned views do not use IDs"),
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
    pub fn type_expr(&self, id: usize) -> TypeView<'_> {
        match &self.storage {
            Storage::Compact(table) => {
                let t = &table.types[id];
                TypeView {
                    kind: table.texts.text(t.kind),
                    name: table.texts.text(t.name),
                    scope: table.texts.text(t.scope),
                    owner: t.owner,
                    component: t.component,
                    variance: table.texts.text(t.variance),
                    arguments: &t.arguments,
                }
            }
            Storage::Owned(table) => {
                let t = &table.types[id];
                TypeView {
                    kind: &t.kind,
                    name: &t.name,
                    scope: &t.scope,
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
            Storage::Compact(t) => t.fields.get(&t.lookup([owner, name, descriptor])).copied(),
            Storage::Owned(t) => t
                .fields
                .get(&BorrowedMemberKey(owner, name, descriptor))
                .copied(),
        }
    }
    pub fn method_types(
        &self,
        owner: &str,
        name: &str,
        descriptor: &str,
    ) -> Option<MethodView<'_>> {
        match &self.storage {
            Storage::Compact(t) => {
                let m = t.methods.get(&t.lookup([owner, name, descriptor]))?;
                Some(MethodView {
                    parameters: &t.method_parameters[m.parameters.clone()],
                    returns: m.returns,
                    type_parameters: Formals::Compact(
                        &t.method_formals[m.type_parameters.clone()],
                        self,
                    ),
                })
            }
            Storage::Owned(t) => {
                let m = t.methods.get(&BorrowedMemberKey(owner, name, descriptor))?;
                Some(MethodView {
                    parameters: &m.parameters,
                    returns: m.returns,
                    type_parameters: Formals::Owned(&m.type_parameters),
                })
            }
        }
    }
    fn class_types(&self, name: &str) -> Option<ClassView<'_>> {
        match &self.storage {
            Storage::Compact(t) => {
                let c = t.classes.get(&t.lookup([name]))?;
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
    pub fn field_entries(&self) -> impl ExactSizeIterator<Item = ([&str; 3], usize)> {
        (0..self.field_count()).map(move |i| match &self.storage {
            Storage::Compact(t) => {
                let (key, value) = t.fields.get_index(i).unwrap();
                (key.ids.map(|id| t.texts.text(id)), *value)
            }
            Storage::Owned(t) => {
                let (key, value) = t.fields.get_index(i).unwrap();
                ([key.0.as_ref(), key.1.as_ref(), key.2.as_ref()], *value)
            }
        })
    }
    pub fn method_entries(&self) -> impl ExactSizeIterator<Item = ([&str; 3], MethodView<'_>)> {
        (0..self.method_count()).map(move |i| match &self.storage {
            Storage::Compact(t) => {
                let (key, v) = t.methods.get_index(i).unwrap();
                (
                    key.ids.map(|id| t.texts.text(id)),
                    MethodView {
                        parameters: &t.method_parameters[v.parameters.clone()],
                        returns: v.returns,
                        type_parameters: Formals::Compact(
                            &t.method_formals[v.type_parameters.clone()],
                            self,
                        ),
                    },
                )
            }
            Storage::Owned(t) => {
                let (key, v) = t.methods.get_index(i).unwrap();
                (
                    [key.0.as_ref(), key.1.as_ref(), key.2.as_ref()],
                    MethodView {
                        parameters: &v.parameters,
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
                    t.texts.text(key.ids[0]),
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
            self.storage = Storage::Owned(owned);
        }
    }
    /// Explicit mutation boundary; text is materialized only when requested.
    pub fn into_mutable(self) -> MutableDeclaredTypes {
        match self.storage {
            Storage::Owned(t) => t,
            storage => Self { storage }.to_mutable(),
        }
    }
    pub fn to_mutable(&self) -> MutableDeclaredTypes {
        if let Storage::Owned(t) = &self.storage {
            return t.clone();
        }
        let Storage::Compact(t) = &self.storage else {
            unreachable!()
        };
        let mut strings: HashMap<&str, Arc<str>> = HashMap::new();
        let mut text = |id| {
            strings
                .entry(t.texts.text(id))
                .or_insert_with(|| Arc::from(t.texts.text(id)))
                .clone()
        };
        fn formals(
            p: &[TypeParameter<usize>],
            text: &mut impl FnMut(usize) -> Arc<str>,
        ) -> Vec<TypeParameter> {
            p.iter()
                .map(|p| TypeParameter {
                    name: text(p.name),
                    scope: text(p.scope),
                    bounds: p.bounds.clone(),
                })
                .collect()
        }
        MutableDeclaredTypes {
            types: t
                .types
                .iter()
                .map(|v| TypeExpr {
                    kind: text(v.kind),
                    name: text(v.name),
                    scope: text(v.scope),
                    owner: v.owner,
                    component: v.component,
                    variance: text(v.variance),
                    arguments: v.arguments.clone(),
                })
                .collect(),
            fields: t
                .fields
                .iter()
                .map(|(k, v)| ((text(k.ids[0]), text(k.ids[1]), text(k.ids[2])), *v))
                .collect(),
            methods: t
                .methods
                .iter()
                .map(|(k, v)| {
                    (
                        (text(k.ids[0]), text(k.ids[1]), text(k.ids[2])),
                        MethodTypes {
                            parameters: t.method_parameters[v.parameters.clone()].to_vec(),
                            returns: v.returns,
                            type_parameters: formals(
                                &t.method_formals[v.type_parameters.clone()],
                                &mut text,
                            ),
                        },
                    )
                })
                .collect(),
            classes: t
                .classes
                .iter()
                .map(|(k, v)| {
                    (
                        text(k.ids[0]),
                        ClassTypes {
                            type_parameters: formals(&v.type_parameters, &mut text),
                            superclass: v.superclass,
                            interfaces: v.interfaces.clone(),
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
                other.field_type(owner, name, descriptor) == Some(id)
            })
            && self
                .method_entries()
                .all(|([owner, name, descriptor], method)| {
                    other.method_types(owner, name, descriptor) == Some(method)
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
            storage: Storage::Compact(table),
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
                scope: "scope",
                bounds: &[1, 0],
            }]
        );
        let pair = declarations.method_types("Owner", "pair", "(TT)T").unwrap();
        assert_eq!(pair.parameters, [1, 2]);
        assert_eq!(pair.type_parameters.iter().count(), 0);
        assert_eq!(
            declarations
                .method_entries()
                .map(|(key, method)| (key[1], method.parameters.to_vec()))
                .collect::<Vec<_>>(),
            vec![
                ("empty", vec![]),
                ("generic", vec![2]),
                ("pair", vec![1, 2])
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
            storage: Storage::Compact(table),
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
                    scope: "name-298",
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
                texts: &texts,
            };
            assert_eq!(map.get(&key), Some(&i));
        }
        assert!(map
            .get(&Lookup {
                fingerprint: 7,
                values: ["text-0", "text-2", "text-1"],
                texts: &texts
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

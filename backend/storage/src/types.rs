//! Graph-local declared types. Erased node identities remain in the main graph.
use crate::source::GraphSource;
use crate::strings::StringTable;
#[cfg(test)]
use indexmap::IndexMap;
use indexmap::IndexSet;
use sha2::{Digest, Sha256};
#[cfg(test)]
use std::hash::Hash;
use std::hash::{BuildHasher, Hasher};
use std::sync::Arc;

#[path = "types_raw.rs"]
mod raw;
#[path = "types_repr.rs"]
mod repr;
#[path = "types_text_summary.rs"]
mod text_summary;
#[cfg(test)]
use repr::{BorrowedMemberKey, MemberKey};
pub use repr::{
    ClassTypes, DeclaredTypes, MethodTypes, MethodView, MutableDeclaredTypes, TypeExpr,
    TypeParameter, TypeView,
};
use repr::{CompactTable, Storage, StoredType, Texts};

#[derive(Debug, Clone, thiserror::Error)]
#[error("graph.types: {0}")]
pub struct TypeError(pub String);

struct Reader<'a> {
    bytes: &'a [u8],
    pos: usize,
    strings: IndexSet<Arc<str>>,
    dictionary: Vec<Arc<str>>,
    pooled: bool,
    shared: Option<&'a StringTable>,
    structural: bool,
}
impl<'a> Reader<'a> {
    fn at(bytes: &'a [u8], pos: usize) -> Self {
        Self {
            bytes,
            pos,
            strings: IndexSet::new(),
            dictionary: Vec::new(),
            pooled: false,
            shared: None,
            structural: false,
        }
    }

    fn take(&mut self, len: usize) -> Result<&'a [u8], TypeError> {
        let end = self
            .pos
            .checked_add(len)
            .ok_or_else(|| TypeError("length overflow".into()))?;
        let bytes = self
            .bytes
            .get(self.pos..end)
            .ok_or_else(|| TypeError("truncated table".into()))?;
        self.pos = end;
        Ok(bytes)
    }
    fn int(&mut self) -> Result<i32, TypeError> {
        Ok(i32::from_be_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn count(&mut self) -> Result<usize, TypeError> {
        let n = self.int()?;
        if n < 0 || n as usize > self.bytes.len().saturating_sub(self.pos) / 4 {
            return Err(TypeError("invalid count".into()));
        }
        Ok(n as usize)
    }
    // Both wire versions use at least four bytes per text (length or pool ID).
    // Check a complete section's minimum footprint before reserving row storage;
    // the generic count bound alone only allows four bytes per entire row.
    fn section_count(&mut self, minimum_row_bytes: usize) -> Result<usize, TypeError> {
        let count = self.count()?;
        let minimum = count
            .checked_mul(minimum_row_bytes)
            .ok_or_else(|| TypeError("section byte length overflow".into()))?;
        if minimum > self.bytes.len().saturating_sub(self.pos) {
            return Err(TypeError("section count exceeds remaining bytes".into()));
        }
        Ok(count)
    }
    fn inline_string(&mut self) -> Result<&'a str, TypeError> {
        let len = self.int()?;
        if len < 0 {
            return Err(TypeError("negative string length".into()));
        }
        std::str::from_utf8(self.take(len as usize)?).map_err(|_| TypeError("invalid UTF-8".into()))
    }
    fn dictionary(&mut self) -> Result<(), TypeError> {
        for _ in 0..self.count()? {
            let value = self.inline_string()?;
            if !self.strings.insert(Arc::from(value)) {
                return Err(TypeError("duplicate dictionary string".into()));
            }
        }
        self.dictionary = std::mem::take(&mut self.strings).into_iter().collect();
        self.pooled = true;
        Ok(())
    }
    fn string(&mut self) -> Result<usize, TypeError> {
        if let Some(strings) = self.shared {
            let id = usize::try_from(self.int()?)
                .map_err(|_| TypeError("invalid global string ID".into()))?;
            strings
                .strict_get(id)
                .map_err(|error| TypeError(error.to_string()))?;
            return Ok(id);
        }
        if self.pooled {
            let id = self.int()?;
            let value = usize::try_from(id)
                .ok()
                .and_then(|id| self.dictionary.get(id))
                .cloned()
                .ok_or_else(|| TypeError("invalid string ID".into()))?;
            return Ok(self.strings.insert_full(value).0);
        }
        let value = self.inline_string()?;
        if let Some(id) = self.strings.get_index_of(value) {
            return Ok(id);
        }
        Ok(self.strings.insert_full(Arc::from(value)).0)
    }
    fn reference(&mut self, count: usize) -> Result<usize, TypeError> {
        let n = self.int()?;
        if n < 0 || n as usize >= count {
            return Err(TypeError("invalid type reference".into()));
        }
        Ok(n as usize)
    }
    fn optional(&mut self, count: usize) -> Result<Option<usize>, TypeError> {
        let n = self.int()?;
        if n == -1 {
            return Ok(None);
        }
        if n < 0 || n as usize >= count {
            return Err(TypeError("invalid optional type reference".into()));
        }
        Ok(Some(n as usize))
    }
    fn references(&mut self, count: usize) -> Result<Vec<usize>, TypeError> {
        (0..self.count()?).map(|_| self.reference(count)).collect()
    }
    fn parameters(&mut self, count: usize) -> Result<Vec<TypeParameter<usize, u64>>, TypeError> {
        (0..self.section_count(if self.structural { 16 } else { 12 })?)
            .map(|_| {
                Ok(TypeParameter {
                    name: self.string()?,
                    scope: if self.structural {
                        let tag = self.take(1)?[0];
                        if self.take(3)? != [0, 0, 0] {
                            return Err(TypeError("nonzero reserved formal bytes".into()));
                        }
                        self.scope_reference(tag)?
                    } else {
                        self.string()? as u64
                    },
                    bounds: self.references(count)?,
                })
            })
            .collect()
    }
    fn scope_reference(&mut self, tag: u8) -> Result<u64, TypeError> {
        let target = self.int()?;
        match (tag, target) {
            (0, -1) => Ok(0),
            (1..=4, 0..) => Ok(((target as u64) << 3) | u64::from(tag)),
            _ => Err(TypeError("invalid declaration scope encoding".into())),
        }
    }
    fn structural_type(&mut self, count: usize) -> Result<TypeExpr<usize, u64>, TypeError> {
        let tags = self.take(4)?;
        let (kind, variance, scope_tag) = (tags[0], tags[1], tags[2]);
        if kind > 4 || variance > 3 || tags[3] != 0 {
            return Err(TypeError(
                "invalid structural type tags or reserved byte".into(),
            ));
        }
        let name = self.int()?;
        let name = match name {
            -1 => usize::MAX,
            0.. => {
                self.shared
                    .expect("structural strings context")
                    .strict_get(name as usize)
                    .map_err(|error| TypeError(error.to_string()))?;
                name as usize
            }
            _ => return Err(TypeError("invalid optional name string ID".into())),
        };
        let scope = self.scope_reference(scope_tag)?;
        Ok(TypeExpr {
            kind: kind as usize,
            variance: variance as usize,
            name,
            scope,
            owner: self.optional(count)?,
            component: self.optional(count)?,
            arguments: self.references(count)?,
        })
    }
    fn signatures(
        &mut self,
        types: &[StoredType],
        validation: &mut raw::RawValidation,
    ) -> Result<Vec<raw::RawSignature>, TypeError> {
        let count = self.section_count(8)?;
        let mut signatures: Vec<raw::RawSignature> = Vec::new();
        signatures
            .try_reserve_exact(count)
            .map_err(|error| TypeError(format!("signature allocation: {error}")))?;
        let mut index = hashbrown::HashTable::<usize>::new();
        index
            .try_reserve(count, |_| unreachable!("empty signature index"))
            .map_err(|error| TypeError(format!("signature index allocation: {error}")))?;
        let state = std::collections::hash_map::RandomState::new();
        for _ in 0..count {
            let signature = raw::RawSignature {
                parameters: self.references(types.len())?,
                returns: self.reference(types.len())?,
            };
            for &parameter in &signature.parameters {
                validation.validate(types, self, parameter, false)?;
            }
            validation.validate(types, self, signature.returns, true)?;
            raw::check_descriptor_length(raw::signature_descriptor_length(
                types, self, &signature,
            ))?;
            let hash = |signature: &raw::RawSignature| {
                let mut hash = state.build_hasher();
                raw::hash_bytes(&mut hash, raw::signature_bytes(types, self, signature));
                hash.finish()
            };
            let fingerprint = hash(&signature);
            if index
                .find(fingerprint, |&other| {
                    raw::signature_bytes(types, self, &signature).eq(raw::signature_bytes(
                        types,
                        self,
                        &signatures[other],
                    ))
                })
                .is_some()
            {
                return Err(TypeError("duplicate erased signature".into()));
            }
            index.insert_unique(fingerprint, signatures.len(), |&other| {
                hash(&signatures[other])
            });
            signatures.push(signature);
        }
        Ok(signatures)
    }
    fn key(&mut self) -> Result<[usize; 3], TypeError> {
        Ok([self.string()?, self.string()?, self.string()?])
    }
}
impl Texts for Reader<'_> {
    fn text(&self, id: usize) -> &str {
        match self.shared {
            Some(table) => table.get(id),
            None => &self.strings[id],
        }
    }
}
impl DeclaredTypes {
    /// Exact erased member identity shared by projections and load-time key summaries.
    pub fn method<'a>(
        &'a self,
        method: &crate::node::MethodDesc,
        strings: &crate::strings::StringTable,
    ) -> Option<MethodView<'a>> {
        self.method_types(
            strings.get(method.declaring_class as usize),
            strings.get(method.name as usize),
            &method.descriptor(strings),
        )
    }

    pub fn node_type_id(
        &self,
        node: &crate::node::Node,
        strings: &crate::strings::StringTable,
    ) -> Option<usize> {
        use crate::node::NodeKind;
        match &node.kind {
            NodeKind::Field {
                declaring_class,
                name,
                field_type,
                ..
            } => {
                let mut descriptor = String::new();
                crate::node::push_type_descriptor(
                    strings.get(*field_type as usize),
                    &mut descriptor,
                );
                self.field_type(
                    strings.get(*declaring_class as usize),
                    strings.get(*name as usize),
                    &descriptor,
                )
            }
            NodeKind::Parameter { method, index, .. } => {
                self.method(method, strings).and_then(|m| {
                    usize::try_from(*index)
                        .ok()
                        .and_then(|i| m.parameters.get(i).copied())
                })
            }
            NodeKind::Return { method, .. } => self.method(method, strings).map(|m| m.returns),
            _ => None,
        }
    }
    fn binding(source: &GraphSource) -> Result<Option<String>, TypeError> {
        // This declaration is authoritative: older BVGraph writers replace
        // forward.properties, so an orphaned table cannot survive their saves.
        let properties = source
            .require("forward.properties")
            .map_err(|(p, e)| TypeError(format!("{p}: {e}")))?;
        let properties = std::str::from_utf8(&properties)
            .map_err(|_| TypeError("forward.properties is not UTF-8".into()))?;
        let mut binding = None;
        for line in properties.lines().map(str::trim) {
            if line.starts_with('#') || line.starts_with('!') {
                continue;
            }
            if let Some((key, value)) = line.split_once('=').or_else(|| line.split_once(':')) {
                if key.trim() == "graphite.declaredTypes.sha256" {
                    binding = Some(value.trim());
                }
            }
        }
        let Some(binding) = binding else {
            return Ok(None);
        };
        if binding.len() != 64 || !binding.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(TypeError("invalid forward.properties type binding".into()));
        }
        Ok(Some(binding.to_owned()))
    }

    /// Advisory only. Shared-table parsers independently require verified strings.
    pub(crate) fn needs_shared_strings(source: &GraphSource) -> Result<bool, TypeError> {
        if Self::binding(source)?.is_none() {
            return Ok(false);
        }
        let bytes = source
            .bytes("graph.types")
            .map_err(|(path, error)| TypeError(format!("{path}: {error}")))?;
        Ok(bytes
            .as_ref()
            .is_some_and(|bytes| matches!(bytes.get(..4), Some([0x47, 0x54, 0x59, 3..=5]))))
    }

    pub fn load(source: &GraphSource) -> Result<Option<Self>, TypeError> {
        if Self::needs_shared_strings(source)? {
            let strings = StringTable::load_for_declared_types(source)
                .map_err(|error| TypeError(error.to_string()))?;
            Self::load_context(source, Some(&strings))
        } else {
            Self::load_context(source, None)
        }
    }

    pub fn load_with_strings(
        source: &GraphSource,
        strings: &StringTable,
    ) -> Result<Option<Self>, TypeError> {
        Self::load_context(source, Some(strings))
    }

    fn load_context(
        source: &GraphSource,
        strings: Option<&StringTable>,
    ) -> Result<Option<Self>, TypeError> {
        let Some(binding) = Self::binding(source)? else {
            return Ok(None);
        };
        let bytes = source
            .require("graph.types")
            .map_err(|(p, e)| TypeError(format!("{p}: {e}")))?;
        let actual = Sha256::digest(&bytes)
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect::<String>();
        if !actual.eq_ignore_ascii_case(&binding) {
            return Err(TypeError("forward.properties type digest mismatch".into()));
        }
        let metadata = source
            .require("graph.metadata")
            .map_err(|(p, e)| TypeError(format!("{p}: {e}")))?;
        Self::parse_context(&bytes, &metadata, strings).map(Some)
    }
    pub fn parse(bytes: &[u8], metadata: &[u8]) -> Result<Self, TypeError> {
        Self::parse_context(bytes, metadata, None)
    }

    pub fn parse_with_strings(
        bytes: &[u8],
        metadata: &[u8],
        strings: &StringTable,
    ) -> Result<Self, TypeError> {
        Self::parse_context(bytes, metadata, Some(strings))
    }

    fn parse_context<'a>(
        bytes: &'a [u8],
        metadata: &[u8],
        strings: Option<&'a StringTable>,
    ) -> Result<Self, TypeError> {
        let mut r = Reader::at(bytes, 0);
        let version = r.int()?;
        if !matches!(version, 0x47545901..=0x47545905) {
            return Err(TypeError("unsupported header/version".into()));
        }
        if r.take(32)? != Sha256::digest(metadata).as_slice() {
            return Err(TypeError("metadata digest mismatch".into()));
        }
        if version == 0x47545902 {
            r.dictionary()?;
        } else if version >= 0x47545903 {
            r.structural = version >= 0x47545904;
            let strings = strings.ok_or_else(|| {
                TypeError("GTY03–GTY05 requires verified graph.strings context".into())
            })?;
            let digest = strings.serialized_digest().ok_or_else(|| {
                TypeError("GTY03–GTY05 requires verified serialized graph.strings digest".into())
            })?;
            if r.take(32)? != digest {
                return Err(TypeError("serialized graph.strings digest mismatch".into()));
            }
            r.shared = Some(strings);
        }
        let count = r.section_count(if r.structural { 24 } else { 28 })?;
        let mut table = CompactTable::default();
        table.structural = r.structural;
        table.descriptorless = version == 0x47545905;
        table
            .types
            .try_reserve_exact(count)
            .map_err(|error| TypeError(format!("type section allocation: {error}")))?;
        for _ in 0..count {
            let decoded = if r.structural {
                r.structural_type(count)?
            } else {
                TypeExpr {
                    kind: r.string()?,
                    name: r.string()?,
                    scope: r.string()? as u64,
                    owner: r.optional(count)?,
                    component: r.optional(count)?,
                    variance: r.string()?,
                    arguments: r.references(count)?,
                }
            };
            table.types.push(StoredType::try_from(decoded)?);
        }
        let mut raw_validation =
            raw::RawValidation::new(if table.descriptorless { count } else { 0 });
        if table.descriptorless {
            table.signatures = r.signatures(&table.types, &mut raw_validation)?;
        }
        let field_count = r.section_count(16)?;
        table
            .reserve_fields(field_count)
            .map_err(|error| TypeError(format!("field section allocation: {error}")))?;
        for _ in 0..field_count {
            let key = if table.descriptorless {
                let key = [r.string()?, r.string()?, r.reference(count)?];
                raw_validation.validate(&table.types, &r, key[2], false)?;
                raw::check_descriptor_length(raw::type_descriptor_length(
                    &table.types,
                    &r,
                    key[2],
                ))?;
                key
            } else {
                r.key()?
            };
            let value = r.reference(count)?;
            if !table.insert_field(&r, key, value) {
                return Err(TypeError("duplicate field".into()));
            }
        }
        let method_count = r.section_count(24)?;
        table
            .reserve_methods(method_count)
            .map_err(|error| TypeError(format!("method section allocation: {error}")))?;
        for _ in 0..method_count {
            let key = if table.descriptorless {
                [
                    r.string()?,
                    r.string()?,
                    r.reference(table.signatures.len())?,
                ]
            } else {
                r.key()?
            };
            let method = MethodTypes {
                parameters: r.references(count)?,
                returns: r.reference(count)?,
                type_parameters: r.parameters(count)?,
            };
            if !table.insert_method(&r, key, method) {
                return Err(TypeError("duplicate method".into()));
            }
        }
        let class_count = r.section_count(16)?;
        table
            .reserve_classes(class_count)
            .map_err(|error| TypeError(format!("class section allocation: {error}")))?;
        for _ in 0..class_count {
            let key = r.string()?;
            let class = ClassTypes {
                type_parameters: r.parameters(count)?,
                superclass: r.optional(count)?,
                interfaces: r.references(count)?,
            };
            if !table.insert_class(&r, key, class) {
                return Err(TypeError("duplicate class".into()));
            }
        }
        if r.pos != bytes.len() {
            return Err(TypeError("trailing bytes".into()));
        }
        table.validate_scopes()?;
        table.finish_method_buffers();
        table.texts = match r.shared {
            Some(strings) => repr::TextStore::Shared(strings.clone()),
            None => repr::TextStore::Owned(r.strings.into_iter().collect()),
        };
        let mut table = Self {
            storage: Storage::Compact(table),
        };
        table.validate()?;
        if let Storage::Compact(compact) = &mut table.storage {
            compact.finish_text_summary();
        }
        Ok(table)
    }
    fn validate(&self) -> Result<(), TypeError> {
        let mut state = vec![0u8; self.type_count()];
        let mut heights = vec![0usize; self.type_count()];
        let mut expanded_nodes = vec![0usize; self.type_count()];
        let mut expanded_bytes = vec![0usize; self.type_count()];
        fn visit(
            table: &DeclaredTypes,
            id: usize,
            depth: usize,
            state: &mut [u8],
            heights: &mut [usize],
            expanded_nodes: &mut [usize],
            expanded_bytes: &mut [usize],
        ) -> Result<usize, TypeError> {
            if state[id] == 1 {
                return Err(TypeError("cyclic type expression".into()));
            }
            if state[id] == 2 {
                return Ok(heights[id]);
            }
            if depth > 256 {
                return Err(TypeError("type nesting exceeds 256".into()));
            }
            state[id] = 1;
            let t = table.type_expr_without_scope(id);
            let scope_bytes = table.scope_length(id);
            let shape = match t.kind {
                "class" => !t.name.is_empty() && t.component.is_none() && t.variance.is_empty(),
                "primitive" => {
                    matches!(
                        t.name,
                        "boolean"
                            | "byte"
                            | "char"
                            | "short"
                            | "int"
                            | "long"
                            | "float"
                            | "double"
                            | "void"
                    ) && t.owner.is_none()
                        && t.component.is_none()
                        && t.arguments.is_empty()
                        && t.variance.is_empty()
                }
                "array" => {
                    t.component.is_some()
                        && t.owner.is_none()
                        && t.arguments.is_empty()
                        && t.variance.is_empty()
                }
                "variable" => {
                    !t.name.is_empty()
                        && scope_bytes != 0
                        && t.owner.is_none()
                        && t.component.is_none()
                        && t.arguments.is_empty()
                        && t.variance.is_empty()
                }
                "wildcard" => {
                    t.owner.is_none()
                        && t.arguments.is_empty()
                        && match t.variance {
                            "extends" | "super" => t.component.is_some(),
                            "unbounded" => t.component.is_none(),
                            _ => false,
                        }
                }
                _ => false,
            };
            if !shape {
                return Err(TypeError(format!("invalid {} type expression", t.kind)));
            }
            let mut height = 1;
            let mut nodes = 1usize;
            let mut bytes = t
                .kind
                .len()
                .saturating_add(t.name.len())
                .saturating_add(scope_bytes)
                .saturating_add(t.variance.len());
            for next in t
                .owner
                .iter()
                .chain(t.component.iter())
                .chain(t.arguments.iter())
            {
                height = height.max(
                    1 + visit(
                        table,
                        *next,
                        depth + 1,
                        state,
                        heights,
                        expanded_nodes,
                        expanded_bytes,
                    )?,
                );
                nodes = nodes.saturating_add(expanded_nodes[*next]);
                bytes = bytes.saturating_add(expanded_bytes[*next]);
            }
            if height > 256 {
                return Err(TypeError("type nesting exceeds 256".into()));
            }
            if nodes > 100_000 || bytes > 1_000_000 {
                return Err(TypeError(
                    "expanded type expression exceeds projection budget".into(),
                ));
            }
            state[id] = 2;
            heights[id] = height;
            expanded_nodes[id] = nodes;
            expanded_bytes[id] = bytes;
            Ok(height)
        }
        for id in 0..self.type_count() {
            visit(
                self,
                id,
                1,
                &mut state,
                &mut heights,
                &mut expanded_nodes,
                &mut expanded_bytes,
            )?;
        }
        Ok(())
    }
    pub fn render(&self, id: usize) -> String {
        let t = self.type_expr_without_scope(id);
        match t.kind {
            "array" => format!("{}[]", self.render(t.component.unwrap())),
            "wildcard" => t
                .component
                .map(|b| format!("? {} {}", t.variance, self.render(b)))
                .unwrap_or_else(|| "?".into()),
            "class" => {
                let mut out = match t.owner {
                    Some(owner) => format!(
                        "{}.{}",
                        self.render(owner),
                        t.name
                            .strip_prefix(&format!("{}$", self.type_expr_without_scope(owner).name))
                            .unwrap_or(t.name)
                    ),
                    None => t.name.to_string(),
                };
                if !t.arguments.is_empty() {
                    out.push('<');
                    out.push_str(
                        &t.arguments
                            .iter()
                            .map(|id| self.render(*id))
                            .collect::<Vec<_>>()
                            .join(", "),
                    );
                    out.push('>');
                }
                out
            }
            _ => t.name.to_string(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::MutableDeclaredTypes as DeclaredTypes;
    use super::*;
    fn int(out: &mut Vec<u8>, value: i32) {
        out.extend(value.to_be_bytes());
    }
    fn string(out: &mut Vec<u8>, value: &str) {
        int(out, value.len() as i32);
        out.extend(value.as_bytes());
    }
    fn ids(out: &mut Vec<u8>, values: &[i32]) {
        int(out, values.len() as i32);
        for id in values {
            int(out, *id);
        }
    }
    fn row(
        out: &mut Vec<u8>,
        kind: &str,
        name: &str,
        scope: &str,
        component: i32,
        variance: &str,
        args: &[i32],
    ) {
        for s in [kind, name, scope] {
            string(out, s);
        }
        int(out, -1);
        int(out, component);
        string(out, variance);
        ids(out, args);
    }
    pub(super) fn bind(dir: &std::path::Path, bytes: &[u8]) {
        let digest = Sha256::digest(bytes)
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect::<String>();
        std::fs::write(
            dir.join("forward.properties"),
            format!("nodes=0\narcs=0\ngraphite.declaredTypes.sha256={digest}\n"),
        )
        .unwrap();
    }

    pub(super) fn fixture() -> Vec<u8> {
        let mut out = Vec::new();
        int(&mut out, 0x47545901);
        out.extend(Sha256::digest(b"metadata"));
        int(&mut out, 5);
        row(&mut out, "class", "java.util.List", "", -1, "", &[2]);
        row(&mut out, "variable", "T", "class:Example", -1, "", &[]);
        row(&mut out, "wildcard", "", "", 1, "super", &[]);
        row(&mut out, "array", "", "", 0, "", &[]);
        row(&mut out, "class", "java.lang.Comparable", "", -1, "", &[1]);
        int(&mut out, 2);
        for name in ["first", "second"] {
            for s in ["Example", name, "Ljava/util/List;"] {
                string(&mut out, s);
            }
            int(&mut out, 0);
        }
        int(&mut out, 1);
        for s in ["Example", "method", "(Ljava/util/List;)[Ljava/util/List;"] {
            string(&mut out, s);
        }
        ids(&mut out, &[0]);
        int(&mut out, 3);
        int(&mut out, 0);
        int(&mut out, 1);
        string(&mut out, "Example");
        int(&mut out, 1);
        string(&mut out, "T");
        string(&mut out, "class:Example");
        ids(&mut out, &[4]);
        int(&mut out, -1);
        ids(&mut out, &[]);
        out
    }
    #[test]
    fn declaration_references_share_structural_types_and_recursive_bounds() {
        let table = DeclaredTypes::parse(&fixture(), b"metadata").unwrap();
        assert_eq!(table.types.len(), 5);
        assert_eq!(table.fields.values().copied().collect::<Vec<_>>(), [0, 0]);
        assert_eq!(table.render(3), "java.util.List<? super T>[]");
        let method = table
            .method_types("Example", "method", "(Ljava/util/List;)[Ljava/util/List;")
            .unwrap();
        assert_eq!(method.parameters, [0]);
        assert_eq!(method.returns, 3);
        let parameter = &table.classes["Example"].type_parameters[0];
        assert_eq!(parameter.scope.as_ref(), "class:Example");
        assert_eq!(table.render(parameter.bounds[0]), "java.lang.Comparable<T>");
    }
    #[test]
    fn corrupt_truncated_stale_and_cyclic_tables_are_rejected() {
        let bytes = fixture();
        for end in 0..bytes.len() {
            assert!(
                DeclaredTypes::parse(&bytes[..end], b"metadata").is_err(),
                "prefix {end}"
            );
        }
        assert!(DeclaredTypes::parse(&bytes, b"different metadata")
            .unwrap_err()
            .0
            .contains("digest"));
        let mut unknown = bytes.clone();
        unknown[3] = 3;
        assert!(DeclaredTypes::parse(&unknown, b"metadata").is_err());
        let mut trailing = bytes.clone();
        trailing.push(0);
        assert!(DeclaredTypes::parse(&trailing, b"metadata")
            .unwrap_err()
            .0
            .contains("trailing"));
        let mut huge = bytes.clone();
        huge[36..40].copy_from_slice(&i32::MAX.to_be_bytes());
        assert!(DeclaredTypes::parse(&huge, b"metadata").is_err());
        let mut table = DeclaredTypes::parse(&bytes, b"metadata").unwrap();
        table.types[0].arguments = vec![0];
        assert!(table.validate().unwrap_err().0.contains("cyclic"));
        table.types[0].arguments = vec![2];
        table.types[2].variance = "wrong".into();
        assert!(table.validate().is_err());
    }
    #[test]
    fn wire_references_and_utf8_are_checked_before_projection() {
        let original = fixture();
        let mut reader = Reader::at(&original, 36);
        let count = reader.count().unwrap();
        reader.string().unwrap();
        reader.string().unwrap();
        reader.string().unwrap();
        let owner = reader.pos;
        reader.optional(count).unwrap();
        let component = reader.pos;
        reader.optional(count).unwrap();
        reader.string().unwrap();
        assert_eq!(reader.count().unwrap(), 1);
        let argument = reader.pos;
        for (position, bad_reference) in [
            (owner, -2i32),
            (owner, 5),
            (component, 5),
            (argument, -1),
            (argument, 5),
        ] {
            let mut corrupt = original.clone();
            corrupt[position..position + 4].copy_from_slice(&bad_reference.to_be_bytes());
            assert!(DeclaredTypes::parse(&corrupt, b"metadata")
                .unwrap_err()
                .0
                .contains("reference"));
        }
        let mut malformed_utf8 = original.clone();
        malformed_utf8[44] = 0xff;
        assert!(DeclaredTypes::parse(&malformed_utf8, b"metadata")
            .unwrap_err()
            .0
            .contains("UTF-8"));
        assert!(
            DeclaredTypes::parse(&original, b"metadata plus an optional trailer")
                .unwrap_err()
                .0
                .contains("digest")
        );
    }

    #[test]
    fn exponentially_shared_types_and_repeated_scope_bytes_are_rejected() {
        let mut table = DeclaredTypes::parse(&fixture(), b"metadata").unwrap();
        for _ in 0..18 {
            let previous = table.types.len() - 1;
            table.types.push(TypeExpr {
                kind: "class".into(),
                name: "Pair".into(),
                scope: Arc::from(""),
                owner: None,
                component: None,
                variance: Arc::from(""),
                arguments: vec![previous, previous],
            });
        }
        assert!(table
            .validate()
            .unwrap_err()
            .0
            .contains("projection budget"));
        let mut table = DeclaredTypes::parse(&fixture(), b"metadata").unwrap();
        table.types[1].scope = "界".repeat(20_000).into();
        table.types[0].arguments = vec![1; 17];
        assert!(table
            .validate()
            .unwrap_err()
            .0
            .contains("projection budget"));
    }

    #[test]
    fn malformed_type_shapes_are_rejected() {
        let base = fixture();
        for variant in 0..8 {
            let mut table = DeclaredTypes::parse(&base, b"metadata").unwrap();
            match variant {
                0 => table.types[0].name = Arc::from(""),
                1 => table.types[0].component = Some(1),
                2 => table.types[1].scope = Arc::from(""),
                3 => table.types[1].arguments.push(0),
                4 => table.types[2].component = None,
                5 => table.types[2].variance = "unbounded".into(),
                6 => table.types[3].owner = Some(0),
                7 => table.types[3].component = None,
                _ => unreachable!(),
            }
            assert!(table.validate().is_err(), "malformed variant {variant}");
        }
        let mut bytes = base;
        // The initial count is followed by the first string's byte length.
        bytes[40..44].copy_from_slice(&(-1i32).to_be_bytes());
        assert!(DeclaredTypes::parse(&bytes, b"metadata").is_err());
    }

    #[test]
    fn depth_limit_checks_shared_leaf_first_chains_and_owner_names() {
        let mut table = DeclaredTypes::parse(&fixture(), b"metadata").unwrap();
        table.types[0].name = "example.Outer".into();
        table.types.push(TypeExpr {
            kind: "class".into(),
            name: "example.Outer$Inner$Name".into(),
            scope: Arc::from(""),
            owner: Some(0),
            component: None,
            variance: Arc::from(""),
            arguments: Vec::new(),
        });
        assert_eq!(table.render(5), "example.Outer<? super T>.Inner$Name");
        for _ in 0..257 {
            let component = table.types.len() - 1;
            table.types.push(TypeExpr {
                kind: "array".into(),
                name: Arc::from(""),
                scope: Arc::from(""),
                owner: None,
                component: Some(component),
                variance: Arc::from(""),
                arguments: Vec::new(),
            });
        }
        assert!(table.validate().unwrap_err().0.contains("nesting"));
    }

    #[test]
    fn authoritative_binding_rejects_signature_only_swaps_and_ignores_old_writer_orphans() {
        let root =
            std::env::temp_dir().join(format!("graphite-types-binding-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        std::fs::write(root.join("graph.metadata"), b"metadata").unwrap();
        let original = fixture();
        std::fs::write(root.join("graph.types"), &original).unwrap();
        bind(&root, &original);
        let source = GraphSource::open(&root).unwrap();
        let properties = std::fs::read_to_string(root.join("forward.properties")).unwrap();
        let bv = crate::bvgraph::BvProperties::parse(&properties).unwrap();
        assert_eq!(bv.nodes, 0);
        assert_eq!(bv.arcs, 0);
        assert_eq!(
            DeclaredTypes::load(&source).unwrap().unwrap().render(3),
            "java.util.List<? super T>[]"
        );
        // Changing a type-variable name leaves erased graph.metadata unchanged.
        let mut swapped = original.clone();
        let mut reader = Reader::at(&original, 36);
        let count = reader.count().unwrap();
        for _ in 0..1 {
            reader.string().unwrap();
            reader.string().unwrap();
            reader.string().unwrap();
            reader.optional(count).unwrap();
            reader.optional(count).unwrap();
            reader.string().unwrap();
            reader.references(count).unwrap();
        }
        reader.string().unwrap();
        let variable_name = reader.pos + 4;
        assert_eq!(swapped[variable_name], b'T');
        swapped[variable_name] = b'U';
        assert_eq!(
            DeclaredTypes::parse(&swapped, b"metadata")
                .unwrap()
                .render(3),
            "java.util.List<? super U>[]"
        );
        std::fs::write(root.join("graph.types"), &swapped).unwrap();
        assert!(DeclaredTypes::load(&source)
            .unwrap_err()
            .0
            .contains("digest mismatch"));
        std::fs::remove_file(root.join("graph.types")).unwrap();
        assert!(
            DeclaredTypes::load(&source).is_err(),
            "referenced sidecar must exist"
        );
        std::fs::write(root.join("graph.types"), b"orphan corrupt sidecar").unwrap();
        std::fs::write(root.join("forward.properties"), "nodes=0\narcs=0\n").unwrap();
        assert!(
            DeclaredTypes::load(&source).unwrap().is_none(),
            "an older save removes the declaration"
        );
        std::fs::write(
            root.join("forward.properties"),
            "graphite.declaredTypes.sha256=not-a-digest\n",
        )
        .unwrap();
        assert!(DeclaredTypes::load(&source)
            .unwrap_err()
            .0
            .contains("invalid"));
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn optional_sidecar_survives_container_packing() {
        let root = std::env::temp_dir().join(format!("graphite-types-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let dir = root.join("source");
        std::fs::create_dir_all(&dir).unwrap();
        for name in crate::container::REQUIRED_ENTRIES {
            std::fs::write(dir.join(name), b"metadata").unwrap();
        }
        let source = GraphSource::open(&dir).unwrap();
        assert!(DeclaredTypes::load(&source).unwrap().is_none());
        std::fs::write(dir.join("graph.types"), fixture()).unwrap();
        assert!(
            DeclaredTypes::load(&source).unwrap().is_none(),
            "unbound tables are legacy orphans"
        );
        bind(&dir, &fixture());
        let file = root.join("test.graphite");
        crate::container::pack(&dir, &file).unwrap();
        let packed = DeclaredTypes::load(&GraphSource::open(&file).unwrap())
            .unwrap()
            .unwrap();
        assert_eq!(packed.render(3), "java.util.List<? super T>[]");
        assert_eq!(packed.fields.len(), 2);
        std::fs::write(dir.join("graph.metadata"), b"changed").unwrap();
        assert!(DeclaredTypes::load(&source).is_err());
        std::fs::remove_dir_all(root).unwrap();
    }
}

#[cfg(test)]
#[path = "types_wire_tests.rs"]
mod wire_tests;

//! GTY05 erased descriptors are projected from validated type/signature references.
use super::repr::{Texts, TypeExpr};
use super::TypeError;

#[derive(Debug)]
pub(super) struct RawSignature {
    pub(super) parameters: Vec<usize>,
    pub(super) returns: usize,
}

fn primitive_descriptor(name: &str) -> Option<&'static str> {
    Some(match name {
        "boolean" => "Z",
        "byte" => "B",
        "char" => "C",
        "short" => "S",
        "int" => "I",
        "long" => "J",
        "float" => "F",
        "double" => "D",
        "void" => "V",
        _ => return None,
    })
}

/// Load-local validation facts only; no names or rendered descriptors are retained.
pub(super) struct RawValidation(Vec<u8>);
impl RawValidation {
    pub(super) fn new(count: usize) -> Self {
        Self(vec![0; count])
    }
    pub(super) fn validate(
        &mut self,
        types: &[TypeExpr<usize, u64>],
        texts: &dyn Texts,
        id: usize,
        allow_void: bool,
    ) -> Result<(), TypeError> {
        let state = self
            .0
            .get_mut(id)
            .ok_or_else(|| TypeError("invalid erased type reference".into()))?;
        if *state == 0 {
            validate_raw(types, texts, id, true)?;
            *state = if types[id].kind == 1 && texts.text(types[id].name) == "void" {
                2
            } else {
                1
            };
        }
        if *state == 2 && !allow_void {
            return Err(TypeError("void is not a field or parameter type".into()));
        }
        Ok(())
    }
}

pub(super) fn validate_raw(
    types: &[TypeExpr<usize, u64>],
    texts: &dyn Texts,
    mut id: usize,
    allow_void: bool,
) -> Result<(), TypeError> {
    let mut arrays = 0;
    loop {
        let t = types
            .get(id)
            .ok_or_else(|| TypeError("invalid erased type reference".into()))?;
        let invalid = || TypeError("invalid canonical erased type".into());
        if t.scope != 0 || t.owner.is_some() || !t.arguments.is_empty() || t.variance != 0 {
            return Err(invalid());
        }
        match t.kind {
            0 => {
                if t.name == usize::MAX || t.component.is_some() {
                    return Err(invalid());
                }
                let name = texts.text(t.name);
                if name.split('.').any(str::is_empty) || name.contains(['/', ';', '[']) {
                    return Err(invalid());
                }
                return Ok(());
            }
            1 => {
                if t.name == usize::MAX || t.component.is_some() {
                    return Err(invalid());
                }
                let name = texts.text(t.name);
                if primitive_descriptor(name).is_none()
                    || (name == "void" && (!allow_void || arrays != 0))
                {
                    return Err(invalid());
                }
                return Ok(());
            }
            2 => {
                if t.name != usize::MAX && !texts.text(t.name).is_empty() {
                    return Err(invalid());
                }
                arrays += 1;
                // Also terminates a malformed cyclic array chain before any descriptor rendering.
                if arrays > 255 {
                    return Err(TypeError(
                        "erased array nesting exceeds 255 or is cyclic".into(),
                    ));
                }
                id = t.component.ok_or_else(invalid)?;
            }
            _ => return Err(invalid()),
        }
    }
}

/// Stream canonical descriptor bytes without expanding repeated pool references.
pub(super) fn type_bytes<'a>(
    types: &'a [TypeExpr<usize, u64>],
    texts: &'a dyn Texts,
    mut id: usize,
) -> impl Iterator<Item = u8> + 'a {
    let mut arrays = 0;
    while types[id].kind == 2 {
        arrays += 1;
        id = types[id].component.expect("validated array");
    }
    let t = &types[id];
    let class = t.kind == 0;
    let name = if class {
        texts.text(t.name)
    } else {
        primitive_descriptor(texts.text(t.name)).expect("validated primitive")
    };
    std::iter::repeat_n(b'[', arrays)
        .chain(class.then_some(b'L'))
        .chain(
            name.bytes()
                .map(move |b| if class && b == b'.' { b'/' } else { b }),
        )
        .chain(class.then_some(b';'))
}
pub(super) fn signature_bytes<'a>(
    types: &'a [TypeExpr<usize, u64>],
    texts: &'a dyn Texts,
    signature: &'a RawSignature,
) -> impl Iterator<Item = u8> + 'a {
    std::iter::once(b'(')
        .chain(
            signature
                .parameters
                .iter()
                .flat_map(move |&id| type_bytes(types, texts, id)),
        )
        .chain(std::iter::once(b')'))
        .chain(type_bytes(types, texts, signature.returns))
}
pub(super) fn type_descriptor(
    types: &[TypeExpr<usize, u64>],
    texts: &dyn Texts,
    id: usize,
) -> String {
    String::from_utf8(type_bytes(types, texts, id).collect()).expect("validated descriptor UTF-8")
}
pub(super) fn signature_descriptor(
    types: &[TypeExpr<usize, u64>],
    texts: &dyn Texts,
    signature: &RawSignature,
) -> String {
    String::from_utf8(signature_bytes(types, texts, signature).collect())
        .expect("validated descriptor UTF-8")
}
pub(super) fn type_descriptor_length(
    types: &[TypeExpr<usize, u64>],
    texts: &dyn Texts,
    mut id: usize,
) -> usize {
    let mut arrays = 0usize;
    loop {
        let t = &types[id];
        match t.kind {
            0 => {
                return arrays
                    .saturating_add(texts.text(t.name).len())
                    .saturating_add(2)
            }
            1 => return arrays.saturating_add(1),
            2 => {
                arrays += 1;
                id = t.component.expect("validated array");
            }
            _ => unreachable!("validated erased type"),
        }
    }
}
pub(super) fn signature_descriptor_length(
    types: &[TypeExpr<usize, u64>],
    texts: &dyn Texts,
    signature: &RawSignature,
) -> usize {
    signature
        .parameters
        .iter()
        .chain(std::iter::once(&signature.returns))
        .fold(2usize, |length, &id| {
            length.saturating_add(type_descriptor_length(types, texts, id))
        })
}

/// Stable chunk boundaries make all text hashes identical for streamed and borrowed input.
pub(super) fn hash_bytes(state: &mut impl std::hash::Hasher, bytes: impl Iterator<Item = u8>) {
    let mut buffer = [0u8; 1024];
    let mut used = 0;
    for byte in bytes {
        buffer[used] = byte;
        used += 1;
        if used == buffer.len() {
            state.write(&buffer);
            used = 0;
        }
    }
    if used != 0 {
        state.write(&buffer[..used]);
    }
    state.write_u8(0xff);
}

pub(super) fn hash_text(state: &mut impl std::hash::Hasher, value: &str) {
    for chunk in value.as_bytes().chunks(1024) {
        state.write(chunk);
    }
    state.write_u8(0xff);
}

/// UTF-8 byte length also bounds UTF-16 code units, matching both shared wire readers.
pub(super) fn check_descriptor_length(length: usize) -> Result<(), TypeError> {
    if length > i32::MAX as usize {
        return Err(TypeError(
            "erased descriptor exceeds representable text length".into(),
        ));
    }
    Ok(())
}

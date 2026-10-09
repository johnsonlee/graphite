use super::MutableDeclaredTypes as DeclaredTypes;
use super::*;
use crate::strings::StringTable;

// Independent Java-serialization writer: ratio=1 makes every entry a full block.
// The decoder still sees the production object's fields and primitive arrays.
fn utf(out: &mut Vec<u8>, value: &str) {
    out.extend((value.len() as u16).to_be_bytes());
    out.extend(value.as_bytes());
}
fn descriptor(out: &mut Vec<u8>, name: &str, fields: &[(u8, &str)]) {
    out.push(0x72);
    utf(out, name);
    out.extend(0i64.to_be_bytes());
    out.push(2);
    out.extend((fields.len() as u16).to_be_bytes());
    for (kind, name) in fields {
        out.push(*kind);
        utf(out, name);
        if *kind == b'L' || *kind == b'[' {
            out.push(0x74);
            utf(out, "Ljava/lang/Object;");
        }
    }
    out.extend([0x78, 0x70]);
}
fn serialized(units: &[Vec<u16>]) -> Vec<u8> {
    let mut out = vec![0xac, 0xed, 0, 5, 0x73];
    descriptor(
        &mut out,
        "it.unimi.dsi.util.FrontCodedStringList",
        &[(b'Z', "utf8"), (b'L', "charFrontCodedList")],
    );
    out.extend([0, 0x73]);
    descriptor(
        &mut out,
        "it.unimi.dsi.fastutil.chars.CharArrayFrontCodedList",
        &[(b'I', "n"), (b'I', "ratio"), (b'[', "array")],
    );
    out.extend((units.len() as i32).to_be_bytes());
    out.extend(1i32.to_be_bytes());
    out.push(0x75);
    descriptor(&mut out, "[C", &[]);
    out.extend((units.iter().map(|s| s.len() + 1).sum::<usize>() as i32).to_be_bytes());
    for value in units {
        assert!(value.len() < 0x8000);
        out.extend((value.len() as u16).to_be_bytes());
        for unit in value {
            out.extend(unit.to_be_bytes());
        }
    }
    out
}
fn serialized_utf8(entries: &[Vec<u8>]) -> Vec<u8> {
    let mut out = vec![0xac, 0xed, 0, 5, 0x73];
    descriptor(
        &mut out,
        "it.unimi.dsi.util.FrontCodedStringList",
        &[(b'Z', "utf8"), (b'L', "byteFrontCodedList")],
    );
    out.extend([1, 0x73]);
    descriptor(
        &mut out,
        "it.unimi.dsi.fastutil.bytes.ByteArrayFrontCodedList",
        &[(b'I', "n"), (b'I', "ratio"), (b'[', "array")],
    );
    out.extend((entries.len() as i32).to_be_bytes());
    out.extend(1i32.to_be_bytes());
    out.push(0x75);
    descriptor(&mut out, "[B", &[]);
    let mut bytes = Vec::new();
    for value in entries {
        assert!(value.len() < 128);
        bytes.push(value.len() as u8);
        bytes.extend(value);
    }
    out.extend((bytes.len() as i32).to_be_bytes());
    out.extend(bytes);
    out
}

struct SharedWire {
    bytes: Vec<u8>,
    strings: Vec<u8>,
    dictionary: Vec<String>,
    text_offsets: Vec<usize>,
    reference_offsets: Vec<usize>,
    sections: Vec<(&'static str, usize, usize, usize)>,
}
fn shared_wire(table: &DeclaredTypes) -> SharedWire {
    // Read only the independently written GTY02 pool/body, never the reader under test.
    let old = encode(table, true, &[]);
    let int = |at: usize| i32::from_be_bytes(old.bytes[at..at + 4].try_into().unwrap()) as usize;
    let count = int(36);
    let mut at = 40;
    let mut local = Vec::new();
    for _ in 0..count {
        let length = int(at);
        at += 4;
        local.push(
            std::str::from_utf8(&old.bytes[at..at + length])
                .unwrap()
                .to_owned(),
        );
        at += length;
    }
    let mut dictionary = local.clone();
    dictionary.extend([
        "core-only unrelated string".to_owned(),
        "另一核心🚀".to_owned(),
    ]);
    dictionary.sort_by(|a, b| crate::strings::java_cmp(a, b));
    dictionary.dedup();
    let units = dictionary
        .iter()
        .map(|s| s.encode_utf16().collect())
        .collect::<Vec<_>>();
    let strings = serialized(&units);
    let mut bytes = Vec::new();
    bytes.extend(0x47545903i32.to_be_bytes());
    bytes.extend(Sha256::digest(b"metadata"));
    bytes.extend(Sha256::digest(&strings));
    bytes.extend(&old.bytes[at..]);
    let text_offsets = old
        .text_offsets
        .iter()
        .map(|offset| offset - at + 68)
        .collect::<Vec<_>>();
    for (&old_offset, &new_offset) in old.text_offsets.iter().zip(&text_offsets) {
        let value = &local[int(old_offset)];
        let global = dictionary.iter().position(|text| text == value).unwrap();
        bytes[new_offset..new_offset + 4].copy_from_slice(&(global as i32).to_be_bytes());
    }
    SharedWire {
        bytes,
        strings,
        dictionary,
        text_offsets,
        reference_offsets: old
            .reference_offsets
            .iter()
            .map(|offset| offset - at + 68)
            .collect(),
        sections: old
            .sections
            .iter()
            .map(|&(name, count, start, end)| {
                (name, count - at + 68, start - at + 68, end - at + 68)
            })
            .collect(),
    }
}

#[test]
fn shared_wire_uses_global_ids_and_preserves_complete_v1_v2_values_and_order() {
    let expected = full_table();
    let wire = shared_wire(&expected);
    let strings = StringTable::from_serialized_for_declared_types(&wire.strings).unwrap();
    assert_eq!(
        strings.serialized_digest().unwrap().as_slice(),
        Sha256::digest(&wire.strings).as_slice()
    );
    assert_compact_values(
        &crate::types::DeclaredTypes::parse_with_strings(&wire.bytes, b"metadata", &strings)
            .unwrap(),
        &expected,
    );
    let actual = DeclaredTypes::parse_with_strings(&wire.bytes, b"metadata", &strings).unwrap();
    assert_eq!(actual, expected);
    assert_eq!(
        actual.fields.iter().collect::<Vec<_>>(),
        expected.fields.iter().collect::<Vec<_>>()
    );
    assert_eq!(
        actual.methods.iter().collect::<Vec<_>>(),
        expected.methods.iter().collect::<Vec<_>>()
    );
    assert_eq!(
        actual.classes.iter().collect::<Vec<_>>(),
        expected.classes.iter().collect::<Vec<_>>()
    );
    for id in 0..actual.types.len() {
        assert_eq!(actual.render(id), expected.render(id));
    }
    for pooled in [false, true] {
        assert_eq!(
            DeclaredTypes::parse_with_strings(
                &encode(&expected, pooled, &[]).bytes,
                b"metadata",
                &strings
            )
            .unwrap(),
            expected
        );
    }
    assert!(DeclaredTypes::parse(&wire.bytes, b"metadata")
        .unwrap_err()
        .0
        .contains("requires verified"));
    let ordinary = StringTable::from_serialized(&wire.strings).unwrap();
    assert!(ordinary.serialized_digest().is_none());
    assert!(
        DeclaredTypes::parse_with_strings(&wire.bytes, b"metadata", &ordinary)
            .unwrap_err()
            .0
            .contains("verified serialized")
    );
}

#[test]
fn shared_digest_covers_trailing_bytes_and_same_id_text_without_changing_legacy_decode() {
    let wire = shared_wire(&full_table());
    let mut trailing = wire.strings.clone();
    trailing.extend(b"legacy accepted trailing bytes");
    let strings = StringTable::from_serialized_for_declared_types(&trailing).unwrap();
    assert_eq!(strings.len(), wire.dictionary.len());
    assert!(
        DeclaredTypes::parse_with_strings(&wire.bytes, b"metadata", &strings)
            .unwrap_err()
            .0
            .contains("digest mismatch")
    );
    let mut rebound = wire.bytes.clone();
    rebound[36..68].copy_from_slice(&Sha256::digest(&trailing));
    assert_eq!(
        DeclaredTypes::parse_with_strings(&rebound, b"metadata", &strings).unwrap(),
        full_table()
    );
    let mut changed = wire.dictionary.clone();
    let index = changed.iter().position(|s| s == "java.util.List").unwrap();
    changed[index] = "java.util.Mist".into();
    let changed = serialized(
        &changed
            .iter()
            .map(|s| s.encode_utf16().collect())
            .collect::<Vec<_>>(),
    );
    let table = StringTable::from_serialized_for_declared_types(&changed).unwrap();
    assert!(
        DeclaredTypes::parse_with_strings(&wire.bytes, b"metadata", &table)
            .unwrap_err()
            .0
            .contains("digest mismatch")
    );
    assert!(
        DeclaredTypes::parse_with_strings(&wire.bytes, b"wrong metadata", &table)
            .unwrap_err()
            .0
            .contains("metadata digest mismatch")
    );
}

#[test]
fn shared_all_global_id_positions_references_and_partial_inputs_remain_checked() {
    let wire = shared_wire(&full_table());
    let strings = StringTable::from_serialized_for_declared_types(&wire.strings).unwrap();
    for &offset in &wire.text_offsets {
        for invalid in [-1, wire.dictionary.len() as i32, i32::MAX] {
            let mut bytes = wire.bytes.clone();
            bytes[offset..offset + 4].copy_from_slice(&invalid.to_be_bytes());
            assert!(
                DeclaredTypes::parse_with_strings(&bytes, b"metadata", &strings).is_err(),
                "global ID at {offset}"
            );
        }
    }
    for &offset in &wire.reference_offsets {
        let mut bytes = wire.bytes.clone();
        bytes[offset..offset + 4].copy_from_slice(&i32::MAX.to_be_bytes());
        assert!(
            DeclaredTypes::parse_with_strings(&bytes, b"metadata", &strings).is_err(),
            "reference at {offset}"
        );
    }
    for end in 0..wire.bytes.len() {
        assert!(
            DeclaredTypes::parse_with_strings(&wire.bytes[..end], b"metadata", &strings).is_err(),
            "partial {end}"
        );
    }
    let mut trailing = wire.bytes.clone();
    trailing.push(0);
    assert!(DeclaredTypes::parse_with_strings(&trailing, b"metadata", &strings).is_err());
}

#[test]
fn shared_utf16_rejects_only_referenced_bad_ids_and_utf8_is_checked_before_access() {
    let wire = shared_wire(&full_table());
    let mut units = wire
        .dictionary
        .iter()
        .map(|s| s.encode_utf16().collect())
        .collect::<Vec<Vec<u16>>>();
    units.push(vec![0xd800]);
    let raw = serialized(&units);
    let strings = StringTable::from_serialized_for_declared_types(&raw).unwrap();
    assert_eq!(strings.get(units.len() - 1), "�");
    assert!(strings.strict_get(units.len() - 1).is_err());
    let mut bytes = wire.bytes.clone();
    bytes[36..68].copy_from_slice(&Sha256::digest(&raw));
    assert_eq!(
        DeclaredTypes::parse_with_strings(&bytes, b"metadata", &strings).unwrap(),
        full_table()
    );
    let offset = wire.text_offsets[0];
    bytes[offset..offset + 4].copy_from_slice(&((units.len() - 1) as i32).to_be_bytes());
    assert!(
        DeclaredTypes::parse_with_strings(&bytes, b"metadata", &strings)
            .unwrap_err()
            .0
            .contains("UTF-16")
    );
    let valid = serialized_utf8(&[b"ordinary".to_vec(), "类型🚀".as_bytes().to_vec()]);
    let table = StringTable::from_serialized_for_declared_types(&valid).unwrap();
    assert_eq!(table.strict_get(1).unwrap(), "类型🚀");
    let invalid = serialized_utf8(&[b"ordinary".to_vec(), vec![0xff]]);
    assert!(StringTable::from_serialized_for_declared_types(&invalid).is_err());
}

#[test]
fn shared_directory_and_container_load_ignore_forged_identity_and_require_actual_strings() {
    let root = std::env::temp_dir().join(format!("graphite-types-shared-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    let dir = root.join("source");
    std::fs::create_dir_all(&dir).unwrap();
    for name in crate::container::REQUIRED_ENTRIES {
        std::fs::write(dir.join(name), b"metadata").unwrap();
    }
    let wire = shared_wire(&full_table());
    std::fs::write(dir.join("graph.strings"), &wire.strings).unwrap();
    std::fs::write(dir.join("graph.strings.identity"), [0x55; 32]).unwrap();
    std::fs::write(dir.join("graph.types"), &wire.bytes).unwrap();
    super::super::tests::bind(&dir, &wire.bytes);
    for packed in [false, true] {
        let packed_path = root.join("valid.graphite");
        let path = if packed {
            crate::container::pack(&dir, &packed_path).unwrap();
            &packed_path
        } else {
            &dir
        };
        let source = GraphSource::open(path).unwrap();
        assert!(crate::types::DeclaredTypes::needs_shared_strings(&source).unwrap());
        assert_eq!(DeclaredTypes::load(&source).unwrap().unwrap(), full_table());
        let strings = StringTable::load_for_declared_types(&source).unwrap();
        assert_eq!(strings.identity(), Some(&[0x55; 32]));
        assert_eq!(
            DeclaredTypes::load_with_strings(&source, &strings)
                .unwrap()
                .unwrap(),
            full_table()
        );
        let legacy = StringTable::load(&source).unwrap();
        assert!(DeclaredTypes::load_with_strings(&source, &legacy).is_err());
    }
    let mut changed = wire.dictionary.clone();
    let index = changed
        .iter()
        .position(|value| value == "java.util.List")
        .unwrap();
    changed[index] = "java.util.Mist".into();
    let changed = serialized(
        &changed
            .iter()
            .map(|value| value.encode_utf16().collect())
            .collect::<Vec<_>>(),
    );
    std::fs::write(dir.join("graph.strings"), changed).unwrap();
    let original = StringTable::from_serialized(&wire.strings).unwrap();
    std::fs::write(
        dir.join("graph.strings.identity"),
        original.compute_identity(),
    )
    .unwrap();
    for packed in [false, true] {
        let packed_path = root.join("forged.graphite");
        let path = if packed {
            crate::container::pack(&dir, &packed_path).unwrap();
            &packed_path
        } else {
            &dir
        };
        assert!(DeclaredTypes::load(&GraphSource::open(path).unwrap())
            .unwrap_err()
            .0
            .contains("digest mismatch"));
    }
    std::fs::remove_file(dir.join("graph.strings")).unwrap();
    assert!(DeclaredTypes::load(&GraphSource::open(&dir).unwrap()).is_err());
    assert!(crate::container::pack(&dir, &root.join("missing-strings.graphite")).is_err());
    std::fs::write(dir.join("forward.properties"), b"nodes=0\narcs=0\n").unwrap();
    assert!(DeclaredTypes::load(&GraphSource::open(&dir).unwrap())
        .unwrap()
        .is_none());
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn shared_format_preserves_duplicate_shape_cycle_depth_and_expansion_checks() {
    let wire = shared_wire(&full_table());
    let strings = StringTable::from_serialized_for_declared_types(&wire.strings).unwrap();
    for &(name, count_offset, start, end) in &wire.sections {
        let mut bytes = wire.bytes.clone();
        let count = i32::from_be_bytes(bytes[count_offset..count_offset + 4].try_into().unwrap());
        bytes[count_offset..count_offset + 4].copy_from_slice(&(count + 1).to_be_bytes());
        let row = bytes[start..end].to_vec();
        bytes.splice(end..end, row);
        assert!(
            DeclaredTypes::parse_with_strings(&bytes, b"metadata", &strings)
                .unwrap_err()
                .0
                .contains(&format!("duplicate {name}"))
        );
    }
    for invalid in 0..4 {
        let mut table = full_table();
        match invalid {
            0 => table.types[0].arguments.push(0),
            1 => table.types[0].kind = "primitive".into(),
            _ => {
                table.fields.clear();
                table.methods.clear();
                table.classes.clear();
                table.types.clear();
                let length = if invalid == 2 { 257 } else { 18 };
                for i in 0..length {
                    table.types.push(TypeExpr {
                        kind: "class".into(),
                        name: "Example".into(),
                        scope: "".into(),
                        owner: None,
                        component: None,
                        variance: "".into(),
                        arguments: if i == 0 {
                            vec![]
                        } else if invalid == 2 {
                            vec![i - 1]
                        } else {
                            vec![i - 1, i - 1]
                        },
                    });
                }
            }
        }
        let wire = shared_wire(&table);
        let strings = StringTable::from_serialized_for_declared_types(&wire.strings).unwrap();
        assert!(
            DeclaredTypes::parse_with_strings(&wire.bytes, b"metadata", &strings).is_err(),
            "validation case {invalid}"
        );
    }
}

// Rebind all text references to an unsorted dictionary containing equal values
// at distinct IDs. The wire writer remains independent of the production reader.
fn unsorted_equal_ids(mut wire: SharedWire) -> SharedWire {
    let old = wire.dictionary.clone();
    wire.dictionary.reverse();
    let unique = wire.dictionary.len();
    wire.dictionary.extend(wire.dictionary.clone());
    for (i, offset) in wire.text_offsets.iter().enumerate() {
        let old_id =
            i32::from_be_bytes(wire.bytes[*offset..*offset + 4].try_into().unwrap()) as usize;
        let id = wire.dictionary[..unique]
            .iter()
            .position(|v| v == &old[old_id])
            .unwrap()
            + if i % 2 == 0 { unique } else { 0 };
        wire.bytes[*offset..*offset + 4].copy_from_slice(&(id as i32).to_be_bytes());
    }
    wire.strings = serialized(
        &wire
            .dictionary
            .iter()
            .map(|s| s.encode_utf16().collect())
            .collect::<Vec<_>>(),
    );
    wire.bytes[36..68].copy_from_slice(&Sha256::digest(&wire.strings));
    wire
}

#[test]
fn compact_shared_backing_survives_original_drop_without_copying_text() {
    let expected = full_table();
    let wire = shared_wire(&expected);
    let strings = StringTable::from_serialized_for_declared_types(&wire.strings).unwrap();
    let clone = strings.clone();
    assert_eq!(strings.get(0).as_ptr(), clone.get(0).as_ptr());
    let actual =
        crate::types::DeclaredTypes::parse_with_strings(&wire.bytes, b"metadata", &strings)
            .unwrap();
    let id = wire
        .dictionary
        .iter()
        .position(|v| v == "java.util.List")
        .unwrap();
    assert_eq!(actual.type_expr(0).name.as_ptr(), strings.get(id).as_ptr());
    drop(strings);
    drop(clone);
    assert_compact_values(&actual, &expected);
    let view = actual.type_expr(2);
    assert_eq!(
        (view.kind, view.variance, view.component),
        ("wildcard", "super", Some(1))
    );
}

#[test]
fn compact_unsorted_global_ids_compare_full_values_without_dictionary_search() {
    let expected = full_table();
    let wire = unsorted_equal_ids(shared_wire(&expected));
    let strings = StringTable::from_serialized_for_declared_types(&wire.strings).unwrap();
    let actual =
        crate::types::DeclaredTypes::parse_with_strings(&wire.bytes, b"metadata", &strings)
            .unwrap();
    drop(strings);
    assert_compact_values(&actual, &expected);
    assert_eq!(
        actual.field_type("Example", "not-a-field", "Ljava/util/List;"),
        None
    );
}

#[test]
fn compact_global_equal_value_keys_are_duplicates_for_all_three_maps() {
    let expected = full_table();
    let original = unsorted_equal_ids(shared_wire(&expected));
    let strings = StringTable::from_serialized_for_declared_types(&original.strings).unwrap();
    let half = original.dictionary.len() / 2;
    for (section, width) in [("field", 3), ("method", 3), ("class", 1)] {
        let mut bytes = original.bytes.clone();
        let (_, _, first, second) = *original
            .sections
            .iter()
            .find(|(name, _, _, _)| *name == section)
            .unwrap();
        for k in 0..width {
            let at = first + 4 * k;
            let id = i32::from_be_bytes(bytes[at..at + 4].try_into().unwrap()) as usize;
            let distinct = (id + half) % original.dictionary.len();
            bytes[second + 4 * k..second + 4 * k + 4]
                .copy_from_slice(&(distinct as i32).to_be_bytes());
        }
        let error = crate::types::DeclaredTypes::parse_with_strings(&bytes, b"metadata", &strings)
            .unwrap_err();
        assert!(error.0.contains(&format!("duplicate {section}")), "{error}");
    }
}

#[test]
fn compact_legacy_pool_drops_unused_dictionary_values() {
    let expected = full_table();
    let wire = encode(&expected, true, &[b"unused-unique-text"]);
    let actual = crate::types::DeclaredTypes::parse(&wire.bytes, b"metadata").unwrap();
    assert_compact_values(&actual, &expected);
    let crate::types::repr::Storage::Compact(table) = &actual.storage else {
        panic!("compact")
    };
    let crate::types::repr::TextStore::Owned(texts) = &table.texts else {
        panic!("legacy owned pool")
    };
    assert!(!texts.iter().any(|v| v.as_ref() == "unused-unique-text"));
}

#[path = "types_structural_tests.rs"]
mod structural_tests;

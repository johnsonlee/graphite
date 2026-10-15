//! Independently assembled GSCHEMA/1 bytes exercise the decoder beyond checksum
//! validation. These fixtures do not call the production encoder to form input.
use graphite_schema::*;
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;

fn u32_bytes(out: &mut Vec<u8>, value: u32) {
    out.extend(value.to_le_bytes());
}
fn u64_bytes(out: &mut Vec<u8>, value: u64) {
    out.extend(value.to_le_bytes());
}
fn text(out: &mut Vec<u8>, value: &[u8]) {
    u64_bytes(out, value.len() as u64);
    out.extend(value);
}
fn name(out: &mut Vec<u8>, value: &str) {
    text(out, b"u");
    text(out, value.as_bytes());
}
fn q(value: &str) -> QualifiedName {
    QualifiedName::new("u", value)
}

fn container(sections: &[Vec<u8>; 4]) -> Vec<u8> {
    let mut payload = vec![];
    u32_bytes(&mut payload, 4);
    for (index, section) in sections.iter().enumerate() {
        u32_bytes(&mut payload, index as u32 + 1);
        u64_bytes(&mut payload, section.len() as u64);
        payload.extend(Sha256::digest(section));
    }
    for section in sections {
        payload.extend(section);
    }
    let mut result = b"GSCHEMA\0".to_vec();
    u32_bytes(&mut result, 1);
    u64_bytes(&mut result, payload.len() as u64);
    result.extend(Sha256::digest(&payload));
    result.extend(payload);
    result
}
fn rehash_payload(bytes: &mut [u8]) {
    let digest = Sha256::digest(&bytes[52..]);
    bytes[20..52].copy_from_slice(&digest);
}
fn empty_section() -> Vec<u8> {
    0u32.to_le_bytes().to_vec()
}
fn field_wire(out: &mut Vec<u8>, field_name: &str, descriptor: &[u8], required: u8, nullable: u8) {
    name(out, field_name);
    out.extend(descriptor);
    out.extend([required, nullable, 0]); // No semantic role.
}

struct Golden {
    sections: [Vec<u8>; 4],
    document: Document,
    descriptor_offset: usize,
    required_flag_offset: usize,
    group_count_offset: usize,
    group_offset: usize,
    row_count_offset: usize,
    row_offset: usize,
    row_size_offset: usize,
    row_data_offset: usize,
}
fn golden() -> Golden {
    // String ID 3 is lambda. No semantic name resolution is needed.
    let mut strings = vec![];
    u32_bytes(&mut strings, 1);
    u32_bytes(&mut strings, 3);
    text(&mut strings, "λ".as_bytes());
    let mut profiles = vec![];
    u32_bytes(&mut profiles, 1);
    name(&mut profiles, "profile");
    text(&mut profiles, b"r1");
    let mut definitions = vec![];
    u32_bytes(&mut definitions, 1);
    u32_bytes(&mut definitions, 5);
    name(&mut definitions, "future");
    text(&mut definitions, b"r1");
    name(&mut definitions, "node");
    u32_bytes(&mut definitions, 4);
    name(&mut definitions, "b");
    let descriptor_offset = definitions.len();
    definitions.push(1); // Bool descriptor.
    let required_flag_offset = definitions.len();
    definitions.extend([1, 0, 0]);
    field_wire(&mut definitions, "s", &[5], 1, 0); // StringRef.
    field_wire(&mut definitions, "r", &[7, 1, 9, 0, 0, 0], 1, 0); // Ref(table 9).
    field_wire(&mut definitions, "o", &[3], 0, 1); // Nullable optional UInt64.
    let mut tables = vec![];
    u32_bytes(&mut tables, 1);
    u32_bytes(&mut tables, 9);
    name(&mut tables, "t");
    let group_count_offset = tables.len();
    u32_bytes(&mut tables, 1);
    let group_offset = tables.len();
    u32_bytes(&mut tables, 5);
    let row_count_offset = tables.len();
    u32_bytes(&mut tables, 1);
    let row_offset = tables.len();
    u32_bytes(&mut tables, 17);
    let row_size_offset = tables.len();
    u64_bytes(&mut tables, 11);
    let row_data_offset = tables.len();
    // Four present fields, only field 3 null, true, StringRef(3), typed Ref(17).
    tables.extend([0x0f, 0x08, 0x01, 3, 0, 0, 0, 17, 0, 0, 0]);
    let document = Document {
        strings: BTreeMap::from([(3, "λ".into())]),
        profiles: vec![Profile {
            name: q("profile"),
            revision: "r1".into(),
        }],
        definitions: BTreeMap::from([(
            5,
            Definition {
                name: q("future"),
                revision: "r1".into(),
                category: q("node"),
                fields: vec![
                    Field {
                        name: q("b"),
                        descriptor: Descriptor::Bool,
                        required: true,
                        nullable: false,
                        role: None,
                    },
                    Field {
                        name: q("s"),
                        descriptor: Descriptor::String,
                        required: true,
                        nullable: false,
                        role: None,
                    },
                    Field {
                        name: q("r"),
                        descriptor: Descriptor::Ref(Some(9)),
                        required: true,
                        nullable: false,
                        role: None,
                    },
                    Field {
                        name: q("o"),
                        descriptor: Descriptor::UInt64,
                        required: false,
                        nullable: true,
                        role: None,
                    },
                ],
            },
        )]),
        tables: BTreeMap::from([(
            9,
            Table {
                name: q("t"),
                rows: BTreeMap::from([(
                    17,
                    Record {
                        layout: 5,
                        fields: vec![
                            Some(Value::Bool(true)),
                            Some(Value::String(3)),
                            Some(Value::Ref(Reference { table: 9, row: 17 })),
                            Some(Value::Null),
                        ],
                    },
                )]),
            },
        )]),
    };
    Golden {
        sections: [strings, profiles, definitions, tables],
        document,
        descriptor_offset,
        required_flag_offset,
        group_count_offset,
        group_offset,
        row_count_offset,
        row_offset,
        row_size_offset,
        row_data_offset,
    }
}
fn invalid(bytes: &[u8]) {
    assert!(
        matches!(decode(bytes, &Limits::default()), Err(Error::Invalid(_))),
        "expected parser/validator rejection beyond checksums: {:?}",
        decode(bytes, &Limits::default())
    );
}

#[test]
fn manually_assembled_golden_contract_decodes_and_matches_encoder() {
    let fixture = golden();
    let bytes = container(&fixture.sections);
    assert_eq!(
        decode(&bytes, &Limits::default()).unwrap(),
        fixture.document
    );
    assert_eq!(
        encode(&fixture.document, &Limits::default()).unwrap(),
        bytes
    );
    // The golden explicitly checks typed references occupy one u32, not two,
    // and the null optional field consumes no UInt64 bytes.
    assert_eq!(fixture.sections[3].len() - fixture.row_data_offset, 11);
}

#[test]
fn directory_lengths_order_digests_and_trailing_bytes_are_checked() {
    let golden = container(&golden().sections);
    for (offset, replacement) in [(52, 3u32), (56, 2u32), (100, 1u32)] {
        let mut bytes = golden.clone();
        bytes[offset..offset + 4].copy_from_slice(&replacement.to_le_bytes());
        rehash_payload(&mut bytes);
        invalid(&bytes);
    }
    let mut bytes = golden.clone();
    bytes[60..68].copy_from_slice(&u64::MAX.to_le_bytes());
    rehash_payload(&mut bytes);
    assert!(decode(&bytes, &Limits::default()).is_err());
    let mut bytes = golden.clone();
    bytes[68] ^= 1; // First section hash, with valid outer payload hash.
    rehash_payload(&mut bytes);
    assert!(matches!(
        decode(&bytes, &Limits::default()),
        Err(Error::Checksum)
    ));
    let mut trailing_file = golden.clone();
    trailing_file.push(0);
    invalid(&trailing_file);
    let mut trailing_payload = golden;
    trailing_payload.push(0);
    let length = trailing_payload.len() as u64 - 52;
    trailing_payload[12..20].copy_from_slice(&length.to_le_bytes());
    rehash_payload(&mut trailing_payload);
    invalid(&trailing_payload);
}

#[test]
fn checksummed_invalid_utf8_flags_tags_and_bitmaps_are_rejected() {
    let fixture = golden();
    // String body offset: count + id + u64 length.
    let mut sections = fixture.sections.clone();
    sections[0][16] = 0xff;
    invalid(&container(&sections));
    let mut sections = fixture.sections.clone();
    sections[2][fixture.required_flag_offset] = 2;
    invalid(&container(&sections));
    let mut sections = fixture.sections.clone();
    sections[2][fixture.descriptor_offset] = 255;
    invalid(&container(&sections));
    for (offset, replacement) in [
        (fixture.row_data_offset, 0x8f),     // Padding presence bit.
        (fixture.row_data_offset + 1, 0x88), // Padding null bit.
        (fixture.row_data_offset, 0x07),     // Null field 3 lacks presence.
        (fixture.row_data_offset + 2, 0x02), // Invalid boolean value.
    ] {
        let mut sections = fixture.sections.clone();
        sections[3][offset] = replacement;
        invalid(&container(&sections));
    }
}

#[test]
fn checksummed_duplicate_ids_groups_and_row_boundaries_are_rejected() {
    let fixture = golden();
    for section in [0, 2, 3] {
        let mut sections = fixture.sections.clone();
        let entry = sections[section][4..].to_vec();
        sections[section][..4].copy_from_slice(&2u32.to_le_bytes());
        sections[section].extend(entry);
        invalid(&container(&sections));
    }
    let mut sections = fixture.sections.clone();
    let row = sections[3][fixture.row_offset..].to_vec();
    sections[3][fixture.row_count_offset..fixture.row_count_offset + 4]
        .copy_from_slice(&2u32.to_le_bytes());
    sections[3].extend(row);
    invalid(&container(&sections));
    let mut sections = fixture.sections.clone();
    let group = sections[3][fixture.group_offset..].to_vec();
    sections[3][fixture.group_count_offset..fixture.group_count_offset + 4]
        .copy_from_slice(&2u32.to_le_bytes());
    sections[3].extend(group);
    invalid(&container(&sections));
    let mut sections = fixture.sections.clone();
    sections[3][fixture.row_count_offset..fixture.row_count_offset + 4]
        .copy_from_slice(&0u32.to_le_bytes());
    sections[3].truncate(fixture.row_offset);
    invalid(&container(&sections));
    for length in [10u64, 12, u64::MAX] {
        let mut sections = fixture.sections.clone();
        sections[3][fixture.row_size_offset..fixture.row_size_offset + 8]
            .copy_from_slice(&length.to_le_bytes());
        if length == 12 {
            sections[3].push(0);
        }
        assert!(decode(&container(&sections), &Limits::default()).is_err());
    }
    for section in 0..4 {
        let mut sections = fixture.sections.clone();
        sections[section].push(0);
        invalid(&container(&sections));
    }
}

#[test]
fn checksummed_references_are_validated_after_physical_decoding() {
    let fixture = golden();
    for offset in [fixture.row_data_offset + 3, fixture.row_data_offset + 7] {
        let mut sections = fixture.sections.clone();
        sections[3][offset..offset + 4].copy_from_slice(&999u32.to_le_bytes());
        invalid(&container(&sections)); // Missing string or target row.
    }
    let mut sections = fixture.sections.clone();
    // A valid container with an unknown row layout must not invent empty data.
    sections[3][fixture.group_offset..fixture.group_offset + 4]
        .copy_from_slice(&999u32.to_le_bytes());
    invalid(&container(&sections));
    let mut sections = fixture.sections.clone();
    // Remove the only table; its descriptor still names a now-missing target.
    sections[3] = empty_section();
    invalid(&container(&sections));
}

fn one_field_definition(descriptor: &[u8], required: u8, nullable: u8) -> Vec<u8> {
    let mut data = vec![];
    u32_bytes(&mut data, 1);
    u32_bytes(&mut data, 5);
    name(&mut data, "recursive");
    text(&mut data, b"r1");
    name(&mut data, "expression");
    u32_bytes(&mut data, 1);
    field_wire(&mut data, "child", descriptor, required, nullable);
    data
}
fn one_row(data: &[u8]) -> Vec<u8> {
    let mut result = vec![];
    u32_bytes(&mut result, 1);
    u32_bytes(&mut result, 9);
    name(&mut result, "t");
    for value in [1, 5, 1, 17] {
        u32_bytes(&mut result, value);
    }
    u64_bytes(&mut result, data.len() as u64);
    result.extend(data);
    result
}

#[test]
fn recursive_inline_records_and_descriptors_obey_depth_and_item_budgets() {
    let limits = Limits {
        max_depth: 8,
        ..Limits::default()
    };
    let mut deep_descriptor = vec![8; 40]; // List<List<...Bool>>.
    deep_descriptor.push(1);
    let sections = [
        empty_section(),
        empty_section(),
        one_field_definition(&deep_descriptor, 0, 0),
        empty_section(),
    ];
    assert!(matches!(
        decode(&container(&sections), &limits),
        Err(Error::Limit("depth"))
    ));
    let mut nested_record = vec![];
    for _ in 0..40 {
        nested_record.extend([1, 0]); // Child present, nonnull.
        u32_bytes(&mut nested_record, 5); // Another instance of this layout.
    }
    nested_record.extend([0, 0]); // Last optional child absent.
    let sections = [
        empty_section(),
        empty_section(),
        one_field_definition(&[9], 0, 0),
        one_row(&nested_record),
    ];
    assert!(matches!(
        decode(&container(&sections), &limits),
        Err(Error::Limit("depth"))
    ));
    assert!(
        decode(
            &container(&sections),
            &Limits {
                max_depth: 128,
                ..Limits::default()
            }
        )
        .is_ok(),
        "finite nested records are valid when within budget"
    );
    // A list of null values has no element bytes, but still consumes work/items.
    let mut list_row = vec![1, 0];
    u32_bytes(&mut list_row, u32::MAX);
    let sections = [
        empty_section(),
        empty_section(),
        one_field_definition(&[8, 0], 1, 0),
        one_row(&list_row),
    ];
    assert!(matches!(
        decode(&container(&sections), &Limits::default()),
        Err(Error::Limit("items"))
    ));
}

#[test]
fn encoder_output_decodes_with_the_same_resource_limits() {
    let fixture = golden();
    for max_items in 1..64 {
        let limits = Limits {
            max_items,
            ..Limits::default()
        };
        if let Ok(encoded) = encode(&fixture.document, &limits) {
            assert_eq!(
                decode(&encoded, &limits).unwrap_or_else(|e| panic!(
                    "encoder accepted item budget {max_items}, decoder rejected it: {e}"
                )),
                fixture.document
            );
        }
    }
}

#[test]
fn manually_assembled_multibyte_bitmaps_and_remaining_value_forms() {
    let mut definitions = vec![];
    u32_bytes(&mut definitions, 2);
    u32_bytes(&mut definitions, 5);
    name(&mut definitions, "all-values");
    text(&mut definitions, b"r1");
    name(&mut definitions, "literal");
    u32_bytes(&mut definitions, 9);
    for (field, descriptor, nullable) in [
        ("signed", vec![2], 0),
        ("unsigned", vec![3], 0),
        ("nan", vec![4], 0),
        ("blob", vec![6], 0),
        ("ref", vec![7, 0], 0),
        ("list", vec![8, 0], 0),
        ("record", vec![9], 0),
        ("null", vec![0], 1),
        ("bool", vec![1], 0),
    ] {
        field_wire(&mut definitions, field, &descriptor, 1, nullable);
    }
    u32_bytes(&mut definitions, 6);
    name(&mut definitions, "empty-inline");
    text(&mut definitions, b"r1");
    name(&mut definitions, "literal");
    u32_bytes(&mut definitions, 0);
    let mut row = vec![0xff, 0x01, 0x80, 0x00]; // Nine present fields; field 7 null.
    row.extend([0, 0, 0, 0, 0, 0, 0, 0x80]); // i64::MIN.
    row.extend([0xff; 8]); // u64::MAX.
    row.extend([0x42, 0, 0, 0, 0, 0, 0xf8, 0x7f]); // Quiet NaN with payload 0x42.
    u64_bytes(&mut row, 3);
    row.extend([0, 255, 1]);
    u32_bytes(&mut row, 9); // Heterogeneous reference table.
    u32_bytes(&mut row, 17);
    u32_bytes(&mut row, 3); // List<Null>, no bytes per element.
    u32_bytes(&mut row, 6); // Zero-field inline record, no bitmaps.
    row.push(0); // Field 8 false.
    let sections = [empty_section(), empty_section(), definitions, one_row(&row)];
    let bytes = container(&sections);
    let decoded = decode(&bytes, &Limits::default()).unwrap();
    assert_eq!(
        decoded.tables[&9].rows[&17].fields,
        vec![
            Some(Value::Int64(i64::MIN)),
            Some(Value::UInt64(u64::MAX)),
            Some(Value::Float64(0x7ff8_0000_0000_0042)),
            Some(Value::Bytes(vec![0, 255, 1])),
            Some(Value::Ref(Reference { table: 9, row: 17 })),
            Some(Value::List(vec![Value::Null, Value::Null, Value::Null])),
            Some(Value::Record(Box::new(Record {
                layout: 6,
                fields: vec![]
            }))),
            Some(Value::Null),
            Some(Value::Bool(false)),
        ]
    );
    assert_eq!(encode(&decoded, &Limits::default()).unwrap(), bytes);
    // Declaring a direct Null field present-but-nonnull is not another encoding
    // of null, even though its descriptor ordinarily consumes no bytes.
    let mut sections = sections;
    let bitmap = sections[3].len() - row.len();
    sections[3][bitmap + 2] = 0;
    invalid(&container(&sections));
}

#[test]
fn checksummed_required_and_nullable_constraints_are_enforced_on_read() {
    let fixture = golden();
    for (presence, nulls) in [(0x0e, 0x08), (0x0f, 0x09)] {
        let mut sections = fixture.sections.clone();
        sections[3][fixture.row_data_offset] = presence;
        sections[3][fixture.row_data_offset + 1] = nulls;
        sections[3].remove(fixture.row_data_offset + 2); // No bool value when absent/null.
        sections[3][fixture.row_size_offset..fixture.row_size_offset + 8]
            .copy_from_slice(&10u64.to_le_bytes());
        invalid(&container(&sections));
    }
    let bytes = container(&fixture.sections);
    for cut in 0..bytes.len() {
        assert!(
            decode(&bytes[..cut], &Limits::default()).is_err(),
            "accepted incomplete file at {cut}"
        );
    }
}

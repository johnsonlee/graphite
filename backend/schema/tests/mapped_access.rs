//! On-demand access correctness, including unknown layouts and sparse wire IDs.
//! These fixtures make no performance or memory-usage claims.
use graphite_schema::*;
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::sync::atomic::{AtomicU64, Ordering};

const TABLE: u32 = 7;
const OTHER: u32 = 1009;
const LAYOUT: u32 = 5;
const BOOL_LAYOUT: u32 = 900;
const ROW: u32 = 11;

fn q(name: &str) -> QualifiedName {
    QualifiedName::new("https://example.test/unregistered-language", name)
}
fn reference(table: u32, row: u32) -> Reference {
    Reference { table, row }
}
fn definition(name: &str, descriptors: Vec<Descriptor>) -> Definition {
    Definition {
        name: q(name),
        revision: "future-1".into(),
        category: q("unknown-category"),
        fields: descriptors
            .into_iter()
            .enumerate()
            .map(|(index, descriptor)| Field {
                name: q(&format!("field-{index}")),
                descriptor,
                required: index != 12,
                nullable: index == 0 || index == 12,
                role: Some(q("unknown-role")),
            })
            .collect(),
    }
}
fn boolean(value: bool) -> Record {
    Record {
        layout: BOOL_LAYOUT,
        fields: vec![Some(Value::Bool(value))],
    }
}
fn fixture() -> Document {
    let record = |target, optional| Record {
        layout: LAYOUT,
        fields: vec![
            Some(Value::Null),
            Some(Value::Bool(true)),
            Some(Value::Int64(i64::MIN)),
            Some(Value::UInt64(u64::MAX)),
            Some(Value::Float64(0x7ff8_0000_0000_0042)),
            Some(Value::String(u32::MAX)),
            Some(Value::Bytes(vec![0, 255, 128, 1])),
            Some(Value::Ref(reference(TABLE, target))),
            Some(Value::Ref(reference(OTHER, 0))),
            Some(Value::List(vec![
                Value::Float64((-0.0f64).to_bits()),
                Value::Float64(f64::INFINITY.to_bits()),
            ])),
            Some(Value::Record(Box::new(boolean(false)))),
            Some(Value::List(vec![
                Value::List(vec![Value::Ref(reference(TABLE, target))]),
                Value::List(vec![]),
            ])),
            optional,
        ],
    };
    Document {
        strings: BTreeMap::from([(0, String::new()), (u32::MAX, "λ\0future 🦀".into())]),
        definitions: BTreeMap::from([
            (
                LAYOUT,
                definition(
                    "unregistered-expression",
                    vec![
                        Descriptor::Null,
                        Descriptor::Bool,
                        Descriptor::Int64,
                        Descriptor::UInt64,
                        Descriptor::Float64,
                        Descriptor::String,
                        Descriptor::Bytes,
                        Descriptor::Ref(Some(TABLE)),
                        Descriptor::Ref(None),
                        Descriptor::List(Box::new(Descriptor::Float64)),
                        Descriptor::Record,
                        Descriptor::List(Box::new(Descriptor::List(Box::new(Descriptor::Ref(
                            Some(TABLE),
                        ))))),
                        Descriptor::String,
                    ],
                ),
            ),
            (
                BOOL_LAYOUT,
                definition("unregistered-bool", vec![Descriptor::Bool]),
            ),
        ]),
        profiles: vec![Profile {
            name: q("unregistered-profile"),
            revision: "future-1".into(),
        }],
        tables: BTreeMap::from([
            (
                TABLE,
                Table {
                    name: q("expressions"),
                    rows: BTreeMap::from([
                        (ROW, record(u32::MAX, None)),
                        (u32::MAX, record(ROW, Some(Value::Null))),
                    ]),
                },
            ),
            (
                OTHER,
                Table {
                    name: q("literals"),
                    rows: BTreeMap::from([(0, boolean(true)), (u32::MAX, boolean(false))]),
                },
            ),
        ]),
    }
}
fn encoded() -> Vec<u8> {
    encode_mapped(&fixture(), &Limits::default()).unwrap()
}
fn u64_at(bytes: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(bytes[at..at + 8].try_into().unwrap())
}
fn put_u32(bytes: &mut [u8], at: usize, value: u32) {
    bytes[at..at + 4].copy_from_slice(&value.to_le_bytes());
}
fn put_u64(bytes: &mut [u8], at: usize, value: u64) {
    bytes[at..at + 8].copy_from_slice(&value.to_le_bytes());
}
fn string_directory(bytes: &[u8]) -> usize {
    80 + u64_at(bytes, 24) as usize
}
fn row_directory(bytes: &[u8]) -> usize {
    string_directory(bytes) + u64_at(bytes, 32) as usize * 56
}
fn row_payload(bytes: &[u8], index: usize) -> (usize, usize) {
    let directory = row_directory(bytes) + index * 64;
    (
        u64_at(bytes, directory + 16) as usize,
        u64_at(bytes, directory + 24) as usize,
    )
}
fn rehash_directory(bytes: &mut [u8], payload_at: usize) {
    let mut digest = Sha256::new();
    digest.update(&bytes[..48]);
    digest.update(&bytes[80..payload_at]);
    bytes[48..80].copy_from_slice(&digest.finalize());
}
fn payload_start(bytes: &[u8]) -> usize {
    row_directory(bytes) + u64_at(bytes, 40) as usize * 64
}
fn rehash_row(bytes: &mut [u8], index: usize) {
    let directory = row_directory(bytes) + index * 64;
    let (offset, len) = row_payload(bytes, index);
    let digest = Sha256::digest(&bytes[offset..offset + len]);
    bytes[directory + 32..directory + 64].copy_from_slice(&digest);
    rehash_directory(bytes, payload_start(bytes));
}

#[test]
fn unknown_layouts_preserve_every_value_and_cyclic_sparse_references() {
    let original = fixture();
    let bytes = encoded();
    assert_eq!(&bytes[..8], b"GSCHEMA\0");
    assert_eq!(u32::from_le_bytes(bytes[8..12].try_into().unwrap()), 2);
    assert_eq!(u64_at(&bytes, 16) as usize, bytes.len());
    let mapped = MappedDocument::from_bytes(bytes, MappedLimits::default()).unwrap();
    assert_eq!(mapped.metadata().definitions, original.definitions);
    assert_eq!(mapped.metadata().profiles, original.profiles);
    assert!(mapped.metadata().strings.is_empty());
    for (id, table) in &original.tables {
        assert_eq!(mapped.metadata().tables[id].name, table.name);
        assert!(mapped.metadata().tables[id].rows.is_empty());
        for (row, record) in &table.rows {
            assert_eq!(
                mapped.record(reference(*id, *row)).unwrap().as_ref(),
                Some(record)
            );
        }
    }
    for (id, string) in &original.strings {
        assert_eq!(mapped.string(*id).unwrap(), Some(string.as_str()));
    }
    assert_eq!(mapped.record(reference(TABLE, 12)).unwrap(), None);
    assert_eq!(mapped.record(reference(8, ROW)).unwrap(), None);
    assert_eq!(mapped.string(1).unwrap(), None);
    mapped.verify_all().unwrap();
}

#[test]
fn string_view_borrows_the_original_mapping_including_embedded_nul() {
    let bytes = encoded();
    let start = bytes.as_ptr() as usize;
    let end = start + bytes.len();
    let mapped = MappedDocument::from_bytes(bytes.as_slice(), MappedLimits::default()).unwrap();
    let string = mapped.string(u32::MAX).unwrap().unwrap();
    assert_eq!(string, "λ\0future 🦀");
    assert!((start..end).contains(&(string.as_ptr() as usize)));
    assert!(string.as_ptr() as usize + string.len() <= end);
}

#[test]
fn unread_corrupt_record_is_not_decoded_or_checksummed() {
    for repair_checksum in [false, true] {
        let mut bytes = encoded();
        let (offset, len) = row_payload(&bytes, 3);
        assert_eq!(
            len, 3,
            "the independent Boolean body is two bitmaps and a value"
        );
        bytes[offset + 2] = 0xff;
        if repair_checksum {
            rehash_row(&mut bytes, 3);
        }
        let mapped = MappedDocument::from_bytes(bytes, MappedLimits::default()).unwrap();
        assert_eq!(
            mapped.record(reference(OTHER, 0)).unwrap(),
            Some(boolean(true))
        );
        assert_eq!(mapped.string(u32::MAX).unwrap(), Some("λ\0future 🦀"));
        assert!(mapped.record(reference(OTHER, u32::MAX)).is_err());
        assert!(mapped.verify_all().is_err());
    }
}

#[test]
fn unread_string_payload_is_not_decoded_and_selected_checksum_is_checked() {
    let mut bytes = encoded();
    let directory = string_directory(&bytes) + 56;
    let offset = u64_at(&bytes, directory + 8) as usize;
    bytes[offset] = 0xff;
    let mapped = MappedDocument::from_bytes(bytes, MappedLimits::default()).unwrap();
    assert_eq!(mapped.string(0).unwrap(), Some(""));
    assert_eq!(
        mapped.record(reference(OTHER, 0)).unwrap(),
        Some(boolean(true))
    );
    assert!(matches!(mapped.string(u32::MAX), Err(Error::Checksum)));
    assert!(mapped.verify_all().is_err());
}

#[test]
fn valid_checksum_does_not_bypass_utf8_validation() {
    let mut bytes = encoded();
    let directory = string_directory(&bytes) + 56;
    let offset = u64_at(&bytes, directory + 8) as usize;
    let len = u64_at(&bytes, directory + 16) as usize;
    bytes[offset] = 0xff;
    let digest = Sha256::digest(&bytes[offset..offset + len]);
    bytes[directory + 24..directory + 56].copy_from_slice(&digest);
    let payload_at = payload_start(&bytes);
    rehash_directory(&mut bytes, payload_at);
    let mapped = MappedDocument::from_bytes(bytes, MappedLimits::default()).unwrap();
    assert!(mapped.string(u32::MAX).is_err());
}

#[test]
fn malformed_directories_fail_before_any_payload_is_exposed() {
    type Mutation = (&'static str, Box<dyn Fn(&mut Vec<u8>)>);
    let mutations: Vec<Mutation> = vec![
        (
            "duplicate string",
            Box::new(|b| {
                let d = string_directory(b);
                put_u32(b, d + 56, 0);
            }),
        ),
        (
            "unsorted strings",
            Box::new(|b| {
                let d = string_directory(b);
                put_u32(b, d, u32::MAX);
                put_u32(b, d + 56, 0);
            }),
        ),
        (
            "duplicate row",
            Box::new(|b| {
                let d = row_directory(b);
                put_u32(b, d + 64 + 4, ROW);
            }),
        ),
        (
            "unsorted rows",
            Box::new(|b| {
                let d = row_directory(b);
                put_u32(b, d + 4, u32::MAX);
                put_u32(b, d + 64 + 4, ROW);
            }),
        ),
        (
            "string out of bounds",
            Box::new(|b| {
                let d = string_directory(b);
                put_u64(b, d + 8, u64::MAX);
            }),
        ),
        (
            "row length overflow",
            Box::new(|b| {
                let d = row_directory(b);
                put_u64(b, d + 24, u64::MAX);
            }),
        ),
        (
            "row points into header",
            Box::new(|b| {
                let d = row_directory(b);
                put_u64(b, d + 16, 0);
            }),
        ),
        (
            "missing layout",
            Box::new(|b| {
                let d = row_directory(b);
                put_u32(b, d + 8, 1234);
            }),
        ),
        (
            "missing table",
            Box::new(|b| {
                let d = row_directory(b);
                put_u32(b, d, 6);
            }),
        ),
        (
            "string count overflow",
            Box::new(|b| put_u64(b, 32, u64::MAX)),
        ),
        ("row count overflow", Box::new(|b| put_u64(b, 40, u64::MAX))),
        (
            "metadata length overflow",
            Box::new(|b| put_u64(b, 24, u64::MAX)),
        ),
        ("reserved header bits", Box::new(|b| put_u32(b, 12, 1))),
    ];
    for (name, mutate) in mutations {
        let mut bytes = encoded();
        let payload_at = payload_start(&bytes);
        mutate(&mut bytes);
        rehash_directory(&mut bytes, payload_at);
        assert!(
            MappedDocument::from_bytes(bytes, MappedLimits::default()).is_err(),
            "accepted {name}"
        );
    }
}

#[test]
fn truncated_files_and_metadata_corruption_fail_closed() {
    let bytes = encoded();
    for end in [
        0,
        7,
        8,
        12,
        79,
        80,
        string_directory(&bytes),
        row_directory(&bytes),
        bytes.len() - 1,
    ] {
        assert!(
            MappedDocument::from_bytes(&bytes[..end], MappedLimits::default()).is_err(),
            "accepted truncation at {end}"
        );
    }
    let mut damaged = bytes;
    damaged[80] ^= 0x80;
    assert!(MappedDocument::from_bytes(damaged, MappedLimits::default()).is_err());
}

#[test]
fn metadata_and_selected_record_have_separate_limits() {
    let bytes = encoded();
    let metadata_limited = MappedLimits {
        metadata: Limits {
            max_bytes: 1,
            ..Limits::default()
        },
        ..MappedLimits::default()
    };
    assert!(MappedDocument::from_bytes(bytes.as_slice(), metadata_limited).is_err());
    let record_limited = MappedLimits {
        record: Limits {
            max_bytes: 3,
            ..Limits::default()
        },
        ..MappedLimits::default()
    };
    let mapped = MappedDocument::from_bytes(bytes, record_limited).unwrap();
    assert!(matches!(
        mapped.record(reference(TABLE, ROW)),
        Err(Error::Limit(_))
    ));
    assert_eq!(
        mapped.record(reference(OTHER, 0)).unwrap(),
        Some(boolean(true))
    );
}

struct TempFile(std::path::PathBuf);
impl Drop for TempFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}
#[test]
fn file_backed_mmap_reads_unknown_records_without_materializing_tables() {
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let path = std::env::temp_dir().join(format!(
        "graphite-schema-mapped-{}-{}.bin",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    let fixture_file = TempFile(path);
    std::fs::write(&fixture_file.0, encoded()).unwrap();
    // SAFETY: this test owns the file; it is not modified for the mapping's lifetime.
    let mapped = unsafe { MappedDocument::open(&fixture_file.0, MappedLimits::default()) }.unwrap();
    assert!(mapped
        .metadata()
        .tables
        .values()
        .all(|table| table.rows.is_empty()));
    assert_eq!(mapped.string(u32::MAX).unwrap(), Some("λ\0future 🦀"));
    assert_eq!(
        mapped.record(reference(TABLE, ROW)).unwrap(),
        Some(fixture().tables[&TABLE].rows[&ROW].clone())
    );
    mapped.verify_all().unwrap();
    drop(mapped);
}

#[test]
fn independently_assembled_indexed_container_matches_the_wire_contract() {
    let mut metadata = Document {
        definitions: BTreeMap::from([(
            BOOL_LAYOUT,
            definition("unregistered-bool", vec![Descriptor::Bool]),
        )]),
        tables: BTreeMap::from([(
            OTHER,
            Table {
                name: q("literals"),
                rows: BTreeMap::new(),
            },
        )]),
        ..Document::default()
    };
    // The metadata uses the separately tested v1 codec. The v2 header,
    // directory, offsets, record bytes and checksums are assembled here.
    let metadata_bytes = encode(&metadata, &Limits::default()).unwrap();
    let body = [1u8, 0, 1];
    let payload_offset = 80 + metadata_bytes.len() + 64;
    let mut bytes = vec![0u8; payload_offset + body.len()];
    bytes[..8].copy_from_slice(b"GSCHEMA\0");
    put_u32(&mut bytes, 8, 2);
    put_u64(&mut bytes, 16, (payload_offset + body.len()) as u64);
    put_u64(&mut bytes, 24, metadata_bytes.len() as u64);
    put_u64(&mut bytes, 40, 1);
    bytes[80..80 + metadata_bytes.len()].copy_from_slice(&metadata_bytes);
    let directory = 80 + metadata_bytes.len();
    put_u32(&mut bytes, directory, OTHER);
    put_u32(&mut bytes, directory + 4, u32::MAX);
    put_u32(&mut bytes, directory + 8, BOOL_LAYOUT);
    put_u64(&mut bytes, directory + 16, payload_offset as u64);
    put_u64(&mut bytes, directory + 24, body.len() as u64);
    bytes[directory + 32..directory + 64].copy_from_slice(&Sha256::digest(body));
    bytes[payload_offset..].copy_from_slice(&body);
    rehash_directory(&mut bytes, payload_offset);
    let mapped = MappedDocument::from_bytes(bytes.as_slice(), MappedLimits::default()).unwrap();
    assert_eq!(mapped.metadata(), &metadata);
    assert_eq!(
        mapped.record(reference(OTHER, u32::MAX)).unwrap(),
        Some(boolean(true))
    );
    mapped.verify_all().unwrap();
    metadata
        .tables
        .get_mut(&OTHER)
        .unwrap()
        .rows
        .insert(u32::MAX, boolean(true));
    assert_eq!(encode_mapped(&metadata, &Limits::default()).unwrap(), bytes);
}

#[test]
fn directory_id_changes_are_covered_by_the_header_checksum() {
    let mut bytes = encoded();
    let directory = row_directory(&bytes);
    // This is still a sorted, structurally valid directory, but its identity
    // changed. Opening must detect it without checking any row payload.
    put_u32(&mut bytes, directory + 4, ROW + 1);
    assert!(matches!(
        MappedDocument::from_bytes(bytes, MappedLimits::default()),
        Err(Error::Checksum)
    ));
}

#[test]
fn selected_references_check_existence_without_decoding_the_target_body() {
    let mut bytes = encoded();
    let (offset, _) = row_payload(&bytes, 2);
    bytes[offset + 2] = 0xff; // This literal is referenced by the expression.
    rehash_row(&mut bytes, 2);
    let mapped = MappedDocument::from_bytes(bytes, MappedLimits::default()).unwrap();
    assert_eq!(
        mapped.record(reference(TABLE, ROW)).unwrap(),
        Some(fixture().tables[&TABLE].rows[&ROW].clone())
    );
    assert!(mapped.record(reference(OTHER, 0)).is_err());
}

#[test]
fn selected_rows_reject_missing_string_and_row_references_after_checksum_validation() {
    // Offsets follow this fixture's two 2-byte bitmaps, Boolean, three 8-byte
    // scalars, StringId, 8-byte bytes length, four bytes, and typed RowId.
    for offset_in_record in [29, 45] {
        let mut bytes = encoded();
        let (offset, _) = row_payload(&bytes, 0);
        assert_eq!(
            &bytes[offset + offset_in_record..offset + offset_in_record + 4],
            &u32::MAX.to_le_bytes()
        );
        put_u32(&mut bytes, offset + offset_in_record, 777);
        rehash_row(&mut bytes, 0);
        let mapped = MappedDocument::from_bytes(bytes, MappedLimits::default()).unwrap();
        assert!(matches!(
            mapped.record(reference(TABLE, ROW)),
            Err(Error::Invalid(_))
        ));
        assert_eq!(
            mapped.record(reference(OTHER, 0)).unwrap(),
            Some(boolean(true))
        );
    }
}

#[test]
fn directory_iterators_expose_sparse_ids_and_layouts_without_reading_payloads() {
    let mut bytes = encoded();
    let (offset, _) = row_payload(&bytes, 0);
    bytes[offset] ^= 0x80; // Leave the body checksum invalid.
    let mapped = MappedDocument::from_bytes(bytes, MappedLimits::default()).unwrap();
    let mut strings = mapped.string_ids();
    assert_eq!(strings.len(), 2);
    assert_eq!(strings.next(), Some(0));
    assert_eq!(strings.len(), 1);
    assert_eq!(strings.next(), Some(u32::MAX));
    assert_eq!(strings.len(), 0);
    assert_eq!(strings.next(), None);
    let mut records = mapped.records();
    assert_eq!(records.len(), 4);
    assert_eq!(records.next(), Some((reference(TABLE, ROW), LAYOUT)));
    assert_eq!(records.len(), 3);
    assert_eq!(
        records.collect::<Vec<_>>(),
        vec![
            (reference(TABLE, u32::MAX), LAYOUT),
            (reference(OTHER, 0), BOOL_LAYOUT),
            (reference(OTHER, u32::MAX), BOOL_LAYOUT),
        ]
    );
    assert!(mapped.record(reference(TABLE, ROW)).is_err());
}

/// The subprocess isolates SIGSEGV/SIGBUS if opening or querying accidentally
/// touches an unrelated payload. This is a source-access assertion, not a
/// synthetic performance measurement.
#[cfg(unix)]
#[test]
fn mapped_payload_guard_pages_remain_unread_during_open_and_selected_access() {
    const CHILD_ENV: &str = "GRAPHITE_SCHEMA_MAPPED_ACCESS_GUARD_CHILD";
    const PASSED: &str = "GRAPHITE_SCHEMA_GUARDED_ACCESS_VERIFIED";
    const TEST: &str = "mapped_payload_guard_pages_remain_unread_during_open_and_selected_access";
    if std::env::var_os(CHILD_ENV).as_deref() != Some(std::ffi::OsStr::new("1")) {
        let output = std::process::Command::new(std::env::current_exe().unwrap())
            .args(["--exact", TEST, "--nocapture"])
            .env(CHILD_ENV, "1")
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "mapped reader touched inaccessible payload pages or failed its assertions: {}\nstdout: {}\nstderr: {}",
            output.status,
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        assert!(
            String::from_utf8_lossy(&output.stdout).contains(PASSED),
            "child test did not execute"
        );
        return;
    }

    // SAFETY: sysconf takes no pointers and only returns the process page size.
    let page_size = unsafe { libc::sysconf(libc::_SC_PAGESIZE) };
    assert!(page_size > 0);
    let page_size = page_size as usize;
    let bulk_len = 6 * page_size;
    let mut doc = fixture();
    doc.strings.insert(0, "selected string".into());
    doc.strings.insert(u32::MAX, "s".repeat(bulk_len));
    doc.tables
        .get_mut(&TABLE)
        .unwrap()
        .rows
        .get_mut(&ROW)
        .unwrap()
        .fields[6] = Some(Value::Bytes(vec![0x42; bulk_len]));
    let bytes = encode_mapped(&doc, &Limits::default()).unwrap();
    let string_entry = string_directory(&bytes) + 56;
    let string_offset = u64_at(&bytes, string_entry + 8) as usize;
    let (row_offset, _) = row_payload(&bytes, 0);
    // Four bitmap bytes, Bool, three 8-byte scalars, StringId, and u64 length.
    let bytes_offset = row_offset + 41;
    assert_eq!(
        &bytes[bytes_offset..bytes_offset + bulk_len],
        vec![0x42; bulk_len]
    );

    let path = std::env::temp_dir().join(format!(
        "graphite-schema-guard-{}-{}.bin",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .read(true)
        .create_new(true)
        .open(&path)
        .unwrap();
    let _cleanup = TempFile(path);
    std::io::Write::write_all(&mut file, &bytes).unwrap();
    // SAFETY: this process owns the file and will neither modify nor truncate
    // it until after the mapping is dropped. The file is never shared.
    let map = unsafe { memmap2::MmapOptions::new().map(&file) }.unwrap();
    assert_eq!((map.as_ptr() as usize) % page_size, 0);

    struct GuardPages {
        address: *mut libc::c_void,
        length: usize,
    }
    impl Drop for GuardPages {
        fn drop(&mut self) {
            // SAFETY: this region belongs to the still-live mapping. It was
            // originally readable; restoration happens before unmapping.
            let result = unsafe { libc::mprotect(self.address, self.length, libc::PROT_READ) };
            assert_eq!(result, 0, "restore mmap page permissions");
        }
    }
    let protect = |offset: usize| {
        let start = offset.div_ceil(page_size) * page_size;
        let end = (offset + bulk_len) / page_size * page_size;
        assert!(end - start >= 4 * page_size);
        assert!(end <= map.len());
        // SAFETY: start/end are page-aligned and lie entirely within this
        // mapping and within one unrelated bulk payload. No directory or
        // selected record/string occupies these pages.
        let address = unsafe { map.as_ptr().add(start) }.cast_mut().cast();
        let result = unsafe { libc::mprotect(address, end - start, libc::PROT_NONE) };
        assert_eq!(
            result,
            0,
            "protect payload pages: {}",
            std::io::Error::last_os_error()
        );
        GuardPages {
            address,
            length: end - start,
        }
    };
    let string_guard = protect(string_offset);
    let record_guard = protect(bytes_offset);
    let mapped = MappedDocument::from_bytes(&map[..], MappedLimits::default()).unwrap();
    assert_eq!(mapped.string(0).unwrap(), Some("selected string"));
    assert_eq!(
        mapped.record(reference(OTHER, 0)).unwrap(),
        Some(boolean(true))
    );
    assert_eq!(mapped.string_ids().collect::<Vec<_>>(), vec![0, u32::MAX]);
    assert_eq!(mapped.records().len(), 4);
    assert!(mapped
        .metadata()
        .tables
        .values()
        .all(|table| table.rows.is_empty()));
    drop(mapped);
    drop(record_guard);
    drop(string_guard);
    drop(map);
    println!("{PASSED}");
}

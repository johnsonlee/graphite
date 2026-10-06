//! The schema reader consumes the same mapped bytes from directories and STORED
//! containers. This is an envelope correctness fixture, not a graph benchmark.
use graphite_schema::*;
use graphite_storage::{container, Bytes, Container, GraphSource};
use std::{collections::BTreeMap, path::PathBuf, sync::Arc};

struct TempDir(PathBuf);
impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

#[test]
fn schema_reads_directory_and_container_ranges_without_copying_or_extracting() {
    let dir = TempDir(std::env::temp_dir().join(format!(
        "graphite-mapped-schema-source-{}",
        std::process::id()
    )));
    std::fs::create_dir_all(&dir.0).unwrap();
    let input = dir.0.join("input");
    std::fs::create_dir_all(&input).unwrap();
    let name = |name| QualifiedName::new("future.language", name);
    let document = Document {
        strings: BTreeMap::from([(52, "λ-Type".into())]),
        definitions: BTreeMap::from([(
            2,
            Definition {
                name: name("qualified-type"),
                revision: "1".into(),
                category: name("expression"),
                fields: vec![Field {
                    name: name("display-name"),
                    descriptor: Descriptor::String,
                    required: true,
                    nullable: false,
                    role: None,
                }],
            },
        )]),
        tables: BTreeMap::from([(
            6,
            Table {
                name: name("types"),
                rows: BTreeMap::from([(
                    90,
                    Record {
                        layout: 2,
                        fields: vec![Some(Value::String(52))],
                    },
                )]),
            },
        )]),
        ..Document::default()
    };
    let encoded = encode_mapped(&document, &Limits::default()).unwrap();
    std::fs::write(input.join("graph.schema"), &encoded).unwrap();
    // Existing pack requires the legacy inventory. These placeholders exercise
    // the container layer only and are never passed to the legacy graph loader.
    for entry in container::REQUIRED_ENTRIES {
        std::fs::write(input.join(entry), []).unwrap();
    }
    let archive = dir.0.join("fixture.graphite");
    container::pack(&input, &archive).unwrap();
    let container = Container::open(&archive).unwrap();
    assert!(container.verify().unwrap().ok());
    let entry = container.entry("graph.schema").unwrap();
    assert_eq!(entry.offset % container::ALIGNMENT, 0);
    let whole = container.bytes("graph.schema").unwrap();
    let second = container.bytes("graph.strings").unwrap();
    match (&whole, &second) {
        (Bytes::Mapped { map: a, start, end }, Bytes::Mapped { map: b, .. }) => {
            assert!(Arc::ptr_eq(a, b), "container entries must share one mmap");
            assert_eq!(*start as u64, entry.offset);
            assert_eq!(*end - *start, encoded.len());
        }
        _ => panic!("container entries must remain mapped"),
    }
    for path in [&input, &archive] {
        let source = GraphSource::open(path).unwrap();
        let bytes = source.require("graph.schema").unwrap();
        assert!(matches!(bytes, Bytes::Mapped { .. }));
        let start = bytes.as_ref().as_ptr() as usize;
        let end = start + bytes.as_ref().len();
        let mapped = MappedDocument::from_bytes(bytes, MappedLimits::default()).unwrap();
        drop(source); // The view owns its mapped range independently of the source.
        let record = mapped
            .record(Reference { table: 6, row: 90 })
            .unwrap()
            .unwrap();
        assert_eq!(record, document.tables[&6].rows[&90]);
        let string = mapped.string(52).unwrap().unwrap();
        assert_eq!(string, "λ-Type");
        assert!((start..end).contains(&(string.as_ptr() as usize)));
        mapped.verify_all().unwrap();
    }
}

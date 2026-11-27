//! `graph.manifest`: the inventory every graph writer seals a directory with.
//!
//! The JVM saver and the JSON importer both write it last. It lists every file of
//! the graph with its size and SHA-256 (the container manifest's line format), and
//! `@key value` attributes naming the producer and the storage format. A reader
//! checks each listed file's presence and size before it reads anything, which
//! costs a `stat` per file and refuses a truncated, partially copied or replaced
//! file instead of decoding it; `verify` hashes every byte. A graph written before
//! the manifest existed has none and is read as before.

use crate::source::GraphSource;
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::path::Path;

pub const FILE_NAME: &str = "graph.manifest";
pub const HEADER: &str = "graphite-graph 1";
pub const PRODUCER: &str = "producer";
pub const STORAGE_FORMAT: &str = "storage-format";
/// The storage format this reader understands; a later one is refused by name.
pub const CURRENT_STORAGE_FORMAT: u32 = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub size: u64,
    pub sha256: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GraphManifest {
    pub attributes: BTreeMap<String, String>,
    pub entries: BTreeMap<String, Entry>,
    /// SHA-256 of the manifest bytes: the identity of the saved graph.
    pub digest: String,
}

/// `graphite-rust <version>`: what this crate's writer records.
pub fn producer() -> String {
    format!("graphite-rust {}", env!("CARGO_PKG_VERSION"))
}

impl GraphManifest {
    pub fn parse(bytes: &[u8]) -> Result<GraphManifest, String> {
        let text = std::str::from_utf8(bytes).map_err(|_| format!("{FILE_NAME} is not UTF-8"))?;
        let mut lines = text.lines();
        if lines.next() != Some(HEADER) {
            return Err(format!("{FILE_NAME} does not start with {HEADER:?}"));
        }
        let mut attributes = BTreeMap::new();
        let mut entries = BTreeMap::new();
        for line in lines {
            if line.trim().is_empty() {
                continue;
            }
            if let Some(attribute) = line.strip_prefix('@') {
                let (key, value) = attribute.split_once(' ').unwrap_or((attribute, ""));
                attributes.insert(key.to_string(), value.to_string());
                continue;
            }
            let mut parts = line.splitn(3, "  ");
            let (Some(sha256), Some(size), Some(name)) = (parts.next(), parts.next(), parts.next())
            else {
                return Err(format!("{FILE_NAME}: malformed line {line:?}"));
            };
            let size = size.parse().ok();
            let digest_ok = sha256.len() == 64
                && sha256
                    .bytes()
                    .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c));
            let (Some(size), true) = (size, digest_ok) else {
                return Err(format!("{FILE_NAME}: malformed line {line:?}"));
            };
            entries.insert(
                name.to_string(),
                Entry {
                    size,
                    sha256: sha256.to_string(),
                },
            );
        }
        let format = attributes
            .get(STORAGE_FORMAT)
            .map(|v| {
                v.parse::<u32>()
                    .map_err(|_| format!("{FILE_NAME}: storage format {v:?}"))
            })
            .transpose()?
            .unwrap_or(CURRENT_STORAGE_FORMAT);
        if format > CURRENT_STORAGE_FORMAT {
            return Err(format!(
                "storage format {format} was written by {}; this reader supports format {CURRENT_STORAGE_FORMAT}",
                attributes
                    .get(PRODUCER)
                    .map(String::as_str)
                    .unwrap_or("an unknown producer")
            ));
        }
        Ok(GraphManifest {
            attributes,
            entries,
            digest: hex(&Sha256::digest(bytes)),
        })
    }

    pub fn producer(&self) -> Option<&str> {
        self.attributes.get(PRODUCER).map(String::as_str)
    }

    pub fn render(
        attributes: &BTreeMap<String, String>,
        entries: &BTreeMap<String, Entry>,
    ) -> String {
        let mut out = format!("{HEADER}\n");
        // The producer and the format lead, as the JVM writer orders them.
        for key in [PRODUCER, STORAGE_FORMAT] {
            if let Some(value) = attributes.get(key) {
                out.push_str(&format!("@{key} {value}\n"));
            }
        }
        for (key, value) in attributes {
            if key != PRODUCER && key != STORAGE_FORMAT {
                out.push_str(&format!("@{key} {value}\n"));
            }
        }
        for (name, entry) in entries {
            out.push_str(&format!("{}  {}  {name}\n", entry.sha256, entry.size));
        }
        out
    }

    /// Seals `dir`: lists every regular file in it, then writes the manifest.
    pub fn write_dir(dir: &Path) -> std::io::Result<GraphManifest> {
        let mut entries = BTreeMap::new();
        for item in std::fs::read_dir(dir)? {
            let item = item?;
            let name = item.file_name().to_string_lossy().into_owned();
            if name == FILE_NAME || !item.file_type()?.is_file() {
                continue;
            }
            let bytes = std::fs::read(item.path())?;
            entries.insert(
                name,
                Entry {
                    size: bytes.len() as u64,
                    sha256: hex(&Sha256::digest(&bytes)),
                },
            );
        }
        let attributes = BTreeMap::from([
            (PRODUCER.to_string(), producer()),
            (
                STORAGE_FORMAT.to_string(),
                CURRENT_STORAGE_FORMAT.to_string(),
            ),
        ]);
        let text = GraphManifest::render(&attributes, &entries);
        std::fs::write(dir.join(FILE_NAME), &text)?;
        GraphManifest::parse(text.as_bytes()).map_err(std::io::Error::other)
    }
}

/// Environment switch: hash every listed file on load, not only check sizes.
pub const VERIFY_ON_LOAD_ENV: &str = "GRAPHITE_VERIFY_GRAPHS";

pub fn verify_on_load() -> bool {
    std::env::var(VERIFY_ON_LOAD_ENV).is_ok_and(|v| v == "1" || v.eq_ignore_ascii_case("true"))
}

/// The manifest of `src`, `None` for a graph written before manifests existed.
pub fn read(src: &GraphSource) -> Result<Option<GraphManifest>, String> {
    match src.bytes(FILE_NAME) {
        Ok(Some(bytes)) => GraphManifest::parse(&bytes).map(Some),
        Ok(None) => Ok(None),
        Err((path, e)) => Err(format!("{path}: {e}")),
    }
}

/// Refuses a graph whose listed files are missing or not the size the manifest
/// records. Cheap enough for every load.
pub fn check_sizes(src: &GraphSource) -> Result<Option<GraphManifest>, String> {
    let Some(manifest) = read(src)? else {
        return Ok(None);
    };
    for (name, entry) in &manifest.entries {
        match src.size(name) {
            Ok(Some(size)) if size == entry.size => {}
            Ok(Some(size)) => {
                return Err(format!(
                "{}: {size} bytes, but {FILE_NAME} lists {}; the graph was modified or cut short",
                src.describe(name),
                entry.size
            ))
            }
            Ok(None) => {
                return Err(format!(
                    "{}: listed in {FILE_NAME} but missing",
                    src.describe(name)
                ))
            }
            Err((path, e)) => return Err(format!("{path}: {e}")),
        }
    }
    Ok(Some(manifest))
}

/// Hashes every listed file: one message per file that is missing or differs.
pub fn verify(src: &GraphSource) -> Result<Vec<String>, String> {
    let Some(manifest) = read(src)? else {
        return Ok(vec![format!(
            "{}: missing; nothing to verify the graph against",
            src.describe(FILE_NAME)
        )]);
    };
    let mut failures = Vec::new();
    for (name, entry) in &manifest.entries {
        match src.bytes(name) {
            Ok(Some(bytes)) => {
                let digest = hex(&Sha256::digest(&bytes[..]));
                if bytes.len() as u64 != entry.size || digest != entry.sha256 {
                    failures.push(format!(
                        "{name}: expected {} bytes sha256 {}, found {} bytes sha256 {digest}",
                        entry.size,
                        entry.sha256,
                        bytes.len()
                    ));
                }
            }
            Ok(None) => failures.push(format!("{name}: missing")),
            Err((path, e)) => failures.push(format!("{path}: {e}")),
        }
    }
    Ok(failures)
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sealed_dir(name: &str) -> std::path::PathBuf {
        let dir =
            std::env::temp_dir().join(format!("graphite-manifest-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("graph.a"), b"hello").unwrap();
        std::fs::write(dir.join("forward.b"), b"world!").unwrap();
        GraphManifest::write_dir(&dir).unwrap();
        dir
    }

    #[test]
    fn written_manifest_lists_every_file_with_producer_and_format() {
        let dir = sealed_dir("write");
        let src = GraphSource::open(&dir).unwrap();
        let manifest = check_sizes(&src).unwrap().unwrap();
        assert_eq!(manifest.producer(), Some(producer().as_str()));
        assert!(manifest.producer().unwrap().starts_with("graphite-rust "));
        assert_eq!(manifest.attributes[STORAGE_FORMAT], "1");
        assert_eq!(
            manifest.entries.keys().cloned().collect::<Vec<_>>(),
            vec!["forward.b".to_string(), "graph.a".to_string()]
        );
        assert_eq!(manifest.entries["graph.a"].size, 5);
        assert_eq!(
            manifest.entries["graph.a"].sha256,
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        );
        assert!(verify(&src).unwrap().is_empty());
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn truncated_missing_and_flipped_files_are_reported() {
        let dir = sealed_dir("damage");
        std::fs::write(dir.join("graph.a"), b"hell").unwrap();
        let src = GraphSource::open(&dir).unwrap();
        let err = check_sizes(&src).unwrap_err();
        assert!(
            err.contains("graph.a: 4 bytes, but graph.manifest lists 5"),
            "{err}"
        );

        std::fs::write(dir.join("graph.a"), b"jello").unwrap();
        assert!(
            check_sizes(&src).is_ok(),
            "same size passes the cheap check"
        );
        let failures = verify(&src).unwrap();
        assert_eq!(failures.len(), 1);
        assert!(
            failures[0].starts_with("graph.a: expected 5 bytes sha256 2cf24dba"),
            "{failures:?}"
        );

        std::fs::remove_file(dir.join("forward.b")).unwrap();
        let err = check_sizes(&src).unwrap_err();
        assert!(
            err.contains("forward.b: listed in graph.manifest but missing"),
            "{err}"
        );
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn a_newer_storage_format_is_refused_by_producer_name() {
        let text = format!("{HEADER}\n@producer graphite-jvm 9.0.0\n@storage-format 2\n");
        let err = GraphManifest::parse(text.as_bytes()).unwrap_err();
        assert_eq!(
            err,
            "storage format 2 was written by graphite-jvm 9.0.0; this reader supports format 1"
        );
    }

    #[test]
    fn malformed_manifests_are_errors() {
        assert!(GraphManifest::parse(b"nope\n")
            .unwrap_err()
            .contains("does not start"));
        let bad = format!("{HEADER}\nxyz  1  graph.a\n");
        assert!(GraphManifest::parse(bad.as_bytes())
            .unwrap_err()
            .contains("malformed line"));
        let bad = format!("{HEADER}\n{}  big  graph.a\n", "a".repeat(64));
        assert!(GraphManifest::parse(bad.as_bytes())
            .unwrap_err()
            .contains("malformed line"));
        let bad = format!("{HEADER}\n@storage-format x\n");
        assert!(GraphManifest::parse(bad.as_bytes())
            .unwrap_err()
            .contains("storage format \"x\""));
    }

    #[test]
    fn a_directory_without_a_manifest_is_a_legacy_graph() {
        let dir =
            std::env::temp_dir().join(format!("graphite-manifest-legacy-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let src = GraphSource::open(&dir).unwrap();
        assert_eq!(check_sizes(&src).unwrap(), None);
        assert!(verify(&src).unwrap()[0].contains("missing; nothing to verify"));
        std::fs::remove_dir_all(&dir).unwrap();
    }
}

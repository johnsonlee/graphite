//! Where a persisted graph's files come from: a directory of files, or one
//! `.graphite` container holding the same files as entries. Every loader reads
//! through this, so the two layouts are the same graph.

use crate::container::{Bytes, Container, ContainerError};
use memmap2::Mmap;
use std::fs::File;
use std::path::{Path, PathBuf};

pub enum GraphSource {
    Dir(PathBuf),
    Container(Container),
}

#[derive(Debug, thiserror::Error)]
pub enum SourceError {
    #[error("io error on {0}: {1}")]
    Io(String, std::io::Error),
    #[error(transparent)]
    Container(#[from] ContainerError),
}

/// An I/O failure on one named file: the path shown to the user, and the error.
pub type IoAt = (String, std::io::Error);

impl GraphSource {
    /// A directory is read as files; anything else must be a container.
    pub fn open(path: &Path) -> Result<GraphSource, SourceError> {
        let meta =
            std::fs::metadata(path).map_err(|e| SourceError::Io(path.display().to_string(), e))?;
        if meta.is_dir() {
            Ok(GraphSource::Dir(path.to_path_buf()))
        } else {
            Ok(GraphSource::Container(Container::open(path)?))
        }
    }

    pub fn location(&self) -> &Path {
        match self {
            GraphSource::Dir(d) => d,
            GraphSource::Container(c) => c.path(),
        }
    }

    pub fn is_container(&self) -> bool {
        matches!(self, GraphSource::Container(_))
    }

    /// Where a file lives, for messages: `<dir>/<name>` or `<file>!/<name>`.
    pub fn describe(&self, name: &str) -> String {
        match self {
            GraphSource::Dir(d) => d.join(name).display().to_string(),
            GraphSource::Container(c) => format!("{}!/{name}", c.path().display()),
        }
    }

    /// The file's bytes, `None` when it is absent. A directory file is mapped; a
    /// container entry is a slice of the container's one map.
    pub fn bytes(&self, name: &str) -> Result<Option<Bytes>, IoAt> {
        match self {
            GraphSource::Container(c) => Ok(c.bytes(name)),
            GraphSource::Dir(d) => {
                let path = d.join(name);
                let file = match File::open(&path) {
                    Ok(f) => f,
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
                    Err(e) => return Err((path.display().to_string(), e)),
                };
                let len = file
                    .metadata()
                    .map_err(|e| (path.display().to_string(), e))?
                    .len();
                if len == 0 {
                    // An empty file cannot be mapped on every platform.
                    return Ok(Some(Bytes::owned(Vec::new())));
                }
                // SAFETY: read-only mapping of a file we do not modify.
                let map =
                    unsafe { Mmap::map(&file) }.map_err(|e| (path.display().to_string(), e))?;
                Ok(Some(Bytes::whole(map)))
            }
        }
    }

    /// The file's bytes; an absent file is a not-found error naming it.
    pub fn require(&self, name: &str) -> Result<Bytes, IoAt> {
        self.bytes(name)?.ok_or_else(|| {
            (
                self.describe(name),
                std::io::Error::new(std::io::ErrorKind::NotFound, "no such file"),
            )
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// With a real graph directory at hand, its container answers exactly as it does.
    #[test]
    fn a_packed_graph_is_the_same_graph_as_its_directory() {
        let Some(dir) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
            return;
        };
        let dir = PathBuf::from(dir);
        let root = std::env::temp_dir().join(format!("graphite-source-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        let file = root.join("fixture.graphite");
        let report = crate::container::pack(&dir, &file).unwrap();
        let c = Container::open(&file).unwrap();
        assert!(c.verify().unwrap().ok());
        assert_eq!(c.fingerprint().unwrap(), report.fingerprint);

        let a = crate::graph::Graph::load(&dir).unwrap();
        let b = crate::graph::Graph::load(&file).unwrap();
        assert_eq!(a.node_count(), b.node_count());
        assert_eq!(a.node_capacity(), b.node_capacity());
        assert_eq!(a.edge_count(), b.edge_count());
        assert_eq!(a.strings.len(), b.strings.len());
        assert_eq!(a.call_site_index().is_some(), b.call_site_index().is_some());
        for tag in 0..crate::node::TAG_COUNT as u8 {
            assert_eq!(a.count_by_tag(tag), b.count_by_tag(tag), "tag {tag}");
        }
        for id in a.all_ids().step_by(97) {
            assert_eq!(
                a.node(id).map(|n| n.kind.clone()),
                b.node(id).map(|n| n.kind.clone())
            );
            let x: Vec<_> = a.outgoing(id).map(|e| (e.to, e.label)).collect();
            let y: Vec<_> = b.outgoing(id).map(|e| (e.to, e.label)).collect();
            assert_eq!(x, y, "outgoing of {id}");
            let x: Vec<_> = a.incoming(id).map(|e| (e.from, e.label)).collect();
            let y: Vec<_> = b.incoming(id).map(|e| (e.from, e.label)).collect();
            assert_eq!(x, y, "incoming of {id}");
        }
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn a_directory_source_reads_files_and_reports_absent_ones() {
        let root = std::env::temp_dir().join(format!("graphite-source-dir-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        std::fs::write(root.join("graph.metadata"), b"abc").unwrap();
        std::fs::write(root.join("empty"), b"").unwrap();
        let src = GraphSource::open(&root).unwrap();
        assert!(!src.is_container());
        assert_eq!(src.location(), root);
        assert_eq!(&src.require("graph.metadata").unwrap()[..], b"abc");
        assert_eq!(src.bytes("empty").unwrap().unwrap().len(), 0);
        assert!(src.bytes("missing").unwrap().is_none());
        let (shown, e) = src.require("missing").unwrap_err();
        assert_eq!(e.kind(), std::io::ErrorKind::NotFound);
        assert!(shown.ends_with("missing"));
        assert!(src.describe("x").ends_with("/x"));
        assert!(matches!(
            GraphSource::open(&root.join("nowhere")),
            Err(SourceError::Io(..))
        ));
        assert!(matches!(
            GraphSource::open(&root.join("graph.metadata")),
            Err(SourceError::Container(_))
        ));
        std::fs::remove_dir_all(root).unwrap();
    }
}

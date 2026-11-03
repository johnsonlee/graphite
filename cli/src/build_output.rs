//! Sibling staging and packed output publication for frontend builds.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

pub(crate) fn require_absent(path: &Path) -> Result<(), String> {
    match std::fs::symlink_metadata(path) {
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(format!("could not inspect {}: {e}", path.display())),
        Ok(_) => Err(format!("output already exists: {}", path.display())),
    }
}

/// Both files are complete in a sibling staging directory. Linking creates a final
/// name only if it is absent, even if another build passed the same initial check.
/// Publish the digest first so that a visible container always has its matching digest.
fn publish_container(staged: &Path, staged_digest: &Path, output: &Path) -> Result<(), String> {
    let link = |from: &Path, to: &Path| {
        std::fs::hard_link(from, to).map_err(|e| {
            if e.kind() == std::io::ErrorKind::AlreadyExists {
                format!("output already exists: {}", to.display())
            } else {
                format!("could not publish {}: {e}", to.display())
            }
        })
    };
    let digest = graphite_storage::container::digest_path(output);
    link(staged_digest, &digest)?;
    if let Err(error) = link(staged, output) {
        std::fs::remove_file(&digest)
            .map_err(|e| format!("{error}; could not remove {}: {e}", digest.display()))?;
        return Err(error);
    }
    Ok(())
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) struct Stage(pub(crate) PathBuf);
#[derive(Copy, Clone)]
pub(crate) enum Publication {
    CreateNew,
    Replace,
}

impl Stage {
    pub(crate) fn graph(&self) -> PathBuf {
        self.0.join("saved")
    }

    pub(crate) fn pack(&self, output: &Path, publication: Publication) -> Result<(), String> {
        // Packing under the final basename keeps the sidecar valid after publication.
        let destination = match publication {
            Publication::CreateNew => self
                .0
                .join(output.file_name().ok_or("missing output filename")?),
            Publication::Replace => output.to_owned(),
        };
        let report = graphite_storage::container::pack(&self.graph(), &destination)
            .map_err(|e| format!("could not pack {}: {e}", output.display()))?;
        if let Publication::CreateNew = publication {
            publish_container(&destination, &report.digest_file, output)?;
        }
        crate::pack::print_report(&report, output);
        Ok(())
    }

    pub(crate) fn new(output: &Path) -> std::io::Result<Self> {
        static COUNTER: AtomicU64 = AtomicU64::new(0);
        let parent = output
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .unwrap_or(Path::new("."));
        std::fs::create_dir_all(parent)?;
        loop {
            let name = format!(
                ".graphite-build-{}-{}",
                std::process::id(),
                COUNTER.fetch_add(1, Ordering::Relaxed)
            );
            let path = parent.join(name);
            match std::fs::create_dir(&path) {
                Ok(()) => return Ok(Self(path)),
                Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(e) => return Err(e),
            }
        }
    }
}
impl Drop for Stage {
    fn drop(&mut self) {
        if let Err(e) = std::fs::remove_dir_all(&self.0) {
            eprintln!(
                "Warning: could not remove staging directory {}: {e}",
                self.0.display()
            );
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn concurrent_publishers_leave_one_complete_unmixed_pair() {
        let root = Stage::new(&std::env::temp_dir().join("publish-test")).unwrap();
        let output = root.0.join("saved.graphite");
        let digest = graphite_storage::container::digest_path(&output);
        let staged = ["first", "second"].map(|name| {
            let file = root.0.join(name);
            let checksum = root.0.join(format!("{name}.sha256"));
            std::fs::write(&file, name).unwrap();
            std::fs::write(&checksum, format!("digest of {name}")).unwrap();
            (file, checksum)
        });
        let barrier = std::sync::Barrier::new(2);
        let publish = |(file, checksum): &(PathBuf, PathBuf)| {
            barrier.wait();
            publish_container(file, checksum, &output)
        };
        let (first, second) = std::thread::scope(|scope| {
            let first = scope.spawn(|| publish(&staged[0]));
            let second = publish(&staged[1]);
            (first.join().unwrap(), second)
        });
        let winner = match (first, second) {
            (Ok(()), Err(_)) => "first",
            (Err(_), Ok(())) => "second",
            other => panic!("expected exactly one success: {other:?}"),
        };
        assert_eq!(std::fs::read_to_string(&output).unwrap(), winner);
        assert_eq!(
            std::fs::read_to_string(&digest).unwrap(),
            format!("digest of {winner}")
        );
        // An output created after initial validation is preserved, and a newly
        // linked digest is removed when the container cannot be published.
        std::fs::remove_file(&digest).unwrap();
        assert!(publish_container(&staged[0].0, &staged[0].1, &output).is_err());
        assert_eq!(std::fs::read_to_string(&output).unwrap(), winner);
        assert!(!digest.exists());
    }
}

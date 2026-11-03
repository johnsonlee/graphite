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

/// Both staged files are complete. Claim the digest first so a visible container
/// always has its matching digest, even when multiple builds race.
fn publish_container(staged: &Path, staged_digest: &Path, output: &Path) -> Result<(), String> {
    publish_with(staged, staged_digest, output, |from, to| {
        std::fs::hard_link(from, to)
    })
}

fn publish_with(
    staged: &Path,
    staged_digest: &Path,
    output: &Path,
    hard_link: impl Fn(&Path, &Path) -> std::io::Result<()>,
) -> Result<(), String> {
    let publish = |from: &Path, to: &Path| {
        let exists = || format!("output already exists: {}", to.display());
        match hard_link(from, to) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => Err(exists()),
            Err(link_error) => {
                // Same-filesystem staging allows an exclusive native rename on
                // macOS, Linux, and Windows even where hard links are disabled.
                let staged = tempfile::TempPath::try_from_path(from)
                    .map_err(|e| format!("could not access {}: {e}", from.display()))?;
                staged.persist_noclobber(to).map_err(|e| {
                    if e.error.kind() == std::io::ErrorKind::AlreadyExists {
                        exists()
                    } else {
                        format!("could not publish {}: {link_error} (hard link); {} (no-clobber persist)", to.display(), e.error)
                    }
                })
            }
        }
    };
    let digest = graphite_storage::container::digest_path(output);
    publish(staged_digest, &digest)?;
    if let Err(error) = publish(staged, output) {
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
        let parent = output
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .unwrap_or(Path::new("."));
        std::fs::create_dir_all(parent)?;
        Self::in_directory(parent)
    }

    /// A failing JVM frontend must not leave a newly created output parent behind.
    pub(crate) fn for_jvm_output(output: &Path) -> std::io::Result<Self> {
        let mut parent = output.parent().unwrap_or(Path::new("."));
        loop {
            if parent.as_os_str().is_empty() {
                parent = Path::new(".");
            }
            match std::fs::metadata(parent) {
                Ok(_) => return Self::in_directory(parent),
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => match parent.parent() {
                    Some(next) => parent = next,
                    None => return Err(e),
                },
                Err(e) => return Err(e),
            }
        }
    }

    fn in_directory(parent: &Path) -> std::io::Result<Self> {
        static COUNTER: AtomicU64 = AtomicU64::new(0);
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

    #[cfg(any(target_os = "macos", target_os = "linux", windows))]
    #[test]
    fn publishes_complete_files_without_hard_links_and_rolls_back_a_late_collision() {
        let stage = Stage::new(&std::env::temp_dir().join("publish-test")).unwrap();
        let data = b"complete container\x00\xff";
        let output = stage.0.join("output.graphite");
        let digest = graphite_storage::container::digest_path(&output);
        let staged = stage.0.join("packed");
        let checksum = stage.0.join("checksum");
        let checksum_data =
            "038a4206e9510a3601784dc6071340a8a0ee4893e552d34599b3d44ff8eac3c8  output.graphite\n";
        let pack = || {
            std::fs::write(&staged, data).unwrap();
            std::fs::write(&checksum, checksum_data).unwrap();
        };
        let unsupported = |_: &Path, _: &Path| Err(std::io::ErrorKind::Unsupported.into());
        pack();
        publish_with(&staged, &checksum, &output, unsupported).unwrap();
        assert_eq!(std::fs::read(&output).unwrap(), data);
        assert_eq!(std::fs::read_to_string(&digest).unwrap(), checksum_data);

        // A competing graph can appear after the initial existence check.
        std::fs::remove_file(&digest).unwrap();
        std::fs::write(&output, b"another writer").unwrap();
        pack();
        assert!(publish_with(&staged, &checksum, &output, unsupported).is_err());
        assert_eq!(std::fs::read(&output).unwrap(), b"another writer");
        assert!(!digest.exists());

        std::fs::remove_file(&output).unwrap();
        std::fs::write(&digest, b"another digest").unwrap();
        pack();
        assert!(publish_with(&staged, &checksum, &output, unsupported).is_err());
        assert!(!output.exists());
        assert_eq!(std::fs::read(&digest).unwrap(), b"another digest");
    }

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
        // published digest is removed when the container cannot be published.
        std::fs::remove_file(&digest).unwrap();
        let retry_file = root.0.join("retry");
        let retry_digest = root.0.join("retry.sha256");
        std::fs::write(&retry_file, b"competing retry").unwrap();
        std::fs::write(&retry_digest, b"digest of competing retry").unwrap();
        assert!(publish_container(&retry_file, &retry_digest, &output).is_err());
        assert_eq!(std::fs::read_to_string(&output).unwrap(), winner);
        assert!(!digest.exists());
    }
}

//! Same-filesystem staging and packed output publication for frontend builds.

use std::path::{Path, PathBuf};

mod directory;
mod replace;

pub(crate) fn require_absent(path: &Path) -> Result<(), String> {
    match std::fs::symlink_metadata(path) {
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(format!("could not inspect {}: {e}", path.display())),
        Ok(_) => Err(format!("output already exists: {}", path.display())),
    }
}

fn create_output_parent(output: &Path) -> Result<(), String> {
    if let Some(parent) = output.parent().filter(|p| !p.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent)
            .map_err(|e| format!("could not create {}: {e}", parent.display()))?;
    }
    Ok(())
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
    let digest = graphite_storage::container::digest_path(output);
    publish_new_file(staged_digest, &digest, &hard_link)?;
    if let Err(error) = publish_new_file(staged, output, &hard_link) {
        std::fs::remove_file(&digest)
            .map_err(|e| format!("{error}; could not remove {}: {e}", digest.display()))?;
        return Err(error);
    }
    Ok(())
}

fn publish_new_file(
    from: &Path,
    to: &Path,
    hard_link: &impl Fn(&Path, &Path) -> std::io::Result<()>,
) -> Result<(), String> {
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
                    format!(
                        "could not publish {}: {link_error} (hard link); {} (no-clobber persist)",
                        to.display(),
                        e.error
                    )
                }
            })
        }
    }
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
        let name = output.file_name().ok_or("missing output filename")?;
        let destination = self.0.join(name);
        let report = graphite_storage::container::pack(&self.graph(), &destination)
            .map_err(|e| format!("could not pack {}: {e}", output.display()))?;
        create_output_parent(output)?;
        let mut claim = replace::Claim::new(output)?;
        match publication {
            Publication::CreateNew => publish_container(&destination, &report.digest_file, output)?,
            Publication::Replace => {
                claim.replace(&destination, &report.digest_file, output, |a, b| {
                    std::fs::rename(a, b)
                })?
            }
        }
        crate::pack::print_report(&report, output);
        Ok(())
    }

    pub(crate) fn publish_directory(&self, output: &Path) -> Result<(), String> {
        create_output_parent(output)?;
        directory::publish(&self.graph(), output, rename_directory_exclusive).map_err(|e| {
            if e.kind() == std::io::ErrorKind::AlreadyExists {
                format!("output already exists: {}", output.display())
            } else {
                format!(
                    "could not publish directory {} without replacing an existing path: {e}",
                    output.display()
                )
            }
        })
    }

    pub(crate) fn new(output: &Path) -> std::io::Result<Self> {
        Self::for_output(output, &std::env::temp_dir(), |from, to| {
            std::fs::rename(from, to)
        })
    }

    fn for_output(
        output: &Path,
        temp_dir: &Path,
        rename_probe: impl FnOnce(&Path, &Path) -> std::io::Result<()>,
    ) -> std::io::Result<Self> {
        let parent = output.parent().unwrap_or(Path::new("."));
        for ancestor in parent.ancestors() {
            let dir = if ancestor.as_os_str().is_empty() {
                Path::new(".")
            } else {
                ancestor
            };
            match std::fs::metadata(dir) {
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue,
                Err(e) => return Err(e),
                Ok(_) => {}
            }
            let local = Self::in_directory(dir)?;
            if ancestor == parent || dir == temp_dir {
                return Ok(local);
            }
            // Even bind mounts with matching device IDs may reject renames. Move
            // an empty directory before allowing the frontend to write any graph data.
            if let Ok(temp) = Self::in_directory(temp_dir) {
                let probe = temp.0.join("probe");
                if std::fs::create_dir(&probe).is_ok()
                    && rename_probe(&probe, &local.0.join("probe")).is_ok()
                {
                    return Ok(temp);
                }
            }
            return Ok(local);
        }
        Err(std::io::Error::new(
            std::io::ErrorKind::NotFound,
            "no existing output ancestor",
        ))
    }

    fn in_directory(parent: &Path) -> std::io::Result<Self> {
        let mut builder = tempfile::Builder::new();
        builder.prefix(".graphite-build-");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            builder.permissions(std::fs::Permissions::from_mode(0o700));
        }
        Ok(Self(builder.tempdir_in(parent)?.keep()))
    }
}

#[cfg(any(
    target_vendor = "apple",
    target_os = "linux",
    target_os = "android",
    target_os = "redox"
))]
fn rename_directory_exclusive(from: &Path, to: &Path) -> std::io::Result<()> {
    use rustix::fs::{renameat_with, RenameFlags, CWD};
    Ok(renameat_with(CWD, from, CWD, to, RenameFlags::NOREPLACE)?)
}

#[cfg(windows)]
fn rename_directory_exclusive(from: &Path, to: &Path) -> std::io::Result<()> {
    // On Windows persist_noclobber uses MoveFileExW without REPLACE_EXISTING or COPY_ALLOWED.
    tempfile::TempPath::try_from_path(from)?
        .persist_noclobber(to)
        .map_err(|e| e.error)
}

#[cfg(not(any(
    windows,
    target_vendor = "apple",
    target_os = "linux",
    target_os = "android",
    target_os = "redox"
)))]
fn rename_directory_exclusive(_: &Path, _: &Path) -> std::io::Result<()> {
    Err(std::io::Error::new(
        std::io::ErrorKind::Unsupported,
        "exclusive directory rename is unavailable",
    ))
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
    fn missing_parents_use_system_temp_and_leave_other_directories_alone() {
        let repo = tempfile::tempdir().unwrap();
        let unrelated = repo.path().join(".graphite-build-user");
        std::fs::create_dir(&unrelated).unwrap();
        std::fs::write(unrelated.join("keep"), b"user data").unwrap();
        let live = Stage::new(&repo.path().join("sibling.graphite")).unwrap();
        std::fs::write(live.0.join("keep"), b"live frontend").unwrap();
        let output = repo.path().join("out/new/app.graphite");
        let stage = Stage::new(&output).unwrap();
        assert_eq!(stage.0.parent(), Some(std::env::temp_dir().as_path()));
        assert!(!repo.path().join("out").exists());
        let owned = stage.0.clone();
        drop(stage);
        assert!(!owned.exists());
        assert_eq!(std::fs::read(unrelated.join("keep")).unwrap(), b"user data");
        assert_eq!(
            std::fs::read(live.0.join("keep")).unwrap(),
            b"live frontend"
        );
    }

    #[test]
    fn different_volume_or_unavailable_temp_stages_next_to_the_missing_parent() {
        let disk = tempfile::tempdir().unwrap();
        let temp = tempfile::tempdir().unwrap();
        let nearest = disk.path().join("data");
        std::fs::create_dir(&nearest).unwrap();
        let output = nearest.join("new/nested/app.graphite");
        let stage = Stage::for_output(&output, temp.path(), |from, to| {
            assert!(from.is_dir() && from.starts_with(temp.path()));
            assert!(to.starts_with(&nearest) && !to.exists());
            Err(std::io::ErrorKind::CrossesDevices.into())
        })
        .unwrap();
        assert_eq!(stage.0.parent(), Some(nearest.as_path()));
        assert!(!nearest.join("new").exists());
        assert_eq!(std::fs::read_dir(temp.path()).unwrap().count(), 0);
        drop(stage);
        let stage = Stage::for_output(&output, &temp.path().join("unavailable"), |_, _| {
            panic!("no temp probe")
        })
        .unwrap();
        assert_eq!(stage.0.parent(), Some(nearest.as_path()));
        assert!(!nearest.join("new").exists());
        drop(stage);
        assert_eq!(std::fs::read_dir(&nearest).unwrap().count(), 0);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn native_cross_device_probe_uses_the_output_mount() {
        let disk = tempfile::tempdir().unwrap();
        let Ok(tmpfs) = tempfile::tempdir_in("/dev/shm") else {
            return;
        };
        let marker = tmpfs.path().join("check");
        std::fs::create_dir(&marker).unwrap();
        match std::fs::rename(&marker, disk.path().join("check")) {
            Ok(()) => return, // Same mount on this host.
            Err(e) => assert_eq!(e.kind(), std::io::ErrorKind::CrossesDevices),
        }
        let output = disk.path().join("not-created/graph");
        let stage = Stage::for_output(&output, tmpfs.path(), |a, b| std::fs::rename(a, b)).unwrap();
        assert_eq!(stage.0.parent(), Some(disk.path()));
        assert!(!output.parent().unwrap().exists());
    }

    #[cfg(any(target_vendor = "apple", target_os = "linux", windows))]
    #[test]
    fn a_directory_created_after_preflight_is_never_replaced() {
        let root = tempfile::tempdir().unwrap();
        let output = root.path().join("saved");
        require_absent(&output).unwrap();
        let stage = Stage::new(&output).unwrap();
        std::fs::create_dir(stage.graph()).unwrap();
        std::fs::write(stage.graph().join("graph.data"), b"complete graph").unwrap();
        std::fs::create_dir(&output).unwrap(); // A different writer claimed the name.
        assert!(stage.publish_directory(&output).is_err());
        assert_eq!(std::fs::read_dir(&output).unwrap().count(), 0);
        assert_eq!(
            std::fs::read(stage.graph().join("graph.data")).unwrap(),
            b"complete graph"
        );
        std::fs::remove_dir(&output).unwrap();
        stage.publish_directory(&output).unwrap();
        assert_eq!(
            std::fs::read(output.join("graph.data")).unwrap(),
            b"complete graph"
        );
        assert!(!stage.graph().exists());
    }

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

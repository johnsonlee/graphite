//! Same-filesystem staging and output publication for frontend builds.

use std::path::{Path, PathBuf};

mod directory;
mod directory_replace;
pub(crate) use directory_replace::DirectoryPublication;
mod packed;
mod replace;
mod stale;

fn create_output_parent(output: &Path) -> Result<(), String> {
    if let Some(parent) = output.parent().filter(|p| !p.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent)
            .map_err(|e| format!("could not create {}: {e}", parent.display()))?;
    }
    Ok(())
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) struct Stage(pub(crate) PathBuf, bool);
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum Publication {
    CreateNew,
    Rebuild {
        container: same_file::Handle,
        digest: Option<same_file::Handle>,
    },
}

impl Publication {
    /// Remember the pair we intend to rebuild. A competing publisher that finishes
    /// during analysis must never turn a new build into an overwrite.
    pub(crate) fn inspect(output: &Path) -> Result<Self, String> {
        if output.is_file() {
            replace::recover(output);
        }
        Self::snapshot(output)
    }

    fn snapshot(output: &Path) -> Result<Self, String> {
        let digest = graphite_storage::container::digest_path(output);
        let regular = |p: &Path| {
            replace::regular(p).map_err(|e| format!("could not inspect {}: {e}", p.display()))
        };
        match (regular(output)?, regular(&digest)?) {
            (None, None) => Ok(Self::CreateNew),
            (Some(container), digest) => Ok(Self::Rebuild { container, digest }),
            _ => Err(format!(
                "output already exists without a complete graph and digest pair: {}",
                output.display()
            )),
        }
    }

    fn verify_rebuild(&self, output: &Path) -> Result<String, String> {
        if Self::snapshot(output)? != *self {
            return Err(format!(
                "output changed during this build: {}",
                output.display()
            ));
        }
        let verify = || -> Result<String, String> {
            let container = graphite_storage::Container::open(output).map_err(|e| e.to_string())?;
            use graphite_storage::io::{check_header, read_i32_at, MAGIC_NODEDATA};
            if !container.bytes("graph.nodedata").is_some_and(|bytes| {
                bytes.len() >= 8
                    && matches!(
                        check_header(read_i32_at(&bytes, 0), MAGIC_NODEDATA),
                        Some(1..=3)
                    )
            }) {
                return Err("missing or unsupported Graphite node-data header".into());
            }
            let report = container.verify().map_err(|e| e.to_string())?;
            let sidecar_expected = matches!(
                self,
                Self::Rebuild {
                    digest: Some(_),
                    ..
                }
            );
            if !report.ok()
                || !report.has_manifest
                || report.digest_file != sidecar_expected.then_some(true)
            {
                return Err(format!("expected a verified Graphite manifest, required graph entries and a matching sidecar when present: {}", report.failures.join(", ")));
            }
            Ok(report.file_sha256)
        };
        verify().map_err(|e| format!("refusing to replace {}: {e}", output.display()))
    }
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
        match publication {
            Publication::CreateNew => packed::publish(&destination, &report.digest_file, output)?,
            Publication::Rebuild {
                ref container,
                ref digest,
            } => {
                let mut claim = replace::Claim::new(output)?;
                let old_hash = publication.verify_rebuild(output)?;
                claim.replace(
                    &destination,
                    &report.digest_file,
                    output,
                    (container, digest.as_ref(), &old_hash),
                    |a, b| std::fs::rename(a, b),
                )?
            }
        }
        crate::pack::print_report(&report, output);
        Ok(())
    }

    pub(crate) fn publish_directory(&self, output: &Path) -> Result<(), String> {
        create_output_parent(output)?;
        directory::publish(&self.graph(), output, rename_exclusive).map_err(|e| {
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

    pub(crate) fn rebuild_directory(
        &mut self,
        output: &Path,
        publication: DirectoryPublication,
    ) -> Result<(), String> {
        create_output_parent(output)?;
        publication.publish(self, output)
    }

    fn retain(&mut self) -> Result<(), String> {
        self.1 = true;
        stale::prevent_sweep(&self.0)
            .map_err(|e| format!("could not retain {}: {e}", self.0.display()))
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
            if ancestor == parent || dir == temp_dir {
                return Self::in_directory(dir);
            }
            // A device ID cannot distinguish all bind mounts. Move an empty
            // scratch directory to a private, unoccupied name on the target.
            // Only the chosen stage parent needs an owner record or a sweep.
            let probe = || -> std::io::Result<()> {
                let source = tempfile::tempdir_in(temp_dir)?;
                let target = tempfile::tempdir_in(dir)?;
                rename_probe(source.path(), &target.path().join("probe"))
            };
            if probe().is_ok() {
                if let Ok(stage) = Self::in_directory(temp_dir) {
                    return Ok(stage);
                }
            }
            return Self::in_directory(dir);
        }
        Err(std::io::Error::new(
            std::io::ErrorKind::NotFound,
            "no existing output ancestor",
        ))
    }

    fn in_directory(parent: &Path) -> std::io::Result<Self> {
        Ok(Self(stale::create(parent)?.keep(), false))
    }

    pub(crate) fn run(
        &self,
        command: &mut std::process::Command,
    ) -> std::io::Result<std::process::ExitStatus> {
        stale::run(&self.0, command)
    }
}

#[cfg(any(
    target_vendor = "apple",
    target_os = "linux",
    target_os = "android",
    target_os = "redox"
))]
fn rename_exclusive(from: &Path, to: &Path) -> std::io::Result<()> {
    use rustix::fs::{renameat_with, RenameFlags, CWD};
    Ok(renameat_with(CWD, from, CWD, to, RenameFlags::NOREPLACE)?)
}

#[cfg(windows)]
fn rename_exclusive(from: &Path, to: &Path) -> std::io::Result<()> {
    // On Windows persist_noclobber uses MoveFileExW without REPLACE_EXISTING or COPY_ALLOWED.
    let mut staged = tempfile::TempPath::try_from_path(from)?;
    staged.disable_cleanup(true); // The stage owns the source even if the rename fails.
    staged.persist_noclobber(to).map_err(|e| e.error)
}

#[cfg(not(any(
    windows,
    target_vendor = "apple",
    target_os = "linux",
    target_os = "android",
    target_os = "redox"
)))]
fn rename_exclusive(_: &Path, _: &Path) -> std::io::Result<()> {
    Err(std::io::Error::new(
        std::io::ErrorKind::Unsupported,
        "exclusive rename is unavailable",
    ))
}

impl Drop for Stage {
    fn drop(&mut self) {
        if self.1 {
            return;
        }
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

    pub(super) fn stage_value(output: &Path, value: &str) -> Stage {
        let stage = Stage::new(output).unwrap();
        let input = serde_json::json!({"version":1,"methods":[], "nodes":[{"id":0,"kind":"StringConstant","value":value}],"edges":[]});
        graphite_storage::interchange::import_json(input.to_string().as_bytes(), &stage.graph())
            .unwrap();
        stage
    }

    #[test]
    fn a_build_cannot_replace_an_output_published_since_it_started() {
        let dir = tempfile::tempdir().unwrap();
        let output = dir.path().join("published.graphite");
        let fresh = Publication::inspect(&output).unwrap();
        stage_value(&output, "first")
            .pack(&output, Publication::inspect(&output).unwrap())
            .unwrap();
        let digest = graphite_storage::container::digest_path(&output);
        let contents = || {
            (
                std::fs::read(&output).unwrap(),
                std::fs::read(&digest).unwrap(),
            )
        };
        let first = contents();
        let err = stage_value(&output, "late new")
            .pack(&output, fresh)
            .unwrap_err();
        assert!(err.contains("already exists"));
        assert_eq!(contents(), first);

        let concurrent_rebuild = Publication::inspect(&output).unwrap();
        stage_value(&output, "winner")
            .pack(&output, Publication::inspect(&output).unwrap())
            .unwrap();
        let winner = contents();
        assert_ne!(winner, first);
        let err = stage_value(&output, "late rebuild")
            .pack(&output, concurrent_rebuild)
            .unwrap_err();
        assert!(err.contains("output changed during this build"), "{err}");
        assert_eq!(contents(), winner);
    }

    #[test]
    fn moved_standalone_graph_is_verified_and_rebuilt_with_its_new_checksum() {
        let dir = tempfile::tempdir().unwrap();
        let original = dir.path().join("original.graphite");
        stage_value(&original, "old")
            .pack(&original, Publication::CreateNew)
            .unwrap();
        let moved = dir.path().join("relocated.graphite");
        std::fs::rename(&original, &moved).unwrap();
        let checksum = graphite_storage::container::digest_path(&moved);
        let expected = Publication::inspect(&moved).unwrap();
        let stage = stage_value(&moved, "replacement");
        let previous = std::fs::read(&moved).unwrap();

        std::fs::write(&checksum, b"other file").unwrap();
        assert!(stage
            .pack(&moved, expected)
            .unwrap_err()
            .contains("output changed"));
        assert_eq!(std::fs::read(&moved).unwrap(), previous);
        assert_eq!(std::fs::read(&checksum).unwrap(), b"other file");
        std::fs::remove_file(&checksum).unwrap();

        // A header alone cannot prove the old bytes are a graph. Flip a byte
        // in the manifest-covered string table without changing the directory.
        let archive = graphite_storage::Container::open(&moved).unwrap();
        let offset = archive.entry("graph.strings").unwrap().offset as usize;
        drop(archive);
        let mut corrupt = previous.clone();
        corrupt[offset] ^= 1;
        std::fs::write(&moved, &corrupt).unwrap();
        let err = stage
            .pack(&moved, Publication::inspect(&moved).unwrap())
            .unwrap_err();
        assert!(err.contains("CRC-32 mismatch"), "{err}");
        assert_eq!(std::fs::read(&moved).unwrap(), corrupt);
        assert!(!checksum.exists());

        std::fs::write(&moved, &previous).unwrap();
        stage
            .pack(&moved, Publication::inspect(&moved).unwrap())
            .unwrap();
        let verified = graphite_storage::Container::open(&moved)
            .unwrap()
            .verify()
            .unwrap();
        assert!(verified.ok() && verified.has_manifest && verified.digest_file == Some(true));
        let graph = graphite_storage::Graph::load(&moved).unwrap();
        let graphite_storage::NodeKind::StringConstant(id) = graph.node(0).unwrap().kind else {
            panic!("expected string")
        };
        assert_eq!(graph.str(id), "replacement");
    }

    #[test]
    fn rebuilding_refuses_unrelated_files_and_mismatched_digests() {
        let dir = tempfile::tempdir().unwrap();
        let output = dir.path().join("published.graphite");
        let digest = graphite_storage::container::digest_path(&output);
        let contents = || {
            (
                std::fs::read(&output).unwrap(),
                std::fs::read(&digest).unwrap(),
            )
        };
        std::fs::write(&output, b"unrelated").unwrap();
        let error = stage_value(&output, "never published")
            .pack(&output, Publication::inspect(&output).unwrap())
            .unwrap_err();
        assert!(error.contains("refusing to replace"), "{error}");
        assert!(digest.symlink_metadata().is_err());
        assert_eq!(std::fs::read(&output).unwrap(), b"unrelated");
        std::fs::write(&digest, b"unrelated digest").unwrap();
        let refuse = || {
            let original = contents();
            let error = stage_value(&output, "never published")
                .pack(&output, Publication::inspect(&output).unwrap())
                .unwrap_err();
            assert!(error.contains("refusing to replace"), "{error}");
            assert_eq!(contents(), original);
        };
        refuse();

        let fake = dir.path().join("fake");
        std::fs::create_dir(&fake).unwrap();
        for entry in graphite_storage::container::REQUIRED_ENTRIES {
            std::fs::write(fake.join(entry), b"unrelated bytes").unwrap();
        }
        graphite_storage::container::pack(&fake, &output).unwrap();
        refuse();

        graphite_storage::container::pack(&stage_value(&output, "previous").graph(), &output)
            .unwrap();
        std::fs::write(&digest, format!("{}  published.graphite\n", "0".repeat(64))).unwrap();
        refuse();
    }

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
            assert_eq!(from.parent(), Some(temp.path()));
            assert_eq!(std::fs::read_dir(from).unwrap().count(), 0);
            let target = to.parent().unwrap();
            assert_eq!(target.parent(), Some(nearest.as_path()));
            assert_eq!(std::fs::read_dir(target).unwrap().count(), 0);
            assert!(!to.exists());
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
        assert_eq!(
            DirectoryPublication::inspect(&output).unwrap(),
            DirectoryPublication::CreateNew
        );
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
        packed::publish_with(&staged, &checksum, &output, unsupported, rename_exclusive).unwrap();
        assert_eq!(std::fs::read(&output).unwrap(), data);
        assert_eq!(std::fs::read_to_string(&digest).unwrap(), checksum_data);

        // A competing graph can appear after the initial existence check.
        std::fs::remove_file(&digest).unwrap();
        std::fs::write(&output, b"another writer").unwrap();
        pack();
        assert!(
            packed::publish_with(&staged, &checksum, &output, unsupported, rename_exclusive)
                .is_err()
        );
        assert_eq!(std::fs::read(&output).unwrap(), b"another writer");
        assert!(!digest.exists());

        std::fs::remove_file(&output).unwrap();
        std::fs::write(&digest, b"another digest").unwrap();
        pack();
        assert!(
            packed::publish_with(&staged, &checksum, &output, unsupported, rename_exclusive)
                .is_err()
        );
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
            packed::publish(file, checksum, &output)
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
        assert!(packed::publish(&retry_file, &retry_digest, &output).is_err());
        assert_eq!(std::fs::read_to_string(&output).unwrap(), winner);
        assert!(!digest.exists());
    }
}

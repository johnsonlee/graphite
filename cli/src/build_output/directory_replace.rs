//! Offline replacement of an existing directory. Never expose a partially saved graph.
use super::Stage;
use same_file::Handle;
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fs;
#[cfg(unix)]
use std::fs::File;
use std::io::{self, Read};
use std::path::{Path, PathBuf};

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum DirectoryPublication {
    CreateNew,
    Rebuild(Snapshot),
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) struct Snapshot {
    directory: Handle,
    permissions: fs::Permissions,
    files: BTreeMap<PathBuf, (Handle, [u8; 32])>,
    metadata_directory: Option<Handle>,
}

impl DirectoryPublication {
    pub(crate) fn inspect(path: &Path) -> Result<Self, String> {
        // Remove trailing separators and "/." without following the final path.
        // Unix otherwise resolves "symlink/" as the target directory.
        let normalized = path.components().collect::<PathBuf>();
        let path = normalized.as_path();
        match fs::symlink_metadata(path) {
            Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(Self::CreateNew),
            Err(e) => return Err(format!("could not inspect {}: {e}", path.display())),
            Ok(meta) if !meta.is_dir() => {
                return Err(format!("output is not a directory: {}", path.display()))
            }
            Ok(_) => {}
        }
        let resolved = path.canonicalize().map_err(|e| e.to_string())?;
        let cwd = std::env::current_dir()
            .and_then(|p| p.canonicalize())
            .map_err(|e| e.to_string())?;
        if cwd.starts_with(&resolved) {
            return Err(format!("refusing to rebuild the current working directory or its ancestor {}; choose a different output directory", path.display()));
        }
        let snapshot = Snapshot::read(path)
            .map_err(|e| format!("refusing to replace {}: {e}", path.display()))?;
        snapshot
            .directory
            .as_file()
            .try_lock_shared()
            .map_err(|e| {
                format!(
                    "could not inspect {} while another publisher is active: {e}",
                    path.display()
                )
            })?;
        snapshot.require_unchanged(path)?;
        if !snapshot.files.is_empty() || snapshot.metadata_directory.is_some() {
            // A recognized filename alone does not authorize replacing a directory.
            // Legacy readers contain unchecked offsets in some formats. A
            // corrupt old graph must remain untouched even if that reader panics.
            std::panic::catch_unwind(|| {
                let graph = graphite_storage::Graph::load(path).map_err(|e| e.to_string())?;
                if let Some(error) = graph.declared_types_error() {
                    return Err(error.to_string());
                }
                Ok::<_, String>(())
            })
            .unwrap_or_else(|_| Err("existing graph could not be decoded".into()))
            .map_err(|e| format!("refusing to replace {}: {e}", path.display()))?;
        }
        snapshot
            .directory
            .as_file()
            .unlock()
            .map_err(|e| e.to_string())?;
        Ok(Self::Rebuild(snapshot))
    }

    pub(super) fn publish(self, stage: &mut Stage, output: &Path) -> Result<(), String> {
        self.publish_with(stage, output, exchange)
    }

    fn publish_with(
        self,
        stage: &mut Stage,
        output: &Path,
        exchange: impl Fn(&Path, &Path) -> io::Result<()>,
    ) -> Result<(), String> {
        let Self::Rebuild(previous) = self else {
            return stage.publish_directory(output);
        };
        previous.directory.as_file().try_lock().map_err(|e| {
            format!(
                "could not claim {} for directory rebuild: {e}",
                output.display()
            )
        })?;
        previous.require_unchanged(output)?;
        fs::set_permissions(stage.graph(), previous.permissions.clone())
            .map_err(|e| e.to_string())?;
        let next = Snapshot::read(&stage.graph()).map_err(|e| e.to_string())?;
        // Once the exchange starts the stage can contain a user's old directory.
        // A crash or an unexpected displaced path must never let the stage sweeper
        // recursively delete that directory.
        stage.retain()?;
        if let Err(error) = exchange(&stage.graph(), output) {
            stage.1 = false;
            return Err(format!("could not atomically rebuild directory {}: {error}; the previous directory was preserved", output.display()));
        }
        if let Err(error) = previous.require_unchanged(&stage.graph()) {
            // Only roll back while our complete generation still owns the public name.
            if next.require_unchanged(output).is_ok() && exchange(&stage.graph(), output).is_ok() {
                stage.1 = false;
                return Err(format!("{error}; the previous directory was restored"));
            }
            return Err(format!(
                "{error}; displaced directory retained at {}",
                stage.graph().display()
            ));
        }

        // Unlink only the files from the verified snapshot. Unknown late additions
        // make remove_dir fail and leave the retained stage for manual inspection.
        if let Err(error) = previous.remove_known(&stage.graph()) {
            eprintln!(
                "Warning: rebuilt {}, but retained its previous directory at {}: {error}",
                output.display(),
                stage.graph().display()
            );
            return Ok(());
        }
        stage.1 = false;
        Ok(())
    }
}

impl Snapshot {
    fn read(path: &Path) -> io::Result<Self> {
        let directory = handle(path, true)?;
        let mut files = BTreeMap::new();
        let mut metadata_directory = None;
        for item in fs::read_dir(path)? {
            let item = item?;
            if item.file_name() == "META-INF" {
                metadata_directory = Some(handle(&item.path(), true)?);
                for child in fs::read_dir(item.path())? {
                    let child = child?;
                    if child.file_name() != "graphite.manifest" {
                        return Err(unrelated(&child.path()));
                    }
                    files.insert(
                        PathBuf::from("META-INF/graphite.manifest"),
                        fingerprint(&child.path())?,
                    );
                }
            } else {
                let name = item.file_name();
                if !known_graph_file(name.to_str().unwrap_or("")) {
                    return Err(unrelated(&item.path()));
                }
                files.insert(PathBuf::from(name), fingerprint(&item.path())?);
            }
        }
        if handle(path, true)? != directory {
            return Err(io::Error::other("directory changed while inspecting it"));
        }
        let permissions = directory.as_file().metadata()?.permissions();
        Ok(Self {
            directory,
            permissions,
            files,
            metadata_directory,
        })
    }

    fn require_unchanged(&self, path: &Path) -> Result<(), String> {
        if Self::read(path).is_ok_and(|snapshot| snapshot == *self) {
            Ok(())
        } else {
            Err(format!(
                "output changed during this build: {}",
                path.display()
            ))
        }
    }

    fn remove_known(&self, path: &Path) -> io::Result<()> {
        if handle(path, true)? != self.directory {
            return Err(io::Error::other("displaced directory identity changed"));
        }
        for (name, expected) in &self.files {
            let file = path.join(name);
            if fingerprint(&file)? != *expected {
                return Err(io::Error::other(format!(
                    "displaced entry changed: {}",
                    file.display()
                )));
            }
            fs::remove_file(file)?;
        }
        if let Some(expected) = &self.metadata_directory {
            let meta = path.join("META-INF");
            if handle(&meta, true)? != *expected {
                return Err(io::Error::other("displaced META-INF changed"));
            }
            fs::remove_dir(meta)?;
        }
        fs::remove_dir(path)
    }
}

fn unrelated(path: &Path) -> io::Error {
    io::Error::other(format!(
        "unrelated entry in output directory: {}",
        path.display()
    ))
}

fn known_graph_file(name: &str) -> bool {
    graphite_storage::container::REQUIRED_ENTRIES.contains(&name)
        || matches!(
            name,
            "graph.nodeindex"
                | "graph.comparisons"
                | "graph.classoverview"
                | "graph.resources"
                | "graph.branchdefs"
                | "graph.labelprefix"
                | "graph.strings.identity"
                | "graph.types"
                | "graph.declared-keys"
                | "graph.declared-presence"
                | "graph.callsite-ordinals"
                | "graph.callsite-string-index"
                | "graph.callsite-string-content.identity"
                | "graph.folds.json"
                | "graph.folds.plan.json"
                | "backward.graph"
                | "backward.offsets"
                | "backward.properties"
        )
}

fn fingerprint(path: &Path) -> io::Result<(Handle, [u8; 32])> {
    let id = handle(path, false)?;
    let mut file = id.as_file();
    let mut digest = Sha256::new();
    let mut buf = [0; 65536];
    loop {
        let count = file.read(&mut buf)?;
        if count == 0 {
            break;
        }
        digest.update(&buf[..count]);
    }
    Ok((id, digest.finalize().into()))
}

#[cfg(unix)]
fn handle(path: &Path, directory: bool) -> io::Result<Handle> {
    use rustix::fs::{open, Mode, OFlags};
    let mut flags = OFlags::RDONLY | OFlags::NOFOLLOW | OFlags::NONBLOCK | OFlags::CLOEXEC;
    if directory {
        flags |= OFlags::DIRECTORY;
    }
    let file = File::from(open(path, flags, Mode::empty())?);
    let meta = file.metadata()?;
    if (directory && !meta.is_dir()) || (!directory && !meta.is_file()) {
        return Err(unrelated(path));
    }
    Handle::from_file(file)
}

#[cfg(not(unix))]
fn handle(path: &Path, directory: bool) -> io::Result<Handle> {
    let meta = fs::symlink_metadata(path)?;
    if (directory && !meta.is_dir()) || (!directory && !meta.is_file()) {
        return Err(unrelated(path));
    }
    Handle::from_path(path)
}

#[cfg(any(target_vendor = "apple", target_os = "linux", target_os = "android"))]
fn exchange(from: &Path, to: &Path) -> io::Result<()> {
    use rustix::fs::{renameat_with, RenameFlags, CWD};
    Ok(renameat_with(CWD, from, CWD, to, RenameFlags::EXCHANGE)?)
}

#[cfg(not(any(target_vendor = "apple", target_os = "linux", target_os = "android")))]
fn exchange(_: &Path, _: &Path) -> io::Result<()> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "atomic directory exchange is unavailable",
    ))
}

#[cfg(all(test, any(target_vendor = "apple", target_os = "linux")))]
mod tests {
    use super::*;
    use crate::build_output::tests::stage_value;
    use graphite_storage::{Graph, NodeKind};
    use std::os::unix::fs::{symlink, PermissionsExt};

    fn value(dir: &Path) -> String {
        let graph = Graph::load(dir).unwrap();
        let NodeKind::StringConstant(id) = graph.node(0).unwrap().kind else {
            panic!("string expected")
        };
        graph.str(id).to_owned()
    }

    fn create(output: &Path, text: &str) {
        stage_value(output, text).publish_directory(output).unwrap();
    }

    #[test]
    fn empty_and_saved_directories_rebuild_and_preserve_permissions() {
        let root = tempfile::tempdir().unwrap();
        let out = root.path().join("graph");
        fs::create_dir(&out).unwrap();
        fs::set_permissions(&out, fs::Permissions::from_mode(0o700)).unwrap();
        for next in ["first", "second"] {
            let publication = DirectoryPublication::inspect(&out).unwrap();
            let mut stage = stage_value(&out, next);
            publication.publish(&mut stage, &out).unwrap();
            assert_eq!(value(&out), next);
            assert_eq!(
                fs::metadata(&out).unwrap().permissions().mode() & 0o777,
                0o700
            );
            assert!(!stage.graph().exists());
        }
        assert_eq!(fs::read_dir(root.path()).unwrap().count(), 1);
    }

    #[test]
    fn snapshots_detect_in_place_edits_and_competing_publishers() {
        let root = tempfile::tempdir().unwrap();
        let out = root.path().join("graph");
        create(&out, "old");
        let stale = DirectoryPublication::inspect(&out).unwrap();
        let winner = DirectoryPublication::inspect(&out).unwrap();
        winner
            .publish(&mut stage_value(&out, "winner"), &out)
            .unwrap();
        assert!(stale
            .publish(&mut stage_value(&out, "loser"), &out)
            .unwrap_err()
            .contains("output changed"));
        assert_eq!(value(&out), "winner");

        let edited = DirectoryPublication::inspect(&out).unwrap();
        let file = out.join("graph.strings");
        let mut bytes = fs::read(&file).unwrap();
        *bytes.last_mut().unwrap() ^= 1;
        fs::write(&file, &bytes).unwrap();
        assert!(edited
            .publish(&mut stage_value(&out, "ignore"), &out)
            .unwrap_err()
            .contains("output changed"));
        assert_eq!(fs::read(&file).unwrap(), bytes);
    }

    #[test]
    fn unrelated_files_links_and_incomplete_graphs_are_never_authorized() {
        let root = tempfile::tempdir().unwrap();
        let out = root.path().join("graph");
        create(&out, "old");
        for name in ["keep.txt", "graph.unknown", "nested/keep"] {
            let file = out.join(name);
            fs::create_dir_all(file.parent().unwrap()).unwrap();
            fs::write(&file, b"keep").unwrap();
            assert!(DirectoryPublication::inspect(&out)
                .unwrap_err()
                .contains("unrelated"));
            assert_eq!(fs::read(&file).unwrap(), b"keep");
            fs::remove_file(file).unwrap();
        }
        fs::remove_dir(out.join("nested")).unwrap();
        let file = out.join("graph.resources");
        symlink(root.path().join("foreign"), &file).unwrap();
        assert!(DirectoryPublication::inspect(&out).is_err());
        fs::remove_file(file).unwrap();
        symlink(&out, root.path().join("link")).unwrap();
        assert!(DirectoryPublication::inspect(&root.path().join("link")).is_err());
        fs::remove_file(out.join("graph.metadata")).unwrap();
        assert!(DirectoryPublication::inspect(&out)
            .unwrap_err()
            .contains("refusing to replace"));
    }

    #[test]
    fn output_aliases_refuse_cwd_and_do_not_hide_symlinks() {
        assert!(DirectoryPublication::inspect(Path::new("."))
            .unwrap_err()
            .contains("current working directory"));
        let root = tempfile::tempdir().unwrap();
        let out = root.path().join("graph");
        fs::create_dir(&out).unwrap();
        assert!(DirectoryPublication::inspect(&out.join(".")).is_ok());
        let link = root.path().join("link");
        symlink(&out, &link).unwrap();
        for alias in [link.clone(), link.join(""), link.join(".")] {
            assert!(DirectoryPublication::inspect(&alias)
                .unwrap_err()
                .contains("not a directory"));
        }
        assert_eq!(fs::read_dir(&out).unwrap().count(), 0);
    }

    #[test]
    fn a_truncated_existing_graph_is_refused_without_mutating_it() {
        let root = tempfile::tempdir().unwrap();
        let out = root.path().join("graph");
        create(&out, "old");
        let file = out.join("graph.nodeoffsets");
        fs::write(&file, b"x").unwrap();
        let before = Snapshot::read(&out).unwrap();
        assert!(DirectoryPublication::inspect(&out)
            .unwrap_err()
            .contains("could not be decoded"));
        before.require_unchanged(&out).unwrap();
        assert_eq!(fs::read(file).unwrap(), b"x");
    }

    #[test]
    fn unsupported_exchange_and_active_directory_claim_preserve_the_old_graph() {
        let root = tempfile::tempdir().unwrap();
        let out = root.path().join("graph");
        create(&out, "old");
        let publication = DirectoryPublication::inspect(&out).unwrap();
        let lock = File::open(&out).unwrap();
        lock.lock().unwrap();
        assert!(publication
            .publish(&mut stage_value(&out, "failed"), &out)
            .unwrap_err()
            .contains("could not claim"));
        drop(lock);
        let publication = DirectoryPublication::inspect(&out).unwrap();
        let err = publication
            .publish_with(&mut stage_value(&out, "failed"), &out, |_, _| {
                Err(io::ErrorKind::Unsupported.into())
            })
            .unwrap_err();
        assert!(err.contains("previous directory was preserved"), "{err}");
        assert_eq!(value(&out), "old");
        assert_eq!(fs::read_dir(root.path()).unwrap().count(), 1);
    }

    #[test]
    fn a_late_unrelated_entry_is_restored_and_an_ambiguous_backup_is_retained() {
        for rollback in [true, false] {
            let root = tempfile::tempdir().unwrap();
            let out = root.path().join("graph");
            create(&out, "old");
            let publication = DirectoryPublication::inspect(&out).unwrap();
            let mut stage = stage_value(&out, "new");
            let retained = stage.0.clone();
            let calls = std::cell::Cell::new(0);
            let err = publication
                .publish_with(&mut stage, &out, |a, b| {
                    calls.set(calls.get() + 1);
                    if calls.get() == 1 {
                        fs::write(b.join("unrelated"), b"keep")?;
                        exchange(a, b)?;
                        if !rollback {
                            fs::write(b.join("another-writer"), b"also keep")?;
                        }
                        Ok(())
                    } else {
                        exchange(a, b)
                    }
                })
                .unwrap_err();
            drop(stage);
            if rollback {
                assert!(err.contains("restored"), "{err}");
                assert_eq!(fs::read(out.join("unrelated")).unwrap(), b"keep");
                assert_eq!(value(&out), "old");
                assert!(!retained.exists());
            } else {
                assert!(err.contains("retained"), "{err}");
                assert_eq!(fs::read(retained.join("saved/unrelated")).unwrap(), b"keep");
                assert_eq!(value(&retained.join("saved")), "old");
                assert_eq!(value(&out), "new");
                assert_eq!(fs::read(out.join("another-writer")).unwrap(), b"also keep");
                // Creating another stage must not sweep the retained backup.
                drop(Stage::new(&out).unwrap());
                assert!(retained.join("saved/unrelated").is_file());
            }
        }
    }

    #[test]
    fn an_unpacked_manifest_is_a_recognized_graph_entry() {
        let root = tempfile::tempdir().unwrap();
        let out = root.path().join("graph");
        create(&out, "old");
        let archive = root.path().join("old.graphite");
        graphite_storage::container::pack(&out, &archive).unwrap();
        let unpacked = root.path().join("unpacked");
        graphite_storage::Container::open(&archive)
            .unwrap()
            .unpack(&unpacked)
            .unwrap();
        let publication = DirectoryPublication::inspect(&unpacked).unwrap();
        publication
            .publish(&mut stage_value(&unpacked, "new"), &unpacked)
            .unwrap();
        assert_eq!(value(&unpacked), "new");
    }
}

//! Exclusive directory publication when the filesystem lacks exclusive rename.
use same_file::Handle;
use std::fs::{self, File};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

// Graph::load requires this header before consuming any node data.
const READY: &str = "graph.nodedata";

pub(super) fn publish(
    from: &Path,
    to: &Path,
    rename: impl Fn(&Path, &Path) -> io::Result<()>,
) -> io::Result<()> {
    let Err(error) = rename(from, to) else {
        return Ok(());
    };
    let ambiguous = error.kind() == io::ErrorKind::InvalidInput;
    if !ambiguous && !rename_unsupported(&error) {
        return Err(error);
    }

    let parent = to
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    // A directory cannot be moved into itself or a descendant, including
    // through a symlink. The fallback must respect that native restriction.
    let (Ok(source), Ok(destination_parent), Some(name)) =
        (from.canonicalize(), parent.canonicalize(), to.file_name())
    else {
        return Err(error);
    };
    if destination_parent.starts_with(&source) || destination_parent.join(name) == source {
        return Err(error);
    }
    if ambiguous && !probe_unsupported(parent, &rename).unwrap_or(false) {
        return Err(error);
    }
    publish_claimed(from, to, |a, b| fs::hard_link(a, b))
}

fn probe_unsupported(
    parent: &Path,
    rename: &impl Fn(&Path, &Path) -> io::Result<()>,
) -> io::Result<bool> {
    // EINVAL also describes invalid path layouts. Distinguish flag support
    // using a valid exclusive rename of an empty directory on this filesystem.
    let mut builder = tempfile::Builder::new();
    builder.prefix(".graphite-build-probe-");
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        builder.permissions(fs::Permissions::from_mode(0o700));
    }
    let scratch = builder.tempdir_in(parent)?;
    let source = scratch.path().join("source");
    fs::create_dir(&source)?;
    Ok(rename(&source, &scratch.path().join("destination"))
        .is_err_and(|e| e.kind() == io::ErrorKind::InvalidInput || rename_unsupported(&e)))
}

pub(super) fn rename_unsupported(e: &io::Error) -> bool {
    if e.kind() == io::ErrorKind::Unsupported {
        return true;
    }
    // Darwin ENOTSUP and EOPNOTSUPP are distinct; std's ErrorKind needn't map both.
    #[cfg(any(
        target_vendor = "apple",
        target_os = "linux",
        target_os = "android",
        target_os = "redox"
    ))]
    if let Some(errno) = rustix::io::Errno::from_io_error(e) {
        use rustix::io::Errno;
        return [Errno::NOSYS, Errno::NOTSUP, Errno::OPNOTSUPP].contains(&errno);
    }
    #[cfg(windows)]
    if matches!(e.raw_os_error(), Some(1 | 50)) {
        // ERROR_INVALID_FUNCTION, ERROR_NOT_SUPPORTED
        return true;
    }
    false
}

fn publish_claimed(
    from: &Path,
    to: &Path,
    link: impl Fn(&Path, &Path) -> io::Result<()>,
) -> io::Result<()> {
    let mut paths = Vec::new();
    for entry in fs::read_dir(from)? {
        let entry = entry?;
        if !entry.file_type()?.is_file() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "unexpected entry in saved graph",
            ));
        }
        paths.push(entry.file_name());
    }
    paths.sort();
    let ready = paths
        .iter()
        .position(|p| p == READY)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing graph.nodedata"))?;
    let ready = paths.remove(ready);
    paths.push(ready);

    let directory = claim_directory(to, |path| Handle::from_path(path))?;
    let mut owned = Vec::new();
    for name in paths {
        if !still_owned(to, &directory, true)? {
            return Err(io::Error::new(
                io::ErrorKind::AlreadyExists,
                "claimed output directory was replaced",
            ));
        }
        let target = to.join(&name);
        if let Err(error) =
            publish_entry(&from.join(&name), &target, name == READY, &link, &mut owned)
        {
            if let Err(cleanup) = rollback(to, &directory, owned) {
                return Err(io::Error::new(
                    error.kind(),
                    format!("{error}; cleaning {}: {cleanup}", to.display()),
                ));
            }
            return Err(error);
        }
    }
    Ok(())
}

fn claim_directory(
    to: &Path,
    identify: impl FnOnce(&Path) -> io::Result<Handle>,
) -> io::Result<Handle> {
    fs::create_dir(to)?;
    // stat does not need a free descriptor. It can still identify our empty
    // directory for rollback if opening the durable handle fails with EMFILE.
    #[cfg(unix)]
    let created = fs::symlink_metadata(to)?;
    identify(to).map_err(|error| {
        #[cfg(unix)]
        {
            use std::os::unix::fs::MetadataExt;
            if created.is_dir() && fs::symlink_metadata(to).is_ok_and(|now| {
                now.is_dir() && now.dev() == created.dev() && now.ino() == created.ino()
            }) {
                match fs::remove_dir(to) {
                    Ok(()) => return error,
                    Err(e) if e.kind() == io::ErrorKind::NotFound => return error,
                    Err(e) => return io::Error::new(error.kind(), format!("{error}; cleaning {}: {e}", to.display())),
                }
            }
        }
        io::Error::new(error.kind(), format!("{error}; could not establish ownership of {}; inspect the directory before retrying", to.display()))
    })
}

fn publish_entry(
    from: &Path,
    to: &Path,
    ready: bool,
    link: &impl Fn(&Path, &Path) -> io::Result<()>,
    owned: &mut Vec<(PathBuf, Handle)>,
) -> io::Result<()> {
    let mut source = File::open(from)?;
    let id = Handle::from_file(source.try_clone()?)?;
    match link(from, to) {
        Ok(()) => {
            owned.push((to.to_owned(), id));
            return Ok(());
        }
        Err(e) if e.kind() == io::ErrorKind::AlreadyExists => return Err(e),
        Err(_) => {}
    }

    let mut target = File::create_new(to)?;
    owned.push((to.to_owned(), Handle::from_file(target.try_clone()?)?));
    if ready {
        copy_ready(&mut source, &target)?;
    } else {
        io::copy(&mut source, &mut target)?;
    }
    Ok(())
}

// With no hard links, copy through the exclusively opened handle. Leave the
// header invalid until every byte of every graph file is in place.
fn copy_ready(mut source: impl Read, mut target: &File) -> io::Result<()> {
    let mut header = [0; 4];
    source.read_exact(&mut header)?;
    target.seek(SeekFrom::Start(4))?;
    io::copy(&mut source, &mut target)?;
    target.seek(SeekFrom::Start(0))?;
    target.write_all(&header)
}

fn still_owned(path: &Path, id: &Handle, directory: bool) -> io::Result<bool> {
    let meta = match fs::symlink_metadata(path) {
        Ok(m) => m,
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(false),
        Err(e) => return Err(e),
    };
    let same_type = if directory {
        meta.is_dir()
    } else {
        meta.is_file()
    };
    Ok(same_type && Handle::from_path(path)? == *id)
}

fn rollback(to: &Path, directory: &Handle, owned: Vec<(PathBuf, Handle)>) -> io::Result<()> {
    if !still_owned(to, directory, true)? {
        return Ok(());
    }
    for (path, id) in owned.into_iter().rev() {
        if still_owned(&path, &id, false)? {
            fs::remove_file(path)?;
        }
    }
    // Leave other writers' files (including replacements of our own entries) intact.
    if still_owned(to, directory, true)? {
        match fs::remove_dir(to) {
            Err(e) if e.kind() == io::ErrorKind::DirectoryNotEmpty => {}
            result => result?,
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use graphite_storage::{Graph, NodeKind};

    #[cfg(unix)]
    #[test]
    fn identity_failure_releases_only_the_empty_directory_we_claimed() {
        let tmp = tempfile::tempdir().unwrap();
        let out = tmp.path().join("out");
        let fail = || io::Error::other("injected handle failure");
        assert!(claim_directory(&out, |_| Err(fail())).is_err());
        assert!(!out.exists());

        assert!(claim_directory(&out, |path| {
            fs::rename(path, tmp.path().join("moved original"))?;
            fs::create_dir(path)?;
            Err(fail())
        })
        .is_err());
        assert!(out.is_dir());
        fs::remove_dir(&out).unwrap();

        assert!(claim_directory(&out, |path| {
            fs::write(path.join("competing entry"), b"preserve")?;
            Err(fail())
        })
        .is_err());
        assert_eq!(fs::read(out.join("competing entry")).unwrap(), b"preserve");
    }

    fn saved(dir: &Path) -> PathBuf {
        let from = dir.join("staged");
        let json = br#"{"version":1,"methods":[],"nodes":[{"id":0,"kind":"StringConstant","value":"complete graph"}],"edges":[]}"#;
        graphite_storage::interchange::import_json(json.as_slice(), &from).unwrap();
        from
    }

    fn assert_complete(dir: &Path) {
        let graph = Graph::load(dir).unwrap();
        assert_eq!(graph.node_count(), 1);
        let NodeKind::StringConstant(id) = graph.node(0).unwrap().kind else {
            panic!("expected string")
        };
        assert_eq!(graph.str(id), "complete graph");
    }

    #[test]
    fn unsupported_native_rename_claims_a_new_directory_and_refuses_an_existing_one() {
        let root = tempfile::tempdir().unwrap();
        let from = saved(root.path());
        let to = root.path().join("output");
        publish(&from, &to, |_, _| Err(io::ErrorKind::InvalidInput.into())).unwrap();
        assert_complete(&to);
        let other = root.path().join("already-claimed");
        fs::create_dir(&other).unwrap();
        let error =
            publish(&from, &other, |_, _| Err(io::ErrorKind::Unsupported.into())).unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::AlreadyExists);
        assert_eq!(fs::read_dir(other).unwrap().count(), 0);
        let denied = root.path().join("denied");
        let error = publish(&from, &denied, |_, _| {
            Err(io::ErrorKind::PermissionDenied.into())
        })
        .unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::PermissionDenied);
        assert!(!denied.exists());
    }

    #[test]
    fn invalid_publish_is_not_retried_when_the_probe_supports_exclusive_rename() {
        let root = tempfile::tempdir().unwrap();
        let from = saved(root.path());
        let to = root.path().join("output");
        let probes = std::cell::Cell::new(0);
        let invalid = publish(&from, &to, |a, b| {
            if a == from {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "actual invalid rename",
                ));
            }
            assert!(a.is_dir() && !b.exists() && b.starts_with(root.path()));
            probes.set(probes.get() + 1);
            fs::rename(a, b)
        })
        .unwrap_err();
        assert_eq!(invalid.to_string(), "actual invalid rename");
        assert_eq!(probes.get(), 1);
        assert!(!to.exists());
        assert_complete(&from);
        assert_eq!(fs::read_dir(root.path()).unwrap().count(), 1);
    }

    #[test]
    fn an_unsupported_volume_still_rejects_a_destination_nested_in_the_source() {
        let root = tempfile::tempdir().unwrap();
        let from = saved(root.path());
        let to = from.join("nested");
        for kind in [io::ErrorKind::Unsupported, io::ErrorKind::InvalidInput] {
            let err = publish(&from, &to, |a, b| {
                assert_eq!(
                    (a, b),
                    (from.as_path(), to.as_path()),
                    "invalid layout must not be probed"
                );
                Err(kind.into())
            })
            .unwrap_err();
            assert_eq!(err.kind(), kind);
            assert!(!to.exists());
        }
        assert_complete(&from);
    }

    #[cfg(target_vendor = "apple")]
    #[test]
    fn darwin_notsup_triggers_the_fallback() {
        let root = tempfile::tempdir().unwrap();
        let from = saved(root.path());
        let to = root.path().join("output");
        publish(&from, &to, |_, _| Err(rustix::io::Errno::NOTSUP.into())).unwrap();
        assert_complete(&to);
    }

    #[test]
    fn no_hard_links_copy_all_bytes_with_readiness_last() {
        let root = tempfile::tempdir().unwrap();
        let from = saved(root.path());
        let to = root.path().join("output");
        publish_claimed(&from, &to, |_, _| {
            assert!(Graph::load(&to).is_err(), "a partial graph was readable");
            Err(io::ErrorKind::Unsupported.into())
        })
        .unwrap();
        assert_complete(&to);
        for entry in fs::read_dir(from).unwrap() {
            let f = entry.unwrap();
            assert_eq!(
                fs::read(f.path()).unwrap(),
                fs::read(to.join(f.file_name())).unwrap()
            );
        }
    }

    #[test]
    fn even_a_fully_copied_node_body_is_unreadable_until_header_commit() {
        let root = tempfile::tempdir().unwrap();
        let dir = saved(root.path());
        let path = dir.join(READY);
        let bytes = fs::read(&path).unwrap();
        fs::remove_file(&path).unwrap();
        struct ObserveEof<'a> {
            bytes: &'a [u8],
            dir: &'a Path,
        }
        impl Read for ObserveEof<'_> {
            fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
                if self.bytes.is_empty() {
                    let data = fs::read(self.dir.join(READY))?;
                    assert!(data.len() > 4 && data[..4] == [0; 4]);
                    assert!(Graph::load(self.dir).is_err());
                }
                self.bytes.read(buf)
            }
        }
        copy_ready(
            ObserveEof {
                bytes: &bytes,
                dir: &dir,
            },
            &File::create_new(path).unwrap(),
        )
        .unwrap();
        assert_complete(&dir);
    }

    #[test]
    fn copy_errors_roll_back_only_files_created_by_this_build() {
        let root = tempfile::tempdir().unwrap();
        let from = saved(root.path());
        let to = root.path().join("output");
        fs::write(from.join(READY), b"bad").unwrap();
        let err =
            publish_claimed(&from, &to, |_, _| Err(io::ErrorKind::Unsupported.into())).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::UnexpectedEof);
        assert!(!to.exists(), "owned partial destination must be removed");

        let result = publish_claimed(&from, &to, |_, dest| {
            if dest.file_name().unwrap() == "graph.labels" {
                // A new file, a replaced entry and a competing directory at the
                // next filename all belong to another writer.
                fs::write(to.join("keep"), "unrelated")?;
                fs::rename(to.join("forward.graph"), root.path().join("other-original"))?;
                fs::write(to.join("forward.graph"), "replacement")?;
                fs::create_dir(dest)?;
            }
            Err(io::ErrorKind::Unsupported.into())
        });
        assert!(
            result.is_err(),
            "a competing directory at graph.labels must refuse publication"
        );
        assert_eq!(fs::read_to_string(to.join("keep")).unwrap(), "unrelated");
        assert_eq!(
            fs::read_to_string(to.join("forward.graph")).unwrap(),
            "replacement"
        );
        assert!(to.join("graph.labels").is_dir());
        assert_eq!(
            fs::read_dir(&to).unwrap().count(),
            3,
            "owned entries were left"
        );
        assert!(Graph::load(&to).is_err());
    }
}

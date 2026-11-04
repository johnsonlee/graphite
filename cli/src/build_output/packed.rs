//! Create a new packed graph, claiming its checksum before its container.
use super::{directory::rename_unsupported, rename_exclusive, replace::regular};
use same_file::Handle;
use std::fs::{self, File};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::Path;

pub(super) fn publish(staged: &Path, checksum: &Path, output: &Path) -> Result<(), String> {
    publish_with(
        staged,
        checksum,
        output,
        |a, b| fs::hard_link(a, b),
        rename_exclusive,
    )
}

pub(super) fn publish_with(
    staged: &Path,
    checksum: &Path,
    output: &Path,
    link: impl Fn(&Path, &Path) -> io::Result<()>,
    rename: impl Fn(&Path, &Path) -> io::Result<()>,
) -> Result<(), String> {
    let digest = graphite_storage::container::digest_path(output);
    let file = |from, to: &Path, container| {
        publish_file(from, to, container, &link, &rename).map_err(|e| {
            if e.kind() == io::ErrorKind::AlreadyExists {
                format!("output already exists: {}", to.display())
            } else {
                format!("could not publish {}: {e}", to.display())
            }
        })
    };
    let id = file(checksum, &digest, false)?;
    if let Err(error) = file(staged, output, true) {
        remove_owned(&digest, &id)
            .map_err(|e| format!("{error}; cleaning {}: {e}", digest.display()))?;
        return Err(error);
    }
    Ok(())
}

fn remove_owned(to: &Path, id: &Handle) -> io::Result<()> {
    if regular(to)?.as_ref() == Some(id) {
        fs::remove_file(to)?;
    }
    Ok(())
}

fn publish_file(
    from: &Path,
    to: &Path,
    container: bool,
    link: &impl Fn(&Path, &Path) -> io::Result<()>,
    rename: &impl Fn(&Path, &Path) -> io::Result<()>,
) -> io::Result<Handle> {
    let source = Handle::from_file(File::open(from)?)?;
    match link(from, to) {
        Ok(()) => return Ok(source),
        Err(e) if e.kind() == io::ErrorKind::AlreadyExists => return Err(e),
        Err(_) => {}
    }
    match rename(from, to) {
        Ok(()) => return Ok(source),
        // Some filesystems return EINVAL for an unsupported NOREPLACE flag.
        // create_new still enforces exclusivity and all normal path restrictions.
        Err(e) if rename_unsupported(&e) || e.kind() == io::ErrorKind::InvalidInput => {}
        Err(e) => return Err(e),
    }

    let target = Handle::from_file(File::create_new(to)?)?;
    let mut input = source.as_file();
    let mut output = target.as_file();
    let result = if container {
        copy_container(input, output, |src, dst| io::copy(src, dst))
    } else {
        io::copy(&mut input, &mut output).map(|_| ())
    };
    if let Err(error) = result {
        remove_owned(to, &target)
            .map_err(|e| io::Error::new(error.kind(), format!("{error}; cleanup: {e}")))?;
        return Err(error);
    }
    Ok(target)
}

// The packer ends both ZIP32 and ZIP64 containers with this fixed, commented
// end record. Plant it at its final offset first: the reader scans backwards,
// so this record wins even if a graph string contains an embedded ZIP.
fn copy_container<'a>(
    mut input: &'a File,
    mut output: &File,
    copy_body: impl FnOnce(&mut io::Take<&'a File>, &mut &File) -> io::Result<u64>,
) -> io::Result<()> {
    const COMMENT: &[u8] = graphite_storage::container::ARCHIVE_COMMENT.as_bytes();
    let mut tail = [0; 22 + COMMENT.len()];
    let at = input.seek(SeekFrom::End(-(tail.len() as i64)))?;
    input.read_exact(&mut tail)?;
    if &tail[..8] != b"PK\x05\x06\0\0\0\0" || &tail[22..] != COMMENT {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "invalid staged Graphite ZIP end record",
        ));
    }
    // A nonzero disk number makes Container::open reject this file, even
    // when all payload/central-directory bytes are present. Never truncate a
    // file a concurrent reader could have mapped.
    output.seek(SeekFrom::Start(at))?;
    tail[4] = 1;
    output.write_all(&tail)?;
    input.rewind()?;
    output.rewind()?;
    if copy_body(&mut input.take(at), &mut output)? != at {
        return Err(io::ErrorKind::UnexpectedEof.into());
    }
    output.seek(SeekFrom::Start(at + 4))?;
    output.write_all(&[0])
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::build_output::{Publication, Stage};
    use graphite_storage::{container, Container, Graph, NodeKind};

    fn staged(root: &Path, output: &Path) -> std::path::PathBuf {
        let graph = root.join("saved");
        let json = br#"{"version":1,"methods":[],"nodes":[{"id":0,"kind":"StringConstant","value":"packed fallback"}],"edges":[]}"#;
        graphite_storage::interchange::import_json(json.as_slice(), &graph).unwrap();
        let from = root.join(output.file_name().unwrap());
        container::pack(&graph, &from).unwrap();
        from
    }

    fn unsupported(_: &Path, _: &Path) -> io::Result<()> {
        Err(io::ErrorKind::Unsupported.into())
    }

    #[test]
    fn copies_and_verifies_a_complete_graph_when_neither_primitive_is_supported() {
        let src = tempfile::tempdir().unwrap();
        let dst = tempfile::tempdir().unwrap();
        let out = dst.path().join("app.graphite");
        let input = staged(src.path(), &out);
        publish_with(
            &input,
            &container::digest_path(&input),
            &out,
            unsupported,
            unsupported,
        )
        .unwrap();
        assert_eq!(fs::read(&input).unwrap(), fs::read(&out).unwrap());
        let verified = Container::open(&out).unwrap().verify().unwrap();
        assert!(verified.ok() && verified.has_manifest && verified.digest_file == Some(true));
        let graph = Graph::load(&out).unwrap();
        let NodeKind::StringConstant(id) = graph.node(0).unwrap().kind else {
            panic!("expected string")
        };
        assert_eq!(graph.str(id), "packed fallback");
    }

    #[test]
    fn copy_cannot_be_opened_until_the_complete_body_and_directory_are_present() {
        let src = tempfile::tempdir().unwrap();
        let out = src.path().join("copied.graphite");
        let input = staged(src.path(), &src.path().join("original.graphite"));
        let target = File::create_new(&out).unwrap();
        copy_container(&File::open(input).unwrap(), &target, |body, file| {
            let rejected = || {
                Container::open(&out)
                    .err()
                    .unwrap()
                    .to_string()
                    .contains("multi-disk archive")
            };
            assert!(rejected(), "no body has been copied");
            let n = io::copy(body, file)?;
            assert!(
                rejected(),
                "the complete body must still be unreadable before commit"
            );
            Ok(n)
        })
        .unwrap();
        assert!(Graph::load(&out).is_ok());
    }

    #[test]
    fn copy_failure_rolls_back_its_files_but_keeps_a_replaced_digest() {
        let dir = tempfile::tempdir().unwrap();
        let src = dir.path().join("invalid");
        let checksum = dir.path().join("checksum");
        let out = dir.path().join("app.graphite");
        let digest = container::digest_path(&out);
        fs::write(&src, b"not a packed graph").unwrap();
        fs::write(&checksum, b"original digest").unwrap();
        assert!(publish_with(&src, &checksum, &out, unsupported, unsupported).is_err());
        assert!(!out.exists() && !digest.exists());

        let fail = |a: &Path, b: &Path| {
            if b == out {
                fs::rename(&digest, dir.path().join("moved digest"))?;
                fs::write(&digest, b"competitor")?;
            }
            unsupported(a, b)
        };
        assert!(publish_with(&src, &checksum, &out, fail, unsupported).is_err());
        assert!(!out.exists());
        assert_eq!(fs::read(&digest).unwrap(), b"competitor");
    }

    #[test]
    fn create_new_never_uses_or_removes_a_rebuild_claim() {
        let root = tempfile::tempdir().unwrap();
        let output = root.path().join("app.graphite");
        let lock = output.with_added_extension("lock");
        fs::create_dir(&lock).unwrap();
        fs::write(lock.join("previous.sha256"), b"unrelated recovery data").unwrap();
        let new = Publication::inspect(&output).unwrap();
        let late = Publication::inspect(&output).unwrap();
        let stage = Stage::new(&output).unwrap();
        let raw = br#"{"version":1,"methods":[],"nodes":[],"edges":[]}"#;
        graphite_storage::interchange::import_json(raw.as_slice(), &stage.graph()).unwrap();
        stage.pack(&output, new).unwrap();
        assert!(stage
            .pack(&output, late)
            .unwrap_err()
            .contains("already exists"));
        assert!(stage
            .pack(&output, Publication::inspect(&output).unwrap())
            .unwrap_err()
            .contains("could not claim"));
        assert_eq!(
            fs::read(lock.join("previous.sha256")).unwrap(),
            b"unrelated recovery data"
        );
        assert!(Container::open(&output).unwrap().verify().unwrap().ok());
    }
}

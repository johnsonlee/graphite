//! Serialize frontend publishers of one packed graph. Back up only its digest:
//! the container itself is untouched until the final atomic rename.
use same_file::Handle;
use std::fs::{self, File};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

pub(super) struct Claim {
    dir: PathBuf,
    id: Handle,
    owner: Option<Handle>,
    backup: Option<Handle>,
    retain: bool,
}

pub(super) fn regular(path: &Path) -> io::Result<Option<Handle>> {
    match fs::symlink_metadata(path) {
        Ok(m) if m.is_file() => Handle::from_path(path).map(Some),
        Ok(_) => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("not a regular file: {}", path.display()),
        )),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e),
    }
}

fn occupied_message(output: &Path, dir: &Path) -> String {
    if fs::symlink_metadata(dir).is_ok_and(|m| !m.is_dir()) {
        return format!("could not claim {} for publication: an unrelated file or link occupies the path; preserve it and choose a different output", dir.display());
    }
    format!("could not claim {} for publication: another publisher may be active or may have been interrupted. Check {} if present and confirm all publishers have exited. If {} exists, run graphite verify on it and inspect {} for the previous digest. Restore the matching sidecar if needed, remove only the confirmed abandoned Graphite lock directory {}, then retry.",
        dir.display(), dir.join("owner.pid").display(), output.display(), dir.join("previous.sha256").display(), dir.display())
}

impl Claim {
    pub(super) fn new(output: &Path) -> Result<Self, String> {
        let dir = output.with_added_extension("lock");
        let mut builder = fs::DirBuilder::new();
        #[cfg(unix)]
        {
            use std::os::unix::fs::DirBuilderExt;
            builder.mode(0o700);
        }
        builder.create(&dir).map_err(|e| {
            if e.kind() == io::ErrorKind::AlreadyExists {
                occupied_message(output, &dir)
            } else {
                format!("could not claim {} for publication: {e}", dir.display())
            }
        })?;
        let id = Handle::from_path(&dir).map_err(|e| {
            let cleanup = fs::remove_dir(&dir).err();
            format!(
                "could not identify {}: {e}{}",
                dir.display(),
                cleanup
                    .map(|c| format!("; cleanup: {c}"))
                    .unwrap_or_default()
            )
        })?;
        let mut claim = Self {
            dir,
            id,
            owner: None,
            backup: None,
            retain: false,
        };
        let owner = claim.dir.join("owner.pid");
        (|| -> io::Result<()> {
            let mut file = File::create_new(&owner)?;
            claim.owner = Some(Handle::from_file(file.try_clone()?)?);
            writeln!(file, "{}", std::process::id())
        })()
        .map_err(|e| format!("could not record publisher in {}: {e}", owner.display()))?;
        Ok(claim)
    }

    fn owns_dir(&self) -> io::Result<bool> {
        match fs::symlink_metadata(&self.dir) {
            Ok(m) if m.is_dir() => Ok(Handle::from_path(&self.dir)? == self.id),
            Ok(_) => Ok(false),
            Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(false),
            Err(e) => Err(e),
        }
    }

    fn backup_digest(&mut self, digest: &Path, previous: &Handle) -> io::Result<fs::Permissions> {
        if !self.owns_dir()? {
            return Err(io::Error::other("publication claim changed"));
        }
        let mut input = File::open(digest)?;
        if Handle::from_file(input.try_clone()?)? != *previous {
            return Err(io::Error::other("digest changed during publication"));
        }
        let mut copy = File::create_new(self.dir.join("previous.sha256"))?;
        self.backup = Some(Handle::from_file(copy.try_clone()?)?);
        io::copy(&mut input, &mut copy)?;
        input.metadata().map(|m| m.permissions())
    }

    pub(super) fn replace(
        &mut self,
        staged: &Path,
        staged_digest: &Path,
        output: &Path,
        (old_output, old_digest): (&Handle, &Handle),
        rename: impl Fn(&Path, &Path) -> io::Result<()>,
    ) -> Result<(), String> {
        let digest = graphite_storage::container::digest_path(output);
        let inspect =
            |p: &Path| regular(p).map_err(|e| format!("could not inspect {}: {e}", p.display()));
        let backup = self.dir.join("previous.sha256");
        let new_digest = Handle::from_path(staged_digest).map_err(|e| e.to_string())?;
        let permissions = self
            .backup_digest(&digest, old_digest)
            .map_err(|e| format!("could not back up {}: {e}", digest.display()))?;

        let publish = |from: &Path, to: &Path, old: &Handle| {
            if !self.owns_dir().map_err(|e| e.to_string())? {
                return Err(format!("publication claim changed: {}", self.dir.display()));
            }
            if inspect(to)?.as_ref() != Some(old) {
                return Err(format!(
                    "destination changed during publication: {}",
                    to.display()
                ));
            }
            rename(from, to).map_err(|e| format!("could not publish {}: {e}", to.display()))
        };
        publish(staged_digest, &digest, old_digest)?;
        if let Err(error) = publish(staged, output, old_output) {
            let rollback = (|| {
                if regular(&digest)?.as_ref() != Some(&new_digest) {
                    return Err(io::Error::other("another writer changed the digest"));
                }
                if !self.owns_dir()? || regular(&backup)?.as_ref() != self.backup.as_ref() {
                    return Err(io::Error::other("digest backup changed"));
                }
                fs::set_permissions(&backup, permissions)?;
                fs::rename(&backup, &digest)
            })();
            if let Err(e) = rollback {
                self.retain = true;
                return Err(format!(
                    "{error}; could not restore {}: {e}. Publication state retained in {}",
                    digest.display(),
                    self.dir.display()
                ));
            }
            return Err(error);
        }
        Ok(())
    }
}

impl Drop for Claim {
    fn drop(&mut self) {
        if self.retain {
            return;
        }
        let cleanup = (|| -> io::Result<()> {
            if !self.owns_dir()? {
                return Ok(());
            }
            for (name, id) in [
                ("owner.pid", &self.owner),
                ("previous.sha256", &self.backup),
            ] {
                if let Some(id) = id {
                    let file = self.dir.join(name);
                    if regular(&file)?.as_ref() == Some(id) {
                        fs::remove_file(file)?;
                    }
                }
            }
            fs::remove_dir(&self.dir)
        })();
        if let Err(e) = cleanup {
            eprintln!(
                "Warning: could not remove publication claim {}: {e}",
                self.dir.display()
            );
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use graphite_storage::{container, Container, Graph, NodeKind};
    use serde_json::json;

    fn packed(dir: &Path, value: &str) -> PathBuf {
        fs::create_dir_all(dir).unwrap();
        let graph = dir.join("saved");
        let doc = json!({"version":1,"methods":[],"nodes":[{"id":0,"kind":"StringConstant","value":value}],"edges":[]});
        graphite_storage::interchange::import_json(doc.to_string().as_bytes(), &graph).unwrap();
        let out = dir.join("app.graphite");
        container::pack(&graph, &out).unwrap();
        out
    }

    fn verify(path: &Path, expected: &str) {
        let report = Container::open(path).unwrap().verify().unwrap();
        assert!(report.ok());
        assert_eq!(report.digest_file, Some(true));
        let graph = Graph::load(path).unwrap();
        let NodeKind::StringConstant(id) = graph.node(0).unwrap().kind else {
            panic!("string expected")
        };
        assert_eq!(graph.str(id), expected);
    }

    #[test]
    fn failed_second_rename_restores_verified_previous_pair_then_allows_retry() {
        let tmp = tempfile::tempdir().unwrap();
        let old = packed(&tmp.path().join("old"), "previous");
        let digest = container::digest_path(&old);
        let before = (fs::read(&old).unwrap(), fs::read(&digest).unwrap());
        let candidate = packed(&tmp.path().join("candidate"), "replacement");
        let sidecar = container::digest_path(&candidate);
        let new_digest = fs::read(&sidecar).unwrap();
        let old_container = regular(&old).unwrap().unwrap();
        let old_digest = regular(&digest).unwrap().unwrap();
        let err = Claim::new(&old)
            .unwrap()
            .replace(
                &candidate,
                &sidecar,
                &old,
                (&old_container, &old_digest),
                |a, b| {
                    if b == old {
                        // A late CreateNew publisher cannot claim the existing digest,
                        // even between the two rebuild renames.
                        assert!(super::super::packed::publish(&candidate, &digest, &old)
                            .unwrap_err()
                            .contains("already exists"));
                        assert_eq!(fs::read(&digest)?, new_digest);
                        assert_eq!(fs::read(&old)?, before.0);
                        return Err(io::Error::new(
                            io::ErrorKind::PermissionDenied,
                            "container is busy",
                        ));
                    }
                    fs::rename(a, b)
                },
            )
            .unwrap_err();
        assert!(err.contains("container is busy"));
        assert_eq!(
            (fs::read(&old).unwrap(), fs::read(&digest).unwrap()),
            before
        );
        assert!(!old.with_added_extension("lock").exists());
        verify(&old, "previous");
        let retry = packed(&tmp.path().join("retry"), "replacement");
        Claim::new(&old)
            .unwrap()
            .replace(
                &retry,
                &container::digest_path(&retry),
                &old,
                (&old_container, &regular(&digest).unwrap().unwrap()),
                |a, b| fs::rename(a, b),
            )
            .unwrap();
        verify(&old, "replacement");
        assert!(!old.with_added_extension("lock").exists());
    }

    #[test]
    fn refuses_a_digest_directory_and_preserves_the_existing_container() {
        let tmp = tempfile::tempdir().unwrap();
        let old = packed(&tmp.path().join("old"), "previous");
        let digest = container::digest_path(&old);
        let old_container = regular(&old).unwrap().unwrap();
        let old_digest = regular(&digest).unwrap().unwrap();
        fs::remove_file(&digest).unwrap();
        fs::create_dir(&digest).unwrap();
        fs::write(digest.join("keep"), "other data").unwrap();
        let bytes = fs::read(&old).unwrap();
        let candidate = packed(&tmp.path().join("candidate"), "new");
        let err = Claim::new(&old)
            .unwrap()
            .replace(
                &candidate,
                &container::digest_path(&candidate),
                &old,
                (&old_container, &old_digest),
                |a, b| fs::rename(a, b),
            )
            .unwrap_err();
        assert!(err.contains("could not back up"));
        assert_eq!(fs::read(&old).unwrap(), bytes);
        assert_eq!(
            fs::read_to_string(digest.join("keep")).unwrap(),
            "other data"
        );
        assert!(!old.with_added_extension("lock").exists());
    }

    #[test]
    fn missing_original_container_failure_leaves_no_sidecar_and_can_be_retried() {
        let tmp = tempfile::tempdir().unwrap();
        let output = tmp.path().join("app.graphite");
        let candidate = packed(&tmp.path().join("candidate"), "new");
        fs::remove_file(&candidate).unwrap();
        let digest = container::digest_path(&output);
        assert!(super::super::packed::publish(
            &candidate,
            &container::digest_path(&candidate),
            &output
        )
        .is_err());
        assert!(
            !output.exists() && !digest.exists() && !output.with_added_extension("lock").exists()
        );
        let candidate = packed(&tmp.path().join("retry"), "new");
        super::super::packed::publish(&candidate, &container::digest_path(&candidate), &output)
            .unwrap();
        verify(&output, "new");
    }

    #[test]
    fn killed_publisher_claim_identifies_its_owner_and_explains_manual_recovery() {
        use std::io::Read;
        use std::process::{Command, Stdio};
        use std::time::{Duration, Instant};
        const CHILD_OUTPUT: &str = "GRAPHITE_TEST_KILLED_PUBLISHER_OUTPUT";
        if let Some(path) = std::env::var_os(CHILD_OUTPUT) {
            let path = PathBuf::from(path);
            let mut claim = Claim::new(&path).unwrap();
            let digest = container::digest_path(&path);
            claim
                .backup_digest(&digest, &regular(&digest).unwrap().unwrap())
                .unwrap();
            fs::write(path.with_added_extension("ready"), b"claimed").unwrap();
            // The parent kills this process. If the parent itself exits early,
            // EOF releases this child instead of leaving a process behind.
            io::stdin().read_to_end(&mut Vec::new()).unwrap();
            return;
        }
        let root = tempfile::tempdir().unwrap();
        let output = packed(root.path(), "previous");
        let lock = output.with_added_extension("lock");
        let mut child = Command::new(std::env::current_exe().unwrap())
            .args(["--exact", "build_output::replace::tests::killed_publisher_claim_identifies_its_owner_and_explains_manual_recovery"])
            .env(CHILD_OUTPUT, &output)
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .spawn().unwrap();
        let deadline = Instant::now() + Duration::from_secs(15);
        while !output.with_added_extension("ready").exists() {
            assert!(
                child.try_wait().unwrap().is_none() && Instant::now() < deadline,
                "child did not hold a claim"
            );
            std::thread::sleep(Duration::from_millis(10));
        }
        let pid = child.id().to_string();
        assert_eq!(
            fs::read_to_string(lock.join("owner.pid")).unwrap().trim(),
            pid
        );
        child.kill().unwrap();
        assert!(!child.wait().unwrap().success());

        let err = Claim::new(&output)
            .err()
            .expect("a killed publisher leaves the claim occupied");
        for hint in [
            "may have been interrupted",
            "confirm all publishers have exited",
            "graphite verify",
            "previous.sha256",
            "remove only the confirmed abandoned Graphite lock directory",
            "then retry",
        ] {
            assert!(err.contains(hint), "{err}");
        }
        assert!(err.contains(&lock.display().to_string()));
        verify(&output, "previous");
        assert_eq!(
            fs::read(lock.join("previous.sha256")).unwrap(),
            fs::read(container::digest_path(&output)).unwrap()
        );
        assert_eq!(
            fs::read_to_string(lock.join("owner.pid")).unwrap().trim(),
            pid
        );
        fs::remove_file(lock.join("previous.sha256")).unwrap();
        fs::remove_file(lock.join("owner.pid")).unwrap();
        fs::remove_dir(&lock).unwrap();
        drop(Claim::new(&output).unwrap());
        assert!(!lock.exists());
    }

    #[test]
    fn never_steals_an_existing_claim_or_rolls_back_another_writers_digest() {
        let tmp = tempfile::tempdir().unwrap();
        let output = packed(&tmp.path().join("old"), "previous");
        let candidate = packed(&tmp.path().join("candidate"), "new");
        let digest = container::digest_path(&output);
        let original = fs::read(&digest).unwrap();
        let lock = output.with_added_extension("lock");
        let err = Claim::new(&output)
            .unwrap()
            .replace(
                &candidate,
                &container::digest_path(&candidate),
                &output,
                (
                    &regular(&output).unwrap().unwrap(),
                    &regular(&digest).unwrap().unwrap(),
                ),
                |a, b| {
                    assert!(Claim::new(&output).is_err());
                    if b == output {
                        fs::rename(&digest, tmp.path().join("moved"))?;
                        fs::write(&digest, "competing bytes")?;
                        return Err(io::Error::other("second publish failed"));
                    }
                    fs::rename(a, b)
                },
            )
            .unwrap_err();
        assert!(
            err.contains("another writer changed the digest")
                && err.contains("Publication state retained")
        );
        assert_eq!(fs::read(lock.join("previous.sha256")).unwrap(), original);
        assert_eq!(fs::read_to_string(&digest).unwrap(), "competing bytes");
        assert!(Claim::new(&output).is_err());
    }
}

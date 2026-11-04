//! Serialize frontend publishers of one packed graph. Back up only its digest:
//! the container itself is untouched until the final atomic rename.
#[cfg(any(target_os = "linux", target_os = "macos"))]
mod recovery;
#[cfg(any(target_os = "linux", target_os = "macos"))]
pub(super) use recovery::recover;
#[cfg(not(any(target_os = "linux", target_os = "macos")))]
pub(super) fn recover(_: &Path) -> bool {
    false
}

use same_file::Handle;
use std::fs::{self, File};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

pub(super) struct Claim {
    dir: PathBuf,
    id: Handle,
    owner: Option<Handle>,
    backup: Option<Handle>,
    pending: Option<Handle>,
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
        let create = match builder.create(&dir) {
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists && recover(output) => {
                builder.create(&dir)
            }
            outcome => outcome,
        };
        create.map_err(|e| {
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
            pending: None,
            retain: false,
        };
        let owner = claim.dir.join("owner.pid");
        (|| -> io::Result<()> {
            let mut file = File::create_new(&owner)?;
            claim.owner = Some(Handle::from_file(file.try_clone()?)?);
            writeln!(file, "{}", std::process::id())?;
            #[cfg(any(target_os = "linux", target_os = "macos"))]
            if let Some(scope) = super::stale::process_scope() {
                writeln!(
                    file,
                    "graphite-claim-v1 {scope} {}",
                    recovery::identity(claim.id.as_file())?
                )?;
            }
            Ok(())
        })()
        .map_err(|e| format!("could not record publisher in {}: {e}", owner.display()))?;
        Ok(claim)
    }

    fn record_pending(
        &mut self,
        staged_digest: &Path,
        old_output: &Handle,
        old_digest: Option<&Handle>,
        old_graph_sha: &str,
    ) -> Result<(), String> {
        #[cfg(any(target_os = "linux", target_os = "macos"))]
        recovery::record(self, staged_digest, old_output, old_digest, old_graph_sha)
            .map_err(|e| format!("could not record pending publication: {e}"))?;
        #[cfg(not(any(target_os = "linux", target_os = "macos")))]
        let _ = (staged_digest, old_output, old_digest, old_graph_sha);
        Ok(())
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
        let permissions = input.metadata()?.permissions();
        copy.set_permissions(permissions.clone())?;
        Ok(permissions)
    }

    pub(super) fn replace(
        &mut self,
        staged: &Path,
        staged_digest: &Path,
        output: &Path,
        (old_output, old_digest, old_hash): (&Handle, Option<&Handle>, &str),
        rename: impl Fn(&Path, &Path) -> io::Result<()>,
    ) -> Result<(), String> {
        let digest = graphite_storage::container::digest_path(output);
        let inspect =
            |p: &Path| regular(p).map_err(|e| format!("could not inspect {}: {e}", p.display()));
        let backup = self.dir.join("previous.sha256");
        let permissions = old_digest
            .map(|id| self.backup_digest(&digest, id))
            .transpose()
            .map_err(|e| format!("could not back up {}: {e}", digest.display()))?;
        self.record_pending(staged_digest, old_output, old_digest, old_hash)?;

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
        let new_digest = if let Some(previous) = old_digest {
            let id = Handle::from_path(staged_digest).map_err(|e| e.to_string())?;
            publish(staged_digest, &digest, previous)?;
            id
        } else {
            super::packed::publish_checksum(staged_digest, &digest)
                .map_err(|e| format!("could not create {}: {e}", digest.display()))?
        };
        if let Err(error) = publish(staged, output, old_output) {
            let rollback = (|| {
                if regular(&digest)?.as_ref() != Some(&new_digest) {
                    return Err(io::Error::other("another writer changed the digest"));
                }
                if permissions.is_none() {
                    return fs::remove_file(&digest);
                }
                if !self.owns_dir()? || regular(&backup)?.as_ref() != self.backup.as_ref() {
                    return Err(io::Error::other("digest backup changed"));
                }
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
                ("pending", &self.pending),
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
                (
                    &old_container,
                    Some(&old_digest),
                    &Container::open(&old).unwrap().file_sha256(),
                ),
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
                (
                    &old_container,
                    Some(&regular(&digest).unwrap().unwrap()),
                    &Container::open(&old).unwrap().file_sha256(),
                ),
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
                (
                    &old_container,
                    Some(&old_digest),
                    &Container::open(&old).unwrap().file_sha256(),
                ),
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
            fs::read_to_string(lock.join("owner.pid"))
                .unwrap()
                .lines()
                .next()
                .unwrap(),
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
            fs::read_to_string(lock.join("owner.pid"))
                .unwrap()
                .lines()
                .next()
                .unwrap(),
            pid
        );
        fs::remove_file(lock.join("previous.sha256")).unwrap();
        fs::remove_file(lock.join("owner.pid")).unwrap();
        fs::remove_dir(&lock).unwrap();
        drop(Claim::new(&output).unwrap());
        assert!(!lock.exists());
    }

    #[cfg(any(target_os = "linux", target_os = "macos"))]
    #[test]
    fn only_one_recoverer_repairs_a_killed_publishers_exact_files() {
        use std::io::Read;
        use std::process::{Command, Stdio};
        use std::time::{Duration, Instant};
        const CHILD: &str = "GRAPHITE_TEST_CRASH_RECOVERY";
        if let Some(path) = std::env::var_os(CHILD) {
            let path = PathBuf::from(path);
            let dest = path.join("out/app.graphite");
            let digest = container::digest_path(&dest);
            let next = packed(&path.join("candidate"), "after");
            let next_digest = container::digest_path(&next);
            let mut claim = Claim::new(&dest).unwrap();
            let old = regular(&dest).unwrap().unwrap();
            let previous = regular(&digest).unwrap();
            if let Some(id) = &previous {
                claim.backup_digest(&digest, id).unwrap();
            }
            let old_sha = Container::open(&dest)
                .unwrap()
                .verify()
                .unwrap()
                .file_sha256;
            claim
                .record_pending(&next_digest, &old, previous.as_ref(), &old_sha)
                .unwrap();
            match fs::read_to_string(path.join("stop-at")).unwrap().as_str() {
                "before" => (),
                "between" => fs::rename(&next_digest, &digest).unwrap(),
                "after" => {
                    fs::rename(&next_digest, &digest).unwrap();
                    fs::rename(&next, &dest).unwrap();
                }
                _ => unreachable!(),
            }
            fs::write(path.join("ready"), b"ready").unwrap();
            io::stdin().read_to_end(&mut Vec::new()).unwrap();
            return;
        }
        for with_sidecar in [false, true] {
            for window in ["before", "between", "after"] {
                let tmp = tempfile::tempdir().unwrap();
                let dir = tmp.path();
                let output = packed(&dir.join("out"), "before");
                let digest = container::digest_path(&output);
                if !with_sidecar {
                    fs::remove_file(&digest).unwrap();
                }
                fs::write(dir.join("stop-at"), window).unwrap();
                let mut child = Command::new(std::env::current_exe().unwrap())
                    .args(["--exact", "build_output::replace::tests::only_one_recoverer_repairs_a_killed_publishers_exact_files"])
                    .env(CHILD, dir).stdin(Stdio::piped()).stdout(Stdio::null()).spawn().unwrap();
                let deadline = Instant::now() + Duration::from_secs(15);
                while !dir.join("ready").exists() {
                    assert!(child.try_wait().unwrap().is_none() && Instant::now() < deadline);
                    std::thread::sleep(Duration::from_millis(5));
                }
                assert!(!recover(&output), "publisher is alive");
                child.kill().unwrap();
                child.wait().unwrap();
                let lock = output.with_added_extension("lock");
                let locked = File::open(&lock).unwrap();
                locked.try_lock().unwrap();
                assert!(!recover(&output), "another recoverer holds the same inode");
                drop(locked);

                let owner = lock.join("owner.pid");
                let token = fs::read(&owner).unwrap();
                fs::write(
                    &owner,
                    String::from_utf8(token.clone())
                        .unwrap()
                        .replace("graphite-claim-v1 ", "graphite-claim-v1 another-boot:"),
                )
                .unwrap();
                assert!(!recover(&output));
                fs::write(&owner, token).unwrap();
                if window == "between" {
                    let live = fs::read(&digest).unwrap();
                    fs::write(&digest, format!("{}  app.graphite\n", "0".repeat(64))).unwrap();
                    assert!(!recover(&output), "edited public checksum");
                    fs::write(&digest, live).unwrap();
                }
                let barrier = std::sync::Barrier::new(2);
                let race = || {
                    barrier.wait();
                    recover(&output)
                };
                let (a, b) = std::thread::scope(|t| {
                    let first = t.spawn(race);
                    let second = race();
                    (first.join().unwrap(), second)
                });
                assert_ne!(a, b, "exactly one recoverer wins {window} {with_sidecar}");
                assert!(!lock.exists());
                let report = Container::open(&output).unwrap().verify().unwrap();
                assert!(report.ok() && report.has_manifest);
                assert_eq!(
                    report.digest_file,
                    if with_sidecar || window == "after" {
                        Some(true)
                    } else {
                        None
                    }
                );
                let graph = Graph::load(&output).unwrap();
                let NodeKind::StringConstant(i) = graph.node(0).unwrap().kind else {
                    panic!("expected literal")
                };
                assert_eq!(
                    graph.str(i),
                    if window == "after" { "after" } else { "before" }
                );
            }
        }
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
                    Some(&regular(&digest).unwrap().unwrap()),
                    &Container::open(&output).unwrap().file_sha256(),
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

#![cfg(any(target_os = "linux", target_os = "macos"))]

use super::*;

#[test]
fn reclaim_only_marked_stages_with_no_live_owner_or_worker() {
    use std::os::unix::fs::PermissionsExt;
    use std::process::Stdio;

    let parent = tempfile::tempdir().unwrap();
    let mut child = Command::new(std::env::current_exe().unwrap())
        .arg("--list")
        .stdout(Stdio::null())
        .spawn()
        .unwrap();
    let owner = child.id();
    assert!(child.wait().unwrap().success());
    let make = |suffix: &str, worker: Option<&str>| {
        let path = parent.path().join(format!("{PREFIX}{owner}-{suffix}"));
        fs::create_dir(&path).unwrap();
        fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
        fs::write(path.join("payload"), "keep").unwrap();
        if let Some(worker) = worker {
            record(&path, worker).unwrap();
        }
        path
    };
    let finished = make("finished", Some("0"));
    let running = make("running", Some(&std::process::id().to_string()));
    let ambiguous = make("ambiguous", Some("starting"));
    let unrelated = make("unmarked", None);
    let remote = make("remote", Some("0"));
    let mut lines: Vec<_> = fs::read_to_string(remote.join(MARKER))
        .unwrap()
        .lines()
        .map(str::to_owned)
        .collect();
    let scope = lines.last_mut().unwrap();
    // Different valid boot UUID, same OS (and on Linux, same namespace inode).
    let i = scope.find(':').unwrap() + 1;
    let replacement = if &scope[i..i + 1] == "a" { "b" } else { "a" };
    scope.replace_range(i..i + 1, replacement);
    fs::write(remote.join(MARKER), lines.join("\n")).unwrap();
    let linked = parent.path().join(format!("{PREFIX}{owner}-link"));
    std::os::unix::fs::symlink(&unrelated, &linked).unwrap();
    let live = create(parent.path()).unwrap();
    assert!(!finished.exists());
    for dir in [&running, &ambiguous, &unrelated, &linked, &remote] {
        assert_eq!(fs::read(dir.join("payload")).unwrap(), b"keep");
    }
    record(&running, &owner.to_string()).unwrap();
    sweep(parent.path());
    assert!(!running.exists());
    assert!(live.path().is_dir());
    assert!(unrelated.join("payload").is_file());
}

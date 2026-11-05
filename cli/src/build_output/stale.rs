//! Recover private stages after the owning CLI and its frontend have both exited.
use std::fs;
use std::io::{self, Write};
use std::path::Path;
use std::process::{Command, ExitStatus};

const MARKER: &str = ".graphite-stage-owner-v2";
const PREFIX: &str = ".graphite-build-";

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn boot_uuid(value: &str) -> Option<String> {
    let uuid = value.trim();
    let valid = uuid.len() == 36
        && uuid.bytes().enumerate().all(|(i, b)| {
            if matches!(i, 8 | 13 | 18 | 23) {
                b == b'-'
            } else {
                b.is_ascii_hexdigit()
            }
        })
        && uuid != "00000000-0000-0000-0000-000000000000";
    valid.then(|| uuid.to_ascii_lowercase())
}

#[cfg(target_os = "linux")]
pub(super) fn process_scope() -> Option<String> {
    let boot = boot_uuid(&fs::read_to_string("/proc/sys/kernel/random/boot_id").ok()?)?;
    let ns = fs::read_link("/proc/self/ns/pid").ok()?;
    let raw = ns.to_str()?.strip_prefix("pid:[")?.strip_suffix(']')?;
    let inode = raw.parse::<u64>().ok()?;
    if inode == 0 || raw != inode.to_string() {
        return None;
    }
    Some(format!("linux:{boot}:pid:[{inode}]"))
}

#[cfg(target_os = "macos")]
pub(super) fn process_scope() -> Option<String> {
    // A host name can repeat across shared mounts. Use the kernel's per-boot
    // session UUID; one lookup suffices for the lifetime of this CLI process.
    static SCOPE: std::sync::OnceLock<Option<String>> = std::sync::OnceLock::new();
    SCOPE
        .get_or_init(|| {
            let output = Command::new("/usr/sbin/sysctl")
                .args(["-n", "kern.bootsessionuuid"])
                .output()
                .ok()?;
            if !output.status.success() {
                return None;
            }
            let session = boot_uuid(std::str::from_utf8(&output.stdout).ok()?)?;
            Some(format!("macos:{session}"))
        })
        .clone()
}

#[cfg(not(any(target_os = "linux", target_os = "macos")))]
pub(super) fn process_scope() -> Option<String> {
    // Without a trustworthy boot and process-namespace discriminator, PIDs on
    // a shared filesystem cannot establish that its original writers exited.
    None
}

pub(super) fn prevent_sweep(dir: &Path) -> io::Result<()> {
    fs::remove_file(dir.join(MARKER))
}

pub(super) fn create(parent: &Path) -> io::Result<tempfile::TempDir> {
    sweep(parent);
    let mut builder = tempfile::Builder::new();
    let prefix = format!("{PREFIX}{}-", std::process::id());
    builder.prefix(&prefix);
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        builder.permissions(fs::Permissions::from_mode(0o700));
    }
    let stage = builder.tempdir_in(parent)?;
    record(stage.path(), "0")?;
    Ok(stage)
}

fn record(dir: &Path, worker: &str) -> io::Result<()> {
    let mut next = tempfile::NamedTempFile::new_in(dir)?;
    writeln!(
        next,
        "{}",
        dir.file_name().unwrap_or_default().to_string_lossy()
    )?;
    writeln!(next, "{worker}")?;
    writeln!(
        next,
        "{}",
        process_scope().as_deref().unwrap_or("unconfirmed")
    )?;
    next.persist(dir.join(MARKER))?;
    Ok(())
}

pub(super) fn run(dir: &Path, cmd: &mut Command) -> io::Result<ExitStatus> {
    // A kill between spawn and recording the PID must never make a live frontend
    // look idle. Leave that uncommon, ambiguous stage for manual cleanup.
    record(dir, "starting")?;
    let mut child = cmd.spawn()?;
    if let Err(error) = record(dir, &child.id().to_string()) {
        let _ = child.kill();
        let _ = child.wait();
        return Err(error);
    }
    child.wait()
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn sweep(parent: &Path) {
    use rustix::fs::{open, Mode, OFlags};
    use std::os::unix::fs::MetadataExt;

    let Some(scope) = process_scope() else {
        return;
    };
    let Ok(entries) = fs::read_dir(parent) else {
        return;
    };
    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(name) = name.to_str() else { continue };
        let Some((owner, suffix)) = name.strip_prefix(PREFIX).and_then(|s| s.split_once('-'))
        else {
            continue;
        };
        let Ok(owner) = owner.parse::<u32>() else {
            continue;
        };
        if owner == 0 || suffix.is_empty() || !entry.file_type().is_ok_and(|t| t.is_dir()) {
            continue;
        }

        let path = entry.path();
        let Ok(directory) = open(
            &path,
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
            Mode::empty(),
        ) else {
            continue;
        };
        let directory = fs::File::from(directory);
        let Ok(meta) = directory.metadata() else {
            continue;
        };
        if meta.uid() != rustix::process::geteuid().as_raw() || meta.mode() & 0o077 != 0 {
            continue;
        }
        if reclaimable(&directory, name, owner, &scope)
            && fs::symlink_metadata(&path)
                .is_ok_and(|now| now.is_dir() && now.dev() == meta.dev() && now.ino() == meta.ino())
        {
            if let Err(error) = fs::remove_dir_all(&path) {
                if error.kind() != io::ErrorKind::NotFound {
                    eprintln!(
                        "Warning: could not clean interrupted stage {}: {error}",
                        path.display()
                    );
                }
            }
        }
    }
}

#[cfg(not(any(target_os = "linux", target_os = "macos")))]
fn sweep(_: &Path) {}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn reclaimable(dir: &fs::File, name: &str, owner: u32, scope: &str) -> bool {
    use rustix::fs::{openat, Mode, OFlags};
    use std::io::Read;
    use std::os::unix::fs::MetadataExt;

    // Open the marker relative to the pinned directory. A symlink or FIFO
    // substituted on a shared mount must neither redirect nor block a build.
    let Ok(marker) = openat(
        dir,
        MARKER,
        OFlags::RDONLY | OFlags::NOFOLLOW | OFlags::NONBLOCK | OFlags::CLOEXEC,
        Mode::empty(),
    ) else {
        return false;
    };
    let marker = fs::File::from(marker);
    if !marker.metadata().is_ok_and(|m| {
        m.is_file()
            && m.uid() == rustix::process::geteuid().as_raw()
            && m.mode() & 0o077 == 0
            && m.len() < 256
    }) {
        return false;
    }
    let mut record = String::new();
    if marker.take(256).read_to_string(&mut record).is_err() || record.len() >= 256 {
        return false;
    }
    let mut lines = record.lines();
    if lines.next() != Some(name) {
        return false;
    }
    let Some(worker) = lines.next().and_then(|s| s.parse::<u32>().ok()) else {
        return false;
    };
    lines.next() == Some(scope)
        && lines.next().is_none()
        && exited(owner)
        && (worker == 0 || exited(worker))
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
pub(super) fn exited(pid: u32) -> bool {
    use rustix::process::{test_kill_process, Pid};
    i32::try_from(pid)
        .ok()
        .and_then(Pid::from_raw)
        .is_some_and(|pid| test_kill_process(pid) == Err(rustix::io::Errno::SRCH))
}

#[cfg(test)]
mod tests;

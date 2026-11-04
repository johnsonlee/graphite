//! Recover a dead local publisher under an exclusive lock on its pinned claim.
use super::{Claim, Handle};
use crate::build_output::stale::{exited, process_scope};
use rustix::fs::{open, Mode, OFlags};
use sha2::{Digest, Sha256};
use std::fs::{self, File};
use std::io::{self, Write};
use std::os::unix::fs::MetadataExt;
use std::path::Path;

pub(super) fn identity(file: &File) -> io::Result<String> {
    let meta = file.metadata()?;
    Ok(format!("{}:{}", meta.dev(), meta.ino()))
}

fn small(file: &File) -> io::Result<Vec<u8>> {
    use std::os::unix::fs::FileExt;
    let len = file.metadata()?.len();
    // Only the tiny journal, owner marker and checksum files are read here.
    if len > 8192 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "oversized recovery record",
        ));
    }
    let mut data = vec![0; len as usize];
    file.read_exact_at(&mut data, 0)?;
    Ok(data)
}

pub(super) fn record(
    claim: &mut Claim,
    staged_digest: &Path,
    old_output: &Handle,
    old_digest: Option<&Handle>,
    old_graph_sha: &str,
) -> io::Result<()> {
    let next = File::open(staged_digest)?;
    // Hash the staged sidecar and record its inode. A replaced or edited
    // sidecar can never authorize overwriting an unrelated destination.
    if old_digest.is_some_and(|id| id.as_file().metadata().is_ok_and(|m| m.len() > 8192)) {
        return Ok(()); // An unusually large sidecar still rebuilds, with manual recovery.
    }
    let old = old_digest
        .map(|id| small(id.as_file()).map(|b| hash(&b)))
        .transpose()?;
    let mut file = File::create_new(claim.dir.join("pending"))?;
    claim.pending = Some(Handle::from_file(file.try_clone()?)?);
    writeln!(
        file,
        "{} {} {} {} {}",
        identity(old_output.as_file())?,
        identity(&next)?,
        hash(&small(&next)?),
        old.as_deref().unwrap_or("-"),
        old_graph_sha
    )
}

fn hash(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

fn known_file(path: &Path) -> Option<Handle> {
    let fd = open(
        path,
        OFlags::RDONLY | OFlags::NOFOLLOW | OFlags::NONBLOCK | OFlags::CLOEXEC,
        Mode::empty(),
    )
    .ok()?;
    let file = File::from(fd);
    file.metadata().ok()?.is_file().then_some(())?;
    Handle::from_file(file).ok()
}

pub(in crate::build_output) fn recover(output: &Path) -> bool {
    attempt(output).unwrap_or(false)
}

fn attempt(output: &Path) -> Option<bool> {
    let dir = output.with_added_extension("lock");
    let fd = open(
        &dir,
        OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::empty(),
    )
    .ok()?;
    let id = Handle::from_file(File::from(fd)).ok()?;
    let meta = id.as_file().metadata().ok()?;
    if meta.uid() != rustix::process::geteuid().as_raw() || meta.mode() & 0o077 != 0 {
        return None;
    }
    id.as_file().try_lock().ok()?;
    let still_dir = || {
        fs::symlink_metadata(&dir)
            .is_ok_and(|m| m.is_dir() && m.dev() == meta.dev() && m.ino() == meta.ino())
    };
    if !still_dir() {
        return None;
    }
    let owner_path = dir.join("owner.pid");
    let owner = known_file(&owner_path)?;
    let marker = String::from_utf8(small(owner.as_file()).ok()?).ok()?;
    let (pid, record) = marker.trim_end().split_once('\n')?;
    if record
        != format!(
            "graphite-claim-v1 {} {}",
            process_scope()?,
            identity(id.as_file()).ok()?
        )
        || !exited(pid.parse().ok()?)
    {
        return None;
    }

    // Reject unknown entries and half-written records. Only touch files whose
    // identities still match our pinned handles when restoration begins.
    let mut pending = None;
    let mut backup = None;
    for entry in fs::read_dir(&dir).ok()? {
        let entry = entry.ok()?;
        match entry.file_name().to_str()? {
            "owner.pid" => (),
            "pending" => pending = Some(known_file(&entry.path())?),
            "previous.sha256" => backup = Some(known_file(&entry.path())?),
            _ => return None,
        }
    }
    let graph = known_file(output)?;
    let digest_path = graphite_storage::container::digest_path(output);
    let digest = match fs::symlink_metadata(&digest_path) {
        Err(e) if e.kind() == io::ErrorKind::NotFound => None,
        Ok(m) if m.is_file() => Some(known_file(&digest_path)?),
        _ => return None,
    };
    let archive = graphite_storage::Container::open(output).ok()?;
    use graphite_storage::io::{check_header, read_i32_at, MAGIC_NODEDATA};
    let nodes = archive.bytes("graph.nodedata")?;
    if nodes.len() < 8
        || !matches!(
            check_header(read_i32_at(&nodes, 0), MAGIC_NODEDATA),
            Some(1..=3)
        )
    {
        return None;
    }
    let report = archive.verify().ok()?;
    // An intentional sidecar mismatch contributes precisely one failure.
    // CRC, manifest, or required-entry failures always disqualify recovery.
    if !report.has_manifest
        || report.failures.len() != usize::from(report.digest_file == Some(false))
    {
        return None;
    }
    let previous_path = dir.join("previous.sha256");
    let pending_path = dir.join("pending");
    let action = action_for(
        &graph,
        digest.as_ref(),
        &report,
        pending.as_ref(),
        backup.as_ref(),
    )?;
    if !still_dir()
        || !still_file(output, Some(&graph))
        || !still_file(&digest_path, digest.as_ref())
        || !still_file(&owner_path, Some(&owner))
        || !still_file(&pending_path, pending.as_ref())
        || !still_file(&previous_path, backup.as_ref())
    {
        return None;
    }
    match action {
        Action::RemoveNew => fs::remove_file(&digest_path).ok()?,
        Action::RestorePrevious => fs::rename(&previous_path, &digest_path).ok()?,
        Action::Keep if backup.is_some() => fs::remove_file(&previous_path).ok()?,
        Action::Keep => (),
    }
    if pending.is_some() {
        fs::remove_file(pending_path).ok()?;
    }
    fs::remove_file(owner_path).ok()?;
    fs::remove_dir(dir).ok()?;
    Some(true)
}

fn still_file(path: &Path, pinned: Option<&Handle>) -> bool {
    match (fs::symlink_metadata(path), pinned) {
        (Err(e), None) => e.kind() == io::ErrorKind::NotFound,
        (Ok(now), Some(id)) => {
            now.is_file()
                && id
                    .as_file()
                    .metadata()
                    .is_ok_and(|m| now.ino() == m.ino() && now.dev() == m.dev())
        }
        _ => false,
    }
}

enum Action {
    Keep,
    RestorePrevious,
    RemoveNew,
}

fn action_for(
    graph: &Handle,
    digest: Option<&Handle>,
    report: &graphite_storage::container::Verification,
    pending: Option<&Handle>,
    backup: Option<&Handle>,
) -> Option<Action> {
    let Some(plan) = pending else {
        return (backup.is_none() && report.ok()).then_some(Action::Keep);
    };
    let data = String::from_utf8(small(plan.as_file()).ok()?).ok()?;
    let fields: Vec<_> = data.split_whitespace().collect();
    let [old_id, next_id, next_hash, previous_hash, old_graph_sha] = fields.as_slice() else {
        return None;
    };
    let old = identity(graph.as_file()).ok()? == *old_id && report.file_sha256 == *old_graph_sha;
    let current = match digest {
        Some(id) => Some(small(id.as_file()).ok()?),
        None => None,
    };
    let next = digest.zip(current.as_ref()).is_some_and(|(id, b)| {
        identity(id.as_file()).ok().as_deref() == Some(next_id) && hash(b) == *next_hash
    });
    let matching = report.digest_file == Some(true);
    if let Some(backup) = backup {
        let original = small(backup.as_file()).ok()?;
        let original_graph_sha = std::str::from_utf8(&original)
            .ok()?
            .split_whitespace()
            .next()?;
        if hash(&original) != *previous_hash
            || !original_graph_sha.eq_ignore_ascii_case(old_graph_sha)
        {
            return None;
        }
        if old && next && !matching {
            return Some(Action::RestorePrevious);
        }
        return (matching && (next || old && current.as_ref() == Some(&original)))
            .then_some(Action::Keep);
    }
    if *previous_hash != "-" {
        return None;
    }
    // A byte-identical rebuild still installs a different container inode.
    // The old inode proves we must restore the original sidecar absence.
    if old && next {
        return Some(Action::RemoveNew);
    }
    (old && digest.is_none() || next && matching).then_some(Action::Keep)
}

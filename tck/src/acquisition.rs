//! Integrity-checked, runtime-only acquisition of the pinned official corpus.
use anyhow::{ensure, Context, Result};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    fs,
    io::Read,
    path::{Component, Path},
    time::Duration,
};
use walkdir::WalkDir;

fn file_hash(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

fn pinned_files(pin: &Value) -> Result<BTreeMap<String, String>> {
    serde_json::from_value(pin["files"].clone()).context("invalid pinned file inventory")
}

pub fn verify_cache(directory: &Path, pin: &Value) -> Result<()> {
    ensure!(
        directory.is_dir() && !directory.is_symlink(),
        "TCK cache must be a regular directory"
    );
    let mut actual = BTreeMap::new();
    for entry in WalkDir::new(directory).follow_links(false) {
        let entry = entry?;
        ensure!(
            !entry.file_type().is_symlink(),
            "TCK cache contains a symbolic link"
        );
        if entry.file_type().is_file() {
            let name = entry
                .path()
                .strip_prefix(directory)?
                .to_str()
                .context("non UTF-8 cache path")?
                .replace('\\', "/");
            actual.insert(name, file_hash(&fs::read(entry.path())?));
        }
    }
    ensure!(actual == pinned_files(pin)?, "TCK cache differs from pinned file inventory; remove the cache explicitly to download again");
    Ok(())
}

fn verified_archive(bytes: &[u8], pin: &Value) -> Result<BTreeMap<String, Vec<u8>>> {
    ensure!(
        Some(file_hash(bytes).as_str()) == pin["archiveSha256"].as_str(),
        "upstream archive SHA256 differs from reviewed pin"
    );
    let expected = pinned_files(pin)?;
    let prefix = format!(
        "openCypher-{}/",
        pin["commit"].as_str().context("missing pinned commit")?
    );
    let decoder = flate2::read::GzDecoder::new(bytes);
    let mut archive = tar::Archive::new(decoder);
    let mut extracted = BTreeMap::new();
    for entry in archive.entries()? {
        let mut entry = entry?;
        let raw_path = entry.path_bytes();
        let raw = std::str::from_utf8(&raw_path).context("non UTF-8 archive path")?;
        let Some(relative) = raw.strip_prefix(&prefix) else {
            continue;
        };
        let Some(expected_hash) = expected.get(relative) else {
            continue;
        };
        ensure!(
            !relative.contains('\\')
                && !Path::new(relative).is_absolute()
                && Path::new(relative)
                    .components()
                    .all(|c| matches!(c, Component::Normal(_))),
            "unsafe pinned upstream path: {relative}"
        );
        ensure!(
            entry.header().entry_type().is_file() && !extracted.contains_key(relative),
            "duplicate or non-regular upstream file: {relative}"
        );
        let relative = relative.to_owned();
        let mut data = Vec::new();
        entry.read_to_end(&mut data)?;
        ensure!(
            &file_hash(&data) == expected_hash,
            "upstream file differs: {relative}"
        );
        extracted.insert(relative, data);
    }
    ensure!(
        extracted.keys().eq(expected.keys()),
        "archive is missing pinned files"
    );
    Ok(extracted)
}

fn publish(root: &Path, files: &BTreeMap<String, Vec<u8>>, pin: &Value) -> Result<()> {
    let temporary = tempfile::Builder::new()
        .prefix(".upstream-")
        .tempdir_in(root)?;
    let staging = temporary.path().join("cache");
    fs::create_dir(&staging)?;
    for (relative, data) in files {
        let target = staging.join(relative);
        fs::create_dir_all(target.parent().context("missing parent")?)?;
        fs::write(target, data)?;
    }
    verify_cache(&staging, pin)?;
    let destination = root.join("upstream");
    if destination.exists() || destination.is_symlink() {
        verify_cache(&destination, pin)?;
    } else if let Err(error) = fs::rename(&staging, &destination) {
        if !destination.exists() {
            return Err(error.into());
        }
        verify_cache(&destination, pin)?;
    }
    Ok(())
}

pub fn ensure_upstream(root: &Path) -> Result<Value> {
    let pin: Value = serde_json::from_slice(&fs::read(root.join("upstream.json"))?)?;
    let destination = root.join("upstream");
    if destination.exists() || destination.is_symlink() {
        verify_cache(&destination, &pin)?;
        return Ok(pin);
    }
    let url = pin["archive"]
        .as_str()
        .context("missing upstream archive URL")?;
    ensure!(
        url.starts_with("https://"),
        "upstream archive URL must use HTTPS"
    );
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(60))
        .build()?;
    let bytes = client.get(url).send()?.error_for_status()?.bytes()?;
    let files = verified_archive(&bytes, &pin)?;
    publish(root, &files, &pin)?;
    Ok(pin)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn fixture(omit_license: bool) -> (Vec<u8>, Value) {
        let files = BTreeMap::from([
            ("LICENSE".to_owned(), b"license".to_vec()),
            (
                "tck/features/one.feature".to_owned(),
                b"Feature: example\n".to_vec(),
            ),
        ]);
        let encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        let mut tar = tar::Builder::new(encoder);
        for (path, bytes) in &files {
            if omit_license && path == "LICENSE" {
                continue;
            }
            let mut header = tar::Header::new_gnu();
            header.set_size(bytes.len() as u64);
            header.set_mode(0o644);
            header.set_cksum();
            tar.append_data(&mut header, format!("openCypher-abc/{path}"), &bytes[..])
                .unwrap();
        }
        let bytes = tar.into_inner().unwrap().finish().unwrap();
        let hashes: BTreeMap<_, _> = files
            .iter()
            .map(|(k, v)| (k.clone(), file_hash(v)))
            .collect();
        let pin = json!({"commit":"abc", "archiveSha256":file_hash(&bytes), "files":hashes});
        (bytes, pin)
    }

    #[test]
    fn archive_and_file_hashes_and_missing_files_are_checked() {
        let (bytes, pin) = fixture(false);
        assert_eq!(verified_archive(&bytes, &pin).unwrap().len(), 2);
        let mut bad = bytes.clone();
        bad.push(0);
        assert!(verified_archive(&bad, &pin).is_err());
        let mut pin_bad = pin.clone();
        pin_bad["files"]["LICENSE"] = json!("bad");
        assert!(verified_archive(&bytes, &pin_bad).is_err());
        let (missing, pin_missing) = fixture(true);
        assert!(verified_archive(&missing, &pin_missing).is_err());
    }

    #[test]
    fn publication_is_complete_and_corruption_is_not_repaired() {
        let root = tempfile::tempdir().unwrap();
        let (bytes, pin) = fixture(false);
        let files = verified_archive(&bytes, &pin).unwrap();
        publish(root.path(), &files, &pin).unwrap();
        verify_cache(&root.path().join("upstream"), &pin).unwrap();
        fs::write(root.path().join("upstream/LICENSE"), b"tamper").unwrap();
        assert!(publish(root.path(), &files, &pin).is_err());
        assert_eq!(
            fs::read(root.path().join("upstream/LICENSE")).unwrap(),
            b"tamper"
        );
        assert_eq!(fs::read_dir(root.path()).unwrap().count(), 1);
    }

    #[test]
    fn failed_staging_leaves_no_partial_cache() {
        let root = tempfile::tempdir().unwrap();
        let (bytes, pin) = fixture(false);
        let mut files = verified_archive(&bytes, &pin).unwrap();
        files.remove("LICENSE");
        assert!(publish(root.path(), &files, &pin).is_err());
        assert!(!root.path().join("upstream").exists());
        assert_eq!(fs::read_dir(root.path()).unwrap().count(), 0);
    }

    #[test]
    fn valid_cache_requires_no_network_and_tampered_cache_fails() {
        let root = tempfile::tempdir().unwrap();
        let (bytes, mut pin) = fixture(false);
        pin["archive"] = json!("https://invalid.invalid/unreachable");
        fs::write(
            root.path().join("upstream.json"),
            serde_json::to_vec(&pin).unwrap(),
        )
        .unwrap();
        publish(root.path(), &verified_archive(&bytes, &pin).unwrap(), &pin).unwrap();
        assert_eq!(ensure_upstream(root.path()).unwrap(), pin);
        fs::remove_file(root.path().join("upstream/LICENSE")).unwrap();
        assert!(ensure_upstream(root.path()).is_err());
    }
}

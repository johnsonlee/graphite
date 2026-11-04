//! `graphite frontend install`: fetch a frontend from a GitHub release into
//! `~/.graphite/frontends/<lang>/<version>/` and verify its checksum.
//!
//! Downloads go through `curl`, which every macOS and most Linux installs carry, rather
//! than an HTTP client compiled into the binary. The checksum file published next to the
//! asset is required unless the caller opts out, so a truncated or substituted download
//! never becomes the frontend that builds someone's graphs.

mod js;

use crate::frontend::{self, Env, Lang};
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::process::Command;

pub fn install(
    env: &Env,
    lang: Lang,
    version: &str,
    skip_checksum: bool,
) -> Result<PathBuf, String> {
    // The version is both a URL segment and the name of a local install directory.
    semver::Version::parse(version)
        .map_err(|e| format!("invalid release version '{version}': {e}"))?;
    match lang {
        Lang::Jvm => install_jvm(env, version, skip_checksum),
        Lang::Js => js::install_js(env, version, skip_checksum),
    }
}

/// Where the JVM frontend jar for `version` lives once installed.
pub fn jvm_install_path(env: &Env, version: &str) -> Result<PathBuf, String> {
    let dir = env
        .frontends_dir()
        .ok_or("cannot determine the home directory (HOME is unset)")?;
    Ok(dir
        .join("jvm")
        .join(version)
        .join(frontend::JVM_FRONTEND_JAR))
}

/// The hex digest in a `sha256sum`-style file: the first token of the first line.
pub fn parse_sha256_file(text: &str) -> Option<String> {
    let token = text.lines().next()?.split_whitespace().next()?;
    (token.len() == 64 && token.chars().all(|c| c.is_ascii_hexdigit()))
        .then(|| token.to_ascii_lowercase())
}

pub fn sha256_of(path: &Path) -> Result<String, String> {
    let bytes = std::fs::read(path).map_err(|e| format!("{}: {e}", path.display()))?;
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let digest = Sha256::digest(bytes);
    let mut hex = String::with_capacity(digest.len() * 2);
    for &byte in digest.iter() {
        hex.push(char::from(HEX[usize::from(byte >> 4)]));
        hex.push(char::from(HEX[usize::from(byte & 0x0f)]));
    }
    Ok(hex)
}

/// Fetch `url` into `target` with curl. Fails on any HTTP error (`-f`).
fn download(url: &str, target: &Path) -> Result<(), String> {
    let status = Command::new("curl")
        .args(["-fsSL", "--retry", "3", "-o"])
        .arg(target)
        .arg(url)
        .status()
        .map_err(|e| format!("curl is required to download {url}: {e}"))?;
    if status.success() {
        Ok(())
    } else {
        Err(format!("download failed: {url}"))
    }
}

/// Install the JVM frontend for `version`. Returns the jar path; an existing install of
/// that version is left alone.
pub fn install_jvm(env: &Env, version: &str, skip_checksum: bool) -> Result<PathBuf, String> {
    let jar = jvm_install_path(env, version)?;
    if jar.is_file() {
        eprintln!(
            "JVM frontend {version} is already installed at {}",
            jar.display()
        );
        return Ok(jar);
    }
    let dir = jar.parent().expect("install path has a parent");
    std::fs::create_dir_all(dir).map_err(|e| format!("{}: {e}", dir.display()))?;
    let part = dir.join(format!("{}.part", frontend::JVM_FRONTEND_JAR));
    let url = frontend::release_asset_url(version, frontend::JVM_RELEASE_ASSET);
    if let Err(error) =
        download_verified(&url, &part, &dir.join("graphite.jar.sha256"), skip_checksum)
    {
        discard_partial_install(dir);
        return Err(error);
    }
    std::fs::rename(&part, &jar).map_err(|e| format!("{}: {e}", jar.display()))?;
    eprintln!("Installed JVM frontend {version} at {}", jar.display());
    Ok(jar)
}

/// Both release frontends use the same adjacent sha256sum receipt. Opting out
/// of a missing receipt never bypasses a malformed or mismatching digest.
fn download_verified(
    url: &str,
    asset: &Path,
    checksum: &Path,
    skip_checksum: bool,
) -> Result<(), String> {
    eprintln!("Downloading {url}");
    download(url, asset)?;
    let sum_url = format!("{url}.sha256");
    match download(&sum_url, checksum) {
        Ok(()) => {
            let text = std::fs::read_to_string(checksum).map_err(|e| e.to_string())?;
            let expected = parse_sha256_file(&text)
                .ok_or_else(|| format!("{sum_url} does not contain a sha256 digest"))?;
            let actual = sha256_of(asset)?;
            if actual != expected {
                return Err(format!("checksum mismatch for {url}: expected {expected}, got {actual}"));
            }
            Ok(())
        }
        Err(_) if skip_checksum => {
            eprintln!("WARNING: no checksum available for {url}; installing unverified");
            Ok(())
        }
        Err(error) => Err(format!(
            "{error}. Releases before checksums were published can be installed with --skip-checksum."
        )),
    }
}

/// Remove what a failed install left in its version directory (the `.part` download,
/// a checksum file) and the directory itself, so the directory never counts as an
/// install and never hides an older, complete one from the frontend lookup.
fn discard_partial_install(dir: &Path) {
    if dir.join(frontend::JVM_FRONTEND_JAR).is_file() {
        return;
    }
    let _ = std::fs::remove_dir_all(dir);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn invalid_versions_are_rejected_before_touching_the_install_directory() {
        let root = tempfile::tempdir().unwrap();
        let env = Env {
            home: Some(root.path().into()),
            ..Default::default()
        };
        for lang in [Lang::Js, Lang::Jvm] {
            for version in ["", "../escape", "1.2.3/a", "1.2.3?query", "2.5.0-rc.01"] {
                let error = install(&env, lang, version, false).unwrap_err();
                assert!(error.starts_with("invalid release version"), "{error}");
                assert!(!env.frontends_dir().unwrap().exists());
            }
        }
    }

    #[test]
    fn install_path_is_versioned_under_the_home_frontends_dir() {
        let env = Env {
            home: Some(PathBuf::from("/home/u")),
            ..Default::default()
        };
        assert_eq!(
            jvm_install_path(&env, "2.4.8").unwrap(),
            PathBuf::from("/home/u/.graphite/frontends/jvm/2.4.8/graphite-frontend-jvm.jar")
        );
        assert!(jvm_install_path(&Env::default(), "2.4.8").is_err());
    }

    #[test]
    fn sha256_files_are_parsed_and_validated() {
        let digest = "a".repeat(64);
        assert_eq!(
            parse_sha256_file(&format!("{digest}  graphite.jar\n")),
            Some(digest.clone())
        );
        assert_eq!(
            parse_sha256_file(&digest.to_ascii_uppercase()),
            Some(digest)
        );
        assert_eq!(parse_sha256_file("not a digest"), None);
        assert_eq!(parse_sha256_file(""), None);
    }

    #[test]
    fn sha256_of_a_file_matches_the_reference_digest() {
        let path = std::env::temp_dir().join(format!("graphite-sha-{}", std::process::id()));
        std::fs::write(&path, b"abc").unwrap();
        assert_eq!(
            sha256_of(&path).unwrap(),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        std::fs::remove_file(&path).unwrap();
        assert!(sha256_of(Path::new("/nonexistent/file")).is_err());
    }

    #[test]
    fn a_failed_install_leaves_no_version_directory_behind() {
        let root = std::env::temp_dir().join(format!("graphite-partial-{}", std::process::id()));
        let dir = root.join("jvm").join("9.9.9");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("graphite-frontend-jvm.jar.part"), b"half").unwrap();
        std::fs::write(dir.join("graphite.jar.sha256"), b"x").unwrap();
        discard_partial_install(&dir);
        assert!(!dir.exists());
        // A complete install is never discarded.
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join(frontend::JVM_FRONTEND_JAR), b"jar").unwrap();
        discard_partial_install(&dir);
        assert!(dir.join(frontend::JVM_FRONTEND_JAR).is_file());
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn an_existing_install_is_reused_without_downloading() {
        let root = std::env::temp_dir().join(format!("graphite-install-{}", std::process::id()));
        let env = Env {
            home: Some(root.clone()),
            ..Default::default()
        };
        let jar = jvm_install_path(&env, "9.9.9").unwrap();
        std::fs::create_dir_all(jar.parent().unwrap()).unwrap();
        std::fs::write(&jar, b"jar").unwrap();
        assert_eq!(install_jvm(&env, "9.9.9", false).unwrap(), jar);
        std::fs::remove_dir_all(root).unwrap();
    }
}

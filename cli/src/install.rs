//! `graphite frontend install`: fetch a frontend from a GitHub release into
//! `~/.graphite/frontends/<lang>/<version>/` and verify its checksum. The JVM frontend
//! is one jar; the Apple frontend is a tarball per target holding one executable.
//!
//! Downloads go through `curl`, which every macOS and most Linux installs carry, rather
//! than an HTTP client compiled into the binary. The checksum file published next to the
//! asset is required unless the caller opts out, so a truncated or substituted download
//! never becomes the frontend that builds someone's graphs.

use crate::frontend::{self, Env};
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::process::Command;

/// Where a frontend's file for `version` lives once installed.
fn install_path(env: &Env, lang: &str, version: &str, file: &str) -> Result<PathBuf, String> {
    let dir = env
        .frontends_dir()
        .ok_or("cannot determine the home directory (HOME is unset)")?;
    Ok(dir.join(lang).join(version).join(file))
}

/// Where the JVM frontend jar for `version` lives once installed.
pub fn jvm_install_path(env: &Env, version: &str) -> Result<PathBuf, String> {
    install_path(env, "jvm", version, frontend::JVM_FRONTEND_JAR)
}

/// Where the Apple frontend executable for `version` lives once installed.
pub fn apple_install_path(env: &Env, version: &str) -> Result<PathBuf, String> {
    install_path(
        env,
        "apple",
        version,
        &frontend::exe_name(frontend::APPLE_FRONTEND_EXE),
    )
}

/// The hex digest in a `sha256sum`-style file: the first token of the first line.
pub fn parse_sha256_file(text: &str) -> Option<String> {
    let token = text.lines().next()?.split_whitespace().next()?;
    (token.len() == 64 && token.chars().all(|c| c.is_ascii_hexdigit()))
        .then(|| token.to_ascii_lowercase())
}

pub fn sha256_of(path: &Path) -> Result<String, String> {
    let bytes = std::fs::read(path).map_err(|e| format!("{}: {e}", path.display()))?;
    Ok(format!("{:x}", Sha256::digest(bytes)))
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

/// Download `url` to `part` and check it against the `.sha256` published next to it.
/// On any failure the version directory is discarded unless `complete` already exists.
fn fetch_verified(
    url: &str,
    part: &Path,
    complete: &Path,
    skip_checksum: bool,
) -> Result<(), String> {
    let dir = part.parent().expect("download path has a parent");
    eprintln!("Downloading {url}");
    if let Err(e) = download(url, part) {
        discard_partial_install(dir, complete);
        return Err(e);
    }
    let sum_url = format!("{url}.sha256");
    let asset = url.rsplit('/').next().unwrap_or("asset");
    let sum_file = dir.join(format!("{asset}.sha256"));
    match download(&sum_url, &sum_file) {
        Ok(()) => {
            let text = std::fs::read_to_string(&sum_file).map_err(|e| e.to_string())?;
            let expected = parse_sha256_file(&text)
                .ok_or_else(|| format!("{sum_url} does not contain a sha256 digest"))?;
            let actual = sha256_of(part)?;
            if actual != expected {
                discard_partial_install(dir, complete);
                return Err(format!(
                    "checksum mismatch for {url}: expected {expected}, got {actual}"
                ));
            }
            Ok(())
        }
        Err(_) if skip_checksum => {
            eprintln!("WARNING: no checksum published for {url}; installing unverified");
            Ok(())
        }
        Err(e) => {
            discard_partial_install(dir, complete);
            Err(format!(
                "{e}. Releases before checksums were published can be installed with \
                 --skip-checksum."
            ))
        }
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
    let url = frontend::release_asset_url(env, version, frontend::JVM_RELEASE_ASSET);
    fetch_verified(&url, &part, &jar, skip_checksum)?;
    std::fs::rename(&part, &jar).map_err(|e| format!("{}: {e}", jar.display()))?;
    eprintln!("Installed JVM frontend {version} at {}", jar.display());
    Ok(jar)
}

/// Install the Apple frontend for `version`: the tarball for this machine's target,
/// verified, unpacked into the version directory. Returns the executable path; an
/// existing install of that version is left alone.
pub fn install_apple(env: &Env, version: &str, skip_checksum: bool) -> Result<PathBuf, String> {
    let exe = apple_install_path(env, version)?;
    if exe.is_file() {
        eprintln!(
            "Apple frontend {version} is already installed at {}",
            exe.display()
        );
        return Ok(exe);
    }
    let target = frontend::host_target().ok_or_else(|| {
        format!(
            "the Apple frontend is released for macOS and Linux (x86_64, aarch64), not for \
             {}-{}; build it from frontend/apple and set {}",
            std::env::consts::OS,
            std::env::consts::ARCH,
            frontend::APPLE_FRONTEND_VAR
        )
    })?;
    let dir = exe.parent().expect("install path has a parent");
    std::fs::create_dir_all(dir).map_err(|e| format!("{}: {e}", dir.display()))?;
    let asset = frontend::apple_release_asset(version, target);
    let part = dir.join(format!("{asset}.part"));
    let url = frontend::release_asset_url(env, version, &asset);
    fetch_verified(&url, &part, &exe, skip_checksum)?;
    if let Err(e) = unpack(&part, dir, &exe) {
        discard_partial_install(dir, &exe);
        return Err(e);
    }
    let _ = std::fs::remove_file(&part);
    eprintln!("Installed Apple frontend {version} at {}", exe.display());
    Ok(exe)
}

/// Unpack a `.tar.gz` into `dir` with the system `tar` and check `expected` came out of
/// it, executable.
fn unpack(archive: &Path, dir: &Path, expected: &Path) -> Result<(), String> {
    let status = Command::new("tar")
        .arg("-xzf")
        .arg(archive)
        .arg("-C")
        .arg(dir)
        .status()
        .map_err(|e| format!("tar is required to unpack {}: {e}", archive.display()))?;
    if !status.success() {
        return Err(format!("could not unpack {}", archive.display()));
    }
    if !expected.is_file() {
        return Err(format!(
            "{} does not contain {}",
            archive.display(),
            expected.file_name().unwrap_or_default().to_string_lossy()
        ));
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(expected, std::fs::Permissions::from_mode(0o755))
            .map_err(|e| format!("{}: {e}", expected.display()))?;
    }
    Ok(())
}

/// Remove what a failed install left in its version directory (the `.part` download,
/// a checksum file) and the directory itself, so the directory never counts as an
/// install and never hides an older, complete one from the frontend lookup.
fn discard_partial_install(dir: &Path, complete: &Path) {
    if complete.is_file() {
        return;
    }
    let _ = std::fs::remove_dir_all(dir);
}

#[cfg(test)]
mod tests {
    use super::*;

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
        assert_eq!(
            apple_install_path(&env, "2.6.0").unwrap(),
            PathBuf::from("/home/u/.graphite/frontends/apple/2.6.0")
                .join(frontend::exe_name(frontend::APPLE_FRONTEND_EXE))
        );
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
        let jar = dir.join(frontend::JVM_FRONTEND_JAR);
        discard_partial_install(&dir, &jar);
        assert!(!dir.exists());
        // A complete install is never discarded.
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(&jar, b"jar").unwrap();
        discard_partial_install(&dir, &jar);
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
        let exe = apple_install_path(&env, "9.9.9").unwrap();
        std::fs::create_dir_all(exe.parent().unwrap()).unwrap();
        std::fs::write(&exe, b"exe").unwrap();
        assert_eq!(install_apple(&env, "9.9.9", false).unwrap(), exe);
        std::fs::remove_dir_all(root).unwrap();
    }

    /// A release directory served over `file://`: the tarball and its checksum, as the
    /// publish workflow lays them out.
    #[cfg(unix)]
    #[test]
    fn the_apple_frontend_is_installed_from_a_verified_tarball() {
        let root =
            std::env::temp_dir().join(format!("graphite-apple-install-{}", std::process::id()));
        let release = root.join("release");
        std::fs::create_dir_all(&release).unwrap();
        let payload = root.join("payload");
        std::fs::create_dir_all(&payload).unwrap();
        std::fs::write(
            payload.join(frontend::APPLE_FRONTEND_EXE),
            b"#!/bin/sh\necho 0.1.0\n",
        )
        .unwrap();
        let target = frontend::host_target().unwrap();
        let asset = frontend::apple_release_asset("9.9.9", target);
        let archive = release.join(&asset);
        assert!(Command::new("tar")
            .arg("-czf")
            .arg(&archive)
            .arg("-C")
            .arg(&payload)
            .arg(frontend::APPLE_FRONTEND_EXE)
            .status()
            .unwrap()
            .success());
        let mut env = Env {
            home: Some(root.join("home")),
            ..Default::default()
        };
        env.vars.insert(
            "GRAPHITE_RELEASE_BASE".into(),
            format!("file://{}", release.display()).into(),
        );

        // No checksum published: refused unless --skip-checksum.
        let err = install_apple(&env, "9.9.9", false).unwrap_err();
        assert!(err.contains("--skip-checksum"), "{err}");
        assert!(!root.join("home/.graphite/frontends/apple/9.9.9").exists());

        // A wrong checksum is a refusal that leaves nothing behind.
        std::fs::write(
            release.join(format!("{asset}.sha256")),
            format!("{}  {asset}\n", "0".repeat(64)),
        )
        .unwrap();
        let err = install_apple(&env, "9.9.9", false).unwrap_err();
        assert!(err.contains("checksum mismatch"), "{err}");
        assert!(!root.join("home/.graphite/frontends/apple/9.9.9").exists());

        // The right checksum installs an executable the lookup then finds.
        let digest = sha256_of(&archive).unwrap();
        std::fs::write(
            release.join(format!("{asset}.sha256")),
            format!("{digest}  {asset}\n"),
        )
        .unwrap();
        let exe = install_apple(&env, "9.9.9", false).unwrap();
        assert_eq!(exe, apple_install_path(&env, "9.9.9").unwrap());
        let out = Command::new(&exe).output().unwrap();
        assert_eq!(String::from_utf8_lossy(&out.stdout).trim(), "0.1.0");
        assert!(!exe.parent().unwrap().join(format!("{asset}.part")).exists());
        let found = frontend::locate_apple(&env).unwrap();
        assert_eq!(found.path(), exe);
        assert_eq!(found.found_via, "~/.graphite/frontends");

        // A tarball without the executable is refused.
        let empty = root.join("empty");
        std::fs::create_dir_all(empty.join("other")).unwrap();
        let bad_asset = frontend::apple_release_asset("9.9.8", target);
        assert!(Command::new("tar")
            .arg("-czf")
            .arg(release.join(&bad_asset))
            .arg("-C")
            .arg(&empty)
            .arg("other")
            .status()
            .unwrap()
            .success());
        let err = install_apple(&env, "9.9.8", true).unwrap_err();
        assert!(err.contains("does not contain"), "{err}");
        assert!(!root.join("home/.graphite/frontends/apple/9.9.8").exists());
        std::fs::remove_dir_all(root).unwrap();
    }
}

use super::download_verified;
use crate::frontend::{release_asset_url, Env, JS_INSTALLED_ENTRY, JS_RELEASE_ASSET};
use flate2::read::GzDecoder;
use std::fs::{self, File};
use std::io;
use std::path::{Component, Path, PathBuf};

pub(super) fn install_js(env: &Env, version: &str, skip_checksum: bool) -> Result<PathBuf, String> {
    let base = env
        .frontends_dir()
        .ok_or("cannot determine the home directory (HOME is unset)")?
        .join("js");
    let target = base.join(version);
    let entry = target.join(JS_INSTALLED_ENTRY);
    if entry.is_file() {
        eprintln!(
            "JS frontend {version} is already installed at {}",
            entry.display()
        );
        return Ok(entry);
    }
    fs::create_dir_all(&base).map_err(|e| format!("{}: {e}", base.display()))?;
    let stage = tempfile::tempdir_in(&base).map_err(|e| e.to_string())?;
    let archive = stage.path().join(JS_RELEASE_ASSET);
    let url = release_asset_url(version, JS_RELEASE_ASSET);
    download_verified(
        &url,
        &archive,
        &stage.path().join("checksum"),
        skip_checksum,
    )?;

    // Only the version directory exposes package/dist/cli.js to discovery.
    // The download and its unpacked tree are private until the single rename.
    let unpacked = stage.path().join("unpacked");
    unpack(&archive, &unpacked, version).map_err(|e| format!("invalid {JS_RELEASE_ASSET}: {e}"))?;
    if let Err(error) = fs::rename(&unpacked, &target) {
        // Another installation of the same version may already have finished.
        if !entry.is_file() {
            return Err(format!("cannot install to {}: {error}", target.display()));
        }
    }
    eprintln!("Installed JS frontend {version} at {}", entry.display());
    Ok(entry)
}

fn unpack(archive: &Path, dest: &Path, version: &str) -> io::Result<()> {
    let gzip = GzDecoder::new(File::open(archive)?);
    let mut tar = tar::Archive::new(gzip);
    for member in tar.entries()? {
        let mut member = member?;
        let path = member.path()?;
        // npm pack puts the entire package (including bundled dependencies)
        // under package/. Only copy data: archive links, modes and special
        // entries must never redirect writes or install executable metadata.
        if !path.starts_with("package")
            || !path
                .components()
                .all(|part| matches!(part, Component::Normal(_)))
        {
            return Err(io::Error::other(format!(
                "unexpected archive path {}",
                path.display()
            )));
        }
        let out = dest.join(&path);
        let kind = member.header().entry_type();
        if kind.is_dir() {
            fs::create_dir_all(out)?;
        } else if kind.is_file() {
            fs::create_dir_all(out.parent().expect("archive path has a parent"))?;
            let expected_size = member.size();
            if io::copy(&mut member, &mut File::create_new(out)?)? != expected_size {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "truncated archive member",
                ));
            }
        } else {
            return Err(io::Error::other(format!(
                "unsupported archive entry {}",
                path.display()
            )));
        }
    }

    // The tar reader stops at the end marker before the gzip trailer.
    io::copy(&mut tar.into_inner(), &mut io::sink())?;
    for required in [
        JS_INSTALLED_ENTRY,
        "package/node_modules/typescript/package.json",
        "package/node_modules/typescript/lib/typescript.js",
        "package/node_modules/typescript/lib/lib.d.ts",
    ] {
        if !dest.join(required).is_file() {
            return Err(io::Error::other(format!("missing {required}")));
        }
    }
    let manifest: serde_json::Value =
        serde_json::from_reader(File::open(dest.join("package/package.json"))?)?;
    if manifest["version"].as_str() != Some(version) {
        return Err(io::Error::other(format!(
            "expected package version {version}, found {}",
            manifest["version"]
        )));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::{write::GzEncoder, Compression};
    use tar::{Builder, EntryType, Header};

    fn archive(path: &Path, entries: &[(&str, EntryType, &[u8])]) {
        let writer = GzEncoder::new(File::create(path).unwrap(), Compression::fast());
        let mut tar = Builder::new(writer);
        for &(name, kind, data) in entries {
            let mut header = Header::new_gnu();
            // Set raw bytes so even names that Builder rejects can be presented to the reader.
            header.as_mut_bytes()[..name.len()].copy_from_slice(name.as_bytes());
            header.set_size(data.len() as u64);
            header.set_entry_type(kind);
            if kind.is_symlink() || kind.is_hard_link() {
                header.set_link_name("../../outside").unwrap();
            }
            header.set_cksum();
            tar.append(&header, data).unwrap();
        }
        tar.into_inner().unwrap().finish().unwrap();
    }

    #[test]
    fn extraction_rejects_escaping_paths_and_link_entries() {
        for (name, kind, error) in [
            (
                "package/../../outside",
                EntryType::Regular,
                "unexpected archive path",
            ),
            ("outside", EntryType::Regular, "unexpected archive path"),
            (
                "package/link",
                EntryType::Symlink,
                "unsupported archive entry",
            ),
            ("package/link", EntryType::Link, "unsupported archive entry"),
        ] {
            let root = tempfile::tempdir().unwrap();
            let input = root.path().join("release.tgz");
            let dest = root.path().join("unpacked");
            archive(&input, &[(name, kind, b"")]);
            let message = unpack(&input, &dest, "1.2.3").unwrap_err().to_string();
            assert!(message.contains(error), "{message}");
            assert!(!root.path().join("outside").exists());
            assert!(!dest.join("package/link").exists());
        }
    }

    #[test]
    fn extraction_requires_the_compiler_and_matching_release_version() {
        let root = tempfile::tempdir().unwrap();
        let input = root.path().join("release.tgz");
        let mut entries = vec![
            (JS_INSTALLED_ENTRY, EntryType::Regular, &b"// script"[..]),
            (
                "package/package.json",
                EntryType::Regular,
                &br#"{"version":"1.2.3"}"#[..],
            ),
        ];
        archive(&input, &entries);
        let missing = unpack(&input, &root.path().join("missing"), "1.2.3").unwrap_err();
        assert!(missing
            .to_string()
            .contains("missing package/node_modules/typescript"));
        for name in [
            "package/node_modules/typescript/package.json",
            "package/node_modules/typescript/lib/typescript.js",
            "package/node_modules/typescript/lib/lib.d.ts",
        ] {
            entries.push((name, EntryType::Regular, b"content"));
        }
        archive(&input, &entries);
        let mistagged = unpack(&input, &root.path().join("mistagged"), "1.3.0").unwrap_err();
        assert!(mistagged
            .to_string()
            .contains("expected package version 1.3.0, found \"1.2.3\""));
        let dest = root.path().join("ok");
        unpack(&input, &dest, "1.2.3").unwrap();
        assert_eq!(
            fs::read(dest.join(JS_INSTALLED_ENTRY)).unwrap(),
            b"// script"
        );
    }
}

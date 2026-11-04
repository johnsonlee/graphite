use super::download_verified;
use crate::frontend::{is_launcher, js_release_asset, managed_js_command, release_asset_url, Env};
use std::fs;
use std::path::PathBuf;
use tempfile::NamedTempFile;

pub(super) fn install_js(env: &Env, version: &str) -> Result<PathBuf, String> {
    let asset = js_release_asset()?;
    let base = env
        .frontends_dir()
        .ok_or("cannot determine the home directory (HOME is unset)")?
        .join("js");
    let dir = base.join(version);
    let entry = dir.join(&asset);
    if is_launcher(&entry) {
        eprintln!(
            "JS frontend {version} is already installed at {}",
            entry.display()
        );
        return Ok(entry);
    }

    fs::create_dir_all(&base).map_err(|e| format!("{}: {e}", base.display()))?;
    let staged = NamedTempFile::new_in(&base)
        .map_err(|e| e.to_string())?
        .into_temp_path();
    let checksum = NamedTempFile::new_in(&base).map_err(|e| e.to_string())?;
    download_verified(
        &release_asset_url(version, &asset),
        &staged,
        checksum.path(),
        false,
    )?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&staged, fs::Permissions::from_mode(0o755))
            .map_err(|e| e.to_string())?;
    }

    let probe = managed_js_command(&staged)
        .arg("--version")
        .output()
        .map_err(|error| format!("cannot run {asset} on this system: {error}"))?;
    if !probe.status.success() {
        return Err(format!(
            "{asset} --version failed ({}): {}",
            probe.status,
            String::from_utf8_lossy(&probe.stderr).trim()
        ));
    }
    let reported = String::from_utf8_lossy(&probe.stdout);
    if reported.trim() != format!("graphite {version}") {
        return Err(format!(
            "{asset} --version reported {:?}, expected \"graphite {version}\"",
            reported.trim()
        ));
    }

    // Only fully verified executables are visible at their platform-specific
    // name. Two simultaneous installs may publish the same release once.
    fs::create_dir_all(&dir).map_err(|e| format!("{}: {e}", dir.display()))?;
    if let Err(error) = staged.persist_noclobber(&entry) {
        if error.error.kind() != std::io::ErrorKind::AlreadyExists || !is_launcher(&entry) {
            return Err(format!("cannot install to {}: {error}", entry.display()));
        }
    }
    eprintln!("Installed JS frontend {version} at {}", entry.display());
    Ok(entry)
}

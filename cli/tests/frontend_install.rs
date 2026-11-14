use std::process::Command;

#[cfg(all(
    any(target_os = "linux", target_os = "macos"),
    any(target_arch = "aarch64", target_arch = "x86_64")
))]
#[test]
fn all_js_install_aliases_share_one_executable_and_run_without_node_on_path() {
    use std::os::unix::fs::PermissionsExt;

    let home = tempfile::tempdir().unwrap();
    let bin = home.path().join("bin");
    std::fs::create_dir(&bin).unwrap();
    let payload = home.path().join("asset");
    let receipt = home.path().join("checksum");
    let write_asset = |script: &str| {
        use sha2::{Digest, Sha256};
        std::fs::write(&payload, script).unwrap();
        let digest: String = Sha256::digest(script.as_bytes())
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        std::fs::write(&receipt, digest).unwrap();
    };
    let curl = bin.join("curl");
    std::fs::write(&curl, r#"#!/bin/sh
case "$6" in
  https://github.com/johnsonlee/graphite/releases/download/v2.6.0/graphite-frontend-js-*.sha256) /bin/cp "$RECEIPT" "$5";;
  https://github.com/johnsonlee/graphite/releases/download/v2.6.0/graphite-frontend-js-*) /bin/cp "$PAYLOAD" "$5";;
  *) exit 22;;
esac
"#).unwrap();
    std::fs::set_permissions(&curl, std::fs::Permissions::from_mode(0o755)).unwrap();
    let run = |args: &[&str]| {
        Command::new(env!("CARGO_BIN_EXE_graphite"))
            .args(args)
            .env_clear()
            .env("HOME", home.path())
            .env("PATH", &bin)
            .env("BUN_BE_BUN", "1")
            .env("BUN_OPTIONS", "--preload=bad.js")
            .env("RECEIPT", &receipt)
            .env("PAYLOAD", &payload)
            .output()
            .unwrap()
    };
    let install = ["frontend", "install", "js", "--version", "v2.6.0"];
    let wrong_binary = "#!/bin/sh\n: > \"$HOME/probed\"\necho 1.3.11\n";
    write_asset(wrong_binary);
    std::fs::write(&receipt, "0".repeat(64)).unwrap();
    let corrupt = run(&install);
    assert!(!corrupt.status.success());
    assert!(String::from_utf8(corrupt.stderr)
        .unwrap()
        .contains("checksum mismatch"));
    assert!(
        !home.path().join("probed").exists(),
        "unverified executable ran"
    );

    write_asset(wrong_binary);
    let incompatible = run(&install);
    assert!(!incompatible.status.success());
    assert!(String::from_utf8(incompatible.stderr)
        .unwrap()
        .contains("reported \"1.3.11\", expected \"graphite 2.6.0\""));
    assert!(home.path().join("probed").is_file());
    assert!(!home.path().join(".graphite/frontends/js/2.6.0").exists());

    write_asset("#!/bin/sh\n[ -z \"$BUN_BE_BUN$BUN_OPTIONS\" ] || exit 1\necho 'graphite 2.6.0'\n");
    let first = run(&install);
    assert!(first.status.success(), "{first:?}");
    std::fs::remove_file(curl).unwrap(); // From here on PATH is empty of tools.
    let descriptor = run(&["frontend", "describe", "js"]);
    assert!(descriptor.status.success(), "{descriptor:?}");
    let info: serde_json::Value = serde_json::from_slice(&descriptor.stdout).unwrap();
    assert_eq!(info["kind"], "executable");
    assert_eq!(info["version"], "2.6.0");
    let installed = std::path::Path::new(info["path"].as_str().unwrap());
    assert_eq!(
        installed.parent().unwrap(),
        home.path().join(".graphite/frontends/js/2.6.0")
    );
    assert_eq!(
        std::fs::read(installed).unwrap(),
        std::fs::read(&payload).unwrap()
    );
    for alias in ["js", "ts", "javascript", "typescript", "es"] {
        let output = run(&["frontend", "install", alias, "--version", "v2.6.0"]);
        assert!(output.status.success(), "{output:?}");
        let message = String::from_utf8(output.stderr).unwrap();
        assert!(
            message.contains("already installed") && message.contains(installed.to_str().unwrap()),
            "{message}"
        );
    }
}

#[test]
fn js_install_requires_a_sha256_checksum() {
    let home = tempfile::tempdir().unwrap();
    let out = Command::new(env!("CARGO_BIN_EXE_graphite"))
        .args([
            "frontend",
            "install",
            "es",
            "--skip-checksum",
            "--version",
            "2.6.0",
        ])
        .env("HOME", home.path())
        .env("PATH", home.path())
        .output()
        .unwrap();
    assert!(!out.status.success());
    assert!(String::from_utf8(out.stderr)
        .unwrap()
        .contains("--skip-checksum is only supported for legacy JVM or Apple releases"));
    assert!(!home.path().join(".graphite").exists());
}

#[test]
fn a_source_file_is_not_an_installed_frontend() {
    let root = tempfile::tempdir().unwrap();
    let source = root.path().join("cli.ts");
    std::fs::write(&source, "const value: number = 1;").unwrap();
    let run = |args: &[&str]| {
        Command::new(env!("CARGO_BIN_EXE_graphite"))
            .args(args)
            .current_dir(root.path())
            .env("HOME", root.path())
            .env_remove("GRAPHITE_FRONTEND_TS")
            .env("GRAPHITE_FRONTEND_JS", &source)
            .env("PATH", root.path())
            .output()
            .unwrap()
    };
    let list = run(&["frontend", "list"]);
    assert!(list.status.success());
    assert!(String::from_utf8(list.stdout)
        .unwrap()
        .contains("js\tnot installed"));
    let build = run(&["build", "--lang", "js", "main.js", "-o", "graph"]);
    assert!(!build.status.success());
    let message = String::from_utf8(build.stderr).unwrap();
    assert!(message.contains("graphite frontend install js"));
    assert!(message.contains(&format!("GRAPHITE_FRONTEND_JS={}", source.display())));
    assert!(!root.path().join("graph").exists());
}

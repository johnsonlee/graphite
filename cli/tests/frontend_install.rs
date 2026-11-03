use std::process::Command;

#[test]
fn js_install_aliases_explain_manual_setup_with_and_without_a_frontend_on_path() {
    let bin = tempfile::tempdir().unwrap();
    let launcher = bin.path().join(format!(
        "graphite-frontend-js{}",
        std::env::consts::EXE_SUFFIX
    ));
    for present in [false, true] {
        if present {
            std::fs::write(&launcher, "installed launcher").unwrap();
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                std::fs::set_permissions(&launcher, std::fs::Permissions::from_mode(0o755))
                    .unwrap();
            }
        }
        for alias in ["js", "ts"] {
            let result = Command::new(env!("CARGO_BIN_EXE_graphite"))
                .args(["frontend", "install", alias])
                .env_remove("GRAPHITE_FRONTEND_JS")
                .env_remove("GRAPHITE_FRONTEND_TS")
                .env("PATH", bin.path())
                .output()
                .unwrap();
            assert!(!result.status.success());
            let error = String::from_utf8(result.stderr).unwrap();
            assert!(
                error.contains("cannot download the JavaScript artifact frontend"),
                "{error}"
            );
            assert!(
                error.contains("npm ci && npm run build") && error.contains("GRAPHITE_FRONTEND_JS"),
                "{error}"
            );
            assert_eq!(
                error.contains(&launcher.display().to_string()),
                present,
                "{error}"
            );
            assert!(
                !error.contains("No JavaScript artifact frontend found"),
                "{error}"
            );
        }
    }
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
    assert!(message.contains("npm ci && npm run build"));
    assert!(message.contains(&format!("GRAPHITE_FRONTEND_JS={}", source.display())));
    assert!(!root.path().join("graph").exists());
}

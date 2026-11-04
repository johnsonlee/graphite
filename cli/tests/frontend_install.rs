use std::process::Command;

#[test]
fn all_js_install_aliases_share_one_versioned_install() {
    let home = tempfile::tempdir().unwrap();
    let entry = home
        .path()
        .join(".graphite/frontends/js/2.6.0/package/dist/cli.js");
    std::fs::create_dir_all(entry.parent().unwrap()).unwrap();
    std::fs::write(&entry, "// installed frontend").unwrap();
    for alias in ["js", "ts", "javascript", "typescript"] {
        let output = Command::new(env!("CARGO_BIN_EXE_graphite"))
            .args(["frontend", "install", alias, "--version", "v2.6.0"])
            .env("HOME", home.path())
            .env("PATH", home.path())
            .output()
            .unwrap();
        assert!(output.status.success(), "{output:?}");
        let message = String::from_utf8(output.stderr).unwrap();
        assert!(message.contains("already installed"), "{message}");
        assert!(message.contains(&entry.display().to_string()), "{message}");
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

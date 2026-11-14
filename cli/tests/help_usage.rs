use std::process::Command;

fn help(args: &[&str]) -> String {
    let home = tempfile::tempdir().unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_graphite"))
        .args(args)
        .env("HOME", home.path())
        .env("PATH", home.path())
        .env("GRAPHITE_FRONTEND_JVM", home.path().join("missing-jvm"))
        .env("GRAPHITE_FRONTEND_JS", home.path().join("missing-js"))
        .env("GRAPHITE_FRONTEND_APPLE", home.path().join("missing-apple"))
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{args:?}: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(output.stderr.is_empty(), "{args:?}: unexpected diagnostic");
    String::from_utf8(output.stdout)
        .unwrap()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

#[test]
fn every_shell_command_has_help_without_an_installed_frontend() {
    for (args, usage) in [
        (vec!["--help"], "Usage: graphite [COMMAND]"),
        (vec!["help", "build"], "Usage: graphite build"),
        (vec!["help", "import"], "Usage: graphite import"),
        (vec!["query", "--help"], "Usage: graphite query"),
        (vec!["serve", "--help"], "Usage: graphite serve"),
        (vec!["mcp", "--help"], "Usage: graphite mcp"),
        (vec!["pack", "--help"], "Usage: graphite pack"),
        (vec!["unpack", "--help"], "Usage: graphite unpack"),
        (vec!["verify", "--help"], "Usage: graphite verify"),
        (vec!["info", "--help"], "Usage: graphite info"),
        (vec!["frontend", "--help"], "Usage: graphite frontend"),
        (
            vec!["frontend", "list", "--help"],
            "Usage: graphite frontend list",
        ),
        (
            vec!["frontend", "describe", "--help"],
            "Usage: graphite frontend describe <LANG>",
        ),
        (
            vec!["frontend", "install", "--help"],
            "Usage: graphite frontend install",
        ),
    ] {
        assert!(help(&args).contains(usage), "{args:?}");
    }
}

#[test]
fn transport_help_distinguishes_http_server_from_stdio_subprocess() {
    let serve = help(&["serve", "--help"]);
    assert!(serve.contains("web UI, REST API and MCP at /mcp"));
    assert!(serve.contains("http://localhost:8080/api/graphs"));
    assert!(serve.contains("http://localhost:8080/mcp"));
    assert!(serve.contains("--port <PORT>"));
    assert!(serve.contains("use graphite mcp"));

    let mcp = help(&["mcp", "--help"]);
    assert!(mcp.contains("MCP over stdio (stdin/stdout)"));
    assert!(mcp.contains("needs no running serve process"));
    assert!(mcp.contains("opens no HTTP port or web UI"));
    assert!(mcp.contains("startup diagnostics go to stderr"));
    assert!(!mcp.contains("--port"));

    for text in [&serve, &mcp] {
        assert!(text.contains("[GRAPH]"));
        assert!(text.contains("--graph <ID:PATH>"));
        assert!(text.contains("app.graphite -> app"));
        assert!(!text.contains("GRAPH_DIR"));
    }
    let query = help(&["query", "--help"]);
    assert!(query.contains("<GRAPH> <QUERY>"));
    assert!(query.contains("a directory or a .graphite file"));
    assert!(!query.contains("GRAPH_DIR"));
}

#[test]
fn build_and_import_help_explain_different_output_and_frontend_contracts() {
    let build = help(&["help", "build"]);
    assert!(build.contains("-o <OUTPUT>"));
    assert!(build.contains("-o app and -o app.graphite both write app.graphite"));
    assert!(build.contains("Existing verified archives are rebuilt in place"));
    assert!(build.contains("This is shell help"));
    assert!(build.contains("options reported by the installed frontend"));
    assert!(build.contains("graphite unpack app.graphite app/"));
    assert!(!build.contains("--pack"));

    let import = help(&["help", "import"]);
    assert!(import.contains("<INPUT.graphite-ir> -o <OUTPUT>"));
    assert!(import.contains("Requires the JVM frontend"));
    assert!(import.contains("import does not append an extension"));
    assert!(import.contains("-o app writes a graph directory"));
    assert!(import.contains("-o app.graphite writes an archive"));
    assert!(
        import.contains("graphite import --help reports options from the installed JVM frontend")
    );
}

#[test]
fn archive_help_explains_exact_paths_overwrite_and_optional_checksums() {
    let pack = help(&["pack", "--help"]);
    assert!(pack.contains("--output <ARCHIVE> <GRAPH_DIR_OR_FILE>..."));
    assert!(pack.contains("used exactly as given"));
    assert!(pack.contains("existing outputs are refused"));

    let unpack = help(&["unpack", "--help"]);
    assert!(unpack.contains("<ARCHIVE> [DIRECTORY]"));
    assert!(unpack.contains("default: current directory"));
    assert!(unpack.contains("same-named files overwritten"));

    let verify = help(&["verify", "--help"]);
    assert!(verify.contains("A missing manifest produces a warning"));
    assert!(verify.contains("CRC-32 only"));
    assert!(verify.contains("A missing .sha256 sidecar is reported but is not an error"));
    assert!(verify.contains("Mismatching available checksums fail verification"));
    assert!(help(&["info", "--help"]).contains("Usage: graphite info <ARCHIVE>"));
}

use std::process::Command;

#[test]
fn invalid_output_is_a_usage_error_with_or_without_fold() {
    for inputs in [
        (
            vec!["-o", "x.graphite", "--output=y.graphite"],
            "specify --output only once",
        ),
        (
            vec!["--output"],
            "--output requires a path (use --output=-name for a path starting with '-')",
        ),
    ] {
        for fold in [true, false] {
            let mut command = Command::new(env!("CARGO_BIN_EXE_graphite"));
            command.args(["build", "missing.jar"]);
            if fold {
                command.args(["--fold", "missing-rules.yml"]);
            }
            let result = command.args(&inputs.0).output().unwrap();
            assert_eq!(result.status.code(), Some(2));
            assert_eq!(
                String::from_utf8(result.stderr).unwrap().trim(),
                format!("Error: {}", inputs.1)
            );
        }
    }
}

#[test]
fn a_separate_output_value_cannot_consume_another_option() {
    for lang in ["js", "jvm"] {
        for options in [
            ["-o", "--help"],
            ["-o", "--pack"],
            ["-o", "-h"],
            ["-o", "--version"],
            ["-o", "--android-sdk"],
            ["-o", "--fold-strict"],
            ["-o", "-v"],
            ["--output=", "--help"],
        ] {
            let output = Command::new(env!("CARGO_BIN_EXE_graphite"))
                .args(["build", "--lang", lang, "input"])
                .args(options)
                .output()
                .unwrap();
            assert_eq!(output.status.code(), Some(2));
            assert_eq!(
                String::from_utf8(output.stderr).unwrap().trim(),
                "Error: --output requires a path (use --output=-name for a path starting with '-')"
            );
        }
    }
}

#[test]
fn shell_build_help_explains_profile_placement() {
    let result = Command::new(env!("CARGO_BIN_EXE_graphite"))
        .args(["help", "build"])
        .output()
        .unwrap();
    assert!(result.status.success());
    let help = String::from_utf8(result.stdout).unwrap();
    assert!(help.contains("--profile before the -- separator"));
    assert!(help.contains("graphite build --profile -o app.graphite -- app.jar"));
}

#[test]
fn shell_build_help_explains_archive_output() {
    let result = Command::new(env!("CARGO_BIN_EXE_graphite"))
        .args(["help", "build"])
        .output()
        .unwrap();
    assert!(result.status.success());
    let help = String::from_utf8(result.stdout).unwrap();
    assert!(help.contains("Build writes a .graphite archive"));
    assert!(help.contains("appending .graphite to -o when needed"));
}

#[cfg(unix)]
#[test]
fn build_publishes_verified_archive_only_after_frontend_success() {
    use std::os::unix::fs::PermissionsExt;
    let root = tempfile::tempdir().unwrap();
    let fixture = root.path().join("fixture");
    let json = br#"{"version":1,"methods":[],"nodes":[{"id":0,"kind":"StringConstant","value":"packed"}],"edges":[]}"#;
    graphite_storage::interchange::import_json(json.as_slice(), &fixture).unwrap();
    let frontend = root.path().join("frontend");
    std::fs::write(
        &frontend,
        r#"#!/bin/sh
set -eu
while [ $# -gt 0 ]; do
  case "$1" in
    --pack) exit 99 ;;
    -o) mkdir -p "$2"; cp "$FIXTURE"/* "$2/"; shift ;;
  esac
  shift
done
exit "$BUILD_EXIT"
"#,
    )
    .unwrap();
    std::fs::set_permissions(&frontend, std::fs::Permissions::from_mode(0o755)).unwrap();
    for (iteration, (name, code)) in [("success.v1", 0), ("success.v1", 0), ("failed", 7)]
        .into_iter()
        .enumerate()
    {
        let fixture = root.path().join(format!("fixture-{iteration}"));
        let expected = format!("packed-{iteration}");
        let json = String::from_utf8(json.to_vec())
            .unwrap()
            .replace("packed", &expected);
        graphite_storage::interchange::import_json(json.as_bytes(), &fixture).unwrap();
        let output = root.path().join(name);
        if iteration == 1 {
            graphite_storage::interchange::import_json(
                json.replace(&expected, "old-directory").as_bytes(),
                &output,
            )
            .unwrap();
        }
        let result = Command::new(env!("CARGO_BIN_EXE_graphite"))
            .args(["build", "app.jar", "-o"])
            .arg(&output)
            .env("GRAPHITE_FRONTEND_JVM", &frontend)
            .env("FIXTURE", &fixture)
            .env("BUILD_EXIT", code.to_string())
            .output()
            .unwrap();
        assert_eq!(
            result.status.code(),
            Some(code),
            "{}",
            String::from_utf8_lossy(&result.stderr)
        );
        let stderr = String::from_utf8_lossy(&result.stderr);
        assert!(
            stderr.contains("appended .graphite to --output"),
            "{stderr}"
        );
        if iteration == 1 {
            assert!(stderr.contains("existing directory"), "{stderr}");
            assert!(stderr.contains("is no longer updated"), "{stderr}");
            assert!(stderr.contains("graphite unpack"), "{stderr}");
            assert!(output.is_dir());
            let old = graphite_storage::Graph::load(&output).unwrap();
            let graphite_storage::NodeKind::StringConstant(value) = old.node(0).unwrap().kind
            else {
                panic!("expected old string")
            };
            assert_eq!(old.str(value), "old-directory");
        } else {
            assert!(!stderr.contains("is no longer updated"), "{stderr}");
            assert!(!output.exists());
        }
        let archive = root.path().join(format!("{name}.graphite"));
        if code == 0 {
            let verified = graphite_storage::Container::open(&archive)
                .unwrap()
                .verify()
                .unwrap();
            assert!(verified.ok());
            assert_eq!(verified.digest_file, Some(true));
            let graph = graphite_storage::Graph::load(&archive).unwrap();
            let graphite_storage::NodeKind::StringConstant(value) = graph.node(0).unwrap().kind
            else {
                panic!("expected string")
            };
            assert_eq!(graph.str(value), expected);
        } else {
            assert!(!archive.exists());
            assert!(!graphite_storage::container::digest_path(&archive).exists());
        }
    }
}

#[test]
fn removed_pack_flag_fails_before_frontend_discovery() {
    let root = tempfile::tempdir().unwrap();
    let output = root.path().join("app");
    for lang in ["jvm", "js", "apple"] {
        let result = Command::new(env!("CARGO_BIN_EXE_graphite"))
            .args(["build", "--lang", lang, "input", "--pack", "-o"])
            .arg(&output)
            .output()
            .unwrap();
        assert_eq!(result.status.code(), Some(2));
        assert_eq!(
            String::from_utf8(result.stderr).unwrap().trim(),
            "Error: unknown option --pack: build already writes a .graphite archive"
        );
        assert_eq!(std::fs::read_dir(root.path()).unwrap().count(), 0);
    }
}

#[cfg(unix)]
#[test]
fn apple_and_js_builds_default_to_archives_and_import_keeps_directories() {
    use std::os::unix::fs::PermissionsExt;
    let root = tempfile::tempdir().unwrap();
    let fixture = root.path().join("fixture");
    let json = br#"{"version":1,"methods":[],"nodes":[{"id":0,"kind":"StringConstant","value":"frontend"}],"edges":[]}"#;
    graphite_storage::interchange::import_json(json.as_slice(), &fixture).unwrap();
    let json_file = root.path().join("fixture.json");
    std::fs::write(&json_file, json).unwrap();
    let frontend = root.path().join("frontend");
    std::fs::write(
        &frontend,
        r#"#!/bin/sh
set -eu
if [ "$1" = describe ]; then echo '{"ir_schema":[1]}'; exit 0; fi
while [ $# -gt 0 ]; do
  case "$1" in
    --pack) exit 99 ;;
    --output) cp "$JSON_FIXTURE" "$2"; shift ;;
    --out) echo ir > "$2"; shift ;;
    -o) mkdir -p "$2"; cp "$FIXTURE"/* "$2/"; shift ;;
  esac
  shift
done
exit "${BUILD_EXIT:-0}"
"#,
    )
    .unwrap();
    std::fs::set_permissions(&frontend, std::fs::Permissions::from_mode(0o755)).unwrap();
    for lang in ["apple", "js"] {
        let output = root.path().join(lang);
        let archive = output.with_extension("graphite");
        for (iteration, code) in [7, 0, 0, 7].into_iter().enumerate() {
            let fixture = root.path().join(format!("fixture-{lang}-{iteration}"));
            let expected = format!("frontend-{iteration}");
            let current_json = String::from_utf8(json.to_vec())
                .unwrap()
                .replace("frontend", &expected);
            graphite_storage::interchange::import_json(current_json.as_bytes(), &fixture).unwrap();
            std::fs::write(&json_file, current_json).unwrap();
            let previous = std::fs::read(&archive).ok();
            let result = Command::new(env!("CARGO_BIN_EXE_graphite"))
                .args(["build", "--lang", lang, "input", "-o"])
                .arg(&output)
                .env("GRAPHITE_FRONTEND_APPLE", &frontend)
                .env("GRAPHITE_FRONTEND_JS", &frontend)
                .env("GRAPHITE_FRONTEND_JVM", &frontend)
                .env("FIXTURE", &fixture)
                .env("JSON_FIXTURE", &json_file)
                .env("BUILD_EXIT", code.to_string())
                .output()
                .unwrap();
            assert_eq!(
                result.status.code(),
                Some(code),
                "{}",
                String::from_utf8_lossy(&result.stderr)
            );
            assert!(!output.exists());
            if code == 0 {
                let verified = graphite_storage::Container::open(&archive)
                    .unwrap()
                    .verify()
                    .unwrap();
                assert!(verified.ok());
                assert_eq!(verified.digest_file, Some(true));
                let graph = graphite_storage::Graph::load(&archive).unwrap();
                let graphite_storage::NodeKind::StringConstant(value) = graph.node(0).unwrap().kind
                else {
                    panic!("expected string")
                };
                assert_eq!(graph.str(value), expected);
            } else {
                assert_eq!(std::fs::read(&archive).ok(), previous);
            }
        }
    }
    let imported = root.path().join("imported");
    let result = Command::new(env!("CARGO_BIN_EXE_graphite"))
        .args(["import", "input.graphite-ir", "-o"])
        .arg(&imported)
        .env("GRAPHITE_FRONTEND_JVM", &frontend)
        .env("FIXTURE", &fixture)
        .output()
        .unwrap();
    assert!(
        result.status.success(),
        "{}",
        String::from_utf8_lossy(&result.stderr)
    );
    assert!(imported.is_dir());
    assert!(!imported.with_extension("graphite").exists());
    let graph = graphite_storage::Graph::load(&imported).unwrap();
    let graphite_storage::NodeKind::StringConstant(value) = graph.node(0).unwrap().kind else {
        panic!("expected string")
    };
    assert_eq!(graph.str(value), "frontend");
}

#[test]
fn serve_and_mcp_help_name_the_positional_graph_and_explain_default_id() {
    for command in ["serve", "mcp"] {
        let result = Command::new(env!("CARGO_BIN_EXE_graphite"))
            .args([command, "--help"])
            .output()
            .unwrap();
        assert!(result.status.success());
        let help = String::from_utf8(result.stdout).unwrap();
        assert!(help.contains("[GRAPH]"), "{help}");
        assert!(!help.contains("GRAPH_DIR"), "{help}");
        assert!(help.contains("defaults to its basename"), "{help}");
    }
}

#[test]
fn query_help_names_the_graph_argument() {
    let result = Command::new(env!("CARGO_BIN_EXE_graphite"))
        .args(["query", "--help"])
        .output()
        .unwrap();
    assert!(result.status.success());
    let help = String::from_utf8(result.stdout).unwrap();
    assert!(help.contains("<GRAPH>"), "{help}");
    assert!(!help.contains("GRAPH_DIR"), "{help}");
}

#[test]
fn trailing_output_separator_fails_before_frontend_discovery() {
    let root = tempfile::tempdir().unwrap();
    let output = format!("{}/out/", root.path().display());
    for lang in ["jvm", "js", "apple"] {
        let result = Command::new(env!("CARGO_BIN_EXE_graphite"))
            .args(["build", "--lang", lang, "input", "-o", &output])
            .output()
            .unwrap();
        assert_eq!(result.status.code(), Some(2));
        let stderr = String::from_utf8_lossy(&result.stderr);
        assert!(stderr.contains("directory separator"), "{stderr}");
        assert!(stderr.contains("graphite unpack"), "{stderr}");
        assert_eq!(std::fs::read_dir(root.path()).unwrap().count(), 0);
    }
}

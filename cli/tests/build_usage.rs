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
fn shell_build_help_explains_pack_output() {
    let result = Command::new(env!("CARGO_BIN_EXE_graphite"))
        .args(["help", "build"])
        .output()
        .unwrap();
    assert!(result.status.success());
    let help = String::from_utf8(result.stdout).unwrap();
    assert!(help.contains("--pack builds a .graphite archive"));
    assert!(help.contains("appending .graphite to -o when needed"));
}

#[cfg(unix)]
#[test]
fn build_pack_publishes_verified_graph_only_after_frontend_success() {
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
    for (name, code) in [("success.v1", 0), ("failed", 7)] {
        let output = root.path().join(name);
        let result = Command::new(env!("CARGO_BIN_EXE_graphite"))
            .args(["build", "--pack", "app.jar", "-o"])
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
        assert!(!output.exists());
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
            assert_eq!(graph.str(value), "packed");
        } else {
            assert!(!archive.exists());
            assert!(!graphite_storage::container::digest_path(&archive).exists());
        }
    }
}

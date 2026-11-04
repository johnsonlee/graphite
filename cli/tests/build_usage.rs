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
            ["-o", "-h"],
            ["-o", "--version"],
            ["-o", "--unknown"],
            ["-o", "-graphs"],
            ["-o", "-1.graphite"],
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

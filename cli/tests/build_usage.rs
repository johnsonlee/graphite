use std::process::Command;

#[test]
fn invalid_output_is_a_usage_error_with_or_without_fold() {
    for inputs in [
        (
            vec!["-o", "x.graphite", "--output=y.graphite"],
            "specify --output only once",
        ),
        (vec!["--output"], "--output requires a path"),
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

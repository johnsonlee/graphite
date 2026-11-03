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

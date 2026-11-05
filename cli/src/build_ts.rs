//! Explicit JavaScript artifact dispatch and conversion from the frontend JSON interchange.

use crate::build_output::{require_absent, Publication, Stage};
use crate::frontend::{self, Env, Lang, Launch};
use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::process::Command;

/// Remove a single language selector, preserving every other argument byte-for-byte.
pub fn select_language(args: &[OsString]) -> Result<(Lang, Vec<OsString>), String> {
    let Some(opt) = crate::build_args::language_option(args)? else {
        return Ok((Lang::Jvm, args.to_vec()));
    };
    let value = opt
        .value
        .and_then(|v| v.to_str())
        .ok_or("--lang requires jvm, js, ts or es")?;
    Ok((Lang::parse(value)?, opt.without(args)))
}

/// Remove the persisted output option; the frontend receives an interchange path.
fn output_args(args: &[OsString]) -> Result<(PathBuf, Vec<OsString>), String> {
    let opt = crate::build_args::output_option(args)?
        .ok_or("JavaScript artifact builds require -o <directory-or-file.graphite>")?;
    Ok((
        PathBuf::from(opt.value.expect("validated output")),
        opt.without(args),
    ))
}

/// Refuse source/config input before creating output or invoking a project launcher.
/// Let the frontend diagnose option combinations, arity and other file extensions.
fn validate_artifact_input(args: &[OsString]) -> Result<(), String> {
    let mut forwarded = crate::build_args::walk(args);
    if let Some(arg) = forwarded
        .next()
        .filter(|arg| arg.positional && forwarded.next().is_none())
    {
        let path = Path::new(arg.raw);
        if !path.is_dir()
            && path
                .extension()
                .and_then(|e| e.to_str())
                .is_some_and(|ext| {
                    matches!(
                        ext.to_ascii_lowercase().as_str(),
                        "ts" | "tsx" | "mts" | "cts" | "jsx" | "json"
                    )
                })
        {
            return Err(format!("{} is not a compiled JavaScript artifact. Compile the project first, then pass emitted .js/.mjs/.cjs files or its .next/dist/build artifact directory. Graphite does not run project builds.", path.display()));
        }
    }
    Ok(())
}

fn command(env: &Env) -> Result<Command, String> {
    let frontend = frontend::locate_js(env).ok_or_else(frontend::missing_js_message)?;
    if matches!(frontend.launch, Launch::Jar(_)) {
        return Err(
            "JavaScript artifact analysis requires a JavaScript frontend executable or script"
                .into(),
        );
    }
    let mut command = frontend.command(env)?;
    command.arg("build");
    Ok(command)
}

pub fn run(env: &Env, args: &[OsString]) -> i32 {
    match execute(env, args) {
        Ok(code) => code,
        Err(message) => {
            eprintln!("Error: {message}");
            2
        }
    }
}

fn execute(env: &Env, args: &[OsString]) -> Result<i32, String> {
    let mut command = command(env)?;
    // Help and version belong to the frontend and need no graph output.
    if crate::build_args::requests_help_or_version(args) {
        return command
            .args(args)
            .status()
            .map(|s| s.code().unwrap_or(1))
            .map_err(|e| e.to_string());
    }
    let (output, args) = output_args(args)?;
    validate_artifact_input(&args)?;
    let packed = output
        .extension()
        .is_some_and(|e| e == graphite_storage::container::EXTENSION);
    let publication = if packed {
        Some(Publication::inspect(&output)?)
    } else {
        require_absent(&output)?;
        None
    };
    let stage =
        Stage::new(&output).map_err(|e| format!("could not stage {}: {e}", output.display()))?;
    let json = stage.0.join("graph.json");
    // Place our output before `--`, so a positional source beginning with '-' works.
    let status = stage
        .run(command.arg("--output").arg(&json).args(args))
        .map_err(|e| format!("could not start JavaScript artifact frontend: {e}"))?;
    if !status.success() {
        return Ok(status.code().unwrap_or(1));
    }
    let reader =
        std::fs::File::open(&json).map_err(|e| format!("could not read frontend graph: {e}"))?;
    let graph = stage.graph();
    graphite_storage::interchange::import_json(std::io::BufReader::new(reader), &graph)
        .map_err(|e| format!("invalid frontend graph: {e}"))?;
    if let Some(publication) = publication {
        stage.pack(&output, publication)?;
    } else {
        stage.publish_directory(&output)?;
    }
    println!("Saved JavaScript artifact graph to {}", output.display());
    Ok(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn os(args: &[&str]) -> Vec<OsString> {
        args.iter().map(OsString::from).collect()
    }

    #[test]
    fn language_dispatch_is_explicit_and_preserves_jvm_arguments() {
        let jvm = os(&["app.jar", "--include", "--lang", "-o", "out"]);
        assert_eq!(select_language(&jvm).unwrap(), (Lang::Jvm, jvm.clone()));
        assert_eq!(
            select_language(&os(&["--", "--lang", "ts"])).unwrap().0,
            Lang::Jvm
        );
        for selector in [
            os(&["--lang", "js"]),
            os(&["--lang=javascript"]),
            os(&["--lang=es"]),
            os(&["--lang", "ts"]),
            os(&["--lang=typescript"]),
        ] {
            let args = [selector, os(&["src", "-o", "graph"])].concat();
            assert_eq!(
                select_language(&args).unwrap(),
                (Lang::Js, os(&["src", "-o", "graph"]))
            );
        }
        for args in [
            os(&["app/.next/server", "--lang", "js", "-o", "server.graphite"]),
            os(&["app/.next/server", "-o", "server.graphite", "--lang=ts"]),
        ] {
            assert_eq!(
                select_language(&args).unwrap(),
                (Lang::Js, os(&["app/.next/server", "-o", "server.graphite"]))
            );
        }
        assert_eq!(
            select_language(&os(&["--output=--lang", "app.mjs", "--lang=js"])).unwrap(),
            (Lang::Js, os(&["--output=--lang", "app.mjs"]))
        );
        assert!(select_language(&os(&["--lang", "js", "app.js", "--lang=ts"])).is_err());
        assert!(select_language(&os(&["app.js", "--lang", "--", "js"])).is_err());
        assert!(select_language(&os(&["--lang"])).is_err());
        assert!(select_language(&os(&["--lang=python"])).is_err());
    }

    #[test]
    fn output_redirect_preserves_source_and_rejects_ambiguous_outputs() {
        for option in [os(&["-o", "out.graphite"]), os(&["--output=out.graphite"])] {
            let args = [os(&["src"]), option].concat();
            assert_eq!(
                output_args(&args).unwrap(),
                (PathBuf::from("out.graphite"), os(&["src"]))
            );
        }
        assert_eq!(
            output_args(&os(&["-o", "out", "--", "--output=source.ts"])).unwrap(),
            (PathBuf::from("out"), os(&["--", "--output=source.ts"]))
        );
        for args in [
            os(&["src"]),
            os(&["src", "-o"]),
            os(&["src", "--output="]),
            os(&["-o", "a", "-o", "b"]),
        ] {
            assert!(output_args(&args).is_err(), "{args:?}");
        }
    }

    #[test]
    fn source_and_configuration_inputs_require_explicit_compilation() {
        for input in [
            "app.ts",
            "app.tsx",
            "app.mts",
            "app.cts",
            "app.jsx",
            "tsconfig.json",
            "project/tsconfig.production.json",
        ] {
            for args in [os(&[input]), os(&["--", input])] {
                let message = validate_artifact_input(&args).unwrap_err();
                assert!(message.contains("Compile the project first"), "{message}");
                assert!(message.contains(".next/dist/build"), "{message}");
            }
        }
        for input in [
            "app.js",
            "app.mjs",
            "app.cjs",
            ".next",
            "dist",
            "build",
            "out/.next/server/app/page.js",
        ] {
            assert!(validate_artifact_input(&os(&[input])).is_ok(), "{input}");
        }
    }

    #[test]
    fn javascript_entry_point_uses_the_selected_node_runtime() {
        let dir = tempfile::tempdir().unwrap();
        let script = dir.path().join("cli.js");
        std::fs::write(&script, "").unwrap();
        let mut env = Env::default();
        env.vars.insert(
            frontend::TS_FRONTEND_VAR.into(),
            script.clone().into_os_string(),
        );
        env.vars
            .insert(frontend::NODE_VAR.into(), "/runtime/node".into());
        let command = command(&env).unwrap();
        assert_eq!(command.get_program(), "/runtime/node");
        assert_eq!(
            command.get_args().collect::<Vec<_>>(),
            vec![script.as_os_str(), std::ffi::OsStr::new("build")]
        );
    }

    #[cfg(unix)]
    fn fake_frontend(root: &Path, body: &str) -> Env {
        use std::os::unix::fs::PermissionsExt;
        std::fs::create_dir_all(root).unwrap();
        let script = root.join("frontend");
        std::fs::write(&script, format!("#!/bin/sh\n{body}\n")).unwrap();
        std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o700)).unwrap();
        let mut env = Env::default();
        env.vars
            .insert(frontend::TS_FRONTEND_VAR.into(), script.into_os_string());
        env
    }

    #[test]
    fn only_an_unambiguous_source_input_is_preflighted() {
        for args in [
            os(&["--include", "foo.json", "app.js"]),
            os(&["--fold=rules.json", "--", "-app.js"]),
            os(&["--include", "foo.json", "--", "-app.ts"]),
            os(&["app.ts", "other.js"]),
        ] {
            assert!(validate_artifact_input(&args).is_ok());
        }
        assert!(validate_artifact_input(&os(&["--", "-app.ts"])).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn source_rejection_does_not_start_the_frontend_or_create_output() {
        let root = std::env::temp_dir().join(format!(
            "graphite-js-source-rejection-{}",
            std::process::id()
        ));
        let _ = std::fs::remove_dir_all(&root);
        let env = fake_frontend(&root, "exit 37");
        let output = root.join("saved.graphite");
        let args = vec![
            OsString::from("app.tsx"),
            OsString::from("-o"),
            output.clone().into_os_string(),
        ];
        assert!(execute(&env, &args)
            .unwrap_err()
            .contains("Compile the project first"));
        assert!(!output.exists());
        assert_eq!(std::fs::read_dir(&root).unwrap().count(), 1);
        std::fs::remove_dir_all(root).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn frontend_json_is_saved_packed_and_queryable() {
        let root =
            std::env::temp_dir().join(format!("graphite-ts-roundtrip-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let frontend = |value: &str| {
            fake_frontend(
                &root,
                &format!(
                    r#"[ "$1" = build ] && [ "$2" = --output ] || exit 31
cat > "$3" <<'GRAPH'
{{"version":1,"methods":[],"nodes":[{{"id":0,"kind":"StringConstant","value":"{value}"}}],"edges":[]}}
GRAPH"#
                ),
            )
        };
        let assert_value = |output: &Path, expected: &str| {
            let graph = graphite_storage::Graph::load(output).unwrap();
            let executor =
                graphite_cypher::engine::Executor::single("js", std::sync::Arc::new(graph));
            let result = executor
                .execute("MATCH (n:StringConstant) RETURN n.value AS value", None)
                .unwrap();
            assert_eq!(result.rows.len(), 1);
            assert_eq!(result.rows[0]["value"].as_str(), Some(expected));
        };
        for name in ["directory/nested/saved", "packed/nested/saved.graphite"] {
            let output = root.join(name);
            let args = vec![
                OsString::from("src"),
                OsString::from("-o"),
                output.clone().into_os_string(),
            ];
            assert_eq!(execute(&frontend("hello"), &args).unwrap(), 0);
            assert_value(&output, "hello");
            if output.is_dir() {
                assert!(execute(&frontend("ignored"), &args)
                    .unwrap_err()
                    .contains("output already exists"));
                assert_value(&output, "hello");
                continue;
            }
            assert_eq!(execute(&frontend("updated"), &args).unwrap(), 0);
            assert_value(&output, "updated");
            let digest = graphite_storage::container::digest_path(&output);
            let bytes = || {
                (
                    std::fs::read(&output).unwrap(),
                    std::fs::read(&digest).unwrap(),
                )
            };
            let previous = bytes();
            assert_eq!(
                execute(&fake_frontend(&root, "exit 37"), &args).unwrap(),
                37
            );
            assert_eq!(bytes(), previous);
            let bad = fake_frontend(&root, r#"echo '{}' > "$3""#);
            assert!(execute(&bad, &args)
                .unwrap_err()
                .contains("invalid frontend graph"));
            assert_eq!(bytes(), previous);
            assert_value(&output, "updated");
        }
        assert!(
            graphite_storage::Container::open(&root.join("packed/nested/saved.graphite"))
                .unwrap()
                .verify()
                .unwrap()
                .ok()
        );
        assert!(std::fs::read_dir(&root).unwrap().all(|entry| !entry
            .unwrap()
            .file_name()
            .to_string_lossy()
            .starts_with(".graphite-build-")));
        std::fs::remove_dir_all(root).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn existing_sidecars_and_dangling_outputs_are_refused_before_analysis() {
        use std::os::unix::fs::symlink;
        let root = Stage::new(&std::env::temp_dir().join("publish-test")).unwrap();
        let env = fake_frontend(&root.0, "exit 37");
        let output = root.0.join("saved.graphite");
        let digest = graphite_storage::container::digest_path(&output);
        let run = || {
            execute(
                &env,
                &[
                    "input.js".into(),
                    "-o".into(),
                    output.clone().into_os_string(),
                ],
            )
        };
        std::fs::create_dir(&digest).unwrap();
        assert!(run().unwrap_err().contains("not a regular file"));
        assert!(digest.is_dir() && !output.exists());
        std::fs::remove_dir(&digest).unwrap();
        std::fs::write(&digest, "original").unwrap();
        assert!(run().unwrap_err().contains("output already exists"));
        assert_eq!(std::fs::read_to_string(&digest).unwrap(), "original");
        std::fs::remove_file(&digest).unwrap();
        for existing in [&digest, &output] {
            symlink("missing", existing).unwrap();
            assert!(run().unwrap_err().contains("not a regular file"));
            assert_eq!(std::fs::read_link(existing).unwrap(), Path::new("missing"));
            std::fs::remove_file(existing).unwrap();
        }
        assert!(!output.exists() && !digest.exists());
    }

    #[cfg(unix)]
    #[test]
    fn failed_or_invalid_frontend_output_leaves_no_graph_or_staging_files() {
        let root = std::env::temp_dir().join(format!("graphite-ts-failure-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        for name in ["saved", "saved.graphite"] {
            let output = root.join("missing/nested").join(name);
            let args = vec![
                OsString::from("src"),
                OsString::from("-o"),
                output.into_os_string(),
            ];
            let env = fake_frontend(&root, "exit 37");
            assert_eq!(execute(&env, &args).unwrap(), 37);
            assert!(!root.join("missing").exists());
            let env = fake_frontend(
                &root,
                r#"echo '{"version":99,"methods":[],"nodes":[],"edges":[]}' > "$3""#,
            );
            assert!(execute(&env, &args)
                .unwrap_err()
                .contains("unsupported version 99"));
            assert!(!root.join("missing").exists());
            assert_eq!(std::fs::read_dir(&root).unwrap().count(), 1);
        }
        std::fs::remove_dir_all(root).unwrap();
    }
}

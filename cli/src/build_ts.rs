//! Explicit TypeScript dispatch and conversion from the frontend JSON interchange.

use crate::frontend::{self, Env, Launch};
use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::atomic::{AtomicU64, Ordering};

/// Only a leading language selector belongs to the shell. Preserve JVM option values,
/// positional filenames and the `--` separator byte-for-byte.
pub fn select_language(args: &[OsString]) -> Result<(&'static str, Vec<OsString>), String> {
    let Some(first) = args.first() else {
        return Ok(("jvm", Vec::new()));
    };
    let (value, consumed) = if first == "--lang" {
        (
            args.get(1)
                .and_then(|s| s.to_str())
                .ok_or("--lang requires jvm or ts")?,
            2,
        )
    } else if let Some(value) = first.to_str().and_then(|s| s.strip_prefix("--lang=")) {
        (value, 1)
    } else {
        return Ok(("jvm", args.to_vec()));
    };
    let lang = match value {
        "jvm" => "jvm",
        "ts" | "typescript" => "ts",
        _ => return Err(format!("unknown frontend '{value}'; available: jvm, ts")),
    };
    Ok((lang, args[consumed..].to_vec()))
}

/// Remove the persisted output option; the frontend receives an interchange path.
fn output_args(args: &[OsString]) -> Result<(PathBuf, Vec<OsString>), String> {
    let mut output = None;
    let mut forwarded = Vec::new();
    let mut args = args.iter();
    while let Some(arg) = args.next() {
        if arg == "--" {
            forwarded.push(arg.clone());
            forwarded.extend(args.cloned());
            break;
        }
        let value = if arg == "-o" || arg == "--output" {
            Some(PathBuf::from(
                args.next().ok_or("--output requires a path")?,
            ))
        } else {
            arg.to_str()
                .and_then(|s| s.strip_prefix("--output="))
                .map(PathBuf::from)
        };
        if let Some(path) = value {
            if output.is_some() {
                return Err("specify --output only once".into());
            }
            if path.as_os_str().is_empty() {
                return Err("--output requires a path".into());
            }
            output = Some(path);
        } else {
            forwarded.push(arg.clone());
        }
    }
    Ok((
        output.ok_or("TypeScript builds require -o <directory-or-file.graphite>")?,
        forwarded,
    ))
}

fn command(env: &Env) -> Result<Command, String> {
    let frontend = frontend::locate_ts(env).ok_or_else(frontend::missing_ts_message)?;
    let mut command = match frontend.launch {
        Launch::Node(script) => {
            let mut command = Command::new(frontend::locate_node(env)?);
            command.arg(script);
            command
        }
        Launch::Executable(exe) => Command::new(exe),
        Launch::Jar(_) => return Err("TypeScript requires a Node.js frontend".into()),
    };
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
    if matches!(args, [arg] if arg == "--help" || arg == "-h" || arg == "--version" || arg == "-V")
    {
        return command
            .args(args)
            .status()
            .map(|s| s.code().unwrap_or(1))
            .map_err(|e| e.to_string());
    }
    let (output, args) = output_args(args)?;
    if output.exists() {
        return Err(format!("output already exists: {}", output.display()));
    }
    let stage =
        Stage::new(&output).map_err(|e| format!("could not stage {}: {e}", output.display()))?;
    let json = stage.0.join("graph.json");
    // Place our output before `--`, so a positional source beginning with '-' works.
    let status = command
        .arg("--output")
        .arg(&json)
        .args(args)
        .status()
        .map_err(|e| format!("could not start TypeScript frontend: {e}"))?;
    if !status.success() {
        return Ok(status.code().unwrap_or(1));
    }
    let reader =
        std::fs::File::open(&json).map_err(|e| format!("could not read frontend graph: {e}"))?;
    let graph = stage.0.join("saved");
    graphite_storage::interchange::import_json(reader, &graph)
        .map_err(|e| format!("invalid frontend graph: {e}"))?;
    if output
        .extension()
        .is_some_and(|e| e == graphite_storage::container::EXTENSION)
    {
        graphite_storage::container::pack(&graph, &output)
            .map_err(|e| format!("could not pack graph: {e}"))?;
    } else {
        std::fs::rename(&graph, &output).map_err(|e| format!("could not save graph: {e}"))?;
    }
    println!("Saved TypeScript graph to {}", output.display());
    Ok(0)
}

struct Stage(PathBuf);
impl Stage {
    fn new(output: &Path) -> std::io::Result<Self> {
        static COUNTER: AtomicU64 = AtomicU64::new(0);
        let parent = output
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .unwrap_or(Path::new("."));
        std::fs::create_dir_all(parent)?;
        loop {
            let name = format!(
                ".graphite-ts-{}-{}",
                std::process::id(),
                COUNTER.fetch_add(1, Ordering::Relaxed)
            );
            let path = parent.join(name);
            match std::fs::create_dir(&path) {
                Ok(()) => return Ok(Self(path)),
                Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(e) => return Err(e),
            }
        }
    }
}
impl Drop for Stage {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
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
        assert_eq!(select_language(&jvm).unwrap(), ("jvm", jvm.clone()));
        assert_eq!(
            select_language(&os(&["--", "--lang", "ts"])).unwrap().0,
            "jvm"
        );
        for selector in [os(&["--lang", "ts"]), os(&["--lang=typescript"])] {
            let args = [selector, os(&["src", "-o", "graph"])].concat();
            assert_eq!(
                select_language(&args).unwrap(),
                ("ts", os(&["src", "-o", "graph"]))
            );
        }
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
    fn javascript_entry_point_uses_the_selected_node_runtime() {
        let mut env = Env::default();
        env.vars
            .insert(frontend::TS_FRONTEND_VAR.into(), "/frontend/cli.js".into());
        env.vars
            .insert(frontend::NODE_VAR.into(), "/runtime/node".into());
        let command = command(&env).unwrap();
        assert_eq!(command.get_program(), "/runtime/node");
        assert_eq!(
            command.get_args().collect::<Vec<_>>(),
            vec!["/frontend/cli.js", "build"]
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

    #[cfg(unix)]
    #[test]
    fn frontend_json_is_saved_packed_and_queryable() {
        let root =
            std::env::temp_dir().join(format!("graphite-ts-roundtrip-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let env = fake_frontend(
            &root,
            r#"[ "$1" = build ] && [ "$2" = --output ] || exit 31
cat > "$3" <<'GRAPH'
{"version":1,"methods":[],"nodes":[{"id":0,"kind":"StringConstant","value":"hello"}],"edges":[]}
GRAPH"#,
        );
        for name in ["saved", "saved.graphite"] {
            let output = root.join(name);
            let args = vec![
                OsString::from("src"),
                OsString::from("-o"),
                output.clone().into_os_string(),
            ];
            assert_eq!(execute(&env, &args).unwrap(), 0);
            let graph = graphite_storage::Graph::load(&output).unwrap();
            let executor =
                graphite_cypher::engine::Executor::single("ts", std::sync::Arc::new(graph));
            let result = executor
                .execute("MATCH (n:StringConstant) RETURN n.value AS value", None)
                .unwrap();
            assert_eq!(result.rows.len(), 1);
            assert_eq!(result.rows[0]["value"].as_str(), Some("hello"));
            // An accidental retry must leave the previous complete graph intact.
            assert!(execute(&env, &args)
                .unwrap_err()
                .contains("output already exists"));
        }
        assert!(
            graphite_storage::Container::open(&root.join("saved.graphite"))
                .unwrap()
                .verify()
                .unwrap()
                .ok()
        );
        assert!(std::fs::read_dir(&root).unwrap().all(|entry| !entry
            .unwrap()
            .file_name()
            .to_string_lossy()
            .starts_with(".graphite-ts-")));
        std::fs::remove_dir_all(root).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn failed_or_invalid_frontend_output_leaves_no_graph_or_staging_files() {
        let root = std::env::temp_dir().join(format!("graphite-ts-failure-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let output = root.join("saved.graphite");
        let args = vec![
            OsString::from("src"),
            OsString::from("-o"),
            output.clone().into_os_string(),
        ];
        let env = fake_frontend(&root, "exit 37");
        assert_eq!(execute(&env, &args).unwrap(), 37);
        assert!(!output.exists());
        let env = fake_frontend(&root, r#"echo '{"version":99}' > "$3""#);
        assert!(execute(&env, &args)
            .unwrap_err()
            .contains("invalid frontend graph"));
        assert!(!output.exists());
        assert_eq!(std::fs::read_dir(&root).unwrap().count(), 1);
        std::fs::remove_dir_all(root).unwrap();
    }
}

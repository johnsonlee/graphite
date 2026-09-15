//! `graphite build`: a shell over the JVM frontend.
//!
//! Every argument after `build` goes to the frontend untouched, `--help` included, so the
//! command line a user has today for `graphite.jar build` keeps working. The one argument
//! the shell interprets is `--profile`, which the Homebrew wrapper around the jar used to
//! turn into an async-profiler agent; it does the same here.
//!
//! One output form is the shell's too: when `-o` names a `.graphite` file, the frontend
//! writes a staging directory next to it (the frontend only ever writes directories) and
//! the shell packs that into the file afterwards, so every frontend produces the single
//! file without knowing about it.

use crate::frontend::{self, Env, Frontend, Launch};
use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::process::Command;

/// The output `-o` named a `.graphite` file: the frontend writes `stage`, and the
/// shell packs it into `output` when the frontend succeeds.
#[derive(Debug, PartialEq, Eq)]
pub struct Pack {
    pub stage: PathBuf,
    pub output: PathBuf,
}

/// A fully resolved frontend invocation: program, arguments and the environment
/// variables to add. Built separately from running it so tests can look at it.
#[derive(Debug, PartialEq, Eq)]
pub struct Invocation {
    pub program: PathBuf,
    pub args: Vec<OsString>,
    pub env: Vec<(String, OsString)>,
    pub pack: Option<Pack>,
}

/// The staging directory for a `.graphite` output: a sibling, so the pack's rename
/// stays on one filesystem, named after the output and this process.
pub fn stage_dir_for(output: &Path) -> PathBuf {
    let name = output.file_name().unwrap_or_default().to_string_lossy();
    output.with_file_name(format!("{name}.build-{}", std::process::id()))
}

/// Rewrite a `.graphite` output in the frontend's arguments to its staging directory.
/// `-o X`, `--output X` and `--output=X` are the forms picocli accepts for the option.
fn redirect_output(args: &mut [OsString]) -> Option<Pack> {
    let mut index = 0;
    while index < args.len() {
        let arg = args[index].to_string_lossy().into_owned();
        let (slot, value, prefix) = if arg == "-o" || arg == "--output" {
            let value = args.get(index + 1)?;
            (index + 1, PathBuf::from(value), String::new())
        } else if let Some(value) = arg.strip_prefix("--output=") {
            (index, PathBuf::from(value), "--output=".to_string())
        } else {
            index += 1;
            continue;
        };
        if value.extension().and_then(|e| e.to_str())
            != Some(graphite_storage::container::EXTENSION)
        {
            return None;
        }
        let stage = stage_dir_for(&value);
        let mut rewritten = OsString::from(prefix);
        rewritten.push(stage.as_os_str());
        args[slot] = rewritten;
        return Some(Pack {
            stage,
            output: value,
        });
    }
    None
}

impl Invocation {
    pub fn command(&self) -> Command {
        let mut cmd = Command::new(&self.program);
        cmd.args(&self.args);
        for (k, v) in &self.env {
            cmd.env(k, v);
        }
        cmd
    }
}

/// What to run for `graphite build <args>` with the frontend `fe`.
pub fn invocation(env: &Env, fe: &Frontend, args: &[OsString]) -> Result<Invocation, String> {
    let mut passthrough = Vec::with_capacity(args.len());
    let mut profile = false;
    for arg in args {
        if arg == "--profile" {
            profile = true;
        } else {
            passthrough.push(arg.clone());
        }
    }
    let pack = redirect_output(&mut passthrough);
    match &fe.launch {
        Launch::Executable(exe) => {
            if profile {
                return Err(
                    "--profile applies to the jar frontend only; this frontend is a launcher"
                        .into(),
                );
            }
            let mut argv = vec![OsString::from("build")];
            argv.extend(passthrough);
            Ok(Invocation {
                program: exe.clone(),
                args: argv,
                env: Vec::new(),
                pack,
            })
        }
        Launch::Jar(jar) => {
            let java = frontend::locate_java(env)?;
            let mut argv = Vec::new();
            if profile {
                argv.push(frontend::profiler_agent_arg(env)?);
            }
            argv.push(OsString::from("-jar"));
            argv.push(jar.as_os_str().to_os_string());
            argv.push(OsString::from("build"));
            argv.extend(passthrough);
            let mut vars = Vec::new();
            if let Some(opts) = frontend::default_java_tool_options(env) {
                vars.push(("JAVA_TOOL_OPTIONS".to_string(), opts));
            }
            Ok(Invocation {
                program: java,
                args: argv,
                env: vars,
                pack,
            })
        }
    }
}

/// The message when no frontend is installed. Exit code 2, as for unsupported input.
pub fn missing_frontend_message() -> String {
    format!(
        "No JVM frontend found. `graphite build` runs the JVM frontend (graphite.jar) to \
         analyse JAR/WAR/APK inputs.\n\
         Install it with `graphite frontend install jvm`, or set {} to a graphite.jar.",
        frontend::JVM_FRONTEND_VAR
    )
}

/// Run `graphite build` and return the exit code to use.
pub fn run(env: &Env, args: &[OsString]) -> i32 {
    let Some(fe) = frontend::locate_jvm(env) else {
        eprintln!("{}", missing_frontend_message());
        return 2;
    };
    let inv = match invocation(env, &fe, args) {
        Ok(inv) => inv,
        Err(message) => {
            eprintln!("Error: {message}");
            return 1;
        }
    };
    let code = match inv.command().status() {
        Ok(status) => status.code().unwrap_or(1),
        Err(e) => {
            eprintln!("Error: could not run {}: {e}", inv.program.display());
            1
        }
    };
    match inv.pack {
        Some(pack) => finish_pack(pack, code),
        None => code,
    }
}

/// Pack the staging directory into the output once the frontend has succeeded, and
/// remove the staging directory either way: the file is the only result.
fn finish_pack(pack: Pack, code: i32) -> i32 {
    let outcome = if code == 0 {
        match graphite_storage::container::pack(&pack.stage, &pack.output) {
            Ok(report) => {
                println!(
                    "Packed {} entries ({} bytes) into {}\nfingerprint: {}\nsha256: {} (written to {})",
                    report.entries,
                    report.bytes,
                    pack.output.display(),
                    report.fingerprint,
                    report.file_sha256,
                    report.digest_file.display()
                );
                0
            }
            Err(e) => {
                eprintln!("Error: could not pack {}: {e}", pack.output.display());
                1
            }
        }
    } else {
        code
    };
    if pack.stage.exists() {
        if let Err(e) = std::fs::remove_dir_all(&pack.stage) {
            eprintln!(
                "Warning: could not remove the staging directory {}: {e}",
                pack.stage.display()
            );
        }
    }
    outcome
}

#[cfg(test)]
mod tests {
    use super::*;

    fn env_with(vars: &[(&str, &str)]) -> Env {
        let mut env = Env::default();
        for (k, v) in vars {
            env.vars.insert((*k).to_string(), OsString::from(v));
        }
        env
    }

    fn jar_frontend() -> Frontend {
        Frontend {
            lang: "jvm",
            launch: Launch::Jar(PathBuf::from("/opt/graphite/graphite.jar")),
            found_via: "test",
        }
    }

    fn os(args: &[&str]) -> Vec<OsString> {
        args.iter().map(OsString::from).collect()
    }

    #[test]
    fn jar_invocation_passes_every_argument_through_after_build() {
        let env = env_with(&[("GRAPHITE_JAVA", "/usr/bin/java")]);
        let args = os(&[
            "app.jar",
            "-o",
            "/tmp/g",
            "--include",
            "com.example",
            "--help",
        ]);
        let inv = invocation(&env, &jar_frontend(), &args).unwrap();
        assert_eq!(inv.program, Path::new("/usr/bin/java"));
        assert_eq!(
            inv.args,
            os(&[
                "-jar",
                "/opt/graphite/graphite.jar",
                "build",
                "app.jar",
                "-o",
                "/tmp/g",
                "--include",
                "com.example",
                "--help"
            ])
        );
        assert_eq!(
            inv.env,
            vec![("JAVA_TOOL_OPTIONS".to_string(), OsString::from("-Xmx8g"))]
        );
    }

    #[test]
    fn a_graphite_output_is_staged_in_a_sibling_directory_and_packed() {
        let env = env_with(&[("GRAPHITE_JAVA", "/usr/bin/java")]);
        let stage = stage_dir_for(Path::new("/tmp/out/app.graphite"));
        assert_eq!(stage.parent(), Some(Path::new("/tmp/out")));
        assert!(stage
            .file_name()
            .unwrap()
            .to_string_lossy()
            .starts_with("app.graphite.build-"));
        for form in [
            os(&["a.jar", "-o", "/tmp/out/app.graphite"]),
            os(&["a.jar", "--output", "/tmp/out/app.graphite"]),
            os(&["a.jar", "--output=/tmp/out/app.graphite"]),
        ] {
            let inv = invocation(&env, &jar_frontend(), &form).unwrap();
            assert_eq!(
                inv.pack,
                Some(Pack {
                    stage: stage.clone(),
                    output: PathBuf::from("/tmp/out/app.graphite")
                })
            );
            let joined = inv
                .args
                .iter()
                .map(|a| a.to_string_lossy().into_owned())
                .collect::<Vec<_>>()
                .join(" ");
            assert!(joined.contains(&stage.display().to_string()), "{joined}");
            assert!(!joined.contains("app.graphite "), "{joined}");
        }
        // A directory output, or no output at all, is passed through untouched.
        let inv = invocation(&env, &jar_frontend(), &os(&["a.jar", "-o", "/tmp/g"])).unwrap();
        assert_eq!(inv.pack, None);
        assert!(inv.args.contains(&OsString::from("/tmp/g")));
        assert_eq!(
            invocation(&env, &jar_frontend(), &os(&["a.jar", "-o"]))
                .unwrap()
                .pack,
            None
        );
    }

    #[test]
    fn finish_pack_packs_on_success_and_only_cleans_up_on_failure() {
        let root =
            std::env::temp_dir().join(format!("graphite-finish-pack-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let output = root.join("app.graphite");
        let stage = stage_dir_for(&output);
        let write_graph = |stage: &Path| {
            std::fs::create_dir_all(stage).unwrap();
            for name in graphite_storage::container::REQUIRED_ENTRIES {
                std::fs::write(stage.join(name), name.as_bytes()).unwrap();
            }
        };
        write_graph(&stage);
        assert_eq!(
            finish_pack(
                Pack {
                    stage: stage.clone(),
                    output: output.clone()
                },
                3
            ),
            3
        );
        assert!(!stage.exists());
        assert!(!output.exists());

        write_graph(&stage);
        assert_eq!(
            finish_pack(
                Pack {
                    stage: stage.clone(),
                    output: output.clone()
                },
                0
            ),
            0
        );
        assert!(!stage.exists());
        let v = graphite_storage::Container::open(&output)
            .unwrap()
            .verify()
            .unwrap();
        assert!(v.ok());
        assert_eq!(v.digest_file, Some(true));
        assert!(root.join("app.graphite.sha256").exists());

        // An empty staging directory cannot be packed: the error is reported, nothing is left.
        std::fs::create_dir_all(&stage).unwrap();
        assert_eq!(
            finish_pack(
                Pack {
                    stage: stage.clone(),
                    output: root.join("empty.graphite")
                },
                0
            ),
            1
        );
        assert!(!stage.exists());
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn caller_heap_settings_are_respected() {
        let env = env_with(&[
            ("GRAPHITE_JAVA", "/usr/bin/java"),
            ("JAVA_TOOL_OPTIONS", "-Xmx1g"),
        ]);
        let inv = invocation(&env, &jar_frontend(), &os(&["a.jar", "-o", "g"])).unwrap();
        assert!(inv.env.is_empty());
    }

    #[test]
    fn profile_is_consumed_by_the_shell_and_becomes_an_agent() {
        let root =
            std::env::temp_dir().join(format!("graphite-build-profile-{}", std::process::id()));
        std::fs::create_dir_all(root.join("bin")).unwrap();
        std::fs::create_dir_all(root.join("lib")).unwrap();
        std::fs::write(root.join("bin/asprof"), b"").unwrap();
        std::fs::write(root.join("lib/libasyncProfiler.so"), b"").unwrap();
        let mut env = env_with(&[("GRAPHITE_JAVA", "/usr/bin/java")]);
        env.vars
            .insert("PATH".into(), root.join("bin").into_os_string());
        let inv = invocation(
            &env,
            &jar_frontend(),
            &os(&["--profile", "a.jar", "-o", "g"]),
        )
        .unwrap();
        assert!(inv.args[0].to_string_lossy().starts_with("-agentpath:"));
        assert_eq!(
            &inv.args[1..],
            &os(&[
                "-jar",
                "/opt/graphite/graphite.jar",
                "build",
                "a.jar",
                "-o",
                "g"
            ])[..]
        );
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn profile_without_async_profiler_is_the_wrapper_error() {
        let env = env_with(&[("GRAPHITE_JAVA", "/usr/bin/java"), ("PATH", "/nonexistent")]);
        let err = invocation(&env, &jar_frontend(), &os(&["--profile", "a.jar"])).unwrap_err();
        assert_eq!(
            err,
            "async-profiler not found. Install: brew install async-profiler"
        );
    }

    #[test]
    fn launcher_frontend_runs_directly_without_java() {
        let env = Env::default();
        let fe = Frontend {
            lang: "jvm",
            launch: Launch::Executable(PathBuf::from("/usr/local/bin/graphite-frontend-jvm")),
            found_via: "PATH",
        };
        let inv = invocation(&env, &fe, &os(&["a.jar", "-o", "g"])).unwrap();
        assert_eq!(
            inv.program,
            Path::new("/usr/local/bin/graphite-frontend-jvm")
        );
        assert_eq!(inv.args, os(&["build", "a.jar", "-o", "g"]));
        assert!(inv.env.is_empty());
        assert!(invocation(&env, &fe, &os(&["--profile"])).is_err());
    }

    #[test]
    fn missing_java_is_reported_for_a_jar_frontend() {
        let env = env_with(&[("PATH", "/nonexistent")]);
        let err = invocation(&env, &jar_frontend(), &os(&["a.jar"])).unwrap_err();
        assert!(err.contains("java not found"));
    }

    #[test]
    fn run_without_a_frontend_exits_two_with_the_install_hint() {
        let env = env_with(&[("PATH", "/nonexistent")]);
        assert_eq!(run(&env, &os(&["a.jar"])), 2);
        assert!(missing_frontend_message().contains("graphite frontend install jvm"));
    }
}

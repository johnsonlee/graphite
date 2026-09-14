//! `graphite build`: a shell over the JVM frontend.
//!
//! Every argument after `build` goes to the frontend untouched, `--help` included, so the
//! command line a user has today for `graphite.jar build` keeps working. The one argument
//! the shell interprets is `--profile`, which the Homebrew wrapper around the jar used to
//! turn into an async-profiler agent; it does the same here.

use crate::frontend::{self, Env, Frontend, Launch};
use std::ffi::OsString;
use std::path::PathBuf;
use std::process::Command;

/// A fully resolved frontend invocation: program, arguments and the environment
/// variables to add. Built separately from running it so tests can look at it.
#[derive(Debug, PartialEq, Eq)]
pub struct Invocation {
    pub program: PathBuf,
    pub args: Vec<OsString>,
    pub env: Vec<(String, OsString)>,
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
    match inv.command().status() {
        Ok(status) => status.code().unwrap_or(1),
        Err(e) => {
            eprintln!("Error: could not run {}: {e}", inv.program.display());
            1
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;

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

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
//!
//! A `--fold` file is the shell's to read as well: when it holds `select` rules, the shell
//! builds once without rules, resolves every query on that graph, and builds again with
//! the selected call sites (`fold.rs`); a file of `match` rules alone goes straight to
//! the frontend, one build as before.

#[path = "build_jvm_options.rs"]
mod jvm_options;

use crate::build_output::{Publication, Stage};
use crate::fold;
use crate::frontend::{self, Env, Frontend, Launch};
use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::process::Command;

/// The output `-o` named a `.graphite` file: the frontend writes `stage`, and the
/// shell packs it into `output` when the frontend succeeds.
#[derive(Debug, PartialEq, Eq)]
pub struct Pack {
    pub stage: Stage,
    pub output: PathBuf,
}

/// A fully resolved frontend invocation: program, arguments and the environment
/// variables to add. Built separately from running it so tests can look at it.
#[derive(Debug, PartialEq, Eq)]
pub struct Invocation {
    pub program: PathBuf,
    pub args: Vec<OsString>,
    pub env: Vec<(String, OsString)>,
    frontend_args_start: usize,
}

/// Rewrite a `.graphite` output in the frontend's arguments to its staging directory.
/// `-o X`, `--output X` and `--output=X` are the forms picocli accepts for the option.
fn redirect_output(args: &mut [OsString]) -> Result<Option<Pack>, String> {
    let Some(opt) = crate::build_args::output_option(args)? else {
        return Ok(None);
    };
    let output = PathBuf::from(opt.value.expect("validated output"));
    if output.extension().and_then(|e| e.to_str()) != Some(graphite_storage::container::EXTENSION) {
        return Ok(None);
    }
    let stage =
        Stage::new(&output).map_err(|e| format!("could not stage {}: {e}", output.display()))?;
    let (slot, replacement) = opt.replacement(&stage.graph());
    args[slot] = replacement;
    Ok(Some(Pack { stage, output }))
}

impl Invocation {
    fn prepare_pack(&mut self) -> Result<Option<Pack>, String> {
        let args = &mut self.args[self.frontend_args_start..];
        if crate::build_args::requests_help_or_version(args) {
            return Ok(None);
        }
        redirect_output(args)
    }

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
    let mut start = 0;
    for option in crate::build_args::walk(args).filter(|option| option.flag("--profile")) {
        passthrough.extend_from_slice(&args[start..option.range.start]);
        start = option.range.end;
        profile = true;
    }
    passthrough.extend_from_slice(&args[start..]);
    match &fe.launch {
        Launch::Executable(exe) => {
            if profile {
                return Err(
                    "--profile applies to the jar frontend only; this frontend is a launcher"
                        .into(),
                );
            }
            let mut argv = vec![OsString::from("build")];
            let frontend_args_start = argv.len();
            argv.extend(passthrough);
            Ok(Invocation {
                program: exe.clone(),
                args: argv,
                env: Vec::new(),
                frontend_args_start,
            })
        }
        Launch::Node(_) => {
            Err("JavaScript artifact builds use the graph interchange adapter".into())
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
            let frontend_args_start = argv.len();
            argv.extend(passthrough);
            let mut vars = Vec::new();
            if let Some(opts) = frontend::default_java_tool_options(env) {
                vars.push(("JAVA_TOOL_OPTIONS".to_string(), opts));
            }
            Ok(Invocation {
                program: java,
                args: argv,
                env: vars,
                frontend_args_start,
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
    let (lang, args) = match crate::build_ts::select_language(args) {
        Ok(selected) => selected,
        Err(message) => {
            eprintln!("Error: {message}");
            return 2;
        }
    };
    if lang == frontend::Lang::Js {
        return crate::build_ts::run(env, &args);
    }
    run_jvm(env, &args)
}

fn run_jvm(env: &Env, args: &[OsString]) -> i32 {
    // Diagnose shell-owned output options before frontend discovery or any fold pass.
    let output = if crate::build_args::requests_help_or_version(args) {
        None
    } else {
        match fold::output_of(args) {
            Ok(output) => output,
            Err(message) => {
                eprintln!("Error: {message}");
                return 2;
            }
        }
    };
    let Some(fe) = frontend::locate_jvm(env) else {
        eprintln!("{}", missing_frontend_message());
        return 2;
    };
    // Resolve a supported default once; a two-pass fold reuses this environment.
    // Explicit user options and executable frontends bypass capability detection.
    let build_env = match jvm_options::build_env(env, &fe, args) {
        Ok(env) => env,
        Err(error) => {
            eprintln!("Error: could not finish the JVM capability check: {error}");
            return 1;
        }
    };
    let env = build_env.as_ref();
    // A fold file with `select` rules needs a graph to run them on: two builds. An output
    // the frontend would reject anyway (none named) goes straight to it for the message.
    if let (Some(file), Some(output)) = (fold::fold_file(args), output) {
        let input = fold::input_of(args);
        let analysis = fold::analysis_options(args);
        let plan = match fold::plan(env, &fe, &file, input.as_deref(), &analysis) {
            Ok(plan) => plan,
            Err(code) => return code,
        };
        let selects = fold::selects(&plan);
        if !selects.is_empty() {
            return run_with_selects(env, &fe, args, &output, plan, &selects);
        }
    }
    run_frontend(env, &fe, args)
}

/// One frontend build with `args`, packed when the output is a `.graphite` file.
fn run_frontend(env: &Env, fe: &Frontend, args: &[OsString]) -> i32 {
    let mut inv = match invocation(env, fe, args) {
        Ok(inv) => inv,
        Err(message) => {
            eprintln!("Error: {message}");
            return 1;
        }
    };
    let pack = match inv.prepare_pack() {
        Ok(pack) => pack,
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
    match pack {
        Some(pack) => finish_pack(pack, code),
        None => code,
    }
}

/// Build without rules into a staging directory, resolve every `select` rule on that
/// graph, write the resolved plan, and build again with it into `output`. The staging
/// directory goes either way; the second build's output is the only result.
fn run_with_selects(
    env: &Env,
    fe: &Frontend,
    args: &[OsString],
    output: &Path,
    plan: serde_json::Value,
    selects: &[(usize, String)],
) -> i32 {
    let stage = fold::unfolded_dir_for(output);
    let code = run_two_passes(env, fe, args, &stage, plan, selects);
    if stage.exists() {
        if let Err(e) = std::fs::remove_dir_all(&stage) {
            eprintln!(
                "Warning: could not remove the unfolded graph {}: {e}",
                stage.display()
            );
        }
    }
    code
}

fn run_two_passes(
    env: &Env,
    fe: &Frontend,
    args: &[OsString],
    stage: &Path,
    plan: serde_json::Value,
    selects: &[(usize, String)],
) -> i32 {
    eprintln!(
        "Building the graph without rules to resolve {} select rule(s)",
        selects.len()
    );
    let staged = match fold::staging_args(args, stage) {
        Ok(args) => args,
        Err(message) => {
            eprintln!("Error: {message}");
            return 2;
        }
    };
    let first = run_frontend(env, fe, &staged);
    if first != 0 {
        return first;
    }
    let resolved = match resolve(stage, plan, selects) {
        Ok(resolved) => resolved,
        Err(message) => {
            eprintln!("Error: {message}");
            return 1;
        }
    };
    let resolved_path = stage.join(fold::RESOLVED_PLAN);
    if let Err(e) = std::fs::write(&resolved_path, resolved.to_string()) {
        eprintln!("Error: could not write {}: {e}", resolved_path.display());
        return 1;
    }
    eprintln!("Building the graph with the selected call sites folded");
    run_frontend(env, fe, &fold::with_fold_file(args, &resolved_path))
}

/// Run the `select` queries on the graph in `stage` and fill their call sites into the
/// plan. The graph is dropped before this returns, so the directory can go.
fn resolve(
    stage: &Path,
    plan: serde_json::Value,
    selects: &[(usize, String)],
) -> Result<serde_json::Value, String> {
    let graph = graphite_storage::Graph::load(stage)
        .map_err(|e| format!("could not load the unfolded graph {}: {e}", stage.display()))?;
    let executor =
        graphite_cypher::engine::Executor::single("unfolded", std::sync::Arc::new(graph));
    let mut selections = Vec::with_capacity(selects.len());
    for (index, query) in selects {
        let keys = fold::select_sites(&executor, *index, query)?;
        eprintln!("select rule {index}: {} call site(s) selected", keys.len());
        selections.push((*index, keys));
    }
    Ok(fold::resolve_plan(plan, &selections))
}

/// Pack the staging directory into the output once the frontend has succeeded, and
/// remove the staging directory either way: the file is the only result.
fn finish_pack(pack: Pack, code: i32) -> i32 {
    if code != 0 {
        return code;
    }
    match pack.stage.pack(&pack.output, Publication::Replace) {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("Error: {e}");
            1
        }
    }
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
        let root = Stage::new(&std::env::temp_dir().join("jvm-build-test")).unwrap();
        let output = root.0.join("app.graphite");
        for mut form in [
            os(&["a.jar", "-o"]),
            os(&["a.jar", "--output"]),
            os(&["a.jar"]),
        ] {
            if form.len() == 1 {
                let mut arg = OsString::from("--output=");
                arg.push(&output);
                form.push(arg);
            } else {
                form.push(output.clone().into_os_string());
            }
            let mut inv = invocation(&env, &jar_frontend(), &form).unwrap();
            let pack = inv.prepare_pack().unwrap().unwrap();
            assert_eq!(pack.output, output);
            assert_eq!(pack.stage.0.parent(), Some(root.0.as_path()));
            let stage = pack.stage.graph();
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
        let mut inv = invocation(&env, &jar_frontend(), &os(&["a.jar", "-o", "/tmp/g"])).unwrap();
        assert_eq!(inv.prepare_pack().unwrap(), None);
        assert!(inv.args.contains(&OsString::from("/tmp/g")));
        assert_eq!(
            invocation(&env, &jar_frontend(), &os(&["a.jar", "-o"]))
                .unwrap()
                .prepare_pack()
                .unwrap_err(),
            "--output requires a path"
        );
    }

    #[test]
    fn help_and_failed_frontend_leave_missing_output_parent_absent() {
        let temp = Stage::new(&std::env::temp_dir().join("jvm-output-test")).unwrap();
        let parent = temp.0.join("new").join("nested");
        let output = parent.join("app.graphite");
        let mut args = os(&["missing.jar", "-o"]);
        args.push(output.into_os_string());
        let mut inv = invocation(
            &env_with(&[("GRAPHITE_JAVA", "/usr/bin/java")]),
            &jar_frontend(),
            &args,
        )
        .unwrap();
        assert!(!parent.exists());
        let pack = inv.prepare_pack().unwrap().unwrap();
        assert_eq!(pack.stage.0.parent(), Some(std::env::temp_dir().as_path()));
        assert_eq!(finish_pack(pack, 3), 3);
        assert!(std::fs::read_dir(&temp.0).unwrap().next().is_none());
        args.insert(0, "--help".into());
        let mut help = invocation(
            &env_with(&[("GRAPHITE_JAVA", "/usr/bin/java")]),
            &jar_frontend(),
            &args,
        )
        .unwrap();
        assert!(help.prepare_pack().unwrap().is_none());
        assert_eq!(help.args.last(), args.last());
        assert!(std::fs::read_dir(&temp.0).unwrap().next().is_none());
    }

    #[test]
    fn finish_pack_packs_on_success_and_only_cleans_up_on_failure() {
        let root =
            std::env::temp_dir().join(format!("graphite-finish-pack-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let output = root.join("app.graphite");
        let stage = Stage::new(&output).unwrap();
        let path = stage.0.clone();
        assert_eq!(
            finish_pack(
                Pack {
                    stage,
                    output: output.clone()
                },
                3
            ),
            3
        );
        assert!(!path.exists());
        assert!(!root.exists());

        std::fs::create_dir(&root).unwrap(); // An existing JVM output should be replaced.
        let stage = Stage::new(&output).unwrap();
        let path = stage.0.clone();
        std::fs::create_dir(stage.graph()).unwrap();
        for name in graphite_storage::container::REQUIRED_ENTRIES {
            std::fs::write(stage.graph().join(name), name.as_bytes()).unwrap();
        }
        // JVM builds historically allow replacing the destination.
        std::fs::write(&output, b"previous").unwrap();
        assert_eq!(
            finish_pack(
                Pack {
                    stage,
                    output: output.clone()
                },
                0
            ),
            0
        );
        assert!(!path.exists());
        let v = graphite_storage::Container::open(&output)
            .unwrap()
            .verify()
            .unwrap();
        assert!(v.ok());
        assert_eq!(v.digest_file, Some(true));
        assert!(root.join("app.graphite.sha256").exists());

        // An invalid saved graph must not create the final parent, even after frontend success.
        let missing = root.join("missing");
        let output = missing.join("nested/empty.graphite");
        let stage = Stage::new(&output).unwrap();
        let path = stage.0.clone();
        std::fs::create_dir(stage.graph()).unwrap();
        assert_eq!(finish_pack(Pack { stage, output }, 0), 1);
        assert!(!path.exists());
        assert!(!missing.exists());
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

//! `graphite build` and `graphite import`: shells over the frontends.
//!
//! `build` picks a frontend by `--lang`, by `--frontend <executable>`, or by the input:
//! a `Package.swift` (or a directory holding one), an `.xcodeproj` or `.xcworkspace` is
//! the Apple frontend's; everything else is the JVM frontend's, as before.
//!
//! For the JVM frontend every argument after `build` goes to the jar untouched, `--help`
//! included, so the command line a user has today for `graphite.jar build` keeps
//! working. The one argument the shell interprets is `--profile`, which the Homebrew
//! wrapper around the jar used to turn into an async-profiler agent; it does the same
//! here.
//!
//! For the Apple frontend the shell speaks the frontend protocol: `describe` (the IR
//! schema must be one this CLI reads), `build --out <ir>` with the input as `--package`
//! and the other arguments passed through, then `import` of that IR through the jar into
//! the requested output. `import` on its own is the same shell over `graphite.jar
//! import`, which persists a Graph IR any frontend wrote with the writer `build` uses.
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

/// What to run for `graphite <subcommand> <args>` (`build` or `import`) with the
/// frontend `fe`.
pub fn invocation_for(
    env: &Env,
    fe: &Frontend,
    subcommand: &str,
    args: &[OsString],
) -> Result<Invocation, String> {
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
            let mut argv = vec![OsString::from(subcommand)];
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
            argv.push(OsString::from(subcommand));
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

/// The message when the frontend for `lang` is not installed. Exit code 2, as for
/// unsupported input.
pub fn missing_frontend_message_for(lang: &str) -> String {
    match lang {
        "apple" => format!(
            "No Apple frontend found. `graphite build` on a Swift package or an Xcode project \
             runs the Apple frontend ({}) to index it.\n\
             Install it with `graphite frontend install apple`, or set {} to the executable.",
            frontend::APPLE_FRONTEND_EXE,
            frontend::APPLE_FRONTEND_VAR
        ),
        _ => missing_jvm_message("build"),
    }
}

fn missing_jvm_message(subcommand: &str) -> String {
    let purpose = match subcommand {
        "import" => "persist a Graph IR written by another frontend",
        _ => "analyse JAR/WAR/APK inputs",
    };
    format!(
        "No JVM frontend found. `graphite {subcommand}` runs the JVM frontend (graphite.jar) to \
         {purpose}.\n\
         Install it with `graphite frontend install jvm`, or set {} to a graphite.jar.",
        frontend::JVM_FRONTEND_VAR
    )
}

/// Which frontend a `graphite build` command line is for, and the executable when
/// `--frontend` names one.
#[derive(Debug, PartialEq, Eq)]
pub struct Choice {
    pub lang: &'static str,
    pub frontend: Option<PathBuf>,
}

/// Choose the frontend: `--lang`, then `--frontend`, then the first input argument.
pub fn choose(args: &[OsString]) -> Result<Choice, String> {
    let mut lang: Option<&'static str> = None;
    let mut explicit: Option<PathBuf> = None;
    let mut input: Option<PathBuf> = None;
    let mut index = 0;
    while index < args.len() {
        let arg = args[index].to_string_lossy().into_owned();
        if arg == "--lang" || arg == "--frontend" {
            let Some(value) = args.get(index + 1) else {
                return Err(format!("{arg} needs a value"));
            };
            if arg == "--lang" {
                lang = Some(parse_lang(&value.to_string_lossy())?);
            } else {
                explicit = Some(PathBuf::from(value));
            }
            index += 2;
            continue;
        }
        if let Some(value) = arg.strip_prefix("--lang=") {
            lang = Some(parse_lang(value)?);
        } else if let Some(value) = arg.strip_prefix("--frontend=") {
            explicit = Some(PathBuf::from(value));
        } else if arg == "-o" || arg == "--output" || VALUE_OPTIONS.contains(&arg.as_str()) {
            index += 2;
            continue;
        } else if !arg.starts_with('-') && input.is_none() {
            input = Some(PathBuf::from(&args[index]));
        }
        index += 1;
    }
    let lang = match (lang, &explicit) {
        (Some(l), _) => l,
        (None, Some(_)) => "apple",
        (None, None) => input.as_deref().map(lang_of_input).unwrap_or("jvm"),
    };
    Ok(Choice {
        lang,
        frontend: explicit,
    })
}

fn parse_lang(value: &str) -> Result<&'static str, String> {
    frontend::canonical_lang(value).ok_or_else(|| {
        format!(
            "unknown --lang '{value}'; available: {}",
            frontend::LANGS.join(", ")
        )
    })
}

/// The frontend an input belongs to when nothing says otherwise.
pub fn lang_of_input(input: &Path) -> &'static str {
    let name = input
        .file_name()
        .map(|n| n.to_string_lossy())
        .unwrap_or_default();
    if name == "Package.swift" || input.join("Package.swift").is_file() {
        return "apple";
    }
    if is_xcode_project(input) {
        return "apple";
    }
    "jvm"
}

/// An `.xcodeproj` or `.xcworkspace` path (a trailing slash allowed).
fn is_xcode_project(input: &Path) -> bool {
    matches!(
        input.extension().and_then(|e| e.to_str()),
        Some("xcodeproj") | Some("xcworkspace")
    )
}

/// Apple frontend options that take a value, so their value is never taken for the
/// input (`--sources` takes several, up to the next option).
const VALUE_OPTIONS: [&str; 8] = [
    "--package",
    "--project",
    "--scheme",
    "--destination",
    "--derived-data",
    "--index-store",
    "--configuration",
    "--sources",
];

/// A `graphite build` for the Apple frontend, split into what the shell needs and what
/// the frontend gets.
#[derive(Debug, PartialEq, Eq)]
pub struct AppleBuild {
    /// The positional input, passed as `--package` or, for an `.xcodeproj` or
    /// `.xcworkspace`, as `--project`.
    pub input: Option<PathBuf>,
    pub output: PathBuf,
    pub allow_partial: bool,
    /// Every other argument, in order.
    pub passthrough: Vec<OsString>,
}

/// Split the arguments of an Apple build. `-o` is required: the shell, not the frontend,
/// writes the graph.
pub fn split_apple_args(args: &[OsString]) -> Result<AppleBuild, String> {
    let mut input = None;
    let mut output = None;
    let mut allow_partial = false;
    let mut passthrough = Vec::new();
    let mut index = 0;
    while index < args.len() {
        let arg = args[index].to_string_lossy().into_owned();
        match arg.as_str() {
            "-o" | "--output" | "--lang" | "--frontend" => {
                let Some(value) = args.get(index + 1) else {
                    return Err(format!("{arg} needs a value"));
                };
                if arg == "-o" || arg == "--output" {
                    output = Some(PathBuf::from(value));
                }
                index += 2;
                continue;
            }
            "--allow-partial" => allow_partial = true,
            "--out" => return Err("--out is the frontend's; give the graph output with -o".into()),
            _ if arg.starts_with("--output=") => {
                output = Some(PathBuf::from(&arg["--output=".len()..]));
            }
            _ if arg.starts_with("--lang=") || arg.starts_with("--frontend=") => {}
            _ if arg == "--sources" => {
                passthrough.push(args[index].clone());
                index += 1;
                while index < args.len() && !args[index].to_string_lossy().starts_with('-') {
                    passthrough.push(args[index].clone());
                    index += 1;
                }
                continue;
            }
            _ if VALUE_OPTIONS.contains(&arg.as_str()) => {
                passthrough.push(args[index].clone());
                if let Some(value) = args.get(index + 1) {
                    passthrough.push(value.clone());
                }
                index += 2;
                continue;
            }
            _ if !arg.starts_with('-') && input.is_none() => {
                input = Some(PathBuf::from(&args[index]));
            }
            _ => passthrough.push(args[index].clone()),
        }
        index += 1;
    }
    let output = output
        .ok_or("the Apple frontend needs an output: -o <graph directory or .graphite file>")?;
    Ok(AppleBuild {
        input,
        output,
        allow_partial,
        passthrough,
    })
}

/// The IR file an Apple build writes before `import`: next to the output, named after it
/// and this process, removed once imported.
pub fn ir_path_for(output: &Path) -> PathBuf {
    let name = output.file_name().unwrap_or_default().to_string_lossy();
    output.with_file_name(format!("{name}.ir-{}.graphite-ir", std::process::id()))
}

/// The frontend's `build` command line for an Apple build.
pub fn apple_invocation(fe: &Frontend, build: &AppleBuild, ir: &Path) -> Invocation {
    let mut argv = vec![OsString::from("build"), OsString::from("--out"), ir.into()];
    if let Some(input) = &build.input {
        if is_xcode_project(input) {
            argv.push(OsString::from("--project"));
            argv.push(input.clone().into());
        } else {
            let root = if input.file_name().is_some_and(|n| n == "Package.swift") {
                input.parent().map(Path::to_path_buf).unwrap_or_default()
            } else {
                input.clone()
            };
            argv.push(OsString::from("--package"));
            argv.push(root.into());
        }
    }
    argv.extend(build.passthrough.iter().cloned());
    Invocation {
        program: fe.path().to_path_buf(),
        args: argv,
        env: Vec::new(),
        pack: None,
    }
}

/// Run an Apple build: describe, build the IR, import it. Returns the exit code.
fn run_apple(env: &Env, choice: &Choice, args: &[OsString]) -> i32 {
    let fe = match &choice.frontend {
        Some(exe) => Frontend {
            lang: "apple",
            launch: Launch::Executable(exe.clone()),
            found_via: "--frontend",
        },
        None => match frontend::locate_apple(env) {
            Some(fe) => fe,
            None => {
                eprintln!("{}", missing_frontend_message_for("apple"));
                return 2;
            }
        },
    };
    let build = match split_apple_args(args) {
        Ok(b) => b,
        Err(message) => {
            eprintln!("Error: {message}");
            return 1;
        }
    };
    let described =
        frontend::own_description(&fe).and_then(|own| frontend::ir_schema_supported(&own));
    if let Err(message) = described {
        eprintln!("Error: {message}");
        return 2;
    }
    let ir = ir_path_for(&build.output);
    let inv = apple_invocation(&fe, &build, &ir);
    eprintln!("Running {} build", fe.path().display());
    let code = match inv.command().status() {
        Ok(status) => status.code().unwrap_or(1),
        Err(e) => {
            eprintln!("Error: could not run {}: {e}", inv.program.display());
            return 1;
        }
    };
    let proceed = match code {
        0 => true,
        3 if build.allow_partial => {
            eprintln!("Warning: the frontend wrote a partial graph; importing it because of --allow-partial");
            true
        }
        3 => {
            eprintln!("Error: the frontend wrote a partial graph; pass --allow-partial to import it anyway");
            false
        }
        2 => {
            eprintln!("Error: the frontend does not support this input");
            false
        }
        _ => false,
    };
    if !proceed {
        let _ = std::fs::remove_file(&ir);
        return code;
    }
    let import_args = [
        ir.as_os_str().to_os_string(),
        OsString::from("-o"),
        build.output.into(),
    ];
    let code = run_subcommand(env, "import", &import_args);
    let _ = std::fs::remove_file(&ir);
    code
}

/// Run `graphite build` and return the exit code to use.
pub fn run(env: &Env, args: &[OsString]) -> i32 {
    let choice = match choose(args) {
        Ok(c) => c,
        Err(message) => {
            eprintln!("Error: {message}");
            return 1;
        }
    };
    match choice.lang {
        "apple" => run_apple(env, &choice, args),
        _ => run_subcommand(env, "build", args),
    }
}

/// Run `graphite import <ir> -o <output>` (the jar's `import`) and return the exit code.
pub fn run_import(env: &Env, args: &[OsString]) -> i32 {
    run_subcommand(env, "import", args)
}

fn run_subcommand(env: &Env, subcommand: &str, args: &[OsString]) -> i32 {
    let Some(fe) = frontend::locate_jvm(env) else {
        eprintln!("{}", missing_jvm_message(subcommand));
        return 2;
    };
    let inv = match invocation_for(env, &fe, subcommand, args) {
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

    fn invocation(env: &Env, fe: &Frontend, args: &[OsString]) -> Result<Invocation, String> {
        invocation_for(env, fe, "build", args)
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
        let inv = invocation_for(&env, &jar_frontend(), "build", &args).unwrap();
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
    fn import_invocation_runs_the_jar_import_with_the_same_shell() {
        let env = env_with(&[("GRAPHITE_JAVA", "/usr/bin/java")]);
        let args = os(&["app.graphite-ir", "-o", "/tmp/app.graphite"]);
        let inv = invocation_for(&env, &jar_frontend(), "import", &args).unwrap();
        assert_eq!(inv.program, Path::new("/usr/bin/java"));
        let stage = stage_dir_for(Path::new("/tmp/app.graphite"));
        assert_eq!(
            inv.args,
            os(&[
                "-jar",
                "/opt/graphite/graphite.jar",
                "import",
                "app.graphite-ir",
                "-o",
                stage.to_str().unwrap()
            ])
        );
        assert_eq!(
            inv.pack,
            Some(Pack {
                stage,
                output: PathBuf::from("/tmp/app.graphite")
            })
        );
        let launcher = Frontend {
            lang: "jvm",
            launch: Launch::Executable(PathBuf::from("/opt/graphite/graphite-frontend-jvm")),
            found_via: "test",
        };
        let inv = invocation_for(
            &env,
            &launcher,
            "import",
            &os(&["app.graphite-ir", "-o", "/tmp/g"]),
        )
        .unwrap();
        assert_eq!(inv.args, os(&["import", "app.graphite-ir", "-o", "/tmp/g"]));
        assert!(missing_jvm_message("import").contains("`graphite import`"));
        assert!(missing_jvm_message("import").contains("persist a Graph IR"));
        assert!(missing_frontend_message_for("jvm").contains("graphite.jar"));
        assert!(missing_frontend_message_for("apple").contains("frontend install apple"));
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
            let inv = invocation_for(&env, &jar_frontend(), "build", &form).unwrap();
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
        assert!(invocation_for(&env, &fe, "build", &os(&["--profile"])).is_err());
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
        assert!(missing_frontend_message_for("jvm").contains("graphite frontend install jvm"));
    }

    #[test]
    fn the_frontend_is_chosen_by_lang_then_frontend_then_input() {
        let root = std::env::temp_dir().join(format!("graphite-choose-{}", std::process::id()));
        std::fs::create_dir_all(root.join("pkg")).unwrap();
        std::fs::write(
            root.join("pkg/Package.swift"),
            b"// swift-tools-version:5.10",
        )
        .unwrap();
        let pkg = root.join("pkg").to_string_lossy().into_owned();
        let manifest = root
            .join("pkg/Package.swift")
            .to_string_lossy()
            .into_owned();

        assert_eq!(
            choose(&os(&["app.jar", "-o", "g"])).unwrap(),
            Choice {
                lang: "jvm",
                frontend: None
            }
        );
        assert_eq!(choose(&os(&[])).unwrap().lang, "jvm");
        assert_eq!(choose(&os(&["--help"])).unwrap().lang, "jvm");
        assert_eq!(choose(&os(&[&pkg, "-o", "g"])).unwrap().lang, "apple");
        assert_eq!(choose(&os(&[&manifest, "-o", "g"])).unwrap().lang, "apple");
        assert_eq!(
            choose(&os(&["App.xcodeproj", "-o", "g"])).unwrap().lang,
            "apple"
        );
        assert_eq!(choose(&os(&["App.xcworkspace"])).unwrap().lang, "apple");
        assert_eq!(
            choose(&os(&["--lang", "swift", "-o", "g"])).unwrap().lang,
            "apple"
        );
        assert_eq!(choose(&os(&["--lang=jvm", &pkg])).unwrap().lang, "jvm");
        // -o's value and a frontend option's value are never the input.
        assert_eq!(choose(&os(&["-o", &pkg])).unwrap().lang, "jvm");
        assert_eq!(
            choose(&os(&["--package", &pkg, "-o", "g"])).unwrap().lang,
            "jvm"
        );
        assert_eq!(
            choose(&os(&[
                "--frontend",
                "/opt/fe",
                "--index-store",
                "s",
                "-o",
                "g"
            ]))
            .unwrap(),
            Choice {
                lang: "apple",
                frontend: Some(PathBuf::from("/opt/fe"))
            }
        );
        assert_eq!(
            choose(&os(&["--frontend=/opt/fe"])).unwrap().frontend,
            Some(PathBuf::from("/opt/fe"))
        );
        assert!(choose(&os(&["--lang", "web"]))
            .unwrap_err()
            .contains("unknown --lang"));
        assert!(choose(&os(&["--lang"]))
            .unwrap_err()
            .contains("needs a value"));
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn apple_arguments_are_split_into_input_output_and_passthrough() {
        let build = split_apple_args(&os(&[
            "--lang",
            "apple",
            "MyApp",
            "-o",
            "/tmp/my.graphite",
            "--configuration",
            "release",
            "--sources",
            "a",
            "b",
            "--skip-build",
            "--allow-partial",
        ]))
        .unwrap();
        assert_eq!(
            build,
            AppleBuild {
                input: Some(PathBuf::from("MyApp")),
                output: PathBuf::from("/tmp/my.graphite"),
                allow_partial: true,
                passthrough: os(&[
                    "--configuration",
                    "release",
                    "--sources",
                    "a",
                    "b",
                    "--skip-build"
                ]),
            }
        );
        let build = split_apple_args(&os(&[
            "--index-store",
            "/s",
            "--sources",
            "/src",
            "--output=/g",
            "--frontend=/fe",
        ]))
        .unwrap();
        assert_eq!(build.input, None);
        assert_eq!(build.output, PathBuf::from("/g"));
        assert_eq!(
            build.passthrough,
            os(&["--index-store", "/s", "--sources", "/src"])
        );
        assert!(split_apple_args(&os(&["MyApp"]))
            .unwrap_err()
            .contains("-o"));
        assert!(split_apple_args(&os(&["MyApp", "-o"]))
            .unwrap_err()
            .contains("needs a value"));
        assert!(split_apple_args(&os(&["MyApp", "--out", "x"]))
            .unwrap_err()
            .contains("--out is the frontend's"));

        let fe = Frontend {
            lang: "apple",
            launch: Launch::Executable(PathBuf::from("/opt/graphite-frontend-apple")),
            found_via: "test",
        };
        let ir = ir_path_for(Path::new("/tmp/my.graphite"));
        assert!(ir.to_string_lossy().starts_with("/tmp/my.graphite.ir-"));
        assert!(ir.to_string_lossy().ends_with(".graphite-ir"));
        let build = split_apple_args(&os(&[
            "MyApp/Package.swift",
            "-o",
            "/tmp/my.graphite",
            "--skip-build",
        ]))
        .unwrap();
        let inv = apple_invocation(&fe, &build, &ir);
        assert_eq!(inv.program, Path::new("/opt/graphite-frontend-apple"));
        let mut expected = os(&["build", "--out"]);
        expected.push(ir.as_os_str().to_os_string());
        expected.extend(os(&["--package", "MyApp", "--skip-build"]));
        assert_eq!(inv.args, expected);
        assert_eq!(inv.pack, None);
        let build = split_apple_args(&os(&["--index-store", "/s", "-o", "/g"])).unwrap();
        let inv = apple_invocation(&fe, &build, &ir);
        assert!(!inv.args.iter().any(|a| a == "--package"));
        assert_eq!(lang_of_input(Path::new("lib.aar")), "jvm");

        // An Xcode project or workspace is passed as --project, with its build options.
        let build = split_apple_args(&os(&[
            "ios/Acme.xcworkspace",
            "--scheme",
            "Acme",
            "--destination",
            "generic/platform=iOS Simulator",
            "--derived-data",
            "/dd",
            "-o",
            "/tmp/acme.graphite",
        ]))
        .unwrap();
        assert_eq!(build.input, Some(PathBuf::from("ios/Acme.xcworkspace")));
        let inv = apple_invocation(&fe, &build, &ir);
        let mut expected = os(&["build", "--out"]);
        expected.push(ir.as_os_str().to_os_string());
        expected.extend(os(&[
            "--project",
            "ios/Acme.xcworkspace",
            "--scheme",
            "Acme",
            "--destination",
            "generic/platform=iOS Simulator",
            "--derived-data",
            "/dd",
        ]));
        assert_eq!(inv.args, expected);
        assert_eq!(lang_of_input(Path::new("Acme.xcodeproj/")), "apple");
        assert_eq!(
            choose(&os(&["--scheme", "Acme", "Acme.xcodeproj", "-o", "g"]))
                .unwrap()
                .lang,
            "apple"
        );
    }

    /// The whole Apple path with a stub frontend and a stub jar: describe is checked,
    /// the IR goes to import, the IR file is removed, exit codes pass through.
    #[cfg(unix)]
    #[test]
    fn an_apple_build_describes_builds_and_imports() {
        use std::os::unix::fs::PermissionsExt;
        let root =
            std::env::temp_dir().join(format!("graphite-apple-build-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let fe = root.join("fe.sh");
        let java = root.join("java.sh");
        let log = root.join("java.log");
        // The stub frontend: describe reports schema 1; build writes its --out and exits
        // with GRAPHITE_STUB_EXIT.
        std::fs::write(
            &fe,
            "#!/bin/sh\nif [ \"$1\" = describe ]; then echo \"{\\\"ir_schema\\\": [${GRAPHITE_STUB_SCHEMA:-1}]}\"; exit 0; fi\n\
             shift; while [ $# -gt 0 ]; do if [ \"$1\" = --out ]; then echo ir > \"$2\"; fi; shift; done\n\
             exit ${GRAPHITE_STUB_EXIT:-0}\n",
        )
        .unwrap();
        std::fs::write(
            &java,
            format!("#!/bin/sh\necho \"$@\" >> {}\n", log.display()),
        )
        .unwrap();
        for f in [&fe, &java] {
            std::fs::set_permissions(f, std::fs::Permissions::from_mode(0o755)).unwrap();
        }
        let jar = root.join("graphite.jar");
        std::fs::write(&jar, b"jar").unwrap();
        let env = env_with(&[
            ("GRAPHITE_JAVA", java.to_str().unwrap()),
            ("GRAPHITE_FRONTEND_JVM", jar.to_str().unwrap()),
            ("GRAPHITE_FRONTEND_APPLE", fe.to_str().unwrap()),
        ]);
        let output = root.join("out-graph");
        let args = os(&[
            "--lang",
            "apple",
            "MyApp",
            "-o",
            output.to_str().unwrap(),
            "--skip-build",
        ]);

        assert_eq!(run(&env, &args), 0);
        let logged = std::fs::read_to_string(&log).unwrap();
        assert!(logged.contains("import"), "{logged}");
        assert!(logged.contains(".graphite-ir -o"), "{logged}");
        assert!(logged.contains(output.to_str().unwrap()), "{logged}");
        assert!(!std::fs::read_dir(&root).unwrap().any(|e| e
            .unwrap()
            .file_name()
            .to_string_lossy()
            .contains(".graphite-ir")));

        // Exit codes: unsupported input, partial without and with --allow-partial.
        std::env::set_var("GRAPHITE_STUB_EXIT", "2");
        assert_eq!(run(&env, &args), 2);
        std::env::set_var("GRAPHITE_STUB_EXIT", "3");
        assert_eq!(run(&env, &args), 3);
        let mut partial = args.clone();
        partial.push(OsString::from("--allow-partial"));
        assert_eq!(run(&env, &partial), 0);
        std::env::set_var("GRAPHITE_STUB_EXIT", "0");
        // A schema this CLI does not read is refused before building.
        std::env::set_var("GRAPHITE_STUB_SCHEMA", "7");
        assert_eq!(run(&env, &args), 2);
        std::env::remove_var("GRAPHITE_STUB_SCHEMA");
        std::env::remove_var("GRAPHITE_STUB_EXIT");
        // No output, no frontend, an explicit --frontend that cannot run.
        assert_eq!(run(&env, &os(&["--lang", "apple", "MyApp"])), 1);
        let no_fe = env_with(&[("GRAPHITE_JAVA", java.to_str().unwrap())]);
        assert_eq!(run(&no_fe, &os(&["--lang", "apple", "-o", "g"])), 2);
        assert_eq!(
            run(&env, &os(&["--frontend", "/nonexistent/fe", "-o", "g"])),
            2
        );
        assert_eq!(run(&env, &os(&["--lang", "web"])), 1);
        std::fs::remove_dir_all(root).unwrap();
    }
}

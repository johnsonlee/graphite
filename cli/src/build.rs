//! `graphite build` and `graphite import`: shells over the frontends.
//!
//! The shell dispatches JVM artifacts, explicitly selected JavaScript artifacts,
//! and Swift packages or Xcode apps. An app directory resolves to its workspace or project.
//! Apple frontends write protobuf IR, which the JVM importer persists.
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
    publication: Publication,
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
    let publication = Publication::inspect(&output)?;
    let stage =
        Stage::new(&output).map_err(|e| format!("could not stage {}: {e}", output.display()))?;
    let (slot, replacement) = opt.replacement(&stage.graph());
    args[slot] = replacement;
    Ok(Some(Pack {
        stage,
        output,
        publication,
    }))
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
            let mut argv = vec![OsString::from(subcommand)];
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
            argv.push(OsString::from(subcommand));
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
        "js" => frontend::missing_js_message(),
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
fn select_frontend(args: &[OsString]) -> Result<(Choice, Vec<OsString>), (i32, String)> {
    let (lang, forwarded) = crate::build_ts::select_language(args).map_err(|e| (2, e))?;
    let explicit_language = forwarded.len() != args.len();
    let option = crate::build_args::frontend_option(&forwarded).map_err(|e| (2, e))?;
    let frontend = option
        .as_ref()
        .map(|opt| PathBuf::from(opt.value.expect("validated frontend")));
    let lang = if explicit_language {
        lang.name()
    } else if frontend.is_some() {
        "apple"
    } else {
        match crate::build_args::walk(&forwarded).find(|opt| opt.positional) {
            Some(input)
                if resolve_apple_input(Path::new(input.raw))
                    .map_err(|e| (1, e))?
                    .is_some() =>
            {
                "apple"
            }
            _ => "jvm",
        }
    };
    if frontend.is_some() && lang == "js" {
        return Err((
            2,
            "--frontend requires a JVM or Apple frontend, not a JavaScript interchange adapter"
                .into(),
        ));
    }
    let forwarded = option
        .map(|opt| opt.without(&forwarded))
        .unwrap_or(forwarded);
    Ok((Choice { lang, frontend }, forwarded))
}

/// Resolve the positional Apple input for both frontend selection and invocation.
/// An app directory can contain a workspace and its projects: open the workspace,
/// just as a developer would, without picking projects out of subdirectories.
fn resolve_apple_input(input: &Path) -> Result<Option<PathBuf>, String> {
    if is_xcode_project(input)
        || input
            .file_name()
            .is_some_and(|name| name == "Package.swift")
        || input.join("Package.swift").is_file()
    {
        return Ok(Some(input.to_owned()));
    }
    if !input.is_dir() {
        return Ok(None);
    }
    let read_error = |e| format!("could not inspect build input {}: {e}", input.display());
    let mut workspaces = Vec::new();
    let mut projects = Vec::new();
    for entry in std::fs::read_dir(input).map_err(read_error)? {
        let path = entry.map_err(read_error)?.path();
        match path.extension().and_then(|ext| ext.to_str()) {
            Some("xcworkspace") if path.is_dir() => workspaces.push(path),
            Some("xcodeproj") if path.is_dir() => projects.push(path),
            _ => {}
        }
    }
    let mut candidates = if workspaces.is_empty() {
        projects
    } else {
        workspaces
    };
    if candidates.len() <= 1 {
        return Ok(candidates.pop());
    }
    candidates.sort();
    Err(format!(
        "multiple Xcode inputs in {}; pass one of these paths to `graphite build`:\n  {}",
        input.display(),
        candidates
            .iter()
            .map(|p| p.display().to_string())
            .collect::<Vec<_>>()
            .join("\n  ")
    ))
}

/// An `.xcodeproj` or `.xcworkspace` path (a trailing slash allowed).
fn is_xcode_project(input: &Path) -> bool {
    matches!(
        input.extension().and_then(|e| e.to_str()),
        Some("xcodeproj") | Some("xcworkspace")
    )
}

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
    let output = crate::build_args::output_option(args)?;
    let mut input = None;
    let mut allow_partial = false;
    let mut passthrough = Vec::new();
    let mut sources = false;
    let mut end = 0;
    for arg in crate::build_args::walk(args) {
        if arg.range.start != end || !arg.positional {
            sources = false;
        }
        end = arg.range.end;
        if arg.flag("--allow-partial") {
            allow_partial = true;
        } else if arg.flag("--out")
            || (!arg.positional && arg.raw.as_encoded_bytes().starts_with(b"--out="))
        {
            return Err("--out is the frontend's; give the graph output with -o".into());
        } else if matches!(arg.name, Some("--lang" | "--frontend" | "-o" | "--output")) {
            continue;
        } else if arg.positional && !sources && input.is_none() {
            let path = Path::new(arg.raw);
            input = Some(resolve_apple_input(path)?.unwrap_or_else(|| path.to_owned()));
        } else {
            sources |= arg.name == Some("--sources");
            passthrough.extend_from_slice(&args[arg.range]);
        }
    }
    Ok(AppleBuild {
        input,
        output: PathBuf::from(
            output
                .ok_or(
                    "the Apple frontend needs an output: -o <graph directory or .graphite file>",
                )?
                .value
                .expect("validated output"),
        ),
        allow_partial,
        passthrough,
    })
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
        frontend_args_start: 1,
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
    let packed = build
        .output
        .extension()
        .is_some_and(|e| e == graphite_storage::container::EXTENSION);
    let publication = if packed {
        Publication::inspect(&build.output).map(Some)
    } else {
        // publish_directory only creates: an existing directory is refused, not rebuilt.
        match crate::build_output::DirectoryPublication::inspect(&build.output) {
            Ok(crate::build_output::DirectoryPublication::CreateNew) => Ok(None),
            Ok(crate::build_output::DirectoryPublication::Rebuild(_)) => {
                Err(format!("output already exists: {}", build.output.display()))
            }
            Err(message) => Err(message),
        }
    };
    let publication = match publication {
        Ok(p) => p,
        Err(message) => {
            eprintln!("Error: {message}");
            return 1;
        }
    };
    let stage = match Stage::new(&build.output) {
        Ok(s) => s,
        Err(error) => {
            eprintln!("Error: could not stage {}: {error}", build.output.display());
            return 1;
        }
    };
    let ir = stage.0.join("frontend.graphite-ir");
    let inv = apple_invocation(&fe, &build, &ir);
    eprintln!("Running {} build", fe.path().display());
    let code = match stage.run(&mut inv.command()) {
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
        return code;
    }
    let import_args = [
        ir.as_os_str().to_os_string(),
        OsString::from("-o"),
        stage.graph().into(),
    ];
    let Some(importer) = frontend::locate_jvm(env) else {
        eprintln!("{}", missing_jvm_message("import"));
        return 2;
    };
    let inv = match invocation_for(env, &importer, "import", &import_args) {
        Ok(inv) => inv,
        Err(message) => {
            eprintln!("Error: {message}");
            return 1;
        }
    };
    match stage.run(&mut inv.command()) {
        Ok(status) if !status.success() => return status.code().unwrap_or(1),
        Err(error) => {
            eprintln!("Error: could not run {}: {error}", inv.program.display());
            return 1;
        }
        Ok(_) => {}
    }
    let published = match publication {
        Some(publication) => stage.pack(&build.output, publication),
        None => stage.publish_directory(&build.output),
    };
    match published {
        Ok(()) => 0,
        Err(message) => {
            eprintln!("Error: {message}");
            1
        }
    }
}

/// Run `graphite build` and return the exit code to use.
pub fn run(env: &Env, args: &[OsString]) -> i32 {
    let args = match crate::build_args::packed_output_args(args) {
        Ok(args) => args,
        Err(message) => {
            eprintln!("Error: {message}");
            return 2;
        }
    };
    let args = args.as_slice();
    // Validate the shell's output before interpreting any flag that follows it
    // or attempting frontend discovery. Joined values can start with a dash.
    let output = match fold::output_of(args) {
        Ok(output) => output,
        Err(message) => {
            eprintln!("Error: {message}");
            return 2;
        }
    };
    let (choice, args) = match select_frontend(args) {
        Ok(selected) => selected,
        Err((code, message)) => {
            eprintln!("Error: {message}");
            return code;
        }
    };
    match choice.lang {
        "js" => crate::build_ts::run(env, &args),
        "apple" => run_apple(env, &choice, &args),
        _ => {
            if let Some(path) = choice.frontend {
                let mut env = env.clone();
                env.vars
                    .insert(frontend::JVM_FRONTEND_VAR.into(), path.into());
                run_subcommand(&env, "build", &args, output)
            } else {
                run_subcommand(env, "build", &args, output)
            }
        }
    }
}

pub fn run_import(env: &Env, args: &[OsString]) -> i32 {
    match fold::output_of(args) {
        Ok(output) => run_subcommand(env, "import", args, output),
        Err(message) => {
            eprintln!("Error: {message}");
            2
        }
    }
}

fn run_subcommand(env: &Env, subcommand: &str, args: &[OsString], output: Option<PathBuf>) -> i32 {
    let output = output
        .filter(|_| subcommand == "build" && !crate::build_args::requests_help_or_version(args));
    let Some(fe) = frontend::locate_jvm(env) else {
        eprintln!("{}", missing_jvm_message(subcommand));
        return 2;
    };
    // Resolve a supported default once; a two-pass fold reuses this environment.
    // Explicit user options and executable frontends bypass capability detection.
    // The default is the build's: `import` keeps the environment it was given.
    let build_env = if subcommand == "build" {
        match jvm_options::build_env(env, &fe, args) {
            Ok(env) => env,
            Err(error) => {
                eprintln!("Error: could not finish the JVM capability check: {error}");
                return 1;
            }
        }
    } else {
        std::borrow::Cow::Borrowed(env)
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
    run_frontend(env, &fe, subcommand, args)
}

/// One frontend run of `subcommand` with `args`, packed when the output is a `.graphite` file.
fn run_frontend(env: &Env, fe: &Frontend, subcommand: &str, args: &[OsString]) -> i32 {
    let mut inv = match invocation_for(env, fe, subcommand, args) {
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
    let mut command = inv.command();
    let status = match &pack {
        Some(pack) => pack.stage.run(&mut command),
        None => command.status(),
    };
    let code = match status {
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
    let first = run_frontend(env, fe, "build", &staged);
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
    run_frontend(
        env,
        fe,
        "build",
        &fold::with_fold_file(args, &resolved_path),
    )
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
    match pack.stage.pack(&pack.output, pack.publication) {
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

    fn choose(args: &[OsString]) -> Result<Choice, String> {
        super::select_frontend(args)
            .map(|(choice, _)| choice)
            .map_err(|(_, message)| message)
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
        let mut inv = invocation_for(&env, &jar_frontend(), "import", &args).unwrap();
        assert_eq!(inv.program, Path::new("/usr/bin/java"));
        let pack = inv.prepare_pack().unwrap().unwrap();
        assert_eq!(pack.output, Path::new("/tmp/app.graphite"));
        assert_eq!(
            &inv.args[..5],
            os(&[
                "-jar",
                "/opt/graphite/graphite.jar",
                "import",
                "app.graphite-ir",
                "-o"
            ])
        );
        assert_eq!(Path::new(&inv.args[5]), pack.stage.graph());
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
            "--output requires a path (use --output=-name for a path starting with '-')"
        );
    }

    #[test]
    fn help_and_failed_frontend_leave_missing_output_parent_absent() {
        let temp = tempfile::tempdir().unwrap();
        let parent = temp.path().join("new").join("nested");
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
        assert!(std::fs::read_dir(temp.path()).unwrap().next().is_none());
        args.insert(0, "--help".into());
        let mut help = invocation(
            &env_with(&[("GRAPHITE_JAVA", "/usr/bin/java")]),
            &jar_frontend(),
            &args,
        )
        .unwrap();
        assert!(help.prepare_pack().unwrap().is_none());
        assert_eq!(help.args.last(), args.last());
        assert!(std::fs::read_dir(temp.path()).unwrap().next().is_none());
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
                    publication: Publication::inspect(&output).unwrap(),
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
        let input = br#"{"version":1,"methods":[],"nodes":[{"id":0,"kind":"StringConstant","value":"published"}],"edges":[]}"#;
        graphite_storage::interchange::import_json(input.as_slice(), &stage.graph()).unwrap();
        let previous = root.join("previous");
        let old = br#"{"version":1,"methods":[],"nodes":[{"id":0,"kind":"StringConstant","value":"previous"}],"edges":[]}"#;
        graphite_storage::interchange::import_json(old.as_slice(), &previous).unwrap();
        graphite_storage::container::pack(&previous, &output).unwrap();
        assert_eq!(
            finish_pack(
                Pack {
                    stage,
                    publication: Publication::inspect(&output).unwrap(),
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
        let graph = graphite_storage::Graph::load(&output).unwrap();
        let graphite_storage::NodeKind::StringConstant(value) = graph.node(0).unwrap().kind else {
            panic!("string expected")
        };
        assert_eq!(graph.str(value), "published");

        // An invalid saved graph must not create the final parent, even after frontend success.
        let missing = root.join("missing");
        let output = missing.join("nested/empty.graphite");
        let stage = Stage::new(&output).unwrap();
        let path = stage.0.clone();
        std::fs::create_dir(stage.graph()).unwrap();
        assert_eq!(
            finish_pack(
                Pack {
                    stage,
                    publication: Publication::inspect(&output).unwrap(),
                    output
                },
                0
            ),
            1
        );
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
    fn profile_after_separator_is_forwarded_as_an_input() {
        let env = env_with(&[("GRAPHITE_JAVA", "/usr/bin/java"), ("PATH", "/nonexistent")]);
        let args = os(&["app.jar", "-o", "g", "--", "--profile"]);
        let inv = invocation(&env, &jar_frontend(), &args).unwrap();
        assert_eq!(
            inv.args,
            [os(&["-jar", "/opt/graphite/graphite.jar", "build"]), args].concat()
        );
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
            .contains("unknown frontend"));
        assert!(choose(&os(&["--lang"])).unwrap_err().contains("requires"));
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn app_directory_uses_its_workspace_or_project_as_the_actual_frontend_argument() {
        let root =
            std::env::temp_dir().join(format!("graphite-ios-directory-{}", std::process::id()));
        std::fs::create_dir_all(root.join("Pods/Vendor.xcodeproj")).unwrap();
        std::fs::write(root.join("NotABundle.xcworkspace"), b"ordinary file").unwrap();
        let args = vec![
            root.clone().into_os_string(),
            "-o".into(),
            "app.graphite".into(),
        ];
        assert_eq!(choose(&args).unwrap().lang, "jvm");
        let project = root.join("App.xcodeproj");
        let workspace = root.join("App.xcworkspace");
        let fe = Frontend {
            lang: "apple",
            launch: Launch::Executable("graphite-frontend-apple".into()),
            found_via: "test",
        };
        for (create, flag, expected) in [
            (&project, "--project", &project),
            (&workspace, "--project", &workspace),
            (&root.join("Package.swift"), "--package", &root),
        ] {
            if flag == "--package" {
                std::fs::write(create, b"// swift-tools-version:5.10").unwrap();
            } else {
                std::fs::create_dir(create).unwrap();
            }
            assert_eq!(choose(&args).unwrap().lang, "apple");
            let build = split_apple_args(&args).unwrap();
            let inv = apple_invocation(&fe, &build, Path::new("graph.ir"));
            let mut expected_args = os(&["build", "--out", "graph.ir", flag]);
            expected_args.push(expected.clone().into_os_string());
            assert_eq!(inv.args, expected_args);
        }
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn ambiguous_app_directory_lists_eligible_paths_and_never_falls_back_to_jvm() {
        let root =
            std::env::temp_dir().join(format!("graphite-ios-ambiguous-{}", std::process::id()));
        std::fs::create_dir(&root).unwrap();
        let args = vec![
            root.clone().into_os_string(),
            "-o".into(),
            "app.graphite".into(),
        ];
        for ext in ["xcodeproj", "xcworkspace"] {
            let a = root.join(format!("Client.{ext}"));
            let b = root.join(format!("Server.{ext}"));
            for path in [&b, &a] {
                std::fs::create_dir(path).unwrap();
            }
            let err = choose(&args).unwrap_err();
            assert!(err.contains("pass one of these paths to `graphite build`"));
            assert!(err.ends_with(&format!("\n  {}\n  {}", a.display(), b.display())));
            assert_eq!(split_apple_args(&args).unwrap_err(), err);
            assert_eq!(run(&env_with(&[("PATH", "/nonexistent")]), &args), 1);
            let mut explicit = os(&["--lang=jvm"]);
            explicit.extend(args.iter().cloned());
            assert_eq!(choose(&explicit).unwrap().lang, "jvm");
            assert_eq!(resolve_apple_input(&a).unwrap(), Some(a));
        }
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn selected_resource_bundles_are_options_not_positional_inputs() {
        let args = os(&[
            "--resource-bundle",
            "/built/Probe.app",
            "--resource-bundle",
            "/built/Share.appex",
            "Probe.xcworkspace",
            "-o",
            "probe.graphite",
        ]);
        assert_eq!(choose(&args).unwrap().lang, "apple");
        let build = split_apple_args(&args).unwrap();
        assert_eq!(build.input, Some(PathBuf::from("Probe.xcworkspace")));
        assert_eq!(
            build.passthrough,
            os(&[
                "--resource-bundle",
                "/built/Probe.app",
                "--resource-bundle",
                "/built/Share.appex",
            ])
        );
        let fe = Frontend {
            lang: "apple",
            launch: Launch::Executable("graphite-frontend-apple".into()),
            found_via: "test",
        };
        assert_eq!(
            apple_invocation(&fe, &build, Path::new("probe.ir")).args,
            os(&[
                "build",
                "--out",
                "probe.ir",
                "--project",
                "Probe.xcworkspace",
                "--resource-bundle",
                "/built/Probe.app",
                "--resource-bundle",
                "/built/Share.appex",
            ])
        );
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
            .contains("requires"));
        assert!(split_apple_args(&os(&["MyApp", "--out", "x"]))
            .unwrap_err()
            .contains("--out is the frontend's"));

        let separated = split_apple_args(&os(&[
            "--sources",
            "A.swift",
            "B.swift",
            "-o",
            "g",
            "--",
            "-App.xcworkspace",
        ]))
        .unwrap();
        assert_eq!(
            separated.input.as_deref(),
            Some(Path::new("-App.xcworkspace"))
        );
        assert_eq!(
            separated.passthrough,
            os(&["--sources", "A.swift", "B.swift"])
        );

        let fe = Frontend {
            lang: "apple",
            launch: Launch::Executable(PathBuf::from("/opt/graphite-frontend-apple")),
            found_via: "test",
        };
        let ir = PathBuf::from("/tmp/stage/frontend.graphite-ir");
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
        let build = split_apple_args(&os(&["--index-store", "/s", "-o", "/g"])).unwrap();
        let inv = apple_invocation(&fe, &build, &ir);
        assert!(!inv.args.iter().any(|a| a == "--package"));
        assert_eq!(choose(&os(&["lib.aar"])).unwrap().lang, "jvm");

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
        assert_eq!(choose(&os(&["Acme.xcodeproj/"])).unwrap().lang, "apple");
        assert_eq!(
            choose(&os(&["--scheme", "Acme", "Acme.xcodeproj", "-o", "g"]))
                .unwrap()
                .lang,
            "apple"
        );
    }

    #[cfg(unix)]
    #[test]
    fn jvm_selection_flags_are_consumed_and_explicit_frontends_build_the_output() {
        use std::os::unix::fs::PermissionsExt;
        let root =
            std::env::temp_dir().join(format!("graphite-jvm-selection-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let launcher = root.join("launcher");
        let java = root.join("java");
        let script = "#!/bin/sh\nset -eu\nprintf '%s\\n' \"$@\" > \"$0.log\"\n\
            while [ $# -gt 0 ]; do\n\
              if [ \"$1\" = -o ]; then mkdir -p \"$2\"; echo built > \"$2/result\"; fi\n\
              shift\n\
            done\n";
        for path in [&launcher, &java] {
            std::fs::write(path, script).unwrap();
            std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o755)).unwrap();
        }
        let output = root.join("graph");
        let jar = root.join("custom.jar");
        std::fs::write(&jar, "jar").unwrap();
        let env = env_with(&[
            ("GRAPHITE_FRONTEND_JVM", launcher.to_str().unwrap()),
            ("GRAPHITE_JAVA", java.to_str().unwrap()),
            ("JAVA_TOOL_OPTIONS", "-Xmx256m"),
        ]);
        let forwarded = os(&[
            "app.jar",
            "--include",
            "com.example",
            "-o",
            output.to_str().unwrap(),
        ]);
        for selectors in [os(&["--lang", "jvm"]), os(&["--lang=java"])] {
            let mut args = selectors;
            args.extend(forwarded.clone());
            assert_eq!(run(&env, &args), 0);
            let expected = std::iter::once("build".to_string())
                .chain(
                    forwarded
                        .iter()
                        .map(|arg| arg.to_str().unwrap().to_string()),
                )
                .collect::<Vec<_>>()
                .join("\n")
                + "\n";
            assert_eq!(
                std::fs::read_to_string(root.join("launcher.log")).unwrap(),
                expected
            );
            assert_eq!(
                std::fs::read_to_string(output.join("result")).unwrap(),
                "built\n"
            );
            std::fs::remove_dir_all(&output).unwrap();
        }
        // Both selector spellings must override even a pinned, unusable frontend.
        let mut pinned = env.clone();
        pinned.vars.insert(
            frontend::JVM_FRONTEND_VAR.into(),
            root.join("missing").into(),
        );
        for path in [&launcher, &jar] {
            for selector in [
                os(&["--frontend", path.to_str().unwrap()]),
                vec![OsString::from(format!("--frontend={}", path.display()))],
            ] {
                let mut args = os(&["--lang=kotlin"]);
                args.extend(selector);
                args.extend(forwarded.clone());
                assert_eq!(run(&pinned, &args), 0);
                let (log, mut expected) = if path == &jar {
                    (
                        root.join("java.log"),
                        os(&["-jar", jar.to_str().unwrap(), "build"]),
                    )
                } else {
                    (root.join("launcher.log"), os(&["build"]))
                };
                expected.extend(forwarded.clone());
                assert_eq!(
                    std::fs::read_to_string(log).unwrap(),
                    expected
                        .iter()
                        .map(|arg| arg.to_str().unwrap())
                        .collect::<Vec<_>>()
                        .join("\n")
                        + "\n"
                );
                assert_eq!(
                    std::fs::read_to_string(output.join("result")).unwrap(),
                    "built\n"
                );
                std::fs::remove_dir_all(&output).unwrap();
            }
        }
        let terminated = os(&["-o", "g", "--", "--lang=apple"]);
        assert_eq!(choose(&terminated).unwrap().lang, "jvm");
        assert_eq!(select_frontend(&terminated).unwrap().1, terminated);
        let selector_values = os(&[
            "--fold",
            "--frontend=rules",
            "--output=--lang=apple",
            "app.jar",
        ]);
        assert_eq!(choose(&selector_values).unwrap().lang, "jvm");
        assert_eq!(
            select_frontend(&selector_values).unwrap().1,
            selector_values
        );
        std::fs::remove_dir_all(root).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn apple_build_prepares_nested_output_and_refuses_blocked_parent_before_building() {
        use std::os::unix::fs::PermissionsExt;
        let root =
            std::env::temp_dir().join(format!("graphite-apple-output-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let apple = root.join("apple");
        let jvm = root.join("jvm");
        std::fs::write(
            &apple,
            "#!/bin/sh\nset -eu\n\
            if [ \"$1\" = describe ]; then echo '{\"ir_schema\":[1]}'; exit; fi\n\
            echo \"$3\" > \"$0.log\"\n\
            [ \"$1\" = build ] && [ \"$2\" = --out ]\n\
            echo 'IR payload' > \"$3\"\n",
        )
        .unwrap();
        std::fs::write(
            &jvm,
            "#!/bin/sh\nset -eu\n\
            [ \"$1\" = import ] && [ \"$3\" = -o ]\n\
            mkdir -p \"$4\"\n\
            cp \"$2\" \"$4/result\"\n",
        )
        .unwrap();
        for path in [&apple, &jvm] {
            std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o755)).unwrap();
        }
        let env = env_with(&[
            ("GRAPHITE_FRONTEND_APPLE", apple.to_str().unwrap()),
            ("GRAPHITE_FRONTEND_JVM", jvm.to_str().unwrap()),
        ]);
        let output = root.join("new/nested/graph");
        let args =
            |output: &Path| os(&["--lang", "apple", "MyApp", "-o", output.to_str().unwrap()]);
        assert!(!output.parent().unwrap().exists());
        assert_eq!(run(&env, &args(&output)), 0);
        assert_eq!(
            std::fs::read_to_string(output.join("result")).unwrap(),
            "IR payload\n"
        );
        let ir = std::fs::read_to_string(root.join("apple.log")).unwrap();
        assert!(!Path::new(ir.trim()).exists());

        std::fs::remove_file(root.join("apple.log")).unwrap();
        assert_eq!(run(&env, &args(&output)), 1);
        assert!(!root.join("apple.log").exists());
        assert_eq!(
            std::fs::read_to_string(output.join("result")).unwrap(),
            "IR payload\n"
        );
        let alias = root.join("alias");
        std::os::unix::fs::symlink(&output, &alias).unwrap();
        assert_eq!(run(&env, &args(&alias)), 1);
        assert!(!root.join("apple.log").exists());
        let dangling = root.join("dangling");
        let missing = root.join("missing-target");
        std::os::unix::fs::symlink(&missing, &dangling).unwrap();
        assert_eq!(run(&env, &args(&dangling)), 1);
        assert!(!missing.exists());
        assert!(!root.join("apple.log").exists());
        let blocked = root.join("blocked");
        std::fs::write(&blocked, "file").unwrap();
        assert_eq!(run(&env, &args(&blocked.join("graph.graphite"))), 1);
        assert!(!root.join("apple.log").exists());
        std::fs::remove_dir_all(root).unwrap();
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
            format!("#!/bin/sh\necho \"$@\" >> {}\n[ \"$3\" = import ] && grep -qx ir \"$4\" && mkdir -p \"$6\" && echo imported > \"$6/result\"\n", log.display()),
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
        assert_eq!(
            std::fs::read_to_string(output.join("result")).unwrap(),
            "imported\n"
        );
        std::fs::remove_dir_all(&output).unwrap();
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
        assert_eq!(run(&env, &os(&["--lang", "web"])), 2);
        std::fs::remove_dir_all(root).unwrap();
    }
}

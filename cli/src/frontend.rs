//! Locating and describing frontends.
//!
//! A frontend turns compiled artifacts into a graph. The only one today is the JVM
//! frontend, shipped as `graphite.jar` (the Kotlin CLI, whose `build` runs the SootUp
//! analysis). This module finds it, finds a `java` to run it with, and reports what it
//! found. Nothing here touches the real environment directly: every lookup goes through
//! [`Env`], so the search order is unit-tested against fake homes and PATHs.

use serde_json::{json, Map, Value};
use std::collections::BTreeMap;
use std::ffi::OsString;
use std::path::{Path, PathBuf};

/// The environment a lookup runs in: variables, the running executable, the home
/// directory. Built from the process by [`Env::from_process`], or by hand in tests.
#[derive(Debug, Clone, Default)]
pub struct Env {
    pub vars: BTreeMap<String, OsString>,
    pub exe: Option<PathBuf>,
    pub home: Option<PathBuf>,
}

impl Env {
    pub fn from_process() -> Self {
        let vars = std::env::vars_os()
            .filter_map(|(k, v)| k.into_string().ok().map(|k| (k, v)))
            .collect();
        Env {
            vars,
            exe: std::env::current_exe().ok(),
            home: std::env::var_os("HOME")
                .or_else(|| std::env::var_os("USERPROFILE"))
                .map(PathBuf::from),
        }
    }

    pub fn var(&self, name: &str) -> Option<&OsString> {
        self.vars.get(name).filter(|v| !v.is_empty())
    }

    fn var_path(&self, name: &str) -> Option<PathBuf> {
        self.var(name).map(PathBuf::from)
    }

    /// Directories on `PATH`, in order.
    pub fn path_dirs(&self) -> Vec<PathBuf> {
        self.var("PATH")
            .map(|p| std::env::split_paths(p).collect())
            .unwrap_or_default()
    }

    /// The first executable called `name` on `PATH` (`name.exe` on Windows).
    pub fn which(&self, name: &str) -> Option<PathBuf> {
        let candidates: Vec<String> = if cfg!(windows) {
            vec![format!("{name}.exe"), name.to_string()]
        } else {
            vec![name.to_string()]
        };
        self.path_dirs()
            .into_iter()
            .find_map(|dir| candidates.iter().map(|c| dir.join(c)).find(|p| p.is_file()))
    }

    /// `~/.graphite/frontends`, where `graphite frontend install` puts frontends.
    pub fn frontends_dir(&self) -> Option<PathBuf> {
        self.home
            .as_ref()
            .map(|h| h.join(".graphite").join("frontends"))
    }
}

/// How a located frontend is started.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Launch {
    /// A jar, run as `java -jar <jar> <args>`.
    Jar(PathBuf),
    /// An executable (`graphite-frontend-jvm` launcher), run directly.
    Executable(PathBuf),
}

/// A frontend the CLI can run, and where it came from.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frontend {
    pub lang: &'static str,
    pub launch: Launch,
    /// Which rule of the search order matched, for `frontend list`.
    pub found_via: &'static str,
}

impl Frontend {
    pub fn path(&self) -> &Path {
        match &self.launch {
            Launch::Jar(p) | Launch::Executable(p) => p,
        }
    }
}

/// The environment variable that pins the JVM frontend jar.
pub const JVM_FRONTEND_VAR: &str = "GRAPHITE_FRONTEND_JVM";
/// The environment variable that pins the `java` executable.
pub const JAVA_VAR: &str = "GRAPHITE_JAVA";
/// The jar's name inside `~/.graphite/frontends/jvm/<version>/`.
pub const JVM_FRONTEND_JAR: &str = "graphite-frontend-jvm.jar";
/// The asset name on a GitHub release.
pub const JVM_RELEASE_ASSET: &str = "graphite.jar";

/// Find the JVM frontend. The order, first match wins:
///
/// 1. `GRAPHITE_FRONTEND_JVM`: a jar, or an executable launcher.
/// 2. Next to this executable, or in a sibling `libexec/` (the Homebrew layout):
///    `graphite-frontend-jvm.jar`, then `graphite.jar`.
/// 3. `graphite-frontend-jvm` on `PATH`.
/// 4. The highest version under `~/.graphite/frontends/jvm/<version>/`.
pub fn locate_jvm(env: &Env) -> Option<Frontend> {
    if let Some(pinned) = env.var_path(JVM_FRONTEND_VAR) {
        let launch = if pinned.extension().is_some_and(|e| e == "jar") {
            Launch::Jar(pinned)
        } else {
            Launch::Executable(pinned)
        };
        return Some(Frontend {
            lang: "jvm",
            launch,
            found_via: JVM_FRONTEND_VAR,
        });
    }
    if let Some(exe_dir) = env.exe.as_ref().and_then(|e| e.parent()) {
        let mut dirs = vec![exe_dir.to_path_buf()];
        if let Some(prefix) = exe_dir.parent() {
            dirs.push(prefix.join("libexec"));
        }
        for dir in dirs {
            for name in [JVM_FRONTEND_JAR, JVM_RELEASE_ASSET] {
                let jar = dir.join(name);
                if jar.is_file() {
                    return Some(Frontend {
                        lang: "jvm",
                        launch: Launch::Jar(jar),
                        found_via: "next to the graphite executable",
                    });
                }
            }
        }
    }
    if let Some(exe) = env.which("graphite-frontend-jvm") {
        return Some(Frontend {
            lang: "jvm",
            launch: Launch::Executable(exe),
            found_via: "PATH",
        });
    }
    let installed = env.frontends_dir()?.join("jvm");
    let jar = newest_installed_jar(&installed)?;
    Some(Frontend {
        lang: "jvm",
        launch: Launch::Jar(jar),
        found_via: "~/.graphite/frontends",
    })
}

/// The jar of the highest-versioned *complete* install under `dir`, comparing dotted
/// numeric components first and pre-release suffixes after (`2.5.0` > `2.5.0-rc.1` >
/// `2.4.9`). A version directory without the jar (an install that failed or is still
/// downloading) is not a candidate, so it never hides an older install that works.
fn newest_installed_jar(dir: &Path) -> Option<PathBuf> {
    let mut versions: Vec<(VersionKey, PathBuf)> = std::fs::read_dir(dir)
        .ok()?
        .filter_map(|e| e.ok())
        .map(|e| e.path().join(JVM_FRONTEND_JAR))
        .filter(|jar| jar.is_file())
        .map(|jar| {
            let version = jar
                .parent()
                .and_then(Path::file_name)
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_default();
            (VersionKey::parse(&version), jar)
        })
        .collect();
    versions.sort();
    versions.pop().map(|(_, jar)| jar)
}

/// Sort key for a version string: numeric components, then a flag for "is a release"
/// (no suffix), then the suffix text.
#[derive(Debug, PartialEq, Eq, PartialOrd, Ord)]
struct VersionKey(Vec<u64>, bool, String);

impl VersionKey {
    fn parse(text: &str) -> Self {
        let text = text.strip_prefix('v').unwrap_or(text);
        let (numbers, suffix) = match text.find(['-', '+']) {
            Some(i) => (&text[..i], text[i + 1..].to_string()),
            None => (text, String::new()),
        };
        let parts: Vec<u64> = numbers.split('.').map(|p| p.parse().unwrap_or(0)).collect();
        VersionKey(parts, suffix.is_empty(), suffix)
    }
}

/// Find `java`: `GRAPHITE_JAVA`, then `JAVA_HOME/bin/java`, then `java` on `PATH`.
pub fn locate_java(env: &Env) -> Result<PathBuf, String> {
    if let Some(java) = env.var_path(JAVA_VAR) {
        return Ok(java);
    }
    if let Some(home) = env.var_path("JAVA_HOME") {
        let java = home
            .join("bin")
            .join(if cfg!(windows) { "java.exe" } else { "java" });
        if java.is_file() {
            return Ok(java);
        }
    }
    env.which("java").ok_or_else(|| {
        format!(
            "java not found. The JVM frontend needs a JDK 17+: set JAVA_HOME, put java on PATH, \
             or set {JAVA_VAR} to the java executable."
        )
    })
}

/// The `JAVA_TOOL_OPTIONS` the frontend JVM runs with when the caller set none:
/// `JAVA_OPTS`, else `-Xmx8g`. This is what the Homebrew wrapper around `graphite.jar`
/// did, so a build that fit before keeps fitting.
pub fn default_java_tool_options(env: &Env) -> Option<OsString> {
    if env.var("JAVA_TOOL_OPTIONS").is_some() {
        return None;
    }
    Some(
        env.var("JAVA_OPTS")
            .cloned()
            .unwrap_or_else(|| OsString::from("-Xmx8g")),
    )
}

/// The `-agentpath:` argument that `--profile` adds, from async-profiler installed next to
/// `asprof` on `PATH`. The output file is `GRAPHITE_PROFILE`, default `profile.html`.
pub fn profiler_agent_arg(env: &Env) -> Result<OsString, String> {
    let asprof = env
        .which("asprof")
        .ok_or("async-profiler not found. Install: brew install async-profiler")?;
    let lib_dir = asprof
        .parent()
        .and_then(|bin| bin.parent())
        .map(|prefix| prefix.join("lib"))
        .ok_or("async-profiler not found. Install: brew install async-profiler")?;
    let lib = ["dylib", "so"]
        .iter()
        .map(|ext| lib_dir.join(format!("libasyncProfiler.{ext}")))
        .find(|p| p.is_file())
        .ok_or("async-profiler not found. Install: brew install async-profiler")?;
    let out = env
        .var("GRAPHITE_PROFILE")
        .cloned()
        .unwrap_or_else(|| OsString::from("profile.html"));
    let mut arg = OsString::from("-agentpath:");
    arg.push(lib);
    arg.push("=start,event=cpu,file=");
    arg.push(out);
    Ok(arg)
}

/// What `frontend describe` reports for a located frontend. `version` is whatever the
/// caller could learn by running it (`None` when it could not be run).
pub fn describe(frontend: &Frontend, version: Option<String>) -> Value {
    let mut m = Map::new();
    m.insert("name".into(), json!(frontend.lang));
    m.insert(
        "kind".into(),
        json!(match frontend.launch {
            Launch::Jar(_) => "jar",
            Launch::Executable(_) => "executable",
        }),
    );
    m.insert("path".into(), json!(frontend.path().to_string_lossy()));
    m.insert("found_via".into(), json!(frontend.found_via));
    m.insert("version".into(), json!(version));
    m.insert(
        "inputs".into(),
        json!(["jar", "war", "apk", "aar", "dex", "class directory"]),
    );
    // The jar-era frontend writes the persisted graph itself; it emits no IR yet.
    m.insert("ir_schema".into(), json!([]));
    m.insert("writes".into(), json!("persisted-graph"));
    Value::Object(m)
}

/// The URL of a release asset for `version` (a bare version, no `v`).
pub fn release_asset_url(version: &str, asset: &str) -> String {
    format!("https://github.com/johnsonlee/graphite/releases/download/v{version}/{asset}")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "graphite-cli-{name}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn touch(path: &Path) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, b"").unwrap();
    }

    fn env_with(vars: &[(&str, &Path)]) -> Env {
        let mut env = Env::default();
        for (k, v) in vars {
            env.vars
                .insert((*k).to_string(), v.as_os_str().to_os_string());
        }
        env
    }

    #[test]
    fn pinned_variable_wins_and_distinguishes_jar_from_launcher() {
        let jar = PathBuf::from("/opt/fe/graphite.jar");
        let env = env_with(&[(JVM_FRONTEND_VAR, &jar)]);
        let found = locate_jvm(&env).unwrap();
        assert_eq!(found.launch, Launch::Jar(jar));
        assert_eq!(found.found_via, JVM_FRONTEND_VAR);

        let launcher = PathBuf::from("/opt/fe/graphite-frontend-jvm");
        let env = env_with(&[(JVM_FRONTEND_VAR, &launcher)]);
        assert_eq!(
            locate_jvm(&env).unwrap().launch,
            Launch::Executable(launcher)
        );
    }

    #[test]
    fn jar_next_to_the_executable_or_in_libexec_is_found() {
        let root = temp_dir("exe");
        let exe = root.join("bin").join("graphite");
        touch(&exe);
        let env = Env {
            exe: Some(exe.clone()),
            ..Default::default()
        };
        assert_eq!(locate_jvm(&env), None);

        let libexec_jar = root.join("libexec").join(JVM_RELEASE_ASSET);
        touch(&libexec_jar);
        let found = locate_jvm(&env).unwrap();
        assert_eq!(found.launch, Launch::Jar(libexec_jar.clone()));
        assert_eq!(found.found_via, "next to the graphite executable");

        // The renamed jar beside the binary outranks the libexec copy.
        let sibling = root.join("bin").join(JVM_FRONTEND_JAR);
        touch(&sibling);
        assert_eq!(locate_jvm(&env).unwrap().launch, Launch::Jar(sibling));
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn path_launcher_outranks_the_home_install_which_picks_the_newest_version() {
        let root = temp_dir("home");
        let home = root.join("home");
        for v in ["2.4.9", "2.5.0-rc.1", "2.5.0", "2.10.0-beta.2"] {
            touch(
                &home
                    .join(".graphite/frontends/jvm")
                    .join(v)
                    .join(JVM_FRONTEND_JAR),
            );
        }
        let mut env = Env {
            home: Some(home.clone()),
            ..Default::default()
        };
        let found = locate_jvm(&env).unwrap();
        assert_eq!(found.found_via, "~/.graphite/frontends");
        assert!(found
            .path()
            .ends_with(Path::new("2.10.0-beta.2").join(JVM_FRONTEND_JAR)));
        // A newer version directory without the jar (a failed or unfinished install) is
        // not a candidate: the newest complete install is still found.
        std::fs::create_dir_all(home.join(".graphite/frontends/jvm/3.0.0")).unwrap();
        touch(&home.join(".graphite/frontends/jvm/3.1.0/graphite-frontend-jvm.jar.part"));
        let found = locate_jvm(&env).unwrap();
        assert!(found
            .path()
            .ends_with(Path::new("2.10.0-beta.2").join(JVM_FRONTEND_JAR)));

        let bin = root.join("bin");
        let launcher = bin.join("graphite-frontend-jvm");
        touch(&launcher);
        env.vars
            .insert("PATH".into(), bin.as_os_str().to_os_string());
        let found = locate_jvm(&env).unwrap();
        assert_eq!(found.launch, Launch::Executable(launcher));
        assert_eq!(found.found_via, "PATH");
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn version_ordering_puts_releases_above_their_prereleases() {
        let mut keys = [
            VersionKey::parse("2.5.0-rc.1"),
            VersionKey::parse("2.4.10"),
            VersionKey::parse("v2.5.0"),
            VersionKey::parse("2.4.9"),
            VersionKey::parse("2.5.0-alpha.3"),
        ];
        keys.sort();
        let order: Vec<String> = keys
            .iter()
            .map(|k| {
                format!(
                    "{}{}{}",
                    k.0.iter()
                        .map(|n| n.to_string())
                        .collect::<Vec<_>>()
                        .join("."),
                    if k.1 { "" } else { "-" },
                    k.2
                )
            })
            .collect();
        assert_eq!(
            order,
            ["2.4.9", "2.4.10", "2.5.0-alpha.3", "2.5.0-rc.1", "2.5.0"]
        );
    }

    #[test]
    fn java_lookup_order_is_variable_then_java_home_then_path() {
        let root = temp_dir("java");
        let home_java = root.join("jdk/bin/java");
        touch(&home_java);
        let path_java = root.join("bin/java");
        touch(&path_java);

        let env = env_with(&[
            ("JAVA_HOME", &root.join("jdk")),
            ("PATH", &root.join("bin")),
        ]);
        assert_eq!(locate_java(&env).unwrap(), home_java);

        let env = env_with(&[("PATH", &root.join("bin"))]);
        assert_eq!(locate_java(&env).unwrap(), path_java);

        let pinned = PathBuf::from("/custom/java");
        let env = env_with(&[(JAVA_VAR, &pinned), ("JAVA_HOME", &root.join("jdk"))]);
        assert_eq!(locate_java(&env).unwrap(), pinned);

        let env = env_with(&[("JAVA_HOME", &root.join("missing"))]);
        assert!(locate_java(&env).unwrap_err().contains("java not found"));
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn heap_default_matches_the_homebrew_wrapper() {
        assert_eq!(
            default_java_tool_options(&Env::default()),
            Some(OsString::from("-Xmx8g"))
        );
        let env = env_with(&[("JAVA_OPTS", Path::new("-Xmx2g"))]);
        assert_eq!(
            default_java_tool_options(&env),
            Some(OsString::from("-Xmx2g"))
        );
        let env = env_with(&[
            ("JAVA_OPTS", Path::new("-Xmx2g")),
            ("JAVA_TOOL_OPTIONS", Path::new("-Xmx1g")),
        ]);
        assert_eq!(default_java_tool_options(&env), None);
    }

    #[test]
    fn profiler_agent_comes_from_asprof_on_path() {
        let root = temp_dir("asprof");
        let env = env_with(&[("PATH", &root.join("bin"))]);
        assert!(profiler_agent_arg(&env)
            .unwrap_err()
            .contains("async-profiler not found"));
        touch(&root.join("bin/asprof"));
        assert!(
            profiler_agent_arg(&env).is_err(),
            "asprof without its library"
        );
        let lib = root.join("lib/libasyncProfiler.so");
        touch(&lib);
        let arg = profiler_agent_arg(&env).unwrap();
        let mut expected = OsString::from("-agentpath:");
        expected.push(&lib);
        expected.push("=start,event=cpu,file=profile.html");
        assert_eq!(arg, expected);
        let env = env_with(&[
            ("PATH", &root.join("bin")),
            ("GRAPHITE_PROFILE", Path::new("out.html")),
        ]);
        assert!(profiler_agent_arg(&env)
            .unwrap()
            .to_string_lossy()
            .ends_with("file=out.html"));
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn describe_reports_kind_path_and_version() {
        let fe = Frontend {
            lang: "jvm",
            launch: Launch::Jar(PathBuf::from("/x/graphite.jar")),
            found_via: "PATH",
        };
        let d = describe(&fe, Some("2.4.8".into()));
        assert_eq!(d["name"], "jvm");
        assert_eq!(d["kind"], "jar");
        assert_eq!(d["path"], "/x/graphite.jar");
        assert_eq!(d["version"], "2.4.8");
        assert_eq!(d["ir_schema"], json!([]));
        let fe = Frontend {
            lang: "jvm",
            launch: Launch::Executable(PathBuf::from("/x/fe")),
            found_via: "PATH",
        };
        let d = describe(&fe, None);
        assert_eq!(d["kind"], "executable");
        assert!(d["version"].is_null());
    }

    #[test]
    fn release_asset_urls_point_at_the_tagged_release() {
        assert_eq!(
            release_asset_url("2.4.8", JVM_RELEASE_ASSET),
            "https://github.com/johnsonlee/graphite/releases/download/v2.4.8/graphite.jar"
        );
    }
}

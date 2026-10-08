//! Capability-gated defaults for one native CLI build, without changing user overrides.

use crate::frontend::{self, Env, Frontend, Launch};
use std::borrow::Cow;
use std::ffi::OsString;
use std::fs::{File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

const PROFILE: &str = "-Xmx8g -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:G1MaxNewSizePercent=30 -XX:MinHeapFreeRatio=20 -XX:GCTimeRatio=4";
const EXPLICIT_OPTIONS: [&str; 4] = [
    "JAVA_TOOL_OPTIONS",
    "JAVA_OPTS",
    "JDK_JAVA_OPTIONS",
    "_JAVA_OPTIONS",
];
const REQUIRED_FLAGS: [(&str, &str, &str); 6] = [
    ("bool", "UseG1GC", "true"),
    ("bool", "UnlockExperimentalVMOptions", "true"),
    ("uintx", "G1MaxNewSizePercent", "30"),
    ("uintx", "MinHeapFreeRatio", "20"),
    ("uintx", "GCTimeRatio", "4"),
    ("size_t", "MaxHeapSize", "8589934592"),
];

pub(super) fn build_env<'a>(
    env: &'a Env,
    fe: &Frontend,
    args: &[OsString],
) -> io::Result<Cow<'a, Env>> {
    build_env_with(env, fe, args, probe)
}

// Only classify a pure forwarded informational request, not an option value or
// a positional path after `--`. The frontend still handles the unchanged args.
fn informational_request(args: &[OsString]) -> bool {
    matches!(args, [arg] if ["--help", "-h", "--version", "-V"].iter().any(|flag| arg == *flag))
}

fn build_env_with<'a>(
    env: &'a Env,
    fe: &Frontend,
    args: &[OsString],
    check: impl FnOnce(&Path, &Env) -> io::Result<bool>,
) -> io::Result<Cow<'a, Env>> {
    if informational_request(args)
        || !matches!(fe.launch, Launch::Jar(_))
        || EXPLICIT_OPTIONS.iter().any(|name| env.var(name).is_some())
    {
        return Ok(Cow::Borrowed(env));
    }
    let Ok(java) = frontend::locate_java(env) else {
        // The normal invocation reports the original missing-Java error.
        return Ok(Cow::Borrowed(env));
    };
    if !check(&java, env)? {
        return Ok(Cow::Borrowed(env));
    }
    let mut configured = env.clone();
    // Invocation exports this as JAVA_TOOL_OPTIONS. Setting TOOL_OPTIONS here would
    // suppress that export: Env is a snapshot, not a mutation of the process env.
    configured.vars.insert("JAVA_OPTS".into(), PROFILE.into());
    configured
        .vars
        .insert(frontend::JAVA_VAR.into(), java.into_os_string());
    Ok(Cow::Owned(configured))
}

fn has_effective_profile(output: &[u8]) -> bool {
    let Ok(text) = std::str::from_utf8(output) else {
        return false;
    };
    let mut found = [false; REQUIRED_FLAGS.len()];
    for line in text.lines() {
        let fields: Vec<_> = line.split_whitespace().collect();
        let Some(name) = fields.get(1) else { continue };
        let Some(index) = REQUIRED_FLAGS.iter().position(|(_, flag, _)| flag == name) else {
            continue;
        };
        let (kind, _, value) = REQUIRED_FLAGS[index];
        if found[index]
            || fields.first() != Some(&kind)
            || !matches!(fields.get(2), Some(&"=") | Some(&":="))
            || fields.get(3) != Some(&value)
            || fields.get(4).is_none_or(|field| !field.starts_with('{'))
        {
            return false;
        }
        found[index] = true;
    }
    found.into_iter().all(|value| value)
}

const PROBE_TIMEOUT: Duration = Duration::from_secs(5);
const PROBE_POLL: Duration = Duration::from_millis(20);
const MAX_OUTPUT: u64 = 1024 * 1024;
static NEXT_CAPTURE: AtomicU64 = AtomicU64::new(0);

// No pipe reader thread: a wrapper inheriting stdout cannot make a join hang.
// The retained read is capped. Disk output can overshoot between size polls;
// this is not a filesystem quota or a promise to terminate wrapper descendants.
struct Capture {
    path: PathBuf,
    file: Option<File>,
}

impl Capture {
    fn new() -> io::Result<Self> {
        for _ in 0..16 {
            let serial = NEXT_CAPTURE.fetch_add(1, Ordering::Relaxed);
            let path = std::env::temp_dir().join(format!(
                "graphite-jvm-profile-{}-{serial}",
                std::process::id()
            ));
            let mut options = OpenOptions::new();
            options.read(true).write(true).create_new(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::OpenOptionsExt;
                options.mode(0o600);
            }
            match options.open(&path) {
                Ok(file) => {
                    return Ok(Self {
                        path,
                        file: Some(file),
                    })
                }
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
                Err(error) => return Err(error),
            }
        }
        Err(io::Error::new(
            io::ErrorKind::AlreadyExists,
            "JVM probe capture collision",
        ))
    }

    fn file(&mut self) -> &mut File {
        self.file.as_mut().expect("capture file is open until drop")
    }
}

impl Drop for Capture {
    fn drop(&mut self) {
        drop(self.file.take());
        if let Err(error) = std::fs::remove_file(&self.path) {
            if error.kind() != io::ErrorKind::NotFound {
                eprintln!(
                    "Warning: could not remove JVM probe capture {}: {error}",
                    self.path.display()
                );
            }
        }
    }
}

fn stop_probe(child: &mut Child) -> io::Result<()> {
    if child.try_wait()?.is_none() {
        if let Err(error) = child.kill() {
            // Exiting between try_wait and kill is not a cleanup failure.
            if child.try_wait()?.is_none() {
                return Err(error);
            }
        }
    }
    child.wait().map(|_| ())
}

fn probe(java: &Path, env: &Env) -> io::Result<bool> {
    probe_with_limits(java, env, PROBE_TIMEOUT, MAX_OUTPUT)
}

fn probe_with_limits(java: &Path, env: &Env, timeout: Duration, limit: u64) -> io::Result<bool> {
    let Ok(mut capture) = Capture::new() else {
        return Ok(false);
    };
    let Ok(stdout) = capture.file().try_clone() else {
        return Ok(false);
    };
    let mut command = Command::new(java);
    command
        .args(PROFILE.split_ascii_whitespace())
        .args(["-XX:+PrintFlagsFinal", "-version"])
        .env_clear()
        .envs(&env.vars)
        .stdin(Stdio::null())
        .stdout(Stdio::from(stdout))
        .stderr(Stdio::null());
    let start = Instant::now();
    let Ok(mut child) = command.spawn() else {
        return Ok(false);
    };
    loop {
        let within_limit = capture.file().metadata().is_ok_and(|m| m.len() <= limit);
        if !within_limit || start.elapsed() >= timeout {
            stop_probe(&mut child)?;
            return Ok(false);
        }
        match child.try_wait() {
            Ok(Some(status)) => {
                if !status.success() {
                    return Ok(false);
                }
                let mut output = Vec::new();
                if capture.file().seek(SeekFrom::Start(0)).is_err()
                    || capture
                        .file()
                        .take(limit.saturating_add(1))
                        .read_to_end(&mut output)
                        .is_err()
                    || output.len() as u64 > limit
                {
                    return Ok(false);
                }
                return Ok(has_effective_profile(&output));
            }
            Ok(None) => std::thread::sleep(PROBE_POLL.min(timeout.saturating_sub(start.elapsed()))),
            Err(_) => {
                stop_probe(&mut child)?;
                return Ok(false);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;
    use std::ffi::OsString;

    // Hold this for every test that writes an executable or starts a child, including
    // build::run and /bin/kill. A concurrent fork can inherit fake_java's writable
    // script descriptor until exec, causing Linux ETXTBSY even after our fd closes.
    // Keep the guard across the whole test so helpers never need a nested lock.
    #[cfg(unix)]
    static SPAWN_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

    // A minimal PrintFlagsFinal excerpt, including the actual flag value types.
    const FLAGS: &str = "[Global flags]\n\
        bool UseG1GC = true {product} {command line}\n\
        bool UnlockExperimentalVMOptions = true {experimental} {command line}\n\
        uintx G1MaxNewSizePercent = 30 {experimental} {command line}\n\
        uintx MinHeapFreeRatio = 20 {manageable} {command line}\n\
        uintx GCTimeRatio = 4 {product} {command line}\n\
        size_t MaxHeapSize = 8589934592 {product} {command line}\n";

    fn jar() -> Frontend {
        Frontend {
            lang: "jvm",
            launch: Launch::Jar("/fixture/graphite.jar".into()),
            found_via: "test",
        }
    }

    fn env() -> Env {
        let mut env = Env::default();
        env.vars
            .insert(frontend::JAVA_VAR.into(), "/fixture/java".into());
        env
    }

    #[test]
    fn effective_flags_require_every_exact_value_once() {
        assert!(has_effective_profile(FLAGS.as_bytes()));
        assert!(has_effective_profile(
            FLAGS.replace(" = ", " := ").as_bytes()
        ));
        for (before, after) in [
            ("UseG1GC = true", "UseG1GC = false"),
            ("G1MaxNewSizePercent = 30", "G1MaxNewSizePercent = 60"),
            ("MinHeapFreeRatio = 20", "MinHeapFreeRatio = 40"),
            ("GCTimeRatio = 4", "GCTimeRatio = 12"),
            ("8589934592", "2147483648"),
            (
                "UnlockExperimentalVMOptions",
                "IgnoredExperimentalVMOptions",
            ),
            ("size_t MaxHeapSize", "uintx MaxHeapSize"),
            ("UseG1GC = true", "UseG1GC true"),
        ] {
            assert!(
                !has_effective_profile(FLAGS.replace(before, after).as_bytes()),
                "{before}"
            );
        }
        assert!(!has_effective_profile(format!("{FLAGS}{FLAGS}").as_bytes()));
        assert!(!has_effective_profile(
            b"OpenJDK version 17; unknown flags ignored"
        ));
        assert!(!has_effective_profile(&[0xff]));
    }

    #[test]
    fn verified_environment_exports_the_profile_once_without_mutating_input() {
        let marker = Capture::new().unwrap();
        let home = marker.path.with_extension("jdk");
        let java = home
            .join("bin")
            .join(if cfg!(windows) { "java.exe" } else { "java" });
        std::fs::create_dir_all(java.parent().unwrap()).unwrap();
        std::fs::write(&java, []).unwrap();
        let mut input = Env::default();
        input
            .vars
            .insert("JAVA_HOME".into(), home.clone().into_os_string());
        let before = input.vars.clone();
        let calls = Cell::new(0);
        let configured = build_env_with(&input, &jar(), &[], |java, seen| {
            assert_eq!(java, frontend::locate_java(&input).unwrap());
            assert_eq!(seen.vars, before);
            calls.set(calls.get() + 1);
            Ok(true)
        })
        .unwrap();
        assert!(matches!(&configured, Cow::Owned(_)));
        assert_eq!(input.vars, before);
        assert_eq!(configured.vars[frontend::JAVA_VAR], java.as_os_str());
        // Removing JAVA_HOME's discovery target cannot select a different path:
        // the configured environment keeps the exact executable that was probed.
        std::fs::remove_dir_all(home).unwrap();
        for file in ["first.jar", "second.jar"] {
            let args = vec![OsString::from(file), "-o".into(), "graph".into()];
            let inv = crate::build::invocation(configured.as_ref(), &jar(), &args).unwrap();
            assert_eq!(inv.program, java);
            assert_eq!(inv.env, vec![("JAVA_TOOL_OPTIONS".into(), PROFILE.into())]);
            assert_eq!(
                &inv.args[2..],
                &["build", file, "-o", "graph"].map(OsString::from)
            );
        }
        assert_eq!(calls.get(), 1);
    }

    #[test]
    fn pure_forwarded_help_and_version_skip_probe_without_rewriting_arguments() {
        let input = env();
        for flag in ["--help", "-h", "--version", "-V"] {
            let args = [OsString::from(flag)];
            let configured = build_env_with(&input, &jar(), &args, |_, _| {
                panic!("informational request")
            })
            .unwrap();
            assert!(matches!(configured, Cow::Borrowed(_)));
            let inv = crate::build::invocation(&configured, &jar(), &args).unwrap();
            assert_eq!(
                inv.args,
                ["-jar", "/fixture/graphite.jar", "build", flag].map(OsString::from)
            );
            assert_eq!(inv.env, vec![("JAVA_TOOL_OPTIONS".into(), "-Xmx8g".into())]);
        }
        for args in [
            vec!["--", "--help"],
            vec!["--include", "--help"],
            vec!["input.jar", "-o", "output", "--help"],
        ] {
            let args: Vec<_> = args.into_iter().map(OsString::from).collect();
            let called = Cell::new(false);
            build_env_with(&input, &jar(), &args, |_, _| {
                called.set(true);
                Ok(false)
            })
            .unwrap();
            assert!(called.get());
        }
    }

    #[test]
    fn all_explicit_options_bypass_detection_and_empty_values_keep_existing_behavior() {
        for name in EXPLICIT_OPTIONS {
            let mut input = env();
            input
                .vars
                .insert(name.into(), " -Xmx2g -XX:+UseSerialGC ".into());
            let before = input.vars.clone();
            let configured =
                build_env_with(&input, &jar(), &[], |_, _| panic!("explicit {name}")).unwrap();
            assert!(matches!(configured, Cow::Borrowed(_)));
            assert_eq!(configured.vars, before);
        }
        let mut input = env();
        for name in EXPLICIT_OPTIONS {
            input.vars.insert(name.into(), OsString::new());
        }
        let configured = build_env_with(&input, &jar(), &[], |_, _| Ok(true)).unwrap();
        assert_eq!(
            frontend::default_java_tool_options(&configured),
            Some(PROFILE.into())
        );
        assert_eq!(input.vars["JAVA_OPTS"], OsString::new());
    }

    #[test]
    fn unsupported_missing_java_and_executable_frontends_keep_the_old_path() {
        let input = env();
        let configured = build_env_with(&input, &jar(), &[], |_, _| Ok(false)).unwrap();
        assert!(matches!(&configured, Cow::Borrowed(_)));
        assert_eq!(
            frontend::default_java_tool_options(&configured),
            Some("-Xmx8g".into())
        );
        let missing = Env::default();
        assert!(matches!(
            build_env_with(&missing, &jar(), &[], |_, _| panic!("missing Java")).unwrap(),
            Cow::Borrowed(_)
        ));
        let launcher = Frontend {
            launch: Launch::Executable("/fixture/launcher".into()),
            ..jar()
        };
        assert!(matches!(
            build_env_with(&input, &launcher, &[], |_, _| panic!("launcher")).unwrap(),
            Cow::Borrowed(_)
        ));
        assert!(
            build_env_with(&input, &jar(), &[], |_, _| Err(io::Error::other(
                "cleanup failed"
            )))
            .is_err()
        );
    }

    #[cfg(unix)]
    fn fake_java(body: &str) -> Capture {
        use std::io::Write;
        use std::os::unix::fs::PermissionsExt;
        let mut script = Capture::new().unwrap();
        writeln!(script.file(), "#!/bin/sh\n{body}").unwrap();
        script.file().flush().unwrap();
        std::fs::set_permissions(&script.path, std::fs::Permissions::from_mode(0o700)).unwrap();
        // Linux refuses exec while a writable descriptor still holds the script.
        drop(script.file.take());
        script
    }

    #[test]
    #[cfg(unix)]
    fn probe_checks_exit_status_effective_values_and_exact_requested_options() {
        let _spawn_guard = SPAWN_LOCK.lock().unwrap_or_else(|error| error.into_inner());
        let args_log = Capture::new().unwrap();
        let mut input = env();
        input
            .vars
            .insert("ARGS_LOG".into(), args_log.path.clone().into_os_string());
        let fake = fake_java(&format!(
            "printf '%s\\n' \"$@\" > \"$ARGS_LOG\"\nprintf '%s' '{FLAGS}'"
        ));
        assert!(probe_with_limits(&fake.path, &input, Duration::from_secs(2), MAX_OUTPUT).unwrap());
        let args = std::fs::read_to_string(&args_log.path).unwrap();
        let expected: Vec<_> = PROFILE
            .split_ascii_whitespace()
            .chain(["-XX:+PrintFlagsFinal", "-version"])
            .collect();
        assert_eq!(args.lines().collect::<Vec<_>>(), expected);
        let rejected = fake_java(&format!("printf '%s' '{FLAGS}'; exit 1"));
        assert!(
            !probe_with_limits(&rejected.path, &input, Duration::from_secs(2), MAX_OUTPUT).unwrap()
        );
        let ignored = fake_java("printf '%s' 'OpenJ9 ignored unsupported options'; exit 0");
        assert!(
            !probe_with_limits(&ignored.path, &input, Duration::from_secs(2), MAX_OUTPUT).unwrap()
        );
        assert!(!probe_with_limits(
            Path::new("/no/such/java"),
            &input,
            Duration::from_secs(2),
            MAX_OUTPUT
        )
        .unwrap());
    }

    #[test]
    #[cfg(unix)]
    fn build_run_probes_once_then_exports_profile_and_preserves_build_failure() {
        let _spawn_guard = SPAWN_LOCK.lock().unwrap_or_else(|error| error.into_inner());
        let log = Capture::new().unwrap();
        let fake = fake_java(&format!(
            "if [ \"$1\" = '-Xmx8g' ]; then\n  printf 'probe\\n' >> '{}'\n  printf '%s' '{FLAGS}'\nelse\n  printf 'build:%s\\n' \"$JAVA_TOOL_OPTIONS\" >> '{}'\n  printf '%s\\n' \"$@\" >> '{}'\n  exit 23\nfi",
            log.path.display(), log.path.display(), log.path.display()
        ));
        let mut input = env();
        input.vars.insert(
            frontend::JAVA_VAR.into(),
            fake.path.clone().into_os_string(),
        );
        input.vars.insert(
            frontend::JVM_FRONTEND_VAR.into(),
            "/fixture/graphite.jar".into(),
        );
        let original = input.vars.clone();
        let args = ["input.jar", "-o", "graph-directory"].map(OsString::from);
        assert_eq!(crate::build::run(&input, &args), 23);
        assert_eq!(input.vars, original);
        assert_eq!(std::fs::read_to_string(&log.path).unwrap(), format!(
            "probe\nbuild:{PROFILE}\n-jar\n/fixture/graphite.jar\nbuild\ninput.jar\n-o\ngraph-directory\n"
        ));
    }

    #[test]
    #[cfg(unix)]
    fn probe_timeout_and_output_limit_reap_the_direct_child() {
        let _spawn_guard = SPAWN_LOCK.lock().unwrap_or_else(|error| error.into_inner());
        let pid_log = Capture::new().unwrap();
        let mut input = env();
        input
            .vars
            .insert("PID_LOG".into(), pid_log.path.clone().into_os_string());
        // exec ensures the sleeper is the owned child, not an orphaned shell child.
        let sleeper = fake_java("printf '%s' \"$$\" > \"$PID_LOG\"; exec /bin/sleep 30");
        assert!(!probe_with_limits(&sleeper.path, &input, PROBE_TIMEOUT, MAX_OUTPUT).unwrap());
        let pid = std::fs::read_to_string(&pid_log.path).unwrap();
        assert!(
            pid.parse::<u32>().is_ok(),
            "timeout child did not record a PID: {pid:?}"
        );
        let sleeper_pid = pid.parse::<u32>().unwrap();
        assert!(!Command::new("/bin/kill")
            .args(["-0", &pid])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .unwrap()
            .success());
        std::fs::write(&pid_log.path, []).unwrap();
        let excessive = fake_java(
            "printf '%s' \"$$\" > \"$PID_LOG\"; while :; do printf '0123456789abcdef'; done",
        );
        assert!(!probe_with_limits(&excessive.path, &input, PROBE_TIMEOUT, 128).unwrap());
        let pid = std::fs::read_to_string(&pid_log.path).unwrap();
        assert!(
            pid.parse::<u32>().is_ok(),
            "output-limit child did not record a PID: {pid:?}"
        );
        assert_ne!(pid.parse::<u32>().unwrap(), sleeper_pid);
        assert!(!Command::new("/bin/kill")
            .args(["-0", &pid])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .unwrap()
            .success());
    }

    #[test]
    fn capture_removes_its_file_after_closing_the_handle() {
        let capture = Capture::new().unwrap();
        let path = capture.path.clone();
        assert!(path.is_file());
        drop(capture);
        assert!(!path.exists());
    }

    #[test]
    #[cfg(unix)]
    fn non_utf8_explicit_options_are_not_reinterpreted() {
        use std::os::unix::ffi::OsStringExt;
        let mut input = env();
        input
            .vars
            .insert("JAVA_OPTS".into(), OsString::from_vec(vec![0xff]));
        let configured =
            build_env_with(&input, &jar(), &[], |_, _| panic!("explicit non-UTF8")).unwrap();
        assert_eq!(
            frontend::default_java_tool_options(&configured),
            Some(OsString::from_vec(vec![0xff]))
        );
    }
}

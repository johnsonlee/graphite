//! Child processes own a process group. Every exit path reaps and kills descendants.
use anyhow::{Context, Result};
use serde::Serialize;
use std::{
    collections::BTreeMap,
    ffi::OsString,
    fs::{self, File},
    path::PathBuf,
    process::{Child, Command, ExitStatus, Stdio},
    sync::atomic::{AtomicBool, AtomicI32, Ordering},
    thread,
    time::{Duration, Instant},
};

static CANCELLED: AtomicBool = AtomicBool::new(false);
// Fixed slots let the async-signal-safe handler kill children even while the
// controller is blocked consuming an HTTP response. No allocation or lock there.
static PROCESS_GROUPS: [AtomicI32; 32] = [const { AtomicI32::new(0) }; 32];

// Remove inherited benchmark tuning before applying this controller's fixed
// per-command environment. Toolchain locations and transport/proxy setup remain.
const CLEARED_TUNING: &[&str] = &[
    "RAYON_NUM_THREADS",
    "RAYON_RS_NUM_CPUS",
    "GRAPHITE_NO_FASTPATH",
    "MALLOC_CONF",
    "_RJEM_MALLOC_CONF",
    "JEMALLOC_CONF",
    "MALLOC_ARENA_MAX",
    "MALLOC_TRIM_THRESHOLD_",
    "GLIBC_TUNABLES",
    "LD_PRELOAD",
    "LD_LIBRARY_PATH",
    "DYLD_INSERT_LIBRARIES",
    "DYLD_LIBRARY_PATH",
    "DYLD_FRAMEWORK_PATH",
    "DYLD_FALLBACK_LIBRARY_PATH",
    "JAVA_TOOL_OPTIONS",
    "_JAVA_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "JAVA_OPTS",
    "GRADLE_OPTS",
];

#[derive(Debug)]
pub struct ProcessSpec {
    pub label: String,
    pub program: PathBuf,
    pub args: Vec<OsString>,
    pub cwd: Option<PathBuf>,
    pub env: BTreeMap<OsString, OsString>,
    pub stdout: PathBuf,
    pub stderr: PathBuf,
    pub timeout: Duration,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProcessOutcome {
    pub success: bool,
    pub exit_code: Option<i32>,
    pub reason: String,
    pub elapsed_ms: f64,
}

pub struct ManagedProcess {
    child: Child,
    started: Instant,
    timeout: Duration,
    outcome: Option<ProcessOutcome>,
    signal_slot: usize,
}

#[cfg(unix)]
extern "C" fn cancelled(_: libc::c_int) {
    CANCELLED.store(true, Ordering::SeqCst);
    for group in &PROCESS_GROUPS {
        let pid = group.load(Ordering::SeqCst);
        if pid > 0 {
            unsafe {
                libc::kill(-pid, libc::SIGKILL);
            }
        }
    }
}

/// Install once at the executable boundary; library tests do not mutate signal handlers.
pub fn install_signal_handlers() -> Result<()> {
    #[cfg(unix)]
    unsafe {
        let mut action: libc::sigaction = std::mem::zeroed();
        action.sa_sigaction = cancelled as *const () as usize;
        libc::sigemptyset(&mut action.sa_mask);
        if libc::sigaction(libc::SIGINT, &action, std::ptr::null_mut()) != 0
            || libc::sigaction(libc::SIGTERM, &action, std::ptr::null_mut()) != 0
        {
            return Err(std::io::Error::last_os_error().into());
        }
    }
    Ok(())
}

pub fn is_cancelled() -> bool {
    CANCELLED.load(Ordering::SeqCst)
}

fn log_file(path: &PathBuf) -> Result<File> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    File::create(path).with_context(|| format!("create log {}", path.display()))
}

impl ManagedProcess {
    pub fn spawn(spec: &ProcessSpec) -> Result<Self> {
        anyhow::ensure!(!is_cancelled(), "cancelled before {}", spec.label);
        anyhow::ensure!(!spec.timeout.is_zero(), "process timeout must be positive");
        let mut command = Command::new(&spec.program);
        for variable in CLEARED_TUNING {
            command.env_remove(variable);
        }
        command
            .args(&spec.args)
            .envs(&spec.env)
            .stdin(Stdio::null())
            .stdout(Stdio::from(log_file(&spec.stdout)?))
            .stderr(Stdio::from(log_file(&spec.stderr)?));
        if let Some(cwd) = &spec.cwd {
            command.current_dir(cwd);
        }
        #[cfg(unix)]
        {
            use std::os::unix::process::CommandExt;
            command.process_group(0);
        }
        let started = Instant::now();
        let mut child = command
            .spawn()
            .with_context(|| format!("start {} ({})", spec.label, spec.program.display()))?;
        let slot = PROCESS_GROUPS.iter().position(|slot| {
            slot.compare_exchange(0, child.id() as i32, Ordering::SeqCst, Ordering::SeqCst)
                .is_ok()
        });
        let Some(signal_slot) = slot else {
            #[cfg(unix)]
            unsafe {
                libc::kill(-(child.id() as i32), libc::SIGKILL);
            }
            let _ = child.kill();
            let _ = child.wait();
            anyhow::bail!("too many supervised processes");
        };
        let mut process = Self {
            child,
            started,
            timeout: spec.timeout,
            outcome: None,
            signal_slot,
        };
        // Close the race between the pre-spawn cancellation check and registration.
        if is_cancelled() {
            process.stop("cancelled")?;
        }
        Ok(process)
    }

    pub fn id(&self) -> u32 {
        self.child.id()
    }

    fn kill_group(&mut self) {
        #[cfg(unix)]
        unsafe {
            libc::kill(-(self.child.id() as i32), libc::SIGKILL);
        }
        let _ = self.child.kill();
    }

    fn finish(&mut self, status: ExitStatus, reason: &str) -> ProcessOutcome {
        // A parent can exit while its fork remains alive; retire the complete group.
        self.kill_group();
        PROCESS_GROUPS[self.signal_slot].store(0, Ordering::SeqCst);
        let result = ProcessOutcome {
            success: reason == "exited" && status.success(),
            exit_code: status.code(),
            reason: reason.to_owned(),
            elapsed_ms: self.started.elapsed().as_secs_f64() * 1000.0,
        };
        self.outcome = Some(result.clone());
        result
    }

    fn stop(&mut self, reason: &str) -> Result<ProcessOutcome> {
        if let Some(outcome) = &self.outcome {
            return Ok(outcome.clone());
        }
        self.kill_group();
        let status = self.child.wait()?;
        Ok(self.finish(status, reason))
    }

    pub fn terminate(&mut self) -> Result<ProcessOutcome> {
        self.stop("terminated")
    }

    pub fn try_wait(&mut self) -> Result<Option<ProcessOutcome>> {
        if let Some(outcome) = &self.outcome {
            return Ok(Some(outcome.clone()));
        }
        if is_cancelled() {
            return self.stop("cancelled").map(Some);
        }
        if self.started.elapsed() >= self.timeout {
            return self.stop("timeout").map(Some);
        }
        if let Some(status) = self.child.try_wait()? {
            return Ok(Some(self.finish(status, "exited")));
        }
        Ok(None)
    }

    pub fn wait(&mut self) -> Result<ProcessOutcome> {
        loop {
            if let Some(outcome) = self.try_wait()? {
                return Ok(outcome);
            }
            thread::sleep(Duration::from_millis(20));
        }
    }
}

impl Drop for ManagedProcess {
    fn drop(&mut self) {
        if self.outcome.is_none() {
            self.kill_group();
            let _ = self.child.wait();
            PROCESS_GROUPS[self.signal_slot].store(0, Ordering::SeqCst);
        }
    }
}

pub fn run(spec: &ProcessSpec) -> Result<ProcessOutcome> {
    ManagedProcess::spawn(spec)?.wait()
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicU64;
    static NEXT: AtomicU64 = AtomicU64::new(0);

    fn spec(script: &str, timeout: Duration) -> ProcessSpec {
        let directory = std::env::temp_dir().join(format!(
            "graphite-process-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        ProcessSpec {
            label: "test".into(),
            program: "/bin/sh".into(),
            args: vec!["-c".into(), script.into()],
            cwd: None,
            env: BTreeMap::new(),
            stdout: directory.join("stdout"),
            stderr: directory.join("stderr"),
            timeout,
        }
    }

    #[test]
    fn nonzero_is_failure_and_both_logs_survive() {
        let spec = spec(
            "printf evidence; printf failure >&2; exit 7",
            Duration::from_secs(5),
        );
        let outcome = run(&spec).unwrap();
        assert!(!outcome.success);
        assert_eq!(outcome.exit_code, Some(7));
        assert_eq!(outcome.reason, "exited");
        assert_eq!(fs::read_to_string(&spec.stdout).unwrap(), "evidence");
        assert_eq!(fs::read_to_string(&spec.stderr).unwrap(), "failure");
        fs::remove_dir_all(spec.stdout.parent().unwrap()).unwrap();
    }

    #[test]
    fn timeout_kills_fork_and_never_becomes_success() {
        let spec = spec("(sleep 0.3; echo orphan) & wait", Duration::from_millis(60));
        let outcome = run(&spec).unwrap();
        assert!(!outcome.success);
        assert_eq!(outcome.reason, "timeout");
        thread::sleep(Duration::from_millis(350));
        assert_eq!(fs::read_to_string(&spec.stdout).unwrap(), "");
        fs::remove_dir_all(spec.stdout.parent().unwrap()).unwrap();
    }

    #[test]
    fn successful_parent_cannot_leave_background_work() {
        let spec = spec("(sleep 0.3; echo orphan) & exit 0", Duration::from_secs(5));
        assert!(run(&spec).unwrap().success);
        thread::sleep(Duration::from_millis(350));
        assert_eq!(fs::read_to_string(&spec.stdout).unwrap(), "");
        fs::remove_dir_all(spec.stdout.parent().unwrap()).unwrap();
    }

    #[test]
    fn dropping_server_stops_child() {
        let spec = spec("sleep 30", Duration::from_secs(60));
        let process = ManagedProcess::spawn(&spec).unwrap();
        let id = process.id();
        drop(process);
        assert_eq!(unsafe { libc::kill(id as i32, 0) }, -1);
        fs::remove_dir_all(spec.stdout.parent().unwrap()).unwrap();
    }

    #[test]
    fn cancellation_kills_forks_while_controller_is_blocked() {
        const CHILD: &str = "GRAPHITE_SIGNAL_TEST_DIRECTORY";
        if let Some(directory) = std::env::var_os(CHILD) {
            install_signal_handlers().unwrap();
            let mut spec = spec(
                "(while [ ! -f \"$1\" ]; do sleep 0.01; done; echo orphan) & echo ready; wait",
                Duration::from_secs(5),
            );
            spec.args.push("signal-test".into());
            spec.args.push(
                PathBuf::from(&directory)
                    .join("release-fork")
                    .into_os_string(),
            );
            spec.stdout = PathBuf::from(&directory).join("child.stdout");
            spec.stderr = PathBuf::from(&directory).join("child.stderr");
            let mut process = ManagedProcess::spawn(&spec).unwrap();
            // Simulate the supervisor blocked in result consumption, without polling.
            thread::sleep(Duration::from_millis(700));
            let outcome = process.wait().unwrap();
            assert!(!outcome.success);
            assert_eq!(outcome.reason, "cancelled");
            return;
        }
        let spec = spec("", Duration::from_secs(5));
        let directory = spec.stdout.parent().unwrap();
        fs::create_dir_all(directory).unwrap();
        let mut child = Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "process::tests::cancellation_kills_forks_while_controller_is_blocked",
                "--nocapture",
            ])
            .env(CHILD, directory)
            .stdout(Stdio::from(File::create(&spec.stdout).unwrap()))
            .stderr(Stdio::from(File::create(&spec.stderr).unwrap()))
            .spawn()
            .unwrap();
        let ready = directory.join("child.stdout");
        let deadline = Instant::now() + Duration::from_secs(5);
        while !fs::read_to_string(&ready).is_ok_and(|s| s.contains("ready")) {
            assert!(Instant::now() < deadline, "child never became ready");
            thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(unsafe { libc::kill(child.id() as i32, libc::SIGTERM) }, 0);
        // A surviving fork would now write. The parent cannot race a short sleep.
        fs::write(directory.join("release-fork"), "release").unwrap();
        assert!(
            child.wait().unwrap().success(),
            "{}",
            fs::read_to_string(&spec.stderr).unwrap()
        );
        assert_eq!(fs::read_to_string(ready).unwrap(), "ready\n");
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn inherited_tuning_is_removed_but_fixed_options_and_toolchains_survive() {
        const CHILD: &str = "GRAPHITE_ENVIRONMENT_TEST_CHILD";
        if std::env::var_os(CHILD).is_some() {
            let mut spec = spec(
                "printf '%s\\n' \"${RAYON_NUM_THREADS-unset}\" \"${GRAPHITE_NO_FASTPATH-unset}\" \"${_JAVA_OPTIONS-unset}\" \"$JAVA_TOOL_OPTIONS\" \"$JAVA_HOME\" \"$HTTP_PROXY\"",
                Duration::from_secs(5),
            );
            spec.env.insert(
                "JAVA_TOOL_OPTIONS".into(),
                "-Xmx4g -javaagent:fixed.jar".into(),
            );
            assert!(run(&spec).unwrap().success);
            assert_eq!(fs::read_to_string(&spec.stdout).unwrap(),
                "unset\nunset\nunset\n-Xmx4g -javaagent:fixed.jar\n/toolchain/java\nhttp://proxy.invalid:3128\n");
            fs::remove_dir_all(spec.stdout.parent().unwrap()).unwrap();
            return;
        }
        // A subprocess avoids mutating global environment during parallel tests.
        let output = Command::new(std::env::current_exe().unwrap())
            .args(["--exact", "process::tests::inherited_tuning_is_removed_but_fixed_options_and_toolchains_survive", "--nocapture"])
            .env(CHILD, "1")
            .env("RAYON_NUM_THREADS", "99")
            .env("GRAPHITE_NO_FASTPATH", "1")
            .env("_JAVA_OPTIONS", "-Xmx100g")
            .env("JAVA_TOOL_OPTIONS", "-Xmx100g")
            .env("JAVA_HOME", "/toolchain/java")
            .env("HTTP_PROXY", "http://proxy.invalid:3128")
            .output().unwrap();
        assert!(
            output.status.success(),
            "{}\n{}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
    }
}

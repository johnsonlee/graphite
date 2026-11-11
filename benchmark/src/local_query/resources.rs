//! Process-owned resource evidence, never controller/children-wide accounting.
use anyhow::{Context, Result};
use serde_json::{json, Value};

#[cfg(target_os = "macos")]
pub fn snapshot(pid: u32) -> Result<Value> {
    // Darwin rusage_info_v2: UUID followed by eighteen uint64 fields. The ABI
    // uses nanoseconds for CPU and bytes for resident size (not ru_maxrss).
    #[repr(C)]
    struct Info {
        uuid: [u8; 16],
        fields: [u64; 18],
    }
    unsafe extern "C" {
        fn proc_pid_rusage(pid: i32, flavor: i32, buffer: *mut std::ffi::c_void) -> i32;
    }
    let mut info = Info {
        uuid: [0; 16],
        fields: [0; 18],
    };
    let rc = unsafe { proc_pid_rusage(pid as i32, 2, (&mut info as *mut Info).cast()) };
    anyhow::ensure!(
        rc == 0,
        "proc_pid_rusage({pid}): {}",
        std::io::Error::last_os_error()
    );
    Ok(json!({"pid":pid,"userSeconds":info.fields[0] as f64 / 1e9,
        "systemSeconds":info.fields[1] as f64 / 1e9,"residentBytes":info.fields[6],
        "residentMetric":"instantaneous RSS, not peak","source":"proc_pid_rusage v2"}))
}

#[cfg(target_os = "linux")]
pub fn snapshot(pid: u32) -> Result<Value> {
    let stat = std::fs::read_to_string(format!("/proc/{pid}/stat"))?;
    let fields: Vec<_> = stat
        .rsplit_once(')')
        .context("invalid proc stat")?
        .1
        .split_whitespace()
        .collect();
    let ticks = unsafe { libc::sysconf(libc::_SC_CLK_TCK) };
    let page = unsafe { libc::sysconf(libc::_SC_PAGESIZE) };
    anyhow::ensure!(ticks > 0 && page > 0, "invalid sysconf resource units");
    Ok(
        json!({"pid":pid,"userSeconds":fields[11].parse::<u64>()? as f64 / ticks as f64,
        "systemSeconds":fields[12].parse::<u64>()? as f64 / ticks as f64,
        "residentBytes":fields[21].parse::<u64>()? * page as u64,
        "residentMetric":"instantaneous RSS, not peak","source":"proc stat"}),
    )
}

#[cfg(not(any(target_os = "linux", target_os = "macos")))]
pub fn snapshot(_: u32) -> Result<Value> {
    anyhow::bail!("resource snapshots require macOS or Linux")
}

pub fn cpu_delta(before: &Value, after: &Value) -> Result<f64> {
    let total = |value: &Value| -> Result<f64> {
        Ok(value["userSeconds"].as_f64().context("missing user CPU")?
            + value["systemSeconds"]
                .as_f64()
                .context("missing system CPU")?)
    };
    let delta = total(after)? - total(before)?;
    anyhow::ensure!(delta >= 0.0, "process CPU moved backwards");
    Ok(delta)
}

#[cfg(unix)]
pub fn lifetime(usage: &libc::rusage) -> Value {
    let seconds = |v: libc::timeval| v.tv_sec as f64 + v.tv_usec as f64 / 1e6;
    let unit = if cfg!(target_os = "macos") { 1 } else { 1024 };
    json!({"userSeconds":seconds(usage.ru_utime),"systemSeconds":seconds(usage.ru_stime),
        "cpuSeconds":seconds(usage.ru_utime)+seconds(usage.ru_stime),
        "kernelPeakRssBytes":usage.ru_maxrss as u64 * unit,
        "source":"wait4 exact child lifetime; peak includes loading and all executed stages"})
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn process_snapshot_and_cpu_units() {
        let a = snapshot(std::process::id()).unwrap();
        let b = snapshot(std::process::id()).unwrap();
        assert_eq!(a["pid"].as_u64(), Some(std::process::id() as u64));
        assert!(a["residentBytes"].as_u64().unwrap() > 0);
        assert!(cpu_delta(&a, &b).unwrap() >= 0.0);
        assert_eq!(
            cpu_delta(
                &json!({"userSeconds":1.0,"systemSeconds":2.0}),
                &json!({"userSeconds":2.5,"systemSeconds":3.0})
            )
            .unwrap(),
            2.5
        );
        assert!(cpu_delta(&b, &json!({"userSeconds":-1.0,"systemSeconds":0.0})).is_err());
    }
    #[test]
    fn kernel_usage_units_are_platform_specific() {
        let mut usage: libc::rusage = unsafe { std::mem::zeroed() };
        usage.ru_utime.tv_sec = 1;
        usage.ru_utime.tv_usec = 250_000;
        usage.ru_stime.tv_usec = 500_000;
        usage.ru_maxrss = 123;
        let value = lifetime(&usage);
        assert_eq!(value["cpuSeconds"], 1.75);
        assert_eq!(
            value["kernelPeakRssBytes"],
            if cfg!(target_os = "macos") {
                123
            } else {
                123 * 1024
            }
        );
    }
}

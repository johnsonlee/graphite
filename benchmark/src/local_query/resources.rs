//! Process-owned resource evidence, never controller/children-wide accounting.
use anyhow::{Context, Result};
use serde_json::{json, Value};

#[cfg(target_os = "macos")]
fn mach_timebase() -> Result<(u32, u32)> {
    #[repr(C)]
    struct Timebase {
        numer: u32,
        denom: u32,
    }
    unsafe extern "C" {
        fn mach_timebase_info(info: *mut Timebase) -> i32;
    }
    let mut info = Timebase { numer: 0, denom: 0 };
    let status = unsafe { mach_timebase_info(&mut info) };
    anyhow::ensure!(
        status == 0 && info.numer > 0 && info.denom > 0,
        "mach_timebase_info failed: {status}, {}/{}",
        info.numer,
        info.denom
    );
    Ok((info.numer, info.denom))
}

#[cfg(target_os = "macos")]
fn mach_seconds(ticks: u64, numer: u32, denom: u32) -> f64 {
    ticks as f64 * f64::from(numer) / f64::from(denom) / 1e9
}

#[cfg(target_os = "macos")]
pub fn snapshot(pid: u32) -> Result<Value> {
    // Darwin rusage_info_v2: UUID followed by eighteen uint64 fields. The ABI
    // returns CPU in Mach absolute-time ticks, not nanoseconds. On Apple Silicon
    // the timebase is commonly 125/3; assuming 1/1 silently undercounts CPU.
    // Resident size is bytes. wait4's timeval CPU uses seconds/microseconds.
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
    let (numer, denom) = mach_timebase()?;
    Ok(
        json!({"pid":pid,"userSeconds":mach_seconds(info.fields[0], numer, denom),
        "systemSeconds":mach_seconds(info.fields[1], numer, denom),"residentBytes":info.fields[6],
        "rawUserTicks":info.fields[0],"rawSystemTicks":info.fields[1],
        "cpuTimeUnit":"Mach absolute-time ticks","timebaseNumer":numer,"timebaseDenom":denom,
        "residentMetric":"instantaneous RSS, not peak","source":"proc_pid_rusage v2"}),
    )
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
    #[cfg(target_os = "macos")]
    #[test]
    fn mach_cpu_conversion_matches_independent_getrusage_bracket() {
        fn self_cpu() -> f64 {
            let mut usage: libc::rusage = unsafe { std::mem::zeroed() };
            assert_eq!(unsafe { libc::getrusage(libc::RUSAGE_SELF, &mut usage) }, 0);
            lifetime(&usage)["cpuSeconds"].as_f64().unwrap()
        }
        // Ensure enough CPU has accrued to expose a wrong timebase even when
        // this test is the only selected test. This is an accounting correctness
        // test; elapsed durations are not benchmark or performance evidence.
        let start = self_cpu();
        let mut state = 1u64;
        while self_cpu() - start < 0.02 {
            for _ in 0..10_000 {
                state =
                    std::hint::black_box(state.wrapping_mul(6364136223846793005).wrapping_add(1));
            }
        }
        std::hint::black_box(state);
        let before = self_cpu();
        let snapshot = snapshot(std::process::id()).unwrap();
        let after = self_cpu();
        let observed =
            snapshot["userSeconds"].as_f64().unwrap() + snapshot["systemSeconds"].as_f64().unwrap();
        // Kernel accounting samples may differ by a microsecond of rounding.
        assert!(
            observed >= before - 0.001 && observed <= after + 0.001,
            "CPU snapshot {observed} outside getrusage bracket {before}..{after}: {snapshot}"
        );
        let (numer, denom) = mach_timebase().unwrap();
        assert_eq!(snapshot["timebaseNumer"], numer);
        assert_eq!(snapshot["timebaseDenom"], denom);
        assert_eq!(
            snapshot["userSeconds"].as_f64().unwrap(),
            mach_seconds(snapshot["rawUserTicks"].as_u64().unwrap(), numer, denom)
        );
        assert_eq!(mach_seconds(24_000_000, 125, 3), 1.0);
    }
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

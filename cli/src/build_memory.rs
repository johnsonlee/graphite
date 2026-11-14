//! Physical/container memory capacity for automatic builds.
//!
//! This module detects capacity, not current availability or a heap reserve policy.
//! Unknown capacity returns `None`; detected zero remains `Some(0)`.
#[cfg(any(target_os = "linux", test))]
use std::path::{Component, Path, PathBuf};

#[cfg(target_os = "linux")]
pub(super) fn effective_memory_bytes() -> Option<u64> {
    linux_memory_bytes(|path| std::fs::read_to_string(path))
}

#[cfg(target_os = "macos")]
pub(super) fn effective_memory_bytes() -> Option<u64> {
    use std::ffi::{c_char, c_int, c_void};
    unsafe extern "C" {
        fn sysctlbyname(
            name: *const c_char,
            oldp: *mut c_void,
            oldlenp: *mut usize,
            newp: *mut c_void,
            newlen: usize,
        ) -> c_int;
    }
    let mut total = 0u64;
    let mut length = std::mem::size_of::<u64>();
    // SAFETY: hw.memsize returns uint64_t into a writable, correctly sized
    // buffer, and sysctlbyname is synchronous. newp=null makes this read-only.
    let status = unsafe {
        sysctlbyname(
            c"hw.memsize".as_ptr(),
            (&mut total as *mut u64).cast(),
            &mut length,
            std::ptr::null_mut(),
            0,
        )
    };
    (status == 0 && length == std::mem::size_of::<u64>()).then_some(total)
}

#[cfg(target_os = "windows")]
pub(super) fn effective_memory_bytes() -> Option<u64> {
    #[repr(C)]
    struct MemoryStatusEx {
        length: u32,
        memory_load: u32,
        total_physical: u64,
        available_physical: u64,
        total_page_file: u64,
        available_page_file: u64,
        total_virtual: u64,
        available_virtual: u64,
        available_extended_virtual: u64,
    }
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn GlobalMemoryStatusEx(status: *mut MemoryStatusEx) -> i32;
    }
    let mut status = MemoryStatusEx {
        length: std::mem::size_of::<MemoryStatusEx>() as u32,
        memory_load: 0,
        total_physical: 0,
        available_physical: 0,
        total_page_file: 0,
        available_page_file: 0,
        total_virtual: 0,
        available_virtual: 0,
        available_extended_virtual: 0,
    };
    // SAFETY: repr(C) matches MEMORYSTATUSEX and dwLength describes the writable
    // allocation. Only ullTotalPhys is used for the capacity policy.
    if unsafe { GlobalMemoryStatusEx(&mut status) } == 0 {
        return None;
    }
    Some(status.total_physical)
}

#[cfg(not(any(target_os = "linux", target_os = "macos", target_os = "windows")))]
pub(super) fn effective_memory_bytes() -> Option<u64> {
    None
}

#[cfg(any(target_os = "linux", test))]
fn meminfo_bytes(text: &str, name: &str) -> Option<u64> {
    text.lines().find_map(|line| {
        let mut fields = line.split_whitespace();
        if fields.next()? != name {
            return None;
        }
        let value = fields.next()?.parse::<u64>().ok()?;
        (fields.next()? == "kB" && fields.next().is_none())
            .then(|| value.checked_mul(1024))
            .flatten()
    })
}

#[cfg(any(target_os = "linux", test))]
fn finite_limit(text: &str) -> Option<u64> {
    text.trim().parse().ok()
}

#[cfg(any(target_os = "linux", test))]
fn mount_path(text: &str) -> Option<PathBuf> {
    // mountinfo escapes whitespace and backslashes with octal sequences.
    let mut bytes = Vec::with_capacity(text.len());
    let mut input = text.as_bytes().iter().copied();
    while let Some(byte) = input.next() {
        if byte == b'\\' {
            let a = input.next()?;
            let b = input.next()?;
            let c = input.next()?;
            if ![a, b, c].iter().all(|byte| (b'0'..=b'7').contains(byte)) {
                return None;
            }
            let escaped = u16::from(a - b'0') * 64 + u16::from(b - b'0') * 8 + u16::from(c - b'0');
            bytes.push(u8::try_from(escaped).ok()?);
        } else {
            bytes.push(byte);
        }
    }
    // Kernel cgroup paths are generally UTF-8. Unrepresentable paths are an
    // unavailable observation rather than a reason to inspect a different path.
    let path = PathBuf::from(String::from_utf8(bytes).ok()?);
    (path.is_absolute()
        && !path
            .components()
            .any(|part| matches!(part, Component::ParentDir)))
    .then_some(path)
}

#[cfg(any(target_os = "linux", test))]
fn linux_memory_bytes(read: impl Fn(&Path) -> std::io::Result<String>) -> Option<u64> {
    let mut total = meminfo_bytes(&read(Path::new("/proc/meminfo")).ok()?, "MemTotal:")?;
    let cgroups = read(Path::new("/proc/self/cgroup")).ok()?;
    let mounts = read(Path::new("/proc/self/mountinfo")).ok()?;
    for line in cgroups.lines() {
        let fields: Vec<_> = line.splitn(3, ':').collect();
        if fields.len() != 3 {
            return None;
        }
        let v2 = fields[0] == "0" && fields[1].is_empty();
        if !v2 && !fields[1].split(',').any(|name| name == "memory") {
            continue;
        }
        let group = mount_path(fields[2])?;
        let mut resolved = false;
        for mount in mounts.lines() {
            let Some((before, after)) = mount.split_once(" - ") else {
                continue;
            };
            let before: Vec<_> = before.split_whitespace().collect();
            let after: Vec<_> = after.split_whitespace().collect();
            if before.len() < 6 || after.len() < 3 {
                continue;
            }
            if (v2 && after[0] != "cgroup2")
                || (!v2
                    && (after[0] != "cgroup" || !after[2].split(',').any(|name| name == "memory")))
            {
                continue;
            }
            let (Some(root), Some(mountpoint)) = (mount_path(before[3]), mount_path(before[4]))
            else {
                continue;
            };
            let Ok(relative) = group.strip_prefix(&root) else {
                continue;
            };
            let mut current = mountpoint.join(relative);
            let file = if v2 {
                "memory.max"
            } else {
                "memory.limit_in_bytes"
            };
            // A finite ancestor can constrain a leaf whose own limit is "max".
            loop {
                match read(&current.join(file)) {
                    Ok(text) => {
                        if !(v2 && text.trim() == "max") {
                            total = total.min(finite_limit(&text)?);
                        }
                    }
                    Err(error) if v2 && error.kind() == std::io::ErrorKind::NotFound => {
                        // The cgroup2 hierarchy root has no memory.max. A child
                        // without the delegated memory controller also has no
                        // limit file, but its ancestors can still constrain it.
                        // Read controller state so an unavailable mount cannot
                        // silently masquerade as an unlimited hierarchy.
                        let controllers = read(&current.join("cgroup.controllers")).ok()?;
                        let hierarchy_root = current == mountpoint && root == Path::new("/");
                        if !hierarchy_root
                            && controllers.split_whitespace().any(|name| name == "memory")
                        {
                            return None;
                        }
                    }
                    Err(_) => return None,
                }
                if current == mountpoint || !current.pop() || !current.starts_with(&mountpoint) {
                    break;
                }
            }
            resolved = true;
        }
        if !resolved {
            return None;
        }
    }
    Some(total)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;
    const GIB: u64 = 1024 * 1024 * 1024;

    fn memory(files: &[(&str, &str)]) -> Option<u64> {
        let files: BTreeMap<_, _> = files.iter().copied().collect();
        linux_memory_bytes(|path| {
            path.to_str()
                .and_then(|path| files.get(path))
                .map(|text| text.to_string())
                .ok_or_else(|| std::io::Error::from(std::io::ErrorKind::NotFound))
        })
    }

    #[test]
    fn physical_capacity_does_not_depend_on_available_memory() {
        assert_eq!(memory(&[]), None);
        for (info, expected) in [
            ("MemTotal: 8388608 kB", 8 * GIB),
            ("MemTotal: 8388608 kB\nMemAvailable: 0 kB", 8 * GIB),
            ("MemTotal: 0 kB", 0),
        ] {
            assert_eq!(
                memory(&[
                    ("/proc/meminfo", info),
                    ("/proc/self/cgroup", "5:cpu:/worker"),
                    ("/proc/self/mountinfo", "")
                ]),
                Some(expected)
            );
        }
    }

    #[test]
    fn meminfo_parser_checks_units_and_overflow() {
        assert_eq!(
            meminfo_bytes("MemFree: 40 kB\nMemTotal: 16777216 kB\n", "MemTotal:"),
            Some(16 * GIB)
        );
        for text in [
            "MemTotal: 2 MB",
            "MemTotal: 2",
            "MemTotal: -2 kB",
            "MemTotal: 18446744073709551615 kB",
        ] {
            assert_eq!(meminfo_bytes(text, "MemTotal:"), None, "{text}");
        }
    }

    #[test]
    fn cgroup_v2_minimum_includes_ancestors_and_ignores_current_usage() {
        let files = [
            ("/proc/meminfo", "MemTotal: 67108864 kB"),
            ("/proc/self/cgroup", "0::/tenant/workload\n"),
            (
                "/proc/self/mountinfo",
                "29 23 0:26 / /sys/fs/cgroup rw - cgroup2 cgroup rw\n",
            ),
            ("/sys/fs/cgroup/tenant/workload/memory.max", "max"),
            (
                "/sys/fs/cgroup/tenant/workload/memory.current",
                "1073741824",
            ),
            ("/sys/fs/cgroup/tenant/memory.max", "12884901888"),
            ("/sys/fs/cgroup/tenant/memory.current", "12884901888"),
            ("/sys/fs/cgroup/memory.max", "17179869184"),
        ];
        assert_eq!(memory(&files), Some(12 * GIB));
    }

    #[test]
    fn v1_mount_root_escaped_mountpoint_and_unlimited_value() {
        let files = [
            ("/proc/meminfo", "MemTotal: 8388608 kB"),
            (
                "/proc/self/cgroup",
                "5:cpu:/ignored\n7:memory,blkio:/docker/abc/worker\n",
            ),
            (
                "/proc/self/mountinfo",
                "31 23 0:28 /docker/abc /memory\\040mount rw - cgroup cgroup rw,memory,blkio\n",
            ),
            ("/memory mount/worker/memory.limit_in_bytes", "4294967296"),
            ("/memory mount/memory.limit_in_bytes", "9223372036854771712"),
        ];
        assert_eq!(memory(&files), Some(4 * GIB));
    }

    #[test]
    fn malformed_cgroup_limit_fails_closed_and_zero_remains_zero() {
        let mut files = vec![
            ("/proc/meminfo", "MemTotal: 8388608 kB"),
            ("/proc/self/cgroup", "0::/\n"),
            (
                "/proc/self/mountinfo",
                "29 23 0:26 / /sys/fs/cgroup rw - cgroup2 cgroup rw\n",
            ),
            ("/sys/fs/cgroup/memory.max", "broken"),
        ];
        assert_eq!(memory(&files), None);
        files[3].1 = "0";
        assert_eq!(memory(&files), Some(0));
        files[3].1 = "max";
        assert_eq!(memory(&files), Some(8 * GIB));
        assert_eq!(mount_path("/a/../b"), None);
        assert_eq!(mount_path("relative"), None);
        assert_eq!(mount_path("/bad\\999"), None);
    }
    #[test]
    fn missing_or_unresolved_hierarchy_never_falls_back_to_host_ram() {
        let info = ("/proc/meminfo", "MemTotal: 67108864 kB");
        let group = ("/proc/self/cgroup", "0::/tenant");
        let mount = (
            "/proc/self/mountinfo",
            "29 23 0:26 / /sys/fs/cgroup rw - cgroup2 cgroup rw",
        );
        assert_eq!(memory(&[info]), None);
        assert_eq!(memory(&[info, group]), None);
        assert_eq!(memory(&[info, mount]), None);
        assert_eq!(memory(&[info, group, ("/proc/self/mountinfo", "")]), None);
        assert_eq!(memory(&[info, group, mount]), None);
        assert_eq!(
            memory(&[
                info,
                group,
                (
                    "/proc/self/mountinfo",
                    "29 23 0:26 /different /sys/fs/cgroup rw - cgroup2 cgroup rw"
                )
            ]),
            None
        );
        assert_eq!(
            memory(&[
                info,
                group,
                mount,
                ("/sys/fs/cgroup/tenant/cgroup.controllers", "memory cpu")
            ]),
            None
        );
        assert_eq!(
            memory(&[
                info,
                ("/proc/self/cgroup", "7:memory:/"),
                (
                    "/proc/self/mountinfo",
                    "31 23 0:28 / /memory rw - cgroup cgroup rw,memory"
                )
            ]),
            None
        );
    }

    #[test]
    fn v2_root_without_limit_is_valid_only_with_controller_state() {
        let files = [
            ("/proc/meminfo", "MemTotal: 67108864 kB"),
            ("/proc/self/cgroup", "0::/"),
            (
                "/proc/self/mountinfo",
                "29 23 0:26 / /sys/fs/cgroup rw - cgroup2 cgroup rw",
            ),
            ("/sys/fs/cgroup/cgroup.controllers", "cpu io memory pids"),
        ];
        assert_eq!(memory(&files), Some(64 * GIB));
    }

    #[test]
    fn undelegated_leaf_still_obeys_finite_ancestor() {
        let files = [
            ("/proc/meminfo", "MemTotal: 67108864 kB"),
            ("/proc/self/cgroup", "0::/tenant/worker"),
            (
                "/proc/self/mountinfo",
                "29 23 0:26 / /sys/fs/cgroup rw - cgroup2 cgroup rw",
            ),
            (
                "/sys/fs/cgroup/tenant/worker/cgroup.controllers",
                "cpu pids",
            ),
            ("/sys/fs/cgroup/tenant/memory.max", "12884901888"),
            ("/sys/fs/cgroup/cgroup.controllers", "cpu memory pids"),
        ];
        assert_eq!(memory(&files), Some(12 * GIB));
    }

    #[test]
    fn unreadable_limit_is_unknown_even_when_controller_state_is_readable() {
        let result = linux_memory_bytes(|path| {
            let text = match path.to_str().unwrap() {
                "/proc/meminfo" => "MemTotal: 67108864 kB",
                "/proc/self/cgroup" => "0::/",
                "/proc/self/mountinfo" => "29 23 0:26 / /sys/fs/cgroup rw - cgroup2 cgroup rw",
                "/sys/fs/cgroup/cgroup.controllers" => "cpu memory",
                _ => return Err(std::io::ErrorKind::PermissionDenied.into()),
            };
            Ok(text.into())
        });
        assert_eq!(result, None);
    }
}

//! Acceptance configuration is code, never an environment or CLI override.
use std::time::Duration;

pub const SCHEMA: &str = "graphite-benchmark-suite-v1";
pub const DEFAULT_HEAP_BYTES: u64 = 4 * 1024 * 1024 * 1024;
pub const APK_HEAP_BYTES: u64 = 8 * 1024 * 1024 * 1024;
pub const JMH_TIMEOUT: Duration = Duration::from_secs(7200);
pub const SERVER_TIMEOUT: Duration = Duration::from_secs(7200);
pub const REQUEST_TIMEOUT: Duration = Duration::from_secs(310);
pub const NATIVE_SERVER_TIMEOUT: Duration = SERVER_TIMEOUT;
pub const NATIVE_REQUEST_TIMEOUT: Duration = REQUEST_TIMEOUT;
pub const NATIVE_STARTUP_TIMEOUT: Duration = Duration::from_secs(300);

pub const REQUIRED_SUITES: &[&str] = &["frontend-build-save", "native-real64"];

pub const FIXTURES: &[(&str, &str, &str, u64)] = &[
    (
        "android-all-",
        "android.jar",
        "6be2218c6a53fe3c57bc22ebdc723edcb7270a8a6f187545708aa5c0ed813977",
        137666235,
    ),
    (
        "tika-app-",
        "tika.jar",
        "87e06f88c801fcb2beae5f15e707241edb14da468a154ad78be4e31ff982c3da",
        60900523,
    ),
    (
        "hive-exec-",
        "hive.jar",
        "232d67c5d2ff54806944bb5b7402eaf1ebb81f11dbe4fd51bc5604a8e0c0bdad",
        84163106,
    ),
    (
        "kotlin-compiler-embeddable-",
        "kotlin-compiler.jar",
        "9fa8cdd1de0dccffe154c997d423ec6b5f53cd6d9177e3a77a9b0de03fb1bc81",
        58272093,
    ),
];

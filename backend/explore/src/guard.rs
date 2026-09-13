//! Cypher concurrency guard: bounded parallelism, per-request timeouts, cancellation.
//!
//! Queries run on a blocking pool so a long scan never stalls the async runtime. A
//! permit is held until the response has been serialized, so capacity reflects work
//! actually in flight.

use graphite_cypher::engine::CancelToken;
use graphite_cypher::CypherError;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use tokio::sync::{OwnedSemaphorePermit, Semaphore};

pub const DEFAULT_MAX_CONCURRENT_CYPHER: usize = 4;
pub const DEFAULT_CYPHER_MAX_TIMEOUT_MILLIS: u64 = 60_000;
pub const DEFAULT_CYPHER_ROW_LIMIT: i64 = 1000;
pub const MAX_CYPHER_ROW_LIMIT: i64 = 5000;

/// Outcome buckets reported to `/metrics`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    Success,
    Cancelled,
    Timeout,
    BudgetExceeded,
    Failed,
}

impl Outcome {
    pub fn tag(self) -> &'static str {
        match self {
            Outcome::Success => "success",
            Outcome::Cancelled => "cancelled",
            Outcome::Timeout => "timeout",
            Outcome::BudgetExceeded => "budget_exceeded",
            Outcome::Failed => "failed",
        }
    }
    pub fn of(err: Option<&CypherError>) -> Outcome {
        match err {
            None => Outcome::Success,
            // Timeout is checked before cancellation: it is a subtype in Kotlin.
            Some(CypherError::Timeout(_)) => Outcome::Timeout,
            Some(CypherError::Cancelled) => Outcome::Cancelled,
            Some(CypherError::BudgetExceeded(_)) => Outcome::BudgetExceeded,
            Some(_) => Outcome::Failed,
        }
    }
}

/// Service level objectives for `graphite.cypher.query.duration`, in nanoseconds.
///
/// The same ladder the Kotlin server configures through Micrometer's
/// `serviceLevelObjectives`, so the timer scrapes as a histogram with identical bucket
/// boundaries and a dashboard binds to either server unchanged.
pub const DURATION_SLO_NANOS: [u64; 8] = [
    10_000_000,
    50_000_000,
    100_000_000,
    500_000_000,
    1_000_000_000,
    5_000_000_000,
    30_000_000_000,
    120_000_000_000,
];

#[derive(Default)]
pub struct GuardMetrics {
    pub rejected: AtomicU64,
    pub active: AtomicU64,
    pub duration_nanos: [AtomicU64; 5],
    pub counts: [AtomicU64; 5],
    /// Per outcome, how many observations fell at or below each SLO.
    pub buckets: [[AtomicU64; DURATION_SLO_NANOS.len()]; 5],
    /// Per outcome, the longest observation seen.
    pub max_nanos: [AtomicU64; 5],
}

impl GuardMetrics {
    fn slot(o: Outcome) -> usize {
        match o {
            Outcome::Success => 0,
            Outcome::Cancelled => 1,
            Outcome::Timeout => 2,
            Outcome::BudgetExceeded => 3,
            Outcome::Failed => 4,
        }
    }
    pub fn record(&self, o: Outcome, nanos: u64) {
        let i = Self::slot(o);
        self.duration_nanos[i].fetch_add(nanos, Ordering::Relaxed);
        self.counts[i].fetch_add(1, Ordering::Relaxed);
        // Cumulative, as Prometheus histograms are: every bucket the observation is at
        // or below counts it.
        for (b, slo) in self.buckets[i].iter().zip(DURATION_SLO_NANOS) {
            if nanos <= slo {
                b.fetch_add(1, Ordering::Relaxed);
            }
        }
        self.max_nanos[i].fetch_max(nanos, Ordering::Relaxed);
    }

    /// Per outcome: cumulative bucket counts and the longest observation.
    pub fn histogram(&self, o: Outcome) -> ([u64; DURATION_SLO_NANOS.len()], u64) {
        let i = Self::slot(o);
        let mut counts = [0u64; DURATION_SLO_NANOS.len()];
        for (out, b) in counts.iter_mut().zip(self.buckets[i].iter()) {
            *out = b.load(Ordering::Relaxed);
        }
        (counts, self.max_nanos[i].load(Ordering::Relaxed))
    }
    pub fn snapshot(&self) -> Vec<(Outcome, u64, u64)> {
        [
            Outcome::Success,
            Outcome::Cancelled,
            Outcome::Timeout,
            Outcome::BudgetExceeded,
            Outcome::Failed,
        ]
        .into_iter()
        .map(|o| {
            let i = Self::slot(o);
            (
                o,
                self.counts[i].load(Ordering::Relaxed),
                self.duration_nanos[i].load(Ordering::Relaxed),
            )
        })
        .collect()
    }
}

pub struct CypherGuard {
    permits: Arc<Semaphore>,
    pub max_concurrent: usize,
    pub max_timeout_millis: u64,
    pub metrics: GuardMetrics,
}

/// Raised when every permit is taken; the caller maps it to HTTP 429.
pub struct ConcurrencyLimit(pub usize);

impl ConcurrencyLimit {
    pub fn message(&self) -> String {
        format!(
            "Cypher concurrency limit reached ({} active queries); retry later",
            self.0
        )
    }
}

pub struct QueryPermit {
    _permit: OwnedSemaphorePermit,
    pub cancel: Arc<CancelToken>,
    pub timeout_millis: u64,
}

impl CypherGuard {
    pub fn new(max_concurrent: usize, max_timeout_millis: u64) -> CypherGuard {
        assert!(max_concurrent > 0, "maxConcurrent must be positive");
        assert!(max_timeout_millis > 0, "maxTimeoutMillis must be positive");
        CypherGuard {
            permits: Arc::new(Semaphore::new(max_concurrent)),
            max_concurrent,
            max_timeout_millis,
            metrics: GuardMetrics::default(),
        }
    }

    /// Effective timeout: the client's request, capped by the server maximum.
    pub fn effective_timeout(&self, requested: Option<u64>) -> u64 {
        requested
            .map(|t| t.min(self.max_timeout_millis))
            .unwrap_or(self.max_timeout_millis)
    }

    /// Take a permit without waiting. Returns `Err` when at capacity.
    pub fn try_acquire(
        &self,
        requested_timeout: Option<u64>,
    ) -> Result<QueryPermit, ConcurrencyLimit> {
        match self.permits.clone().try_acquire_owned() {
            Ok(permit) => {
                let timeout_millis = self.effective_timeout(requested_timeout);
                self.metrics.active.fetch_add(1, Ordering::Relaxed);
                Ok(QueryPermit {
                    _permit: permit,
                    cancel: CancelToken::with_timeout(timeout_millis),
                    timeout_millis,
                })
            }
            Err(_) => {
                self.metrics.rejected.fetch_add(1, Ordering::Relaxed);
                Err(ConcurrencyLimit(self.max_concurrent))
            }
        }
    }

    pub fn finish(&self, outcome: Outcome, nanos: u64) {
        self.metrics.active.fetch_sub(1, Ordering::Relaxed);
        self.metrics.record(outcome, nanos);
    }
}

/// Clamp a row limit into `0..=MAX_CYPHER_ROW_LIMIT`.
pub fn bounded_row_limit(raw: Option<&str>) -> i64 {
    bounded_limit(raw, DEFAULT_CYPHER_ROW_LIMIT, MAX_CYPHER_ROW_LIMIT)
}

pub fn bounded_limit(raw: Option<&str>, default: i64, max: i64) -> i64 {
    raw.and_then(|s| s.parse::<i64>().ok())
        .unwrap_or(default)
        .clamp(0, max)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn timeout_is_capped_by_the_server_maximum() {
        let g = CypherGuard::new(2, 1000);
        assert_eq!(g.effective_timeout(None), 1000);
        assert_eq!(g.effective_timeout(Some(500)), 500);
        assert_eq!(g.effective_timeout(Some(9999)), 1000);
    }

    #[test]
    fn permits_are_bounded_and_released() {
        let g = CypherGuard::new(1, 1000);
        let p = g.try_acquire(None).ok().expect("first permit");
        assert!(g.try_acquire(None).is_err());
        drop(p);
        g.finish(Outcome::Success, 1);
        assert!(g.try_acquire(None).is_ok());
    }

    #[test]
    fn concurrency_message_matches_kotlin() {
        assert_eq!(
            ConcurrencyLimit(4).message(),
            "Cypher concurrency limit reached (4 active queries); retry later"
        );
    }

    #[test]
    fn timeout_outranks_cancellation_when_classifying() {
        assert_eq!(Outcome::of(None), Outcome::Success);
        assert_eq!(
            Outcome::of(Some(&CypherError::Timeout(5))),
            Outcome::Timeout
        );
        assert_eq!(
            Outcome::of(Some(&CypherError::Cancelled)),
            Outcome::Cancelled
        );
        assert_eq!(
            Outcome::of(Some(&CypherError::Runtime("x".into()))),
            Outcome::Failed
        );
    }

    #[test]
    fn row_limits_are_clamped() {
        assert_eq!(bounded_row_limit(None), 1000);
        assert_eq!(bounded_row_limit(Some("50")), 50);
        assert_eq!(bounded_row_limit(Some("99999")), 5000);
        assert_eq!(bounded_row_limit(Some("-5")), 0);
        assert_eq!(bounded_row_limit(Some("junk")), 1000);
    }
}

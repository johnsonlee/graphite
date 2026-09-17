//! Runtime and HTTP performance metrics for `/metrics`.
//!
//! The Kotlin server got these from Micrometer's JVM, system and Jetty binders. This
//! binary has no JVM and no Jetty, so the families are the Prometheus-native ones a
//! process exports about itself (`process_*`, read from `/proc`), an HTTP request
//! histogram by route template (`http_server_requests_seconds`), and what the server
//! knows about the graphs it serves (`graphite_graphs_loaded`, `graphite_graph_*`).
//! The Cypher families stay in `guard.rs`; everything here is appended after them.
//!
//! Labels never carry a graph id, a query, a path parameter or anything else a request
//! chooses: the `uri` label is the matched route template, and at most
//! [`MAX_URI_VALUES`] distinct templates are ever recorded.

use crate::guard::DURATION_SLO_NANOS;
use crate::routes::AppState;
use axum::extract::{MatchedPath, Request, State};
use axum::middleware::Next;
use axum::response::Response;
use parking_lot::Mutex;
use std::collections::BTreeMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Instant;

/// Distinct `uri` label values recorded before further templates are dropped, as the
/// Kotlin server's `MeterFilter.maximumAllowableTags` does at the same number.
pub const MAX_URI_VALUES: usize = 64;

/// `uri` for a request no route matched.
const NOT_FOUND_URI: &str = "NOT_FOUND";

/// One `http_server_requests_seconds` series: method, route template, status.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub struct HttpKey {
    pub method: String,
    pub uri: String,
    pub status: u16,
}

impl HttpKey {
    /// Micrometer's `outcome` tag, from the status class.
    pub fn outcome(&self) -> &'static str {
        match self.status / 100 {
            1 => "INFORMATIONAL",
            2 => "SUCCESS",
            3 => "REDIRECTION",
            4 => "CLIENT_ERROR",
            _ => "SERVER_ERROR",
        }
    }
}

/// A cumulative histogram over [`DURATION_SLO_NANOS`] plus count, sum and max.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Series {
    pub buckets: [u64; DURATION_SLO_NANOS.len()],
    pub count: u64,
    pub sum_nanos: u64,
    pub max_nanos: u64,
}

impl Series {
    fn observe(&mut self, nanos: u64) {
        for (b, slo) in self.buckets.iter_mut().zip(DURATION_SLO_NANOS) {
            if nanos <= slo {
                *b += 1;
            }
        }
        self.count += 1;
        self.sum_nanos += nanos;
        self.max_nanos = self.max_nanos.max(nanos);
    }
}

/// HTTP request metrics, one series per (method, uri, status).
#[derive(Default)]
pub struct HttpMetrics {
    series: Mutex<BTreeMap<HttpKey, Series>>,
    /// Requests accepted and not yet answered.
    pub in_flight: AtomicU64,
    /// Requests dropped because their route template would have been the
    /// `MAX_URI_VALUES + 1`th distinct `uri`.
    pub dropped: AtomicU64,
}

impl HttpMetrics {
    pub fn record(&self, key: HttpKey, nanos: u64) {
        let mut series = self.series.lock();
        if !series.contains_key(&key) {
            let uris: std::collections::BTreeSet<&str> =
                series.keys().map(|k| k.uri.as_str()).collect();
            if uris.len() >= MAX_URI_VALUES && !uris.contains(key.uri.as_str()) {
                self.dropped.fetch_add(1, Ordering::Relaxed);
                return;
            }
        }
        series.entry(key).or_default().observe(nanos);
    }

    pub fn snapshot(&self) -> Vec<(HttpKey, Series)> {
        self.series
            .lock()
            .iter()
            .map(|(k, s)| (k.clone(), s.clone()))
            .collect()
    }
}

/// Which JSON-RPC methods `/mcp` counts. Anything else is `other`, so the label set
/// is fixed however a client names its methods.
pub const MCP_METHODS: [&str; 5] = ["initialize", "ping", "tools/list", "tools/call", "other"];

/// One `graphite_mcp_tool_duration_seconds` series: tool name and outcome.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub struct McpToolKey {
    /// A name from `mcp::tools()`; an unknown tool is refused before it is timed.
    pub tool: &'static str,
    /// `ok` when the tool answered, `error` when it returned `isError`.
    pub ok: bool,
}

impl McpToolKey {
    pub fn outcome(&self) -> &'static str {
        if self.ok {
            "ok"
        } else {
            "error"
        }
    }
}

/// MCP metrics over `POST /mcp`: requests by JSON-RPC method and tool calls by tool and
/// outcome. Both label sets are closed (the method list above, the tool list in
/// `mcp::tools()`), so a client can never create a series. The stdio transport
/// (`graphite mcp`) has no `/metrics` and records nothing.
#[derive(Default)]
pub struct McpMetrics {
    requests: Mutex<BTreeMap<&'static str, u64>>,
    tools: Mutex<BTreeMap<McpToolKey, Series>>,
}

impl McpMetrics {
    /// Count one JSON-RPC request; a method outside [`MCP_METHODS`] counts as `other`.
    pub fn record_request(&self, method: &str) {
        let label = MCP_METHODS
            .iter()
            .copied()
            .find(|m| *m == method && *m != "other")
            .unwrap_or("other");
        *self.requests.lock().entry(label).or_default() += 1;
    }

    /// Time one tool call that reached its tool.
    pub fn record_tool(&self, tool: &'static str, ok: bool, nanos: u64) {
        self.tools
            .lock()
            .entry(McpToolKey { tool, ok })
            .or_default()
            .observe(nanos);
    }

    pub fn requests(&self) -> Vec<(&'static str, u64)> {
        self.requests.lock().iter().map(|(k, v)| (*k, *v)).collect()
    }

    pub fn tools(&self) -> Vec<(McpToolKey, Series)> {
        self.tools
            .lock()
            .iter()
            .map(|(k, s)| (k.clone(), s.clone()))
            .collect()
    }
}

/// Tower middleware: time every request and record it under its route template.
pub async fn record_http(State(state): State<Arc<AppState>>, req: Request, next: Next) -> Response {
    let method = req.method().as_str().to_string();
    let uri = req
        .extensions()
        .get::<MatchedPath>()
        .map(|p| p.as_str().to_string())
        .unwrap_or_else(|| NOT_FOUND_URI.to_string());
    let metrics = &state.http_metrics;
    metrics.in_flight.fetch_add(1, Ordering::Relaxed);
    let started = Instant::now();
    let response = next.run(req).await;
    metrics.in_flight.fetch_sub(1, Ordering::Relaxed);
    let key = HttpKey {
        method,
        uri,
        status: response.status().as_u16(),
    };
    metrics.record(key, started.elapsed().as_nanos() as u64);
    response
}

/// What `/proc` says about this process, or `None` off Linux.
#[derive(Debug, Clone, PartialEq)]
pub struct ProcessSample {
    pub cpu_seconds: f64,
    pub threads: u64,
    pub resident_bytes: u64,
    pub virtual_bytes: u64,
    pub open_fds: u64,
    pub max_fds: Option<u64>,
    pub load_average_1m: Option<f64>,
}

/// Parse `/proc/self/stat` (`pid (comm) state ...`), `/proc/self/limits`, a count of
/// `/proc/self/fd` entries and `/proc/loadavg`. Split from the reads so the parsing
/// is testable from captured text.
pub fn parse_process(
    stat: &str,
    limits: &str,
    open_fds: u64,
    loadavg: &str,
    clock_ticks: f64,
    page_size: u64,
) -> Option<ProcessSample> {
    // The command name is parenthesised and may itself hold spaces or parentheses:
    // fields start after the last `)`.
    let rest = &stat[stat.rfind(')')? + 1..];
    let fields: Vec<&str> = rest.split_whitespace().collect();
    // Fields are numbered from 1 in proc(5); `rest` starts at field 3 (state).
    let field = |n: usize| fields.get(n - 3).copied();
    let utime: f64 = field(14)?.parse().ok()?;
    let stime: f64 = field(15)?.parse().ok()?;
    let threads: u64 = field(20)?.parse().ok()?;
    let vsize: u64 = field(23)?.parse().ok()?;
    let rss_pages: u64 = field(24)?.parse().ok()?;
    let max_fds = limits
        .lines()
        .find(|l| l.starts_with("Max open files"))
        .and_then(|l| l.split_whitespace().nth(3)?.parse::<u64>().ok());
    let load_average_1m = loadavg
        .split_whitespace()
        .next()
        .and_then(|v| v.parse::<f64>().ok());
    Some(ProcessSample {
        cpu_seconds: (utime + stime) / clock_ticks,
        threads,
        resident_bytes: rss_pages * page_size,
        virtual_bytes: vsize,
        open_fds,
        max_fds,
        load_average_1m,
    })
}

#[cfg(target_os = "linux")]
pub fn sample_process() -> Option<ProcessSample> {
    let stat = std::fs::read_to_string("/proc/self/stat").ok()?;
    let limits = std::fs::read_to_string("/proc/self/limits").unwrap_or_default();
    let open_fds = std::fs::read_dir("/proc/self/fd").ok()?.count() as u64;
    let loadavg = std::fs::read_to_string("/proc/loadavg").unwrap_or_default();
    // SAFETY: sysconf reads a constant and has no preconditions.
    let ticks = unsafe { libc_sysconf(libc_SC_CLK_TCK) };
    let page = unsafe { libc_sysconf(libc_SC_PAGESIZE) };
    parse_process(
        &stat,
        &limits,
        open_fds,
        &loadavg,
        if ticks > 0 { ticks as f64 } else { 100.0 },
        if page > 0 { page as u64 } else { 4096 },
    )
}

#[cfg(not(target_os = "linux"))]
pub fn sample_process() -> Option<ProcessSample> {
    None
}

#[cfg(target_os = "linux")]
extern "C" {
    #[link_name = "sysconf"]
    fn libc_sysconf(name: i32) -> i64;
}
#[cfg(target_os = "linux")]
#[allow(non_upper_case_globals)]
const libc_SC_CLK_TCK: i32 = 2;
#[cfg(target_os = "linux")]
#[allow(non_upper_case_globals)]
const libc_SC_PAGESIZE: i32 = 30;

/// Render everything this module knows, in exposition format, for appending after the
/// Cypher families. `fmt` renders a double the way the Cypher families do.
pub fn render(state: &AppState, fmt: &dyn Fn(f64) -> String) -> String {
    let mut out = String::new();
    let mut gauge = |name: &str, help: &str, value: String| {
        out.push_str(&format!(
            "# HELP {name} {help}\n# TYPE {name} gauge\n{name} {value}\n"
        ));
    };
    if let Some(p) = sample_process() {
        gauge(
            "process_cpu_seconds_total",
            "Total user and system CPU time spent in seconds.",
            fmt(p.cpu_seconds),
        );
        gauge(
            "process_resident_memory_bytes",
            "Resident memory size in bytes.",
            fmt(p.resident_bytes as f64),
        );
        gauge(
            "process_virtual_memory_bytes",
            "Virtual memory size in bytes.",
            fmt(p.virtual_bytes as f64),
        );
        gauge(
            "process_threads",
            "Number of OS threads in the process.",
            fmt(p.threads as f64),
        );
        gauge(
            "process_open_fds",
            "Number of open file descriptors.",
            fmt(p.open_fds as f64),
        );
        if let Some(max) = p.max_fds {
            gauge(
                "process_max_fds",
                "Maximum number of open file descriptors.",
                fmt(max as f64),
            );
        }
        if let Some(load) = p.load_average_1m {
            gauge(
                "system_load_average_1m",
                "The sum of the number of runnable entities queued to available processors and the number of runnable entities running on the available processors averaged over a period of time",
                fmt(load),
            );
        }
    }
    gauge(
        "system_cpu_count",
        "The number of processors available to the process",
        fmt(std::thread::available_parallelism().map_or(1, |n| n.get()) as f64),
    );

    let graphs = state.registry.list();
    let totals = state.registry.totals();
    gauge(
        "graphite_graphs_loaded",
        "Graphs currently served",
        fmt(graphs.len() as f64),
    );
    gauge(
        "graphite_graph_nodes",
        "Nodes across every served graph",
        fmt(totals.nodes as f64),
    );
    gauge(
        "graphite_graph_edges",
        "Edges across every served graph",
        fmt(totals.edges as f64),
    );
    gauge(
        "graphite_graph_mapped_bytes",
        "Bytes of served graphs that are memory-mapped rather than owned",
        fmt(graphs.iter().map(|g| g.graph.mapped_bytes()).sum::<u64>() as f64),
    );

    let http = &state.http_metrics;
    gauge(
        "http_server_requests_active",
        "HTTP requests accepted and not yet answered",
        fmt(http.in_flight.load(Ordering::Relaxed) as f64),
    );
    // Same shape as `graphite_cypher_query_duration_seconds`: counts as integers,
    // sums and gauges as doubles, one `_max` family apart.
    out.push_str("# HELP http_server_requests_seconds HTTP request duration by route template\n");
    out.push_str("# TYPE http_server_requests_seconds histogram\n");
    let snapshot = http.snapshot();
    for (k, s) in &snapshot {
        let labels = format!(
            "method=\"{}\",outcome=\"{}\",status=\"{}\",uri=\"{}\"",
            k.method,
            k.outcome(),
            k.status,
            k.uri
        );
        for (slo, at_or_below) in DURATION_SLO_NANOS.iter().zip(s.buckets) {
            out.push_str(&format!(
                "http_server_requests_seconds_bucket{{{labels},le=\"{}\"}} {at_or_below}\n",
                fmt(*slo as f64 / 1e9)
            ));
        }
        out.push_str(&format!(
            "http_server_requests_seconds_bucket{{{labels},le=\"+Inf\"}} {}\n",
            s.count
        ));
        out.push_str(&format!(
            "http_server_requests_seconds_count{{{labels}}} {}\n",
            s.count
        ));
        out.push_str(&format!(
            "http_server_requests_seconds_sum{{{labels}}} {}\n",
            fmt(s.sum_nanos as f64 / 1e9)
        ));
    }
    out.push_str(
        "# HELP http_server_requests_seconds_max HTTP request duration by route template\n",
    );
    out.push_str("# TYPE http_server_requests_seconds_max gauge\n");
    for (k, s) in &snapshot {
        out.push_str(&format!(
            "http_server_requests_seconds_max{{method=\"{}\",outcome=\"{}\",status=\"{}\",uri=\"{}\"}} {}\n",
            k.method,
            k.outcome(),
            k.status,
            k.uri,
            fmt(s.max_nanos as f64 / 1e9)
        ));
    }

    let mcp = &state.mcp_metrics;
    out.push_str("# HELP graphite_mcp_requests_total JSON-RPC requests over POST /mcp by method\n");
    out.push_str("# TYPE graphite_mcp_requests_total counter\n");
    for (method, count) in mcp.requests() {
        out.push_str(&format!(
            "graphite_mcp_requests_total{{method=\"{method}\"}} {count}\n"
        ));
    }
    let tools = mcp.tools();
    out.push_str(
        "# HELP graphite_mcp_tool_duration_seconds MCP tool call duration by tool and outcome\n",
    );
    out.push_str("# TYPE graphite_mcp_tool_duration_seconds histogram\n");
    for (k, s) in &tools {
        let labels = format!("outcome=\"{}\",tool=\"{}\"", k.outcome(), k.tool);
        for (slo, at_or_below) in DURATION_SLO_NANOS.iter().zip(s.buckets) {
            out.push_str(&format!(
                "graphite_mcp_tool_duration_seconds_bucket{{{labels},le=\"{}\"}} {at_or_below}\n",
                fmt(*slo as f64 / 1e9)
            ));
        }
        out.push_str(&format!(
            "graphite_mcp_tool_duration_seconds_bucket{{{labels},le=\"+Inf\"}} {}\n",
            s.count
        ));
        out.push_str(&format!(
            "graphite_mcp_tool_duration_seconds_count{{{labels}}} {}\n",
            s.count
        ));
        out.push_str(&format!(
            "graphite_mcp_tool_duration_seconds_sum{{{labels}}} {}\n",
            fmt(s.sum_nanos as f64 / 1e9)
        ));
    }
    out.push_str("# HELP graphite_mcp_tool_duration_seconds_max MCP tool call duration by tool and outcome\n");
    out.push_str("# TYPE graphite_mcp_tool_duration_seconds_max gauge\n");
    for (k, s) in &tools {
        out.push_str(&format!(
            "graphite_mcp_tool_duration_seconds_max{{outcome=\"{}\",tool=\"{}\"}} {}\n",
            k.outcome(),
            k.tool,
            fmt(s.max_nanos as f64 / 1e9)
        ));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const STAT: &str = "965 (grap hite) serve) R 960 965 960 0 -1 4194304 83 0 0 0 250 50 0 0 20 0 7 0 35969 2920448 367 18446744073709551615 1 2 3 0 0 0 0 0 0 0 0 0 17 1 0 0 0 0 0 1 2 3 4 5 6 7 0";
    const LIMITS: &str = "Limit                     Soft Limit           Hard Limit           Units     \nMax cpu time              unlimited            unlimited            seconds   \nMax open files            20000                40000                files     \n";

    #[test]
    fn process_stat_is_parsed_past_a_command_name_with_spaces_and_parentheses() {
        let p = parse_process(STAT, LIMITS, 12, "0.25 0.10 0.05 1/105 966\n", 100.0, 4096)
            .expect("parsed");
        assert_eq!(
            p,
            ProcessSample {
                cpu_seconds: 3.0,
                threads: 7,
                resident_bytes: 367 * 4096,
                virtual_bytes: 2920448,
                open_fds: 12,
                max_fds: Some(20000),
                load_average_1m: Some(0.25),
            }
        );
        // Missing pieces degrade to None rather than to a refused sample.
        let p = parse_process(STAT, "", 3, "", 100.0, 4096);
        assert_eq!(p.as_ref().map(|p| p.max_fds), Some(None));
        assert_eq!(p.map(|p| p.load_average_1m), Some(None));
        assert_eq!(parse_process("garbage", LIMITS, 0, "", 100.0, 4096), None);
        assert_eq!(parse_process("1 (x) R 1", LIMITS, 0, "", 100.0, 4096), None);
    }

    #[test]
    fn a_live_sample_describes_this_process() {
        let Some(p) = sample_process() else {
            return; // not Linux
        };
        assert!(p.threads >= 1);
        assert!(p.resident_bytes > 0);
        assert!(p.open_fds >= 3);
    }

    #[test]
    fn http_series_are_cumulative_and_keyed_by_route_template() {
        let m = HttpMetrics::default();
        let key = |uri: &str, status: u16| HttpKey {
            method: "GET".into(),
            uri: uri.into(),
            status,
        };
        m.record(key("/api/graphs", 200), 5_000_000);
        m.record(key("/api/graphs", 200), 60_000_000);
        m.record(key("/api/graphs/{graphId}", 404), 200_000_000);
        let snapshot = m.snapshot();
        assert_eq!(snapshot.len(), 2);
        let (k, s) = &snapshot[0];
        assert_eq!(k.uri, "/api/graphs");
        assert_eq!(k.outcome(), "SUCCESS");
        assert_eq!(s.count, 2);
        assert_eq!(s.sum_nanos, 65_000_000);
        assert_eq!(s.max_nanos, 60_000_000);
        // 5 ms is at or below every SLO; 60 ms only from the 100 ms boundary on.
        assert_eq!(s.buckets, [1, 1, 2, 2, 2, 2, 2, 2]);
        assert_eq!(snapshot[1].0.outcome(), "CLIENT_ERROR");
        assert_eq!(
            HttpKey {
                method: "GET".into(),
                uri: "/".into(),
                status: 500
            }
            .outcome(),
            "SERVER_ERROR"
        );
        assert_eq!(key("/", 302).outcome(), "REDIRECTION");
        assert_eq!(key("/", 101).outcome(), "INFORMATIONAL");
    }

    #[test]
    fn the_uri_label_is_capped_and_overflow_is_counted() {
        let m = HttpMetrics::default();
        for i in 0..MAX_URI_VALUES {
            m.record(
                HttpKey {
                    method: "GET".into(),
                    uri: format!("/r{i}"),
                    status: 200,
                },
                1,
            );
        }
        // A new status on a known template is a new series, not a new uri.
        m.record(
            HttpKey {
                method: "GET".into(),
                uri: "/r0".into(),
                status: 500,
            },
            1,
        );
        m.record(
            HttpKey {
                method: "GET".into(),
                uri: "/one-too-many".into(),
                status: 200,
            },
            1,
        );
        assert_eq!(m.snapshot().len(), MAX_URI_VALUES + 1);
        assert_eq!(m.dropped.load(Ordering::Relaxed), 1);
    }

    #[test]
    fn mcp_requests_count_by_a_closed_method_set_and_tools_by_tool_and_outcome() {
        let m = McpMetrics::default();
        for method in [
            "initialize",
            "ping",
            "tools/list",
            "tools/call",
            "tools/call",
        ] {
            m.record_request(method);
        }
        for method in ["resources/list", "other", "prompts/get"] {
            m.record_request(method);
        }
        let requests: BTreeMap<_, _> = m.requests().into_iter().collect();
        assert_eq!(requests["initialize"], 1);
        assert_eq!(requests["tools/call"], 2);
        assert_eq!(
            requests["other"], 3,
            "everything outside MCP_METHODS is `other`"
        );
        assert!(MCP_METHODS.contains(&"other"));

        m.record_tool("cypher", true, 40_000_000);
        m.record_tool("cypher", true, 2_000_000_000);
        m.record_tool("cypher", false, 5_000_000);
        let tools: BTreeMap<_, _> = m.tools().into_iter().collect();
        let ok = &tools[&McpToolKey {
            tool: "cypher",
            ok: true,
        }];
        assert_eq!(
            (ok.count, ok.sum_nanos, ok.max_nanos),
            (2, 2_040_000_000, 2_000_000_000)
        );
        // 40 ms is at or below the 50 ms objective; 2 s is at or below 5 s.
        assert_eq!(ok.buckets, [0, 1, 1, 1, 1, 2, 2, 2]);
        let err = &tools[&McpToolKey {
            tool: "cypher",
            ok: false,
        }];
        assert_eq!(err.count, 1);
        assert_eq!(
            McpToolKey {
                tool: "cypher",
                ok: false
            }
            .outcome(),
            "error"
        );
        assert_eq!(
            McpToolKey {
                tool: "cypher",
                ok: true
            }
            .outcome(),
            "ok"
        );
    }
}

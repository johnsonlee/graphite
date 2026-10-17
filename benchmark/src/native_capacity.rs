//! Network-level correctness probes, separate from all pressure measurements.
//! Native execution has deadlines, not a work budget or disconnect cancellation.
use super::{
    parse_json, registry, response, sha, Graph, MAX_RESPONSE_BYTES, PORT, STARTUP_TIMEOUT,
};
use crate::process::{ManagedProcess, ProcessSpec};
use anyhow::{ensure, Context, Result};
use reqwest::blocking::Client;
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};
use std::ffi::OsString;
use std::fs;
use std::io::Read;
use std::path::Path;
use std::time::{Duration, Instant};

const DEADLINE_MS: u64 = 10_000;
const HTTP_TIMEOUT: Duration = Duration::from_secs(30);
const POLL_DEADLINE: Duration = Duration::from_secs(5);
const RECOVERY_QUERY: &str = "RETURN 1 AS ok";

struct Reply {
    status: u16,
    retry_after: Option<String>,
    body: Vec<u8>,
}

fn exchange(client: &Client, url: &str, payload: Option<Value>, path: &Path) -> Result<Reply> {
    let mut bytes = Vec::new();
    let mut status = None;
    let mut retry_after = None;
    let operation = (|| -> Result<()> {
        let request = match payload {
            Some(ref value) => client.post(url).json(value),
            None => client.get(url),
        };
        let mut reply = request.header("Connection", "close").send()?;
        status = Some(reply.status().as_u16());
        retry_after = reply
            .headers()
            .get("retry-after")
            .and_then(|v| v.to_str().ok())
            .map(str::to_owned);
        (&mut reply)
            .take((MAX_RESPONSE_BYTES + 1) as u64)
            .read_to_end(&mut bytes)?;
        ensure!(
            bytes.len() <= MAX_RESPONSE_BYTES,
            "Capacity response exceeds body limit"
        );
        Ok(())
    })();
    fs::write(path, &bytes)?;
    fs::write(
        path.with_extension("receipt.json"),
        serde_json::to_vec_pretty(&json!({
            "status":status,"retryAfter":retry_after,"complete":operation.is_ok(),
            "bodyBytes":bytes.len(),"bodySha256":sha(&bytes),
            "error":operation.as_ref().err().map(|e|format!("{e:#}"))
        }))?,
    )?;
    operation?;
    Ok(Reply {
        status: status.context("Missing HTTP status")?,
        retry_after,
        body: bytes,
    })
}

fn metric(text: &str, name: &str) -> Result<u64> {
    let prefix = format!("{name} ");
    let values: Vec<_> = text
        .lines()
        .filter_map(|line| line.strip_prefix(&prefix))
        .collect();
    ensure!(values.len() == 1, "Missing/duplicate metric {name}");
    let number: f64 = values[0].parse()?;
    ensure!(
        number.is_finite() && number >= 0.0 && number.fract() == 0.0 && number < u64::MAX as f64,
        "Invalid counter {name}"
    );
    Ok(number as u64)
}

fn error_reply(reply: &Reply, status: u16, code: &str, timeout: Option<u64>) -> Result<()> {
    ensure!(
        reply.status == status,
        "Expected HTTP {status}, got {}",
        reply.status
    );
    let body = parse_json(&reply.body)?;
    ensure!(
        body["code"] == code && body["error"].as_str().is_some_and(|e| !e.is_empty()),
        "Wrong error contract: {body}"
    );
    if let Some(ms) = timeout {
        ensure!(
            body["timeoutMs"] == ms,
            "Server deadline cap not preserved: {body}"
        );
    }
    if code == "cypher_concurrency_limit" {
        ensure!(
            reply.retry_after.as_deref() == Some("1"),
            "Missing rejection Retry-After"
        );
    }
    Ok(())
}

fn recovery(reply: &Reply, ids: &BTreeSet<String>) -> Result<()> {
    ensure!(
        reply.status == 200,
        "Recovery request failed: {}",
        reply.status
    );
    let body = response(&reply.body, ids)?;
    ensure!(
        body["columns"] == json!(["ok"]) && body["rowCount"] == 1 && body["rows"][0]["ok"] == 1,
        "Recovery result differs: {body}"
    );
    Ok(())
}

fn metrics(client: &Client, output: &Path, name: &str) -> Result<String> {
    let reply = exchange(
        client,
        &format!("http://127.0.0.1:{PORT}/metrics"),
        None,
        &output.join(format!("{name}.txt")),
    )?;
    ensure!(reply.status == 200, "Metrics unavailable");
    Ok(String::from_utf8(reply.body)?)
}

fn poll_active(
    server: &mut ManagedProcess,
    client: &Client,
    output: &Path,
    expected: u64,
) -> Result<String> {
    let started = Instant::now();
    for attempt in 0.. {
        ensure!(server.try_wait()?.is_none(), "Capacity server exited");
        let text = metrics(client, output, &format!("active-{expected}-{attempt}"))?;
        if metric(&text, "graphite_cypher_queries_active")? == expected {
            return Ok(text);
        }
        ensure!(
            started.elapsed() < POLL_DEADLINE,
            "Did not observe {expected} active queries"
        );
        std::thread::sleep(Duration::from_millis(25));
    }
    unreachable!()
}

fn probe(binary: &Path, graphs: &[Graph], output: &Path) -> Result<()> {
    let ids: BTreeSet<_> = graphs.iter().map(|g| g.id.clone()).collect();
    ensure!(
        std::net::TcpListener::bind(("127.0.0.1", PORT)).is_ok(),
        "Capacity server port occupied"
    );
    let mut args: Vec<OsString> = [
        "serve",
        "--port",
        "18080",
        "--cypher-max-timeout-ms",
        "10000",
        "--max-concurrent-cypher",
        "4",
        "--load-mode",
        "MAPPED",
        "--metrics",
    ]
    .iter()
    .map(OsString::from)
    .collect();
    for graph in graphs {
        args.extend([
            "--graph".into(),
            format!("{}:{}", graph.id, graph.path.display()).into(),
        ]);
    }
    let mut server = ManagedProcess::spawn(&ProcessSpec {
        label: "native-capacity-correctness".into(),
        program: binary.canonicalize()?,
        args,
        cwd: None,
        env: BTreeMap::new(),
        stdout: output.join("stdout.log"),
        stderr: output.join("stderr.log"),
        timeout: STARTUP_TIMEOUT + Duration::from_secs(90),
    })?;
    let client = Client::builder()
        .timeout(HTTP_TIMEOUT)
        .pool_max_idle_per_host(0)
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .build()?;
    let readiness_client = Client::builder()
        .timeout(Duration::from_secs(2))
        .no_proxy()
        .build()?;
    let started = Instant::now();
    loop {
        ensure!(
            server.try_wait()?.is_none(),
            "Capacity server exited before readiness"
        );
        ensure!(
            started.elapsed() < STARTUP_TIMEOUT,
            "Capacity readiness deadline exceeded"
        );
        if let Ok(reply) = readiness_client
            .get(format!("http://127.0.0.1:{PORT}/api/graphs"))
            .send()
        {
            if reply.status().as_u16() == 200 {
                let bytes = reply.bytes()?;
                registry(&bytes, &ids)?;
                fs::write(output.join("registry.json"), bytes)?;
                break;
            }
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    let before = metrics(&client, output, "before")?;
    ensure!(
        metric(&before, "graphite_cypher_queries_active")? == 0
            && metric(&before, "graphite_cypher_queries_limit")? == 4,
        "Unexpected initial guard state"
    );
    let url = format!("http://127.0.0.1:{PORT}/api/cypher");
    // Correctness only: all four requests may finish immediately. Rejection and
    // expired-deadline contracts are tested at the production handler with a
    // deterministic test-only execution barrier, never a deliberately slow query.
    std::thread::scope(|scope| -> Result<()> {
        let workers: Vec<_> = (0..4)
            .map(|i| {
                let client = &client;
                let url = &url;
                scope.spawn(move || {
                    exchange(
                        client,
                        url,
                        Some(json!({"query":RECOVERY_QUERY,"timeoutMs":DEADLINE_MS * 2})),
                        &output.join(format!("concurrent-{i}.json")),
                    )
                })
            })
            .collect();
        let results: Vec<_> = workers
            .into_iter()
            .map(|worker| {
                worker
                    .join()
                    .map_err(|_| anyhow::anyhow!("Capacity request worker panicked"))
                    .and_then(|r| r)
            })
            .collect();
        for result in results {
            recovery(&result?, &ids)?;
        }
        Ok(())
    })?;
    let after = poll_active(&mut server, &client, output, 0)?;
    let successful = "graphite_cypher_query_duration_seconds_count{outcome=\"success\"}";
    ensure!(
        metric(&after, successful)? == metric(&before, successful)? + 4,
        "Four concurrent requests did not complete successfully"
    );
    ensure!(
        metric(&after, "graphite_cypher_queries_rejected_total")?
            == metric(&before, "graphite_cypher_queries_rejected_total")?,
        "Requests within configured capacity were rejected"
    );
    recovery(
        &exchange(
            &client,
            &url,
            Some(json!({"query":RECOVERY_QUERY})),
            &output.join("recovered-after-concurrent.json"),
        )?,
        &ids,
    )?;
    let invalid = exchange(
        &client,
        &url,
        Some(json!({"query":RECOVERY_QUERY,"timeoutMs":0})),
        &output.join("invalid-timeout.json"),
    )?;
    ensure!(
        invalid.status == 400
            && parse_json(&invalid.body)?["error"] == "'timeoutMs' must be a positive integer",
        "Invalid timeout was not rejected"
    );
    let malformed = exchange(
        &client,
        &url,
        Some(json!({"query":"MATCH ("})),
        &output.join("invalid-query.json"),
    )?;
    error_reply(&malformed, 400, "cypher_query_failed", None)?;
    recovery(
        &exchange(
            &client,
            &url,
            Some(json!({"query":RECOVERY_QUERY})),
            &output.join("recovered-after-errors.json"),
        )?,
        &ids,
    )?;
    poll_active(&mut server, &client, output, 0)?;
    ensure!(
        server.try_wait()?.is_none(),
        "Capacity server exited before completion"
    );
    server.terminate()?;
    Ok(())
}

pub(super) fn run(binary: &Path, graphs: &[Graph], output: &Path) -> Result<Value> {
    fs::create_dir_all(output)?;
    let result = probe(binary, graphs, output);
    let value = json!({"passed":result.is_ok(),"performanceEvidence":false,
        "graphIds":graphs.iter().map(|g|&g.id).collect::<Vec<_>>(),"concurrency":4,"serverTimeoutMs":DEADLINE_MS,
        "query":RECOVERY_QUERY,"checks":["four concurrent complete requests with exact results","successful outcome metrics and idle recovery","invalid timeout/query rejection and exact recovery"],
        "limitations":"These base/head network probes do not force saturation or deadline expiry. Required production-handler unit tests on the candidate deterministically verify fifth-request rejection, deadline capping, timeout responses and permit release. Native has no work-budget enforcement or HTTP-disconnect cancellation contract; JVM contract tests cover those existing JVM features.",
        "error":result.err().map(|e|format!("{e:#}"))});
    fs::write(
        output.join("checks.json"),
        serde_json::to_vec_pretty(&value)?,
    )?;
    Ok(value)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    /// Explicit correctness-only smoke; repeated input is never performance evidence.
    #[test]
    #[ignore = "requires a compiled production server and a saved graph from the JVM frontend"]
    fn production_server_capacity_smoke() {
        let binary = std::path::PathBuf::from(
            std::env::var_os("GRAPHITE_CAPACITY_BINARY").expect("binary path"),
        );
        let fixture = std::path::PathBuf::from(
            std::env::var_os("GRAPHITE_CAPACITY_FIXTURE").expect("saved fixture path"),
        );
        let output = std::path::PathBuf::from(
            std::env::var_os("GRAPHITE_CAPACITY_OUTPUT").expect("evidence output path"),
        );
        let graphs: Vec<_> = (0..64)
            .map(|i| Graph {
                id: format!("smoke-{i:02}"),
                path: fixture.clone(),
                terms: Default::default(),
                fingerprint: String::new(),
            })
            .collect();
        let evidence = run(&binary, &graphs, &output).unwrap();
        assert_eq!(evidence["passed"], true, "{evidence}");
        assert_eq!(evidence["performanceEvidence"], false);
    }

    #[test]
    fn complete_error_bodies_and_truncated_failure_bytes_are_retained() {
        let directory = std::env::temp_dir().join(format!(
            "graphite-capacity-body-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&directory).unwrap();
        for (index, declared_length) in [4, 20].into_iter().enumerate() {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            let address = listener.local_addr().unwrap();
            let worker = std::thread::spawn(move || {
                let (mut stream, _) = listener.accept().unwrap();
                let mut request = [0; 1024];
                assert!(stream.read(&mut request).unwrap() > 0);
                write!(stream,"HTTP/1.1 429 Too Many Requests\r\nContent-Length: {declared_length}\r\nRetry-After: 1\r\nConnection: close\r\n\r\noops").unwrap();
            });
            let path = directory.join(format!("{index}.json"));
            let client = Client::builder()
                .timeout(Duration::from_secs(2))
                .no_proxy()
                .build()
                .unwrap();
            let result = exchange(
                &client,
                &format!("http://{address}/api/cypher"),
                None,
                &path,
            );
            worker.join().unwrap();
            assert_eq!(fs::read(&path).unwrap(), b"oops");
            let receipt: Value =
                serde_json::from_slice(&fs::read(path.with_extension("receipt.json")).unwrap())
                    .unwrap();
            assert_eq!(receipt["status"], 429);
            assert_eq!(receipt["bodySha256"], sha(b"oops"));
            assert_eq!(receipt["complete"], declared_length == 4);
            if declared_length == 4 {
                assert_eq!(result.unwrap().retry_after.as_deref(), Some("1"));
            } else {
                assert!(result.is_err());
                assert!(receipt["error"].is_string());
            }
        }
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn missing_duplicate_fractional_and_nonfinite_guard_metrics_fail() {
        for text in [
            "",
            "active 1\nactive 1\n",
            "active 0.5\n",
            "active NaN\n",
            "active -1\n",
        ] {
            assert!(metric(text, "active").is_err(), "{text}");
        }
        assert_eq!(metric("# comment\nactive 4.0\n", "active").unwrap(), 4);
    }

    #[test]
    fn timeout_requires_real_error_status_code_and_capped_deadline() {
        let mut reply = Reply {
            status: 504,
            retry_after: None,
            body: br#"{"error":"deadline","code":"cypher_query_timeout","timeoutMs":10000}"#
                .to_vec(),
        };
        error_reply(&reply, 504, "cypher_query_timeout", Some(DEADLINE_MS)).unwrap();
        reply.status = 200;
        assert!(error_reply(&reply, 504, "cypher_query_timeout", Some(DEADLINE_MS)).is_err());
        reply.status = 504;
        assert!(error_reply(&reply, 504, "cypher_query_timeout", Some(20000)).is_err());
        assert!(error_reply(&reply, 504, "cypher_query_cancelled", Some(DEADLINE_MS)).is_err());
    }

    #[test]
    fn rejection_requires_retry_header_and_recovery_requires_value() {
        let mut reply = Reply {
            status: 429,
            retry_after: None,
            body: br#"{"error":"full","code":"cypher_concurrency_limit"}"#.to_vec(),
        };
        assert!(error_reply(&reply, 429, "cypher_concurrency_limit", None).is_err());
        reply.retry_after = Some("1".into());
        error_reply(&reply, 429, "cypher_concurrency_limit", None).unwrap();
        let ids = BTreeSet::from(["g".into()]);
        reply.status = 200;
        reply.body=br#"{"columns":["ok"],"rows":[{"ok":1,"$metadata":{"graphIds":[]}}],"rowCount":1,"graphCount":1}"#.to_vec();
        recovery(&reply, &ids).unwrap();
        reply.body=br#"{"columns":["ok"],"rows":[{"ok":2,"$metadata":{"graphIds":[]}}],"rowCount":1,"graphCount":1}"#.to_vec();
        assert!(recovery(&reply, &ids).is_err());
    }
}

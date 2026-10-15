//! Fixed real64 pressure workload through the production HTTP server.
//! Request durations are observations only; they never decide acceptance.
use anyhow::{ensure, Context, Result};
use clap::Args;
use reqwest::blocking::Client;
use serde::de::{self, MapAccess, SeqAccess, Visitor};
use serde::{Deserialize, Deserializer};
use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::ffi::OsString;
use std::fmt;
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use crate::plan::{
    NATIVE_REQUEST_TIMEOUT as REQUEST_TIMEOUT, NATIVE_SERVER_TIMEOUT as SUITE_TIMEOUT,
    NATIVE_STARTUP_TIMEOUT as STARTUP_TIMEOUT,
};
use crate::process::{ManagedProcess, ProcessSpec};

const GRAPH_COUNT: usize = 64;
const CASE_COUNT: usize = 73;
const CONCURRENCY: usize = 4;
const WARMUP_PER_CASE: usize = 2;
const PRESSURE_PER_CASE: usize = 20;
const ISOLATED_PER_CASE: usize = 40;
const ISOLATED_THRESHOLD_MS: f64 = 100.0;
const PORT: u16 = 18080;
const QUERY_TIMEOUT_MS: u64 = 300_000;
const MAX_RESPONSE_BYTES: usize = 64 * 1024 * 1024;
const FOUR: &str = "n.caller_class, n.caller_name, n.callee_class, n.callee_name";
const DISTRIBUTIONS: [&str; 4] = [
    "localized-early",
    "localized-middle",
    "localized-late",
    "broad-all-64",
];

#[derive(Args, Debug)]
pub struct NativeArgs {
    #[arg(long)]
    pub base: PathBuf,
    #[arg(long, alias = "candidate")]
    pub head: PathBuf,
    #[arg(long)]
    pub base_manifest: PathBuf,
    #[arg(long)]
    pub head_manifest: PathBuf,
    #[arg(long)]
    pub output: PathBuf,
    #[arg(long)]
    pub base_sha: String,
    #[arg(long)]
    pub head_sha: String,
}

#[derive(Debug, Clone)]
struct Graph {
    id: String,
    path: PathBuf,
    terms: [String; 3],
    fingerprint: String,
}

#[derive(Debug, Clone)]
struct Distribution {
    name: String,
    request_graph: String,
    term: String,
    expected: BTreeSet<String>,
}

#[derive(Debug)]
struct Manifest {
    graphs: Vec<Graph>,
    distributions: Vec<Distribution>,
}

fn read_manifest(path: &Path) -> Result<Manifest> {
    let text = fs::read_to_string(path)?;
    let mut graphs = Vec::new();
    let mut distributions = Vec::new();
    for line in text.lines().filter(|line| !line.trim().is_empty()) {
        let fields: Vec<_> = line.split('\t').collect();
        if line.starts_with('#') {
            if fields[0] == "# global-wide-distribution-v1" {
                ensure!(fields.len() == 5, "Invalid distribution fields");
                let expected: Vec<_> = fields[4].split(',').map(str::to_owned).collect();
                let unique: BTreeSet<_> = expected.iter().cloned().collect();
                ensure!(expected.len() == unique.len(), "Duplicate expected graph");
                distributions.push(Distribution {
                    name: fields[1].into(),
                    request_graph: fields[2].into(),
                    term: fields[3].into(),
                    expected: unique,
                });
            }
            continue;
        }
        ensure!(
            fields.len() == 6 && fields.iter().all(|s| !s.is_empty()),
            "Invalid six-column graph record"
        );
        ensure!(
            fields[0].len() <= 128
                && fields[0]
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b)),
            "Invalid graph id"
        );
        let raw = PathBuf::from(fields[1]);
        let graph_path = if raw.is_absolute() {
            raw
        } else {
            path.parent().unwrap_or(Path::new(".")).join(raw)
        };
        let graph_path = graph_path
            .canonicalize()
            .context("Missing graph directory")?;
        ensure!(
            graph_path.join("graph.nodedata").is_file(),
            "Missing persisted graph.nodedata"
        );
        ensure!(
            fields[5].len() == 64 && fields[5].bytes().all(|b| b.is_ascii_hexdigit()),
            "Invalid workload fingerprint"
        );
        graphs.push(Graph {
            id: fields[0].into(),
            path: graph_path,
            terms: [fields[2].into(), fields[3].into(), fields[4].into()],
            fingerprint: fields[5].into(),
        });
    }
    let manifest = Manifest {
        graphs,
        distributions,
    };
    validate_manifest(&manifest)?;
    Ok(manifest)
}

fn validate_manifest(m: &Manifest) -> Result<()> {
    ensure!(m.graphs.len() == GRAPH_COUNT, "Requires exactly 64 graphs");
    let ids: BTreeSet<_> = m.graphs.iter().map(|g| g.id.clone()).collect();
    ensure!(ids.len() == GRAPH_COUNT, "Duplicate graph id");
    ensure!(
        m.graphs
            .iter()
            .map(|g| &g.path)
            .collect::<BTreeSet<_>>()
            .len()
            == GRAPH_COUNT,
        "Duplicate graph path"
    );
    ensure!(
        m.graphs
            .iter()
            .map(|g| &g.fingerprint)
            .collect::<BTreeSet<_>>()
            .len()
            == GRAPH_COUNT,
        "Duplicate graph content identity"
    );
    ensure!(m.distributions.len() == 4, "Requires four distributions");
    for (index, (d, expected_name)) in m.distributions.iter().zip(DISTRIBUTIONS).enumerate() {
        ensure!(
            d.name == expected_name && !d.term.is_empty(),
            "Distribution catalog changed"
        );
        let ordinal = [0, 31, 63, 0][index];
        ensure!(
            d.request_graph == m.graphs[ordinal].id,
            "Distribution request graph changed"
        );
        let expected = if index == 3 {
            ids.clone()
        } else {
            BTreeSet::from([d.request_graph.clone()])
        };
        ensure!(d.expected == expected, "Distribution graph scope changed");
    }
    Ok(())
}

#[derive(Debug, Clone)]
struct Case {
    id: String,
    query: String,
    selectivity: String,
}

fn quoted(value: &str) -> String {
    value.replace('\\', "\\\\").replace('\'', "\\'")
}

fn predicate(term: &str, wrapped: bool) -> String {
    let term = quoted(&if wrapped {
        term.to_lowercase()
    } else {
        term.to_owned()
    });
    ["caller_class", "caller_name", "callee_class", "callee_name"]
        .iter()
        .map(|p| {
            if wrapped {
                format!("toLower(coalesce(n.{p}, '')) CONTAINS '{term}'")
            } else {
                format!("n.{p} CONTAINS '{term}'")
            }
        })
        .collect::<Vec<_>>()
        .join(" OR ")
}

fn wide(term: &str, projection: &str, wrapped: bool, distinct: bool) -> String {
    format!(
        "MATCH (n)\nWHERE {}\nRETURN {}{projection}\nLIMIT 200",
        predicate(term, wrapped),
        if distinct { "DISTINCT " } else { "" }
    )
}

fn catalog(m: &Manifest) -> Result<Vec<Case>> {
    let mut cases = Vec::new();
    let shapes = [
        ("four-properties", FOUR), ("class-pair", "n.caller_class, n.callee_class"),
        ("name-pair", "n.caller_name, n.callee_name"), ("caller-class", "n.caller_class"),
        ("callee-class", "n.callee_class"), ("provenance", "n.graphId, n.caller_class, n.caller_name, n.callee_class, n.callee_name"),
        ("aliased", "n.caller_class AS caller, n.caller_name AS callerMethod, n.callee_class AS callee, n.callee_name AS calleeMethod"),
        ("parameterized-as-literal", FOUR), ("wrapped-case-insensitive", FOUR),
        ("wrapped-case-insensitive-distinct", FOUR),
    ];
    for (selectivity, term) in ["zero", "targeted", "dense"].iter().zip(&m.graphs[0].terms) {
        for (name, projection) in shapes {
            cases.push(Case {
                id: format!("{selectivity}/global-wide-{name}"),
                selectivity: (*selectivity).into(),
                query: wide(
                    term,
                    projection,
                    name.starts_with("wrapped"),
                    name.ends_with("distinct"),
                ),
            });
        }
    }
    for d in &m.distributions {
        cases.push(Case {
            id: format!("{}/global-wide-four-properties", d.name),
            selectivity: d.name.clone(),
            query: wide(&d.term, FOUR, false, false),
        });
    }
    let fixed: Vec<(String, String)> = serde_json::from_str(include_str!("native_queries.json"))?;
    cases.extend(fixed.into_iter().map(|(id, query)| Case {
        id,
        query,
        selectivity: "catalog".into(),
    }));
    ensure!(
        cases.len() == CASE_COUNT
            && cases.iter().map(|c| &c.id).collect::<BTreeSet<_>>().len() == CASE_COUNT,
        "Incomplete query catalog"
    );
    Ok(cases)
}

// serde_json::Value normally accepts duplicate object keys. The wire validator must not.
struct StrictJson(Value);
impl<'de> Deserialize<'de> for StrictJson {
    fn deserialize<D: Deserializer<'de>>(d: D) -> std::result::Result<Self, D::Error> {
        struct StrictVisitor;
        impl<'de> Visitor<'de> for StrictVisitor {
            type Value = StrictJson;
            fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
                f.write_str("strict JSON")
            }
            fn visit_bool<E: de::Error>(self, v: bool) -> std::result::Result<Self::Value, E> {
                Ok(StrictJson(json!(v)))
            }
            fn visit_i64<E: de::Error>(self, v: i64) -> std::result::Result<Self::Value, E> {
                Ok(StrictJson(json!(v)))
            }
            fn visit_u64<E: de::Error>(self, v: u64) -> std::result::Result<Self::Value, E> {
                Ok(StrictJson(json!(v)))
            }
            fn visit_f64<E: de::Error>(self, v: f64) -> std::result::Result<Self::Value, E> {
                serde_json::Number::from_f64(v)
                    .map(|n| StrictJson(Value::Number(n)))
                    .ok_or_else(|| E::custom("nonfinite number"))
            }
            fn visit_str<E: de::Error>(self, v: &str) -> std::result::Result<Self::Value, E> {
                Ok(StrictJson(json!(v)))
            }
            fn visit_string<E: de::Error>(self, v: String) -> std::result::Result<Self::Value, E> {
                Ok(StrictJson(json!(v)))
            }
            fn visit_unit<E: de::Error>(self) -> std::result::Result<Self::Value, E> {
                Ok(StrictJson(Value::Null))
            }
            fn visit_seq<A: SeqAccess<'de>>(
                self,
                mut seq: A,
            ) -> std::result::Result<Self::Value, A::Error> {
                let mut values = Vec::new();
                while let Some(StrictJson(v)) = seq.next_element()? {
                    values.push(v);
                }
                Ok(StrictJson(Value::Array(values)))
            }
            fn visit_map<A: MapAccess<'de>>(
                self,
                mut map: A,
            ) -> std::result::Result<Self::Value, A::Error> {
                let mut values = Map::new();
                while let Some((key, StrictJson(v))) = map.next_entry::<String, StrictJson>()? {
                    if values.insert(key, v).is_some() {
                        return Err(de::Error::custom("duplicate JSON key"));
                    }
                }
                Ok(StrictJson(Value::Object(values)))
            }
        }
        d.deserialize_any(StrictVisitor)
    }
}

fn parse_json(bytes: &[u8]) -> Result<Value> {
    Ok(serde_json::from_slice::<StrictJson>(bytes)?.0)
}

fn canonical(value: &Value) -> Value {
    match value {
        Value::Object(map) => Value::Object(
            map.iter()
                .map(|(k, v)| (k.clone(), canonical(v)))
                .collect::<BTreeMap<_, _>>()
                .into_iter()
                .collect(),
        ),
        Value::Array(values) => Value::Array(values.iter().map(canonical).collect()),
        v => v.clone(),
    }
}

fn response(bytes: &[u8], ids: &BTreeSet<String>) -> Result<Value> {
    let value = parse_json(bytes)?;
    ensure!(
        value.is_object() && value.get("error").is_none(),
        "Error/non-object response"
    );
    let columns = value["columns"].as_array().context("Missing columns")?;
    let columns: Vec<_> = columns
        .iter()
        .map(|c| c.as_str().context("Nonstring column"))
        .collect::<Result<_>>()?;
    ensure!(
        columns.iter().collect::<BTreeSet<_>>().len() == columns.len(),
        "Duplicate columns"
    );
    let rows = value["rows"].as_array().context("Missing rows")?;
    ensure!(
        value["rowCount"].as_u64() == Some(rows.len() as u64),
        "Incorrect rowCount"
    );
    ensure!(
        value["graphCount"].as_u64() == Some(ids.len() as u64),
        "Request did not cover all 64 graphs"
    );
    for row in rows {
        let object = row.as_object().context("Nonobject row")?;
        // The production RowBody formatter omits null-valued projected keys,
        // matching Gson. Keep that omission in the typed oracle rather than
        // inventing null fields or rejecting valid nullable projections.
        ensure!(
            object
                .keys()
                .all(|key| key == "$metadata" || columns.contains(&key.as_str())),
            "Row contains an undeclared projected field"
        );
        let metadata = row["$metadata"]["graphIds"]
            .as_array()
            .context("Missing cross-graph provenance")?;
        let scopes: Vec<_> = metadata
            .iter()
            .map(|g| g.as_str().context("Invalid provenance graph"))
            .collect::<Result<_>>()?;
        ensure!(
            scopes.iter().all(|id| ids.contains(*id))
                && scopes.iter().collect::<BTreeSet<_>>().len() == scopes.len(),
            "Foreign/duplicate provenance graph"
        );
    }
    Ok(canonical(&value))
}

fn validate_semantics(case: &Case, body: &Value, node_count: u64) -> Result<()> {
    let rows = body["rows"].as_array().context("Missing rows")?;
    if case.selectivity == "zero" {
        ensure!(rows.is_empty(), "Known zero term returned rows");
    }
    if case.selectivity == "targeted" {
        ensure!(!rows.is_empty(), "Known targeted term disappeared");
    }
    if case.selectivity == "dense" && !case.id.ends_with("distinct") {
        ensure!(rows.len() == 200, "Dense query did not fill its limit");
    }
    if DISTRIBUTIONS.contains(&case.selectivity.as_str()) {
        ensure!(
            rows.len() == 200,
            "Known dense distribution did not fill its limit"
        );
    }
    if case.id == "shape-count-all" {
        ensure!(
            rows.len() == 1 && rows[0]["count(*)"].as_u64() == Some(node_count),
            "Count does not match registered graph node totals"
        );
    }
    if case.id == "shape-all-nodes" {
        ensure!(
            rows.len() == 200,
            "Nonempty corpus returned incomplete node sample"
        );
    }
    Ok(())
}

fn registry(bytes: &[u8], expected: &BTreeSet<String>) -> Result<u64> {
    let value = parse_json(bytes)?;
    ensure!(
        value["count"].as_u64() == Some(GRAPH_COUNT as u64),
        "Registry count differs"
    );
    let graphs = value["graphs"]
        .as_array()
        .context("Missing graph registry")?;
    let ids: BTreeSet<_> = graphs
        .iter()
        .map(|g| {
            g["id"]
                .as_str()
                .map(str::to_owned)
                .context("Missing registered graph id")
        })
        .collect::<Result<_>>()?;
    ensure!(
        graphs.len() == GRAPH_COUNT && &ids == expected,
        "Registry graph identities differ"
    );
    let mut total = 0u64;
    for graph in graphs {
        let nodes = graph["nodes"].as_u64().context("Missing node statistics")?;
        ensure!(nodes > 0, "Empty fixture graph");
        total = total.checked_add(nodes).context("Node total overflow")?;
    }
    ensure!(
        value["totals"]["nodes"].as_u64() == Some(total),
        "Registry totals differ"
    );
    Ok(total)
}

fn sha(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

// Always retain bytes, including partial/error bodies. Request timeout bounds the
// entire read, not only receipt of response headers.
fn request(client: &Client, query: &str, path: &Path) -> Result<(Vec<u8>, f64)> {
    request_at(
        client,
        &format!("http://127.0.0.1:{PORT}/api/cypher"),
        query,
        path,
    )
}

fn request_at(client: &Client, url: &str, query: &str, path: &Path) -> Result<(Vec<u8>, f64)> {
    let started = Instant::now();
    let mut bytes = Vec::new();
    let mut status = None;
    let mut complete_body = false;
    let operation = (|| -> Result<()> {
        let mut reply = client
            .post(url)
            .header("Connection", "close")
            .json(&json!({"query":query,"timeoutMs":QUERY_TIMEOUT_MS}))
            .send()?;
        status = Some(reply.status().as_u16());
        let length = reply.content_length();
        let mut chunk = [0u8; 65536];
        loop {
            let count = reply.read(&mut chunk)?;
            if count == 0 {
                break;
            }
            bytes.extend_from_slice(&chunk[..count]);
            ensure!(
                bytes.len() <= MAX_RESPONSE_BYTES,
                "Response body exceeds fixed 64 MiB cap"
            );
        }
        ensure!(
            length.is_none_or(|n| n == bytes.len() as u64),
            "Truncated HTTP response"
        );
        complete_body = true;
        ensure!(
            reply.status().as_u16() == 200,
            "HTTP query status {}",
            reply.status()
        );
        Ok(())
    })();
    let ms = started.elapsed().as_secs_f64() * 1000.0;
    fs::write(path, &bytes)?;
    fs::write(
        path.with_extension("receipt.json"),
        serde_json::to_vec_pretty(&json!({
            "status":status,"complete":complete_body,"passed":operation.is_ok(),"bodyBytes":bytes.len(),"bodySha256":sha(&bytes),
            "requestMs":ms,"timingUse":"REPORT_ONLY","error":operation.as_ref().err().map(|e|format!("{e:#}"))
        }))?,
    )?;
    operation?;
    Ok((bytes, ms))
}

#[derive(Default)]
struct Arm {
    samples: Vec<Value>,
    oracle: Vec<Value>,
    load_ms: Option<f64>,
    first_use_ms: Option<f64>,
    error: Option<String>,
}

fn percentile(samples: &[f64], fraction: f64) -> Option<f64> {
    if samples.is_empty() {
        return None;
    }
    let mut sorted = samples.to_vec();
    sorted.sort_by(f64::total_cmp);
    Some(sorted[((sorted.len() as f64 * fraction).ceil() as usize).saturating_sub(1)])
}

fn stage_tasks(cases: usize, repeats: usize) -> Vec<usize> {
    (0..repeats).flat_map(|_| 0..cases).collect()
}

fn request_samples(samples: &[Value], case: &str, stage: &str) -> Vec<f64> {
    samples
        .iter()
        .filter(|r| r["stage"] == stage && r["case"] == case && r["passed"] == true)
        .filter_map(|r| r["requestMs"].as_f64())
        .collect()
}

fn isolated_tasks(cases: &[Case], samples: &[Value]) -> Vec<usize> {
    cases
        .iter()
        .enumerate()
        .filter(|(_, case)| {
            percentile(&request_samples(samples, &case.id, "pressure"), 0.5)
                .is_some_and(|p| p < ISOLATED_THRESHOLD_MS)
        })
        .flat_map(|(i, _)| std::iter::repeat_n(i, ISOLATED_PER_CASE))
        .collect()
}

// Exactly four closed-loop workers for oracle/warmup/pressure. Workers consume
// complete bodies and validate them before taking their next task. This is a
// declared validation gap, not a saturation or open-loop arrival-rate claim.
// All arguments describe one immutable workload stage and its owned journals.
#[allow(clippy::too_many_arguments)]
fn stage(
    server: &mut ManagedProcess,
    client: &Client,
    name: &str,
    tasks: &[usize],
    concurrency: usize,
    cases: &[Case],
    ids: &BTreeSet<String>,
    node_count: u64,
    expected: Option<&[Value]>,
    output: &Path,
    samples: &mut Vec<Value>,
    started: Instant,
) -> Result<Vec<Value>> {
    use std::sync::{
        atomic::{AtomicBool, AtomicUsize, Ordering},
        mpsc, Barrier,
    };
    let directory = output.join(name);
    fs::create_dir_all(&directory)?;
    let next = AtomicUsize::new(0);
    let stop = AtomicBool::new(false);
    let ready = Barrier::new(concurrency);
    let (sender, receiver) = mpsc::channel();
    let mut results: Vec<Option<Value>> = vec![None; cases.len()];
    let mut failures = Vec::new();
    let mut terminated = false;
    std::thread::scope(|scope| {
        for worker in 0..concurrency {
            let sender = sender.clone();
            let next = &next;
            let stop = &stop;
            let ready = &ready;
            let directory = &directory;
            scope.spawn(move || {
                ready.wait();
                loop {
                    if stop.load(Ordering::SeqCst) || crate::process::is_cancelled() { break; }
                    let sequence = next.fetch_add(1, Ordering::SeqCst);
                    let Some(&index) = tasks.get(sequence) else { break; };
                    let case = &cases[index];
                    let begin = Instant::now();
                    let path = directory.join(format!("{sequence:06}.json"));
                    let mut wire_ms = None;
                    let result = (|| -> Result<Value> {
                        ensure!(started.elapsed() < SUITE_TIMEOUT, "Native suite work budget exhausted");
                        let (bytes, ms) = request(client, &case.query, &path)?;
                        wire_ms = Some(ms);
                        let body = response(&bytes, ids)?;
                        validate_semantics(case, &body, node_count)?;
                        if let Some(expected) = expected { ensure!(body == expected[index], "Complete response differs from own first-use oracle"); }
                        Ok(body)
                    })();
                    let record = json!({"stage":name,"sequence":sequence,"worker":worker,"caseIndex":index,
                        "case":case.id,"querySha256":sha(case.query.as_bytes()),"graphIds":ids,
                        "startNs":begin.duration_since(started).as_nanos() as u64,
                        "endNs":started.elapsed().as_nanos() as u64,
                        "requestMs":wire_ms,"validatedRequestMs":begin.elapsed().as_secs_f64()*1000.0,
                        "timingUse":"REPORT_ONLY","passed":result.is_ok(),
                        "bodyPath":path.display().to_string(),"error":result.as_ref().err().map(|e|format!("{e:#}"))});
                    let saved = fs::write(directory.join(format!("{sequence:06}.record.json")),
                        serde_json::to_vec_pretty(&record).expect("finite request record"));
                    if result.is_err() || saved.is_err() { stop.store(true, Ordering::SeqCst); }
                    let result = result.and_then(|body| { saved?; Ok(body) });
                    if sender.send((index, record, result)).is_err() { break; }
                }
            });
        }
        drop(sender);
        loop {
            match receiver.recv_timeout(Duration::from_millis(100)) {
                Ok((index, record, result)) => {
                    samples.push(record);
                    match result {
                        Ok(body) => results[index] = Some(body),
                        Err(error) => failures.push(format!("{error:#}")),
                    }
                }
                Err(mpsc::RecvTimeoutError::Disconnected) => break,
                Err(mpsc::RecvTimeoutError::Timeout) => {}
            }
            if !stop.load(Ordering::SeqCst) {
                match server.try_wait() {
                    Ok(None) if started.elapsed() < SUITE_TIMEOUT => {}
                    other => {
                        stop.store(true, Ordering::SeqCst);
                        failures.push(format!(
                            "Server ended/cancelled or work budget exceeded: {other:?}"
                        ));
                    }
                }
            }
            if stop.load(Ordering::SeqCst) && !terminated {
                // Close active sockets promptly on failure/cancellation rather than
                // waiting for every in-flight HTTP timeout before reaping the group.
                if let Err(error) = server.terminate() {
                    failures.push(format!("Terminate failed server group: {error:#}"));
                }
                terminated = true;
            }
        }
    });
    let actual = samples.iter().filter(|r| r["stage"] == name).count();
    fs::write(
        directory.join("stage.json"),
        serde_json::to_vec_pretty(&json!({
            "stage":name,"expectedRequests":tasks.len(),"completedRequests":actual,"concurrency":concurrency,
            "errors":failures,"performanceAcceptance":false
        }))?,
    )?;
    ensure!(
        failures.is_empty() && actual == tasks.len(),
        "{name}: incomplete/failed pressure stage: {failures:?}"
    );
    Ok(results
        .into_iter()
        .map(|v| v.unwrap_or(Value::Null))
        .collect())
}

fn run_revision(
    binary: &Path,
    m: &Manifest,
    cases: &[Case],
    output: &Path,
    arm: &mut Arm,
) -> Result<()> {
    fs::create_dir_all(output)?;
    let client = Client::builder()
        .timeout(REQUEST_TIMEOUT)
        .pool_max_idle_per_host(0)
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .build()?;
    let probe = Client::builder()
        .timeout(Duration::from_secs(2))
        .no_proxy()
        .build()?;
    ensure!(
        std::net::TcpListener::bind(("127.0.0.1", PORT)).is_ok(),
        "Native server port already occupied"
    );
    let mut args: Vec<OsString> = [
        "serve",
        "--port",
        "18080",
        "--cypher-max-timeout-ms",
        "300000",
        "--max-concurrent-cypher",
        "4",
        "--load-mode",
        "MAPPED",
    ]
    .iter()
    .map(OsString::from)
    .collect();
    for graph in &m.graphs {
        args.extend([
            "--graph".into(),
            format!("{}:{}", graph.id, graph.path.display()).into(),
        ]);
    }
    let spec = ProcessSpec {
        label: "native-real64".into(),
        program: binary.canonicalize()?,
        args,
        cwd: None,
        env: BTreeMap::new(),
        stdout: output.join("stdout.log"),
        stderr: output.join("stderr.log"),
        timeout: SUITE_TIMEOUT,
    };
    let started = Instant::now();
    let mut server = ManagedProcess::spawn(&spec)?;
    let ids: BTreeSet<_> = m.graphs.iter().map(|g| g.id.clone()).collect();
    let startup = Instant::now();
    let node_count = loop {
        ensure!(
            server.try_wait()?.is_none(),
            "Native server exited before readiness"
        );
        ensure!(
            startup.elapsed() < STARTUP_TIMEOUT,
            "Native readiness deadline exceeded"
        );
        if let Ok(reply) = probe
            .get(format!("http://127.0.0.1:{PORT}/api/graphs"))
            .send()
        {
            if reply.status().as_u16() == 200 {
                let bytes = reply.bytes()?;
                let count = registry(&bytes, &ids)?;
                arm.load_ms = Some(started.elapsed().as_secs_f64() * 1000.0);
                fs::write(output.join("registry.json"), &bytes)?;
                break count;
            }
        }
        std::thread::sleep(Duration::from_millis(100));
    };
    arm.oracle = stage(
        &mut server,
        &client,
        "first-use-oracle",
        &stage_tasks(cases.len(), 1),
        CONCURRENCY,
        cases,
        &ids,
        node_count,
        None,
        output,
        &mut arm.samples,
        started,
    )?;
    arm.first_use_ms = Some(started.elapsed().as_secs_f64() * 1000.0);
    stage(
        &mut server,
        &client,
        "warmup",
        &stage_tasks(cases.len(), WARMUP_PER_CASE),
        CONCURRENCY,
        cases,
        &ids,
        node_count,
        Some(&arm.oracle),
        output,
        &mut arm.samples,
        started,
    )?;
    stage(
        &mut server,
        &client,
        "pressure",
        &stage_tasks(cases.len(), PRESSURE_PER_CASE),
        CONCURRENCY,
        cases,
        &ids,
        node_count,
        Some(&arm.oracle),
        output,
        &mut arm.samples,
        started,
    )?;
    let isolated = isolated_tasks(cases, &arm.samples);
    stage(
        &mut server,
        &client,
        "isolated",
        &isolated,
        1,
        cases,
        &ids,
        node_count,
        Some(&arm.oracle),
        output,
        &mut arm.samples,
        started,
    )?;
    // Independent scope assertions come after pressure, so they cannot hide first-use work.
    for distribution in &m.distributions {
        ensure!(
            server.try_wait()?.is_none(),
            "Native server exited before scope assertions"
        );
        let query = format!(
            "MATCH (n) WHERE {} RETURN DISTINCT n.graphId AS graph ORDER BY graph",
            predicate(&distribution.term, true)
        );
        let path = output.join(format!("scope-{}.json", distribution.name));
        let (bytes, _) = request(&client, &query, &path)?;
        let body = response(&bytes, &ids)?;
        let rows = body["rows"].as_array().context("Missing scope rows")?;
        let actual: BTreeSet<_> = rows
            .iter()
            .map(|r| {
                r["graph"]
                    .as_str()
                    .map(str::to_owned)
                    .context("Missing scope id")
            })
            .collect::<Result<_>>()?;
        ensure!(
            actual == distribution.expected && rows.len() == actual.len(),
            "{} distribution scope differs",
            distribution.name
        );
    }
    ensure!(
        server.try_wait()?.is_none(),
        "Native server exited before completion"
    );
    server.terminate()?;
    Ok(())
}

fn source_inputs(manifest: &Path, graphs: &[Graph]) -> Result<Value> {
    let raw = fs::read_to_string(
        manifest
            .parent()
            .context("Manifest parent")?
            .join("fixture-provenance.tsv"),
    )?;
    let mut lines = raw.lines();
    let headers: Vec<_> = lines
        .next()
        .context("Missing provenance header")?
        .split('\t')
        .collect();
    let wanted = [
        "graphId",
        "corpus",
        "shard",
        "sourceJarSha256",
        "shardBytecodeSha256",
        "classCount",
    ];
    let indices: Vec<_> = wanted
        .iter()
        .map(|key| {
            headers
                .iter()
                .position(|h| h == key)
                .with_context(|| format!("Missing provenance {key}"))
        })
        .collect::<Result<_>>()?;
    let rows = lines
        .map(|line| {
            let fields: Vec<_> = line.split('\t').collect();
            ensure!(
                fields.len() == headers.len(),
                "Malformed fixture provenance"
            );
            let row: BTreeMap<_, _> = wanted
                .iter()
                .zip(&indices)
                .map(|(key, &i)| ((*key).to_owned(), fields[i].to_owned()))
                .collect();
            for key in ["sourceJarSha256", "shardBytecodeSha256"] {
                ensure!(
                    row[key].len() == 64 && row[key].bytes().all(|b| b.is_ascii_hexdigit()),
                    "Invalid fixture source digest"
                );
            }
            ensure!(row["classCount"].parse::<u64>()? > 0, "Empty source shard");
            Ok(row)
        })
        .collect::<Result<Vec<_>>>()?;
    ensure!(
        rows.len() == 64
            && rows
                .iter()
                .map(|r| &r["graphId"])
                .collect::<BTreeSet<_>>()
                .len()
                == 64,
        "Incomplete source provenance"
    );
    for (index, (row, graph)) in rows.iter().zip(graphs).enumerate() {
        let corpus = ["android", "tika", "hive", "kotlin-compiler"][index / 16];
        ensure!(
            row["graphId"] == graph.id && graph.id == format!("fixture-{corpus}-{:02}", index % 16),
            "Source provenance does not identify the fixed ordered real64 graphs"
        );
        ensure!(
            row["corpus"] == corpus && row["shard"].parse::<usize>()? == index % 16,
            "Source corpus/shard assignment changed"
        );
    }
    Ok(json!(rows))
}

fn timing(
    case: &str,
    stage: &str,
    statistic: &str,
    base: &[f64],
    head: &[f64],
    comparable: bool,
) -> Value {
    let fraction = if statistic == "p95" { 0.95 } else { 0.5 };
    json!({"case":case,"stage":stage,"workload":"real64","method":format!("nearest-rank {statistic}; request through complete HTTP body consumption, before artifact writes/validation; REPORT_ONLY"),
        "baseMs":percentile(base,fraction),"headMs":percentile(head,fraction),"samplesBase":base.len(),"samplesHead":head.len(),
        "comparable":comparable && !base.is_empty() && !head.is_empty(),
        "unavailableReason":if !comparable {Some("Incomplete execution, source/query drift, or differing complete results")}else if base.is_empty() || head.is_empty(){Some("Case not selected for this arm's predeclared isolated stage")}else{None}})
}

pub fn run(args: &NativeArgs) -> Result<Value> {
    fs::create_dir_all(&args.output)?;
    let oom_before = crate::jmh::cgroup_events();
    let mut arms = [Arm::default(), Arm::default()];
    let mut catalogs = Vec::new();
    let mut sources = Vec::new();
    for (i, (label, binary, path)) in [
        ("base", &args.base, &args.base_manifest),
        ("head", &args.head, &args.head_manifest),
    ]
    .into_iter()
    .enumerate()
    {
        let input = (|| -> Result<_> {
            let m = read_manifest(path)?;
            let cases = catalog(&m)?;
            let sources = source_inputs(path, &m.graphs)?;
            Ok((m, cases, sources))
        })();
        match input {
            Ok((m, cases, source)) => {
                fs::create_dir_all(args.output.join(label))?;
                fs::write(
                    args.output.join(label).join("catalog.json"),
                    serde_json::to_vec_pretty(&json!({
                        "cases":cases.iter().map(|c|json!({"id":c.id,"query":c.query,"querySha256":sha(c.query.as_bytes())})).collect::<Vec<_>>(),
                        "graphIds":m.graphs.iter().map(|g|&g.id).collect::<Vec<_>>(),"sources":source
                    }))?,
                )?;
                let result =
                    run_revision(binary, &m, &cases, &args.output.join(label), &mut arms[i]);
                arms[i].error = result.err().map(|e| format!("{e:#}"));
                catalogs.push(Some(cases));
                sources.push(Some(source));
            }
            Err(error) => {
                arms[i].error = Some(format!("{error:#}"));
                catalogs.push(None);
                sources.push(None);
            }
        }
        fs::write(
            args.output.join(format!("{label}-requests.json")),
            serde_json::to_vec_pretty(&arms[i].samples)?,
        )?;
    }
    let oom_after = crate::jmh::cgroup_events();
    let oom_passed = match (&oom_before, &oom_after) {
        (Ok(Some(before)), Ok(Some(after))) => ["oom", "oom_kill", "oom_group_kill"]
            .iter()
            .all(|key| before.get(*key) == after.get(*key)),
        (Ok(None), Ok(None)) => !cfg!(target_os = "linux"),
        _ => false,
    };
    let oom_evidence = json!({"before":oom_before.as_ref().ok(),"after":oom_after.as_ref().ok(),
        "beforeError":oom_before.as_ref().err().map(|e|format!("{e:#}")),
        "afterError":oom_after.as_ref().err().map(|e|format!("{e:#}")),"passed":oom_passed});
    fs::write(
        args.output.join("host-oom.json"),
        serde_json::to_vec_pretty(&oom_evidence)?,
    )?;
    let source_match = sources[0].is_some() && sources[0] == sources[1];
    let query_match = match (&catalogs[0], &catalogs[1]) {
        (Some(a), Some(b)) => a
            .iter()
            .map(|c| (&c.id, &c.query))
            .eq(b.iter().map(|c| (&c.id, &c.query))),
        _ => false,
    };
    let response_match = arms[0].oracle.len() == CASE_COUNT && arms[0].oracle == arms[1].oracle;
    let complete = arms.iter().all(|a| a.error.is_none());
    let comparable = complete && oom_passed && source_match && query_match && response_match;
    let mut timings = Vec::new();
    for (stage, base, head) in [
        ("registry-ready", arms[0].load_ms, arms[1].load_ms),
        (
            "startup-through-first-use-oracle",
            arms[0].first_use_ms,
            arms[1].first_use_ms,
        ),
    ] {
        timings.push(json!({"case":"real64-loading","stage":stage,"workload":"real64","method":"single fresh-process boundary; REPORT_ONLY; filesystem cache uncontrolled",
            "baseMs":base,"headMs":head,"samplesBase":usize::from(base.is_some()),"samplesHead":usize::from(head.is_some()),"comparable":comparable,
            "unavailableReason":if comparable{None}else{Some("Execution or comparability checks failed")}}));
    }
    if let Some(cases) = catalogs[1].as_ref().or(catalogs[0].as_ref()) {
        for case in cases {
            for stage in ["pressure", "isolated"] {
                for statistic in ["p50", "p95"] {
                    timings.push(timing(
                        &case.id,
                        stage,
                        statistic,
                        &request_samples(&arms[0].samples, &case.id, stage),
                        &request_samples(&arms[1].samples, &case.id, stage),
                        comparable,
                    ));
                }
            }
        }
    }
    let checks = vec![
        json!({"name":"base-real64-pressure-correctness","kind":"correctness","passed":arms[0].error.is_none(),"detail":arms[0].error.clone().unwrap_or_else(||"All complete responses, repeated-request stability and semantic scope assertions passed".into())}),
        json!({"name":"head-real64-pressure-correctness","kind":"correctness","passed":arms[1].error.is_none(),"detail":arms[1].error.clone().unwrap_or_else(||"All complete responses, repeated-request stability and semantic scope assertions passed".into())}),
        json!({"name":"matched-real-source-inputs-and-queries","kind":"coverage","passed":source_match && query_match,"detail":format!("source JAR/shard hashes match: {source_match}; exact 73 query texts match: {query_match}")}),
        json!({"name":"base-head-complete-response-equivalence","kind":"correctness","passed":response_match,"detail":"Every typed response field, order, duplicate row, metadata and actual graph scope participates"}),
        json!({"name":"fixed-real64-pressure-protocol","kind":"coverage","passed":complete,"detail":"73 cases; concurrency4; first-use1/warmup2/pressure20; pressure p50<100ms selects isolated40 at concurrency1. Selection changes reporting only, never acceptance."}),
        json!({"name":"native-host-oom","kind":"heap","passed":oom_passed,"detail":if cfg!(target_os="linux") {"Linux cgroup OOM counters must remain unchanged; every native process must survive all requests. JVM heap ceilings are checked by frontend-build-save"}else{"No Linux cgroup accounting on this platform; process failures are checked; formal hosted OOM evidence requires Linux"}}),
    ];
    let artifacts = vec![
        args.output.join("base-requests.json"),
        args.output.join("head-requests.json"),
        args.output.join("base"),
        args.output.join("head"),
    ];
    let identity = |binary: &Path, manifest: &Path| json!({"binaryPath":binary,"binarySha256":fs::read(binary).ok().map(|b|sha(&b)),"manifestPath":manifest,"manifestSha256":fs::read(manifest).ok().map(|b|sha(&b))});
    Ok(
        json!({"schema":"graphite-benchmark-suite-v1","suite":"native-real64","baseSha":args.base_sha,"headSha":args.head_sha,
        "baseStatus":if arms[0].error.is_none(){"AVAILABLE"}else{"UNAVAILABLE"},"headStatus":if arms[1].error.is_none(){"AVAILABLE"}else{"UNAVAILABLE"},
        "baseUnavailableReason":arms[0].error,"headUnavailableReason":arms[1].error,
        "inputs":{"base":identity(&args.base,&args.base_manifest),"head":identity(&args.head,&args.head_manifest)},
        "checks":checks,"timings":timings,"artifacts":artifacts,"performanceAcceptance":false,
        "sampling":{"concurrency":CONCURRENCY,"oraclePerCase":1,"warmupPerCase":WARMUP_PER_CASE,"pressurePerCase":PRESSURE_PER_CASE,"isolatedPerCase":ISOLATED_PER_CASE,"isolatedThresholdMs":ISOLATED_THRESHOLD_MS,
        "percentiles":"nearest-rank requestMs per individual case, never across cases","boundary":"requestMs ends at complete HTTP body consumption before artifact writes and JSON validation; validatedRequestMs is client-cycle diagnostic only; workers validate before next task","cold":"first-use sequence after registry readiness; not independent cold start per case; no disk-cold claim"}}),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    fn temporary(name: &str) -> PathBuf {
        static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
        let path = std::env::temp_dir().join(format!(
            "graphite-native-{name}-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, std::sync::atomic::Ordering::SeqCst)
        ));
        fs::create_dir_all(&path).unwrap();
        path
    }
    fn fixture() -> Manifest {
        let graphs: Vec<_> = (0..64)
            .map(|n| Graph {
                id: format!("g{n:02}"),
                path: PathBuf::from(format!("/graphs/{n}")),
                terms: ["absent".into(), "target".into(), "java".into()],
                fingerprint: format!("{n:064x}"),
            })
            .collect();
        let ids = graphs.iter().map(|g| g.id.clone()).collect::<BTreeSet<_>>();
        let distributions = DISTRIBUTIONS
            .iter()
            .enumerate()
            .map(|(i, name)| {
                let request_graph = graphs[[0, 31, 63, 0][i]].id.clone();
                Distribution {
                    name: (*name).into(),
                    request_graph: request_graph.clone(),
                    term: "java".into(),
                    expected: if i == 3 {
                        ids.clone()
                    } else {
                        BTreeSet::from([request_graph])
                    },
                }
            })
            .collect();
        Manifest {
            graphs,
            distributions,
        }
    }
    fn body(rows: Value) -> Value {
        json!({"columns":["v"],"rowCount":rows.as_array().unwrap().len(),"rows":rows,"graphCount":64})
    }
    fn ids() -> BTreeSet<String> {
        fixture().graphs.into_iter().map(|g| g.id).collect()
    }
    #[test]
    fn fixed_catalog_preserves_73_cases_and_escapes_terms() {
        let m = fixture();
        validate_manifest(&m).unwrap();
        let cases = catalog(&m).unwrap();
        assert_eq!(cases.len(), 73);
        assert_eq!(cases[30].id, "localized-early/global-wide-four-properties");
        assert_eq!(cases[37].id, "shape-wide-contains");
        assert_eq!(cases[72].id, "shape-union");
        assert!(wide("a'\\b", FOUR, false, false).contains("a\\'\\\\b"));
    }
    #[test]
    fn manifest_rejects_duplicate_and_wrong_graph_scopes() {
        let mut m = fixture();
        m.graphs[1].path = m.graphs[0].path.clone();
        assert!(validate_manifest(&m).is_err());
        let mut m = fixture();
        m.distributions[3].expected.remove("g63");
        assert!(validate_manifest(&m).is_err());
        let mut m = fixture();
        m.distributions[1].request_graph = "g00".into();
        assert!(validate_manifest(&m).is_err());
    }
    #[test]
    fn malformed_truncated_duplicate_and_nonfinite_json_fail() {
        for raw in [
            "{",
            "{\"a\":1,\"a\":2}",
            "{\"a\":{\"v\":1,\"v\":2}}",
            "NaN",
            "1e999",
            "{} trailing",
        ] {
            assert!(parse_json(raw.as_bytes()).is_err(), "{raw}");
        }
    }
    #[test]
    fn rejects_error_count_scope_and_undeclared_projection() {
        let row = json!({"v":1,"$metadata":{"graphIds":["g00"]}});
        let original = body(json!([row]));
        assert!(response(&serde_json::to_vec(&original).unwrap(), &ids()).is_ok());
        for (key, value) in [
            ("error", json!("broken")),
            ("rowCount", json!(2)),
            ("graphCount", json!(1)),
            (
                "rows",
                json!([{"unexpected":1,"$metadata":{"graphIds":["g00"]}}]),
            ),
        ] {
            let mut invalid = original.clone();
            invalid[key] = value;
            assert!(response(&serde_json::to_vec(&invalid).unwrap(), &ids()).is_err());
        }
        let foreign = body(json!([{"v":1,"$metadata":{"graphIds":["other"]}}]));
        assert!(response(&serde_json::to_vec(&foreign).unwrap(), &ids()).is_err());
    }
    #[test]
    fn production_null_constant_omission_is_preserved_in_complete_response() {
        // CI 38756010269 case40 returned exactly this mixed projection shape:
        // a string constant, a null constant with no n.value key, then an int.
        let wire = json!({"columns":["n.value"],"rowCount":3,"graphCount":64,"rows":[
            {"n.value":"","$metadata":{"graphIds":["g00"]}},
            {"$metadata":{"graphIds":["g00"]}},
            {"n.value":-2147483648i64,"$metadata":{"graphIds":["g00"]}}
        ]});
        let checked = response(&serde_json::to_vec(&wire).unwrap(), &ids()).unwrap();
        assert_eq!(checked, canonical(&wire));
        assert!(checked["rows"][1].get("n.value").is_none());
        assert_eq!(checked["rows"][0]["n.value"], "");
        assert_eq!(checked["rows"][2]["n.value"], -2147483648i64);
        let mut explicit_null = wire.clone();
        explicit_null["rows"][1]["n.value"] = Value::Null;
        assert_ne!(
            checked,
            response(&serde_json::to_vec(&explicit_null).unwrap(), &ids()).unwrap()
        );
        let mut changed = wire;
        changed["rows"][1]["n.value"] = json!(0);
        assert_ne!(
            checked,
            response(&serde_json::to_vec(&changed).unwrap(), &ids()).unwrap()
        );
    }
    #[test]
    fn canonical_comparison_keeps_order_multiplicity_types_and_metadata() {
        assert_eq!(
            canonical(&json!({"b":2,"a":1})),
            canonical(&json!({"a":1,"b":2}))
        );
        assert_ne!(canonical(&json!([1, 2])), canonical(&json!([2, 1])));
        assert_ne!(canonical(&json!([1, 1, 2])), canonical(&json!([1, 2])));
        assert_ne!(canonical(&json!({"v":1})), canonical(&json!({"v":"1"})));
        assert_ne!(
            canonical(&json!({"$metadata":{"graphIds":["g00"]}})),
            canonical(&json!({"$metadata":{"graphIds":["g01"]}}))
        );
    }
    #[test]
    fn structural_oracles_reject_shared_wrong_answers() {
        let m = fixture();
        let cases = catalog(&m).unwrap();
        assert!(validate_semantics(&cases[0], &body(json!([{"v":1}])), 100).is_err());
        assert!(validate_semantics(&cases[10], &body(json!([])), 100).is_err());
        assert!(validate_semantics(&cases[20], &body(json!([{"v":1}])), 100).is_err());
        let count = cases.iter().find(|c| c.id == "shape-count-all").unwrap();
        assert!(validate_semantics(count, &json!({"rows":[{"count(*)":99}]}), 100).is_err());
        assert!(validate_semantics(count, &json!({"rows":[{"count(*)":100}]}), 100).is_ok());
    }
    #[test]
    fn http_errors_and_truncation_retain_actual_bodies() {
        for (name,wire,complete) in [
            ("http-error","HTTP/1.1 500 Internal Server Error\r\nContent-Length: 16\r\nConnection: close\r\n\r\n{\"error\":\"oops\"}",true),
            ("truncated","HTTP/1.1 200 OK\r\nContent-Length: 99\r\nConnection: close\r\n\r\n{\"partial\":1}",false),
        ] {
            let listener=std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            let address=listener.local_addr().unwrap();
            let server=std::thread::spawn(move || {let (mut connection,_)=listener.accept().unwrap();let mut request=[0u8;4096];let _=connection.read(&mut request);connection.write_all(wire.as_bytes()).unwrap();});
            let dir=temporary(name);let path=dir.join("response.json");
            let client=Client::builder().timeout(Duration::from_secs(3)).no_proxy().build().unwrap();
            assert!(request_at(&client,&format!("http://{address}/api/cypher"),"RETURN 1",&path).is_err());
            server.join().unwrap();
            let receipt=parse_json(&fs::read(path.with_extension("receipt.json")).unwrap()).unwrap();
            assert_eq!(receipt["complete"],complete);assert_eq!(receipt["passed"],false);
            assert_eq!(fs::read_to_string(&path).unwrap(),wire.split("\r\n\r\n").nth(1).unwrap());
            fs::remove_dir_all(dir).unwrap();
        }
    }
    #[test]
    fn pressure_sampling_is_round_major_and_percentiles_are_per_case() {
        assert_eq!(stage_tasks(3, 2), vec![0, 1, 2, 0, 1, 2]);
        assert_eq!(stage_tasks(73, PRESSURE_PER_CASE).len(), 1460);
        let samples: Vec<_> = (1..=20).map(f64::from).collect();
        assert_eq!(percentile(&samples, 0.5), Some(10.0));
        assert_eq!(percentile(&samples, 0.95), Some(19.0));
        assert_eq!(percentile(&[], 0.95), None);
        let observation = timing("query", "pressure", "p95", &[1.0], &[1_000_000.0], true);
        assert_eq!(observation["comparable"], true);
        assert!(observation.get("passed").is_none());
        assert_eq!(observation["headMs"], 1_000_000.0);
    }
    #[test]
    fn artifact_and_validation_delays_do_not_change_request_statistics_or_selection() {
        let cases = vec![
            Case {
                id: "fast".into(),
                query: "RETURN 1".into(),
                selectivity: "catalog".into(),
            },
            Case {
                id: "slow".into(),
                query: "RETURN 2".into(),
                selectivity: "catalog".into(),
            },
        ];
        let records = vec![
            json!({"case":"fast","stage":"pressure","passed":true,"requestMs":12.0,"validatedRequestMs":1500.0}),
            json!({"case":"slow","stage":"pressure","passed":true,"requestMs":120.0,"validatedRequestMs":1600.0}),
        ];
        assert_eq!(request_samples(&records, "fast", "pressure"), vec![12.0]);
        assert_eq!(isolated_tasks(&cases, &records), vec![0; 40]);
        let mut delayed = records.clone();
        for record in &mut delayed {
            record["validatedRequestMs"] = json!(1_000_000.0);
        }
        assert_eq!(request_samples(&delayed, "fast", "pressure"), vec![12.0]);
        assert_eq!(
            isolated_tasks(&cases, &delayed),
            isolated_tasks(&cases, &records)
        );
        let result = timing(
            "fast",
            "pressure",
            "p95",
            &request_samples(&records, "fast", "pressure"),
            &request_samples(&delayed, "fast", "pressure"),
            true,
        );
        assert_eq!(result["baseMs"], 12.0);
        assert_eq!(result["headMs"], 12.0);
    }
    #[test]
    fn report_preserves_per_case_percentiles_and_unmatched_isolated_selection() {
        let low: Vec<_> = (1..=20).map(f64::from).collect();
        let high: Vec<_> = (101..=120).map(f64::from).collect();
        let observations = vec![
            timing("low", "pressure", "p50", &low, &high, true),
            timing("low", "pressure", "p95", &low, &high, true),
            timing("other", "pressure", "p95", &high, &low, true),
            timing("low", "isolated", "p95", &low, &[], true),
        ];
        assert_eq!(observations[0]["baseMs"], 10.0);
        assert_eq!(observations[0]["headMs"], 110.0);
        assert_eq!(observations[1]["baseMs"], 19.0);
        assert_eq!(observations[1]["headMs"], 119.0);
        assert_eq!(observations[2]["baseMs"], 119.0);
        assert_eq!(observations[2]["headMs"], 19.0);
        assert_eq!(observations[3]["headMs"], Value::Null);
        assert_eq!(observations[3]["comparable"], false);
        let suite = json!({"schema":"graphite-benchmark-suite-v1","suite":"native-real64","baseSha":"a".repeat(40),"headSha":"b".repeat(40),
            "checks":[{"name":"correct","kind":"correctness","passed":true,"detail":"fixture"},{"name":"heap","kind":"heap","passed":true,"detail":"fixture"},{"name":"coverage","kind":"coverage","passed":true,"detail":"fixture"}],
            "timings":observations,"artifacts":[]});
        let output = temporary("report");
        assert!(crate::report::write_suite(&output, &suite).unwrap());
        let markdown = fs::read_to_string(output.join("report.md")).unwrap();
        assert!(markdown.contains("20/20 | 19.000 | 119.000 | 100.000"));
        assert!(markdown.contains("20/20 | 119.000 | 19.000 | -100.000"));
        assert!(markdown.contains("20/0 | 19.000 | UNAVAILABLE | UNAVAILABLE"));
        assert!(
            markdown.contains("Case not selected for this arm&#39;s predeclared isolated stage")
        );
        fs::remove_dir_all(output).unwrap();
    }
    #[test]
    fn base_input_failure_does_not_skip_head_attempt() {
        let output = temporary("independent-head");
        let head_manifest = output.join("head.tsv");
        fs::write(&head_manifest, "").unwrap();
        let report = run(&NativeArgs {
            base: output.join("missing-base"),
            head: output.join("missing-head"),
            base_manifest: output.join("missing-manifest"),
            head_manifest,
            output: output.clone(),
            base_sha: "a".repeat(40),
            head_sha: "b".repeat(40),
        })
        .unwrap();
        assert_eq!(report["baseStatus"], "UNAVAILABLE");
        assert_eq!(report["headStatus"], "UNAVAILABLE");
        assert!(report["headUnavailableReason"]
            .as_str()
            .unwrap()
            .contains("exactly 64"));
        assert!(
            output.join("base-requests.json").is_file()
                && output.join("head-requests.json").is_file()
        );
        assert!(!crate::report::validate_suite(&report).unwrap());
        fs::remove_dir_all(output).unwrap();
    }
    #[cfg(unix)]
    #[test]
    fn pressure_workers_issue_every_task_and_retain_every_sample() {
        use std::sync::{
            atomic::{AtomicUsize, Ordering},
            Arc,
        };
        let listener = std::net::TcpListener::bind(("127.0.0.1", PORT)).unwrap();
        let active = Arc::new(AtomicUsize::new(0));
        let peak = Arc::new(AtomicUsize::new(0));
        let seen_peak = peak.clone();
        let server = std::thread::spawn(move || {
            let mut workers = Vec::new();
            for _ in 0..20 {
                let (mut connection, _) = listener.accept().unwrap();
                let active = active.clone();
                let peak = peak.clone();
                workers.push(std::thread::spawn(move || {
                    let mut buffer = [0u8; 4096];
                    let _ = connection.read(&mut buffer);
                    let count = active.fetch_add(1, Ordering::SeqCst) + 1;
                    peak.fetch_max(count, Ordering::SeqCst);
                    std::thread::sleep(Duration::from_millis(20));
                    let payload =
                        body(json!([{"v":7,"$metadata":{"graphIds":["g00"]}}])).to_string();
                    write!(
                        connection,
                        "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                        payload.len(),
                        payload
                    )
                    .unwrap();
                    active.fetch_sub(1, Ordering::SeqCst);
                }));
            }
            for worker in workers {
                worker.join().unwrap();
            }
        });
        let output = temporary("pressure");
        let spec = ProcessSpec {
            label: "test-process-lifetime".into(),
            program: "/bin/sleep".into(),
            args: vec!["30".into()],
            cwd: None,
            env: BTreeMap::new(),
            stdout: output.join("stdout"),
            stderr: output.join("stderr"),
            timeout: Duration::from_secs(30),
        };
        let mut process = ManagedProcess::spawn(&spec).unwrap();
        let client = Client::builder()
            .timeout(Duration::from_secs(3))
            .no_proxy()
            .pool_max_idle_per_host(0)
            .build()
            .unwrap();
        let cases = vec![Case {
            id: "test".into(),
            query: "RETURN 7 AS v".into(),
            selectivity: "catalog".into(),
        }];
        let mut samples = Vec::new();
        let result = stage(
            &mut process,
            &client,
            "pressure",
            &stage_tasks(1, 20),
            4,
            &cases,
            &ids(),
            100,
            None,
            &output,
            &mut samples,
            Instant::now(),
        )
        .unwrap();
        process.terminate().unwrap();
        server.join().unwrap();
        assert_eq!(result[0]["rows"][0]["v"], 7);
        assert_eq!(samples.len(), 20);
        assert_eq!(
            samples
                .iter()
                .map(|s| s["sequence"].as_u64().unwrap())
                .collect::<BTreeSet<_>>()
                .len(),
            20
        );
        assert!(seen_peak.load(Ordering::SeqCst) > 1 && seen_peak.load(Ordering::SeqCst) <= 4);
        assert_eq!(fs::read_dir(output.join("pressure")).unwrap().count(), 61);
        fs::remove_dir_all(output).unwrap();
    }
}

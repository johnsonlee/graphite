//! Local six-case diagnostic protocol. Does not change trusted real64 CI policy.
use super::*;
use clap::{Args, ValueEnum};
use serde::Serialize;
use std::io::Write;

#[path = "local_query/resources.rs"]
mod resources;

#[derive(Debug, Clone, Copy, ValueEnum, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum Mode {
    Smoke,
    Pilot,
    Measure,
}
impl Mode {
    fn pressure(self) -> usize {
        match self {
            Self::Smoke => 0,
            Self::Pilot => 20,
            Self::Measure => 100,
        }
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, Serialize)]
pub enum Order {
    Ab,
    Ba,
}

#[derive(Debug, Clone, Copy, ValueEnum, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum Workload {
    Complex,
    Regression,
}
impl Workload {
    fn pressure(self, mode: Mode) -> usize {
        if matches!(mode, Mode::Smoke) {
            0
        } else if matches!(self, Self::Regression) {
            PRESSURE_PER_CASE
        } else {
            mode.pressure()
        }
    }
}

#[derive(Debug, Args)]
pub struct LocalQueryArgs {
    #[arg(long)]
    pub base: PathBuf,
    #[arg(long)]
    pub candidate: Option<PathBuf>,
    /// Existing trusted producer's complete real64 graphs.tsv and provenance.
    #[arg(long)]
    pub manifest: PathBuf,
    #[arg(long)]
    pub output: PathBuf,
    #[arg(long, value_enum, default_value = "smoke")]
    pub mode: Mode,
    #[arg(long, value_enum, default_value = "ab")]
    pub order: Order,
    /// Fixed six analytical queries or the unchanged 73-case query regression catalog.
    #[arg(long, value_enum, default_value = "complex")]
    pub workload: Workload,
}

fn write_json(path: &Path, value: &Value) -> Result<()> {
    fs::write(path, serde_json::to_vec_pretty(value)?)?;
    Ok(())
}

fn file_sha(path: &Path) -> Result<String> {
    let mut file = fs::File::open(path)?;
    let mut digest = Sha256::new();
    let mut buffer = [0u8; 1024 * 128];
    loop {
        let n = file.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        digest.update(&buffer[..n]);
    }
    Ok(digest
        .finalize()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect())
}

fn cases() -> Result<Vec<Case>> {
    #[derive(Deserialize)]
    struct Entry {
        id: String,
        query: String,
    }
    let entries: Vec<Entry> =
        serde_json::from_str(include_str!("../complex_aggregation_queries.json"))?;
    ensure!(
        entries.len() == 6 && entries.iter().map(|e| &e.id).collect::<BTreeSet<_>>().len() == 6,
        "Local protocol requires six distinct cases"
    );
    ensure!(
        entries.iter().all(|e| e.query.ends_with("LIMIT 100")),
        "Final transport limit changed"
    );
    Ok(entries
        .into_iter()
        .map(|e| Case {
            id: e.id,
            query: e.query,
            selectivity: "complex-local".into(),
        })
        .collect())
}

fn files(directory: &Path, out: &mut Vec<PathBuf>) -> Result<()> {
    for entry in fs::read_dir(directory)? {
        let entry = entry?;
        let kind = entry.file_type()?;
        ensure!(
            !kind.is_symlink(),
            "Fixture symlinks are not accepted: {}",
            entry.path().display()
        );
        if kind.is_dir() {
            files(&entry.path(), out)?;
        } else if kind.is_file() {
            out.push(entry.path());
        }
    }
    Ok(())
}

// Hash once before any server is started. Post-run metadata checks detect input
// mutation without repeatedly rereading every graph between timed arms.
fn fixture_seal(manifest: &Manifest) -> Result<Vec<Value>> {
    let mut result = Vec::new();
    for graph in &manifest.graphs {
        let mut paths = Vec::new();
        files(&graph.path, &mut paths)?;
        paths.sort();
        for path in paths {
            let meta = fs::metadata(&path)?;
            result.push(json!({"graph":graph.id,"path":path,"bytes":meta.len(),
                "modifiedNs":meta.modified()?.duration_since(std::time::UNIX_EPOCH)?.as_nanos().to_string(),
                "sha256":file_sha(&path)?}));
        }
    }
    Ok(result)
}

fn check_unchanged(manifest: &Manifest, seal: &[Value]) -> Result<()> {
    let mut actual = BTreeSet::new();
    for graph in &manifest.graphs {
        let mut paths = Vec::new();
        files(&graph.path, &mut paths)?;
        actual.extend(paths);
    }
    ensure!(
        actual.len() == seal.len(),
        "Persisted fixture file set changed"
    );
    for row in seal {
        let path = PathBuf::from(row["path"].as_str().context("seal path")?);
        ensure!(actual.contains(&path), "Fixture path changed");
        let meta = fs::metadata(path)?;
        ensure!(
            Some(meta.len()) == row["bytes"].as_u64()
                && meta
                    .modified()?
                    .duration_since(std::time::UNIX_EPOCH)?
                    .as_nanos()
                    .to_string()
                    == row["modifiedNs"],
            "Fixture mutated during run"
        );
    }
    Ok(())
}

fn start(
    binary: &Path,
    manifest: &Manifest,
    output: &Path,
) -> Result<(ManagedProcess, Instant, u64, Value)> {
    fs::create_dir_all(output)?;
    ensure!(
        std::net::TcpListener::bind(("127.0.0.1", PORT)).is_ok(),
        "Native server port already occupied"
    );
    let mut args: Vec<OsString> = [
        "serve",
        "--port",
        "18080",
        "--cypher-max-timeout-ms",
        "60000",
        "--max-concurrent-cypher",
        "4",
        "--load-mode",
        "MAPPED",
    ]
    .iter()
    .map(OsString::from)
    .collect();
    for graph in &manifest.graphs {
        args.extend([
            "--graph".into(),
            format!("{}:{}", graph.id, graph.path.display()).into(),
        ]);
    }
    let spec = ProcessSpec {
        label: "local-complex-query".into(),
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
    let ids = manifest.graphs.iter().map(|g| g.id.clone()).collect();
    let probe = Client::builder()
        .timeout(Duration::from_secs(2))
        .no_proxy()
        .build()?;
    let mut progress = Instant::now();
    loop {
        ensure!(
            server.try_wait()?.is_none(),
            "Server exited before readiness"
        );
        ensure!(
            started.elapsed() < STARTUP_TIMEOUT,
            "Readiness deadline exceeded"
        );
        if let Ok(reply) = probe
            .get(format!("http://127.0.0.1:{PORT}/api/graphs"))
            .send()
        {
            if reply.status().as_u16() == 200 {
                let bytes = reply.bytes()?;
                let total = registry(&bytes, &ids)?;
                fs::write(output.join("registry.json"), &bytes)?;
                let snapshot = resources::snapshot(server.id())?;
                write_json(
                    &output.join("ready.json"),
                    &json!({"wallMs":started.elapsed().as_secs_f64()*1000.,"resources":snapshot}),
                )?;
                return Ok((server, started, total, snapshot));
            }
        }
        if progress.elapsed() >= Duration::from_secs(10) {
            eprintln!(
                "local-query loading: {:.1}s",
                started.elapsed().as_secs_f64()
            );
            progress = Instant::now();
        }
        std::thread::sleep(Duration::from_millis(100));
    }
}

#[allow(clippy::too_many_arguments)]
fn local_stage(
    server: &mut ManagedProcess,
    client: &Client,
    name: &str,
    repeats: usize,
    cases: &[Case],
    ids: &BTreeSet<String>,
    nodes: u64,
    expected: Option<&[Value]>,
    output: &Path,
    samples: &mut Vec<Value>,
    started: Instant,
) -> Result<Vec<Value>> {
    local_tasks(
        server,
        client,
        name,
        &stage_tasks(cases.len(), repeats),
        CONCURRENCY,
        cases,
        ids,
        nodes,
        expected,
        output,
        samples,
        started,
    )
}

#[allow(clippy::too_many_arguments)]
fn local_tasks(
    server: &mut ManagedProcess,
    client: &Client,
    name: &str,
    tasks: &[usize],
    concurrency: usize,
    cases: &[Case],
    ids: &BTreeSet<String>,
    nodes: u64,
    expected: Option<&[Value]>,
    output: &Path,
    samples: &mut Vec<Value>,
    started: Instant,
) -> Result<Vec<Value>> {
    use std::sync::atomic::{AtomicBool, Ordering};
    let done = AtomicBool::new(false);
    std::thread::scope(|scope| {
        scope.spawn(|| {
            let mut last=0;
            let mut reported=BTreeSet::new();
            let mut tick=Instant::now();
            while !done.load(Ordering::Acquire) {
                let completed=fs::read_dir(output.join(name)).map(|es|es.flatten().filter(|e|e.file_name().to_string_lossy().ends_with(".record.json")).count()).unwrap_or(0);
                if completed!=last || tick.elapsed()>=Duration::from_secs(10) {
                    eprintln!("local-query {name}: {completed}/{} complete, elapsed {:.1}s",tasks.len(),started.elapsed().as_secs_f64());
                    let _=std::io::stderr().flush(); last=completed; tick=Instant::now();
                }
                if name=="first-use" {
                    if let Ok(entries)=fs::read_dir(output.join(name)) {
                        for entry in entries.flatten() {
                            let path=entry.path();
                            if !entry.file_name().to_string_lossy().ends_with(".record.json") || reported.contains(&path) {continue;}
                            if let Ok(bytes)=fs::read(&path) { if let Ok(row)=serde_json::from_slice::<Value>(&bytes) {
                                eprintln!("local-query first-use case={} passed={} requestMs={} error={}",row["case"],row["passed"],row["requestMs"],row["error"]);
                                reported.insert(path);
                            }}
                        }
                    }
                }
                std::thread::sleep(Duration::from_millis(200));
            }
        });
        let result = stage_with_timeout(
            server,
            client,
            name,
            tasks,
            concurrency,
            cases,
            ids,
            nodes,
            expected,
            output,
            samples,
            started,
            60_000,
            true,
        );
        done.store(true, Ordering::Release);
        result
    })
}

fn arm(
    binary: &Path,
    manifest: &Manifest,
    cases: &[Case],
    output: &Path,
    mode: Mode,
    workload: Workload,
    matched_oracle: Option<&[Value]>,
) -> Value {
    let mut data = json!({"binary":binary,"samples":[],"errors":[],"performanceAcceptance":false});
    let mut samples = Vec::new();
    let result = (|| -> Result<()> {
        data["binarySha256"] = json!(file_sha(binary)?);
        if !matches!(mode, Mode::Smoke) {
            let (mut load, _, _, ready) = start(binary, manifest, &output.join("load-only"))?;
            let (_, usage) = load.terminate_with_rusage()?;
            data["loading"] = json!({"ready":ready,"lifetime":resources::lifetime(&usage),
                "boundary":"fresh server start through verified registry readiness; controlled stop"});
            write_json(&output.join("loading.json"), &data["loading"])?;
        }
        let query_dir = output.join("query");
        let (mut server, started, nodes, ready) = start(binary, manifest, &query_dir)?;
        data["queryReady"] = ready;
        let client = Client::builder()
            .timeout(Duration::from_secs(70))
            .pool_max_idle_per_host(0)
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .build()?;
        let ids = manifest.graphs.iter().map(|g| g.id.clone()).collect();
        let query_result = (|| -> Result<()> {
            let oracle = local_stage(
                &mut server,
                &client,
                "first-use",
                1,
                cases,
                &ids,
                nodes,
                matched_oracle,
                &query_dir,
                &mut samples,
                started,
            )?;
            data["oracle"] = json!(oracle);
            data["afterFirstUse"] = resources::snapshot(server.id())?;
            if !matches!(mode, Mode::Smoke) {
                local_stage(
                    &mut server,
                    &client,
                    "warmup",
                    2,
                    cases,
                    &ids,
                    nodes,
                    Some(&oracle),
                    &query_dir,
                    &mut samples,
                    started,
                )?;
                let before = resources::snapshot(server.id())?;
                let pressure_start = Instant::now();
                local_stage(
                    &mut server,
                    &client,
                    "pressure",
                    workload.pressure(mode),
                    cases,
                    &ids,
                    nodes,
                    Some(&oracle),
                    &query_dir,
                    &mut samples,
                    started,
                )?;
                let after = resources::snapshot(server.id())?;
                data["pressure"] = json!({"before":before,"after":after,
                "cpuSeconds":resources::cpu_delta(&before,&after)?,"wallMs":pressure_start.elapsed().as_secs_f64()*1000.,
                "boundary":"complete fixed mixed pressure stage including client validation gaps; server CPU only"});
                if matches!(workload, Workload::Regression) {
                    let tasks = isolated_tasks(cases, &samples);
                    local_tasks(
                        &mut server,
                        &client,
                        "isolated",
                        &tasks,
                        1,
                        cases,
                        &ids,
                        nodes,
                        Some(&oracle),
                        &query_dir,
                        &mut samples,
                        started,
                    )?;
                }
            }
            ensure!(
                server.try_wait()?.is_none(),
                "Server ended before final snapshot"
            );
            data["queryEnd"] = resources::snapshot(server.id())?;
            Ok(())
        })();
        let resource_result = match server.captured_rusage() {
            Some(usage) => Ok(resources::lifetime(usage)),
            None => server
                .terminate_with_rusage()
                .map(|(_, usage)| resources::lifetime(&usage)),
        };
        match &resource_result {
            Ok(usage) => data["queryLifecycle"] = usage.clone(),
            Err(error) => data["queryLifecycleError"] = json!(format!("{error:#}")),
        }
        query_result?;
        resource_result?;
        ensure!(
            data["binarySha256"] == file_sha(binary)?,
            "Binary changed during arm"
        );
        Ok(())
    })();
    data["samples"] = json!(samples);
    data["complete"] = json!(result.is_ok());
    if let Err(e) = result {
        data["errors"] = json!([format!("{e:#}")]);
    }
    data
}

fn summarize(
    cases: &[Case],
    arms: &Value,
    comparable: bool,
    pressure_expected: usize,
) -> Vec<Value> {
    let mut rows = Vec::new();
    for case in cases {
        for stage in ["first-use", "pressure", "isolated"] {
            let mut row = json!({"case":case.id,"stage":stage,"comparable":comparable,"performanceAcceptance":false});
            for name in ["base", "candidate"] {
                let all = arms[name]["samples"]
                    .as_array()
                    .cloned()
                    .unwrap_or_default();
                let issued: Vec<_> = all
                    .iter()
                    .filter(|r| r["case"] == case.id && r["stage"] == stage)
                    .collect();
                let failed = issued.iter().filter(|r| r["passed"] != true).count();
                let expected = match stage {
                    "first-use" => 1,
                    "pressure" => pressure_expected,
                    _ => 40,
                };
                let samples = request_samples(&all, &case.id, stage);
                let available = failed == 0
                    && expected > 0
                    && issued.len() == expected
                    && samples.len() == expected;
                row[name] = json!({"issued":issued.len(),"expected":expected,"failedCount":failed,"successfulCount":samples.len(),
                "available":available,"p50Ms":if available && stage!="first-use"{percentile(&samples,0.5)}else{None},
                "p95Ms":if available && stage!="first-use"{percentile(&samples,0.95)}else{None},
                "firstUseMs":if available && stage=="first-use"{samples.first().copied()}else{None},
                "minMs":if available{samples.iter().copied().reduce(f64::min)}else{None},
                "maxMs":if available{samples.iter().copied().reduce(f64::max)}else{None},
                "firstUseNote":if stage=="first-use" {Some("one observation; not a latency distribution; not disk cold")}else{None}});
            }
            row["comparable"] = json!(
                comparable
                    && row["base"]["available"] == true
                    && row["candidate"]["available"] == true
            );
            rows.push(row);
        }
    }
    rows
}

pub fn run(args: &LocalQueryArgs) -> Result<bool> {
    ensure!(
        !args.output.exists(),
        "Use a fresh output directory; retained failures must not be overwritten"
    );
    fs::create_dir_all(&args.output)?;
    let mut report = json!({"schema":"graphite-local-complex-query-v1","mode":args.mode,"order":args.order,"workload":args.workload,
        "environment":{"os":std::env::consts::OS,"arch":std::env::consts::ARCH,
        "availableParallelism":std::thread::available_parallelism().ok().map(|n|n.get()),
        "backgroundLoad":"uncontrolled; record external observations separately; controller does not stop other processes"},
        "performanceAcceptance":false,"complete":false,"arms":{},"errors":[],
        "protocol":{"graphs":64,"cases":if matches!(args.workload,Workload::Complex){6}else{73},"concurrency":4,"firstUsePerCase":1,"warmupPerCase":if matches!(args.mode,Mode::Smoke){0}else{2},
        "pressurePerCase":args.workload.pressure(args.mode),"isolatedPerCase":if matches!(args.workload,Workload::Regression){40}else{0},"isolatedThresholdMs":100,"serverTimeoutMs":60000,"clientTimeoutMs":70000,"percentiles":"nearest rank per case; preserve all failures",
        "cache":"filesystem cache uncontrolled; fixture hashing precedes runs; load-only process precedes query process except smoke",
        "boundary":"complete HTTP response; closed-loop workers validate before next request; not saturation",
        "resources":"server CPU user+system; exact kernel RSS is process lifetime including loading, never subtracted",
        "correctness":"own first-use repeat oracle and matched full typed responses; not an independent real-data semantic oracle",
        "acceptance":"local diagnostic only; official 73-case CI and production acceptance unchanged"}});
    write_json(&args.output.join("protocol.json"), &report)?;
    let result = (|| -> Result<()> {
        let manifest = read_manifest(&args.manifest)?;
        let sources = source_inputs(&args.manifest, &manifest.graphs)?;
        let cases = match args.workload {
            Workload::Complex => cases()?,
            Workload::Regression => catalog(&manifest)?,
        };
        report["sourceInputs"] = sources;
        report["manifestSha256"] = json!(file_sha(&args.manifest)?);
        report["catalogSha256"] = json!(sha(&serde_json::to_vec(
            &cases.iter().map(|c| (&c.id, &c.query)).collect::<Vec<_>>()
        )?));
        write_json(
            &args.output.join("queries.json"),
            &json!(cases
                .iter()
                .map(|c| json!({"id":c.id,"query":c.query,"sha256":sha(c.query.as_bytes())}))
                .collect::<Vec<_>>()),
        )?;
        eprintln!("local-query sealing 64 persisted input graphs before execution");
        let seal = fixture_seal(&manifest)?;
        write_json(&args.output.join("fixture-seal.json"), &json!(seal))?;
        let mut order = vec![("base", &args.base)];
        if let Some(candidate) = &args.candidate {
            order.push(("candidate", candidate));
        }
        if matches!(args.order, Order::Ba) {
            order.reverse();
        }
        let mut oracle: Option<Vec<Value>> = None;
        for (name, binary) in order {
            eprintln!("local-query {name}: {:?}", args.mode);
            let directory = args.output.join(name);
            fs::create_dir_all(&directory)?;
            let value = arm(
                binary,
                &manifest,
                &cases,
                &directory,
                args.mode,
                args.workload,
                oracle.as_deref(),
            );
            let complete = value["complete"] == true;
            if oracle.is_none() {
                oracle = value["oracle"].as_array().cloned();
            }
            report["arms"][name] = value.clone();
            write_json(&directory.join("arm.json"), &value)?;
            write_json(&args.output.join("report.json"), &report)?;
            ensure!(complete, "{name} incomplete: {}", value["errors"]);
            check_unchanged(&manifest, &seal)?;
        }
        let comparable = args.candidate.is_some()
            && report["arms"]["base"]["oracle"] == report["arms"]["candidate"]["oracle"];
        report["comparable"] = json!(comparable);
        report["timings"] = json!(summarize(
            &cases,
            &report["arms"],
            comparable,
            args.workload.pressure(args.mode)
        ));
        report["complete"] = json!(true);
        Ok(())
    })();
    if let Err(error) = result {
        report["errors"] = json!([format!("{error:#}")]);
    }
    if report.get("timings").is_none() {
        if let Ok(manifest) = read_manifest(&args.manifest) {
            let catalog = match args.workload {
                Workload::Complex => cases(),
                Workload::Regression => catalog(&manifest),
            };
            if let Ok(cases) = catalog {
                report["timings"] = json!(summarize(
                    &cases,
                    &report["arms"],
                    false,
                    args.workload.pressure(args.mode)
                ));
            }
        }
    }
    write_json(&args.output.join("report.json"), &report)?;
    Ok(report["complete"] == true)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn fixed_modes_and_catalog_preserve_complex_work() {
        assert_eq!(Mode::Smoke.pressure(), 0);
        assert_eq!(Mode::Pilot.pressure(), 20);
        assert_eq!(Mode::Measure.pressure(), 100);
        assert_eq!(Workload::Regression.pressure(Mode::Measure), 20);
        assert_eq!(Workload::Regression.pressure(Mode::Smoke), 0);
        let cases = cases().unwrap();
        assert_eq!(cases.len(), 6);
        assert!(cases.iter().any(|c| c.query.contains("OPTIONAL MATCH")));
        assert!(cases.iter().any(|c| c
            .query
            .contains("UNWIND targets AS left_target UNWIND targets AS right_target")));
        assert!(cases.iter().all(|c| c.query.matches("LIMIT").count() == 1));
    }
    #[test]
    fn summary_never_passes_missing_or_failed_evidence() {
        let cases = vec![Case {
            id: "q".into(),
            query: "RETURN 1".into(),
            selectivity: "test".into(),
        }];
        let arms = json!({"base":{"samples":[{"case":"q","stage":"pressure","passed":true,"requestMs":2.0},
            {"case":"q","stage":"pressure","passed":true,"requestMs":9.0},
            {"case":"q","stage":"pressure","passed":false,"requestMs":100.0}]}});
        let rows = summarize(&cases, &arms, false, 3);
        assert_eq!(rows[1]["base"]["p95Ms"], Value::Null);
        assert_eq!(rows[1]["base"]["failedCount"], 1);
        assert_eq!(rows[1]["base"]["successfulCount"], 2);
        assert_eq!(rows[1]["candidate"]["p95Ms"], Value::Null);
        assert_eq!(rows[1]["performanceAcceptance"], false);
        assert_eq!(rows[1]["comparable"], false);
    }
}

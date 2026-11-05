# Fixed real64 diagnostic protocol (no measurements started)

Purpose: separate native code, persisted-data layout, and environment/order effects.
The source graph population is the existing real64 output from the old paired producer
run: 64 distinct shards from Android, Tika, Hive and Kotlin compiler real JARs, with
source JAR/shard hashes in fixture-provenance.tsv. No synthetic graph and no single-graph
performance request is permitted. Root must verify producer closure before binding config.

`config.unbound.json` defines four arms: main/candidate code × base/head saved data.
Supply exact binary paths, SHA256 and full source commit in a separate config.json;
set each independently reviewed dataset's producerClosureVerified to true. The template
cannot run. Explicit pairs isolate same-binary data effects or same-data code effects;
product-pair changes both and must not be mistaken for causal isolation. No binary/path
or graph-version inference and no reuse of one measured arm across separate pair runs.

73 exact query texts and full historical typed response oracles are pinned in
oracle-index.json and oracles/*.json. They were read from retained first-use responses,
not generated fixtures. The focused five were fixed from the five highest historical
baseline pressure p95 values before new measurements: shape-order-by-desc,
shape-order-by, shape-collect, shape-with-aggregate, shape-group-count. Execution order
is their original catalog order. Full mode preserves all 73 catalog queries.

Every request POSTs the global /api/cypher endpoint with query and timeoutMs. The server
contains exactly the 64 manifest graph IDs and no extra graph. Registry IDs and each
per-graph node count must match pinned source receipts. Every response must report
 graphCount=64, exact rowCount, declared unique columns, and valid distinct per-row graph
provenance; full typed JSON equals the pinned oracle, including null omissions, row
order/duplicates, metadata and total. No normalization removes actual semantic fields.
The selected queries have no single-graph restriction. Request-level graphCount and
registry scope are validated; row provenance records result origins, not scan coverage.

Default three paired rounds use AB, BA, AB. Explicitly changing rounds to one permits
only a bounded exploratory pair, not final repeated evidence; final full runs need
three rounds. Each arm gets a fresh server. There is one first-use oracle per case,
then two warmups and twenty measured pressure requests per case. All stages use four
closed-loop workers consuming one shared FIFO in repeat-major catalog order. A worker
fully reads, saves and validates its response before taking its next case. Work remains
interleaved across cases; this is not case-by-case isolated scheduling or saturation.
No isolated stage is substituted for this protocol. Request latency ends after full
HTTP consumption, before artifact writes/JSON validation; stage wall time includes
those client steps. A 300-second total read deadline and 64 MiB response bound retain
partial bytes/errors on failure. Failed samples and all completed samples are retained.

Loading and queries run as separate operations/processes. Load wall/CPU ends after
registry readiness and the complete selected first-use workload; registry-ready is
reported separately. Thus a five-case load and a73-case load are different declared
boundaries. Query processes independently perform readiness, first-use and warmup
before the pressure stage. Scope/failure checks are outside timing conclusions.

Live server user+system CPU is sampled around each entire interleaved stage using the
existing corrected lifecycle_resources.py: macOS proc_pid_rusage V2 Mach ticks converted
with mach_timebase_info, Linux /proc clock ticks. This CPU cannot be attributed to any
individual concurrent case; CPU/request refers only to the complete mixed stage.
wait4 records kernel peak process RSS plus lifetime CPU for each fresh load-only/query
process, including startup, first-use, warmup if any, requests and shutdown. It is not
query-only RSS or a concurrent process-tree sum. Ten-millisecond current-RSS sampling
is merely a supplementary lower bound; no replacement of kernel high-water. Native
processes have no JVM heap setting; this runner builds no JVM product or graph.

No cache eviction, no quiet-host assumption, no one-arm output overwrite. Input file
stat inventories (size/mtime), manifest and source receipt hashes are saved. Stat
inventories do not claim complete byte hashes for large sparse persisted files; root's
producer/source audit is separate. Repeated runs may have different OS cache states;
report cache state and ordering, do not rename filesystem-warm as cold. Retain prior
failed/incomparable outcomes. Report each case's nearest-rank p50/p95 separately, all
rounds and pooled samples, plus whole-workload CPU, lifetime RSS, and load boundaries.
No result automatically declares performance acceptance or relaxes the5% CPU/RSS limits.

Correctness-only tests (no server, graph query or timing sample):

    python3 -m unittest -v test_multi64

After root binds config and authorizes measurement, fresh output examples:

    python3 multi64.py run config.json --pair main-data-effect --cases focused --operation query --out focused-data
    python3 multi64.py run config.json --pair code-on-head-data --cases focused --operation query --out focused-code
    python3 multi64.py run config.json --pair code-on-head-data --cases all --operation load --out full-load
    python3 multi64.py run config.json --pair code-on-head-data --cases all --operation query --out full-query

These commands are instructions only; none has been executed during preparation.
Expected counts per query pair: rounds ×2 arms ×caseCount ×(1 first-use+2 warmup+20
pressure). At three rounds: focused690 total/600 pressure; full10074 total/8760 pressure.
Load has rounds ×2 ×caseCount first-use requests. Kernel resource records count one per
fresh arm/process. Full bytes, commands, exact selection, protocol, samples.jsonl and
summary.json remain in the fresh output directory. Pair-level parity must match the
same pinned oracle for all arms before correctness is reported successful.

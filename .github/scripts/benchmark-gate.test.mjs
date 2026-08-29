import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
    COMMENT_MARKER,
    aggregateReports,
    combineLatencyShards,
    compareLatencyResources,
    confirmLatencyResources,
    confirmLargeCorpus,
    LATENCY_EXPECTED_BENCHMARK_KEYS,
    LATENCY_EXPECTED_SHARDS,
    LATENCY_RESOURCE_EXPECTED_BENCHMARK_KEYS,
    confirmLatencyBaseline,
    confirmJmh,
    compareJmh,
    compareLatencyBaseline,
    compareLargeCorpus,
    parseLargeCorpusLog,
    renderJmhReport,
    renderLatencyBaselineReport,
    renderLargeCorpusReport
} from "./benchmark-gate.mjs";

function jmhResult({
    benchmark = "io.johnsonlee.graphite.cypher.CypherBenchmark.query",
    mode = "avgt",
    score,
    confidence,
    unit = "us/op",
    params,
    jvmArgs,
    secondaryMetrics
}) {
    const result = {
        benchmark,
        mode,
        primaryMetric: {
            score,
            scoreConfidence: confidence,
            scoreUnit: unit
        }
    };
    if (params !== undefined) result.params = params;
    if (jvmArgs !== undefined) result.jvmArgs = jvmArgs;
    if (secondaryMetrics !== undefined) result.secondaryMetrics = secondaryMetrics;
    return result;
}

function metric(score, scoreUnit) {
    return { score, scoreUnit };
}

function eventMetric(value, scoreUnit = "#") {
    return { score: value * 3, scoreUnit, rawData: [[value, value, value]] };
}

function resourceResult({ allFixture = false, overrides = {}, jvmArgs } = {}) {
    const maxHeapBytes = (allFixture ? 8 : 4) * 1024 ** 3;
    return jmhResult({
        benchmark: LATENCY_RESOURCE_EXPECTED_BENCHMARK_KEYS[allFixture ? 1 : 0],
        mode: "ss",
        score: 1,
        confidence: [0.9, 1.1],
        unit: "ms/op",
        jvmArgs: jvmArgs ?? [`-Xmx${allFixture ? 8 : 4}g`],
        secondaryMetrics: {
            maxHeapBytes: eventMetric(maxHeapBytes),
            loadedHeapBytes: eventMetric(100 * 1024 ** 2),
            peakUsedHeapBytes: eventMetric(200 * 1024 ** 2),
            retainedHeapBytes: eventMetric(120 * 1024 ** 2),
            retainedHeapDeltaBytes: eventMetric(20 * 1024 ** 2),
            queryGcCount: eventMetric(0),
            queryGcTimeMs: eventMetric(0),
            "gc.alloc.rate.norm": metric(1_000_000, "B/op"),
            "gc.count": metric(0, "counts"),
            "gc.time": metric(0, "ms"),
            ...overrides
        }
    });
}

test("JMH comparison blocks a separated latency regression", () => {
    const comparison = compareJmh(
        [jmhResult({ score: 100, confidence: [95, 105] })],
        [jmhResult({ score: 125, confidence: [120, 130] })],
        15
    );

    assert.equal(comparison.passed, false);
    assert.equal(comparison.rows[0].blocked, true);
    assert.match(renderJmhReport(comparison), /\*\*FAIL\*\*/);
});

test("JMH comparison does not block overlapping confidence intervals", () => {
    const comparison = compareJmh(
        [jmhResult({ score: 100, confidence: [80, 120] })],
        [jmhResult({ score: 125, confidence: [110, 140] })],
        15
    );

    assert.equal(comparison.passed, true);
    assert.equal(comparison.rows[0].aboveThreshold, true);
    assert.equal(comparison.rows[0].blocked, false);
    assert.match(renderJmhReport(comparison), /\*\*NOISE\*\*/);
});

test("threshold-only JMH comparison confirms a regression despite overlapping intervals", () => {
    const comparison = compareJmh(
        [jmhResult({ score: 100, confidence: [80, 120] })],
        [jmhResult({ score: 225, confidence: [90, 360] })],
        15,
        true
    );

    assert.equal(comparison.passed, false);
    assert.equal(comparison.rows[0].confidenceSeparated, false);
    assert.equal(comparison.rows[0].blocked, true);
    assert.match(renderJmhReport(comparison), /regardless of confidence/);
});

test("threshold-only JMH confirmation blocks a repeated score regression", () => {
    const initial = compareJmh(
        [jmhResult({ score: 100, confidence: [80, 120] })],
        [jmhResult({ score: 225, confidence: [90, 360] })],
        15,
        true
    );
    const retry = compareJmh(
        [jmhResult({ score: 105, confidence: [70, 140] })],
        [jmhResult({ score: 220, confidence: [80, 360] })],
        15,
        true
    );
    const confirmed = confirmJmh(initial, retry);

    assert.equal(confirmed.passed, false);
    assert.equal(confirmed.rows[0].blocked, true);
    assert.match(renderJmhReport(confirmed), /\*\*FAIL\*\*/);
});

test("JMH report accepts a gate-specific title", () => {
    const comparison = compareJmh(
        [jmhResult({ score: 100, confidence: [95, 105] })],
        [jmhResult({ score: 101, confidence: [96, 106] })],
        15
    );

    assert.match(
        renderJmhReport(comparison, "Budgeted mapped-string latency"),
        /^### Budgeted mapped-string latency/m
    );
});

test("JMH throughput regression uses higher-is-better semantics", () => {
    const comparison = compareJmh(
        [jmhResult({ mode: "thrpt", score: 1_000, confidence: [980, 1_020], unit: "ops/s" })],
        [jmhResult({ mode: "thrpt", score: 700, confidence: [680, 720], unit: "ops/s" })],
        15
    );

    assert.equal(comparison.passed, false);
    assert.equal(Math.round(comparison.rows[0].delta), 30);
});

test("JMH comparison fails when a benchmark is missing", () => {
    const comparison = compareJmh(
        [jmhResult({ score: 100, confidence: [95, 105] })],
        [],
        15
    );

    assert.equal(comparison.passed, false);
    assert.match(comparison.errors[0], /missing from candidate/);
});

test("JMH reverse-order confirmation rejects a one-round false positive", () => {
    const initial = compareJmh(
        [jmhResult({ score: 100, confidence: [98, 102] })],
        [jmhResult({ score: 125, confidence: [123, 127] })],
        15
    );
    const retry = compareJmh(
        [jmhResult({ score: 110, confidence: [108, 112] })],
        [jmhResult({ score: 111, confidence: [109, 113] })],
        15
    );
    const confirmed = confirmJmh(initial, retry);

    assert.equal(confirmed.passed, true);
    assert.equal(confirmed.rows[0].blocked, false);
    assert.match(renderJmhReport(confirmed), /\*\*NOISE\*\*/);
});

test("JMH reverse-order confirmation blocks a repeated regression", () => {
    const initial = compareJmh(
        [jmhResult({ score: 100, confidence: [98, 102] })],
        [jmhResult({ score: 125, confidence: [123, 127] })],
        15
    );
    const retry = compareJmh(
        [jmhResult({ score: 102, confidence: [100, 104] })],
        [jmhResult({ score: 128, confidence: [126, 130] })],
        15
    );
    const confirmed = confirmJmh(initial, retry);

    assert.equal(confirmed.passed, false);
    assert.equal(confirmed.rows[0].blocked, true);
    assert.match(renderJmhReport(confirmed), /\*\*FAIL\*\*/);
});

test("latency baseline requires both fixed-baseline speedup and no base regression", () => {
    const comparison = compareLatencyBaseline(
        [jmhResult({ score: 100, confidence: [98, 102] })],
        [jmhResult({ score: 20, confidence: [19, 21] })],
        [jmhResult({ score: 21, confidence: [20, 22] })]
    );

    assert.equal(comparison.passed, true);
    assert.equal(Math.round(comparison.rows[0].speedup), 376);
    assert.match(renderLatencyBaselineReport(comparison), /Pre-PR-95/);
});

test("latency baseline blocks loss of the fixed-baseline optimization", () => {
    const comparison = compareLatencyBaseline(
        [jmhResult({ score: 100, confidence: [98, 102] })],
        [jmhResult({ score: 20, confidence: [19, 21] })],
        [jmhResult({ score: 80, confidence: [78, 82] })]
    );

    assert.equal(comparison.passed, false);
    assert.equal(comparison.rows[0].improvementBlocked, true);
    assert.equal(comparison.rows[0].blocked, true);
});

test("latency baseline fails closed without fixed speedup confidence bounds", () => {
    const comparison = compareLatencyBaseline(
        [jmhResult({ score: 100 })],
        [jmhResult({ score: 20 })],
        [jmhResult({ score: 20 })]
    );

    assert.equal(comparison.passed, false);
    assert.equal(comparison.rows[0].improvementSeparated, false);
    assert.equal(comparison.rows[0].blocked, true);
    assert.match(comparison.errors.join("\n"), /requires finite confidence bounds/);
});

test("latency confirmation blocks only failures repeated by the same benchmark", () => {
    const first = "example.First.query";
    const second = "example.Second.query";
    const initial = compareLatencyBaseline(
        [
            jmhResult({ benchmark: first, score: 100, confidence: [98, 102] }),
            jmhResult({ benchmark: second, score: 100, confidence: [98, 102] })
        ],
        [
            jmhResult({ benchmark: first, score: 20, confidence: [19, 21] }),
            jmhResult({ benchmark: second, score: 20, confidence: [19, 21] })
        ],
        [
            jmhResult({ benchmark: first, score: 80, confidence: [78, 82] }),
            jmhResult({ benchmark: second, score: 20, confidence: [19, 21] })
        ]
    );
    const retry = compareLatencyBaseline(
        [
            jmhResult({ benchmark: first, score: 100, confidence: [98, 102] }),
            jmhResult({ benchmark: second, score: 100, confidence: [98, 102] })
        ],
        [
            jmhResult({ benchmark: first, score: 20, confidence: [19, 21] }),
            jmhResult({ benchmark: second, score: 20, confidence: [19, 21] })
        ],
        [
            jmhResult({ benchmark: first, score: 20, confidence: [19, 21] }),
            jmhResult({ benchmark: second, score: 80, confidence: [78, 82] })
        ]
    );
    const confirmed = confirmLatencyBaseline(initial, retry);

    assert.equal(initial.passed, false);
    assert.equal(retry.passed, false);
    assert.equal(confirmed.passed, true);
    assert.equal(confirmed.rows.find((row) => row.key === first).blocked, false);
    assert.equal(confirmed.rows.find((row) => row.key === second).blocked, false);
    assert.match(renderLatencyBaselineReport(confirmed), /Confirmation/);
});

test("latency baseline fails closed when one graph-count parameter is missing", () => {
    const fixed = [
        { ...jmhResult({ score: 100 }), params: { graphCount: "1" } },
        { ...jmhResult({ score: 6_400 }), params: { graphCount: "64" } }
    ];
    const base = fixed.map((result) => ({ ...result, primaryMetric: { ...result.primaryMetric, score: 20 } }));
    const candidate = base.slice(0, 1);
    const comparison = compareLatencyBaseline(fixed, base, candidate);

    assert.equal(comparison.passed, false);
    assert.match(comparison.errors.join("\n"), /graphCount=64/);
});

test("latency expected keys include geometric synthetic scaling and real 36-graph coverage", () => {
    for (const graphCount of [1, 4, 16, 64]) {
        assert.ok(LATENCY_EXPECTED_BENCHMARK_KEYS.some((key) => key.includes(`graphCount=${graphCount}]`)));
    }
    assert.ok(LATENCY_EXPECTED_BENCHMARK_KEYS.some((key) => key.includes("ThirtySixRealGraphs")));
});

test("multi-graph latency requires a ten-times fixed-baseline factor", () => {
    const benchmark = "io.johnsonlee.graphite.webgraph.AllFixtureWrappedDiscoveryLatencyBenchmark.zeroHitBroadContainsCaseInsensitiveDiscovery";
    const fixed = jmhResult({ benchmark, score: 100, confidence: [98, 102] });
    const base = jmhResult({ benchmark, score: 10, confidence: [9.5, 10.5] });
    const tooSlow = jmhResult({ benchmark, score: 11, confidence: [10.5, 11.5] });
    const comparison = compareLatencyBaseline([fixed], [base], [tooSlow]);

    assert.equal(comparison.passed, false);
    assert.equal(comparison.rows[0].minimumSpeedup, 900);
});

test("synthetic graph-count curves use the normal fixed-baseline floor", () => {
    const params = { graphCount: "64" };
    const fixed = jmhResult({ score: 100, confidence: [98, 102], params });
    const base = jmhResult({ score: 20, confidence: [19, 21], params });
    const candidate = jmhResult({ score: 20, confidence: [19, 21], params });
    const comparison = compareLatencyBaseline([fixed], [base], [candidate]);

    assert.equal(comparison.passed, true);
    assert.equal(comparison.rows[0].minimumSpeedup, 50);
});

test("synthetic latency ignores sub-half-millisecond changes but blocks larger regressions", () => {
    const benchmark = "io.johnsonlee.graphite.webgraph.WrappedDiscoveryLatencyBenchmark.wrappedCaseInsensitiveDiscovery";
    const params = { graphCount: "64" };
    const common = { benchmark, unit: "ms/op", params };
    const fixed = jmhResult({ ...common, score: 10, confidence: [9.8, 10.2] });
    const base = jmhResult({ ...common, score: 0.58, confidence: [0.56, 0.60] });
    const noise = jmhResult({ ...common, score: 0.87, confidence: [0.85, 0.89] });
    const regression = jmhResult({ ...common, score: 1.20, confidence: [1.18, 1.22] });

    const accepted = compareLatencyBaseline([fixed], [base], [noise]);
    const blocked = compareLatencyBaseline([fixed], [base], [regression]);

    assert.equal(accepted.passed, true);
    assert.equal(accepted.rows[0].aboveThreshold, true);
    assert.equal(accepted.rows[0].belowAbsoluteNoiseFloor, true);
    assert.equal(blocked.passed, false);
    assert.equal(blocked.rows[0].belowAbsoluteNoiseFloor, false);
});

test("resource gate accepts 4 GiB single and 8 GiB AllFixture profiles", () => {
    const base = [resourceResult(), resourceResult({ allFixture: true })];
    const candidate = [resourceResult(), resourceResult({ allFixture: true })];

    assert.equal(compareLatencyResources(base, candidate).passed, true);
});

test("resource gate reads per-invocation gauges instead of summed AuxCounters scores", () => {
    const base = [resourceResult(), resourceResult({ allFixture: true })];
    const candidate = [resourceResult(), resourceResult({ allFixture: true })];

    assert.equal(candidate[0].secondaryMetrics.maxHeapBytes.score, 12 * 1024 ** 3);
    assert.equal(candidate[1].secondaryMetrics.maxHeapBytes.score, 24 * 1024 ** 3);
    assert.equal(compareLatencyResources(base, candidate).passed, true);
});

test("resource gate fails closed on the wrong single-graph max heap", () => {
    const base = [resourceResult(), resourceResult({ allFixture: true })];
    const candidate = [resourceResult({ jvmArgs: ["-Xmx8g"] }), resourceResult({ allFixture: true })];
    const comparison = compareLatencyResources(base, candidate);

    assert.equal(comparison.passed, false);
    assert.match(comparison.errors.join("\n"), /expected exactly -Xmx4g/);
});

test("resource gate requires GC, retained, and peak metrics", () => {
    const missingGc = resourceResult({ overrides: { "gc.time": undefined } });
    const invalidHeap = resourceResult({ overrides: { retainedHeapBytes: eventMetric(300 * 1024 ** 2) } });
    const allFixture = resourceResult({ allFixture: true });

    assert.match(compareLatencyResources([resourceResult(), allFixture], [missingGc, allFixture]).errors.join("\n"),
        /gc.time/);
    assert.match(compareLatencyResources([resourceResult(), allFixture], [invalidHeap, allFixture]).errors.join("\n"),
        /invalid loaded\/retained\/peak/);
});

test("resource confirmation aligns the same metric before blocking", () => {
    const base = [resourceResult(), resourceResult({ allFixture: true })];
    const firstCandidate = [
        resourceResult({ overrides: { retainedHeapDeltaBytes: eventMetric(80 * 1024 ** 2) } }),
        resourceResult({ allFixture: true })
    ];
    const retryCandidate = [resourceResult(), resourceResult({ allFixture: true })];
    const initial = compareLatencyResources(base, firstCandidate);
    const confirmed = confirmLatencyResources(initial, compareLatencyResources(base, retryCandidate));

    assert.equal(initial.passed, false);
    assert.equal(confirmed.passed, true);
});

test("latency baseline fails when an expected benchmark disappears from all revisions", () => {
    const key = LATENCY_EXPECTED_BENCHMARK_KEYS[0];
    const benchmark = key.slice(0, key.indexOf("["));
    const params = { graphCount: "1" };
    const result = jmhResult({ benchmark, score: 10, confidence: [9, 11], unit: "ms/op", params });
    const comparison = compareLatencyBaseline(
        [result],
        [result],
        [jmhResult({ benchmark, score: 1, confidence: [0.9, 1.1], unit: "ms/op", params })],
        15,
        50,
        [key, LATENCY_EXPECTED_BENCHMARK_KEYS[1]]
    );

    assert.equal(comparison.passed, false);
    assert.match(comparison.errors.join("\n"), /missing expected latency benchmark/);
});

test("latency shard aggregation requires every shard and the complete benchmark key set", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "benchmark-latency-shards-"));
    try {
        LATENCY_EXPECTED_SHARDS.forEach((shard, index) => {
            const rows = LATENCY_EXPECTED_BENCHMARK_KEYS
                .filter((_, keyIndex) => keyIndex % LATENCY_EXPECTED_SHARDS.length === index)
                .map((key) => ({ key, blocked: false }));
            fs.writeFileSync(
                path.join(directory, `latency-status-${shard}.json`),
                JSON.stringify({ passed: true, errors: [], rows })
            );
        });

        assert.equal(combineLatencyShards(directory).passed, true);
        fs.rmSync(path.join(directory, "latency-status-real-d.json"));
        const incomplete = combineLatencyShards(directory);
        assert.equal(incomplete.passed, false);
        assert.match(incomplete.errors.join("\n"), /real-d: latency shard status is missing/);
        assert.match(incomplete.errors.join("\n"), /missing expected benchmark/);
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

const baseCorpusLine = [
    "LARGE_CORPUS_BASELINE",
    "hive",
    "nodes=100",
    "buildMs=10000",
    "saveMs=2000",
    "mappedLoadMs=200",
    "queryMs=1000",
    "pipelineMs=13200",
    `peakHeapBytes=${2_000 * 1024 * 1024}`
].join("\t");

test("large-corpus parser accepts Gradle-prefixed output", () => {
    const parsed = parseLargeCorpusLog(`runner prefix ${baseCorpusLine}\n`);

    assert.equal(parsed.get("hive").buildMs, 10_000);
    assert.equal(parsed.get("hive").nodes, 100);
});

test("large-corpus comparison blocks a material pipeline regression", () => {
    const candidate = baseCorpusLine
        .replace("pipelineMs=13200", "pipelineMs=17000")
        .replace("buildMs=10000", "buildMs=13000");
    const comparison = compareLargeCorpus(baseCorpusLine, candidate);

    assert.equal(comparison.passed, false);
    assert.equal(comparison.rows.find((row) => row.metric === "pipeline").blocked, true);
    assert.match(renderLargeCorpusReport(comparison), /\*\*FAIL\*\*/);
});

test("large-corpus comparison ignores changes below the absolute noise floor", () => {
    const candidate = baseCorpusLine.replace("mappedLoadMs=200", "mappedLoadMs=250");
    const comparison = compareLargeCorpus(baseCorpusLine, candidate);

    assert.equal(comparison.passed, true);
    assert.equal(comparison.rows.find((row) => row.metric === "mapped load").blocked, false);
});

test("large-corpus comparison reports sampled heap without blocking on GC noise", () => {
    const candidate = baseCorpusLine.replace(
        `peakHeapBytes=${2_000 * 1024 * 1024}`,
        `peakHeapBytes=${3_500 * 1024 * 1024}`
    );
    const comparison = compareLargeCorpus(baseCorpusLine, candidate);
    const heap = comparison.rows.find((row) => row.metric === "peak heap");

    assert.equal(comparison.passed, true);
    assert.equal(heap.advisory, true);
    assert.equal(heap.blocked, false);
    assert.match(renderLargeCorpusReport(comparison), /4 GiB cap \| \*\*INFO\*\*/);
});

test("large-corpus reverse-order confirmation rejects a one-round false positive", () => {
    const initialCandidate = baseCorpusLine.replace("saveMs=2000", "saveMs=3000");
    const initial = compareLargeCorpus(baseCorpusLine, initialCandidate);
    const retryCandidate = baseCorpusLine.replace("saveMs=2000", "saveMs=2100");
    const retry = compareLargeCorpus(baseCorpusLine, retryCandidate);
    const confirmed = confirmLargeCorpus(initial, retry);

    assert.equal(initial.passed, false);
    assert.equal(confirmed.passed, true);
    assert.equal(confirmed.rows.find((row) => row.metric === "save").blocked, false);
    assert.match(renderLargeCorpusReport(confirmed), /\*\*NOISE\*\*/);
});

test("large-corpus reverse-order confirmation blocks a repeated regression", () => {
    const candidate = baseCorpusLine.replace("saveMs=2000", "saveMs=3000");
    const initial = compareLargeCorpus(baseCorpusLine, candidate);
    const retry = compareLargeCorpus(baseCorpusLine, candidate);
    const confirmed = confirmLargeCorpus(initial, retry);

    assert.equal(confirmed.passed, false);
    assert.equal(confirmed.rows.find((row) => row.metric === "save").blocked, true);
    assert.match(renderLargeCorpusReport(confirmed), /\*\*FAIL\*\*/);
});

test("aggregate report fails closed when an artifact is missing", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "benchmark-gate-test-"));
    try {
        fs.writeFileSync(path.join(directory, "method-report.md"), "method report\n");
        fs.writeFileSync(path.join(directory, "method-status.json"), JSON.stringify({ passed: true }));
        const aggregate = aggregateReports(directory, {
            baseSha: "a".repeat(40),
            candidateSha: "b".repeat(40),
            runner: "test-runner",
            runUrl: "https://example.invalid/run"
        });

        assert.equal(aggregate.passed, false);
        assert.match(aggregate.body, new RegExp(COMMENT_MARKER));
        assert.match(aggregate.body, /budgeted-collection: result artifact is missing/);
        assert.match(aggregate.body, /explorer: result artifact is missing/);
        assert.match(aggregate.body, /method-compatibility: result artifact is missing/);
        assert.match(aggregate.body, /cypher-capacity: result artifact is missing/);
        assert.match(aggregate.body, /large-corpus: result artifact is missing/);
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

test("aggregate report includes every independent benchmark gate", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "benchmark-gate-complete-"));
    try {
        for (const [report, status, body] of [
            ["method-report.md", "method-status.json", "method report"],
            ["explorer-report.md", "explorer-status.json", "explorer report"],
            ["method-compatibility-report.md", "method-compatibility-status.json", "Method migration report"],
            ["cypher-capacity-report.md", "cypher-capacity-status.json", "capacity report"],
            ["budgeted-collection-report.md", "budgeted-collection-status.json", "collection report"],
            ["budgeted-string-report.md", "budgeted-string-status.json", "budgeted report"],
            ["large-corpus-report.md", "large-corpus-status.json", "large report"],
            ["latency-report.md", "latency-status.json", "latency report"],
            ["latency-resource-report.md", "latency-resource-status.json", "resource report"]
        ]) {
            fs.writeFileSync(path.join(directory, report), `${body}\n`);
            fs.writeFileSync(path.join(directory, status), JSON.stringify({ passed: true }));
        }
        const aggregate = aggregateReports(directory, {
            baseSha: "a".repeat(40),
            candidateSha: "b".repeat(40),
            runner: "test-runner",
            runUrl: "https://example.invalid/run"
        });

        assert.equal(aggregate.passed, true);
        assert.match(aggregate.body, /collection report/);
        assert.match(aggregate.body, /explorer report/);
        assert.match(aggregate.body, /Method migration report/);
        assert.match(aggregate.body, /capacity report/);
        assert.match(aggregate.body, /budgeted report/);
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

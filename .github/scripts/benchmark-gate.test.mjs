import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
    COMMENT_MARKER,
    aggregateReports,
    confirmLargeCorpus,
    LATENCY_EXPECTED_BENCHMARK_KEYS,
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
    params
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
    return result;
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
        { ...jmhResult({ score: 1_700 }), params: { graphCount: "17" } }
    ];
    const base = fixed.map((result) => ({ ...result, primaryMetric: { ...result.primaryMetric, score: 20 } }));
    const candidate = base.slice(0, 1);
    const comparison = compareLatencyBaseline(fixed, base, candidate);

    assert.equal(comparison.passed, false);
    assert.match(comparison.errors.join("\n"), /graphCount=17/);
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
        assert.match(aggregate.body, /large-corpus: result artifact is missing/);
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

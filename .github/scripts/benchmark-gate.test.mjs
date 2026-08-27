import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
    COMMENT_MARKER,
    aggregateReports,
    compareJmh,
    compareLargeCorpus,
    parseLargeCorpusLog,
    renderJmhReport,
    renderLargeCorpusReport
} from "./benchmark-gate.mjs";

function jmhResult({
    benchmark = "io.johnsonlee.graphite.cypher.CypherBenchmark.query",
    mode = "avgt",
    score,
    confidence,
    unit = "us/op"
}) {
    return {
        benchmark,
        mode,
        primaryMetric: {
            score,
            scoreConfidence: confidence,
            scoreUnit: unit
        }
    };
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

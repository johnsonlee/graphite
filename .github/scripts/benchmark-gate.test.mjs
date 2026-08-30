import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { validatePairedEvidence } from "./benchmark-pages.mjs";
import {
    BENCHMARK_COMPONENTS,
    BENCHMARK_COVERAGE_DOMAINS,
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
    confirmLatencyAnchor,
    confirmJmh,
    compareJmh,
    compareLatencyBaseline,
    compareLatencyAnchor,
    compareLargeCorpus,
    parseLargeCorpusLog,
    makeJmhAdvisory,
    renderJmhReport,
    renderLatencyBaselineReport,
    renderLatencyAnchorReport,
    renderLargeCorpusReport,
    selectJmhMetric,
    stageLatestArtifacts
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

test("JMH comparison gates a selected secondary metric", () => {
    const base = jmhResult({
        score: 100,
        secondaryMetrics: { processCpuNanos: metric(100, "ns/op") }
    });
    const candidate = jmhResult({
        score: 50,
        secondaryMetrics: { processCpuNanos: metric(130, "ns/op") }
    });
    const comparison = compareJmh(
        selectJmhMetric([base], "processCpuNanos"),
        selectJmhMetric([candidate], "processCpuNanos"),
        15,
        true
    );

    assert.equal(comparison.passed, false);
    assert.equal(Math.round(comparison.rows[0].delta), 30);
});

test("JMH comparison fails closed when a selected secondary metric is missing", () => {
    const comparison = compareJmh(
        selectJmhMetric([jmhResult({ score: 100 })], "residentSetAfterBytes"),
        selectJmhMetric([jmhResult({ score: 90 })], "residentSetAfterBytes"),
        15,
        true
    );

    assert.equal(comparison.passed, false);
    assert.match(comparison.errors.join("\n"), /invalid score/);
});

test("JMH secondary metric comparison accepts a stable zero baseline", () => {
    const comparison = compareJmh(
        selectJmhMetric([
            jmhResult({ score: 100, secondaryMetrics: { residentSetDeltaBytes: metric(0, "bytes") } })
        ], "residentSetDeltaBytes"),
        selectJmhMetric([
            jmhResult({ score: 90, secondaryMetrics: { residentSetDeltaBytes: metric(0, "bytes") } })
        ], "residentSetDeltaBytes"),
        15,
        true,
        true
    );

    assert.equal(comparison.passed, true);
    assert.equal(comparison.rows[0].delta, 0);
});

test("JMH advisory metric reports a regression without blocking", () => {
    const comparison = compareJmh(
        [jmhResult({ score: 100 })],
        [jmhResult({ score: 200 })],
        15,
        true
    );
    const advisory = makeJmhAdvisory(comparison);

    assert.equal(advisory.passed, true);
    assert.equal(advisory.rows[0].aboveThreshold, true);
    assert.equal(advisory.rows[0].blocked, false);
    assert.match(renderJmhReport(advisory), /reported for context/);
    assert.match(renderJmhReport(advisory), /\*\*INFO\*\*/);
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

test("latency anchor requires both the known-good and current-base guardrails", () => {
    const common = { unit: "ms/op" };
    const anchor = jmhResult({ ...common, score: 10, confidence: [9.8, 10.2] });
    const base = jmhResult({ ...common, score: 11, confidence: [10.8, 11.2] });
    const candidate = jmhResult({ ...common, score: 12, confidence: [11.8, 12.2] });
    const comparison = compareLatencyAnchor([anchor], [base], [candidate]);

    assert.equal(comparison.passed, true);
    assert.equal(comparison.comparison, "anchor");
    assert.match(renderLatencyAnchorReport(comparison), /Known-good/);
    assert.doesNotMatch(renderLatencyAnchorReport(comparison), /Pre-PR-95/);
});

test("latency anchor blocks drift against either comparator", () => {
    const common = { unit: "ms/op" };
    const anchorBlocked = compareLatencyAnchor(
        [jmhResult({ ...common, score: 10, confidence: [9.8, 10.2] })],
        [jmhResult({ ...common, score: 15, confidence: [14.8, 15.2] })],
        [jmhResult({ ...common, score: 16, confidence: [15.8, 16.2] })]
    );
    const baseBlocked = compareLatencyAnchor(
        [jmhResult({ ...common, score: 9, confidence: [8.8, 9.2] })],
        [jmhResult({ ...common, score: 10, confidence: [9.8, 10.2] })],
        [jmhResult({ ...common, score: 12, confidence: [11.8, 12.2] })]
    );

    assert.equal(anchorBlocked.passed, false);
    assert.equal(anchorBlocked.rows[0].anchorBlocked, true);
    assert.equal(baseBlocked.passed, false);
    assert.equal(baseBlocked.rows[0].blocked, true);
});

test("latency anchor keeps the synthetic half-millisecond noise floor", () => {
    const benchmark = "io.johnsonlee.graphite.webgraph.WrappedDiscoveryLatencyBenchmark.wrappedCaseInsensitiveDiscovery";
    const common = { benchmark, unit: "ms/op", params: { graphCount: "1" } };
    const comparison = compareLatencyAnchor(
        [jmhResult({ ...common, score: 0.5, confidence: [0.48, 0.52] })],
        [jmhResult({ ...common, score: 0.6, confidence: [0.58, 0.62] })],
        [jmhResult({ ...common, score: 0.9, confidence: [0.88, 0.92] })]
    );

    assert.equal(comparison.passed, true);
    assert.equal(comparison.rows[0].anchorBelowAbsoluteNoiseFloor, true);
    assert.equal(comparison.rows[0].baseBelowAbsoluteNoiseFloor, true);
});

test("latency anchor confirmation evaluates only initially blocked keys", () => {
    const first = "example.First.query";
    const second = "example.Second.query";
    const revision = (firstScore, secondScore) => [
        jmhResult({ benchmark: first, score: firstScore, confidence: [firstScore - 0.1, firstScore + 0.1] }),
        jmhResult({ benchmark: second, score: secondScore, confidence: [secondScore - 0.1, secondScore + 0.1] })
    ];
    const initial = compareLatencyAnchor(revision(10, 10), revision(10, 10), revision(20, 10));
    const retry = compareLatencyAnchor(revision(10, 10), revision(10, 10), revision(10, 20));
    const confirmed = confirmLatencyAnchor(initial, retry);

    assert.equal(initial.passed, false);
    assert.equal(retry.passed, false);
    assert.equal(confirmed.passed, true);
    assert.equal(confirmed.rows.find((row) => row.key === first).blocked, false);
    assert.equal(confirmed.rows.find((row) => row.key === second).blocked, false);
});

test("latency anchor fails closed for a missing expected key", () => {
    const key = LATENCY_EXPECTED_BENCHMARK_KEYS[0];
    const benchmark = key.slice(0, key.indexOf("["));
    const result = jmhResult({
        benchmark,
        score: 1,
        confidence: [0.9, 1.1],
        unit: "ms/op",
        params: { graphCount: "1" }
    });
    const comparison = compareLatencyAnchor(
        [result],
        [result],
        [result],
        15,
        50,
        [key, LATENCY_EXPECTED_BENCHMARK_KEYS[1]]
    );

    assert.equal(comparison.passed, false);
    assert.match(comparison.errors.join("\n"), /missing expected latency benchmark/);
});

test("latency anchor fails closed on duplicate keys in every revision", () => {
    const result = jmhResult({ score: 1, confidence: [0.9, 1.1], unit: "ms/op" });
    for (const duplicateRevision of ["anchor", "base", "candidate"]) {
        const revisions = {
            anchor: [result],
            base: [result],
            candidate: [result]
        };
        revisions[duplicateRevision] = [result, result];
        const comparison = compareLatencyAnchor(
            revisions.anchor,
            revisions.base,
            revisions.candidate
        );

        assert.equal(comparison.passed, false, duplicateRevision);
        assert.match(comparison.errors.join("\n"), /duplicate benchmark/, duplicateRevision);
    }
});

test("latency anchor rejects zero scores in every revision", () => {
    const valid = jmhResult({ score: 1, confidence: [0.9, 1.1], unit: "ms/op" });
    const zero = jmhResult({ score: 0, confidence: [0, 0], unit: "ms/op" });
    for (const zeroRevision of ["anchor", "base", "candidate"]) {
        const revisions = { anchor: [valid], base: [valid], candidate: [valid] };
        revisions[zeroRevision] = [zero];
        const comparison = compareLatencyAnchor(
            revisions.anchor,
            revisions.base,
            revisions.candidate
        );

        assert.equal(comparison.passed, false, zeroRevision);
        assert.match(comparison.errors.join("\n"), /latency score must be finite and positive/, zeroRevision);
    }
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

test("latency shard aggregation rejects mixed anchor and historical policies", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "benchmark-latency-policy-"));
    try {
        LATENCY_EXPECTED_SHARDS.forEach((shard, index) => {
            const rows = LATENCY_EXPECTED_BENCHMARK_KEYS
                .filter((_, keyIndex) => keyIndex % LATENCY_EXPECTED_SHARDS.length === index)
                .map((key) => ({ key, blocked: false }));
            fs.writeFileSync(
                path.join(directory, `latency-status-${shard}.json`),
                JSON.stringify({
                    comparison: index === 0 ? "baseline" : "anchor",
                    passed: true,
                    errors: [],
                    rows
                })
            );
        });

        const comparison = combineLatencyShards(directory);
        assert.equal(comparison.passed, false);
        assert.match(comparison.errors.join("\n"), /mixed comparison policies/);
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

const expectedLargeCorpora = ["tika", "hive", "kotlin-compiler"];

function corpusLine(corpus, overrides = {}) {
    const measurement = {
        nodes: 100,
        sourceEdges: 200,
        persistedEdges: 190,
        methods: 80,
        callSites: 60,
        persistedBytes: 100_000_000,
        buildMs: 10_000,
        saveMs: 2_000,
        mappedLoadSamples: 5,
        mappedLoadMinMs: 180,
        mappedLoadMs: 200,
        mappedLoadMaxMs: 220,
        queryMs: 1_000,
        pipelineMs: 13_200,
        peakHeapBytes: 2_000 * 1024 * 1024,
        ...overrides
    };
    return [
        "LARGE_CORPUS_BASELINE",
        corpus,
        ...Object.entries(measurement).map(([name, value]) => `${name}=${value}`)
    ].join("\t");
}

function corpusLog(overrides = {}) {
    return expectedLargeCorpora
        .map((corpus) => corpusLine(corpus, overrides[corpus]))
        .join("\n");
}

const baseCorpusLog = corpusLog();

test("large-corpus parser accepts Gradle-prefixed output", () => {
    const parsed = parseLargeCorpusLog(`runner prefix ${corpusLine("hive")}\n`);

    assert.deepEqual(parsed.errors, []);
    assert.equal(parsed.results.get("hive").buildMs, 10_000);
    assert.equal(parsed.results.get("hive").nodes, 100);
});

test("large-corpus comparison blocks a material pipeline regression", () => {
    const candidate = corpusLog({ hive: { buildMs: 13_000, pipelineMs: 16_200 } });
    const comparison = compareLargeCorpus(baseCorpusLog, candidate);

    assert.equal(comparison.passed, false);
    assert.equal(comparison.rows.find((row) => row.corpus === "hive" && row.metric === "pipeline").blocked, true);
    assert.match(renderLargeCorpusReport(comparison), /\*\*FAIL\*\*/);
});

test("large-corpus comparison ignores mapped-load changes at the absolute noise floor", () => {
    const candidate = corpusLog({
        hive: { mappedLoadMinMs: 210, mappedLoadMs: 250, mappedLoadMaxMs: 270, pipelineMs: 13_250 }
    });
    const comparison = compareLargeCorpus(baseCorpusLog, candidate);

    assert.equal(comparison.passed, true);
    assert.equal(comparison.rows.find((row) => row.corpus === "hive" && row.metric === "mapped load").blocked, false);
});

test("large-corpus comparison blocks a robust mapped-load median above the noise floor", () => {
    const candidate = corpusLog({
        hive: { mappedLoadMinMs: 240, mappedLoadMs: 280, mappedLoadMaxMs: 320, pipelineMs: 13_280 }
    });
    const comparison = compareLargeCorpus(baseCorpusLog, candidate);

    assert.equal(comparison.passed, false);
    assert.equal(comparison.rows.find((row) => row.corpus === "hive" && row.metric === "mapped load").blocked, true);
    assert.match(renderLargeCorpusReport(comparison), /median of five loads/);
    assert.match(renderLargeCorpusReport(comparison), /30% \+ 50 ms/);
});

test("large-corpus comparison requires the five-sample mapped-load protocol", () => {
    const wrongCount = corpusLog({ hive: { mappedLoadSamples: 3 } });
    const invalidDistribution = corpusLog({ hive: { mappedLoadMinMs: 220, mappedLoadMaxMs: 180 } });

    assert.equal(compareLargeCorpus(baseCorpusLog, wrongCount).passed, false);
    assert.match(compareLargeCorpus(baseCorpusLog, wrongCount).errors.join("\n"), /mappedLoadSamples must equal 5/);
    assert.equal(compareLargeCorpus(baseCorpusLog, invalidDistribution).passed, false);
    assert.match(
        compareLargeCorpus(baseCorpusLog, invalidDistribution).errors.join("\n"),
        /invalid mapped-load sample distribution/
    );
});

test("large-corpus comparison reports sampled heap without blocking on GC noise", () => {
    const candidate = corpusLog({ hive: { peakHeapBytes: 3_500 * 1024 * 1024 } });
    const comparison = compareLargeCorpus(baseCorpusLog, candidate);
    const heap = comparison.rows.find((row) => row.corpus === "hive" && row.metric === "peak heap");

    assert.equal(comparison.passed, true);
    assert.equal(heap.advisory, true);
    assert.equal(heap.blocked, false);
    assert.match(renderLargeCorpusReport(comparison), /4 GiB cap \| \*\*INFO\*\*/);
});

test("large-corpus reverse-order confirmation rejects a one-round false positive", () => {
    const initialCandidate = corpusLog({ hive: { saveMs: 3_000, pipelineMs: 14_200 } });
    const initial = compareLargeCorpus(baseCorpusLog, initialCandidate);
    const retryCandidate = corpusLog({ hive: { saveMs: 2_100, pipelineMs: 13_300 } });
    const retry = compareLargeCorpus(baseCorpusLog, retryCandidate);
    const confirmed = confirmLargeCorpus(initial, retry);

    assert.equal(initial.passed, false);
    assert.equal(confirmed.passed, true);
    assert.equal(confirmed.rows.find((row) => row.corpus === "hive" && row.metric === "save").blocked, false);
    assert.match(renderLargeCorpusReport(confirmed), /\*\*NOISE\*\*/);
});

test("large-corpus reverse-order confirmation blocks a repeated regression", () => {
    const candidate = corpusLog({ hive: { saveMs: 3_000, pipelineMs: 14_200 } });
    const initial = compareLargeCorpus(baseCorpusLog, candidate);
    const retry = compareLargeCorpus(baseCorpusLog, candidate);
    const confirmed = confirmLargeCorpus(initial, retry);

    assert.equal(confirmed.passed, false);
    assert.equal(confirmed.rows.find((row) => row.corpus === "hive" && row.metric === "save").blocked, true);
    assert.match(renderLargeCorpusReport(confirmed), /\*\*FAIL\*\*/);
});

test("large-corpus comparison requires the exact corpus set", () => {
    const missing = expectedLargeCorpora.slice(0, 2).map((corpus) => corpusLine(corpus)).join("\n");
    const unexpected = `${baseCorpusLog}\n${corpusLine("android")}`;

    assert.equal(compareLargeCorpus(missing, missing).passed, false);
    assert.match(compareLargeCorpus(missing, missing).errors.join("\n"), /missing expected large corpus kotlin-compiler/);
    assert.equal(compareLargeCorpus(unexpected, unexpected).passed, false);
    assert.match(compareLargeCorpus(unexpected, unexpected).errors.join("\n"), /unexpected large corpus android/);
});

test("large-corpus comparison rejects duplicate corpus markers without overwriting", () => {
    const duplicate = `${baseCorpusLog}\n${corpusLine("hive", { nodes: 1 })}`;
    const comparison = compareLargeCorpus(duplicate, baseCorpusLog);

    assert.equal(comparison.passed, false);
    assert.match(comparison.errors.join("\n"), /base: duplicate large-corpus marker hive/);
});

test("large-corpus comparison rejects graph-shape drift even when the candidate is faster", () => {
    for (const field of ["nodes", "sourceEdges", "persistedEdges", "methods", "callSites"]) {
        const candidate = corpusLog({
            hive: {
                [field]: 1,
                buildMs: 1_000,
                saveMs: 200,
                mappedLoadMs: 20,
                queryMs: 100,
                pipelineMs: 1_320
            }
        });
        const comparison = compareLargeCorpus(baseCorpusLog, candidate);
        assert.equal(comparison.passed, false, field);
        assert.match(comparison.errors.join("\n"), new RegExp(`hive/${field}: graph shape changed`));
    }
});

test("large-corpus confirmation cannot clear a graph-shape failure", () => {
    const drifted = corpusLog({ hive: { nodes: 99 } });
    const initial = compareLargeCorpus(baseCorpusLog, drifted);
    const cleanRetry = compareLargeCorpus(baseCorpusLog, baseCorpusLog);
    const confirmed = confirmLargeCorpus(initial, cleanRetry);

    assert.equal(confirmed.passed, false);
    assert.match(confirmed.errors.join("\n"), /hive\/nodes: graph shape changed/);
});

test("large-corpus comparison validates required measurements", () => {
    const zeroTiming = corpusLog({ hive: { queryMs: 0, pipelineMs: 12_200 } });
    const missingShape = baseCorpusLog.replace("\tsourceEdges=200", "");

    assert.equal(compareLargeCorpus(baseCorpusLog, zeroTiming).passed, false);
    assert.match(compareLargeCorpus(baseCorpusLog, zeroTiming).errors.join("\n"), /hive\/query: invalid measurement/);
    assert.equal(compareLargeCorpus(missingShape, baseCorpusLog).passed, false);
    assert.match(compareLargeCorpus(missingShape, baseCorpusLog).errors.join("\n"), /invalid graph-shape measurement/);
});

test("large-corpus comparison tolerates only small persisted-size noise", () => {
    const withinTolerance = corpusLog({ hive: { persistedBytes: 100_004_096 } });
    const outsideTolerance = corpusLog({ hive: { persistedBytes: 100_004_097 } });

    assert.equal(compareLargeCorpus(baseCorpusLog, withinTolerance).passed, true);
    const comparison = compareLargeCorpus(baseCorpusLog, outsideTolerance);
    assert.equal(comparison.passed, false);
    assert.match(comparison.errors.join("\n"), /exceeding the 4096-byte tolerance/);
});

test("coverage taxonomy assigns every blocking component exactly once", () => {
    const classified = BENCHMARK_COVERAGE_DOMAINS.flatMap((domain) => domain.components);
    const manifest = BENCHMARK_COMPONENTS.map((component) => component.name);

    assert.equal(new Set(classified).size, classified.length);
    assert.deepEqual([...classified].sort(), [...manifest].sort());
    assert.deepEqual(BENCHMARK_COVERAGE_DOMAINS.map((domain) => domain.name), [
        "Semantic correctness",
        "Latency regression",
        "Throughput and capacity",
        "Memory and resources",
        "Scalability",
        "Build and persistence lifecycle"
    ]);
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
            ["method-compatibility-report.md", "method-compatibility-status.json", "### Method migration report"],
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
        assert.equal(aggregate.baseSha, "a".repeat(40));
        assert.equal(aggregate.candidateSha, "b".repeat(40));
        assert.equal(aggregate.runner, "test-runner");
        assert.equal(aggregate.runUrl, "https://example.invalid/run");
        assert.match(aggregate.body, /PASS — 9\/9 component reports passed/);
        assert.match(aggregate.body, /### Coverage summary/);
        assert.match(aggregate.body, /#### Semantic correctness/);
        assert.match(aggregate.body, /#### Latency regression/);
        assert.match(aggregate.body, /#### Throughput and capacity/);
        assert.match(aggregate.body, /#### Memory and resources/);
        assert.match(aggregate.body, /#### Scalability/);
        assert.match(aggregate.body, /#### Build and persistence lifecycle/);
        assert.match(aggregate.body, /##### Method migration report/);
        assert.match(aggregate.body, /Not covered by this suite \(non-blocking for this run\)/);
        assert.doesNotMatch(aggregate.body, /Missing required families/);
        assert.match(aggregate.body, /collection report/);
        assert.match(aggregate.body, /explorer report/);
        assert.match(aggregate.body, /Method migration report/);
        assert.match(aggregate.body, /capacity report/);
        assert.match(aggregate.body, /budgeted report/);

        fs.writeFileSync(path.join(directory, "method-status.json"), JSON.stringify({ passed: false }));
        const failed = aggregateReports(directory, {
            baseSha: "a".repeat(40),
            candidateSha: "b".repeat(40),
            runner: "test-runner",
            runUrl: "https://example.invalid/run"
        });
        assert.equal(failed.passed, false);
        assert.match(failed.body, /FAIL — 8\/9 component reports passed/);
        assert.match(failed.body, /`method-level` \| \*\*FAIL\*\*/);
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

test("taxonomy rollout publishes an exact Pages-compatible report and status pair", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "benchmark-gate-rollout-"));
    try {
        for (const component of BENCHMARK_COMPONENTS) {
            fs.writeFileSync(path.join(directory, component.report), `${component.name} report\n`);
            fs.writeFileSync(path.join(directory, component.status), JSON.stringify({ passed: true }));
        }
        const baseSha = "a".repeat(40);
        const candidateSha = "b".repeat(40);
        const runUrl = "https://example.invalid/transition-run";
        const rendered = aggregateReports(directory, {
            baseSha,
            candidateSha,
            runner: "Linux-X64",
            runUrl
        });
        const authoritative = {
            passed: true,
            errors: [],
            body: "## Benchmark Regression Gate\n\n**PASS**\n",
            baseSha,
            candidateSha,
            runner: "Linux-X64",
            runUrl
        };
        assert.notEqual(authoritative.body, rendered.body);
        for (const key of ["passed", "baseSha", "candidateSha", "runner", "runUrl"]) {
            assert.equal(rendered[key], authoritative[key]);
        }
        assert.deepEqual(rendered.errors, authoritative.errors);

        const integrationSha = "c".repeat(40);
        const integrationTreeSha = "d".repeat(40);
        const validation = validatePairedEvidence({
            reportMarkdown: rendered.body,
            status: rendered,
            provenance: {
                schemaVersion: 1,
                integrationSha,
                integrationParentSha: baseSha,
                integrationTreeSha,
                baseSha,
                candidateSha,
                candidateTreeSha: integrationTreeSha,
                sourcePr: 106,
                sourceRunId: 789,
                sourceRunUrl: runUrl
            },
            commitSha: integrationSha
        });
        assert.equal(validation.available, true);
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

test("artifact staging keeps successful jobs and selects only the latest producer attempt", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "benchmark-artifacts-"));
    const output = path.join(directory, "staged");
    try {
        const method = path.join(directory, "benchmark-method-101-1");
        const resourceFirst = path.join(directory, "benchmark-resource-101-1");
        const resourceRetry = path.join(directory, "benchmark-resource-101-2");
        fs.mkdirSync(method);
        fs.mkdirSync(resourceFirst);
        fs.mkdirSync(resourceRetry);
        fs.writeFileSync(path.join(method, "method-status.json"), JSON.stringify({ passed: true }));
        fs.writeFileSync(path.join(resourceFirst, "resource-status.json"), JSON.stringify({ passed: false }));
        fs.writeFileSync(path.join(resourceRetry, "resource-status.json"), JSON.stringify({ passed: true }));

        const staged = stageLatestArtifacts(directory, output);

        assert.deepEqual(staged, [
            "benchmark-method-101-1",
            "benchmark-resource-101-2"
        ]);
        assert.deepEqual(JSON.parse(fs.readFileSync(path.join(output, "method-status.json"))), { passed: true });
        assert.deepEqual(JSON.parse(fs.readFileSync(path.join(output, "resource-status.json"))), { passed: true });
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

test("artifact staging does not mix stale files into a partial retry", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "benchmark-artifacts-partial-retry-"));
    const output = path.join(directory, "staged");
    try {
        const resourceFirst = path.join(directory, "benchmark-resource-101-1");
        const resourceRetry = path.join(directory, "benchmark-resource-101-2");
        fs.mkdirSync(resourceFirst);
        fs.mkdirSync(resourceRetry);
        fs.writeFileSync(path.join(resourceFirst, "resource-status.json"), JSON.stringify({ passed: true }));
        fs.writeFileSync(path.join(resourceFirst, "candidate.json"), JSON.stringify({ attempt: 1 }));
        fs.writeFileSync(path.join(resourceRetry, "candidate.json"), JSON.stringify({ attempt: 2 }));

        const staged = stageLatestArtifacts(directory, output);

        assert.deepEqual(staged, ["benchmark-resource-101-2"]);
        assert.equal(fs.existsSync(path.join(output, "resource-status.json")), false);
        assert.deepEqual(JSON.parse(fs.readFileSync(path.join(output, "candidate.json"))), { attempt: 2 });
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

test("workflow component artifacts include the run attempt required by staging", () => {
    const workflow = fs.readFileSync(
        new URL("../workflows/benchmark.yml", import.meta.url),
        "utf8"
    );
    const pullRequest = "${{ github.event.pull_request.number }}";
    const runAttempt = "${{ github.run_attempt }}";
    const producers = [
        "benchmark-method",
        "benchmark-explorer",
        "benchmark-method-compatibility",
        "benchmark-cypher-capacity",
        "benchmark-budgeted-collection",
        "benchmark-budgeted-string",
        "benchmark-large-corpus",
        "benchmark-latency",
        "benchmark-latency-resources"
    ];

    for (const producer of producers) {
        assert.ok(
            workflow.includes(`name: ${producer}-${pullRequest}-${runAttempt}`),
            `${producer} must identify its run attempt`
        );
    }
    assert.ok(workflow.includes(`pattern: benchmark-*-${pullRequest}-*`));
});

test("pull-request workflow uses shared JMH artifacts, method shards, and the known-good anchor", () => {
    const workflow = fs.readFileSync(new URL("../workflows/benchmark.yml", import.meta.url), "utf8");

    assert.doesNotMatch(workflow, /44b57562f2b3d0c88882a9002bdc488e05e5d7a7|PRE_PR_95_BASELINE_SHA/);
    assert.match(workflow, /WRAPPED_QUERY_REFERENCE_SHA: 0b421f8a25800193fd86a7e4aebf72aa9e9d6cc6/);
    assert.match(workflow, /^  build-explore-jmh:/m);
    assert.match(workflow, /^  build-wrapped-query-jmh:/m);
    const webgraphBuild = fs.readFileSync(
        new URL("../../graphite-webgraph/build.gradle.kts", import.meta.url),
        "utf8"
    );
    assert.match(webgraphBuild, /includeTests\.set\(false\)/);
    assert.match(webgraphBuild, /val verifyJmhJarExcludesTests by tasks\.registering/);
    assert.match(webgraphBuild, /filter\(testEntries::contains\)/);
    for (const module of ["cypher", "explore", "sootup"]) {
        const build = fs.readFileSync(
            new URL(`../../graphite-${module}/build.gradle.kts`, import.meta.url),
            "utf8"
        );
        assert.match(build, /includeTests\.set\(false\)/, `${module} JMH must exclude tests`);
    }
    const transitionHarness = fs.readFileSync(
        new URL(
            "../../graphite-webgraph/src/test/kotlin/io/johnsonlee/graphite/webgraph/" +
                "LargeCorpusPerformanceGateTest.kt",
            import.meta.url
        )
    );
    const comparator = fs.readFileSync(new URL("./benchmark-gate.mjs", import.meta.url));
    const isolationInit = fs.readFileSync(
        new URL("./benchmark-jmh-isolation.init.gradle", import.meta.url)
    );
    const isolationVerifier = fs.readFileSync(
        new URL("./verify-jmh-jar-isolation.sh", import.meta.url)
    );
    const sha256 = (contents) => crypto.createHash("sha256").update(contents).digest("hex");
    assert.match(
        workflow,
        new RegExp(`JMH_ISOLATION_INIT_SHA256: ${sha256(isolationInit)}`)
    );
    assert.match(
        workflow,
        new RegExp(`JMH_ISOLATION_VERIFIER_SHA256: ${sha256(isolationVerifier)}`)
    );
    assert.match(workflow, /Checkout candidate build controls/);
    assert.match(workflow, /Select trusted JMH isolation controls/);
    assert.match(workflow, /benchmark-jmh-isolation\.init\.gradle/);
    assert.match(workflow, /verify-jmh-jar-isolation\.sh/);
    assert.match(workflow, /:webgraph:testClasses :webgraph:jmhJar/);
    assert.match(workflow, /:explore:testClasses :explore:jmhJar/);
    assert.match(workflow, /:cypher:testClasses :cypher:jmhJar/);
    assert.match(
        workflow,
        new RegExp(`LARGE_CORPUS_TRANSITION_HARNESS_SHA256: ${sha256(transitionHarness)}`)
    );
    assert.match(
        workflow,
        new RegExp(`LARGE_CORPUS_TRANSITION_COMPARATOR_SHA256: ${sha256(comparator)}`)
    );
    assert.match(workflow, /LARGE_CORPUS_LEGACY_HARNESS_SHA256: 9c439a7a0b625442/);
    assert.match(
        workflow,
        new RegExp(`BENCHMARK_REPORT_TRANSITION_SHA256: ${sha256(comparator)}`)
    );
    const aggregateJob = workflow.slice(
        workflow.indexOf("  benchmark-regression-gate:"),
        workflow.indexOf("  benchmark-comment:")
    );
    assert.match(aggregateJob, /Checkout candidate reporting code for taxonomy rollout/);
    assert.match(workflow, /benchmark-authoritative-status\.json/);
    assert.match(workflow, /benchmark-render-status\.json/);
    assert.match(workflow, /grep -q '\^### Coverage summary\$'/);
    assert.match(workflow, /install -m 0644 benchmark-results\/benchmark-render-status\.json/);
    assert.match(aggregateJob, /for\(const k of \['passed','baseSha','candidateSha','runner','runUrl'\]\)/);
    const enforcement = aggregateJob.slice(aggregateJob.indexOf("    - name: Enforce benchmark gate"));
    assert.match(enforcement, /benchmark-authoritative-status\.json/);
    assert.doesNotMatch(enforcement, /require\('\.\/benchmark-results\/benchmark-status\.json'\)/);
    assert.match(workflow, /mode=pinned-transition/);
    assert.match(workflow, /needs: \[candidate-gate-tests\]/);
    assert.match(workflow, /compare-latency-anchor/);
    assert.match(workflow, /confirm-latency-anchor/);
    assert.match(workflow, /Checkout candidate reporting code for anchor rollout/);
    const anchorEnforcementStart = workflow.indexOf(
        "    - name: Enforce known-good anchor and current-base regression gate",
    );
    const anchorEnforcementEnd = workflow.indexOf(
        "    - name: Upload wrapped-query latency results",
        anchorEnforcementStart,
    );
    assert.notEqual(anchorEnforcementStart, -1, "anchor enforcement step must exist");
    assert.notEqual(anchorEnforcementEnd, -1, "wrapped-query latency upload step must exist");
    assert.ok(
        anchorEnforcementEnd > anchorEnforcementStart,
        "wrapped-query latency upload must follow anchor enforcement",
    );
    const anchorEnforcement = workflow.slice(
        anchorEnforcementStart,
        anchorEnforcementEnd,
    );
    assert.match(
        anchorEnforcement,
        /CANDIDATE_GATE_TEST_JOB: \$\{\{ needs\.candidate-gate-tests\.result \}\}/,
    );
    assert.equal((workflow.match(/^        - graph_count: (4|17|36)$/gm) ?? []).length, 12);
    assert.equal((workflow.match(/^          group: (position|string|scan|aggregate)$/gm) ?? []).length, 12);
    assert.match(workflow, /length == 33 and/);
    for (const revision of ["base", "candidate"]) {
        assert.match(workflow, new RegExp(`jmh-explore-${revision}`));
    }
    for (const revision of ["reference", "base", "candidate"]) {
        assert.match(workflow, new RegExp(`jmh-wrapped-query-${revision}`));
    }
    const wrappedQueryBuildStart = workflow.indexOf("  build-wrapped-query-jmh:");
    const wrappedQueryBuildEnd = workflow.indexOf("  method-level:", wrappedQueryBuildStart);
    assert.notEqual(wrappedQueryBuildStart, -1, "shared wrapped-query JMH build job must exist");
    assert.notEqual(wrappedQueryBuildEnd, -1, "method-level job boundary must exist");
    const wrappedQueryBuild = workflow.slice(wrappedQueryBuildStart, wrappedQueryBuildEnd);
    const harnessOverlayStart = wrappedQueryBuild.indexOf(
        "    - name: Install base-owned wrapped-query harnesses",
    );
    const harnessOverlayEnd = wrappedQueryBuild.indexOf(
        "    - name: Build comparable wrapped-query JMH JAR",
        harnessOverlayStart,
    );
    assert.notEqual(harnessOverlayStart, -1, "base-owned wrapped-query overlay step must exist");
    assert.notEqual(harnessOverlayEnd, -1, "wrapped-query build step boundary must exist");
    const harnessOverlay = wrappedQueryBuild.slice(harnessOverlayStart, harnessOverlayEnd);
    const overlaidHarnesses = [...harnessOverlay.matchAll(/^          ([A-Za-z0-9]+\.kt)(?= |;)/gm)]
        .map((match) => match[1]);
    assert.deepEqual(overlaidHarnesses, [
        "BenchmarkCorpus.kt",
        "AllFixtureBenchmarkGraphPreparation.kt",
        "WrappedDiscoveryLatencyBenchmark.kt",
        "AllFixtureWrappedDiscoveryLatencyBenchmark.kt",
        "WrappedDiscoveryResourceBenchmark.kt",
    ]);
    assert.doesNotMatch(workflow, /name: jmh-(?:explore|wrapped-query)-[^\n]*run_attempt/);
    const fixturePreparation = workflow.slice(
        workflow.indexOf("  prepare-latency-fixtures:"),
        workflow.indexOf("  wrapped-query-latency-shard:")
    );
    assert.match(fixturePreparation, /Download candidate wrapped-query JMH JAR/);
    assert.doesNotMatch(fixturePreparation, /:webgraph:jmhJar/);
});

test("historical known-bad latency proof runs only in the scheduled workflow", () => {
    const workflow = fs.readFileSync(
        new URL("../workflows/benchmark-historical-latency.yml", import.meta.url),
        "utf8"
    );

    assert.match(workflow, /schedule:/);
    assert.match(workflow, /workflow_dispatch:/);
    assert.doesNotMatch(workflow, /pull_request:/);
    assert.match(workflow, /PRE_PR_95_BASELINE_SHA: 44b57562f2b3d0c88882a9002bdc488e05e5d7a7/);
    assert.match(workflow, /RealThirtySixGraphWrappedDiscoveryLatencyBenchmark/);
    assert.match(workflow, /benchmark-jmh-isolation\.init\.gradle/);
    assert.match(workflow, /verify-jmh-jar-isolation\.sh/);
    assert.match(workflow, /:webgraph:testClasses :webgraph:jmhJar/);
    assert.match(workflow, /Verify historical and current real-fixture query results/);
    assert.match(workflow, /current\/\.github\/scripts\/benchmark-gate\.mjs compare-latency-baseline/);
    assert.match(workflow, /current\/\.github\/scripts\/benchmark-gate\.mjs confirm-latency-baseline/);
    assert.doesNotMatch(workflow, /name: historical-jmh-[^\n]*run_attempt/);
    assert.doesNotMatch(workflow, /name: historical-latency-[^\n]*run_attempt/);
    assert.match(workflow, /pattern: historical-latency-\*-\$\{\{ github\.run_id \}\}/);
    const cacheHashInputs = workflow.match(/hashFiles\(([^\n]+)\)/g) ?? [];
    assert.ok(cacheHashInputs.length >= 2);
    for (const hashInput of cacheHashInputs) assert.match(hashInput, /'current\//);
});

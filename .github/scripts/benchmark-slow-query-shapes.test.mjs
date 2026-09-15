import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { QUERIES, measurements, resultMarkers, compare, confirm, addComponent, COMPONENT } from "./benchmark-slow-query-shapes.mjs";

const fixture = "/real/android";
const hash = "a".repeat(64);
// `spread` is the half-width of JMH's 99.9% confidence interval around the score.
function entries(ms = 100, spread = 0) {
    return ["COLD", "WARM"].flatMap(cacheState => QUERIES.map(queryName => ({
        benchmark: "io.johnsonlee.graphite.webgraph.SlowQueryShapesBenchmark.execute", mode: "ss",
        threads: 1, forks: 5, warmupIterations: 0, measurementIterations: 1,
        params: { cacheState, queryName, corpus: "android" },
        jvmArgs: ["-Xmx8g", "-XX:ActiveProcessorCount=4", `-Dandroid.graph.path=${fixture}`],
        primaryMetric: { score: ms, scoreUnit: "ms/op", rawData: [[ms], [ms], [ms], [ms], [ms]], scoreConfidence: [ms - spread, ms + spread] }
    })));
}
function markers() {
    return entries().flatMap((entry, index) => Array.from({ length: 5 }, (_, fork) =>
        `SLOW_QUERY_SHAPE_FIXTURE\tprotocol=private-copy-no-callsite-index-v2\tcorpus=android\tsource=${fixture}\tsnapshot=/tmp/graphite-slow-query-shapes-${index}-${fork}/android\tindexAbsent=true\n` +
        `SLOW_QUERY_SHAPE_RESULT\tandroid\t${entry.params.cacheState}\t${entry.params.queryName}\trows=${entry.params.queryName.endsWith("Hit") ? 1 : 0}\tsha256=${hash}\n`
    )).join("");
}
function comparison(ms = 100, policy = "unmodified-base", spread = 0) {
    const results = resultMarkers(markers(), QUERIES, fixture, () => false);
    return compare(measurements(entries(100, spread), QUERIES, fixture), measurements(entries(ms, spread), QUERIES, fixture), results, results, policy);
}

test("all five families require 24 cold/warm keys, with nonempty hit oracles", () => {
    assert.equal(measurements(entries(), QUERIES, fixture).size, 24);
    assert.equal(resultMarkers(markers(), QUERIES, fixture, () => false).size, 24);
    assert.equal(comparison().passed, true);
    assert.throws(() => measurements(entries().filter(entry => !entry.params.queryName.startsWith("qualifiedId")), QUERIES, fixture), /Missing/);
    assert.throws(() => resultMarkers(markers().replace("rows=1", "rows=0"), QUERIES, fixture, () => false), /hit\/miss/);
});

test("JMH mode, scope, effective heap, raw samples and exact keys fail closed", () => {
    for (const mutate of [
        rows => { rows[0].mode = "avgt"; }, rows => { rows[0].forks = 1; },
        rows => { rows[0].primaryMetric.score = NaN; }, rows => { rows[0].primaryMetric.scoreUnit = "us/op"; },
        rows => { rows[0].primaryMetric.rawData.pop(); }, rows => { rows[0].primaryMetric.rawData[0][0] = 200; },
        rows => { delete rows[0].primaryMetric.scoreConfidence; }, rows => { rows[0].primaryMetric.scoreConfidence = [NaN, NaN]; },
        rows => { rows[0].primaryMetric.scoreConfidence = [101, 102]; }, rows => { rows[0].primaryMetric.scoreConfidence = [90]; },
        rows => { rows[0].jvmArgs[0] = "-Xmx16g"; }, rows => { rows[0].params.corpus = "synthetic"; },
        rows => { rows[0].params.queryName = "unexpectedHit"; }, rows => { rows.push(rows[0]); }
    ]) {
        const rows = entries(); mutate(rows);
        assert.throws(() => measurements(rows, QUERIES, fixture));
    }
});

test("ordered digests, fork counts and private fixture cleanup are mandatory", () => {
    assert.throws(() => resultMarkers(markers().replace(hash, "b".repeat(64)), QUERIES, fixture, () => false), /between forks/);
    assert.throws(() => resultMarkers(markers() + markers(), QUERIES, fixture, () => false), /duplicate result fork/);
    assert.throws(() => resultMarkers(markers(), QUERIES, fixture, () => true), /not removed/);
    assert.throws(() => resultMarkers(markers().replace("source=/real/android", "source=/synthetic/android"), QUERIES, fixture, () => false), /identity/);
    assert.throws(() => resultMarkers(markers().replace("indexAbsent=true", "indexAbsent=false"), QUERIES, fixture, () => false), /fixture count/);
    const a = resultMarkers(markers(), QUERIES, fixture, () => false);
    const b = new Map(a); b.set("COLD/qualifiedIdHit", { ...b.get("COLD/qualifiedIdHit"), sha256: "b".repeat(64) });
    assert.throws(() => compare(measurements(entries(), QUERIES, fixture), measurements(entries(), QUERIES, fixture), a, b, "unmodified-base"), /semantic mismatch/);
});

test("only dynamic rows may use the explicitly named semantic reference", () => {
    const result = comparison(100, "base-plus-subscript-correctness-repair");
    assert.equal(result.rows.filter(row => row.reference === "base-plus-subscript-correctness-repair").length, 4);
    assert.equal(result.rows.find(row => row.key === "COLD/qualifiedIdHit").reference, "unmodified-base");
    assert.throws(() => comparison(100, "silently-patched-base"), /reference policy/);
});

test("a row blocks only when 15 percent slower with separated confidence intervals", () => {
    // Zero spread: any 16% point delta is also a separated interval.
    assert.equal(comparison(116).passed, false);
    assert.equal(comparison(116).rows.every(row => row.confidenceSeparated && row.blocked), true);
    // The same 16% with intervals of ±10 ms around 100 and 116 overlap: reported, not blocked.
    const noisy = comparison(116, "unmodified-base", 10);
    assert.equal(noisy.passed, true);
    assert.equal(noisy.rows.every(row => row.delta > 15 && !row.confidenceSeparated && !row.blocked), true);
    assert.deepEqual(noisy.rows[0].baseConfidence, [90, 110]);
    assert.deepEqual(noisy.rows[0].candidateConfidence, [106, 126]);
    // Separated intervals under the threshold do not block either.
    assert.equal(comparison(110, "unmodified-base", 1).rows.every(row => row.confidenceSeparated && !row.blocked), true);
    // A large regression separates even through the noise that hides a small one.
    assert.equal(comparison(200, "unmodified-base", 10).passed, false);
    assert.equal(comparison().confidencePercent, 99.9);
});

test("15 percent suspect must repeat in reverse order; integrity cannot be cleared", () => {
    assert.equal(comparison(110).passed, true);
    assert.equal(comparison(116).passed, false);
    assert.equal(confirm(comparison(116), comparison(117)).passed, false);
    assert.equal(confirm(comparison(116), comparison(110)).passed, true);
    const invalid = comparison(); invalid.errors = ["missing results"];
    assert.throws(() => confirm(comparison(116), invalid), /Integrity/);
    const changed = comparison(); changed.rows[0].sha256 = "b".repeat(64);
    assert.throws(() => confirm(comparison(116), changed), /Semantic/);
    assert.throws(() => confirm(comparison(116), comparison(116, "base-plus-subscript-correctness-repair")), /policy changed/);
});

test("additive reporting retains every existing component and coverage domain", () => {
    const previous = [{ name: "existing-required", threshold: 15 }];
    const module = { BENCHMARK_COMPONENTS: [...previous], BENCHMARK_COVERAGE_DOMAINS: [
        { name: "Latency regression", components: ["existing-required"] },
        { name: "Memory and resources", components: ["untouched-memory"] }
    ] };
    addComponent(module);
    assert.deepEqual(module.BENCHMARK_COMPONENTS, [...previous, COMPONENT]);
    assert.deepEqual(module.BENCHMARK_COVERAGE_DOMAINS[0].components, ["existing-required", "slow-query-shapes"]);
    assert.deepEqual(module.BENCHMARK_COVERAGE_DOMAINS[1].components, ["untouched-memory"]);
    assert.throws(() => addComponent(module), /Duplicate/);
});

test("CLI aggregation adds the required report without allowing an existing failure to pass", async () => {
    const original = await import("./benchmark-gate.mjs");
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "slow-shape-gate-control-"));
    const script = fileURLToPath(new URL("./benchmark-slow-query-shapes.mjs", import.meta.url));
    const baseComparator = fileURLToPath(new URL("./benchmark-gate.mjs", import.meta.url));
    try {
        const output = path.join(directory, "aggregate.json");
        for (const component of original.BENCHMARK_COMPONENTS) {
            fs.writeFileSync(path.join(directory, component.report), `### ${component.name}\n`);
            fs.writeFileSync(path.join(directory, component.status), JSON.stringify({ passed: true }));
        }
        const invoke = () => spawnSync(process.execPath, [script, "aggregate", "--base-comparator", baseComparator,
            "--directory", directory, "--base-sha", "a".repeat(40), "--candidate-sha", "b".repeat(40),
            "--runner", "Linux-X64", "--run-url", "https://example.test/run", "--status", output,
            "--report", path.join(directory, "aggregate.md")], { encoding: "utf8" });
        assert.equal(invoke().status, 0);
        assert.equal(JSON.parse(fs.readFileSync(output)).passed, false);
        assert.match(JSON.parse(fs.readFileSync(output)).errors.join(), /slow-query-shapes.*missing/);
        fs.writeFileSync(path.join(directory, COMPONENT.report), "### Five slow query families\n");
        fs.writeFileSync(path.join(directory, COMPONENT.status), JSON.stringify(comparison()));
        assert.equal(invoke().status, 0);
        const passed = JSON.parse(fs.readFileSync(output));
        assert.equal(passed.passed, true);
        assert.match(passed.body, /7\/7 blocking component reports passed; 6\/6 advisory JVM engine reports passed/);
        fs.writeFileSync(path.join(directory, original.BENCHMARK_COMPONENTS[0].status), JSON.stringify({ passed: false }));
        assert.equal(invoke().status, 0);
        assert.equal(JSON.parse(fs.readFileSync(output)).passed, false);
        assert.equal(original.BENCHMARK_COMPONENTS.length, 12);
    } finally { fs.rmSync(directory, { recursive: true }); }
});

test("CLI fingerprints detect changed immutable fixture files", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "slow-shape-fixture-control-"));
    const source = path.join(directory, "source"); fs.mkdirSync(source);
    const script = fileURLToPath(new URL("./benchmark-slow-query-shapes.mjs", import.meta.url));
    const before = path.join(directory, "before.json"), verified = path.join(directory, "verified.json");
    const invoke = args => spawnSync(process.execPath, [script, ...args], { encoding: "utf8" });
    try {
        fs.writeFileSync(path.join(source, "graph.nodedata"), "fixture identity check only");
        assert.equal(invoke(["fingerprint", "--fixture", source, "--output", before]).status, 0);
        assert.equal(invoke(["verify-fixture", "--fixture", source, "--before", before, "--output", verified]).status, 0);
        assert.equal(JSON.parse(fs.readFileSync(verified)).sharedFixtureUnchanged, true);
        fs.writeFileSync(path.join(source, "graph.nodedata"), "changed bytes");
        const failure = invoke(["verify-fixture", "--fixture", source, "--before", before, "--output", verified]);
        assert.equal(failure.status, 1);
        assert.match(failure.stderr, /fixture changed/);
    } finally { fs.rmSync(directory, { recursive: true }); }
});

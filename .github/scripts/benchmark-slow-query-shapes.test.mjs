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
function entries(ms = 100) {
    return ["COLD", "WARM"].flatMap(cacheState => QUERIES.map(queryName => ({
        benchmark: "io.johnsonlee.graphite.webgraph.SlowQueryShapesBenchmark.execute", mode: "ss",
        threads: 1, forks: 3, warmupIterations: 0, measurementIterations: 1,
        params: { cacheState, queryName, corpus: "android" },
        jvmArgs: ["-Xmx8g", "-XX:ActiveProcessorCount=4", `-Dandroid.graph.path=${fixture}`],
        primaryMetric: { score: ms, scoreUnit: "ms/op", rawData: [[ms], [ms], [ms]] }
    })));
}
function markers() {
    return entries().flatMap((entry, index) => Array.from({ length: 3 }, (_, fork) =>
        `SLOW_QUERY_SHAPE_FIXTURE\tprotocol=private-copy-no-callsite-index-v2\tcorpus=android\tsource=${fixture}\tsnapshot=/tmp/graphite-slow-query-shapes-${index}-${fork}/android\tindexAbsent=true\n` +
        `SLOW_QUERY_SHAPE_RESULT\tandroid\t${entry.params.cacheState}\t${entry.params.queryName}\trows=${entry.params.queryName.endsWith("Hit") ? 1 : 0}\tsha256=${hash}\n`
    )).join("");
}
function comparison(ms = 100, policy = "unmodified-base") {
    const results = resultMarkers(markers(), QUERIES, fixture, () => false);
    return compare(measurements(entries(), QUERIES, fixture), measurements(entries(ms), QUERIES, fixture), results, results, policy);
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
        assert.match(passed.body, /12\/12 component reports passed/);
        fs.writeFileSync(path.join(directory, original.BENCHMARK_COMPONENTS[0].status), JSON.stringify({ passed: false }));
        assert.equal(invoke().status, 0);
        assert.equal(JSON.parse(fs.readFileSync(output)).passed, false);
        assert.equal(original.BENCHMARK_COMPONENTS.length, 11);
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

// Mock JMH numbers below test policy, not performance on synthetic data.
function stateComparison(coldMs, warmMs, diagnostic = true) {
    const current = entries().map(entry => {
        const ms = entry.params.cacheState === "COLD" ? coldMs : warmMs;
        return { ...entry, primaryMetric: { ...entry.primaryMetric, score: ms, rawData: [[ms], [ms], [ms]] } };
    });
    const results = resultMarkers(markers(), QUERIES, fixture, () => false);
    return compare(measurements(entries(), QUERIES, fixture), measurements(current, QUERIES, fixture),
        results, results, "unmodified-base", diagnostic);
}

test("cold-only numerical regression is advisory only when explicitly selected; retain every value", async () => {
    const { render } = await import("./benchmark-slow-query-shapes.mjs");
    const result = stateComparison(200, 100);
    assert.equal(result.passed, true); // Driver's successful comparison skips reverse confirmation.
    assert.equal(result.coldDiagnosticsOnly, true);
    assert.equal(result.rows.length, 24);
    assert.equal(result.rows.filter(row => row.diagnostic).length, 12);
    for (const row of result.rows.filter(row => row.diagnostic)) {
        assert.equal(row.delta, 100);
        assert.deepEqual(row.candidateSamples, [200, 200, 200]);
        assert.equal(row.blocked, false);
    }
    assert.equal((render(result).match(/\| COLD DIAGNOSTIC \|/g) ?? []).length, 12);
    assert.equal(stateComparison(200, 100, false).passed, false);
});

test("cold diagnostic policy cannot clear wrong ordered results or missing cold measurements", () => {
    const results = resultMarkers(markers(), QUERIES, fixture, () => false);
    const wrong = new Map(results);
    wrong.set("COLD/valueHit", { ...wrong.get("COLD/valueHit"), sha256: "b".repeat(64) });
    assert.throws(() => compare(measurements(entries(), QUERIES, fixture), measurements(entries(), QUERIES, fixture),
        results, wrong, "unmodified-base", true), /semantic mismatch/);
    const missing = measurements(entries(), QUERIES, fixture); missing.delete("COLD/valueHit");
    assert.throws(() => compare(missing, measurements(entries(), QUERIES, fixture), results, results,
        "unmodified-base", true), /24 cold\/warm/);
    const broken = entries(); broken[0].primaryMetric.rawData.pop();
    assert.throws(() => measurements(broken, QUERIES, fixture), /fork samples/);
});

test("warm suspects still require reverse confirmation; cold policy must match and be authentic", () => {
    const initial = stateComparison(200, 116);
    assert.equal(initial.passed, false);
    const repeated = confirm(initial, stateComparison(300, 117));
    assert.equal(repeated.passed, false);
    assert.equal(repeated.rows.filter(row => row.blocked).length, 12);
    assert.equal(confirm(initial, stateComparison(300, 110)).passed, true);
    assert.throws(() => confirm(initial, stateComparison(200, 116, false)), /Cold diagnostics policy/);
    const missing = stateComparison(200, 116); delete missing.coldDiagnosticsOnly;
    assert.throws(() => confirm(initial, missing), /Cold diagnostics policy/);
    const forged = stateComparison(200, 116); forged.rows.find(row => row.key === "WARM/valueHit").blocked = false;
    assert.throws(() => confirm(initial, forged), /numerical policy/);
});

test("CLI propagates explicit cold diagnostics and rejects invalid selection", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "slow-shape-cold-policy-"));
    const script = fileURLToPath(new URL("./benchmark-slow-query-shapes.mjs", import.meta.url));
    try {
        const current = entries().map(entry => entry.params.cacheState === "COLD"
            ? { ...entry, primaryMetric: { score: 200, scoreUnit: "ms/op", rawData: [[200], [200], [200]] } } : entry);
        for (const [name, rows] of [["base", entries()], ["candidate", current]]) {
            fs.writeFileSync(path.join(directory, `${name}.json`), JSON.stringify(rows));
            fs.writeFileSync(path.join(directory, `${name}.log`), markers());
        }
        const args = [script, "compare", "--fixture", fixture, "--reference-kind", "unmodified-base",
            "--base", path.join(directory, "base.json"), "--candidate", path.join(directory, "candidate.json"),
            "--base-log", path.join(directory, "base.log"), "--candidate-log", path.join(directory, "candidate.log"),
            "--status", path.join(directory, "status.json"), "--report", path.join(directory, "report.md")];
        assert.equal(spawnSync(process.execPath, args).status, 1);
        assert.equal(spawnSync(process.execPath, [...args, "--cold-diagnostics-only", "true"]).status, 0);
        assert.equal(JSON.parse(fs.readFileSync(path.join(directory, "status.json"))).coldDiagnosticsOnly, true);
        assert.equal(spawnSync(process.execPath, [...args, "--cold-diagnostics-only", "typo"]).status, 1);
    } finally { fs.rmSync(directory, { recursive: true }); }
});

test("driver rejects unknown extra options and binds selected policy to comparison and provenance", () => {
    const script = fileURLToPath(new URL("./benchmark-slow-query-shapes.sh", import.meta.url));
    for (const args of [["a", "b", "c", "d", "e", "f", "--typo"],
        ["a", "b", "c", "d", "e", "f", "--cold-diagnostics-only", "extra"]]) {
        const result = spawnSync("bash", [script, ...args], { encoding: "utf8" });
        assert.equal(result.status, 2);
        assert.match(result.stderr, /Usage/);
    }
    const source = fs.readFileSync(script, "utf8");
    assert.match(source, /--cold-diagnostics-only "\$COLD_DIAGNOSTICS_ONLY"/);
    assert.match(source, /--argjson coldDiagnosticsOnly "\$COLD_DIAGNOSTICS_ONLY"/);
    assert.match(source, /coldDiagnosticsOnly:\$coldDiagnosticsOnly/);
    assert.match(source, /if compare_phase initial; then[\s\S]*?else[\s\S]*?measure candidate confirmation/);
});

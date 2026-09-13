import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { compareWideLatency, renderWideLatency, selectWideCatalog, WIDE_PROTOCOL } from "./benchmark-wide-latency.mjs";

const catalog = JSON.parse(fs.readFileSync(new URL("./wide-query-catalog.json", import.meta.url), "utf8")).queries;
const fields = ["id", "family", "shape", "selectivity", "operator", "boundary", "projection", "targetGraphId",
    "workloadIdentity", "limit", "outcome", "rowCount", "responseBytes", "digest"];
// Fabricated numeric evidence tests comparator behavior only; never performance evidence.
const graphIds = [...new Set(catalog.flatMap(q => q.expectedMatchingGraphIds ?? []))];
while (graphIds.length < 64) graphIds.push(`test-graph-${graphIds.length}`);
const manifest = graphIds.map(id => `${id}\tfixture`).join("\n");
const row = q => ({ ...q, targetGraphId: "", workloadIdentity: q.workloadIdentity ?? "a".repeat(64),
    outcome: "success", rowCount: q.selectivity === "zero" ? "0" : "1", responseBytes: "10", digest: "b".repeat(64),
    hitGraphIds: q.selectivity === "zero" ? "" : (q.expectedMatchingGraphIds?.[0] ?? graphIds[0]),
    executionPath: "cross-graph-query", inputSourceCount: "64" });
const oracle = catalog.map(q => fields.map(f => row(q)[f]).join("|")).join("\n");
const headers = ["phase", "round", "phaseElapsedNanos", ...fields, "latencyNanos", "hitGraphIds", "executionPath", "inputSourceCount"];
function evidence(transform = r => r, queries = catalog, measurementCalls = 40) {
    return Array.from({ length: 3 }, (_, fork) => [headers.join("\t"),
        ...queries.flatMap(q => ["warmup", "measurement"].flatMap(phase => {
            const count = phase === "warmup" ? 5 : measurementCalls;
            return Array.from({ length: count }, (_, i) => {
                const sample = transform({ ...row(q), phase, round: i + 1,
                    phaseElapsedNanos: Math.ceil(10_000_000_000 / count) * (i + 1), latencyNanos: 1_000_000 }, fork);
                return headers.map(h => sample[h]).join("\t");
            });
        }))].join("\n"));
}
const base = evidence();
const compare = (candidate = base, originals = base, expected = oracle) =>
    compareWideLatency(originals, candidate, expected, manifest);

test("all 72 identical queries pass independently with timed warmup, 40 minimum samples and three forks", () => {
    const result = compare();
    assert.equal(result.passed, true, result.integrityErrors.join("\n"));
    assert.equal(result.queries.length, 72);
    assert.ok(result.queries.every(q => q.passed && q.runs.length === 3));
    assert.equal(result.queries[0].runs[0].candidateP95Nanos, 1_000_000);
    assert.match(renderWideLatency(result), /Per-query wide latency gates/);
});

test("regression below 5% passes but exactly 5% fails only that query", () => {
    const id = catalog[0].id;
    for (const [latencyNanos, passed] of [[1_049_999, true], [1_050_000, false]]) {
        const result = compare(evidence(r => r.id === id ? { ...r, latencyNanos } : r));
        assert.equal(result.passed, passed);
        assert.equal(result.queries.filter(q => !q.passed).length, passed ? 0 : 1);
        if (!passed) assert.match(result.latencyErrors.join("\n"), /P50 regression.*<5%/);
    }
});

test("a tail-only slowdown blocks P95 even when P50 is unchanged", () => {
    const result = compare(evidence(r => r.id === catalog[0].id && r.round >= 38
        ? { ...r, latencyNanos: 1_100_000 } : r));
    assert.equal(result.passed, false);
    assert.match(result.latencyErrors.join("\n"), /P95 regression/);
    assert.doesNotMatch(result.latencyErrors.join("\n"), /P50 regression/);
});

test("cross-fork spread is diagnostic for both baseline and candidate", () => {
    const unstable = evidence((r, fork) => r.id === catalog[0].id && fork === 2
        ? { ...r, latencyNanos: 1_050_000 } : r);
    const sameUnstable = compare(unstable, unstable);
    assert.equal(sameUnstable.passed, true);
    assert.deepEqual(sameUnstable.latencyErrors, []);
    assert.equal(sameUnstable.queries[0].stability.baseP50SpreadPercent, 5);
    assert.equal(sameUnstable.queries[0].stability.candidateP95SpreadPercent, 5);
});

test("one wrong result signature in one sample blocks despite faster latency", () => {
    for (const field of ["digest", "rowCount", "responseBytes", "projection", "outcome", "workloadIdentity"]) {
        const result = compare(evidence((r, fork) => r.id === catalog[0].id && r.round === 40 && fork === 2
            ? { ...r, [field]: "wrong", latencyNanos: 1 } : r));
        assert.equal(result.passed, false);
        assert.match(result.integrityErrors.join("\n"), new RegExp(`${field} differs from correctness oracle`));
    }
});

test("missing or duplicate samples, missing query and detached oracle fail closed", () => {
    for (const mutate of [lines => lines.slice(0, -1), lines => [...lines, lines[1]],
        lines => lines.filter(line => !line.includes(catalog[0].id)),
        lines => lines.map(line => line.replace(/\t1000000\t/, "\tNaN\t"))]) {
        const result = compare([mutate(base[0].split("\n")).join("\n"), ...base.slice(1)]);
        assert.equal(result.passed, false);
        assert.ok(result.integrityErrors.length > 0);
    }
    assert.equal(compare(base, base, oracle.split("\n").slice(1).join("\n")).passed, false);
});

test("catalog includes original projections and multi-keyword zero, single, multi-graph cases", () => {
    assert.equal(new Set(catalog.map(q => q.id)).size, 72);
    for (const shape of ["or-four-zero", "or-four-single-early", "or-four-single-middle", "or-four-single-late",
        "or-four-few-early-late", "or-four-all", "and-broad-all", "and-zero-disjoint-graphs", "mixed-four-few"]) {
        for (const suffix of ["rows", "distinct"]) assert.ok(catalog.some(q => q.id === `${shape}-${suffix}`));
    }
});


test("changed query identity cannot pass by changing oracle and every sample together", () => {
    const query = catalog.find(q => q.workloadIdentity);
    const replacement = "f".repeat(64);
    const candidate = evidence(r => r.id === query.id ? { ...r, workloadIdentity: replacement } : r);
    const detachedOracle = oracle.replaceAll(query.workloadIdentity, replacement);
    const result = compare(candidate, candidate, detachedOracle);
    assert.equal(result.passed, false);
    assert.match(result.integrityErrors.join("\n"), /workloadIdentity differs from reviewed catalog/);
});


test("timed phases reject absent, short, reordered and noncontiguous evidence", () => {
    const id = catalog[0].id;
    for (const transform of [
        r => ({ ...r, phaseElapsedNanos: r.phaseElapsedNanos - 1 }),
        r => ({ ...r, phase: r.phase === "warmup" ? "measurement" : r.phase }),
        r => ({ ...r, round: r.round + 1 }),
        r => ({ ...r, phaseElapsedNanos: 1 }),
        r => ({ ...r, latencyNanos: r.phaseElapsedNanos + 1 }),
    ]) {
        const result = compare(evidence(r => r.id === id ? transform(r) : r));
        assert.equal(result.passed, false);
        assert.ok(result.integrityErrors.length);
    }
    const corruptWarmup = compare(evidence(r => r.id === id && r.phase === "warmup"
        ? { ...r, digest: "c".repeat(64) } : r));
    assert.match(corruptWarmup.integrityErrors.join("\n"), /digest differs from correctness oracle/);
    const lines = base[0].split("\n");
    // Move the first query's final measurement past the next query's first warmup.
    [lines[45], lines[46]] = [lines[46], lines[45]];
    assert.match(compare([lines.join("\n"), ...base.slice(1)]).integrityErrors.join("\n"), /must be contiguous/);
});

test("every measured call contributes to quantiles and actual run metadata", () => {
    const expanded = evidence(r => r.phase === "measurement" && r.round > 40
        ? { ...r, latencyNanos: 2_000_000 } : r, catalog, 80);
    const result = compare(expanded, expanded);
    assert.equal(result.passed, true, result.integrityErrors.join("\n"));
    assert.deepEqual(result.protocol, WIDE_PROTOCOL);
    const run = result.queries[0].runs[0];
    assert.equal(run.candidateMeasurementCalls, 80);
    assert.equal(run.candidateWarmupCalls, 5);
    assert.equal(run.candidateMeasurementElapsedNanos, 10_000_000_000);
    assert.equal(run.candidateP95Nanos, 2_000_000);
});

test("declared shards can be parsed independently but default aggregation requires all 72", () => {
    const standard = selectWideCatalog(catalog, { shard: "standard" });
    const fullScan = selectWideCatalog(catalog, { shard: "full-scan" });
    assert.equal(standard.length, 71);
    assert.deepEqual(fullScan.map(q => q.id), ["mixed-four-few-distinct"]);
    assert.throws(() => selectWideCatalog(catalog, { shard: "invented" }), /Unknown/);
    for (const [shard, subset] of [["standard", standard], ["full-scan", fullScan]]) {
        const subsetOracle = oracle.split("\n").filter(line => subset.some(q => line.startsWith(`${q.id}|`))).join("\n");
        const raw = evidence(r => r, subset);
        const result = compareWideLatency(raw, raw, subsetOracle, manifest, catalog, { shard });
        assert.equal(result.passed, true, result.integrityErrors.join("\n"));
        assert.equal(compareWideLatency(raw, raw, subsetOracle, manifest).passed, false,
            "missing shard must never produce a green full aggregate");
    }
});


test("fork evidence accepts one-pass line generators without materializing whole files", () => {
    let consumed = 0;
    function* lines(content) {
        for (const line of content.split("\n")) { consumed++; yield line; }
    }
    const result = compareWideLatency(base.map(lines), base.map(lines), oracle, manifest);
    assert.equal(result.passed, true);
    assert.equal(consumed, 6 * (1 + 72 * 45));
    assert.equal(result.queries[0].runs[0].baseP95Nanos, 1_000_000);
});


test("reviewed catalog declares the exact timed protocol and a complete disjoint shard partition", () => {
    const document = JSON.parse(fs.readFileSync(new URL("./wide-query-catalog.json", import.meta.url), "utf8"));
    assert.equal(document.schema, "graphite-wide-query-supplemental-v2");
    assert.deepEqual(document.protocol, WIDE_PROTOCOL);
    assert.deepEqual(Object.keys(document.shards).sort(), ["full-scan", "standard"]);
    const ids = Object.values(document.shards).flat();
    assert.equal(ids.length, 72);
    assert.equal(new Set(ids).size, 72);
    assert.deepEqual(ids.sort(), catalog.map(q => q.id).sort());
});

test('partial checkpoints never pass final acceptance, even with all three completed pairs', () => {
    for (const count of [1, 2, 3]) {
        const checkpoint = compareWideLatency(base.slice(0, count), base.slice(0, count), oracle, manifest, catalog, { partial: true });
        assert.equal(checkpoint.canContinue, true);
        assert.equal(checkpoint.passed, false);
        assert.equal(checkpoint.partial, true);
        assert.ok(checkpoint.queries.every(query => query.passed === false));
    }
});
test('first-pair exceedance waits for reverse pair while second-pair spread is nonblocking', () => {
    const slower = evidence(r => ({ ...r, latencyNanos: 1_050_000 }));
    const first = compareWideLatency(base.slice(0, 1), slower.slice(0, 1), oracle, manifest, catalog, { partial: true });
    assert.equal(first.canContinue, true);
    assert.match(first.latencyErrors.join('\n'), /P50 regression/);
    const unstable = evidence((r, fork) => ({ ...r, latencyNanos: fork === 1 ? 1_050_000 : 1_000_000 }));
    const second = compareWideLatency(unstable.slice(0, 2), unstable.slice(0, 2), oracle, manifest, catalog, { partial: true });
    assert.equal(second.canContinue, true);
    assert.deepEqual(second.latencyErrors, []);
    assert.equal(second.queries[0].stability.baseP50SpreadPercent, 5);
});
test('partial validation cannot hide incomplete pairs or malformed warmup', () => {
    const unmatched = compareWideLatency(base.slice(0, 1), [], oracle, manifest, catalog, { partial: true });
    assert.equal(unmatched.canContinue, false);
    const short = evidence(r => ({ ...r, phaseElapsedNanos: r.phaseElapsedNanos / 2 }));
    const invalid = compareWideLatency(base.slice(0, 1), short.slice(0, 1), oracle, manifest, catalog, { partial: true });
    assert.equal(invalid.canContinue, false);
    assert.ok(invalid.integrityErrors.length > 0);
    assert.equal(compareWideLatency(base.slice(0, 1), base.slice(0, 1), oracle, manifest).passed, false);
});

test('report displays same-revision variation separately from failures without changing evidence', () => {
    const unstable = evidence((r, fork) => r.id === catalog[0].id && fork === 2
        ? { ...r, latencyNanos: 1_050_000 } : r);
    const result = compare(unstable, unstable);
    const original = JSON.stringify(result);
    const report = renderWideLatency(result);
    assert.match(report, /Paired latency exceedance: 0/);
    assert.match(report, /Cross-fork variation \(diagnostic only/);
    assert.doesNotMatch(report, /Base instability:|Candidate instability:/);
    assert.match(report, /Integrity: 0/);
    assert.match(report, /does not establish a runtime regression caused by the candidate/);
    const queryLine = report.split('\n').find(line => line.startsWith(`| ${catalog[0].id} |`));
    assert.ok(queryLine.includes('| 3/3 | PASS |'));
    assert.ok(report.includes(`| ${catalog[0].id} | 5.00% | 5.00% | 5.00% | 5.00% |`));
    assert.doesNotMatch(queryLine, /Paired latency exceedance|regression/);
    assert.equal(JSON.stringify(result), original);
    assert.equal(result.passed, true);
});

test('first-pair report records partial observations and unavailable controls without claiming causation', () => {
    const slower = evidence(r => r.id === catalog[0].id ? { ...r, latencyNanos: 1_050_000 } : r);
    const result = compareWideLatency(base.slice(0, 1), slower.slice(0, 1), oracle, manifest, catalog, { partial: true });
    const original = JSON.stringify(result);
    const report = renderWideLatency(result);
    assert.match(report, /Partial checkpoint: 1\/3 paired forks completed/);
    assert.match(report, /Reverse-order control has not completed/);
    assert.match(report, /Three-fork stability diagnostics are incomplete/);
    assert.match(report, /Paired latency exceedance: 2/);
    assert.ok(report.includes(`| ${catalog[0].id} | unavailable | unavailable | unavailable | unavailable |`));
    assert.match(report, /AWAITING REVERSE PAIR/);
    assert.doesNotMatch(report, /CHECKPOINT FAIL|fail-fast stops|No observed checkpoint failure/);
    assert.match(report, /INCOMPLETE/);
    assert.doesNotMatch(report, /\| PASS \||regression must be/);
    assert.match(report, /observed increase must be <5%/);
    assert.equal(JSON.stringify(result), original);
    assert.equal(result.canContinue, true);
    assert.equal(result.passed, false);
});

test('report displays integrity errors separately and never labels a clean partial checkpoint PASS', () => {
    const invalid = compare(evidence(r => r.id === catalog[0].id && r.round === 1 ? { ...r, digest: 'wrong' } : r));
    const report = renderWideLatency(invalid);
    assert.match(report, /Integrity: 6/);
    assert.match(report, /Integrity: candidate-1\/.*digest differs from correctness oracle/);
    const partial = compareWideLatency(base.slice(0, 2), base.slice(0, 2), oracle, manifest, catalog, { partial: true });
    const partialReport = renderWideLatency(partial);
    assert.match(partialReport, /2\/3 paired forks completed/);
    assert.match(partialReport, /Reverse-order control is included/);
    assert.doesNotMatch(partialReport, /CHECKPOINT FAIL|\| PASS \|/);
    assert.equal(partial.canContinue, true);
});


test('reverse pair passing cannot erase the original paired exceedance or allow final acceptance', () => {
    const slowerFirst = evidence((r, fork) => ({ ...r, latencyNanos: fork === 0 ? 1_050_000 : 1_000_000 }));
    const second = compareWideLatency(base.slice(0, 2), slowerFirst.slice(0, 2), oracle, manifest, catalog, { partial: true });
    assert.equal(second.canContinue, false);
    assert.ok(second.latencyErrors.some(error => error.includes('P50 regression')));
    assert.ok(second.queries.every(query => query.runs[1].candidateP50Nanos === query.runs[1].baseP50Nanos));
    const report = renderWideLatency(second);
    assert.match(report, /Reverse-order control is included/);
    assert.match(report, /CHECKPOINT FAIL/);
    assert.doesNotMatch(report, /AWAITING REVERSE PAIR/);
    assert.equal(compareWideLatency(base, slowerFirst, oracle, manifest, catalog).passed, false);
});

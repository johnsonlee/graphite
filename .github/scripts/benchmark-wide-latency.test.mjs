import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { compareWideLatency, renderWideLatency } from "./benchmark-wide-latency.mjs";

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
const headers = ["round", ...fields, "latencyNanos", "hitGraphIds", "executionPath", "inputSourceCount"];
function evidence(transform = r => r) {
    return Array.from({ length: 3 }, (_, fork) => [headers.join("\t"),
        ...Array.from({ length: 40 }, (_, i) => catalog.map(q => {
            const sample = transform({ ...row(q), round: i + 1, latencyNanos: 1_000_000 }, fork);
            return headers.map(h => sample[h]).join("\t");
        })).flat()].join("\n"));
}
const base = evidence();
const compare = (candidate = base, originals = base, expected = oracle) =>
    compareWideLatency(originals, candidate, expected, manifest);

test("all 72 identical queries pass independently with 40 samples and three forks", () => {
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

test("cross-fork spread is independently enforced for both baseline and candidate", () => {
    const unstable = evidence((r, fork) => r.id === catalog[0].id && fork === 2
        ? { ...r, latencyNanos: 1_050_000 } : r);
    const sameUnstable = compare(unstable, unstable);
    assert.equal(sameUnstable.passed, false);
    assert.doesNotMatch(sameUnstable.latencyErrors.join("\n"), /regression/);
    assert.match(sameUnstable.latencyErrors.join("\n"), /base P50 cross-fork spread/);
    assert.match(sameUnstable.latencyErrors.join("\n"), /candidate P95 cross-fork spread/);
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

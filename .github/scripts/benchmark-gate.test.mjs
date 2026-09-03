import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { validatePairedEvidence } from "./benchmark-pages.mjs";
import { materializeGistFiles } from "./gist-evidence.mjs";
import {
    BENCHMARK_COMPONENTS,
    BENCHMARK_COVERAGE_DOMAINS,
    COMMENT_MARKER,
    aggregateGraphRoutingStates,
    aggregateReports,
    canonicalCorrectnessManifest,
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
    compareGraphIdPressure as compareGraphIdPressureRaw,
    compareGlobalWidePressure,
    deriveGraphRoutingOracle,
    parseLargeCorpusLog,
    makeJmhAdvisory,
    renderJmhReport,
    renderLatencyBaselineReport,
    renderLatencyAnchorReport,
    renderLargeCorpusReport,
    renderGraphIdPressureReport,
    renderGlobalWidePressureReport,
    selectJmhMetric,
    stageLatestArtifacts
} from "./benchmark-gate.mjs";

test("graph-routing aggregation retains every state and fails on a red state", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "graph-routing-aggregate-"));
    try {
        for (const state of ["cold", "warm", "startup-prepared"]) {
            const passed = state !== "warm";
            fs.writeFileSync(
                path.join(directory, `graph-routing-${state}-status.json`),
                JSON.stringify({ passed, errors: passed ? [] : ["warm P95 regressed"], gateP95Speedup: 9 })
            );
            fs.writeFileSync(path.join(directory, `graph-routing-${state}-report.md`), `### ${state}\n`);
        }
        const aggregate = aggregateGraphRoutingStates(directory);
        assert.equal(aggregate.passed, false);
        assert.deepEqual(Object.keys(aggregate.states), ["cold", "warm", "startup-prepared"]);
        assert.match(aggregate.body, /### cold/);
        assert.match(aggregate.body, /### warm/);
        assert.match(aggregate.body, /### startup-prepared/);
        assert.deepEqual(aggregate.errors, ["warm: warm P95 regressed"]);
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

test("canonical correctness comparison is order-insensitive and rejects incomplete or ambiguous records", () => {
    const first = "query-a|payload-a";
    const second = "query-b|payload-b";
    const expected = `${first}\n${second}\n`;

    assert.equal(
        canonicalCorrectnessManifest(`${second}\n${first}\n`, "candidate"),
        canonicalCorrectnessManifest(expected, "oracle")
    );
    assert.notEqual(
        canonicalCorrectnessManifest(`${first}\n`, "candidate"),
        canonicalCorrectnessManifest(expected, "oracle")
    );
    assert.notEqual(
        canonicalCorrectnessManifest(`${first}-mutated\n${second}\n`, "candidate"),
        canonicalCorrectnessManifest(expected, "oracle")
    );
    assert.throws(
        () => canonicalCorrectnessManifest(`${first}\nquery-a|payload-b\n`, "candidate"),
        /duplicate query IDs/
    );
});

function graphIdPressureResult(overrides = {}, indexState = "cold") {
    const warm = indexState === "warm" || indexState === "startup-prepared";
    const values = {
        graphCount: 64,
        distinctGraphPathCount: 64,
        queryCount: 1137,
        successCount: 1137,
        timeoutCount: 0,
        failureCount: 0,
        graphIdTargetCount: 64,
        graphParameterTargetCount: 64,
        coverageShapeCount: 7,
        coverageFamilyCount: 4,
        coverageSelectivityCount: 3,
        cpuCoreUtilizationPermille: 1_000,
        peakUsedHeapBytes: 3 * 1024 ** 3,
        peakResidentSetBytes: 4 * 1024 ** 3,
        gcCount: 2,
        gcMillis: 25,
        callSiteIndexAdmittedGraphs: 0,
        callSiteIndexRetainedBytes: 0,
        callSiteTrigramIndexedGraphs: 0,
        callSiteParallelScanCount: warm ? 0 : 64,
        callSiteParallelScanGraphCount: warm ? 0 : 64,
        callSiteStringIndexLookupCount: warm ? 2043 : 1979,
        callSiteStringIndexLookupGraphCount: 64,
        callSiteStringIndexLookupMinPerGraph: warm ? 30 : 29,
        callSiteStringIndexLookupMaxPerGraph: warm ? 39 : 38,
        callSiteScanPeakActiveWorkers: warm ? 0 : 8,
        ...overrides
    };
    return jmhResult({
        benchmark: "io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries",
        mode: "ss",
        score: 1,
        confidence: [1, 1],
        unit: "s/op",
        params: { graphCount: "64", coverageFamily: "graph-routing", indexState },
        secondaryMetrics: Object.fromEntries(
            Object.entries(values).map(([name, score]) => [name, { score, scoreUnit: "#" }])
        )
    });
}

function globalWidePressureResult(p95LatencyNanos, overrides = {}) {
    const values = {
        availableProcessors: 16,
        graphWorkerCount: 8,
        segmentWorkerCount: 8,
        graphScanPeakActiveWorkers: 8,
        segmentScanPeakActiveWorkers: 8,
        graphCount: 64,
        distinctGraphPathCount: 64,
        queryCount: 34,
        successCount: 34,
        timeoutCount: 0,
        failureCount: 0,
        coverageShapeCount: 14,
        coverageFamilyCount: 1,
        coverageSelectivityCount: 3,
        coverageProjectionCount: 8,
        coverageOperatorCount: 2,
        coverageBoundaryCount: 3,
        maxHeapBytes: 8 * 1024 ** 3,
        p50LatencyNanos: p95LatencyNanos,
        p95LatencyNanos,
        maxLatencyNanos: p95LatencyNanos,
        processCpuNanos: 100_000_000,
        cpuCoreUtilizationPermille: 5_000,
        peakUsedHeapBytes: 4 * 1024 ** 3,
        peakResidentSetBytes: 5 * 1024 ** 3,
        graphWorkUnits: 1_000_000,
        ...overrides
    };
    return jmhResult({
        benchmark: "io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries",
        mode: "ss",
        score: 1,
        confidence: [1, 1],
        unit: "s/op",
        params: { graphCount: "64", coverageFamily: "global-wide", indexState: "cold" },
        secondaryMetrics: Object.fromEntries(
            Object.entries(values).map(([name, score]) => [name, { score, scoreUnit: "#" }])
        )
    });
}

function globalWideEvidence(latencyNanos = 35_000_000) {
    const shapes = [
        "global-wide-four-properties",
        "global-wide-class-pair",
        "global-wide-name-pair",
        "global-wide-caller-class",
        "global-wide-callee-class",
        "global-wide-provenance",
        "global-wide-aliased",
        "global-wide-parameterized",
        "global-wide-wrapped-case-insensitive",
        "global-wide-wrapped-case-insensitive-distinct"
    ];
    const workloadIdentities = Array.from({ length: 64 }, (_, graphIndex) =>
        crypto.createHash("sha256").update(`graph-${graphIndex}`).digest("hex"));
    const allGraphIds = Array.from({ length: 64 }, (_, graphIndex) => `graph-${graphIndex}`);
    const distributions = [
        ["localized-early", "graph-0", "local-early", "graph-0"],
        ["localized-middle", "graph-31", "local-middle", "graph-31"],
        ["localized-late", "graph-63", "local-late", "graph-63"],
        ["broad-all-64", "graph-0", "broad", allGraphIds.join(",")]
    ];
    const manifest = ["# fixture64", ...Array.from({ length: 64 }, (_, graphIndex) =>
        `graph-${graphIndex}\t/graphs/graph-${graphIndex}\tabsent-${graphIndex}\t` +
            `targeted-${graphIndex}\tdense-${graphIndex}\t${workloadIdentities[graphIndex]}`
    ), ...distributions.map((columns) =>
        ["# global-wide-distribution-v1", ...columns].join("\t"))].join("\n") + "\n";
    const header = "id\tfamily\tshape\tselectivity\toperator\tboundary\tprojection\tlimit\t" +
        "targetGraphId\ttargetGraphIds\tselectedGraphCount\tworkloadIdentity\toutcome\trowCount\t" +
        "responseBytes\tdigest\tlatencyNanos\tfixtureDistributionId\thitGraphIds\texecutionPath\t" +
        "inputSourceCount\taccessedGraphCount\t" +
        "accessedGraphIds\ttargetGraphAccessCount\tnonTargetGraphAccessCount\tparallelScanCount\t" +
        "indexLookupCount\tpeakActiveWorkers\tgraphWorkUnits\tgraphIdSourceSelections\t" +
        "graphIdSourcePruningExecutions\tgraphIdSourcesPruned\tfilteredNodeLimitFastPathExecutions\t" +
        "generalFallbackExecutions";
    const rows = [];
    const oracle = [];
    for (let shapeIndex = 0; shapeIndex < shapes.length; shapeIndex++) {
        for (const selectivity of ["zero", "targeted", "dense"]) {
            const id = `shape-${shapeIndex}-${selectivity}`;
            const shape = shapes[shapeIndex];
            const operator = shapeIndex >= 8 ? "wrapped-lowercase-contains" : "raw-contains";
            const boundary = shapeIndex === 7 ? "parameters" : "single-query";
            const projection = [
                "properties", "class-properties", "name-properties", "caller-class", "callee-class",
                "graph-id-properties", "aliased-properties", "properties", "properties", "distinct-properties"
            ][shapeIndex];
            const rowCount = selectivity === "zero" ? 0 : selectivity === "dense" ? 200 : 10;
            const digest = crypto.createHash("sha256").update(id).digest("hex");
            const placementOrdinal = Math.floor(shapeIndex * 63 / (shapes.length - 1));
            const expectedOrdinal = selectivity === "dense" ? 0 : placementOrdinal;
            const completeDistinctProvenance = shapeIndex === 9 && selectivity === "dense";
            const accessedCount = selectivity === "zero" || completeDistinctProvenance ?
                64 : expectedOrdinal + 1;
            const accessedGraphIds = Array.from(
                { length: accessedCount },
                (_, graphIndex) => `graph-${graphIndex}`
            ).join(",");
            const accessedGraphCount = String(accessedCount);
            const nonTargetGraphAccessCount = String(accessedCount);
            const hitGraphIds = selectivity === "zero" ? "" :
                completeDistinctProvenance ? "graph-0,graph-1" :
                    selectivity === "dense" ? "graph-0" : `graph-${expectedOrdinal}`;
            rows.push([
                id, "global-wide", shape, selectivity, operator, boundary, projection, "200",
                "", "", "0", workloadIdentities[expectedOrdinal], "success", String(rowCount), "128", digest,
                String(latencyNanos), "", hitGraphIds,
                "cross-graph-query", "64", accessedGraphCount, accessedGraphIds, "0",
                nonTargetGraphAccessCount, "0", "1", "0", "100",
                "0", "0", "0", "1", "0"
            ].join("\t"));
            oracle.push([
                id, "global-wide", shape, selectivity, operator, boundary, projection,
                "", workloadIdentities[expectedOrdinal], "200", "success", String(rowCount), "128", digest
            ].join("|"));
        }
    }
    for (const [distributionId, targetGraphId] of distributions) {
        const targetOrdinal = Number(targetGraphId.slice("graph-".length));
        const broad = distributionId === "broad-all-64";
        const middle = distributionId === "localized-middle";
        const late = distributionId === "localized-late";
        const accessedCount = late ? 64 : middle ? 33 : 1;
        const accessedGraphIds = allGraphIds.slice(0, accessedCount).join(",");
        const hitGraphIds = broad ? "graph-0" : targetGraphId;
        const id = `global-wide-distribution-${distributionId}`;
        const digest = crypto.createHash("sha256").update(id).digest("hex");
        rows.push([
            id, "global-wide", id, "dense", "wrapped-lowercase-contains", "fixture-distribution",
            "properties", "200", "", "", "0", workloadIdentities[targetOrdinal], "success", "200",
            "128", digest, String(latencyNanos), distributionId, hitGraphIds, "cross-graph-query", "64",
            String(accessedCount), accessedGraphIds, "0", String(accessedCount), "0", String(accessedCount),
            "0", "100", "0", "0", "0", "1", "0"
        ].join("\t"));
        oracle.push([
            id, "global-wide", id, "dense", "wrapped-lowercase-contains", "fixture-distribution",
            "properties", "", workloadIdentities[targetOrdinal], "200", "success", "200", "128", digest
        ].join("|"));
    }
    return { observations: `${header}\n${rows.join("\n")}\n`, oracle: `${oracle.join("\n")}\n`, manifest };
}

function withoutGlobalWideAccessTelemetry(contents) {
    const lines = contents.trimEnd().split("\n");
    const header = lines[0].split("\t");
    const accessFields = [
        "accessedGraphCount", "accessedGraphIds", "targetGraphAccessCount",
        "nonTargetGraphAccessCount", "parallelScanCount", "indexLookupCount", "peakActiveWorkers"
    ];
    const indexes = accessFields.map((field) => header.indexOf(field));
    return `${lines.map((line, lineIndex) => {
        if (lineIndex === 0) return line;
        const fields = line.split("\t");
        for (const index of indexes) fields[index] = index === header.indexOf("accessedGraphIds") ? "" : "0";
        return fields.join("\t");
    }).join("\n")}\n`;
}

function graphIdObservations(
    latencyNanos,
    outcome = "success",
    graphParameterLatencyNanos = 1_000_000_000,
    forceZeroRows = false
) {
    const header = "id\tfamily\tshape\tselectivity\toperator\tboundary\tprojection\tlimit\t" +
        "targetGraphId\ttargetGraphIds\tselectedGraphCount\tworkloadIdentity\toutcome\trowCount\t" +
        "responseBytes\tdigest\tlatencyNanos\t" +
        "executionPath\tinputSourceCount\taccessedGraphCount\taccessedGraphIds\t" +
        "targetGraphAccessCount\tnonTargetGraphAccessCount\tparallelScanCount\tindexLookupCount\t" +
        "peakActiveWorkers\tgraphWorkUnits\tgraphIdSourceSelections\tgraphIdSourcePruningExecutions\t" +
        "graphIdSourcesPruned\tfilteredNodeLimitFastPathExecutions\tgeneralFallbackExecutions";
    const rows = [];
    const shapes = [
        "graph-id-property-wrapped-contains",
        "graph-id-function-wrapped-contains",
        "graph-id-parameter-wrapped-contains"
    ];
    const selectivities = ["zero", "targeted", "dense"];
    for (let targetIndex = 0; targetIndex < 64; targetIndex++) {
        const workloadIdentity = crypto.createHash("sha256").update(`graph-${targetIndex}`).digest("hex");
        for (const selectivity of selectivities) {
            const rowCount = forceZeroRows || selectivity === "zero" ? "0" :
                selectivity === "targeted" ? "10" : "200";
            const responseBytes = rowCount === "0" ? "64" : "128";
            const digest = crypto.createHash("sha256")
                .update(`${targetIndex}-${selectivity}`).digest("hex");
            for (const shape of shapes) {
                rows.push([
                    `${shape}-target-${String(targetIndex).padStart(2, "0")}-${selectivity}`,
                    "graph-id", shape, selectivity, "contains", "graph-routing",
                    "properties", "200", `graph-${targetIndex}`, `graph-${targetIndex}`, "1", workloadIdentity,
                    outcome, rowCount, responseBytes, digest,
                    String(latencyNanos), "cypher-graph-id-predicate", "64", "1", `graph-${targetIndex}`,
                    "1", "0", "0", "1", "0", "1", "1", "1", "63", "1", "0"
                ].join("\t"));
            }
            rows.push([
                `request-selected-source-wrapped-contains-target-${String(targetIndex).padStart(2, "0")}-${selectivity}`,
                "graph-parameter", "request-selected-source-wrapped-contains", selectivity,
                "request-graph-selection-and-wrapped-contains", "request-selected-source", "properties", "200",
                `graph-${targetIndex}`, `graph-${targetIndex}`, "1", workloadIdentity,
                outcome, rowCount, responseBytes, digest,
                String(graphParameterLatencyNanos), "request-selected-source", "1", "1", `graph-${targetIndex}`,
                "1", "0", "0", "1", "0", "1", "0", "0", "0", "1", "0"
            ].join("\t"));
        }
    }
    for (const width of [2, 8, 64]) {
        const groupCount = 64 / width;
        for (let groupIndex = 0; groupIndex < groupCount; groupIndex++) {
            const targetGraphIds = Array.from(
                { length: width }, (_, offset) => `graph-${groupIndex * width + offset}`
            );
            const targetGraphId = targetGraphIds[0];
            const workloadIdentity = crypto.createHash("sha256")
                .update(targetGraphIds.map((graphId) => {
                    const index = Number(graphId.slice("graph-".length));
                    const identity = crypto.createHash("sha256").update(`graph-${index}`).digest("hex");
                    return `${graphId}\0${identity}`;
                }).join("\0"))
                .digest("hex");
            for (const selectivity of selectivities) {
                const rowCount = forceZeroRows || selectivity === "zero" ? "0" :
                    selectivity === "targeted" ? "10" : "200";
                const responseBytes = rowCount === "0" ? "64" : "128";
                const digest = crypto.createHash("sha256")
                    .update(`set-${width}-${groupIndex}-${selectivity}`).digest("hex");
                const suffix = `k${String(width).padStart(2, "0")}-group-` +
                    `${String(groupIndex).padStart(2, "0")}-${selectivity}`;
                const accessedGraphIds = selectivity === "dense" ? targetGraphIds.slice(0, 1) : targetGraphIds;
                const accessedGraphCount = accessedGraphIds.length;
                for (const [shape, operator, boundary] of [
                    ["graph-id-in-literal-wrapped-contains", "graph-id-in-literal-and-wrapped-contains",
                        "graph-routing-set"],
                    ["graph-id-in-parameter-wrapped-contains", "graph-id-in-parameter-and-wrapped-contains",
                        "parameters"]
                ]) {
                    rows.push([
                        `${shape}-${suffix}`, "graph-id-set", shape, selectivity, operator, boundary,
                        "properties", "200", targetGraphId, targetGraphIds.join(","), String(width),
                        workloadIdentity, outcome, rowCount, responseBytes, digest, String(latencyNanos),
                        "cypher-graph-id-predicate", "64", String(accessedGraphCount),
                        accessedGraphIds.join(","), String(accessedGraphCount), "0", "0",
                        String(accessedGraphCount), "0", "1", "1",
                        width < 64 ? "1" : "0", String(64 - width), "1", "0"
                    ].join("\t"));
                }
                rows.push([
                    `request-selected-set-wrapped-contains-${suffix}`, "graph-set-reference",
                    "request-selected-set-wrapped-contains", selectivity,
                    "request-graph-set-selection-and-wrapped-contains", "request-selected-source",
                    "properties", "200", targetGraphId, targetGraphIds.join(","), String(width),
                    workloadIdentity, outcome, rowCount, responseBytes, digest,
                    String(graphParameterLatencyNanos), "request-selected-source", String(width),
                    String(accessedGraphCount), accessedGraphIds.join(","), String(accessedGraphCount),
                    "0", "0", String(accessedGraphCount), "0", "1",
                    "0", "0", "0", "1", "0"
                ].join("\t"));
            }
        }
    }
    const coldFirstId = "request-selected-set-wrapped-contains-k64-group-00-zero";
    const coldFirstIndex = rows.findIndex((row) => row.startsWith(`${coldFirstId}\t`));
    const [coldFirst] = rows.splice(coldFirstIndex, 1);
    rows.unshift(coldFirst);
    return `${header}\n${rows.join("\n")}\n`;
}

function correctnessFromObservations(contents) {
    const [header, ...lines] = contents.trim().split("\n");
    const columns = header.split("\t");
    const field = (values, name) => values[columns.indexOf(name)];
    return `${lines.map((line) => {
        const values = line.split("\t");
        return [
            "id", "family", "shape", "selectivity", "operator", "boundary", "projection",
            "targetGraphId", "workloadIdentity", "limit", "outcome", "rowCount", "responseBytes", "digest"
        ].map((name) => field(values, name)).join("|");
    }).join("\n")}\n`;
}

function compareGraphIdPressure(
    baseResults,
    candidateResults,
    baseObservations,
    candidateObservations,
    minimumSpeedup = 10
) {
    return compareGraphIdPressureRaw(
        baseResults,
        candidateResults,
        baseObservations,
        candidateObservations,
        correctnessFromObservations(baseObservations),
        correctnessFromObservations(candidateObservations),
        minimumSpeedup
    );
}

function graphParameterReferenceManifest() {
    const records = [];
    for (let targetIndex = 0; targetIndex < 64; targetIndex++) {
        const workloadIdentity = crypto.createHash("sha256").update(`graph-${targetIndex}`).digest("hex");
        for (const selectivity of ["zero", "targeted", "dense"]) {
            const rowCount = selectivity === "zero" ? 0 : selectivity === "targeted" ? 10 : 200;
            records.push([
                `request-selected-source-wrapped-contains-target-${String(targetIndex).padStart(2, "0")}-${selectivity}`,
                "graph-parameter",
                "request-selected-source-wrapped-contains",
                selectivity,
                "request-graph-selection-and-wrapped-contains",
                "request-selected-source",
                "properties",
                `graph-${targetIndex}`,
                workloadIdentity,
                200,
                "success",
                rowCount,
                rowCount === 0 ? 64 : 128,
                crypto.createHash("sha256").update(`${targetIndex}-${selectivity}`).digest("hex")
            ].join("|"));
        }
    }
    for (const width of [2, 8, 64]) {
        for (let groupIndex = 0; groupIndex < 64 / width; groupIndex++) {
            const targetGraphIds = Array.from(
                { length: width }, (_, offset) => `graph-${groupIndex * width + offset}`
            );
            const workloadIdentity = crypto.createHash("sha256")
                .update(targetGraphIds.map((graphId) => {
                    const index = Number(graphId.slice("graph-".length));
                    const identity = crypto.createHash("sha256").update(`graph-${index}`).digest("hex");
                    return `${graphId}\0${identity}`;
                }).join("\0"))
                .digest("hex");
            for (const selectivity of ["zero", "targeted", "dense"]) {
                const rowCount = selectivity === "zero" ? 0 : selectivity === "targeted" ? 10 : 200;
                records.push([
                    `request-selected-set-wrapped-contains-k${String(width).padStart(2, "0")}-group-` +
                        `${String(groupIndex).padStart(2, "0")}-${selectivity}`,
                    "graph-set-reference",
                    "request-selected-set-wrapped-contains",
                    selectivity,
                    "request-graph-set-selection-and-wrapped-contains",
                    "request-selected-source",
                    "properties",
                    targetGraphIds[0],
                    workloadIdentity,
                    200,
                    "success",
                    rowCount,
                    rowCount === 0 ? 64 : 128,
                    crypto.createHash("sha256")
                        .update(`set-${width}-${groupIndex}-${selectivity}`).digest("hex")
                ].join("|"));
            }
        }
    }
    return `${records.join("\n")}\n`;
}

test("fixture64 global wide-query pressure requires 10x in both paired run orders", () => {
    const evidence = globalWideEvidence();
    const baseRuns = Array.from({ length: 3 }, () => [
        globalWidePressureResult(400_000_000, { graphWorkerCount: 0, segmentWorkerCount: 0 })
    ]);
    const baseObservations = Array.from(
        { length: 3 }, () => withoutGlobalWideAccessTelemetry(globalWideEvidence(400_000_000).observations));
    const comparison = compareGlobalWidePressure(
        baseRuns,
        [39_000_000, 38_000_000, 35_000_000].map((p95) => [globalWidePressureResult(p95)]),
        baseObservations,
        [39_000_000, 38_000_000, 35_000_000].map((latency) =>
            globalWideEvidence(latency).observations),
        evidence.oracle,
        10,
        ["candidate-base", "base-candidate", "candidate-base"],
        evidence.manifest
    );
    assert.equal(comparison.passed, true);
    assert.equal(comparison.runs.length, 3);
    assert.ok(comparison.runs.every((run) => run.p95Speedup >= 10));
    assert.match(renderGlobalWidePressureReport(comparison), /Result: PASS/);

    const unstable = compareGlobalWidePressure(
        baseRuns,
        [39_000_000, 41_000_000, 35_000_000].map((p95) => [globalWidePressureResult(p95)]),
        baseObservations,
        [39_000_000, 41_000_000, 35_000_000].map((latency) =>
            globalWideEvidence(latency).observations),
        evidence.oracle,
        10,
        ["candidate-base", "base-candidate", "candidate-base"],
        evidence.manifest
    );
    assert.equal(unstable.passed, false);
    assert.match(unstable.errors.join("\n"), /pair-2: P95 speedup/);
});

test("fixture64 global wide-query pressure verifies NCPU split and correctness", () => {
    const evidence = globalWideEvidence();
    const baseRuns = Array.from({ length: 3 }, () => [
        globalWidePressureResult(400_000_000, { graphWorkerCount: 0, segmentWorkerCount: 0 })
    ]);
    const baseObservations = Array.from(
        { length: 3 }, () => withoutGlobalWideAccessTelemetry(globalWideEvidence(400_000_000).observations));
    const wrongSplit = globalWidePressureResult(35_000_000, { graphWorkerCount: 16, segmentWorkerCount: 8 });
    const incorrect = evidence.observations.replace(
        /shape-0-zero\tglobal-wide/,
        "shape-0-zero\tglobal"
    );
    const comparison = compareGlobalWidePressure(
        baseRuns,
        [[wrongSplit], [globalWidePressureResult(35_000_000)], [globalWidePressureResult(35_000_000)]],
        baseObservations,
        [incorrect, evidence.observations, evidence.observations],
        evidence.oracle,
        10,
        ["candidate-base", "base-candidate", "candidate-base"],
        evidence.manifest
    );
    assert.equal(comparison.passed, false);
    assert.match(comparison.errors.join("\n"), /NCPU split 16 -> 16\+8; expected 8\+8/);
    assert.match(comparison.errors.join("\n"), /family differs from correctness oracle/);

    const wrongObservedPeak = globalWidePressureResult(35_000_000, {
        graphScanPeakActiveWorkers: 8,
        segmentScanPeakActiveWorkers: 16
    });
    const overcommitted = compareGlobalWidePressure(
        baseRuns,
        [[wrongObservedPeak], [globalWidePressureResult(35_000_000)], [globalWidePressureResult(35_000_000)]],
        baseObservations,
        [evidence.observations, evidence.observations, evidence.observations],
        evidence.oracle,
        10,
        ["candidate-base", "base-candidate", "candidate-base"],
        evidence.manifest
    );
    assert.equal(overcommitted.passed, false);
    assert.match(overcommitted.errors.join("\n"), /observed graph\/segment worker peaks 8\+16; expected 8\+8/);

    const skippedGraphs = globalWideEvidence();
    skippedGraphs.observations = skippedGraphs.observations.replace(
        /\t64\tgraph-0,graph-1[^\t]*\t0\t64\t/,
        "\t1\tgraph-0\t0\t1\t"
    );
    const incomplete = compareGlobalWidePressure(
        baseRuns,
        [35_000_000, 35_000_000, 35_000_000].map((p95) => [globalWidePressureResult(p95)]),
        baseObservations,
        [skippedGraphs.observations, evidence.observations, evidence.observations],
        evidence.oracle,
        10,
        ["candidate-base", "base-candidate", "candidate-base"],
        evidence.manifest
    );
    assert.equal(incomplete.passed, false);
    assert.match(incomplete.errors.join("\n"), /zero-hit query must prove the exact graphs.tsv set was searched/);

    const detached = globalWideEvidence();
    detached.observations = detached.observations.replaceAll("graph-", "detached-");
    const detachedComparison = compareGlobalWidePressure(
        baseRuns,
        [35_000_000, 35_000_000, 35_000_000].map((p95) => [globalWidePressureResult(p95)]),
        baseObservations,
        [detached.observations, evidence.observations, evidence.observations],
        evidence.oracle,
        10,
        ["candidate-base", "base-candidate", "candidate-base"],
        evidence.manifest
    );
    assert.equal(detachedComparison.passed, false);
    assert.match(detachedComparison.errors.join("\n"), /accessed graph IDs are not bound to graphs.tsv/);

    const nonLocalizedManifest = evidence.manifest.replace(
        "# global-wide-distribution-v1\tlocalized-early\tgraph-0\tlocal-early\tgraph-0",
        "# global-wide-distribution-v1\tlocalized-early\tgraph-0\tlocal-early\tgraph-0,graph-1"
    );
    const nonLocalized = compareGlobalWidePressure(
        baseRuns,
        [35_000_000, 35_000_000, 35_000_000].map((p95) => [globalWidePressureResult(p95)]),
        baseObservations,
        [evidence.observations, evidence.observations, evidence.observations],
        evidence.oracle,
        10,
        ["candidate-base", "base-candidate", "candidate-base"],
        nonLocalizedManifest
    );
    assert.equal(nonLocalized.passed, false);
    assert.match(nonLocalized.errors.join("\n"), /localized-early must be globally localized/);
});

test("fixture64 global wide-query pressure requires the wrapped case-insensitive shape and its 10x P95", () => {
    const evidence = globalWideEvidence();
    const baseEvidence = globalWideEvidence(400_000_000);
    const replaceWrappedLatency = (contents, latencyNanos) => {
        const lines = contents.trimEnd().split("\n");
        const headers = lines[0].split("\t");
        const shapeIndex = headers.indexOf("shape");
        const latencyIndex = headers.indexOf("latencyNanos");
        return `${lines.map((line, index) => {
            if (index === 0) return line;
            const columns = line.split("\t");
            if (columns[shapeIndex] === "global-wide-wrapped-case-insensitive") {
                columns[latencyIndex] = String(latencyNanos);
            }
            return columns.join("\t");
        }).join("\n")}\n`;
    };
    const slowWrapped = replaceWrappedLatency(evidence.observations, 80_000_000);
    const slowComparison = compareGlobalWidePressure(
        Array.from({ length: 3 }, () => [
            globalWidePressureResult(400_000_000, { graphWorkerCount: 0, segmentWorkerCount: 0 })
        ]),
        Array.from({ length: 3 }, () => [globalWidePressureResult(80_000_000)]),
        Array.from({ length: 3 }, () => withoutGlobalWideAccessTelemetry(baseEvidence.observations)),
        Array.from({ length: 3 }, () => slowWrapped),
        evidence.oracle,
        10,
        ["candidate-base", "base-candidate", "candidate-base"],
        evidence.manifest
    );
    assert.equal(slowComparison.passed, false);
    assert.match(
        slowComparison.errors.join("\n"),
        /global-wide-wrapped-case-insensitive P95 speedup 5\.00x/
    );

    const rawOnlyObservations = evidence.observations.split("\n")
        .filter((line) => !line.includes("\twrapped-lowercase-contains\t"))
        .join("\n");
    const rawOnlyOracle = evidence.oracle.split("\n")
        .filter((line) => !line.includes("|wrapped-lowercase-contains|"))
        .join("\n");
    const rawOnlyMetrics = {
        queryCount: 24,
        successCount: 24,
        coverageShapeCount: 8,
        coverageProjectionCount: 7,
        coverageOperatorCount: 1
    };
    const rawOnlyComparison = compareGlobalWidePressure(
        Array.from({ length: 3 }, () => [globalWidePressureResult(400_000_000, {
            graphWorkerCount: 0,
            segmentWorkerCount: 0,
            ...rawOnlyMetrics
        })]),
        Array.from({ length: 3 }, () => [globalWidePressureResult(35_000_000, rawOnlyMetrics)]),
        Array.from({ length: 3 }, () => withoutGlobalWideAccessTelemetry(rawOnlyObservations)),
        Array.from({ length: 3 }, () => rawOnlyObservations),
        rawOnlyOracle,
        10,
        ["candidate-base", "base-candidate", "candidate-base"],
        evidence.manifest
    );
    assert.equal(rawOnlyComparison.passed, false);
    assert.match(rawOnlyComparison.errors.join("\n"), /expected 30 records|queryCount=24/);

    const replaceLatency = (contents, shape, selectivity, latencyNanos) => {
        const lines = contents.trimEnd().split("\n");
        const headers = lines[0].split("\t");
        const shapeIndex = headers.indexOf("shape");
        const selectivityIndex = headers.indexOf("selectivity");
        const latencyIndex = headers.indexOf("latencyNanos");
        return `${lines.map((line, index) => {
            if (index === 0) return line;
            const columns = line.split("\t");
            if (columns[shapeIndex] === shape && columns[selectivityIndex] === selectivity) {
                columns[latencyIndex] = String(latencyNanos);
            }
            return columns.join("\t");
        }).join("\n")}\n`;
    };
    let shiftedBase = globalWideEvidence(400_000_000).observations;
    let shiftedCandidate = globalWideEvidence(35_000_000).observations;
    for (const [selectivity, baseLatency, candidateLatency] of [
        ["zero", 1_000_000_000, 50_000_000],
        ["targeted", 1_000_000, 10_000_000],
        ["dense", 1_000_000, 1_000_000]
    ]) {
        shiftedBase = replaceLatency(
            shiftedBase,
            "global-wide-wrapped-case-insensitive",
            selectivity,
            baseLatency
        );
        shiftedCandidate = replaceLatency(
            shiftedCandidate,
            "global-wide-wrapped-case-insensitive",
            selectivity,
            candidateLatency
        );
    }
    const shifted = compareGlobalWidePressure(
        Array.from({ length: 3 }, () => [globalWidePressureResult(400_000_000, {
            graphWorkerCount: 0,
            segmentWorkerCount: 0,
            maxLatencyNanos: 1_000_000_000
        })]),
        Array.from({ length: 3 }, () => [globalWidePressureResult(35_000_000, {
            maxLatencyNanos: 50_000_000
        })]),
        Array.from({ length: 3 }, () => withoutGlobalWideAccessTelemetry(shiftedBase)),
        Array.from({ length: 3 }, () => shiftedCandidate),
        evidence.oracle,
        10,
        ["candidate-base", "base-candidate", "candidate-base"],
        evidence.manifest
    );
    assert.equal(shifted.passed, false);
    assert.match(
        shifted.errors.join("\n"),
        /global-wide-wrapped-case-insensitive\/targeted: aligned latency .* by >15% and >1 ms/
    );
});

test("fixture64 global wide-query pressure rejects detached JSON latency summaries", () => {
    const evidence = globalWideEvidence();
    const baseEvidence = globalWideEvidence(400_000_000);
    const lines = evidence.observations.trimEnd().split("\n");
    const headers = lines[0].split("\t");
    const shapeIndex = headers.indexOf("shape");
    const latencyIndex = headers.indexOf("latencyNanos");
    const detachedRows = `${lines.map((line, index) => {
        if (index === 0) return line;
        const columns = line.split("\t");
        columns[latencyIndex] = columns[shapeIndex].includes("wrapped-case-insensitive") ?
            "20000000" : "3500000000";
        return columns.join("\t");
    }).join("\n")}\n`;
    const comparison = compareGlobalWidePressure(
        Array.from({ length: 3 }, () => [globalWidePressureResult(400_000_000, {
            graphWorkerCount: 0,
            segmentWorkerCount: 0
        })]),
        Array.from({ length: 3 }, () => [globalWidePressureResult(35_000_000)]),
        Array.from({ length: 3 }, () => withoutGlobalWideAccessTelemetry(baseEvidence.observations)),
        Array.from({ length: 3 }, () => detachedRows),
        evidence.oracle,
        10,
        ["candidate-base", "base-candidate", "candidate-base"],
        evidence.manifest
    );
    assert.equal(comparison.passed, false);
    assert.match(
        comparison.errors.join("\n"),
        /candidate-1: p95LatencyNanos=35000000; observation rows recompute to 3500000000/
    );
});

test("fixture64 global wide-query pressure gates every fork and paired resources", () => {
    const evidence = globalWideEvidence();
    const baseRuns = Array.from({ length: 3 }, () => [
        globalWidePressureResult(400_000_000, {
            graphWorkerCount: 0,
            segmentWorkerCount: 0,
            processCpuNanos: 100_000_000,
            peakUsedHeapBytes: 1024 ** 3,
            peakResidentSetBytes: 1024 ** 3
        })
    ]);
    const candidates = [80_000_000, 32_000_000, 25_000_000].map((p95, index) => [
        globalWidePressureResult(p95, {
            processCpuNanos: index === 1 ? 149_000_000 : 100_000_000,
            peakUsedHeapBytes: index === 1 ? Math.floor(1.49 * 1024 ** 3) : 1024 ** 3,
            peakResidentSetBytes: index === 1 ? Math.floor(1.49 * 1024 ** 3) : 1024 ** 3
        })
    ]);
    const comparison = compareGlobalWidePressure(
        baseRuns,
        candidates,
        Array.from({ length: 3 }, () => globalWideEvidence(400_000_000).observations),
        [80_000_000, 32_000_000, 25_000_000].map((latency) =>
            globalWideEvidence(latency).observations),
        evidence.oracle,
        10,
        ["candidate-base", "base-candidate", "candidate-base"],
        evidence.manifest
    );
    assert.equal(comparison.passed, false);
    assert.match(comparison.errors.join("\n"), /pair-1: P95 speedup 5\.00x/);
    assert.match(comparison.errors.join("\n"), /pair-2: process CPU .* by >15%/);
});

test("fixture64 global-wide driver binds pinned JAR provenance and alternates paired forks", () => {
    const driver = fs.readFileSync(new URL("./run-real64-global-wide.sh", import.meta.url), "utf8");
    assert.match(driver, /<fixture-jar-directory>/);
    assert.match(driver, /:webgraph:prepareBenchmarkFixtures/);
    assert.match(driver, /Fixture64GraphPreparation/);
    assert.match(driver, /--verify "\$\{MANIFEST\}" "\$\{FIXTURE_PROVENANCE\}"/);
    assert.match(driver, /cmp -s "\$\{SUPPLIED_JAR\}" "\$\{PINNED_JAR\}"/);
    assert.match(driver, /test-fixture64-reproducibility\.sh|REPRODUCIBILITY_SCRIPT_PATH/);
    assert.match(driver, /if \(\( RUN % 2 == 1 \)\); then run_candidate; run_base;/);
    assert.match(driver, /--bases "\$\{BASE_JSON_LIST\}"/);
    assert.match(driver, /--minimum-speedup 5/);
    assert.match(driver, /GRAPHITE_PRESSURE_PUBLISH_EVIDENCE/);
    assert.match(driver, /if \[\[ "\$\{PUBLISH_EVIDENCE\}" == false \]\]/);
    assert.match(driver, /graphite\/fixture64-global-wide/);
    assert.match(driver, /gh gist create --public/);
});

test("fixture64 startup-prepared graphId pressure guards the optimization already on main", () => {
    const startupBase = graphIdPressureResult({
        callSiteIndexAdmittedGraphs: 64,
        callSiteIndexRetainedBytes: 1024,
        callSiteTrigramIndexedGraphs: 64
    }, "startup-prepared");
    const startupCandidate = graphIdPressureResult({
        callSiteIndexAdmittedGraphs: 64,
        callSiteIndexRetainedBytes: 1024,
        callSiteTrigramIndexedGraphs: 64
    }, "startup-prepared");
    const passed = compareGraphIdPressure(
        [startupBase],
        [startupCandidate],
        graphIdObservations(20_000_000_000, "success", 20_000_000_000),
        graphIdObservations(1_000_000_000, "success", 1_000_000_000)
    );
    assert.equal(passed.passed, true);
    assert.equal(passed.p50Speedup, 20);
    assert.equal(passed.p95Speedup, 20);
    assert.equal(passed.graphParameterP50Speedup, 20);
    assert.equal(passed.graphParameterP95Speedup, 20);
    assert.equal(passed.gateP50Speedup, 20);
    assert.equal(passed.gateP95Speedup, 20);
    assert.equal(passed.resources.candidate.callSiteParallelScanCount, 0);
    assert.equal(passed.resources.candidate.callSiteStringIndexLookupCount, 2043);
    assert.equal(passed.resources.candidate.callSiteScanPeakActiveWorkers, 0);
    assert.deepEqual(passed.graphSetLatencyByWidth.map((summary) => summary.width), [2, 8, 64]);
    assert.deepEqual(passed.graphSetLatencyByWidth.map((summary) => summary.sampleCount), [192, 48, 6]);
    assert.match(renderGraphIdPressureReport(passed), /Effective CPU cores/);
    assert.match(renderGraphIdPressureReport(passed), /Peak used heap/);

    const materiallyRegressed = compareGraphIdPressure(
        [startupBase],
        [startupCandidate],
        graphIdObservations(1_000_000, "success", 1_000_000),
        graphIdObservations(2_000_000, "success", 2_000_000)
    );
    assert.equal(materiallyRegressed.passed, false);
    assert.equal(materiallyRegressed.p50Speedup, 0.5);
    assert.equal(materiallyRegressed.p95Speedup, 0.5);

    const serialCandidate = compareGraphIdPressure(
        [graphIdPressureResult()],
        [graphIdPressureResult({
            callSiteParallelScanCount: 0,
            callSiteParallelScanGraphCount: 0,
            callSiteScanPeakActiveWorkers: 1
        })],
        graphIdObservations(20_000_000_000, "success", 20_000_000_000),
        graphIdObservations(1_000_000_000, "success", 1_000_000_000)
    );
    assert.equal(serialCandidate.passed, false);
    assert.match(serialCandidate.errors.join("\n"), /build one parallel index per graph or restore all 64/);

    const sidecarCandidate = compareGraphIdPressure(
        [graphIdPressureResult()],
        [graphIdPressureResult({
            callSiteIndexAdmittedGraphs: 64,
            callSiteIndexRetainedBytes: 1024,
            callSiteTrigramIndexedGraphs: 64,
            callSiteParallelScanCount: 0,
            callSiteParallelScanGraphCount: 0,
            callSiteScanPeakActiveWorkers: 0
        })],
        graphIdObservations(20_000_000_000, "success", 20_000_000_000),
        graphIdObservations(1_000_000_000, "success", 1_000_000_000)
    );
    assert.equal(sidecarCandidate.passed, true, sidecarCandidate.errors.join("\n"));
});

test("fixture64 cold graphId pressure uses a micro-latency regression guard instead of a 10x target", () => {
    const stable = compareGraphIdPressure(
        [graphIdPressureResult()],
        [graphIdPressureResult()],
        graphIdObservations(1_000_000, "success", 1_000_000),
        graphIdObservations(1_100_000, "success", 1_100_000)
    );
    assert.equal(stable.passed, true, stable.errors.join("\n"));
    assert.ok(stable.p95Speedup < 1);

    const materialRegression = compareGraphIdPressure(
        [graphIdPressureResult()],
        [graphIdPressureResult()],
        graphIdObservations(1_000_000, "success", 1_000_000),
        graphIdObservations(2_000_000, "success", 2_000_000)
    );
    assert.equal(materialRegression.passed, false);

    const base = graphIdObservations(1_000_000, "success", 1_000_000);
    const candidateRows = graphIdObservations(1_000_000, "success", 1_000_000).trim().split("\n");
    const header = candidateRows[0].split("\t");
    const first = candidateRows[1].split("\t");
    first[header.indexOf("latencyNanos")] = "4500000000";
    candidateRows[1] = first.join("\t");
    const hiddenFirstRequestRegression = compareGraphIdPressure(
        [graphIdPressureResult()],
        [graphIdPressureResult()],
        base,
        `${candidateRows.join("\n")}\n`
    );
    assert.equal(hiddenFirstRequestRegression.passed, false);
    assert.match(hiddenFirstRequestRegression.errors.join("\n"), /first K64 request latency regressed/);
});

test("fixture64 scorer rejects detached and correlated-rotation latency rows", () => {
    const base = graphIdObservations(20_000_000_000, "success", 20_000_000_000);
    const candidate = graphIdObservations(1_000_000_000, "success", 1_000_000_000);
    const baseCorrectness = correctnessFromObservations(base);
    const semanticOracle = correctnessFromObservations(candidate);
    const withoutEvidence = compareGraphIdPressureRaw(
        [graphIdPressureResult()], [graphIdPressureResult()], base, candidate
    );
    assert.equal(withoutEvidence.passed, false);
    assert.match(withoutEvidence.errors.join("\n"), /independent correctness evidence is required/);

    const spoofed = candidate.replace(
        "graph-id-property-wrapped-contains-target-00-targeted",
        "spoof-query-target-00-unbound"
    ).replace("\tsuccess\t10\t128\t", "\tsuccess\t42\t999\t");
    const detached = compareGraphIdPressureRaw(
        [graphIdPressureResult()], [graphIdPressureResult()], base, spoofed,
        baseCorrectness, semanticOracle
    );
    assert.equal(detached.passed, false);
    assert.match(detached.errors.join("\n"), /observation ID is not canonical/);

    const rows = candidate.trim().split("\n");
    const rotated = [rows[0], ...rows.slice(1).map((row) => {
        const columns = row.split("\t");
        const targetMatch = columns[0].match(/-target-(\d{2})-/);
        if (targetMatch === null) return row;
        const ordinal = Number(targetMatch[1]);
        const replacement = (ordinal + 1) % 64;
        columns[0] = columns[0].replace(
            `-target-${String(ordinal).padStart(2, "0")}-`,
            `-target-${String(replacement).padStart(2, "0")}-`
        );
        columns[8] = `graph-${replacement}`;
        columns[9] = `graph-${replacement}`;
        columns[11] = crypto.createHash("sha256").update(`graph-${replacement}`).digest("hex");
        return columns.join("\t");
    })].join("\n") + "\n";
    const correlatedRotation = compareGraphIdPressureRaw(
        [graphIdPressureResult()], [graphIdPressureResult()], base, rotated,
        baseCorrectness, semanticOracle
    );
    assert.equal(correlatedRotation.passed, false);
    assert.match(correlatedRotation.errors.join("\n"), /differs from independent correctness record/);
});

test("fixture64 warm pressure proves the trigram path instead of requiring a raw scan", () => {
    const warmBase = graphIdPressureResult({}, "warm");
    const warmCandidate = graphIdPressureResult({
        callSiteIndexAdmittedGraphs: 64,
        callSiteIndexRetainedBytes: 1024,
        callSiteTrigramIndexedGraphs: 64,
        callSiteParallelScanCount: 0
    }, "warm");
    const passed = compareGraphIdPressure(
        [warmBase],
        [warmCandidate],
        graphIdObservations(20_000_000_000, "success", 20_000_000_000),
        graphIdObservations(1_000_000_000, "success", 1_000_000_000)
    );
    assert.equal(passed.passed, true);
    assert.equal(passed.indexState, "warm");
    assert.match(renderGraphIdPressureReport(passed), /Index state: \*\*warm\*\*/);
    assert.match(renderGraphIdPressureReport(passed), /Trigram-indexed graphs: \*\*0 → 64\*\*/);

    const missingTrigram = compareGraphIdPressure(
        [warmBase],
        [graphIdPressureResult({}, "warm")],
        graphIdObservations(20_000_000_000, "success", 20_000_000_000),
        graphIdObservations(1_000_000_000, "success", 1_000_000_000)
    );
    assert.equal(missingTrigram.passed, false);
    assert.match(missingTrigram.errors.join("\n"), /all 64 graphs/);

    const partialWarmCoverage = compareGraphIdPressure(
        [warmBase],
        [graphIdPressureResult({
            callSiteIndexAdmittedGraphs: 63,
            callSiteIndexRetainedBytes: 1024,
            callSiteTrigramIndexedGraphs: 63,
            callSiteParallelScanCount: 0
        }, "warm")],
        graphIdObservations(20_000_000_000, "success", 20_000_000_000),
        graphIdObservations(1_000_000_000, "success", 1_000_000_000)
    );
    assert.equal(partialWarmCoverage.passed, false);
    assert.match(partialWarmCoverage.errors.join("\n"), /admitted=63, trigram=63/);

    const hiddenWarmScan = compareGraphIdPressure(
        [warmBase],
        [graphIdPressureResult({
            callSiteIndexAdmittedGraphs: 64,
            callSiteTrigramIndexedGraphs: 64,
            callSiteParallelScanCount: 1,
            callSiteParallelScanGraphCount: 1,
            callSiteScanPeakActiveWorkers: 8
        }, "warm")],
        graphIdObservations(20_000_000_000, "success", 20_000_000_000),
        graphIdObservations(1_000_000_000, "success", 1_000_000_000)
    );
    assert.equal(hiddenWarmScan.passed, false);
    assert.match(hiddenWarmScan.errors.join("\n"), /must not fall back to raw scans/);

    const partialIndexUse = compareGraphIdPressure(
        [warmBase],
        [graphIdPressureResult({
            callSiteIndexAdmittedGraphs: 64,
            callSiteTrigramIndexedGraphs: 64,
            callSiteStringIndexLookupCount: 2042,
            callSiteStringIndexLookupGraphCount: 63
        }, "warm")],
        graphIdObservations(20_000_000_000, "success", 20_000_000_000),
        graphIdObservations(1_000_000_000, "success", 1_000_000_000)
    );
    assert.equal(partialIndexUse.passed, false);
    assert.match(partialIndexUse.errors.join("\n"), /exactly 2,043 retained-index lookups/);

    const imbalancedColdIndexUse = compareGraphIdPressure(
        [graphIdPressureResult()],
        [graphIdPressureResult({
            callSiteStringIndexLookupMinPerGraph: 1,
            callSiteStringIndexLookupMaxPerGraph: 641
        })],
        graphIdObservations(20_000_000_000, "success", 20_000_000_000),
        graphIdObservations(1_000_000_000, "success", 1_000_000_000)
    );
    assert.equal(imbalancedColdIndexUse.passed, false);
    assert.match(imbalancedColdIndexUse.errors.join("\n"), /perGraph=1\.\.641/);

    const imbalancedWarmIndexUse = compareGraphIdPressure(
        [warmBase],
        [graphIdPressureResult({
            callSiteIndexAdmittedGraphs: 64,
            callSiteTrigramIndexedGraphs: 64,
            callSiteStringIndexLookupMinPerGraph: 1,
            callSiteStringIndexLookupMaxPerGraph: 2044
        }, "warm")],
        graphIdObservations(20_000_000_000, "success", 20_000_000_000),
        graphIdObservations(1_000_000_000, "success", 1_000_000_000)
    );
    assert.equal(imbalancedWarmIndexUse.passed, false);
    assert.match(imbalancedWarmIndexUse.errors.join("\n"), /perGraph=1\.\.2044/);
});

test("fixture64 startup-prepared pressure measures load-time readiness without query warmup", () => {
    const base = graphIdPressureResult({
        callSiteIndexAdmittedGraphs: 64,
        callSiteIndexRetainedBytes: 1024,
        callSiteTrigramIndexedGraphs: 64
    }, "startup-prepared");
    const candidate = graphIdPressureResult({
        callSiteIndexAdmittedGraphs: 64,
        callSiteIndexRetainedBytes: 1024,
        callSiteTrigramIndexedGraphs: 64
    }, "startup-prepared");
    const comparison = compareGraphIdPressure(
        [base],
        [candidate],
        graphIdObservations(20_000_000_000, "success", 20_000_000_000),
        graphIdObservations(1_000_000_000, "success", 1_000_000_000)
    );
    assert.equal(comparison.passed, true);
    assert.equal(comparison.indexState, "startup-prepared");
    assert.match(renderGraphIdPressureReport(comparison), /Index state: \*\*startup-prepared\*\*/);

    const lazyScanRegression = compareGraphIdPressure(
        [graphIdPressureResult({
            callSiteParallelScanCount: 64,
            callSiteParallelScanGraphCount: 64,
            callSiteStringIndexLookupCount: 14139,
            callSiteStringIndexLookupMinPerGraph: 181,
            callSiteStringIndexLookupMaxPerGraph: 266,
            callSiteScanPeakActiveWorkers: 8
        }, "startup-prepared")],
        [candidate],
        graphIdObservations(20_000_000_000, "success", 20_000_000_000),
        graphIdObservations(1_000_000_000, "success", 1_000_000_000)
    );
    assert.equal(lazyScanRegression.passed, false);
    assert.match(lazyScanRegression.errors.join("\n"), /must use the retained index without raw scans/);
});

test("graphId pressure rejects repeated graph paths and failed candidate queries", () => {
    const comparison = compareGraphIdPressure(
        [graphIdPressureResult()],
        [graphIdPressureResult({ distinctGraphPathCount: 4, successCount: 0, failureCount: 1137 })],
        graphIdObservations(20_000_000_000, "success", 20_000_000_000),
        graphIdObservations(1_000_000_000, "failed")
    );
    assert.equal(comparison.passed, false);
    assert.match(comparison.errors.join("\n"), /distinctGraphPathCount=4/);
    assert.match(comparison.errors.join("\n"), /candidate: successCount=0/);
    assert.match(comparison.errors.join("\n"), /successful positive latency samples/);
});

test("graphId pressure hard-gates request-selected source parity and latency", () => {
    const correct = graphIdObservations(1_000_000_000, "success", 1_000_000_000);
    const targetedDigest = crypto.createHash("sha256").update("1-targeted").digest("hex");
    const wrongDigest = correct.replace(targetedDigest, "f".repeat(64));
    const incorrect = compareGraphIdPressure(
        [graphIdPressureResult()],
        [graphIdPressureResult()],
        graphIdObservations(20_000_000_000, "success", 20_000_000_000),
        wrongDigest
    );
    assert.equal(incorrect.passed, false);
    assert.match(incorrect.errors.join("\n"), /differs from the graph-parameter reference/);

    const regressed = compareGraphIdPressure(
        [graphIdPressureResult()],
        [graphIdPressureResult()],
        graphIdObservations(20_000_000_000, "success", 1_000_000_000),
        graphIdObservations(1_000_000_000, "success", 2_000_000_000)
    );
    assert.equal(regressed.passed, false);
    assert.equal(regressed.graphParameterP50Regression, 1);
    assert.equal(regressed.graphParameterP95Regression, 1);
    assert.equal(regressed.graphParameterP50Speedup, 0.5);
    assert.equal(regressed.graphParameterP95Speedup, 0.5);

    const routingOnly = compareGraphIdPressure(
        [graphIdPressureResult()],
        [graphIdPressureResult()],
        graphIdObservations(20_000_000_000, "success", 1_000_000_000),
        graphIdObservations(1_000_000_000, "success", 1_000_000_000)
    );
    assert.equal(routingOnly.passed, true);
    assert.equal(routingOnly.p50Speedup, 20);
    assert.equal(routingOnly.graphParameterP50Speedup, 1);

    const acceptableRequestSelectionRegression = compareGraphIdPressure(
        [graphIdPressureResult()],
        [graphIdPressureResult()],
        graphIdObservations(20_000_000_000, "success", 1_000_000_000),
        graphIdObservations(1_000_000_000, "success", 1_100_000_000)
    );
    assert.equal(acceptableRequestSelectionRegression.passed, true);
    assert.equal(acceptableRequestSelectionRegression.graphParameterP50Regression, 0.10000000000000009);

    const microsecondJitter = compareGraphIdPressure(
        [graphIdPressureResult({
            callSiteIndexAdmittedGraphs: 64,
            callSiteIndexRetainedBytes: 1024,
            callSiteTrigramIndexedGraphs: 64
        }, "startup-prepared")],
        [graphIdPressureResult({
            callSiteIndexAdmittedGraphs: 64,
            callSiteIndexRetainedBytes: 1024,
            callSiteTrigramIndexedGraphs: 64
        }, "startup-prepared")],
        graphIdObservations(20_000_000, "success", 30_000),
        graphIdObservations(1_000_000, "success", 40_000)
    );
    assert.equal(microsecondJitter.passed, true);
    assert.ok(microsecondJitter.graphParameterP50Regression > 0.15);

    const fakeDistribution = compareGraphIdPressure(
        [graphIdPressureResult()],
        [graphIdPressureResult()],
        graphIdObservations(20_000_000_000, "success", 20_000_000_000, true),
        graphIdObservations(1_000_000_000, "success", 1_000_000_000, true)
    );
    assert.equal(fakeDistribution.passed, false);
    assert.match(fakeDistribution.errors.join("\n"), /does not satisfy zero=0, targeted=1\.\.199, dense=200/);
});

test("graphId pressure requires every target graph and all three graphId spellings", () => {
    const complete = graphIdObservations(1_000_000_000, "success", 1_000_000_000);
    const missingSpelling = complete.replaceAll(
        "graph-id-function-wrapped-contains",
        "graph-id-property-wrapped-contains"
    );
    const comparison = compareGraphIdPressure(
        [graphIdPressureResult()],
        [graphIdPressureResult()],
        graphIdObservations(20_000_000_000, "success", 20_000_000_000),
        missingSpelling
    );

    assert.equal(comparison.passed, false);
    assert.match(comparison.errors.join("\n"), /zero\/targeted\/dense coverage is incomplete/);
});

test("graphId set routing proves K-source access and 64-K pruning against its reference", () => {
    const base = graphIdObservations(20_000_000_000, "success", 20_000_000_000);
    const candidate = graphIdObservations(1_000_000_000, "success", 1_000_000_000);
    const mutate = (contents, id, changes) => {
        const lines = contents.trimEnd().split("\n");
        const headers = lines[0].split("\t");
        return `${lines.map((line, index) => {
            if (index === 0) return line;
            const values = line.split("\t");
            if (values[headers.indexOf("id")] !== id) return line;
            for (const [field, value] of Object.entries(changes)) values[headers.indexOf(field)] = value;
            return values.join("\t");
        }).join("\n")}\n`;
    };
    const k8Id = "graph-id-in-literal-wrapped-contains-k08-group-00-targeted";
    const touchedExtraSource = mutate(candidate, k8Id, {
        accessedGraphCount: "9",
        accessedGraphIds: "graph-0,graph-1,graph-2,graph-3,graph-4,graph-5,graph-6,graph-7,graph-8",
        targetGraphAccessCount: "8",
        nonTargetGraphAccessCount: "1"
    });
    const accessFailure = compareGraphIdPressure(
        [graphIdPressureResult()], [graphIdPressureResult()], base, touchedExtraSource
    );
    assert.equal(accessFailure.passed, false);
    assert.match(accessFailure.errors.join("\n"), /selected-graph isolation failed/);

    const k64Id = "graph-id-in-parameter-wrapped-contains-k64-group-00-dense";
    const fakeK64Pruning = mutate(candidate, k64Id, {
        graphIdSourcePruningExecutions: "1",
        graphIdSourcesPruned: "63"
    });
    const pruningFailure = compareGraphIdPressure(
        [graphIdPressureResult()], [graphIdPressureResult()], base, fakeK64Pruning
    );
    assert.equal(pruningFailure.passed, false);
    assert.match(pruningFailure.errors.join("\n"), /do not prove 1\/0\/0/);
});

test("K64 graph-set tolerates sub-0.25ms P50 and sub-1ms P95 single-shot jitter", () => {
    const setK64Latencies = (contents, values) => {
        let index = 0;
        const lines = contents.trimEnd().split("\n");
        const headers = lines[0].split("\t");
        const idColumn = headers.indexOf("id");
        const latencyColumn = headers.indexOf("latencyNanos");
        return `${lines.map((line, lineIndex) => {
            if (lineIndex === 0) return line;
            const fields = line.split("\t");
            if (fields[idColumn].startsWith("graph-id-in-") && fields[idColumn].includes("-k64-")) {
                fields[latencyColumn] = String(values[index++]);
            }
            return fields.join("\t");
        }).join("\n")}\n`;
    };
    const base = setK64Latencies(
        graphIdObservations(20_000_000, "success", 1_000_000),
        [167_000, 167_000, 167_000, 167_000, 1_000_000, 1_000_000]
    );
    const candidate = setK64Latencies(
        graphIdObservations(1_000_000, "success", 1_000_000),
        [358_000, 358_000, 358_000, 358_000, 1_800_000, 1_800_000]
    );
    const comparison = compareGraphIdPressure(
        [graphIdPressureResult()], [graphIdPressureResult()], base, candidate
    );
    assert.equal(comparison.passed, true, comparison.errors.join("\n"));
    const k64 = comparison.graphSetLatencyByWidth.find((summary) => summary.width === 64);
    assert.equal(k64.baseP50, 167_000);
    assert.equal(k64.candidateP50, 358_000);
    assert.equal(k64.baseP95, 1_000_000);
    assert.equal(k64.candidateP95, 1_800_000);
});

test("graph-routing oracle is derived only from complete successful base single-source references", () => {
    const references = graphParameterReferenceManifest();
    const derived = deriveGraphRoutingOracle(references);
    assert.equal(derived.passed, true);
    assert.equal(derived.records.length, 1137);
    assert.equal(new Set(derived.records.map((record) => record.id)).size, 1137);

    const referenceBySlot = new Map(derived.records
        .filter((record) => record.family === "graph-parameter")
        .map((record) => [`${record.id.match(/target-(\d{2})-/)[1]}\0${record.selectivity}`, record]));
    for (const record of derived.records.filter((item) => item.family === "graph-id")) {
        const target = record.id.match(/target-(\d{2})-/)[1];
        const reference = referenceBySlot.get(`${target}\0${record.selectivity}`);
        for (const field of ["outcome", "rowCount", "responseBytes", "digest"]) {
            assert.equal(record[field], reference[field], `${record.id} ${field}`);
        }
    }
    const graphSetReferences = new Map(derived.records
        .filter((record) => record.family === "graph-set-reference")
        .map((record) => [record.id.replace("request-selected-set-wrapped-contains-", ""), record]));
    for (const record of derived.records.filter((item) => item.family === "graph-id-set")) {
        const slot = record.id.replace(`${record.shape}-`, "");
        const reference = graphSetReferences.get(slot);
        assert.ok(reference, `${record.id} reference`);
        for (const field of ["outcome", "rowCount", "responseBytes", "digest", "workloadIdentity"]) {
            assert.equal(record[field], reference[field], `${record.id} ${field}`);
        }
    }

    const missing = deriveGraphRoutingOracle(references.split("\n").slice(1).join("\n"));
    assert.equal(missing.passed, false);
    assert.match(missing.errors.join("\n"), /expected 192 single-source|missing target-00-zero/);

    const duplicateLine = references.split("\n")[0];
    const duplicate = deriveGraphRoutingOracle(`${references}${duplicateLine}\n`);
    assert.equal(duplicate.passed, false);
    assert.match(duplicate.errors.join("\n"), /duplicate correctness id|duplicate target\/selectivity/);

    const failed = deriveGraphRoutingOracle(references.replace("|success|0|", "|failed|0|"));
    assert.equal(failed.passed, false);
    assert.match(failed.errors.join("\n"), /outcome=failed/);

    const wrongBandRecords = references.trimEnd().split("\n");
    const targetedIndex = wrongBandRecords.findIndex((line) => line.split("|")[3] === "targeted");
    const targetedFields = wrongBandRecords[targetedIndex].split("|");
    targetedFields[11] = "200";
    wrongBandRecords[targetedIndex] = targetedFields.join("|");
    const wrongBand = deriveGraphRoutingOracle(`${wrongBandRecords.join("\n")}\n`);
    assert.equal(wrongBand.passed, false);
    assert.match(wrongBand.errors.join("\n"), /targeted=1\.\.199/);
});

test("comparator CLI runs when invoked through a symlinked path", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "graphite-gate-cli-"));
    const alias = path.join(directory, "benchmark-gate.mjs");
    fs.symlinkSync(new URL("./benchmark-gate.mjs", import.meta.url), alias);
    const result = spawnSync(process.execPath, [alias, "unknown-command"], { encoding: "utf8" });

    assert.equal(result.status, 1);
    assert.match(result.stderr, /Unknown command: unknown-command/);
});

test("immutable Gist materialization fetches complete truncated evidence", async () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "graphite-gist-evidence-"));
    const gistId = "a".repeat(32);
    const revision = "b".repeat(40);
    const requested = [];
    const contents = await materializeGistFiles({
        gistId,
        owner: "johnsonlee",
        directory,
        files: {
            "inline.txt": { truncated: false, content: "inline\n" },
            "large.tsv": {
                truncated: true,
                raw_url: `https://gist.githubusercontent.com/johnsonlee/${gistId}/raw/${revision}/large.tsv`
            }
        },
        request: async options => {
            requested.push(options);
            return { data: "complete-large-content\n" };
        }
    });
    assert.equal(contents["inline.txt"], "inline\n");
    assert.equal(contents["large.tsv"], "complete-large-content\n");
    assert.equal(fs.readFileSync(path.join(directory, "large.tsv"), "utf8"), "complete-large-content\n");
    assert.equal(requested.length, 1);
    assert.equal(requested[0].headers.accept, "application/vnd.github.raw");
    assert.match(requested[0].url, new RegExp(`/raw/${revision}/large\\.tsv$`));
    fs.rmSync(directory, { recursive: true });
});

test("immutable Gist materialization rejects an unbound truncated URL", async () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "graphite-gist-evidence-invalid-"));
    await assert.rejects(materializeGistFiles({
        gistId: "a".repeat(32),
        owner: "johnsonlee",
        directory,
        files: {
            "large.tsv": {
                truncated: true,
                raw_url: "https://example.com/mutable/large.tsv"
            }
        },
        request: async () => ({ data: "untrusted" })
    }), /invalid immutable raw URL/);
    fs.rmSync(directory, { recursive: true });
});

test("fixture workload verifier binds every result to all 64 regenerated JAR shards", () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "graphite-fixture-workload-"));
    const evidence = path.join(root, "evidence");
    const recomputed = path.join(root, "recomputed");
    fs.mkdirSync(evidence);
    fs.mkdirSync(recomputed);
    const header = [
        "graphId", "corpus", "shard", "sourceJar", "sourceJarSha256", "shardBytecodeSha256",
        "classCount", "nodeCount", "callSiteCount", "zeroTerm", "targetedTerm", "denseTerm",
        "querySemanticSha256", "resourceCount", "resourceSemanticSha256", "workloadIdentity",
        "callSiteIndexBytes", "callSiteIndexSha256", "graphPath"
    ].join("\t");
    const provenanceRows = [];
    const manifestRows = ["# fixture64"];
    const observationHeader =
        "id\tfamily\tshape\tselectivity\toperator\tboundary\tprojection\tlimit\t" +
        "targetGraphId\tworkloadIdentity\toutcome\trowCount\tresponseBytes\tdigest\tlatencyNanos";
    const observationRows = [observationHeader];
    const referenceObservationRows = [observationHeader];
    const correctnessRows = [];
    const referenceCorrectnessRows = [];
    const queryShapes = [
        ["graph-id", "graph-id-property-wrapped-contains", "graph-id-equals-and-wrapped-contains", "graph-routing"],
        ["graph-id", "graph-id-function-wrapped-contains", "graph-id-function-equals-and-wrapped-contains", "graph-routing"],
        ["graph-id", "graph-id-parameter-wrapped-contains", "graph-id-parameter-equals-and-wrapped-contains", "parameters"],
        ["graph-parameter", "request-selected-source-wrapped-contains",
            "request-graph-selection-and-wrapped-contains", "request-selected-source"]
    ];
    const selectivities = ["zero", "targeted", "dense"];
    for (let graphIndex = 0; graphIndex < 64; graphIndex++) {
        const graphId = `fixture-jar-${String(graphIndex).padStart(2, "0")}`;
        const workloadIdentity = crypto.createHash("sha256").update(graphId).digest("hex");
        provenanceRows.push([
            graphId, `jar-${Math.floor(graphIndex / 16)}`, String(graphIndex % 16), "fixture.jar",
            "a".repeat(64), "b".repeat(64), "10", "100", "20", "absent", "target", "dense",
            "c".repeat(64), "2", "d".repeat(64), workloadIdentity,
            "123", "e".repeat(64), `/tmp/${graphId}.graph`
        ].join("\t"));
        manifestRows.push([
            graphId, `/tmp/${graphId}.graph`, "absent", "target", "dense", workloadIdentity
        ].join("\t"));
        for (const [family, shape, operator, boundary] of queryShapes) {
            for (const selectivity of selectivities) {
                const id = `${shape}-target-${String(graphIndex).padStart(2, "0")}-${selectivity}`;
                const rowCount = selectivity === "zero" ? "0" : selectivity === "targeted" ? "10" : "200";
                const responseBytes = rowCount === "0" ? "64" : "128";
                const digest = crypto.createHash("sha256")
                    .update(`${graphIndex}-${selectivity}`).digest("hex");
                const observation = [
                    id, family, shape, selectivity, operator, boundary, "properties", "200",
                    graphId, workloadIdentity, "success", rowCount, responseBytes, digest, "1000000"
                ].join("\t");
                const correctness = [
                    id, family, shape, selectivity, operator, boundary, "properties", graphId,
                    workloadIdentity, "200", "success", rowCount, responseBytes, digest
                ].join("|");
                observationRows.push(observation);
                correctnessRows.push(correctness);
                if (family === "graph-parameter") {
                    referenceObservationRows.push(observation);
                    referenceCorrectnessRows.push(correctness);
                }
            }
        }
    }
    const provenance = `${header}\n${provenanceRows.join("\n")}\n`;
    const manifest = `${manifestRows.join("\n")}\n`;
    const referenceObservations = path.join(evidence, "base-single-source-reference.tsv");
    const referenceCorrectness = path.join(evidence, "base-single-source-reference.manifest");
    const semanticOracle = path.join(evidence, "base-single-source-oracle.manifest");
    const baseColdObservations = path.join(evidence, "base-graph-routing-cold.tsv");
    const baseColdCorrectness = path.join(evidence, "base-graph-routing-cold.correctness");
    const candidateColdObservations = path.join(evidence, "candidate-graph-routing-cold.tsv");
    const candidateColdCorrectness = path.join(evidence, "candidate-graph-routing-cold.correctness");
    const baseWarmObservations = path.join(evidence, "base-graph-routing-warm.tsv");
    const baseWarmCorrectness = path.join(evidence, "base-graph-routing-warm.correctness");
    const candidateWarmObservations = path.join(evidence, "candidate-graph-routing-warm.tsv");
    const candidateWarmCorrectness = path.join(evidence, "candidate-graph-routing-warm.correctness");
    for (const directory of [evidence, recomputed]) {
        fs.writeFileSync(path.join(directory, "fixture-provenance.tsv"), provenance);
        fs.writeFileSync(path.join(directory, "graphs.tsv"), manifest);
    }
    fs.writeFileSync(referenceObservations, `${referenceObservationRows.join("\n")}\n`);
    fs.writeFileSync(referenceCorrectness, `${referenceCorrectnessRows.join("\n")}\n`);
    fs.writeFileSync(semanticOracle, `${correctnessRows.join("\n")}\n`);
    for (const file of [baseColdObservations, candidateColdObservations,
        baseWarmObservations, candidateWarmObservations]) {
        fs.writeFileSync(file, `${observationRows.join("\n")}\n`);
    }
    for (const file of [baseColdCorrectness, candidateColdCorrectness,
        baseWarmCorrectness, candidateWarmCorrectness]) {
        fs.writeFileSync(file, `${correctnessRows.join("\n")}\n`);
    }
    const verifier = new URL("./verify-fixture64-workload.sh", import.meta.url);
    const verify = () => spawnSync(
        "bash",
        [verifier.pathname, evidence, recomputed,
            referenceObservations, referenceCorrectness, semanticOracle,
            baseColdObservations, baseColdCorrectness,
            candidateColdObservations, candidateColdCorrectness,
            baseWarmObservations, baseWarmCorrectness,
            candidateWarmObservations, candidateWarmCorrectness],
        { encoding: "utf8" }
    );
    const initialVerification = verify();
    assert.equal(
        initialVerification.status,
        0,
        `${root}\n${initialVerification.stdout}\n${initialVerification.stderr}`
    );
    fs.writeFileSync(
        path.join(evidence, "fixture-provenance.tsv"),
        provenance.replace("\t100\t20\t", "\t1\t20\t")
    );
    assert.notEqual(verify().status, 0);
    fs.writeFileSync(path.join(evidence, "fixture-provenance.tsv"), provenance);
    fs.writeFileSync(path.join(evidence, "graphs.tsv"), manifest.replace("\ttarget\t", "\tmutated\t"));
    assert.notEqual(verify().status, 0);
    fs.writeFileSync(path.join(evidence, "graphs.tsv"), manifest);
    const cyclicObservationRows = observationRows.map((row, rowIndex) => {
        if (rowIndex === 0) return row;
        const columns = row.split("\t");
        const ordinal = Number(columns[0].match(/-target-(\d{2})-/)[1]);
        const replacement = (ordinal + 1) % 64;
        columns[0] = columns[0].replace(
            `-target-${String(ordinal).padStart(2, "0")}-`,
            `-target-${String(replacement).padStart(2, "0")}-`
        );
        columns[8] = `fixture-jar-${String(replacement).padStart(2, "0")}`;
        columns[9] = provenanceRows[replacement].split("\t")[15];
        return columns.join("\t");
    });
    const cyclicCorrectnessRows = correctnessRows.map((row) => {
        const columns = row.split("|");
        const ordinal = Number(columns[0].match(/-target-(\d{2})-/)[1]);
        const replacement = (ordinal + 1) % 64;
        columns[0] = columns[0].replace(
            `-target-${String(ordinal).padStart(2, "0")}-`,
            `-target-${String(replacement).padStart(2, "0")}-`
        );
        columns[7] = `fixture-jar-${String(replacement).padStart(2, "0")}`;
        columns[8] = provenanceRows[replacement].split("\t")[15];
        return columns.join("|");
    });
    fs.writeFileSync(candidateColdObservations, `${cyclicObservationRows.join("\n")}\n`);
    fs.writeFileSync(candidateColdCorrectness, `${cyclicCorrectnessRows.join("\n")}\n`);
    assert.notEqual(verify().status, 0);
    fs.writeFileSync(candidateColdObservations, `${observationRows.join("\n")}\n`);
    fs.writeFileSync(candidateColdCorrectness, `${correctnessRows.join("\n")}\n`);
    fs.writeFileSync(candidateColdObservations, `${observationRows.join("\n")}\n`.replace(
        "graph-id-property-wrapped-contains-target-00-targeted",
        "spoof-query-target-00-unbound"
    ).replace("\tsuccess\t10\t128\t", "\tsuccess\t42\t999\t"));
    assert.notEqual(verify().status, 0);
    fs.writeFileSync(candidateColdObservations, `${observationRows.join("\n")}\n`);
    const mutatedObservationRows = observationRows.map((row, index) => index === 2
        ? row.replace("\tsuccess\t10\t128\t", "\tsuccess\t42\t999\t")
        : row);
    const mutatedCorrectnessRows = correctnessRows.map((row, index) => index === 1
        ? row.replace("|success|10|128|", "|success|42|999|")
        : row);
    fs.writeFileSync(candidateColdObservations, `${mutatedObservationRows.join("\n")}\n`);
    fs.writeFileSync(candidateColdCorrectness, `${mutatedCorrectnessRows.join("\n")}\n`);
    assert.notEqual(verify().status, 0);
    fs.rmSync(root, { recursive: true });
});

test("fixture workload verifier rejects unpruned graph-set fanout on base and candidate", () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "graphite-fixture-routing-role-"));
    const evidence = path.join(root, "evidence");
    const recomputed = path.join(root, "recomputed");
    fs.mkdirSync(evidence);
    fs.mkdirSync(recomputed);
    const graphIds = Array.from({ length: 64 }, (_, index) => `graph-${index}`);
    const provenanceHeader = [
        "graphId", "corpus", "shard", "sourceJar", "sourceJarSha256", "shardBytecodeSha256",
        "classCount", "nodeCount", "callSiteCount", "zeroTerm", "targetedTerm", "denseTerm",
        "querySemanticSha256", "resourceCount", "resourceSemanticSha256", "workloadIdentity",
        "callSiteIndexBytes", "callSiteIndexSha256", "graphPath"
    ].join("\t");
    const provenance = `${provenanceHeader}\n${graphIds.map((graphId, index) => [
        graphId, `jar-${Math.floor(index / 16)}`, String(index % 16), "fixture.jar",
        "a".repeat(64), "b".repeat(64), "10", "100", "20", "absent", "target", "dense",
        "c".repeat(64), "2", "d".repeat(64),
        crypto.createHash("sha256").update(graphId).digest("hex"),
        "123", "e".repeat(64), `/tmp/${graphId}.graph`
    ].join("\t")).join("\n")}\n`;
    const manifest = `# fixture64\n${graphIds.map((graphId) => [
        graphId, `/tmp/${graphId}.graph`, "absent", "target", "dense",
        crypto.createHash("sha256").update(graphId).digest("hex")
    ].join("\t")).join("\n")}\n`;
    for (const directory of [evidence, recomputed]) {
        fs.writeFileSync(path.join(directory, "fixture-provenance.tsv"), provenance);
        fs.writeFileSync(path.join(directory, "graphs.tsv"), manifest);
    }

    const strictObservations = graphIdObservations(1_000_000);
    const [observationHeader, ...strictRows] = strictObservations.trimEnd().split("\n");
    const fields = observationHeader.split("\t");
    const familyIndex = fields.indexOf("family");
    const selectivityIndex = fields.indexOf("selectivity");
    const selectedCountIndex = fields.indexOf("selectedGraphCount");
    const accessedCountIndex = fields.indexOf("accessedGraphCount");
    const accessedIdsIndex = fields.indexOf("accessedGraphIds");
    const targetAccessCountIndex = fields.indexOf("targetGraphAccessCount");
    const nonTargetAccessCountIndex = fields.indexOf("nonTargetGraphAccessCount");
    const legacyBaseRows = strictRows.map((row) => {
        const values = row.split("\t");
        if (values[familyIndex] === "graph-id-set") {
            const width = Number(values[selectedCountIndex]);
            const targetOrdinal = graphIds.indexOf(values[fields.indexOf("targetGraphId")]);
            const dense = values[selectivityIndex] === "dense";
            const accessedGraphIds = dense ? graphIds.slice(0, targetOrdinal + 1) : graphIds;
            values[accessedCountIndex] = String(accessedGraphIds.length);
            values[accessedIdsIndex] = accessedGraphIds.join(",");
            values[targetAccessCountIndex] = dense ? "1" : String(width);
            values[nonTargetAccessCountIndex] = dense ? String(targetOrdinal) : String(64 - width);
        }
        return values.join("\t");
    });
    const legacyBaseObservations = `${observationHeader}\n${legacyBaseRows.join("\n")}\n`;
    const referenceRows = strictRows.filter((row) => {
        const family = row.split("\t")[familyIndex];
        return family === "graph-parameter" || family === "graph-set-reference";
    });
    const referenceObservationsContents = `${observationHeader}\n${referenceRows.join("\n")}\n`;

    const files = Object.fromEntries([
        "reference-observations", "reference-correctness", "semantic-oracle",
        "base-cold-observations", "base-cold-correctness",
        "candidate-cold-observations", "candidate-cold-correctness",
        "base-warm-observations", "base-warm-correctness",
        "candidate-warm-observations", "candidate-warm-correctness"
    ].map((name) => [name, path.join(evidence, name)]));
    fs.writeFileSync(files["reference-observations"], referenceObservationsContents);
    fs.writeFileSync(files["reference-correctness"], correctnessFromObservations(referenceObservationsContents));
    fs.writeFileSync(files["semantic-oracle"], correctnessFromObservations(strictObservations));
    for (const state of ["cold", "warm"]) {
        fs.writeFileSync(files[`base-${state}-observations`], strictObservations);
        fs.writeFileSync(files[`base-${state}-correctness`], correctnessFromObservations(strictObservations));
        fs.writeFileSync(files[`candidate-${state}-observations`], strictObservations);
        fs.writeFileSync(files[`candidate-${state}-correctness`], correctnessFromObservations(strictObservations));
    }
    const verifier = new URL("./verify-fixture64-workload.sh", import.meta.url);
    const verify = () => spawnSync("bash", [
        verifier.pathname, evidence, recomputed,
        files["reference-observations"], files["reference-correctness"], files["semantic-oracle"],
        files["base-cold-observations"], files["base-cold-correctness"],
        files["candidate-cold-observations"], files["candidate-cold-correctness"],
        files["base-warm-observations"], files["base-warm-correctness"],
        files["candidate-warm-observations"], files["candidate-warm-correctness"]
    ], { encoding: "utf8" });
    const accepted = verify();
    assert.equal(accepted.status, 0, `${accepted.stdout}\n${accepted.stderr}`);

    const referenceWithoutK64ZeroAccess = referenceRows.map((row) => {
        const values = row.split("\t");
        if (values[familyIndex] === "graph-set-reference" &&
            Number(values[selectedCountIndex]) === 64 && values[selectivityIndex] === "zero") {
            values[accessedCountIndex] = "0";
            values[accessedIdsIndex] = "";
            values[targetAccessCountIndex] = "0";
            values[nonTargetAccessCountIndex] = "0";
        }
        return values.join("\t");
    });
    const uninstrumentedReference = `${observationHeader}\n${referenceWithoutK64ZeroAccess.join("\n")}\n`;
    fs.writeFileSync(files["reference-observations"], uninstrumentedReference);
    fs.writeFileSync(files["reference-correctness"], correctnessFromObservations(uninstrumentedReference));
    const acceptedReferenceGap = verify();
    assert.equal(
        acceptedReferenceGap.status,
        0,
        `${acceptedReferenceGap.stdout}\n${acceptedReferenceGap.stderr}`
    );

    const withoutK64ZeroAccess = (family) => strictRows.map((row) => {
        const values = row.split("\t");
        if (values[familyIndex] === family &&
            Number(values[selectedCountIndex]) === 64 && values[selectivityIndex] === "zero") {
            values[accessedCountIndex] = "0";
            values[accessedIdsIndex] = "";
            values[targetAccessCountIndex] = "0";
            values[nonTargetAccessCountIndex] = "0";
        }
        return values.join("\t");
    });
    const uninstrumentedBase = `${observationHeader}\n${
        withoutK64ZeroAccess("graph-set-reference").join("\n")
    }\n`;
    fs.writeFileSync(files["base-cold-observations"], uninstrumentedBase);
    const acceptedBaseGap = verify();
    assert.equal(acceptedBaseGap.status, 0, `${acceptedBaseGap.stdout}\n${acceptedBaseGap.stderr}`);
    fs.writeFileSync(files["base-cold-observations"], strictObservations);

    const candidateWithoutK64ZeroAccess = withoutK64ZeroAccess("graph-id-set");
    fs.writeFileSync(
        files["candidate-cold-observations"],
        `${observationHeader}\n${candidateWithoutK64ZeroAccess.join("\n")}\n`
    );
    assert.notEqual(verify().status, 0);
    fs.writeFileSync(files["candidate-cold-observations"], strictObservations);

    const candidateReferenceWithoutK64ZeroAccess = withoutK64ZeroAccess("graph-set-reference");
    fs.writeFileSync(
        files["candidate-cold-observations"],
        `${observationHeader}\n${candidateReferenceWithoutK64ZeroAccess.join("\n")}\n`
    );
    assert.notEqual(verify().status, 0);
    fs.writeFileSync(files["candidate-cold-observations"], strictObservations);

    fs.writeFileSync(files["base-cold-observations"], legacyBaseObservations);
    assert.notEqual(verify().status, 0);
    fs.writeFileSync(files["base-cold-observations"], strictObservations);
    fs.writeFileSync(files["candidate-cold-observations"], legacyBaseObservations);
    assert.notEqual(verify().status, 0);
    fs.rmSync(root, { recursive: true });
});

test("canonical JAR hash accepts overlapping duplicate fat-JAR entries", () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "graphite-overlapping-jar-"));
    const archivePath = path.join(root, "overlapping.jar");
    const created = spawnSync("python3", [
        "-c",
        "import sys,zipfile; z=zipfile.ZipFile(sys.argv[1],'w'); " +
            "z.writestr('META-INF/LICENSE.txt',b'same'); " +
            "z.writestr('META-INF/LICENSE.txt',b'same'); z.close()",
        archivePath
    ], { encoding: "utf8" });
    assert.equal(created.status, 0, created.stderr);
    const bytes = fs.readFileSync(archivePath);
    const centralSignature = Buffer.from([0x50, 0x4b, 0x01, 0x02]);
    const centralOffsets = [];
    for (let offset = bytes.indexOf(centralSignature); offset >= 0;
        offset = bytes.indexOf(centralSignature, offset + 1)) {
        centralOffsets.push(offset);
    }
    assert.equal(centralOffsets.length, 2);
    bytes.writeUInt32LE(bytes.readUInt32LE(centralOffsets[0] + 42), centralOffsets[1] + 42);
    fs.writeFileSync(archivePath, bytes);

    const hasher = new URL("./canonical-zip-sha256.py", import.meta.url);
    const result = spawnSync("python3", [hasher.pathname, archivePath], { encoding: "utf8" });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout.trim(), /^[0-9a-f]{64}$/);
    fs.rmSync(root, { recursive: true });
});

test("fixture64 driver builds commit-bound JARs and records fixture provenance", () => {
    const driver = fs.readFileSync(
        new URL("./run-real64-graph-routing.sh", import.meta.url),
        "utf8"
    );
    const harness = fs.readFileSync(
        new URL(
            "../../graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/" +
                "LargeBroadQueryPressureBenchmark.kt",
            import.meta.url
        ),
        "utf8"
    );

    assert.doesNotMatch(driver, /<base-jmh\.jar>|<candidate-jmh\.jar>/);
    assert.doesNotMatch(driver, /<reviewed-oracle>|ORACLE=\$2/);
    assert.equal((driver.match(/git clone --no-checkout/g) ?? []).length, 2);
    assert.match(driver, /checkout --detach "\$\{BASE_SHA\}"/);
    assert.match(driver, /checkout --detach "\$\{CANDIDATE_SHA\}"/);
    assert.match(driver, /cmp -s "\$0" "\$\{CANDIDATE_TREE\}\/\$\{SCRIPT_PATH\}"/);
    assert.match(
        driver,
        /cp "\$\{CANDIDATE_TREE\}\/\$\{HARNESS_PATH\}" "\$\{BASE_TREE\}\/\$\{HARNESS_PATH\}"/
    );
    assert.match(
        driver,
        /cp "\$\{CANDIDATE_TREE\}\/\$\{CORRECTNESS_MANIFEST_PATH\}" "\$\{BASE_TREE\}\/\$\{CORRECTNESS_MANIFEST_PATH\}"/
    );
    assert.match(driver, /if ! cmp -s[\s\S]*BASE_TREE[\s\S]*CANDIDATE_TREE/);
    assert.match(
        driver,
        /git -C "\$\{BASE_TREE\}" diff --name-only \| sort\)" = "\$\{EXPECTED_BASE_INSTRUMENTATION\}"/
    );
    assert.equal((driver.match(/:webgraph:jmhJar/g) ?? []).length, 2);
    assert.equal((driver.match(/-Xmx8g/g) ?? []).length, 2);
    assert.equal((driver.match(/-to 30m/g) ?? []).length, 2);
    assert.match(driver, /-jvmArgs "-Xmx8g /);
    assert.doesNotMatch(driver, /-jvmArgsAppend/);
    for (const field of [
        "baseSha",
        "candidateSha",
        "harnessSha256",
        "correctnessManifestSha256",
        "fixtureVerifierSha256",
        "comparatorSha256",
        "scriptSha256",
        "reproducibilityScriptSha256",
        "zipHasherSha256",
        "gistEvidenceSha256",
        "fixturePreparationScriptSha256",
        "workloadVerifierSha256",
        "baseJarContentSha256",
        "candidateJarContentSha256",
        "manifestSha256",
        "fixtureProvenanceSha256",
        "oracleSha256",
        "oracleSource",
    ]) {
        assert.match(driver, new RegExp(`--arg ${field} `));
    }
    assert.match(driver, /> "\$\{OUTPUT_DIR\}\/provenance\.json"/);
    assert.match(driver, /coverageFamily=graph-routing-reference/);
    assert.match(harness, /graphite\.webgraph\.prepareCallSiteStringIndexOnLoad/);
    assert.match(harness, /indexState == STARTUP_PREPARED_INDEX_STATE/);
    assert.match(harness, /else LAZY_INDEX_PREPARATION_MODE/);
    assert.match(harness, /System\.clearProperty\(PREPARE_INDEX_ON_LOAD_PROPERTY\)/);
    assert.equal((driver.match(/for INDEX_STATE in cold warm startup-prepared/g) ?? []).length, 2);
    assert.match(driver, /graphite-fixture64-evidence-v8/);
    assert.match(driver, /startup=%.2f\/%.2fx/);
    assert.match(driver, /derive-graph-routing-oracle/);
    assert.match(driver, /ORACLE=\$\{OUTPUT_DIR\}\/base-single-source-oracle\.manifest/);
    assert.match(driver, /Fixture64GraphPreparation/);
    assert.match(driver, /--verify "\$\{MANIFEST\}" "\$\{FIXTURE_PROVENANCE\}"/);
    assert.match(driver, /test -f "\$\{GRAPH_PATH\}\/graph\.callsite-string-index"/);
    assert.match(driver, /:webgraph:prepareBenchmarkFixtures/);
    assert.match(driver, /cmp -s "\$\{SUPPLIED_JAR\}" "\$\{PINNED_JAR\}"/);
    assert.match(driver, /test-fixture64-reproducibility\.sh/);
    assert.match(driver, /gh gist create --public/);
    assert.match(driver, /\/revisions\/\$\{GIST_REVISION\}/);
    assert.match(driver, /-f target_url="\$\{EVIDENCE_URL\}"/);
});

test("fixture64 preparation partitions pinned real JARs into 64 verified graph shards", () => {
    const script = fs.readFileSync(new URL("./prepare-fixture64-graphs.sh", import.meta.url), "utf8");
    const reproducibility = fs.readFileSync(
        new URL("./test-fixture64-reproducibility.sh", import.meta.url),
        "utf8"
    );
    const source = fs.readFileSync(
        new URL(
            "../../graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/" +
                "Fixture64GraphPreparation.kt",
            import.meta.url
        ),
        "utf8"
    );

    for (const fixture of ["android-all", "tika-app", "hive-exec", "kotlin-compiler-embeddable"]) {
        assert.match(script, new RegExp(fixture));
    }
    assert.match(source, /SHARDS_PER_CORPUS = 16/);
    assert.match(source, /FIXTURE_GRAPH_COUNT = 64/);
    assert.match(source, /entry\.name\.endsWith\(CLASS_SUFFIX\)/);
    assert.match(source, /verifyMappedGraph\(\s*persistedPath/);
    assert.match(source, /graphFingerprints\.add\(querySemanticSha256\)/);
    assert.match(source, /sourceJarSha256/);
    assert.match(source, /shardBytecodeSha256/);
    assert.match(source, /querySemanticSha256/);
    assert.match(source, /resourceSemanticSha256/);
    assert.match(source, /workloadIdentitySha256/);
    assert.match(source, /resourceSemanticSummary\(graph\.resources\)/);
    assert.match(source, /verifyPreparedCorpus\(output\.resolve\(MANIFEST_FILE\)/);
    assert.match(source, /duplicates query-semantic graph content/);
    assert.match(source, /Synthetic nodes are never used/);
    assert.equal((reproducibility.match(/prepare-fixture64-graphs\.sh/g) ?? []).length, 1);
    assert.match(reproducibility, /if \[\[ ! -e "\$\{SECOND_OUTPUT\}" \]\]/);
    assert.match(reproducibility, /relocate_output "\$\{FIRST_OUTPUT\}"/);
    assert.match(reproducibility, /relocate_output "\$\{SECOND_OUTPUT\}"/);
    assert.match(reproducibility, /for OUTPUT in "\$\{FIRST_OUTPUT\}" "\$\{SECOND_OUTPUT\}"/);
    assert.match(reproducibility, /cut -f1-18/);
    assert.match(reproducibility, /--self-test-order-fingerprint/);
    assert.match(reproducibility, /fixture-reproducibility\.json|RECEIPT/);
    assert.match(reproducibility, /TAMPERED_PROVENANCE/);
    assert.match(reproducibility, /unexpectedly passed verification/);
    assert.match(reproducibility, /Substituted fixture JAR unexpectedly passed verification/);
    assert.match(reproducibility, /Missing fixture64 graph\.resources unexpectedly passed verification/);
    assert.match(reproducibility, /Corrupt fixture64 graph\.resources unexpectedly passed verification/);
    assert.match(reproducibility, /Content-tampered fixture64 graph\.resources unexpectedly passed verification/);
    assert.match(
        reproducibility,
        /Corrupt fixture64 graph\.callsite-string-index unexpectedly passed verification/
    );
    assert.match(source, /callSiteIndexSha256/);
    assert.match(source, /isCallSiteStringIndexLoadedFromPersistence\(\)/);
    assert.match(source, /deriveGlobalWideDistributions\(manifestRows\)/);
    assert.match(source, /localized\(LOCALIZED_EARLY_DISTRIBUTION, 0\)/);
    assert.match(source, /localized\(LOCALIZED_MIDDLE_DISTRIBUTION/);
    assert.match(source, /localized\(LOCALIZED_LATE_DISTRIBUTION/);
    assert.match(source, /broadHits == rows\.map\(FixtureManifestRow::graphId\)/);
    assert.match(source, /recordedDistributions == deriveGlobalWideDistributions\(manifestRows\)/);
});

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

function resourceResult({ overrides = {}, jvmArgs } = {}) {
    const maxHeapBytes = 8 * 1024 ** 3;
    return jmhResult({
        benchmark: LATENCY_RESOURCE_EXPECTED_BENCHMARK_KEYS[0],
        mode: "ss",
        score: 1,
        confidence: [0.9, 1.1],
        unit: "ms/op",
        jvmArgs: jvmArgs ?? ["-Xmx8g"],
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

test("latency expected keys contain only real persisted graph coverage", () => {
    assert.ok(LATENCY_EXPECTED_BENCHMARK_KEYS.every((key) => !key.includes("graphCount=")));
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

test("latency anchor confirms point regressions despite overlapping confidence intervals", () => {
    const common = { unit: "ms/op", confidence: [1, 30] };
    const comparison = compareLatencyAnchor(
        [jmhResult({ ...common, score: 10 })],
        [jmhResult({ ...common, score: 10 })],
        [jmhResult({ ...common, score: 12 })]
    );

    assert.equal(comparison.rows[0].confidenceSeparated, false);
    assert.equal(comparison.rows[0].aboveThreshold, true);
    assert.equal(comparison.rows[0].blocked, true);
    assert.equal(comparison.passed, false);
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

test("resource gate accepts the 8 GiB real persisted graph profile", () => {
    const base = [resourceResult()];
    const candidate = [resourceResult()];

    assert.equal(compareLatencyResources(base, candidate).passed, true);
});

test("resource gate reads per-invocation gauges instead of summed AuxCounters scores", () => {
    const base = [resourceResult()];
    const candidate = [resourceResult()];

    assert.equal(candidate[0].secondaryMetrics.maxHeapBytes.score, 24 * 1024 ** 3);
    assert.equal(compareLatencyResources(base, candidate).passed, true);
});

test("resource gate fails closed on the wrong real-graph max heap", () => {
    const base = [resourceResult()];
    const candidate = [resourceResult({ jvmArgs: ["-Xmx4g"] })];
    const comparison = compareLatencyResources(base, candidate);

    assert.equal(comparison.passed, false);
    assert.match(comparison.errors.join("\n"), /expected exactly -Xmx8g/);
});

test("resource gate requires GC, retained, and peak metrics", () => {
    const missingGc = resourceResult({ overrides: { "gc.time": undefined } });
    const invalidHeap = resourceResult({ overrides: { retainedHeapBytes: eventMetric(300 * 1024 ** 2) } });
    assert.match(compareLatencyResources([resourceResult()], [missingGc]).errors.join("\n"),
        /gc.time/);
    assert.match(compareLatencyResources([resourceResult()], [invalidHeap]).errors.join("\n"),
        /invalid loaded\/retained\/peak/);
});

test("resource confirmation aligns the same metric before blocking", () => {
    const base = [resourceResult()];
    const firstCandidate = [
        resourceResult({ overrides: { retainedHeapDeltaBytes: eventMetric(80 * 1024 ** 2) } })
    ];
    const retryCandidate = [resourceResult()];
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
        callSiteIndexBytes: 0,
        productionIndexPrepared: 0,
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

test("large-corpus comparison requires a self-consistent production index lifecycle", () => {
    const missingIndex = corpusLog({
        hive: { productionIndexPrepared: 1, callSiteIndexBytes: 0 }
    });
    const unexpectedIndex = corpusLog({
        hive: { productionIndexPrepared: 0, callSiteIndexBytes: 1_024 }
    });

    assert.equal(compareLargeCorpus(baseCorpusLog, missingIndex).passed, false);
    assert.equal(compareLargeCorpus(baseCorpusLog, unexpectedIndex).passed, false);
    assert.match(
        compareLargeCorpus(baseCorpusLog, missingIndex).errors.join("\n"),
        /invalid production CallSite-index lifecycle measurement/
    );
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
        assert.match(aggregate.body, /graph-routing-pressure: result artifact is missing/);
        assert.match(aggregate.body, /global-wide-pressure: result artifact is missing/);
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
            ["latency-resource-report.md", "latency-resource-status.json", "resource report"],
            ["graph-routing-report.md", "graph-routing-status.json", "graph routing report"],
            ["global-wide-report.md", "global-wide-status.json", "global wide report"]
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
        assert.match(aggregate.body, /PASS — 11\/11 component reports passed/);
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
        assert.match(aggregate.body, /graph routing report/);
        assert.match(aggregate.body, /global wide report/);

        fs.writeFileSync(path.join(directory, "method-status.json"), JSON.stringify({ passed: false }));
        const failed = aggregateReports(directory, {
            baseSha: "a".repeat(40),
            candidateSha: "b".repeat(40),
            runner: "test-runner",
            runUrl: "https://example.invalid/run"
        });
        assert.equal(failed.passed, false);
        assert.match(failed.body, /FAIL — 10\/11 component reports passed/);
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
        "benchmark-latency-resources",
        "benchmark-graph-routing"
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
    assert.match(workflow, /^  graph-routing-pressure-evidence:/m);
    assert.match(workflow, /^  global-wide-pressure-evidence:/m);
    assert.match(workflow, /^  prepare-fixture64:/m);
    assert.match(workflow, /EVIDENCE_CONTEXT: graphite\/fixture64-graph-routing/);
    assert.match(workflow, /TRUSTED_EVIDENCE_ACTOR: johnsonlee/);
    assert.doesNotMatch(workflow, /Startup-prepared P95 speedup must be >=10x/);
    assert.ok(workflow.includes("gist\\.github\\.com\\/johnsonlee"));
    assert.match(workflow, /graphite-fixture64-evidence-v8/);
    assert.match(workflow, /Evidence digest mismatch/);
    assert.match(workflow, /Independently recompute fixture64 comparisons/);
    assert.match(workflow, /reproducibilityScriptSha256/);
    assert.match(workflow, /:webgraph:prepareBenchmarkFixtures/);
    assert.match(workflow, /recomputed-fixture64/);
    assert.match(workflow, /sys\.version_info >= \(3, 12\)/);
    assert.match(workflow, /canonical-zip-sha256\.py "\$\{BASE_JAR\}"/);
    assert.match(workflow, /canonical-zip-sha256\.py "\$\{CANDIDATE_JAR\}"/);
    assert.match(workflow, /materializeGistFiles/);
    assert.match(workflow, /https:\/\/api\.github\.com\/gists\/\$\{evidenceMatch\[1\]\}\/\$\{evidenceMatch\[2\]\}/);
    assert.match(workflow, /public Gist API returned HTTP/);
    assert.doesNotMatch(workflow, /github\.request\('GET \/gists\/\{gist_id\}\/\{sha\}'/);
    assert.match(workflow, /compare-graph-id-pressure/);
    assert.match(workflow, /candidate-graph-routing-\$\{state\}\.correctness/);
    assert.match(workflow, /const \{ canonicalCorrectnessManifest \} = await import/);
    assert.match(workflow, /canonicalCorrectnessManifest\(correctness/);
    assert.doesNotMatch(workflow, /if \(correctness !== oracle\)/);
    assert.match(workflow, /GRAPH_ROUTING_JOB: \$\{\{ needs\.graph-routing-pressure-evidence\.result \}\}/);
    assert.match(workflow, /GLOBAL_WIDE_JOB: \$\{\{ needs\.global-wide-pressure-evidence\.result \}\}/);
    const graphRoutingJob = workflow.match(
        /^  graph-routing-pressure-evidence:\n[\s\S]*?(?=^  graph-routing-pressure-external-evidence-disabled:)/m
    )?.[0] ?? "";
    const fixture64Job = workflow.match(
        /^  prepare-fixture64:\n[\s\S]*?(?=^  graph-routing-pressure-evidence:)/m
    )?.[0] ?? "";
    assert.match(fixture64Job, /prepare-fixture64-graphs\.sh/);
    assert.match(fixture64Job, /test-fixture64-reproducibility\.sh/);
    assert.match(fixture64Job, /actions\/cache\/restore@v5/);
    assert.match(fixture64Job, /actions\/cache\/save@v5/);
    assert.match(fixture64Job, /fixture64-real-v1-temurin17/);
    assert.match(fixture64Job, /graphite-webgraph\/build\/benchmark-fixtures\/\*\.jar/);
    assert.match(fixture64Job, /graphite-core\/src\/main\/\*\*/);
    assert.match(fixture64Job, /graphite-sootup\/src\/main\/\*\*/);
    assert.match(fixture64Job, /graphite-webgraph\/src\/main\/kotlin\/io\/johnsonlee\/graphite\/webgraph\/GraphStore\.kt/);
    assert.match(
        fixture64Job,
        /graphite-webgraph\/src\/main\/kotlin\/io\/johnsonlee\/graphite\/webgraph\/CallSiteIndexPersistenceInput\.kt/
    );
    assert.doesNotMatch(fixture64Job, /graphite-webgraph\/src\/main\/\*\*/);
    assert.doesNotMatch(fixture64Job, /MappedCallSiteStringIndexView\.kt/);
    assert.match(fixture64Job, /FIXTURE64_CACHE_HIT/);
    assert.match(fixture64Job, /if: steps\.fixture64-cache\.outputs\.cache-hit != 'true'/);
    assert.match(
        fixture64Job,
        /path: \|\n\s+shared-fixture64\/graphs\n\s+fixture64-repeat-cache/
    );
    assert.match(fixture64Job, /fixture64\.complete\.json/);
    assert.match(fixture64Job, /Upload shared fixture64 corpus/);
    assert.match(fixture64Job, /shared-fixture64-\$\{\{ github\.event\.pull_request\.head\.sha \}\}/);
    assert.match(fixture64Job, /overwrite: true/);
    assert.doesNotMatch(fixture64Job, /shared-fixture64-.*github\.run_attempt/);
    assert.match(graphRoutingJob, /:webgraph:jmhJar :webgraph:prepareBenchmarkFixtures/);
    assert.match(graphRoutingJob, /needs: \[prepare-fixture64\]/);
    assert.match(graphRoutingJob, /Download shared fixture64 corpus/);
    assert.match(graphRoutingJob, /verify-shared-fixture64\.sh/);
    assert.doesNotMatch(graphRoutingJob, /Generate 64 persisted graphs|prepare-fixture64-graphs\.sh/);
    assert.match(graphRoutingJob, /run-real64-graph-routing\.sh/);
    assert.match(
        graphRoutingJob,
        /cd candidate\n\s*\.github\/scripts\/run-real64-graph-routing\.sh \\\n\s*\.\.\/shared-fixture64\/graphs\/graphs\.tsv/
    );
    assert.match(graphRoutingJob, /GRAPHITE_FIXTURE64_REPRODUCIBILITY_RECEIPT:/);
    assert.match(graphRoutingJob, /GRAPHITE_PRESSURE_PUBLISH_EVIDENCE: false/);
    assert.match(graphRoutingJob, /github\.event\.pull_request\.base\.sha/);
    assert.match(graphRoutingJob, /github\.event\.pull_request\.head\.sha/);
    assert.doesNotMatch(graphRoutingJob, /gist|EVIDENCE_CONTEXT|materializeGistFiles/);
    const globalWideJob = workflow.match(
        /^  global-wide-pressure-evidence:\n[\s\S]*?(?=^  global-wide-pressure-external-evidence-disabled:)/m
    )?.[0] ?? "";
    assert.match(globalWideJob, /:webgraph:jmhJar :webgraph:prepareBenchmarkFixtures/);
    assert.match(globalWideJob, /needs: \[prepare-fixture64\]/);
    assert.match(globalWideJob, /Download shared fixture64 corpus/);
    assert.match(globalWideJob, /verify-shared-fixture64\.sh/);
    assert.doesNotMatch(globalWideJob, /Generate 64 persisted graphs|prepare-fixture64-graphs\.sh/);
    assert.match(globalWideJob, /run-real64-global-wide\.sh/);
    assert.match(
        globalWideJob,
        /cd candidate\n\s*\.github\/scripts\/run-real64-global-wide\.sh \\\n\s*\.\.\/shared-fixture64\/graphs\/graphs\.tsv/
    );
    assert.match(globalWideJob, /GRAPHITE_FIXTURE64_REPRODUCIBILITY_RECEIPT:/);
    assert.match(globalWideJob, /GRAPHITE_PRESSURE_PUBLISH_EVIDENCE: false/);
    assert.match(globalWideJob, /github\.event\.pull_request\.base\.sha/);
    assert.match(globalWideJob, /github\.event\.pull_request\.head\.sha/);
    assert.doesNotMatch(globalWideJob, /gist|EVIDENCE_CONTEXT|materializeGistFiles/);
    const sharedFixtureVerifier = fs.readFileSync(
        new URL("./verify-shared-fixture64.sh", import.meta.url),
        "utf8"
    );
    assert.match(sharedFixtureVerifier, /graphite-shared-fixture64-v1/);
    assert.match(sharedFixtureVerifier, /fixtureJarSetSha256/);
    assert.match(sharedFixtureVerifier, /fixture-reproducibility\.json/);
    assert.match(sharedFixtureVerifier, /Fixture64GraphPreparation/);
    assert.match(sharedFixtureVerifier, /--verify/);
    assert.match(workflow, /global-wide-pressure-evidence\.result/);
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
        ),
        "utf8"
    );
    const comparator = fs.readFileSync(new URL("./benchmark-gate.mjs", import.meta.url));
    const realOnlyResourceHarness = fs.readFileSync(
        new URL(
            "../../graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/" +
                "WrappedDiscoveryResourceBenchmark.kt",
            import.meta.url
        )
    );
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
    assert.match(workflow, /HARNESS_SOURCE="controls\/\$\{SOURCE\}"/);
    assert.match(
        workflow,
        /grep -q 'SingleGraphWrappedDiscoveryResourceBenchmark' "\$\{HARNESS_SOURCE\}"/
    );
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
    assert.match(transitionHarness, /saveWithProductionCallSiteIndex/);
    assert.match(transitionHarness, /productionIndexPrepared=/);
    assert.match(transitionHarness, /callSiteIndexBytes=/);
    assert.match(transitionHarness, /CALL_SITE_INDEX_QUERY/);
    assert.match(workflow, /grep -Fq 'productionIndexPrepared=' "base\/\$\{HARNESS\}"/);
    assert.match(
        workflow,
        new RegExp(`LARGE_CORPUS_TRANSITION_COMPARATOR_SHA256: ${sha256(comparator)}`)
    );
    assert.match(workflow, /LARGE_CORPUS_LEGACY_HARNESS_SHA256: 66feedea8a6d8087/);
    assert.match(
        workflow,
        new RegExp(`BENCHMARK_REPORT_TRANSITION_SHA256: ${sha256(comparator)}`)
    );
    assert.match(
        workflow,
        new RegExp(`REAL_ONLY_LATENCY_COMPARATOR_SHA256: ${sha256(comparator)}`)
    );
    assert.match(
        workflow,
        new RegExp(`LATENCY_POINT_ESTIMATE_COMPARATOR_SHA256: ${sha256(comparator)}`)
    );
    assert.match(
        workflow,
        new RegExp(`REAL_ONLY_RESOURCE_HARNESS_SHA256: ${sha256(realOnlyResourceHarness)}`)
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
    assert.match(workflow, /grep -q '\"synthetic-1\"'/);
    assert.match(workflow, /if grep -q '\"synthetic-1\"' "\$\{COMBINER\}"; then exit 1; fi/);
    assert.match(workflow, /needs: \[candidate-gate-tests, prepare-latency-fixtures, build-wrapped-query-jmh\]/);
    assert.match(workflow, /grep -q 'SingleGraphWrappedDiscoveryResourceBenchmark'/);
    assert.match(workflow, /COMPARATOR=candidate\/\.github\/scripts\/benchmark-gate\.mjs/);
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
    assert.match(anchorEnforcement, /COMPARATOR=base\/\.github\/scripts\/benchmark-gate\.mjs/);
    assert.doesNotMatch(
        anchorEnforcement,
        /if ! grep -q 'compare-latency-anchor' "\$\{COMPARATOR\}"/,
        "a base that merely has the command must not bypass the point-estimate policy transition",
    );
    assert.match(
        anchorEnforcement,
        /POINT_ESTIMATE_POLICY='const baseComparison = compareJmh\(baseResults, candidateResults, regressionThreshold, true\);'/,
    );
    assert.match(
        anchorEnforcement,
        /if ! grep -Fq "\$\{POINT_ESTIMATE_POLICY\}" "\$\{COMPARATOR\}"/,
    );
    assert.match(anchorEnforcement, /test "\$\{CANDIDATE_GATE_TEST_JOB\}" = success/);
    assert.match(
        anchorEnforcement,
        /sha256sum candidate\/\.github\/scripts\/benchmark-gate\.mjs/,
    );
    assert.match(
        anchorEnforcement,
        /"\$\{LATENCY_POINT_ESTIMATE_COMPARATOR_SHA256\}"/,
    );
    assert.match(anchorEnforcement, /COMPARATOR=candidate\/\.github\/scripts\/benchmark-gate\.mjs/);
    assert.match(
        anchorEnforcement,
        /const anchorComparison = compareJmh\(anchorResults, candidateResults, anchorThreshold, true\);/,
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

test("latency workflow transitions when base has the anchor command but lacks point-estimate enforcement", () => {
    const workflow = fs.readFileSync(new URL("../workflows/benchmark.yml", import.meta.url), "utf8");
    const start = workflow.indexOf("    - name: Enforce known-good anchor and current-base regression gate");
    const end = workflow.indexOf("    - name: Upload wrapped-query latency results", start);
    const enforcement = workflow.slice(start, end);
    const policy = enforcement.match(/POINT_ESTIMATE_POLICY='([^']+)'/)?.[1];
    const oldBaseWithAnchorCommand = "else if (command === 'compare-latency-anchor') compareLatencyAnchorCommand(args);";
    const reviewedCandidate = fs.readFileSync(new URL("./benchmark-gate.mjs", import.meta.url), "utf8");

    assert.ok(oldBaseWithAnchorCommand.includes("compare-latency-anchor"));
    assert.equal(typeof policy, "string");
    assert.equal(oldBaseWithAnchorCommand.includes(policy), false);
    assert.equal(reviewedCandidate.includes(policy), true);
    assert.match(enforcement, /if ! grep -Fq "\$\{POINT_ESTIMATE_POLICY\}" "\$\{COMPARATOR\}"/);
    assert.match(enforcement, /test "\$\{CANDIDATE_GATE_TEST_JOB\}" = success/);
    assert.match(enforcement, /sha256sum candidate\/\.github\/scripts\/benchmark-gate\.mjs/);
    assert.match(enforcement, /COMPARATOR=candidate\/\.github\/scripts\/benchmark-gate\.mjs/);
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

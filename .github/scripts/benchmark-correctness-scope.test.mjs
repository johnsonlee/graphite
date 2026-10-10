import test from "node:test";
import assert from "node:assert/strict";
import { compareLargeCorpus, renderLargeCorpusReport } from "./benchmark-gate.mjs";

const log = ["tika", "hive", "kotlin-compiler"].map(corpus =>
    `LARGE_CORPUS_CORRECTNESS\t${corpus}\tnodes=20\tsourceEdges=30\tpersistedEdges=25` +
    "\tmethods=10\tcallSites=12\tpersistedBytes=10000\tcallSiteIndexBytes=100" +
    "\tproductionIndexPrepared=1\tbranchDefinitionBytes=100\tsyntheticIdentities=4"
).join("\n");
const options = { correctnessOnly: true };

test("correctness requires complete semantic/storage markers, never timing placeholders", () => {
    const result = compareLargeCorpus(log, log, options);
    assert.equal(result.passed, true);
    assert.equal(result.performanceAcceptance, false);
    assert.equal(result.scope, "correctness-only");
    assert.deepEqual(result.rows, []);
    assert.match(renderLargeCorpusReport(result), /Performance acceptance: unavailable/);
    for (const field of ["pipelineMs=0", "mappedLoadMs=100", "peakHeapBytes=100"]) {
        const changed = log.replace("syntheticIdentities=4", `syntheticIdentities=4\t${field}`);
        assert.equal(compareLargeCorpus(log, changed, options).passed, false);
    }
});

test("correctness cannot accept historical timings, missing corpora, or changed identities", () => {
    assert.equal(compareLargeCorpus(log, log.replaceAll("CORRECTNESS", "BASELINE"), options).passed, false);
    for (const changed of [
        log.split("\n").slice(1).join("\n"),
        log + "\n" + log.split("\n")[0],
        log.replace("nodes=20", "nodes=21"),
        log.replace("persistedBytes=10000", "persistedBytes=20000"),
        log.replace("branchDefinitionBytes=100", "branchDefinitionBytes=0"),
        log.replace("syntheticIdentities=4", "syntheticIdentities=0"),
        log.replace("productionIndexPrepared=1", "productionIndexPrepared=0")
    ]) assert.equal(compareLargeCorpus(log, changed, options).passed, false);
});

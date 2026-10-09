import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
    compareLargeCorpus,
    LARGE_CORPUS_SHAPE_TRANSITION,
    LARGE_CORPUS_SHARED_STRINGS_TRANSITION,
    LARGE_CORPUS_STRUCTURAL_TYPES_TRANSITION
} from "./benchmark-gate.mjs";

// Exact b033 CI correctness records. These values are independent of the constants
// under test; no timings, server, or real-corpus execution is used by these tests.
const records = {
    tika: {
        nodes: 3_901_103, sourceEdges: 4_510_016, persistedEdges: 4_353_588,
        methods: 312_788, callSites: 1_006_172, persistedBytes: 344_993_419,
        candidateMethods: 312_852, candidateBytes: 358_550_052
    },
    hive: {
        nodes: 5_992_914, sourceEdges: 6_597_267, persistedEdges: 6_376_682,
        methods: 404_016, callSites: 1_443_886, persistedBytes: 540_289_069,
        candidateMethods: 404_043, candidateBytes: 559_679_479
    },
    "kotlin-compiler": {
        nodes: 3_292_214, sourceEdges: 3_906_617, persistedEdges: 3_785_858,
        methods: 249_669, callSites: 922_876, persistedBytes: 318_590_673,
        candidateMethods: 249_669, candidateBytes: 330_684_523
    }
};
const shapeFields = ["nodes", "sourceEdges", "persistedEdges", "methods", "callSites"];
function corpusLog(candidate, changes = {}) {
    return Object.entries(records).map(([corpus, facts]) => {
        const values = {
            ...Object.fromEntries(shapeFields.map(key => [key, facts[key]])),
            methods: candidate ? facts.candidateMethods : facts.methods,
            persistedBytes: candidate ? facts.candidateBytes : facts.persistedBytes,
            callSiteIndexBytes: 100, productionIndexPrepared: 1,
            branchDefinitionBytes: 100, syntheticIdentities: 4,
            ...changes[corpus]
        };
        return `LARGE_CORPUS_CORRECTNESS\t${corpus}\t` +
            Object.entries(values).map(([key, value]) => `${key}=${value}`).join("\t");
    }).join("\n");
}
const options = { shapeTransition: LARGE_CORPUS_STRUCTURAL_TYPES_TRANSITION, correctnessOnly: true };

test("GTY05 accounts for CI storage bytes without changing GTY01/03 history or shape", () => {
    const deltas = transition => Object.values(transition).map(value => value.persistedBytesDelta);
    assert.deepEqual(deltas(LARGE_CORPUS_SHAPE_TRANSITION), [61_386_645, 101_839_125, 67_384_686]);
    assert.deepEqual(deltas(LARGE_CORPUS_SHARED_STRINGS_TRANSITION), [18_148_723, 28_492_338, 21_184_278]);
    assert.deepEqual(deltas(LARGE_CORPUS_STRUCTURAL_TYPES_TRANSITION), [13_556_633, 19_390_410, 12_093_850]);
    for (const corpus of Object.keys(records)) {
        assert.deepEqual(LARGE_CORPUS_STRUCTURAL_TYPES_TRANSITION[corpus].base, LARGE_CORPUS_SHAPE_TRANSITION[corpus].base);
        assert.deepEqual(LARGE_CORPUS_STRUCTURAL_TYPES_TRANSITION[corpus].candidate, LARGE_CORPUS_SHAPE_TRANSITION[corpus].candidate);
    }
    const result = compareLargeCorpus(corpusLog(false), corpusLog(true), options);
    assert.equal(result.passed, true);
    assert.deepEqual(result.errors, []);
    assert.equal(result.scope, "correctness-only");
    assert.equal(result.performanceAcceptance, false);
    assert.deepEqual(result.rows, []);
});

test("GTY05 refuses either legacy storage mode, omitted transition, and non-correctness use", () => {
    for (const shapeTransition of [null, LARGE_CORPUS_SHAPE_TRANSITION, LARGE_CORPUS_SHARED_STRINGS_TRANSITION]) {
        const result = compareLargeCorpus(corpusLog(false), corpusLog(true), { shapeTransition, correctnessOnly: true });
        assert.equal(result.passed, false);
        assert.match(result.errors.join("\n"), /persistedBytes: persisted size changed/);
    }
    assert.throws(() => compareLargeCorpus(corpusLog(false), corpusLog(true), {
        shapeTransition: LARGE_CORPUS_STRUCTURAL_TYPES_TRANSITION
    }), /GTY05 storage transition requires correctness-only scope/);
    assert.equal(compareLargeCorpus(corpusLog(false), corpusLog(true).replaceAll("CORRECTNESS", "BASELINE"), options).passed, false);
});

test("GTY05 retains exact shapes on both arms and rejects storage corruption beyond 4096 bytes", () => {
    for (const corpus of Object.keys(records)) {
        for (const field of shapeFields) {
            for (const candidate of [false, true]) {
                const old = candidate && field === "methods" ? records[corpus].candidateMethods : records[corpus][field];
                const changed = corpusLog(candidate, { [corpus]: { [field]: old + 1 } });
                const result = compareLargeCorpus(candidate ? corpusLog(false) : changed,
                    candidate ? changed : corpusLog(true), options);
                assert.equal(result.passed, false, `${corpus}/${field}/${candidate}`);
                assert.match(result.errors.join("\n"), /does not match the transition/);
            }
        }
        for (const offset of [-4097, -4096, 4096, 4097]) {
            const changed = corpusLog(true, { [corpus]: { persistedBytes: records[corpus].candidateBytes + offset } });
            const result = compareLargeCorpus(corpusLog(false), changed, options);
            assert.equal(result.passed, Math.abs(offset) <= 4096, `${corpus}/${offset}`);
            if (!result.passed) assert.match(result.errors.join("\n"), /exceeding the 4096-byte tolerance/);
        }
    }
});

test("GTY05 CLI is explicit, mutually exclusive, correctness-only, and leaves ordinary comparisons strict", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "gty05-transition-"));
    try {
        const base = path.join(directory, "base.log"), candidate = path.join(directory, "candidate.log");
        fs.writeFileSync(base, corpusLog(false));
        fs.writeFileSync(candidate, corpusLog(true));
        const script = fileURLToPath(new URL("./benchmark-gate.mjs", import.meta.url));
        let ordinal = 0;
        const invoke = flags => {
            const status = path.join(directory, `status-${ordinal++}.json`);
            const result = spawnSync(process.execPath, [script, "compare-large-corpus", ...flags,
                "--base", base, "--candidate", candidate, "--status", status,
                "--report", status + ".md"], { encoding: "utf8" });
            return { ...result, comparison: fs.existsSync(status) ? JSON.parse(fs.readFileSync(status, "utf8")) : null };
        };
        const successful = invoke(["--correctness-only", "--structural-types-transition"]);
        assert.equal(successful.status, 0, successful.stderr);
        assert.equal(successful.comparison.passed, true);
        assert.equal(successful.comparison.performanceAcceptance, false);
        const nonCorrectness = invoke(["--structural-types-transition"]);
        assert.notEqual(nonCorrectness.status, 0);
        assert.match(nonCorrectness.stderr, /GTY05 storage transition requires correctness-only scope/);
        for (const flags of [
            ["--shape-transition", "--shared-strings-transition"],
            ["--shape-transition", "--structural-types-transition"],
            ["--shared-strings-transition", "--structural-types-transition"],
            ["--shape-transition", "--shared-strings-transition", "--structural-types-transition"]
        ]) {
            const mixed = invoke(["--correctness-only", ...flags]);
            assert.notEqual(mixed.status, 0);
            assert.match(mixed.stderr, /Choose exactly one storage transition/);
        }
        for (const flags of [[], ["--shared-strings-transition"], ["--shape-transition"]]) {
            assert.notEqual(invoke(["--correctness-only", ...flags]).status, 0);
        }
        // A future same-format base gets no transition: equality succeeds, any shape drift fails.
        fs.writeFileSync(base, corpusLog(true));
        assert.equal(invoke(["--correctness-only"]).status, 0);
        fs.writeFileSync(candidate, corpusLog(true, { hive: { methods: records.hive.candidateMethods + 1 } }));
        const drift = invoke(["--correctness-only"]);
        assert.notEqual(drift.status, 0);
        assert.match(drift.comparison.errors.join("\n"), /hive\/methods: graph shape changed/);
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

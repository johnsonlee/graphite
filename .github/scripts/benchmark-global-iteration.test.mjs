import assert from "node:assert/strict";
import test from "node:test";
import {
    FROZEN_TARGET_REF,
    aggregateIteration,
    renderIterationReport,
    validateReferences
} from "./benchmark-global-iteration.mjs";

// These JSON fixtures verify gate decisions only; they are not performance measurements.
const HEAD = "a".repeat(40);
const BASE = "b".repeat(40);
const ACCEPTED = "c".repeat(40);
const acceptedRunIds = {
    ".github/workflows/build.yml": { id: 123, attempt: 1 },
    ".github/workflows/benchmark.yml": { id: 456, attempt: 2 }
};

function references(overrides = {}) {
    return {
        frozenTargetRef: FROZEN_TARGET_REF,
        targetSpeedup: 10,
        currentHead: HEAD,
        currentPrBase: BASE,
        lastAcceptedRef: ACCEPTED,
        regressionRefs: [BASE, ACCEPTED],
        acceptedRunIds,
        ...overrides
    };
}

function targetErrors(runs) {
    return runs.flatMap((run, index) => {
        const aggregateSpeedup = run.baseP95LatencyNanos / run.p95LatencyNanos;
        return [
            ...(aggregateSpeedup < 10 ? [`pair-${index + 1}: P95 speedup ${aggregateSpeedup.toFixed(2)}x; ` +
                "required 10.00x in every independent fork"] : []),
            ...run.wrappedShapeRuns.flatMap(shape => {
                const speedup = shape.baseLatencyNanos / shape.latencyNanos;
                return speedup < 10 ? [`pair-${index + 1}: ${shape.shape} P95 speedup ` +
                    `${speedup.toFixed(2)}x; required 10.00x`] : [];
            })
        ];
    });
}

function status({ regressionPassed = true, targetAchieved = false, progressAchieved = true } = {}) {
    const measured = targetAchieved ? 10 : progressAchieved ? 90 : 100;
    const runs = ["candidate-base", "base-candidate", "candidate-base"].map((order) => ({
        order, baseP95LatencyNanos: 100, p95LatencyNanos: measured,
        wrappedShapeRuns: ["global-wide-wrapped-case-insensitive", "global-wide-wrapped-case-insensitive-distinct"]
            .map(shape => ({ shape, baseLatencyNanos: 100, latencyNanos: measured }))
    }));
    return {
        passed: regressionPassed,
        regressionPassed,
        targetAchieved: regressionPassed && targetAchieved,
        regressionOnly: true,
        minimumSpeedup: 10,
        errors: regressionPassed ? [] : ["paired process CPU exceeds the regression limit"],
        targetErrors: targetErrors(runs),
        runs
    };
}

function executions(refs, overrides = {}) {
    return validateReferences(refs).map((referenceSha) => ({
        referenceSha,
        exitCode: 0,
        status: status(),
        ...overrides[referenceSha]
    }));
}

test("the plan deduplicates references while preserving base, accepted, frozen order", () => {
    assert.deepEqual(validateReferences(references(), HEAD), [BASE, ACCEPTED, FROZEN_TARGET_REF]);
    const refs = references({ currentPrBase: FROZEN_TARGET_REF, lastAcceptedRef: FROZEN_TARGET_REF,
        regressionRefs: [FROZEN_TARGET_REF], acceptedRunIds: null });
    assert.deepEqual(validateReferences(refs), [FROZEN_TARGET_REF]);
});

test("the final frozen reference and 10x requirement cannot change", () => {
    for (const patch of [{ frozenTargetRef: BASE }, { targetSpeedup: 5 }, { targetSpeedup: "10" }]) {
        assert.throws(() => validateReferences(references(patch)), /final target/);
    }
});

test("every SHA and the actual checked-out head must match exactly", () => {
    for (const key of ["currentHead", "currentPrBase", "lastAcceptedRef", "frozenTargetRef"]) {
        assert.throws(() => validateReferences(references({ [key]: "main" })), /exact lowercase commit SHA/);
    }
    assert.throws(() => validateReferences(references(), BASE), /checked-out HEAD/);
    assert.throws(() => validateReferences(references({ currentHead: BASE })), /own regression/);
});

test("regression references cannot omit, reorder, duplicate or add references", () => {
    for (const regressionRefs of [[BASE], [ACCEPTED, BASE], [BASE, ACCEPTED, ACCEPTED],
        [BASE, ACCEPTED, FROZEN_TARGET_REF]]) {
        assert.throws(() => validateReferences(references({ regressionRefs })), /unique ordered/);
    }
});

test("a distinct accepted reference requires successful workflow evidence identities", () => {
    assert.throws(() => validateReferences(references({ acceptedRunIds: null })), /requires both/);
    for (const ids of [undefined, {}, [], { ...acceptedRunIds, extra: { id: 1, attempt: 1 } },
        { ...acceptedRunIds, ".github/workflows/build.yml": { id: 123, attempt: 0 } }]) {
        assert.throws(() => validateReferences(references({ acceptedRunIds: ids })), /acceptedRunIds/);
    }
});

test("an improving no-regression iteration can pass while the final target remains unmet", () => {
    const refs = references();
    const result = aggregateIteration(refs, executions(refs));
    assert.equal(result.passed, true);
    assert.equal(result.iterationPassed, true);
    assert.equal(result.targetAchieved, false);
    assert.equal(result.frozenTargetAchieved, false);
    assert.equal(result.progressAchieved, true);
    assert.deepEqual(result.progressErrors, []);
    assert.deepEqual(result.errors, []);
    assert.match(result.targetErrors[0], /required 10\.00x/);
    assert.equal(Object.keys(result.comparisons).length, 3);
});

test("the explicit final-target gate stays red until the frozen comparison reaches 10x", () => {
    const refs = references();
    const result = aggregateIteration(refs, executions(refs), { requireTarget: true });
    assert.equal(result.passed, false);
    assert.equal(result.iterationPassed, true);
    assert.equal(result.targetAchieved, false);
    assert.match(result.errors[0], /required 10\.00x/);
    const achieved = aggregateIteration(refs, executions(refs, {
        [FROZEN_TARGET_REF]: { status: status({ targetAchieved: true }) }
    }), { requireTarget: true });
    assert.equal(achieved.passed, true);
    assert.equal(achieved.targetAchieved, true);
});

test("10x against a moving reference cannot substitute for the frozen target", () => {
    const refs = references();
    const result = aggregateIteration(refs, executions(refs, {
        [BASE]: { status: status({ targetAchieved: true }) },
        [ACCEPTED]: { status: status({ targetAchieved: true }) }
    }), { requireTarget: true });
    assert.equal(result.targetAchieved, false);
    assert.equal(result.passed, false);
});

test("every reference regression blocks even when frozen target measurements improve", () => {
    const refs = references();
    for (const failedRef of validateReferences(refs)) {
        const result = aggregateIteration(refs, executions(refs, {
            [FROZEN_TARGET_REF]: { status: status({ targetAchieved: true }) },
            [failedRef]: { exitCode: 1, status: status({ regressionPassed: false }) }
        }));
        assert.equal(result.passed, false);
        assert.equal(result.iterationPassed, false);
        assert.equal(result.targetAchieved, false);
        assert.match(result.errors[0], /process CPU/);
        assert.equal(Object.keys(result.comparisons).length, 3);
    }
});

test("a failed driver with a valid failed status retains its raw failure evidence", () => {
    const refs = references();
    const failed = status({ regressionPassed: false });
    const result = aggregateIteration(refs, executions(refs, { [BASE]: { exitCode: 1, status: failed } }));
    assert.equal(result.comparisons[BASE].status, failed);
    assert.equal(result.comparisons[BASE].error, null);
    assert.deepEqual(result.errors, [`${BASE}: ${failed.errors[0]}`]);
});

test("missing status, execution errors and nonzero exits with a passing status fail closed", () => {
    const refs = references();
    for (const patch of [{ status: null, exitCode: 1 }, { exitCode: 1 }, { exitCode: null },
        { error: "report missing" }]) {
        const result = aggregateIteration(refs, executions(refs, { [BASE]: patch }));
        assert.equal(result.passed, false);
        assert.equal(result.iterationPassed, false);
        assert.ok(result.comparisons[BASE].error);
    }
});

test("inconsistent comparison flags and malformed frozen evidence cannot pass", () => {
    const refs = references();
    for (const patch of [{ regressionOnly: false }, { minimumSpeedup: 5 }, { runs: [] },
        { passed: false }, { targetAchieved: true }, { errors: ["correctness differs"] },
        { targetErrors: "invalid" }]) {
        const result = aggregateIteration(refs, executions(refs, {
            [FROZEN_TARGET_REF]: { status: { ...status(), ...patch } }
        }));
        assert.equal(result.passed, false);
        assert.equal(result.targetAchieved, false);
        assert.match(result.comparisons[FROZEN_TARGET_REF].error, /status|flags/);
    }
});

test("missing, duplicate and unexpected executions are rejected", () => {
    const refs = references();
    const all = executions(refs);
    for (const rows of [all.slice(1), [...all, all[0]], [...all, { ...all[0], referenceSha: HEAD }]]) {
        assert.equal(aggregateIteration(refs, rows).passed, false);
    }
});

test("untrusted extra reference fields cannot override computed results", () => {
    const refs = references({ passed: true, targetAchieved: true, iterationPassed: true });
    const result = aggregateIteration(refs, executions(refs, {
        [BASE]: { exitCode: 1, status: status({ regressionPassed: false }) }
    }));
    assert.equal(result.passed, false);
    assert.equal(result.targetAchieved, false);
    assert.equal(result.iterationPassed, false);
});

test("the report distinguishes iteration approval from final success and includes each reference", () => {
    const refs = references();
    const result = aggregateIteration(refs, executions(refs));
    const report = renderIterationReport(result, { [BASE]: "Base comparison retained verbatim." });
    assert.match(report, /Iteration acceptance: \*\*passed\*\*/);
    assert.match(report, /Final 10x target[^\n]+\*\*not achieved\*\*/);
    assert.match(report, /does not establish completion/);
    assert.match(report, /Base comparison retained verbatim/);
    for (const ref of validateReferences(refs)) assert.ok(report.includes(`reference-${ref}/global-wide-report.md`));
});


test("a 1x control fails the iteration even when every no-regression check passes", () => {
    const refs = references();
    const result = aggregateIteration(refs, executions(refs, {
        [ACCEPTED]: { status: status({ progressAchieved: false }) }
    }));
    assert.equal(result.regressionPassed, true);
    assert.equal(result.progressAchieved, false);
    assert.equal(result.iterationPassed, false);
    assert.equal(result.passed, false);
    assert.equal(result.targetAchieved, false);
    assert.equal(result.progressErrors.length, 3);
    assert.match(renderIterationReport(result), /P95 progress[^\n]+\*\*not achieved\*\*/);
});

test("progress requires every fork to improve strictly against the last accepted reference", () => {
    const refs = references();
    for (const p95 of [100, 101]) {
        const mixed = status();
        mixed.runs[1].p95LatencyNanos = p95;
        mixed.targetErrors = targetErrors(mixed.runs);
        const result = aggregateIteration(refs, executions(refs, { [ACCEPTED]: { status: mixed } }));
        assert.equal(result.passed, false);
        assert.equal(result.progressAchieved, false);
        assert.equal(result.progressErrors.length, 1);
        assert.match(result.progressErrors[0], /pair-2/);
    }
});

test("improvement against base or frozen main cannot replace progress against the last accepted reference", () => {
    const refs = references();
    const result = aggregateIteration(refs, executions(refs, {
        [BASE]: { status: status({ targetAchieved: true }) },
        [FROZEN_TARGET_REF]: { status: status({ targetAchieved: true }) },
        [ACCEPTED]: { status: status({ progressAchieved: false }) }
    }));
    assert.equal(result.frozenTargetAchieved, true);
    assert.equal(result.progressAchieved, false);
    assert.equal(result.targetAchieved, false);
    assert.equal(result.passed, false);
});


test("strict progress accepts a positive gain without inventing a minimum percentage", () => {
    const refs = references();
    const slight = status();
    for (const run of slight.runs) run.p95LatencyNanos = 99.999;
    slight.targetErrors = targetErrors(slight.runs);
    const result = aggregateIteration(refs, executions(refs, { [ACCEPTED]: { status: slight } }));
    assert.equal(result.progressAchieved, true);
    assert.equal(result.iterationPassed, true);
    assert.equal(result.targetAchieved, false);
});

test("paired progress requires the prescribed reversed run order", () => {
    const refs = references();
    const oneSided = status();
    oneSided.runs[1].order = "candidate-base";
    const result = aggregateIteration(refs, executions(refs, { [ACCEPTED]: { status: oneSided } }));
    assert.equal(result.progressAchieved, false);
    assert.equal(result.passed, false);
    assert.match(result.comparisons[ACCEPTED].error, /flags/);
});


test("strict readiness rejects a fabricated target flag over 1.11x raw measurements", () => {
    const refs = references();
    const forged = { ...status(), targetAchieved: true, targetErrors: [] };
    const result = aggregateIteration(refs, executions(refs, {
        [FROZEN_TARGET_REF]: { status: forged }
    }), { requireTarget: true });
    assert.equal(result.passed, false);
    assert.equal(result.targetAchieved, false);
    assert.match(result.comparisons[FROZEN_TARGET_REF].error, /contradict.*P95/);
});

test("all three aggregate and both wrapped measurements must be complete finite positive numbers", () => {
    const refs = references();
    for (const invalid of [undefined, null, 0, -1, Infinity, NaN, "10"]) {
        for (const key of ["baseP95LatencyNanos", "p95LatencyNanos", "baseLatencyNanos", "latencyNanos"]) {
            for (const pair of [0, 1, 2]) {
                const malformed = status();
                if (["baseP95LatencyNanos", "p95LatencyNanos"].includes(key)) malformed.runs[pair][key] = invalid;
                else malformed.runs[pair].wrappedShapeRuns[1][key] = invalid;
                const result = aggregateIteration(refs, executions(refs, {
                    [FROZEN_TARGET_REF]: { status: malformed }
                }));
                assert.equal(result.passed, false);
                assert.equal(result.targetAchieved, false);
                assert.match(result.comparisons[FROZEN_TARGET_REF].error, /finite positive/);
            }
        }
    }
});

test("missing, duplicate and unexpected wrapped identities fail even in regression-only mode", () => {
    const refs = references();
    const shapes = status().runs[0].wrappedShapeRuns;
    for (const wrappedShapeRuns of [undefined, [], [shapes[0]], [shapes[0], shapes[0]],
        [shapes[0], { ...shapes[1], shape: "unexpected" }], [...shapes, shapes[0]]]) {
        const malformed = status();
        malformed.runs[1].wrappedShapeRuns = wrappedShapeRuns;
        const result = aggregateIteration(refs, executions(refs, { [BASE]: { status: malformed } }));
        assert.equal(result.passed, false);
        assert.match(result.comparisons[BASE].error, /exact wrapped shape identities/);
    }
});

test("each aggregate and wrapped fork independently binds the target flags and errors", () => {
    const refs = references();
    for (const pair of [0, 1, 2]) {
        for (const shape of [-1, 0, 1]) {
            const malformed = status({ targetAchieved: true });
            if (shape === -1) malformed.runs[pair].p95LatencyNanos = 10.01;
            else malformed.runs[pair].wrappedShapeRuns[shape].latencyNanos = 10.01;
            const result = aggregateIteration(refs, executions(refs, {
                [FROZEN_TARGET_REF]: { status: malformed }
            }), { requireTarget: true });
            assert.equal(result.passed, false);
            assert.match(result.comparisons[FROZEN_TARGET_REF].error, /contradict.*P95/);
        }
    }
    for (const targetErrorMutation of [errors => errors.slice(1), errors => [...errors, errors[0]],
        errors => errors.map((error, index) => index === 0 ? "unbound target failure" : error)]) {
        const malformed = status();
        malformed.targetErrors = targetErrorMutation(malformed.targetErrors);
        const result = aggregateIteration(refs, executions(refs, { [BASE]: { status: malformed } }));
        assert.equal(result.passed, false);
        assert.match(result.comparisons[BASE].error, /contradict.*P95/);
    }
});

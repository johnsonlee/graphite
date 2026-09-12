import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import {
    FROZEN_TARGET_REF, REQUIRED_WORKFLOWS, TARGET_SPEEDUP, resolveOptimizationReferences
} from "./benchmark-optimization-references.mjs";

const HEAD = "a".repeat(40);
const NEWER = "b".repeat(40);
const OLDER = "c".repeat(40);
const SIDE = "d".repeat(40);
const PR = 116;

function run(head, workflow, id, overrides = {}) {
    return {
        id, run_attempt: 1, head_sha: head, path: workflow,
        event: "pull_request", status: "completed", conclusion: "success",
        created_at: `2026-09-05T12:00:${String(id % 60).padStart(2, "0")}Z`,
        pull_requests: [{ number: PR }],
        ...overrides
    };
}

function green(head, firstId = 1) {
    return REQUIRED_WORKFLOWS.map((workflow, index) => run(head, workflow, firstId + index));
}

function resolve(workflowRuns = [], overrides = {}) {
    return resolveOptimizationReferences({
        prNumber: PR, currentHead: HEAD, currentPrBase: FROZEN_TARGET_REF,
        firstParentChain: [HEAD, NEWER, OLDER, FROZEN_TARGET_REF],
        workflowRuns, ...overrides
    });
}

test("the goal stays pinned while empty history falls back to current PR base", () => {
    assert.equal(FROZEN_TARGET_REF, "4e328b0109e13c896b74004823fb049fcb19251a");
    assert.equal(TARGET_SPEEDUP, 10);
    assert.deepEqual(resolve(), {
        frozenTargetRef: FROZEN_TARGET_REF, targetSpeedup: 10,
        currentHead: HEAD, currentPrBase: FROZEN_TARGET_REF,
        lastAcceptedRef: FROZEN_TARGET_REF, regressionRefs: [FROZEN_TARGET_REF], acceptedRunIds: null
    });
});

test("nearest first-parent accepted head wins independently of workflow array order", () => {
    const result = resolve([...green(OLDER, 20), ...green(NEWER, 1)].reverse());
    assert.equal(result.lastAcceptedRef, NEWER);
    assert.deepEqual(result.regressionRefs, [FROZEN_TARGET_REF, NEWER]);
    assert.deepEqual(result.acceptedRunIds, {
        [REQUIRED_WORKFLOWS[0]]: { id: 1, attempt: 1 },
        [REQUIRED_WORKFLOWS[1]]: { id: 2, attempt: 1 }
    });
});

test("current head, merge-side commits and wrong PR successes cannot establish acceptance", () => {
    const result = resolve([
        ...green(HEAD), ...green(SIDE, 5),
        ...green(NEWER, 10).map(value => ({ ...value, pull_requests: [{ number: PR + 1 }] })),
        ...green(OLDER, 20)
    ], { ancestorRefs: [HEAD, NEWER, OLDER, SIDE, FROZEN_TARGET_REF] });
    assert.equal(result.lastAcceptedRef, OLDER);
});

for (const rejected of [
    { status: "completed", conclusion: "failure" },
    { status: "completed", conclusion: "cancelled" },
    { status: "completed", conclusion: "skipped" },
    { status: "completed", conclusion: "neutral" },
    { status: "in_progress", conclusion: null },
    { status: "queued", conclusion: null },
    { event: "push" },
    { pull_requests: [] },
    { path: ".github/workflows/publish.yml" }
]) {
    test(`both exact-head workflows are mandatory: ${JSON.stringify(rejected)}`, () => {
        const newer = green(NEWER).map((value, index) => index === 1 ? { ...value, ...rejected } : value);
        assert.equal(resolve([...newer, ...green(OLDER, 20)]).lastAcceptedRef, OLDER);
    });
}

test("a latest failed or unfinished push run vetoes otherwise green PR proof", () => {
    for (const rejected of [
        { status: "completed", conclusion: "failure" },
        { status: "completed", conclusion: "cancelled" },
        { status: "in_progress", conclusion: null },
        { status: "queued", conclusion: null }
    ]) {
        const push = run(NEWER, REQUIRED_WORKFLOWS[0], 3, {
            event: "push", pull_requests: [], ...rejected
        });
        assert.equal(resolve([...green(NEWER), push, ...green(OLDER, 20)]).lastAcceptedRef, OLDER);
    }
});

test("missing push runs do not fail PR coverage and push-only success cannot establish it", () => {
    assert.equal(resolve(green(NEWER)).lastAcceptedRef, NEWER);
    assert.equal(resolve(green(NEWER).map(value => ({ ...value, event: "push", pull_requests: [] })))
        .lastAcceptedRef, FROZEN_TARGET_REF);
});

test("successful push retry supersedes only older push failure and preserves PR proof IDs", () => {
    const failed = run(NEWER, REQUIRED_WORKFLOWS[0], 3, {
        event: "push", pull_requests: [], conclusion: "failure"
    });
    const retry = { ...failed, id: 4, created_at: "2026-09-05T13:00:00Z", conclusion: "success" };
    const result = resolve([...green(NEWER), failed, retry]);
    assert.equal(result.lastAcceptedRef, NEWER);
    assert.equal(result.acceptedRunIds[REQUIRED_WORKFLOWS[0]].id, 1);
    assert.equal(resolve([failed, retry, green(NEWER)[1]]).lastAcceptedRef, FROZEN_TARGET_REF);
});

test("newer failed push rerun cannot be hidden by a later-numbered successful run", () => {
    const push = run(NEWER, REQUIRED_WORKFLOWS[0], 3, { event: "push", pull_requests: [] });
    const failedRetry = { ...push, run_attempt: 2, run_started_at: "2026-09-05T14:00:00Z", conclusion: "failure" };
    const otherPush = { ...push, id: 4, created_at: "2026-09-05T13:00:00Z" };
    assert.equal(resolve([...green(NEWER), push, failedRetry, otherPush]).lastAcceptedRef, FROZEN_TARGET_REF);
});

test("exact-head dispatch failures veto acceptance while unrelated workflows and heads do not", () => {
    const failed = run(NEWER, REQUIRED_WORKFLOWS[0], 3, {
        event: "repository_dispatch", pull_requests: [], conclusion: "failure"
    });
    assert.equal(resolve([...green(NEWER), failed]).lastAcceptedRef, FROZEN_TARGET_REF);
    assert.equal(resolve([...green(NEWER), { ...failed, path: ".github/workflows/publish.yml" }])
        .lastAcceptedRef, NEWER);
    assert.equal(resolve([...green(NEWER), { ...failed, head_sha: SIDE }]).lastAcceptedRef, NEWER);
});

test("successes on different heads cannot be combined", () => {
    assert.equal(resolve([green(NEWER)[0], green(OLDER, 20)[1]]).lastAcceptedRef, FROZEN_TARGET_REF);
});

test("a later failed or unfinished run supersedes earlier success on the same head", () => {
    for (const state of [{ status: "completed", conclusion: "failure" }, { status: "in_progress", conclusion: null }]) {
        const runs = [...green(NEWER), run(NEWER, REQUIRED_WORKFLOWS[1], 3, state), ...green(OLDER, 20)];
        assert.equal(resolve(runs).lastAcceptedRef, OLDER);
    }
});

test("a later successful retry can supersede an earlier failure", () => {
    const runs = [...green(NEWER), run(NEWER, REQUIRED_WORKFLOWS[1], 3, { conclusion: "failure" }),
        run(NEWER, REQUIRED_WORKFLOWS[1], 4)];
    assert.equal(resolve(runs).acceptedRunIds[REQUIRED_WORKFLOWS[1]].id, 4);
});

test("the latest rerun attempt replaces stale successful evidence, even for an older run id", () => {
    const runs = [...green(NEWER), run(NEWER, REQUIRED_WORKFLOWS[1], 2, {
        run_attempt: 2, run_started_at: "2026-09-05T13:00:00Z", conclusion: "failure"
    }), run(NEWER, REQUIRED_WORKFLOWS[1], 3), ...green(OLDER, 20)];
    assert.equal(resolve(runs).lastAcceptedRef, OLDER);
});

test("run attempt breaks ties when GitHub timestamps have equal second precision", () => {
    const runs = [...green(NEWER), run(NEWER, REQUIRED_WORKFLOWS[1], 2, {
        run_attempt: 2, run_started_at: "2026-09-05T12:00:02Z", conclusion: "failure"
    })];
    assert.equal(resolve(runs).lastAcceptedRef, FROZEN_TARGET_REF);
});

test("accepted current PR base is deduplicated and frozen target never moves", () => {
    const result = resolve(green(OLDER), { currentPrBase: OLDER });
    assert.equal(result.lastAcceptedRef, OLDER);
    assert.deepEqual(result.regressionRefs, [OLDER]);
    assert.equal(result.frozenTargetRef, FROZEN_TARGET_REF);
    assert.deepEqual(resolve([], { currentPrBase: OLDER }).regressionRefs, [OLDER]);
});

test("a merged current main base may be a second-parent ancestor, but is never an accepted iteration", () => {
    const result = resolve(green(SIDE), {
        currentPrBase: SIDE,
        ancestorRefs: [HEAD, NEWER, OLDER, SIDE, FROZEN_TARGET_REF],
        baseAncestorRefs: [SIDE, FROZEN_TARGET_REF]
    });
    assert.equal(result.lastAcceptedRef, SIDE);
    assert.equal(result.acceptedRunIds, null);
});

test("commits preceding frozen starting main cannot become accepted iterations", () => {
    assert.equal(resolve(green(SIDE), {
        firstParentChain: [HEAD, NEWER, OLDER, FROZEN_TARGET_REF, SIDE]
    }).lastAcceptedRef, FROZEN_TARGET_REF);
});

test("invalid ancestry and malformed run evidence fail closed", () => {
    const invalidInputs = [
        { currentHead: "a" }, { prNumber: 0 },
        { firstParentChain: [NEWER, HEAD, FROZEN_TARGET_REF] },
        { firstParentChain: [HEAD, NEWER] },
        { firstParentChain: [HEAD, NEWER, NEWER, FROZEN_TARGET_REF] },
        { currentPrBase: SIDE }, { ancestorRefs: [HEAD, FROZEN_TARGET_REF] },
        { baseAncestorRefs: [HEAD] },
        { firstParentChain: [HEAD, FROZEN_TARGET_REF, OLDER], currentPrBase: OLDER },
        { workflowRuns: null }, { workflowRuns: [null] },
        { workflowRuns: [{ ...green(NEWER)[0], pull_requests: undefined }] },
        { workflowRuns: [{ ...green(NEWER)[0], run_attempt: undefined }] },
        { workflowRuns: [{ ...green(NEWER)[0], run_attempt: 2 }] },
        { workflowRuns: [{ ...green(NEWER)[0], conclusion: null }] },
        { workflowRuns: [{ ...green(NEWER)[0], created_at: "bad" }] },
        { workflowRuns: [green(NEWER)[0], green(NEWER)[0]] }
    ];
    for (const input of invalidInputs) assert.throws(() => resolve([], input), Error, JSON.stringify(input));
});

const script = fileURLToPath(new URL("./benchmark-optimization-references.mjs", import.meta.url));

function cli(t, { mode = "success", runs = [] } = {}) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "benchmark-references-test-"));
    t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
    const fixture = { mode, runs, head: HEAD, base: FROZEN_TARGET_REF, candidate: NEWER, pr: PR };
    const fixturePath = path.join(dir, "evidence.json");
    fs.writeFileSync(fixturePath, JSON.stringify(fixture));
    const stub = `#!${process.execPath}
import fs from 'node:fs';
import path from 'node:path';
const f = JSON.parse(fs.readFileSync(process.env.REFERENCE_TEST_DATA, 'utf8'));
const args = process.argv.slice(2);
const isGit = path.basename(process.argv[1]) === 'git';
let output;
if (isGit) {
    if (args[0] === 'rev-parse') output = args[2].split('^')[0];
    else if (args[0] === 'merge-base') { if (f.mode === 'ancestry-error') process.exit(1); output = ''; }
    else if (args[0] === 'rev-list') output = args.at(-1) === f.base ? f.base : [f.head, f.candidate, f.base].join('\\n');
    else throw new Error('unexpected git invocation');
} else {
    if (f.mode === 'api-error') process.exit(1);
    if (f.mode === 'malformed-json') { process.stdout.write('{'); process.exit(0); }
    if (args[1].includes('/pulls/')) {
        const marker = process.env.REFERENCE_TEST_DATA + '.pr-read';
        const changed = f.mode === 'changed-pr' && fs.existsSync(marker);
        fs.writeFileSync(marker, 'read');
        output = JSON.stringify({number: f.pr, head: {sha: f.mode === 'wrong-head' || changed ? f.base : f.head}, base: {sha: f.base}});
    }
    else {
        const workflow = args[1].includes('/build.yml/') ? '.github/workflows/build.yml' : '.github/workflows/benchmark.yml';
        const query = new URL('https://example.invalid/' + args[1]).searchParams;
        const rows = f.runs.filter(run => run.path === workflow &&
            (!query.has('event') || run.event === query.get('event')));
        const page = Number(query.get('page'));
        output = JSON.stringify({total_count: f.mode === 'partial-page' ? 2 : f.mode === 'excess-history' ? 1001 : rows.length, workflow_runs: rows.slice((page - 1) * 100, page * 100)});
    }
}
process.stdout.write(output + '\\n');
`;
    for (const executable of ["gh", "git"]) fs.writeFileSync(path.join(dir, executable), stub, { mode: 0o755 });
    return spawnSync(process.execPath, [script, "--repo", "owner/repo", "--pr", String(PR), "--head", HEAD, "--base", FROZEN_TARGET_REF], {
        encoding: "utf8", env: { ...process.env, PATH: `${dir}${path.delimiter}${process.env.PATH}`, REFERENCE_TEST_DATA: fixturePath }
    });
}

test("CLI emits the same exact accepted-head proof as pure resolution", t => {
    const result = cli(t, { runs: green(NEWER) });
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(JSON.parse(result.stdout), resolve(green(NEWER)));
});

test("CLI allows genuine empty history to fall back", t => {
    const result = cli(t);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(JSON.parse(result.stdout).acceptedRunIds, null);
});

test("CLI includes non-PR workflow events instead of hiding a failed push run", t => {
    const result = cli(t, { runs: [...green(NEWER), run(NEWER, REQUIRED_WORKFLOWS[0], 3, {
        event: "push", pull_requests: [], conclusion: "failure"
    })] });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(JSON.parse(result.stdout).lastAcceptedRef, FROZEN_TARGET_REF);
    assert.equal(JSON.parse(result.stdout).acceptedRunIds, null);
});

test("CLI follows all pages before accepting a head, including a failure beyond the first page", t => {
    const runs = [green(NEWER)[0]];
    for (let id = 2; id <= 102; id += 1) {
        runs.push(run(NEWER, REQUIRED_WORKFLOWS[1], id, {
            created_at: new Date(Date.UTC(2026, 8, 5, 12, 0, id)).toISOString(),
            conclusion: id === 102 ? "failure" : "success"
        }));
    }
    const result = cli(t, { runs });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(JSON.parse(result.stdout).lastAcceptedRef, FROZEN_TARGET_REF);
    assert.equal(JSON.parse(result.stdout).acceptedRunIds, null);
});

for (const mode of ["api-error", "malformed-json", "wrong-head", "changed-pr", "ancestry-error", "partial-page", "excess-history"]) {
    test(`CLI fails closed without reference output on ${mode}`, t => {
        const result = cli(t, { mode });
        assert.equal(result.status, 1);
        assert.equal(result.stdout, "");
        assert.match(result.stderr, /Optimization reference resolution failed/);
    });
}

test("CLI rejects unknown or missing arguments before accessing GitHub", () => {
    for (const args of [[], ["--repo", "owner/repo", "--oops", "1"], ["--repo"]]) {
        const result = spawnSync(process.execPath, [script, ...args], { encoding: "utf8" });
        assert.equal(result.status, 1);
        assert.equal(result.stdout, "");
    }
});

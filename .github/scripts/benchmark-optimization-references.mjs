#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { pathToFileURL } from "node:url";

// This reference is the optimization objective, not a moving regression baseline.
export const FROZEN_TARGET_REF = "4e328b0109e13c896b74004823fb049fcb19251a";
export const TARGET_SPEEDUP = 10;
export const REQUIRED_WORKFLOWS = [
    ".github/workflows/build.yml",
    ".github/workflows/benchmark.yml"
];

function requireValue(condition, message) {
    if (!condition) throw new Error(message);
}

function sha(value, label) {
    requireValue(typeof value === "string" && /^[0-9a-f]{40}$/.test(value), `${label} must be a full lowercase commit SHA`);
    return value;
}

function timestamp(value, label) {
    requireValue(typeof value === "string" && Number.isFinite(Date.parse(value)), `${label} must be a timestamp`);
    return Date.parse(value);
}

function validateRun(run) {
    requireValue(run && typeof run === "object", "Workflow run must be an object");
    requireValue(Number.isSafeInteger(run.id) && run.id > 0, "Workflow run id is invalid");
    requireValue(Number.isSafeInteger(run.run_attempt) && run.run_attempt > 0, "Workflow run attempt is invalid");
    sha(run.head_sha, "Workflow head_sha");
    requireValue(typeof run.path === "string" && typeof run.event === "string", "Workflow identity is missing");
    requireValue(typeof run.status === "string" && run.status.length > 0, "Workflow status is missing");
    requireValue(run.conclusion === null || typeof run.conclusion === "string", "Workflow conclusion is missing");
    requireValue(run.status !== "completed" || typeof run.conclusion === "string", "Completed workflow has no conclusion");
    requireValue(run.status === "completed" || run.conclusion === null, "Unfinished workflow has a conclusion");
    timestamp(run.created_at, "Workflow created_at");
    requireValue(run.run_attempt === 1 || run.run_started_at != null, "Rerun attempt is missing its start timestamp");
    timestamp(run.run_started_at ?? run.created_at, "Workflow run_started_at");
    requireValue(Array.isArray(run.pull_requests), "Workflow pull_requests is missing");
    for (const pr of run.pull_requests) {
        requireValue(pr && Number.isSafeInteger(pr.number) && pr.number > 0, "Workflow PR association is invalid");
    }
}

function latestRun(runs) {
    const attempts = new Map();
    for (const run of runs) {
        if (!attempts.has(run.id) || attempts.get(run.id).run_attempt < run.run_attempt) attempts.set(run.id, run);
    }
    return [...attempts.values()].sort((left, right) =>
        timestamp(right.run_started_at ?? right.created_at, "Workflow run_started_at") -
            timestamp(left.run_started_at ?? left.created_at, "Workflow run_started_at") ||
        right.id - left.id || right.run_attempt - left.run_attempt
    )[0];
}

/**
 * Resolve references from complete, supplied Git/API evidence. Chains are newest
 * first and include the current head and frozen start. ancestorRefs may include
 * ancestors reached through merge parents; only first-parent commits can be kept.
 * Runs must include every attempt returned by GitHub for the inspected heads.
 */
export function resolveOptimizationReferences({
    prNumber, currentHead, currentPrBase, firstParentChain,
    ancestorRefs = firstParentChain,
    baseAncestorRefs = firstParentChain?.includes(currentPrBase) ? firstParentChain.slice(firstParentChain.indexOf(currentPrBase)) : [],
    workflowRuns
}) {
    requireValue(Number.isSafeInteger(prNumber) && prNumber > 0, "PR number is invalid");
    sha(currentHead, "Current head");
    sha(currentPrBase, "Current PR base");
    requireValue(Array.isArray(firstParentChain) && firstParentChain.length > 0, "First-parent chain is missing");
    firstParentChain.forEach(ref => sha(ref, "First-parent commit"));
    requireValue(new Set(firstParentChain).size === firstParentChain.length, "First-parent chain contains duplicates");
    requireValue(firstParentChain[0] === currentHead, "First-parent chain does not start at current head");
    const startIndex = firstParentChain.indexOf(FROZEN_TARGET_REF);
    requireValue(startIndex >= 0, "Frozen starting main must be on the head's first-parent ancestry");
    requireValue(Array.isArray(ancestorRefs), "Ancestor evidence is missing");
    ancestorRefs.forEach(ref => sha(ref, "Ancestor commit"));
    const ancestors = new Set(ancestorRefs);
    requireValue(firstParentChain.every(ref => ancestors.has(ref)), "First-parent commits contradict ancestor evidence");
    requireValue(ancestors.has(currentPrBase), "Current PR base is not an ancestor of current head");
    requireValue(Array.isArray(baseAncestorRefs), "Base ancestor evidence is missing");
    baseAncestorRefs.forEach(ref => sha(ref, "Base ancestor commit"));
    requireValue(baseAncestorRefs.includes(currentPrBase) && baseAncestorRefs.includes(FROZEN_TARGET_REF), "Frozen starting main is not an ancestor of current PR base");
    requireValue(baseAncestorRefs.every(ref => ancestors.has(ref)), "Base ancestors contradict head ancestor evidence");
    requireValue(Array.isArray(workflowRuns), "Workflow run evidence is missing");
    workflowRuns.forEach(validateRun);
    const seen = new Set();
    for (const run of workflowRuns) {
        const key = `${run.id}:${run.run_attempt}`;
        requireValue(!seen.has(key), "Duplicate workflow run attempt in evidence");
        seen.add(key);
    }

    let lastAcceptedRef = currentPrBase;
    let acceptedRunIds = null;
    for (const ref of firstParentChain.slice(1, startIndex)) {
        const sameHeadRuns = workflowRuns.filter(run => run.head_sha === ref &&
            REQUIRED_WORKFLOWS.includes(run.path) &&
            (run.event !== "pull_request" || run.pull_requests.some(pr => pr.number === prNumber)));
        const prRuns = sameHeadRuns.filter(run => run.event === "pull_request");
        const latest = REQUIRED_WORKFLOWS.map(workflow => latestRun(prRuns.filter(run => run.path === workflow)));
        // PR runs prove coverage; other events cannot substitute for them. But an independent
        // latest push/dispatch run must not be hidden by a green PR run of the same workflow.
        const eventGroups = new Map();
        for (const run of sameHeadRuns) {
            const key = `${run.path}\0${run.event}`;
            const group = eventGroups.get(key) ?? [];
            group.push(run);
            eventGroups.set(key, group);
        }
        const latestByEvent = [...eventGroups.values()].map(latestRun);
        const successful = run => run?.status === "completed" && run.conclusion === "success";
        if (latest.every(successful) && latestByEvent.every(successful)) {
            lastAcceptedRef = ref;
            acceptedRunIds = Object.fromEntries(latest.map(run => [run.path, { id: run.id, attempt: run.run_attempt }]));
            break;
        }
    }
    return {
        frozenTargetRef: FROZEN_TARGET_REF,
        targetSpeedup: TARGET_SPEEDUP,
        currentHead,
        currentPrBase,
        lastAcceptedRef,
        regressionRefs: [...new Set([currentPrBase, lastAcceptedRef])],
        acceptedRunIds
    };
}

function command(file, args) {
    return execFileSync(file, args, { encoding: "utf8", maxBuffer: 32 * 1024 * 1024 }).trim();
}

function api(endpoint) {
    return JSON.parse(command("gh", ["api", endpoint]));
}

function workflowRunsForHead(repo, workflow, head) {
    const runs = [];
    let expectedCount;
    for (let page = 1; ; page += 1) {
        const result = api(`repos/${repo}/actions/workflows/${workflow}/runs?head_sha=${head}&per_page=100&page=${page}`);
        requireValue(Number.isSafeInteger(result.total_count) && result.total_count >= 0 && Array.isArray(result.workflow_runs), "Malformed workflow run API response");
        requireValue(result.total_count <= 1000, "Workflow run evidence exceeds GitHub's filtered pagination limit");
        expectedCount ??= result.total_count;
        requireValue(result.total_count === expectedCount, "Workflow history changed during pagination; retry resolution");
        for (const run of result.workflow_runs) {
            validateRun(run);
            requireValue(run.head_sha === head && run.path === `.github/workflows/${workflow}`, "Workflow API returned mismatched evidence");
        }
        runs.push(...result.workflow_runs);
        requireValue(runs.length <= expectedCount, "Workflow run API returned excess results");
        if (runs.length === expectedCount) return runs;
        requireValue(result.workflow_runs.length === 100, "Workflow run API returned incomplete evidence");
    }
}

export function main(args = process.argv.slice(2)) {
    const options = {};
    for (let index = 0; index < args.length; index += 2) {
        requireValue(["--repo", "--pr", "--head", "--base"].includes(args[index]) && args[index + 1] !== undefined, "Usage: --repo OWNER/REPO --pr NUMBER --head SHA [--base SHA]");
        requireValue(options[args[index]] === undefined, `Duplicate option ${args[index]}`);
        options[args[index]] = args[index + 1];
    }
    const repo = options["--repo"];
    requireValue(typeof repo === "string" && /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repo), "Repository must be OWNER/REPO");
    requireValue(/^[1-9][0-9]*$/.test(options["--pr"] ?? ""), "PR number is required");
    const prNumber = Number(options["--pr"]);
    requireValue(Number.isSafeInteger(prNumber), "PR number is invalid");
    const currentHead = sha(options["--head"], "Current head");
    const pr = api(`repos/${repo}/pulls/${prNumber}`);
    requireValue(pr.number === prNumber && pr.head?.sha === currentHead, "Live PR does not match requested head");
    const currentPrBase = sha(pr.base?.sha, "Live PR base");
    if (options["--base"] !== undefined) requireValue(sha(options["--base"], "Requested base") === currentPrBase, "Live PR base changed");
    for (const ref of [currentHead, currentPrBase, FROZEN_TARGET_REF]) {
        requireValue(command("git", ["rev-parse", "--verify", `${ref}^{commit}`]) === ref, "Commit resolution did not match requested SHA");
    }
    command("git", ["merge-base", "--is-ancestor", FROZEN_TARGET_REF, currentPrBase]);
    command("git", ["merge-base", "--is-ancestor", currentPrBase, currentHead]);
    const firstParentChain = command("git", ["rev-list", "--first-parent", currentHead]).split("\n");
    const ancestorRefs = command("git", ["rev-list", currentHead]).split("\n");
    const baseAncestorRefs = command("git", ["rev-list", currentPrBase]).split("\n");
    const evidence = { prNumber, currentHead, currentPrBase, firstParentChain, ancestorRefs, baseAncestorRefs, workflowRuns: [] };
    let result = resolveOptimizationReferences(evidence);
    for (const ref of firstParentChain.slice(1, firstParentChain.indexOf(FROZEN_TARGET_REF))) {
        for (const workflow of ["build.yml", "benchmark.yml"]) {
            evidence.workflowRuns.push(...workflowRunsForHead(repo, workflow, ref));
        }
        result = resolveOptimizationReferences(evidence);
        if (result.acceptedRunIds !== null) break;
    }
    // Do not publish references for a PR that moved while evidence was fetched.
    const finalPr = api(`repos/${repo}/pulls/${prNumber}`);
    requireValue(finalPr.head?.sha === currentHead && finalPr.base?.sha === currentPrBase, "PR changed during reference resolution");
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    return result;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    try {
        main();
    } catch (error) {
        process.stderr.write(`Optimization reference resolution failed: ${error.message}\n`);
        process.exitCode = 1;
    }
}

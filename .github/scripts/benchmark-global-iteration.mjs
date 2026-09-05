#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

export const FROZEN_TARGET_REF = "4e328b0109e13c896b74004823fb049fcb19251a";
const SHA = /^[0-9a-f]{40}$/;
const WORKFLOWS = [".github/workflows/build.yml", ".github/workflows/benchmark.yml"];

export function validateReferences(references, actualHead = references?.currentHead) {
    if (references === null || typeof references !== "object" || Array.isArray(references)) {
        throw new Error("Reference resolution must be an object");
    }
    for (const key of ["frozenTargetRef", "currentHead", "currentPrBase", "lastAcceptedRef"]) {
        if (typeof references[key] !== "string" || !SHA.test(references[key])) {
            throw new Error(`${key} must be an exact lowercase commit SHA`);
        }
    }
    if (references.frozenTargetRef !== FROZEN_TARGET_REF || references.targetSpeedup !== 10) {
        throw new Error("The final target must remain 10x against frozen main 4e328b01");
    }
    if (actualHead !== references.currentHead) throw new Error("Resolved currentHead differs from the checked-out HEAD");
    const expected = [...new Set([references.currentPrBase, references.lastAcceptedRef])];
    if (!Array.isArray(references.regressionRefs) || references.regressionRefs.length !== expected.length ||
        references.regressionRefs.some((ref, index) => ref !== expected[index])
    ) {
        throw new Error("regressionRefs must be the unique ordered currentPrBase and lastAcceptedRef");
    }
    if (expected.includes(references.currentHead) || references.frozenTargetRef === references.currentHead) {
        throw new Error("The candidate cannot be its own regression or final-target reference");
    }
    if (references.acceptedRunIds === null) {
        if (references.lastAcceptedRef !== references.currentPrBase) {
            throw new Error("A distinct lastAcceptedRef requires both accepted workflow runs");
        }
    } else {
        const runs = references.acceptedRunIds;
        if (runs === null || typeof runs !== "object" || Array.isArray(runs) ||
            Object.keys(runs).length !== WORKFLOWS.length || WORKFLOWS.some((workflow) => {
                const run = runs[workflow];
                return !Number.isSafeInteger(run?.id) || run.id <= 0 ||
                    !Number.isSafeInteger(run?.attempt) || run.attempt <= 0;
            })
        ) throw new Error("acceptedRunIds must identify both successful workflow runs or be null");
    }
    return [...new Set([...expected, references.frozenTargetRef])];
}

function validateComparison(status) {
    if (status === null || typeof status !== "object" || Array.isArray(status)) {
        return "Missing or invalid comparison status";
    }
    if (["passed", "regressionPassed", "targetAchieved", "regressionOnly"]
        .some((key) => typeof status[key] !== "boolean") || !status.regressionOnly ||
        status.minimumSpeedup !== 10 || !Array.isArray(status.runs) ||
        !Array.isArray(status.errors) || !Array.isArray(status.targetErrors) ||
        [...status.errors, ...status.targetErrors].some((error) => typeof error !== "string")
    ) return "Comparison status does not contain the required 10x regression-only result";
    if (status.passed !== status.regressionPassed ||
        status.regressionPassed !== (status.errors.length === 0) ||
        status.targetAchieved !== (status.regressionPassed && status.targetErrors.length === 0) ||
        (status.regressionPassed && (status.runs.length !== 3 || status.runs.some((run, index) =>
            run?.order !== ["candidate-base", "base-candidate", "candidate-base"][index])))
    ) return "Comparison flags are inconsistent with the recorded errors or paired forks";
    const expectedShapes = ["global-wide-wrapped-case-insensitive", "global-wide-wrapped-case-insensitive-distinct"];
    if (status.runs.length !== 3) return "Comparison requires exactly three paired forks";
    const numericalTargetErrors = [];
    const positive = value => Number.isFinite(value) && value > 0;
    for (const [index, run] of status.runs.entries()) {
        if (!positive(run?.baseP95LatencyNanos) || !positive(run?.p95LatencyNanos)) {
            return `Comparison pair-${index + 1}: aggregate P95 measurements must be finite positive numbers`;
        }
        const wrapped = run.wrappedShapeRuns;
        if (!Array.isArray(wrapped) || wrapped.length !== expectedShapes.length ||
            new Set(wrapped.map(shape => shape?.shape)).size !== expectedShapes.length ||
            wrapped.some(shape => !expectedShapes.includes(shape?.shape))
        ) return `Comparison pair-${index + 1}: both exact wrapped shape identities are required`;
        const speedup = run.baseP95LatencyNanos / run.p95LatencyNanos;
        if (speedup < 10) numericalTargetErrors.push(`pair-${index + 1}: P95 speedup ${speedup.toFixed(2)}x; ` +
            "required 10.00x in every independent fork");
        for (const shape of wrapped) {
            if (!positive(shape.baseLatencyNanos) || !positive(shape.latencyNanos)) {
                return `Comparison pair-${index + 1}/${shape.shape}: wrapped P95 measurements must be finite positive numbers`;
            }
            const wrappedSpeedup = shape.baseLatencyNanos / shape.latencyNanos;
            if (wrappedSpeedup < 10) numericalTargetErrors.push(`pair-${index + 1}: ${shape.shape} P95 speedup ` +
                `${wrappedSpeedup.toFixed(2)}x; required 10.00x`);
        }
    }
    if (status.targetAchieved !== (status.regressionPassed && numericalTargetErrors.length === 0) ||
        status.targetErrors.length !== numericalTargetErrors.length ||
        [...status.targetErrors].sort().some((error, index) => error !== [...numericalTargetErrors].sort()[index])
    ) return "Comparison target flags/errors contradict the recorded aggregate or wrapped P95 measurements";
    return null;
}

export function aggregateIteration(references, executions, { requireTarget = false } = {}) {
    const refs = validateReferences(references);
    if (typeof requireTarget !== "boolean") throw new Error("requireTarget must be a boolean");
    if (!Array.isArray(executions)) throw new Error("Executions must be an array");
    const errors = [];
    const results = {};
    for (const execution of executions) {
        if (!refs.includes(execution?.referenceSha)) errors.push("Unexpected execution reference");
    }
    for (const ref of refs) {
        const matches = executions.filter((execution) => execution?.referenceSha === ref);
        if (matches.length !== 1) {
            errors.push(`${ref}: expected exactly one execution, found ${matches.length}`);
            continue;
        }
        const execution = matches[0];
        const invalid = validateComparison(execution.status);
        const executionError = typeof execution.error === "string" ? execution.error : null;
        let failure = invalid ?? executionError;
        if (!failure && (!Number.isInteger(execution.exitCode) || execution.exitCode < 0 ||
            (execution.exitCode !== 0 && execution.status.passed)
        )) failure = "Driver failed without a valid failed comparison status";
        results[ref] = {
            exitCode: execution.exitCode ?? null,
            status: execution.status ?? null,
            error: failure,
            report: `reference-${ref}/global-wide-report.md`
        };
        if (failure) errors.push(`${ref}: ${failure}`);
        else if (!execution.status.regressionPassed) {
            errors.push(...execution.status.errors.map((error) => `${ref}: ${error}`));
        }
    }
    const regressionPassed = errors.length === 0;
    const accepted = results[references.lastAcceptedRef];
    const progressErrors = [];
    if (accepted?.error !== null || !accepted.status.regressionPassed || accepted.status.runs.length !== 3) {
        progressErrors.push("Valid passing paired evidence against the last accepted iteration is required for progress");
    } else {
        accepted.status.runs.forEach((run, index) => {
            const baseP95 = run?.baseP95LatencyNanos;
            const candidateP95 = run?.p95LatencyNanos;
            if (!Number.isFinite(baseP95) || !Number.isFinite(candidateP95) || baseP95 <= 0 || candidateP95 <= 0) {
                progressErrors.push(`last-accepted pair-${index + 1}: finite positive P95 measurements are required`);
            } else if (candidateP95 >= baseP95) {
                progressErrors.push(`last-accepted pair-${index + 1}: candidate P95 ${candidateP95} ` +
                    `must improve on reference P95 ${baseP95}`);
            }
        });
    }
    const progressAchieved = progressErrors.length === 0;
    const iterationPassed = regressionPassed && progressAchieved;
    const frozen = results[references.frozenTargetRef];
    const frozenTargetAchieved = frozen?.error === null && frozen.status.targetAchieved === true;
    const targetAchieved = iterationPassed && frozenTargetAchieved;
    const targetErrors = frozen?.error === null
        ? frozen.status.targetErrors.map((error) => `${references.frozenTargetRef}: ${error}`)
        : ["Valid frozen-target comparison evidence is missing"];
    if (!targetAchieved && targetErrors.length === 0) {
        targetErrors.push("The final target also requires every reference regression check and iteration progress to pass");
    }
    return {
        schema: "graphite-global-iteration-v1",
        passed: iterationPassed && (!requireTarget || targetAchieved),
        iterationPassed,
        regressionPassed,
        progressAchieved,
        progressErrors,
        targetAchieved,
        frozenTargetAchieved,
        requireTarget,
        minimumSpeedup: 10,
        errors: [...errors, ...progressErrors, ...(requireTarget ? targetErrors : [])],
        targetErrors,
        frozenTargetRef: references.frozenTargetRef,
        targetSpeedup: references.targetSpeedup,
        currentHead: references.currentHead,
        currentPrBase: references.currentPrBase,
        lastAcceptedRef: references.lastAcceptedRef,
        regressionRefs: references.regressionRefs,
        acceptedRunIds: references.acceptedRunIds,
        evaluatedRefs: refs,
        comparisons: results,
        runs: Array.isArray(frozen?.status?.runs) ? frozen.status.runs : []
    };
}

export function renderIterationReport(comparison, reports = {}) {
    const lines = [
        "### Global-query iteration verification", "",
        `Iteration acceptance: **${comparison.iterationPassed ? "passed" : "failed"}**.`,
        `No-regression checks: **${comparison.regressionPassed ? "passed" : "failed"}**.`,
        `P95 progress against the last accepted iteration in every paired fork: ` +
            `**${comparison.progressAchieved ? "achieved" : "not achieved"}**.`,
        `Final 10x target against frozen main \`${comparison.frozenTargetRef}\`: ` +
            `**${comparison.targetAchieved ? "achieved" : "not achieved"}**.`,
        `This run ${comparison.requireTarget ? "requires" : "reports separately"} the final 10x target.`, "",
        `Candidate: \`${comparison.currentHead}\`. Current PR base: \`${comparison.currentPrBase}\`. ` +
            `Last accepted iteration: \`${comparison.lastAcceptedRef}\`.`, "",
        "A passing iteration does not establish completion of the 10x objective.", ""
    ];
    if (comparison.errors.length) lines.push("Blocking failures:", "", ...comparison.errors.map((error) => `- ${error}`), "");
    if (comparison.targetErrors.length) lines.push("Final target evidence:", "", ...comparison.targetErrors.map((error) => `- ${error}`), "");
    for (const ref of comparison.evaluatedRefs) {
        lines.push(`#### Reference ${ref}`, "",
            `[Individual report](reference-${ref}/global-wide-report.md)`, "",
            reports[ref] ?? "Individual report unavailable; inspect the recorded execution failure.", "");
    }
    return lines.join("\n");
}

function parseArgs(argv) {
    const values = {};
    const valued = new Set(["references", "manifest", "fixtures", "repository", "output"]);
    for (let index = 0; index < argv.length; index++) {
        const key = argv[index].replace(/^--/, "");
        if (argv[index] !== `--${key}` || key in values) throw new Error(`Invalid or duplicate argument: ${argv[index]}`);
        if (key === "require-target") values[key] = true;
        else if (valued.has(key) && argv[index + 1] && !argv[index + 1].startsWith("--")) values[key] = argv[++index];
        else throw new Error(`Unknown argument or missing value: ${argv[index]}`);
    }
    for (const key of valued) if (!values[key]) throw new Error(`Missing --${key}`);
    if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(values.repository)) throw new Error("Invalid --repository");
    return values;
}

export function main(argv) {
    const args = parseArgs(argv);
    const references = JSON.parse(fs.readFileSync(args.references, "utf8"));
    const head = execFileSync("git", ["rev-parse", "HEAD"], { encoding: "utf8" }).trim();
    const refs = validateReferences(references, head);
    const manifest = path.resolve(args.manifest);
    const fixtures = path.resolve(args.fixtures);
    if (!fs.statSync(manifest).isFile() || !fs.statSync(fixtures).isDirectory()) throw new Error("Missing fixture inputs");
    const output = path.resolve(args.output);
    fs.mkdirSync(output, { recursive: true });
    const driver = path.join(path.dirname(fileURLToPath(import.meta.url)), "run-real64-global-wide.sh");
    const executions = [];
    const reports = {};
    for (const ref of refs) {
        const directory = path.join(output, `reference-${ref}`);
        let exitCode = 0;
        let error = null;
        let status = null;
        try {
            // Refuse to reuse stale status files from an earlier run.
            fs.mkdirSync(directory);
            try {
                execFileSync(driver, [manifest, fixtures, ref, head, args.repository, directory], {
                    stdio: "inherit",
                    env: {
                        ...process.env,
                        GRAPHITE_PRESSURE_REGRESSION_ONLY: "true",
                        GRAPHITE_PRESSURE_MINIMUM_SPEEDUP: "10",
                        GRAPHITE_PRESSURE_PUBLISH_EVIDENCE: "false"
                    }
                });
            } catch (failure) {
                exitCode = Number.isInteger(failure.status) ? failure.status : null;
            }
            status = JSON.parse(fs.readFileSync(path.join(directory, "global-wide-status.json"), "utf8"));
            reports[ref] = fs.readFileSync(path.join(directory, "global-wide-report.md"), "utf8");
        } catch (failure) {
            error = failure instanceof Error ? failure.message : String(failure);
        }
        executions.push({ referenceSha: ref, exitCode, status, error });
    }
    const comparison = aggregateIteration(references, executions, { requireTarget: args["require-target"] === true });
    fs.writeFileSync(path.join(output, "global-wide-status.json"), `${JSON.stringify(comparison, null, 2)}\n`);
    fs.writeFileSync(path.join(output, "global-wide-report.md"), renderIterationReport(comparison, reports));
    if (!comparison.passed) process.exitCode = 1;
    return comparison;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try {
        main(process.argv.slice(2));
    } catch (error) {
        console.error(error instanceof Error ? error.stack : String(error));
        process.exitCode = 1;
    }
}

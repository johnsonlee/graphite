import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import {
    buildBenchmarkPage,
    renderMarkdown,
    snapshotKey,
    updateBenchmarkHistory,
    validatePairedEvidence
} from "./benchmark-pages.mjs";

const BASE_SHA = "a".repeat(40);
const CANDIDATE_SHA = "b".repeat(40);
const INTEGRATION_SHA = "c".repeat(40);
const TREE_SHA = "d".repeat(40);
const SOURCE_RUN_URL = "https://example.invalid/benchmark-run";

function pairedEvidence(overrides = {}) {
    const provenance = {
        schemaVersion: 1,
        integrationSha: INTEGRATION_SHA,
        integrationParentSha: BASE_SHA,
        integrationTreeSha: TREE_SHA,
        baseSha: BASE_SHA,
        candidateSha: CANDIDATE_SHA,
        candidateTreeSha: TREE_SHA,
        sourcePr: 123,
        sourceRunId: 456,
        sourceRunUrl: SOURCE_RUN_URL,
        ...overrides
    };
    const reportMarkdown = [
        "## Benchmark Regression Gate",
        "",
        "**PASS**",
        "",
        `Base: \`${BASE_SHA.slice(0, 12)}\`  `,
        `PR: \`${CANDIDATE_SHA.slice(0, 12)}\``,
        ""
    ].join("\n");
    return {
        reportMarkdown,
        status: {
            passed: true,
            baseSha: BASE_SHA,
            candidateSha: CANDIDATE_SHA,
            runner: "Linux X64",
            runUrl: SOURCE_RUN_URL,
            body: reportMarkdown
        },
        provenance
    };
}

function result({
    benchmark = "io.johnsonlee.graphite.cypher.CypherBenchmark.query",
    score = 10,
    params = { size: "4" }
} = {}) {
    return {
        benchmark,
        mode: "avgt",
        params,
        primaryMetric: {
            score,
            scoreConfidence: [score * 0.9, score * 1.1],
            scoreUnit: "us/op"
        }
    };
}

test("markdown rendering supports gate tables without allowing raw HTML", () => {
    const html = renderMarkdown("### Gate\n\n| Benchmark | Gate |\n|---|---|\n| `<script>` | **FAIL** |\n");

    assert.match(html, /<h4/);
    assert.match(html, /<table>/);
    assert.match(html, /&lt;script&gt;/);
    assert.doesNotMatch(html, /<script>/);
    assert.match(html, /data-attention="true"/);
});

test("snapshot series keys canonicalize parameter order", () => {
    assert.equal(
        snapshotKey(result({ params: { right: "2", left: "1" } })),
        snapshotKey(result({ params: { left: "1", right: "2" } }))
    );
    assert.notEqual(
        snapshotKey(result()),
        snapshotKey({ ...result(), mode: "ss" })
    );
});

test("history replaces a rerun of the same SHA and rejects incompatible data", () => {
    const old = {
        schemaVersion: 1,
        repository: "johnsonlee/graphite",
        sha: "a".repeat(40),
        generatedAt: "2026-08-29T00:00:00.000Z",
        runUrl: "https://example.invalid/old",
        snapshot: [result({ score: 11 })]
    };
    const current = { ...old, generatedAt: "2026-08-30T00:00:00.000Z", snapshot: [result({ score: 10 })] };
    const history = updateBenchmarkHistory([old], current);

    assert.equal(history.length, 1);
    assert.equal(history[0].snapshot[0].primaryMetric.score, 10);
    assert.throws(
        () => updateBenchmarkHistory([{ ...old, repository: "other/repo" }], current),
        /incompatible or malformed/
    );
    assert.throws(
        () => updateBenchmarkHistory([{ ...old, pairedEvidence: { baseSha: BASE_SHA } }], current),
        /incompatible or malformed/
    );
});

test("paired evidence accepts an exact squash integration and persists both compared SHAs", () => {
    const fixture = pairedEvidence();
    const validation = validatePairedEvidence({
        ...fixture,
        commitSha: INTEGRATION_SHA
    });

    assert.equal(validation.available, true);
    assert.notEqual(validation.evidence.candidateSha, validation.evidence.integrationSha);
    assert.equal(validation.evidence.baseSha, validation.evidence.integrationParentSha);
    assert.equal(validation.evidence.candidateTreeSha, validation.evidence.integrationTreeSha);

    const html = buildBenchmarkPage({
        ...fixture,
        snapshot: [result()],
        commitSha: INTEGRATION_SHA,
        runUrl: "https://example.invalid/pages-run",
        generatedAt: "2026-08-30T01:02:03.000Z"
    });
    const payload = html.match(/<script id="benchmark-history" type="application\/json">([^]*?)<\/script>/);
    assert.ok(payload);
    const stored = JSON.parse(payload[1])[0].pairedEvidence;
    assert.equal(stored.baseSha, BASE_SHA);
    assert.equal(stored.candidateSha, CANDIDATE_SHA);
});

test("paired evidence is unavailable for a behind-base squash or mismatched artifact status", () => {
    const behindBase = pairedEvidence({ integrationParentSha: "e".repeat(40) });
    assert.deepEqual(
        validatePairedEvidence({ ...behindBase, commitSha: INTEGRATION_SHA }).available,
        false
    );

    const mismatchedStatus = pairedEvidence();
    mismatchedStatus.status = { ...mismatchedStatus.status, candidateSha: "f".repeat(40) };
    assert.equal(
        validatePairedEvidence({ ...mismatchedStatus, commitSha: INTEGRATION_SHA }).available,
        false
    );

    const html = buildBenchmarkPage({
        ...behindBase,
        snapshot: [result()],
        commitSha: INTEGRATION_SHA,
        runUrl: "https://example.invalid/pages-run",
        generatedAt: "2026-08-30T01:02:03.000Z"
    });
    assert.match(html, /pill unavailable">UNAVAILABLE/);
    assert.match(html, /Paired PR evidence unavailable/);
    assert.doesNotMatch(html, /Open paired benchmark run/);
});

test("benchmark page is self-contained, classified, interactive, and injection-safe", () => {
    const malicious = result({ benchmark: "</script><script>alert(1)</script>" });
    const fixture = pairedEvidence();
    const html = buildBenchmarkPage({
        ...fixture,
        snapshot: [malicious],
        commitSha: INTEGRATION_SHA,
        branch: "main",
        repository: "johnsonlee/graphite",
        runUrl: "https://example.invalid/pages-run",
        generatedAt: "2026-08-30T01:02:03.000Z"
    });

    assert.match(html, /Benchmark Observatory/);
    assert.match(html, /pill good">PASS/);
    assert.match(html, /Semantic correctness/);
    assert.match(html, /Build and persistence lifecycle/);
    assert.match(html, /benchmark-history/);
    assert.match(html, /Content-Security-Policy/);
    assert.match(html, /Filter benchmark rows/);
    assert.doesNotMatch(html, /<script src=/);
    assert.doesNotMatch(html, /<link[^>]+stylesheet/);
    assert.doesNotMatch(html, /<\/script><script>alert/);
    const payload = html.match(/<script id="benchmark-history" type="application\/json">([^]*?)<\/script>/);
    assert.ok(payload);
    assert.equal(JSON.parse(payload[1])[0].sha, INTEGRATION_SHA);
    assert.equal(JSON.parse(payload[1])[0].pairedEvidence.candidateSha, CANDIDATE_SHA);
});

test("CLI creates a bounded Pages artifact and can recover its history", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "graphite-pages-"));
    try {
        const snapshot = path.join(directory, "snapshot.json");
        const output = path.join(directory, "site");
        fs.writeFileSync(snapshot, JSON.stringify([result()]));
        const build = spawnSync(process.execPath, [
            new URL("./benchmark-pages.mjs", import.meta.url).pathname,
            "build",
            "--snapshot", snapshot,
            "--output", output,
            "--sha", "c".repeat(40),
            "--run-url", "https://example.invalid/run",
            "--generated-at", "2026-08-30T00:00:00.000Z"
        ], { encoding: "utf8" });
        assert.equal(build.status, 0, build.stderr);
        assert.equal(fs.existsSync(path.join(output, "index.html")), true);
        assert.equal(fs.existsSync(path.join(output, "404.html")), true);
        assert.ok(fs.statSync(path.join(output, "index.html")).size < 5 * 1024 * 1024);

        const history = path.join(directory, "history.json");
        const extract = spawnSync(process.execPath, [
            new URL("./benchmark-pages.mjs", import.meta.url).pathname,
            "extract-history",
            "--input", path.join(output, "index.html"),
            "--output", history
        ], { encoding: "utf8" });
        assert.equal(extract.status, 0, extract.stderr);
        assert.equal(JSON.parse(fs.readFileSync(history))[0].sha, "c".repeat(40));
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

test("Pages workflow is main-only and uses least-privilege official deployment actions", () => {
    const workflow = fs.readFileSync(new URL("../workflows/benchmark-pages.yml", import.meta.url), "utf8");

    assert.match(workflow, /push:\n    branches: \[main\]/);
    assert.match(workflow, /workflow_dispatch:/);
    assert.doesNotMatch(workflow, /pull_request(?:_target)?:/);
    assert.match(workflow, /group: pages/);
    assert.match(workflow, /cancel-in-progress: false/);
    assert.match(workflow, /queue: max/);
    assert.equal((workflow.match(/if: github\.ref == 'refs\/heads\/main'/g) ?? []).length, 3);
    assert.match(workflow, /actions\/configure-pages@v6/);
    assert.match(workflow, /actions\/upload-pages-artifact@v5/);
    assert.match(workflow, /actions\/deploy-pages@v5/);
    assert.match(workflow, /pages: write/);
    assert.match(workflow, /id-token: write/);
    assert.match(workflow, /environment:\n      name: github-pages/);
    assert.match(workflow, /benchmark-pages-snapshot-\$\{\{ github\.sha \}\}-\$\{\{ github\.run_id \}\}/);
    assert.match(workflow, /retention-days: 90/);
    assert.match(workflow, /benchmark-jmh-isolation\.init\.gradle/);
    assert.match(workflow, /verify-jmh-jar-isolation\.sh/);
    assert.match(workflow, /:cypher:testClasses :cypher:jmhJar/);
    assert.match(workflow, /item\.merge_commit_sha === context\.sha/);
    assert.match(workflow, /baseSha !== integrationParentSha/);
    assert.match(workflow, /candidateTreeSha !== integrationTreeSha/);
    assert.match(workflow, /benchmark-source\/paired-provenance\.json/);
    assert.match(workflow, /--provenance benchmark-source\/paired-provenance\.json/);
    assert.doesNotMatch(workflow, /PRE_PR_95|confirm-latency|method-compatibility/);
});

test("Pages deployment refuses stale main reruns immediately before publishing", () => {
    const workflow = fs.readFileSync(new URL("../workflows/benchmark-pages.yml", import.meta.url), "utf8");
    const deployJob = workflow.slice(workflow.indexOf("\n  deploy:"));
    const staleGuard = deployJob.indexOf("- name: Reject a stale main workflow run");
    const deployAction = deployJob.indexOf("- name: Deploy benchmark observatory");

    assert.ok(staleGuard >= 0 && deployAction > staleGuard);
    assert.match(deployJob.slice(staleGuard, deployAction), /github\.rest\.repos\.getBranch/);
    assert.match(deployJob.slice(staleGuard, deployAction), /branch: 'main'/);
    assert.match(deployJob.slice(staleGuard, deployAction), /currentMainSha !== context\.sha/);
    assert.match(deployJob.slice(staleGuard, deployAction), /core\.setFailed/);
    assert.doesNotMatch(deployJob.slice(deployAction), /github\.rest\.repos\.getBranch/);
});

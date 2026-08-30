#!/usr/bin/env node

import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
    buildTagDiffReport,
    comparableHarnessSource,
    comparableJmhConfiguration,
    compareSemver,
    COVERAGE_TAXONOMY,
    parseSemver,
    REPRESENTATIVE_BENCHMARKS,
    resolvePreviousTag,
    validateJmhResults
} from "./benchmark-tag-diff.mjs";

const SCRIPT = new URL("./benchmark-tag-diff.mjs", import.meta.url).pathname;

function result(benchmark, score = 10, unit = "us/op") {
    return {
        benchmark,
        mode: "avgt",
        params: {},
        primaryMetric: {
            score,
            scoreError: 0.5,
            scoreConfidence: [score - 1, score + 1],
            scoreUnit: unit
        }
    };
}

function results(multiplier = 1) {
    return REPRESENTATIVE_BENCHMARKS.map((benchmark, index) => result(benchmark, (index + 1) * 10 * multiplier));
}

function harnessSource(simpleValue) {
    const methods = REPRESENTATIVE_BENCHMARKS.map((benchmark) => benchmark.split(".").at(-1));
    return `import org.openjdk.jmh.annotations.Benchmark

open class CypherBenchmark {
    private val fixture = 1

${methods.map((name) => `    @Benchmark
    fun ${name}(): Int {
        return ${name === "simpleNodeMatch" ? simpleValue : "fixture"}
    }`).join("\n\n")}
}
`;
}

function jmhConfigurationSources(version = "1.37") {
    return {
        "build.gradle.kts": 'plugins {\n    id("me.champeau.jmh") version "0.7.2" apply false\n}\n',
        "gradle/libs.versions.toml": `[versions]\njmh = "${version}"\n\n[libraries]\njmh-core = { module = "org.openjdk.jmh:jmh-core", version.ref = "jmh" }\njmh-generator = { module = "org.openjdk.jmh:jmh-generator-annprocess", version.ref = "jmh" }\n`,
        "graphite-cypher/build.gradle.kts": `plugins {
    id("me.champeau.jmh")
}

dependencies {
    jmh(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.generator)
}

jmh {
    warmupIterations.set(3)
    iterations.set(5)
}
`
    };
}

function metadata(previous = {
    tag: "v2.4.3",
    sha: "b".repeat(40),
    refSha: "b".repeat(40),
    harnessFingerprint: "c".repeat(64)
}) {
    return {
        schemaVersion: 1,
        current: {
            tag: "v2.4.4",
            sha: "a".repeat(40),
            refSha: "a".repeat(40),
            harnessFingerprint: "c".repeat(64)
        },
        previous,
        baselineAvailable: previous !== null,
        resolvedAt: "2026-08-30T00:00:00.000Z"
    };
}

test("SemVer parsing and ordering follow release precedence", () => {
    const alpha = parseSemver("v1.2.0-alpha.1");
    const beta = parseSemver("v1.2.0-beta.1+build.7");
    const stable = parseSemver("v1.2.0");
    assert.ok(alpha && beta && stable);
    assert.equal(compareSemver(alpha, beta) < 0, true);
    assert.equal(compareSemver(beta, stable) < 0, true);
    assert.equal(compareSemver(parseSemver("v2.0.0+one"), parseSemver("v2.0.0+two")), 0);
    assert.equal(parseSemver("v1.02.0"), null);
    assert.equal(parseSemver("v1.2.0-01"), null);
    assert.equal(parseSemver("release-1.2.0"), null);
});

test("previous tag resolution is semantic, deterministic, and ignores malformed tags", () => {
    const tags = ["v1.2.0", "v1.2.0-alpha.1", "v1.1.9", "v1.2.0-beta.1", "vbad", "v1.2.1"];
    assert.equal(resolvePreviousTag(tags, "v1.2.0"), "v1.2.0-beta.1");
    assert.equal(resolvePreviousTag(tags, "v1.2.0-alpha.1"), "v1.1.9");
    assert.equal(resolvePreviousTag(["v1.0.0"], "v1.0.0"), null);
    assert.throws(() => resolvePreviousTag(tags, "vbad"), /not valid SemVer/);
    assert.throws(() => resolvePreviousTag(tags, "v9.9.9"), /missing from the fetched tag set/);
});

test("harness fingerprint source ignores unrelated benchmarks but includes selected method bodies", () => {
    const base = harnessSource(1);
    const withUnrelatedBenchmark = base.replace(
        /\n}\n$/,
        "\n\n    @Benchmark\n    fun unrelatedBenchmark(): Int {\n        return 99\n    }\n}\n",
    );
    assert.equal(comparableHarnessSource(withUnrelatedBenchmark), comparableHarnessSource(base));
    assert.notEqual(comparableHarnessSource(harnessSource(2)), comparableHarnessSource(base));
});

test("JMH configuration fingerprint includes runtime version, plugin, dependencies, and module options", () => {
    const base = comparableJmhConfiguration(jmhConfigurationSources("1.37"));
    const runtimeDrift = comparableJmhConfiguration(jmhConfigurationSources("1.38"));
    assert.notEqual(runtimeDrift, base);

    const unrelatedTask = jmhConfigurationSources("1.37");
    unrelatedTask["graphite-cypher/build.gradle.kts"] += "\ntasks.register(\"unrelatedTest\")\n";
    assert.equal(comparableJmhConfiguration(unrelatedTask), base);
});

test("JMH validation fails closed on drift, duplication, and invalid metrics", () => {
    assert.equal(validateJmhResults(results()).length, REPRESENTATIVE_BENCHMARKS.length);
    assert.throws(() => validateJmhResults(results().slice(1)), /exact representative benchmark set/);
    const duplicate = results();
    duplicate[1] = { ...duplicate[0] };
    assert.throws(() => validateJmhResults(duplicate), /exact representative benchmark set|duplicate/);
    const invalid = results();
    invalid[0].primaryMetric.score = Number.NaN;
    assert.throws(() => validateJmhResults(invalid), /invalid primary metrics/);
    const zero = results();
    zero[0].primaryMetric.score = 0;
    assert.throws(() => validateJmhResults(zero), /invalid primary metrics/);
    const parameterized = results();
    parameterized[0].params = { size: "10" };
    assert.throws(() => validateJmhResults(parameterized), /must not have parameters/);
});

test("HTML report is self-contained, provenance-rich, classified, and injection-safe", () => {
    const report = buildTagDiffReport({
        metadata: metadata(),
        currentResults: results(1.2),
        previousResults: results(),
        repository: "johnsonlee/graphite<script>alert(1)</script>",
        runUrl: "https://example.invalid/run",
        generatedAt: "2026-08-30T01:02:03.000Z"
    });
    assert.match(report.html, /Tag benchmark diff/);
    assert.match(report.html, /v2\.4\.4/);
    assert.match(report.html, new RegExp("a{40}"));
    assert.match(report.html, /v2\.4\.3/);
    assert.match(report.html, new RegExp("b{40}"));
    assert.match(report.html, /\+20\.00%/);
    assert.match(report.html, /No verdict/);
    assert.match(report.html, /Content-Security-Policy/);
    assert.doesNotMatch(report.html, /<script[ >]/);
    assert.doesNotMatch(report.html, /<link[^>]+stylesheet/);
    assert.doesNotMatch(report.html, /<script>alert/);
    for (const area of COVERAGE_TAXONOMY) assert.match(report.html, new RegExp(area.name));
    assert.equal(report.manifest.comparisonStatus, "available");
    assert.equal(report.manifest.measurements.length, 6);
    assert.ok(Math.abs(report.manifest.measurements[0].deltaPercent - 20) < 1e-9);
});

test("first semantic tag renders an explicit unavailable baseline without a verdict", () => {
    const report = buildTagDiffReport({
        metadata: metadata(null),
        currentResults: results(),
        generatedAt: "2026-08-30T01:02:03.000Z"
    });
    assert.match(report.html, /Baseline unavailable/);
    assert.match(report.html, /No lower valid semantic-version tag exists/);
    assert.match(report.html, /no deltas or pass\/fail verdict are fabricated/i);
    assert.doesNotMatch(report.html, /NaN/);
    assert.match(report.summary, /baseline unavailable/i);
    assert.equal(report.manifest.comparisonStatus, "baseline-unavailable");
    assert.ok(report.manifest.measurements.every((measurement) => measurement.deltaPercent === null));
    assert.throws(() => buildTagDiffReport({
        metadata: metadata(null),
        currentResults: results(),
        previousResults: results()
    }), /without a resolved baseline/);
});

test("resolve CLI peels annotated tags and disables deltas when a method body changed", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "graphite-tag-resolve-"));
    try {
        execFileSync("git", ["init", "-q"], { cwd: directory });
        execFileSync("git", ["config", "user.name", "Tag Test"], { cwd: directory });
        execFileSync("git", ["config", "user.email", "tag@example.invalid"], { cwd: directory });
        const harness = path.join(
            directory,
            "graphite-cypher/src/jmh/kotlin/io/johnsonlee/graphite/cypher/CypherBenchmark.kt"
        );
        fs.mkdirSync(path.dirname(harness), { recursive: true });
        fs.writeFileSync(harness, harnessSource(1));
        for (const [file, contents] of Object.entries(jmhConfigurationSources())) {
            const target = path.join(directory, file);
            fs.mkdirSync(path.dirname(target), { recursive: true });
            fs.writeFileSync(target, contents);
        }
        execFileSync("git", ["add", "."], { cwd: directory });
        execFileSync("git", ["commit", "-q", "-m", "one"], { cwd: directory });
        execFileSync("git", ["tag", "v1.0.0"], { cwd: directory });
        fs.writeFileSync(harness, harnessSource(2));
        execFileSync("git", ["commit", "-q", "-am", "two"], { cwd: directory });
        execFileSync("git", ["tag", "-a", "v1.1.0", "-m", "release"], { cwd: directory });
        const commitSha = execFileSync("git", ["rev-parse", "HEAD"], { cwd: directory, encoding: "utf8" }).trim();
        const output = path.join(directory, "metadata.json");
        const run = spawnSync(process.execPath, [
            SCRIPT, "resolve",
            "--current-tag", "v1.1.0",
            "--expected-ref", "refs/tags/v1.1.0",
            "--expected-sha", commitSha,
            "--resolved-at", "2026-08-30T00:00:00.000Z",
            "--output", output
        ], { cwd: directory, encoding: "utf8" });
        assert.equal(run.status, 0, run.stderr);
        const resolved = JSON.parse(fs.readFileSync(output));
        assert.equal(resolved.current.sha, commitSha);
        assert.notEqual(resolved.current.refSha, commitSha);
        assert.equal(resolved.previous.tag, "v1.0.0");
        assert.equal(resolved.baselineAvailable, true);
        assert.notEqual(resolved.current.harnessFingerprint, resolved.previous.harnessFingerprint);
        const report = buildTagDiffReport({
            metadata: resolved,
            currentResults: results(),
            previousResults: results(),
            generatedAt: "2026-08-30T01:02:03.000Z"
        });
        assert.equal(report.manifest.comparisonStatus, "workload-drift");
        assert.equal(report.manifest.workloadCompatible, false);
        assert.ok(report.manifest.measurements.every((measurement) => measurement.deltaPercent === null));
        assert.match(report.html, /Deltas unavailable · workload drift/);
        assert.doesNotMatch(report.html, /\+0\.00%/);
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

test("render CLI writes a bounded HTML report, summary, and audit manifest", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "graphite-tag-render-"));
    try {
        const metadataFile = path.join(directory, "metadata.json");
        const currentFile = path.join(directory, "current.json");
        const previousFile = path.join(directory, "previous.json");
        fs.writeFileSync(metadataFile, JSON.stringify(metadata()));
        fs.writeFileSync(currentFile, JSON.stringify(results(0.9)));
        fs.writeFileSync(previousFile, JSON.stringify(results()));
        const output = path.join(directory, "report", "index.html");
        const summary = path.join(directory, "report", "summary.md");
        const manifest = path.join(directory, "report", "manifest.json");
        const run = spawnSync(process.execPath, [
            SCRIPT, "render",
            "--metadata", metadataFile,
            "--current", currentFile,
            "--previous", previousFile,
            "--output", output,
            "--summary", summary,
            "--manifest", manifest,
            "--generated-at", "2026-08-30T00:00:00.000Z"
        ], { encoding: "utf8" });
        assert.equal(run.status, 0, run.stderr);
        assert.equal(fs.existsSync(output), true);
        assert.equal(fs.existsSync(summary), true);
        assert.equal(fs.existsSync(manifest), true);
        assert.ok(fs.statSync(output).size < 5 * 1024 * 1024);
        assert.equal(JSON.parse(fs.readFileSync(manifest)).measurements.length, 6);
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

test("tag diff workflow is tag-only, isolated from publishing, and least privilege", () => {
    const workflow = fs.readFileSync(new URL("../workflows/benchmark-tag-diff.yml", import.meta.url), "utf8");
    const publish = fs.readFileSync(new URL("../workflows/publish.yml", import.meta.url), "utf8");
    assert.match(workflow, /on:\n  push:\n    tags: \['v\*']\n/);
    assert.doesNotMatch(workflow, /pull_request|workflow_dispatch|repository_dispatch|workflow_call/);
    assert.match(workflow, /permissions:\n  contents: read/);
    assert.doesNotMatch(workflow, /contents: write|actions: write|packages: write|pages: write|id-token: write/);
    assert.match(workflow, /actions\/checkout@v7/g);
    assert.match(workflow, /actions\/setup-java@v5/g);
    assert.match(workflow, /gradle\/actions\/setup-gradle@v6/g);
    assert.match(workflow, /actions\/upload-artifact@v7/g);
    assert.match(workflow, /actions\/download-artifact@v8/g);
    assert.match(workflow, /persist-credentials: false/g);
    assert.match(workflow, /retention-days: 90/);
    assert.match(workflow, /steps\.report\.outputs\.artifact-url/);
    assert.match(workflow, /benchmark-tag-diff\.test\.mjs/);
    assert.match(workflow, /previous_available/);
    assert.doesNotMatch(workflow, /continue-on-error/);
    assert.doesNotMatch(workflow, /publish\.yml|softprops|release:/);
    assert.doesNotMatch(publish, /benchmark-tag-diff/);
    const uploadNames = [...workflow.matchAll(
        /uses: actions\/upload-artifact@v7\n\s+with:\n\s+name: ([^\n]+)/g,
    )].map((match) => match[1]);
    assert.equal(uploadNames.length, 5);
    assert.ok(uploadNames.every((name) => name.includes("github.run_attempt")));
    for (const name of uploadNames.slice(0, 4)) {
        assert.match(workflow, new RegExp(
            `uses: actions\\/download-artifact@v8\\n\\s+with:\\n\\s+name: ${name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}`,
        ));
    }
});

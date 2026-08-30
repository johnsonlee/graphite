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
    EVIDENCE_DIMENSIONS,
    parseSemver,
    PRODUCT_CORE_INDICATORS,
    REPRESENTATIVE_BENCHMARKS,
    resolvePreviousTag,
    summarizeReleaseMetrics,
    trendLabelIndices,
    updateReleaseHistory,
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

    const explicitIsolation = jmhConfigurationSources("1.37");
    explicitIsolation["graphite-cypher/build.gradle.kts"] =
        explicitIsolation["graphite-cypher/build.gradle.kts"].replace(
            "jmh {\n",
            "jmh {\n    includeTests.set(false)\n"
        );
    assert.equal(comparableJmhConfiguration(explicitIsolation), base);

    const testContaminated = jmhConfigurationSources("1.37");
    testContaminated["graphite-cypher/build.gradle.kts"] =
        testContaminated["graphite-cypher/build.gradle.kts"].replace(
            "jmh {\n",
            "jmh {\n    includeTests.set(true)\n"
        );
    assert.throws(
        () => comparableJmhConfiguration(testContaminated),
        /must exclude project test output/
    );
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

test("HTML report is self-contained, 7+2 classified, provenance-rich, and injection-safe", () => {
    const report = buildTagDiffReport({
        metadata: metadata(),
        currentResults: results(1.2),
        previousResults: results(),
        repository: "johnsonlee/graphite<script>alert(1)</script>",
        runUrl: "https://example.invalid/run",
        generatedAt: "2026-08-30T01:02:03.000Z"
    });
    assert.match(report.html, /Release Benchmark Observatory/);
    assert.match(report.html, /v2\.4\.4/);
    assert.match(report.html, new RegExp("a{40}"));
    assert.match(report.html, /v2\.4\.3/);
    assert.match(report.html, new RegExp("b{40}"));
    assert.match(report.html, /\+20\.00%/);
    assert.match(report.html, /No release verdict/);
    assert.match(report.html, /Release benchmark questions/);
    assert.match(report.html, /Seven product indicators/);
    assert.match(report.html, /Operational stability/);
    assert.match(report.html, /Measurement confidence/);
    assert.match(report.html, /Coverage completeness/);
    assert.match(report.html, /Can we trust and generalize the shift/);
    assert.match(report.html, /Which product domains remain incomplete/);
    assert.match(report.html, /benchmark point \+ 99\.9% CI/);
    assert.match(report.html, /not a synthesized aggregate CI/);
    assert.match(report.html, /Latency forest plot/);
    assert.match(report.html, /All 6 methods/);
    assert.match(report.html, /\.forest-track:before\{[^}]*width:2px/);
    assert.match(report.html, /\.axis-track:before\{[^}]*width:2px/);
    assert.match(report.html, /\.axis-track>b\{top:50%;transform:translate\(-50%,-50%\) rotate\(45deg\)\}/);
    assert.match(report.html, /\.signal-columns\{margin-bottom:1rem\}/);
    assert.match(report.html, /\.method-table td\{vertical-align:middle\}/);
    assert.match(report.html, /<table class="method-table">/);
    assert.match(report.html, /Primary observed shift/);
    assert.match(report.html, /Worse · shifted right/);
    assert.match(report.html, /Component-CI envelope [+-]\d+\.\d+% to [+-]\d+\.\d+%/);
    assert.match(report.html, /Exact values · 1 release/);
    assert.deepEqual(
        [...report.html.matchAll(/data-trend-metric="([^"]+)"/g)].map((match) => match[1]),
        ["latency", "measurement-confidence", "coverage-completeness"]
    );
    assert.match(report.html, /Product signal trends/);
    assert.match(report.html, /Evidence quality trends/);
    assert.match(report.html, /Coverage completeness trend/);
    assert.match(report.html, /6 indicator gaps map to 5 unmeasured domains/);
    assert.match(report.html, /href="#coverage-map"/);
    assert.doesNotMatch(report.html, /Tracked indicator/);
    assert.match(report.html, /Feeds core indicators/);
    assert.match(report.html, /Would unlock/);
    assert.doesNotMatch(report.html, /<ol class="trend-values">/);
    assert.match(report.html, /Is this a one-off or a trend/);
    assert.match(report.html, /What needs attention/);
    assert.match(report.html, /prefers-reduced-motion:reduce/);
    assert.match(report.html, /Content-Security-Policy/);
    assert.doesNotMatch(report.html, /<script[ >]/);
    assert.doesNotMatch(report.html, /<link[^>]+stylesheet/);
    assert.doesNotMatch(report.html, /<script>alert/);
    for (const area of COVERAGE_TAXONOMY) assert.match(report.html, new RegExp(area.name));
    assert.equal(report.manifest.comparisonStatus, "available");
    assert.equal(report.manifest.productMetrics.length, PRODUCT_CORE_INDICATORS.length);
    assert.equal(report.manifest.evidenceDimensions.length, EVIDENCE_DIMENSIONS.length);
    assert.equal(report.manifest.productMetrics.filter((metric) => metric.state === "observed").length, 1);
    assert.equal(report.manifest.productMetrics.find((metric) => metric.id === "operational-stability").state, "unavailable");
    assert.equal(report.manifest.evidenceDimensions.find((metric) => metric.id === "measurement-confidence").state, "observed");
    assert.equal(report.manifest.releaseHistory.length, 1);
    assert.equal(report.manifest.measurements.length, 6);
    assert.ok(Math.abs(report.manifest.measurements[0].deltaPercent - 20) < 1e-9);
    assert.deepEqual(
        report.manifest.measurements.map((measurement) => measurement.classification),
        ["inconclusive", "regression", "regression", "regression", "regression", "regression"]
    );
});

test("release summary separates product shifts from evidence confidence and method drill-down", () => {
    const previous = results();
    const current = results();
    current[1] = result(REPRESENTATIVE_BENCHMARKS[1], 24);
    current[2] = result(REPRESENTATIVE_BENCHMARKS[2], 24);
    const report = buildTagDiffReport({
        metadata: metadata(),
        currentResults: current,
        previousResults: previous,
        generatedAt: "2026-08-30T01:02:03.000Z"
    });

    assert.match(report.html, /Product regressions<\/span><span>Right<\/span><\/div><strong>0<\/strong>/);
    assert.match(report.html, /Product improvements<\/span><span>Left<\/span><\/div><strong>1<\/strong>/);
    assert.match(report.html, /Needs attention<\/span><span>Review<\/span><\/div><strong>5<\/strong>/);
    assert.match(report.html, /Latency aggregate envelope crosses zero/);
    assert.match(report.html, /Latency<\/h3>[\s\S]*?-0\.68%/);
    assert.match(report.html, /Measurement confidence<\/h3>[\s\S]*?\+0\.42 pp/);
    assert.match(report.html, /nodeMatchWithWhere<\/code><strong>\+20\.00%/);
    assert.match(report.html, /simpleNodeMatch<\/code><strong>-20\.00%/);
    assert.match(report.summary, /Product regressions: \*\*0\*\*/);
    assert.match(report.summary, /Product improvements: \*\*1\*\*/);
    assert.match(report.summary, /Core coverage: \*\*1\/7 product indicators observed\*\*/);
    assert.deepEqual(
        report.manifest.measurements.map((measurement) => measurement.classification),
        ["inconclusive", "regression", "improvement", "inconclusive", "inconclusive", "inconclusive"]
    );
});

test("7+2 metrics keep operational stability separate from measurement confidence", () => {
    const summary = summarizeReleaseMetrics(
        validateJmhResults(results(1.1)),
        validateJmhResults(results()),
        true
    );
    assert.deepEqual(summary.productMetrics.map((metric) => metric.id), PRODUCT_CORE_INDICATORS.map((metric) => metric.id));
    assert.deepEqual(summary.evidenceDimensions.map((metric) => metric.id), EVIDENCE_DIMENSIONS.map((metric) => metric.id));
    assert.equal(summary.productMetrics.find((metric) => metric.id === "operational-stability").state, "unavailable");
    assert.equal(summary.evidenceDimensions.find((metric) => metric.id === "measurement-confidence").state, "observed");
    assert.equal(summary.evidenceDimensions.find((metric) => metric.id === "coverage-completeness").scope, "1/7 product indicators");
    assert.ok(Math.abs(summary.productMetrics.find((metric) => metric.id === "latency").shift - 10) < 1e-9);
});

test("release history replaces reruns, stays semantic, and rejects incomplete 7+2 entries", () => {
    const first = buildTagDiffReport({
        metadata: metadata(),
        currentResults: results(1.1),
        previousResults: results(),
        generatedAt: "2026-08-30T01:02:03.000Z"
    }).manifest.releaseHistory[0];
    const nextMetadata = metadata({
        tag: "v2.4.4",
        sha: "a".repeat(40),
        refSha: "a".repeat(40),
        harnessFingerprint: "c".repeat(64)
    });
    nextMetadata.current = {
        tag: "v2.4.5",
        sha: "d".repeat(40),
        refSha: "d".repeat(40),
        harnessFingerprint: "c".repeat(64)
    };
    const next = buildTagDiffReport({
        metadata: nextMetadata,
        currentResults: results(.9),
        previousResults: results(),
        history: [first],
        generatedAt: "2026-08-31T01:02:03.000Z"
    }).manifest.releaseHistory;
    assert.deepEqual(next.map((entry) => entry.tag), ["v2.4.4", "v2.4.5"]);
    assert.equal(updateReleaseHistory(next, { ...next[1], generatedAt: "2026-09-01T00:00:00.000Z" }).length, 2);
    assert.throws(() => updateReleaseHistory([], { ...first, coreMetrics: first.coreMetrics.slice(1) }), /incomplete/);
});

test("trend version labels show every short-history release and thin long histories", () => {
    assert.deepEqual(trendLabelIndices(5), [0, 1, 2, 3, 4]);
    assert.deepEqual(trendLabelIndices(100), [0, 17, 33, 50, 66, 83, 99]);
    assert.deepEqual(trendLabelIndices(0), []);
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
    assert.ok(report.manifest.measurements.every((measurement) => measurement.classification === "unavailable"));
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
        const audit = JSON.parse(fs.readFileSync(manifest));
        assert.equal(audit.measurements.length, 6);
        assert.equal(audit.productMetrics.length, 7);
        assert.equal(audit.evidenceDimensions.length, 2);
        const history = path.join(directory, "recovered-history.json");
        const extract = spawnSync(process.execPath, [
            SCRIPT, "extract-history",
            "--input", output,
            "--output", history
        ], { encoding: "utf8" });
        assert.equal(extract.status, 0, extract.stderr);
        assert.deepEqual(JSON.parse(fs.readFileSync(history)).map((entry) => entry.tag), ["v2.4.4"]);
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

test("tag diff workflow is tag-only, publishes only Pages, and stays isolated from release publishing", () => {
    const workflow = fs.readFileSync(new URL("../workflows/benchmark-tag-diff.yml", import.meta.url), "utf8");
    const publish = fs.readFileSync(new URL("../workflows/publish.yml", import.meta.url), "utf8");
    assert.match(workflow, /on:\n  push:\n    tags: \['v\*']\n/);
    assert.doesNotMatch(workflow, /pull_request|workflow_dispatch|repository_dispatch|workflow_call/);
    assert.match(workflow, /permissions:\n  contents: read/);
    assert.doesNotMatch(workflow, /contents: write|actions: write|packages: write/);
    assert.match(workflow, /concurrency:\n  group: pages\n  cancel-in-progress: false\n  queue: max/);
    assert.match(workflow, /actions\/checkout@v7/g);
    assert.match(workflow, /actions\/setup-java@v5/g);
    assert.match(workflow, /gradle\/actions\/setup-gradle@v6/g);
    assert.match(workflow, /actions\/upload-artifact@v7/g);
    assert.match(workflow, /actions\/download-artifact@v8/g);
    assert.match(workflow, /persist-credentials: false/g);
    assert.match(workflow, /retention-days: 90/);
    assert.match(workflow, /steps\.report\.outputs\.artifact-url/);
    assert.match(workflow, /benchmark-tag-diff\.mjs extract-history/);
    assert.match(workflow, /--history tag-resolution\/history\.json/);
    assert.match(workflow, /actions\/configure-pages@v6/);
    assert.match(workflow, /actions\/upload-pages-artifact@v5/);
    assert.match(workflow, /actions\/deploy-pages@v5/);
    assert.match(workflow, /pages: write/);
    assert.match(workflow, /id-token: write/);
    assert.match(workflow, /_site\/releases\/index\.html/);
    assert.match(workflow, /benchmark-tag-diff\.test\.mjs/);
    assert.match(workflow, /previous_available/);
    assert.equal((workflow.match(/benchmark-jmh-isolation\.init\.gradle/g) ?? []).length, 2);
    assert.equal((workflow.match(/verify-jmh-jar-isolation\.sh/g) ?? []).length, 2);
    assert.equal((workflow.match(/:cypher:testClasses :cypher:jmhJar/g) ?? []).length, 2);
    assert.match(workflow, /Checkout exact current isolation controls/);
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

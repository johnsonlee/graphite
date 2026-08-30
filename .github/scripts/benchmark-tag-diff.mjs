#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";

export const REPRESENTATIVE_BENCHMARKS = [
    "io.johnsonlee.graphite.cypher.CypherBenchmark.aggregationCountGroupBy",
    "io.johnsonlee.graphite.cypher.CypherBenchmark.nodeMatchWithWhere",
    "io.johnsonlee.graphite.cypher.CypherBenchmark.simpleNodeMatch",
    "io.johnsonlee.graphite.cypher.CypherBenchmark.singleHopRelationship",
    "io.johnsonlee.graphite.cypher.CypherBenchmark.variableLengthPath",
    "io.johnsonlee.graphite.cypher.CypherBenchmark.withPipeline"
];

export const HARNESS_FINGERPRINT_PATHS = [
    "graphite-cypher/src/jmh/kotlin/io/johnsonlee/graphite/cypher/CypherBenchmark.kt"
];

export const JMH_CONFIG_FINGERPRINT_PATHS = [
    "build.gradle.kts",
    "gradle/libs.versions.toml",
    "graphite-cypher/build.gradle.kts"
];

const BENCHMARK_PROTOCOL = {
    mode: "avgt",
    forks: 1,
    warmupIterations: 2,
    measurementIterations: 4,
    warmupTime: "1s",
    measurementTime: "1s",
    benchmarks: REPRESENTATIVE_BENCHMARKS
};

export const COVERAGE_TAXONOMY = [
    {
        name: "Semantic correctness",
        state: "Not measured",
        detail: "This report compares performance samples; semantic compatibility remains the responsibility of correctness gates."
    },
    {
        name: "Latency regression",
        state: "Observed",
        detail: "Six bounded method-level AverageTime benchmarks provide a release-to-release latency signal."
    },
    {
        name: "Throughput and capacity",
        state: "Not measured",
        detail: "No sustained throughput, saturation, cancellation, or multi-client capacity workload is included."
    },
    {
        name: "Memory and resources",
        state: "Not measured",
        detail: "The representative set does not collect RSS, heap, allocation, GC, or leak evidence."
    },
    {
        name: "Scalability",
        state: "Not measured",
        detail: "Each method runs against one fixed fixture; no graph-size or concurrency scaling curve is inferred."
    },
    {
        name: "Build and persistence lifecycle",
        state: "Not measured",
        detail: "JMH JAR construction is setup only; graph build, save, load, mapped query, and migration are outside scope."
    }
];

const SEMVER_PATTERN = /^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/;
const SHA_PATTERN = /^[0-9a-f]{40}$/;
const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const MAX_REPORT_BYTES = 5 * 1024 * 1024;

function numericIdentifier(value) {
    return /^\d+$/.test(value);
}

export function parseSemver(tag) {
    const match = String(tag).match(SEMVER_PATTERN);
    if (match === null) return null;
    const prerelease = match[4] === undefined ? [] : match[4].split(".");
    if (prerelease.some((identifier) => numericIdentifier(identifier) && identifier.length > 1 && identifier.startsWith("0"))) {
        return null;
    }
    return {
        tag: String(tag),
        major: BigInt(match[1]),
        minor: BigInt(match[2]),
        patch: BigInt(match[3]),
        prerelease,
        build: match[5] ?? ""
    };
}

function compareIdentifier(left, right) {
    const leftNumeric = numericIdentifier(left);
    const rightNumeric = numericIdentifier(right);
    if (leftNumeric && rightNumeric) {
        const leftNumber = BigInt(left);
        const rightNumber = BigInt(right);
        return leftNumber < rightNumber ? -1 : leftNumber > rightNumber ? 1 : 0;
    }
    if (leftNumeric !== rightNumeric) return leftNumeric ? -1 : 1;
    return left < right ? -1 : left > right ? 1 : 0;
}

export function compareSemver(left, right) {
    for (const field of ["major", "minor", "patch"]) {
        if (left[field] !== right[field]) return left[field] < right[field] ? -1 : 1;
    }
    if (left.prerelease.length === 0 || right.prerelease.length === 0) {
        if (left.prerelease.length === right.prerelease.length) return 0;
        return left.prerelease.length === 0 ? 1 : -1;
    }
    const length = Math.max(left.prerelease.length, right.prerelease.length);
    for (let index = 0; index < length; index++) {
        if (left.prerelease[index] === undefined) return -1;
        if (right.prerelease[index] === undefined) return 1;
        const comparison = compareIdentifier(left.prerelease[index], right.prerelease[index]);
        if (comparison !== 0) return comparison;
    }
    return 0;
}

export function resolvePreviousTag(tags, currentTag) {
    const current = parseSemver(currentTag);
    if (current === null) throw new Error(`Current tag is not valid SemVer: ${currentTag}`);
    const uniqueTags = [...new Set(tags.map(String))];
    if (!uniqueTags.includes(currentTag)) throw new Error(`Current tag is missing from the fetched tag set: ${currentTag}`);
    const candidates = uniqueTags
        .map((tag) => parseSemver(tag))
        .filter((version) => version !== null && compareSemver(version, current) < 0)
        .sort((left, right) => compareSemver(right, left) || left.tag.localeCompare(right.tag));
    return candidates[0]?.tag ?? null;
}

function gitOutput(args, cwd = process.cwd()) {
    return execFileSync("git", args, { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
}

function kotlinBlockEnd(source, openingBrace) {
    let depth = 0;
    let state = "code";
    for (let index = openingBrace; index < source.length; index++) {
        const current = source[index];
        const next = source[index + 1];
        if (state === "line-comment") {
            if (current === "\n") state = "code";
            continue;
        }
        if (state === "block-comment") {
            if (current === "*" && next === "/") {
                state = "code";
                index++;
            }
            continue;
        }
        if (state === "string") {
            if (current === "\\") index++;
            else if (current === '"') state = "code";
            continue;
        }
        if (state === "triple-string") {
            if (source.slice(index, index + 3) === '\"\"\"') {
                state = "code";
                index += 2;
            }
            continue;
        }
        if (state === "character") {
            if (current === "\\") index++;
            else if (current === "'") state = "code";
            continue;
        }
        if (current === "/" && next === "/") {
            state = "line-comment";
            index++;
        } else if (current === "/" && next === "*") {
            state = "block-comment";
            index++;
        } else if (source.slice(index, index + 3) === '\"\"\"') {
            state = "triple-string";
            index += 2;
        } else if (current === '"') {
            state = "string";
        } else if (current === "'") {
            state = "character";
        } else if (current === "{") {
            depth++;
        } else if (current === "}") {
            depth--;
            if (depth === 0) return index + 1;
            if (depth < 0) break;
        }
    }
    throw new Error("Cypher benchmark harness contains an unterminated Kotlin block");
}

export function comparableHarnessSource(source) {
    const classMatch = /\b(?:open\s+)?class\s+CypherBenchmark\b[^\{]*\{/.exec(source);
    if (classMatch === null) throw new Error("CypherBenchmark class declaration is missing");
    const classOpening = source.indexOf("{", classMatch.index);
    const classEnd = kotlinBlockEnd(source, classOpening);
    const classSource = source.slice(0, classEnd);
    const methodPattern = /^[ \t]*@Benchmark(?:\([^\n]*\))?[ \t]*\r?\n(?:(?:^[ \t]*@[A-Za-z0-9_.]+(?:\([^\n]*\))?[ \t]*\r?\n)*)^[ \t]*(?:(?:public|internal|protected|private|open|final|suspend|inline|tailrec|operator|infix|external)\s+)*fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(/gm;
    const methods = new Map();
    const spans = [];
    for (const match of classSource.matchAll(methodPattern)) {
        const opening = classSource.indexOf("{", match.index + match[0].length);
        if (opening < 0 || opening >= classEnd) throw new Error(`Benchmark method ${match[1]} must use a block body`);
        const end = kotlinBlockEnd(classSource, opening);
        if (methods.has(match[1])) throw new Error(`Duplicate benchmark method ${match[1]}`);
        methods.set(match[1], classSource.slice(match.index, end).trim());
        spans.push([match.index, end]);
    }
    for (const name of REPRESENTATIVE_BENCHMARKS.map(shortName)) {
        if (!methods.has(name)) throw new Error(`Representative benchmark method ${name} is missing from the harness`);
    }
    if (spans.length === 0) throw new Error("CypherBenchmark has no @Benchmark methods");
    const firstBenchmark = spans[0][0];
    let cursor = firstBenchmark;
    for (const [start, end] of spans) {
        if (classSource.slice(cursor, start).trim() !== "") {
            throw new Error("Unsupported shared harness code appears between @Benchmark methods");
        }
        cursor = end;
    }
    if (classSource.slice(cursor, classEnd - 1).trim() !== "") {
        throw new Error("Unsupported shared harness code appears after the final @Benchmark method");
    }
    return [
        classSource.slice(0, firstBenchmark).trimEnd(),
        ...REPRESENTATIVE_BENCHMARKS.map((benchmark) => methods.get(shortName(benchmark)))
    ].join("\n\n");
}

function exactlyOneLine(source, pattern, label) {
    const lines = source.split(/\r?\n/).filter((line) => pattern.test(line));
    if (lines.length !== 1) throw new Error(`Expected exactly one ${label}, found ${lines.length}`);
    return lines[0].trim();
}

export function comparableJmhConfiguration(sources) {
    const rootBuild = sources["build.gradle.kts"];
    const versions = sources["gradle/libs.versions.toml"];
    const moduleBuild = sources["graphite-cypher/build.gradle.kts"];
    if ([rootBuild, versions, moduleBuild].some((source) => typeof source !== "string")) {
        throw new Error("JMH configuration fingerprint inputs are incomplete");
    }
    const pluginVersion = exactlyOneLine(
        rootBuild,
        /^\s*id\("me\.champeau\.jmh"\)\s+version\s+"[^"]+"\s+apply\s+false\s*$/,
        "root JMH plugin declaration"
    );
    const runtimeVersion = exactlyOneLine(
        versions,
        /^\s*jmh\s*=\s*"[^"]+"\s*(?:#.*)?$/,
        "JMH runtime version"
    );
    const coreLibrary = exactlyOneLine(
        versions,
        /^\s*jmh-core\s*=\s*\{[^\n]+\}\s*(?:#.*)?$/,
        "JMH core catalog entry"
    );
    const generatorLibrary = exactlyOneLine(
        versions,
        /^\s*jmh-generator\s*=\s*\{[^\n]+\}\s*(?:#.*)?$/,
        "JMH generator catalog entry"
    );
    const modulePlugin = exactlyOneLine(
        moduleBuild,
        /^\s*id\("me\.champeau\.jmh"\)\s*$/,
        "module JMH plugin declaration"
    );
    const coreDependency = exactlyOneLine(
        moduleBuild,
        /^\s*jmh\([^\n]+\)\s*$/,
        "module JMH core dependency"
    );
    const generatorDependency = exactlyOneLine(
        moduleBuild,
        /^\s*jmhAnnotationProcessor\([^\n]+\)\s*$/,
        "module JMH generator dependency"
    );
    const blockMatch = /^\s*jmh\s*\{/m.exec(moduleBuild);
    if (blockMatch === null) throw new Error("Module jmh configuration block is missing");
    const blockOpening = moduleBuild.indexOf("{", blockMatch.index);
    const jmhBlock = moduleBuild.slice(blockMatch.index, kotlinBlockEnd(moduleBuild, blockOpening)).trim();
    const includeTestsLines = jmhBlock
        .split(/\r?\n/)
        .filter((line) => /^\s*includeTests\.set\(/.test(line));
    if (includeTestsLines.length > 1) {
        throw new Error("Module jmh configuration contains duplicate includeTests controls");
    }
    if (includeTestsLines.length === 1 && !/^\s*includeTests\.set\(false\)\s*$/.test(includeTestsLines[0])) {
        throw new Error("Tag benchmark JMH configuration must exclude project test output");
    }
    const effectiveJmhBlock = jmhBlock
        .split(/\r?\n/)
        .filter((line) => !/^\s*includeTests\.set\(false\)\s*$/.test(line))
        .join("\n");
    return [
        pluginVersion,
        runtimeVersion,
        coreLibrary,
        generatorLibrary,
        modulePlugin,
        coreDependency,
        generatorDependency,
        effectiveJmhBlock,
        "includeTests.set(false) // workflow-enforced"
    ].join("\n");
}

function harnessFingerprint(commitSha, cwd) {
    const hash = createHash("sha256");
    hash.update("graphite-tag-benchmark-harness-v1\0");
    hash.update(JSON.stringify(BENCHMARK_PROTOCOL));
    hash.update("\0");
    for (const file of HARNESS_FINGERPRINT_PATHS) {
        const contents = execFileSync("git", ["show", `${commitSha}:${file}`], {
            cwd,
            encoding: null,
            stdio: ["ignore", "pipe", "pipe"]
        });
        hash.update(file);
        hash.update("\0");
        hash.update(comparableHarnessSource(contents.toString("utf8")));
        hash.update("\0");
    }
    const configurationSources = {};
    for (const file of JMH_CONFIG_FINGERPRINT_PATHS) {
        configurationSources[file] = execFileSync("git", ["show", `${commitSha}:${file}`], {
            cwd,
            encoding: "utf8",
            stdio: ["ignore", "pipe", "pipe"]
        });
    }
    hash.update("jmh-configuration\0");
    hash.update(comparableJmhConfiguration(configurationSources));
    hash.update("\0");
    return hash.digest("hex");
}

function resolveTagRef(tag, cwd) {
    const rawSha = gitOutput(["rev-parse", "--verify", `refs/tags/${tag}`], cwd);
    const commitSha = gitOutput(["rev-parse", "--verify", `refs/tags/${tag}^{commit}`], cwd);
    if (!SHA_PATTERN.test(rawSha) || !SHA_PATTERN.test(commitSha)) throw new Error(`Tag ${tag} did not resolve to SHA-1 object IDs`);
    return { tag, sha: commitSha, refSha: rawSha, harnessFingerprint: harnessFingerprint(commitSha, cwd) };
}

export function resolveGitTagMetadata({
    currentTag,
    expectedRef = "",
    expectedSha = "",
    cwd = process.cwd(),
    resolvedAt = new Date().toISOString()
}) {
    if (parseSemver(currentTag) === null) throw new Error(`Current tag is not valid SemVer: ${currentTag}`);
    const ref = `refs/tags/${currentTag}`;
    if (expectedRef !== "" && expectedRef !== ref) throw new Error(`Expected tag ref ${ref}, got ${expectedRef}`);
    const tags = gitOutput(["tag", "--list", "v*"], cwd).split(/\r?\n/).filter(Boolean);
    const current = resolveTagRef(currentTag, cwd);
    if (expectedSha !== "" && expectedSha !== current.sha && expectedSha !== current.refSha) {
        throw new Error(`Tag ${currentTag} resolves to ${current.sha}, not event SHA ${expectedSha}`);
    }
    const previousTag = resolvePreviousTag(tags, currentTag);
    const previous = previousTag === null ? null : resolveTagRef(previousTag, cwd);
    if (!Number.isFinite(Date.parse(resolvedAt))) throw new Error("Resolution timestamp is invalid");
    return {
        schemaVersion: 1,
        current,
        previous,
        baselineAvailable: previous !== null,
        resolvedAt
    };
}

function finiteNumber(value) {
    return typeof value === "number" && Number.isFinite(value);
}

export function validateJmhResults(results, label = "benchmark") {
    if (!Array.isArray(results)) throw new Error(`${label} JMH result must be an array`);
    const expected = [...REPRESENTATIVE_BENCHMARKS].sort();
    const actual = results.map((result) => result?.benchmark).sort();
    if (JSON.stringify(actual) !== JSON.stringify(expected)) {
        throw new Error(`${label} JMH result does not contain the exact representative benchmark set`);
    }
    const seen = new Set();
    const validated = results.map((result) => {
        if (seen.has(result.benchmark)) throw new Error(`${label} JMH result contains duplicate benchmark ${result.benchmark}`);
        seen.add(result.benchmark);
        if (result.mode !== "avgt") throw new Error(`${label} benchmark ${result.benchmark} must use AverageTime mode`);
        if (result.params !== undefined && result.params !== null &&
            (typeof result.params !== "object" || Array.isArray(result.params) || Object.keys(result.params).length !== 0)
        ) {
            throw new Error(`${label} benchmark ${result.benchmark} must not have parameters`);
        }
        const metric = result.primaryMetric;
        if (metric === null || typeof metric !== "object" ||
            !finiteNumber(metric.score) || metric.score <= 0 ||
            !finiteNumber(metric.scoreError) || metric.scoreError < 0 ||
            !/^(ns|us|ms|s)\/op$/.test(metric.scoreUnit)
        ) {
            throw new Error(`${label} benchmark ${result.benchmark} has invalid primary metrics`);
        }
        if (!Array.isArray(metric.scoreConfidence) || metric.scoreConfidence.length !== 2 ||
            !metric.scoreConfidence.every(finiteNumber) || metric.scoreConfidence[0] > metric.scoreConfidence[1]
        ) {
            throw new Error(`${label} benchmark ${result.benchmark} has invalid confidence bounds`);
        }
        return {
            benchmark: result.benchmark,
            mode: result.mode,
            score: metric.score,
            scoreError: metric.scoreError,
            scoreConfidence: [...metric.scoreConfidence],
            scoreUnit: metric.scoreUnit
        };
    });
    return validated.sort((left, right) => left.benchmark.localeCompare(right.benchmark));
}

function validateMetadata(metadata) {
    if (metadata?.schemaVersion !== 1 || metadata?.baselineAvailable !== (metadata?.previous !== null) ||
        parseSemver(metadata?.current?.tag) === null || !SHA_PATTERN.test(metadata?.current?.sha ?? "") ||
        !SHA_PATTERN.test(metadata?.current?.refSha ?? "") ||
        !SHA256_PATTERN.test(metadata?.current?.harnessFingerprint ?? "") ||
        !Number.isFinite(Date.parse(metadata?.resolvedAt))
    ) {
        throw new Error("Tag metadata is malformed");
    }
    if (metadata.previous !== null) {
        const previous = parseSemver(metadata.previous?.tag);
        const current = parseSemver(metadata.current.tag);
        if (previous === null || !SHA_PATTERN.test(metadata.previous?.sha ?? "") ||
            !SHA_PATTERN.test(metadata.previous?.refSha ?? "") ||
            !SHA256_PATTERN.test(metadata.previous?.harnessFingerprint ?? "") ||
            compareSemver(previous, current) >= 0
        ) {
            throw new Error("Previous tag metadata is malformed or not older than the current tag");
        }
    }
}

function escapeHtml(value) {
    return String(value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}

function safeUrl(value) {
    try {
        const url = new URL(String(value));
        return url.protocol === "https:" ? url.href : "#";
    } catch {
        return "#";
    }
}

function shortName(benchmark) {
    return benchmark.replace("io.johnsonlee.graphite.cypher.CypherBenchmark.", "");
}

function score(value) {
    return Number(value).toPrecision(6);
}

function confidence(metric) {
    return `${score(metric.scoreConfidence[0])} – ${score(metric.scoreConfidence[1])}`;
}

function signedPercent(value) {
    return `${value >= 0 ? "+" : ""}${value.toFixed(2)}%`;
}

function median(values) {
    if (values.length === 0) return null;
    const sorted = [...values].sort((left, right) => left - right);
    const middle = Math.floor(sorted.length / 2);
    return sorted.length % 2 === 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
}

function renderCoverage() {
    return COVERAGE_TAXONOMY.map((area) => {
        const observed = area.state === "Observed";
        return `<article class="coverage ${observed ? "observed" : "missing"}"><div><span>${observed ? "Observed" : "Not measured"}</span><h3>${escapeHtml(area.name)}</h3></div><p>${escapeHtml(area.detail)}</p></article>`;
    }).join("\n");
}

function renderRows(current, previous, deltasAvailable) {
    const previousByName = new Map((previous ?? []).map((result) => [result.benchmark, result]));
    return current.map((result) => {
        const baseline = previousByName.get(result.benchmark);
        let delta = null;
        let classification = "unavailable";
        if (baseline !== undefined) {
            if (baseline.mode !== result.mode || baseline.scoreUnit !== result.scoreUnit) {
                throw new Error(`Incomparable JMH modes or units for ${result.benchmark}`);
            }
            if (deltasAvailable && baseline.score > 0) {
                delta = (result.score / baseline.score - 1) * 100;
                if (result.scoreConfidence[0] > baseline.scoreConfidence[1]) classification = "regression";
                else if (result.scoreConfidence[1] < baseline.scoreConfidence[0]) classification = "improvement";
                else classification = "inconclusive";
            }
        }
        const deltaMarkup = delta === null
            ? "<span class=\"neutral\">unavailable</span>"
            : `<span class="${delta > 0 ? "slower" : delta < 0 ? "faster" : "neutral"}">${escapeHtml(signedPercent(delta))}</span>`;
        const classificationLabel = {
            regression: "Regression signal",
            improvement: "Improvement signal",
            inconclusive: "Inconclusive",
            unavailable: "Unavailable"
        }[classification];
        return {
            delta,
            classification,
            benchmark: result.benchmark,
            html: `<tr class="${classification}"><td><code>${escapeHtml(shortName(result.benchmark))}</code></td><td><span class="trend ${classification}">${classificationLabel}</span></td><td class="number">${baseline === undefined ? "unavailable" : escapeHtml(score(baseline.score))}</td><td class="number">${escapeHtml(score(result.score))}</td><td>${escapeHtml(result.scoreUnit)}</td><td class="number">${deltaMarkup}</td><td class="confidence"><small>previous</small>${baseline === undefined ? "unavailable" : escapeHtml(confidence(baseline))}<small>current</small>${escapeHtml(confidence(result))}</td></tr>`
        };
    });
}

function signalList(rows, classification, emptyCopy, available) {
    if (!available) return `<p class="signal-empty">Comparison unavailable. Raw measurements are preserved below.</p>`;
    const matches = rows.filter((row) => row.classification === classification);
    if (matches.length === 0) return `<p class="signal-empty">${escapeHtml(emptyCopy)}</p>`;
    return `<ol class="signal-list">${matches.map((row) => `<li><code>${escapeHtml(shortName(row.benchmark))}</code><strong>${escapeHtml(signedPercent(row.delta))}</strong></li>`).join("")}</ol>`;
}

export function buildTagDiffReport({
    metadata,
    currentResults,
    previousResults = null,
    repository = "johnsonlee/graphite",
    runUrl = "",
    generatedAt = new Date().toISOString()
}) {
    validateMetadata(metadata);
    if (!Number.isFinite(Date.parse(generatedAt))) throw new Error("Report timestamp is invalid");
    const current = validateJmhResults(currentResults, "current");
    let previous = null;
    if (metadata.baselineAvailable) {
        if (previousResults === null) throw new Error("Previous JMH result is required when a baseline tag is available");
        previous = validateJmhResults(previousResults, "previous");
    } else if (previousResults !== null) {
        throw new Error("Previous JMH result was provided without a resolved baseline tag");
    }
    const workloadCompatible = metadata.baselineAvailable &&
        metadata.previous.harnessFingerprint === metadata.current.harnessFingerprint;
    const deltasAvailable = metadata.baselineAvailable && workloadCompatible;
    const renderedRows = renderRows(current, previous, deltasAvailable);
    const deltas = renderedRows.map((row) => row.delta).filter((value) => value !== null);
    const medianDelta = median(deltas);
    const regressions = renderedRows.filter((row) => row.classification === "regression");
    const improvements = renderedRows.filter((row) => row.classification === "improvement");
    const inconclusive = renderedRows.filter((row) => row.classification === "inconclusive");
    const largestRegression = regressions.reduce((largest, row) => largest === null || row.delta > largest.delta ? row : largest, null);
    const largestImprovement = improvements.reduce((largest, row) => largest === null || row.delta < largest.delta ? row : largest, null);
    const integrityAttention = deltasAvailable ? 0 : 1;
    const attentionCount = regressions.length + inconclusive.length + integrityAttention;
    const observedCoverage = COVERAGE_TAXONOMY.filter((area) => area.state === "Observed").length;
    const comparisonStatus = !metadata.baselineAvailable
        ? "baseline-unavailable"
        : workloadCompatible ? "available" : "workload-drift";
    const baselineTag = metadata.previous?.tag ?? "No prior semantic tag";
    const baselineSha = metadata.previous?.sha ?? "unavailable";
    const statusTitle = !metadata.baselineAvailable
        ? "Baseline unavailable"
        : workloadCompatible ? "Release diff available" : "Deltas unavailable · workload drift";
    const statusCopy = !metadata.baselineAvailable
        ? "No lower valid semantic-version tag exists. Current measurements are preserved, but no deltas or pass/fail verdict are fabricated."
        : workloadCompatible
            ? "Signed deltas compare the current tag with its semantic predecessor. Negative is faster; positive is slower. This is an informational signal, not a release verdict."
            : "The benchmark harness fingerprint changed between tags. Both raw score sets are preserved, but signed deltas are unavailable because they do not describe an identical workload.";
    const rows = renderedRows.map((row) => row.html).join("\n");
    const regressionValue = deltasAvailable ? `${regressions.length}` : "—";
    const regressionCopy = !deltasAvailable
        ? "The release pair is not comparable."
        : largestRegression === null
            ? "No slower result has separated 99.9% confidence intervals."
            : `${shortName(largestRegression.benchmark)} is the largest slower signal at ${signedPercent(largestRegression.delta)}.`;
    const improvementValue = deltasAvailable ? `${improvements.length}` : "—";
    const improvementCopy = !deltasAvailable
        ? "The release pair is not comparable."
        : largestImprovement === null
            ? "No faster result has separated 99.9% confidence intervals."
            : `${shortName(largestImprovement.benchmark)} is the largest faster signal at ${signedPercent(largestImprovement.delta)}.`;
    const attentionCopy = !deltasAvailable
        ? statusTitle
        : `${regressions.length} slower ${regressions.length === 1 ? "signal" : "signals"} and ${inconclusive.length} inconclusive ${inconclusive.length === 1 ? "comparison" : "comparisons"} need review.`;
    const html = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="dark"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; base-uri 'none'; form-action 'none'"><title>Graphite Release Benchmark Observatory</title>
<style>
:root{--canvas:#07101c;--surface:#0d1828;--surface-raised:#132238;--surface-soft:#091421;--text:#edf4fc;--muted:#9aacc0;--quiet:#71859b;--line:#293d54;--line-strong:#3c5570;--faster:#65dda6;--faster-soft:#0c2b22;--slower:#ff7185;--slower-soft:#321923;--warn:#f3c969;--warn-soft:#302817;--focus:#91b7ff;--observed:#75a9ff;--radius:14px;font-family:"Avenir Next","Segoe UI",ui-sans-serif,system-ui,sans-serif}*{box-sizing:border-box}html{scroll-behavior:smooth;scroll-padding-top:5.5rem}body{margin:0;color:var(--text);line-height:1.52;background-color:var(--canvas);background-image:linear-gradient(#19283a35 1px,transparent 1px),linear-gradient(90deg,#19283a35 1px,transparent 1px);background-size:32px 32px}body:before{content:"";position:fixed;inset:0;pointer-events:none;background:linear-gradient(180deg,#07101cc7,#07101cf5 32rem,#07101c)}main{position:relative;width:min(1400px,calc(100% - 2rem));margin:auto;padding:1rem 0 4rem}.hero,.section{border:1px solid var(--line-strong);background:var(--surface)}.hero{display:grid;grid-template-columns:minmax(0,1.25fr) minmax(340px,.75fr);min-height:27rem;border-radius:20px;overflow:hidden}.hero-copy-block{display:flex;flex-direction:column;justify-content:center;padding:clamp(1.5rem,4vw,4.2rem)}.eyebrow{margin:0;color:var(--focus);font-family:"SFMono-Regular",Consolas,monospace;font-size:.75rem;font-weight:800;letter-spacing:.14em;text-transform:uppercase}.hero h1{max-width:850px;margin:.5rem 0 1rem;font-family:"Arial Narrow","Avenir Next Condensed","Segoe UI",sans-serif;font-size:clamp(2.7rem,6.4vw,6rem);font-weight:760;line-height:.88;letter-spacing:-.06em}.hero-copy{max-width:760px;margin:0;color:var(--muted);font-size:1rem}.pills{display:flex;gap:.55rem;flex-wrap:wrap;margin-top:1.25rem}.pill{display:inline-flex;align-items:center;min-height:30px;padding:.3rem .65rem;border:1px solid var(--line);border-radius:999px;background:var(--surface-soft);color:var(--muted);font-size:.8rem}.pill.available{color:var(--faster);border-color:#65dda666}.pill.unavailable{color:var(--warn);border-color:#f3c96966}.button{display:inline-flex;align-items:center;min-height:44px;width:max-content;margin-top:1.2rem;padding:.62rem .88rem;border:1px solid var(--focus);border-radius:8px;background:var(--focus);color:#07101c;text-decoration:none;font-weight:800;transition:transform 180ms ease-out}.button:hover{transform:translateY(-1px)}.release-axis{display:flex;flex-direction:column;justify-content:flex-end;padding:clamp(1.5rem,3vw,3rem);border-left:1px solid var(--line-strong);background:var(--surface-soft)}.release-pair{display:grid;gap:.2rem}.release-pair small{color:var(--quiet);font:700 .72rem "SFMono-Regular",Consolas,monospace;letter-spacing:.1em;text-transform:uppercase}.release-pair strong{font-family:"Arial Narrow","Avenir Next Condensed","Segoe UI",sans-serif;font-size:clamp(2.3rem,5vw,4.8rem);line-height:.95;letter-spacing:-.045em}.release-pair .versus{margin:.35rem 0;color:var(--quiet);font:700 .76rem "SFMono-Regular",Consolas,monospace;text-transform:uppercase}.delta-axis{display:grid;grid-template-columns:auto 1fr auto;gap:.7rem;align-items:center;margin-top:1.6rem;color:var(--quiet);font:700 .68rem "SFMono-Regular",Consolas,monospace;text-transform:uppercase}.delta-axis i{position:relative;height:2px;background:linear-gradient(90deg,var(--faster),var(--line-strong) 50%,var(--slower))}.delta-axis i:after{content:"";position:absolute;left:50%;top:50%;width:7px;height:7px;border:2px solid var(--text);border-radius:50%;background:var(--surface-soft);transform:translate(-50%,-50%)}.question-strip{position:sticky;top:.5rem;z-index:10;display:grid;grid-template-columns:repeat(4,1fr);margin:1rem 0;border:1px solid var(--line-strong);border-radius:12px;background:#081321eb;backdrop-filter:blur(16px);overflow:hidden}.question-strip a{display:grid;grid-template-columns:auto 1fr;grid-template-rows:auto auto;column-gap:.7rem;padding:.8rem .9rem;color:var(--text);text-decoration:none;border-right:1px solid var(--line)}.question-strip a:last-child{border-right:0}.question-strip .step{grid-row:1/3;align-self:center;color:var(--quiet);font:700 .74rem "SFMono-Regular",Consolas,monospace}.question-strip strong{font-size:.86rem}.question-strip small{color:var(--muted);font-size:.72rem}.question-strip a:hover{background:#ffffff08}.answers{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:.8rem}.answer{min-width:0;padding:1.15rem;border:1px solid var(--line);border-radius:var(--radius);background:var(--surface)}.answer.regressions{border-top:4px solid var(--slower)}.answer.improvements{border-top:4px solid var(--faster)}.answer.coverage-answer{border-top:4px solid var(--observed)}.answer.attention{border-top:4px solid var(--warn)}.answer-label{display:flex;justify-content:space-between;gap:.8rem;color:var(--muted);font:750 .7rem "SFMono-Regular",Consolas,monospace;letter-spacing:.08em;text-transform:uppercase}.answer strong{display:block;margin:.35rem 0 .3rem;font-family:"Arial Narrow","Avenir Next Condensed","Segoe UI",sans-serif;font-size:clamp(2.25rem,4vw,3.9rem);line-height:.95;letter-spacing:-.045em}.answer.regressions strong{color:var(--slower)}.answer.improvements strong{color:var(--faster)}.answer.coverage-answer strong{color:var(--observed)}.answer.attention strong{color:var(--warn)}.answer p{margin:0;color:var(--muted);font-size:.84rem}.section{margin-top:1rem;padding:clamp(1rem,2.5vw,2.1rem);border-radius:var(--radius)}.section-head{display:flex;justify-content:space-between;gap:1.4rem;align-items:end;margin-bottom:1.15rem}.section-head>div{max-width:820px}.section h2{margin:.2rem 0;font-family:"Arial Narrow","Avenir Next Condensed","Segoe UI",sans-serif;font-size:clamp(1.7rem,3.7vw,3rem);line-height:1;letter-spacing:-.035em}.section-head p{margin:.35rem 0 0;color:var(--muted)}.signal-columns{display:grid;grid-template-columns:1fr 1fr;gap:.8rem}.signal-panel{padding:1rem;border:1px solid var(--line);border-radius:11px;background:var(--surface-soft)}.signal-panel.regression-panel{border-left:4px solid var(--slower)}.signal-panel.improvement-panel{border-left:4px solid var(--faster)}.signal-panel h3{margin:0 0 .7rem}.signal-list{list-style:none;padding:0;margin:0}.signal-list li{display:flex;justify-content:space-between;gap:1rem;align-items:center;padding:.7rem 0;border-top:1px solid var(--line)}.signal-list strong{font-variant-numeric:tabular-nums}.regression-panel .signal-list strong{color:var(--slower)}.improvement-panel .signal-list strong{color:var(--faster)}.signal-empty{margin:0;color:var(--muted)}.coverage-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:.8rem}.coverage{padding:1rem;border:1px solid var(--line);border-radius:11px;background:var(--surface-soft)}.coverage span{display:inline-block;color:var(--quiet);font:800 .68rem "SFMono-Regular",Consolas,monospace;letter-spacing:.08em;text-transform:uppercase}.coverage.observed{border-color:#75a9ff66}.coverage.observed span{color:var(--observed)}.coverage h3{margin:.3rem 0}.coverage p{color:var(--muted);font-size:.86rem;margin:.45rem 0 0}.attention-list{display:grid;gap:.65rem;margin:0;padding:0;list-style:none}.attention-list li{padding:.85rem 1rem;border-left:4px solid var(--warn);background:var(--warn-soft);color:var(--muted)}.attention-list strong{display:block;color:var(--warn)}.metadata{display:grid;grid-template-columns:1fr 1fr;gap:.8rem}.revision{padding:1rem;border-radius:10px;background:var(--surface-soft);border:1px solid var(--line)}.revision small{display:block;color:var(--muted);margin-top:.45rem}.revision code{overflow-wrap:anywhere}.notice{border-left:4px solid var(--warn);padding:1rem;background:var(--warn-soft);border-radius:6px;color:var(--muted)}.notice strong{display:block;color:var(--warn)}.table-scroll{overflow:auto;border:1px solid var(--line);border-radius:10px}table{width:100%;border-collapse:collapse;background:var(--surface-soft);font-size:.84rem}th,td{padding:.7rem;text-align:left;border-bottom:1px solid var(--line);vertical-align:top}th{background:var(--surface-raised);color:#c8d6e7;white-space:nowrap}td.number{font-variant-numeric:tabular-nums;white-space:nowrap}tr.regression{background:#ff718508}tr.improvement{background:#65dda608}code{font-family:"SFMono-Regular",Consolas,monospace;background:#ffffff0a;border:1px solid #ffffff12;border-radius:5px;padding:.08rem .3rem}.confidence{min-width:15rem}.confidence small{display:block;color:var(--muted);text-transform:uppercase;font-size:.65rem;letter-spacing:.08em}.faster{color:var(--faster);font-weight:800}.slower{color:var(--slower);font-weight:800}.neutral{color:var(--muted)}.trend{display:inline-flex;padding:.2rem .45rem;border:1px solid var(--line);border-radius:999px;font-size:.67rem;font-weight:800;white-space:nowrap}.trend.regression{color:var(--slower);border-color:#ff718555;background:var(--slower-soft)}.trend.improvement{color:var(--faster);border-color:#65dda655;background:var(--faster-soft)}.trend.inconclusive{color:var(--warn);border-color:#f3c96955;background:var(--warn-soft)}.trend.unavailable{color:var(--quiet)}footer{text-align:center;color:var(--quiet);padding:2rem 1rem;font-size:.8rem}:focus-visible{outline:3px solid var(--focus);outline-offset:3px}
@media(max-width:1050px){.hero{grid-template-columns:1fr}.release-axis{min-height:18rem;border-left:0;border-top:1px solid var(--line-strong)}.answers{grid-template-columns:1fr 1fr}.coverage-grid{grid-template-columns:1fr 1fr}}
@media(max-width:720px){main{width:min(100% - 1rem,1400px);padding-top:.5rem}.hero{min-height:0;border-radius:14px}.hero-copy-block,.release-axis{padding:1.3rem}.hero h1{font-size:clamp(2.6rem,15vw,4.5rem)}.question-strip{grid-template-columns:repeat(4,minmax(170px,1fr));overflow-x:auto}.answers,.signal-columns,.coverage-grid,.metadata{grid-template-columns:1fr}.section{padding:1rem}.section-head{align-items:start;flex-direction:column}.confidence{min-width:12rem}table{font-size:.875rem}}
@media(prefers-reduced-motion:reduce){html{scroll-behavior:auto}.button{transition:none}}
</style></head><body><main>
<header class="hero"><div class="hero-copy-block"><p class="eyebrow">Graphite release benchmark observatory</p><h1>What changed in ${escapeHtml(metadata.current.tag)}?</h1><p class="hero-copy">A same-runner comparison with ${escapeHtml(baselineTag)}, organized around regressions, improvements, coverage, and evidence that needs attention.</p><div class="pills"><span class="pill ${deltasAvailable ? "available" : "unavailable"}">${escapeHtml(statusTitle)}</span><span class="pill">${current.length} representative methods</span><span class="pill">No release verdict</span></div>${runUrl === "" ? "" : `<a class="button" href="${escapeHtml(safeUrl(runUrl))}">Open workflow run</a>`}</div><aside class="release-axis" aria-label="Compared release tags"><div class="release-pair"><small>Current release</small><strong>${escapeHtml(metadata.current.tag)}</strong><span class="versus">compared with</span><small>Previous release</small><strong>${escapeHtml(baselineTag)}</strong></div><div class="delta-axis"><span>← faster</span><i></i><span>slower →</span></div></aside></header>
<nav class="question-strip" aria-label="Release benchmark questions"><a href="#regressions"><span class="step">01</span><strong>Regressions</strong><small>${regressionValue} clear signals</small></a><a href="#improvements"><span class="step">02</span><strong>Improvements</strong><small>${improvementValue} clear signals</small></a><a href="#coverage"><span class="step">03</span><strong>Coverage</strong><small>${observedCoverage}/${COVERAGE_TAXONOMY.length} domains observed</small></a><a href="#attention"><span class="step">04</span><strong>Attention</strong><small>${attentionCount} items to review</small></a></nav>
<section class="answers" aria-label="Release comparison summary"><article class="answer regressions"><div class="answer-label"><span>Regression signals</span><span>Slower</span></div><strong>${regressionValue}</strong><p>${escapeHtml(regressionCopy)}</p></article><article class="answer improvements"><div class="answer-label"><span>Improvement signals</span><span>Faster</span></div><strong>${improvementValue}</strong><p>${escapeHtml(improvementCopy)}</p></article><article class="answer coverage-answer"><div class="answer-label"><span>Coverage</span><span>Scoped</span></div><strong>${observedCoverage} / ${COVERAGE_TAXONOMY.length}</strong><p>Only method-level latency is observed; five performance domains are not measured.</p></article><article class="answer attention"><div class="answer-label"><span>Needs attention</span><span>Review</span></div><strong>${attentionCount}</strong><p>${escapeHtml(attentionCopy)}</p></article></section>
<section class="section"><div class="section-head"><div><p class="eyebrow">Questions 01–02 · Directional change</p><h2>Regression and improvement signals</h2><p>A signal requires separated 99.9% confidence intervals. Overlapping intervals remain inconclusive; this report never fabricates a release verdict.</p></div></div><div class="signal-columns"><article class="signal-panel regression-panel" id="regressions"><h3>Regression signals</h3>${signalList(renderedRows, "regression", "No clear regression signal in the representative set.", deltasAvailable)}</article><article class="signal-panel improvement-panel" id="improvements"><h3>Improvement signals</h3>${signalList(renderedRows, "improvement", "No clear improvement signal in the representative set.", deltasAvailable)}</article></div></section>
<section class="section" id="coverage"><div class="section-head"><div><p class="eyebrow">Question 03 · PR #104 coverage taxonomy</p><h2>How complete is this comparison?</h2><p>Observed means sampled, not comprehensive. An unmeasured area never inherits a conclusion from method latency.</p></div></div><div class="coverage-grid">${renderCoverage()}</div></section>
<section class="section" id="attention"><div class="section-head"><div><p class="eyebrow">Question 04 · Manual review</p><h2>What needs attention?</h2><p>Integrity limits, slower signals, and interval overlap are surfaced before the raw table.</p></div></div><ul class="attention-list"><li><strong>${escapeHtml(statusTitle)}</strong>${escapeHtml(statusCopy)}</li>${regressions.length === 0 ? "" : `<li><strong>${regressions.length} regression ${regressions.length === 1 ? "signal" : "signals"}</strong>Review the slower methods and confidence bounds in the detailed table.</li>`}${inconclusive.length === 0 ? "" : `<li><strong>${inconclusive.length} inconclusive ${inconclusive.length === 1 ? "comparison" : "comparisons"}</strong>The 99.9% confidence intervals overlap, so direction is not treated as a clear signal.</li>`}<li><strong>Coverage remains partial</strong>${COVERAGE_TAXONOMY.length - observedCoverage} of ${COVERAGE_TAXONOMY.length} product-performance domains are not measured by this bounded release comparison.</li></ul></section>
<section class="section"><div class="section-head"><div><p class="eyebrow">Detailed method evidence</p><h2>Current vs previous</h2><p>JMH AverageTime; lower is better. Signed delta is shown alongside a confidence-aware classification.</p></div><p>Median delta: <strong>${medianDelta === null ? "unavailable" : escapeHtml(signedPercent(medianDelta))}</strong></p></div><div class="table-scroll"><table><thead><tr><th>Benchmark</th><th>Signal</th><th>Previous</th><th>Current</th><th>Unit</th><th>Delta</th><th>99.9% confidence bounds</th></tr></thead><tbody>${rows}</tbody></table></div></section>
<section class="section"><div class="section-head"><div><p class="eyebrow">Exact provenance</p><h2>Compared revisions</h2></div></div><div class="metadata"><article class="revision"><strong>Current · ${escapeHtml(metadata.current.tag)}</strong><small>Commit SHA</small><code>${escapeHtml(metadata.current.sha)}</code><small>Harness fingerprint</small><code>${escapeHtml(metadata.current.harnessFingerprint)}</code></article><article class="revision"><strong>Previous · ${escapeHtml(baselineTag)}</strong><small>Commit SHA</small><code>${escapeHtml(baselineSha)}</code><small>Harness fingerprint</small><code>${escapeHtml(metadata.previous?.harnessFingerprint ?? "unavailable")}</code></article></div></section>
<footer>${escapeHtml(repository)} · generated ${escapeHtml(generatedAt)} · source metadata resolved ${escapeHtml(metadata.resolvedAt)}</footer>
</main></body></html>`;
    if (Buffer.byteLength(html) > MAX_REPORT_BYTES) throw new Error("Rendered HTML exceeds the 5 MiB artifact limit");
    const comparisonLine = metadata.baselineAvailable
        ? `Previous: \`${metadata.previous.tag}\` (\`${metadata.previous.sha}\`)`
        : "Previous: unavailable — no lower valid semantic-version tag exists";
    const summary = `## Release benchmark observatory\n\nCurrent: \`${metadata.current.tag}\` (\`${metadata.current.sha}\`)  \n${comparisonLine}  \nComparison: **${comparisonStatus.replaceAll("-", " ")}**  \nRegression signals: **${deltasAvailable ? regressions.length : "unavailable"}**  \nImprovement signals: **${deltasAvailable ? improvements.length : "unavailable"}**  \nInconclusive comparisons: **${deltasAvailable ? inconclusive.length : "unavailable"}**  \nCoverage: **${observedCoverage}/${COVERAGE_TAXONOMY.length} domains observed**\n\n${statusCopy}\n`;
    const manifest = {
        schemaVersion: 1,
        kind: "graphite-tag-benchmark-diff",
        comparisonStatus,
        workloadCompatible,
        repository,
        generatedAt,
        metadata,
        coverageTaxonomy: COVERAGE_TAXONOMY,
        measurements: current.map((result, index) => ({
            benchmark: result.benchmark,
            mode: result.mode,
            unit: result.scoreUnit,
            previousScore: previous?.[index]?.score ?? null,
            currentScore: result.score,
            deltaPercent: renderedRows[index].delta,
            classification: renderedRows[index].classification
        }))
    };
    return { html, summary, manifest };
}

function parseArgs(argv) {
    const args = {};
    for (let index = 0; index < argv.length; index += 2) {
        const name = argv[index];
        const value = argv[index + 1];
        if (!name?.startsWith("--") || value === undefined) throw new Error(`Invalid argument near ${name ?? "<end>"}`);
        args[name.slice(2)] = value;
    }
    return args;
}

function required(args, name) {
    const value = args[name];
    if (value === undefined || value === "") throw new Error(`Missing --${name}`);
    return value;
}

function writeJson(file, value) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
}

function resolveCommand(args) {
    const metadata = resolveGitTagMetadata({
        currentTag: required(args, "current-tag"),
        expectedRef: args["expected-ref"] ?? "",
        expectedSha: args["expected-sha"] ?? "",
        resolvedAt: args["resolved-at"] ?? new Date().toISOString()
    });
    writeJson(required(args, "output"), metadata);
}

function renderCommand(args) {
    const metadata = JSON.parse(fs.readFileSync(required(args, "metadata"), "utf8"));
    const currentResults = JSON.parse(fs.readFileSync(required(args, "current"), "utf8"));
    const previousResults = args.previous === undefined ? null : JSON.parse(fs.readFileSync(args.previous, "utf8"));
    const report = buildTagDiffReport({
        metadata,
        currentResults,
        previousResults,
        repository: args.repository ?? "johnsonlee/graphite",
        runUrl: args["run-url"] ?? "",
        generatedAt: args["generated-at"] ?? new Date().toISOString()
    });
    const output = required(args, "output");
    const summary = required(args, "summary");
    const manifest = required(args, "manifest");
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output, report.html);
    fs.mkdirSync(path.dirname(summary), { recursive: true });
    fs.writeFileSync(summary, report.summary);
    writeJson(manifest, report.manifest);
}

function main() {
    const [command, ...argv] = process.argv.slice(2);
    const args = parseArgs(argv);
    if (command === "resolve") resolveCommand(args);
    else if (command === "render") renderCommand(args);
    else throw new Error(`Unknown command: ${command ?? "<missing>"}`);
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
    try {
        main();
    } catch (error) {
        console.error(error instanceof Error ? error.message : error);
        process.exitCode = 1;
    }
}

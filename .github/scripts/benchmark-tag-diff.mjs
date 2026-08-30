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
    return [
        pluginVersion,
        runtimeVersion,
        coreLibrary,
        generatorLibrary,
        modulePlugin,
        coreDependency,
        generatorDependency,
        jmhBlock
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
        if (baseline !== undefined) {
            if (baseline.mode !== result.mode || baseline.scoreUnit !== result.scoreUnit) {
                throw new Error(`Incomparable JMH modes or units for ${result.benchmark}`);
            }
            if (deltasAvailable && baseline.score > 0) delta = (result.score / baseline.score - 1) * 100;
        }
        const deltaMarkup = delta === null
            ? "<span class=\"neutral\">unavailable</span>"
            : `<span class="${delta > 0 ? "slower" : delta < 0 ? "faster" : "neutral"}">${escapeHtml(signedPercent(delta))}</span>`;
        return {
            delta,
            html: `<tr><td><code>${escapeHtml(shortName(result.benchmark))}</code></td><td class="number">${baseline === undefined ? "unavailable" : escapeHtml(score(baseline.score))}</td><td class="number">${escapeHtml(score(result.score))}</td><td>${escapeHtml(result.scoreUnit)}</td><td class="number">${deltaMarkup}</td><td class="confidence"><small>previous</small>${baseline === undefined ? "unavailable" : escapeHtml(confidence(baseline))}<small>current</small>${escapeHtml(confidence(result))}</td></tr>`
        };
    });
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
    const html = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="dark"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; base-uri 'none'; form-action 'none'"><title>Graphite tag benchmark diff</title>
<style>
:root{--bg:#07111f;--panel:#102139;--panel2:#142b47;--text:#eef6ff;--muted:#9bb0c9;--line:#2b4668;--cyan:#6de5d0;--blue:#8cb9ff;--good:#62dda0;--bad:#ff7585;--warn:#f5cf68;font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}*{box-sizing:border-box}body{margin:0;color:var(--text);background:radial-gradient(circle at 12% 2%,#173e68 0,transparent 34rem),radial-gradient(circle at 90% 8%,#164941 0,transparent 30rem),var(--bg);line-height:1.5}main{width:min(1380px,calc(100% - 2rem));margin:auto;padding:2rem 0 4rem}.hero,.section,.coverage{border:1px solid #ffffff18;background:linear-gradient(145deg,#132943ed,#0b1728ed);box-shadow:0 22px 55px #0005}.hero{position:relative;overflow:hidden;border-radius:1.8rem;padding:clamp(1.5rem,4vw,4rem)}.hero:after{content:"";position:absolute;right:-5rem;top:-8rem;width:21rem;height:21rem;border-radius:50%;background:linear-gradient(135deg,var(--cyan),var(--blue));filter:blur(12px);opacity:.2}.eyebrow{margin:0;color:var(--cyan);font-size:.76rem;font-weight:850;letter-spacing:.17em;text-transform:uppercase}.hero h1{max-width:900px;margin:.35rem 0 1rem;font-size:clamp(2.4rem,6vw,5.5rem);line-height:.96;letter-spacing:-.055em}.hero-copy{max-width:900px;color:var(--muted);font-size:1.05rem}.pills{display:flex;gap:.65rem;flex-wrap:wrap;margin-top:1.3rem}.pill{padding:.42rem .72rem;border:1px solid var(--line);border-radius:999px;background:#ffffff0b;color:var(--muted);font-size:.83rem}.pill.available{color:var(--good);border-color:#62dda066}.pill.unavailable{color:var(--warn);border-color:#f5cf6866}.button{display:inline-flex;margin-top:1.2rem;padding:.65rem .85rem;border-radius:.7rem;background:var(--cyan);color:#052019;text-decoration:none;font-weight:800}.kpis{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:1rem;margin:1rem 0}.kpi,.section{border-radius:1.15rem}.kpi{padding:1.1rem;background:#102139d9;border:1px solid #ffffff15}.kpi strong{display:block;font-size:1.55rem;letter-spacing:-.035em}.kpi span{color:var(--muted);font-size:.82rem}.section{padding:clamp(1rem,2.5vw,2rem);margin-top:1rem}.section-head{display:flex;justify-content:space-between;gap:1rem;align-items:end;margin-bottom:1rem}.section h2{margin:.2rem 0;font-size:clamp(1.45rem,3vw,2.25rem)}.section-head p{margin:0;color:var(--muted)}.metadata{display:grid;grid-template-columns:1fr 1fr;gap:1rem}.revision{padding:1rem;border-radius:.9rem;background:#081526;border:1px solid var(--line)}.revision small{display:block;color:var(--muted);margin-top:.45rem}.revision code{overflow-wrap:anywhere}.table-scroll{overflow:auto;border:1px solid var(--line);border-radius:.85rem}table{width:100%;border-collapse:collapse;background:#07111f99;font-size:.88rem}th,td{padding:.75rem;text-align:left;border-bottom:1px solid var(--line);vertical-align:top}th{background:#17304e;color:#c3d5e9;white-space:nowrap}td.number{font-variant-numeric:tabular-nums;white-space:nowrap}code{font-family:"SFMono-Regular",Consolas,monospace;background:#ffffff0b;border:1px solid #ffffff12;border-radius:.35rem;padding:.08rem .3rem}.confidence{min-width:16rem}.confidence small{display:block;color:var(--muted);text-transform:uppercase;font-size:.65rem;letter-spacing:.08em}.faster{color:var(--good);font-weight:800}.slower{color:var(--bad);font-weight:800}.neutral{color:var(--muted)}.coverage-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:1rem}.coverage{padding:1rem;border-radius:1rem}.coverage span{display:inline-block;color:var(--muted);font-size:.7rem;font-weight:800;letter-spacing:.1em;text-transform:uppercase}.coverage.observed span{color:var(--good)}.coverage h3{margin:.25rem 0}.coverage p{color:var(--muted);font-size:.88rem;margin:.5rem 0 0}.notice{border-left:4px solid var(--warn);padding:1rem;background:#f5cf6810;border-radius:.4rem}.notice strong{display:block;color:var(--warn)}footer{text-align:center;color:var(--muted);padding:2rem 1rem}@media(max-width:850px){.kpis,.coverage-grid{grid-template-columns:repeat(2,1fr)}.metadata{grid-template-columns:1fr}}@media(max-width:540px){main{width:min(100% - 1rem,1380px)}.kpis,.coverage-grid{grid-template-columns:1fr}.hero{border-radius:1.15rem}.confidence{min-width:12rem}}
</style></head><body><main>
<header class="hero"><p class="eyebrow">Graphite release telemetry</p><h1>Tag benchmark diff</h1><p class="hero-copy">A bounded, same-runner method-level comparison between an exact release tag and its semantic predecessor, rendered with the PR #104 coverage taxonomy and explicit evidence boundaries.</p><div class="pills"><span class="pill ${metadata.baselineAvailable ? "available" : "unavailable"}">${escapeHtml(statusTitle)}</span><span class="pill">current <code>${escapeHtml(metadata.current.tag)}</code></span><span class="pill">previous <code>${escapeHtml(baselineTag)}</code></span></div>${runUrl === "" ? "" : `<a class="button" href="${escapeHtml(safeUrl(runUrl))}">Open workflow run</a>`}</header>
<section class="kpis"><article class="kpi"><strong>${current.length}</strong><span>representative JMH methods</span></article><article class="kpi"><strong>${medianDelta === null ? "—" : escapeHtml(signedPercent(medianDelta))}</strong><span>median current-vs-previous delta</span></article><article class="kpi"><strong>1 fork</strong><span>2 warmup / 4 measurement iterations</span></article><article class="kpi"><strong>No verdict</strong><span>informational and independent of publishing</span></article></section>
<section class="section"><div class="section-head"><div><p class="eyebrow">Exact provenance</p><h2>Compared revisions</h2></div></div><div class="metadata"><article class="revision"><strong>Current · ${escapeHtml(metadata.current.tag)}</strong><small>Commit SHA</small><code>${escapeHtml(metadata.current.sha)}</code><small>Harness fingerprint</small><code>${escapeHtml(metadata.current.harnessFingerprint)}</code></article><article class="revision"><strong>Previous · ${escapeHtml(baselineTag)}</strong><small>Commit SHA</small><code>${escapeHtml(baselineSha)}</code><small>Harness fingerprint</small><code>${escapeHtml(metadata.previous?.harnessFingerprint ?? "unavailable")}</code></article></div><p class="notice"><strong>${escapeHtml(statusTitle)}</strong>${escapeHtml(statusCopy)}</p></section>
<section class="section"><div class="section-head"><div><p class="eyebrow">Method-level latency</p><h2>Current vs previous</h2><p>Scores use JMH AverageTime. Lower is better. Confidence bounds are descriptive and do not establish a release gate.</p></div></div><div class="table-scroll"><table><thead><tr><th>Benchmark</th><th>Previous</th><th>Current</th><th>Unit</th><th>Delta</th><th>99.9% confidence bounds</th></tr></thead><tbody>${rows}</tbody></table></div></section>
<section class="section"><div class="section-head"><div><p class="eyebrow">PR #104 coverage taxonomy</p><h2>What this report does—and does not—measure</h2><p>An observed area is still intentionally partial; unmeasured areas never inherit a verdict from method latency.</p></div></div><div class="coverage-grid">${renderCoverage()}</div></section>
<footer>${escapeHtml(repository)} · generated ${escapeHtml(generatedAt)} · source metadata resolved ${escapeHtml(metadata.resolvedAt)}</footer>
</main></body></html>`;
    if (Buffer.byteLength(html) > MAX_REPORT_BYTES) throw new Error("Rendered HTML exceeds the 5 MiB artifact limit");
    const comparisonLine = metadata.baselineAvailable
        ? `Previous: \`${metadata.previous.tag}\` (\`${metadata.previous.sha}\`)`
        : "Previous: unavailable — no lower valid semantic-version tag exists";
    const summary = `## Tag benchmark diff\n\nCurrent: \`${metadata.current.tag}\` (\`${metadata.current.sha}\`)  \n${comparisonLine}  \nComparison: **${comparisonStatus.replaceAll("-", " ")}**  \nRepresentative measurements: **${current.length}**\n\n${statusCopy}\n`;
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
            deltaPercent: renderedRows[index].delta
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

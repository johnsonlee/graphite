#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath, pathToFileURL } from "node:url";

export const QUERIES = [
    "valueHit", "valueMiss", "qualifiedIdHit", "qualifiedIdMiss", "dynamicHit", "dynamicMiss",
    "wrappedCallerHit", "wrappedCallerMiss", "dataflowSourceHit", "dataflowSourceMiss",
    "dataflowTargetHit", "dataflowTargetMiss"
];
export const DYNAMIC_QUERIES = ["dynamicHit", "dynamicMiss"];
export const COMPONENT = {
    name: "slow-query-shapes", report: "slow-query-shapes-report.md", status: "slow-query-shapes-status.json",
    coverage: "partial", gap: "Five query families on real Android, hit/miss and cold/warm; other corpora and arbitrary expressions remain uncovered."
};
const BENCHMARK = "io.johnsonlee.graphite.webgraph.SlowQueryShapesBenchmark.execute";
const STATES = ["COLD", "WARM"];
// Five independent single-shot JVMs per row. Three gave a point estimate with no usable
// spread: on the hosted runner the same jar measured 214 ms and 396 ms in consecutive forks,
// and three consecutive pull requests with identical JVM sources failed the 15% check on
// one row each. Five forks make JMH's 99.9% confidence interval narrow enough to tell a
// regression from that noise, and the verdict below requires the intervals to separate.
const FORKS = 5;
const require = (condition, message) => { if (!condition) throw new Error(message); };
const finitePositive = value => typeof value === "number" && Number.isFinite(value) && value > 0;
const key = (state, query) => `${state}/${query}`;
const expected = queries => new Set(STATES.flatMap(state => queries.map(query => key(state, query))));
const sameKeys = (actual, wanted) => actual.size === wanted.size && [...wanted].every(item => actual.has(item));

export function measurements(entries, queries, fixture) {
    const result = new Map();
    for (const entry of entries) {
        require(entry.benchmark === BENCHMARK && entry.mode === "ss", "Unexpected benchmark or mode");
        require(entry.threads === 1 && entry.forks === FORKS && entry.warmupIterations === 0 &&
            entry.measurementIterations === 1, `Expected ${FORKS} independent single-shot forks`);
        require(entry.params?.corpus === "android" && Object.keys(entry.params).sort().join() ===
            "cacheState,corpus,queryName", "Unexpected parameters");
        const id = key(entry.params.cacheState, entry.params.queryName);
        require(!result.has(id), `Duplicate measurement: ${id}`);
        require(["-Xmx8g", "-XX:ActiveProcessorCount=4", `-Dandroid.graph.path=${fixture}`]
            .every(arg => entry.jvmArgs?.includes(arg)), `Unexpected JVM/fixture arguments: ${id}`);
        const metric = entry.primaryMetric;
        require(metric?.scoreUnit === "ms/op" && finitePositive(metric.score), `Invalid latency: ${id}`);
        require(metric.rawData?.length === FORKS && metric.rawData.every(samples =>
            samples.length === 1 && samples.every(finitePositive)), `Missing fork samples: ${id}`);
        const mean = metric.rawData.reduce((sum, samples) => sum + samples[0], 0) / FORKS;
        require(Math.abs(mean - metric.score) <= Math.max(1e-9, mean * 1e-9), `Inconsistent JMH score: ${id}`);
        // JMH's 99.9% confidence interval over the fork samples; the verdict needs it, so a
        // result without a finite one is refused rather than judged on the point estimate.
        const confidence = metric.scoreConfidence;
        require(Array.isArray(confidence) && confidence.length === 2 && confidence.every(Number.isFinite) &&
            confidence[0] <= metric.score && metric.score <= confidence[1], `Missing confidence interval: ${id}`);
        result.set(id, { ms: metric.score, samples: metric.rawData.map(samples => samples[0]), confidence: [...confidence] });
    }
    require(sameKeys(result, expected(queries)), "Missing or unexpected query/state measurements");
    return result;
}

export function resultMarkers(text, queries, fixture, exists = fs.existsSync) {
    require(!/<failure>|Exception in thread|ERROR:/.test(text), "Benchmark execution failure marker");
    const rows = new Map();
    for (const match of text.matchAll(/SLOW_QUERY_SHAPE_RESULT\tandroid\t([^\t]+)\t([^\t]+)\trows=(\d+)\tsha256=([a-f0-9]{64})/g)) {
        const [, state, query, countText, digest] = match;
        const count = Number(countText), id = key(state, query);
        require(count <= 50 && (query.endsWith("Hit") ? count > 0 : count === 0), `Unexpected hit/miss result: ${id}`);
        const previous = rows.get(id);
        require(previous === undefined || previous.rows === count && previous.sha256 === digest,
            `Ordered result changed between forks: ${id}`);
        rows.set(id, { rows: count, sha256: digest, forks: (previous?.forks ?? 0) + 1 });
    }
    require(sameKeys(rows, expected(queries)), "Missing or unexpected ordered result keys");
    require([...rows.values()].every(row => row.forks === FORKS), "Missing or duplicate result fork");
    const snapshots = [...text.matchAll(/SLOW_QUERY_SHAPE_FIXTURE\tprotocol=private-copy-no-callsite-index-v2\tcorpus=android\tsource=([^\t]+)\tsnapshot=([^\t]+)\tindexAbsent=true/g)];
    require(snapshots.length === rows.size * FORKS, "Incorrect private fixture count");
    require(new Set(snapshots.map(match => match[2])).size === snapshots.length, "Reused private fixture");
    for (const [, source, snapshot] of snapshots) {
        require(source === fixture && path.basename(snapshot) === "android" &&
            path.basename(path.dirname(snapshot)).startsWith("graphite-slow-query-shapes-"), "Unexpected fixture identity");
        require(!exists(path.dirname(snapshot)), `Private fixture was not removed: ${snapshot}`);
    }
    return rows;
}

export function compare(base, candidate, baseResults, candidateResults, referenceKind) {
    require(["unmodified-base", "base-plus-subscript-correctness-repair"].includes(referenceKind), "Unknown reference policy");
    require([base, candidate, baseResults, candidateResults].every(values => sameKeys(values, expected(QUERIES))),
        "Expected all 24 cold/warm query keys");
    const rows = [...expected(QUERIES)].map(id => {
        const a = baseResults.get(id), b = candidateResults.get(id);
        require(a.rows === b.rows && a.sha256 === b.sha256, `Ordered semantic mismatch: ${id}`);
        const baseline = base.get(id), current = candidate.get(id);
        const delta = (current.ms / baseline.ms - 1) * 100;
        // Blocked only when the candidate is more than 15% slower and its whole 99.9%
        // confidence interval lies above the base's: the rule the method-level gate applies.
        // A point estimate over the threshold with overlapping intervals is noise until a
        // run says otherwise, and is reported as such rather than confirmed.
        const confidenceSeparated = current.confidence[0] > baseline.confidence[1];
        return { key: id, reference: DYNAMIC_QUERIES.includes(id.split("/")[1]) ? referenceKind : "unmodified-base",
            baseMs: baseline.ms, candidateMs: current.ms, baseSamples: baseline.samples,
            candidateSamples: current.samples, baseConfidence: baseline.confidence, candidateConfidence: current.confidence,
            rows: a.rows, sha256: a.sha256, delta, confidenceSeparated, blocked: delta > 15 && confidenceSeparated };
    });
    return { passed: rows.every(row => !row.blocked), errors: [], thresholdPercent: 15, confidencePercent: 99.9,
        referenceKind, orderedResultParity: true, rows };
}

export function confirm(initial, confirmation) {
    require(initial.errors?.length === 0 && confirmation.errors?.length === 0, "Integrity failure cannot be cleared by confirmation");
    require(initial.referenceKind === confirmation.referenceKind, "Reference policy changed during confirmation");
    require(initial.rows?.length === 24 && confirmation.rows?.length === 24, "Incomplete confirmation coverage");
    require(sameKeys(new Map(initial.rows.map(row => [row.key, row])), expected(QUERIES)), "Incorrect initial keys");
    const repeats = new Map(confirmation.rows.map(row => [row.key, row]));
    require(sameKeys(repeats, expected(QUERIES)), "Incorrect confirmation keys");
    const rows = initial.rows.map(row => {
        const repeat = repeats.get(row.key);
        require(repeat && row.sha256 === repeat.sha256 && row.rows === repeat.rows,
            `Semantic result changed during confirmation: ${row.key}`);
        return { ...row, confirmation: repeat, blocked: row.blocked && repeat.blocked };
    });
    return { ...initial, passed: rows.every(row => !row.blocked), rows, confirmedInReverseOrder: true };
}

export function render(comparison) {
    const lines = ["### Five slow query families", "", "Real persisted Android; value, qualifiedId, dynamic properties, toString caller, and single-hop DATAFLOW. Every hit/miss runs COLD and WARM in five fresh private mappings. Ordered full result digests must match.",
        "COLD means a fresh mapping with no persisted callsite index, not cold OS pages. Primary latency excludes fixture copying; first-trial GC profiler values include setup/priming/cleanup and are diagnostic only.",
        "The ongoing gate blocks a row only when the candidate is more than 15% slower than the current base and the two 99.9% confidence intervals over the five forks do not overlap, confirmed candidate-first; a point estimate over 15% with overlapping intervals is reported and passes. Historical 10× acceptance against 144d98ef is a separate experiment.",
        `Dynamic reference: ${comparison.referenceKind ?? "unavailable"}. The legacy repair changes only string-key subscripting; other cases always use unmodified base.`, "",
        "| Query/state | Base ms (99.9% CI) | Candidate ms (99.9% CI) | Change | Separated | Confirmation change | Gate |", "|---|---:|---:|---:|:---:|---:|:---:|"];
    const ci = (ms, bounds) => `${ms.toFixed(3)} (${bounds[0].toFixed(1)}–${bounds[1].toFixed(1)})`;
    for (const row of comparison.rows ?? []) lines.push(`| ${row.key} | ${ci(row.baseMs, row.baseConfidence)} | ${ci(row.candidateMs, row.candidateConfidence)} | ${row.delta.toFixed(1)}% | ${row.confidenceSeparated ? "yes" : "no"} | ${row.confirmation ? row.confirmation.delta.toFixed(1) + "%" : "—"} | ${row.blocked ? "FAIL" : "PASS"} |`);
    if (comparison.errors?.length) lines.push("", ...comparison.errors.map(error => `- ${error}`));
    return lines.join("\n") + "\n";
}

export function addComponent(baseModule) {
    require(!baseModule.BENCHMARK_COMPONENTS.some(component => component.name === COMPONENT.name), "Duplicate slow-shape component registration");
    const domain = baseModule.BENCHMARK_COVERAGE_DOMAINS.find(item => item.name === "Latency regression");
    require(domain !== undefined, "Base aggregator lacks latency domain");
    baseModule.BENCHMARK_COMPONENTS.push({ ...COMPONENT });
    domain.components.push(COMPONENT.name);
}

function fingerprint(directory) {
    return fs.readdirSync(directory).sort().map(name => {
        const file = path.join(directory, name), stat = fs.lstatSync(file, { bigint: true });
        require(stat.isFile(), `Unexpected shared fixture entry: ${file}`);
        const hash = crypto.createHash("sha256"), buffer = Buffer.alloc(1024 * 1024), fd = fs.openSync(file, "r");
        try { let count; while ((count = fs.readSync(fd, buffer)) > 0) hash.update(buffer.subarray(0, count)); }
        finally { fs.closeSync(fd); }
        return { name, bytes: stat.size.toString(), mtimeNs: stat.mtimeNs.toString(), sha256: hash.digest("hex") };
    });
}

const read = file => JSON.parse(fs.readFileSync(file, "utf8"));
const write = (file, value) => { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, typeof value === "string" ? value : JSON.stringify(value, null, 2) + "\n"); };
async function main(argv) {
    const command = argv.shift(), args = {};
    while (argv.length) { const option = argv.shift(); require(option.startsWith("--") && argv.length, `Invalid option: ${option}`); args[option.slice(2)] = argv.shift(); }
    const needed = name => { require(args[name], `Missing --${name}`); return args[name]; };
    try {
        if (command === "fingerprint") write(needed("output"), fingerprint(needed("fixture")));
        else if (command === "verify-fixture") {
            require(JSON.stringify(read(needed("before"))) === JSON.stringify(fingerprint(needed("fixture"))), "Shared persisted fixture changed");
            write(needed("output"), { sharedFixtureUnchanged: true });
        } else if (command === "aggregate") {
            const base = await import(pathToFileURL(path.resolve(needed("base-comparator"))).href);
            addComponent(base);
            const result = base.aggregateReports(needed("directory"), { baseSha: needed("base-sha"), candidateSha: needed("candidate-sha"), runner: needed("runner"), runUrl: needed("run-url") });
            write(needed("report"), result.body); write(needed("status"), result);
            // Match the base reporter: publish a failed verdict completely, then let the
            // workflow's authoritative enforcement step reject it after artifact upload.
        } else {
            let result;
            if (command === "confirm") result = confirm(read(needed("initial")), read(needed("confirmation")));
            else {
                require(command === "compare", `Unknown command: ${command}`);
                const fixture = needed("fixture"), policy = needed("reference-kind");
                const regular = policy === "base-plus-subscript-correctness-repair" ? QUERIES.filter(query => !DYNAMIC_QUERIES.includes(query)) : QUERIES;
                const base = measurements(read(needed("base")), regular, fixture);
                const baseResults = resultMarkers(fs.readFileSync(needed("base-log"), "utf8"), regular, fixture);
                if (policy === "base-plus-subscript-correctness-repair") {
                    for (const [id, value] of measurements(read(needed("reference")), DYNAMIC_QUERIES, fixture)) base.set(id, value);
                    for (const [id, value] of resultMarkers(fs.readFileSync(needed("reference-log"), "utf8"), DYNAMIC_QUERIES, fixture)) baseResults.set(id, value);
                }
                result = compare(base, measurements(read(needed("candidate")), QUERIES, fixture), baseResults,
                    resultMarkers(fs.readFileSync(needed("candidate-log"), "utf8"), QUERIES, fixture), policy);
            }
            write(needed("status"), result); write(needed("report"), render(result));
            if (!result.passed) process.exitCode = 1;
        }
    } catch (error) {
        const failure = { passed: false, errors: [error.message], rows: [] };
        if (args.status) write(args.status, failure);
        if (args.report) write(args.report, render(failure));
        console.error(error.stack); process.exitCode = 1;
    }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main(process.argv.slice(2));

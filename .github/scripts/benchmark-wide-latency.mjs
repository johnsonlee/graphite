import fs from "node:fs";

export const WIDE_SAMPLE_COUNT = 40; // Minimum, never a truncation limit.
export const WIDE_PROTOCOL = Object.freeze({ warmupMinNanos: 10_000_000_000, warmupMinCalls: 5,
    measurementMinNanos: 10_000_000_000, measurementMinCalls: WIDE_SAMPLE_COUNT });
export const WIDE_SCHEMA = "graphite-wide-latency-v2";
const reviewedCatalog = JSON.parse(fs.readFileSync(new URL("./wide-query-catalog.json", import.meta.url), "utf8"));
export function selectWideCatalog(catalog = reviewedCatalog.queries, { shard } = {}) {
    const ids = catalog.map(query => query.id);
    if (catalog.length !== 72 || new Set(ids).size !== 72 ||
        reviewedCatalog.queries.some(query => !ids.includes(query.id))) throw new Error("catalog: exactly 72 reviewed unique queries required");
    if (shard === undefined) return catalog;
    const subset = reviewedCatalog.shards[shard];
    if (!subset) throw new Error(`Unknown wide-query shard ${shard}`);
    return catalog.filter(query => subset.includes(query.id));
}
export const WIDE_FORK_COUNT = 3;
const SIGNATURE_FIELDS = ["id", "family", "shape", "selectivity", "operator", "boundary", "projection",
    "targetGraphId", "workloadIdentity", "limit", "outcome", "rowCount", "responseBytes", "digest"];
const percentile = (samples, fraction) => [...samples].sort((a, b) => a - b)[Math.ceil(samples.length * fraction) - 1];

// Accept synchronous line iterables so multi-gigabyte evidence never becomes one JS string.
function* sampleLines(content) {
    if (typeof content !== "string") { yield* content; return; }
    let start = 0;
    while (start < content.length) {
        const end = content.indexOf("\n", start);
        const lineEnd = end < 0 ? content.length : end;
        yield content.slice(start, lineEnd).replace(/\r$/, "");
        start = lineEnd + 1;
    }
}

// Each query is its own experiment. Never pool unrelated query timings into an acceptance percentile.
export function compareWideLatency(baseContents, candidateContents, oracleContents, manifestContents,
    catalog = reviewedCatalog.queries, options = {}) {
    const integrityErrors = [];
    const latencyErrors = [];
    const partial = options.partial === true;
    if (options.partial !== undefined && typeof options.partial !== "boolean") integrityErrors.push("partial must be boolean");
    const expectedForks = partial ? baseContents.length : WIDE_FORK_COUNT;
    if (partial && (!Number.isInteger(expectedForks) || expectedForks < 1 || expectedForks > WIDE_FORK_COUNT ||
        candidateContents.length !== expectedForks)) integrityErrors.push("partial validation requires one to three complete paired forks");
    try { catalog = selectWideCatalog(catalog, options); }
    catch (error) { integrityErrors.push(error.message); }
    const expected = new Map(catalog.map(query => [query.id, query]));
    const graphIds = new Set(manifestContents.split(/\r?\n/).filter(line => line && !line.startsWith("#"))
        .map(line => line.split("\t")[0]));
    if (graphIds.size !== 64) integrityErrors.push("latency manifest: exactly 64 unique real graph IDs required");
    const oracle = new Map();
    for (const line of oracleContents.split(/\r?\n/).filter(line => line && !line.startsWith("#"))) {
        const values = line.split("|");
        const row = Object.fromEntries(SIGNATURE_FIELDS.map((field, i) => [field, values[i]]));
        if (values.length !== SIGNATURE_FIELDS.length || oracle.has(row.id) || !expected.has(row.id)) {
            integrityErrors.push(`oracle/${row.id}: malformed, duplicate or unexpected query`);
            continue;
        }
        if (row.outcome !== "success" || !/^[0-9a-f]{64}$/.test(row.digest) ||
            ["limit", "rowCount", "responseBytes"].some(field => !/^\d+$/.test(row[field]))) {
            integrityErrors.push(`oracle/${row.id}: invalid result signature`);
        }
        const entry = expected.get(row.id);
        for (const field of ["family", "shape", "selectivity", "operator", "boundary", "projection", "limit", "workloadIdentity"]) {
            if (entry[field] !== undefined && String(entry[field]) !== row[field]) {
                integrityErrors.push(`oracle/${row.id}: ${field} differs from reviewed catalog`);
            }
        }
        oracle.set(row.id, row);
    }
    for (const id of expected.keys()) if (!oracle.has(id)) integrityErrors.push(`oracle/${id}: missing query`);
    const provenance = new Map();
    const parseForks = (contents, revision) => {
        if (contents.length !== expectedForks) integrityErrors.push(`${revision}: expected ${expectedForks} independent forks`);
        return contents.map((content, fork) => {
            const lines = sampleLines(content);
            const header = lines.next().value ?? "";
            const headers = header.split("\t");
            const required = [...SIGNATURE_FIELDS, "phase", "round", "phaseElapsedNanos", "latencyNanos", "hitGraphIds", "executionPath", "inputSourceCount"];
            if (headers.slice(0, 3).join("\t") !== "phase\tround\tphaseElapsedNanos" ||
                new Set(headers).size !== headers.length || required.some(field => !headers.includes(field))) {
                integrityErrors.push(`${revision}-${fork + 1}: incomplete or duplicate sample columns`);
            }
            const samples = new Map([...expected.keys()].map(id => [id, {
                warmup: [], measurement: [], warmupElapsedNanos: 0, measurementElapsedNanos: 0,
                warmupLatencySum: 0, measurementLatencySum: 0,
            }]));
            let currentId;
            const completed = new Set();
            for (const line of lines) {
                const values = line.split("\t");
                const row = Object.fromEntries(headers.map((field, i) => [field, values[i]]));
                const label = `${revision}-${fork + 1}/${row.id}/${row.phase}/round-${row.round}`;
                const round = Number(row.round);
                const latency = Number(row.latencyNanos);
                const elapsed = Number(row.phaseElapsedNanos);
                const querySamples = samples.get(row.id);
                if (values.length !== headers.length || !querySamples ||
                    !["warmup", "measurement"].includes(row.phase) || !Number.isSafeInteger(round) || round < 1) {
                    integrityErrors.push(`${label}: malformed or unexpected sample`);
                    continue;
                }
                if (row.id !== currentId) {
                    if (currentId !== undefined) completed.add(currentId);
                    if (completed.has(row.id)) integrityErrors.push(`${label}: query phases must be contiguous`);
                    currentId = row.id;
                }
                const phase = row.phase;
                if (round !== querySamples[phase].length + 1) integrityErrors.push(`${label}: phase ordinals must be contiguous from one`);
                if (phase === "warmup" && querySamples.measurement.length) integrityErrors.push(`${label}: warmup must precede measurement`);
                if (phase === "measurement" && (!querySamples.warmup.length ||
                    querySamples.warmup.length < WIDE_PROTOCOL.warmupMinCalls ||
                    querySamples.warmupElapsedNanos < WIDE_PROTOCOL.warmupMinNanos)) {
                    integrityErrors.push(`${label}: complete timed warmup must precede measurement`);
                }
                if (!Number.isSafeInteger(latency) || latency <= 0) integrityErrors.push(`${label}: latency must be positive integer nanoseconds`);
                const previousElapsed = querySamples[`${phase}ElapsedNanos`];
                if (!Number.isSafeInteger(elapsed) || elapsed <= previousElapsed ||
                    latency > elapsed - previousElapsed) integrityErrors.push(`${label}: phase duration must increase and contain call latency`);
                querySamples[phase].push(latency);
                querySamples[`${phase}ElapsedNanos`] = elapsed;
                querySamples[`${phase}LatencySum`] += latency;
                if (querySamples[`${phase}LatencySum`] > elapsed) integrityErrors.push(`${label}: summed latency exceeds phase duration`);
                const signature = oracle.get(row.id);
                for (const field of SIGNATURE_FIELDS) {
                    if (!signature || row[field] !== signature[field]) integrityErrors.push(`${label}: ${field} differs from correctness oracle`);
                }
                if (row.executionPath !== "cross-graph-query" || row.inputSourceCount !== "64") {
                    integrityErrors.push(`${label}: must execute an unscoped query across 64 sources`);
                }
                const hits = row.hitGraphIds ? row.hitGraphIds.split(",") : [];
                if (new Set(hits).size !== hits.length || hits.some(id => !graphIds.has(id)) ||
                    (Number(row.rowCount) === 0 ? hits.length !== 0 : hits.length === 0)) {
                    integrityErrors.push(`${label}: invalid result graph provenance`);
                }
                const orderedHits = [...hits].sort().join(",");
                if (!provenance.has(row.id)) provenance.set(row.id, orderedHits);
                else if (provenance.get(row.id) !== orderedHits) integrityErrors.push(`${label}: result graph provenance differs across samples`);
                const entry = expected.get(row.id);
                if (entry.expectedMatchingGraphIds && hits.some(id => !entry.expectedMatchingGraphIds.includes(id))) {
                    integrityErrors.push(`${label}: result graph is outside the independently calibrated matching graphs`);
                }
            }
            for (const [id, rows] of samples) for (const phase of ["warmup", "measurement"]) {
                if (rows[phase].length < WIDE_PROTOCOL[`${phase}MinCalls`] ||
                    rows[`${phase}ElapsedNanos`] < WIDE_PROTOCOL[`${phase}MinNanos`]) {
                    integrityErrors.push(`${revision}-${fork + 1}/${id}: ${phase} requires at least ` +
                        `${WIDE_PROTOCOL[`${phase}MinCalls`]} calls and 10 seconds`);
                }
            }
            return samples;
        });
    };
    const baseForks = parseForks(baseContents, "base");
    const candidateForks = parseForks(candidateContents, "candidate");
    const queries = catalog.map(entry => {
        const errors = integrityErrors.filter(error => error.includes(`/${entry.id}:`) || error.includes(`/${entry.id}/`));
        const runs = [];
        for (let fork = 0; fork < expectedForks; fork++) {
            const baseRow = baseForks[fork]?.get(entry.id);
            const candidateRow = candidateForks[fork]?.get(entry.id);
            const base = baseRow?.measurement ?? [];
            const candidate = candidateRow?.measurement ?? [];
            if (base.length < WIDE_SAMPLE_COUNT || candidate.length < WIDE_SAMPLE_COUNT ||
                [...base, ...candidate].some(value => !Number.isSafeInteger(value) || value <= 0)) continue;
            const run = { fork: fork + 1, baseP50Nanos: percentile(base, .5), baseP95Nanos: percentile(base, .95),
                candidateP50Nanos: percentile(candidate, .5), candidateP95Nanos: percentile(candidate, .95) };
            for (const [revision, row] of [["base", baseRow], ["candidate", candidateRow]]) {
                run[`${revision}WarmupCalls`] = row.warmup.length;
                run[`${revision}WarmupElapsedNanos`] = row.warmupElapsedNanos;
                run[`${revision}MeasurementCalls`] = row.measurement.length;
                run[`${revision}MeasurementElapsedNanos`] = row.measurementElapsedNanos;
            }
            for (const quantile of ["P50", "P95"]) {
                // Integer cross multiplication makes the exact 5% boundary fail deterministically.
                if (BigInt(run[`candidate${quantile}Nanos`]) * 100n >= BigInt(run[`base${quantile}Nanos`]) * 105n) {
                    errors.push(`${entry.id}/fork-${fork + 1}: ${quantile} regression must be <5%`);
                }
            }
            runs.push(run);
        }
        const stability = {};
        for (const revision of ["base", "candidate"]) for (const quantile of ["P50", "P95"]) {
            const values = runs.map(run => run[`${revision}${quantile}Nanos`]);
            if (values.length !== expectedForks || values.length < 2) continue;
            const min = Math.min(...values), max = Math.max(...values);
            stability[`${revision}${quantile}SpreadPercent`] = (max - min) / min * 100;
            if (BigInt(max) * 100n >= BigInt(min) * 105n) {
                errors.push(`${entry.id}: ${revision} ${quantile} cross-fork spread (max-min)/min must be <5%`);
            }
        }
        const localIntegrity = new Set(integrityErrors);
        latencyErrors.push(...errors.filter(error => !localIntegrity.has(error)));
        return { id: entry.id, shape: entry.shape, passed: !partial && errors.length === 0 && runs.length === WIDE_FORK_COUNT,
            errors, runs, stability };
    });
    const sharedIntegrityErrors = integrityErrors.filter(error => !catalog.some(query =>
        error.includes(`/${query.id}:`) || error.includes(`/${query.id}/`)));
    return { passed: !partial && integrityErrors.length === 0 && latencyErrors.length === 0,
        ...(partial ? { partial: true, completedForkCount: expectedForks, canContinue: integrityErrors.length === 0 && (expectedForks === 1 || latencyErrors.length === 0) } : {}),
        sharedIntegrityErrors,
        schema: WIDE_SCHEMA, protocol: { ...WIDE_PROTOCOL }, shard: options.shard ?? null,
        queryCount: catalog.length, forkCount: WIDE_FORK_COUNT,
        integrityErrors, latencyErrors, queries };
}

export function renderWideLatency(comparison) {
    // Classification is presentation only: retain the comparator's original errors and verdicts.
    const integrity = new Set(comparison.integrityErrors);
    const classify = error => {
        if (integrity.has(error)) return { category: "Integrity", detail: error };
        if (/\bbase P(?:50|95) cross-fork spread/.test(error)) return { category: "Base instability", detail: error };
        if (/\bcandidate P(?:50|95) cross-fork spread/.test(error)) return { category: "Candidate instability", detail: error };
        if (/P(?:50|95) regression must be <5%/.test(error)) return {
            category: "Paired latency exceedance", detail: error.replace("regression must be", "observed increase must be")
        };
        return { category: "Other evidence failure", detail: error };
    };
    const observations = [...comparison.integrityErrors, ...comparison.latencyErrors].map(classify);
    const categories = ["Paired latency exceedance", "Base instability", "Candidate instability", "Integrity"];
    const awaitingReversePair = comparison.partial && comparison.completedForkCount === 1 &&
        comparison.canContinue && comparison.latencyErrors.length > 0;
    const checkpoint = comparison.partial ? [
        `Partial checkpoint: ${comparison.completedForkCount}/3 paired forks completed; this is not final acceptance.`,
        comparison.completedForkCount < 2 ? "Reverse-order control has not completed." : "Reverse-order control is included in the completed pairs.",
        comparison.completedForkCount < 3 ? "Three-fork stability diagnostics are incomplete; any reported spread uses only completed pairs." :
            "All three pairs are present, but a partial checkpoint does not issue final acceptance.",
        comparison.canContinue === false ? "Observed failures already violate the acceptance contract; fail-fast stops remaining runs." :
            awaitingReversePair ? "Numerical exceedances are retained; the required reverse-order pair runs before a terminal latency decision." :
                "No observed checkpoint failure; final acceptance has not been issued."
    ] : [];
    return ["### Per-query wide latency gates", "",
        `${comparison.queryCount} queries; the protocol requires contiguous warmup ≥10 seconds/5 calls and measurement ≥10 seconds/40 calls in three independent paired forks; maximum heap 8 GiB.`,
        ...checkpoint,
        "All measurement calls are retained for quantiles; each run records its actual phase counts and durations.",
        "Each observed paired P50 and P95 increase must be less than 5%. Both base and candidate cross-fork spread",
        "is (max-min)/min and must be less than 5%. Exact result signatures and provenance must match in every sample.",
        "An observed gate failure does not establish a runtime regression caused by the candidate: order, JIT and environment effects require separate diagnosis.",
        "CPU, peak heap and RSS are diagnostic. The legacy mixed-query percentile is not an acceptance criterion.", "",
        "Failure observations by category (counts are observations, not queries):",
        ...categories.map(category => `- ${category}: ${observations.filter(item => item.category === category).length}`),
        ...(observations.some(item => item.category === "Other evidence failure") ?
            [`- Other evidence failure: ${observations.filter(item => item.category === "Other evidence failure").length}`] : []),
        ...(comparison.sharedIntegrityErrors.length ? ["", "Shared integrity failures:", ...comparison.sharedIntegrityErrors.map(error => `- ${error}`)] : []), "",
        "| Query | Observed pairs | Result | Paired P50 base → candidate (ms) | Paired P95 base → candidate (ms) | Failures |",
        "|---|---|---|---|---|---|",
        ...comparison.queries.map(query => {
            const result = comparison.partial ? (query.errors.length ? (awaitingReversePair ? "AWAITING REVERSE PAIR" : "CHECKPOINT FAIL") : "INCOMPLETE") : (query.passed ? "PASS" : "FAIL");
            return `| ${query.id} | ${query.runs.length}/3 | ${result} | ` +
                ["P50", "P95"].map(q => query.runs.map(run =>
                    `${(run[`base${q}Nanos`] / 1e6).toFixed(3)} → ${(run[`candidate${q}Nanos`] / 1e6).toFixed(3)}`)
                    .join(" / ")).join(" | ") + ` | ${query.errors.map(error => {
                        const { category, detail } = classify(error);
                        return `${category}: ${detail}`;
                    }).join("; ")} |`;
        }), ""].join("\n");
}

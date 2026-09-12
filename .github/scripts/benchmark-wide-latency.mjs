import fs from "node:fs";

export const WIDE_SAMPLE_COUNT = 40;
export const WIDE_FORK_COUNT = 3;
const SIGNATURE_FIELDS = ["id", "family", "shape", "selectivity", "operator", "boundary", "projection",
    "targetGraphId", "workloadIdentity", "limit", "outcome", "rowCount", "responseBytes", "digest"];
const percentile = (samples, fraction) => [...samples].sort((a, b) => a - b)[Math.ceil(samples.length * fraction) - 1];

// Each query is its own experiment. Never pool unrelated query timings into an acceptance percentile.
export function compareWideLatency(baseContents, candidateContents, oracleContents, manifestContents,
    catalog = JSON.parse(fs.readFileSync(new URL("./wide-query-catalog.json", import.meta.url), "utf8")).queries) {
    const integrityErrors = [];
    const latencyErrors = [];
    const expected = new Map(catalog.map(query => [query.id, query]));
    if (catalog.length !== 72 || expected.size !== 72) integrityErrors.push("catalog: exactly 72 unique queries required");
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
        if (contents.length !== WIDE_FORK_COUNT) integrityErrors.push(`${revision}: exactly three independent forks required`);
        return contents.map((content, fork) => {
            const [header = "", ...lines] = content.trimEnd().split(/\r?\n/);
            const headers = header.split("\t");
            const required = [...SIGNATURE_FIELDS, "round", "latencyNanos", "hitGraphIds", "executionPath", "inputSourceCount"];
            if (new Set(headers).size !== headers.length || required.some(field => !headers.includes(field))) {
                integrityErrors.push(`${revision}-${fork + 1}: incomplete or duplicate sample columns`);
            }
            const samples = new Map([...expected.keys()].map(id => [id, new Map()]));
            for (const line of lines) {
                const values = line.split("\t");
                const row = Object.fromEntries(headers.map((field, i) => [field, values[i]]));
                const label = `${revision}-${fork + 1}/${row.id}/round-${row.round}`;
                const round = Number(row.round);
                const latency = Number(row.latencyNanos);
                const querySamples = samples.get(row.id);
                if (values.length !== headers.length || !querySamples || !Number.isInteger(round) ||
                    round < 1 || round > WIDE_SAMPLE_COUNT || querySamples.has(round)) {
                    integrityErrors.push(`${label}: malformed, unexpected or duplicate sample`);
                    continue;
                }
                if (!Number.isSafeInteger(latency) || latency <= 0) integrityErrors.push(`${label}: latency must be positive integer nanoseconds`);
                querySamples.set(round, latency);
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
            for (const [id, rows] of samples) if (rows.size !== WIDE_SAMPLE_COUNT) {
                integrityErrors.push(`${revision}-${fork + 1}/${id}: expected 40 samples, found ${rows.size}`);
            }
            return samples;
        });
    };
    const baseForks = parseForks(baseContents, "base");
    const candidateForks = parseForks(candidateContents, "candidate");
    const queries = catalog.map(entry => {
        const errors = integrityErrors.filter(error => error.includes(`/${entry.id}:`) || error.includes(`/${entry.id}/`));
        const runs = [];
        for (let fork = 0; fork < WIDE_FORK_COUNT; fork++) {
            const base = [...(baseForks[fork]?.get(entry.id)?.values() ?? [])];
            const candidate = [...(candidateForks[fork]?.get(entry.id)?.values() ?? [])];
            if (base.length !== WIDE_SAMPLE_COUNT || candidate.length !== WIDE_SAMPLE_COUNT ||
                [...base, ...candidate].some(value => !Number.isSafeInteger(value) || value <= 0)) continue;
            const run = { fork: fork + 1, baseP50Nanos: percentile(base, .5), baseP95Nanos: percentile(base, .95),
                candidateP50Nanos: percentile(candidate, .5), candidateP95Nanos: percentile(candidate, .95) };
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
            if (values.length !== WIDE_FORK_COUNT) continue;
            const min = Math.min(...values), max = Math.max(...values);
            stability[`${revision}${quantile}SpreadPercent`] = (max - min) / min * 100;
            if (BigInt(max) * 100n >= BigInt(min) * 105n) {
                errors.push(`${entry.id}: ${revision} ${quantile} cross-fork spread (max-min)/min must be <5%`);
            }
        }
        const localIntegrity = new Set(integrityErrors);
        latencyErrors.push(...errors.filter(error => !localIntegrity.has(error)));
        return { id: entry.id, shape: entry.shape, passed: errors.length === 0 && runs.length === WIDE_FORK_COUNT,
            errors, runs, stability };
    });
    const sharedIntegrityErrors = integrityErrors.filter(error => !catalog.some(query =>
        error.includes(`/${query.id}:`) || error.includes(`/${query.id}/`)));
    return { passed: integrityErrors.length === 0 && latencyErrors.length === 0,
        sharedIntegrityErrors,
        queryCount: catalog.length, samplesPerQuery: WIDE_SAMPLE_COUNT, forkCount: WIDE_FORK_COUNT,
        integrityErrors, latencyErrors, queries };
}

export function renderWideLatency(comparison) {
    return ["### Per-query wide latency gates", "",
        "72 queries; 40 measured samples per query in each of three independent paired forks; maximum heap 8 GiB.",
        "Each P50 and P95 must regress by less than 5% in every pair. Both base and candidate cross-fork spread",
        "is (max-min)/min and must be less than 5%. Exact result signatures and provenance must match in every sample.",
        "CPU, peak heap and RSS are diagnostic. The legacy mixed-query percentile is not an acceptance criterion.", "",
        "| Query | Result | Paired P50 base → candidate (ms) | Paired P95 base → candidate (ms) | Failures |",
        "|---|---|---|---|---|",
        ...comparison.queries.map(query => `| ${query.id} | ${query.passed ? "PASS" : "FAIL"} | ` +
            ["P50", "P95"].map(q => query.runs.map(run =>
                `${(run[`base${q}Nanos`] / 1e6).toFixed(3)} → ${(run[`candidate${q}Nanos`] / 1e6).toFixed(3)}`)
                .join(" / ")).join(" | ") + ` | ${query.errors.join("; ")} |`), ""].join("\n");
}

#!/usr/bin/env node
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';

export function queryMatrix(catalog) {
    const queries = catalog.queries;
    if (!Array.isArray(queries) || queries.length !== 72 ||
        queries.some(query => typeof query.id !== 'string' || !/^[a-zA-Z0-9_-]+$/.test(query.id)) ||
        new Set(queries.map(query => query.id)).size !== queries.length) {
        throw new Error('Expected exactly 72 unique query catalog IDs');
    }
    return queries.map(({ id, shape }) => ({ id, shape: shape ?? id }));
}

export function checkQuery(status, catalog, id, { baseSha, headSha } = {}) {
    const matrix = queryMatrix(catalog);
    if (!matrix.some(query => query.id === id)) throw new Error(`Unknown query ${id}`);
    if (typeof status.currentPrBase !== 'string' ||
        (baseSha && status.currentPrBase !== baseSha) ||
        (headSha && status.currentHead !== headSha)) throw new Error('Benchmark revision mismatch');
    const comparison = status.comparisons?.[status.currentPrBase];
    if (!comparison || comparison.error !== null || ![0, 1].includes(comparison.exitCode)) {
        throw new Error('Missing or failed current-main benchmark execution');
    }
    const evidence = comparison.status;
    const repeated = evidence?.repeatedLatency;
    if (![evidence?.integrityErrors, repeated?.integrityErrors, repeated?.sharedIntegrityErrors]
        .every(errors => Array.isArray(errors) && errors.every(error => typeof error === 'string')) ||
        repeated.sharedIntegrityErrors.length ||
        evidence.integrityErrors.some(error => !repeated.integrityErrors.includes(error))) {
        throw new Error('Shared benchmark measurement integrity failed');
    }
    // A query-specific correctness error must not turn unrelated query checks red.
    // Recompute the partition so an omitted shared error cannot produce a green check.
    const belongsToQuery = (error, queryId) => error.includes(`/${queryId}:`) || error.includes(`/${queryId}/`);
    if (repeated.integrityErrors.some(error => !matrix.some(query => belongsToQuery(error, query.id)))) {
        throw new Error('Shared benchmark measurement integrity failed');
    }
    if (repeated.queryCount !== 72 || repeated.samplesPerQuery !== 40 || repeated.forkCount !== 3 ||
        !Array.isArray(repeated.queries)) throw new Error('Incomplete repeated query measurements');
    const ids = repeated.queries.map(query => query.id).sort();
    if (JSON.stringify(ids) !== JSON.stringify(matrix.map(query => query.id).sort())) {
        throw new Error('Missing, duplicate, or unexpected query verdicts');
    }
    const query = repeated.queries.find(query => query.id === id);
    if (typeof query.passed !== 'boolean' || !Array.isArray(query.errors) ||
        repeated.integrityErrors.some(error => belongsToQuery(error, id) && !query.errors.includes(error)) ||
        query.errors.some(error => typeof error !== 'string') || query.passed !== (query.errors.length === 0)) {
        throw new Error(`${id}: invalid query verdict`);
    }
    if (!Array.isArray(query.runs) || query.runs.length !== 3 || query.runs.some((run, index) =>
        run.fork !== index + 1 || ['baseP50Nanos', 'baseP95Nanos', 'candidateP50Nanos', 'candidateP95Nanos']
            .some(key => !Number.isSafeInteger(run[key]) || run[key] <= 0))) {
        throw new Error(`${id}: missing finite P50/P95 measurements for three paired forks`);
    }
    if (query.runs.some(run => run.baseP50Nanos > run.baseP95Nanos ||
        run.candidateP50Nanos > run.candidateP95Nanos)) {
        throw new Error(`${id}: P50 must not exceed P95`);
    }
    let numericalFailure = false;
    for (const run of query.runs) for (const quantile of ['P50', 'P95']) {
        if (BigInt(run[`candidate${quantile}Nanos`]) * 100n >= BigInt(run[`base${quantile}Nanos`]) * 105n) {
            numericalFailure = true;
        }
    }
    for (const revision of ['base', 'candidate']) for (const quantile of ['P50', 'P95']) {
        const values = query.runs.map(run => run[`${revision}${quantile}Nanos`]);
        const min = Math.min(...values), max = Math.max(...values);
        const spread = query.stability?.[`${revision}${quantile}SpreadPercent`];
        if (!Number.isFinite(spread) || spread !== (max - min) / min * 100) {
            throw new Error(`${id}: inconsistent ${revision} ${quantile} fluctuation evidence`);
        }
        if (BigInt(max) * 100n >= BigInt(min) * 105n) numericalFailure = true;
    }
    if (query.passed && numericalFailure) throw new Error(`${id}: green verdict contradicts P50/P95 measurements`);
    return query;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    try {
        const [command, catalogFile, statusFile, id] = process.argv.slice(2);
        const catalog = JSON.parse(fs.readFileSync(catalogFile, 'utf8'));
        if (command === 'matrix') console.log(JSON.stringify(queryMatrix(catalog)));
        else if (command === 'check') {
            const query = checkQuery(JSON.parse(fs.readFileSync(statusFile, 'utf8')), catalog, id,
                { baseSha: process.env.BASE_SHA, headSha: process.env.HEAD_SHA });
            console.log(`${id}: independent P50/P95 latency and <5% fluctuation gate`);
            console.log(JSON.stringify(query, null, 2));
            if (!query.passed) process.exitCode = 1;
        } else throw new Error('Expected matrix or check command');
    } catch (error) {
        console.error(error.message);
        process.exitCode = 1;
    }
}

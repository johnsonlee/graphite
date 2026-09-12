import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { checkQuery, queryMatrix } from './benchmark-query-gate.mjs';

const catalog = JSON.parse(fs.readFileSync(new URL('./wide-query-catalog.json', import.meta.url)));
const selectedId = catalog.queries[0].id;
function statusFixture() {
    return {
        passed: true, currentPrBase: 'main-sha', currentHead: 'head-sha',
        comparisons: { 'main-sha': { exitCode: 0, error: null, status: {
            integrityErrors: [], repeatedLatency: {
                queryCount: 72, samplesPerQuery: 40, forkCount: 3, integrityErrors: [], sharedIntegrityErrors: [],
                queries: catalog.queries.map(({ id }) => ({ id, passed: true, errors: [],
                    runs: [1, 2, 3].map(fork => ({ fork, baseP50Nanos: 100, baseP95Nanos: 200,
                        candidateP50Nanos: 100, candidateP95Nanos: 200 })),
                    stability: { baseP50SpreadPercent: 0, baseP95SpreadPercent: 0,
                        candidateP50SpreadPercent: 0, candidateP95SpreadPercent: 0 }
                }))
            }
        } } }
    };
}
const queries = status => status.comparisons['main-sha'].status.repeatedLatency.queries;

test('each catalog query gets an independent named gate from the shared measurements', () => {
    assert.equal(queryMatrix(catalog).length, 72);
    const status = statusFixture();
    status.passed = false;
    queries(status)[1].passed = false;
    queries(status)[1].errors = ['P95 regression'];
    status.comparisons['main-sha'].exitCode = 1;
    assert.equal(checkQuery(status, catalog, selectedId).passed, true);
    assert.equal(checkQuery(status, catalog, queries(status)[1].id).passed, false);
});

test('missing or duplicate query evidence cannot produce a green query check', () => {
    for (const change of [
        rows => rows.pop(),
        rows => { rows[1] = rows[0]; }
    ]) {
        const status = statusFixture();
        change(queries(status));
        assert.throws(() => checkQuery(status, catalog, selectedId), /Missing, duplicate/);
    }
    assert.throws(() => queryMatrix({ queries: catalog.queries.slice(1) }), /72 unique/);
});

test('measurement failure, revision mismatch, missing status, or contradictory query verdict fails closed', () => {
    for (const change of [
        status => { status.comparisons['main-sha'].error = 'driver failure'; },
        status => { status.comparisons['main-sha'].status.integrityErrors.push('wrong heap cap'); },
        status => { status.comparisons['main-sha'].status.repeatedLatency.forkCount = 2; },
        status => { queries(status)[0].errors.push('P50 regression'); },
        status => { delete status.comparisons; }
    ]) {
        const status = statusFixture();
        change(status);
        assert.throws(() => checkQuery(status, catalog, selectedId));
    }
    assert.throws(() => checkQuery(statusFixture(), catalog, selectedId, { headSha: 'wrong' }), /revision mismatch/);
});

test('workflow matrix reuses measurements and is mandatory even when another query fails', () => {
    const workflow = fs.readFileSync(new URL('../workflows/benchmark.yml', import.meta.url), 'utf8');
    const matrix = workflow.slice(workflow.indexOf('  wide-query-latency-gate:'),
        workflow.indexOf('  benchmark-regression-gate:'));
    assert.match(matrix, /name: wide-query-latency-\$\{\{ matrix.query.id \}\}/);
    assert.match(matrix, /always\(\)/);
    assert.match(matrix, /fail-fast: false/);
    assert.match(matrix, /fromJSON\(needs.candidate-gate-tests.outputs.queries\)/);
    assert.match(matrix, /benchmark-query-gate.mjs check/);
    assert.match(matrix, /benchmark-global-wide-\$\{\{ github.event.pull_request.number \}\}-\$\{\{ github.run_attempt \}\}/);
    assert.doesNotMatch(matrix, /java -jar|gradlew|run-real64-global-wide/);
    const aggregate = workflow.slice(workflow.indexOf('  benchmark-regression-gate:'));
    assert.match(aggregate, /needs: \[[^\n]*wide-query-latency-gate/);
    assert.match(aggregate, /QUERY_LATENCY_JOB: \$\{\{ needs.wide-query-latency-gate.result \}\}/);
    assert.match(aggregate, /\[ "\$\{QUERY_LATENCY_JOB\}" != success \]/);
});


test('query check rejects missing measurements and a fabricated green numerical verdict', () => {
    for (const change of [
        query => { query.runs.pop(); },
        query => { query.runs[0].candidateP95Nanos = 0; },
        query => { query.runs.forEach(run => { run.candidateP95Nanos = 210; }); },
        query => { query.stability.candidateP50SpreadPercent = 4; },
        query => { query.runs[0].candidateP50Nanos = 95; query.stability.candidateP50SpreadPercent = 5 / 95 * 100; }
    ]) {
        const status = statusFixture();
        change(queries(status)[0]);
        assert.throws(() => checkQuery(status, catalog, selectedId));
    }
});


test('query-specific correctness failures only fail their own check', () => {
    const status = statusFixture();
    const failed = queries(status)[1];
    const error = `candidate-1/${failed.id}/1: result signature differs from correctness oracle`;
    failed.passed = false;
    failed.errors = [error];
    const evidence = status.comparisons['main-sha'].status;
    evidence.integrityErrors = [error];
    evidence.repeatedLatency.integrityErrors = [error];
    status.comparisons['main-sha'].exitCode = 1;
    assert.equal(checkQuery(status, catalog, selectedId).passed, true);
    assert.equal(checkQuery(status, catalog, failed.id).passed, false);
    failed.passed = true;
    failed.errors = [];
    assert.throws(() => checkQuery(status, catalog, failed.id), /invalid query verdict/);
    const shared = 'candidate-1: incomplete sample columns';
    evidence.integrityErrors.push(shared);
    evidence.repeatedLatency.integrityErrors.push(shared);
    // Even a mistakenly empty shared partition cannot hide a genuinely shared failure.
    assert.throws(() => checkQuery(status, catalog, selectedId), /Shared benchmark/);
    evidence.repeatedLatency.sharedIntegrityErrors.push(shared);
    assert.throws(() => checkQuery(status, catalog, selectedId), /Shared benchmark/);
});

test('both revisions must have P50 at or below P95', () => {
    for (const revision of ['base', 'candidate']) {
        const status = statusFixture();
        queries(status)[0].runs.forEach(run => { run[`${revision}P50Nanos`] = 201; });
        assert.throws(() => checkQuery(status, catalog, selectedId), /P50 must not exceed P95/);
    }
});

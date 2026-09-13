import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { sealBuild, verifyBuild, sealShard, verifyShard, aggregateShards, readLines, checkProgress } from './benchmark-wide-shards.mjs';
import { selectWideCatalog } from './benchmark-wide-latency.mjs';

const catalogFile = new URL('./wide-query-catalog.json', import.meta.url);
const catalog = JSON.parse(fs.readFileSync(catalogFile, 'utf8')).queries;
const base = 'a'.repeat(40), candidate = 'b'.repeat(40);
const fields = ['id', 'family', 'shape', 'selectivity', 'operator', 'boundary', 'projection', 'targetGraphId',
    'workloadIdentity', 'limit', 'outcome', 'rowCount', 'responseBytes', 'digest'];
const headers = ['phase', 'round', 'phaseElapsedNanos', ...fields, 'latencyNanos', 'hitGraphIds', 'executionPath', 'inputSourceCount'];
const graphIds = [...new Set(catalog.flatMap(q => q.expectedMatchingGraphIds ?? []))];
while (graphIds.length < 64) graphIds.push(`test-graph-${graphIds.length}`);
const row = q => ({ ...q, targetGraphId: '', workloadIdentity: q.workloadIdentity ?? 'a'.repeat(64),
    outcome: 'success', rowCount: q.selectivity === 'zero' ? '0' : '1', responseBytes: '10', digest: 'b'.repeat(64),
    hitGraphIds: q.selectivity === 'zero' ? '' : (q.expectedMatchingGraphIds?.[0] ?? graphIds[0]),
    executionPath: 'cross-graph-query', inputSourceCount: '64' });
const write = (file, value) => fs.writeFileSync(file, `${JSON.stringify(value)}\n`);
const json = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const hash = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
function setup(t) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'wide-shards-test-'));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const bundle = path.join(root, 'bundle'); fs.mkdirSync(bundle);
    // Dummy bytes exercise artifact binding, never claim a real benchmark execution.
    for (const file of ['base.jar', 'candidate.jar', 'harness.kt', 'correctness.kt', 'fixture-provenance.tsv']) fs.writeFileSync(path.join(bundle, file), file);
    fs.copyFileSync(catalogFile, path.join(bundle, 'catalog.json'));
    fs.writeFileSync(path.join(bundle, 'oracle.correctness'), catalog.map(q => fields.map(f => row(q)[f]).join('|')).join('\n'));
    fs.writeFileSync(path.join(bundle, 'graphs.tsv'), graphIds.map(id => `${id}\tfixture`).join('\n'));
    write(path.join(bundle, 'fixture-reproducibility.json'), { passed: true });
    write(path.join(bundle, 'fixture64.complete.json'), { schema: 'graphite-shared-fixture64-v1', complete: true, candidateSha: candidate,
        manifestSha256: hash(path.join(bundle, 'graphs.tsv')), provenanceSha256: hash(path.join(bundle, 'fixture-provenance.tsv')),
        receiptSha256: hash(path.join(bundle, 'fixture-reproducibility.json')) });
    sealBuild(bundle, base, candidate);
    const legacy = path.join(root, 'legacy'); fs.mkdirSync(legacy);
    write(path.join(legacy, 'global-wide-status.json'), { evidenceMode: 'legacy-diagnostics-only', currentPrBase: base,
        currentHead: candidate, passed: true, errors: [], comparisons: { historical: { diagnostic: true } } });
    fs.writeFileSync(path.join(legacy, 'global-wide-report.md'), 'Legacy diagnostic report\n');
    return { root, bundle, legacy, base, candidate, output: path.join(root, 'output') };
}
function makeShard(context, shard, transform = r => r) {
    const directory = path.join(context.root, shard); fs.mkdirSync(directory);
    fs.copyFileSync(path.join(context.bundle, 'build.json'), path.join(directory, 'build.json'));
    write(path.join(directory, 'runner.json'), { runId: '123', runAttempt: '1', job: `measure-${shard}`,
        runnerName: 'runner', hostname: `host-${shard}`, shard, baseSha: base, candidateSha: candidate,
        order: ['candidate-base', 'base-candidate', 'candidate-base'] });
    const queries = selectWideCatalog(catalog, { shard });
    for (const revision of ['base', 'candidate']) for (let fork = 1; fork <= 3; fork++) {
        const rows = [headers.join('\t')];
        for (const q of queries) for (const phase of ['warmup', 'measurement']) {
            const count = phase === 'warmup' ? 5 : 40;
            for (let round = 1; round <= count; round++) {
                const sample = transform({ ...row(q), phase, round,
                    phaseElapsedNanos: 10_000_000_000 / count * round, latencyNanos: 1_000_000 }, revision, fork);
                rows.push(headers.map(h => sample[h]).join('\t'));
            }
        }
        fs.writeFileSync(path.join(directory, `${revision}-${fork}.tsv`), `${rows.join('\n')}\n`);
    }
    return directory;
}
function complete(t) {
    const context = setup(t);
    context.standard = makeShard(context, 'standard'); context.fullScan = makeShard(context, 'full-scan');
    sealShard(context.standard, context.bundle, 'standard'); sealShard(context.fullScan, context.bundle, 'full-scan');
    return context;
}
test('sealed build rejects tampered bytes, wrong revision and detached catalog', t => {
    const c = setup(t); assert.equal(verifyBuild(c.bundle, base, candidate).baseSha, base);
    assert.throws(() => verifyBuild(c.bundle, candidate, base), /revision/);
    fs.appendFileSync(path.join(c.bundle, 'candidate.jar'), 'tamper');
    assert.throws(() => verifyBuild(c.bundle, base, candidate), /Hash mismatch/);
    assert.throws(() => sealBuild(c.bundle, base, candidate, path.join(c.bundle, 'base.jar')), /Catalog/);
});
test('fixture marker binds candidate and receipt instead of accepting a successful unbound marker', t => {
    const c = setup(t), file = path.join(c.bundle, 'fixture64.complete.json');
    write(file, { ...json(file), candidateSha: base });
    assert.throws(() => sealBuild(c.bundle, base, candidate), /Fixture marker/);
});
test('complete disjoint shards aggregate all 72 queries and preserve historical diagnostics', t => {
    const c = complete(t); const result = aggregateShards(c);
    assert.equal(result.passed, true, result.errors.join('\n'));
    assert.equal(result.evidenceMode, 'separate-query-latency');
    assert.equal(result.repeatedLatency.queries.length, 72);
    assert.deepEqual(result.comparisons, { historical: { diagnostic: true } });
    assert.equal([...readLines(path.join(c.output, 'timed-query-latency', 'base-1.tsv'))].length, 72 * 45 + 1);
});
test('missing shard and altered measurement cannot become green', t => {
    const c = complete(t);
    fs.appendFileSync(path.join(c.standard, 'base-1.tsv'), 'tamper');
    assert.throws(() => aggregateShards(c), /Hash mismatch/);
    fs.rmSync(path.join(c.fullScan, 'receipt.json'));
    assert.throws(() => verifyShard(c.fullScan, c.bundle, 'full-scan', json(path.join(c.bundle, 'build.json'))), /ENOENT/);
});
test('wrong shard membership, paired order and workflow identity fail closed', t => {
    const c = complete(t), receiptFile = path.join(c.fullScan, 'receipt.json');
    const original = json(receiptFile);
    write(receiptFile, { ...original, queryIds: [catalog[0].id] });
    assert.throws(() => aggregateShards(c), /membership/);
    write(receiptFile, original);
    const runnerFile = path.join(c.fullScan, 'runner.json'), runner = json(runnerFile);
    write(runnerFile, { ...runner, order: ['base-candidate', 'base-candidate', 'base-candidate'] });
    assert.throws(() => sealShard(c.fullScan, c.bundle, 'full-scan'), /run order/);
    write(runnerFile, { ...runner, runId: 'different-run' }); sealShard(c.fullScan, c.bundle, 'full-scan');
    assert.throws(() => aggregateShards(c), /different workflow/);
});
test('insufficient warmup duration produces failed integrity receipt', t => {
    const c = setup(t), directory = makeShard(c, 'full-scan', r => ({ ...r, phaseElapsedNanos: r.phaseElapsedNanos / 2 }));
    const result = sealShard(directory, c.bundle, 'full-scan');
    assert.equal(result.integrityPassed, false);
    assert.ok(result.integrityErrors.length > 0);
    assert.equal(json(path.join(directory, 'receipt.json')).integrityPassed, false);
});
test('numeric regression is sealed for reporting but blocks combined verdict', t => {
    const c = setup(t); c.standard = makeShard(c, 'standard');
    c.fullScan = makeShard(c, 'full-scan', (r, revision) => ({ ...r, latencyNanos: revision === 'candidate' ? 1_100_000 : 1_000_000 }));
    sealShard(c.standard, c.bundle, 'standard');
    const receipt = sealShard(c.fullScan, c.bundle, 'full-scan');
    assert.equal(receipt.integrityPassed, true); assert.equal(receipt.passed, false);
    const combined = aggregateShards(c);
    assert.equal(combined.passed, false);
    assert.equal(combined.regressionPassed, false);
    assert.equal(combined.iterationPassed, false);
    assert.equal(combined.targetAchieved, false);
    assert.equal(combined.legacyDiagnostics.passed, true);
    assert.equal(combined.blockingLatencyRef, base);
});
test('legacy integrity failures remain blocking and legacy revision mismatches are rejected', t => {
    const c = complete(t), file = path.join(c.legacy, 'global-wide-status.json'), original = json(file);
    write(file, { ...original, passed: false, errors: ['historical result digest mismatch'] });
    const result = aggregateShards(c); assert.equal(result.passed, false); assert.ok(result.errors.includes('historical result digest mismatch'));
    write(file, { ...original, currentPrBase: candidate });
    assert.throws(() => aggregateShards(c), /Legacy evidence/);
});
test('streamed lines preserve multibyte boundaries, CRLF and final non-newline data', t => {
    const c = setup(t), file = path.join(c.root, 'lines');
    const long = `${'a'.repeat(262143)}中文`;
    fs.writeFileSync(file, `${long}\r\nsecond\nfinal`);
    assert.deepEqual([...readLines(file)], [long, 'second', 'final']);
});

test('failed first pair leaves explicit progress evidence without producing final receipt', t => {
    const c = setup(t), directory = makeShard(c, 'full-scan', (r, revision) => ({ ...r,
        latencyNanos: revision === 'candidate' ? 1_050_000 : 1_000_000 }));
    const result = checkProgress(directory, c.bundle, 'full-scan', 1);
    assert.equal(result.canContinue, false); assert.equal(result.passed, false);
    assert.ok(json(path.join(directory, 'progress-1.json')).latencyErrors.length > 0);
    assert.equal(fs.existsSync(path.join(directory, 'receipt.json')), false);
});
test('shard shell actually stops after first irreversible pair failure and retains checkpoint', t => {
    const c = setup(t), data = makeShard(c, 'full-scan', (r, revision) => ({ ...r,
        latencyNanos: revision === 'candidate' ? 1_050_000 : 1_000_000 }));
    const scripts = path.join(c.root, 'scripts'); fs.mkdirSync(scripts);
    for (const name of ['run-wide-latency-shard.sh', 'benchmark-wide-shards.mjs', 'benchmark-wide-latency.mjs', 'wide-query-catalog.json']) {
        fs.copyFileSync(new URL(`./${name}`, import.meta.url), path.join(scripts, name));
    }
    fs.writeFileSync(path.join(scripts, 'verify-shared-fixture64.sh'), '#!/usr/bin/env bash\nexit 0\n', { mode: 0o755 });
    const bin = path.join(c.root, 'bin'); fs.mkdirSync(bin);
    fs.writeFileSync(path.join(bin, 'java'), `#!/usr/bin/env bash
set -euo pipefail
while [[ $# -gt 0 ]]; do
  if [[ "$1" == -rff ]]; then shift; PREFIX=\${1%.json}; fi
  shift
done
printf '%s\\n' "$PREFIX" >> "$CALL_LOG"
cp "$SAMPLE_DATA/$(basename "$PREFIX").tsv" "$PREFIX.tsv"
printf '{}' > "$PREFIX.json"
`, { mode: 0o755 });
    const shared = path.join(c.root, 'shared'); fs.mkdirSync(path.join(shared, 'graphs'), { recursive: true });
    for (const file of ['graphs.tsv', 'fixture-provenance.tsv']) fs.copyFileSync(path.join(c.bundle, file), path.join(shared, 'graphs', file));
    for (const file of ['fixture64.complete.json', 'fixture-reproducibility.json']) fs.copyFileSync(path.join(c.bundle, file), path.join(shared, file));
    const output = path.join(c.root, 'execution'), callLog = path.join(c.root, 'calls');
    const run = spawnSync('bash', [path.join(scripts, 'run-wide-latency-shard.sh'), c.bundle, shared, 'full-scan', base, candidate, output], {
        encoding: 'utf8', env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, SAMPLE_DATA: data, CALL_LOG: callLog,
            GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '1', GITHUB_JOB: 'full-scan', RUNNER_NAME: 'test' }
    });
    assert.equal(run.status, 1, run.stderr);
    assert.equal(fs.readFileSync(callLog, 'utf8').trim().split('\n').length, 2);
    assert.equal(json(path.join(output, 'progress-1.json')).canContinue, false);
    assert.equal(fs.existsSync(path.join(output, 'base-2.tsv')), false);
    assert.equal(fs.existsSync(path.join(output, 'receipt.json')), false);
});

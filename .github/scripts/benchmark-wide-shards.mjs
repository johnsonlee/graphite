#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { StringDecoder } from 'node:string_decoder';
import { pathToFileURL } from 'node:url';
import { WIDE_ACCEPTANCE, WIDE_PROTOCOL, compareWideLatency, renderWideLatency, selectWideCatalog } from './benchmark-wide-latency.mjs';

const BUILD_FILES = ['base.jar', 'candidate.jar', 'oracle.correctness', 'catalog.json', 'harness.kt',
    'correctness.kt', 'graphs.tsv', 'fixture-provenance.tsv', 'fixture-reproducibility.json', 'fixture64.complete.json'];
const SAMPLE_FILES = [1, 2, 3].flatMap(i => [`base-${i}.tsv`, `candidate-${i}.tsv`]);
const ORDER = ['candidate-base', 'base-candidate', 'candidate-base'];
const reviewedCatalog = new URL('./wide-query-catalog.json', import.meta.url);
const read = file => fs.readFileSync(file, 'utf8');
const json = file => JSON.parse(read(file));
const write = (file, value) => fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
function requireValue(condition, message) { if (!condition) throw new Error(message); }
function sha(file) {
    requireValue(fs.lstatSync(file).isFile() && !fs.lstatSync(file).isSymbolicLink(), `Not a regular file: ${file}`);
    const digest = crypto.createHash('sha256');
    const fd = fs.openSync(file, 'r'); const buffer = Buffer.alloc(1024 * 1024);
    try { let count; while ((count = fs.readSync(fd, buffer, 0, buffer.length, null)) > 0) digest.update(buffer.subarray(0, count)); }
    finally { fs.closeSync(fd); }
    return digest.digest('hex');
}
function revisions(base, candidate) {
    requireValue([base, candidate].every(value => typeof value === 'string' && /^[a-f0-9]{40}$/.test(value)), 'Invalid revision SHA');
}
function hashes(directory, files) { return Object.fromEntries(files.map(file => [file, sha(path.join(directory, file))])); }
function verifyHashes(directory, files, expected) {
    requireValue(expected && JSON.stringify(Object.keys(expected).sort()) === JSON.stringify([...files].sort()), 'Unexpected or missing hash entries');
    for (const file of files) requireValue(sha(path.join(directory, file)) === expected[file], `Hash mismatch: ${file}`);
}
function verifyInputs(directory, candidate, catalogFile = reviewedCatalog) {
    requireValue(read(path.join(directory, 'catalog.json')) === read(catalogFile), 'Catalog differs from reviewed source');
    const marker = json(path.join(directory, 'fixture64.complete.json'));
    requireValue(marker.schema === 'graphite-shared-fixture64-v1' && marker.complete === true && marker.candidateSha === candidate,
        'Fixture marker revision or completion mismatch');
    for (const [key, file] of [['manifestSha256', 'graphs.tsv'], ['provenanceSha256', 'fixture-provenance.tsv'],
        ['receiptSha256', 'fixture-reproducibility.json']]) requireValue(marker[key] === sha(path.join(directory, file)), `Fixture marker ${key} mismatch`);
    requireValue(json(path.join(directory, 'fixture-reproducibility.json')).passed === true, 'Fixture reproducibility failed');
}
export function sealBuild(directory, base, candidate, catalogFile = reviewedCatalog) {
    revisions(base, candidate);
    verifyInputs(directory, candidate, catalogFile);
    const receipt = { schema: 'graphite-wide-build-v1', baseSha: base, candidateSha: candidate,
        protocol: { ...WIDE_PROTOCOL }, files: hashes(directory, BUILD_FILES) };
    write(path.join(directory, 'build.json'), receipt);
    return receipt;
}
export function verifyBuild(directory, base, candidate, catalogFile = reviewedCatalog) {
    revisions(base, candidate);
    const receipt = json(path.join(directory, 'build.json'));
    requireValue(receipt.schema === 'graphite-wide-build-v1' && receipt.baseSha === base && receipt.candidateSha === candidate,
        'Build revision mismatch');
    requireValue(JSON.stringify(receipt.protocol) === JSON.stringify(WIDE_PROTOCOL), 'Build protocol mismatch');
    verifyHashes(directory, BUILD_FILES, receipt.files);
    verifyInputs(directory, candidate, catalogFile);
    return receipt;
}
function verifyRunner(runner, shard, build) {
    requireValue(runner && runner.shard === shard && runner.baseSha === build.baseSha && runner.candidateSha === build.candidateSha,
        'Runner shard or revision mismatch');
    requireValue(['runId', 'runAttempt', 'job', 'runnerName', 'hostname'].every(key =>
        ['string', 'number'].includes(typeof runner[key]) && String(runner[key]).length > 0), 'Missing runner identity');
    requireValue(JSON.stringify(runner.order) === JSON.stringify(ORDER), 'Invalid paired run order');
}
export function* readLines(file) {
    const fd = fs.openSync(file, 'r'); const buffer = Buffer.alloc(256 * 1024);
    const decoder = new StringDecoder('utf8'); let pending = '';
    try {
        let count;
        while ((count = fs.readSync(fd, buffer, 0, buffer.length, null)) > 0) {
            pending += decoder.write(buffer.subarray(0, count));
            let start = 0, end;
            while ((end = pending.indexOf('\n', start)) >= 0) {
                yield pending.slice(start, end).replace(/\r$/, ''); start = end + 1;
            }
            pending = pending.slice(start);
        }
        pending += decoder.end(); if (pending) yield pending.replace(/\r$/, '');
    } finally { fs.closeSync(fd); }
}
function compareDirectory(directory, bundle, shard, completedPairs) {
    const catalog = json(path.join(bundle, 'catalog.json')).queries;
    const ids = new Set(selectWideCatalog(catalog, { shard }).map(query => query.id));
    const oracle = read(path.join(bundle, 'oracle.correctness')).split(/\r?\n/)
        .filter(line => !line || line.startsWith('#') || ids.has(line.split('|')[0])).join('\n');
    const forks = completedPairs === undefined ? [1, 2, 3] : Array.from({ length: completedPairs }, (_, i) => i + 1);
    return compareWideLatency(forks.map(i => readLines(path.join(directory, `base-${i}.tsv`))),
        forks.map(i => readLines(path.join(directory, `candidate-${i}.tsv`))),
        oracle, read(path.join(bundle, 'graphs.tsv')), catalog, { shard, ...(completedPairs === undefined ? {} : { partial: true }) });
}
export function checkProgress(directory, bundle, shard, completedPairs) {
    requireValue(Number.isInteger(completedPairs) && completedPairs >= 1 && completedPairs <= 3, 'Expected one to three completed pairs');
    const build = json(path.join(bundle, 'build.json'));
    verifyBuild(bundle, build.baseSha, build.candidateSha);
    requireValue(sha(path.join(directory, 'build.json')) === sha(path.join(bundle, 'build.json')), 'Progress build mismatch');
    const runner = json(path.join(directory, 'runner.json'));
    verifyRunner(runner, shard, build);
    const comparison = compareDirectory(directory, bundle, shard, completedPairs);
    const result = { schema: 'graphite-wide-progress-v1', acceptance: WIDE_ACCEPTANCE, partial: true, passed: false,
        shard, completedPairs, runner, canContinue: comparison.canContinue,
        integrityErrors: comparison.integrityErrors, latencyErrors: comparison.latencyErrors,
        queries: comparison.queries };
    write(path.join(directory, `progress-${completedPairs}.json`), result);
    fs.writeFileSync(path.join(directory, `progress-${completedPairs}.md`), renderWideLatency(comparison));
    return result;
}
export function sealShard(directory, bundle, shard) {
    const build = json(path.join(bundle, 'build.json'));
    verifyBuild(bundle, build.baseSha, build.candidateSha);
    requireValue(['standard', 'full-scan'].includes(shard), 'Unknown shard');
    requireValue(sha(path.join(directory, 'build.json')) === sha(path.join(bundle, 'build.json')), 'Shard build mismatch');
    const runner = json(path.join(directory, 'runner.json'));
    verifyRunner(runner, shard, build);
    const comparison = compareDirectory(directory, bundle, shard);
    const receipt = { schema: 'graphite-wide-shard-v1', acceptance: WIDE_ACCEPTANCE, shard, runner,
        queryIds: selectWideCatalog(json(path.join(bundle, 'catalog.json')).queries, { shard }).map(q => q.id),
        integrityPassed: comparison.integrityErrors.length === 0, passed: comparison.passed,
        integrityErrors: comparison.integrityErrors, latencyErrors: comparison.latencyErrors,
        files: hashes(directory, [...SAMPLE_FILES, 'build.json', 'runner.json']) };
    write(path.join(directory, 'receipt.json'), receipt);
    return receipt;
}
export function verifyShard(directory, bundle, shard, build) {
    const receipt = json(path.join(directory, 'receipt.json'));
    requireValue(receipt.schema === 'graphite-wide-shard-v1' && receipt.shard === shard, 'Shard receipt identity mismatch');
    requireValue(receipt.acceptance === WIDE_ACCEPTANCE, 'Shard acceptance policy mismatch');
    verifyHashes(directory, [...SAMPLE_FILES, 'build.json', 'runner.json'], receipt.files);
    requireValue(sha(path.join(directory, 'build.json')) === sha(path.join(bundle, 'build.json')), 'Shard build mismatch');
    const runner = json(path.join(directory, 'runner.json'));
    verifyRunner(runner, shard, build);
    requireValue(JSON.stringify(runner) === JSON.stringify(receipt.runner), 'Shard runner receipt mismatch');
    const expected = selectWideCatalog(json(path.join(bundle, 'catalog.json')).queries, { shard }).map(q => q.id);
    requireValue(JSON.stringify(receipt.queryIds) === JSON.stringify(expected), 'Shard query membership mismatch');
    requireValue(receipt.integrityPassed === true && Array.isArray(receipt.integrityErrors) && receipt.integrityErrors.length === 0,
        'Shard measurement integrity failed');
    return receipt;
}
export function aggregateShards({ legacy, bundle, standard, fullScan, output, base, candidate }) {
    const build = verifyBuild(bundle, base, candidate);
    const first = verifyShard(standard, bundle, 'standard', build);
    const second = verifyShard(fullScan, bundle, 'full-scan', build);
    requireValue(first.runner.runId === second.runner.runId && first.runner.runAttempt === second.runner.runAttempt,
        'Shards came from different workflow runs');
    const allIds = [...first.queryIds, ...second.queryIds];
    requireValue(first.queryIds.length === 71 && second.queryIds.length === 1 && new Set(allIds).size === 72, 'Incomplete or overlapping shards');
    const previous = json(path.join(legacy, 'global-wide-status.json'));
    requireValue(previous.evidenceMode === 'legacy-diagnostics-only' && previous.currentPrBase === base && previous.currentHead === candidate,
        'Legacy evidence mode or revision mismatch');
    requireValue(typeof previous.passed === 'boolean' && Array.isArray(previous.errors) &&
        previous.errors.every(error => typeof error === 'string') && previous.passed === (previous.errors.length === 0), 'Invalid legacy integrity verdict');
    requireValue(path.resolve(output) !== path.resolve(legacy), 'Output must differ from legacy artifact');
    fs.mkdirSync(output, { recursive: true });
    fs.cpSync(legacy, output, { recursive: true });
    const merged = path.join(output, 'timed-query-latency');
    fs.mkdirSync(merged, { recursive: true });
    for (const file of SAMPLE_FILES) {
        const fd = fs.openSync(path.join(merged, file), 'w');
        try {
            let header;
            for (const directory of [standard, fullScan]) {
                let firstLine = true;
                for (const line of readLines(path.join(directory, file))) {
                    if (firstLine) {
                        firstLine = false;
                        if (header === undefined) header = line;
                        else { requireValue(header === line, 'Shard sample headers differ'); continue; }
                    }
                    fs.writeSync(fd, `${line}\n`);
                }
                requireValue(!firstLine, 'Empty shard sample file');
            }
        } finally { fs.closeSync(fd); }
    }
    const repeated = compareDirectory(merged, bundle);
    const errors = [...previous.errors, ...repeated.integrityErrors, ...repeated.latencyErrors];
    const result = { ...previous, evidenceMode: 'separate-query-latency', repeatedLatency: repeated,
        legacyDiagnostics: Object.fromEntries(['passed', 'regressionPassed', 'iterationPassed', 'targetAchieved',
            'progressAchieved', 'frozenTargetAchieved', 'blockingLatencyRef'].filter(key => key in previous).map(key => [key, previous[key]])),
        legacyIntegrityPassed: previous.passed, passed: previous.passed && repeated.passed,
        regressionPassed: previous.passed && repeated.passed, iterationPassed: previous.passed && repeated.passed,
        targetAchieved: previous.targetAchieved === true && previous.passed && repeated.passed, blockingLatencyRef: base,
        errors, shardReceipts: { standard: first, 'full-scan': second }, build };
    write(path.join(output, 'global-wide-status.json'), result);
    fs.writeFileSync(path.join(output, 'global-wide-report.md'),
        `${read(path.join(legacy, 'global-wide-report.md'))}\n${renderWideLatency(repeated)}`);
    for (const file of ['build.json', 'oracle.correctness', 'catalog.json', 'graphs.tsv']) fs.copyFileSync(path.join(bundle, file), path.join(merged, file));
    return result;
}
function flags(argv) {
    const result = {};
    for (let i = 0; i < argv.length; i += 2) {
        requireValue(/^--[a-z-]+$/.test(argv[i]) && argv[i + 1] && !argv[i + 1].startsWith('--'), 'Expected paired command flags');
        const key = argv[i].slice(2); requireValue(!(key in result), `Duplicate flag ${key}`); result[key] = argv[i + 1];
    }
    return result;
}
export function main(argv) {
    const [command, ...rest] = argv; const args = flags(rest); let result;
    if (command === 'seal-build') result = sealBuild(args.directory, args.base, args.candidate, args.catalog);
    else if (command === 'verify-build') result = verifyBuild(args.directory, args.base, args.candidate, args.catalog);
    else if (command === 'seal-shard') result = sealShard(args.directory, args.bundle, args.shard);
    else if (command === 'check-progress') result = checkProgress(args.directory, args.bundle, args.shard, Number(args.pairs));
    else if (command === 'aggregate') result = aggregateShards({ ...args, fullScan: args['full-scan'] });
    else throw new Error('Expected seal-build, verify-build, seal-shard, check-progress, or aggregate');
    if (command === 'seal-shard' ? !result.integrityPassed : command === 'check-progress' ? !result.canContinue : command === 'aggregate' && !result.passed) process.exitCode = 1;
    console.log(JSON.stringify({ command, passed: result.passed ?? true }));
    return result;
}
if (process.argv[1] && import.meta.url === pathToFileURL(fs.realpathSync(process.argv[1])).href) {
    try { main(process.argv.slice(2)); } catch (error) { console.error(error.message); process.exitCode = 1; }
}

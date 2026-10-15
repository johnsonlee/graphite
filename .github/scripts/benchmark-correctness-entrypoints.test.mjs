import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {checkMarkers} from './benchmark-correctness.mjs';
import {QUERIES, resultMarkers, compareCorrectness} from './benchmark-slow-query-shapes.mjs';

const sourceRoot = new URL('../../', import.meta.url);
const cypherSource = 'frontend/jvm/cypher/src/jmh/kotlin/io/johnsonlee/graphite/cypher/';
const read = path => fs.readFileSync(new URL(path, sourceRoot), 'utf8');
const budgetCases = ['budgetedGeneralRegex', 'budgetedListConcatenation', 'budgetedListMembership'];

test('method correctness keeps every original query shape and every retained budget case', () => {
    const benchmark = read(cypherSource + 'CypherBenchmark.kt');
    const methods = [...benchmark.matchAll(/@Benchmark\s+fun (\w+)\(/g)].map(m => m[1]);
    assert.equal(methods.length, 13);
    const entrypoint = read(cypherSource + 'SyntheticQueryCorrectness.kt');
    const checks = [...entrypoint.matchAll(/(?:verify|ordered)\(\s*"(\w+)"/g)].map(m => m[1]);
    assert.deepEqual(checks.toSorted(), methods.toSorted());
    for (const name of methods) assert.match(entrypoint, new RegExp(`suite\\.${name}\\(\\)`));
    assert.match(entrypoint, /BudgetedQueryCorrectness\.main\(emptyArray\(\)\)/);
    const budget = read(cypherSource + 'BudgetedQueryCorrectness.kt');
    assert.deepEqual([...budget.matchAll(/verify\("(\w+)"/g)].map(m => m[1]), budgetCases);
    for (const source of [entrypoint, budget]) assert.doesNotMatch(source, /nanoTime|currentTimeMillis|Runner\(|Thread\.sleep/);
    const workflow = read('.github/workflows/benchmark.yml');
    const methodJob = workflow.split('  method-level:')[1].split('  explorer:')[0];
    assert.match(methodJob, /cypher\.SyntheticQueryCorrectness/);
    assert.doesNotMatch(methodJob, /java -jar/);
    const markerArgs = methodJob.split('benchmark-results/candidate-method.log')[1].split('\n\n')[0];
    assert.deepEqual(markerArgs.replaceAll('\\', '').trim().split(/\s+/).toSorted(), [...methods, ...budgetCases].toSorted());
});

test('complete 16-case markers reject missing, duplicate, replaced or extra coverage', () => {
    const methods = [...read(cypherSource + 'CypherBenchmark.kt').matchAll(/@Benchmark\s+fun (\w+)\(/g)].map(m => m[1]);
    const names = [...methods, ...budgetCases];
    const lines = names.map(name => `CORRECTNESS_PASS\t${name}`);
    assert.deepEqual(checkMarkers(lines.join('\n'), names), names);
    for (const broken of [lines.slice(1), [...lines, lines[0]], [...lines.slice(1), 'CORRECTNESS_PASS\tunknown']]) {
        assert.throws(() => checkMarkers(broken.join('\n'), names));
    }
});

test('marker proofs require every exact case once, not mere child exit success', () => {
    assert.deepEqual(checkMarkers('CORRECTNESS_PASS\texplorer\n',['explorer']),['explorer']);
    for (const text of ['', 'CORRECTNESS_PASS\tcapacity', 'CORRECTNESS_PASS\texplorer\nCORRECTNESS_PASS\texplorer']) {
        assert.throws(()=>checkMarkers(text,['explorer']));
    }
});
function slowLog() {
    return QUERIES.map((query,i)=>[
        `SLOW_QUERY_SHAPE_FIXTURE\tprotocol=private-copy-no-callsite-index-v2\tcorpus=android\tsource=/fixture\tsnapshot=/tmp/graphite-slow-query-shapes-${i}/android\tindexAbsent=true`,
        ...['COLD','WARM'].map(state=>`SLOW_QUERY_SHAPE_RESULT\tandroid\t${state}\t${query}\trows=${query.endsWith('Hit')?1:0}\tsha256=${'a'.repeat(64)}`)
    ].join('\n')).join('\n');
}
test('slow correctness retains all 24 digest rows and fixture ownership without timing', () => {
    const rows=resultMarkers(slowLog(),QUERIES,'/fixture',()=>false,true);
    assert.equal(rows.size,24);
    const result=compareCorrectness(rows,rows,'unmodified-base');
    assert.equal(result.passed,true);assert.equal(result.performanceAcceptance,false);
    assert.throws(()=>resultMarkers(slowLog()+'\nSLOW_QUERY_SHAPE_RESOURCES',QUERIES,'/fixture',()=>false,true));
    assert.throws(()=>resultMarkers(slowLog(),QUERIES,'/fixture',()=>true,true));
    assert.throws(()=>resultMarkers(slowLog().replace('rows=1','rows=0'),QUERIES,'/fixture',()=>false,true));
    const drift=new Map(rows);drift.set('COLD/valueHit',{...drift.get('COLD/valueHit'),sha256:'b'.repeat(64)});
    assert.throws(()=>compareCorrectness(rows,drift,'unmodified-base'));
});


test('correctness CLI through a directory symlink emits reports and rejects missing cases', t => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'correctness-cli-alias-'));
    t.after(() => fs.rmSync(root, {recursive:true, force:true}));
    const alias = path.join(root, 'scripts');
    fs.symlinkSync(fileURLToPath(new URL('.', import.meta.url)), alias, 'dir');
    const base = path.join(root, 'base.log'), candidate = path.join(root, 'candidate.log');
    fs.writeFileSync(base, 'CORRECTNESS_PASS\tquery\n');
    fs.writeFileSync(candidate, 'CORRECTNESS_PASS\tquery\n');
    const prefix = path.join(root, 'result');
    const invoke = () => spawnSync(process.execPath, [path.join(alias, 'benchmark-correctness.mjs'),
        'query', prefix, base, candidate, 'query'], {encoding:'utf8'});
    assert.equal(invoke().status, 0);
    const passed = JSON.parse(fs.readFileSync(prefix + '-status.json'));
    assert.equal(passed.passed, true);
    assert.equal(passed.scope, 'correctness-only');
    assert.equal(passed.performanceAcceptance, false);
    assert.deepEqual(passed.evidence.map(e => e.cases), [['query'], ['query']]);
    assert.match(fs.readFileSync(prefix + '-report.md', 'utf8'), /PASS/);
    fs.writeFileSync(candidate, '');
    assert.equal(invoke().status, 1);
    const failed = JSON.parse(fs.readFileSync(prefix + '-status.json'));
    assert.equal(failed.passed, false);
    assert.match(failed.errors.join(), /Missing, duplicate or unexpected/);
    assert.match(fs.readFileSync(prefix + '-report.md', 'utf8'), /FAIL/);
});

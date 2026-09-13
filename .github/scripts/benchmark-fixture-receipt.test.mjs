import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('./reuse-fixture64-receipt.sh', import.meta.url));
const digest = text => crypto.createHash('sha256').update(text).digest('hex');
function fixture(t) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'fixture-receipt-'));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const graphs = path.join(root, 'graphs'), jars = path.join(root, 'jars'), bin = path.join(root, 'bin');
    for (const dir of [graphs, jars, bin]) fs.mkdirSync(dir);
    const manifest = '# graph\tpath\tcount\tkind\tversion\tdigest\n' +
        Array.from({ length: 64 }, (_, i) => `fixture-${i}\t/old/root/fixture-${i}\t10\tandroid\t1\tabcd\n`).join('');
    const provenance = Array.from({ length: 19 }, (_, i) => `column${i}`).join('\t') + '\n' +
        Array.from({ length: 64 }, (_, i) => [`fixture-${i}`, ...Array(17).fill('same'), `/old/root/fixture-${i}`].join('\t') + '\n').join('');
    fs.writeFileSync(path.join(graphs, 'graphs.tsv'), manifest);
    fs.writeFileSync(path.join(graphs, 'fixture-provenance.tsv'), provenance);
    const semantic = text => text.trimEnd().split('\n').map(line => line.startsWith('#') ? line :
        line.split('\t').filter((_, i) => i !== 1).join('\t')).join('\n') + '\n';
    const provenanceDigest = digest(provenance.trimEnd().split('\n').map(line => line.split('\t').slice(0, 18).join('\t')).join('\n') + '\n');
    const proof = { passed: true, inputKey: 'fixture64-exact-key', firstManifestSemanticSha256: digest(semantic(manifest)),
        repeatedManifestSemanticSha256: digest(semantic(manifest)), firstProvenanceSha256: provenanceDigest,
        repeatedProvenanceSha256: provenanceDigest };
    const receipt = path.join(root, 'receipt.json');
    fs.writeFileSync(receipt, JSON.stringify(proof));
    const jar = path.join(root, 'harness.jar');
    fs.writeFileSync(jar, 'transport-test-only');
    for (const name of ['android-all-test.jar', 'tika-app-test.jar', 'hive-exec-test.jar', 'kotlin-compiler-embeddable-test.jar']) {
        fs.writeFileSync(path.join(jars, name), 'transport-test-only');
    }
    // Stub only the Java boundary: tests verify dispatch and fail-closed propagation, not graph integrity.
    fs.writeFileSync(path.join(bin, 'java'), '#!/bin/bash\nprintf "%s\\n" "$@" > "$VERIFY_ARGS"\nexit "${VERIFY_EXIT:-0}"\n', { mode: 0o755 });
    const argsFile = path.join(root, 'verify-args');
    const run = (key = proof.inputKey, exit = '0') => spawnSync('bash', [script, jar, jars, graphs, receipt, key], {
        encoding: 'utf8', env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, VERIFY_ARGS: argsFile, VERIFY_EXIT: exit }
    });
    return { root, graphs, jars, receipt, proof, argsFile, run };
}

test('cached proof is unchanged while locations relocate and current full verifier runs', t => {
    const c = fixture(t), before = fs.readFileSync(c.receipt, 'utf8');
    const result = c.run();
    assert.equal(result.status, 0, result.stderr);
    assert.equal(fs.readFileSync(c.receipt, 'utf8'), before);
    const manifest = fs.readFileSync(path.join(c.graphs, 'graphs.tsv'), 'utf8');
    assert.ok(manifest.includes(`${c.graphs}/fixture-0`));
    assert.ok(!manifest.includes('/old/root/'));
    const provenance = fs.readFileSync(path.join(c.graphs, 'fixture-provenance.tsv'), 'utf8');
    assert.ok(provenance.includes(`${c.graphs}/fixture-63`));
    const arguments_ = fs.readFileSync(c.argsFile, 'utf8');
    assert.ok(arguments_.includes('-Xmx4g\n'));
    assert.ok(arguments_.includes('io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation\n--verify\n'));
    assert.ok(arguments_.endsWith(`${c.graphs}/graphs.tsv\n${c.graphs}/fixture-provenance.tsv\n`));
    assert.equal((arguments_.match(/-D.*jar.path=/g) ?? []).length, 4);
});

for (const [name, mutate] of [
    ['wrong input key', c => { c.proof.inputKey = 'other-input'; }],
    ['unbound receipt', c => { delete c.proof.inputKey; }],
    ['failed proof', c => { c.proof.passed = false; }],
    ['different repeat output', c => { c.proof.repeatedProvenanceSha256 = '0'.repeat(64); }],
    ['changed manifest semantics', c => { const p = path.join(c.graphs, 'graphs.tsv'); fs.writeFileSync(p, fs.readFileSync(p, 'utf8').replace('\t10\t', '\t11\t')); }],
    ['changed provenance', c => { const p = path.join(c.graphs, 'fixture-provenance.tsv'); fs.writeFileSync(p, fs.readFileSync(p, 'utf8').replace('\tsame\t', '\tchanged\t')); }],
]) {
    test(`rejects ${name} before calling Java`, t => {
        const c = fixture(t); mutate(c); fs.writeFileSync(c.receipt, JSON.stringify(c.proof));
        assert.notEqual(c.run('fixture64-exact-key').status, 0);
        assert.equal(fs.existsSync(c.argsFile), false);
    });
}

test('valid cached proof cannot conceal current content verification failure', t => {
    const c = fixture(t);
    assert.equal(c.run(c.proof.inputKey, '13').status, 13);
    assert.ok(fs.existsSync(c.argsFile));
});

test('missing source fixture prevents reuse', t => {
    const c = fixture(t); fs.unlinkSync(path.join(c.jars, 'hive-exec-test.jar'));
    assert.notEqual(c.run().status, 0);
    assert.equal(fs.existsSync(c.argsFile), false);
});

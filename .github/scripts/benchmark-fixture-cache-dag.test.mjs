import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

const text = fs.readFileSync(new URL('../workflows/benchmark.yml', import.meta.url), 'utf8').split(/^jobs:\s*$/m)[1];
const starts = [...text.matchAll(/^  ([a-z][a-z0-9-]*):\n/gm)];
const jobs = Object.fromEntries(starts.map((m, i) => {
    const body = text.slice(m.index, starts[i + 1]?.index);
    const needs = body.match(/^    needs: \[([^\]]+)\]/m)?.[1].split(',').map(x => x.trim()) ?? [];
    const expression = body.match(/^    if: (.+)$/m)?.[1].replace(/^\$\{\{\s*|\s*\}\}$/g, '') ?? 'true';
    return [m[1], { needs, expression }];
}));
const heavy = ['graph-routing-pressure-evidence', 'global-wide-historical-evidence',
    'build-wide-latency-bundle', 'wide-latency-measurements', 'benchmark-fail-fast-late'];
const hasStatus = expression => /\b(?:cancelled|success|failure|always)\(\)/.test(expression);

// Deliberately bounded interpreter for the actual job predicates, not arbitrary JS eval.
// Unrecognized syntax/variables fail closed so a future workflow change needs explicit review.
function evaluate(expression, context) {
    try {
        const tokens = []; let rest = expression;
        while (rest.trim()) {
            const m = rest.match(/^\s*(&&|\|\||==|!=|!|\(|\)|'[^']*'|[A-Za-z_][A-Za-z0-9_.-]*)/);
            if (!m) throw new Error('unsupported token');
            tokens.push(m[1]); rest = rest.slice(m[0].length);
        }
        let i = 0;
        function atom() {
            const token = tokens[i++];
            if (token === '!') return !atom();
            if (token === '(') { const value = or(); if (tokens[i++] !== ')') throw new Error('parenthesis'); return value; }
            if (token?.startsWith("'")) return token.slice(1, -1);
            if (token === 'true' || token === 'false') return token === 'true';
            if (['cancelled', 'success', 'failure', 'always'].includes(token)) {
                if (tokens[i++] !== '(' || tokens[i++] !== ')') throw new Error('status syntax');
                return context[token];
            }
            if (!Object.hasOwn(context, token)) throw new Error('unknown variable');
            return context[token];
        }
        function equality() { let value = atom(); while (['==', '!='].includes(tokens[i])) { const op = tokens[i++]; const rhs = atom(); value = op === '==' ? value === rhs : value !== rhs; } return value; }
        function and() { let value = equality(); while (tokens[i] === '&&') { i++; const rhs = equality(); value = Boolean(value && rhs); } return value; }
        function or() { let value = and(); while (tokens[i] === '||') { i++; const rhs = and(); value = Boolean(value || rhs); } return value; }
        const value = or(); if (i !== tokens.length || typeof value !== 'boolean') throw new Error('incomplete predicate'); return value;
    } catch { return false; }
}
function ancestors(name, result = new Set()) {
    for (const dep of jobs[name]?.needs ?? []) { if (!result.has(dep)) { result.add(dep); ancestors(dep, result); } }
    return result;
}
function permitted(name, states, { hit = 'true', cancelled = false, sameRepo = true } = {}) {
    const history = [...ancestors(name)].map(x => states[x] ?? 'success');
    const context = { cancelled, success: !cancelled && history.every(x => x === 'success'),
        failure: history.includes('failure'), always: true,
        'github.repository': 'owner/repo', 'github.event.pull_request.head.repo.full_name': sameRepo ? 'owner/repo' : 'fork/repo',
        'needs.prepare-fixture64-inputs.outputs.cache-hit': hit };
    for (const dep of jobs[name].needs) context[`needs.${dep}.result`] = states[dep] ?? 'success';
    // Model GitHub's implicit success guard, including a skipped optional ancestor.
    return (hasStatus(jobs[name].expression) || context.success) && evaluate(jobs[name].expression, context);
}
function fixturePath(hit, overrides = {}, cancelled = false, sameRepo = true) {
    const states = { 'prepare-fixture64-inputs': 'success', 'candidate-gate-tests': 'success', ...overrides };
    const order = ['generate-fixture64', 'prepare-fixture64', ...heavy];
    for (const name of order) if (!Object.hasOwn(states, name)) states[name] = permitted(name, states, { hit, cancelled, sameRepo }) ? 'success' : 'skipped';
    return states;
}

test('every transitive consumer of optional fixture producers explicitly handles status', () => {
    const descendants = Object.keys(jobs).filter(name => ancestors(name).has('generate-fixture64'));
    assert.ok(descendants.includes('wide-latency-measurements'));
    for (const name of descendants) assert.ok(hasStatus(jobs[name].expression), `${name} inherits implicit success and optional ancestor skip`);
});
test('verified cache hit with skipped producer reaches every heavy downstream job', () => {
    const states = fixturePath('true');
    assert.equal(states['generate-fixture64'], 'skipped');
    assert.equal(states['prepare-fixture64'], 'success');
    for (const name of heavy) assert.equal(states[name], 'success', name);
});
test('cache miss with both successful producers reaches every heavy downstream job', () => {
    const states = fixturePath('false');
    for (const name of ['generate-fixture64', 'prepare-fixture64', ...heavy]) assert.equal(states[name], 'success', name);
});
test('each heavy job rejects a failed cancelled or skipped direct prerequisite', () => {
    for (const name of heavy) for (const dep of jobs[name].needs) for (const status of ['failure', 'cancelled', 'skipped']) {
        const states = fixturePath('true'); states[dep] = status;
        assert.equal(permitted(name, states), false, `${name}: ${dep}=${status}`);
    }
});
test('failed missing or cancelled producer cannot publish a cache miss', () => {
    for (const status of ['failure', 'cancelled', 'skipped']) {
        const states = fixturePath('false', { 'generate-fixture64': status });
        assert.equal(states['prepare-fixture64'], 'skipped');
        for (const name of heavy) assert.equal(states[name], 'skipped', name);
    }
});
test('cancellation starts no heavy jobs and fork never starts privileged late watchdog', () => {
    for (const hit of ['true', 'false']) {
        const states = fixturePath(hit, {}, true);
        for (const name of heavy) assert.equal(states[name], 'skipped', name);
        const fork = fixturePath(hit, {}, false, false);
        assert.equal(fork['benchmark-fail-fast-late'], 'skipped');
        for (const name of heavy.filter(x => x !== 'benchmark-fail-fast-late')) assert.equal(fork[name], 'success', name);
    }
});
test('interpreter fails closed on unknown syntax variables or status functions', () => {
    for (const expression of ['unknown()', 'needs.missing.result == \'success\'', 'true + true', 'true trailing', 'true || unknown()']) assert.equal(evaluate(expression, {}), false);
    assert.equal(evaluate('!cancelled() && (true || false)', { cancelled: false }), true);
});

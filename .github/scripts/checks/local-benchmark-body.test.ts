import test from 'node:test';
import assert from 'node:assert/strict';
import { heading, validateLocalBenchmarkBody } from './local-benchmark-body.ts';

const base = '1'.repeat(40);
const head = '2'.repeat(40);

function validBody(): string {
  return `${heading}

- Machine: local MacBook Pro, Apple M4 Max, 64 GiB, macOS 15.6
- Baseline: ${base}
- Candidate: ${head}
- Workload: real persisted real64 multi-graph scope, four concurrent workers, warm cache, complete HTTP body consumption
- Sampling: alternating order; 2 warmups and 20 measured requests per case; nearest-rank intervals retained
- Query p50/p95: p50 and p95 base/head/delta tables below; 0 failed requests
- Graph loading: base 10 ms, candidate 11 ms, +1 ms (+10%); CPU/RSS below
- Graph construction: base 20 ms, candidate 21 ms, +1 ms (+5%); CPU/RSS below
- CPU / peak RSS: CPU +1%; RSS +2%; both within the 5% limits
- Correctness / stability: 1460/1460 requests passed; no crash, timeout or OOM
- Evidence: retained report archive https://example.invalid/report; SHA-256 ${'a'.repeat(64)}
- Conclusion: query accepted; loading accepted; construction accepted; CPU accepted; RSS accepted; correctness and stability accepted
`;
}

test('accepts a complete exact-revision report', () => {
  assert.doesNotThrow(() => validateLocalBenchmarkBody(validBody(), base, head));
});

test('rejects a stale candidate', () => {
  assert.throws(() => validateLocalBenchmarkBody(validBody(), base, '3'.repeat(40)), /Candidate .* does not match/);
});

test('rejects CI-only placeholders', () => {
  assert.throws(() => validateLocalBenchmarkBody(validBody().replace(/- Machine:.*/, '- Machine: see CI'), base, head), /Machine/);
});

test('rejects a hosted runner presented as the machine', () => {
  const body = validBody().replace(/- Machine:.*/, '- Machine: GitHub Actions ubuntu-latest, 16 GiB');
  assert.throws(() => validateLocalBenchmarkBody(body, base, head), /local benchmark host/);
});

test('rejects a single-graph workload', () => {
  const body = validBody().replace(/- Workload:.*/, '- Workload: real persisted single graph, four workers, complete HTTP body consumption');
  assert.throws(() => validateLocalBenchmarkBody(body, base, head), /multi-graph/);
});

test('requires a digest for retained evidence', () => {
  const body = validBody().replace(/- Evidence:.*/, '- Evidence: retained locally');
  assert.throws(() => validateLocalBenchmarkBody(body, base, head), /durable URL and SHA-256/);
});

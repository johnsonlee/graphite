import fs from 'node:fs';

export const heading = '## Local performance benchmark';

const required = [
  'Machine',
  'Baseline',
  'Candidate',
  'Workload',
  'Sampling',
  'Query p50/p95',
  'Graph loading',
  'Graph construction',
  'CPU / peak RSS',
  'Correctness / stability',
  'Evidence',
  'Conclusion',
] as const;

const placeholder = /^(?:n\/?a|none applicable|tbd|todo|pending|not run|unavailable|unchanged|see ci|ci only|-)\.?$/i;

function section(body: string): string {
  const start = body.indexOf(heading);
  if (start < 0) throw new Error(`PR body is missing the exact heading: ${heading}`);
  const rest = body.slice(start + heading.length);
  const next = rest.search(/^##\s+/m);
  return next < 0 ? rest : rest.slice(0, next);
}

function fields(content: string): Map<string, string> {
  const values = new Map<string, string>();
  for (const line of content.split(/\r?\n/)) {
    const match = line.match(/^-\s+([^:]+):\s*(.*)$/);
    if (!match) continue;
    const label = match[1]!.trim();
    if (!required.includes(label as typeof required[number])) continue;
    if (values.has(label)) throw new Error(`Local benchmark field is duplicated: ${label}`);
    const value = match[2]!.replace(/<!--.*?-->/g, '').trim();
    values.set(label, value);
  }
  return values;
}

function requireRevision(label: 'Baseline' | 'Candidate', value: string, expected: string): void {
  const revision = value.match(/\b[0-9a-f]{40}\b/i)?.[0]?.toLowerCase();
  if (!revision) throw new Error(`${label} must contain the full 40-character Git revision`);
  const normalized = expected.toLowerCase();
  if (revision !== normalized) {
    throw new Error(`${label} ${revision} does not match expected revision ${expected}`);
  }
}

export function validateLocalBenchmarkBody(body: string, expectedBase: string, expectedHead: string): void {
  const content = section(body);
  const values = fields(content);
  for (const label of required) {
    const value = values.get(label) ?? '';
    if (!value || placeholder.test(value)) throw new Error(`Local benchmark field is missing or a placeholder: ${label}`);
  }

  requireRevision('Baseline', values.get('Baseline')!, expectedBase);
  requireRevision('Candidate', values.get('Candidate')!, expectedHead);

  const workload = values.get('Workload')!;
  if (!/(?:multi[- ]graph|real64)/i.test(workload) || !/(?:real|persisted)/i.test(workload)) {
    throw new Error('Workload must identify a real persisted multi-graph load workload');
  }
  if (!/(?:concurren|worker)/i.test(workload)) throw new Error('Workload must declare concurrency');
  if (!/(?:complete|consume|body)/i.test(workload)) throw new Error('Workload must declare complete result consumption');

  if (/(?:github actions|hosted runner|ubuntu-latest|macos-latest|windows-latest)/i.test(values.get('Machine')!)) {
    throw new Error('Machine must describe the local benchmark host, not a hosted CI runner');
  }

  const sampling = values.get('Sampling')!;
  if (!/(?:warmup|warm-up)/i.test(sampling) || !/(?:sample|request|repetition|trial)/i.test(sampling)) {
    throw new Error('Sampling must declare warmups and measured samples/repetitions');
  }

  if (!/p50/i.test(values.get('Query p50/p95')!) || !/p95/i.test(values.get('Query p50/p95')!)) {
    throw new Error('Query p50/p95 must report both p50 and p95');
  }
  const evidence = values.get('Evidence')!;
  if (!/https?:\/\//i.test(evidence) || !/(?:sha-?256|sha256)/i.test(evidence)) {
    throw new Error('Evidence must include a durable URL and SHA-256 digest');
  }
}

function argument(name: string): string {
  const index = process.argv.indexOf(name);
  const value = index < 0 ? undefined : process.argv[index + 1];
  if (!value) throw new Error(`Missing ${name}`);
  return value;
}

if (import.meta.main) {
  validateLocalBenchmarkBody(
    fs.readFileSync(argument('--body'), 'utf8'),
    argument('--base'),
    argument('--head'),
  );
  console.log('Local benchmark report matches the PR merge base and head.');
}

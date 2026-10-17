// GitHub transport only. Benchmark execution and reports live in benchmark/.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

export const MARKER = '<!-- graphite-benchmark-report:v1 -->';
export const LEGACY_MARKER = '<!-- graphite-benchmark-regression-gate -->';
const IDENTITY = /<!-- graphite-benchmark-run:(\d+):(\d+):([0-9a-f]{40}) -->/;
const SHA = /^[0-9a-f]{40}$/;
const MAX_ARTIFACT_BYTES = 4 * 1024 * 1024;

export interface Association { number: number; base?: { sha: string } }
export interface Pull extends Association {
  state: string;
  base: { sha: string; repo: { id: number } };
  head: { sha: string };
}
export interface Run {
  id: number;
  run_attempt: number;
  head_sha: string;
  html_url: string;
  event: string;
  conclusion: string | null;
  repository: { id: number };
  pull_requests?: Association[];
}
export interface Payload { repository: { id: number }; workflow_run: Run }
export interface Comment { id: number; user?: { login: string }; body?: string }
export interface Artifact { id: number; name: string; expired: boolean }
export interface GitHub {
  associatedPulls(head: string): Promise<Association[]>;
  artifacts(run: number): Promise<Artifact[]>;
  pull(number: number): Promise<Pull>;
  mergeBase(base: string, head: string): Promise<string | undefined>;
  comments(number: number): Promise<Comment[]>;
  create(number: number, body: string): Promise<void>;
  update(id: number, body: string): Promise<void>;
}

export function existingReport(comments: Comment[]): Comment | undefined {
  const owned = comments.filter(comment => comment.user?.login === 'github-actions[bot]');
  return owned.filter(comment => comment.body?.startsWith(MARKER)).sort((a, b) => a.id - b.id)[0] ??
    owned.filter(comment => comment.body?.startsWith(LEGACY_MARKER)).sort((a, b) => a.id - b.id)[0];
}

export function isStale(comment: Pick<Comment, 'body'> | undefined, run: Pick<Run, 'id' | 'run_attempt'>): boolean {
  const match = comment?.body?.match(IDENTITY);
  return Boolean(match && (BigInt(match[1]!) > BigInt(run.id) ||
    (BigInt(match[1]!) === BigInt(run.id) && Number(match[2]) >= run.run_attempt)));
}

export function bodyFor(markdown: string, run: Pick<Run, 'id' | 'run_attempt' | 'head_sha' | 'html_url'>, baseSha: string, artifactUrl: string): string {
  const identity = `<!-- graphite-benchmark-run:${run.id}:${run.run_attempt}:${run.head_sha} -->`;
  const provenance = `Base: \`${baseSha}\` · Head: \`${run.head_sha}\`\n\n` +
    `[Workflow and raw evidence](${run.html_url}) · [Full report](${artifactUrl})\n\n`;
  const prefix = `${MARKER}\n${identity}\n## Benchmark report\n\n${provenance}`;
  // UTF-16 length is conservative for GitHub's character limit, even for emoji.
  // Retain complete rows only; a single oversized line is omitted entirely.
  const budget = Math.max(0, 58000 - prefix.length);
  let text = markdown;
  if (text.length > budget) {
    const cut = text.slice(0, budget);
    const newline = cut.lastIndexOf('\n');
    text = newline < 0 ? '' : cut.slice(0, newline);
    text += '\n\nThe comment is abbreviated; the full report and every measurement are retained in the linked artifact.\n';
  }
  return prefix + text;
}

function readArtifact(filename: string, description: string): string {
  const stat = fs.lstatSync(filename);
  if (!stat.isFile() || stat.size > MAX_ARTIFACT_BYTES) throw new Error(`Invalid ${description}`);
  return fs.readFileSync(filename, 'utf8');
}

export async function publish({ github, payload, directory, info = console.log }: {
  github: GitHub; payload: Payload; directory: string; info?: (message: string) => void;
}): Promise<void> {
  const run = payload.workflow_run;
  if (run.event !== 'pull_request' || run.repository.id !== payload.repository.id) return;
  // Both association and comparison base come from GitHub, never PR artifacts.
  const pulls = run.pull_requests?.length ? run.pull_requests : await github.associatedPulls(run.head_sha);
  const artifacts = await github.artifacts(run.id);
  for (const association of pulls) {
    const pull = await github.pull(association.number);
    if (pull.state !== 'open' || pull.base.repo.id !== payload.repository.id || pull.head.sha !== run.head_sha) continue;
    const artifactName = `benchmark-report-${pull.number}-${run.run_attempt}`;
    const artifact = artifacts.find(item => item.name === artifactName && !item.expired);
    const recordedBaseTip = association.base?.sha;
    let comparisonBase: string | undefined;
    let markdown = `Correctness/OOM execution: **${run.conclusion}**.\n\nNo complete report was produced. See the workflow diagnostics.\n\nTimings are informational and do not decide the gate.\n`;
    const reportDirectory = path.join(directory, artifactName);
    const filename = path.join(reportDirectory, 'summary.md');
    if (recordedBaseTip) {
      try {
        comparisonBase = await github.mergeBase(recordedBaseTip, run.head_sha);
        if (!comparisonBase || !SHA.test(comparisonBase)) throw new Error('Missing comparison merge base');
      } catch (error) {
        if (artifact && fs.existsSync(filename)) throw error;
        comparisonBase = undefined;
        info(`Comparison merge base unavailable for run ${run.id}/${run.run_attempt}`);
      }
    }
    if (artifact && fs.existsSync(filename)) {
      const identity: { baseSha?: string; headSha?: string } = JSON.parse(
        readArtifact(path.join(reportDirectory, 'report.json'), 'report identity'));
      if (typeof identity?.baseSha !== 'string' || !SHA.test(identity.baseSha) || identity.headSha !== run.head_sha ||
          !comparisonBase || identity.baseSha !== comparisonBase) {
        throw new Error('Report does not describe this workflow run');
      }
      markdown = readArtifact(filename, 'benchmark Markdown artifact');
    }
    if (recordedBaseTip && recordedBaseTip !== pull.base.sha) {
      info(`Ignoring run ${run.id}/${run.run_attempt}: PR base has changed`);
      continue;
    }
    const existing = existingReport(await github.comments(pull.number));
    if (isStale(existing, run)) { info(`Ignoring superseded run ${run.id}/${run.run_attempt}`); continue; }
    // Recheck immediately before publishing so a slow run cannot replace a new head/base.
    const latest = await github.pull(pull.number);
    if (latest.state !== 'open' || latest.head.sha !== run.head_sha) continue;
    if (recordedBaseTip && latest.base.sha !== recordedBaseTip) continue;
    const body = bodyFor(markdown, run, comparisonBase || 'unavailable',
      artifact ? `${run.html_url}/artifacts/${artifact.id}` : run.html_url);
    if (existing) await github.update(existing.id, body);
    else await github.create(pull.number, body);
  }
}

export type Execute = (args: string[], input?: string) => string;
function execute(args: string[], input?: string): string {
  const result = spawnSync('gh', args, { input, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`gh api failed (${result.status}): ${result.stderr}`);
  return result.stdout;
}

export class GhGitHub implements GitHub {
  private readonly prefix: string;
  private readonly execute: Execute;
  constructor(repository: string, runner: Execute = execute) {
    if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository)) throw new Error('Invalid GITHUB_REPOSITORY');
    this.prefix = `repos/${repository}`;
    this.execute = runner;
  }
  private request<T>(endpoint: string, method = 'GET', body?: { body: string }, paginate = false): T {
    const args = ['api', `${this.prefix}/${endpoint}`, '--hostname', 'github.com', '--method', method,
      '--header', 'Accept: application/vnd.github+json'];
    if (paginate) args.push('--paginate', '--slurp');
    if (body) args.push('--input', '-');
    return JSON.parse(this.execute(args, body ? JSON.stringify(body) : undefined)) as T;
  }
  async associatedPulls(head: string): Promise<Association[]> {
    return this.request<Association[][]>(`commits/${encodeURIComponent(head)}/pulls?per_page=100`, 'GET', undefined, true).flat();
  }
  async artifacts(run: number): Promise<Artifact[]> {
    return this.request<{ artifacts: Artifact[] }[]>(`actions/runs/${run}/artifacts?per_page=100`, 'GET', undefined, true)
      .flatMap(page => page.artifacts);
  }
  async pull(number: number): Promise<Pull> { return this.request<Pull>(`pulls/${number}`); }
  async mergeBase(base: string, head: string): Promise<string | undefined> {
    return this.request<{ merge_base_commit?: { sha: string } }>(
      `compare/${encodeURIComponent(base)}...${encodeURIComponent(head)}`).merge_base_commit?.sha;
  }
  async comments(number: number): Promise<Comment[]> {
    return this.request<Comment[][]>(`issues/${number}/comments?per_page=100`, 'GET', undefined, true).flat();
  }
  async create(number: number, body: string): Promise<void> { this.request(`issues/${number}/comments`, 'POST', { body }); }
  async update(id: number, body: string): Promise<void> { this.request(`issues/comments/${id}`, 'PATCH', { body }); }
}

export async function main(args: string[], env: NodeJS.ProcessEnv = process.env): Promise<void> {
  if (args.length !== 2 || args[0] !== '--directory' || !args[1]) throw new Error('Usage: comment.ts --directory reports');
  if (!env.GITHUB_EVENT_PATH || !env.GITHUB_REPOSITORY) throw new Error('GITHUB_EVENT_PATH and GITHUB_REPOSITORY are required');
  const payload: Payload = JSON.parse(fs.readFileSync(env.GITHUB_EVENT_PATH, 'utf8'));
  await publish({ github: new GhGitHub(env.GITHUB_REPOSITORY), payload, directory: args[1] });
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).catch(error => { console.error(error); process.exitCode = 1; });
}

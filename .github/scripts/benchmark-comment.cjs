// GitHub transport only. Benchmark execution and report generation live in benchmark/.
const fs = require('node:fs');
const path = require('node:path');
const MARKER = '<!-- graphite-benchmark-report:v1 -->';
const LEGACY_MARKER = '<!-- graphite-benchmark-regression-gate -->';
const IDENTITY = /<!-- graphite-benchmark-run:(\d+):(\d+):([0-9a-f]{40}) -->/;

function existingReport(comments) {
  const owned = comments.filter(comment => comment.user?.login === 'github-actions[bot]');
  return owned.filter(comment => comment.body?.startsWith(MARKER)).sort((a, b) => a.id - b.id)[0] ||
    owned.filter(comment => comment.body?.startsWith(LEGACY_MARKER)).sort((a, b) => a.id - b.id)[0];
}

function isStale(comment, run) {
  const match = comment?.body?.match(IDENTITY);
  return Boolean(match && (BigInt(match[1]) > BigInt(run.id) ||
    (BigInt(match[1]) === BigInt(run.id) && Number(match[2]) >= run.run_attempt)));
}

function bodyFor(markdown, run, baseSha, artifactUrl) {
  const identity = `<!-- graphite-benchmark-run:${run.id}:${run.run_attempt}:${run.head_sha} -->`;
  const provenance = `Base: \`${baseSha}\` · Head: \`${run.head_sha}\`\n\n` +
    `[Workflow and raw evidence](${run.html_url}) · [Full report](${artifactUrl})\n\n`;
  const prefix = `${MARKER}\n${identity}\n## Benchmark report\n\n${provenance}`;
  // GitHub's issue-comment limit is 65,536 characters. Never truncate a table mid-row.
  const budget = 58000 - prefix.length;
  let text = markdown;
  if (text.length > budget) {
    text = text.slice(0, budget);
    text = text.slice(0, text.lastIndexOf('\n'));
    text += '\n\nThe comment is abbreviated; the full report and every measurement are retained in the linked artifact.\n';
  }
  return prefix + text;
}

async function publish({ github, context, core, directory }) {
  const run = context.payload.workflow_run;
  if (run.event !== 'pull_request' || run.repository.id !== context.payload.repository.id) return;
  const repo = context.repo;
  // The association comes from GitHub, never from an artifact supplied by the PR.
  const pulls = run.pull_requests?.length ? run.pull_requests : await github.paginate(
    github.rest.repos.listPullRequestsAssociatedWithCommit, { ...repo, commit_sha: run.head_sha, per_page: 100 });
  const artifacts = await github.paginate(github.rest.actions.listWorkflowRunArtifacts,
    { ...repo, run_id: run.id, per_page: 100 });
  for (const association of pulls) {
    const pull = (await github.rest.pulls.get({ ...repo, pull_number: association.number })).data;
    if (pull.state !== 'open' || pull.base.repo.id !== context.payload.repository.id || pull.head.sha !== run.head_sha) continue;
    const artifact = artifacts.find(item => item.name === `benchmark-report-${pull.number}` && !item.expired);
    let recordedBase = association.base?.sha;
    let markdown = `Correctness/OOM execution: **${run.conclusion}**.\n\nNo complete report was produced. See the workflow diagnostics.\n\nTimings are informational and do not decide the gate.\n`;
    const reportDirectory = path.join(directory, `benchmark-report-${pull.number}`);
    const filename = path.join(reportDirectory, 'summary.md');
    if (artifact && fs.existsSync(filename)) {
      const identityFile = path.join(reportDirectory, 'report.json');
      const identityStat = fs.lstatSync(identityFile);
      if (!identityStat.isFile() || identityStat.size > 4 * 1024 * 1024) throw new Error('Invalid report identity');
      const identity = JSON.parse(fs.readFileSync(identityFile, 'utf8'));
      if (!/^[0-9a-f]{40}$/.test(identity.baseSha) || identity.headSha !== run.head_sha ||
          identity.baseSha !== pull.base.sha || (recordedBase && identity.baseSha !== recordedBase)) {
        throw new Error('Report does not describe this workflow run');
      }
      recordedBase = identity.baseSha;
      const stat = fs.lstatSync(filename);
      if (!stat.isFile() || stat.size > 4 * 1024 * 1024) throw new Error('Invalid benchmark Markdown artifact');
      markdown = fs.readFileSync(filename, 'utf8');
    }
    const comments = await github.paginate(github.rest.issues.listComments,
      { ...repo, issue_number: pull.number, per_page: 100 });
    const existing = existingReport(comments);
    if (isStale(existing, run)) { core.info(`Ignoring superseded run ${run.id}/${run.run_attempt}`); continue; }
    // Recheck immediately before publishing; an old, slow run must not replace a new head.
    const latest = (await github.rest.pulls.get({ ...repo, pull_number: pull.number })).data;
    if (latest.state !== 'open' || latest.head.sha !== run.head_sha) continue;
    // A changed base needs a fresh matched comparison too.
    if (recordedBase && latest.base.sha !== recordedBase) continue;
    const body = bodyFor(markdown, run, recordedBase || 'unavailable',
      artifact ? `${run.html_url}/artifacts/${artifact.id}` : run.html_url);
    if (existing) await github.rest.issues.updateComment({ ...repo, comment_id: existing.id, body });
    else await github.rest.issues.createComment({ ...repo, issue_number: pull.number, body });
  }
}

module.exports = { publish, existingReport, isStale, bodyFor, MARKER };

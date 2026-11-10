import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

type Step = { name?: string; id?: string; if?: string; run?: string; env?: Record<string, string>; 'continue-on-error'?: boolean };
type Job = { needs: string | string[]; permissions?: Record<string, string>; steps: Step[]; 'continue-on-error'?: boolean };
type Workflow = { env: Record<string, string>; jobs: Record<string, Job> };
const runtime = globalThis as unknown as { Bun: { YAML: { parse(text: string): unknown } } };
const workflow = runtime.Bun.YAML.parse(readFileSync(new URL('../../workflows/publish.yml', import.meta.url), 'utf8')) as Workflow;
const github = workflow.jobs['github-packages'];
const local = github.steps.find(step => step.name === 'Rehearse GitHub Packages in a local file repository')!;
const remote = github.steps.find(step => step.name === 'Publish Maven artifacts to GitHub Packages')!;

test('GitHub Packages is independent and only real publication receives its token', () => {
  assert.equal(github.needs, 'validate');
  assert.deepEqual(github.permissions, { contents: 'read', packages: 'write' });
  assert.equal(github['continue-on-error'], undefined);
  assert.match(workflow.env.DRY_RUN, /github.event_name == 'workflow_dispatch'/);
  assert.equal(local.if, "env.DRY_RUN == 'true'");
  assert.equal(remote.if, "env.DRY_RUN != 'true'");
  assert.equal(local.env, undefined);
  assert.equal(remote.env?.GITHUB_TOKEN, '${{ secrets.GITHUB_TOKEN }}');
  for (const step of github.steps) {
    assert.equal(step['continue-on-error'], undefined);
    assert.doesNotMatch(JSON.stringify(step), /OSSRH|signing\.|GPG_|SonatypeRepository|publishToMavenLocal/);
  }
  assert.match(remote.run!, /^\.\/gradlew publishMavenJavaPublicationToGitHubPackagesRepository /);
  assert.doesNotMatch(remote.run!, /githubPackagesLocalRepository/);
});

test('only the real Sonatype publication can fail softly; dry-run builds stay mandatory', () => {
  const maven = workflow.jobs.maven;
  assert.equal(maven['continue-on-error'], undefined);
  const soft = maven.steps.filter(step => step['continue-on-error']);
  assert.equal(soft.length, 1);
  assert.equal(soft[0].id, 'sonatype');
  assert.equal(soft[0].if, "env.DRY_RUN != 'true'");
  const build = maven.steps.find(step => step.name === 'Publish the Maven artifacts to the local repository (dry run)')!;
  assert.equal(build.if, "env.DRY_RUN == 'true'");
  assert.equal(build['continue-on-error'], undefined);
  assert.match(build.run!, /for MODULE in core ir cypher sootup webgraph/);
  assert.ok(maven.steps.indexOf(build) < maven.steps.indexOf(soft[0]));
  const report = maven.steps.find(step => step.name === 'Report Sonatype publication failure')!;
  assert.equal(report.if, "always() && steps.sonatype.outcome == 'failure'");
  assert.match(report.run!, /::warning/);
  assert.match(report.run!, /GITHUB_STEP_SUMMARY/);
  assert.ok(![workflow.jobs.release.needs].flat().includes('maven'));
});

test('Sonatype failures produce a visible warning and retained summary', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'graphite-sonatype-report-'));
  try {
    const summary = path.join(directory, 'summary');
    const report = workflow.jobs.maven.steps.find(step => step.name === 'Report Sonatype publication failure')!;
    const result = spawnSync('bash', ['-e', '-o', 'pipefail', '-c', report.run!], {
      env: { PATH: process.env.PATH, GITHUB_STEP_SUMMARY: summary }, encoding: 'utf8',
    });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /::warning title=Sonatype publication failed::/);
    assert.match(readFileSync(summary, 'utf8'), /original error logs remain/);
    assert.match(readFileSync(summary, 'utf8'), /Maven Central availability is not confirmed/);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('dry-run invokes the named repository tasks with a file override and propagates failure', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'graphite-publication-'));
  try {
    writeFileSync(path.join(directory, 'gradlew'), `#!/bin/bash
set -eu
printf '%s\\n' "$@" > args
if [ "\${FAIL_GRADLE:-}" = 1 ]; then exit 23; fi
mkdir -p "$RUNNER_TEMP/github-packages-repository"
printf '%s\\n' 'io.johnsonlee.graphite:ir:0.0.0-test' > "$RUNNER_TEMP/github-packages-repository/verified-publications.txt"
`, { mode: 0o755 });
    const env = { PATH: process.env.PATH, RELEASE_VERSION: '0.0.0-test', RUNNER_TEMP: directory, GITHUB_STEP_SUMMARY: path.join(directory, 'summary') };
    const success = spawnSync('bash', ['-e', '-o', 'pipefail', '-c', local.run!], { cwd: directory, env, encoding: 'utf8' });
    assert.equal(success.status, 0, success.stderr);
    const args = readFileSync(path.join(directory, 'args'), 'utf8').trim().split('\n');
    assert.deepEqual(args.slice(0, 2), ['publishMavenJavaPublicationToGitHubPackagesRepository', 'verifyGitHubPackagesLocalPublication']);
    assert.ok(args.includes(`-PgithubPackagesLocalRepository=${directory}/github-packages-repository`));
    assert.equal(readFileSync(env.GITHUB_STEP_SUMMARY, 'utf8'), 'io.johnsonlee.graphite:ir:0.0.0-test\n');
    const failure = spawnSync('bash', ['-e', '-o', 'pipefail', '-c', local.run!], { cwd: directory, env: { ...env, FAIL_GRADLE: '1' }, encoding: 'utf8' });
    assert.equal(failure.status, 23);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

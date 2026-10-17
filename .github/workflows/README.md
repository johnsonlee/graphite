# GitHub Actions workflows

English · [简体中文](README.zh.md)

Workflows wire together checks, artifacts and publishing. Benchmark execution lives in
[`benchmark/`](../../benchmark/README.md); TypeScript CI helpers and their Bun version
configuration live in [`.github/scripts/`](../scripts/README.md).

## Workflow index

| Workflow | Trigger | Responsibility |
| --- | --- | --- |
| [`build.yml`](build.yml) — Run unit tests | Branch pushes matching `*`, PRs targeting `**`, `repository_dispatch: test` | Check TypeScript helpers, test Explorer web state, run Gradle checks, enforce 98% application line coverage on PRs, and post coverage/failure diagnostics. |
| [`rust.yml`](rust.yml) — Rust | Branch pushes matching `*`, PRs targeting `**` | Format, lint, build and test Rust; check static Linux binaries and container startup; exercise real JVM construction, persisted graphs and Kotlin/Rust response parity. Also compile-check the workspace on Windows. |
| [`benchmark.yml`](benchmark.yml) — Benchmark correctness and OOM | PRs targeting `**` | Compare the product merge-base and exact PR head on one runner. Gate correctness, execution completeness and OOM; report timing differences without thresholds. |
| [`benchmark-comment.yml`](benchmark-comment.yml) — Update benchmark report comment | Completion of Benchmark correctness and OOM; only PR-originated runs are published | Validate report provenance and update one benchmark comment for the current PR head. |
| [`publish.yml`](publish.yml) — Publish | `v*` tag pushes, `repository_dispatch: publish`, or manual dispatch | Build release assets and publish them, or rehearse the release with manual dry-run mode. Jobs are restricted to the `johnsonlee` repository owner. |

The literal branch patterns above reflect the YAML: `*` does not match a slash in a
branch name. PR workflows use `**`. Rust has no path filter because JVM/frontend and
fixture changes can affect its integration checks too.

## PR checks and benchmark reporting

The correctness workflows run independently. Their job names are:

| Workflow | Check names |
| --- | --- |
| JVM and CI scripts | `run-unit-test` |
| Rust | `rust-build-test`, `rust-check-windows` |
| Benchmark | `benchmark-harness-tests` → `benchmark-base-head` → `benchmark-regression-gate` |

`rust-check-windows` runs `cargo check`; it is not a Windows execution test suite.
`benchmark-regression-gate` retains its historical name for branch protection, but
performance changes never determine its result. Required-check selection is managed
in repository branch protection/rulesets, separately from these YAML files.

The benchmark workflow has three jobs:

1. **Harness:** resolve the comparison merge-base, then build and test the controller
   from the trusted target branch tip. Upload that binary for the comparison job.
2. **Comparison:** check out base, head and the same trusted harness, then invoke one
   `graphite-benchmark run` command. Rust owns preparation, JMH build/save, production
   Rust server load/query, source identities and artifact paths. An independent
   `always()` report step re-renders available evidence even if the controller is killed.
3. **Gate:** require both upstream jobs to succeed. Missing preparation, incomplete
   execution, failed correctness checks and OOM evidence cannot become a passing report.

Build/save validation uses the JVM frontend's JMH harness. Load/query uses production
Rust servers and the real64 multi-graph workload. JAR benchmarks use a fixed 4 GiB
maximum JVM heap. The APK policy is 8 GiB, but APK fixture integration is deferred;
the current four fixtures are JARs. The separate production CLI-default check in
`rust.yml` tests its 8 GiB default and a 2 GiB user override through Java MXBeans.

Hosted timings are observations, not performance acceptance. Reports compare base/head
values and retain unavailable or failed measurements explicitly. See the
[benchmark protocol](../../benchmark/README.md) for sampling, workload and heap details.

## Why the benchmark comment has its own workflow

`benchmark.yml` executes candidate code with `contents: read`. The follow-up
`workflow_run` publisher uses trusted workflow code and has `actions: read` plus
`pull-requests: write`. It downloads the report as data; it does not execute a PR checkout
with its write token. Keeping this publishing step separate also supports fork PRs.

The publisher checks the run/attempt, comparison merge-base and current PR head/base
before updating the marked bot comment. New commits update that comment rather than
create another one; stale runs cannot replace newer results. Missing artifacts produce
an explicit failure/unavailable result. Publication is serialized without cancelling
an in-flight publisher, while benchmark runs cancel superseded runs for the same PR.

The publisher must first exist on the default branch to receive completion events.
During the initial controller migration, PR #179 has a narrowly scoped fixed-snapshot
bootstrap because its base predates the Rust controller. The exact exception is in
`benchmark.yml`; normal PRs use their trusted target base's controller.

## Results and failure diagnosis

| Artifact/output | Contents | Retention |
| --- | --- | --- |
| Actions job summary | Compact Markdown benchmark report | With the workflow run |
| `benchmark-report-<PR>-<attempt>` | `summary.md`, full Markdown/HTML and `report.json`; consumed by the comment publisher | 30 days |
| `benchmark-evidence-<PR>-<attempt>` | Raw suite/request data, logs, heap receipts, preparation identities and `execution.json` | 14 days |
| `benchmark-controller` | Trusted compiled controller, reused by downstream-job retries | 30 days |
| `failed-test-diagnostics` | JVM test XML and Gradle build output | 7 days |

Start with the failing job and its report errors, then follow the retained evidence.
For interrupted benchmark runs, missing completion evidence is itself a failure; a
successfully rendered report does not imply the checks passed. Local reproduction and
report re-rendering commands are in the [benchmark README](../../benchmark/README.md#running).

## Release and dry-run behavior

`publish.yml` validates the `v`-prefixed SemVer tag before publishing jobs run. Maven
publication and the four Linux/macOS binary builds follow validation. The release job
collects binaries, builds the JVM JAR, verifies assets and renders the Homebrew formula.
Docker and Homebrew jobs consume those release assets.

Tag pushes and `repository_dispatch: publish` are real publication paths: Sonatype,
GitHub Releases and container registries can be updated. Stable releases also update
the Homebrew tap; prereleases do not update the tap or the Docker `latest` tag.

Manual `workflow_dispatch` is a dry run from the selected ref. For example:

```sh
gh workflow run publish.yml --ref <branch> -f tag=v2.11.4
```

The supplied tag stamps the version and need not exist. Dry runs publish Maven artifacts
only to the runner's local repository, retain `release-<tag>` as an Actions artifact,
install/test the rendered formula locally, and build both image architectures with
`push: false`. They do not publish to Sonatype, create a GitHub Release, update the tap
or push container images. These remote publication steps are not validated by a dry run.

## Maintaining workflows

Keep workload settings and acceptance rules in the benchmark controller/JVM code,
not in workflow inputs. Keep reusable CI logic in the TypeScript helpers; workflows
should describe dependencies, environments and artifact movement.

All of `.github/` is covered by [`CODEOWNERS`](../CODEOWNERS). Keep owner-review and
required-check rules enabled in repository settings, and preserve check names referenced
there. Update this index when adding, removing or changing a workflow's responsibility.

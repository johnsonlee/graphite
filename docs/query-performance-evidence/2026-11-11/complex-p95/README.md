# Retained local complex-query evidence

These files preserve the first baseline pilot and Attempt315 pilot, including the
failed baseline. They are separate from the later matched 315→316 run and do not
establish final performance acceptance. The fixed workload and boundaries are in
[PROTOCOL.md](PROTOCOL.md).

**The original baseline failed during warmup. It has no pressure samples, baseline
P95, or complete mixed-pressure CPU. No P95/CPU/RSS improvement comparison can be
constructed from that failed run and the completed Attempt315 run.** In particular,
their process lifetime RSS peaks cover different completed work. Their numeric
values remain observations, not a paired resource-limit verdict.

## Source and executable identities

The [derived provenance record](provenance.json) records full commits, Git tree and
path-object IDs, executable SHA256, and the toolchain observed during this review.
The original run reports record executable hashes and fixture identities; they do
**not** record product source commits, compiler versions or embedded build IDs.

| Role | Fixed source reference | Executable SHA256 |
| --- | --- | --- |
| Original main native implementation | [58215b98](https://github.com/johnsonlee/graphite/tree/58215b986f8c1be68b0480bc209e00311bc80dc1), built from [a911529a](https://github.com/johnsonlee/graphite/tree/a911529a8fe2fc66185dca7715179ec13bf7b7c3) | `8176dd0faa6203e7ec753007ddef98d8f30ab918ae56dddad6f2ee7678c4175c` |
| Attempt315 | [94c7d3b9](https://github.com/johnsonlee/graphite/tree/94c7d3b9ed19f54324ed2a8379ff2597c1af3163) | `3fae4f183c160a93ccb04382de98205fcb799ca1c6728fc5fee3a9f32a1e6ac8` |
| Cumulative Attempt316 | [3498c8ea](https://github.com/johnsonlee/graphite/tree/3498c8ead089e2d881719104ac6f368ec24d8c94) | `c774c87c5ffbff94491fa7b48f478b67d392c88c9d59737b16f8d04ddbf39374` |
| Fixed local controller | [3d1d19c1](https://github.com/johnsonlee/graphite/tree/3d1d19c16d4a3439297a09aad84bc6a666206c50) | `d26e591832bdb8e1df50e264335e7934c94eab1da7cc27af70dbb0c048cac2d8` (current retained executable checked after the runs) |

The baseline checkout adds workload tests and documentation; its native production
source objects, CLI, Cargo manifest and lockfile match original main. Its complete
backend tree differs because a test file was added. The original baseline build
identity is retained in the historical archive and copied into provenance.json.

Attempt315 and Attempt316 were each built, copied to a frozen executable path and
hashed **before** their source commit was created. Their mapping above comes from
the session build/freeze records and subsequent source commits. No independent
product build manifest or complete retained compiler log was produced then. The
[Attempt316 Cypher test log](attempt316-cypher-tests.log) is preserved as supporting
correctness evidence; it does not establish binary build provenance. A
present executable hash check and a Git tree identity do not by themselves prove
which source produced that executable. This record states that limitation rather
than retroactively presenting session attestation as independent build proof.

The fixed controller's source mapping also comes from the session record; its
current executable hash is checked here but was not recorded by each paired-run
report. The historical controller `01db2da2` discussed below is a different,
unfixed version. Current review tools report `rustc 1.93.0 (254b59607 2026-01-19)`
and `cargo 1.93.0 (083ac5135 2025-12-15)`; the baseline build identity and candidate
session output independently recorded Rust1.93.0, not a frozen Cargo version.

## Contents and integrity

- [historical-baseline-attempt315.tar.gz](historical-baseline-attempt315.tar.gz)
  preserves 505 original files, 11,127,585 uncompressed bytes, in 1,176,912 bytes.
  It includes both complete run directories: protocol, queries, report, arm
  report, input seal, registry snapshots, every issued request journal, complete
  or partial body, receipt, stage outcome, server logs, controller logs, and
  baseline/controller build identities. The real graph payloads and executables
  are not included; their identities remain in the seals and reports.
- [archive-manifest.json](archive-manifest.json) lists every member's size and
  SHA256 plus the archive SHA256. [verify_archive.py](verify_archive.py) checks
  the archive and optionally extracts only verified regular files to an empty
  directory without overwriting existing files.
- [audit_local.py](audit_local.py) checks all issued requests, raw journal/report
  correspondence, receipt/body hashes and lengths, 64-graph scope, registry
  totals, full typed results, repeated and matched oracles, every pressure and
  isolated percentile, CPU deltas, and the resource measurement boundaries.
  It distinguishes JSON boolean/integer/float, preserves missing versus null,
  array order, duplicates and provenance. Failed or incomplete cases have
  unavailable percentiles. Incomparable runs have no computed deltas or 5% verdict.
- [historical-cpu-corrected-audit.json](historical-cpu-corrected-audit.json) is the
  derived independent audit; [historical-summary.md](historical-summary.md) is
  rendered by [summarize_audit.py](summarize_audit.py).

Archive SHA256:
`cc56b1b363f903f6d042ab523a48a5a4c6b34e70430a854da5751398c11345a3`.

## Darwin CPU correction

The original controller `01db2da2` incorrectly treated `proc_pid_rusage` CPU
fields as nanoseconds. These fields contain Mach absolute-time ticks. On this
M3 Max host, `mach_timebase_info` returns numerator 125 and denominator 3:

`seconds = ticks × 125 / 3 / 1,000,000,000`.

[darwin-timebase-proof.json](darwin-timebase-proof.json) records the live host
timebase and an independent `getrusage(RUSAGE_SELF)` bracket around a
`proc_pid_rusage` snapshot. Converted values fit the bracket. This is an
accounting correctness check, not a workload performance measurement. The parent
run provenance identifies the retained historical pilots as runs on this same
host. Controller fix `3d1d19c1` retains raw ticks and the timebase and adds a Rust
getrusage bracket test.

The derived audit reconstructs integer ticks from the old tick/1e9 fields,
checks reconstruction residuals, applies the independently measured timebase,
and corroborates the corrected final snapshots against the separately recorded
`wait4` lifetime CPU. Original reports and samples are unchanged. `wait4` already
uses timeval seconds/microseconds, so **its CPU and kernel peak RSS do not need
correction**. Latency measurements are also unchanged.

Attempt315's corrected complete pressure CPU is **1,185.129043875 seconds**.
Its corrected query-end snapshot is 1,375.166345083 seconds versus independently
recorded wait4 lifetime CPU of 1,375.382732 seconds; the 0.216386917-second gap is
post-snapshot process work through controlled termination. Loading readiness is
18.447809 seconds versus wait4 18.466805 seconds. The audit retains each original
field, reconstructed tick count, timebase and corroboration value.

Mixed-pressure CPU covers the whole six-case workload, not individual queries.
The query process's exact kernel RSS is a lifetime peak including loading, first
use, warmup and pressure; it cannot be reset or corrected by subtracting the
load-only process's peak. These limits remain explicit in every summary.

## Reproduce the independent audit

From the repository root, verify and extract to a fresh empty directory:

```sh
python3 docs/query-performance-evidence/2026-11-11/complex-p95/verify_archive.py \
  --extract /tmp/graphite-complex-p95-historical-recheck

python3 docs/query-performance-evidence/2026-11-11/complex-p95/audit_local.py \
  /tmp/graphite-complex-p95-historical-recheck/base-pilot-01 \
  --candidate-run /tmp/graphite-complex-p95-historical-recheck/attempt315-pilot-01 \
  --candidate-arm base \
  --darwin-timebase-proof docs/query-performance-evidence/2026-11-11/complex-p95/darwin-timebase-proof.json \
  --output /tmp/graphite-complex-p95-historical-recheck/audit.json

python3 docs/query-performance-evidence/2026-11-11/complex-p95/summarize_audit.py \
  /tmp/graphite-complex-p95-historical-recheck/audit.json \
  --output /tmp/graphite-complex-p95-historical-recheck/summary.md
```

Expected: both evidence audits are valid; baseline workload incomplete;
Attempt315 workload complete; comparison unavailable; performance acceptance
false. Extraction changes the run path in the derived output but not its evidence
hashes, response semantics or measurements.

For a later paired run, pass that directory as both positional baseline and
`--candidate-run`, with `--candidate-arm candidate`. Fixed controller snapshots
carry their own timebase, so the historical correction proof is unnecessary.
Preserve every failed run; do not replace it with a later successful rerun.

## First matched 315→316 pilot

[paired-pilot-01-summary.md](paired-pilot-01-summary.md) and
[paired-pilot-01-audit.json](paired-pilot-01-audit.json) retain the independently
verified first AB pair. Both arms completed138 requests with identical complete
typed results. Each case has20 pressure samples per arm at concurrency4. This is
an incremental comparison against retained315, not recovery against the original
failed baseline. Further rounds and confirmation are separate evidence.

[paired-pilot-01.tar.gz](paired-pilot-01.tar.gz) contains859 original files,
17,222,741 raw bytes in1,707,360 compressed bytes; the
[manifest](paired-pilot-01-manifest.json) binds every member and the archive.
Verify or extract with `verify_archive.py --manifest paired-pilot-01-manifest.json`
and optionally `--extract` pointing at a fresh empty directory. Then audit the
extracted `paired-pilot-01` directory as both baseline and candidate with
`--candidate-arm candidate`. No historical CPU correction proof is needed.

Archive SHA256:
`1578eded657ba38ff206f31d84cfbf95a0534f131dd5d9c5535cdb9400abe948`.

## Second matched pilot (BA)

[paired-pilot-02-summary.md](paired-pilot-02-summary.md) and
[paired-pilot-02-audit.json](paired-pilot-02-audit.json) retain the second,
reverse-order pair separately. Both arms completed138 requests; the independent
audit verified all complete typed responses and the identical input/binary/query
identities. Do not pool its20 pressure samples per case/arm with other rounds.

[paired-pilot-02.tar.gz](paired-pilot-02.tar.gz) preserves859 original files,
17,222,627 raw bytes in1,706,220 compressed bytes. Its
[manifest](paired-pilot-02-manifest.json) is accepted by
`verify_archive.py --manifest paired-pilot-02-manifest.json`. The archive SHA256 is
`e3c5a1044ec7255c160c3e8484bd3f609ffed29d827c9f4f12bf04d0f0023843`.

[archive_run.py](archive_run.py) provides the same deterministic packaging for
subsequent completed runs without modifying source evidence or replacing an
existing archive. It refuses active incomplete runs; an explicitly retained
failed run must already contain controller errors. Full independent audit remains
necessary after packaging.


## Third matched pilot (AB)

[paired-pilot-03-summary.md](paired-pilot-03-summary.md) and
[paired-pilot-03-audit.json](paired-pilot-03-audit.json) retain the third independent
20-sample pair. All276 responses passed the full typed evidence audit, and
input/query/binary/result identities match the earlier pairs. No rounds are pooled.

[paired-pilot-03.tar.gz](paired-pilot-03.tar.gz) and its
[manifest](paired-pilot-03-manifest.json) retain the original evidence. Verify with
`verify_archive.py --manifest paired-pilot-03-manifest.json`. Archive SHA256:
`17699d633bf75b465be3439a0855950f0d6e5410b07e27b71103657ca1a93081`.


## Independent100-sample confirmation (BA)

[paired-measure-01-summary.md](paired-measure-01-summary.md) and
[paired-measure-01-audit.json](paired-measure-01-audit.json) retain the separate
100-pressure-sample-per-case/arm confirmation. Both arms completed618 requests;
all1236 full typed responses passed independent verification. Its rank95 P95
values remain separate from the three rank19 pilot rounds. Source-input, fixture,
query, binary and complete-result identities match those rounds.

[paired-measure-01.tar.gz](paired-measure-01.tar.gz) preserves3739 original files,
67,834,939 raw bytes in6,665,823 compressed bytes. Verify the
[manifest](paired-measure-01-manifest.json) using
`verify_archive.py --manifest paired-measure-01-manifest.json`. Archive SHA256:
`1dc802fa8b884b782ce0b4b40d79a2e66b48bed5b89e41757d6cb99d68274bc5`.


## Original-main→316 73-case regression (AB)

[paired-regression-01-summary.md](paired-regression-01-summary.md) and
[paired-regression-01-audit.json](paired-regression-01-audit.json) retain the
separate regression run: each arm73 first-use,146 warmup,1460 pressure and2920
isolated requests. All9198 complete typed responses passed independent audit.
The pressure stage is20 requests/case at concurrency4; isolated is40 at
concurrency1, not sustained pressure. All73 qualified for isolated sampling.

42/73 pressure P95 observations increased (largest absolute+2.702041ms). The
resource bounds held for the measured mix, but this is not a claim of per-case
latency nonregression. No adverse observations were removed or favorable reruns
selected. Complete pressure and isolated values remain in the audit JSON.

[paired-regression-01.tar.gz](paired-regression-01.tar.gz) preserves27627 original
files,265,960,247 raw bytes in18,666,997 compressed bytes. Verify the
[manifest](paired-regression-01-manifest.json) with
`verify_archive.py --manifest paired-regression-01-manifest.json`. Archive SHA256:
`dd9cbcf71e8322087b869d9dc90d93cca4e5f6c58ce4a46166177c4bebd52154`.

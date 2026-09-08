# Native generic string disjunction candidate

Baseline Go is `513e2b96d4cd5136560461d06f3b804e6aa67c41`; original main is
`4e328b0109e13c896b74004823fb049fcb19251a`. The optimization is retained; full
performance and server acceptance remain unproven.

Main's six-term DISTINCT path excludes CallSite before constructing its generic
providers. Each mapped Enum/Local/Field provider reads raw property SIDs, shares
match states by transform/mode/expected, and decodes only matching nodes. The Go
indexed DISTINCT fallback previously sorted and fully decoded all generic nodes
before testing any predicate. The candidate reuses the existing lazy generic
provider and merge, adds main's per-concrete-iterator SID match arrays, and uses
the yielded payload ID for canonical order. Annotation retains full decoding.
Empty concrete types do not allocate string match arrays. The separate general
node decoder and source-worker scheduler are unchanged.

The complete actual-Java correctness oracle has 201 scenarios: 181 public calls
and 20 private-provider controls. Baseline matches 157 public and 19 provider
controls against each of two Java captures. Candidate v1 and v2 match 166 public
and 19 provider controls. Nine raw-filter/error-order differences are resolved;
no newly mismatching case appears in this bounded matrix. All original outputs,
remaining differences and source identities are preserved in `go-baseline/` and
`candidate-replay/`. The private Java provider has narrower scope than the Go
wrapper's merge/order checks; those controls do not establish equivalent APIs.

Remaining differences include general malformed collection decoding, truncated
Field load validation, isolated-surrogate materialization, and empty-coalesced
needle fallback order. Annotation's global malformed-tail message changes but
still differs from main. These failures remain recorded, not waived. Separate
read-only review also identifies an existing generic provenance stopping gap:
main checks cumulative raw plus generic selected hits after each candidate,
while Go's existing helper stops at the projection limit.

83 focused Java-observed regression cases pass, including all nine corrected
global-miss cases, raw SID versus full-read errors, payload identity, offsets,
source order, LIMIT and provenance. Initial test-authoring failures and their
corrections are retained in `implementation-notes/`. Full-module race tests and
vet pass; 2,539 recorded module/oracle/runner inputs remain unchanged. The original
Java oracle and frozen Go baseline archive also pass independent verification.

The first full cold real64 replay retains all 1,267 cases, all 1,266 successful
public results and the original case821 error. All 1,152 original graph files
remain unchanged. However, its strict state comparison FAILS: 15,162
`mappedRangeCount` differences among 162,304 graph-state observations. The first
is case3, `single-contains-unlabeled-dense`, after `fixture-android-02`: main has7,
candidate has0. Formal warm and startup-prepared captures did not start because
the controller stopped at this failure.

Case3 executes unchanged `ordinaryCandidateIterator -> mainCandidateIterator`,
without entering either changed provider function. Both responses end at the
same 200th row from android01. The ordered source-task helper then cancels its
speculative siblings: main's reference and earlier Go captures have populated
android02–07 mapped range caches, while this candidate capture has not. Raw SID,
offset and node reads do not clear those caches. This suggests existing
scheduling-sensitive cancellation, but source inspection alone does not prove
the candidate correct. The first capture overlapped other correctness checks;
predeclared sequential baseline and candidate cold controls investigate this.
Every control and failed comparison must remain visible; no repeat-until-green
policy or relaxed comparator is authorized by this observation.

Both predeclared sequential cold controls have now completed: unchanged baseline
and candidate each pass the original strict comparator for all 1,267 cases and
162,304 graph-state observations. Candidate warm prewarm and startup-prepared
then also complete with zero public/state differences. Their combined three-state
evidence covers 486,848 observations and preserves the original error and failed
formal-warm preparation. All runtime original fixture files remain unchanged.
The first failed cold comparison remains archived separately; these later results
do not establish schedule-invariant cache state under arbitrary contention.
`correctness-audit.json` binds the 2,535-file module across frozen replays and the
2,539 unchanged check inputs. `real64/` preserves all five runtimes, including
the failed comparison and the unchanged baseline control.

Three controlled main/Go pairs have completed from measurement commit
`acb065745f123d1dc362e24574628e40a05b4e91`. Builds and correctness checks terminate
before timing; all six timed runtimes run serially on separate audited clones.
Every runtime retains all 1,267 cases, with 1,266 successes and the original
failed case. The warm pair retains both original failing prewarm ledgers and is
explicitly a diagnostic continuation. The incremental source bundle preserves
the measured commit before the final evidence amend.

| State (one sample per case/runtime) | Previous Go success sum (s) | Candidate Go success sum (s) | Paired main success sum (s) | Candidate Go slower than main |
| --- | ---: | ---: | ---: | ---: |
| cold | 56.654 | 47.519 | 27.065 | 925 / 1266 |
| startup-prepared | 56.084 | 46.956 | 22.414 | 935 / 1266 |
| warm-after-failed-prewarm (diagnostic) | 50.492 | 41.551 | 19.279 | 1188 / 1266 |

The three wrapped six-term OR cases take 1.46–1.68 s in Go, compared with
3.07–3.38 s in the prior Go campaign; paired main takes 0.053–0.072 s. Unwrapped
six-term cases take 1.31–1.39 s, versus the prior 2.58–2.74 s. Every previous/new
case point remains in `timing/baseline-comparison.json`. These separate n=1
campaigns establish no per-case P95 or statistical causal estimate. All 3,801
state/case summary rows remain insufficient for acceptance.

Main's unchanged sampler records process CPU of 82.193, 66.503 and 38.702 s;
peak used heap of 6,353,716,376, 6,321,362,624 and 6,153,074,832 bytes;
and GC counts/times of 46/109 ms, 45/126 ms and 36/75 ms across those states.
Go still lacks equivalent formal sampled resource/work accounting. Separate
before/after diagnostic profiles complete only after formal timing terminates;
their clocks are not mixed with the paired observations. Both profiles execute
the full original workload with identical instrumentation, no added forced GC,
all canonical results verified, and all original files unchanged.

| Original case | Before TotalAlloc (bytes) | After TotalAlloc (bytes) | Before process CPU (s) | After process CPU (s) |
| --- | ---: | ---: | ---: | ---: |
| global-six-or-zero | 1,512,904,392 | 83,735,392 | 17.680708 | 6.022078 |
| global-six-or-targeted | 1,785,231,040 | 376,804,616 | 13.565079 | 6.676716 |
| global-six-or-dense | 1,711,444,576 | 294,379,144 | 14.214895 | 6.396731 |
| global-six-or-wrapped-zero | 1,632,995,480 | 163,166,408 | 18.012802 | 7.344588 |
| global-six-or-wrapped-targeted | 1,882,581,256 | 421,672,936 | 19.750954 | 11.768850 |
| global-six-or-wrapped-dense | 1,833,654,184 | 379,019,200 | 24.346378 | 7.535464 |

These exact process-counter brackets include concurrent runtime/profiler work;
they are single diagnostic observations. Sampled CPU mass disagrees strongly
with the Rusage brackets (for example, after zero has 73.16 sampled seconds but
6.022078 process CPU seconds). Profiler boundaries differ and waiting-runtime
frames dominate. The samples support neither precise CPU-share attribution nor
an operating-system/runtime-bug diagnosis. All raw samples, counter pairs and
limitations remain in `profile-before/` and `profile-after/`; sampled memory
deltas may lag GC and cannot replace TotalAlloc. Zero GC-stack samples do not
prove zero GC work.

Keep the raw SID route: it corrects nine observed public behaviors, preserves
the complete isolated real64 results/state, and all six relevant cases show
lower paired point clocks and lower independent diagnostic allocation/CPU
brackets. The first contended state failure and all remaining oracle gaps stay
visible. The complete 10x per-case P95 target, full server fidelity, resource/work
accounting, original formal warm and required benchmark gates remain unproven.

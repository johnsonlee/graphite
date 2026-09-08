# Native DISTINCT capability boundary correction

Baseline Go is `2e75f69e19ee4660a9187ac9af719bf7cd567c6a`; actual main is
`4e328b0109e13c896b74004823fb049fcb19251a`. This change corrects observable
behavior on graphs without CallSite nodes. It has no new performance claim.

Main distinguishes an available empty serial raw scan from an unavailable
structural index. With no CallSites, one source normally reaches unavailable;
2–39 sources use serial raw scanning and succeed; 40 sources use split storage,
whose structural fallback is unavailable. A regular persisted-index file makes
one-source DISTINCT select serial storage even if that file cannot be loaded.
A mapped-view request cannot become an available empty index.

Go previously returned an available empty index before deciding the serial
policy. `PrepareDistinctStringIndex` now checks zero count after the prepared
file-presence decision and returns either an empty raw scan or unavailable.
Existing retained indexes retain their precedence. The shared query preparation
helper propagates unavailable: initial projection throws main's exact
`IllegalStateException("Distinct projection capability became unavailable")`,
whereas provenance treats it as no raw hits and continues generic candidates.
Four ordinary candidate branches now tolerate a missing index and naturally
produce an empty CallSite sequence. Filtered count and explicit preparation
already handle zero count/unavailable and required no changes.

| Public oracle | Baseline | Candidate |
| --- | ---: | ---: |
| Primary capability matrix | 30/46 | 46/46 |
| Invalid prepared file / ordinary projection supplement | 6/8 | 8/8 |
| Original capability-absence observations from prior investigation | 7/13 | 13/13 |

All 67 observations retain complete ordered columns, rows, provenance, exact
error class and nullable message, and atomic failure responses. The final group
closes the six previously recorded capability mismatches; it is not deleted or
reclassified after the final matrix was introduced. The 46-case matrix covers
first versus later sources, 1/2/39/40 sources, LIMIT 0/1/2, SKIP 0/1, label and
unsupported-projection fallbacks, and a globally absent term. The eight-case
supplement checks invalid regular index files and ordinary generic continuation.
All small persisted fixtures are correctness-only inputs.

The test selects the same single or cross executor as the original Java helper.
An initial test-authoring mistake used Cross for every case and produced three
provenance differences. `checks/initial-harness-failure.json` retains them and
the correction; no engine change or expected-value rewrite was used to hide
those differences. The corrected baseline and candidate runs use the same tests,
with a Go overlay replacing all three changed production files by their exact
baseline bytes. Full module race tests and vet pass; 2,552 recorded inputs are
unchanged. `checks/evidence/` retains complete outputs and independent copies
and comparisons. A separate read-only source/test review found no missing
production caller within this boundary.

Both actual-JVM matrices were captured twice independently. Public outputs and
before/after index states repeat exactly. One primary case,
`first-neutral-call-only-40`, records workUnitsConsumed 3,089 versus 3,361;
all original diagnostics are retained. The supplement repeats all eight full
records exactly. These tests do not claim equivalent Go work diagnostics or
deterministic original worker accounting. Go still needs equivalent request
context, resource/work accounting, and full server fidelity.

The existing 201-case adversarial oracle has no compared public output, error,
or state changes from the previous candidate: 166/181 public cases and 19/20
scoped provider controls match each main reference. All 16 remaining differences
are retained, and its comparison still exits 1. `checks/matrix201/` preserves
complete results, exact compiled module and executable, and 3,036 unchanged
original fixture files with 22 named CallSite sidecars. Private provider controls
remain narrower than the Go wrapper and do not establish equivalent APIs.

| Full original real64 replay | Cases | Strict graph-state observations |
| --- | ---: | ---: |
| cold | 1,267 | 162,304 |
| warm prewarm | 1,267 | 162,240 |
| startup-prepared | 1,267 | 162,304 |

All public outcomes and state fields agree in these three serial captures. Each
runtime uses its own audited clone of the same 64 real graphs and verifies all
1,152 original files unchanged. All original testcase definitions and execution
order are preserved. The original case821 error remains, every original
all-success gate exits 1, and formal warm only reaches the failing prewarm
boundary. The combined comparison covers 486,848 observations. The prior
contended cold-state anomaly remains in its historical archive; isolated
agreement does not prove schedule-invariant speculative cache publication.

Keep this correction: the initial six capability gaps and 18 additional observed
matrix differences are resolved, required successes and failures remain intact,
and the existing bounded matrix and full real64 captures have no new compared
differences. `source-verification.json` binds the complete current module to the
module check inputs, frozen real64 source, and matrix source before its diagnostic
helper was added. No latency, CPU, heap, allocation or GC measurement was made
for this correction. Earlier n=1 timings belong to earlier engine revisions and
cannot establish this candidate's P95. Full server fidelity, equivalent request
work/resource accounting, required benchmark gates, original formal warm and the
10x per-case P95 objective remain open.

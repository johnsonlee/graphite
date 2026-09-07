# Attempt17: avoid locking a live context at projection checkpoints

Keep this single optimization. On all64 real graphs, prefix first execution
improves15.676783->1.754574 seconds and repeat11.311840->0.997778 seconds.
Repeat CPU drops52.879306->6.837113 seconds; allocation is virtually unchanged.
These are single instrumented observations against the preceding Go revision,
not P95 results or a10x comparison against main.

## Exact change and correctness

Base is b7bb15a2 (A16+B). The six-file patch271f166fc60570ee329ebbd8cb1b1786194328bb1bfc3c82c5d343316605905d
changes two production files, distinct_projection.go and projection_node.go.
At five existing cancellation sites, inspect the current context's Done without
blocking and call its original Err only when Done is closed. No Done channel is
cached. Closed Store checks, invalid-offset checks, the first decoder failure,
post-read validation, publication and lock ownership retain their ordering.

Two new test files cover standard contexts, real cancellation, deadline/cause,
first failure priority, preparation, Close and worker joining. Two existing
checkpoint observers now observe either Done or Err while canceling an actual
underlying context; they no longer rely on a manufactured Err-only context.
The candidate and the exact original production both pass the actual named
tests. Independent original-production execution verifies24 named PASS records,
plus a separate typed-SID-first-error check passes race10times.

The275-file author freeze and112-file independent A17-on-A16+B integration are
rehash-verified. Fullmodule race/vet pass on the exact2436 module inputs now in
root, with59 external references unchanged. The old1048 corpus stays1044/F4,
generic432 andB595 complete responses/traces pass. Of76 existing artifacts,
74 are byte-exact and two differ only in61 recorded40-source mappedView leaves;
all complete public responses match. B's166 numeric spelling differences remain.

The author's four retained-state differences are narrowly documented at rolling
case8,g5/g7,target0.after/target1.before. Four actual original-production repeats
and existing main captures establish reachable states; no identical scheduling
distribution is asserted. Independent integration observed no retained-state
differences. The first strict comparison failure, initial Close observer deadlock,
incorrect independent fixture filename and all raw evidence remain available.
The root's first filesystem collector included three ignored Python bytecode
files; the corrected Git tracked/nonignored inventory matches all2436 inputs.
Neither ignored caches nor the preexisting ignored executable were changed.

## Why this hypothesis

The frozen A15 mutex diagnostic retains134 root evidence files and25 helper
evidence files. Same-binary control and rate16 runs keep all five complete main
responses and source histories equal. Sampled prefix-repeat mutex delay estimates
24.99 aggregate seconds, including13.77 in context.cancelCtx.Err and11.22 in
unclassified runtime contention. ProjectionNodeOrder and primitive projection
reads call Err against shared wave contexts. Nested cumulative samples overlap;
mutex delay is not a CPU fraction, wall-time fraction, or predicted speedup.
Sampling itself changes repeat9.874752->10.736632 seconds. That diagnostic is
separate from the unsampled paired measurements below and from shipping code.

## Real persisted64 pair

Fixture /tmp/pr113-exp037-fixture.nXn4fg contains1152 files/10,338,207,518bytes,
19,431,891nodes and20,448,885edges. Both revisions run the identical fixed21-query
history against separate-process use of a COW fixture clone. Both exit0 after
Close, all42 complete typed responses equal the frozen pinned-main B HTTP oracle,
and all before/after source histories agree. Original/clone/source/binary hashes
remain unchanged. Queries and parameters, including the unknown graph_id field,
are preserved verbatim in config.json and comparison.json.

Environment: Go1.22.0 darwin/arm64,16 logical CPUs,64GB RAM. getrusage CPU and
allocation counters surround execute+marshal; no asynchronous CPU sampler.
Forced GC occurs outside request counters. All known agents paused heavy tests
and JVM/native execution during the pair; external host activity is uncontrolled.
These observations follow one fixed history, not independent cold measurements.
Commands and exact environment are retained by run-profile.py/run-results.json;
the complete-body/source verifier is verify-pair.py.

| Query | Base seconds | Candidate seconds | Base CPU seconds | Candidate CPU seconds | Base allocated bytes | Candidate allocated bytes |
|---|---:|---:|---:|---:|---:|---:|
| wrapped-firstLastGraphBimodalClassPrefix | 15.676783 | 1.754574 | 76.806707 | 13.262513 | 5966082120 | 5965804912 |
| wrapped-firstLastGraphBimodalClassPrefix-repeat | 11.311840 | 0.997778 | 52.879306 | 6.837113 | 444206968 | 443947144 |
| global-wide-wrapped-case-insensitive-distinct-dense | 0.318998 | 0.305078 | 2.135823 | 2.026589 | 268469336 | 268461440 |
| global-wide-wrapped-case-insensitive-distinct-dense-repeat | 0.300064 | 0.288996 | 1.972064 | 1.790075 | 268437768 | 268440360 |
| global-wide-distribution-broad-all-64 | 0.001845 | 0.001843 | 0.001885 | 0.001889 | 1635120 | 1637456 |
| c-necessary-ordinary | 0.002328 | 0.002467 | 0.002353 | 0.002502 | 2685464 | 2685096 |
| c-necessary-ordinary-repeat | 0.001659 | 0.001678 | 0.001683 | 0.001714 | 2258328 | 2258280 |
| c-necessary-distinct | 2.085637 | 0.207867 | 9.994055 | 1.537487 | 1192559128 | 1192604552 |
| c-necessary-distinct-repeat | 2.158655 | 0.177637 | 10.247792 | 1.297780 | 1172553872 | 1172599832 |
| d-unbounded-android | 0.074510 | 0.075234 | 0.074595 | 0.075220 | 79408704 | 79407600 |
| d-unbounded-kotlin | 0.043484 | 0.045542 | 0.043386 | 0.045614 | 47950064 | 47949808 |
| e-generic-ordinary | 0.000506 | 0.000511 | 0.000533 | 0.000550 | 687032 | 687608 |
| e-generic-distinct | 5.409688 | 5.415945 | 5.368749 | 5.410814 | 5479519272 | 5479522872 |
| b-ordinary-skip | 0.001089 | 0.001101 | 0.001126 | 0.001136 | 1033216 | 1032560 |
| b-distinct-skip | 1.482820 | 1.501467 | 1.481943 | 1.500260 | 1620066496 | 1620068728 |
| b-ordinary-order-parallel | 0.001449 | 0.001347 | 0.004666 | 0.004918 | 22600536 | 22598792 |
| b-ordinary-order-generic-serial | 0.148916 | 0.152519 | 0.148986 | 0.152564 | 203450288 | 203450496 |
| b-distinct-order-retained | 0.002572 | 0.002525 | 0.002614 | 0.002565 | 22295128 | 22295336 |
| b-distinct-order-eviction-candidate | 0.002638 | 0.002650 | 0.002680 | 0.002724 | 22334208 | 22334096 |
| b-order-duplicate-alias-ties | 0.001294 | 0.001394 | 0.004559 | 0.004553 | 22563088 | 22556352 |
| b-distinct-order-callee | 0.002673 | 0.002631 | 0.002711 | 0.002680 | 22388024 | 22388920 |

Necessary-condition DISTINCT also improves2.085637->0.207867 seconds on first
execution and2.158655->0.177637 on repeat. Generic DISTINCT remains5.409688->5.415945
seconds and about5.48GB allocated; that cost is a separate hypothesis. Slightly
slower controls are retained without a blanket no-regression claim. Base prefix
first records one GC/143333ns pause versus candidate zero; both repeats record
zero, so this does not establish a GC-pause improvement.

## Actual shipping HTTP verification

The helper-free binary SHA256 is
fa2d82ff1363038156c530335ae5f80fef8da98d3757321ab76c4e07c74f66fa.
All152 active compiler/embed inputs and2436 module inputs match the tested source.
http-shipping/launch.py runs21 history and42 original regression cases in two
fresh default60-second/capacity4 server processes. Independent verify.py checks
all63 complete typed main bodies, headers and64-graph catalogs. Both processes
exit143 after SIGTERM, no forced kill; both ports are released. Original and HTTP
clone1152 files have unchanged hashes and no extras after both exits.

## Decision and remaining scope

Keep the five cancellation fast checks as one optimization commit. No slot,
candidate source, edge consumer, registry, decoder or cache optimization is mixed
in. Full main-relative repeated paired P95 acceptance and the required
benchmark-regression-gate are still outstanding. The1044 historical passes are
not a complete product compatibility proof. Relationship/lazy-edge integration,
runtime ordering/budget/strategy and malformed topology boundaries remain open.

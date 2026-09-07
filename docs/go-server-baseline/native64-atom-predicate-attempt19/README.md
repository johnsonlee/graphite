# Attempt19: avoid temporary expressions for compiled string atoms

Keep one allocation optimization. Dense repeat on the real64 fixture records
0.315795->0.288127s, CPU2.110352->1.849766s, allocation268,440,984->170,203,144bytes.
This is a single instrumented pair against the preceding Go revision, not P95,
main-relative speedup, or proof that all queries improve.

## Change and exact source identity

Base is39eedb33, including A18. Patch
dfadcbdadfc32ff638bfcce71b0774e60cdf60241e09e710d2c1512cb60c0dfc
specializes CONTAINS,STARTS WITH,ENDS WITH in distinctAtomMatches without building
two Literal interfaces and a Binary expression for each string. Nonstring early
false and javaCase remain first; equals and other operators retain original
binary fallback. The existing six-op matching block moves to stringPredicate
byte-exactly except indentation and the parameter name. Both original literal
eval checks remain explicit before its original matching checkpoints. General
binary operand evaluation, null/type guards, NOT logic and UTF8/UTF16 matching
remain unchanged. No source, decoder, cache, key, map or casing optimization is
mixed in. Four changed files include the new matcher and one new test file.

The275-file author freeze,218-file independent review and24-file prior read-only
allocation audit are rehashed. All2439 root module inputs and59 external references
match the exact fully tested combination; shipping has153 active compiler/embed
inputs. The older audit sampled104,334,904bytes at atom expression construction,
38.93% of dense-repeat sampled allocation. That is not a CPU fraction or prediction.

## Correctness and bounded state differences

Original production plus final tests and candidate fullmodule race pass; candidate
vet and targeted race3 pass. The new27×27×6op×2lower matrix compares the exact old
helper and explicit UTF16 predicate definition; existing actual-JVM string oracles
remain unchanged. Short inputs enumerate all cancellation checkpoints. The long
ASCII case samples observed lower, literal and matcher boundaries with real
WithCancel, plus first checkpoint beyond completion. Initial exhaustive long-input
testing had quadratic cost and reached the180-second package timeout; that source,
partial outputs and failure are retained. No production had changed at that point.

Independent tests overlay only original eval.go while retaining the optimized
atom/new matcher, so the reference does not share the extracted implementation.
Three named tests/60 cancellation subcases pass there. Sixty-six independent
typed/null/right-error records match exact original production. This checks the
operand short-circuit and failure order as well as matching values.

Original1048 remains1044/F4; B595 complete responses and53 ordered traces pass,
including explicit trace-count comparison. The166 numeric spelling differences
remain listed. Author76 outputs are73 raw exact, three differ only in77 existing
40-source mappedView leaves. Independent76 outputs are73 exact, with71 mappedView
and two retained leaves: rollingcase8,g8,target0.after/target1.before. The first
strict failure is retained. Three bounded natural original39 runs all retain g8;
older main records show true/false/false. A separate exact-original test scheduling
hook delays task7/g8 until the original scheduler's LIMIT child cancellation, then
executes the unchanged task. All three full responses remain main-equal, the parent
stays live and watchdog never fires. This proves only allowed-schedule reachability
for those two coordinates, not equal natural scheduling distributions. No broad
retained-state mask or expected response rewrite was introduced.

## Real persisted64 measurement

All64 graphs from /tmp/pr113-exp037-fixture.nXn4fg are used:1152files and
10,338,207,518bytes. Both fresh Go processes execute the same21-query B history;
all42 complete typed bodies equal the frozen pinned-main HTTP oracle and all
before/after source states agree. Both close Stores and exit0. Original/clone
files, source and binary identities remain unchanged after completion.

Environment: Go1.22.0 darwin/arm64,16 logical CPUs,64GB RAM. Execute+marshal is
measured using wall time, getrusage CPU and allocation counters, without an async
CPU sampler. Forced GC is outside each request. All known agents confirmed heavy
processes terminal before the pair; external host activity is uncontrolled.
Subsequent queries inherit earlier history, not independent cold state. Exact
commands, environment and all raw counters remain in run-pair.py/run-profile.py,
run-results.json and per-query receipts.

| Query | Base seconds | Candidate seconds | Base CPU seconds | Candidate CPU seconds | Base allocated bytes | Candidate allocated bytes |
|---|---:|---:|---:|---:|---:|---:|
| wrapped-firstLastGraphBimodalClassPrefix | 1.581503 | 1.670474 | 12.750241 | 13.238413 | 5965813128 | 5940670248 |
| wrapped-firstLastGraphBimodalClassPrefix-repeat | 0.963427 | 0.976493 | 6.686479 | 6.735293 | 443938328 | 443943736 |
| global-wide-wrapped-case-insensitive-distinct-dense | 0.318904 | 0.306293 | 2.132667 | 2.027935 | 268471360 | 170248416 |
| global-wide-wrapped-case-insensitive-distinct-dense-repeat | 0.315795 | 0.288127 | 2.110352 | 1.849766 | 268440984 | 170203144 |
| global-wide-distribution-broad-all-64 | 0.001847 | 0.001630 | 0.001877 | 0.001653 | 1635840 | 1482784 |
| c-necessary-ordinary | 0.002312 | 0.002092 | 0.002342 | 0.002120 | 2685416 | 2416440 |
| c-necessary-ordinary-repeat | 0.001677 | 0.001653 | 0.001709 | 0.001687 | 2258616 | 2258200 |
| c-necessary-distinct | 0.214746 | 0.214472 | 1.536459 | 1.574918 | 1192607472 | 1178587080 |
| c-necessary-distinct-repeat | 0.175957 | 0.176020 | 1.295257 | 1.292378 | 1172598496 | 1172600368 |
| d-unbounded-android | 0.067146 | 0.067741 | 0.067181 | 0.067700 | 15021584 | 15021200 |
| d-unbounded-kotlin | 0.041148 | 0.042435 | 0.041208 | 0.042429 | 10026672 | 10027248 |
| e-generic-ordinary | 0.000503 | 0.000510 | 0.000534 | 0.000546 | 603336 | 603320 |
| e-generic-distinct | 4.998293 | 4.885424 | 4.934228 | 4.881990 | 2249485336 | 2249487240 |
| b-ordinary-skip | 0.001115 | 0.001108 | 0.001147 | 0.001142 | 1031424 | 1032352 |
| b-distinct-skip | 1.506106 | 1.517351 | 1.505360 | 1.516620 | 1620065160 | 1620070824 |
| b-ordinary-order-parallel | 0.001478 | 0.001437 | 0.004674 | 0.004595 | 22595784 | 22597496 |
| b-ordinary-order-generic-serial | 0.148257 | 0.152497 | 0.148328 | 0.152572 | 203449888 | 203451104 |
| b-distinct-order-retained | 0.002529 | 0.002576 | 0.002573 | 0.002606 | 22294088 | 22294552 |
| b-distinct-order-eviction-candidate | 0.002533 | 0.002875 | 0.002578 | 0.002898 | 22335856 | 22336384 |
| b-order-duplicate-alias-ties | 0.001435 | 0.001436 | 0.004503 | 0.004674 | 22563456 | 22553408 |
| b-distinct-order-callee | 0.002663 | 0.002698 | 0.002687 | 0.002727 | 22389624 | 22390552 |

Prefix first is slower1.581503->1.670474s, repeat0.963427->0.976493s; some B controls
also regress in this single observation. Generic E remains about4.9seconds/2.25GB.
No blanket no-regression conclusion is drawn. All42 requests report zero GC cycles
within their measurement windows; this is not a GC-pause improvement claim.

## Shipping command and decision

Helper-free binary SHA256:b9ce9c826918698af318c2841d1fa07bee3e8b0069f48dc0bad73afce6ae250d.
Two fresh default60-second/capacity4 server processes pass63 complete typed main
bodies, headers and64-graph catalogs. All153 active compiler/embed and2439 module
inputs match the tested/root source. Both exit143 after SIGTERM with no forced
kill, release their ports, and leave1152 original/HTTP clone file hashes unchanged
without extras. http-shipping contains commands and independent verification.

Keep the measured dense allocation/CPU reduction as one optimization commit.
The full repeated main-relative P95 acceptance and benchmark-regression-gate
remain outstanding, as do broader functional/source/runtime boundaries. This
attempt does not prove100% compatibility or a10x main-relative improvement.

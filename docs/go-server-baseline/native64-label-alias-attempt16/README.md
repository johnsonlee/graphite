# Attempt16: fixed label aliases without constructing a map

Keep this single measured optimization. The generic DISTINCT query improves
6.516011->5.349303 seconds in this real64 pair, with allocated bytes
8,392,826,728->5,479,520,392 (34.71% less). CPU6.437094->5.347743 seconds.
Both requests recorded zero GC cycles and zero GC pause time; this is not a GC
pause improvement. Prefix repeat remains9.952376->9.991023 seconds. These are
single instrumented observations, not P95 or a speed comparison against main.

## Exact change and verification

Native base is committed B `4e94017f`. Only properties.go changes in production:
replace the fixed nine-entry label aliases map with an equivalent switch.
strings.ToLower, Node/Constant precedence, exact alias target equality and final
EqualFold fallback remain in the same order. No candidate boxing, node decoding,
source selection, required consumption, WHERE/projection, task/cancel/cache or
lifetime behavior changes. The new file label_alias_test.go covers boundaries.
Patch SHA256:
b86c4e149afe22e083c06725475ffa0b383dc73570c037169afe3788e8dc4ee3.

The author worked on2ab90cdc; its157 frozen evidence files and the independent
102-file A16-on-B integration were rehashed. The56-file previous E allocation
audit identifies the removed operation, not a separate candidate measurement.
Root2434 module/testdata inputs exactly match the fully tested and helper-free
HTTP candidate. All2433 base inputs match committed B. Full external Kotlin
OpenAPI reference inputs and CONVENTIONS match the integration archive as well.

Whole-module race/vet pass. Tests compare4,275 Unicode/invalid-UTF8/case/kind
boundaries against the exact original helper body, plus nine aliases across21
kinds and three spellings. Existing actual-main26-case Constant membership and
all-node-kind tests remain unchanged. These tests preserve the old helper policy;
they do not claim to repair unrelated Unicode compatibility differences.

The original1048 suite stays1044 equal/F4 retained; generic432 stays equal and
B595 complete responses/access traces pass. All76 prior output artifacts preserve
every public response:74 raw exact,2 differ only in51 existing speculative
mappedView before/after leaves. All raw states are retained. B's166 numeric
spelling distinctions remain explicit; JSON-value equality is not byte equality.
The source of the initial helper was independently checked against actual B
production, preventing a changed reference from making the switch pass.

## Real persisted64 pair

The same fixed21-query history uses all64 actual graphs from
/tmp/pr113-exp037-fixture.nXn4fg:1152 files/10,338,207,518 bytes. Main full HTTP
responses are the unchanged freshly captured B oracle; no new main performance
measurement is implied here. Both fresh Go processes return all21 complete typed
responses and every before/after source history agrees. Both exit0 after closing
Stores. Original and clone source/graph/binary identities remain fixed.

Go1.22.0 darwin/arm64,16 logical CPUs,64GB host. No CPU asynchronous sampler;
getrusage CPU and allocation counters are measured around execute+marshal.
Forced GC is outside the request counters. All three known agents paused heavy
tests/JVM/native processes for the pair; external host activity is not controlled.
This remains a single sequential instrumented same-history observation, and
queries after prefix initialization are not independent cold measurements.

| Query | Base seconds | Candidate seconds | Base CPU seconds | Candidate CPU seconds | Base allocated bytes | Candidate allocated bytes |
|---|---:|---:|---:|---:|---:|---:|
| wrapped-firstLastGraphBimodalClassPrefix | 13.657089 | 13.488717 | 66.433309 | 64.683189 | 5966116200 | 5966109760 |
| wrapped-firstLastGraphBimodalClassPrefix-repeat | 9.952376 | 9.991023 | 46.127613 | 46.378996 | 444262208 | 444249088 |
| global-wide-wrapped-case-insensitive-distinct-dense | 0.293817 | 0.316240 | 1.933906 | 2.114655 | 268467112 | 268465160 |
| global-wide-wrapped-case-insensitive-distinct-dense-repeat | 0.304520 | 0.316505 | 2.039316 | 2.127692 | 268437848 | 268436944 |
| global-wide-distribution-broad-all-64 | 0.001824 | 0.001892 | 0.001852 | 0.001898 | 1636576 | 1636160 |
| c-necessary-ordinary | 0.002341 | 0.002365 | 0.002389 | 0.002397 | 2698776 | 2685816 |
| c-necessary-ordinary-repeat | 0.001686 | 0.001662 | 0.001719 | 0.001702 | 2272472 | 2257992 |
| c-necessary-distinct | 2.127801 | 2.105143 | 10.167533 | 10.096575 | 1192752640 | 1192559888 |
| c-necessary-distinct-repeat | 2.112207 | 2.087697 | 10.050302 | 9.982085 | 1172735888 | 1172548080 |
| d-unbounded-android | 0.097497 | 0.077069 | 0.097540 | 0.077127 | 137483136 | 79408752 |
| d-unbounded-kotlin | 0.055697 | 0.043519 | 0.055776 | 0.043563 | 82170112 | 47950416 |
| e-generic-ordinary | 0.000553 | 0.000538 | 0.000581 | 0.000576 | 779720 | 686248 |
| e-generic-distinct | 6.516011 | 5.349303 | 6.437094 | 5.347743 | 8392826728 | 5479520392 |
| b-ordinary-skip | 0.001155 | 0.001096 | 0.001443 | 0.001125 | 1042368 | 1032432 |
| b-distinct-skip | 1.485775 | 1.477847 | 1.484781 | 1.476293 | 1620258728 | 1620072328 |
| b-ordinary-order-parallel | 0.001491 | 0.001448 | 0.004832 | 0.004945 | 22796744 | 22598904 |
| b-ordinary-order-generic-serial | 0.172548 | 0.149570 | 0.172432 | 0.149524 | 261532688 | 203451424 |
| b-distinct-order-retained | 0.002604 | 0.002536 | 0.002644 | 0.002568 | 22488488 | 22294536 |
| b-distinct-order-eviction-candidate | 0.002653 | 0.002508 | 0.002697 | 0.002550 | 22527328 | 22336416 |
| b-order-duplicate-alias-ties | 0.001345 | 0.001414 | 0.004624 | 0.004589 | 22753072 | 22561024 |
| b-distinct-order-callee | 0.002817 | 0.002855 | 0.002867 | 0.002913 | 22579944 | 22388168 |

The unbounded generic routes also improve: Android0.097497->0.077069s and
Kotlin0.055697->0.043519s, with allocation137.483->79.409MB and82.170->47.950MB.
The routed B generic ORDER improves0.172548->0.149570s. Other controls, including
some dense timings, are slightly slower; all values are retained without a
blanket no-regression claim. The generic DISTINCT path still costs substantially
more than the older semantically incomplete1.929-second implementation. This
patch preserves the required full-node consumption, rather than restoring that
old behavior. Remaining qualified-node boxing and cancellation checks are
separate hypotheses, not silently mixed into this attempt.

## Actual HTTP command

The helper-free binary uses152 active compiler/embed inputs and2434 total module
inputs. SHA256:
e5726736d5f392ed88a05aaa30c07d4ce73e12fff7ed458edb8dea1faf4957b7.
Two fresh processes pass63/63 complete typed main bodies, headers and64-graph
catalogs (the21-query history and42 original regressions). Default timeout60s and
admission capacity4 remain. Both exit143 after SIGTERM without forced kill;
independent plain binds verify released ports. Source/binary inputs and all1152
original/HTTP-clone graph files are unchanged after both exits, without extras.

## Decision and limits

Keep one label-map optimization in its own commit. The attempt does not establish
P95, peak RSS, lower GC pauses, global100% compatibility or10x speed relative to
main. Full repeated paired acceptance and the required benchmark-regression-gate
remain open. The existing source/row-order/runtime gaps stay visible.

# Attempt 137: real 36-query paired diagnostic

Three alternating fresh-JVM pairs; indexes reset before each query. JIT/OS cache are not reset. These are individual observations, not per-query P95; this diagnostic does not replace the original CI numeric gate. All 216 full output/order/provenance observations pass the independent oracle. Inputs and JAR hashes are unchanged.

| Query | Pair 1 main → candidate (ms) | Pair 2 | Pair 3 |
|---|---:|---:|---:|
| or-single-early-rows | 90.172375 → 74.092625 | 103.908250 → 79.709000 | 100.734416 → 87.634334 |
| or-single-early-distinct | 281.540625 → 159.784917 | 259.270458 → 155.418334 | 293.242375 → 141.938708 |
| and-single-early-rows | 37.771500 → 33.385541 | 39.773750 → 34.700167 | 39.818959 → 34.196417 |
| and-single-early-distinct | 162.521166 → 112.287250 | 193.517000 → 108.321083 | 150.148084 → 109.202417 |
| or-single-middle-rows | 111.097458 → 84.584792 | 104.080875 → 82.284875 | 100.824417 → 83.750375 |
| or-single-middle-distinct | 149.631875 → 119.061791 | 152.603708 → 117.267875 | 152.733250 → 117.199125 |
| and-single-middle-rows | 93.669459 → 55.364833 | 78.130750 → 66.151041 | 93.479875 → 67.235917 |
| and-single-middle-distinct | 150.575583 → 109.211250 | 137.599791 → 108.013083 | 153.515458 → 107.723417 |
| or-single-late-rows | 150.250959 → 104.365292 | 133.399958 → 104.330167 | 147.827833 → 103.590500 |
| or-single-late-distinct | 146.184750 → 102.026792 | 129.145708 → 99.122000 | 145.348709 → 104.006375 |
| and-single-late-rows | 147.276500 → 99.645750 | 130.886542 → 99.123958 | 147.144750 → 100.909458 |
| and-single-late-distinct | 149.215958 → 103.446083 | 136.638250 → 105.381667 | 136.092208 → 105.279166 |
| or-few-early-late-rows | 16.528167 → 10.956250 | 13.404875 → 12.236750 | 14.808375 → 11.439792 |
| or-few-early-late-distinct | 145.657666 → 104.550459 | 140.600375 → 98.155833 | 148.594667 → 104.337750 |
| or-few-early-middle-rows | 14.629750 → 10.772708 | 10.841375 → 11.218416 | 14.398292 → 12.207709 |
| or-few-early-middle-distinct | 147.634792 → 103.480833 | 133.703417 → 103.412417 | 148.129333 → 103.921125 |
| or-broad-all-rows | 5.245959 → 4.878292 | 4.570417 → 5.663708 | 5.154916 → 5.495000 |
| or-broad-all-distinct | 184.821500 → 144.305042 | 175.366959 → 141.939625 | 190.062125 → 142.145417 |
| and-broad-all-rows | 20.214292 → 15.826542 | 18.086125 → 17.150000 | 19.306000 → 16.146042 |
| and-broad-all-distinct | 990.739833 → 1005.690625 | 975.796417 → 995.122792 | 975.570167 → 987.786625 |
| mixed-four-few-rows | 559.513333 → 287.695041 | 754.853875 → 302.262625 | 753.124083 → 618.124167 |
| mixed-four-few-distinct | 38794.365542 → 39131.338958 | 39057.821000 → 38807.373916 | 38000.350417 → 39289.910333 |
| and-zero-disjoint-graphs-rows | 145.838625 → 101.879000 | 143.149125 → 104.538917 | 145.576125 → 102.934750 |
| and-zero-disjoint-graphs-distinct | 147.455833 → 102.233292 | 145.620375 → 104.980000 | 147.350167 → 92.235000 |
| or-four-broad-rows | 36.211833 → 31.664250 | 35.188750 → 32.375458 | 33.895541 → 33.606834 |
| or-four-broad-distinct | 150.348959 → 110.617500 | 150.440625 → 108.686250 | 148.464666 → 108.552125 |
| or-four-single-early-rows | 16.635291 → 13.157500 | 15.193625 → 12.260959 | 13.770250 → 10.842041 |
| or-four-single-early-distinct | 150.636167 → 107.259000 | 147.706500 → 105.220958 | 151.335709 → 108.240208 |
| or-four-single-middle-rows | 92.030708 → 67.710541 | 91.278875 → 64.618291 | 93.942167 → 64.911625 |
| or-four-single-middle-distinct | 142.849917 → 100.693875 | 145.811375 → 105.054708 | 146.085292 → 105.841500 |
| or-four-single-late-rows | 149.902000 → 104.911792 | 149.991708 → 105.368083 | 147.816792 → 103.949041 |
| or-four-single-late-distinct | 146.021333 → 107.122208 | 146.671333 → 105.041458 | 147.453667 → 105.215416 |
| or-four-few-early-late-rows | 14.628500 → 11.866667 | 16.868167 → 11.518875 | 13.774834 → 10.161875 |
| or-four-few-early-late-distinct | 149.107208 → 105.174791 | 147.826750 → 104.959834 | 148.020916 → 104.259792 |
| or-four-all-rows | 14.694958 → 11.140417 | 15.879042 → 12.649416 | 11.771583 → 12.676041 |
| or-four-all-distinct | 181.821084 → 135.619667 | 183.114583 → 135.706958 | 175.411958 → 133.763666 |

No query exceeds the original aligned-row scale (>15% and >1 ms) in two pairs. One pair does: pair 2 `or-broad-all-rows`, 4.570417 → 5.663708 ms. This is a diagnostic comparison, not a new acceptance gate. Pure four-OR has one smaller slowdown: pair 3 `or-four-all-rows`, 11.771583 → 12.676041 ms. Do not claim every row improves.

Candidate was subsequently rejected and reverted after repeated Method4 count/middle CPU failures in exact-head CI. The 10x goal is unmet and CallSite pools remain. Raw output rows and complete graph-file receipts remain under the recorded temporary artifact root; this report, six run summaries, TSVs, exact commands and independent audit are persisted here. Text copies normalize trailing newlines only; numeric values are unchanged.

Unlike old34, v3 work counters are not invariant: mixed-four-few rows are
227190→100227, 304905→105233, 304631→276591; pair 3 pure four-OR middle rows
are 31119327→31185346. Source semantics are unchanged, but these observed
scheduling/work differences must not be described as unchanged measurements.
`and-broad-all-distinct` is slightly slower in all three pairs, and
`mixed-four-few-distinct` is slower in two pairs. Both are below the 15% scale;
the full table retains them without attributing the difference to SAM callbacks.

[Independent v3 audit](../independent-v3-audit.md) verifies all 216 queries and 37,026 returned rows against the catalog, including values, order and provenance.

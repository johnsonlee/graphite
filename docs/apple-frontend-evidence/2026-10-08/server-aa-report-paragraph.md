A separate A/A diagnostic ran six fresh instances of the same candidate native CLI
(SHA256 `b089844defbf59bbad8670f156199f1fed07f63dd19321966d467b933b4cefa6`,
built at `77ec`; native Rust/Cargo sources are unchanged through `357a6206`). It
completed all 4,800 measured requests and 120 warmups with identical result digests
and graph fingerprints; the 15 initial connection-refused readiness attempts were
retained, with no unexpected failures. Independent nearest-rank recalculation matches
the harness summary. Across its six processes, the slow join's p95 ranged from
665.642–781.404 ms at concurrency 1 and 721.693–767.152 ms at concurrency 4; the two
label pools were 750.525/762.664 ms and 742.222/746.547 ms, respectively. Corrected
100-request server CPU ranged from 64.847–72.650 s and 68.923–71.918 s. Competing
unrelated benchmark activity was observed during this A/A, so it is not a quiet-host
control. These descriptive spreads are not uncertainty bounds for the original A/B,
and the observations do not establish contention during that earlier run. The A/A
neither proves main/candidate equivalence nor replaces the original +39.257 ms and
+62.422 ms p95 regressions or failed acceptance; isolating their cause requires a
separately declared comparison with host activity accounted for.

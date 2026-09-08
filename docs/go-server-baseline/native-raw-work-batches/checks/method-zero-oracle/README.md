# Method LIMIT 0 admission and diagnostics

This independent correctness oracle contains 16 public scenarios and 16 ordered execute operations. Both expanded captures invoke actual pinned-main `4e328b0109e13c896b74004823fb049fcb19251a` with the original JVM flags and match in every captured field. The initial ten-scenario capture is retained separately; it is not substituted for the expanded repeat.

`all-cases.json` is the final 16-case input. `cases.json` and `run.py` retain the initial ten-case input and runner; `run-expanded.py` explicitly selects the final input. `main.json`, `main-capture/main.json`, and `repeat-capture/main.json` use the original raw-work public schema: `spec.sources[{fixture,graphId}]`, decimal-string budget, construction, ordered operation results/errors and before/after diagnostics, remaining work, cancellation and storage observations.

The fixture is the immutable `local-first` variant written by actual main in the parent raw-work capture. The original 329-file source archive SHA-256 is `31a7d70c9833dcf84322d541744ae007f1b5b182920eac26b45ede372283092a`. The local `fixtures.tar.gz` contains only this variant: **16 regular files**, identified individually in `fixture-variants.json`. Each expanded capture copies it for 16 cases, giving 256 unchanged persisted files. This fixture has 1,025 LocalVariable nodes and no Method nodes. The scope is LIMIT 0 admission and diagnostic routing; it does not establish nonempty Method result behavior. The harness performs no graph preparation beyond the actual mapped load and does not fabricate Method nodes or counters.

All 16 queries return an empty successful result and consume zero work. Actual diagnostics distinguish their execution paths:

| Cases | fast | filtered | fallback |
| --- | ---: | ---: | ---: |
| Method WHERE true, no WHERE, ORDER alias, SKIP 0, SKIP 1, name CONTAINS, inline name, inline parameter_types | 1 | 0 | 0 |
| Method with an unused nonempty parameter map, parameter SKIP, relationship CALLS, inline bogus property, invalid signature | 0 | 0 | 1 |
| LocalVariable WHERE true | 1 | 1 | 0 |
| LocalVariable WHERE true plus ORDER alias or SKIP 0 | 1 | 0 | 0 |

The parameter SKIP control supplies `s=1`, so it also has nonempty parameters; these cases do not independently attribute its fallback to expression shape versus parameter admission. The other graph-ID diagnostic counters remain zero. Full outputs retain all eight original diagnostics and all storage observations, and the nonempty-parameter control preserves the actual input even though it is unused in the query.

The narrow conclusion is that an empty filtered query cannot be classified solely by the presence of WHERE. The Method-specific admission runs earlier and can either handle the empty result or decline it. These are synthetic correctness controls, with no latency, throughput or P95 measurements.

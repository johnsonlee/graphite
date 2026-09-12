
### 2026-09-06 - Attempt 146: Skip empty child cleanup without changing task lifetime

**Same shared scheduling direction:** one line returns the original failure after
closing child registration under the existing monitor when no child remains.
Nonempty cleanup, actual task exit, caps/lanes and notifications are unchanged.
Parent is145 `cf300347247174a74bbbd188e89c36c094ae2bdd`; at this local snapshot the
candidate is uncommitted/unpushed. A later experiment commit must record its own SHA.

315 build inputs,55 saved XML suites/2107 tests pass; all four test tasks execute,
core lint executes and three other lints use cache. Both JMH packages bind683/958
compiled main classes. Complete v3 control validates36 queries/6171 ordered rows;
real Method control completes33 combinations over four repeated corpora, retaining
root-order normalization and extra-ID limitations. These are correctness controls,
not unpaired performance claims or synthetic-fixture performance evidence.

| Original34 pair | Base P95 ms | Candidate P95 ms | Strict improvement | CPU delta |
|---|---:|---:|---|---:|
|1 C/B|57.550125|42.817666|yes|-3.915%|
|2 B/C|62.780833|47.492458|yes|-1.743%|
|3 C/B|51.742417|52.154333|no|+6.676%|

All204 complete oracle signatures and102 paired non-time rows agree. Each pair
retains graph/segment peaks2/2 and resource ceilings; three queries breach the
latency double threshold only once, with none repeated. The unchanged driver stops
on pair3 strict failure, leaving strictProgressEveryPair=false. Its normal exit0
is not acceptance. Root then runs the original full comparator on the same six
recordings, with no new measurement: regression-only passes, but targetAchieved
is false with9 target errors and10x remains unmet.

**Separate next decision:** one independent exact-head CI is planned for this same
candidate; not an automatic continuation of the driver's no-CI stop branch, not a
local strict pass, and not acceptance. No new local fork or retry-to-green. No CI
has run at this snapshot. [Local evidence and boundaries](profiling/attempt146/README.md).

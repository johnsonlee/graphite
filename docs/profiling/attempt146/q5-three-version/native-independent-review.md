# Existing native q5 extraction: independent review

The extraction is correctly limited to three **historical official-PR production lineages**. The executed JAR was boundary-instrumented `f5f65197…`, built from official `04ae90ec…`; its receipt records only the pressure benchmark modified and QueryBoundaryRecorder added. These are not unmodified official JAR observations, starting-main observations, or current helper177d observations. Full raw command/PID and actual settings bind native wall4.3, monotonic1GHz ticks, 1ms/depth128, plus LogCompilation and boundary instrumentation.

The historical q5 exact14 fields match the corresponding current official observations. Its timings remain historical 5.547/5.677/5.494ms and must not replace the current uninstrumented three-version timings. The one-based ordinal is5 and the query identity/digest match.

Independent arithmetic, without importing the extraction's interval helper, reproduced every selected sample, thread/state/first-Java counter and deduplicated inclusive method count from the small retained q5 sample files:

| Historical run | Interior samples at0/1/2ms | Edge samples at0/1/2ms | Any-Graphite-frame samples at0/1/2ms |
|---|---|---|---|
| base1 | 210 /132 /54 | 0 /156 /312 | 41 /27 /13 |
| base2 | 215 /141 /63 | 0 /148 /299 | 37 /23 /9 |
| base3 | 207 /133 /58 | 0 /148 /301 | 35 /21 /7 |

All event ordinals are unique within an extract; positive actual OS IDs are retained. The extraction includes all threads within the ±2ms outer window. For each band, interior is `[start+band,finish−band]` and edge is the expanded window minus interior. The 0/1/2ms bands are operational sensitivity intervals, not bounds on clock error; 0ms is not an ambiguity-free classification.

Actual1ms interior stacks contain mapped-view `exactMatchingStringIds` in5/1/3 samples respectively, `trigramPostingRange` in4/1/1, and run2 has one `matchingNodeIds`/`validatedPostingCursor` sample. This corroborates the historical q5 mapped lookup route. Counts include callers and overlap; they do not measure guard invocation counts, CPU cost or current helper behavior.

The result field `uniqueApplicationSamples` is broader than query work: it means any Java frame in `io.johnsonlee.graphite`, including scheduler waits, JMH/query-owner frames and the resource sampler. Resource-sampler stacks alone contribute4/4/3 samples in the1ms interiors. Inclusive scheduler frames cannot be called scheduler CPU. Method counters collapse overloads by class+method; full retained frames still carry descriptors/type/positions. Default JIT labels are not C2 assertions. Native GC-thread samples do not establish GC pause intervals.

The old completed analysis report hashes, exact actual JAR lineage/PID, settings, original/full14 TSV bindings and all small extracts were independently read. Large original sample JSONL hashes are bound to the extractor's complete streaming/hash validation and the old frozen receipt; this quick audit did not reread their full payload or raw JFR. No claimed extraction coverage exceeds those limits.

No measurement input or extraction file was changed. The first independent reviewer script had a missing closing parenthesis before it could read evidence; the source/correction receipt are retained and the corrected script passed. No JVM or profiler was run.

# Read-only allocation audit after Attempt17

No new application process, synthetic benchmark, or production change was run.
Five go tool pprof commands inspect the existing real64 A17 candidate profiles.
All2439 measured module inputs (2436 shipping inputs plus3 profiling helpers)
and the measured binary are rehashed. Raw profiles, receipts, commands and
relevant source copies are retained. An initial guessed distinct_string.go read
failed; rg located actual indexed_distinct.go and distinct_string_id.go.

Dense repeat sampled alloc_space delta is268,032,232bytes. distinctAtomMatches
line52 accounts for104,334,904bytes (38.93%) flat, constructing cypher.Binary with
two boxed string Literal operands before e.binary. javaCase's Builder.grow adds
49,285,700bytes (18.39%); these are separate operations/hypotheses. Their cumulative
153,620,604bytes must not be added again to the flat contributions. The query's
actual counters report268,440,360 allocated bytes,0.288996s wall/1.790075s CPU.
Sampling estimates do not establish CPU proportions or speedup predictions.

A possible next bounded hypothesis is removal of per-value literal-AST boxing
for compiled string atoms, reusing the exact evaluator string semantics. No
attempt number is assigned and no candidate exists in this audit. Requirements:
keep nonstring early false and optional javaCase invocation unchanged; compiler
admission only=,CONTAINS,STARTS WITH,ENDS WITH; preserve both original e.eval
literal cancellation checkpoints and existing string matcher checks; preserve
Java UTF16/WTF8 equality/unit matching and lowercasing behavior. Any shared-helper
extraction must preserve all ordinary binary operations including NOT variants,
null/type short-circuit and regex behavior. Unsupported atom operators should
retain original fallback. Preserve source preparation, cache events, full-node
read/consumption, provenance, ordering and exception priority. Verify against the
exact original helper and full76/original1044/B595/166 spelling history, real
cancellation and actual main string oracles before any real64 measurement.

Prefix repeat has a different allocation distribution:440,274,637sampled bytes,
157,295,696 in projection decoder list construction,122,160,968 NodeProperty and
58,197,300 genericDistinctRows.find. These are separate unassigned hypotheses.
No faster-case, GC, RSS, P95 or main-relative performance conclusion follows.

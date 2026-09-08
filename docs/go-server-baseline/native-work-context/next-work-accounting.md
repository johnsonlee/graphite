# Next integration: storage work consumers

This is a read-only source audit against pinned main `4e328b0`, not execution or
performance evidence. Start with serial raw string scans, then integrate retained
and mapped indexes. Do not attach counters to unrelated cancellation polls.

Main's `MappedWebGraphBackedGraph.BufferedGraphWorkConsumer` accumulates one unit
at a time, flushes at 1,024, clears pending **before** calling the delegate, and
does not call the tracker for an empty batch. Raw scanning increments before
reading fields, flushes before a successful yield, and flushes again in finally.
Consequently a final flush failure can replace a decoder failure. Each parallel
range owns its buffer and must flush before worker failure selection and join.

Go integration points include `query/main_string_source.go` raw iteration,
`query/main_string_candidates.go` serial raw candidates and
`query/ordinary_parallel.go` range scanning. A store-owned neutral
`func(int64) error` consumer can bridge into the request tracker without an import
cycle. Preserve the original budget exception across `failProjectionRead`;
today a budget error arriving there through a store callback would fall through
`failNodeRead` and lose its class. Cancellation exceptions already have their
separate recognizable path. No production change for this next step is included
in the current follow-up.

Index consumers require algorithm review before wiring:

- Main retained range selection counts binary/gallop steps and trigram
  intersection work. Go currently scans directories or selects the shortest
  anchor and fully checks candidates; these are different work sequences.
- Main retained posting merge charges unique IDs; mapped-view merge charges
  every pop, including duplicates. Mapped posting-order validation charges cold
  verification and skips that work after validation is cached.
- Node/row cache hits charge at least one unit; matching-string cache hits do
  not necessarily charge the same amount. Header/content validation and index
  initialization use other byte/int block units, not a universal node batch.

The next actual-JVM oracle should cover 1,023/1,024/1,025 inspected candidates,
zero hits and boundary hits, insufficient/exact budgets, pending work during
decode failure and cancellation, and repeated cache-hit executions. Retained
versus mapped duplicate postings and cold/warm verification need separate
controls. Only after serial accounting is established should worker flush and
failure-selection controls expand to parallel and 64-source paths, retaining
any actual scheduling differences.

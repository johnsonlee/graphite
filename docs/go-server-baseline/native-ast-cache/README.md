# Original-main parser cache contract

This is correctness evidence from actual main revision
`4e328b0109e13c896b74004823fb049fcb19251a`. Three JVM runs capture35 events each:
two at `-Xmx512m` and one at `-Xmx8g`. The two512m captures are exactly equal,
including complete AST shapes, errors, LRU order and retained-byte state. The8g
run proves the16MiB upper bound. These are not performance measurements.

Reproduce with new external output directories:

```sh
python3 docs/go-server-baseline/native-ast-cache/run.py /tmp/ast-cache-fresh-a
python3 docs/go-server-baseline/native-ast-cache/run.py /tmp/ast-cache-fresh-b
python3 docs/go-server-baseline/native-ast-cache/run.py /tmp/ast-cache-fresh-8g --heap 8g
python3 docs/go-server-baseline/native-ast-cache/verify.py
```

The last command verifies the committed captures and original input hashes.
Each runtime receipt freezes the actual main JAR, relevant embedded class bytes,
main parser/AST/freezer source and existing main tests, helper source, commands,
compiler/engine exits, compiled helper class and fixtures. The executable JAR's
SHA256 is `91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`.
No Go code is changed by this tool.

Observed behavior:

- The cache key is the exact raw query string. Leading/trailing whitespace and a
  trailing semicolon produce separate entries. A hit returns the same immutable
  outer-list instance. Blank strings, including NBSP, return an empty AST without
  creating or touching an entry. Syntax and numeric-literal conversion errors
  are not cached; exact public exception classes/messages are retained.
- Semicolon splitting uses raw `split(';')`, trims parts and recursively parses
  them when there is more than one nonempty part. Successful subqueries and the
  raw parent are separate entries. A later subquery failure preserves earlier
  successful child entries but does not cache the failing child or parent. This
  also exposes existing naive splitting behavior for semicolons inside quoted
  literals; the error is preserved rather than corrected by the oracle.
- The cache is an access-order LRU, limited to1024 entries and a retained-byte
  estimate of `128 + 2 * rawJavaUtf16Length + 2048 * topLevelClauseCount`. Its byte
  budget is `min(16 MiB, Runtime.maxMemory()/128)`. The actual512m budget is4MiB;
  actual8g budget is16MiB. Hitting entries0 and500 promotes them, and insertion
  of1024 evicts1. Byte pressure independently evicts before1024 entries. An
  exactly-budget-sized entry is cached; one extra UTF-16 character is not cached
  and does not evict the existing entry. Repeated oversized parses have equal AST
  values and different outer-list identities.
- `MATCH ... WHERE` emits separate Match and Where clauses; OPTIONAL MATCH's
  WHERE remains embedded in Match. WITH's WHERE is embedded in With. ORDER BY,
  SKIP and LIMIT are separate clauses. UNION and UNION ALL each contribute a
  Union clause with the appropriate `all` flag. The full clause counts and
  nested ASTs, including an invalid WITH/WHERE ordering control, are captured.
- Parsed AST collections reject26 attempted nested list/map/map-entry mutations.
  The original `toImmutableCypherAst` literal-freezing helper rejects another5
  mutations and isolates its result from subsequent changes to the original
  mutable map/list. Every rejection is `UnsupportedOperationException`, and the
  AST remains unchanged.
- Four public executions of `RETURN $value AS x`, using two distinct mapped graph
  objects and parameter values7/8, produce the corresponding values while
  sharing the exact same cached AST identity. Parser cache ownership is process
  wide and keyed only by query; parameters and graph objects are not keys.

The sole source fixture is the existing original-main-produced `all-types`
fixture. Every run copies its10 files into two fresh physical directories,
checks their bytes before/after, and records the four nodeoffset/typeindex files
created by original main. No source or copied original file changes. Fixture
pre/post manifests and receipts are compressed separately for each run.

`main.json` is the stable Go-comparison input. `before`/`after` snapshots preserve
exact LRU order, raw keys for ordinary queries, Java UTF-16 length, SHA256 of raw
UTF-8 query bytes, entry retainedBytes, total bytes and cap. Deliberately large
queries use a deterministic recipe plus exact length/hash to avoid repeating
megabytes of padding in evidence. `repeat-main.json.gz` is the second512m run;
`main-8g.json.gz` is the8g run. `initial33.tar.gz` preserves the first exploratory
capture, its exact sources, helper class and receipt before two final boundary
controls were added. Its invalid WITH query remains in the final corpus as an
explicit syntax-error control.

## Go public API adaptation

Returning an independent deep clone from Go's public Parse preserves AST values
and query-execution semantics, and prevents callers from corrupting the internal
cached representation. The clone must include all nested slices, maps, literals,
patterns and expression children; sharing any mutable child would defeat this
property. Parameters must remain runtime values rather than be substituted into
the cached AST.

This is an explicit API adaptation: Java cache hits preserve list identity and
reject mutations; Go callers receive separate mutable values. Do not claim
reference-identity or mutation-exception equivalence. Java's implementation can
also return separately built immutable values for concurrent cold misses: it
parses outside its cache lock and avoids overwriting an already-installed entry.
The oracle establishes ordinary cache-hit identity, not single-flight parsing.
For engine behavior, value-preserving deep clones are appropriate; their cost
must be evaluated later on the real64 workload.

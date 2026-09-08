# Actual pinned-main bounded string matcher oracle

`python3 run.py` invokes `BoundedStringMatcher` from unchanged main
`4e328b0109e13c896b74004823fb049fcb19251a`, using the fat JAR whose SHA-256 is
`91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`.
The completed execution passed **165 assertions**. Inputs, class hashes, commands,
terminal exit codes, and JAR hash before/after are recorded in `receipt.json`.

The Java oracle reflects the real private matcher, predicate key, and StringTable
constructor. Its FrontCodedStringList subclass counts calls to the real decoder,
then delegates decoding to `super.get(index, target)`. It can deliberately reject
backing reads to independently prove that cached positive and negative results
skip decoding. It does not implement a replacement main matcher or string table.
All tables are synthetic, in-memory correctness inputs. There are no timing,
allocation, throughput, or performance acceptance claims.

Verified behavior:

- Raw projection capacity 4,096 uses dense state when table size is at most
  4,096, otherwise direct mapped keys/values. Repeated hits and misses reuse state.
- Collisions evict one slot, and revisiting an evicted SID decodes again. The
  unsigned spread `(sid ^ (sid >>> 16)) & (capacity - 1)` is independently visible
  in bytecode and observed through SID 0/4,096 and SID 1/65,536 collisions.
- The actual Kotlin default constructor chooses capacity 65,536. Tables of size
  65,536 and 65,537 demonstrate its dense/hashed boundary, cached last valid ID,
  invalid-ID read timing, and collisions.
- Dense invalid IDs throw `java.lang.ArrayIndexOutOfBoundsException`, with
  `Index N out of bounds for length S`, before decoding. Hashed invalid IDs reach
  the real front-coded decoder and throw `java.lang.IndexOutOfBoundsException`:
  `Index (N) is negative` or `Index (N) is greater than or equal to list size (S)`.
  Negative one, minimum int, size, and maximum int are covered.
- Capacity inputs are clamped to at least one, then rounded down to a power of
  two. The default is distinct from passing zero explicitly.
- CONTAINS compares Java UTF-16 code units, including a high or low surrogate
  occurring within a valid supplementary pair. Lone surrogates remain matchable.
- LOWERCASE CONTAINS lowers ASCII using the reusable MutableString, but any
  non-ASCII character causes a whole Java String lowercase fallback. The oracle
  covers dotted-I expansion, context-sensitive final sigma, supplementary-plane
  case conversion, empty needles, and expected strings remaining untransformed.
  STARTS_WITH, ENDS_WITH, and EQUALS also exercise whole-string transformation.
- Per-query matcher identity consists of transform, mode, and expected string;
  property is excluded. `predicate-identity-source-audit.json` identifies the real
  raw projection call site. This differs from the persistent raw projection
  result cache, whose key includes ordered properties and limit.
- Actual `directStringStorageWorkConsumer` calls cover source counts 1, 2, 39,
  40, 64; forceSerial false/true; and absent/present tracking callbacks, for 20
  policy cases. With configuredGraphWorkers null, preferRaw false, and
  preferMappedView false, the Serial marker is exactly
  `forceSerial || (sourceCount > 1 && sourceCount < 40)`. Forced serial consumers
  also carry PreferredPersisted; unforced one-source consumers are Parallel,
  and unforced 40/64-source consumers are Split. Tracking callbacks preserve
  policy and receive the actual charged work.

`responses.jsonl` contains each result/error, backing read count, and sparse
internal cache state. Unicode input records use numeric UTF-16 units to avoid
loss of isolated surrogates during downstream JSON handling. Disassembly files
preserve the exact main implementations used by this execution.

## Integration boundary

The default-capacity matcher is used by main's
`serialRawCallSiteStringDisjunction`, which requires CallSite nodes, a finite
limit, and `SerialGraphWorkBatchConsumer`. Main also has generic and parallel
raw disjunction paths with full-table dense state arrays. A broad Go `raw` flag
alone does not establish which main path applies. This primitive evidence proves
both matcher capacities but does not authorize replacing every raw query path
with a bounded matcher. Under the tested default configuration, an explicit
serial-consumer policy selector plus CallSite and finite-limit gates is supported
by the actual consumer matrix and `serial-raw-source-audit.json`. Whole-query
state/ordering/error parity requires the separate query replay evidence.

## Public query controls

`python3 run-public.py` completed 16 engine observations: source counts 2 and 64,
four fixture variants, and two consecutive executions. Every graph is an
independent copy of `candidate-index/clean`; only source g00 is mutated where
specified. `public-responses.json` records exact query, mutation offset/new SID,
full result rows or exception, and exception stacks identifying the real path.
All cloned files match their pre-execution hashes afterward.

The query is `MATCH (n) WHERE n.caller_name CONTAINS 'othe' RETURN n.caller_name AS x LIMIT 1`.
Clean cases return `x = "other"`. Changing the matched node's caller_name SID at
byte offset 74 to maximum int or -1 produces the dense matcher array exception
in both source counts. Stacks prove the 2-source default-capacity serial caller
and the 64-source raw projection caller.

The return-type corruption control changes byte offset 98 to maximum int.
Two-source execution throws the front-coded list exception during full node
materialization; 64-source raw projection succeeds because it does not decode
that unused field. The initial oracle mistakenly expected the existing
`bad-matched` return-type corruption to fail in matcher state. Its rejected
assertion is retained in `public-initial-incorrect-fixture-assertion.stderr`;
the corrected oracle distinguishes the actual read boundaries.

# Selected DISTINCT target SID lookup: read-only audit

Scope: f9dc0aac1c1026797cc749f6ba8901ca6a0e0267, the two repeated
selected-target/property/string-table scans in internal/query/indexed_distinct.go.
This directory contains an independent design model and correctness probes only;
there is no production patch, runtime cache,64 process, performance microbenchmark
or quantitative performance conclusion. Root's control/profile observations are
not reinterpreted or remeasured here.

## Recommended single hypothesis

Precollect only the **raw projection string values already present in the selected
row maps** into a wanted set. Maintain one per-source/per-probe forward cursor
and a map of first SIDs for wanted strings encountered so far. At each original
SID lookup point, resolve from this map or advance the cursor until that demanded
string appears or EOF is reached, recording any other wanted strings along the
way. Then continue the original property/target loop and original Postings call.

No Strings traversal or Postings validation occurs while collecting wanted values.
Each table position is examined at most once per resolver; only requested strings
are retained, O(unique selected raw strings), bounded by rows×raw properties.
This is an algorithmic property, not a measured saving. Avoid a full table
text→SID map, Store cache, query-result cache, cross-source reuse, or Postings cache.

The existing loops are `distinctSourceHits` at line470 (lookup near545) and
`distinctRawRows` at line726 (lookup near791). A small resolver can be shared as
an implementation helper, with distinct instances at the original lookup phases.
Its normal interface can be `find(text) SID`, called only for texts precollected
as wanted. It should use the existing e.check cancellation function and propagate
its existing panic. The model in sid_test.go instead returns errors so its traces
can be asserted without depending on the production query package.

## Why not eagerly scan the whole table first?

In distinctSourceHits's retained-index branch, the original loop interleaves:

    target0/property0 SID lookup → Postings → property1 lookup → Postings → ...

Moving a cancellable whole-table scan ahead of the first Postings moves an
observable cancellation opportunity ahead of a storage error. The deterministic
trace probe has target0's text at index0, target1's at index1024, a first-Postings
failure, and a cancellation hook at the late scan boundary:

- Original: `bad first Postings`.
- Eager all-wanted preparation: `context canceled`, without reaching Postings.
- Lazy shared cursor at original lookup points: `bad first Postings`.

This is a controlled error-order/access probe, not a timing or throughput test.
The hook models cancellation arriving at a newly introduced earlier scan; it is
not a claim about a specific wall-clock ordering on the host. The lazy design
avoids scanning future-only text before the earlier observable operation.

Keep every original target/property loop, validity flag, break/continue,
index.Postings call, anchor choice and validation in place. Substituting only
SID discovery prevents the optimization from changing storage-error consumption.
Do not reduce Postings calls even for repeated property/SID pairs.

## Required phase boundaries

1. distinctSourceHits first builds selectedKeys and applies its current rawTargets
   filter (source graphId and non-string values), while preserving generic work.
   If rawTargets is empty it must still avoid raw index preparation entirely.
2. Keep PrepareDistinctStringIndex and the Raw/ParallelRaw/retained branch choice
   in their present positions. The retained branch can construct its wanted cursor
   after preparation, immediately before its existing target loop. Collecting
   query values is pure; it must not become another validity/graph routing pass.
3. distinctRawRows's serial `!ParallelRaw` path must return as before without this
   resolver. For ParallelRaw, preserve the `!Raw` exact-directory/predicate phase,
   all errors and the no-exact-match early return before target SID resolution.
   Only `selected != nil` needs the resolver. Preserve Raw versus retained Postings
   checks and the empty-selectedIDs return before launching raw node workers.
4. Do not share a resolver between different sources or cache it for repeated
   queries. The same text can have SID3 in one source and SID0 in another.

## Equality, duplicates, alias and null constraints

- Record the **first** table position for a wanted text, never overwrite it on a
  later duplicate. Use map membership, not SID>0: empty strings and SID0 are valid.
  Missing remains -1 only after the demand exhausts the cursor; absence before
  EOF means unresolved. Do not sort the table or the demands.
- Equality is current Go string byte equality on its exact loaded WTF-8 encoding.
  Do not lower-case, normalize, decode/encode through replacement runes, or apply
  javaWireString. The WHERE atoms may be transformed but projected target text is
  original text. Isolated high/low surrogates, literal '?', supplementary pairs,
  U+0130 and lowercase i remain distinct keys as appropriate.
- Iterate plan.properties/plan.columns in their existing order and obtain wanted
  text through target[plan.columns[i]], exactly as the old code does. Repeated
  aliases already contain the last projected value in the row map. Do not try to
  recover a discarded earlier projection or iterate map keys for field order.
- Collect only fields recognized by distinctCallSiteProperties and whose current
  row value is a string. Do not treat nil as an empty string. Nonraw graphId/class/
  name slots remain their existing -1/value-validation rules.
- Don't pre-discard an entire row merely because it will eventually be invalid.
  For example a nonraw class value can set valid=false and continue, while a later
  raw property still consumes Postings before the final validity check. Earlier
  missing raw text instead breaks immediately. Only the original loop decides.
  Collecting a never-demanded later wanted string is harmless; scanning ahead for
  it or validating its postings is not.

## Existing main difference: explicitly not fixed here

Pinned main's StringTable.kt:40 findId uses an indexMap on builder tables or Java
UTF16 binary search on loaded tables. Builder output (lines96+) sorts/deduplicates,
so unique sorted strings agree with current Go's first linear match. However,
StringTable.load accepts serialized FrontCodedStringList entries without checking
that they are sorted/unique. The actual pinned-main Java reflection oracle writes
these lists using the real library serializer, loads through StringTable.load,
and invokes its real findId method:

| Serialized entries | Target | Main findId | Current Go first match |
|---|---|---:|---:|
| [a,a,a] | a | 1 | 0 |
| [b,a] | a | -1 | 1 |
| Sorted unique empty/?/a/high-surrogate/emoji/U+E000 | each | same | same |

Those are valid serialized table inputs accepted by main, but not produced by its
normal deduplicating builder. This is an existing lookup-parity gap, not a new
optimization behavior. Preserve current Go first-SID semantics in this hypothesis;
changing to main's binary search or rejecting these inputs is a separate feature
compatibility decision. No end-to-end graph-query impact is claimed from this
small direct-method probe alone. The actual tables and Java results are archived.

Main selectedProjectionHits (MappedCallSiteStringIndex.kt:913) calls findId per
selected property. The indexed implementation around801/842 orders some value
lookups by unique property counts, then queries posting ranges. The present Go
loops have their own established operation order; this hypothesis must not also
change them to main's lookup ordering or alter tuple-index strategy.

## Cancellation and Close

Preserve the existing e.check mechanism (it reads ctx.Err and panics on error),
contexts, tasks, worker count and join behavior. Each original lookup against a
nonempty table checks at its initial j0, so even a cached SID hit/miss should still
check cancellation at the lookup point. Forward advancement should use the
existing branch's bounded polling discipline (retained loop1024, raw loop every
entry) and should not publish partial resolver state outside the probe.

Fewer redundant scans necessarily means fewer total polling calls and different
wall-clock cancellation observation opportunities. This design does not claim
bit-identical outcomes for an artificial Context.Err that changes on its Nth
invocation, or identical choices between genuinely simultaneous failures. It
preserves operation order and responsive cancellation rather than moving all
future work before the first error-producing operation.

Store.Strings is an already decoded Go-owned table retained after Close, immutable
by the Store API's read-only convention. Reading it does not borrow mapped index
bytes and does not itself validate/throw for table contents. Keep the query's
existing source lifetime and all subsequent Postings/Projection methods, which
perform their own Close/context checks. Do not substitute ProjectionString for
the plain equality scan (that would introduce new Close/format errors), add a
long-held lock, release mappings early or attach the resolver to Store. Concurrent
caller mutation of the public string table remains unsupported, as before.

## Probe results and reproduction

Five independent Go correctness tests passed under race: exact first-SID/WTF-8,
source-local IDs/wanted-only state, duplicate-alias field order, eager-scan error
reordering, and cancellation on cached lookup. They test the algorithm model,
not unimplemented production integration. Java's actual findId probe has three
serialized-table cases. No execution-time or allocation values were collected.

```sh
go test -race -v ./...
JAVA17=$(/usr/libexec/java_home -v 17)
JAR=/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar
"$JAVA17/bin/javac" -cp "$JAR" FindIdOracle.java
"$JAVA17/bin/java" -Xmx128m -XX:ActiveProcessorCount=2 -cp ".:$JAR" FindIdOracle .
```

Before accepting a production change, run its existing raw/retained/parallel
projection corruption, cancelled/closed, aliases/WTF8 and complete-result tests,
and add the early-Postings error trace at the real helper boundary. Verify both
lookup sites without changing any work scheduling. Real64 evidence, if later
requested, must be a separate controlled measurement.

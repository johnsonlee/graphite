# Main-semantic lazy string candidates, bounded functional completion

Frozen base: clean `87aaf0ad39c89189d52c859672e5420b26c3f5e9`, followed by
`baseline/ordinary.patch` (the independently integrated ordinary implementation)
and the frozen generic synchronous provider with root's UTF16 equality fix.
`baseline/combined.patch`, `baseline/source-manifest.json` and
`baseline/source.tar.gz` preserve the exact unmodified composed baseline. This
work does not include later root context-check, mapped-decoder or tuple changes.

## Result and evidence

- Original corpus denominator is unchanged: 1048. Baseline 968 equal; final 994
  equal. Exactly 26 changed responses become main-equal. All 28 cases in the
  former A group now match; two were already fixed by the synchronous baseline.
- The former 580 DISTINCT and 122 ordinary eligible responses remain equal.
  The remaining 54 are retained individually: 34 main-success/native-error,
  16 differing errors, four differing successes. This does not claim the B
  pagination or other remaining consumers are implemented.
- `results.json` gives every changed response, all A28 identifiers, full remaining
  classification, and all 44 pre-existing history artifacts. 43 history artifacts
  are wholly identical to baseline. The rolling artifact differs only in
  speculative cancelled-task mappedView publication flags, already nondeterministic
  between main executions; all its complete responses are unchanged.
- New actual-main corpus: 58 scenarios, 116 target requests and six warm requests.
  All 122 complete response values/errors match. A fresh independently prepared
  fixture replay reproduces every response. Before/after cache observations are
  preserved without normalizing speculative completion counts.
- The decisive mapped-view counterexample is in `jvm/offset-main.json` and
  `offset-before.log`: an unselected bad ID90 canonical offset succeeds with
  40-source main mapped views but fails in one/two-source paths. The initial
  shared strict loader failed incorrectly; `offset-after.log` records the fix.
- Cold mapped rows validate all their positions before returning a sequence.
  Warm accepted rows use cached validation and read orders on cursor advance.
  `TestMainStringMappedColdWarmDemandAndCancellation` uses a real cancellable
  context plus an accessor checkpoint observer: cancellation at the second order
  prevents cold sequence construction, but the warm path first produces ID2.
- All-module race and vet pass. Final commands/exit codes and immutable source
  identities are in `manifest.json`. Failed compilation/test attempts remain in
  their original logs: extraction errors, ordinary error-wrapper regression,
  old eligibility expectations, observer counting, and private test offset
  encoding mistakes were repaired rather than deleting their evidence.

## Shared API and ownership

`mainStringCandidates(source Graph, pattern cypher.NodePattern, atoms
[]distinctStringAtom, sourceCount int) mainNodeNext` is the normal entry.
`mainStringCandidatesWithPolicy(..., forcePersisted bool)` preserves the same
factory while allowing a later consumer to select main's persisted-storage
preference. `mainNodeNext` is `func(context.Context) (store.Node, bool)`.
Each factory has independent positions; every returned Node owns its contents.
Call Next with the current source-task context. Never share a partially consumed
cursor with another consumer or query. Errors use the existing typed query panic
boundary. Generic batch scheduling, Java equality, task joins and required-source
failure priority remain in the synchronous provider.

The candidate compiler is the existing structural `compileDistinctDisjunction` /
`compileDistinctAtom`, not a query-name list. The ordered concrete property table
is main's EnumConstant.name, LocalVariable.name, FieldNode.class/name, four
CallSite properties and the corresponding Annotation properties. Raw Enum,
Local and Field candidates read only the relevant SID before decoding a match.
Annotation candidates use full nodes. MAPPED merges concrete-type heads in main
order; EAGER concatenates its concrete-type sequences. Whole-node and nested
projection values are frozen before the borrowed slot advances. Arbitrary
nonaggregate projections are accepted by the generic no-SKIP DISTINCT path;
parallel projection remains restricted to main's safe literal/direct-property
shape. Existing unsupported planners retain their existing fallback.

The ordinary storage implementation is extracted into plan-neutral source,
matcher and cache helpers, with ordinary adapters retaining their prior public
error mapping. A6/A7 certificate preparation and all-source behavior are unchanged.
The strict reader's rejection cache cannot reject the separate main capability.
The same checked binary-format parser has three explicit policies: A6 strict
nodeindex order; main retained all canonical offsets; main mapped node-ID capacity
and selected-range validation. Store owns all mappings and first-load channels.
Close releases them only after readers finish and joins all in-flight loaders;
cancellation never publishes a half-built view or range result.

## Independent review

`/tmp/graphite-main-source-independent-review/manifest.json` SHA256
`630d335f04c0d91a14efdc596e354d7aa73d6eb880b767fa7d95cc50bfe78f52`
contains 53 independent evidence files. Its final receipt SHA256 is
`9b210b8f8d9b5244a1701c52195c7f69a12d05fbcddae9555804ac66c17333fb`.
The reviewer independently matched all 16 production SHA256 values against
`production-files.json` (`6abf76a8bffd1cb2b44c7d6e066d9eb8dd108c5f57e2bdcac9f68dc134b8d862`).
Its tiny JVM callback oracle demonstrates cold/hot node-order and delayed-error
consumption; additional first-head, loader waiting/close, current-wave context,
ownership and natural-exhaustion tests passed under race. The independent source
is not modified by this delivery and no production commit was made.

## Exact limits

This adds the reusable source and generic DISTINCT consumer, not B streaming
pagination, C necessary-AND selection, D/E generic consumers, or relationship
candidates. Those consumers must construct independent source factories and
re-evaluate residual predicates where main does. The source does not model main's
process-wide index-memory reservation budget or externally configured JVM worker /
GraphWork limits; its 1024-slot range validation cache mirrors keys and completion
semantics without claiming shared-budget equivalence. EAGER strategy choices and
all possible custom GraphWork/error races are not exhaustively proven here.
Immutable stores are assumed, as for existing readers; private mutation tests
isolate accessor demand and do not authorize modifying open files.

All work was correctness-only on small fixtures. Other root work could coexist
on the host; no64 runtime or synthetic/real performance measurement was started,
and no latency, allocation or speedup claim is made. The full baseline failures
and known speculative state differences remain visible.

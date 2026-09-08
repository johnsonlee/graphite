# Original main generic string disjunction correctness oracle

Pinned main is `4e328b0109e13c896b74004823fb049fcb19251a`, runnable JAR SHA256
`91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`.
Java is 17.0.18. These are correctness captures only: **no performance measurements**.
The persisted all-types source is the existing JVM correctness fixture under
`graphite-server/internal/query/testdata/main-string-source/all-types`; it is not
one of the production 64-graph performance fixtures.

Two independent, fresh JVM captures completed successfully: 201 scenarios each,
181 public executor calls and 20 direct generic-provider calls. Main supplies all
recorded expected values and errors; no Go implementation supplies the oracle.
There are 149 successful responses and 52 failures in each capture. Exactly 199
scenarios repeat in full, including diagnostics, errors, and UTF16 content.
The other two are explicitly preserved row-order variations described below.

`cases.json` is the ordered input. Each case has name, query, parameters,
fixtures, and scoped policy. Optional providerType/providerProperties select the
private original `lookupStringPropertyDisjunction` through reflection, consumed
as an original Kotlin sequence. `main.json` contains `{cases:[...]}`; each record
includes the original spec, execution phase, outcome, before/after graph flags,
and original diagnostics. Success records contain columns, rows, and rowsUTF16;
errors contain error, qualified errorClass, message, errorUTF16, and actual stack.
Provider records contain providerSupported and the exact yielded prefix, with
payload type, payload ID, toString value, and valueUTF16. Provider diagnostics are
zero because those calls use a null storage work consumer and do not execute the
public Cypher context. Public calls use an explicit Long.MAX_VALUE budget context;
all workUnitsConsumed and fast-path/source-routing diagnostics are retained.

`repeat-main.json.gz` is the unmodified second output. `verification.json` reports
exact repeat equality and the two observed order differences. No case is removed,
no output row order is rewritten, and no diagnostics field is suppressed.

The matrix covers:

- Exact original queries/parameters from workload indices 886–888, with workload
  SHA and complete original case objects in actual-main64.json. Positive six-term
  variants cover EnumConstant.name, LocalVariable.name, Field.class/name,
  CallSite properties, and Annotation fallback through original public planning.
- Invalid first, second, and trailing property SIDs; invalid nested argument/value
  tags; malformed collection count, method-descriptor tail, and truncated Field
  boolean. Typed hit/miss pairs distinguish raw filtering from full deserialization.
  Selected mutations also use the unlabeled original six-term query shape.
- Missing offsets, negative stored offsets, positive offsets wrapping at 2^32,
  wrapped out-of-bounds offsets, and payload kind/ID disagreements while original
  node/type indexes remain intact.
- Typed LIMIT 0/1/2/200, DISTINCT and ordinary projection, two-source duplicate
  handling and provenance; clean/fault source order with both scoped policies.
  Source IDs deliberately preserve request order z-first, a-second while merged
  provenance uses original main's ordering.
- Java lowercase expansion/context (İ, Greek sigma), supplementary characters,
  isolated high/low surrogates, NUL, and accented text. String fixture variants
  replace existing SID slots and may be unsorted; they are declared adversarial
  correctness fixtures. Typed raw SID matching exercises original string decode
  without treating those variants as evidence about sorted-table search.

Observed contracts and implementation implications:

| Actual scenario | Actual main result |
| --- | --- |
| Field first predicate SID invalid | ArrayIndexOutOfBoundsException from the match-state array |
| Field second predicate SID invalid, class hits | IndexOutOfBoundsException from full node deserialization |
| Same second SID invalid, class misses | ArrayIndexOutOfBoundsException from predicate matching |
| Enum/Local unused malformed tail, predicate misses | Empty success; tail is not decoded |
| Same tail, predicate hits | Original deserializer error |
| Field missing final boolean, hit / miss | EOFException / empty success |
| Annotation malformed SID/tail, including predicate miss | Full deserializer error |
| Generic raw offset missing or negative | Empty success |
| Generic positive offset wrapping to valid payload | Valid payload returned |
| Field type index, payload changed to IntConstant | Direct provider yields IntConstant; public string projection yields null columns |
| Field selected ID18, payload ID1152 | Direct provider yields payload ID1152 |
| Unknown nested value tag127 | Original decoder accepts fallback string SID; Enum provider records constructorArgs=[Color] |

These results are actionable fidelity requirements. They do not authorize ignoring
Go errors or treating existing Go ID/tag/offset validation as main-equivalent.
The public response is atomic on failure; direct provider records preserve any
successfully yielded prefix separately.

Both six-empty-1 and six-empty-2 differ only in row order between JVM processes;
row multisets and every other response field are identical. Pinned
QueryPipeline.kt:3018 explicitly rejects extracted string predicates when
`coalescesMissingToEmpty && expected.isEmpty()`, so these are **generic supertype
fallback controls**, outside the fused string-predicate route. The original
mapped type-index supertype enumeration uses a HashMap keyed by Java Class.
The two raw outputs remain authoritative observations; the verifier records
multiset agreement only to characterize the variation, not to claim exact
cross-runtime ordering parity or waive the unresolved Go fallback ordering gap.

Boundaries: this is a bounded adversarial matrix, not all possible malformed
payloads, finite budgets, cancellation/Close schedules, 40-source worker schedules,
or complete server fidelity. Only one generic node per concrete kind exists in
the source fixture; two-source LIMIT cases cover source-level consumption, but
this does not exhaust multi-node same-kind prefix permutations. Full real64
three-state replay and formal timing must still run on the implementation.

Reproduce from the repository root, using fresh destinations:

```sh
python3 docs/go-server-baseline/native-generic-string-disjunction/prepare.py
python3 docs/go-server-baseline/native-generic-string-disjunction/run.py /tmp/new-generic-oracle-a
python3 docs/go-server-baseline/native-generic-string-disjunction/run.py /tmp/new-generic-oracle-b
python3 docs/go-server-baseline/native-generic-string-disjunction/verify.py
```

The last command verifies the committed captures and fixture archive, not arbitrary
new destinations. archive.py documents the exact source destinations used for
this capture; adjust its two input paths to archive new independent runs.

The runner first copies immutable source files, loads and closes each clean copy
to generate original node-offset/type indexes, then applies mutations before the
first measured *correctness* request. This preparation has no timer. Each final
run audits 3,036 original prepared fixture files; all remain unchanged. Only
original main's optional graph.callsite-string-index files are added. Source,
JAR, compiler/runtime, Java helper, input JSON, and relevant Kotlin files are
hashed and unchanged. `fixtures.tar.gz` preserves one audited pre-query copy of
each of 49 variants (588 files), with all file hashes in fixture-variants.json;
query-generated optional indexes are omitted according to the pre-query manifest.
Go correctness tests can extract this archive without executing Java.

initial-audit-failure retains the exploratory first capture and failed assertion:
Java completed, but the controller initially forbade expected new persisted
CallSite index sidecars. The exact added paths remain recorded. Final captures
use the explicit allowance, correct the Annotation positive needle, and preserve
all 201 cases.

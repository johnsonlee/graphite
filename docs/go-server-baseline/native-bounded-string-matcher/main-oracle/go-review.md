# Independent Go implementation review

Reviewed the uncommitted implementation in bounded_string_matcher.go and its
ordinary_leading.go/main_string_source.go integrations, alongside the unchanged
main matcher, consumer policy bytecode, and actual JVM oracle. No blocking issue
was found in this change. This is a source review, not a claim that the full
server goal or P95 acceptance has passed.

- Dense array size, state values, pre-decode bounds exception, unsigned hash,
  SID-plus-one key, capacity normalization, and collision replacement match main.
- `ordinaryStringKey` excludes property and preserves transform/operator/Java
  UTF-16 expected-string identity. `distinctAtomMatches` uses the existing Java
  string semantics; the oracle covers important lowercase and surrogate cases.
- Raw projection creates matchers after the persistent matched-ID cache lookup,
  and publishes no matcher across requests. The serial iterator initializes its
  shared matchers at first consumption, matching Kotlin sequence construction.
- The serial integration requires raw storage and an explicit finite-limit
  default consumer policy selector. Actual main consumer calls support the
  selector; it excludes generic raw fallback in unforced source1/split cases.
  `forcePersisted` is interpreted through the existing plan's forceSerial policy
  contract. Configured-worker overrides remain outside this tested policy.
- The serial `readRawString` adapter invokes `failMainStringRead` before returning
  to the matcher's generic read-error handling. Thus `ErrStoreClosed` remains a
  typed panic in that path, while structured storage read exceptions preserve
  their existing public mapping. Raw projection keeps its prior error adapter.
- Primitive tests compare 54 result/error/read/cache observations and the
  20-case real consumer matrix, rather than asserting only execution. Public
  engine controls independently expose both caller paths and distinguish
  predicate SID bounds from unused-field materialization errors.

Full module race tests and real64 replay remain the integrating agent's checks.

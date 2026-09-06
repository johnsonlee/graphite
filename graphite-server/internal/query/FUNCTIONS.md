# Main function compatibility matrix

Reference: remote main `4e328b0109e13c896b74004823fb049fcb19251a`, `CypherFunctions.kt` and `QueryPipeline.kt`. This tracks the entire dispatch table, including aliases. All 59 scalar dispatch names and 10 aggregation names are implemented natively. The checked oracle corpus is finite evidence of compatibility, not proof over every possible query.

| Family | Complete names | Status |
|---|---|---|
| Identity/properties | `id`, `elementid`, `qualifiedid`, `graphid`, `properties`, `keys`, `labels`, `type` | Implemented; oracle checked |
| Conversion | `coalesce`, `tointeger`, `toint`, `tofloat`, `toboolean`, `tostring` | Implemented; oracle checked |
| String/list | `tolower`, `tolowercase`, `toupper`, `touppercase`, `trim`, `ltrim`, `rtrim`, `replace`, `substring`, `split`, `size`, `length`, `left`, `right`, `reverse`, `head`, `tail`, `last`, `range`, `nodes`, `relationships` | Implemented; oracle checked |
| Math | `abs`, `ceil`, `floor`, `round`, `sign`, `sqrt`, `exp`, `log`, `log10`, `e`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`, `cot`, `pi`, `degrees`, `radians` | Implemented; oracle checked |
| Nondeterministic | `timestamp`, `rand` | Implemented; type/range/time-window invariants |
| Predicate | `exists` | Implemented; oracle checked |
| Aggregation | `count`, `sum`, `avg`, `min`, `max`, `collect`, `percentileCont`, `percentileDisc`, `stdev`, `stdevp` | Implemented; oracle checked |

Main pipeline semantics to preserve: percentile calls use the default 0.5 regardless of extra arguments; only the first aggregate argument is evaluated. Aggregate expressions nested inside scalar/arithmetic expressions are detected selectively but fail with `CypherAggregationException` when evaluated, rather than being recursively reduced. Unknown function and arity errors are evaluated at runtime, so an empty input can avoid those errors.


## Evidence and boundaries

`functions_test.go` compares 369 local JVM query cases: 347 scalar, aggregation,
regex, literal, dispatch and binding-order cases, plus 22 cases across every one
of the 16 persisted node types and scoped/cross-graph values. It compares full
columns, ordered rows and exception class/message. Nonfinite numbers are encoded
as explicit `$number` markers in the oracle. The only string normalization removes
the inherently process-specific graph identity hash from JVM object rendering.
Native graph identity tokens are stable for the lifetime of a store.

All Unicode casing, Java whitespace, decimal digit and final-Sigma word boundary
data is generated from Java 17.0.18. The Go implementation preserves isolated
UTF-16 surrogates internally as WTF-8 and converts them to `?` only at the UTF-8
wire boundary, matching the source JVM encoder. `properties` and `keys` apply to
nodes/methods, not arbitrary maps or relationships, as in main. Maps from literals,
properties and persisted annotation attributes retain insertion order. Queries
that expose entire binding maps or `RETURN *` retain binding insertion order
outside the binding map itself, without reserving a user variable name.

The mathematical target is Java 17.0.18 HotSpot ARM64 `Math`; the independent
[`javamath`](../javamath/README.md) corpus checks 30,320 numerical cases. This does
not assert the same last-bit target for other JVM architectures or versions.
The [`javaregex`](../javaregex/README.md) package checks 4,172 cases, including
isolated surrogates. Query-level regex tests additionally check literal/prefix
and ASCII-range dispatch, error timing, empty inputs and short-circuiting of a
non-string left operand. A 256-entry query-local regex cache matches the source
cache bound.

Missing parameters return null. Extra scalar arguments are evaluated and then
ignored where main ignores them. Only an aggregate's first argument is evaluated;
extra percentile arguments are ignored. Nested aggregate combinations preserve
main's runtime exceptions, including empty-input differences. Aggregated rows
evaluate ORDER BY lazily during comparison, so a one-row result does not evaluate
an otherwise-invalid sort expression.

Reproduce with `testdata/regenerate-functions.py <pinned-main-jar>` and
`../javastring/testdata/generate-java-unicode.py <pinned-main-jar>`. Java is development-only;
the compiled query engine never invokes a JVM. All fixtures are correctness-only.
The remaining server/planner/procedure matrix and performance acceptance remain
separate work; these results do not establish full server parity or a speedup.

# Scalar numeric compatibility audit

The adjacent `arithmetic-jvm-oracle.json` captures 20 concrete requests and full
responses from the main JVM server. Requests use the root endpoint with 64 real
graphs loaded; their arithmetic is graph independent. This is correctness evidence
only. No query timings or generated graphs are used to establish performance.

Source confirmation: main `4e328b0109e13c896b74004823fb049fcb19251a`,
`CypherDslAdapter` integer literal conversion and `ExpressionEvaluator.arithmetic`.
The arithmetic rule is also present in the current worktree. The live process
identity is maintained by the external baseline harness; the fixture records its
URL, timestamp, graph count, queries, HTTP status, parsed response and raw JSON.

Small decimal literals become Kotlin Int; hex/octal and large decimal literals
become Long. Arithmetic converts operands to Double, computes the result, then
converts a whole-number result back to Long only for Int/Int or Long/Long inputs.
Int/Long mixtures stay Double. This creates observable `toString` differences:

| Expression | main value |
| --- | --- |
| `toString(1 + 1)` | `2` |
| `toString(0x1 + 1)` | `2.0` |
| `toString(2147483647 + 1)` | `2147483648` |
| `toString(2147483648 + 1)` | `2.147483649E9` |
| `toString(2147483648 + 2147483648)` | `4294967296` |
| `toString(9223372036854775807 + 0x1)` | `9223372036854775807` |
| `toString(9223372036854775807 + 1)` | `9.223372036854776E18` |
| `toString(9007199254740993 + 0x0)` | `9007199254740992` |

The Long-max case follows Kotlin's saturating Double-to-Long conversion, followed
by a Double equality check. It is not ordinary Go overflow behavior. Arithmetic
also accepts nonnumeric input through the source's coercion: `true - false` is
Double zero, `'5' - 1` is Double four, and unary plus returns its operand unchanged.
`1 % 0` produces non-finite data and currently fails during HTTP serialization
with status 500 in main. This response should not be mistaken for a parser error.

The Go parser now preserves small-decimal Int32 and large/hex/octal Int64
widths. Native arithmetic preserves unary types, applies same-width restoration,
and saturates Double-to-Long conversions. Native Java17 floating-point spelling
is checked against2,185 persisted outputs of local OpenJDK17.0.18, including
boundary bit patterns and deterministic scalar values. All20 scalar query
responses are covered by native tests; the nonfinite modulo case asserts that
JSON serialization fails.

The captured string outputs are necessary: JSON comparison libraries can treat
`2` and `2.0` as equivalent and miss the type error. The stale local main ref
44b57562 differs from remote main4e328b0 in unrelated null-comparison semantics;
the baseline owner confirmed the running oracle's remote revision and Java17
runtime. Use the actual remote revision for future functional assertions.

# Native Java string primitives

This package shares the existing query implementation of Java 17 string behavior
with other native server components. The migration preserves the algorithms and
the generated tables; it is not a performance optimization. Query retains thin
private wrappers, including its original cancellation callback.

- `UTF16`, `FromUTF16` and `CodePoints` retain isolated surrogate units as WTF-8
  and combine valid pairs into Unicode code points.
- `WireString` applies the JVM UTF-8 encoder's `?` replacement to isolated
  surrogates while retaining real U+FFFD characters.
- `Case(s, upper, check)` implements `Locale.ROOT` string casing, including
  expansions and Java's word-boundary handling of final Sigma. A nil callback is
  allowed. `Lower` and `Upper` use the same implementation without a callback.
- `Digit` exposes decimal `Character.digit`; `Whitespace` combines
  `Character.isWhitespace` and `Character.isSpaceChar`, matching Kotlin's predicate.

`unicode_generated.go` is the single shared casing, whitespace, decimal-digit
and word-iterator table. Its generator moved from query with the implementation.
Regenerate with Java 17.0.18 and the pinned main jar (needed for the generator's
Gson dependency), from the Go module directory:

```sh
python3 internal/javastring/testdata/generate-java-unicode.py /path/to/graphite-explore.jar
go test -race ./internal/javastring ./internal/query
go vet ./internal/javastring ./internal/query
```

The generator uses reflection to extract Java's ROOT word-iterator DFA and
conditional-casing categories. No JVM is invoked during server execution.
Shared tests assert concrete casing, UTF-16, digit, whitespace and cancellation
behavior. The existing query JVM corpora remain the end-to-end correctness oracle;
these tests and generated data establish no performance claim.

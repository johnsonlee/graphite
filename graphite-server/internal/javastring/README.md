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

`char.go` adds Kotlin UTF-16 `Char` semantics for native C4 naming:
`IsLowerChar`, `IsUpperChar`, `TitleChar`, `TitleFirst`, `UpperFirst`, `Blank`
and `Trim`. Char operations inspect one UTF-16 unit; whole-string `Lower` and
`Upper` still consume supplementary code points. `TitleChar` supports Kotlin's
one-to-many titlecase expansions, including `ß` to `Ss`.

The additional compact Char ranges come from
`testdata/GenerateJavaChar.java`, invoking Java 17 Character predicates and
`kotlin.text._OneToManyTitlecaseMappingsKt.titlecaseImpl` from the unchanged main
jar. `char_test.go` compares every one of the 65,536 char values, including
surrogates, against the saved binary oracle. These are new BMP predicates/title
mappings; the shared ROOT casing and word-boundary tables remain single copies.
Regenerate from repository root:

```sh
java -Dfile.encoding=UTF-8 -Xmx128m -cp /tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar graphite-server/internal/javastring/testdata/GenerateJavaChar.java graphite-server/internal/javastring/testdata/java17-char.bin.gz
python3 graphite-server/internal/javastring/testdata/generate-char.py
```

The main jar revision and hashes are recorded in
`../analysis/c4/testdata/unicode-provenance.json`. No table generation or JVM is
needed at runtime.

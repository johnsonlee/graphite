# String predicate candidate (Attempt 5)

Effective base: commit `4124bfc44bedd7915e238b7a08f2524b48ed47b6` plus the frozen Attempt 4 patch with SHA-256 `6084e090235c6f6505e17e05fafe657023ba148b1d70667be20454dd13d6955a`.

The production delta only changes `STARTS WITH`, `ENDS WITH`, `CONTAINS`, and their `NOT` forms in `eval.go`. When both operands are valid UTF-8, it uses Go's byte predicates after checking cancellation and checks cancellation again before returning. Any WTF-8 surrogate or other malformed UTF-8 in either operand retains the existing UTF-16 comparison. Operand evaluation and null/type handling precede this branch and are unchanged. Case conversion, node iteration, indexes, and regular expressions are unchanged.

## Why matching valid UTF-8 is equivalent

A valid UTF-8 string represents Unicode scalar values, so its UTF-16 encoding is well formed. A nonempty well-formed UTF-16 needle cannot begin with an isolated low surrogate or end with an isolated high surrogate. Thus a match cannot begin or end halfway through a surrogate pair. In UTF-8, a valid needle begins with an ASCII or leading byte, never a continuation byte; a full match also ends on a code-point boundary. Both predicates therefore match exactly the same sequence of Unicode scalar values. Empty needles match in all three predicates. Neither representation normalizes combining characters or changes case.

A needle containing only the high or low half of an emoji is not valid UTF-8. Such needles require the UTF-16 fallback: the high half is a prefix, the low half is a suffix, and both are contained in the full emoji. Adjacent surrogate units encoded separately as WTF-8 also use the fallback. Genuine U+FFFD stays distinct from isolated surrogate units.

## Correctness evidence

`string-predicate-jvm-oracle.json` records complete columns/rows or exception class/message for 252 independent queries against main `4e328b0109e13c896b74004823fb049fcb19251a`, using Java 17.0.18 ARM64 and the tiny `store/testdata/jvm-v3` correctness fixture. Each query ran three times with identical output. The corpus records the exact jar digest and Java version. It includes empty strings, CJK, supplementary characters, combining sequences, NUL, isolated surrogates, matching across surrogate boundaries, all six operators, null/type handling, and left/right exception order. Regenerate from the Go module directory with:

```
python3 internal/query/testdata/regenerate-string-predicates.py /path/to/graphite-explore.jar
```

The generator compiles the existing `FunctionsOracle.java` helper and runs it locally; it starts no HTTP server and performs no performance measurement. It only updates this new oracle corpus, not any inherited corpus.

`string_predicate_test.go` also compares all six operators over 24 × 24 string pairs against the UTF-16 definition (3,456 comparisons), checks WTF-8 and malformed-string parameters through native `Execute`, and checks cancellation at both fast-path boundaries and during the UTF-16 fallback. Arbitrary malformed Go strings are checked against the existing decoder behavior, not claimed to be raw Java input-byte semantics.

The standard byte predicates do not poll cancellation internally. Cancellation is observed before validation/matching and immediately afterward; the UTF-16 fallback retains its loop checks. Full module race tests and vet are required before freezing this candidate. Performance and complete 64-graph HTTP acceptance are separate experiments; this correctness corpus does not establish a performance result or unrestricted semantic parity.

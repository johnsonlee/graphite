# Java regex matching in native Go

This package provides immutable `Compile(pattern)` / `CompileContext(ctx, pattern)` expressions, `(*Pattern).MatchesContext(ctx, text)`, and the convenience `MatchesContext(ctx, pattern, text)`. Match means the entire input must match, as with `Pattern.compile(pattern).matcher(text).matches()`. Compilation, Unicode name inflation, scanning, and backtracking check cancellation synchronously; no worker goroutine or JVM outlives a call. Compiled patterns are safe for concurrent use.

The parser and backtracking evaluator are independently written Go code. No RE2 translation, .NET regex engine, cgo, executable invocation, or new third-party module is used. `regexp2` was evaluated and rejected as a direct substitute: its native dialect is .NET, newer versions require a newer Go version, its Unicode version differs, and a timeout does not provide arbitrary context cancellation. It is MIT licensed, but permissive licensing does not establish Java behavior.

Implemented constructs include alternation, numbered/named captures and backreferences, positive/negative lookahead and variable lookbehind, greedy/reluctant/possessive quantifiers, atomic groups, nested/intersected/negated classes, Java flags, quote/hex/octal/control/name escapes, Java line/boundary rules, POSIX/Java/Unicode properties, `\R`, `\X`, `\b{g}`, and inline canonical-equivalence behavior. `SyntaxError` preserves Java description, cursor index, source pattern, and diagnostic rendering for the captured error cases. Single-code-point repetition does not consume one Go stack frame per input character.

All Unicode predicates, case mappings, script/block aliases, grapheme classes, character names, canonical decompositions/compositions, and combining classes come from Java **17.0.18**, whose Character data is Unicode **13.0**. The generators in `testdata` record those facts from a development-only JVM; runtime uses the embedded generated data. A Unicode14 emoji is therefore unassigned to the native Java17 predicate even when the host Go library recognizes it.

Some Java behaviors are easy to mistranslate. Default `\w` stays ASCII even with `(?iu)`, but Java17's default `\b` is Unicode-aware. `(?i)\p{Lu}` includes all Unicode upper/lower/title-case letters without requiring `u`. Inline `(?c)` does not trigger the constructor's global pattern normalization: literal `(?c)é` does **not** match `e` plus combining acute, while `(?c)[é]` does. The native matcher reproduces these oracle observations instead of normalizing the entire input.

`testdata/java17-oracle.json` contains 4,050 independently captured cases: targeted syntax/features and deterministic exhaustive short-string combinations. Every case, including Java syntax errors, is compared in `TestJava17Oracle`; no cases are skipped or marked known gaps. Additional tests verify exact Unicode/canonical behavior, concurrent compiled expressions, and cancellation after execution/compilation has already performed many checkpoints. These are correctness tests, not performance evidence.

This evidence does **not** prove complete Java Pattern equivalence. Remaining validation boundaries include arbitrary malformed syntax combinations, resource behavior for extremely deep nested patterns, and Java Strings containing isolated UTF-16 surrogates (ordinary Go UTF-8 strings and the current JSON pipeline cannot preserve those automatically). Captured syntax diagnostics are exact; uncaptured diagnostics remain to be differentially tested. No 100% feature-parity or latency improvement claim follows from this package's finite oracle suite.

Sources: [Java17 Pattern specification](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/regex/Pattern.html), [Unicode UAX29 revision used by Java17](https://www.unicode.org/reports/tr29/tr29-35.html), and the installed Java17 source archive for semantic inspection. No GPL JDK implementation source is copied into this package; generators record observable runtime classification/normalization facts. The repository's Apache-2.0 license applies to the independently authored Go/generator code.

Verification:

```sh
go test -race ./internal/javaregex -count=1
go vet ./internal/javaregex
```

Reproduce oracle inputs using `python3 internal/javaregex/testdata/generate_cases.py`, then compile/run `testdata/Oracle.java` against the pinned main fat JAR (used only for Gson on the development classpath). `GenerateUnicode.java` needs `--add-opens java.base/java.lang=ALL-UNNAMED`; `GenerateAdvanced.java` needs `--add-opens java.base/java.util.regex=ALL-UNNAMED --add-exports java.base/jdk.internal.icu.lang=ALL-UNNAMED`. The generators inspect private runtime data only during oracle construction; no such access occurs in Go.

## WTF-8 follow-up after the 4,050-case snapshot

The string API now also accepts WTF-8, matching the query engine's Java String representation. Isolated UTF-16 surrogates remain isolated code points; adjacent high/low surrogate units combine as Java `Character.codePointAt` would. The prior paragraph's isolated-surrogate limitation describes the original frozen snapshot. `testdata/java17-wtf8-oracle.json` independently adds 122 cases with UTF-16 unit arrays, including `\p{Cs}`, graphemes, canonical properties, captures, raw/escaped surrogate patterns, valid supplementary pairs, and syntax diagnostics. The original `verification.json` is unchanged; `verification-wtf8.json` records follow-up validation.

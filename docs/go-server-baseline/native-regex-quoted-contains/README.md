# Java quoted-contains regex correctness oracle

Two actual Java17/JVM runs each capture609 `java.util.regex.Pattern` cases and68
public Cypher cases on main revision
`4e328b0109e13c896b74004823fb049fcb19251a`. Both complete outputs are equal across
runs. Each direct case calls **Pattern.compile first, then matcher.matches**.
No shortcut implementation supplies expected results. These runs contain no
performance measurements and establish no speedup.

Reproduce into new external directories:

```sh
python3 docs/go-server-baseline/native-regex-quoted-contains/prepare.py
python3 docs/go-server-baseline/native-regex-quoted-contains/run.py /tmp/quoted-regex-fresh-a
python3 docs/go-server-baseline/native-regex-quoted-contains/run.py /tmp/quoted-regex-fresh-b
python3 docs/go-server-baseline/native-regex-quoted-contains/verify.py
```

`prepare.py` deterministically regenerates the frozen case files. `run.py`
compiles the helper and invokes actual Java17, with the pinned main JAR SHA256
`91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`.
Receipts freeze the actual Java executable, runtime modules/JVM library/release,
main executable and relevant embedded class bytes, Kotlin evaluator/parser
source, helper source, workload and generated case inputs. `verify.py` verifies
the committed captures, references, complete repetition and fixture audit.

The exact main64 regex cases are892–894, with literals
`GraphitePressureAbsent45zeroX`, `org.apache.tika.`, and `org`. The original full
queries and parameters are retained verbatim in `actual-main64.json`; public
controls run these exact queries on the existing all-types correctness fixture.
Their results are empty there. Additional positive public parameter controls
exercise the same actual regex strings with matching text.

## What the observations establish

- All118 cases whose pattern and input satisfy the proposed strict conditions
  have actual Java results equal to literal substring containment. The pattern
  is exactly `.*\Qliteral\E.*`, with nonempty ASCII literal, no CR/LF or embedded
  `\E`; the input is entirely ASCII with no CR/LF. Eligibility fields in specs
  are explicit test classifications, not observed engine dispatch decisions.
- First/middle/final and repeated hits match. Empty input and absent substrings
  fail for nonempty literals. ASCII tab, vertical tab, form feed, NUL and DEL do
  not invalidate Java dot matching. CR, LF, CRLF, NEL, LS and PS prevent the
  default `.*` from consuming those line separators. A literal containing CR or
  LF can itself consume one, which is another reason to exclude such patterns.
- Non-ASCII BMP characters, supplementary characters, isolated high/low UTF-16
  surrogates, and surrogate pairs surrounding a literal retain their actual
  Java results. They belong to the fallback input domain. Metacharacters and
  backslashes inside a quote are literal, including a trailing literal slash.
- Empty quotes, embedded quote terminators, `Pattern.quote`-style escaped `\E`,
  inline flags, anchors, alternatives, reluctant/prefix variants, incomplete
  quoting and malformed classes/groups/escapes are included as fallback or
  compile-error controls. All68 compile errors retain exact class, message,
  description and Java character index. Compile errors must never be converted
  to a failed boolean match by recognizing the apparent text shape first.
- Public Cypher regex evaluation returns null immediately when its left value
  is null or nonstring, skipping even a division-by-zero or unknown-function
  right operand. A string left value evaluates the right expression; a null or
  nonstring right value then returns null. Malformed regex compilation raises
  `java.util.regex.PatternSyntaxException` with the full original message.
- Both scalar RETURN and tested MATCH-WHERE OR expressions evaluate the left
  side and then the right side. `true OR malformedRegex` still errors. A regex
  error before division wins; reversing the operands yields Division by zero.
  A true successful quoted match does not suppress the right arithmetic error.
  This ordering is independent of the proposed inner matcher shortcut.

There are541 successful direct compilations/matches and68 compile errors per
run; public cases have56 successes and12 errors. `public-main.json` preserves
complete columns/rows/value types, source index state before/after, phase,
qualified/simple error names, messages, regex details where applicable and
stacks. Root separately owns cancellation and native dispatch tests.

## UTF-16 and fixture evidence

`pattern-main.json` includes `patternUTF16`, `textUTF16` and, for errors,
`errorUTF16`. They are authoritative for lone surrogates. A Go test must rebuild
its WTF8 representation from these units; ordinary JSON string decoding can
replace an unpaired surrogate. JSON source strings and parameters are retained
with explicit surrogate escapes too. Public parameter cases can be correlated
with direct cases using `spec.directPatternCase`.

Each public case loads a fresh physical copy of the original-main-produced
all-types fixture. Each run has68 copies,680 copied source files, no explicit
fixture mutations, and136 mapped nodeoffset/typeindex sidecars created by main.
Every original source/copied file remains unchanged. The original input fixture
is never loaded directly. The two before/after manifests, source manifest,
receipts and raw stdout/stderr are retained separately.

Stable consumption files are `pattern-cases.json`, `pattern-main.json`,
`public-cases.json`, and `public-main.json`. Compressed `repeat-*` files retain
the second complete captures. The explicit `oracle-manifest.json` includes only
this oracle's files, leaving other agents' test and benchmark files outside its
ownership.

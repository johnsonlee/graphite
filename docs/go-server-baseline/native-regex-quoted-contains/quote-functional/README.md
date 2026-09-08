# Java quote preprocessing compatibility correction

The preexisting Go engine at `bc708a810fce0d111988c72a3105820bfd1cf06e`
does not reproduce Java's regex quote preprocessing. For example,
`.*\Qfoo\Ebar\E.*` reports an illegal-escape index of 13 in Go but 9 in actual
Java17. Java removes quote delimiters and escapes quoted text before parsing;
its syntax error displays the original source with an index from the transformed
code-point sequence. A fixed offset adjustment would be wrong for punctuation,
initial quoted digits, multiple quotes and supplementary characters.

This functional correction applies the Java17 `Pattern.RemoveQEQuoting`
transformation before the ordinary parser, keeps the original source for error
messages, and preserves TABs in the UTF-16 caret prefix as
`PatternSyntaxException.getMessage` does. Both the initial scan and expansion
poll for cancellation. It introduces no quoted-contains execution shortcut.
`production.patch` and `regex.go.txt` retain the staged functional-only version;
the separate performance candidate remains outside this correction.

The independent original-Java reference is the parent 609-case corpus plus the
77-case quote-index controls. Each has two complete identical actual-JVM runs,
with authoritative UTF-16 arrays for inputs and errors. These are 686 case/text
combinations, not 686 distinct regex patterns. The small fixtures and controls
are used only for correctness, never performance.

`run-baseline.py` copies the frozen previous module into a fresh external path
and adds only the actual-Java comparison test. All 279 original internal Go files
are byte-identical to the recorded baseline. Its retained test process exits 1:
598 cases pass and 88 fail, with all 686 executed and 2,533 recorded inputs
unchanged. See `baseline/receipt.json`, `baseline/test.jsonl` and
`baseline/engine-identity.json`. These failures are preexisting evidence and are
not deleted or recategorized as passing tests.

The functional-only verification runs against an independent full module copy,
with only the three staged source/test files overlaid and the performance files
absent. Full-module race tests and vet pass across all 14 tested packages; all 686
Java oracle assertions pass and all 16,498 inputs remain unchanged. The initial
missing Kotlin sibling-source setup failure is retained in `failed-setup/`;
adding the three required source files precedes a complete passing rerun.

Independent `valid-integration/` captures add 2,030 actual-Java comparisons
across classes, quantifiers, comments mode and preceding escape fragments. Two
Java runs and two functional-only Go runs agree exactly: 1,643 successful
compilations/matches and 387 errors. The previous Go engine differs on 174 of
these cases, including 46 valid patterns rejected, eight invalid patterns
accepted, 119 diagnostic differences and one wrong boolean result. For example,
`a\Q\E?` matches empty text in Java and the correction but returns false in
the old Go engine. These counts describe a separate corpus and are not combined
with the 88 baseline failures as a count of distinct bugs.

The correction also completes three new real64 correctness captures from its own
frozen module: cold replay, warm prewarm and startup-prepared replay. Each executes
all 1,267 original cases in order, preserving 1,266 successful results and the
original `four-or-graph-id-targeted` error. Public outputs and seven graph-state
fields match pinned main across 486,848 observations; all 1,152 original graph
files remain unchanged in each runtime. All three comparators pass. The original
all-success gate still fails, and formal warm is still unprepared. Raw streams
are losslessly archived with verified decompressed hashes. `correctness-audit.json`
binds the captured module to the staged functional source files.

This correction has no performance result. Regression and timing of the
subsequent optimization remain separate. No result here establishes whole-server
fidelity or 10x per-case P95.

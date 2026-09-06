# Native Cypher parser

`Parse` and `ParseExpression` run generated Go ANTLR code. The grammar is the
existing `graphite-cypher/src/main/antlr/CypherLexer.g4` and `CypherParser.g4`;
there is no handwritten alternate parser and no JVM query execution path.

Regenerate from the Go module directory with:

```sh
go generate ./internal/cypher
go test ./internal/cypher/...
go vet ./internal/cypher/...
```

Generation needs Go, Java, curl, and a SHA-256 tool. `generate.sh` pins ANTLR
4.13.2 and verifies its distribution checksum. Set `ANTLR_JAR` to an already
available copy to avoid downloading. The server uses only the Go runtime,
`github.com/antlr4-go/antlr/v4` v4.13.1. Generated output is committed so ordinary
Go builds do not need Java. The normalization command removes the generator's
unreachable label-use sentinels and unused labels; it makes no grammar or AST
changes and keeps `go vet` enabled for generated code.

The adapter covers read-only MATCH/OPTIONAL MATCH, pattern bindings, label and
relationship alternatives, directions, bounded and unbounded hop ranges, WHERE,
RETURN/WITH DISTINCT, ordering, SKIP/LIMIT, UNWIND, UNION/UNION ALL, and the complete
expression grammar: arithmetic/boolean/comparison/string predicates, parameters,
function calls, lists, ordered maps, CASE, comprehensions, predicate functions,
indexing, and slicing. The execution package determines which parsed operations
it can execute. Parsing a relationship does not assert traversal support.

Statement preprocessing matches Kotlin: blank input produces an empty clause
pipeline; multiple semicolon-separated fragments are trimmed and flattened into
that pipeline. Only UNION introduces an execution branch. The source adapter
splits raw text before lexing, so semicolons in quoted strings and comments retain
the same source quirks. CREATE/DELETE/SET/REMOVE are represented as typed ASTs;
MERGE lowers to CREATE as in Kotlin. Read-only execution rejects these clauses.
Small decimal integers retain Int32 width; large, hex, and octal integers retain
Int64 width. No additional bracket nesting limit is imposed.

`ParseContext(ctx, source)` checks cancellation during character consumption,
lexer lookahead, token prediction, and AST conversion. Both canceled contexts and
expired deadlines propagate their standard Go errors. Tests separately interrupt
long tokens and prediction after tokenization, and parse 600 nested brackets using
a source-only JVM oracle with sufficient stack space.

Compatibility work remains for unpaired UTF-16 surrogate strings, malformed
Unicode escapes, and exact JVM error positions/messages. Full native query
execution still has outstanding coverage beyond parsing. These are gaps, not a
claim of complete Cypher compatibility or the requested 10x P95 improvement.

`testdata/ParserOracle.java` captures actual main ASTs and scalar execution
results without network requests. `statement-jvm-oracle.json` records 21 cases;
the external-package execution test verifies their results, including blank
pipelines, raw statement quirks, mixed-column UNION, and mutation rejection.

Concrete AST tests exercise binding identities, operators, argument values,
precedence, source text, map order, comments/quoting, keyword ambiguity, graph
pattern ranges, and invalid input. Query parity evidence is maintained by the
native execution tests and the external HTTP differential harness.

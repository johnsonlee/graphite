# Independent Attempt 5 string-predicate audit

The audit read the frozen delta in `/tmp/graphite-go-string-predicate-attempt5` and ran tests only in an external module copy. All production Go files were compared against frozen Attempt 4: **only `internal/query/eval.go` differs**. The delta adds the UTF8 validity import and the branch for STARTS WITH, ENDS WITH, CONTAINS and their NOT forms. Lowercase conversion, regex, node handling, candidate slots and other expression paths are unchanged.

The guarded byte operations are equivalent for two well-formed strings. A nonempty well-formed UTF16 needle cannot begin with an isolated low surrogate or end with an isolated high surrogate; any supplementary character in a match must cover its complete pair. A well-formed UTF8 needle likewise cannot begin at a continuation byte. Both searches therefore match the same complete codepoint sequences. Empty-needle behavior also agrees. When either string contains WTF8 surrogate units or malformed UTF8, the original UTF16 path is retained, including Java's ability to match half of a supplementary character.

Independent checks in `string_audit_test.go` passed with the race detector:

- **24,576 comparisons:** 64 original UTF16-unit vectors × 64 needles × six operators. Expected results scan the original units directly; the test does not use the implementation's `javaUTF16` decoder to construct the expected result. Boundary units, isolated halves, valid pairs, reversed pairs, embedded pairs, NUL and U+FFFD are included.
- **60 operand-order cases:** a null/nonstring left operand skips a failing right expression; null/nonstring right operands remain null even for NOT; left errors precede right errors; errors on an evaluated right operand still propagate.
- **12 cancellation assertions:** each operator cancels at entry and exit of the byte branch and does not return a Boolean after observing cancellation.

The new cancellation checks bracket the standard-library validity/search operations. Those operations themselves do not poll context; this audit makes no maximum cancellation-latency claim for arbitrary input lengths. The fallback retains its existing unit-level polling. No wall-clock behavior was benchmarked.

No concrete semantic counterexample was found within this bounded audit. These are independent comparisons with the original UTF16 definition, not a newly captured JVM oracle or a performance result. The implementation agent separately owns its 252-case main JVM oracle and full-module race/vet evidence.

`snapshot.json` embeds the verified five-file freeze manifest (patch SHA `6f3b553bc33ff58d5fc7701156a49406e61fab070c297efd9dbe27a6698f973d`) and the external copy path. `independent-tests.log` is the complete output of:

```sh
go test -race -v ./internal/query -run TestExternalPredicate -count=1
```

No agent/root worktree was modified, no production code was changed by this reviewer, and no performance or 64-graph workload was run.

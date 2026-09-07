# Attempt19 candidate: avoid compiled string-atom expression boxing

No performance run or performance claim. Base39eedb33 contains A18. The new
candidate specializes only CONTAINS/STARTS WITH/ENDS WITH in distinctAtomMatches,
keeping nonstring early false, optional javaCase and all other operators on the
original binary fallback. Both literal eval cancellation checkpoints remain
explicit. The existing six-op string matching block is moved byte-exactly except
indentation and parameter name to stringPredicate; general binary typing/null/
right-evaluation order, UTF8/UTF16 matching and NOT behavior remain unchanged.
No source, decoder, task, registry, cache, row key or lowercasing optimization.

Allocation hypothesis comes from existing real64 A17 dense-repeat sampled data:
104,334,904bytes at construction of binary/literal interfaces,38.93% of sampled
allocation. This is not a CPU share or speed prediction. Separate frozen audit:
/tmp/graphite-a17-dense-allocation-audit/manifest.json
12ec5886143f85ae9ff94c0af236bd2381b13e2e16dbf4d0184ee7817a7bda3e.
A18 does not change that indexed path. Real64 paired measurement is still required.

The original2437 module Git blobs are verified. Candidate2439 module plus59
external references are archived with full hashes. Four changed files comprise
indexed_distinct.go, eval.go, new string_predicate.go and one new test file.
Original helper reference body matches exact committed bytes. Matching-block
extraction has a separate byte equality proof. The reference still calls binary,
so full candidate comparisons share the extracted matcher; independent review
should overlay original eval.go to make that cancellation reference independent.
The explicit UTF16 definition and existing actual-JVM predicate oracle separately
cover results including all six string operators and isolated surrogate values.

Final original-production plus new tests and final candidate fullmodule race pass;
candidate vet and targeted boundary race3 pass. The new27-string×27-string×6op×2lower
matrix compares original helper and explicit UTF16 definition on admitted string
ops. Short cancellation cases enumerate all observed checks; the6000-codepoint
ASCII case samples first/middle/last lower checks, both literal checks and matcher
entry/exit plus first checkpoint beyond completion. Every cancellation uses real
WithCancel and verifies the underlying cancellation fired. Nonstring values
return before any check, preserving the private helper's existing boundary.

Initial fullmodule run timed out180s after a quadratic exhaustive long-string
cancellation test consumed excessive test time. Initial test/log/partial76 output
and command are retained. Only test scheduling selection changed; no production
had changed at that point. Final original baseline and candidate use exactly the
same bounded test. Long-case actual checkpoint logs are retained in named evidence.

All76 complete captures remain unchanged publicly:73 raw exact,3 only77 mappedView
boolean leaves in explicitly verified40-source histories; no retained differences.
The first strict comparison exit1 is retained. Original1048 remains1044/F4, prior
580 DISTINCT/122 ordinary preserved. B595 full JSON-value responses and53 ordered
traces pass with explicit equal trace counts;166 numeric spellings remain listed.
No old expectations or source fixtures were rewritten. Finite tests do not prove
100%Cypher parity, P95 or a main-relative speedup. Independent review and actual
shipping/real64 validation are next; no root production edits or commit here.

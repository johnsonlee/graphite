# Quoted syntax integration differential

Independent review exercised 2,030 actual Java 17 compile/match cases using 19
quoted literals in 14 syntax contexts. The contexts include bare quotes,
quantifiers, groups, character classes, range endpoints, comments mode,
preceding hexadecimal/octal escape fragments and empty quotes. Inputs include
empty, literal, repeated literal and neighboring characters. This is bounded
correctness evidence; it is not performance data or proof of the entire dialect.

Actual Java succeeds in 1,643 cases and rejects 387 patterns. Both complete Java
runs and both staged-only Go runs agree exactly on every case. The staged source
contains the functional preprocessing and diagnostic fix; it excludes the
unstaged quoted-contains matcher optimization. Source hashes are verified before
and after execution. `staged-source.tar.gz` is the exact standalone review module,
including the unchanged embedded Unicode datasets. Its package path was relocated
to `quotecheck/javaregex` so the diagnostic helper can import it; production files
are the exact index bytes at capture time. No production or test file was edited.

Unchanged HEAD `bc708a810fce0d111988c72a3105820bfd1cf06e` differs on 174 cases:
46 Java successes rejected by Go, 8 Java rejections accepted by Go, 119 differing
compile diagnostics, and 1 differing successful boolean match. Examples include
empty quotes joining `a` to `?` (Java matches empty text), empty quotes inside a
character class, quoted text completing a preceding hexadecimal escape, and
empty quotes followed by a dangling quantifier. Thus this correction repairs
valid pattern semantics as well as error indices. The original mismatches are
retained in `head-go.txt`; none were filtered out.

`cases.json` is the ordered input corpus. `QuoteReview.java` embeds the same
UTF-8 input pairs as Base64. Each output line is `OK true`, `OK false`, or `ERR `
followed by Base64 of the complete exception message. These additional cases
contain supplementary characters but no unpaired UTF-16 surrogates; those remain
covered by the separate 609-case and 77-case oracle corpora.

To reproduce, extract a source archive into a new directory, copy `cases.json`
and `QuoteReview.java` there, and run the exact commands in `receipts.json`,
adapting only their working directory and the input path. The Java helper uses
actual `java.util.regex.Pattern`; no expected result is computed from a native
implementation. `verify.py` checks all line counts, repeated outputs, comparison
counts and all archived hashes. There was an initial Go compile failure because
the three embedded datasets were absent in the first temporary package copy;
they were then copied verbatim from the index before any successful Go run.

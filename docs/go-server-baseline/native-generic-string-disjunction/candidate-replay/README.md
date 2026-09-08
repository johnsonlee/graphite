# Generic string disjunction candidate diagnostic captures

Both complete 201-case correctness captures are retained, including every
mismatch. These captures contain **zero performance measurements** and establish
no P95, speedup, or full real64 acceptance result.

The baseline is Go `513e2b96d4cd5136560461d06f3b804e6aa67c41` under
`../go-baseline/`. Candidate v1 contains the fused generic SID route. Final v2
adds the empty-type allocation guard and the new 83-case query test file. Both
candidate captures use fresh complete external module copies and fresh per-case
fixture copies. `frozen-candidate.json` independently records the final two
production-file hashes against the workspace and baseline Git objects.

| Each original Java reference | Baseline | Candidate v1 | Final v2 |
| --- | ---: | ---: | ---: |
| Equal public cases out of 181 | 157 | 166 | 166 |
| Equal provider-wrapper controls out of 20 | 19 | 19 | 19 |
| Total differing cases | 25 | 16 | 16 |

Each candidate resolves the same nine baseline public mismatches: the global-miss
raw filtering controls for EnumConstant (SID 0, SID 1, malformed tail),
LocalVariable (SID 0, SID 1, malformed tail), and Field (SID 0, SID 1, SID 2).
No additional case becomes unequal in this bounded matrix. Ten cases change
compared fields from baseline: these nine plus annotation-bad-tail-global-miss,
whose error message changes while remaining unequal to original main. This is
not a blanket regression or fidelity conclusion.

The remaining 16 differences are two empty-needle fallback row orders, six
isolated-surrogate public outputs, seven malformed-tail/load-boundary public
cases, and one provider payload-ID/order-wrapper control. Both original Java
empty-needle order variants are compared separately, without sorting or dropping
rows. Provider controls call Go's existing `mainStringCandidates`, which includes
merge/order checks absent from the original private provider API. Their scope
remains deliberately explicit; the remaining provider difference does not prove
an identical private-API implementation.

Final v2 and v1 have identical compared outputs, error classes/null messages/
UTF16 messages, phases, yielded prefixes, and before/after retained/mapped states
for all 201 scenarios. The two raw JSON files differ in six Go stack traces
(addresses and source locations); both unmodified files are retained. Go's
original-main diagnostic counters remain unavailable and are not included in the
equality claim. Public captures explicitly enable work tracking and preserve the
original source-scope flag. All parameters and result comparisons preserve UTF16.

Compilation and capture exit 0 for both versions; strict comparison controllers
exit **1** due to the remaining differences. Each capture preserves all 3,036
original fixture files unchanged and adds only 22 optional persisted CallSite
index files. Exact source and executables, input/source/fixture hashes, commands,
raw JSON, comparisons and controller receipts are archived. Compiler/external
Go dependency hashes are checked against the baseline after candidate build;
production-source identities are audited separately. Final v2 has 2,536 compiled
module files including the diagnostic helper; v1 has 2,535.

`archive.py` records and audits the two original external destinations.
`initial-archive-audit-failure.json` preserves two corrected source-set audit
assumptions; neither changed a capture. `verify.py` verifies local artifact
hashes, exact source/binary bytes, original fixture integrity, and recomputes both
complete comparison ledgers against both original Java outputs. Run:

```sh
python3 docs/go-server-baseline/native-generic-string-disjunction/candidate-replay/verify.py
```

For another candidate, use the unchanged copied `run.py` and `replay_test.go`
with a fresh complete module and output directory. Their parent oracle path is
the same as the baseline runner's:

```sh
python3 docs/go-server-baseline/native-generic-string-disjunction/candidate-replay/run.py \
  --module /absolute/path/to/new-candidate-module \
  --output /absolute/path/to/new-capture
```

The full real64 replay, work counters, and formal performance are independent
acceptance steps. This archive makes no claim that those steps have passed.

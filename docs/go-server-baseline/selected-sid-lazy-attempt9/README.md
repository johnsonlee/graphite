# Attempt 9: lazy selected SID cursor, not integrated

This candidate advanced one source-local string-table cursor only at an existing
lookup point, retaining first SIDs for wanted projection values. It preserved
the current Go lookup contract and passed independent helper tests, whole-module
race/vet, and all 17 original complete oracle comparisons.

It was rejected before performance measurement after the main audit established
that loaded StringTable.findId uses UTF-16 binary search. Valid serialized tables
with duplicates or unsorted entries distinguish that algorithm from Go's current
first linear match. Directly porting main's lookup is the next hypothesis; keeping
the lazy cursor would leave this known compatibility difference unresolved.

No production file from this attempt is integrated. `rejected-source/` holds both
production files and the independent test; `rejected.patch` is the tracked-file
delta only (the new helper/test are fully preserved in the source snapshot).
`independent/` contains all completed checks. `design/` preserves the earlier
first-SID model, actual main method oracle and serialized tables unchanged.
Its compiled Java class remains at the external path/hash in `verification.json`.
All manifest entries, including that external class, were checked before copying.

No new64 runtime, latency, CPU or allocation measurement was made for this
candidate. The existing real64 regression motivated it; no saving is claimed.
The 1,048-case corpus still has 739 matches and 309 differences at this point.

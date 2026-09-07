# Superseded lazy selected-SID candidate review

Root supplied this independent candidate at eebec091. Its two production files
were read only; the storage reviewer added distinct_target_strings_integration_test.go.
All6 actual-helper tests passed under race, including firstSID/SID0, exactWTF8,
alias/null behavior, source-local IDs, empty table, cached cancellation, polling
boundaries and actual index.Postings returning ErrStoreClosed before a scheduled
later-lookup cancellation. The two callsite diffs preserve original target/property/
Postings order; no production correctness bug was found against the existing Go
first-linear lookup contract.

The already started complete module race/vet finished successfully. All17 native
oracle files structurally equal the complete frozen integration outputs, including
existing declined differences. This does not claim every1048 native/main pair is
equal. verification.json contains the exact source hashes and comparisons.

Root then chose a different hypothesis: implement main's UTF16 binary search at
the original lookup points, resolving the existing duplicate/unsorted-table SID
parity gap. Lazy tests were not expanded after that instruction, and this candidate
was not performance-tested or committed. All existing results are retained without
rewriting the earlier first-SID audit as binary-search evidence.

Commands already completed:

go -C graphite-server test -race -run '^TestDistinctTargetStrings' -v ./internal/query
INDEXED_DISTINCT_OUTPUT=/tmp/graphite-go-selected-sid-eebec091/selected-sid-review/oracle-native go -C graphite-server test -race ./...
go -C graphite-server vet ./...

The first command's targeted log and whole-module logs are retained here. No64
runtime, HTTP performance process, benchmark, commit or root edit was made.

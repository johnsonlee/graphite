# Main-produced CallSite v2 index correctness fixture

`GenerateIndex.java` constructs four CallSites and calls pinned main `GraphStore.save`. It then loads MAPPED and calls main's index preparation API. Reflection confirms `callSiteStringIndexLoadedFromPersistence` is true, so a silent rebuild cannot satisfy the oracle. Raw IDs and directory membership are derived from main's loaded nodes and StringTable. Signature and trigram values are read with Java DataInputStream from the already accepted sidecar. No Go writer participates.

IDs are sparse and intentionally inserted out of numeric order. The actual main-persisted order is `[17, 2, 41, 90]`; the callee-class posting row preserves this exact order. A caller class contains U+0130 and an isolated high UTF-16 surrogate, and a method has two parameters. These exercise raw field skipping and Java UTF-8 identity hashing when either identity sidecar is missing or malformed.

Reproduce from the repository root using the fixed main jar recorded in `provenance.json`:

```sh
java -Dfile.encoding=UTF-8 -Xmx256m -XX:ActiveProcessorCount=2 -cp /tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar graphite-server/internal/store/testdata/callsite-index/GenerateIndex.java graphite-server/internal/store/testdata/callsite-index
cd graphite-server
go test -race ./internal/store -run 'Test(MainCallSite|CallSite)' -count=1
go test -race ./...
go vet ./...
```

The provenance records the exact main revision, Java runtime, jar and generator hashes, source hashes, and all fixture hashes. Writer timestamp comments and Java map key presentation may change file hashes on regeneration; semantic comparisons use parsed JSON.

The 676-byte index has a numeric-value CRC footer verified by main. The test explicitly distinguishes it from CRC32 over the raw big-endian file bytes. Corruption tests modify temporary copies only; version, truncation, trailing data, overflow counts, identity, retained-byte count, CRC, directory ordering, posting ends, repeated node offset, absent node ID and invalid trigram ID are checked. Errors in required node data remain errors. Cancellation and concurrent first-load/Close tests exercise publication and mapping ownership; returned arrays remain valid after closure.

These tests use a tiny constructed graph solely for correctness. They do not establish performance, memory parity, or validation of the real 64-graph corpus.

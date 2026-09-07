# Node-only mapped decoder cursor: correctness candidate

This candidate is based on e6c3bd0c4a6f73ec85ce892bd173750dd7d62c0a plus root's
frozen DISTINCT integration patch SHA256
8b51d784f8d70674b24ef45ec4a1b4140086f416c0b3dbffa688dc51a3cc50c4.
Only the cursor delta is delivered; the integration patch is not duplicated.
Both modified files' baseline bytes match root's f9dc0aac revision. The worktree
is `/tmp/graphite-go-mapped-cursor-e6c3bd0c`. No production/root edit, commit, new
performance measurement or64 runtime was performed. This candidate has no assigned
performance-attempt number and no measured speedup/allocation-saving claim.

The archived Attempt8 profile identified bytes.NewReader and decoder.bytes as
allocation sources. The preceding read-only audit is independently frozen at
`/tmp/graphite-decoder-cursor-audit`, manifest SHA256
a21bedab44d36b22455d5c6888df66b50a01788f607f70c2f518c7aaba7b5cfe.
This change tests one implementation hypothesis: avoid the mapped Node reader and
per-scalar copying while still decoding the entire Node in the same order.

Production scope is two files:

- `internal/store/decode.go` adds a private mapped-node slice cursor, explicit mode
  flag, and scalarBytes helper. u8/u16/i32/i64 consume a checked temporary mapped
  span; the same methods on reader decoders still call the unchanged bytes method.
- `internal/store/store.go` constructs that decoder directly from the same
  `mappedData[loc.offset:]` suffix. The fallback file reader and the complete
  d.node call, version, error wrapping, and ID/tag mismatch check retain their order.

The original bytes body, str body, node/method/value/annotation deserializers,
resource/string/metadata readers and locking code are unchanged. No pool, cache,
new lock or borrowed result type is introduced. The mapped cursor exists only
inside one synchronous Node decode. Primitive widths are1/2/4/8; each temporary
byte span is immediately converted to an owned scalar. Strings still reference
Store.Strings, while slices/maps/pointers forming Nodes are allocated as before.
The explicit mapped mode also handles exhausted and empty input independently of
whether a slice is nil. No mapped bytes escape through returned Node values.

`str` intentionally retains the baseline interface and text. The contract agent's
not-yet-frozen StringTableReferenceError cause was not copied into this worktree;
when integrating that independent change, retain its str assignment unchanged.
The cursor never constructs or interprets SID errors and preserves %w wrapping.

## Correctness evidence

`mapped_cursor_test.go` adds12 top-level tests. The targeted run also executes the
existing all16-node JVM fixture golden and UnknownNodeTagError checks. It passes
under the race detector. The full module race run and vet also pass, including
existing query error-contract, indexed candidate, DISTINCT and concurrent Close
coverage. Exact logs and hashes are retained alongside this report.

The differential reader branch still executes the original owning bytes/readFull
implementation; the candidate branch executes the new mapped scalar cursor.
Both invoke the same complete node decoder, which is deliberately not rewritten.
Checks compare full/partial Node values, first error dynamic type and exact text,
remaining-byte position, and sticky first-error identity.

- All16 real JVM-written node types:431 byte-prefix cases, including empty input,
  every truncated field and each complete record. Existing concrete JVM node and
  metadata goldens separately check the decoder's expected output, not only
  agreement of two implementations.
- v1/v2/v3 annotation records: null/empty-string distinction and concrete fields;
  v2+ nested lists and forward-compatible scalar fallback. Every byte prefix is
  compared for these cases too.
- Unknown tags16/127/128/255; negative/out-of-range SIDs; negative/oversized/truncated
  collection counts; a258-level list value and every prefix crossing the existing
  nesting guard. Tag errors preserve errors.As(*UnknownNodeTagError).
- Scalar sign/endianness and bounds; float32/float64 positive/negative zero,
  positive/negative infinity and NaN payloads are compared by exact bits.
- Actual Store.Node mapped-versus-file fallback checks preserve decoder errors
  before ID mismatch, ID/tag mismatch text, unknown tag before mismatch,
  truncation text and ErrNodeNotFound. The outer node-ID wrapping is tested too.
- Reader-backed declared-bound preflight versus physical short reads remain
  distinct: no Read call/no position change for the former, io.EOF or
  io.ErrUnexpectedEOF with the existing requested-length advance for the latter.
  Returned reader bytes remain owning copies, including bytes(0).
- A valid CallSite projection prefix with an invalid trailing argument count still
  fails after full consumption. No field decoding is skipped for projection.
- The deliberately overlapping index counterexample preserves the original suffix
  to EOF, not an invented next-node-offset bound. Tightening record bounds would
  change existing behavior and is outside this change.
- All retained Nodes in the16-kind, indexed four-CallSite and isolated-surrogate
  persisted fixtures remain equal to independently EAGER-decoded values after
  CandidateNode has returned and Store.Close has unmapped its input. Calling
  CandidateNode after Close still returns ErrStoreClosed.

The ordinary Close contract remains unchanged: CandidateNode holds the existing
lifetime read lock across the complete Node call; direct Node callers still must
keep the Store open. This candidate does not add nested RLocks, make bare Node
safe against an arbitrary concurrent Close, or alter external-file-mutation/SIGBUS
behavior. Cancellation is checked at the same existing entry boundaries.

## Verification commands and scope

From the worktree root:

```sh
go -C graphite-server test -race -run 'TestMappedCursor|TestDecoderAudit|TestJVMFixtureAllNodesAndMetadata|TestUnknownNodeTag' -v ./internal/store
go -C graphite-server test -race ./...
go -C graphite-server vet ./...
go -C graphite-server build -gcflags='-m=2' ./internal/store
```

All commands completed successfully with Go1.22 on macOS ARM64. Compiler escape
analysis confirms the new mapped decoder remains on the stack at Node's call
site; the Node path no longer constructs bytes.Reader. The reader-based branch
still contains its expected allocations. This is static mechanism evidence,
not a measured runtime-allocation or latency result. Root will decide whether and
when to compare the candidate on the original real64 workload.

An initial targeted-test setup command used a duplicated graphite-server path
from inside that directory and did not install the new test file; it was corrected
before the archived final targeted/full-module runs. Its preliminary log remains
in the worktree's cursor-freeze directory and is not counted as cursor coverage.

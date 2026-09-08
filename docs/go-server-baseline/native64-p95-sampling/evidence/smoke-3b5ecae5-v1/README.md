# Three-state tooling smoke capture

This is a completed tooling smoke check on 64 real persisted graphs, with **one
sample per case, runtime and state**. It does not establish P95 or 10× acceptance.
The complete [summary](smoke-summary.json.gz) retains 3,801 state/case rows and
marks every row `insufficient`; the required floor is 200 samples per runtime.

| State | Ordered cases per runtime | Successes | Original errors | Samples per case |
| --- | ---: | ---: | ---: | ---: |
| cold | 1,267 | 1,266 | 1 | 1 |
| startup-prepared | 1,267 | 1,266 | 1 | 1 |
| warm-after-failed-prewarm | 1,267 | 1,266 | 1 | 1 |

All three main/Go pairs preserve case 821, `four-or-graph-id-targeted`, and its
original `IllegalStateException: Unsafe expression reached parallel string
projection`. Its timing remains labelled time-to-failure. Main's timed observer
records the exception class; the message is explicitly reference-derived. Go's
message is observed. There are no censored timeouts or new result differences.
All six process receipts report exit 1 because the original all-success gate
failed, and their recorded intervals do not overlap.

The third state also preserves both complete 1,267-case prewarm ledgers. It is a
diagnostic continuation after the original prewarm gate failed, not a prepared
formal warm benchmark. `measurementAcceptanceEligible=false` also remains set
because equivalent Go resource/work accounting and full server fidelity are
unfinished. The summary command therefore correctly returned exit 1; its six
`query-failed` issues are the same known case across both runtimes and all states.

Source campaign: `/Users/johnsonlee/.codex/benchmarks/graphite/p95-3b5ecae5-smoke-v1`.
Frozen build: `/Users/johnsonlee/.codex/benchmarks/graphite/p95-3b5ecae5-build-v1`.
Go engine: `3b5ecae5c9a4c1425f18a3b5af3e373b41a184eb`.
Pinned main: `4e328b0109e13c896b74004823fb049fcb19251a`.

[The manifest](archive-manifest.json) records the exact summary command, sources,
file hashes, compression and deduplication. Full normalized records, original
timing/signature ledgers, process receipts, host inventories, small fixture audit
receipts, cleanup receipts and build metadata are retained. The repeated
`actual-cases.json` is stored once. Gzip decompression reproduces exact source
bytes; controller hashes refer to those uncompressed bytes and original paths.
Unpack into a new temporary tree before attempting a relocated replay of the
summary. No original controller paths or hashes were rewritten for the archive.

During archival, all measured and diagnostic-prewarm ledgers were independently
compared with the original canonical signatures, and normalized clocks were
checked against their raw runtime ledgers. A separate pass reread every source
and archive file, checked compressed and uncompressed hashes, and verified the
deduplicated copies: [copy verification](archive-copy-verification.json).
The controller had already deleted graph clones after pair verification; this
archive retains those original audits and does not claim to reopen deleted data.

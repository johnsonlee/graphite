# Attempt 14: synchronous generic DISTINCT cursor

The candidate replaces Attempt11's per-node producer/request rendezvous with
explicit synchronous position advancement. It restores that generic DISTINCT
executor with its independently corrected Java UTF16 equality and preserves its
projection, batching, source merge, required exhaustion and ownership rules.
The source lifetime context and each current request context remain separate, so
completion of one task wave cannot poison a later wave. Preparation still uses
the original A6/A7 certificate and all-source barrier. This is not the separate
main-compatible lazy source implementation.

Base is `082a4736`, including ordinary projection and Attempts12/13. The frozen
complete patch from87aaf0ad is
`5a2e7eed0f304ec038932f4cb7cf51139b9f976fa6368d77142daaf2b8d7ae65`.
The synchronous delta from the rejected Attempt11 source is
`50b779dc89b87796ec178d726300a7525204ad49a1c16c887073c21630032991`.
The two proposals are not compared across different historical baselines here:
the actual paired diagnostic uses clean082a4736 and that revision plus the
complete candidate patch. Ordinary, cancellation and decoder changes are held
constant in both processes.

All166 provider manifest files and all9 independent manifest entries were freshly
verified. The integrated93 files match the frozen provider except engine.go,
whose sole difference is the already-committed ordinary planner hook. Every
other base module file is unchanged. Full-module race/vet pass, and all2186 files
remain unchanged through those checks. Independent review includes actual task
cancellation/join, later-wave continuation, preparation-on-first-demand, Store
Close between demands and restartable legacy walking. See `provider/` and
`independent/` for the exact tested boundaries and unproven scheduling guarantees.

The34 previous ordinary integration artifacts were compared in full. There are
two changed complete responses, primary indices242 and262: scoped direct-id and
literal DISTINCT now stop before the corrupt unmatched tail, matching main.
Thus the unchanged original1048 corpus improves966→968, leaving80 differences
(56 main-success/native-error,20 differing errors,4 differing successful rows).
The432 generic fault outputs equal the frozen provider. All48 rolling ordinary
responses remain equal;32 speculative40-source mappedView booleans differ in
before/after diagnostics. Their exact changed paths and raw states are retained.
The first root collector incorrectly required identical object keys before
classifying the two error-to-success fixes; that collector failure is preserved.
No production or test change was made to satisfy it.

## All64 paired diagnostic

Both processes use the same isolated macOS copy-on-write clone of all64 real
persisted graphs, copied using `/bin/cp -cRp`. The fixture is10,338,207,518 bytes
across1152 graph files. Each process freshly verifies every hash and complete
catalog counts. Store.Close and real sidecar persistence remain enabled. The
original graph paths, exact commands and environment are recorded in
`preparation.json` and the per-process receipts. Source files and binary hashes
were checked again after successful process exit. All1152 original graph files
and all1152 clone files were rehashed after both processes closed; every hash
remains equal, with no extra graph files.

The fixed sequence is two prefix queries, two dense DISTINCT queries, then one
ordinary query. All five full output bodies equal pinned main. Timings include
ExecuteCross and the actual server response encoder. CPU sampling is off;
getrusage supplies CPU, and allocation/GC deltas bracket each request. Forced GC
and heap snapshots occur outside the request intervals. Go1.22.0 darwin/arm64,
16 CPUs,64GB host; background small correctness/build and evidence I/O were
present, with no other64-graph execution overlapping the paired run.

| Query | Execute + marshal seconds, base → candidate | Allocation bytes, base → candidate | Process CPU seconds |
|---|---:|---:|---:|
| Prefix first | 18.624455 → 9.812521 | 9,102,896,136 → 1,837,258,232 | 24.664961 → 53.656092 |
| Prefix repeat | 12.059742 → 2.204654 | 8,320,045,544 → 1,054,280,552 | 18.916477 → 13.878162 |
| Dense DISTINCT first | 4.118135 → 4.105638 | 5,804,046,608 → 5,804,046,200 | 22.697799 → 22.481509 |
| Dense DISTINCT repeat | 4.117561 → 4.092250 | 367,946,600 → 367,932,504 | 19.206036 → 19.024614 |
| Ordinary after that history | 0.001797 → 0.001796 | 1,630,224 → 1,629,952 | 0.001834 → 0.001822 |

The prefix repeat improves latency, CPU and allocation; the first prefix also
improves wall time and allocation but uses substantially more CPU. That first
request CPU increase is an explicit tradeoff, not labelled noise. Other paths
retain their prior allocation and observed latency. GC counts depend on the
changed process history and do not alone establish a pause or heap benefit.
These are single ordered instrumented observations, not HTTP P95, an isolated
cold-start guarantee, peak RSS, or main-relative10× acceptance.

Reproduce with new output directories, sequentially:

```sh
python3 docs/go-server-baseline/native64-generic-sync-attempt14/run-profile.py --worktree /tmp/graphite-go-generic-sync-base-082a4736 --out /tmp/fresh-sync-base
python3 docs/go-server-baseline/native64-generic-sync-attempt14/run-profile.py --worktree /tmp/graphite-go-generic-sync-root-082a4736 --out /tmp/fresh-sync-candidate
```

Independent actual shipping verification passes42/42 HTTP200, complete typed
main bodies, protocol headers and all64 catalog entries. All239 source/embed
inputs match final root; the binary SHA256 is
`bd1d035cbb366876ddd646320b7ef748a52dc559da1956ee6b1765da8cd0ce67`.
Default60-second timeout/capacity4, profiling environment unset, no diagnostic
helpers or tuple code. PID91470 exited normally on SIGTERM with status143,
forcedKill=false, and port18863 was released. All1152 graph files plus two root
TSVs are unchanged in both original and HTTP clone. Root independently repeated
the complete type-sensitive comparison and verified shipping input hashes,
catalog, executable identity, fixture receipts and process cleanup. See
`http-independent/` and `root-http-verification.json`.

Root's source collector initially encountered an existing ignored Mach-O build
output `graphite-server/graphite-server`. Its hash and ignore rule are retained;
it was left untouched and excluded from source identity. All2186 source/test
fixture files equal the tested candidate. No generated binary is substituted
for the independently verified shipping executable.

Keep the synchronous cursor with its explicit first-request CPU cost. The
rejected per-node rendezvous is not restored. Exact tuple construction, the main
lazy source, streaming pagination and other known compatibility gaps are
separate candidates. Full parity, repeated main-relative P95 and the required
benchmark-regression-gate remain unestablished.

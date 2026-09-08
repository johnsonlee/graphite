# Quoted-contains timing smoke

Three completed real64 main/Go pairs; one sample per case/runtime/state. Each pair retains all 1,267 cases, with 1,266 successes and original case821 failure. The 3,801 summary rows are insufficient for P95; summary exit 1 and measurementAcceptanceEligible=false are expected. No 10× acceptance is established.

`baseline-comparison.json` contains every successful old/new Go point ratio, sums and 20 largest new Go timings per state. These are single observations from separate campaigns, not a causal speedup estimate or latency P95. Original failures remain separate. Warm is diagnostic continuation after failed prewarm, not formal original warm. Main failed-query messages are reference-derived, not timed observations.

The archive retains controller, normalized and raw timing/warm ledgers, process/fixture/cleanup receipts and build metadata. Repeated actual-cases bytes are stored once. Gzip decoding reproduces original bytes; controller paths and hashes have not been rewritten. The native binary and full build snapshot remain at the recorded external build path. `measurement-source.bundle` preserves the measured commit before any later amend. Graph clones were already cleaned by the controller; archival reverification checks retained ledgers and original audit receipts, without reopening deleted clones.

See `archive-manifest.json` for source hashes and `archive-copy-verification.json` for independent post-copy byte verification. Re-running archive-timing.py verifies this completed archive without overwriting it.

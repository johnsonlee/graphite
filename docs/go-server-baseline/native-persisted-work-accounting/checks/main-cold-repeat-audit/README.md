# Independent actual-main cold repeat

This correctness-only repeat preserves the original 64 graph order and all 1,267 actual benchmark cases. All parsed capture records, including 162,304 before/after graph-state observations, exactly equal the original main capture. Both same-source Go cold captures have zero public-result differences but the same 15,162 `mappedRangeCount` differences. This evidence does not justify relaxing the state comparison. The original case 821 error and runtime exit 1 remain recorded.

`run.py` verified the original 57-component classpath's 330 file hashes before and after using the unchanged MainReplayCapture with `-Xmx8g`. It cloned the 1,152 original graph files (10,338,207,518 bytes), relocated only the new graph manifest, and verified both clone and original fixture bytes afterward. The original reference and existing Go comparisons were not changed. The configured JDK is OpenJDK 17.0.18; this repeat records its binary identities, but the historical receipt did not preserve a JDK binary hash, so historical binary identity is not claimed.

`compare.py` retains full comparisons, the original error, and both Go state-difference lists. `archive-verification.json` binds 26 byte-preserved external artifacts to repository copies; compressed logs and JSONL retain their uncompressed hash and size. The input-source archive preserves the referenced harness and identity documents. The graph clone, original classpath binaries, and native module snapshots remain external and are identified by receipts rather than duplicated here.

The JVM (PID 30274; controller session 28320) is terminal, exit 1 from the original all-success gate. No performance or P95 acceptance is claimed. No further 64-graph runtime belongs to this audit.

# Lazy core / firstTask independent review

No blocking correctness or lifetime defect was found in this bounded review of `main_fixed_workers.go` SHA-256 `18748857606af60bc785f0051004dc7be87c137eae30629581d1d0501efbface` and tests `6790f97f35bbf31ed82fd4c549ae62901d1ebd3b48e964d6d5b7a524b00ea371`. This is source review and archived correctness evidence, not real64 or performance acceptance. No Go/JVM runtime was launched by the reviewer.

Pinned main `QueryPipeline.kt:156–161` constructs `Executors.newFixedThreadPool` with a thread factory. JDK17 `Executors.java:154–158` uses equal core/max capacity and an unbounded queue; `ThreadPoolExecutor.java:1351–1362` first attempts `addWorker(command, true)` below core capacity, even when an existing core is idle. Its `runWorker` executes the first task before taking queued work. The Go change follows this admission behavior: zero prestarted cores; one new core with a direct firstTask per submission until capacity; FIFO queue thereafter. Source excerpts with complete-source and excerpt hashes are in `source-proof/` and `review.json`.

The executor mutex protects core admission, queue access, and private close. WaitGroup Add happens before goroutine launch while holding that mutex, so private close cannot race a later accepted Add. Close rejects subsequent submissions, wakes idle cores, and joins accepted queued work. The process-wide production executor remains open. Core count is never decremented on normal operation because these cores do not time out.

The future start/cancel/finish protocol, all-closures-before-AfterFunc ordering, and bridge cleanup are unchanged. A canceled firstTask returns without invoking its body and the core continues draining later work. Active execution captures the closure under the future mutex, while completion/cancellation release stored closure and worker references. The firstTask local reference is explicitly cleared after execution. This preserves the prior queued-cancellation resource fix.

| Evidence | Result | Source binding |
|---|---|---|
| v1 | Compile failure, exit 1; no tests executed | Exact failing production/test snapshots match start and terminal receipts |
| v2 | Race enabled, count 3; 14 top-level tests × 3 = 42 passes, exit 0 | Exact snapshots match start/terminal receipts and current reviewed files |
| Existing broader lazy-core-focused | Receipt exit 0, 2,575 manifest entries | Both reviewed file hashes match its module manifest |

All 19 original files from the two temporary evidence directories were copied byte-for-byte, totaling 85,730 bytes. `archive-manifest.json` records each source path, archive path, size, and SHA-256. v1 retains both its pre-change source and the exact failing source. v2 retains its source, original patch, runner, raw log, and receipts. The v1 pre-change files equal the corresponding frozen v4 files. v1→v2 changed only the executor struct declaration to add `capacity` and `cores` plus formatting; tests are identical. `v1-to-v2-production.patch` records that correction. No previous evidence was replaced.

The four new controls assert lazy growth despite prior task completion, firstTask queue bypass and canceled-core reuse, accepted-queue draining and rejection after close, and closing an unused pool. The close/drain test’s intermediate nonblocking check follows a signal sent before the closer acquires its mutex; that check alone does not deterministically prove close is already waiting. Its final join, FIFO outcomes, completion, and rejection assertions remain concrete. No production change is required on that basis.

This review does not establish the actual case 3 suffix-worker schedule or resolve v4’s 15,162 cache-state differences. It does not audit the concurrently running full-module checks or claim a new cold/warm/startup, 201-case, all-success-gate, or P95 pass. Legacy runner integration, configured worker overrides, and active-worker markers remain outside this change. The broader focused receipt was read and its two relevant source hashes checked; the full 2,575-file module was not rehashed here.

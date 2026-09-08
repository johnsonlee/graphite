# Independent cursor terminal audit

**The new source passes full checks but fails the real64 regression.** This is a new terminal record; it does not alter the earlier stage audit or its recorded failures.

The final context tests, complete module race tests and vet all exited 0. Their 3300-input receipt, raw logs and archived copies were independently checked. All 2563 module files were rehashed in both the current workspace and the frozen real64 source. Those complete identities match the full checks, focused v3 and real64 manifest; compiled input hashes and the executed binary also match their records.

The new cold replay completes all 1267 cases. Its 1266 successes and original case-821 error agree with main on columns, rows, canonical output, simple error class and message. However, comparison of all 162304 graph-state observations still finds **15162 mappedRangeCount differences**, first at replay case 3 after execution on `fixture-android-02`: main 7, Go 0. The native response bytes are identical to both earlier failed native cold captures. The correction therefore has not resolved the real64 state mismatch. The runtime and verifier both exited 1; the original all-success gate also remains failed.

The separately recorded qualified error-class observer still differs at case 821: main has `java.lang.IllegalStateException`, while Go does not expose that field. This does not disappear under the narrower public result comparison.

Warm and startup-prepared were not executed for this source. The 201-case diagnostic matrix was not executed for this source. No P95 samples or performance measurements were collected. Neither real64 regression acceptance nor overall acceptance has passed.

`terminal-audit.py` reproduces this artifact-only audit and writes `terminal-audit.json`, including hashes of inspected inputs. It launches no Go/JVM process. Fixture pre/post receipts and archive originals were verified; this audit did not reread every real64 graph byte. It makes no claim about unrelated private counters, JVM stack/FQCN parity, or a global memory reservation budget.

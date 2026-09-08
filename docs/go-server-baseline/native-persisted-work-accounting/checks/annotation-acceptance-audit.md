# Annotation independent acceptance-evidence audit

Overall acceptance is not proven. Annotation’s focused/full checks pass and three public decoder errors now agree with main, but the full 201-case comparison retains 12 differences and the actual 64-graph cold state comparison fails. Warm/startup were not executed. Annotation is not the version measured by the stopped Enum sampling campaign and has no P95 evidence.

This audit launched no Go/JVM/build/test/replay process. Only this new Markdown and accompanying JSON were written; source, tests, oracles, previous evidence, and comparison tools were not modified.

## Full source and correctness checks

All 3,397 recorded full-check inputs were independently rehashed. The complete current module and explicit checked frozen module were also independently enumerated and hashed: both equal the 2,577-entry manifest and the module portion of the full-check input map. The frozen receipt binds the exact full-check receipt SHA-256. Compared with the checked Enum module, exactly three paths differ: `internal/store/projection_node.go`, new `internal/store/projection_annotation_test.go`, and `internal/query/generic_disjunction_test.go`.

Context, full-module race, and vet receipts each record exit 0 and unchanged inputs. All archived full-check logs and receipts match their external original bytes/hashes. The freeze receipt’s `full201ReplayComplete=false` and `real64ReplayComplete=false` are historical facts at freeze time; the later evidence below is evaluated separately.

The focused archive’s 15 manifested files match their recorded hashes and external originals, including the three exact source snapshots. Its 13 before/after inputs are unchanged and were independently rehashed. The additional archived baseline production bytes match checked Enum production. Original baseline failure is preserved: both decoder APIs fail the bad-tail control at the strict count guard and reject -1/MinInt32 counts, totaling six failing leaves. Candidate Store evidence has 10 top-level tests each passing three times with race enabled (30 passes); all three public Annotation bad-tail controls pass. The public test now selects 87 original cases and checks exact errors, empty failed results, and before/after retained/mapped-view state. The suggested test asserting an internal partial Node map on failure was removed. Focused evidence is explicitly a partial source snapshot, not a separate full-module proof.

## Complete 201-case comparison

Compilation and capture exit 0; comparison exits 1. Source-before contains the same 2,577 entries; source-after and compiled-source contain 2,578 entries. All original entries are unchanged and the sole addition is the existing diagnostic helper. Every file’s bytes in the compiled source tar was checked against its complete manifest, and the decompressed diagnostic binary matches its capture hash/size. Every archived matrix artifact was checked against archive hashes and external original bytes. The 3,036 original fixture-manifest entries are unchanged; 22 generated sidecars are recorded separately, not suppressed.

The auditor independently recomputed the adapter’s declared projection against both complete original main and repeat captures: 170/181 public cases and 19/20 provider-wrapper controls match, leaving 12 identical difference records. Each stored difference’s name, scope, fields, native projection, and main projection was revalidated, not merely its count.

Only the three `annotation-bad-tail-hit`, `annotation-bad-tail-miss`, and `annotation-bad-tail-global-miss` projected outputs change from Enum. Each now reports main’s `IndexOutOfBoundsException` and exact message `Index (28) is greater than or equal to list size (21)`, instead of the premature collection-count error. Every other projected outcome is unchanged. Full native record comparison additionally retains six provider `goStack` changes; it is not claimed that all raw records are byte-identical.

The remaining 12 differences are two empty-term ordering cases, three truncated-Field cases, six isolated-surrogate cases, and one provider payload-ID control. The projection deliberately does not compare full JVM stacks/FQCN or work diagnostics, and the legacy adapter does not pass the new ExecutionContext. Provider-wrapper matching is not exact private-provider API parity. Consequently these counts are not full-route work-accounting or whole-engine acceptance.

## Actual 64-graph cold failure

The complete cold capture has all 1,267 cases in original order and 162,304 graph-state observations. All declared public result/error fields, including field presence, match main. There are 2,527 state differences, all `fixture-android-08.mappedRangeCount`: main 4 versus Annotation 0. The first is `replay/3/after`, case `single-contains-unlabeled-dense`; the difference persists through that after snapshot and before/after of the remaining 1,263 cases. Every other compared graph-state field matches.

An independent recursive comparison of all 1,269 parsed native JSON records finds no difference from the archived previous lazy-core cold failure. This is an exact recurrence of the observed native state pattern, not evidence that Annotation decoding changed scheduling or caused the cache mismatch. Full case 3 before/after states are retained in the audit JSON.

All 12 cold archive artifacts match their archive/decompressed hashes and external originals. The complete real64 frozen module was independently rehashed and equals the checked 2,577-entry manifest; all 346 compiled-input hashes match it. Source postflight reports original and copied modules unchanged. Full-fixture preflight/postrun receipts each verify 1,152 original files with zero changed/missing/added entries. No live graph fixture files were rehashed by this audit.

Runtime, verifier, and controller exit 1; the controller stops after cold. The original case 821 error and main’s qualified error-class observer difference remain. Main/native header representation differences are also kept separate from declared public/state comparison. No complete raw-JVM equality is asserted. The original all-success gate remains false; warm and startup-prepared have no execution result for this source.

The preceding Enum campaign was stopped with its records preserved, according to the parent’s separately archived stop audit; that stop evidence was not re-audited here. Its timings do not measure Annotation, and no Annotation P95, speedup, or performance acceptance claim follows from this work.

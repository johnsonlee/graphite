# Enum correction independent acceptance-evidence audit

The recorded three-state declared public/state comparison passes, but overall acceptance remains unproven. The original main all-success gate still fails at case 821, formal warm replay does not run, the 201-case matrix retains 15 differences, and the new diagnostic sampling campaign is still running. This audit launched no Go/JVM/test/build/benchmark runtime.

## Correctness and source binding

The complete current module, explicit checked frozen module (`enum-source-v1/module`), and real64 frozen module were independently rehashed: all 2,576 file entries match the same correctness manifest. That map also equals the module portion of the 3,396-input full-check manifest. The explicit freeze receipt binds the exact full-check receipt hash. Relative to lazy-core, exactly three paths differ: `internal/store/projection_node.go`, new `internal/store/projection_enum_test.go`, and `internal/query/generic_disjunction_test.go`.

Full-check context, module race, and vet steps all have exit 0 and unchanged-input receipts. Their archived raw logs/receipts were verified against external originals. The focused archive’s 19 files were likewise checked byte-for-byte and by recorded hash: the old decoder’s failing controls remain preserved, the candidate records 21 top-level passes (7 tests × 3, race enabled), and the three public Enum bad-tail controls pass. The subset’s original/repeat main records were checked against the complete existing oracle captures and agree. No new JVM execution is claimed.

The permanent public query test includes `enum-bad-tail-hit` and asserts selection denominator 84. Its source is not byte-identical to the temporary focused overlay: the same new wanted name moved into the existing literal list and the explanatory comment was updated. This difference was inspected rather than mislabeled as source equality.

## Complete 201-case replay

Compilation and capture exit 0; comparison exits 1. The source-before manifest contains the same 2,576 entries. Source-after and compiled-source contain 2,577 entries, with every original entry unchanged and only the existing diagnostic helper added (`generic_disjunction_diagnostic_test.go`, SHA-256 `8e788981cbcd8a3ef96edd5bf3257cfa9eb4ebdc7dc0c2d6c6bb8fca031d4594`).

The matrix matches 167/181 public cases and 19/20 provider-wrapper controls against both main references. The remaining 15 differences are explicit in the JSON. Compared with lazy-core, only `enum-bad-tail-hit` changes its public result: the early `CypherException` invalid-count error becomes main’s `IndexOutOfBoundsException`, `Index (37) is greater than or equal to list size (21)`. All other public/result fields in all 201 cases are unchanged; six provider `goStack` values differ. Small matrix artifacts were checked against external originals; the diagnostic binary/source tar and compressed fixture archives were not independently re-audited in this bounded step.

The legacy diagnostic adapter still does not pass the new ExecutionContext or compare its diagnostics. Its provider wrapper is not the exact private-main provider API. These matches do not establish full-route work accounting.

## Three-state actual 64-graph replay

| State | Actual executed phase | Cases | Graph-state observations | Declared public/state differences |
|---|---|---:|---:|---:|
| cold | replay | 1,267 | 162,304 | 0 / 0 |
| warm | warmup only | 1,267 | 162,240 | 0 / 0 |
| startup-prepared | replay | 1,267 | 162,304 | 0 / 0 |

All case identities, ordering, public fields including field presence, and the eight compared per-graph fields were independently checked against archived main raw responses. Totals are 3,801 case executions and 486,848 state observations. All 28 real64 archive artifacts were verified against archive/decompressed hashes and external original bytes. Each state’s complete-fixture preflight/postrun receipt reports all 1,152 original files unchanged with no added/missing files. Compiled-input manifests bind to the same frozen source. Fixture contents were not rehashed again by this auditor.

All three native runtimes still exit 1 for the original case 821 `four-or-graph-id-targeted` failure, `Unsafe expression reached parallel string projection`; all three comparison verifiers exit 0. Main’s qualified `java.lang.IllegalStateException` observer remains absent/null in Go. The original all-success gate remains false, and warm never prepares an invocation or enters formal replay. The statement is declared public/state parity, not complete raw-JVM equality or overall acceptance.

This run’s cache-count agreement does not establish that Enum decoding caused that agreement, nor that suffix-worker scheduling produces a deterministic distribution. Earlier lazy-core/v4 failures remain valid records and were not changed.

## Diagnostic measurement build metadata

Before timing began, the full-source and replay checks above were complete. After notification that the 200-pair-per-state campaign was running, the auditor read only small build/controller/manifest metadata. Build `p95-enum-f0838dda-build-v1` has 2,576 module files and 1,427 recorded inputs. Its identity is `source-sha256:7e9f6b88fa28a505270117472c340e4ccd171bcf27c82127186f5cc7ecf8cfd0`; workspace revision `f0838dda4f0d67628d028826d0577a8151765288` is labeled separately and no commit-content match is claimed. Both manifest copies equal the checked correctness manifest and are hash-bound into inputs.

The recorded binary hash is `f65002ba89f6d8c3c6e73c679186a8570b3244e94b0acf02d8d627f952e42734`. It matches the build input record; the binary/toolchain files were not independently rehashed during active timing. The campaign controller binds the exact build/input JSON hashes, plans 200 pairs per state, remains `running`, and explicitly sets `measurementAcceptanceEligible=false`. No sample-count completion, P95 estimate, latency target, formal warm, or performance acceptance is claimed here.

Only the new `enum-acceptance-audit.json/md` were written. Previous evidence, comparator, production, tests, and chronology were untouched.

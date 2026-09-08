# Entry v4 cold state audit

This audit reads archived captures only. No Go/JVM runtime, production changes, comparator changes, or benchmark measurements were performed. The v4 cold regression remains failed; this is not acceptance.

All four captures contain the same 1,267 cases. The declared public result/error comparison has zero differences, but the 162,304 graph-state observations contain 15,162 `mappedRangeCount` differences between main (or v3) and v4. No other compared graph-state field differs.

The first difference is `replay/3/after`, case `single-contains-unlabeled-dense`, graph `fixture-android-03`: main/v3 = 6, v4 = 0. The differing graphs are precisely android-03 through android-08, each with 2,527 differences: case 3 after plus before/after for each remaining 1,263 cases.

Before case 3, every capture has android-00 = 1,024 and all other 63 graphs = 0. After case 3:

| Graph | Main | v2 | v3 | v4 |
|---|---:|---:|---:|---:|
| fixture-android-00 | 1024 | 1024 | 1024 | 1024 |
| fixture-android-01 | 4 | 4 | 4 | 4 |
| fixture-android-02 | 7 | 0 | 7 | 7 |
| fixture-android-03 | 6 | 0 | 6 | 0 |
| fixture-android-04 | 7 | 0 | 7 | 0 |
| fixture-android-05 | 1 | 0 | 1 | 0 |
| fixture-android-06 | 5 | 0 | 5 | 0 |
| fixture-android-07 | 5 | 0 | 5 | 0 |
| fixture-android-08 | 4 | 4 | 4 | 0 |
| fixture-android-09 | 0 | 0 | 0 | 0 |
| fixture-android-10 | 0 | 0 | 0 | 0 |
| fixture-android-11 | 0 | 0 | 0 | 0 |
| fixture-android-12 | 0 | 0 | 0 | 0 |
| fixture-android-13 | 0 | 0 | 0 | 0 |
| fixture-android-14 | 0 | 0 | 0 | 0 |
| fixture-android-15 | 0 | 0 | 0 | 0 |
| fixture-tika-00 | 0 | 0 | 0 | 0 |
| fixture-tika-01 | 0 | 0 | 0 | 0 |
| fixture-tika-02 | 0 | 0 | 0 | 0 |
| fixture-tika-03 | 0 | 0 | 0 | 0 |
| fixture-tika-04 | 0 | 0 | 0 | 0 |
| fixture-tika-05 | 0 | 0 | 0 | 0 |
| fixture-tika-06 | 0 | 0 | 0 | 0 |
| fixture-tika-07 | 0 | 0 | 0 | 0 |
| fixture-tika-08 | 0 | 0 | 0 | 0 |
| fixture-tika-09 | 0 | 0 | 0 | 0 |
| fixture-tika-10 | 0 | 0 | 0 | 0 |
| fixture-tika-11 | 0 | 0 | 0 | 0 |
| fixture-tika-12 | 0 | 0 | 0 | 0 |
| fixture-tika-13 | 0 | 0 | 0 | 0 |
| fixture-tika-14 | 0 | 0 | 0 | 0 |
| fixture-tika-15 | 0 | 0 | 0 | 0 |
| fixture-hive-00 | 0 | 0 | 0 | 0 |
| fixture-hive-01 | 0 | 0 | 0 | 0 |
| fixture-hive-02 | 0 | 0 | 0 | 0 |
| fixture-hive-03 | 0 | 0 | 0 | 0 |
| fixture-hive-04 | 0 | 0 | 0 | 0 |
| fixture-hive-05 | 0 | 0 | 0 | 0 |
| fixture-hive-06 | 0 | 0 | 0 | 0 |
| fixture-hive-07 | 0 | 0 | 0 | 0 |
| fixture-hive-08 | 0 | 0 | 0 | 0 |
| fixture-hive-09 | 0 | 0 | 0 | 0 |
| fixture-hive-10 | 0 | 0 | 0 | 0 |
| fixture-hive-11 | 0 | 0 | 0 | 0 |
| fixture-hive-12 | 0 | 0 | 0 | 0 |
| fixture-hive-13 | 0 | 0 | 0 | 0 |
| fixture-hive-14 | 0 | 0 | 0 | 0 |
| fixture-hive-15 | 0 | 0 | 0 | 0 |
| fixture-kotlin-compiler-00 | 0 | 0 | 0 | 0 |
| fixture-kotlin-compiler-01 | 0 | 0 | 0 | 0 |
| fixture-kotlin-compiler-02 | 0 | 0 | 0 | 0 |
| fixture-kotlin-compiler-03 | 0 | 0 | 0 | 0 |
| fixture-kotlin-compiler-04 | 0 | 0 | 0 | 0 |
| fixture-kotlin-compiler-05 | 0 | 0 | 0 | 0 |
| fixture-kotlin-compiler-06 | 0 | 0 | 0 | 0 |
| fixture-kotlin-compiler-07 | 0 | 0 | 0 | 0 |
| fixture-kotlin-compiler-08 | 0 | 0 | 0 | 0 |
| fixture-kotlin-compiler-09 | 0 | 0 | 0 | 0 |
| fixture-kotlin-compiler-10 | 0 | 0 | 0 | 0 |
| fixture-kotlin-compiler-11 | 0 | 0 | 0 | 0 |
| fixture-kotlin-compiler-12 | 0 | 0 | 0 | 0 |
| fixture-kotlin-compiler-13 | 0 | 0 | 0 | 0 |
| fixture-kotlin-compiler-14 | 0 | 0 | 0 | 0 |
| fixture-kotlin-compiler-15 | 0 | 0 | 0 | 0 |

The accompanying JSON contains all 64 graphs’ complete before/after states for all four captures, including every observed cache field, and input archive SHA-256 hashes.

Compared with v2, v4 differs only on android-02 (0 → 7) and android-08 (4 → 0), each across 2,527 observations, totaling 5,054. The first difference is case 3 after on android-02. A recursive comparison of every raw native JSON field across all 1,269 records (including non-case records) confirms that v2→v4 and v3→v4 differ only in before/after `mappedRangeCount`. This full raw-native claim does not assert full raw-JVM equality.

The suffix graphs already have `mappedView=true` before case 3 in every capture. Their other state fields agree, including `retained=false`. The variation is therefore published range-validation cache entries, not whether a mapped view was initialized. These snapshots cannot distinguish an unstarted suffix task from a task canceled before publishing its entries. They do not establish a scheduling cause or justify relaxing the state comparison.

The v4 archive’s 10 copied artifacts were checked against its recorded archived/raw hashes and their external original bytes. Input manifests and prior evidence were not changed. The parent’s separate old-frozen-v3 cold rerun is outside this audit until its output is supplied.

The separate entry controls and race/vet pass do not override this failed cold state regression. This audit establishes no warm/startup result, all-success-gate pass, or P95 acceptance for v4.

## Old frozen v3 cold repeat supplement

The separately archived `scheduler-cold-repeat` rerun again has all 1,267 declared public results and 162,304 graph-state observations equal to main. Every parsed native JSON record (1,269 including metadata) is identical to the original v3 cold capture. The repeat module manifest matches the original v3 manifest in all 2,568 files; its receipt reports both original and frozen modules unchanged. This audit compares those full manifest maps without rehashing the frozen module again.

The controller completed with exit 0 and verification exit 0; the native runtime still exits 1 for the original main case 821 failure. Its qualified `errorClass` observer remains different, so this is declared public/state parity, not full JVM raw equality or an all-success pass. The repeat strengthens the observed contrast with v4 but does not prove a worker-scheduling cause. The v4 failed conclusion and absence of warm/startup/P95 acceptance remain unchanged.

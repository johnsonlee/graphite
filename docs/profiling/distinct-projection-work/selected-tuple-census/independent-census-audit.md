# Independent DISTINCT reference-census audit

**Pass.** A separate Python implementation independently reread all **5,046,935** rows of the authenticated CallSite export. It did not import or execute `census.py`, its verifier, Java, or a new query measurement. Reproducer: `audit-census.py`; full checks and file hashes: `independent-census-audit.json`.

## Selection and column correspondence

Reviewed `ExportCallSites.java`: graph ordinal follows manifest order, each graph uses `graph.nodes(CallSiteNode.class)`, and the four fields are caller declaring class, caller method name, callee declaring class, callee method name. The exporter rejects tabs/newlines/carriage returns in values. These correspond exactly to the query's four projected columns. The census preserves that traversal order; it does not sort node IDs or tuple strings.

Independently select the first distinct tuples for which at least one of those four lowercased fields contains `get`. Selection reaches **200 tuples at export row 1,012**, still in `fixture-android-00`. All 200 values in their exact order match `census.json`. Every selected tuple itself satisfies the predicate, so looking for its complete four-field occurrence elsewhere does not require weakening or changing the predicate.

Export and manifest SHA-256 match the previously authenticated v3 catalog. All 64 graph IDs and CallSite counts match both the catalog and fixture provenance. The independent pass reconstructs all **64 × 200 × 4 corresponding-property posting lengths**, matching every recorded entry, not only the grand total. It also reconstructs each selected tuple's occurrence count and every selected tuple's complete graph provenance.

## Counts and false candidates

| Source | Distinct selected tuples present | Selected node occurrences | Tuples with all four corresponding values present | Sum of each eligible tuple's shortest property posting | Nonmatching per-tuple anchor encounters |
|---|---:|---:|---:|---:|---:|
| Android 00 | 200 | 262 | 200 | 1,583 | 1,321 |
| Tika 00 | 11 | 12 | 11 | 53 | 41 |
| Other 62 graphs | 0 | 0 | 0 | 0 | 0 |
| Total | Per-source counts above | **274** | Per-source counts above | **1,636** | **1,362** |

The all-four-values-present check is only a necessary condition in general. For this particular selected set, **zero eligible tuples are entirely absent as complete tuples**. This does not make the condition sufficient: within the shortest postings, there are still nonmatching nodes. For the 63 sources after the leading graph, the sum is **53 per-tuple posting encounters**, of which **12** are complete selected-tuple occurrences and **41** do not match that queried tuple. The independent check confirms each complete tuple count is no larger than any of its four posting lengths.

These are per-tuple encounter sums. A node or posting may participate in multiple probes, so 53 is neither a unique-candidate count nor proof that the engine performs 53 work units. The census deliberately does not deduplicate those per-probe terms or discard nodes that fail full-tuple equality. No actual mapped posting bytes, chosen anchor tie-break, validator work, cancellation overhead, CPU, or speedup is measured here.

The full `get` predicate matches **1,489,740 CallSites** over the corpus, while only **274 node occurrences** contribute to the chosen 200 tuples. Predicate match count, selected tuple count, selected node occurrences, and summed posting lengths are different quantities.

## Existing frozen-main control

Independently read `control-rows.jsonl` and `control.tsv`. The one query returns exactly the 200 reference rows, the four columns in the expected order, and every row's complete canonically ordered graph IDs. The returned graph union is Android 00 and Tika 00. A separate standard-library JSON serialization reproduces digest:

`cc3d91e242315a466d41e05511e341e16092908dd921cca5979f77e3edc566ae`

The workload's decoded query, TSV query digest, manifest, successful row count and 64-source count all match. The recorded command points at the authenticated frozen-main JAR; its current SHA-256 is the recorded frozen value `a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe`. The control is explicitly per-query cold. Its latency/work fields were not used as performance evidence or compared with the warm replay.

This is an independent output-consistency audit of the existing control, not a rerun. The existing short control receipt does not itself contain a complete graph-file pre/post hash receipt; this audit establishes authenticated reference inputs, current JAR identity and exact persisted control-output agreement. It makes no new claim about unrecorded process provenance.

The results support a concrete description of redundant source-search work. They do not select or implement a new candidate, resurrect rejected Attempt 133, bypass full selected-posting order validation before LIMIT, or establish an expected optimization multiplier.

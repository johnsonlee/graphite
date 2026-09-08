# Existing invalid index file and ordinary projection controls

This is an eight-case supplement to the original 46-case capability matrix.
The original matrix and its two captures remain unchanged. These cases use the
same pinned main/JDK identities and public executor harness, with their own
explicit `cases.json`, fixture archive, receipts and two independent captures.
They are synthetic correctness checks, with no performance measurements.

The four fixture variants are empty, generic-only (`hit-A`, `hit-B`), and copies
of each with a regular `graph.callsite-string-index` file containing exactly
the 25 bytes `invalid capability probe\n`. The file exists but is not a valid
index; no valid persisted index is substituted. Actual main writes and loads
the normal graph files before the fixture generator creates this sidecar.
No CallSite nodes are present. The archive contains 66 regular file members.
Every source in a case is a fresh graph copy; 40-source cases use the declared
first variant followed by 39 empty graphs.

`MappedWebGraphBackedGraph.hasPreparedStringPropertyDisjunction` at line 1707
tests regular-file existence, rather than whether loading an index succeeds.
The single-source indexed DISTINCT path uses that signal to request serial
storage. The actual observed boundaries are:

| Query / fixture | One source | Forty sources |
| --- | --- | --- |
| DISTINCT, empty graph with invalid index file | Empty success | Capability-unavailable error |
| DISTINCT, generic-only graph with invalid index file | `hit-A` success | Capability-unavailable error |
| Ordinary non-DISTINCT, empty graph without index file | Empty success | Empty success |
| Ordinary non-DISTINCT, generic-only graph without index file | `hit-A` success | `hit-A` success |

The queries use the unlabelled six-property wrapped OR, project `n.name`, and
have LIMIT 1. This allows the ordinary controls to verify generic continuation
after an empty CallSite candidate set. Main's single-source DISTINCT generic
result carries provenance `single`; its ordinary single-source result does not
include metadata. Both exact public shapes are retained as observed.

The two independent JVM captures match all eight complete parsed records,
including rows, diagnostics, errors and stacks, execution phase, and index
states. There are six successes and two exact
`java.lang.IllegalStateException` failures with message
`Distinct projection capability became unavailable`. No failed request returns
partial rows. The complete fixture inputs, including the invalid sidecar
bytes, remain unchanged during both runs. Independent writer timestamp comments
are retained and audited; no output or fixture bytes are normalized.

`main.json`, `fixtures.tar.gz`, `fixture-variants.json`, and `mutations.json`
are convenience copies from `main-capture`. Run `python3 verify.py` to verify
all archived bytes and the complete repeated records, including the fixed
invalid sidecar payload. Run `python3 run.py /absolute/fresh/output` to reproduce.

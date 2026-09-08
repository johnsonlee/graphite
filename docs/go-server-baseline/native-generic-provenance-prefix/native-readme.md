# Native generic DISTINCT provenance consumption

The Go baseline is `6d061b525a91d844f5c22a7231af6e18c61c0c3a`; original main is
`4e328b0109e13c896b74004823fb049fcb19251a`. This is a retained correctness
correction, with no new performance measurement or P95 claim.

Main's provenance phase collects selected CallSite tuples, then consumes generic
candidates. After each candidate it checks whether the cumulative hit set covers
the complete selected set. Go previously collected generic rows separately and
stopped at the projection limit. It could read a corrupt later node after main
had already stopped and turn a successful query into an error.

`distinctGenericHits` now updates the same hit set used by CallSite storage and
checks its size after every candidate, including duplicate and unselected rows.
It still consumes the first generic candidate when raw hits already cover the
selected set. Ordinary prefix projection retains its separate generic DISTINCT
limit. All three provenance entry paths use the new helper. The probe also no
longer performs a second ordering read after the ordered iterator yields a node;
the original main selected-row matcher does not perform that extra read.

The actual-JVM oracle and its independent repeat preserve 13 complete public
queries on 40 separately copied small persisted correctness graphs. They cover
LocalVariable prefixes, raw/generic hit combinations, duplicates, unselected
rows, empty iteration, absent CallSite nodes in the probe source, and corrupt
records before or after the stopping point. These graphs are not performance
fixtures. The complete parsed JVM outputs repeat exactly; original JSON object
key order and writer timestamp comments remain unmodified in both captures.

| Check | Result |
| --- | --- |
| New public oracle | Baseline 10/13; candidate 13/13 |
| Corrected former failures | raw-complete-first-nonselected; raw-plus-generic-completes; raw-plus-nonselected-then-selected |
| Required error controls | All three preserve exact exception class/message and atomic response |
| Full Go module | Race tests and vet pass; recorded inputs unchanged |
| Existing 201-case oracle | Unchanged compared public results/errors/state; 166/181 public and 19/20 scoped provider controls agree with each main reference |
| Remaining original oracle differences | All 16 retained; complete fidelity still fails |
| Initial capability-absence controls | Baseline 4/13; candidate 7/13; six pre-existing mismatches retained |
| Real64 cold | All 1,267 cases and 162,304 state observations agree |
| Real64 warm prewarm | All 1,267 cases and 162,240 state observations agree |
| Real64 startup-prepared | All 1,267 cases and 162,304 state observations agree |
| New latency, CPU, memory, P95 evidence | None; this correction was checked for behavior only |

The baseline regression run uses a Go overlay containing the exact committed
baseline `indexed_distinct.go`; the candidate runs the same public tests with
the current engine. `checks/evidence/` preserves both full outputs, module
checks, source hashes, overlay, and independent result comparisons. Initial
capability controls use a test-only overlay to select the first JVM capture's
exact 451-file fixture archive. Neither overlay changes workspace production.

`checks/initial-controls/` keeps every initial observation. A generic-only first
graph causes main's initial projection capability to return unavailable and
throw `Distinct projection capability became unavailable`. Go still differs in
six such cases. The final prefix oracle adds a nonmatching CallSite to those
first graphs so it reaches the intended provenance phase; the earlier behavior
remains a real compatibility requirement, not an invalid fixture or waived
failure. Fixing this capability boundary is separate remaining work.

`checks/matrix201/` retains the complete existing matrix, exact compiled module
archive and executable, 3,036 unchanged original fixture files and 22 explicitly
recorded CallSite sidecars. Its comparison exits 1 because the known 16 cases
still differ. No compared public output, error, or state changes from the prior
candidate. Scoped private-provider controls remain narrower than the Go wrapper
and do not establish equivalent public APIs.

The three real64 runtimes run serially after module checks and JVM captures
terminate. Every runtime uses its own clone of the original 64 graphs and
verifies all 1,152 original files unchanged. All original 1,267 testcase
definitions, order, results and errors are preserved; the same original case821
fails and each original all-success gate exits 1. Warm only completes prewarm;
the original formal warm invocation remains unprepared. The strict comparators
retain all cache fields and cover 486,848 observations. The prior attempt's
contended cold state mismatch is still unresolved and remains in its archive;
these isolated captures do not prove scheduling-independent cache publication.

Keep this correction because actual main demonstrates three fixed public
behaviors, required failures remain intact, the prior bounded matrix has no new
differences, and complete isolated real64 results/state remain aligned. Source
identity is bound by `source-verification.json` and the frozen module manifest.
Full server fidelity, original formal warm, equivalent resource/work accounting,
required benchmark gates and the 10x per-case P95 objective remain open.

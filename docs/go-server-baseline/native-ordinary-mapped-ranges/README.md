# Ordinary mapped posting ranges

Ordinary projection used the strict candidate reader and eagerly collected and
sorted matching node IDs. Main instead validates selected posting ranges before
returning a lazy sequence. This was visible as missing mapped-range certificates
in the real64 replay. It also changed public behavior: an unrelated negative node
offset made seven requests fail in Go that succeeded in actual main.

Ordinary projection now uses the existing main-compatible retained/mapped reader
and lazy selected-range iterator. Cold ranges validate completely; warm ranges
read the next node order on demand, so LIMIT does not consume the unused suffix.
An invalid range follows the original fallback. The ordinary leading projection
uses the same iterator while retaining its raw-property projection behavior.

DISTINCT can also publish an initialized mapped view through its strict reader.
That reader now owns a range-validation cache before publication, so ordinary
lookups after DISTINCT use real selected-range validation on the same mapping.
Subsequent preparation preserves the published view and its certificates until
explicit clear. A mapped view that proves a split DISTINCT lookup empty avoids
loading a retained reader and touching unrelated invalid offsets. Generic node
processing still runs, including selected-row provenance collection.

Actual-main evidence uses the pinned Explore JAR at revision
`4e328b0109e13c896b74004823fb049fcb19251a`. `capture-main.py NEW_DIRECTORY`
reconstructs the six tiny fixture histories from repository fixtures and applies
the documented offset/tag mutations to source zero. These synthetic fixtures
are correctness controls only. Each history has 64 distinct fixture copies.

The final matrix has 48 operations: 42 queries (28 successes and 14 expected
errors) plus six explicit clears. All full public responses and 102 first-source
structural/file observations match actual main. The initial 42-operation oracle
and failed candidates remain preserved. An isolated control that removes only
view preservation loses the mapped range certificate after DISTINCT. The initial
ordinary migration also failed existing DISTINCT warmup histories; retaining
range validation on those reader mappings restores their public behavior.

`AnnotationMappedOracle.java` places the annotation fixture at sources `g00` and
`g63`, with the clean fixture at the other 62 sources. After an empty CallSite
warmup, two mixed DISTINCT queries must return one annotation row with both graph
IDs in its metadata. All three queries and both endpoint states match actual
main. The first probe used a term absent from the fixture and produced no rows;
that control and source are preserved separately. The positive probe uses the
fixture's actual annotation name `Audit`. An initial native test mistakenly read
internal provenance from an already serialized result; its failure log remains,
and the final assertion checks the public `$metadata` value.

Run `python3 verify.py` to check full captures and the negative control. Main's
raw match/projection counters are zero in these histories; the verifier checks
that condition before omitting these unavailable native observations. Fixture
hash manifests cover all inputs; the expanded capture starts from fresh copies.
Generated class files and duplicate fixture trees remain local artifacts.

Targeted tests, the full module race suite, and vet pass. The final logs are
`full-module-race-verified.log` and `vet-verified.log`; earlier intermediate
failures and checks are retained. The corresponding full real64 replay is in
`../native64-ordinary-mapped-ranges/`. This correction establishes no P95 claim
and does not complete warm/startup-prepared or full server parity.

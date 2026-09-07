# Independent main StringTable.findId compatibility review

Root supplied the frozen two-file candidate in
`/tmp/graphite-go-findid-eebec091`, based on eebec091. The storage reviewer did not
edit either production file. This review adds only
`internal/query/distinct_string_id_integration_test.go` and this evidence directory.
No64 process, performance test, synthetic microbenchmark, commit or root edit was
made. All checks described below have completed; no oracle process remains active.

## Production review

`indexed_distinct.go` replaces exactly the two selected-target SID linear loops
with distinctStringTableID at their original call points. Target iteration,
property iteration, validity flags, alias-column lookups, Postings calls, anchor
selection, raw range workers, cancellation contexts and Close handling remain in
place. The new helper has no cache or Store access.

`distinct_string_id.go` follows pinned main StringTable.kt:40: low/high inclusive,
first visited equal midpoint, bounds move by comparison. It compares Java UTF16
units using slices.Compare; this preserves prefix/unsigned-unit ordering and
returns equality for distinct byte encodings with identical UTF16 units. The
existing compareUTF16 helper's final byte-based tie behavior is deliberately not
used. For loaded tables within the serializer's signed-int size bound,
low+(high-low)/2 is equivalent to main's unsigned-shift midpoint expression.

Empty tables return -1 before polling, matching the previous empty Go loop.
Nonempty searches poll the existing evaluator context at entry and while searching.
The actual number of polling calls changes with the search algorithm; this is not
an equivalence claim for an artificial Context.Err that changes on its Nth call.
No new context, worker, synchronization or mapped-byte lifetime is introduced.

No production correctness defect was found during this review. The following
results establish both a previously missing main behavior and preservation of
the existing accepted corpus.

## Actual main method oracle:45 lookups

FindIdOracle.java uses the actual pinned main StringTable.load/findId methods and
FrontCodedStringList/BinIO serializer, not a reimplementation. It covers the three
previously frozen tables plus a larger sorted boundary table and missing terms:

- [a,a,a] returns midpoint SID1 for a, not the old Go first SID0.
- [b,a] returns -1 for a, not the old Go linear SID1.
- Sorted unique entries include empty/NUL, ASCII case and prefixes, U+D7FF,
  isolated high/low surrogate boundaries, a supplementary emoji, U+E000/U+FFFF,
  and missing values before/between/after table entries.

All45 candidate lookups equal the actual JVM IDs. Java output includes explicit
numeric UTF16 arrays so Go's ordinary JSON unmarshal cannot replace isolated
surrogate escapes. The initial textual-only oracle output is retained separately;
only main-method.jsonl with explicit units is used by the test. An additional
actual-helper test proves that canonical emoji UTF8 and separate WTF8 surrogate
encodings with the same UTF16 units compare equal, rather than inheriting the old
compareUTF16 byte-level tie distinction.

## Two complete graph queries demonstrate the correction

FindIdQueryOracle.java creates a real one-CallSite graph through main's GraphStore
writer. It then uses the actual library serializer to create accepted duplicate
and unsorted string tables, adjusting only the six raw method SID fields where
needed. After mutation it removes stale identities/index, asks main's mapped
graph to prepare/persist the real CallSite string index, then reloads these stores
for the query. The resulting graph/index fixtures and generator are retained.
These are intentionally constructed format-correctness fixtures, not a production
corpus or a performance dataset. Main's normal builder itself sorts/deduplicates.

The complete query is:

    MATCH (n:CallSite) WHERE n.caller_name CONTAINS 'a'
    RETURN DISTINCT n.caller_name AS x LIMIT 1

Nine source namespaces are required to leave a source outside main's initial
8-source wave, exercising the selected-value provenance probe. Sources g0..g7
reuse the clean one-node graph; g8 uses one of the two altered tables. No9 physical
large graphs or timing claim is involved.

| Late source table | Original Go eebec091 | Actual main4e328b0 | Candidate |
|---|---|---|---|
| [aaa,aaa,aaa], raw SID0 | x=aaa; graphIds g0..g8 | x=aaa; graphIds g0..g7 | exact main |
| [bbb,aaa], raw SID1 | x=aaa; graphIds g0..g8 | x=aaa; graphIds g0..g7 | exact main |

Every column and row is compared, not only the provenance count. The original Go
control runs from independent worktree `/tmp/graphite-go-findid-control-eebec091`
with its unmodified query package. ControlProbe.go.txt is the exact harness;
native-before.json retains both complete old results. The original extra g8 is
not a desirable mathematical DISTINCT behavior claim: reproducing main's actual
accepted-table lookup rule is the compatibility objective here.

## Errors, lifecycle and regression corpus

Five new top-level tests pass under the race detector. In addition to the above,
they check cancellation before a nonempty search, deterministic cancellation
during search, empty-table behavior, and the original Postings Close boundary.
A lookup can still read the owned Strings table after Store.Close; it does not
introduce an earlier Store error. The following actual index.Postings operation
still returns ErrStoreClosed. No helper precomputes later targets or touches an
index to mask an earlier Postings failure.

The complete module passed race and vet. The module run emits all17 native oracle
files for the original1048-pair DISTINCT corpus. Each complete JSON value equals
the frozen accepted integration output, including aliases, source order,
raw/retained/parallel storage paths, required reads, corrupt offsets/SIDs/counts,
nullable errors, speculative failures, cancellation and Close behavior. This
validates both changed callsites through the existing raw and retained suites.

The original corpus denominator is unchanged:580 eligible complete matches and
159 declined complete matches, with309 declined differences still outstanding.
This review does not claim all1048 match main. The two new accepted-table queries
above are additional evidence, not replacements for those cases. See
oracle-verification.json and oracle-native/ for the complete retained outputs.

## Reproduction and identity

From this worktree root:

```sh
JAVA17=$(/usr/libexec/java_home -v 17)
JAR=/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar
"$JAVA17/bin/javac" -cp "$JAR" -d findid-review findid-review/FindIdOracle.java findid-review/FindIdQueryOracle.java
"$JAVA17/bin/java" -Xmx128m -XX:ActiveProcessorCount=2 -cp "findid-review:$JAR" FindIdOracle findid-review/method-fixtures
"$JAVA17/bin/java" -Xmx256m -XX:ActiveProcessorCount=2 -cp "findid-review:$JAR" FindIdQueryOracle findid-review/fixtures
FINDID_QUERY_OUTPUT=/tmp/graphite-go-findid-eebec091/findid-review/native-query-final.json go -C graphite-server test -race -run '^TestDistinctStringID' -v ./internal/query
INDEXED_DISTINCT_OUTPUT=/tmp/graphite-go-findid-eebec091/findid-review/oracle-native go -C graphite-server test -race ./...
go -C graphite-server vet ./...
```

The Java jar/source identities, old control identity, frozen candidate files,
fixture files and raw results are SHA256-identified in identity.json/manifest.json.
Compiled Java classes can be regenerated from the retained sources; their hashes
are recorded as local execution artifacts without requiring them in Git. Test
fixtures are rooted at findid-review relative to the repository, so preserve this
directory with the test. All performance conclusions are deferred to root's
separate real64 measurement.

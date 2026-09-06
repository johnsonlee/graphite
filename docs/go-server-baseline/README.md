# Pinned main HTTP correctness observations

These observations are correctness evidence only. An empty graph catalog is not
a performance dataset, and no latency or speedup claim follows from this run.

The remote main revision was confirmed using `git ls-remote origin refs/heads/main`
as `4e328b0109e13c896b74004823fb049fcb19251a`. A fresh shared local clone at
`/tmp/graphite-go-main-baseline-clone-4e328b0` was checked out at that exact commit.
`./gradlew :explore:shadowJar --console=plain` succeeded: 18 actionable tasks,
8 executed and 10 restored from Gradle cache. No source changes were made there.

A first attempt in an isolated Git worktree failed because the publishing plugin
did not resolve the worktree Git directory. The clean clone build avoids that
tooling limitation. An older existing JAR in the user's regular checkout was
found to expose an obsolete CLI and was not used.

Reproduce from the repository root:

```bash
python3 docs/go-server-baseline/capture.py \
  --jar /tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar \
  --revision 4e328b0109e13c896b74004823fb049fcb19251a \
  --output docs/go-server-baseline/empty-catalog
```

The script starts a local JVM server with a temporary empty data directory,
records 40 HTTP exchanges, then terminates and waits for that process. The output
contains the exact JAR SHA256, Java version, command, request/response headers,
raw bodies and parsed JSON. Dynamic date, port, data directory and topology
`builtAt` fields must be normalized for later differential comparisons. Do not
normalize semantic fields such as columns, null omission, provenance or errors.

Evidence files:

- [Runtime identity](empty-catalog/metadata.json)
- [Raw HTTP observations](empty-catalog/observations.json)
- [Server process log](empty-catalog/server.log)

Observed details worth preserving:

- The timeout request field is `timeoutMs`; `timeoutMillis` is ignored.
- Root graph-independent results carry `$metadata: {graphIds: []}` in each row.
- Null object fields are omitted; null list items are retained.
- `Accept: application/json` overrides an invalid C4 `format` query field;
  with `Accept: */*`, the same invalid format returns 400.
- Missing routes use Javalin's structured problem response under JSON Accept;
  missing loaded graphs use Graphite's separate `{error: ...}` response.
- Empty-catalog `MATCH` returns declared columns and no rows, whereas count
  returns a zero row with empty provenance.

This coverage does not establish persisted graph, all Cypher, topology inference,
C4 rendering, concurrent lifecycle, static UI or metrics parity. Those remain
tracked in [the full inventory](../go-server-parity.md).

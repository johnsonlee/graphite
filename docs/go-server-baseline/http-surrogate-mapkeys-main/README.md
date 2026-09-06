# Surrogate map keys at the HTTP boundary

Pinned main `4e328b0109e13c896b74004823fb049fcb19251a` on Java 17.0.18 emits multiple JSON members when distinct UTF-16 names become the same UTF-8 name. For `RETURN {<D800>:1,<D801>:2,?:3} AS x` (backtick identifiers), its raw response contains `"?":1,"?":2,"?":3` in that order. Reversing source insertion order reverses those values. A repeated identical UTF-16 name is replaced before serialization, retaining its original insertion position; null members are omitted. The same behavior applies to projection aliases and fanout columns.

The oracle contains **72 HTTP cases**: the original 40 cover ten expressions across root, scoped, explicit graph selection and fanout with `includeGraphRows=true`; `union/` adds 24 cases for mixed UNION aliases, RETURN *, collect(), null and non-finite output; `escaped/` adds eight cases whose ASCII query source generates surrogate-containing column names through escaped literals. All requests use one tiny persisted correctness fixture. Each dedicated JVM exited after capture. No performance traffic was run.

Raw main bytes, response headers, commands and complete logs are retained. `native-replay.jsonl` contains test events and base64 native response bytes for every case. The comparator preserves duplicate-member multiplicity and relative order. It sorts distinct keys only, so harmless object-field order changes are ignored without allowing a normal JSON decoder to discard duplicate members.

Native changes keep distinct Java keys through query materialization and write colliding members individually at the final HTTP boundary. `query.OutputObject` carries ordered source keys and values only where conversion collides; ordinary map results retain their existing representation. Result rows retain their existing `[]map[string]any` API, with private per-row order for collision cases and `ResponseRows`/`ResponseRow` accessors at the HTTP boundary. Fanout deduplicates columns by original Java identity via `ColumnKeys`, then encodes their wire spelling. Per-row order is retained separately so a UNION branch is not interpreted using another branch's projection order. Annotation attributes use the stored `ValueOrder`.

For a bare Go `map[string]any` there is no source insertion order to recover. The final writer preserves all colliding members in a deterministic sorted fallback order. This is not a claim that a caller's discarded Java insertion order can be reconstructed. The HTTP query paths under test retain their actual order. A future query-result cache must deep-copy `OutputObject.Keys` and `OutputObject.Values`, as well as preserve Result's private row/column identity state.

Validation commands from `graphite-server`:

```sh
go test -race ./internal/query ./internal/server -count=1
go vet ./internal/query ./internal/server
go test -json ./internal/server -run 'TestHTTPSurrogate' -count=1
```

The full server run also covers the prior 213 valid-JSON, 288 malformed/lenient, 30 non-finite HTTP and 134 direct Gson oracle cases. Annotation materialization, qualified annotation identities, ordinary-map API compatibility and deterministic fallback member preservation have focused unit assertions. These finite cases do not establish complete query or Gson equivalence, and have no performance interpretation.

Reproduce each main capture from repository root with the matching `capture.py`, `capture-union.py` or `capture-escaped.py`, passing a fresh `--out` directory. The scripts reject existing output directories. The prior Gson verification record at `http-malformed-json-main/verification.json` is unchanged; this follow-up has its own source and evidence manifest.

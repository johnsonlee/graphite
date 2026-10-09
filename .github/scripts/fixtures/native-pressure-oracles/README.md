# Native multi-graph query expectations

`index.json` carries the complete expected payloads for the 39 native pressure
queries, with separate accepted-baseline and declared-types expectations. The
four source JAR hashes, graph registry, exact request and actual query scope must
match before these expectations can be used. The two routing cases target two
graphs; the other 37 target all 64.

Payloads and archived audit records are deduplicated by raw SHA-256 in `blobs/`.
These files were imported from previously completed local verification, not
generated from the first response of a new candidate:

- The 15 historical cases retain the original 73-case catalog's predetermined
  full-response digests. Captured bytes were imported only after matching those
  digests. The candidate schema histogram has its separately completed,
  persisted-node-derived authority; its previous catalog digest is not rewritten.
- The other 24 cases use independently derived persisted-graph expectations and
  archived audits binding those expectations to the actual HTTP responses.
- Four DATAFLOW queries have no `ORDER BY`. Their payloads contain the complete
  legal result multiset, including multiplicities and typed values. Validation
  permits any legal LIMIT result and does not assert encounter order.
- Discovery retains the documented existing behavior when the first projected
  property is absent; these expectations do not establish complete late graph
  provenance. Feature-presence queries do not prove every generic value.

Historical revisions and original paths in provenance or audit records describe
where that evidence was obtained. They are not runtime dependencies and are never
opened by `native_portable_oracles.py`. The loader works after relocating this
directory. Its `pins` contain only the current catalog, index and local blobs.

The loader deliberately does not alias a parent revision to the accepted baseline.
It also does not label a new revision as the historical candidate. Fresh runtime,
graph and readiness verification, full HTTP response comparison, and independent
construction/loading/query acceptance are still required. Loading this bundle
alone establishes neither semantic equivalence nor performance or CI acceptance.

Run the portable check after resolving the source JAR manifest:

```sh
python3 .github/scripts/native_portable_oracles.py \
  --source-inputs native-pressure-artifacts/source-inputs.json \
  --output native-pressure-artifacts/portable-expectations.json
```

The output path must be fresh. This check reads expectations and manifest metadata;
the artifact producer separately verifies the actual JAR bytes and graph outputs.

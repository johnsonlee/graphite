# Query performance evidence storage

The eight compressed raw-evidence files total 32,265,658 bytes. Their exact sizes
and SHA256 values are pinned in [assets.json](assets.json); per-file archive
manifests and derived audit JSON remain in the repository.

**Migration status: published.** The raw files are hosted in the dedicated
[PR212 evidence release](https://github.com/johnsonlee/graphite/releases/tag/benchmark-evidence-pr212-2026-11-11),
a prerelease marked not-latest. All eight uploaded files were downloaded to a
fresh directory and independently matched against the pinned sizes and SHA256
values before the repository copies were removed. Manifests and audit scripts
remain here; no earlier measurement bytes were changed. Historical Git objects
are not rewritten by this migration.

The published release is immutable and contains the eight gzip assets only.
The canonical asset manifest is this repository's [assets.json](assets.json),
not an additional release attachment. Attempt317 evidence uses a separate
[published release](https://github.com/johnsonlee/graphite/releases/tag/benchmark-evidence-pr212-attempt317-2026-11-11);
its four archives and five JSON manifests were uploaded and verified while draft
before publication. See the [Attempt317 reproduction instructions](complex-p95/attempt317/README.md).

To fetch all pinned assets into a cache outside the checkout:

```sh
python3 docs/query-performance-evidence/2026-11-11/fetch_assets.py \
  --cache-dir "$HOME/.cache/graphite/evidence/pr212-2026-11-11"
```

The downloader checks size and SHA256 before installing each file, and checks
existing cache entries before reusing them. It fails on unavailable downloads or
mismatched evidence. Add `--verify-only` for offline cache validation.

Recompute the original CI request distributions from the downloaded cache:

```sh
python3 docs/query-performance-evidence/2026-11-11/verify.py \
  --requests-dir "$HOME/.cache/graphite/evidence/pr212-2026-11-11"
```

Verify a local-query archive, optionally extracting into a fresh directory:

```sh
python3 docs/query-performance-evidence/2026-11-11/complex-p95/verify_archive.py \
  --manifest paired-measure-01-manifest.json \
  --archive "$HOME/.cache/graphite/evidence/pr212-2026-11-11/paired-measure-01.tar.gz" \
  --extract /tmp/graphite-evidence-review
```

The manifest checks every archived regular file's size and SHA256, rejects extra
or missing entries and unsafe archive paths. See [local-query evidence](complex-p95/README.md)
for the independent response/percentile audit commands and measurement limitations.

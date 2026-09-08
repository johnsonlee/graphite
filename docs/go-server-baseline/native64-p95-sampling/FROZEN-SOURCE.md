# Building a manifested candidate

`prepare-frozen.py` accepts an explicit frozen module and its complete
path-to-SHA256 manifest. It verifies the manifest before copying, verifies the
copy and original after compilation, and preserves the original manifest bytes.
The binary is identified by `source-sha256:<digest>`; the workspace revision is
recorded separately. It makes no claim that an uncommitted candidate matches a
Git commit. The original committed-source `prepare.py` remains unchanged.

```sh
python3 docs/go-server-baseline/native64-p95-sampling/prepare-frozen.py \
  --module /absolute/frozen-checked-module \
  --manifest /absolute/complete-module-manifest.json \
  --output /absolute/new-measurement-build
```

Use the pinned Go/JDK environment when building. The resulting `build.json`
works with the existing serial `run.py` and per-case `summarize.py`. The complete
manifest establishes source identity; inspect the corresponding test/replay
receipts separately to establish what correctness was verified.

This adds no measurements and changes no timer, workload, sampling threshold,
reference result or acceptance gate. `measurementAcceptanceEligible=false`
remains in the controller. In particular, the original case821 error and the
separately named diagnostic warm continuation remain explicit. At least 200
valid observations per case/runtime/state and the remaining functional/server
and benchmark gates are still required for the 10x objective.

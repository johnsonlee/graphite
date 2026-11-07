# Graphite local benchmark evidence

Classification `current-report-only`. Base `1c28f4aea222b4696f3280c769589dec0704a694`. Head `fb41a6398fa96d9d37d6d5849e6dde95049d5228`.

- [All cases and controller conclusions](report/report.json)
- [Raw base request samples](results/native/base-requests.json.gz)
- [Raw head request samples](results/native/head-requests.json.gz)
- [Preparation, source revisions and hashes](prepared/preparation.json)
- [Semantic comparison](results/native/semantic-transitions.json)
- [GraphFold production correctness parity](graphfold-parity/results.json)
- [Bundle manifest and completeness inventory](manifest.json)

The gzip files preserve every recorded request, including warmups, failures, graph scopes and response receipts. If the aggregate journal was incomplete, surviving individual records are included and identified. Missing files and receipts remain explicit in the manifest.

Original HTTP bodies, graph payloads, input archives and host logs stay in the local run. Machine paths in structured evidence are normalized labels. The controller's performance acceptance field is preserved.

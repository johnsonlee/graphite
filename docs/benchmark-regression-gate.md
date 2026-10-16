# Correctness, OOM and benchmark reports

Every PR runs the Rust controller in [`benchmark/`](../benchmark/README.md). The required
`benchmark-regression-gate` check now enforces correctness, complete execution and OOM checks.
It does **not** reject a PR because a timing, CPU or RSS comparison becomes worse.

The same GitHub runner executes the product merge-base and exact PR head. JVM frontend build/save runs through
JMH with G1, a 4 GiB heap, whole-JAR correctness/OOM cases and fresh real64 graph outputs. Each revision's Rust server then loads its
own graphs and executes the fixed multi-graph pressure plan. The fixed APK heap policy is 8 GiB;
APK is not a member of the existing four-JAR real64 corpus.

Controller policy and frontend harness come from the trusted target base tip; exact reviewed
semantic transitions allow intentional result changes without relaxing unrelated checks.

One Markdown performance report comment is created per PR and updated for subsequent commits.
It includes source SHAs, correctness/OOM results, base/head timing values, absolute and percentage
changes, sample counts and links to complete raw evidence. Missing or failed work remains visible.
Timing is report-only; GitHub runner noise is not treated as evidence of regression or recovery.

See the [controller documentation](../benchmark/README.md) for commands, exact measurement
boundaries, sampling protocol, heap checks, artifacts and the comment publication model.

The previous method-level performance thresholds, repeated confirmation runs, scheduled
historical measurements and main/release Pages deployments are retired. Historical measurements
and optimization records remain in the repository. The
[previous workflow documentation](https://github.com/johnsonlee/graphite/blob/7c693e0f29ef5abf318640bdb20c31bf84d8818e/docs/benchmark-regression-gate.md)
describes those historical results; it is not the current acceptance policy.

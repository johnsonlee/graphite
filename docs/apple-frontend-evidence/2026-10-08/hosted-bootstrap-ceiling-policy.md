# New real-corpus bootstrap ceiling policy

Declared at 2026-10-08T15:32:34.797571+00:00 before root inspected the new SwiftPM/Signal calibration sample values.

For the two newly adopted real corpora, derive the initial absolute health limits from
all five measured candidate frontend-only calibration samples in run37798249852:

- wall_ms ceiling = ceil(1.5 * max(measured wall_ms) / 250) * 250;
- peak_rss_bytes ceiling = ceil(1.5 * max(measured peak_rss_bytes) / (16 * 1024 * 1024)) * (16 * 1024 * 1024).

Retain the separately declared single warmup but exclude it from these measured-sample
maxima. Do not remove outliers, failures or select favorable samples. Source build,
IR validation and import verification remain outside the frontend timing boundary;
all must succeed and every graph must match the observed deterministic full shape.
If calibration fails or its shape is nondeterministic, do not invent or pin a ceiling.
Existing SwiftFormat shape and limits stay unchanged if the matching observations
support them. Record the arithmetic, raw sample links and exact artifact/compiler IDs.

These initial absolute limits are a bootstrap health guard for workloads that did not
previously have real-corpus measurements. The 50% headroom is an explicit operational
choice, not an observed confidence interval or a permitted performance regression.
It does not change the independent 5% CPU/RSS acceptance constraints, latency
requirements or original failed lifecycle comparison. It does not make different
coverage comparable. A ceilings-only comparator result must remain labeled as such;
passing it cannot establish recovery or matched-work acceptance. Do not enlarge these
limits in response to a later gate failure without a new evidenced decision.

This policy is limited to the first calibration of these two new corpora and exactly
this run's five measured samples per candidate. A single runner does not estimate
cross-runner uncertainty. The manifest expresses the resulting limits as wallMs and
rssMiB; the byte formula above is equivalent to rounding RSS to16MiB increments.

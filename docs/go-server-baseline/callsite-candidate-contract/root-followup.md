# Subsequent evidence after the frozen candidate contract

The original README and verification hashes remain unchanged. Its case 26 uses
one captured MAPPED order, not a globally canonical order across JVM launches.
The later [node encounter audit](../../../graphite-server/internal/query/testdata/node-encounter/README.md)
proves that main uses JVM Class identity-hash bucket order for supertype scans:
the same graph and jar can produce different type-group sequences. This does not
change the separate per-CallSite posting contract of increasing persisted byte
offsets. Do not sort arbitrary untyped candidates by offset and claim main parity.

The deterministic EAGER order defect is fixed in `3f08ecf2`, independently
verified by `../node-encounter-integration/verification.json`. The distinct
ResourceValueNode Constant membership defect is fixed in `d9844d42`, with its own
main oracle and independent integration tests. Main's filtered literal zero-limit
branch is fixed in `78f6deb4`, with full evidence under the query testdata and
`../limit-zero-integration/verification.json`. The original 52/56 contract replay
is an immutable before snapshot; these later changes do not rewrite that count.

The full 64-index validation independently establishes zero AnnotationNodes in
each of those 64 graphs. This is a per-fixture fact, not a license to assume
Annotation is absent in arbitrary graphs. The positive Annotation oracle here
still governs future eligibility. The index reader by itself does not perform
query optimization or certify all skipped core node payloads. Query candidate
integration and new complete performance measurements remain outstanding.

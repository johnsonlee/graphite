# Attempt 8: ASCII ROOT lowercase without transient code-point arrays

Hypothesis: an entirely ASCII input to Java Locale.ROOT lowercase can be mapped
byte by byte, avoiding UTF16/CodePoints arrays and Unicode table lookups. The
production delta changes only javastring.Case. It validates the complete input
before choosing the path; any non-ASCII byte or uppercase request retains the
existing implementation. Unchanged lowercase ASCII returns the original immutable
string. Changed input uses one pre-sized builder. Every consumed ASCII code point
still invokes the cancellation callback once, including the unchanged path.
Neither the prior CodePoints preparation nor the new eligibility scan polls the
callback, so no maximum cancellation wall time is claimed.

## Correctness

The Java17 Locale.ROOT oracle covers all 16,384 pairs of ASCII bytes around Az,
including control characters; its generator, actual Java version, commands and
raw/compressed hashes are retained. Checked-in tests consume these exact oracle
outputs, rather than recomputing the candidate algorithm. Additional boundary
cases exercise contextual sigma, dotted I, supplementary characters, WTF8 and
malformed UTF8. Candidate and integrated whole-module race/vet passed.

An independent clean de0608f0 archive plus exactly three candidate source/test
files built a shipping command without diagnostic exports. Its 42/42 real64 HTTP
requests returned 200 and matched all complete pinned-main JSON responses and
checked headers with the default 60-second server deadline. Independent old-Case
control comparisons cover20,544 outputs/callback counts and 110 cancellation
positions under race; no counterexample was found. The initial overlay-path/vet
harness issues and accidental build-directory mistake are explicitly retained in
independent/README.md. The executable remains at the external location recorded
in independent-binary-location.json; every frozen evidence hash was checked.

## Real64 diagnostic

Base is de0608f075174833a06f31eb3214b3ba9c4e0917. Production patch SHA256 is
2101021c97cf94f96372c4d4956fec5e55fc4534e1aaaf4ee5553a8986a726d7.
Base profile binary is ec9ea95e5fcf576ad869f3bb54910894706455a219c6a11e962f7e21190bb0f6;
candidate is 682e0c92d9cac0ec10b4af9a8e61587f6a28806dad20e996f4f8198672989b6f.
The separate HTTP binary SHA is recorded in the independent manifest.

Both use the same corrected instrumentation/serialization harness, all 64 real
persisted shards and fresh verification of all 1,152 fixture hashes and per-graph
counts. The baseline is ../native64-positive-profile-de0608f0/run-corrected.
That directory preserves the earlier rejected raw-engine-serialization run too;
only the corrected actual-server-serializer run is this attempt's baseline.
No production response normalization or result cache is introduced.

| Query, ordered execution | Allocation bytes, base → candidate | GC cycles | CPU user+system (s) | Execution+serialization (s) |
|---|---:|---:|---:|---:|
| Prefix, first | 18,222,452,888 → 11,136,246,944 | 2 → 1 | 42.501 → 28.274 | 32.315 → 22.931 |
| Prefix, repeat | 15,636,032,040 → 8,549,794,336 | 2 → 1 | 31.735 → 18.506 | 19.269 → 11.946 |
| Wrapped DISTINCT dense, first | 12,287,401,632 → 7,807,821,560 | 1 → 1 | 23.870 → 19.579 | 18.663 → 13.424 |
| Wrapped DISTINCT dense, repeat | 10,237,933,488 → 6,406,013,408 | 1 → 1 | 18.454 → 15.074 | 12.867 → 8.461 |
| Ordinary dense projection | 8,070,837,576 → 4,238,998,704 | 1 → 0 | 16.206 → 6.057 | 10.715 → 5.985 |

All five complete outputs match. These are single instrumented native-to-native
observations with host co-tenancy, not HTTP/P95 or controlled main-relative
acceptance. Explicit before/after GC is outside request counters and CPU samples.
Request-end heap can rise despite lower allocation: ordinary projection ends at
10.07→10.52 GB, with no collection in the candidate. Post-forced-GC heap stays
about 6.284 GB. No reduced retained-heap or peak-RSS claim is made.

Sampled CodePoints/UTF16 allocation disappears from the prefix top allocation
list; remaining costs are key construction, row cloning, frozen nodes and full
projection. The main-compatible raw DISTINCT implementation is separate ongoing
functional work. Keep this bounded optimization for verified allocation reduction
and fixed-workload correctness; all functionality and the repeated paired main
10× P95 objective remain open. No synthetic performance measurement was used.

Reproduce with a fresh candidate checkout and output directory:

```sh
python3 docs/go-server-baseline/native64-ascii-lower-attempt8/run-profile.py \
  --worktree /tmp/fresh-attempt8-checkout --out /tmp/fresh-attempt8-profile
```

candidate/ stores source manifests, all commands, complete bodies and profiles;
comparison.json holds exact counters. integration-source.json and integrated
race/vet receipts pin the shipping-source merge onto f4873238.

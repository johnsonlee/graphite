# Native CPU profiling

The native binary can record CPU samples and write a self-contained HTML flame graph:

```sh
GRAPHITE_NATIVE_CPU_PROFILE=1 GRAPHITE_PROFILE=profile.html \
  ./graphite-server --data /path/to/catalog --port 8080
```

Run the work to inspect, then stop the server with Ctrl-C or SIGTERM. Recording starts before argument parsing and graph startup. The runtime's samples are stopped/flushed and the HTML report is completed before the process exits. Help and invalid arguments also finish recording when enabled; short runs can legitimately contain no CPU samples.

Only the exact value `GRAPHITE_NATIVE_CPU_PROFILE=1` enables recording. `GRAPHITE_PROFILE` selects the output path and defaults to `profile.html` when empty. Setting only `GRAPHITE_PROFILE` does not enable the native profiler. Disabled execution follows the existing CLI path. The release wrapper owns routing: native serve uses this facility; JVM build/query retain async-profiler. This package does not implement JVM profiler options, sampling intervals, or profiler-agent configuration.

The output directory must already exist. The compressed profile and pending HTML are private temporary files in that directory. An existing report is replaced by a same-directory atomic rename only after parsing/rendering and closing the new report succeed. Temporary files are removed on normal success/failure. Start, parse, render, close and save errors are reported on stderr with a nonzero exit status. SIGKILL and abrupt process termination cannot run the flush/cleanup handler.

The HTML embeds data, CSS and JavaScript. It needs no pprof executable, Graphviz, Java process, server, CDN or downloaded asset for inspection. A modern browser with BigInt support can open it. Frame widths show inclusive sampled CPU time; overlapping parent/child values are not additive and do not represent request latency. Click or keyboard-activate a frame to zoom, use Back/Reset to return, and search functions/files. Next match can reveal a matching stack that was too narrow to draw. Search coverage counts each matching sample path once even when both parent and child match.

CPU values use the profile's `cpu/nanoseconds` sample type, independent of the position of its `samples/count` field. The period is displayed as time only when its type is also `cpu/nanoseconds`. Exact signed-int64 nanoseconds are embedded as decimal strings and processed with BigInt, avoiding JavaScript Number rounding. Layout ratios use floating point only for pixel geometry. Negative CPU values, overflowing totals and invalid profile metadata are rejected. Empty profiles render an explicit empty state. pprof locations and inlined lines are both leaf-first; reversing both creates outer-to-inner stacks, following the pinned pprof `proto/profile.proto` specification.

Native server code currently adds no pprof query labels. If labels are present, samples are aggregated across labels and the report states this; label names/values are not retained. Function and file names use JSON HTML escaping and DOM textContent, preventing names from becoming executable markup. The total/data tree is retained in full. Drawing is limited to 8,000 frames and 200 levels per view, with subpixel frames omitted; zoom and Next match expose the remaining data. This bounds DOM work without altering recorded totals. Very large recordings still require memory for the full sampled call tree and its embedded JSON.

## Verification

`go test ./internal/profiling ./cmd/graphite-server` checks exact stack order/totals, metadata, integer precision, escaping, empty/error/overflow cases, twenty-thousand-way fanout, private temporaries, atomic replacement, actual nonempty runtime CPU stacks, concurrent Stop and CLI opt-in behavior. The real CPU exercise checks sampling correctness only; it is not a benchmark and has no throughput/latency assertion. Full-module `go test -race ./...` and `go vet ./...` are required.

For optional browser fixtures:

```sh
GRAPHITE_PROFILE_UI_FIXTURES=/tmp/profile-ui \
  go test ./internal/profiling -run TestProfileBrowserFixtures -count=1
```

These generated synthetic profiles verify only format and interaction behavior. The task's independent Chrome CUA checks used a loopback HTTP preview and covered search percentages, zoom/back, current-view detail reset, empty data and Next match on a 20,000-branch profile. Direct `file://` navigation was blocked by the browser tool's URL policy and was not bypassed or claimed as verified; no external resources are needed by the generated artifact.

# Native metrics correctness evidence

`GraphiteMetricsOracle.java` directly instantiates `ServerPerformanceMetrics` from the pinned main fat JAR at revision `4e328b0109e13c896b74004823fb049fcb19251a`. It records one event for each of the five outcomes and two rejected requests. `native-capture.go.txt` invokes the native recorder identically. No HTTP server or performance workload runs in these captures.

Both outputs parse with the official Python `prometheus-client==0.22.1` parser. `verify.py` compares the exact application sample names and labels, and all stable count/gauge/bucket values. **63/63 application samples match**, including integer bucket labels spelled `1.0`, `5.0`, `30.0`, and `120.0`. Durations are actual local recorder lifetimes and are deliberately excluded from cross-runtime value comparison. Unit tests separately validate exact elapsed durations, boundary inclusion, all outcome lifecycles, concurrency, disabled behavior, bounded HTTP templates, and maximum expiration.

The tested main JAR bytecode establishes `PrometheusConfig.step()` defaults to one minute and `PrometheusMeterRegistry.defaultHistogramConfig()` uses that step for expiry. `TimeWindowMax` uses three buffers. Native maximum expiration implements these three one-minute buffers; cumulative histogram counters are not reset with maximum expiry.

Native application names preserve `graphite_cypher_*`. HTTP uses `graphite_http_request_duration_seconds`, with template `uri`, bounded method/status labels, and the same SLO bounds. At most 64 distinct route templates are admitted. Go runtime metrics come from `runtime/metrics`, without `ReadMemStats`. GC pause distribution uses explicit cumulative counters `go_gc_pause_bucket_total{le="..."}` and `go_gc_pauses_total`: Go supplies exact bucket counts but no exact duration sum, so the recorder does not fabricate a sum or declare an incomplete histogram. Finite runtime upper bounds are converted with `math.Nextafter(upper,-Inf)` because runtime buckets are exclusive and `le` is inclusive. The [Prometheus exposition specification](https://prometheus.io/docs/instrumenting/exposition_formats/#histograms-and-summaries) describes the histogram sum/count/bucket convention.

No `jvm_*` or `jetty_*` samples are fabricated for Go. Native operational metrics therefore have documented runtime-specific names; this evidence does not claim byte-identical complete JVM runtime exposition or full server parity.

Verification commands:

```sh
cd graphite-server
go test -race ./internal/server -count=1
go vet ./internal/server
# From repository root, in an isolated environment with prometheus-client 0.22.1:
python docs/go-server-baseline/metrics-correctness/verify.py
```

To recapture Java, compile/run the saved source with the pinned main JAR on the classpath. To recapture Go, temporarily copy `native-capture.go.txt` to `graphite-server/cmd/metricscheck/main.go`, run `go run ./cmd/metricscheck`, and remove the temporary command. Preserve existing outputs before replaying. The 64-graph frozen preflight predates metrics implementation and does not validate these later source changes.

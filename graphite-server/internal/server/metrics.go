package server

import (
	"fmt"
	"math"
	"net/http"
	"runtime/metrics"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// QueryOutcome matches the bounded application outcome tags on main.
type QueryOutcome string

const (
	OutcomeSuccess        QueryOutcome = "success"
	OutcomeCancelled      QueryOutcome = "cancelled"
	OutcomeTimeout        QueryOutcome = "timeout"
	OutcomeBudgetExceeded QueryOutcome = "budget_exceeded"
	OutcomeFailed         QueryOutcome = "failed"
)

var queryOutcomes = [...]QueryOutcome{OutcomeSuccess, OutcomeCancelled, OutcomeTimeout, OutcomeBudgetExceeded, OutcomeFailed}
var queryDurationBounds = [...]float64{.01, .05, .1, .5, 1, 5, 30, 120}
var queryDurationLabels = [...]string{"0.01", "0.05", "0.1", "0.5", "1.0", "5.0", "30.0", "120.0"}

type queryTimer struct {
	count       uint64
	nanoseconds uint64
	buckets     [len(queryDurationBounds)]uint64
	// Micrometer's default TimeWindowMax uses three one-minute buffers.
	maxima  [3]float64
	current int
	rotated time.Time
}

func (t *queryTimer) rotate(now time.Time) {
	const expiry = time.Minute
	n := int(now.Sub(t.rotated) / expiry)
	if n <= 0 {
		return
	}
	if n >= len(t.maxima) {
		t.maxima = [3]float64{}
		t.current = 0
	} else {
		for i := 0; i < n; i++ {
			t.maxima[t.current] = 0
			t.current = (t.current + 1) % len(t.maxima)
		}
	}
	t.rotated = t.rotated.Add(time.Duration(n) * expiry)
}

// PerformanceMetrics records accepted-query lifetimes through serialization.
// All methods permit a nil receiver, so disabled metrics require no recorder.
// Runtime series use Go names; JVM and Jetty series are deliberately not invented.
type PerformanceMetrics struct {
	mu         sync.Mutex
	active     int64
	limit      int
	rejected   uint64
	timers     map[QueryOutcome]*queryTimer
	httpTimers map[httpMetricKey]*queryTimer
	httpRoutes map[string]struct{}
	now        func() time.Time
	started    time.Time
}

func NewPerformanceMetrics(maxConcurrent int) *PerformanceMetrics {
	now := time.Now()
	m := &PerformanceMetrics{limit: maxConcurrent, timers: make(map[QueryOutcome]*queryTimer), now: time.Now, started: now, httpTimers: make(map[httpMetricKey]*queryTimer), httpRoutes: make(map[string]struct{})}
	for _, outcome := range queryOutcomes {
		m.timers[outcome] = &queryTimer{rotated: now}
	}
	return m
}
func (m *PerformanceMetrics) Start() time.Time {
	if m == nil {
		return time.Time{}
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.active++
	return m.now()
}
func (m *PerformanceMetrics) Stop(started time.Time, outcome QueryOutcome) {
	if m == nil {
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.now()
	seconds := now.Sub(started).Seconds()
	if seconds < 0 {
		seconds = 0
	}
	timer, ok := m.timers[outcome]
	if !ok {
		timer = m.timers[OutcomeFailed]
	}
	timer.rotate(now)
	timer.count++
	timer.nanoseconds += uint64(max(0, now.Sub(started)))
	for i, bound := range queryDurationBounds {
		if seconds <= bound {
			timer.buckets[i]++
		}
	}
	for i := range timer.maxima {
		if seconds > timer.maxima[i] {
			timer.maxima[i] = seconds
		}
	}
	m.active--
}
func (m *PerformanceMetrics) Reject() {
	if m == nil {
		return
	}
	m.mu.Lock()
	m.rejected++
	m.mu.Unlock()
}

func metricFamily(b *strings.Builder, name, kind, help string) {
	fmt.Fprintf(b, "# HELP %s %s\n# TYPE %s %s\n", name, help, name, kind)
}
func metricFloat(v float64) string { return strconv.FormatFloat(v, 'g', -1, 64) }

// ServeHTTP exposes Prometheus 0.0.4 text. HTTP instrumentation is a separate
// integration concern; this handler emits application and native runtime metrics.
func (m *PerformanceMetrics) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if m == nil {
		http.NotFound(w, r)
		return
	}
	var b strings.Builder
	m.mu.Lock()
	now := m.now()
	metricFamily(&b, "graphite_cypher_queries_active", "gauge", "Accepted Cypher queries that have not completed")
	fmt.Fprintf(&b, "graphite_cypher_queries_active %d\n", m.active)
	metricFamily(&b, "graphite_cypher_queries_limit", "gauge", "Maximum concurrent Cypher queries")
	fmt.Fprintf(&b, "graphite_cypher_queries_limit %d\n", m.limit)
	metricFamily(&b, "graphite_cypher_queries_rejected_total", "counter", "Cypher queries rejected by the concurrency guard")
	fmt.Fprintf(&b, "graphite_cypher_queries_rejected_total %d\n", m.rejected)
	const name = "graphite_cypher_query_duration_seconds"
	metricFamily(&b, name, "histogram", "Cypher query execution time")
	for _, outcome := range queryOutcomes {
		t := m.timers[outcome]
		for i, label := range queryDurationLabels {
			fmt.Fprintf(&b, "%s_bucket{outcome=%q,le=%q} %d\n", name, outcome, label, t.buckets[i])
		}
		fmt.Fprintf(&b, "%s_bucket{outcome=%q,le=\"+Inf\"} %d\n", name, outcome, t.count)
		fmt.Fprintf(&b, "%s_count{outcome=%q} %d\n", name, outcome, t.count)
		fmt.Fprintf(&b, "%s_sum{outcome=%q} %s\n", name, outcome, metricFloat(float64(t.nanoseconds)/float64(time.Second)))
	}
	metricFamily(&b, name+"_max", "gauge", "Maximum Cypher query execution time over the time window")
	for _, outcome := range queryOutcomes {
		t := m.timers[outcome]
		t.rotate(now)
		fmt.Fprintf(&b, "%s_max{outcome=%q} %s\n", name, outcome, metricFloat(t.maxima[t.current]))
	}
	m.writeHTTPMetrics(&b)
	uptime := now.Sub(m.started).Seconds()
	m.mu.Unlock()
	metricFamily(&b, "process_uptime_seconds", "gauge", "Uptime of the process")
	fmt.Fprintf(&b, "process_uptime_seconds %s\n", metricFloat(math.Max(0, uptime)))
	writeGoRuntimeMetrics(&b)
	w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
	_, _ = w.Write([]byte(b.String()))
}

func writeGoRuntimeMetrics(b *strings.Builder) {
	definitions := []struct{ key, name, kind, help string }{
		{"/memory/classes/heap/objects:bytes", "go_heap_objects_bytes", "gauge", "Bytes occupied by live or unswept heap objects"},
		{"/gc/heap/allocs:bytes", "go_heap_allocated_bytes_total", "counter", "Cumulative bytes allocated to the heap"},
		{"/gc/heap/frees:bytes", "go_heap_freed_bytes_total", "counter", "Cumulative bytes freed from the heap"},
		{"/gc/cycles/total:gc-cycles", "go_gc_cycles_total", "counter", "Completed garbage collection cycles"},
		{"/sched/goroutines:goroutines", "go_goroutines", "gauge", "Live goroutines"},
		{"/cpu/classes/gc/total:cpu-seconds", "go_gc_cpu_seconds_total", "counter", "Estimated CPU time spent in garbage collection"},
	}
	samples := make([]metrics.Sample, len(definitions)+1)
	for i, d := range definitions {
		samples[i].Name = d.key
	}
	samples[len(definitions)].Name = "/gc/pauses:seconds"
	metrics.Read(samples)
	for i, d := range definitions {
		v := samples[i].Value
		var value string
		switch v.Kind() {
		case metrics.KindUint64:
			value = strconv.FormatUint(v.Uint64(), 10)
		case metrics.KindFloat64:
			value = metricFloat(v.Float64())
		default:
			continue
		}
		metricFamily(b, d.name, d.kind, d.help)
		fmt.Fprintf(b, "%s %s\n", d.name, value)
	}
	// runtime/metrics provides exact pause bucket counts but no exact sum; do not
	// fabricate a histogram sum from bucket midpoints.
	v := samples[len(definitions)].Value
	if v.Kind() != metrics.KindFloat64Histogram {
		return
	}
	h := v.Float64Histogram()
	var count uint64
	const name = "go_gc_pause_bucket_total"
	metricFamily(b, name, "counter", "Cumulative GC pause counts below each inclusive bound in seconds")
	for i, n := range h.Counts {
		count += n
		if math.IsInf(h.Buckets[i+1], -1) {
			continue
		}
		// Runtime bucket upper bounds are exclusive; Prometheus le is inclusive.
		upper := metricFloat(math.Nextafter(h.Buckets[i+1], math.Inf(-1)))
		if math.IsInf(h.Buckets[i+1], 1) {
			upper = "+Inf"
		}
		fmt.Fprintf(b, "%s{le=%q} %d\n", name, upper, count)
	}
	metricFamily(b, "go_gc_pauses_total", "counter", "Total garbage collection stop-the-world pauses")
	fmt.Fprintf(b, "go_gc_pauses_total %d\n", count)
}

type httpMetricKey struct{ method, route, status string }

// RecordHTTP accepts only route templates from the router, never raw request
// paths. At most 64 routes, 10 methods, and 501 status values can create series.
// Requests beyond the route cap are ignored, matching main's meter deny policy.
func (m *PerformanceMetrics) RecordHTTP(method, route string, status int, elapsed time.Duration) {
	if m == nil {
		return
	}
	switch method {
	case "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "CONNECT", "TRACE":
	default:
		method = "OTHER"
	}
	statusTag := "UNKNOWN"
	if status >= 100 && status <= 599 {
		statusTag = strconv.Itoa(status)
	}
	if route == "" {
		route = "unmatched"
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.httpRoutes[route]; !ok {
		if len(m.httpRoutes) >= 64 {
			return
		}
		m.httpRoutes[route] = struct{}{}
	}
	key := httpMetricKey{method, route, statusTag}
	t := m.httpTimers[key]
	if t == nil {
		t = &queryTimer{}
		m.httpTimers[key] = t
	}
	seconds := math.Max(0, elapsed.Seconds())
	t.count++
	t.nanoseconds += uint64(max(0, elapsed))
	for i, bound := range queryDurationBounds {
		if seconds <= bound {
			t.buckets[i]++
		}
	}
}

// Caller holds m.mu so each scrape observes coherent counters and sums.
func (m *PerformanceMetrics) writeHTTPMetrics(b *strings.Builder) {
	if len(m.httpTimers) == 0 {
		return
	}
	const name = "graphite_http_request_duration_seconds"
	metricFamily(b, name, "histogram", "HTTP request duration using bounded route templates")
	keys := make([]httpMetricKey, 0, len(m.httpTimers))
	for key := range m.httpTimers {
		keys = append(keys, key)
	}
	sort.Slice(keys, func(i, j int) bool {
		a, c := keys[i], keys[j]
		if a.route != c.route {
			return a.route < c.route
		}
		if a.method != c.method {
			return a.method < c.method
		}
		return a.status < c.status
	})
	for _, key := range keys {
		t := m.httpTimers[key]
		for i, label := range queryDurationLabels {
			fmt.Fprintf(b, "%s_bucket{method=%q,uri=%q,status=%q,le=%q} %d\n", name, key.method, key.route, key.status, label, t.buckets[i])
		}
		fmt.Fprintf(b, "%s_bucket{method=%q,uri=%q,status=%q,le=\"+Inf\"} %d\n", name, key.method, key.route, key.status, t.count)
		fmt.Fprintf(b, "%s_count{method=%q,uri=%q,status=%q} %d\n", name, key.method, key.route, key.status, t.count)
		fmt.Fprintf(b, "%s_sum{method=%q,uri=%q,status=%q} %s\n", name, key.method, key.route, key.status, metricFloat(float64(t.nanoseconds)/float64(time.Second)))
	}
}

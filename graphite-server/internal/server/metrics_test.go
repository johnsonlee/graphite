package server

import (
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

func scrapePerformance(t *testing.T, m *PerformanceMetrics) map[string]float64 {
	t.Helper()
	response := httptest.NewRecorder()
	m.ServeHTTP(response, httptest.NewRequest("GET", "/metrics", nil))
	if response.Code != 200 || response.Header().Get("Content-Type") != "text/plain; version=0.0.4; charset=utf-8" {
		t.Fatalf("scrape response: %d %v", response.Code, response.Header())
	}
	body := response.Body.String()
	if strings.Contains(body, "jvm_") || strings.Contains(body, "jetty_") {
		t.Fatal("native recorder must not invent JVM/Jetty series")
	}
	result := map[string]float64{}
	for _, line := range strings.Split(body, "\n") {
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) != 2 {
			t.Fatalf("invalid sample %q", line)
		}
		value, err := strconv.ParseFloat(fields[1], 64)
		if err != nil {
			t.Fatal(err)
		}
		if _, ok := result[fields[0]]; ok {
			t.Fatalf("duplicate sample %s", fields[0])
		}
		result[fields[0]] = value
	}
	return result
}
func metricEquals(t *testing.T, values map[string]float64, key string, want float64) {
	t.Helper()
	got, ok := values[key]
	if !ok || got != want {
		t.Errorf("%s: got %v (present %v), want %v", key, got, ok, want)
	}
}
func TestPerformanceMetricsOutcomeLifecycle(t *testing.T) {
	m := NewPerformanceMetrics(4)
	clock := m.started
	m.now = func() time.Time { return clock }
	starts := make([]time.Time, len(queryOutcomes))
	for i := range starts {
		starts[i] = m.Start()
	}
	m.Reject()
	m.Reject()
	active := scrapePerformance(t, m)
	metricEquals(t, active, "graphite_cypher_queries_active", 5)
	metricEquals(t, active, "graphite_cypher_queries_limit", 4)
	metricEquals(t, active, "graphite_cypher_queries_rejected_total", 2)
	clock = clock.Add(50 * time.Millisecond)
	for i, outcome := range queryOutcomes {
		m.Stop(starts[i], outcome)
	}
	values := scrapePerformance(t, m)
	metricEquals(t, values, "graphite_cypher_queries_active", 0)
	for _, outcome := range queryOutcomes {
		tag := "{outcome=" + strconv.Quote(string(outcome)) + "}"
		metricEquals(t, values, "graphite_cypher_query_duration_seconds_count"+tag, 1)
		metricEquals(t, values, "graphite_cypher_query_duration_seconds_sum"+tag, .05)
		metricEquals(t, values, "graphite_cypher_query_duration_seconds_max"+tag, .05)
		prefix := "graphite_cypher_query_duration_seconds_bucket{outcome=" + strconv.Quote(string(outcome)) + ",le="
		metricEquals(t, values, prefix+"\"0.01\"}", 0)
		metricEquals(t, values, prefix+"\"0.05\"}", 1)
		metricEquals(t, values, prefix+"\"+Inf\"}", 1)
	}
	if values["go_goroutines"] < 1 || values["go_heap_allocated_bytes_total"] <= 0 {
		t.Fatal("native runtime series missing meaningful values")
	}
	if _, ok := values["go_gc_pauses_total"]; !ok {
		t.Fatal("missing GC pause count")
	}
	if _, ok := values["go_gc_pause_seconds_sum"]; ok {
		t.Fatal("runtime does not supply an exact sum; must not fabricate one")
	}
}
func TestPerformanceMetricsTimeWindowMaxAndCumulativeHistogram(t *testing.T) {
	m := NewPerformanceMetrics(1)
	clock := m.started
	m.now = func() time.Time { return clock }
	start := m.Start()
	clock = clock.Add(time.Second)
	m.Stop(start, OutcomeSuccess)
	clock = m.started.Add(2 * time.Minute)
	metricEquals(t, scrapePerformance(t, m), "graphite_cypher_query_duration_seconds_max{outcome=\"success\"}", 1)
	clock = m.started.Add(3 * time.Minute)
	values := scrapePerformance(t, m)
	metricEquals(t, values, "graphite_cypher_query_duration_seconds_max{outcome=\"success\"}", 0)
	metricEquals(t, values, "graphite_cypher_query_duration_seconds_count{outcome=\"success\"}", 1)
	metricEquals(t, values, "graphite_cypher_query_duration_seconds_sum{outcome=\"success\"}", 1)
}
func TestPerformanceMetricsConcurrentRecordsAndScrapes(t *testing.T) {
	m := NewPerformanceMetrics(32)
	const workers = 16
	const iterations = 50
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < iterations; j++ {
				start := m.Start()
				m.Reject()
				m.Stop(start, OutcomeSuccess)
			}
		}()
	}
	for i := 0; i < 10; i++ {
		scrapePerformance(t, m)
	}
	wg.Wait()
	values := scrapePerformance(t, m)
	metricEquals(t, values, "graphite_cypher_queries_active", 0)
	metricEquals(t, values, "graphite_cypher_queries_rejected_total", workers*iterations)
	metricEquals(t, values, "graphite_cypher_query_duration_seconds_count{outcome=\"success\"}", workers*iterations)
	metricEquals(t, values, "graphite_cypher_query_duration_seconds_bucket{outcome=\"success\",le=\"+Inf\"}", workers*iterations)
}
func TestPerformanceMetricsDisabledAndBoundedOutcome(t *testing.T) {
	var disabled *PerformanceMetrics
	disabled.Stop(disabled.Start(), OutcomeSuccess)
	disabled.Reject()
	response := httptest.NewRecorder()
	disabled.ServeHTTP(response, httptest.NewRequest("GET", "/metrics", nil))
	if response.Code != 404 {
		t.Fatalf("disabled metrics status %d", response.Code)
	}
	m := NewPerformanceMetrics(1)
	m.Stop(m.Start(), QueryOutcome("unbounded-user-input"))
	values := scrapePerformance(t, m)
	metricEquals(t, values, "graphite_cypher_query_duration_seconds_count{outcome=\"failed\"}", 1)
	for key := range values {
		if strings.Contains(key, "unbounded-user-input") {
			t.Fatal("unbounded outcome exposed")
		}
	}
}

func TestPerformanceMetricsHTTPBoundedTemplates(t *testing.T) {
	m := NewPerformanceMetrics(4)
	m.RecordHTTP("GET", "/work/{id}", 200, 50*time.Millisecond)
	m.RecordHTTP("GET", "/work/{id}", 200, 10*time.Millisecond)
	m.RecordHTTP("USER-DEFINED", "/work/{id}", 999, -time.Second)
	values := scrapePerformance(t, m)
	tag := "{method=\"GET\",uri=\"/work/{id}\",status=\"200\"}"
	metricEquals(t, values, "graphite_http_request_duration_seconds_count"+tag, 2)
	metricEquals(t, values, "graphite_http_request_duration_seconds_sum"+tag, .06)
	prefix := "graphite_http_request_duration_seconds_bucket{method=\"GET\",uri=\"/work/{id}\",status=\"200\",le="
	metricEquals(t, values, prefix+"\"0.01\"}", 1)
	metricEquals(t, values, prefix+"\"0.05\"}", 2)
	metricEquals(t, values, prefix+"\"1.0\"}", 2)
	metricEquals(t, values, "graphite_http_request_duration_seconds_sum{method=\"OTHER\",uri=\"/work/{id}\",status=\"UNKNOWN\"}", 0)
	for i := 1; i < 64; i++ {
		m.RecordHTTP("GET", "/route/"+strconv.Itoa(i), 200, time.Millisecond)
	}
	m.RecordHTTP("GET", "/route/rejected", 200, time.Millisecond)
	m.RecordHTTP("GET", "/work/{id}", 200, time.Millisecond)
	if len(m.httpRoutes) != 64 {
		t.Fatalf("route cardinality %d", len(m.httpRoutes))
	}
	values = scrapePerformance(t, m)
	metricEquals(t, values, "graphite_http_request_duration_seconds_count"+tag, 3)
	for key := range values {
		if strings.Contains(key, "/route/rejected") {
			t.Fatal("new route accepted above64 template cap")
		}
	}
}

func TestPerformanceMetricsHTTPConcurrentLifecycle(t *testing.T) {
	m := NewPerformanceMetrics(4)
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				m.RecordHTTP("POST", "/api/cypher", 200, time.Millisecond)
			}
		}()
	}
	for i := 0; i < 5; i++ {
		scrapePerformance(t, m)
	}
	wg.Wait()
	values := scrapePerformance(t, m)
	metricEquals(t, values, "graphite_http_request_duration_seconds_count{method=\"POST\",uri=\"/api/cypher\",status=\"200\"}", 800)
	metricEquals(t, values, "graphite_http_request_duration_seconds_bucket{method=\"POST\",uri=\"/api/cypher\",status=\"200\",le=\"+Inf\"}", 800)
}

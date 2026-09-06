package server

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHTTPMetricsUseRegisteredRoutes(t *testing.T) {
	r := nativeRegistryForTest(t, "a", "z")
	m := NewPerformanceMetrics(1)
	h := (&Server{Registry: r, Metrics: m}).Handler()
	request(t, h, "GET", "/api/graphs/a/node/999999?secret=private", "", 404)
	request(t, h, "GET", "/api/graphs/z/node/999999", "", 404)
	values := scrapePerformance(t, m)
	metricEquals(t, values, `graphite_http_request_duration_seconds_count{method="GET",uri="/api/graphs/{graphId}/node/{id}",status="404"}`, 2)
	for key := range values {
		if !strings.HasPrefix(key, "graphite_http_") {
			continue
		}
		if strings.Contains(key, "private") || strings.Contains(key, "999999") || strings.Contains(key, "/graphs/a") || strings.Contains(key, "/graphs/z") {
			t.Fatalf("unbounded request data in metric: %s", key)
		}
	}
}

func TestMetricWriterPreservesInformationalResponseAndController(t *testing.T) {
	recorder := httptest.NewRecorder()
	w := &metricResponseWriter{ResponseWriter: recorder}
	w.WriteHeader(103)
	if w.status != 0 {
		t.Fatalf("informational status committed: %d", w.status)
	}
	w.WriteHeader(201)
	w.WriteHeader(202)
	if w.status != 201 {
		t.Fatalf("final status changed: %d", w.status)
	}
	recorder = httptest.NewRecorder()
	w = &metricResponseWriter{ResponseWriter: recorder}
	if err := http.NewResponseController(w).Flush(); err != nil || !recorder.Flushed {
		t.Fatalf("controller unwrap: %v", err)
	}
}

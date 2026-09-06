package server

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestGuardMetricsTrackActualWorkerExit(t *testing.T) {
	g, err := NewGuard(1, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer g.Close()
	g.Metrics = NewPerformanceMetrics(1)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	entered, release, finished := make(chan struct{}), make(chan struct{}), make(chan error, 1)
	go func() {
		_, err := g.Execute(ctx, nil, func(context.Context) (any, error) { close(entered); <-release; return "late success", nil })
		finished <- err
	}()
	<-entered
	cancel()
	metricEquals(t, scrapePerformance(t, g.Metrics), "graphite_cypher_queries_active", 1)
	_, err = g.Execute(context.Background(), nil, func(context.Context) (any, error) { t.Error("rejected worker ran"); return nil, nil })
	var qe *QueryError
	if !errors.As(err, &qe) || qe.Status != 429 {
		t.Fatalf("admission: %v", err)
	}
	close(release)
	if err = <-finished; !errors.As(err, &qe) || qe.Code != "cypher_query_cancelled" {
		t.Fatalf("cancelled work: %v", err)
	}
	metrics := scrapePerformance(t, g.Metrics)
	metricEquals(t, metrics, "graphite_cypher_queries_active", 0)
	metricEquals(t, metrics, "graphite_cypher_queries_rejected_total", 1)
	metricEquals(t, metrics, `graphite_cypher_query_duration_seconds_count{outcome="cancelled"}`, 1)
	metricEquals(t, metrics, `graphite_cypher_query_duration_seconds_count{outcome="success"}`, 0)
}

func TestGuardMetricOutcomesAndOptionalRoute(t *testing.T) {
	g, err := NewGuard(1, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer g.Close()
	g.Metrics = NewPerformanceMetrics(1)
	_, _ = g.Execute(context.Background(), nil, func(context.Context) (any, error) { return "ok", nil })
	_, _ = g.Execute(context.Background(), nil, func(context.Context) (any, error) { return nil, errors.New("bad query") })
	timeout := time.Millisecond
	_, _ = g.Execute(context.Background(), &timeout, func(ctx context.Context) (any, error) { <-ctx.Done(); return nil, ctx.Err() })
	func() {
		defer func() {
			if got := recover(); got != "worker panic" {
				t.Errorf("panic changed: %v", got)
			}
		}()
		_, _ = g.Execute(context.Background(), nil, func(context.Context) (any, error) { panic("worker panic") })
	}()
	values := scrapePerformance(t, g.Metrics)
	metricEquals(t, values, `graphite_cypher_query_duration_seconds_count{outcome="success"}`, 1)
	metricEquals(t, values, `graphite_cypher_query_duration_seconds_count{outcome="failed"}`, 2)
	metricEquals(t, values, `graphite_cypher_query_duration_seconds_count{outcome="timeout"}`, 1)
	metricEquals(t, values, "graphite_cypher_queries_active", 0)
	request(t, (&Server{}).Handler(), "GET", "/metrics", "", 404)
	request(t, (&Server{Metrics: g.Metrics}).Handler(), "GET", "/metrics", "", 200)
}

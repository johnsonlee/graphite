package server

import (
	"errors"
	"reflect"
	"sync"
	"sync/atomic"
	"testing"
)

type testGraph struct {
	stats  Stats
	closes atomic.Int32
}

func (g *testGraph) Stats() Stats { return g.stats }
func (g *testGraph) Close() error { g.closes.Add(1); return nil }

func registryForTest(t *testing.T, loader Loader) *Registry {
	t.Helper()
	r, err := NewRegistry(t.TempDir(), "MAPPED", loader)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = r.Close() })
	return r
}

func TestReplacementKeepsLeasedSnapshotAndClosesExactlyOnce(t *testing.T) {
	first := &testGraph{stats: Stats{Nodes: 12, Edges: 25, Methods: 3, CallSites: 4}}
	second := &testGraph{stats: Stats{Nodes: 20}}
	var current Graph = first
	r := registryForTest(t, func(string, string) (Graph, error) { return current, nil })
	d, err := r.Load(" orders ", ".", "")
	if err != nil {
		t.Fatal(err)
	}
	if d.ID != "orders" || d.LoadMode != "MAPPED" || d.Stats != first.stats || d.Path != r.DataDir || d.LoadedAt == "" {
		t.Fatalf("descriptor: %+v", d)
	}
	old, _ := r.Acquire("orders")
	current = second
	replacement, err := r.Load("orders", ".", "EAGER")
	if err != nil {
		t.Fatal(err)
	}
	if replacement.Generation <= d.Generation || replacement.Nodes != 20 {
		t.Fatalf("replacement: %+v", replacement)
	}
	newLease, _ := r.Acquire("orders")
	if old.Graph != first || newLease.Graph != second || first.closes.Load() != 0 {
		t.Fatal("replacement invalidated live snapshot")
	}
	_ = old.Close()
	_ = old.Close()
	if first.closes.Load() != 1 {
		t.Fatal("old graph must close once at final release")
	}
	if removed, err := r.Unload("orders"); !removed || err != nil {
		t.Fatalf("unload: %v %v", removed, err)
	}
	if second.closes.Load() != 0 {
		t.Fatal("unload closed leased graph")
	}
	_ = r.Close()
	_ = newLease.Close()
	_ = r.Close()
	if second.closes.Load() != 1 {
		t.Fatal("replacement graph must close once")
	}
}

func TestLoadAndTopologyFailureRestorePriorCatalog(t *testing.T) {
	first, second := &testGraph{}, &testGraph{}
	var current Graph = first
	var loadErr error
	r := registryForTest(t, func(string, string) (Graph, error) { return current, loadErr })
	before, err := r.Load("a", ".", "")
	if err != nil {
		t.Fatal(err)
	}
	loadErr = errors.New("corrupt graph")
	if _, err := r.Load("a", ".", ""); err == nil {
		t.Fatal("expected failure")
	}
	loadErr = nil
	current = second
	r.Rebuild = func(entries []CatalogEntry) error {
		if len(entries) != 1 || entries[0].Graph != second {
			t.Fatal("rebuild did not see candidate")
		}
		return errors.New("topology failure")
	}
	if _, err := r.Load("a", ".", ""); err == nil {
		t.Fatal("expected topology failure")
	}
	after, _ := r.Describe("a")
	if !reflect.DeepEqual(before, *after) || first.closes.Load() != 0 || second.closes.Load() != 1 {
		t.Fatalf("rollback failed: %+v", after)
	}
	r.Rebuild = func(entries []CatalogEntry) error {
		if len(entries) != 0 {
			t.Fatal("unload callback must see empty catalog")
		}
		return errors.New("topology failure")
	}
	if removed, err := r.Unload("a"); removed || err == nil {
		t.Fatal("unload should roll back")
	}
	after, _ = r.Describe("a")
	if after == nil || after.Generation != before.Generation {
		t.Fatal("unload lost old generation")
	}
}

func TestAtomicSelectionAndCatalogOrdering(t *testing.T) {
	r := registryForTest(t, func(string, string) (Graph, error) { return &testGraph{}, nil })
	for _, id := range []string{"z", "a", "b"} {
		if _, err := r.Load(id, ".", ""); err != nil {
			t.Fatal(err)
		}
	}
	all, _ := r.AcquireSelected(nil)
	for i, id := range []string{"a", "b", "z"} {
		if all[i].ID != id {
			t.Fatal("all selection must sort ids")
		}
		_ = all[i].Close()
	}
	empty, _ := r.AcquireSelected([]string{})
	if len(empty) != 0 {
		t.Fatal("empty selection must remain empty")
	}
	selected, err := r.AcquireSelected([]string{"z", "a", "z"})
	if err != nil || len(selected) != 2 || selected[0].ID != "z" || selected[1].ID != "a" {
		t.Fatalf("selection: %v %v", selected, err)
	}
	for _, l := range selected {
		_ = l.Close()
	}
	if _, err := r.AcquireSelected([]string{"z", "missing"}); err == nil {
		t.Fatal("expected missing graph error")
	}
	if r.graphs["z"].refs != 0 {
		t.Fatal("failed selection leaked lease")
	}
}

func TestCloseDuringLoadRetiresUnpublishedGraph(t *testing.T) {
	entered, finish := make(chan struct{}), make(chan struct{})
	g := &testGraph{}
	r := registryForTest(t, func(string, string) (Graph, error) { close(entered); <-finish; return g, nil })
	done := make(chan error, 1)
	go func() { _, err := r.Load("a", ".", ""); done <- err }()
	<-entered
	_ = r.Close()
	close(finish)
	if err := <-done; !errors.Is(err, ErrRegistryClosed) {
		t.Fatalf("load after close: %v", err)
	}
	if g.closes.Load() != 1 || len(r.List()) != 0 {
		t.Fatal("late load leaked graph")
	}
}

func TestConcurrentReadersAndReplacement(t *testing.T) {
	var graphs []*testGraph
	r := registryForTest(t, func(string, string) (Graph, error) { g := &testGraph{}; graphs = append(graphs, g); return g, nil })
	if _, err := r.Load("a", ".", ""); err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for range 200 {
				l, err := r.Acquire("a")
				if err != nil {
					t.Error(err)
					return
				}
				if l.Graph.(*testGraph).closes.Load() != 0 {
					t.Error("acquired closed graph")
				}
				_ = l.Close()
			}
		}()
	}
	for range 50 {
		if _, err := r.Load("a", ".", ""); err != nil {
			t.Fatal(err)
		}
	}
	wg.Wait()
	_ = r.Close()
	for _, g := range graphs {
		if g.closes.Load() != 1 {
			t.Fatal("graph not closed exactly once")
		}
	}
}

func TestGraphIDValidation(t *testing.T) {
	for _, id := range []string{"", "../x", "_x", "é", "a/b", "a:b"} {
		if _, err := ValidateGraphID(id); err == nil {
			t.Errorf("accepted %q", id)
		}
	}
	for _, id := range []string{"a", " a.B_0-1 ", "0"} {
		if _, err := ValidateGraphID(id); err != nil {
			t.Error(err)
		}
	}
}

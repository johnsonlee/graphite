package store

import (
	"context"
	"errors"
	"reflect"
	"sync"
	"testing"
)

// Close and actual full-node consumption share the lifetime boundary, including
// strings and nested collections returned before the mapping is released.
func TestIntegrationProjectionCandidateCloseAndOwnedValues(t *testing.T) {
	s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
	expected, err := s.Node(17)
	if err != nil {
		t.Fatal(err)
	}
	first, ok, err := s.ProjectionCandidateNode(context.Background(), 17)
	if err != nil || !ok {
		t.Fatalf("first=%#v %v %v", first, ok, err)
	}
	start := make(chan struct{})
	var wg sync.WaitGroup
	for n := 0; n < 8; n++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			node, present, err := s.ProjectionCandidateNode(context.Background(), 17)
			if errors.Is(err, ErrStoreClosed) {
				return
			}
			if err != nil || !present || node.ID != 17 || node.Kind != "CallSiteNode" || !reflect.DeepEqual(node.Caller, expected.Caller) || !reflect.DeepEqual(node.Callee, expected.Callee) {
				t.Errorf("read/close node=%#v present=%v err=%v", node, present, err)
			}
		}()
	}
	close(start)
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	wg.Wait()
	if first.ID != 17 || first.Kind != "CallSiteNode" || !reflect.DeepEqual(first.Caller, expected.Caller) || !reflect.DeepEqual(first.Callee, expected.Callee) || !reflect.DeepEqual(first.Arguments, expected.Arguments) {
		t.Fatalf("closed mapping invalidated returned node: %#v", first)
	}
	if _, _, err := s.ProjectionCandidateNode(context.Background(), 17); !errors.Is(err, ErrStoreClosed) {
		t.Fatalf("read after close=%v", err)
	}
}

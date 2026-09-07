package query

import (
	"context"
	"errors"
	"reflect"
	"sync/atomic"
	"testing"
	"time"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func bindingScanner(t *testing.T, projection string) *genericDistinctScanner {
	t.Helper()
	e := evaluator{ctx: context.Background()}
	p := syncGenericPlan(t, e, "MATCH (n) WHERE n.caller_class STARTS WITH 'example' RETURN DISTINCT "+projection+" LIMIT 2")
	if !p.mainSource {
		t.Fatal("test requires main source projection")
	}
	slot := &candidateSlot{}
	position := 0
	cursor := newGenericValueCursor(context.Background(), func(context.Context) (any, bool) {
		position++
		if position > 2 {
			return nil, false
		}
		slot.node = store.Node{ID: int32(position), Kind: "AnnotationNode", Values: map[string]any{"caller_class": "example.X", "name": []string{"first", "second"}[position-1]}}
		return slot, true
	})
	t.Cleanup(cursor.close)
	return &genericDistinctScanner{e: e, plan: p, cursor: cursor, scratch: make([]any, len(p.unique)), local: newGenericDistinctRows(true)}
}
func assertBindingCleared(t *testing.T, s *genericDistinctScanner) {
	t.Helper()
	if len(s.projectionBindings) != 1 {
		t.Fatal("binding shape", s.projectionBindings)
	}
	if value, present := s.projectionBindings["n"]; !present || value != nil {
		t.Fatal("borrowed candidate retained after exit", value, present)
	}
}
func bindingCaught(f func()) (value any) { defer func() { value = recover() }(); f(); return }

func TestGenericProjectionBindingRetainsOwnedValues(t *testing.T) {
	s := bindingScanner(t, "n AS whole, [n,n.id] AS list, {node:n} AS object, [n IN [1,2] | n] AS shadow, n.graph_id AS absent")
	values, ok := s.next(context.Background(), false)
	if !ok {
		t.Fatal("first missing")
	}
	s.local.add(values, s.row(values))
	first := s.local.rows[0]
	assertBindingCleared(t, s)
	values, ok = s.next(context.Background(), false)
	if !ok || values[0].(store.Node).ID != 2 {
		t.Fatal("second candidate", values, ok)
	}
	assertBindingCleared(t, s)
	if first["whole"].(store.Node).ID != 1 || first["whole"].(store.Node).Values["name"] != "first" {
		t.Fatal("whole candidate was borrowed", first)
	}
	list := first["list"].([]any)
	if list[0].(store.Node).ID != 1 || list[1] != int32(1) {
		t.Fatal("list aliases next candidate", list)
	}
	object := first["object"].(orderedMap)
	if object.Values["node"].(store.Node).ID != 1 {
		t.Fatal("nested candidate was borrowed", object)
	}
	if !reflect.DeepEqual(first["shadow"], []any{int32(1), int32(2)}) || first["absent"] != nil {
		t.Fatal("shadowing mutated source binding", first)
	}
	if _, ok = s.next(context.Background(), true); ok {
		t.Fatal("unexpected third candidate")
	}
	assertBindingCleared(t, s)
	s.cursor.close()
	if first["whole"].(store.Node).Values["name"] != "first" {
		t.Fatal("close invalidated result")
	}
}

func TestGenericProjectionBindingPanicAndSelectedAlias(t *testing.T) {
	projection := "substring('x','bad') AS x,n.id AS x"
	initial := bindingScanner(t, projection)
	caught := bindingCaught(func() { initial.next(context.Background(), false) })
	failure, ok := caught.(*Error)
	want := "class java.lang.String cannot be cast to class java.lang.Number (java.lang.String and java.lang.Number are in module java.base of loader 'bootstrap')"
	if !ok || failure.Class != "ClassCastException" || failure.Message != want {
		t.Fatal("initial overwritten expression was not evaluated", caught)
	}
	assertBindingCleared(t, initial)
	selected := bindingScanner(t, projection)
	values, ok := selected.next(context.Background(), true)
	if !ok || !reflect.DeepEqual(values, []any{int32(1)}) {
		t.Fatal("selected projection must use final alias expression", values, ok)
	}
	assertBindingCleared(t, selected)
}

type bindingProjectionContext struct {
	context.Context
	scanner   *genericDistinctScanner
	entered   chan struct{}
	triggered atomic.Bool
}

func (c *bindingProjectionContext) observe() {
	if c.scanner.projectionBindings["n"] != nil && c.triggered.CompareAndSwap(false, true) {
		close(c.entered)
		<-c.Context.Done()
	}
}
func (c *bindingProjectionContext) Err() error            { c.observe(); return c.Context.Err() }
func (c *bindingProjectionContext) Done() <-chan struct{} { c.observe(); return c.Context.Done() }
func TestGenericProjectionBindingCancellationJoinsAndClears(t *testing.T) {
	s := bindingScanner(t, "n.id AS x")
	parent, cancel := context.WithCancel(context.Background())
	defer cancel()
	entered, exited := make(chan struct{}), make(chan struct{})
	result := make(chan any, 1)
	go func() {
		result <- bindingCaught(func() {
			runDistinctTasks(parent, 1, 1, false, func(ctx context.Context, _ int) []any {
				defer close(exited)
				probe := &bindingProjectionContext{Context: ctx, scanner: s, entered: entered}
				values, _ := s.next(probe, false)
				return values
			}, func(_ int, _ []any) bool { return false })
		})
	}()
	select {
	case <-entered:
	case value := <-result:
		t.Fatal("worker finished before cancellation checkpoint", value)
	case <-time.After(5 * time.Second):
		t.Fatal("projection callback not reached")
	}
	cancel()
	select {
	case caught := <-result:
		err, ok := caught.(error)
		if !ok || !errors.Is(err, context.Canceled) {
			t.Fatal("wrong cancellation", caught)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("canceled source task did not join")
	}
	select {
	case <-exited:
	default:
		t.Fatal("source task still active after owner returned")
	}
	assertBindingCleared(t, s)
	// A later wave uses its current request context and must not retain the first slot.
	values, ok := s.next(context.Background(), true)
	if !ok || !reflect.DeepEqual(values, []any{int32(2)}) {
		t.Fatal("canceled wave poisoned later scanner use", values, ok)
	}
	assertBindingCleared(t, s)
}

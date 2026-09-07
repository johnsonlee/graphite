package query

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func findIDReviewDir() string            { return filepath.Join("..", "..", "..", "findid-review") }
func findIDCaught(f func()) (caught any) { defer func() { caught = recover() }(); f(); return nil }
func TestDistinctStringIDActualMainMethodOracle(t *testing.T) {
	file, err := os.Open(filepath.Join(findIDReviewDir(), "main-method.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	scanner := bufio.NewScanner(file)
	total := 0
	e := evaluator{ctx: context.Background()}
	// Use explicit JVM-produced UTF16 units so JSON decoding cannot replace lone surrogates.
	for scanner.Scan() {
		var spec struct {
			StringsUTF16 [][]uint16
			Hits         []struct {
				TextUTF16 []uint16
				MainSID   int32
			}
		}
		if err := json.Unmarshal(scanner.Bytes(), &spec); err != nil {
			t.Fatal(err)
		}
		table := []string{}
		for _, units := range spec.StringsUTF16 {
			table = append(table, javaFromUTF16(units))
		}
		for _, hit := range spec.Hits {
			text := javaFromUTF16(hit.TextUTF16)
			if got := e.distinctStringTableID(table, text); got != hit.MainSID {
				t.Fatalf("table=%q text=%q got%d main%d", table, text, got, hit.MainSID)
			}
			total++
		}

	}
	if err := scanner.Err(); err != nil {
		t.Fatal(err)
	}
	t.Logf("%d actual main method lookups", total)
}

func TestDistinctStringIDUTF16EqualityAndEmptyCancellation(t *testing.T) {
	e := evaluator{ctx: context.Background()}
	if got := e.distinctStringTableID([]string{"😀"}, "\xed\xa0\xbd\xed\xb8\x80"); got != 0 {
		t.Fatalf("same UTF16 through distinct byte encodings: %d", got)
	}
	if got := e.distinctStringTableID([]string{"a", "a", "a"}, "a"); got != 1 {
		t.Fatal("first equal midpoint", got)
	}
	if got := e.distinctStringTableID([]string{"b", "a"}, "a"); got != -1 {
		t.Fatal("unsorted accepted table", got)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	e.ctx = ctx
	if got := e.distinctStringTableID(nil, "a"); got != -1 {
		t.Fatal(got)
	}
	caught := findIDCaught(func() { e.distinctStringTableID([]string{"a"}, "a") })
	if caught != context.Canceled {
		t.Fatal(caught)
	}
}
func TestDistinctStringIDActualFullQueryMain(t *testing.T) {
	var cases []struct {
		Fixture, Query string
		Sources        int
		Columns        []string
		Rows           []map[string]any
	}
	raw, err := os.ReadFile(filepath.Join(findIDReviewDir(), "main-query.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(raw, &cases); err != nil {
		t.Fatal(err)
	}
	outputs := []map[string]any{}
	for _, spec := range cases {
		t.Run(spec.Fixture, func(t *testing.T) {
			first, err := store.Open(filepath.Join(findIDReviewDir(), "fixtures", "clean"))
			if err != nil {
				t.Fatal(err)
			}
			defer first.Close()
			last, err := store.Open(filepath.Join(findIDReviewDir(), "fixtures", spec.Fixture))
			if err != nil {
				t.Fatal(err)
			}
			defer last.Close()
			graphs := []Graph{}
			for i := 0; i < spec.Sources; i++ {
				g := first
				if i == spec.Sources-1 {
					g = last
				}
				graphs = append(graphs, Graph{ID: fmt.Sprintf("g%d", i), Store: g})
			}
			got, err := ExecuteCross(context.Background(), graphs, spec.Query, nil, -1)
			if err != nil {
				t.Fatal(err)
			}
			normalized := distinctOracleResult(map[string]any{"fixture": spec.Fixture, "query": spec.Query, "sources": spec.Sources}, got, nil)
			want := distinctOracleResult(map[string]any{"fixture": spec.Fixture, "query": spec.Query, "sources": spec.Sources}, Result{Columns: spec.Columns, Rows: spec.Rows}, nil)
			if !reflect.DeepEqual(normalized, want) {
				t.Fatalf("main=%#v native=%#v", want, normalized)
			}
			outputs = append(outputs, normalized)
		})
	}
	if output := os.Getenv("FINDID_QUERY_OUTPUT"); output != "" {
		data, err := json.MarshalIndent(outputs, "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(output, append(data, '\n'), 0600); err != nil {
			t.Fatal(err)
		}
	}
}
func TestDistinctStringIDCloseAndOriginalPostingsBoundary(t *testing.T) {
	graph, err := store.Open("../store/testdata/callsite-index/store")
	if err != nil {
		t.Fatal(err)
	}
	index, ok, err := graph.PrepareDistinctStringIndex(context.Background(), store.DistinctProjectionOptions{SourceCount: 1, Limit: 1})
	if err != nil || !ok {
		t.Fatal(ok, err)
	}
	table := graph.Strings
	value := table[0]
	if err := graph.Close(); err != nil {
		t.Fatal(err)
	}
	e := evaluator{ctx: context.Background()}
	sid := e.distinctStringTableID(table, value)
	if sid < 0 {
		t.Fatal("owned table invalidated by Close")
	}
	_, err = index.Postings(context.Background(), store.CallerClass, sid)
	if !errors.Is(err, store.ErrStoreClosed) {
		t.Fatalf("lookup moved/lost Postings Close check: %v", err)
	}
}

// Deterministic cancellation trigger; it verifies an in-search boundary rather
// than relying on wall-clock timing or a separate goroutine.
type findIDCancelPoll struct {
	context.Context
	polls int
	at    int
}

func (c *findIDCancelPoll) Err() error {
	c.polls++
	if c.polls >= c.at {
		return context.Canceled
	}
	return nil
}
func TestDistinctStringIDCancellationInsideSearch(t *testing.T) {
	ctx := &findIDCancelPoll{Context: context.Background(), at: 3}
	e := evaluator{ctx: ctx}
	caught := findIDCaught(func() { e.distinctStringTableID([]string{"a", "b", "c", "d", "e", "f", "g"}, "missing") })
	if caught != context.Canceled || ctx.polls != 3 {
		t.Fatalf("panic=%v polls=%d", caught, ctx.polls)
	}
}

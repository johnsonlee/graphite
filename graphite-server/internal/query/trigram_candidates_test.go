package query

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestTrigramMainOracle(t *testing.T) {
	for _, suite := range []string{"", "/supplement"} {
		for _, variant := range []string{"", "missing", "extra"} {
			t.Run(suite+"/"+variant, func(t *testing.T) { testTrigramMainOracle(t, suite, variant) })
		}
	}
}
func testTrigramMainOracle(t *testing.T, suite, variant string) {
	var cases []struct {
		Name, Query string
		Params      map[string][]uint16
	}
	var expected []struct {
		Name           string
		Cross          bool
		Columns        []string
		Rows           []map[string]any
		Error, Message string
	}
	for file, target := range map[string]any{"cases-units.json": &cases, "queries.json": &expected} {
		raw, err := os.ReadFile("testdata/trigram-index" + suite + "/" + file)
		if err != nil {
			t.Fatal(err)
		}
		if err = json.Unmarshal(raw, target); err != nil {
			t.Fatal(err)
		}
	}
	g := trigramQueryGraph(t, variant)
	for _, want := range expected {
		var q string
		params := map[string]any{}
		for _, c := range cases {
			if c.Name == want.Name {
				q = c.Query
				for k, u := range c.Params {
					params[k] = javaFromUTF16(u)
				}
				break
			}
		}
		if q == "" {
			t.Fatal("missing case", want.Name)
		}
		for _, mode := range []string{"trigram", "directory", "scan"} {
			t.Run(want.Name+"/"+mode+map[bool]string{true: "/cross", false: "/scoped"}[want.Cross], func(t *testing.T) {
				ctx := context.Background()
				if mode == "directory" {
					ctx = context.WithValue(ctx, candidateDirectoryOnlyKey{}, true)
				}
				if mode == "scan" {
					ctx = context.WithValue(ctx, candidateScanOnlyKey{}, true)
				}
				var got Result
				var err error
				if want.Cross {
					got, err = ExecuteCross(ctx, []Graph{{"a", g}, {"b", g}}, q, params, -1)
				} else {
					got, err = Execute(ctx, g, q, params, -1)
				}
				if want.Error != "" {
					var failure *Error
					if !errors.As(err, &failure) || failure.Class != want.Error || failure.Message != want.Message {
						t.Fatalf("error %v want %s/%s", err, want.Error, want.Message)
					}
					return
				}
				if err != nil {
					t.Fatal(err)
				}
				raw, err := json.Marshal(got.Rows)
				if err != nil {
					t.Fatal(err)
				}
				var rows []map[string]any
				if err = json.Unmarshal(raw, &rows); err != nil {
					t.Fatal(err)
				}
				if !reflect.DeepEqual(got.Columns, want.Columns) || !reflect.DeepEqual(rows, want.Rows) {
					t.Fatalf("got %v %s want %v %s", got.Columns, raw, want.Columns, mustJSON(want.Rows))
				}
			})
		}
	}
}

func TestTrigramEligibilityAndExactHashes(t *testing.T) {
	e := evaluator{ctx: context.Background()}
	for _, c := range []struct {
		term, op string
		lower    bool
		want     []int32
	}{
		{"abc", "CONTAINS", false, []int32{96354}}, {"ABC", "CONTAINS", false, []int32{96354}},
		{"ABC", "CONTAINS", true, []int32{64578}}, {"aaz", "CONTAINS", false, []int32{96346}}, {"ab[", "CONTAINS", false, []int32{96346}},
		{"XΟΣ", "CONTAINS", false, nil}, {"XΟΣ", "CONTAINS", true, []int32{114236}},
		{"", "CONTAINS", true, nil}, {"ab", "CONTAINS", true, nil}, {"😀", "CONTAINS", true, nil},
		{"abc", "=", true, nil}, {"abc", "STARTS WITH", true, nil}, {"abc", "ENDS WITH", true, nil},
	} {
		got := e.candidateTrigrams(stringCandidateAtom{operand: stringCandidateOperand{lower: c.lower}, op: c.op, term: c.term})
		if !reflect.DeepEqual(got, c.want) {
			t.Errorf("%q/%s/lower%v = %v want %v", c.term, c.op, c.lower, got, c.want)
		}
	}
}

func TestTrigramPreparationCancellation(t *testing.T) {
	g := candidateGraph(t, "clean")
	clause := candidateClause(t, "MATCH (n) WHERE toLower(n.caller_class) CONTAINS 'other' OR n.callee_name='invoke' RETURN id(n)")
	warm := evaluator{ctx: context.Background(), indexFirst: true}
	if warm.indexedNodeWalker(g, clause, &candidateSlot{}) == nil {
		t.Fatal("unavailable")
	}
	counter := &traversalCancelContext{Context: context.Background(), cancelAt: int(^uint(0) >> 1)}
	e := evaluator{ctx: counter, indexFirst: true}
	if e.indexedNodeWalker(g, clause, &candidateSlot{}) == nil {
		t.Fatal("unavailable")
	}
	reached := 0
	for at := 1; at <= counter.checks; at++ {
		func() {
			c := &traversalCancelContext{Context: context.Background(), cancelAt: at}
			e := evaluator{ctx: c, indexFirst: true}
			defer func() {
				r := recover()
				if c.checks < at && r == nil {
					return
				}
				err, ok := r.(error)
				if !ok || !errors.Is(err, context.Canceled) {
					t.Fatalf("check %d reached%d got %v", at, c.checks, r)
				}
				reached++
			}()
			if e.indexedNodeWalker(g, clause, &candidateSlot{}) == nil {
				t.Fatal("unavailable before cancellation")
			}
		}()
	}
	if reached == 0 {
		t.Fatal("no cancellation points exercised")
	}
	t.Logf("canceled at %d reached preparation checks", reached)
}

func trigramQueryGraph(t *testing.T, variant string) *store.Store {
	t.Helper()
	root := "testdata/trigram-index"
	dir := filepath.Join(root, "store")
	if variant != "" {
		dst := t.TempDir()
		entries, err := os.ReadDir(dir)
		if err != nil {
			t.Fatal(err)
		}
		for _, entry := range entries {
			raw, err := os.ReadFile(filepath.Join(dir, entry.Name()))
			if err != nil {
				t.Fatal(err)
			}
			if entry.Name() == "graph.callsite-string-index" {
				raw, err = os.ReadFile(filepath.Join(root, variant+".callsite-string-index"))
				if err != nil {
					t.Fatal(err)
				}
			}
			if err = os.WriteFile(filepath.Join(dst, entry.Name()), raw, 0600); err != nil {
				t.Fatal(err)
			}
		}
		dir = dst
	}
	g, err := store.OpenMode(dir, "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { g.Close() })
	v, ok, err := g.TryCallSiteStringIndex(context.Background())
	if err != nil || !ok {
		t.Fatalf("reader %v/%v", ok, err)
	}
	if valid, err := g.CertifyCallSiteCandidates(context.Background(), v); err != nil || !valid {
		t.Fatalf("A6 proof %v/%v", valid, err)
	}
	return g
}

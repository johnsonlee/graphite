package cypher

import (
	"container/list"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"reflect"
	"strings"
	"sync"
	"testing"
)

func testParserCache(entries int, bytes int64) *parsedQueryCache {
	return &parsedQueryCache{maxEntries: entries, maxBytes: bytes}
}

func cachedParse(cache *parsedQueryCache, ctx context.Context, source string) (q *Query, err error) {
	defer recovered(&err)
	return cache.parse(ctx, source), nil
}

func TestCachedParseProtectsNestedResultsFromCallerMutation(t *testing.T) {
	const source = "MATCH p=(a:A {x:[1,{k:2}]})-[r:R*1..3 {v:4}]->(b:B) RETURN {result:[a.x,$value]} AS result ORDER BY a.x SKIP 1 LIMIT 2 UNION ALL RETURN {result:[]} AS result"
	cache := testParserCache(1024, maxParsedQueryBytes)
	first := cache.parse(context.Background(), source)
	want := cache.parseUncached(context.Background(), source)
	match := first.Branches[0].Clauses[0].(MatchClause)
	assertEqual(t, match.Patterns[0].Nodes[0].Properties["x"], List{Elements: []Expr{Literal{Value: int32(1)}, Map{Entries: map[string]Expr{"k": Literal{Value: int32(2)}}, Keys: []string{"k"}}}})
	assertEqual(t, match.Patterns[0].Relationships[0].Types, []string{"R"})
	for i := 0; i < 2; i++ { // Both a miss's returned AST and a hit's copy are owned.
		match = first.Branches[0].Clauses[0].(MatchClause)
		match.Patterns[0].Nodes[0].Labels[0] = "polluted"
		match.Patterns[0].Nodes[0].PropertyKeys[0] = "polluted"
		match.Patterns[0].Nodes[0].Properties["x"].(List).Elements[1].(Map).Entries["k"] = Literal{Value: int32(99)}
		match.Patterns[0].Relationships[0].Types[0] = "polluted"
		match.Patterns[0].Relationships[0].PropertyKeys[0] = "polluted"
		match.Patterns[0].Relationships[0].Properties["v"] = Literal{Value: int32(99)}
		*match.Patterns[0].Relationships[0].MinHops = 99
		*match.Patterns[0].Relationships[0].MaxHops = 99
		projection := first.Branches[0].Clauses[1].(ProjectionClause)
		projection.Items[0].Expression.(Map).Keys[0] = "polluted"
		projection.Items[0].Expression.(Map).Entries["result"].(List).Elements[1] = Literal{Value: "polluted"}
		projection.OrderBy[0].Expression = Literal{Value: "polluted"}
		first.UnionAll[0] = false
		first.Branches[1].Clauses[0] = DeleteClause{}
		first = cache.parse(context.Background(), source)
		if !reflect.DeepEqual(first, want) {
			t.Fatalf("caller mutation contaminated cache on round %d: %#v", i, first)
		}
	}
}

func TestCachedParserKeepsRawKeysFailuresAndSemicolonChildren(t *testing.T) {
	cache := testParserCache(1024, maxParsedQueryBytes)
	ctx := context.Background()
	for _, source := range []string{"", " \t\r\n", "\u00a0"} {
		assertEqual(t, cache.parse(ctx, source), &Query{Branches: []SingleQuery{{}}})
	}
	if len(cache.entries) != 0 {
		t.Fatal("blank source populated cache")
	}
	for i := 0; i < 2; i++ {
		if _, err := cachedParse(cache, ctx, "RETURN 'unterminated"); err == nil {
			t.Fatal("malformed source accepted")
		}
	}
	if len(cache.entries) != 0 {
		t.Fatal("parse errors populated cache")
	}
	cache.parse(ctx, "RETURN 1 AS x")
	cache.parse(ctx, " RETURN 1 AS x ")
	if len(cache.entries) != 2 {
		t.Fatal("raw whitespace keys were collapsed")
	}
	const child = "RETURN 2 AS x UNION ALL RETURN 3 AS x"
	cache.parse(ctx, child)
	cache.parse(ctx, child+"; RETURN x+1 AS y")
	q := cache.parse(ctx, child)
	assertEqual(t, q.UnionAll, []bool{true})
	if len(q.Branches[1].Clauses) != 1 {
		t.Fatal("semicolon parent altered cached child's branch")
	}
	assertEqual(t, q.Branches[1].Clauses[0].(ProjectionClause).Items[0].Expression, Literal{Value: int32(3)})
	const failedParent = "RETURN 4 AS x; RETURN 'unterminated"
	if _, err := cachedParse(cache, ctx, failedParent); err == nil {
		t.Fatal("semicolon parent must fail")
	}
	if cache.entries[failedParent] != nil || cache.entries["RETURN 4 AS x"] == nil {
		t.Fatal("failed parent must retain successful child only")
	}
}

func TestCachedParserCancellationIncludesHitsAndNeverCachesCanceledMiss(t *testing.T) {
	cache := testParserCache(1024, maxParsedQueryBytes)
	const source = "RETURN [1,2,3] AS values"
	cache.parse(context.Background(), source)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if q, err := cachedParse(cache, ctx, source); q != nil || !errors.Is(err, context.Canceled) {
		t.Fatalf("cached hit ignored cancellation: %v %v", q, err)
	}
	base, cancel := context.WithCancel(context.Background())
	defer cancel()
	canceling := &cancelAfterChecks{Context: base, cancel: cancel, after: 100}
	long := "RETURN '" + strings.Repeat("uncached", 1000) + "'"
	if q, err := cachedParse(cache, canceling, long); q != nil || !errors.Is(err, context.Canceled) {
		t.Fatalf("uncached parse did not cancel: %v %v", q, err)
	}
	if cache.entries[long] != nil {
		t.Fatal("canceled parse entered cache")
	}
	// A cache hit bypasses lexing, but copying one large clause still needs
	// cancellation checks within its nested expression containers.
	wide := "RETURN [" + strings.Repeat("1,", 5000) + "2] AS values"
	cache.parse(context.Background(), wide)
	baseHit, cancelHit := context.WithCancel(context.Background())
	defer cancelHit()
	hitContext := &cancelAfterChecks{Context: baseHit, cancel: cancelHit, after: 100}
	if q, err := cachedParse(cache, hitContext, wide); q != nil || !errors.Is(err, context.Canceled) {
		t.Fatalf("single-clause cache hit did not cancel while copying: %v %v", q, err)
	}
	last := cache.parse(context.Background(), wide).Branches[0].Clauses[0].(ProjectionClause).Items[0].Expression.(List).Elements[5000]
	assertEqual(t, last, Literal{Value: int32(2)})
}

func TestParsedQueryCacheEvictsByAccessAndByteBudget(t *testing.T) {
	ctx := context.Background()
	cache := testParserCache(1024, maxParsedQueryBytes)
	for i := 0; i < 1024; i++ {
		cache.parse(ctx, fmt.Sprintf("RETURN %d", i))
	}
	cache.parse(ctx, "RETURN 0")
	cache.parse(ctx, "RETURN 1024")
	if len(cache.entries) != 1024 || cache.entries["RETURN 1"] != nil || cache.entries["RETURN 0"] == nil {
		t.Fatal("cache did not evict the least recently accessed query")
	}
	// A Return costs 2048 + 128 plus two bytes per Java character.
	cache = testParserCache(1024, int64(2*(2048+128+2*len("RETURN 1"))))
	for _, source := range []string{"RETURN 1", "RETURN 2", "RETURN 1", "RETURN 3"} {
		cache.parse(ctx, source)
	}
	if len(cache.entries) != 2 || cache.entries["RETURN 2"] != nil {
		t.Fatal("byte budget did not evict the least recently accessed query")
	}
	cache.parse(ctx, "RETURN '"+strings.Repeat("x", 3000)+"'")
	if len(cache.entries) != 2 || cache.entries["RETURN 1"] == nil || cache.entries["RETURN 3"] == nil {
		t.Fatal("oversized entry evicted existing cached queries")
	}
}

func TestCacheByteEstimateUsesMainClauseShapeAndUTF16(t *testing.T) {
	cases := []struct {
		source  string
		clauses int64
	}{
		{"MATCH (n) WHERE n.x=1 RETURN n ORDER BY n.x SKIP 1 LIMIT 2", 6},
		{"OPTIONAL MATCH (n) WHERE n.x=1 RETURN n", 2},
		{"WITH 1 AS x WHERE x=1 RETURN x", 2},
		{"RETURN 1 UNION ALL RETURN 2; RETURN 3", 4},
	}
	cache := testParserCache(1024, maxParsedQueryBytes)
	for _, c := range cases {
		q := cache.parse(context.Background(), c.source)
		if got, want := estimatedParsedQueryBytes(context.Background(), c.source, q), 128+2*int64(len(c.source))+2048*c.clauses; got != want {
			t.Fatalf("%s: estimate %d want %d", c.source, got, want)
		}
	}
	for _, literal := range []struct {
		text  string
		units int64
	}{{"😀", 2}, {"界", 1}, {"\xed\xa0\x80", 1}, {"\xed\xa0\x80\xed\xb0\x80", 2}} {
		source := "RETURN '" + literal.text + "'"
		q := cache.parse(context.Background(), source)
		if got, want := estimatedParsedQueryBytes(context.Background(), source, q), 128+2*(9+literal.units)+2048; got != want {
			t.Fatalf("%q: estimate %d want %d", source, got, want)
		}
	}
}

func TestCacheRetainsEveryReal64QueryWithoutChangingAST(t *testing.T) {
	data, err := os.ReadFile("../benchmarkcase/testdata/main64.json")
	if err != nil {
		t.Fatal(err)
	}
	var workload struct{ Cases []struct{ Query string } }
	if err := json.Unmarshal(data, &workload); err != nil {
		t.Fatal(err)
	}
	cache := testParserCache(1024, maxParsedQueryBytes)
	ctx := context.Background()
	original := map[string]*Query{}
	for _, c := range workload.Cases {
		original[c.Query] = cache.parseUncached(ctx, c.Query)
		if got := cache.parse(ctx, c.Query); !reflect.DeepEqual(got, original[c.Query]) {
			t.Fatalf("miss changed AST for %q", c.Query)
		}
	}
	if len(original) != 750 || len(cache.entries) != len(original) {
		t.Fatalf("real64 coverage: %d unique queries, %d cached", len(original), len(cache.entries))
	}
	for _, c := range workload.Cases {
		if got := cache.parse(ctx, c.Query); !reflect.DeepEqual(got, original[c.Query]) {
			t.Fatalf("hit changed AST for %q", c.Query)
		}
	}
}

func TestConcurrentCacheMissesAndCallerMutationsAreIsolated(t *testing.T) {
	cache := testParserCache(1024, maxParsedQueryBytes)
	var workers sync.WaitGroup
	for i := 0; i < 12; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for j := 0; j < 20; j++ {
				q := cache.parse(context.Background(), "RETURN {x:[1,$value]} AS result")
				m := q.Branches[0].Clauses[0].(ProjectionClause).Items[0].Expression.(Map)
				if !reflect.DeepEqual(m.Entries["x"], List{Elements: []Expr{Literal{Value: int32(1)}, Parameter{Name: "value"}}}) {
					t.Error("concurrent mutation crossed calls")
					return
				}
				m.Entries["x"].(List).Elements[0] = Literal{Value: "changed"}
				m.Keys[0] = "changed"
			}
		}()
	}
	workers.Wait()
}

func TestCacheTransitionsMatchIndependentMainOracle(t *testing.T) {
	type entry struct {
		Query         struct{ Raw *string }
		RetainedBytes int64
	}
	type state struct {
		EntriesLruToMru []entry
		Bytes           int64
		Size            int
		MaxBytes        int64
	}
	var oracle struct {
		Events []struct {
			Name, Outcome string
			Query         struct{ Raw *string }
			Before, After *state
		}
	}
	data, err := os.ReadFile("../../../docs/go-server-baseline/native-ast-cache/main.json")
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(data, &oracle); err != nil {
		t.Fatal(err)
	}
	checked := 0
	for _, event := range oracle.Events {
		if event.Before == nil || event.After == nil || event.Query.Raw == nil || (event.Outcome != "SUCCESS" && event.Outcome != "FAILED") {
			continue
		}
		t.Run(event.Name, func(t *testing.T) {
			cache := testParserCache(1024, event.Before.MaxBytes)
			cache.entries = make(map[string]*list.Element)
			uncached := testParserCache(0, 0)
			for _, previous := range event.Before.EntriesLruToMru {
				if previous.Query.Raw == nil {
					t.Fatal("oracle omitted cache precondition source")
				}
				source := *previous.Query.Raw
				q := uncached.parse(context.Background(), source)
				cache.entries[source] = cache.lru.PushBack(&parsedQueryEntry{source: source, query: q, bytes: previous.RetainedBytes})
				cache.bytes += previous.RetainedBytes
			}
			_, err := cachedParse(cache, context.Background(), *event.Query.Raw)
			if (err == nil) != (event.Outcome == "SUCCESS") {
				t.Fatalf("main outcome %s, native %v", event.Outcome, err)
			}
			if len(cache.entries) != event.After.Size || cache.bytes != event.After.Bytes {
				t.Fatalf("cache state differs: entries %d bytes %d; main entries %d bytes %d", len(cache.entries), cache.bytes, event.After.Size, event.After.Bytes)
			}
			current := cache.lru.Front()
			for _, expected := range event.After.EntriesLruToMru {
				if expected.Query.Raw == nil || current == nil {
					t.Fatal("incomplete cache state")
				}
				got := current.Value.(*parsedQueryEntry)
				if got.source != *expected.Query.Raw || got.bytes != expected.RetainedBytes {
					t.Fatalf("LRU or estimate differs: %q %d, main %q %d", got.source, got.bytes, *expected.Query.Raw, expected.RetainedBytes)
				}
				current = current.Next()
			}
		})
		checked++
	}
	if checked < 25 {
		t.Fatalf("only %d original-main cache transitions covered", checked)
	}
}

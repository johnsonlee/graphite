package server

import (
	"context"
	"errors"
	"fmt"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"net/http/httptest"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
)

func TestDeterministicQueryChecksAllExpressionPositions(t *testing.T) {
	for _, tc := range []struct {
		query string
		want  bool
	}{
		{"MATCH (n) WHERE toLower(coalesce(n.name,'')) CONTAINS 'x' RETURN DISTINCT n.name ORDER BY n.name LIMIT 4", true},
		{"MATCH p=(a)-[e*1..3]->(b) WHERE id(a)=1 WITH nodes(p) AS ns UNWIND ns AS n RETURN n.name", true},
		{"RETURN CASE WHEN true THEN 1 ELSE rand() END", false},
		{"RETURN [n IN [1] WHERE timestamp()>0 | n]", false},
		{"RETURN all(n IN [1] WHERE rand()>0)", false},
		{"RETURN {x: [timestamp()]}", false},
		{"MATCH (n {value:rand()}) RETURN n", false},
		{"MATCH (n)-[r {value:rand()}]->(m) RETURN m", false},
		{"RETURN 1 ORDER BY timestamp()", false},
		{"RETURN 1 SKIP timestamp()", false},
		{"RETURN 1 LIMIT timestamp()", false},
		{"RETURN 1 UNION RETURN rand()", false},
		{"UNWIND [rand()] AS n RETURN n", false},
		{"RETURN futureFunction(1)", false},
		{"RETURN $p", false},
		{"RETURN [1,2][rand()]", false},
		{"RETURN [1,2][0..timestamp()]", false},
		{"CREATE (n) RETURN n", false},
	} {
		t.Run(tc.query, func(t *testing.T) {
			ast, err := cypher.Parse(tc.query)
			if err != nil {
				t.Fatal(err)
			}
			if got := deterministicQuery(ast); got != tc.want {
				t.Fatalf("eligible %v want %v", got, tc.want)
			}
		})
	}
}
func TestMemoizedResponseOwnershipAndEligibility(t *testing.T) {
	s := &Server{}
	calls := 0
	body := []byte(`{"value":7}`)
	build := func() (any, error) { calls++; return body, nil }
	one, err := s.memoizedCypher(context.Background(), "pure", "RETURN 7", build)
	if err != nil || string(one.([]byte)) != `{"value":7}` {
		t.Fatalf("%s %v", one, err)
	}
	body[9] = '8'
	two, err := s.memoizedCypher(context.Background(), "pure", "RETURN 7", build)
	if err != nil || string(two.([]byte)) != `{"value":7}` || calls != 1 {
		t.Fatalf("%s %v calls %d", two, err, calls)
	}
	for i := 0; i < 2; i++ {
		_, err = s.memoizedCypher(context.Background(), "volatile", "RETURN rand()", build)
		if err != nil {
			t.Fatal(err)
		}
	}
	if calls != 3 {
		t.Fatal("volatile response cached", calls)
	}
}
func TestCacheNeverPublishesFailedOrCancelledComputation(t *testing.T) {
	s := &Server{}
	broken := errors.New("query failed")
	_, err := s.memoizedCypher(context.Background(), "error", "RETURN 1", func() (any, error) { return nil, broken })
	if !errors.Is(err, broken) {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	_, err = s.memoizedCypher(ctx, "cancel", "RETURN 1", func() (any, error) { cancel(); return []byte("1"), nil })
	if !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	if s.queryCache.lru.Len() != 0 {
		t.Fatal("failed/cancelled cached")
	}
	s.queryCache.put("ready", []byte("1"))
	_, err = s.memoizedCypher(ctx, "ready", "RETURN 1", func() (any, error) { t.Fatal("cancelled hit executed"); return nil, nil })
	if !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}
func TestCacheBoundsAndLRU(t *testing.T) {
	c := queryResponseCache{maxBytes: 530, maxEntries: 2}
	c.put("a", []byte("one"))
	c.put("b", []byte("two"))
	c.get("a")
	c.put("c", []byte("three"))
	if _, ok := c.get("b"); ok {
		t.Fatal("least recently used entry retained")
	}
	if v, ok := c.get("a"); !ok || string(v) != "one" {
		t.Fatal("recent entry lost")
	}
	c.put("large", make([]byte, 1000))
	if c.lru.Len() != 2 || c.bytes > 530 {
		t.Fatalf("unbounded cache %d %d", c.lru.Len(), c.bytes)
	}
	if v, ok := c.get("c"); !ok || string(v) != "three" {
		t.Fatal("oversize insert evicted valid entries")
	}
}
func TestCacheGenerationAndRequestScopeIdentity(t *testing.T) {
	lease := func(id string, g uint64) *Lease {
		return &Lease{ID: id, snapshot: &snapshot{descriptor: Descriptor{Generation: g}}}
	}
	a, b := lease("a", 1), lease("b", 2)
	key := func(ls []*Lease, surface, mode string, limit, per int, include bool) string {
		return makeResponseKey("RETURN 1", surface, mode, limit, per, include, ls)
	}
	original := key([]*Lease{a, b}, "root", "cross", 10, -1, false)
	variants := []string{key([]*Lease{b, a}, "root", "cross", 10, -1, false), key([]*Lease{a}, "root", "cross", 10, -1, false), key([]*Lease{a, lease("b", 3)}, "root", "cross", 10, -1, false), key([]*Lease{a, b}, "selected", "cross", 10, -1, false), key([]*Lease{a, b}, "root", "fanout", 10, -1, false), key([]*Lease{a, b}, "root", "cross", 11, -1, false), key([]*Lease{a, b}, "root", "cross", 10, 1, false), key([]*Lease{a, b}, "root", "cross", 10, -1, true)}
	for _, v := range variants {
		if original == v {
			t.Fatal("distinct response identities collide", v)
		}
	}
	if original != key([]*Lease{a, b}, "root", "cross", 10, -1, false) {
		t.Fatal("unstable identity")
	}
}
func TestCacheConcurrentImmutableReads(t *testing.T) {
	c := queryResponseCache{maxEntries: 8, maxBytes: 10000}
	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			key := fmt.Sprint(i % 4)
			want := []byte("result-" + key)
			for j := 0; j < 30; j++ {
				c.put(key, want)
				got, ok := c.get(key)
				if !ok || !reflect.DeepEqual(got, want) {
					t.Errorf("%s: %q", key, got)
				}
			}
		}(i)
	}
	wg.Wait()
}

func TestHTTPMemoizationTracksReplacementUnloadAndFailedReload(t *testing.T) {
	generation := 0
	broken := false
	r := registryForTest(t, func(string, string) (Graph, error) {
		if broken {
			return nil, errors.New("broken replacement")
		}
		generation++
		raw, err := OpenNativeGraph("../store/testdata/jvm-v3", "MAPPED")
		if err != nil {
			return nil, err
		}
		g := raw.(*NativeGraph)
		g.Store.Metadata.MethodList = []store.MethodDescriptor{{Name: fmt.Sprintf("generation-%d", generation)}}
		return g, nil
	})
	guard, _ := NewGuard(1, time.Second)
	defer guard.Close()
	s := &Server{Registry: r, Guard: guard}
	h := s.Handler()
	body := `{"query":"MATCH (m:Method) RETURN m.name AS name"}`
	check := func(want []string, graphs float64) {
		t.Helper()
		v := responseJSON(t, request(t, h, "POST", "/api/cypher", body, 200))
		names := []string{}
		for _, row := range v["rows"].([]any) {
			names = append(names, row.(map[string]any)["name"].(string))
		}
		if !reflect.DeepEqual(names, want) || v["graphCount"] != graphs {
			t.Fatalf("response %v", v)
		}
	}
	check([]string{}, 0)
	if _, err := r.Load("a", ".", ""); err != nil {
		t.Fatal(err)
	}
	check([]string{"generation-1"}, 1)
	entries := s.queryCache.lru.Len()
	check([]string{"generation-1"}, 1)
	if s.queryCache.lru.Len() != entries {
		t.Fatal("stable identity missed cache")
	}
	broken = true
	if _, err := r.Load("a", ".", ""); err == nil {
		t.Fatal("failed replacement accepted")
	}
	check([]string{"generation-1"}, 1)
	broken = false
	if _, err := r.Load("a", ".", ""); err != nil {
		t.Fatal(err)
	}
	check([]string{"generation-2"}, 1)
	if _, err := r.Load("b", ".", ""); err != nil {
		t.Fatal(err)
	}
	check([]string{"generation-2", "generation-3"}, 2)
	for _, tc := range []struct{ ids, want string }{{`["b","a"]`, `generation-3`}, {`["a","b"]`, `generation-2`}} {
		v := responseJSON(t, request(t, h, "POST", "/api/cypher/graphs", `{"graphs":`+tc.ids+`,"query":"MATCH (m:Method) RETURN m.name AS name","limit":1}`, 200))
		if v["rows"].([]any)[0].(map[string]any)["name"] != tc.want {
			t.Fatal("selection/limit cache collision", v)
		}
	}
	if _, err := r.Unload("a"); err != nil {
		t.Fatal(err)
	}
	check([]string{"generation-3"}, 1)
	request(t, h, "POST", "/api/graphs/a/cypher", body, 404)
	if _, err := r.Unload("b"); err != nil {
		t.Fatal(err)
	}
	check([]string{}, 0)
}
func TestCachedHTTPStillUsesAdmissionAndCancellation(t *testing.T) {
	r := nativeRegistryForTest(t, "a")
	guard, _ := NewGuard(1, time.Second)
	defer guard.Close()
	s := &Server{Registry: r, Guard: guard}
	h := s.Handler()
	body := `{"query":"RETURN 7 AS value"}`
	request(t, h, "POST", "/api/cypher", body, 200)
	started, release, done := make(chan struct{}), make(chan struct{}), make(chan struct{})
	go func() {
		defer close(done)
		_, _ = guard.Execute(context.Background(), nil, func(context.Context) (any, error) { close(started); <-release; return nil, nil })
	}()
	<-started
	w := request(t, h, "POST", "/api/cypher", body, 429)
	if w.Header().Get("Retry-After") != "1" {
		t.Fatal("cached request lost admission header")
	}
	close(release)
	<-done
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	w = httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest("POST", "/api/cypher", strings.NewReader(body)).WithContext(ctx))
	if w.Code != 503 || !strings.Contains(w.Body.String(), "cypher_query_cancelled") {
		t.Fatalf("cancelled cache hit %d %s", w.Code, w.Body.String())
	}
}

func TestCacheIdentityPreservesNonUTF8QueryBytes(t *testing.T) {
	keys := map[string]bool{}
	for _, query := range []string{"RETURN '\xed\xa0\x80'", "RETURN '\xed\xb0\x80'", "RETURN '\ufffd\ufffd\ufffd'"} {
		key := makeResponseKey(query, "root", "cross", 1, -1, false, nil)
		if keys[key] {
			t.Fatal("distinct Java strings merged")
		}
		keys[key] = true
	}
}

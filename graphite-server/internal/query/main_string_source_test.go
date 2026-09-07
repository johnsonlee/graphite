package query

import (
	"context"
	"encoding/binary"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"strings"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// A checkpoint observer over a real cancellable context. Count only the public
// canonical-order accessor's own check, excluding its nested preparation check.
type mainOrderCheckpoint struct {
	context.Context
	cancel          context.CancelFunc
	calls, cancelAt int
}

func (c *mainOrderCheckpoint) Err() error {
	pc, _, _, _ := runtime.Caller(1)
	if f := runtime.FuncForPC(pc); f != nil && strings.HasSuffix(f.Name(), ".ProjectionNodeOrder") {
		c.calls++
		if c.calls == c.cancelAt {
			c.cancel()
		}
	}
	return c.Context.Err()
}

func TestMainStringMappedColdWarmDemandAndCancellation(t *testing.T) {
	for _, warm := range []bool{false, true} {
		for _, cancelAt := range []int{0, 2} {
			t.Run(fmt.Sprintf("warm=%v/cancel=%d", warm, cancelAt), func(t *testing.T) {
				g := candidateGraph(t, "clean")
				source := Graph{Store: g}
				plan := &mainStringSourceSpec{lazyMain: true, sourceCount: 40, atoms: []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: "other"}}}
				if warm {
					next := (evaluator{ctx: context.Background()}).mainCandidateIterator(source, plan, 4)
					for {
						if _, ok := next(context.Background()); !ok {
							break
						}
					}
				}
				base, cancel := context.WithCancel(context.Background())
				defer cancel()
				ctx := &mainOrderCheckpoint{Context: base, cancel: cancel, cancelAt: cancelAt}
				var next mainNodeNext
				failure := findIDCaught(func() { next = (evaluator{ctx: ctx}).mainCandidateIterator(source, plan, 4) })
				if !warm && cancelAt == 2 {
					if failure != context.Canceled || next != nil {
						t.Fatal("cold full validation must fail before sequence", failure, next != nil)
					}
					return
				}
				if failure != nil {
					t.Fatal(failure)
				}
				want := 2
				if warm {
					want = 1
				}
				if ctx.calls != want {
					t.Fatal("construction order checks", ctx.calls, want)
				}
				n, ok := next(ctx)
				if !ok || n.ID != 2 || ctx.calls != want {
					t.Fatal("first owned node", n.ID, ok, ctx.calls)
				}
				failure = findIDCaught(func() { n, ok = next(ctx) })
				if warm && cancelAt == 2 {
					if failure != context.Canceled {
						t.Fatal("hot cancellation must occur after first node", failure)
					}
					return
				}
				if failure != nil || !ok || n.ID != 41 || ctx.calls != 2 {
					t.Fatal("second node/order", failure, n.ID, ok, ctx.calls)
				}
				if _, ok := next(ctx); ok || ctx.calls != 2 {
					t.Fatal("unexpected exhaustion work", ok, ctx.calls)
				}
			})
		}
	}
}

func mainSourceFixture(t *testing.T, name string) string {
	t.Helper()
	base := name
	if name == "offset-negative-90" || name == "offset-negative-2" {
		base = "clean"
	}
	if name == "valid-tag" || name == "unknown-tag" {
		base = "clean"
	}
	var mutations map[string]struct {
		Offset int
		SID    int32
	}
	readDistinctJSON(t, "testdata/main-string-source/property-mutations.json", &mutations)
	fixture := "testdata/candidate-index/" + base
	if _, ok := mutations[name]; ok || name == "all-types" {
		fixture = "testdata/main-string-source/all-types"
	}
	dir := ordinaryCopyFixture(t, fixture)
	if mutation, ok := mutations[name]; ok {
		p := filepath.Join(dir, "graph.nodedata")
		data, err := os.ReadFile(p)
		if err != nil {
			t.Fatal(err)
		}
		binary.BigEndian.PutUint32(data[mutation.Offset:], uint32(mutation.SID))
		if err = os.WriteFile(p, data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	if name == "valid-tag" || name == "unknown-tag" {
		p := filepath.Join(dir, "graph.nodedata")
		data, err := os.ReadFile(p)
		if err != nil {
			t.Fatal(err)
		}
		data[69] = 0
		if name == "unknown-tag" {
			data[69] = 127
		}
		if err = os.WriteFile(p, data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	if name == "offset-negative-90" || name == "offset-negative-2" {
		id := 90
		if name == "offset-negative-2" {
			id = 2
		}
		p := filepath.Join(dir, "graph.nodeoffsets")
		data, err := os.ReadFile(p)
		if err != nil {
			t.Fatal(err)
		}
		binary.BigEndian.PutUint64(data[8+id*8:], ^uint64(0))
		if err := os.WriteFile(p, data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	return dir
}

func TestMainStringSourceOffsetOracle(t *testing.T)   { mainSourceOracle(t, "offset-main.json", 6) }
func TestMainStringSourceExecutorOracle(t *testing.T) { mainSourceOracle(t, "main.json", 41) }
func TestMainStringSourcePropertyOracle(t *testing.T) { mainSourceOracle(t, "property-main.json", 11) }
func mainSourceOracle(t *testing.T, file string, countExpected int) {
	var records []map[string]any
	readDistinctJSON(t, "testdata/main-string-source/"+file, &records)
	if len(records) != countExpected {
		t.Fatal("oracle denominator", len(records))
	}
	actualRecords := []map[string]any{}
	for _, record := range records {
		spec := record["spec"].(map[string]any)
		t.Run(spec["name"].(string), func(t *testing.T) {
			fixtures := spec["fixtures"].([]any)
			count := int(spec["sources"].(float64))
			sources := make([]Graph, count)
			dirs := map[string]string{}
			for i := range sources {
				name := fixtures[min(i, len(fixtures)-1)].(string)
				dir, ok := dirs[name]
				if !ok {
					dir = mainSourceFixture(t, name)
					dirs[name] = dir
				}
				g, err := store.OpenMode(dir, "MAPPED")
				if err != nil {
					t.Fatal(err)
				}
				defer g.Close()
				id := "single"
				if count > 1 {
					id = fmt.Sprintf("g%d", i)
				}
				sources[i] = Graph{id, g}
			}
			actual := map[string]any{"spec": spec}
			if warm, ok := record["warm"].(map[string]any); ok {
				got := ordinaryHistoryResult(t, sources, count > 1, spec["warm"].(string))
				actual["warm"] = got
				if !reflect.DeepEqual(ordinaryObservedResponse(got), ordinaryObservedResponse(warm)) {
					t.Errorf("warm main=%s native=%s", mustJSON(warm), mustJSON(got))
				}
			}
			targets := []map[string]any{}
			for _, value := range record["targets"].([]any) {
				expected := value.(map[string]any)
				got := ordinaryHistoryResult(t, sources, count > 1, spec["query"].(string))
				targets = append(targets, got)
				if !reflect.DeepEqual(ordinaryObservedResponse(got), ordinaryObservedResponse(expected)) {
					t.Errorf("main=%s native=%s", mustJSON(expected), mustJSON(got))
				}
			}
			actual["targets"] = targets
			actualRecords = append(actualRecords, actual)
		})
	}
	writeDistinctEvidence(t, "main-string-source-"+file+"-native.json", actualRecords)
}

func TestMainStringSourceMergeDemandAndErrors(t *testing.T) {
	g := candidateGraph(t, "clean")
	source := Graph{Store: g}
	e := evaluator{ctx: context.Background()}
	trace := []string{}
	child := func(name string, ids ...int32) mainNodeNext {
		position := 0
		return func(context.Context) (store.Node, bool) {
			if position == len(ids) {
				trace = append(trace, name+" EOF")
				return store.Node{}, false
			}
			id := ids[position]
			position++
			trace = append(trace, fmt.Sprintf("%s %d", name, id))
			return store.Node{ID: id}, true
		}
	}
	next := e.mainMergeNodeSequences(source, []mainNodeNext{child("a", 17, 90), child("b", 2, 41)})
	n, ok := next(e.ctx)
	if !ok || n.ID != 17 || !reflect.DeepEqual(trace, []string{"a 17", "b 2"}) {
		t.Fatal(n, ok, trace)
	}
	values := []int32{n.ID}
	for {
		n, ok = next(e.ctx)
		if !ok {
			break
		}
		values = append(values, n.ID)
	}
	if !reflect.DeepEqual(values, []int32{17, 2, 41, 90}) {
		t.Fatal(values)
	}
	trace = nil
	failure := fmt.Errorf("annotation head")
	next = e.mainMergeNodeSequences(source, []mainNodeNext{child("first", 17), func(context.Context) (store.Node, bool) { trace = append(trace, "throw head"); panic(failure) }})
	if got := findIDCaught(func() { next(e.ctx) }); got != failure || !reflect.DeepEqual(trace, []string{"first 17", "throw head"}) {
		t.Fatal(got, trace)
	}
	next = e.mainMergeNodeSequences(source, []mainNodeNext{child("decreasing", 90, 17)})
	if n, ok := next(e.ctx); !ok || n.ID != 90 {
		t.Fatal(n, ok)
	}
	if got := findIDCaught(func() { next(e.ctx) }); got == nil {
		t.Fatal("decreasing stream accepted")
	} else if err, ok := got.(*Error); !ok || err.Class != "IllegalArgumentException" || err.Message != "String property lookup sequence is not monotonic in canonical graph order" {
		t.Fatal(got)
	}
	next = e.mainMergeNodeSequences(source, []mainNodeNext{child("dup1", 17, 2), child("dup2", 17, 2)})
	values = nil
	for {
		n, ok := next(e.ctx)
		if !ok {
			break
		}
		values = append(values, n.ID)
	}
	if !reflect.DeepEqual(values, []int32{17, 2}) {
		t.Fatal("duplicate IDs", values)
	}
}

func TestMainStringSourceNaturalExhaustionCacheAndCancellation(t *testing.T) {
	g := candidateGraph(t, "clean")
	e := evaluator{ctx: context.Background()}
	source := Graph{Store: g}
	index, available, err := g.PrepareDistinctStringIndex(e.ctx, store.DistinctProjectionOptions{SourceCount: 1, Limit: 1000, SkipPreparedPreference: true})
	if err != nil || !available || index.Raw {
		t.Fatal(index, available, err)
	}
	plan := &mainStringSourceSpec{atoms: []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: "other"}}, sourceCount: 1}
	cached := func(limit int) bool {
		_, ok, err := index.ProjectionCachedIDs(e.ctx, store.ProjectionNodeMatches, mainNodeKey(plan, limit))
		if err != nil {
			t.Fatal(err)
		}
		return ok
	}
	next := e.mainIndexNodeIDs(source, index, plan, 1)
	if id, ok := next(e.ctx); !ok || id != 2 {
		t.Fatal(id, ok)
	}
	if cached(1) {
		t.Fatal("yield of final wanted ID incorrectly publishes cache")
	}
	if _, ok := next(e.ctx); ok {
		t.Fatal("over limit")
	}
	if !cached(1) {
		t.Fatal("natural completion did not publish")
	}
	next = e.mainIndexNodeIDs(source, index, plan, 2)
	if id, ok := next(e.ctx); !ok || id != 2 {
		t.Fatal(id, ok)
	}
	ctx, cancel := context.WithCancel(e.ctx)
	cancel()
	if got := findIDCaught(func() { next(ctx) }); got != context.Canceled {
		t.Fatal(got)
	}
	if cached(2) {
		t.Fatal("canceled partial cursor published node cache")
	}
	next = e.mainIndexNodeIDs(source, index, plan, 2)
	ids := []int32{}
	for {
		id, ok := next(e.ctx)
		if !ok {
			break
		}
		ids = append(ids, id)
	}
	if !reflect.DeepEqual(ids, []int32{2, 41}) || !cached(2) {
		t.Fatal(ids, cached(2))
	}
	if current, ok, err := g.RetainedProjectionIndex(e.ctx); err != nil || !ok || current == nil {
		t.Fatal("successful publication lost", ok, err)
	}
}

func TestMainStringSourceSeparateCertificateAndOwnedNode(t *testing.T) {
	g := candidateGraph(t, "bad-first-unmatched")
	e := evaluator{ctx: context.Background()}
	clause := candidateClause(t, "MATCH (n:CallSite) WHERE n.caller_name CONTAINS 'other' RETURN id(n)")
	if _, available := e.prepareIndexedNodePositions(g, clause); available {
		t.Fatal("A6 whole-node certificate unexpectedly relaxed")
	}
	next := e.mainStringCandidates(Graph{Store: g}, clause.Patterns[0].Nodes[0], []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: "other"}}, 1)
	node, ok := next(e.ctx)
	if !ok || node.ID != 2 || node.Caller.Name != "other" {
		t.Fatal(node, ok)
	}
	if err := g.Close(); err != nil {
		t.Fatal(err)
	}
	if node.Caller.Name != "other" || node.Callee.Name != "other" {
		t.Fatal("returned node not owned")
	}
	if got := findIDCaught(func() { next(e.ctx) }); got != store.ErrStoreClosed {
		t.Fatal("closed source advanced", got)
	}
}

func TestMainStringSourceWholeNodeEqualityAndOwnership(t *testing.T) {
	pair := javaFromUTF16([]uint16{0xd83d, 0xde00})
	surrogatePair := "\xed\xa0\xbd\xed\xb8\x80"
	a := store.Node{ID: 7, Kind: "AnnotationNode", Name: pair, Values: map[string]any{"v": int32(1), "w": int64(2)}, ValueOrder: []string{"v", "w"}}
	b := a
	b.Name = surrogatePair
	b.ValueOrder = []string{"w", "v"}
	if !genericJavaEqual(a, b) || genericJavaHash(a) != genericJavaHash(b) {
		t.Fatal("semantic node fields lost Java equality")
	}
	b.Values = map[string]any{"v": int64(1), "w": int64(2)}
	if genericJavaEqual(a, b) {
		t.Fatal("boxed annotation types collapsed")
	}
	ga, gb := &store.Store{}, &store.Store{}
	qa, qb := qualifiedNode{"same", ga, a}, qualifiedNode{"same", gb, b}
	if !genericJavaEqual(qa, qb) || genericJavaHash(qa) != genericJavaHash(qb) {
		t.Fatal("qualified identity includes graph pointer or body")
	}
	qb.GraphID = "other"
	if genericJavaEqual(qa, qb) {
		t.Fatal("different graph identity collapsed")
	}
	slot := &candidateSlot{node: a}
	frozen := freezeCandidate(slot)
	slot.node = b
	if !genericJavaEqual(frozen, a) {
		t.Fatal("whole projection retained borrowed slot")
	}
}

func TestMainStringSourceCanceledPrepareAndFreshSource(t *testing.T) {
	for _, warm := range []bool{false, true} {
		for _, at := range []int32{1, 2, 8, 16, 32} {
			t.Run(fmt.Sprintf("warm=%v/at=%d", warm, at), func(t *testing.T) {
				g := candidateGraph(t, "clean")
				base := context.Background()
				if warm {
					if _, ok, err := g.PrepareDistinctStringIndex(base, store.DistinctProjectionOptions{SourceCount: 1, Limit: 1000, SkipPreparedPreference: true}); err != nil || !ok {
						t.Fatal(ok, err)
					}
				}
				parent, cancel := context.WithCancel(base)
				defer cancel()
				ctx := &syncPreparationCancel{Context: parent, cancel: cancel, at: at}
				e := evaluator{ctx: ctx}
				pattern := candidateClause(t, "MATCH (n:CallSite) RETURN n").Patterns[0].Nodes[0]
				atoms := []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: "other"}}
				caught := findIDCaught(func() {
					next := e.mainStringCandidates(Graph{Store: g}, pattern, atoms, 1)
					for {
						_, ok := next(ctx)
						if !ok {
							break
						}
					}
				})
				if ctx.polls.Load() >= at && caught != context.Canceled {
					t.Fatal("cancellation lost", caught, ctx.polls.Load())
				}
				if warm {
					if _, ok, err := g.RetainedProjectionIndex(base); err != nil || !ok {
						t.Fatal("published index rolled back", ok, err)
					}
				}
				e.ctx = base
				next := e.mainStringCandidates(Graph{Store: g}, pattern, atoms, 1)
				ids := []int32{}
				for {
					node, ok := next(base)
					if !ok {
						break
					}
					ids = append(ids, node.ID)
				}
				if !reflect.DeepEqual(ids, []int32{2, 41}) {
					t.Fatal("partial preparation leaked into fresh source", ids)
				}
			})
		}
	}
}

func TestMainStringSourceConcurrentStoreClose(t *testing.T) {
	for iteration := 0; iteration < 12; iteration++ {
		g := candidateGraph(t, "clean")
		ctx := context.Background()
		start := make(chan struct{})
		done := make(chan any, 4)
		pattern := candidateClause(t, "MATCH (n:CallSite) RETURN n").Patterns[0].Nodes[0]
		for i := 0; i < 4; i++ {
			go func() {
				<-start
				done <- findIDCaught(func() {
					e := evaluator{ctx: ctx}
					next := e.mainStringCandidates(Graph{Store: g}, pattern, []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: "other"}}, 1)
					for {
						_, ok := next(ctx)
						if !ok {
							break
						}
					}
				})
			}()
		}
		close(start)
		if err := g.Close(); err != nil {
			t.Fatal(err)
		}
		for i := 0; i < 4; i++ {
			if failure := <-done; failure != nil && failure != store.ErrStoreClosed {
				t.Fatal("untyped close or other failure", failure)
			}
		}
	}
}

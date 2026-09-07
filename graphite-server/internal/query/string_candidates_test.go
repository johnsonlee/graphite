package query

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func candidateClause(t *testing.T, query string) cypher.MatchClause {
	t.Helper()
	q, err := cypher.Parse(query)
	if err != nil {
		t.Fatal(err)
	}
	return q.Branches[0].Clauses[0].(cypher.MatchClause)
}
func candidateGraph(t *testing.T, fixture string) *store.Store {
	t.Helper()
	g, err := store.OpenMode("testdata/candidate-index/"+fixture, "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { g.Close() })
	return g
}

func TestStringCandidateCompilerSafety(t *testing.T) {
	e := evaluator{ctx: context.Background(), indexFirst: true, parameters: map[string]any{"text": "other", "empty": "", "null": nil, "number": float64(3)}}
	for _, c := range []struct {
		query string
		want  bool
	}{
		{"MATCH (arbitrary) WHERE arbitrary.caller_name CONTAINS 'other' RETURN arbitrary", true},
		{"MATCH (n) WHERE toLower(toString(coalesce(n.caller_class,''))) CONTAINS 'other' OR n.callee_name=$text RETURN n", true},
		{"MATCH (n) WHERE n.caller_name STARTS WITH '' RETURN n", true},
		{"MATCH (n) WHERE coalesce(n.caller_name,'') CONTAINS '' RETURN n", false},
		{"MATCH (n) WHERE coalesce(n.caller_name,'')=$empty RETURN n", false},
		{"MATCH (n:CallSite) WHERE coalesce(n.caller_name,'') CONTAINS '' RETURN n", true},
		{"MATCH (n) WHERE n.caller_name CONTAINS $number RETURN n", false},
		{"MATCH (n) WHERE n.caller_name CONTAINS $missing RETURN n", false},
		{"MATCH (n) WHERE n.caller_name CONTAINS $null RETURN n", false},
		{"MATCH (n) WHERE n.caller_name CONTAINS 'x' OR true RETURN n", false},
		{"MATCH (n) WHERE n.caller_name CONTAINS 'x' OR substring('x','bad')='x' RETURN n", false},
		{"MATCH (n) WHERE n.caller_name CONTAINS 'x' AND n.callee_name='other' RETURN n", false},
		{"MATCH (n) WHERE n.caller_name NOT CONTAINS 'x' RETURN n", false},
		{"MATCH (n) WHERE 'other'=n.caller_name RETURN n", false},
		{"MATCH (n) WHERE n.caller_signature CONTAINS 'other' RETURN n", false},
		{"MATCH (n) WHERE toString(n.caller_name,1/0) CONTAINS 'x' RETURN n", false},
		{"MATCH (n) WHERE coalesce(n.caller_name,1/0) CONTAINS 'x' RETURN n", false},
		{"MATCH (n) WHERE toLower(toLower(n.caller_name)) CONTAINS 'x' RETURN n", false},
		{"MATCH (n {id:2}) WHERE n.caller_name='other' RETURN n", false},
		{"OPTIONAL MATCH (n) WHERE n.caller_name='other' RETURN n", false},
		{"MATCH (n:Node) WHERE n.caller_name='other' RETURN n", false},
	} {
		t.Run(c.query, func(t *testing.T) {
			got := e.compileStringCandidates(candidateClause(t, c.query)) != nil
			if got != c.want {
				t.Fatalf("eligibility got %v want %v", got, c.want)
			}
		})
	}
	e.indexFirst = false
	if e.compileStringCandidates(candidateClause(t, "MATCH (n) WHERE n.caller_name='other' RETURN n")) != nil {
		t.Fatal("noninitial match compiled")
	}
}

func TestStringCandidateSourcesAndAvailableEmpty(t *testing.T) {
	g := candidateGraph(t, "clean")
	for _, c := range []struct {
		where string
		ids   []int32
	}{
		{"n.caller_name='other'", []int32{2, 41}},
		{"n.caller_name='other' OR n.callee_name='invoke'", []int32{17, 2, 41, 90}},
		{"toLower(toString(coalesce(n.caller_class,''))) CONTAINS 'other'", []int32{2, 41}},
		{"n.callee_name='no such value'", []int32{}},
	} {
		clause := candidateClause(t, "MATCH (n) WHERE "+c.where+" RETURN id(n)")
		e := evaluator{ctx: context.Background(), indexFirst: true}
		walker := e.indexedNodeWalker(g, clause, &candidateSlot{})
		if walker == nil {
			t.Fatalf("eligible predicate fell back: %s", c.where)
		}
		got := []int32{}
		walker(func(value any) { got = append(got, value.(*candidateSlot).node.ID) })
		if !reflect.DeepEqual(got, c.ids) {
			t.Fatalf("%s got IDs %v want %v", c.where, got, c.ids)
		}
	}
}

func compareCandidateScan(t *testing.T, g *store.Store, query string, parameters map[string]any, cross bool) {
	t.Helper()
	run := func(ctx context.Context) (Result, error) {
		if cross {
			return ExecuteCross(ctx, []Graph{{"b", g}, {"a", g}}, query, parameters, -1)
		}
		return Execute(ctx, g, query, parameters, -1)
	}
	got, err := run(context.Background())
	want, werr := run(context.WithValue(context.Background(), candidateScanOnlyKey{}, true))
	if (err == nil) != (werr == nil) {
		t.Fatalf("%s error got %v want %v", query, err, werr)
	}
	if err != nil {
		if reflect.TypeOf(err) != reflect.TypeOf(werr) || err.Error() != werr.Error() {
			t.Fatalf("%s error got %T %v want %T %v", query, err, err, werr, werr)
		}
		return
	}
	a, _ := json.Marshal(got)
	b, _ := json.Marshal(want)
	if string(a) != string(b) {
		t.Fatalf("%s\nindexed %s\nscan %s", query, a, b)
	}
}
func TestStringCandidateFullExecutionAgainstScan(t *testing.T) {
	g := candidateGraph(t, "clean")
	queries := []string{
		"MATCH (n) WHERE n.caller_name='other' RETURN n,id(n) AS id,properties(n) AS props",
		"MATCH (n) WHERE n.caller_name='other' OR n.callee_name='invoke' RETURN count(n) AS count,collect(id(n)) AS ids",
		"MATCH (n) WHERE toLower(toString(coalesce(n.caller_class,''))) CONTAINS 'other' OR toLower(toString(coalesce(n.callee_name,''))) CONTAINS 'other' RETURN DISTINCT n.caller_class AS caller ORDER BY caller LIMIT 1",
		"MATCH (n) WHERE n.caller_class CONTAINS $term OR n.caller_name STARTS WITH $term OR n.callee_class ENDS WITH $term OR n.callee_name=$term RETURN id(n) AS id ORDER BY id",
		"MATCH (n:CallSiteNode) WHERE coalesce(n.caller_name,'') CONTAINS '' RETURN id(n) AS id",
		"MATCH (n) WHERE coalesce(n.caller_name,'') CONTAINS '' RETURN id(n) AS id",
		"MATCH (n) WHERE n.caller_name='absent' RETURN id(n) AS id",
		"MATCH (n) WHERE n.caller_name='other' RETURN substring(n.callee_name,'bad') AS value",
		"MATCH (n) WHERE n.caller_name='other' OR substring('x','bad')='x' RETURN n",
		"MATCH (n) WHERE coalesce(n.caller_name,1/0)='other' RETURN n",
		"MATCH (n {id:1,caller_name:1/0}) WHERE n.caller_name='other' RETURN n",
		"UNWIND ['other','caller'] AS word MATCH (n) WHERE n.caller_name=word RETURN id(n) AS id",
		"WITH {caller_name:'other'} AS n MATCH (n) WHERE n.caller_name='other' RETURN n",
		"OPTIONAL MATCH (n) WHERE n.caller_name='absent' RETURN n",
		"MATCH (n) WHERE n.caller_name='other' WITH n OPTIONAL MATCH (n) WHERE n.caller_name='absent' RETURN id(n) AS id",
		"MATCH (n) WHERE n.caller_name='other' RETURN id(n) AS id UNION MATCH (n) WHERE n.callee_name='invoke' RETURN id(n) AS id",
	}
	for _, q := range queries {
		for _, cross := range []bool{false, true} {
			compareCandidateScan(t, g, q, map[string]any{"term": "other"}, cross)
		}
	}
	for _, value := range []any{nil, 12, true, []any{"other"}, "", "other", "\xed\xa0\x80"} {
		compareCandidateScan(t, g, "MATCH (n) WHERE n.caller_name CONTAINS $term RETURN id(n) AS id", map[string]any{"term": value}, true)
	}
	for _, fixture := range []string{"bad-first-unmatched", "bad-last-unmatched", "bad-matched"} {
		bad := candidateGraph(t, fixture)
		for _, q := range queries[:3] {
			compareCandidateScan(t, bad, q, nil, true)
		}
		e := evaluator{ctx: context.Background(), indexFirst: true}
		if e.indexedNodeWalker(bad, candidateClause(t, queries[0]), &candidateSlot{}) != nil {
			t.Fatalf("corrupt core %s was certified", fixture)
		}
	}
}

func copyCandidateFixture(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	entries, err := os.ReadDir("testdata/candidate-index/clean")
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		b, err := os.ReadFile(filepath.Join("testdata/candidate-index/clean", entry.Name()))
		if err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(filepath.Join(dir, entry.Name()), b, 0600); err != nil {
			t.Fatal(err)
		}
	}
	return dir
}
func TestStringCandidateUnavailableAndEagerDirectoryLifetime(t *testing.T) {
	for _, mode := range []string{"MAPPED", "EAGER"} {
		dir := copyCandidateFixture(t)
		g, err := store.OpenMode(dir, mode)
		if err != nil {
			t.Fatal(err)
		}
		defer g.Close()
		if mode == "EAGER" {
			if err = os.RemoveAll(dir); err != nil {
				t.Fatal(err)
			}
		} else {
			if err = os.Remove(filepath.Join(dir, "graph.callsite-string-index")); err != nil {
				t.Fatal(err)
			}
		}
		compareCandidateScan(t, g, "MATCH (n) WHERE n.caller_name='other' RETURN id(n) AS id", nil, false)
	}
}
func TestStringCandidateCancellationAndClose(t *testing.T) {
	g := candidateGraph(t, "clean")
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := Execute(ctx, g, "MATCH (n) WHERE n.caller_name='other' RETURN id(n) AS id", nil, -1)
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("cancellation got %v", err)
	}
	clause := candidateClause(t, "MATCH (n) WHERE n.caller_name='other' RETURN id(n) AS id")
	walker := (evaluator{ctx: context.Background(), indexFirst: true}).indexedNodeWalker(g, clause, &candidateSlot{})
	if walker == nil {
		t.Fatal("expected indexed walker")
	}
	g.Close()
	defer func() {
		err, ok := recover().(error)
		if !ok || !errors.Is(err, store.ErrStoreClosed) {
			t.Fatalf("closed candidate got %v", err)
		}
	}()
	walker(func(any) { t.Fatal("closed Store emitted a node") })
}

// Both principal raw and wrapped four-field shapes must select the provider.
func TestFourFieldRawAndWrappedPlansAreEligible(t *testing.T) {
	g := candidateGraph(t, "clean")
	for _, wrapped := range []bool{false, true} {
		arms := []string{}
		for _, p := range []string{"caller_class", "caller_name", "callee_class", "callee_name"} {
			left := "n." + p
			if wrapped {
				left = "toLower(toString(coalesce(" + left + ",'')))"
			}
			arms = append(arms, left+" CONTAINS 'other'")
		}
		q := "MATCH (n) WHERE " + strings.Join(arms, " OR ") + " RETURN id(n) AS id"
		e := evaluator{ctx: context.Background(), indexFirst: true}
		if e.indexedNodeWalker(g, candidateClause(t, q), &candidateSlot{}) == nil {
			t.Fatalf("principal shape fell back: %s", q)
		}
		compareCandidateScan(t, g, q, nil, true)
	}
}

func TestStringCandidateMainOracleAndForcedScan(t *testing.T) {
	var specs []struct {
		Name   string
		Params map[string]any
	}
	raw, err := os.ReadFile("testdata/candidate-index/plan-cases.json")
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(raw, &specs); err != nil {
		t.Fatal(err)
	}
	parameters := map[string]map[string]any{}
	for _, s := range specs {
		parameters[s.Name] = s.Params
	}
	for _, fixture := range []string{"clean", "mixed", "annotation"} {
		t.Run(fixture, func(t *testing.T) {
			g := candidateGraph(t, fixture)
			raw, err := os.ReadFile("testdata/candidate-index/" + fixture + "-plan-main.json")
			if err != nil {
				t.Fatal(err)
			}
			var cases []struct {
				Name, Query, Error, Message string
				Cross                       bool
				Columns                     []string
				Rows                        []map[string]any
			}
			if err = json.Unmarshal(raw, &cases); err != nil {
				t.Fatal(err)
			}
			for _, c := range cases {
				t.Run(c.Name+map[bool]string{true: "/cross", false: "/scoped"}[c.Cross], func(t *testing.T) {
					compareCandidateScan(t, g, c.Query, parameters[c.Name], c.Cross)
					var result Result
					var err error
					if c.Cross {
						result, err = ExecuteCross(context.Background(), []Graph{{"a", g}, {"b", g}}, c.Query, parameters[c.Name], -1)
					} else {
						result, err = Execute(context.Background(), g, c.Query, parameters[c.Name], -1)
					}
					if c.Error != "" {
						var failure *Error
						if !errors.As(err, &failure) || failure.Class != c.Error || failure.Message != c.Message {
							t.Fatalf("got %v, want %s: %s", err, c.Error, c.Message)
						}
						return
					}
					if err != nil {
						t.Fatal(err)
					}
					data, err := json.Marshal(result.Rows)
					if err != nil {
						t.Fatal(err)
					}
					var rows []map[string]any
					if err = json.Unmarshal(data, &rows); err != nil {
						t.Fatal(err)
					}
					if !reflect.DeepEqual(result.Columns, c.Columns) || !reflect.DeepEqual(rows, c.Rows) {
						t.Fatalf("%s\ncolumns %v want %v\nrows %s\nwant %s", c.Query, result.Columns, c.Columns, data, mustJSON(c.Rows))
					}
				})
			}
		})
	}
}
func TestStringCandidateInferenceFallbacks(t *testing.T) {
	for _, fixture := range []string{"mixed", "annotation"} {
		g := candidateGraph(t, fixture)
		e := evaluator{ctx: context.Background(), indexFirst: true}
		for _, q := range []string{"MATCH (n) WHERE coalesce(n.caller_name,'') CONTAINS '' RETURN id(n)", "MATCH (n) WHERE n.caller_class CONTAINS 'other' RETURN id(n)"} {
			walker := e.indexedNodeWalker(g, candidateClause(t, q), &candidateSlot{})
			want := fixture == "mixed" && !strings.Contains(q, "coalesce")
			if (walker != nil) != want {
				t.Fatalf("%s: %s provider got %v want %v", fixture, q, walker != nil, want)
			}
		}
	}
}

func TestStringCandidateRejectsUnprovenPropertyAssociation(t *testing.T) {
	dir := copyCandidateFixture(t)
	index, err := os.ReadFile(filepath.Join(dir, "graph.nodeindex"))
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(dir, "graph.nodedata"))
	if err != nil {
		t.Fatal(err)
	}
	count := int(binary.BigEndian.Uint32(index[4:8]))
	changed := false
	for i := 0; i < count; i++ {
		record := index[8+13*i : 8+13*(i+1)]
		if int32(binary.BigEndian.Uint32(record[:4])) != 17 {
			continue
		}
		offset := int(binary.BigEndian.Uint64(record[5:]))
		args := int(binary.BigEndian.Uint32(data[offset+13 : offset+17]))
		callee := offset + 5 + (4+args)*4
		copy(data[offset+9:offset+13], data[callee+4:callee+8])
		changed = true
	}
	if !changed {
		t.Fatal("fixture is missing node 17")
	}
	if err = os.WriteFile(filepath.Join(dir, "graph.nodedata"), data, 0600); err != nil {
		t.Fatal(err)
	}
	g, err := store.OpenMode(dir, "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	defer g.Close()
	view, available, err := g.TryCallSiteStringIndex(context.Background())
	if err != nil || !available {
		t.Fatalf("reader should accept its trusted identity/CRC: %v/%v", available, err)
	}
	if valid, err := g.CertifyCallSiteCandidates(context.Background(), view); err != nil || valid {
		t.Fatalf("association proof got %v/%v", valid, err)
	}
	query := "MATCH (n) WHERE n.caller_name='invoke' RETURN id(n) AS id"
	compareCandidateScan(t, g, query, nil, false)
	result, err := Execute(context.Background(), g, query, nil, -1)
	if err != nil || len(result.Rows) != 1 || result.Rows[0]["id"] != int32(17) {
		t.Fatalf("fallback lost mutated property's node: %#v/%v", result, err)
	}
}

func TestStringCandidateCancellationAtEveryPreparationCheck(t *testing.T) {
	g := candidateGraph(t, "clean")
	clause := candidateClause(t, "MATCH (n) WHERE toLower(n.caller_class) CONTAINS 'other' OR n.callee_name='invoke' RETURN id(n)")
	// First establish the cold certificate. The enumerated cancellation points
	// then cover compilation, cached certification, dictionary transform/match,
	// postings, offset reads, and union sorting rather than parser cancellation.
	warm := evaluator{ctx: context.WithValue(context.Background(), candidateDirectoryOnlyKey{}, true), indexFirst: true}
	if warm.indexedNodeWalker(g, clause, &candidateSlot{}) == nil {
		t.Fatal("warm preparation unavailable")
	}
	counter := newTraversalCancelContext(t, context.WithValue(context.Background(), candidateDirectoryOnlyKey{}, true), int(^uint(0)>>1))
	e := evaluator{ctx: counter, indexFirst: true}
	if e.indexedNodeWalker(g, clause, &candidateSlot{}) == nil {
		t.Fatal("counted preparation unavailable")
	}
	canceledChecks := 0
	for at := 1; at <= counter.checks; at++ {
		func() {
			ctx := newTraversalCancelContext(t, context.WithValue(context.Background(), candidateDirectoryOnlyKey{}, true), at)
			e := evaluator{ctx: ctx, indexFirst: true}
			defer func() {
				caught := recover()
				// Map encounter order changes the number of sort comparisons.
				// A threshold from the counted run may never be reached here.
				// Inspect the counter without calling Err (which advances it).
				if ctx.checks < at && caught == nil {
					return
				}
				failure, ok := caught.(error)
				if !ok || !errors.Is(failure, context.Canceled) {
					t.Fatalf("check %d got %v", at, failure)
				}
				canceledChecks++
			}()
			if e.indexedNodeWalker(g, clause, &candidateSlot{}) == nil {
				t.Fatal("uncanceled preparation unavailable")
			}
		}()
	}
	if canceledChecks < 100 {
		t.Fatalf("insufficient cancellation coverage: %d checks", canceledChecks)
	}
}

func TestStringCandidateOtherKindNullPremise(t *testing.T) {
	g, err := store.OpenMode("../store/testdata/jvm-v3", "EAGER")
	if err != nil {
		t.Fatal(err)
	}
	defer g.Close()
	for _, id := range g.NodeIDs() {
		n, err := g.Node(id)
		if err != nil {
			t.Fatal(err)
		}
		if n.Kind == "AnnotationNode" {
			continue
		}
		for _, key := range []string{"caller_class", "caller_name", "callee_class", "callee_name"} {
			v := NodeProperty(n, key)
			if n.Kind == "CallSiteNode" {
				if _, ok := v.(string); !ok {
					t.Fatalf("CallSite %s not a string: %#v", key, v)
				}
			} else if v != nil {
				t.Fatalf("inference premise false: %s.%s=%#v", n.Kind, key, v)
			}
		}
	}
}

func TestStringCandidateRejectsNoncanonicalIndexEncounterOrder(t *testing.T) {
	dir := copyCandidateFixture(t)
	path := filepath.Join(dir, "graph.nodeindex")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	first := append([]byte(nil), data[8:21]...)
	copy(data[8:21], data[21:34])
	copy(data[21:34], first)
	if err = os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	g, err := store.OpenMode(dir, "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	defer g.Close()
	view, ok, err := g.TryCallSiteStringIndex(context.Background())
	if err != nil || !ok {
		t.Fatalf("reader %v/%v", ok, err)
	}
	if certified, err := g.CertifyCallSiteCandidates(context.Background(), view); err != nil || certified {
		t.Fatalf("noncanonical encounter order certified: %v/%v", certified, err)
	}
	compareCandidateScan(t, g, "MATCH (n) WHERE n.callee_name CONTAINS '' RETURN id(n) AS id", nil, true)
}

func TestStringCandidateCorruptBaselinePreservation(t *testing.T) {
	raw, err := os.ReadFile("testdata/candidate-index/corrupt-native.json")
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Fixture, Name, Query, Error, Message string
		Cross                                bool
		Columns                              []string
		Rows                                 []map[string]any
	}
	if err = json.Unmarshal(raw, &cases); err != nil {
		t.Fatal(err)
	}
	// This feature intentionally repairs the indexed DISTINCT projection entries
	// in the historical native baseline; preserve every declined entry unchanged.
	mainCases := append(cases[:0:0], cases...)
	mainCases = mainCases[:0]
	for _, fixture := range []string{"clean", "bad-first-unmatched", "bad-last-unmatched", "bad-matched"} {
		mainBytes, err := os.ReadFile("testdata/candidate-index/" + fixture + "-main.json")
		if err != nil {
			t.Fatal(err)
		}
		loaded := append(cases[:0:0], cases...)
		if err = json.Unmarshal(mainBytes, &loaded); err != nil {
			t.Fatal(err)
		}
		for i := range loaded {
			loaded[i].Fixture = fixture
		}
		mainCases = append(mainCases, loaded...)
	}
	for i, c := range cases {
		ast, parseErr := cypher.Parse(c.Query)
		if parseErr != nil {
			t.Fatal(parseErr)
		}
		e := evaluator{ctx: context.Background(), cross: c.Cross}
		if e.compileIndexedDistinct(ast.Branches[0]) == nil {
			continue
		}
		for _, m := range mainCases {
			if m.Fixture == c.Fixture && m.Name == c.Name && m.Cross == c.Cross {
				cases[i] = m
				break
			}
		}
	}
	for _, fixture := range []string{"clean", "bad-first-unmatched", "bad-last-unmatched", "bad-matched"} {
		g := candidateGraph(t, fixture)
		for _, c := range cases {
			if c.Fixture != fixture {
				continue
			}
			t.Run(fixture+"/"+c.Name+map[bool]string{true: "/cross", false: "/scoped"}[c.Cross], func(t *testing.T) {
				compareCandidateScan(t, g, c.Query, nil, c.Cross)
				var result Result
				var err error
				if c.Cross {
					result, err = ExecuteCross(context.Background(), []Graph{{"a", g}, {"b", g}}, c.Query, nil, -1)
				} else {
					result, err = Execute(context.Background(), g, c.Query, nil, -1)
				}
				if c.Error != "" {
					var failure *Error
					if !errors.As(err, &failure) || failure.Class != c.Error || failure.Message != c.Message {
						t.Fatalf("got %v want %s: %s", err, c.Error, c.Message)
					}
					return
				}
				if err != nil {
					t.Fatal(err)
				}
				data, err := json.Marshal(result.Rows)
				if err != nil {
					t.Fatal(err)
				}
				var rows []map[string]any
				if err = json.Unmarshal(data, &rows); err != nil {
					t.Fatal(err)
				}
				if !reflect.DeepEqual(result.Columns, c.Columns) || !reflect.DeepEqual(rows, c.Rows) {
					t.Fatalf("columns %v want %v; rows %s want %s", result.Columns, c.Columns, data, mustJSON(c.Rows))
				}
			})
		}
	}
}

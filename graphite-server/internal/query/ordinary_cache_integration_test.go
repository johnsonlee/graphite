package query

import (
	"context"
	"reflect"
	"strings"
	"testing"
)

func TestOrdinaryIntegrationJavaUTF16CacheIdentity(t *testing.T) {
	g := candidateGraph(t, "clean")
	ctx := context.Background()
	warm := "MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN DISTINCT n.caller_name AS x LIMIT 2"
	if _, err := Execute(ctx, g, warm, nil, -1); err != nil {
		t.Fatal(err)
	}
	q := "MATCH (n) WHERE toLower(n.caller_name) CONTAINS $term RETURN n.caller_name AS x LIMIT 2"
	index, ok, err := g.RetainedProjectionIndex(ctx)
	if err != nil || !ok {
		t.Fatalf("index %v %v", ok, err)
	}
	var oracle []struct {
		UTF16         []uint16
		Columns       []string
		Rows          []map[string]any
		RetainedBytes int64
	}
	readDistinctJSON(t, "testdata/ordinary-integration/cache-unicode-main.json", &oracle)
	terms := []string{"😀x", "\xed\xa0\xbd\xed\xb8\x80x", "\xed\xa0\xbdxy", "\xed\xb8\x80xy", "😀x", "😀x", "😀x", "😀x"}
	if len(oracle) != len(terms) {
		t.Fatalf("oracle count=%d", len(oracle))
	}
	for at, term := range terms {
		query := q
		if at == 5 {
			query = strings.Replace(q, "AS x", "AS `😀`", 1)
		}
		if at == 6 {
			query = strings.Replace(q, "n.caller_name AS x", "n.caller_name AS a,n.caller_name AS b", 1)
		}
		if at == 7 {
			query = strings.Replace(q, "n.caller_name AS x", "n.caller_name AS b,n.caller_name AS a", 1)
		}
		if !reflect.DeepEqual(javaUTF16(term), oracle[at].UTF16) {
			t.Fatalf("step%d input units=%v", at, javaUTF16(term))
		}
		r, err := Execute(ctx, g, query, map[string]any{"term": term}, -1)
		if err != nil {
			t.Fatal(err)
		}
		if !reflect.DeepEqual(r.Columns, oracle[at].Columns) || !reflect.DeepEqual(r.Rows, oracle[at].Rows) {
			t.Fatalf("step%d rows=%#v want=%#v", at, r, oracle[at])
		}
		size, err := index.ProjectionPlannerBytes(ctx)
		if err != nil {
			t.Fatal(err)
		}
		t.Logf("step%d bytes%d", at, size)
		if size != oracle[at].RetainedBytes {
			t.Fatalf("step%d Java cache bytes=%d native=%d", at, oracle[at].RetainedBytes, size)
		}
	}
}

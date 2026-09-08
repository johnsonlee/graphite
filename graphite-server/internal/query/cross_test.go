package query

import (
	"context"
	"encoding/binary"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// These are correctness fixtures only. The original JVM-produced store is
// copied so two separate sources have the same local IDs and different values.
// Expected namespace/provenance rules follow origin/main 4e328b0's
// CrossGraphValues.kt, QueryPipeline.kt, and CrossGraphCypherExecutor tests.
func crossFixtureSources(t *testing.T) []Graph {
	t.Helper()
	var graphs []Graph
	for _, source := range []struct {
		id    string
		value int32
	}{{"orders", 10}, {"billing", 20}} {
		dir := t.TempDir()
		entries, err := os.ReadDir("../store/testdata/jvm-v3")
		if err != nil {
			t.Fatal(err)
		}
		for _, entry := range entries {
			b, err := os.ReadFile(filepath.Join("../store/testdata/jvm-v3", entry.Name()))
			if err != nil {
				t.Fatal(err)
			}
			if entry.Name() == "graph.nodedata" {
				if len(b) < 17 || b[12] != 0 {
					t.Fatal("fixture first record is no longer an IntConstant")
				}
				binary.BigEndian.PutUint32(b[13:17], uint32(source.value))
			}
			if err := os.WriteFile(filepath.Join(dir, entry.Name()), b, 0600); err != nil {
				t.Fatal(err)
			}
		}
		graph, err := store.Open(dir)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { graph.Close() })
		graphs = append(graphs, Graph{ID: source.id, Store: graph})
	}
	return graphs
}
func executeCrossFixture(t *testing.T, graphs []Graph, source string, limit int) Result {
	t.Helper()
	r, err := ExecuteCross(context.Background(), graphs, source, nil, limit)
	if err != nil {
		t.Fatalf("query %s: %v", source, err)
	}
	return r
}
func graphMetadata(ids ...string) map[string]any {
	return map[string]any{"graphIds": append([]string{}, ids...)}
}

func TestCrossQualifiedLocalIDsAndMaterialization(t *testing.T) {
	graphs := crossFixtureSources(t)
	r := executeCrossFixture(t, graphs, "MATCH (n:IntConstant) RETURN n, id(n) AS localId, elementId(n) AS elementId, graphId(n) AS graphId ORDER BY graphId", -1)
	var rows []map[string]any
	for _, g := range []struct {
		id    string
		value int32
	}{{"billing", 20}, {"orders", 10}} {
		rows = append(rows, map[string]any{"n": map[string]any{"id": int32(0), "type": "IntConstant", "value": g.value, "graphId": g.id, "elementId": g.id + ":0", "qualifiedId": g.id + ":0"}, "localId": int32(0), "elementId": g.id + ":0", "graphId": g.id, "$metadata": graphMetadata(g.id)})
	}
	assertResult(t, r, []string{"n", "localId", "elementId", "graphId"}, rows)
}
func TestCrossQualifiedEqualityDoesNotCollapseLocalIDs(t *testing.T) {
	graphs := crossFixtureSources(t)
	r := executeCrossFixture(t, graphs, "MATCH (a:IntConstant), (b:IntConstant) WHERE id(a) = id(b) RETURN graphId(a) AS leftGraph, graphId(b) AS rightGraph, a = b AS same ORDER BY leftGraph, rightGraph", -1)
	rows := []map[string]any{
		{"leftGraph": "billing", "rightGraph": "billing", "same": true, "$metadata": graphMetadata("billing")},
		{"leftGraph": "billing", "rightGraph": "orders", "same": false, "$metadata": graphMetadata("billing", "orders")},
		{"leftGraph": "orders", "rightGraph": "billing", "same": false, "$metadata": graphMetadata("billing", "orders")},
		{"leftGraph": "orders", "rightGraph": "orders", "same": true, "$metadata": graphMetadata("orders")},
	}
	assertResult(t, r, []string{"leftGraph", "rightGraph", "same"}, rows)
	r = executeCrossFixture(t, graphs, "MATCH (n:IntConstant) RETURN count(DISTINCT n) AS qualified, count(DISTINCT id(n)) AS local", -1)
	assertResult(t, r, []string{"qualified", "local"}, []map[string]any{{"qualified": int64(2), "local": int64(1), "$metadata": graphMetadata("billing", "orders")}})
}
func TestCrossCartesianPatternsJoinSources(t *testing.T) {
	r := executeCrossFixture(t, crossFixtureSources(t), "MATCH (a:StringConstant), (b:StringConstant) WHERE a.value = b.value AND a.graphId < b.graphId RETURN graphId(a) AS leftGraph, graphId(b) AS rightGraph, a.value AS value", -1)
	assertResult(t, r, []string{"leftGraph", "rightGraph", "value"}, []map[string]any{{"leftGraph": "billing", "rightGraph": "orders", "value": "hello\x00世界😀", "$metadata": graphMetadata("billing", "orders")}})
}
func TestCrossDistinctMergesAllContributingGraphIDs(t *testing.T) {
	graphs := crossFixtureSources(t)
	for _, source := range []string{"MATCH (n:StringConstant) RETURN DISTINCT n.value AS value LIMIT 1", "MATCH (n:StringConstant) WITH DISTINCT n.value AS value RETURN value LIMIT 1"} {
		assertResult(t, executeCrossFixture(t, graphs, source, 1), []string{"value"}, []map[string]any{{"value": "hello\x00世界😀", "$metadata": graphMetadata("billing", "orders")}})
	}
	r := executeCrossFixture(t, graphs, "MATCH (n:StringConstant) WITH DISTINCT n.value AS value RETURN count(*) AS total, collect(value) AS values", -1)
	assertResult(t, r, []string{"total", "values"}, []map[string]any{{"total": int64(1), "values": []any{"hello\x00世界😀"}, "$metadata": graphMetadata("billing", "orders")}})
}
func TestCrossAggregatesExecuteOverWholeSourceUnion(t *testing.T) {
	graphs := crossFixtureSources(t)
	assertResult(t, executeCrossFixture(t, graphs, "MATCH (n) RETURN count(*) AS total", -1), []string{"total"}, []map[string]any{{"total": int64(32), "$metadata": graphMetadata("billing", "orders")}})
	assertResult(t, executeCrossFixture(t, graphs, "MATCH (n:IntConstant) RETURN sum(n.value) AS total, avg(n.value) AS average, count(n) AS count", -1), []string{"total", "average", "count"}, []map[string]any{{"total": 30.0, "average": 15.0, "count": int64(2), "$metadata": graphMetadata("billing", "orders")}})
	assertResult(t, executeCrossFixture(t, graphs, "MATCH (n) RETURN n.graphId AS source, count(*) AS total ORDER BY source", -1), []string{"source", "total"}, []map[string]any{{"source": "billing", "total": int64(16), "$metadata": graphMetadata("billing")}, {"source": "orders", "total": int64(16), "$metadata": graphMetadata("orders")}})
}
func TestCrossMethodsRetainNamespacesAndProvenance(t *testing.T) {
	graphs := crossFixtureSources(t)
	r := executeCrossFixture(t, graphs, "MATCH (m:Method) RETURN count(DISTINCT m) AS methods, count(DISTINCT m.signature) AS signatures", -1)
	assertResult(t, r, []string{"methods", "signatures"}, []map[string]any{{"methods": int64(2), "signatures": int64(1), "$metadata": graphMetadata("billing", "orders")}})
	r = executeCrossFixture(t, graphs, "MATCH (m:Method) RETURN m, elementId(m) AS elementId ORDER BY m.graphId", -1)
	var rows []map[string]any
	for _, id := range []string{"billing", "orders"} {
		rows = append(rows, map[string]any{"m": map[string]any{"signature": "Example.run(int)", "class": "Example", "name": "run", "parameter_types": []any{"int"}, "return_type": "void", "graphId": id}, "elementId": id + ":Method:Example.run(int)", "$metadata": graphMetadata(id)})
	}
	assertResult(t, r, []string{"m", "elementId"}, rows)
}
func TestCrossWithUnwindPreservesRowAndValueProvenance(t *testing.T) {
	graphs := crossFixtureSources(t)
	r := executeCrossFixture(t, graphs, "MATCH (n:IntConstant) WITH collect(n) AS nodes UNWIND nodes AS n RETURN graphId(n) AS graphId, n.value AS value ORDER BY graphId", -1)
	assertResult(t, r, []string{"graphId", "value"}, []map[string]any{{"graphId": "billing", "value": int32(20), "$metadata": graphMetadata("billing", "orders")}, {"graphId": "orders", "value": int32(10), "$metadata": graphMetadata("billing", "orders")}})
	r = executeCrossFixture(t, graphs, "MATCH (n:IntConstant {graphId:'orders'}) WITH n MATCH (m:IntConstant {graphId:'billing'}) RETURN n.value AS leftValue, m.value AS rightValue", -1)
	assertResult(t, r, []string{"leftValue", "rightValue"}, []map[string]any{{"leftValue": int32(10), "rightValue": int32(20), "$metadata": graphMetadata("billing", "orders")}})
}
func TestCrossUnionMergesProvenanceAfterGlobalLimit(t *testing.T) {
	graphs := crossFixtureSources(t)
	left := "MATCH (n:StringConstant {graphId:'orders'}) RETURN n.value AS value"
	right := "MATCH (n:StringConstant {graphId:'billing'}) RETURN n.value AS value"
	assertResult(t, executeCrossFixture(t, graphs, left+" UNION "+right, 1), []string{"value"}, []map[string]any{{"value": "hello\x00世界😀", "$metadata": graphMetadata("billing", "orders")}})
	assertResult(t, executeCrossFixture(t, graphs, left+" UNION ALL "+right, -1), []string{"value"}, []map[string]any{{"value": "hello\x00世界😀", "$metadata": graphMetadata("orders")}, {"value": "hello\x00世界😀", "$metadata": graphMetadata("billing")}})
}
func TestCrossEmptyCatalogExplicitMetadata(t *testing.T) {
	assertResult(t, executeCrossFixture(t, nil, "RETURN 7 AS value", -1), []string{"value"}, []map[string]any{{"value": int32(7), "$metadata": graphMetadata()}})
	assertResult(t, executeCrossFixture(t, nil, "MATCH (n) RETURN count(*) AS total", -1), []string{"total"}, []map[string]any{{"total": int64(0), "$metadata": graphMetadata()}})
	assertResult(t, executeCrossFixture(t, nil, "OPTIONAL MATCH (n) RETURN n", -1), []string{"n"}, []map[string]any{{"n": nil, "$metadata": graphMetadata()}})
	assertResult(t, executeCrossFixture(t, nil, "MATCH (n) RETURN n", -1), []string{"n"}, []map[string]any{})
}
func TestCrossRejectsAmbiguousSourceNamespaces(t *testing.T) {
	graphs := crossFixtureSources(t)
	for _, invalid := range [][]Graph{{{ID: "orders"}}, {{ID: "orders", Store: graphs[0].Store}, {ID: "orders", Store: graphs[1].Store}}, {{ID: "", Store: graphs[0].Store}, {ID: "", Store: graphs[1].Store}}} {
		if _, err := ExecuteCross(context.Background(), invalid, "RETURN 1", nil, -1); err == nil {
			t.Fatalf("accepted invalid sources %#v", invalid)
		}
	}
}

// QueryPipeline.compareNodes orders qualified values by graph then local ID.
func TestCrossOrderingQualifiedNodeValues(t *testing.T) {
	r := executeCrossFixture(t, crossFixtureSources(t), "MATCH (n:IntConstant) RETURN graphId(n) AS graphId, n.value AS value ORDER BY n", -1)
	assertResult(t, r, []string{"graphId", "value"}, []map[string]any{{"graphId": "billing", "value": int32(20), "$metadata": graphMetadata("billing")}, {"graphId": "orders", "value": int32(10), "$metadata": graphMetadata("orders")}})
}

// main deliberately distinguishes ORDER BY's graph/numeric identity ordering
// from relational predicates, which compare qualified elementId strings.
func TestCrossQualifiedNodePredicateOrdering(t *testing.T) {
	graphs := crossFixtureSources(t)
	r := executeCrossFixture(t, graphs, "MATCH (a:StringConstant {graphId:'orders'}), (b:BooleanConstant {graphId:'orders'}) RETURN a < b AS less, a > b AS greater", -1)
	assertResult(t, r, []string{"less", "greater"}, []map[string]any{{"less": false, "greater": true, "$metadata": graphMetadata("orders")}})
	r = executeCrossFixture(t, graphs, "MATCH (n {graphId:'orders'}) WHERE id(n) IN [2,10] RETURN id(n) AS id ORDER BY n", -1)
	assertResult(t, r, []string{"id"}, []map[string]any{{"id": int32(2), "$metadata": graphMetadata("orders")}, {"id": int32(10), "$metadata": graphMetadata("orders")}})
}

// withExplicitMetadata adds a missing result metadata key. It does not replace
// a user-projected key when no source has contributed provenance.
func TestCrossExplicitMetadataProjectionOnEmptyCatalog(t *testing.T) {
	r := executeCrossFixture(t, nil, "RETURN 7 AS `$metadata`", -1)
	assertResult(t, r, []string{"$metadata"}, []map[string]any{{"$metadata": int32(7)}})
}

func TestAnonymousMatchDoesNotInventProvenance(t *testing.T) {
	graph, err := store.Open("../store/testdata/jvm-v3")
	if err != nil {
		t.Fatal(err)
	}
	defer graph.Close()
	for _, source := range []string{"MATCH (:IntConstant) RETURN DISTINCT 1 AS one LIMIT 1", "MATCH (:IntConstant) RETURN 1 AS one LIMIT 1"} {
		result, err := ExecuteCross(context.Background(), []Graph{{ID: "a", Store: graph}, {ID: "b", Store: graph}}, source, nil, 10)
		if err != nil {
			t.Fatal(err)
		}
		if len(result.Rows) != 1 {
			t.Fatalf("rows: %#v", result.Rows)
		}
		ids := result.Rows[0]["$metadata"].(map[string]any)["graphIds"]
		if !reflect.DeepEqual(ids, []string{}) {
			t.Fatalf("anonymous provenance: %#v", ids)
		}
	}
}

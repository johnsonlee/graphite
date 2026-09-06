package query

import "testing"

func TestStreamingScanKeepsAcceptedRowsAndProvenanceIndependent(t *testing.T) {
	graphs := crossFixtureSources(t)
	r := executeCrossFixture(t, graphs, "UNWIND [10,20,30] AS expected OPTIONAL MATCH (n:IntConstant) WHERE n.value = expected RETURN expected, n.value AS value, graphId(n) AS graph ORDER BY expected", -1)
	assertResult(t, r, []string{"expected", "value", "graph"}, []map[string]any{
		{"expected": int32(10), "value": int32(10), "graph": "orders", "$metadata": graphMetadata("orders")},
		{"expected": int32(20), "value": int32(20), "graph": "billing", "$metadata": graphMetadata("billing")},
		{"expected": int32(30), "value": nil, "graph": nil, "$metadata": graphMetadata()},
	})
}

func TestStreamingScanPreservesPriorBindingsAndGlobalOrder(t *testing.T) {
	graphs := crossFixtureSources(t)
	r := executeCrossFixture(t, graphs, "MATCH (n:IntConstant) WHERE n.value > 0 MATCH (n:IntConstant) WHERE n.value > 15 RETURN n.value AS value, graphId(n) AS graph ORDER BY value DESC", 1)
	assertResult(t, r, []string{"value", "graph"}, []map[string]any{{"value": int32(20), "graph": "billing", "$metadata": graphMetadata("billing")}})
	r = executeCrossFixture(t, graphs, "MATCH (n:IntConstant) WHERE n.value > 0 RETURN n.value AS value ORDER BY value DESC", 1)
	assertResult(t, r, []string{"value"}, []map[string]any{{"value": int32(20), "$metadata": graphMetadata("billing")}})
	r = executeCrossFixture(t, graphs, "MATCH (:IntConstant) WHERE true RETURN DISTINCT 1 AS value", -1)
	assertResult(t, r, []string{"value"}, []map[string]any{{"value": int32(1), "$metadata": graphMetadata()}})
}

func TestStreamingScanRetainsObservableBindingOrder(t *testing.T) {
	graphs := crossFixtureSources(t)
	r := executeCrossFixture(t, graphs, "UNWIND [10,20] AS z MATCH (a:IntConstant) WHERE a.value = z WITH z, a.value AS a RETURN *", -1)
	assertResult(t, r, []string{"z", "a"}, []map[string]any{
		{"z": int32(10), "a": int32(10), "$metadata": graphMetadata("orders")},
		{"z": int32(20), "a": int32(20), "$metadata": graphMetadata("billing")},
	})
}

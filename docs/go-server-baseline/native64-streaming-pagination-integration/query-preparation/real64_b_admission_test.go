package query

import (
	"context"
	"encoding/json"
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"os"
	"testing"
)

// AST-only admission: no Store open, fixture access, query execution or strategy inference.
func TestReal64BDraftAdmission(t *testing.T) {
	var cases []struct {
		Name string `json:"name"`
		Body struct {
			Query string `json:"query"`
		} `json:"body"`
	}
	data, err := os.ReadFile("/tmp/graphite-real64-b-query-preparation/draft-cases.json")
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(data, &cases); err != nil {
		t.Fatal(err)
	}
	e := evaluator{ctx: context.Background(), cross: true}
	output := []map[string]any{}
	for _, c := range cases {
		parsed, err := cypher.Parse(c.Body.Query)
		if err != nil {
			t.Fatal(c.Name, err)
		}
		b := parsed.Branches[0]
		_, empty := filteredLiteralEmpty(b)
		ordinary := e.compileOrdinaryProjection(b) != nil
		raw := e.compileIndexedDistinct(b) != nil
		generic := !hasStreamingPagination(b) && e.compileGenericDistinct(b) != nil
		cde := e.compileLazyFiltered(b) != nil
		p := e.compileStreamingPagination(b)
		if empty || ordinary || raw || generic || cde || p == nil {
			t.Fatalf("%s earlier=%v/%v/%v/%v/%v B=%v", c.Name, empty, ordinary, raw, generic, cde, p != nil)
		}
		atoms := e.lazyNecessaryCandidates(p.match.Where, p.node.Variable)
		route := e.streamingGraphConstraint(p.match.Where, p.node.Variable)
		branch := "ordinary-skip"
		if p.projection.Distinct {
			branch = "distinct-skip-qualified-exhaustion"
		}
		if len(p.projection.OrderBy) > 0 {
			branch = "serial-order"
			if p.projection.Distinct {
				branch = "serial-distinct-retained-order"
			} else if atoms != nil && route == nil {
				branch = "direct-string-order-strategy-dependent-parallel"
			}
		}
		output = append(output, map[string]any{"name": c.Name, "query": c.Body.Query, "BAdmitted": true, "noEarlierIntercept": true, "skip": p.skip, "limit": p.limit, "retained": p.retained, "candidateAtoms": len(atoms), "selectedGraphIDs": route, "branch": branch, "actualStorageStrategyProbed": false})
	}
	data, err = json.MarshalIndent(output, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile("/tmp/graphite-real64-b-query-preparation/admission.json", append(data, '\n'), 0644); err != nil {
		t.Fatal(err)
	}
}

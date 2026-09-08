package query

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"runtime/debug"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

type genericDiagnosticSpec struct {
	Name               string         `json:"name"`
	Query              string         `json:"query"`
	Parameters         map[string]any `json:"parametersEncoded"`
	Fixtures           []string       `json:"fixtures"`
	Scoped             bool           `json:"scoped"`
	ProviderType       string         `json:"providerType"`
	ProviderProperties []string       `json:"providerProperties"`
}

func genericDiagnosticDecode(v any) any {
	switch x := v.(type) {
	case map[string]any:
		if units, ok := x["$utf16"].([]any); ok {
			u := make([]uint16, len(units))
			for i, n := range units {
				u[i] = uint16(n.(float64))
			}
			return javaFromUTF16(u)
		}
		out := map[string]any{}
		for k, v := range x {
			out[k] = genericDiagnosticDecode(v)
		}
		return out
	case []any:
		out := make([]any, len(x))
		for i, v := range x {
			out[i] = genericDiagnosticDecode(v)
		}
		return out
	default:
		return v
	}
}
func genericDiagnosticUTF16(v any) any {
	if s, ok := v.(string); ok {
		u := javaUTF16(s)
		if u == nil {
			return []uint16{}
		}
		return u
	}
	if v == nil {
		return nil
	}
	r := reflect.ValueOf(v)
	switch r.Kind() {
	case reflect.Slice, reflect.Array:
		out := make([]any, r.Len())
		for i := range out {
			out[i] = genericDiagnosticUTF16(r.Index(i).Interface())
		}
		return out
	case reflect.Map:
		out := map[string]any{}
		it := r.MapRange()
		for it.Next() {
			out[it.Key().String()] = genericDiagnosticUTF16(it.Value().Interface())
		}
		return out
	default:
		return v
	}
}
func genericDiagnosticState(graphs []Graph) []any {
	out := []any{}
	for _, g := range graphs {
		s, err := g.Store.StringPropertyIndexes(context.Background())
		if err != nil {
			out = append(out, map[string]any{"id": g.ID, "stateError": err.Error()})
		} else {
			out = append(out, map[string]any{"id": g.ID, "retained": s.Retained, "mappedView": s.MappedView})
		}
	}
	return out
}
func genericDiagnosticError(r map[string]any, v any) {
	r["outcome"] = "FAILED"
	r["error"] = fmt.Sprintf("Go:%T", v)
	r["message"] = fmt.Sprint(v)
	if e, ok := v.(*Error); ok {
		r["error"] = e.Class
		if e.NullMessage {
			r["message"] = nil
		} else {
			r["message"] = e.Message
		}
	}
	if r["message"] == nil {
		r["errorUTF16"] = []uint16{}
	} else {
		r["errorUTF16"] = genericDiagnosticUTF16(r["message"])
	}
}
func genericDiagnosticCase(spec genericDiagnosticSpec, fixtures string) (r map[string]any) {
	r = map[string]any{"name": spec.Name, "phase": "load", "diagnostics": nil, "diagnosticsUnavailable": "Go has no main-equivalent public execution diagnostics; WorkTrackingEnabled is explicitly true for public calls"}
	graphs := []Graph{}
	defer func() {
		if v := recover(); v != nil {
			genericDiagnosticError(r, v)
			r["goStack"] = string(debug.Stack())
		}
		r["after"] = genericDiagnosticState(graphs)
		for _, g := range graphs {
			if err := g.Store.Close(); err != nil {
				r["closeError"] = err.Error()
			}
		}
	}()
	for i := range spec.Fixtures {
		g, err := store.Open(filepath.Join(fixtures, spec.Name, fmt.Sprintf("store%d", i)))
		if err != nil {
			genericDiagnosticError(r, err)
			return
		}
		id := "single"
		if len(spec.Fixtures) > 1 {
			id = "a-second"
			if i == 0 {
				id = "z-first"
			}
		}
		graphs = append(graphs, Graph{ID: id, Store: g})
	}
	r["before"] = genericDiagnosticState(graphs)
	params := genericDiagnosticDecode(spec.Parameters).(map[string]any)
	r["parametersUTF16"] = genericDiagnosticUTF16(params)
	ctx := context.Background()
	if spec.ProviderType != "" {
		r["providerScope"] = "Go mainStringCandidates wrapper includes merge/order checks; original main calls private lookupStringPropertyDisjunction directly"
		r["phase"] = "provider-create"
		e := evaluator{ctx: ctx}
		atoms := []distinctStringAtom{}
		for _, p := range spec.ProviderProperties {
			atoms = append(atoms, distinctStringAtom{property: p, op: "CONTAINS", term: params["term"].(string), lower: true})
		}
		next := e.mainStringCandidates(graphs[0], cypher.NodePattern{Labels: []string{spec.ProviderType}}, atoms, 1)
		r["providerSupported"] = next != nil
		r["yielded"] = []any{}
		if next != nil {
			r["phase"] = "provider-consume"
			for {
				node, ok := next(ctx)
				if !ok {
					break
				}
				v := objectString(node, nil)
				r["yielded"] = append(r["yielded"].([]any), map[string]any{"type": "io.johnsonlee.graphite.core." + node.Kind, "id": node.ID, "value": v, "valueUTF16": genericDiagnosticUTF16(v)})
			}
		}
	} else {
		r["phase"] = "execute"
		var result Result
		var err error
		if len(graphs) == 1 {
			result, err = executeSources(ctx, graphs[0].Store, nil, false, spec.Query, params, -1, ExecutionOptions{WorkTrackingEnabled: true})
		} else {
			result, err = ExecuteCrossWithOptions(ctx, graphs, spec.Query, params, -1, ExecutionOptions{WorkTrackingEnabled: true, SourceScopeApplied: spec.Scoped})
		}
		if err != nil {
			genericDiagnosticError(r, err)
			return
		}
		r["columns"] = result.Columns
		r["rows"] = result.Rows
		r["rowsUTF16"] = genericDiagnosticUTF16(result.Rows)
	}
	r["outcome"] = "SUCCESS"
	return
}
func TestGenericDisjunctionDiagnosticCapture(t *testing.T) {
	if os.Getenv("GRAPHITE_GENERIC_DIAGNOSTIC_INPUT") == "" {
		t.Skip("diagnostic runner only")
	}
	raw, err := os.ReadFile(os.Getenv("GRAPHITE_GENERIC_DIAGNOSTIC_INPUT"))
	if err != nil {
		t.Fatal(err)
	}
	var specs []genericDiagnosticSpec
	if err = json.Unmarshal(raw, &specs); err != nil {
		t.Fatal(err)
	}
	cases := []any{}
	for _, s := range specs {
		cases = append(cases, genericDiagnosticCase(s, os.Getenv("GRAPHITE_GENERIC_DIAGNOSTIC_FIXTURES")))
	}
	data, err := json.MarshalIndent(map[string]any{"performanceMeasurements": 0, "cases": cases}, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(os.Getenv("GRAPHITE_GENERIC_DIAGNOSTIC_OUTPUT"), append(data, '\n'), 0644); err != nil {
		t.Fatal(err)
	}
	if len(cases) != 201 {
		t.Fatalf("expected 201 capture cases, got %d", len(cases))
	}
}

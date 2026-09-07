package benchmarkcase

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"os"
	"reflect"
	"strings"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
)

func fixture(t *testing.T) ([]byte, Workload) {
	t.Helper()
	b, err := os.ReadFile("testdata/main64.json")
	if err != nil {
		t.Fatal(err)
	}
	w, err := Decode(b)
	if err != nil {
		t.Fatal(err)
	}
	return b, w
}
func caseByID(t *testing.T, w Workload, id string) Case {
	t.Helper()
	for _, c := range w.Cases {
		if c.ID == id {
			return c
		}
	}
	t.Fatal(id)
	return Case{}
}
func TestActualMain1267RoundTripAndParse(t *testing.T) {
	b, w := fixture(t)
	if fmt.Sprintf("%x", sha256.Sum256(b)) != "378c200c5ab3053c53962f9d87c59924f732d0c012fcaff6009842a58e547023" {
		t.Fatal("not the actual frozen JVM export")
	}
	if len(w.Cases) != 1267 || w.MainRevision != "4e328b0109e13c896b74004823fb049fcb19251a" {
		t.Fatal("case identity")
	}
	encoded, err := json.Marshal(w)
	if err != nil {
		t.Fatal(err)
	}
	decodeAny := func(b []byte) any {
		d := json.NewDecoder(strings.NewReader(string(b)))
		d.UseNumber()
		var v any
		if err := d.Decode(&v); err != nil {
			t.Fatal(err)
		}
		return v
	}
	if !reflect.DeepEqual(decodeAny(b), decodeAny(encoded)) {
		t.Fatal("query/parameters/order/metadata changed in typed round trip")
	}
	families := map[string]int{}
	for _, c := range w.Cases {
		families[c.Family]++
		ast, err := cypher.Parse(c.Query)
		if err != nil {
			t.Errorf("%s: %v", c.ID, err)
			continue
		}
		if len(ast.Branches) != 1 || len(ast.Branches[0].Clauses) != 2 {
			t.Errorf("%s: expected MATCH+RETURN, got %#v", c.ID, ast)
			continue
		}
		match, ok := ast.Branches[0].Clauses[0].(cypher.MatchClause)
		if !ok || match.Optional || len(match.Patterns) != 1 || len(match.Patterns[0].Nodes) != 1 || match.Patterns[0].Nodes[0].Variable != "n" || match.Where == nil {
			t.Errorf("%s: match structure %#v", c.ID, match)
		}
		projection, ok := ast.Branches[0].Clauses[1].(cypher.ProjectionClause)
		if !ok || projection.With || len(projection.Items) == 0 {
			t.Errorf("%s: return structure %#v", c.ID, projection)
		}
		refs := map[string]int{}
		parameters(reflect.ValueOf(ast), refs)
		if len(refs) != len(c.Parameters) {
			t.Errorf("%s: AST references %v differ from supplied parameters %v", c.ID, refs, c.Parameters)
		}
		for name := range refs {
			if _, ok := c.Parameters[name]; !ok {
				t.Errorf("%s: missing parameter %s", c.ID, name)
			}
		}
	}
	expected := map[string]int{"graph-set-reference": 123, "contains": 24, "wrapped": 9, "graph-id": 576, "graph-parameter": 192, "boolean": 12, "exact": 12, "projection": 21, "aggregation": 6, "global-wide": 34, "global": 9, "regex": 3, "graph-id-set": 246}
	if !reflect.DeepEqual(families, expected) {
		t.Fatalf("families %v", families)
	}
}
func parameters(v reflect.Value, out map[string]int) {
	if !v.IsValid() {
		return
	}
	if v.Kind() == reflect.Interface || v.Kind() == reflect.Pointer {
		if !v.IsNil() {
			parameters(v.Elem(), out)
		}
		return
	}
	if v.CanInterface() {
		if p, ok := v.Interface().(cypher.Parameter); ok {
			out[p.Name]++
			return
		}
	}
	switch v.Kind() {
	case reflect.Struct:
		for i := 0; i < v.NumField(); i++ {
			parameters(v.Field(i), out)
		}
	case reflect.Slice, reflect.Array:
		for i := 0; i < v.Len(); i++ {
			parameters(v.Index(i), out)
		}
	case reflect.Map:
		iter := v.MapRange()
		for iter.Next() {
			parameters(iter.Value(), out)
		}
	}
}
func TestOriginalOrderAndRequestScope(t *testing.T) {
	_, w := fixture(t)
	if w.Cases[0].ID != "request-selected-set-wrapped-contains-k64-group-00-zero" {
		t.Fatal("cold-first case was reordered")
	}
	if w.SourceOrder[16] != "fixture-tika-00" || w.SourceOrder[32] != "fixture-hive-00" {
		t.Fatal("manifest order was sorted like HTTP")
	}
	selected, err := w.Input(w.Cases[0], 60000)
	if err != nil {
		t.Fatal(err)
	}
	if !selected.SourceScopeApplied || !reflect.DeepEqual(selected.GraphIDs, w.SourceOrder) {
		t.Fatal(selected)
	}
	ordinary := caseByID(t, w, "global-wide-four-properties-dense")
	all, _ := w.Input(ordinary, 60000)
	if all.SourceScopeApplied || !reflect.DeepEqual(all.GraphIDs, w.SourceOrder) {
		t.Fatal("all64 predicate path incorrectly marked preselected")
	}
	selected.GraphIDs[0] = "mutated"
	if w.SourceOrder[0] != "fixture-android-00" || w.Cases[0].RequestGraphIDs[0] != "fixture-android-00" {
		t.Fatal("mutable source selection aliases frozen case")
	}
	for _, c := range w.Cases {
		in, _ := w.Input(c, 60000)
		if in.SourceScopeApplied != (c.RequestGraphIDs != nil) || in.Query != c.Query || !reflect.DeepEqual(in.Parameters, c.Parameters) {
			t.Fatal(c.ID)
		}
		if c.RequestGraphIDs == nil && len(in.GraphIDs) != 64 {
			t.Fatal("predicate route filtered before executor", c.ID)
		}
	}
}
func TestActualParameterizedASTRetainsBindings(t *testing.T) {
	_, w := fixture(t)
	c := caseByID(t, w, "global-wide-parameterized-targeted")
	ast, err := cypher.Parse(c.Query)
	if err != nil {
		t.Fatal(err)
	}
	m := ast.Branches[0].Clauses[0].(cypher.MatchClause)
	var leaves []cypher.Binary
	var flatten func(cypher.Expr)
	flatten = func(e cypher.Expr) {
		b, ok := e.(cypher.Binary)
		if !ok {
			t.Fatalf("unexpected predicate %#v", e)
		}
		if b.Op == "OR" {
			flatten(b.Left)
			flatten(b.Right)
		} else {
			leaves = append(leaves, b)
		}
	}
	flatten(m.Where)
	want := []string{"caller_class", "caller_name", "callee_class", "callee_name"}
	if len(leaves) != 4 {
		t.Fatal(leaves)
	}
	for i, b := range leaves {
		property, ok := b.Left.(cypher.Property)
		parameter, pok := b.Right.(cypher.Parameter)
		if b.Op != "CONTAINS" || !ok || property.Key != want[i] || !reflect.DeepEqual(property.Object, cypher.Variable{Name: "n"}) || !pok || parameter.Name != "term" {
			t.Fatal(b)
		}
	}
	if c.Parameters["term"] != "org.jetbrains.kotlin.fir.analysis.jvm.checkers.expression.FirJavaSamConstructorNullabilityChecker$getReturnedExpressions$extractReturnedExpression$finallyBlockEnterNode$1" {
		t.Fatal("bound value changed")
	}
	for _, c := range w.Cases {
		if c.Shape == "graph-id-in-parameter-wrapped-contains" {
			ids, ok := c.Parameters["graphIds"].([]any)
			if !ok || len(ids) != len(c.TargetGraphIDs) {
				t.Fatal(c.ID)
			}
			for i, id := range ids {
				if id != c.TargetGraphIDs[i] {
					t.Fatal(c.ID)
				}
			}
		}
	}
}
func TestRejectLostFieldsAndInvalidSelections(t *testing.T) {
	b, w := fixture(t)
	withUnknown := append([]byte(`{"unrecognizedMainField":true,`), b[1:]...)
	if _, err := Decode(withUnknown); err == nil {
		t.Fatal("unknown field silently discarded")
	}
	if _, err := Decode(append(b, []byte(`{}`)...)); err == nil {
		t.Fatal("extra document accepted")
	}
	w.Cases[0].RequestGraphIDs = []string{"unknown"}
	if err := w.Validate(); err == nil {
		t.Fatal("unknown selected graph accepted")
	}
	_, w = fixture(t)
	w.Cases[1].ID = w.Cases[0].ID
	if err := w.Validate(); err == nil {
		t.Fatal("duplicate case accepted")
	}
}

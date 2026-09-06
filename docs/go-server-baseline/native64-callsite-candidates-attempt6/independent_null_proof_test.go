package query

// External correctness audit only. No performance observations are made here.
import (
	"context"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestExternalCandidateNullAndWrapperProof(t *testing.T) {
	graph, err := store.OpenMode("../store/testdata/jvm-v3", "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	defer graph.Close()
	e := evaluator{ctx: context.Background(), indexFirst: true}
	names := []string{"caller_class", "caller_name", "callee_class", "callee_name"}
	terms := []string{"", "a", "Example", "callee", "Σ", "σ", "İ", "i\u0307", "😀", "\xed\xa0\xbd", "\xed\xb8\x80", "日本"}
	wrap := func(name string, expression cypher.Expr) cypher.Expr {
		args := []cypher.Expr{expression}
		if name == "coalesce" {
			args = append(args, cypher.Literal{Value: ""})
		}
		return cypher.Call{Name: name, Arguments: args}
	}
	combinations := [][]string{{}, {"coalesce"}, {"toString"}, {"toLower"}, {"coalesce", "toLower"}, {"toLower", "coalesce"}, {"coalesce", "toLower", "toString"}, {"toString", "coalesce"}}
	admitted, comparisons := 0, 0
	for _, name := range names {
		for _, wrappers := range combinations {
			var operand cypher.Expr = cypher.Property{Object: cypher.Variable{Name: "probe"}, Key: name}
			for _, wrapper := range wrappers {
				operand = wrap(wrapper, operand)
			}
			for _, op := range []string{"=", "CONTAINS", "STARTS WITH", "ENDS WITH"} {
				for _, term := range terms {
					where := cypher.Binary{Left: operand, Op: op, Right: cypher.Literal{Value: term}}
					clause := cypher.MatchClause{Patterns: []cypher.Pattern{{Nodes: []cypher.NodePattern{{Variable: "probe"}}}}, Where: where}
					plan := e.compileStringCandidates(clause)
					if plan == nil {
						continue
					}
					admitted++
					for _, id := range graph.NodeIDs() {
						node, err := graph.Node(id)
						if err != nil {
							t.Fatal(err)
						}
						actual := e.eval(where, map[string]any{"probe": node})
						if node.Kind != "CallSiteNode" && node.Kind != "AnnotationNode" {
							if actual == true {
								t.Fatalf("unsafe type inference: %s, %s, %v %s %q", node.Kind, name, wrappers, op, term)
							}
							comparisons++
							continue
						}
						if node.Kind == "AnnotationNode" {
							continue
						} // Runtime must fall back for these stores.
						predicted := false
						for _, atom := range plan.atoms {
							var left any = NodeProperty(node, names[atom.operand.property])
							if atom.operand.lower {
								left = e.javaCase(left.(string), false)
							}
							if e.binary(cypher.Binary{Left: cypher.Literal{Value: left}, Op: atom.op, Right: cypher.Literal{Value: atom.term}}, nil) == true {
								predicted = true
							}
						}
						if predicted != (actual == true) {
							t.Fatalf("CallSite transform differs: %s, %v %s %q: plan=%v original=%v", name, wrappers, op, term, predicted, actual)
						}
						comparisons++
					}
				}
			}
		}
	}
	if admitted < 1000 || comparisons < 15000 {
		t.Fatalf("insufficient non-vacuous coverage: admitted=%d comparisons=%d", admitted, comparisons)
	}
	t.Logf("admitted=%d complete-kind/predicate comparisons=%d", admitted, comparisons)
}

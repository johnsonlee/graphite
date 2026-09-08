package query

import (
	"context"
	"fmt"
	"path/filepath"
	"reflect"
	"strconv"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

type leadingWorkOracleOperation struct {
	Spec struct {
		Op, Budget, Query string
		FreshExecutor     bool
		MaxRows           *int
		Parameters        map[string]any
	}
	Outcome       string
	Error         string
	Message       *string
	Before, After map[string]any
	Value         any
}

type leadingWorkOracleCase struct {
	Name string
	Spec struct {
		Mode, Budget string
		Sources      []struct{ Fixture, GraphID string }
		Prelude      *struct{ Fixture string }
	}
	Construction workOracleOperation
	Operations   []leadingWorkOracleOperation
	Final        map[string]any
}

// Expectations come from the original actual-main executions. Fixture file
// manifests and JVM exception stacks remain artifact identity/observer evidence;
// all public rows, simple error classes/messages, eight request diagnostics and
// the four mapped storage states are compared at each operation boundary.
func TestLeadingWorkAccountingMainOracle(t *testing.T) {
	const dir = "../../../docs/go-server-baseline/native-leading-work-accounting"
	var first, repeat struct{ Cases []leadingWorkOracleCase }
	workReadJSON(t, filepath.Join(dir, "main.json"), &first)
	workReadJSON(t, filepath.Join(dir, "repeat-capture/main.json"), &repeat)
	if len(first.Cases) != 26 || len(repeat.Cases) != 26 {
		t.Fatalf("actual JVM case counts = %d/%d, want 26/26", len(first.Cases), len(repeat.Cases))
	}
	fixtures := genericDisjunctionFixtures(t, filepath.Join(dir, "fixtures.tar.gz"), 208)
	seen := map[string]bool{}
	operations := 0
	for index, record := range first.Cases {
		other := repeat.Cases[index]
		if seen[record.Name] || record.Name != other.Name || len(record.Operations) != len(other.Operations) {
			t.Fatalf("duplicate or misaligned actual JVM case %q", record.Name)
		}
		seen[record.Name] = true
		operations += len(record.Operations)
		t.Run(record.Name, func(t *testing.T) {
			workCompare(t, "repeated input", record.Spec, other.Spec)
			if record.Spec.Mode != "context" {
				t.Fatalf("unhandled actual executor mode %q", record.Spec.Mode)
			}
			budget := workLong(t, record.Spec.Budget)
			work, err := NewExecutionContext(budget)
			workCompareOutcome(t, "construction", record.Construction, nil, err)
			if err != nil {
				t.Fatal("leading-work fixture requires successful context construction")
			}
			sources := []Graph{}
			var prelude *store.Store
			snapshot := func() map[string]any {
				state := rawWorkSnapshot(t, budget, work, sources)
				state["preludeLoaded"] = prelude != nil
				return state
			}
			compareState := func(where string, got, original, repeated map[string]any) {
				t.Helper()
				want := rawWorkComparableSnapshot(t, original)
				workCompare(t, where+" repeated JVM state", rawWorkComparableSnapshot(t, repeated), want)
				workCompare(t, where, got, want)
			}
			compareState("construction state", snapshot(), record.Construction.After, other.Construction.After)
			open := func(fixture string) *store.Store {
				t.Helper()
				graph, err := store.OpenMode(ordinaryCopyFixture(t, filepath.Join(fixtures, fixture)), "MAPPED")
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { graph.Close() })
				return graph
			}
			// Each source gets an independent store, including repeated empty
			// variants. Context replacement must preserve these exact objects.
			for _, source := range record.Spec.Sources {
				sources = append(sources, Graph{ID: source.GraphID, Store: open(source.Fixture)})
			}
			if len(sources) == 0 {
				t.Fatal("actual leading-work oracle requires at least one source")
			}
			if record.Spec.Prelude != nil {
				prelude = open(record.Spec.Prelude.Fixture)
			}
			originalSources := append([]Graph(nil), sources...)
			originalPrelude := prelude
			for index, operation := range record.Operations {
				t.Run(fmt.Sprintf("%02d-%s", index, operation.Spec.Op), func(t *testing.T) {
					repeated := other.Operations[index]
					workCompare(t, "repeated operation input", operation.Spec, repeated.Spec)
					compareState("before", snapshot(), operation.Before, repeated.Before)
					value, err := workAttempt(func() (any, error) {
						op := operation.Spec
						if op.Op == "newContext" {
							maximum := workLong(t, op.Budget)
							next, err := NewExecutionContext(maximum)
							if err != nil {
								return nil, err
							}
							work, budget = next, maximum
							preserved := len(sources) == len(originalSources) && prelude == originalPrelude
							for i, source := range sources {
								preserved = preserved && source.ID == originalSources[i].ID && source.Store == originalSources[i].Store
							}
							return map[string]any{"maxWorkUnits": strconv.FormatInt(budget, 10), "sourceStoresPreserved": preserved}, nil
						}
						options := ExecutionOptions{ExecutionContext: work}
						parameters := workJavaParameters(t, op.Parameters)
						var result Result
						var err error
						switch op.Op {
						case "executePrelude":
							if prelude == nil {
								t.Fatal("executePrelude requires its independently loaded fixture")
							}
							// The prelude consumes the shared request through a public
							// query, without injecting private tracker work units.
							result, err = ExecuteWithOptions(context.Background(), prelude, op.Query, parameters, -1, options)
						case "execute":
							if len(sources) > 1 {
								if op.MaxRows != nil {
									result, err = ExecuteCrossWithMaxRows(context.Background(), sources, op.Query, parameters, *op.MaxRows, options)
								} else {
									result, err = ExecuteCrossWithOptions(context.Background(), sources, op.Query, parameters, -1, options)
								}
							} else if op.MaxRows != nil {
								result, err = ExecuteWithMaxRows(context.Background(), sources[0].Store, op.Query, parameters, *op.MaxRows, options)
							} else {
								result, err = ExecuteWithOptions(context.Background(), sources[0].Store, op.Query, parameters, -1, options)
							}
						default:
							t.Fatalf("unhandled actual leading-work operation %q", op.Op)
						}
						if err != nil {
							if !reflect.DeepEqual(result, Result{}) {
								t.Errorf("failed operation exposed partial result: %#v", result)
							}
							return nil, err
						}
						return map[string]any{"columns": result.Columns, "rows": result.Rows}, nil
					})
					workCompare(t, "repeated outcome", repeated.Outcome, operation.Outcome)
					workCompare(t, "repeated error class", repeated.Error, operation.Error)
					workCompare(t, "repeated error message", repeated.Message, operation.Message)
					workCompareOutcome(t, "operation", workOracleOperation{Outcome: operation.Outcome, Error: operation.Error, Message: operation.Message}, value, err)
					workCompare(t, "repeated complete public result", repeated.Value, operation.Value)
					if err == nil {
						workCompare(t, "complete public result", value, operation.Value)
					}
					compareState("after", snapshot(), operation.After, repeated.After)
				})
			}
			compareState("final", snapshot(), record.Final, other.Final)
		})
	}
	if operations != 52 {
		t.Errorf("actual JVM operations = %d, want 52", operations)
	}
}

// These compiler controls supplement the public actual-main cache reuse cases.
// Assert complete filter identity, encounter order and original term payload;
// Go byte equality must not split equivalent Java UTF-16 strings.
func TestLeadingWorkDisjunctionIdentity(t *testing.T) {
	property := func(name string) cypher.Expr {
		return cypher.Property{Object: cypher.Variable{Name: "n"}, Key: name}
	}
	atom := func(name, op, term string, lower bool) cypher.Expr {
		left := property(name)
		if lower {
			left = cypher.Call{Name: "toLower", Arguments: []cypher.Expr{left}}
		}
		return cypher.Binary{Left: left, Op: op, Right: cypher.Literal{Value: term}}
	}
	or := func(left, right cypher.Expr) cypher.Expr {
		return cypher.Binary{Left: left, Op: "OR", Right: right}
	}
	in := func(terms ...string) cypher.Expr {
		values := make([]cypher.Expr, len(terms))
		for i, term := range terms {
			values[i] = cypher.Literal{Value: term}
		}
		return cypher.Binary{Left: property("caller_name"), Op: "IN", Right: cypher.List{Elements: values}}
	}
	const pair = "\xed\xa0\xbd\xed\xb8\x80"
	for _, tc := range []struct {
		name string
		expr cypher.Expr
		want []distinctStringAtom
	}{
		{"repeated-or", or(atom("caller_name", "CONTAINS", "hit", false), atom("caller_name", "CONTAINS", "hit", false)), []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: "hit"}}},
		{"lowercase-identity", or(atom("caller_name", "CONTAINS", "hit", false), atom("caller_name", "CONTAINS", "hit", true)), []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: "hit"}, {property: "caller_name", op: "CONTAINS", term: "hit", lower: true}}},
		{"operator-identity", or(atom("caller_name", "CONTAINS", "hit", false), atom("caller_name", "STARTS WITH", "hit", false)), []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: "hit"}, {property: "caller_name", op: "STARTS WITH", term: "hit"}}},
		{"property-identity", or(atom("caller_name", "CONTAINS", "hit", false), atom("callee_name", "CONTAINS", "hit", false)), []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: "hit"}, {property: "callee_name", op: "CONTAINS", term: "hit"}}},
		{"in-encounter-order", in("second", "first", "second", "last", "first"), []distinctStringAtom{{property: "caller_name", op: "=", term: "second"}, {property: "caller_name", op: "=", term: "first"}, {property: "caller_name", op: "=", term: "last"}}},
		{"complete-identity", or(or(atom("callee_name", "CONTAINS", "hit", false), atom("caller_name", "STARTS WITH", "hit", false)), or(atom("caller_name", "CONTAINS", "hit", true), or(atom("callee_name", "CONTAINS", "hit", false), atom("caller_name", "CONTAINS", "other", true)))), []distinctStringAtom{{property: "callee_name", op: "CONTAINS", term: "hit"}, {property: "caller_name", op: "STARTS WITH", term: "hit"}, {property: "caller_name", op: "CONTAINS", term: "hit", lower: true}, {property: "caller_name", op: "CONTAINS", term: "other", lower: true}}},
		{"utf16-or-original-payload", or(atom("caller_name", "CONTAINS", pair, false), atom("caller_name", "CONTAINS", "😀", false)), []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: pair}}},
		{"utf16-in-original-payload", in("😀", pair, "\xed\xa0\xbd", "\xed\xb8\x80"), []distinctStringAtom{{property: "caller_name", op: "=", term: "😀"}, {property: "caller_name", op: "=", term: "\xed\xa0\xbd"}, {property: "caller_name", op: "=", term: "\xed\xb8\x80"}}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			e := evaluator{ctx: context.Background()}
			got, accepted := e.compileDistinctDisjunction(tc.expr, "n")
			if !accepted || !reflect.DeepEqual(got, tc.want) {
				t.Fatalf("compiled filters = %#v / accepted %v, want %#v", got, accepted, tc.want)
			}
		})
	}
}

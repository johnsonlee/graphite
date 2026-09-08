package query

import (
	"context"
	"errors"
	"fmt"
	"math"
	"path/filepath"
	"reflect"
	"runtime"
	"strconv"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

type rawWorkOracleCase struct {
	Name string
	Spec struct {
		Mode, Budget string
		Sources      []struct{ Fixture, GraphID string }
	}
	Construction workOracleOperation
	Operations   []workOracleOperation
	Final        map[string]any
}

func TestRawWorkBatchesMainOracle(t *testing.T) {
	testRawWorkOracle(t, "../../../docs/go-server-baseline/native-raw-work-batches", 43, 57, 329)
}

func TestRawWorkMethodEmptyMainOracle(t *testing.T) {
	testRawWorkOracle(t, "../../../docs/go-server-baseline/native-raw-work-batches/checks/method-zero-oracle", 16, 16, 16)
}

// A source-loop control for the private admission helper. Nil stores make an
// accidental metadata read fail; this is not a benchmark or a JVM capture.
func TestRawWorkMethodEmptySourceGate(t *testing.T) {
	skip := runtime.NumCPU() + 1
	if skip > 5000 {
		t.Skip("host has no requested-count window for main's parallel route")
	}
	parsed, err := cypher.Parse(fmt.Sprintf("MATCH (m:Method) RETURN m.name AS name SKIP %d LIMIT 0", skip))
	if err != nil {
		t.Fatal(err)
	}
	if len(parsed.Branches) != 1 || len(parsed.Branches[0].Clauses) != 2 {
		t.Fatal("unexpected Method query structure")
	}
	for _, count := range []int{0, 1, 2} {
		t.Run(strconv.Itoa(count), func(t *testing.T) {
			sources := make([]Graph, count)
			for i := range sources {
				sources[i].ID = fmt.Sprintf("source%d", i)
			}
			e := evaluator{ctx: context.Background(), cross: true, graphs: sources}
			result, accepted := e.methodEmpty(nil, parsed.Branches[0])
			if count == 2 {
				if accepted || !reflect.DeepEqual(result, Result{}) {
					t.Fatal("a real parallel source wave must retain execution")
				}
				return
			}
			if !accepted || !reflect.DeepEqual(result, Result{Columns: []string{"name"}, Rows: []map[string]any{}}) {
				t.Fatalf("non-scanning source loop: accepted=%v result=%#v", accepted, result)
			}
		})
	}
}

func testRawWorkOracle(t *testing.T, dir string, expectedCases, expectedOperations, expectedFiles int) {
	t.Helper()
	var first, repeat struct{ Cases []rawWorkOracleCase }
	workReadJSON(t, filepath.Join(dir, "main.json"), &first)
	workReadJSON(t, filepath.Join(dir, "repeat-capture/main.json"), &repeat)
	if len(first.Cases) != expectedCases || len(repeat.Cases) != expectedCases {
		t.Fatalf("actual JVM case counts = %d/%d, want %d/%d", len(first.Cases), len(repeat.Cases), expectedCases, expectedCases)
	}
	fixtures := genericDisjunctionFixtures(t, filepath.Join(dir, "fixtures.tar.gz"), expectedFiles)
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
				t.Fatal("raw scan fixture requires successful context construction")
			}
			sources := []Graph{}
			workCompare(t, "construction state", rawWorkSnapshot(t, budget, work, sources), rawWorkComparableSnapshot(t, record.Construction.After))
			for _, source := range record.Spec.Sources {
				graph, err := store.OpenMode(ordinaryCopyFixture(t, filepath.Join(fixtures, source.Fixture)), "MAPPED")
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { graph.Close() })
				sources = append(sources, Graph{ID: source.GraphID, Store: graph})
			}
			if len(sources) == 0 {
				t.Fatal("actual raw-work oracle requires at least one source")
			}
			for index, operation := range record.Operations {
				t.Run(fmt.Sprintf("%02d-%s", index, operation.Spec.Op), func(t *testing.T) {
					workCompare(t, "repeated operation input", operation.Spec, other.Operations[index].Spec)
					workCompare(t, "before", rawWorkSnapshot(t, budget, work, sources), rawWorkComparableSnapshot(t, operation.Before))
					value, err := workAttempt(func() (any, error) {
						op := operation.Spec
						switch op.Op {
						case "consume":
							work.consume(workLong(t, op.Units))
							return nil, nil
						case "execute":
							options := ExecutionOptions{ExecutionContext: work}
							parameters := workJavaParameters(t, op.Parameters)
							var result Result
							var err error
							// Main chooses Cross only with more than one source. Fresh
							// evaluators retain this request and these stores across calls.
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
							if err != nil {
								if !reflect.DeepEqual(result, Result{}) {
									t.Errorf("failed operation exposed partial result: %#v", result)
								}
								return nil, err
							}
							return map[string]any{"columns": result.Columns, "rows": result.Rows}, nil
						default:
							return nil, fmt.Errorf("unhandled actual raw-work operation %q", op.Op)
						}
					})
					rawWorkCompareOutcome(t, record.Name, operation, other.Operations[index], value, err)
					if err == nil {
						workCompare(t, "complete public result", value, operation.Value)
					}
					workCompare(t, "after", rawWorkSnapshot(t, budget, work, sources), rawWorkComparableSnapshot(t, operation.After))
				})
			}
			workCompare(t, "final", rawWorkSnapshot(t, budget, work, sources), rawWorkComparableSnapshot(t, record.Final))
		})
	}
	if operations != expectedOperations {
		t.Errorf("actual JVM operations = %d, want %d", operations, expectedOperations)
	}
}

func rawWorkCompareOutcome(t *testing.T, name string, first, repeat workOracleOperation, value any, err error) {
	t.Helper()
	// Only these two original default-JVM cases exhibited fast-throw message
	// variation. Accept their actual two observed class/message pairs, preserving
	// every other error message. Diagnostic JVM-flag runs are not expectations.
	if err != nil && (name == "call-bad1023-budget1024" || name == "call-bad1024-budget1025") && repeat.Outcome == "FAILED" {
		var message any
		if repeat.Message != nil {
			message = *repeat.Message
		}
		if reflect.DeepEqual(workErrorValue(err), map[string]any{"error": repeat.Error, "message": message}) {
			return
		}
	}
	workCompareOutcome(t, "operation", first, value, err)
}

func rawWorkSnapshot(t *testing.T, budget int64, work *ExecutionContext, sources []Graph) map[string]any {
	t.Helper()
	result := workSnapshot("context", budget, work)
	storage := []any{}
	for _, source := range sources {
		state, err := source.Store.StringPropertyIndexes(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		storage = append(storage, map[string]any{
			"graphId": source.ID, "isCallSiteStringIndexInitialized": state.Retained,
			"isMappedCallSiteStringIndexViewInitialized": state.MappedView,
			"rawStringMatchStateCount":                   state.RawMatchCount, "rawProjectionMatchCount": state.RawProjectionCount,
		})
	}
	result["storage"] = storage
	return result
}

func rawWorkComparableSnapshot(t *testing.T, input map[string]any) map[string]any {
	t.Helper()
	result := workComparableSnapshot(input)
	storage := []any{}
	for _, raw := range input["storage"].([]any) {
		row := map[string]any{}
		for key, value := range raw.(map[string]any) {
			switch key {
			case "graphId", "isCallSiteStringIndexInitialized", "isMappedCallSiteStringIndexViewInitialized", "rawStringMatchStateCount", "rawProjectionMatchCount":
				row[key] = value
			case "callSiteParallelScanCount", "callSiteStringLookupEntryCount", "callSiteStringIndexLookupCount":
				// Go exposes no equivalent cumulative storage event counters.
				// Keep these three observations in the raw JVM archive; they are
				// distinct from the eight fully compared request diagnostics.
			default:
				t.Fatalf("unmapped JVM storage observer %q", key)
			}
		}
		storage = append(storage, row)
	}
	result["storage"] = storage
	return result
}

// The production delegate is an ExecutionContext, not an arbitrary callback.
// Replay the seven successful batch scenarios plus null from the actual buffer
// oracle; total submitted units are observed through request diagnostics.
func TestRawWorkBatchesBufferedMainOracle(t *testing.T) {
	var oracle struct {
		Cases []struct {
			Name string
			Spec struct {
				Delegate string
				FailCall int
			}
			Operations []struct {
				Spec struct {
					Op    string
					Count int
				}
				Outcome           string
				CompletedConsumes int
				Before, After     struct {
					Pending       string
					DelegateCalls []string
				}
			}
		}
	}
	workReadJSON(t, "../../../docs/go-server-baseline/native-raw-work-batches/buffered-main.json", &oracle)
	matched := 0
	for _, record := range oracle.Cases {
		if record.Spec.Delegate == "unit" || record.Spec.FailCall != 0 {
			continue // The production API does not expose these delegate types.
		}
		matched++
		t.Run(record.Name, func(t *testing.T) {
			var work *ExecutionContext
			if record.Spec.Delegate == "batch" {
				var err error
				work, err = NewExecutionContext(math.MaxInt64)
				if err != nil {
					t.Fatal(err)
				}
			} else if record.Spec.Delegate != "null" {
				t.Fatalf("unknown actual buffer delegate %q", record.Spec.Delegate)
			}
			buffer := bufferedGraphWork{work: work}
			check := func(pending string, calls []string) {
				t.Helper()
				workCompare(t, "pending", strconv.FormatInt(buffer.pending, 10), pending)
				var submitted, actual int64
				for _, call := range calls {
					submitted += workLong(t, call)
				}
				if work != nil {
					actual = work.Diagnostics().WorkUnitsConsumed
				}
				workCompare(t, "submitted graph work", actual, submitted)
			}
			for _, operation := range record.Operations {
				check(operation.Before.Pending, operation.Before.DelegateCalls)
				completed := 0
				_, err := workAttempt(func() (any, error) {
					if operation.Spec.Op == "flush" {
						buffer.flush()
					} else if operation.Spec.Op == "consume" {
						for i := 0; i < operation.Spec.Count; i++ {
							buffer.consume()
							completed++
						}
					} else {
						t.Fatalf("unknown actual buffered operation %q", operation.Spec.Op)
					}
					return nil, nil
				})
				if operation.Outcome != "SUCCESS" || err != nil {
					t.Fatalf("actual outcome %s; Go error %v", operation.Outcome, err)
				}
				workCompare(t, "completed consumes", completed, operation.CompletedConsumes)
				check(operation.After.Pending, operation.After.DelegateCalls)
			}
		})
	}
	if matched != 8 {
		t.Errorf("replayed batch/null primitive scenarios = %d, want 8", matched)
	}
}

// These are Go request-context controls, not a claim to replay the JVM oracle's
// arbitrary IllegalStateException delegate. That original evidence is retained.
func TestRawWorkBatchesContextFailures(t *testing.T) {
	for _, count := range []int{1023, 1024} {
		t.Run(strconv.Itoa(count), func(t *testing.T) {
			work, err := NewExecutionContext(int64(count - 1))
			if err != nil {
				t.Fatal(err)
			}
			buffer := bufferedGraphWork{work: work}
			_, err = workAttempt(func() (any, error) {
				for i := 0; i < count; i++ {
					buffer.consume()
				}
				buffer.flush()
				return nil, nil
			})
			var failure *Error
			if !errors.As(err, &failure) || failure.Class != "CypherBudgetExceededException" || buffer.pending != 0 || work.Diagnostics().WorkUnitsConsumed != int64(count-1) {
				t.Fatalf("failed batch: error %v, pending %d, diagnostics %+v", err, buffer.pending, work.Diagnostics())
			}
			reason := &Error{Class: "CypherQueryCancelledException", Message: "buffer control cancellation"}
			work.Cancel(reason)
			_, err = workAttempt(func() (any, error) { buffer.flush(); return nil, nil })
			if err != nil {
				t.Fatalf("empty flush consulted cancellation or resubmitted failed batch: %v", err)
			}
			buffer.consume() // Pending work is submitted only by flush.
			_, err = workAttempt(func() (any, error) { buffer.flush(); return nil, nil })
			if err != reason || buffer.pending != 0 || work.Diagnostics().WorkUnitsConsumed != int64(count-1) {
				t.Fatalf("cancelled flush: error %v, pending %d, diagnostics %+v", err, buffer.pending, work.Diagnostics())
			}
		})
	}
}

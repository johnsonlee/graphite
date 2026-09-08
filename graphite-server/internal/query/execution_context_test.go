package query

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"sync"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// The expected operations are captured from the actual pinned JVM, including
// errors and post-failure state. All 51 scenarios are replayed; no latency or
// allocation claim is made from these small persisted correctness fixtures.
func TestExecutionContextMainOracle(t *testing.T) {
	oracleDir := "../../../docs/go-server-baseline/native-work-context"
	testExecutionContextOracle(t, oracleDir, filepath.Join(oracleDir, "fixtures.tar.gz"), 51, 129, 80)
}

func TestExecutionContextMainSeekOracle(t *testing.T) {
	oracleDir := "../../../docs/go-server-baseline/native-work-context/checks/seek-oracle"
	testExecutionContextOracle(t, oracleDir, filepath.Join(oracleDir, "fixtures.tar.gz"), 14, 21, 80)
}

func TestExecutionContextMainBoundedOracle(t *testing.T) {
	oracleDir := "../../../docs/go-server-baseline/native-work-context/checks/bounded-oracle"
	testExecutionContextOracle(t, oracleDir, filepath.Join(oracleDir, "fixtures.tar.gz"), 19, 19, 80)
}

func TestExecutionContextMainOrderedOracle(t *testing.T) {
	oracleDir := "../../../docs/go-server-baseline/native-work-context/checks/ordered-oracle"
	testExecutionContextOracle(t, oracleDir, filepath.Join(oracleDir, "fixtures.tar.gz"), 25, 25, 80)
}

func testExecutionContextOracle(t *testing.T, oracleDir, fixtureArchive string, expectedCases, expectedOperations, expectedFixtureFiles int) {
	t.Helper()
	var oracle struct {
		Cases []workOracleCase `json:"cases"`
	}
	workReadJSON(t, filepath.Join(oracleDir, "main.json"), &oracle)
	if len(oracle.Cases) != expectedCases {
		t.Fatalf("actual main scenario count = %d, want %d", len(oracle.Cases), expectedCases)
	}
	fixtures := genericDisjunctionFixtures(t, fixtureArchive, expectedFixtureFiles)
	operations := 0
	seen := map[string]bool{}
	for _, record := range oracle.Cases {
		if seen[record.Name] {
			t.Fatalf("duplicate actual main scenario %q", record.Name)
		}
		seen[record.Name] = true
		operations += len(record.Operations)
		t.Run(record.Name, func(t *testing.T) {
			budget := workLong(t, record.Spec.Budget)
			var execution *ExecutionContext
			var constructionError error
			// The budget-only and unbudgeted Go APIs are functions, not executor
			// objects. Their context is created by each ExecuteWithOptions call.
			if record.Spec.Mode != "budget-only" && record.Spec.Mode != "unbudgeted" {
				execution, constructionError = NewExecutionContext(budget)
			}
			workCompareOutcome(t, "construction", record.Construction, nil, constructionError)
			if constructionError != nil {
				if len(record.Operations) != 0 {
					t.Errorf("constructor failed before %d expected operations", len(record.Operations))
				}
				workCompare(t, "failed construction state", workSnapshot(record.Spec.Mode, budget, execution), workComparableSnapshot(record.Construction.After))
				return
			}
			workCompare(t, "construction state", workSnapshot(record.Spec.Mode, budget, execution), workComparableSnapshot(record.Construction.After))
			var graph *store.Store
			if record.Spec.Mode == "context" || record.Spec.Mode == "budget-only" || record.Spec.Mode == "unbudgeted" {
				dir := ordinaryCopyFixture(t, filepath.Join(fixtures, record.Spec.Fixture))
				var err error
				graph, err = store.OpenMode(dir, "MAPPED")
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { graph.Close() })
			}
			var firstReason *Error
			for index, operation := range record.Operations {
				t.Run(fmt.Sprintf("%02d-%s", index, operation.Spec.Op), func(t *testing.T) {
					workCompare(t, "before", workSnapshot(record.Spec.Mode, budget, execution), workComparableSnapshot(operation.Before))
					value, err := workAttempt(func() (any, error) {
						switch op := operation.Spec; op.Op {
						case "consume":
							execution.consume(workLong(t, op.Units))
						case "check":
							execution.checkCancelled()
						case "fast":
							execution.recordFastPath()
						case "filtered":
							execution.recordFilteredNodeLimitFastPath()
						case "fallback":
							execution.recordGeneralFallback()
						case "selection":
							execution.recordGraphIdSourceSelection(op.Initial, op.Selected, op.Conflicting)
						case "cancel":
							var reason *Error
							switch op.Kind {
							case "default":
							case "timeout":
								reason = &Error{Class: "CypherQueryTimeoutException", Message: "Cypher query timed out after " + op.TimeoutMillis + " ms"}
							case "custom":
								reason = &Error{Class: "CypherQueryCancelledException", Message: op.Message}
							default:
								return nil, fmt.Errorf("unknown cancellation kind %q", op.Kind)
							}
							accepted := execution.Cancel(reason)
							if accepted {
								firstReason = execution.CancellationException()
								if reason != nil && firstReason != reason {
									t.Error("cancel replaced the supplied first reason object")
								}
							}
							return map[string]any{"accepted": accepted, "firstReasonPreserved": execution.CancellationException() == firstReason}, nil
						case "reason":
							value := workErrorValue(execution.CancellationException())
							value["sameAsFirstCancellationReason"] = firstReason != nil && execution.CancellationException() == firstReason
							return value, nil
						case "execute":
							options := ExecutionOptions{}
							if record.Spec.Mode == "context" {
								options.ExecutionContext = execution
							} else if record.Spec.Mode == "budget-only" {
								options.WorkBudget = budget
							}
							// Both functions create a fresh evaluator per call. Reusing
							// execution above preserves the explicit request context even
							// when the JVM case constructs a fresh executor object.
							var result Result
							var err error
							parameters := workJavaParameters(t, op.Parameters)
							if op.MaxRows != nil {
								result, err = ExecuteWithMaxRows(context.Background(), graph, op.Query, parameters, *op.MaxRows, options)
							} else {
								result, err = ExecuteWithOptions(context.Background(), graph, op.Query, parameters, -1, options)
							}
							if err != nil {
								if result.Columns != nil || result.Rows != nil {
									t.Errorf("failed operation exposed partial result: %#v", result)
								}
								return nil, err
							}
							return map[string]any{"columns": result.Columns, "rows": result.Rows}, nil
						case "concurrentConsume":
							return workConcurrentConsumes(execution, budget, op.Workers), nil
						default:
							return nil, fmt.Errorf("unknown oracle operation %q", op.Op)
						}
						return nil, nil
					})
					workCompareOutcome(t, "operation", operation, value, err)
					if err == nil {
						want := operation.Value
						if operation.Spec.Op == "reason" {
							want = workComparableReason(want)
						} else if operation.Spec.Op == "concurrentConsume" {
							want = workComparableWorkers(want)
						}
						workCompare(t, "value", value, want)
					}
					workCompare(t, "after", workSnapshot(record.Spec.Mode, budget, execution), workComparableSnapshot(operation.After))
				})
			}
			workCompare(t, "final state", workSnapshot(record.Spec.Mode, budget, execution), workComparableSnapshot(record.Final))
		})
	}
	if operations != expectedOperations {
		t.Errorf("actual main operation count = %d, want %d", operations, expectedOperations)
	}
}

type workOracleCase struct {
	Name string
	Spec struct {
		Mode, Budget, Fixture string
	}
	Construction workOracleOperation
	Operations   []workOracleOperation
	Final        map[string]any
}

type workOracleOperation struct {
	Spec struct {
		Op, Units, Kind, TimeoutMillis, Message, Query string
		Initial, Selected                              int64
		Conflicting, FreshExecutor                     bool
		Workers                                        int
		MaxRows                                        *int
		Parameters                                     map[string]any
	}
	Outcome       string
	Error         string
	Message       *string
	Before, After map[string]any
	Value         any
}

func workLong(t *testing.T, value string) int64 {
	t.Helper()
	number, err := strconv.ParseInt(value, 10, 64)
	if err != nil {
		t.Fatal(err)
	}
	return number
}

func workReadJSON(t *testing.T, filename string, target any) {
	t.Helper()
	data, err := os.ReadFile(filename)
	if err != nil {
		t.Fatal(err)
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber() // Preserve Long.MAX_VALUE exactly, including diagnostics.
	if err := decoder.Decode(target); err != nil {
		t.Fatal(err)
	}
}

func workJavaParameters(t *testing.T, input map[string]any) map[string]any {
	t.Helper()
	// WorkOracle uses Gson.fromJson(..., Map.class), which produces Double
	// parameter values. Keep long-valued tracker inputs/diagnostics exact via
	// UseNumber, but mirror that declared parameter conversion at execution.
	data, err := json.Marshal(input)
	if err != nil {
		t.Fatal(err)
	}
	var parameters map[string]any
	if err := json.Unmarshal(data, &parameters); err != nil {
		t.Fatal(err)
	}
	return parameters
}

func workComparableReason(value any) any {
	if value == nil {
		return nil
	}
	input := value.(map[string]any)
	result := map[string]any{"error": input["error"], "message": input["message"]}
	if same, ok := input["sameAsFirstCancellationReason"]; ok {
		result["sameAsFirstCancellationReason"] = same
	}
	return result
}

func workComparableSnapshot(input map[string]any) map[string]any {
	result := make(map[string]any, len(input))
	for key, value := range input {
		result[key] = value
	}
	result["cancellationReason"] = workComparableReason(input["cancellationReason"])
	return result
}

func workSnapshot(mode string, budget int64, execution *ExecutionContext) map[string]any {
	if execution == nil {
		availability := "no observable request context"
		if mode == "budget-only" {
			availability = "not exposed by budget-only constructor"
		}
		return map[string]any{"diagnostics": nil, "diagnosticsAvailability": availability, "remaining": nil, "cancelled": nil, "cancellationReason": nil}
	}
	diagnostics := execution.Diagnostics()
	return map[string]any{
		"diagnostics":        diagnostics,
		"remaining":          strconv.FormatInt(budget-diagnostics.WorkUnitsConsumed, 10),
		"cancelled":          execution.IsCancelled(),
		"cancellationReason": workErrorValue(execution.CancellationException()),
	}
}

func workErrorValue(err error) map[string]any {
	var failure *Error
	if errors.As(err, &failure) {
		return map[string]any{"error": failure.Class, "message": failure.JavaMessage()}
	}
	return map[string]any{"error": fmt.Sprintf("%T", err), "message": fmt.Sprint(err)}
}

func workAttempt(action func() (any, error)) (value any, err error) {
	defer func() {
		if failure := recover(); failure != nil {
			value = nil
			if typed, ok := failure.(error); ok {
				err = typed
			} else {
				err = fmt.Errorf("unexpected panic: %v", failure)
			}
		}
	}()
	return action()
}

func workCompareOutcome(t *testing.T, where string, expected workOracleOperation, value any, err error) {
	t.Helper()
	if expected.Outcome == "SUCCESS" {
		if err != nil {
			t.Errorf("%s: main success, Go error %v", where, err)
		}
		return
	}
	if expected.Outcome != "FAILED" || err == nil {
		t.Errorf("%s: main %s / %s, Go value %#v error %v", where, expected.Outcome, expected.Error, value, err)
		return
	}
	var message any
	if expected.Message != nil {
		message = *expected.Message
	}
	// Go exposes Java-compatible simple class/message fields. JVM-qualified
	// class names, stack frames and exception getter fields remain in the raw
	// oracle; they are not a second, invented Go exception surface.
	workCompare(t, where+" error", workErrorValue(err), map[string]any{"error": expected.Error, "message": message})
}

func workCompare(t *testing.T, where string, got, want any) {
	t.Helper()
	canonical := func(value any) any {
		data, err := json.Marshal(value)
		if err != nil {
			t.Fatal(err)
		}
		decoder := json.NewDecoder(bytes.NewReader(data))
		decoder.UseNumber()
		var decoded any
		if err := decoder.Decode(&decoded); err != nil {
			t.Fatal(err)
		}
		return decoded
	}
	got, want = canonical(got), canonical(want)
	if !reflect.DeepEqual(got, want) {
		actual, _ := json.Marshal(got)
		expected, _ := json.Marshal(want)
		t.Errorf("%s: Go %s; actual main %s", where, actual, expected)
	}
}

func workConcurrentConsumes(execution *ExecutionContext, budget int64, count int) map[string]any {
	var group sync.WaitGroup
	start := make(chan struct{})
	workers := make([]map[string]any, count)
	for index := 0; index < count; index++ {
		group.Add(1)
		go func(index int) {
			defer group.Done()
			<-start
			consumed := int64(0)
			var failure error
			for attempt := int64(0); attempt <= budget; attempt++ {
				_, failure = workAttempt(func() (any, error) { execution.consume(1); return nil, nil })
				if failure != nil {
					break
				}
				consumed++
			}
			workers[index] = map[string]any{"consumed": consumed, "error": workErrorValue(failure)}
		}(index)
	}
	close(start)
	group.Wait()
	total, exceeded := int64(0), 0
	failures := make([]any, count)
	for index, worker := range workers {
		total += worker["consumed"].(int64)
		failures[index] = worker["error"]
		if worker["error"].(map[string]any)["error"] == "CypherBudgetExceededException" {
			exceeded++
		}
	}
	return map[string]any{"successfulConsumes": total, "budgetExceededWorkers": exceeded, "workerErrors": failures}
}

func workComparableWorkers(value any) map[string]any {
	input := value.(map[string]any)
	workers := input["workers"].([]any)
	failures := make([]any, len(workers))
	for index, worker := range workers {
		failures[index] = workComparableReason(worker)
	}
	return map[string]any{"successfulConsumes": input["successfulConsumes"], "budgetExceededWorkers": input["budgetExceededWorkers"], "workerErrors": failures}
}

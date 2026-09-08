package query

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strconv"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

type persistedWorkFile struct {
	File   string `json:"file"`
	Bytes  int64  `json:"bytes"`
	SHA256 string `json:"sha256"`
}

type persistedWorkOracleCase struct {
	leadingWorkOracleCase
	FixtureBeforeLoad, FixtureAfterClose []persistedWorkFile
}

// Expectations come from the original actual-main executions. Fixture file
// manifests are compared to real Go bytes before loading and after closing;
// JVM exception stacks remain archived observer evidence.
// all public rows, simple error classes/messages, eight request diagnostics and
// the four mapped storage states and six persisted observers are compared at
// each operation boundary. Main's global persistenceBudgetDenied observation is
// archived and compared across original runs; Go explicitly exposes it as nil.
// After Close, Go's public storage observer rejects access; main's private observer
// fields remain archived and are compared between the two original JVM runs.
func TestPersistedWorkAccountingMainOracle(t *testing.T) {
	testPersistedWorkOracle(t, "../../../docs/go-server-baseline/native-persisted-work-accounting", 52, 187, 352, false, false)
}

func TestMappedWorkAccountingMainOracle(t *testing.T) {
	testPersistedWorkOracle(t, "../../../docs/go-server-baseline/native-persisted-work-accounting/mapped-oracle", 44, 220, 386, true, false)
}

func TestMappedWorkLargeAccountingMainOracle(t *testing.T) {
	testPersistedWorkOracle(t, "../../../docs/go-server-baseline/native-persisted-work-accounting/mapped-oracle/large-oracle", 7, 35, 67, true, false)
}

func TestBuildTrigramWorkAccountingMainOracle(t *testing.T) {
	testPersistedWorkOracle(t, "../../../docs/go-server-baseline/native-persisted-work-accounting/build-trigram-oracle", 8, 40, 33, true, true)
}

func testPersistedWorkOracle(t *testing.T, dir string, expectedCases, expectedOperations, expectedFiles int, mappedObserver, buildObserver bool) {
	t.Helper()
	var first, repeat struct{ Cases []persistedWorkOracleCase }
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
				t.Fatal("persisted-work fixture requires successful context construction")
			}
			sources := []Graph{}
			var prelude *store.Store
			snapshot := func() map[string]any {
				state := persistedWorkSnapshot(t, budget, work, sources, mappedObserver, buildObserver)
				state["preludeLoaded"] = prelude != nil
				return state
			}
			compareState := func(where string, got, original, repeated map[string]any) {
				t.Helper()
				want := persistedWorkComparableSnapshot(t, original, mappedObserver, buildObserver)
				workCompare(t, where+" complete repeated JVM state", repeated, original)
				workCompare(t, where, got, want)
			}
			compareState("construction state", snapshot(), record.Construction.After, other.Construction.After)
			directories := map[string]string{}
			for index, source := range record.Spec.Sources {
				directories[fmt.Sprintf("store%d", index)] = ordinaryCopyFixture(t, filepath.Join(fixtures, source.Fixture))
			}
			if record.Spec.Prelude != nil {
				directories["prelude"] = ordinaryCopyFixture(t, filepath.Join(fixtures, record.Spec.Prelude.Fixture))
			}
			workCompare(t, "repeated fixture before load", other.FixtureBeforeLoad, record.FixtureBeforeLoad)
			workCompare(t, "real fixture before load", persistedWorkFiles(t, directories), record.FixtureBeforeLoad)
			open := func(directory string) *store.Store {
				t.Helper()
				graph, err := store.OpenMode(directory, "MAPPED")
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { graph.Close() })
				return graph
			}
			// Each source gets an independent store, including repeated empty
			// variants. Context replacement must preserve these exact objects.
			for index, source := range record.Spec.Sources {
				sources = append(sources, Graph{ID: source.GraphID, Store: open(directories[fmt.Sprintf("store%d", index)])})
			}
			if len(sources) == 0 {
				t.Fatal("actual persisted-work oracle requires at least one source")
			}
			if record.Spec.Prelude != nil {
				prelude = open(directories["prelude"])
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
							t.Fatalf("unhandled actual persisted-work operation %q", op.Op)
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
			// Main records final after closing every graph. Its private storage
			// getters remain callable after close, whereas Go's public observer
			// returns ErrStoreClosed. Do not substitute invented zero cache state
			// or compare the last live-store snapshot against closed main stores.
			closeStore := func(name string, graph *store.Store) {
				t.Helper()
				if err := graph.Close(); err != nil {
					t.Errorf("close %s: %v", name, err)
				}
				if _, err := graph.StringPropertyIndexes(context.Background()); !errors.Is(err, store.ErrStoreClosed) {
					t.Errorf("%s storage observer after Close: got %v, want ErrStoreClosed", name, err)
				}
				if _, err := graph.PersistedIndexState(context.Background()); !errors.Is(err, store.ErrStoreClosed) {
					t.Errorf("%s persisted observer after Close: got %v, want ErrStoreClosed", name, err)
				}
			}
			for _, source := range sources {
				closeStore(source.ID, source.Store)
			}
			if prelude != nil {
				closeStore("prelude", prelude)
			}
			workCompare(t, "repeated fixture after Close", other.FixtureAfterClose, record.FixtureAfterClose)
			workCompare(t, "real fixture after Close", persistedWorkFiles(t, directories), record.FixtureAfterClose)
			workCompare(t, "complete repeated JVM final including private closed-storage observers", other.Final, record.Final)
			final := workSnapshot("context", budget, work)
			final["preludeLoaded"] = prelude != nil
			wantFinal := workComparableSnapshot(record.Final)
			delete(wantFinal, "storage") // No public Go post-close storage state exists.
			workCompare(t, "post-close request and prelude state", final, wantFinal)
		})
	}
	if operations != expectedOperations {
		t.Errorf("actual JVM operations = %d, want %d", operations, expectedOperations)
	}
}

func persistedWorkFiles(t *testing.T, directories map[string]string) []persistedWorkFile {
	t.Helper()
	files := []persistedWorkFile{}
	for name, directory := range directories {
		err := filepath.WalkDir(directory, func(path string, entry fs.DirEntry, err error) error {
			if err != nil {
				return err
			}
			if entry.IsDir() {
				return nil
			}
			if !entry.Type().IsRegular() {
				return fmt.Errorf("unexpected nonregular fixture entry %s", path)
			}
			contents, err := os.ReadFile(path)
			if err != nil {
				return err
			}
			relative, err := filepath.Rel(directory, path)
			if err != nil {
				return err
			}
			files = append(files, persistedWorkFile{
				File: filepath.ToSlash(filepath.Join(name, relative)), Bytes: int64(len(contents)),
				SHA256: fmt.Sprintf("%x", sha256.Sum256(contents)),
			})
			return nil
		})
		if err != nil {
			t.Fatal(err)
		}
	}
	sort.Slice(files, func(i, j int) bool { return files[i].File < files[j].File })
	return files
}

func persistedWorkSnapshot(t *testing.T, budget int64, work *ExecutionContext, sources []Graph, mappedObserver, buildObserver bool) map[string]any {
	t.Helper()
	state := rawWorkSnapshot(t, budget, work, sources)
	for index, source := range sources {
		persisted, err := source.Store.PersistedIndexState(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		if persisted.PersistenceBudgetDenied != nil {
			t.Fatal("Go's unimplemented global reservation budget must remain explicitly unavailable")
		}
		if mappedObserver {
			state["storage"].([]any)[index].(map[string]any)["mappedViewUnavailable"] = persisted.MappedViewUnavailable
		}
		if buildObserver {
			state["storage"].([]any)[index].(map[string]any)["buildTrigram"] = map[string]any{
				"metadataReady":         persisted.TrigramMetadataReady,
				"postingsReady":         persisted.TrigramPostingsReady,
				"metadataArraysPresent": persisted.TrigramMetadataArraysPresent,
			}
		}
		state["storage"].([]any)[index].(map[string]any)["persisted"] = map[string]any{
			"retainPreference":       persisted.RetainPreference,
			"loadedFromPersistence":  persisted.LoadedFromPersistence,
			"stringIdentityCached":   persisted.StringIdentityCached,
			"matchingStringIdsCount": persisted.MatchingStringIDsCount,
			"matchingNodeIdsCount":   persisted.MatchingNodeIDsCount,
			"projectedRowsCount":     persisted.ProjectedRowsCount,
		}
	}
	return state
}

func persistedWorkComparableSnapshot(t *testing.T, input map[string]any, mappedObserver, buildObserver bool) map[string]any {
	t.Helper()
	// Reuse the existing four-state mapping without changing either original
	// capture. Unknown storage or persisted fields fail rather than disappearing.
	base := workComparableSnapshot(input)
	storage := []any{}
	persistedStates := []map[string]any{}
	mappedStates := []bool{}
	buildStates := []map[string]any{}
	for _, raw := range input["storage"].([]any) {
		row := map[string]any{}
		for key, value := range raw.(map[string]any) {
			if buildObserver && key == "buildTrigram" {
				continue
			}
			if mappedObserver && key == "mappedViewUnavailable" {
				continue
			}
			if key != "persisted" {
				row[key] = value
			}
		}
		if buildObserver {
			build, ok := raw.(map[string]any)["buildTrigram"].(map[string]any)
			if !ok || len(build) != 3 {
				t.Fatalf("expected three original build-trigram observers, got %#v", raw)
			}
			for key, value := range build {
				switch key {
				case "metadataReady", "postingsReady", "metadataArraysPresent":
					if _, ok := value.(bool); value != nil && !ok {
						t.Fatalf("expected nullable build-trigram boolean %s, got %#v", key, value)
					}
				default:
					t.Fatalf("unmapped JVM build-trigram observer %q", key)
				}
			}
			buildStates = append(buildStates, build)
		}
		if mappedObserver {
			unavailable, ok := raw.(map[string]any)["mappedViewUnavailable"].(bool)
			if !ok {
				t.Fatalf("expected actual mapped-view unavailable boolean, got %#v", raw)
			}
			mappedStates = append(mappedStates, unavailable)
		}
		persisted, ok := raw.(map[string]any)["persisted"].(map[string]any)
		if !ok || len(persisted) != 7 {
			t.Fatalf("expected seven original persisted observers, got %#v", raw)
		}
		mapped := map[string]any{}
		for key, value := range persisted {
			switch key {
			case "retainPreference", "loadedFromPersistence", "stringIdentityCached", "matchingStringIdsCount", "matchingNodeIdsCount", "projectedRowsCount":
				mapped[key] = value
			case "persistenceBudgetDenied":
				// This main-global reservation observer has no Go equivalent.
				// The original first/repeat snapshots are still compared in full.
			default:
				t.Fatalf("unmapped JVM persisted observer %q", key)
			}
		}
		storage = append(storage, row)
		persistedStates = append(persistedStates, mapped)
	}
	base["storage"] = storage
	result := rawWorkComparableSnapshot(t, base)
	for index, raw := range result["storage"].([]any) {
		raw.(map[string]any)["persisted"] = persistedStates[index]
		if mappedObserver {
			raw.(map[string]any)["mappedViewUnavailable"] = mappedStates[index]
		}
		if buildObserver {
			raw.(map[string]any)["buildTrigram"] = buildStates[index]
		}
	}
	return result
}

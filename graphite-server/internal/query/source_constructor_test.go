package query

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// Scalar JVM classes distinguish numeric values that JSON alone conflates.
// Map/List implementation classes have no Go equivalent: compare their public
// container kind and complete contents, while retaining both raw runtime types.
func sourceConstructorMainType(t *testing.T, value any) any {
	t.Helper()
	switch value {
	case nil:
		return nil
	case "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.lang.Boolean", "java.lang.String":
		return value
	case "java.util.LinkedHashMap", "java.util.HashMap", "kotlin.collections.builders.MapBuilder", "java.util.Collections$EmptyMap", "java.util.Collections$SingletonMap":
		return "map"
	case "java.util.ArrayList", "java.util.Arrays$ArrayList", "java.util.Collections$SingletonList", "java.util.Collections$EmptyList", "kotlin.collections.EmptyList":
		return "list"
	default:
		t.Fatalf("unclassified actual-main public value type %v", value)
		return nil
	}
}

func sourceConstructorGoType(t *testing.T, value any) any {
	t.Helper()
	switch value.(type) {
	case nil:
		return nil
	case int32:
		return "java.lang.Integer"
	case int64:
		return "java.lang.Long"
	case float32:
		return "java.lang.Float"
	case float64:
		return "java.lang.Double"
	case bool:
		return "java.lang.Boolean"
	case string:
		return "java.lang.String"
	case OutputObject:
		return "map"
	}
	switch reflect.TypeOf(value).Kind() {
	case reflect.Map:
		return "map"
	case reflect.Slice, reflect.Array:
		return "list"
	}
	t.Fatalf("unclassified Go public value type %T", value)
	return nil
}

func sourceConstructorState(t *testing.T, graphs []*store.Store) any {
	t.Helper()
	out := []map[string]any{}
	for i, graph := range graphs {
		state, err := graph.StringPropertyIndexes(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		out = append(out, map[string]any{"store": i, "retained": state.Retained, "mappedView": state.MappedView})
	}
	data, err := json.Marshal(out)
	if err != nil {
		t.Fatal(err)
	}
	var normalized any
	if err := json.Unmarshal(data, &normalized); err != nil {
		t.Fatal(err)
	}
	return normalized
}

func TestSourceConstructorActualMain(t *testing.T) {
	root := "../../../docs/go-server-baseline/native-source-constructor"
	var specs, oracle []map[string]any
	readDistinctJSON(t, filepath.Join(root, "cases.json"), &specs)
	readDistinctJSON(t, filepath.Join(root, "main.json"), &oracle)
	if len(specs) != 298 || len(oracle) != len(specs) {
		t.Fatalf("incomplete constructor oracle specs=%d observations=%d", len(specs), len(oracle))
	}
	seenNames := map[string]bool{}
	outputs := []map[string]any{}
	for i, want := range oracle {
		spec := specs[i]
		name := spec["name"].(string)
		if seenNames[name] {
			t.Fatalf("duplicate oracle case %q", name)
		}
		seenNames[name] = true
		if want["name"] != spec["name"] || !reflect.DeepEqual(want["spec"], spec) {
			t.Fatalf("constructor inventory differs at %d", i)
		}
		t.Run(spec["name"].(string), func(t *testing.T) {
			fixture := "testdata/candidate-index/clean"
			switch spec["fixture"] {
			case "clean":
			case "traversal":
				fixture = "testdata/traversal"
			case "all-types":
				fixture = "testdata/main-string-source/all-types"
			default:
				t.Fatalf("unknown fixture %v", spec["fixture"])
			}
			graphs := []*store.Store{}
			defer func() {
				for _, graph := range graphs {
					graph.Close()
				}
			}()
			for n := 0; n < int(spec["physicalStores"].(float64)); n++ {
				dir := t.TempDir()
				entries, err := os.ReadDir(fixture)
				if err != nil {
					t.Fatal(err)
				}
				for _, entry := range entries {
					if entry.IsDir() {
						continue
					}
					data, err := os.ReadFile(filepath.Join(fixture, entry.Name()))
					if err != nil {
						t.Fatal(err)
					}
					if err := os.WriteFile(filepath.Join(dir, entry.Name()), data, 0600); err != nil {
						t.Fatal(err)
					}
				}
				graph, err := store.OpenMode(dir, "MAPPED")
				if err != nil {
					t.Fatal(err)
				}
				graphs = append(graphs, graph)
			}
			before := sourceConstructorState(t, graphs)
			if !reflect.DeepEqual(before, want["before"]) {
				t.Errorf("before state got=%v want=%v", before, want["before"])
			}
			ctx := context.Background()
			switch spec["cancellation"] {
			case "live":
			case "cancelled":
				var cancel context.CancelFunc
				ctx, cancel = context.WithCancel(ctx)
				cancel()
			case "timeout":
				var cancel context.CancelCauseFunc
				ctx, cancel = context.WithCancelCause(ctx)
				// This is the input signal.cancel(CypherQueryTimeoutException(17)), not
				// an output normalization or an inferred request deadline.
				cancel(&Error{Class: "CypherQueryTimeoutException", Message: "Cypher query timed out after 17 ms"})
			default:
				t.Fatalf("unknown cancellation input %v", spec["cancellation"])
			}
			sources := []Graph{}
			ids := spec["graphIDs"].([]any)
			indexes := spec["storeIndexes"].([]any)
			if len(ids) != len(indexes) {
				t.Fatal("invalid source mapping")
			}
			for n, id := range ids {
				index := int(indexes[n].(float64))
				var graph *store.Store
				if index >= 0 {
					graph = graphs[index]
				}
				sources = append(sources, Graph{ID: id.(string), Store: graph})
			}
			result, err := ExecuteCrossWithOptions(ctx, sources, spec["query"].(string), nil, -1, ExecutionOptions{SourceScopeApplied: spec["scoped"].(bool), WorkTrackingEnabled: true})
			got := distinctOracleResult(map[string]any{"name": want["name"], "spec": spec}, result, err)
			got["before"] = before
			got["after"] = sourceConstructorState(t, graphs)
			got["nativeErrorType"] = fmt.Sprintf("%T", err)
			if err != nil {
				got["nativeErrorMessage"] = err.Error()
			}
			got["nativeErrorIsCanceled"] = errors.Is(err, context.Canceled)
			got["nativeErrorIsDeadline"] = errors.Is(err, context.DeadlineExceeded)
			if ctx.Err() != nil {
				got["inputContextError"] = ctx.Err().Error()
				got["inputContextCauseType"] = fmt.Sprintf("%T", context.Cause(ctx))
				got["inputContextCauseMessage"] = context.Cause(ctx).Error()
			}
			got["outcome"] = "SUCCESS"
			if err != nil {
				got["outcome"] = "FAILED"
			}
			actualTypes := []map[string]any{}
			nativeTypes := []map[string]any{}
			if err == nil {
				for _, row := range result.Rows {
					types := map[string]any{}
					native := map[string]any{}
					for _, column := range result.Columns {
						types[column] = sourceConstructorGoType(t, row[column])
						native[column] = fmt.Sprintf("%T", row[column])
					}
					actualTypes = append(actualTypes, types)
					nativeTypes = append(nativeTypes, native)
				}
				got["publicTypes"] = actualTypes
				got["nativeValueTypes"] = nativeTypes
			}
			outputs = append(outputs, got)
			for _, field := range []string{"outcome", "columns", "rows", "error", "message", "after"} {
				if !reflect.DeepEqual(got[field], want[field]) {
					t.Errorf("%s got=%#v want=%#v", field, got[field], want[field])
				}
			}
			if err == nil && want["outcome"] == "SUCCESS" {
				expectedTypes := []map[string]any{}
				for _, row := range want["types"].([]any) {
					types := map[string]any{}
					for column, value := range row.(map[string]any) {
						types[column] = sourceConstructorMainType(t, value)
					}
					expectedTypes = append(expectedTypes, types)
				}
				if !reflect.DeepEqual(actualTypes, expectedTypes) {
					t.Errorf("public value types got=%v want=%v (main raw=%v)", actualTypes, expectedTypes, want["types"])
				}
			}
			if want["error"] == "CypherQueryCancelledException" || want["error"] == "CypherQueryTimeoutException" {
				if !errors.Is(err, context.Canceled) {
					t.Errorf("public cancellation must preserve errors.Is(context.Canceled), got=%T %v", err, err)
				}
			}
		})
	}
	if len(outputs) != len(oracle) {
		t.Errorf("native output inventory incomplete: %d / %d", len(outputs), len(oracle))
	}
	if output := os.Getenv("SOURCE_CONSTRUCTOR_OUTPUT"); output != "" {
		data, err := json.MarshalIndent(outputs, "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(output, append(data, '\n'), 0600); err != nil {
			t.Fatal(err)
		}
	}
}

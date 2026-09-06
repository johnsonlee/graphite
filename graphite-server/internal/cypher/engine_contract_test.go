package cypher_test

import (
	"context"
	"encoding/json"
	"github.com/johnsonlee/graphite/graphite-server/internal/query"
	"os"
	"reflect"
	"testing"
)

// This external-package test proves the parser's statement flattening reaches
// execution with the same concrete results as actual main JVM classes. The
// fixture is generated offline by testdata/ParserOracle.java, never by the
// measured64-graph server.
func TestStatementExecutionMatchesSourceOnlyJVMOracle(t *testing.T) {
	data, err := os.ReadFile("testdata/statement-jvm-oracle.json")
	if err != nil {
		t.Fatal(err)
	}
	var corpus struct {
		Cases []struct {
			Query, ParseError, ExecutionError string
			Columns                           []string
			Rows                              []map[string]any
		} `json:"cases"`
	}
	if err = json.Unmarshal(data, &corpus); err != nil {
		t.Fatal(err)
	}
	for i, c := range corpus.Cases {
		result, err := query.Execute(context.Background(), nil, c.Query, nil, -1)
		if c.ParseError != "" || c.ExecutionError != "" {
			if err == nil {
				t.Fatalf("case%d must fail, got %#v", i, result)
			}
			continue
		}
		if err != nil {
			t.Fatalf("case%d failed: %v", i, err)
		}
		raw, err := json.Marshal(result)
		if err != nil {
			t.Fatal(err)
		}
		var actual query.Result
		if err = json.Unmarshal(raw, &actual); err != nil {
			t.Fatal(err)
		}
		if !reflect.DeepEqual(actual.Columns, c.Columns) || !reflect.DeepEqual(actual.Rows, c.Rows) {
			t.Fatalf("case%d query%q\ngot%#v\nwant columns%v rows%v", i, c.Query, actual, c.Columns, c.Rows)
		}
	}
}

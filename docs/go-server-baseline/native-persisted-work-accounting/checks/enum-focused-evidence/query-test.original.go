package query

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// These are original Java public-executor observations, including failures.
// The small persisted all-types fixture is for correctness only. In particular,
// it supplies no latency or allocation evidence about the production 64 graphs.
func TestGenericDisjunctionMainReadSemantics(t *testing.T) {
	oracleDir := "../../../docs/go-server-baseline/native-generic-string-disjunction"
	var oracle struct {
		Cases []struct {
			Name string
			Spec struct {
				Query      string
				Parameters map[string]any
				Fixtures   []string
				Scoped     bool
			}
			Outcome string
			Columns []string
			Rows    []map[string]any
			Error   string
			Message *string
		}
	}
	readDistinctJSON(t, filepath.Join(oracleDir, "main.json"), &oracle)
	if len(oracle.Cases) != 201 {
		t.Fatalf("original Java oracle denominator = %d, want 201", len(oracle.Cases))
	}
	fixtures := genericDisjunctionFixtures(t, filepath.Join(oracleDir, "fixtures.tar.gz"), 588)
	wanted := map[string]bool{}
	for _, name := range []string{"original-886", "original-887", "original-888"} {
		wanted[name] = true
	}
	// Positive hits assert projected content, canonical row order, duplicate
	// elimination and two-source provenance, while the absent needle asserts
	// that all concrete generic providers can exhaust without decoding a hit.
	for _, term := range []string{"red", "x", "field", "example", "deprecated", "run", "missing"} {
		for _, count := range []string{"1", "2"} {
			wanted["six-"+term+"-"+count] = true
		}
	}
	for _, kind := range []string{"enum", "local", "field"} {
		for _, mutation := range []string{"payload-tag", "payload-id", "offset-missing", "offset-negative", "offset-wrapped-valid", "offset-wrapped-oob"} {
			for _, hit := range []string{"hit", "miss"} {
				wanted[kind+"-"+mutation+"-"+hit] = true
			}
		}
	}
	// Full malformed Enum argument-count decoding and truncated Field loading
	// remain separately recorded gaps in the complete 201-case replay. These
	// cases isolate the raw-filter skip correction and a full-read control.
	for _, name := range []string{"enum-bad-tail-miss", "enum-bad-tail-global-miss", "local-bad-tail-hit", "local-bad-tail-miss", "local-bad-tail-global-miss"} {
		wanted[name] = true
	}
	for _, kind := range []string{"enum", "local"} {
		for _, sid := range []string{"0", "1"} {
			for _, hit := range []string{"hit", "miss", "global-miss"} {
				wanted[kind+"-bad-sid-"+sid+"-"+hit] = true
			}
		}
	}
	// A matching class short-circuits the second predicate. Its invalid name
	// then fails during full deserialization (list error), whereas a class miss
	// reaches the raw match-state lookup and fails with an array error.
	for _, sid := range []string{"0", "1", "2"} {
		for _, hit := range []string{"hit", "miss", "global-miss"} {
			wanted["field-bad-sid-"+sid+"-"+hit] = true
		}
	}
	// LIMIT 1 must neither read the corrupt second source after a clean first
	// result, nor hide a fault in the first source by using the second source.
	for _, reverse := range []string{"False", "True"} {
		for _, scoped := range []string{"False", "True"} {
			wanted["cross-field-bad-sid-1-"+reverse+"-"+scoped] = true
		}
	}
	const expectedCases = 83
	if len(wanted) != expectedCases {
		t.Fatalf("test selection denominator = %d, want %d", len(wanted), expectedCases)
	}
	seen := map[string]bool{}
	for _, record := range oracle.Cases {
		if !wanted[record.Name] {
			continue
		}
		if seen[record.Name] {
			t.Fatalf("duplicate oracle case %q", record.Name)
		}
		seen[record.Name] = true
		t.Run(record.Name, func(t *testing.T) {
			sources := make([]Graph, len(record.Spec.Fixtures))
			for i, variant := range record.Spec.Fixtures {
				// Each source gets a fresh copy; queries may create index sidecars.
				dir := ordinaryCopyFixture(t, filepath.Join(fixtures, variant))
				graph, err := store.OpenMode(dir, "MAPPED")
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { graph.Close() })
				id := "single"
				if len(sources) > 1 {
					id = []string{"z-first", "a-second"}[i]
				}
				sources[i] = Graph{ID: id, Store: graph}
			}
			options := ExecutionOptions{SourceScopeApplied: record.Spec.Scoped, WorkTrackingEnabled: true}
			var result Result
			var err error
			if len(sources) == 1 {
				result, err = executeSources(context.Background(), sources[0].Store, nil, false, record.Spec.Query, record.Spec.Parameters, -1, options)
			} else {
				result, err = ExecuteCrossWithOptions(context.Background(), sources, record.Spec.Query, record.Spec.Parameters, -1, options)
			}
			if record.Outcome == "FAILED" {
				var failure *Error
				if !errors.As(err, &failure) {
					t.Fatalf("main error %s, Go result %#v, error %v", record.Error, result, err)
				}
				var wantMessage any
				if record.Message != nil {
					wantMessage = *record.Message
				}
				if failure.Class != record.Error || !reflect.DeepEqual(failure.JavaMessage(), wantMessage) {
					t.Fatalf("main error %s / %#v, Go error %s / %#v", record.Error, wantMessage, failure.Class, failure.JavaMessage())
				}
				if result.Columns != nil || result.Rows != nil {
					t.Fatalf("failed response exposed partial result: %#v", result)
				}
				return
			}
			if record.Outcome != "SUCCESS" || err != nil {
				t.Fatalf("main outcome %q, Go error %v", record.Outcome, err)
			}
			// JSON gives integer values the oracle's numeric representation. No
			// rows or columns are sorted, removed or otherwise canonicalized.
			encoded, err := json.Marshal(result)
			if err != nil {
				t.Fatal(err)
			}
			var normalized Result
			if err := json.Unmarshal(encoded, &normalized); err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(normalized.Columns, record.Columns) || !reflect.DeepEqual(normalized.Rows, record.Rows) {
				t.Fatalf("main columns %#v rows %#v; Go %s", record.Columns, record.Rows, encoded)
			}
		})
	}
	if len(seen) != expectedCases {
		for name := range wanted {
			if !seen[name] {
				t.Errorf("selected case absent from original Java oracle: %s", name)
			}
		}
	}
}

func genericDisjunctionFixtures(t *testing.T, archive string, expectedFiles int) string {
	t.Helper()
	file, err := os.Open(archive)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	compressed, err := gzip.NewReader(file)
	if err != nil {
		t.Fatal(err)
	}
	defer compressed.Close()
	reader := tar.NewReader(compressed)
	dir := t.TempDir()
	files := 0
	for {
		header, err := reader.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
		name := filepath.Clean(header.Name)
		if filepath.IsAbs(name) || name == ".." || strings.HasPrefix(name, ".."+string(filepath.Separator)) || header.Typeflag != tar.TypeReg {
			t.Fatalf("unexpected fixture archive entry %q, type %d", header.Name, header.Typeflag)
		}
		destination := filepath.Join(dir, name)
		if err := os.MkdirAll(filepath.Dir(destination), 0700); err != nil {
			t.Fatal(err)
		}
		data, err := io.ReadAll(reader)
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(destination, data, 0600); err != nil {
			t.Fatal(err)
		}
		files++
	}
	if files != expectedFiles {
		t.Fatalf("fixture archive denominator = %d, want %d", files, expectedFiles)
	}
	return dir
}

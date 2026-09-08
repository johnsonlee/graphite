package benchmarkcase

import (
	"bytes"
	"encoding/json"
	"os"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/query"
)

func TestCanonicalActualMain64Results(t *testing.T) {
	data, err := os.ReadFile("testdata/main-canonical.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Cases []struct {
			ID           string
			Columns      []string
			Rows         []map[string]any
			Canonical    string
			NumericClass *string
		}
	}
	d := json.NewDecoder(bytes.NewReader(data))
	d.UseNumber()
	if err := d.Decode(&fixture); err != nil {
		t.Fatal(err)
	}
	if len(fixture.Cases) != 5 {
		t.Fatal("incomplete actual-main canonical controls")
	}
	for _, c := range fixture.Cases {
		t.Run(c.ID, func(t *testing.T) {
			var typed func(any) any
			typed = func(v any) any {
				switch x := v.(type) {
				case json.Number:
					if c.NumericClass == nil {
						t.Fatal("unexpected numeric value")
					}
					n, err := x.Int64()
					if err != nil {
						t.Fatal(err)
					}
					if *c.NumericClass == "java.lang.Integer" {
						return int32(n)
					}
					if *c.NumericClass == "java.lang.Long" {
						return n
					}
					t.Fatal("unsupported control numeric class", *c.NumericClass)
				case map[string]any:
					for k, v := range x {
						x[k] = typed(v)
					}
				case []any:
					for i, v := range x {
						x[i] = typed(v)
					}
				}
				return v
			}
			for _, row := range c.Rows {
				typed(row)
			}
			r := query.Result{Columns: c.Columns, Rows: c.Rows}
			actual, err := CanonicalResult(r)
			if err != nil {
				t.Fatal(err)
			}
			if actual != c.Canonical {
				t.Fatalf("actual main canonical differs for %s", c.ID)
			}
			if len(r.Rows) > 1 {
				r.Rows[0], r.Rows[len(r.Rows)-1] = r.Rows[len(r.Rows)-1], r.Rows[0]
				reordered, err := CanonicalResult(r)
				if err != nil {
					t.Fatal(err)
				}
				if reordered == actual {
					t.Fatal("canonicalization erased result row order")
				}
			}
		})
	}
}

package query

import (
	"encoding/json"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"math"
	"os"
	"strconv"
	"testing"
)

func TestJava17FloatSpellings(t *testing.T) {
	data, err := os.ReadFile("testdata/jvm17-float-spellings.json")
	if err != nil {
		t.Fatal(err)
	}
	var corpus struct {
		Cases []struct {
			Width    int    `json:"width"`
			Bits     string `json:"bits"`
			Expected string `json:"expected"`
		} `json:"cases"`
	}
	if err = json.Unmarshal(data, &corpus); err != nil {
		t.Fatal(err)
	}
	failures := 0
	for _, c := range corpus.Cases {
		bits, err := strconv.ParseUint(c.Bits, 16, 64)
		if err != nil {
			t.Fatal(err)
		}
		value := math.Float64frombits(bits)
		if c.Width == 32 {
			value = float64(math.Float32frombits(uint32(bits)))
		}
		got := javaFloatString(value, c.Width)
		if got != c.Expected {
			t.Errorf("width%d bits%s got%s want%s", c.Width, c.Bits, got, c.Expected)
			failures++
			if failures >= 20 {
				t.Fatal("stopping after20 mismatches")
			}
		}
	}
}
func TestArithmeticAgainstCapturedJVMValues(t *testing.T) {
	data, err := os.ReadFile("../cypher/testdata/arithmetic-jvm-oracle.json")
	if err != nil {
		t.Fatal(err)
	}
	var corpus struct {
		Cases []struct {
			Name, Query string
			Status      int
			Response    struct {
				Rows []map[string]any `json:"rows"`
			} `json:"response"`
		} `json:"cases"`
	}
	if err = json.Unmarshal(data, &corpus); err != nil {
		t.Fatal(err)
	}
	for _, c := range corpus.Cases {
		t.Run(c.Name, func(t *testing.T) {
			if c.Status != 200 {
				if c.Name != "integer-mod-zero" {
					t.Fatalf("unhandled non200 oracle %s", c.Name)
				}
				r := execute(t, c.Query, nil)
				if v, ok := r.Rows[0]["value"].(float64); !ok || !math.IsNaN(v) {
					t.Fatalf("expected NaN to cause JSON encoding error, got %#v", r)
				}
				if _, err := json.Marshal(r); err == nil {
					t.Fatal("nonfinite result unexpectedly serializable")
				}
				return
			}
			r := execute(t, c.Query, nil)
			if got, want := r.Rows[0]["value"], c.Response.Rows[0]["value"]; got != want {
				t.Fatalf("%s got %#v want %#v", c.Query, got, want)
			}
		})
	}
}
func TestNumericLiteralWidthAndUnaryType(t *testing.T) {
	r := execute(t, "RETURN 1 AS small, 2147483648 AS large, 0x1 AS hex, 0o1 AS octal, -1 AS negative, +1 AS positive, size('ab') AS size", nil)
	row := r.Rows[0]
	for _, k := range []string{"small", "negative", "positive", "size"} {
		if _, ok := row[k].(int32); !ok {
			t.Fatalf("%s must retain JVM Int width, got %T", k, row[k])
		}
	}
	for _, k := range []string{"large", "hex", "octal"} {
		if _, ok := row[k].(int64); !ok {
			t.Fatalf("%s must retain JVM Long width, got %T", k, row[k])
		}
	}
}

func TestSortAndPredicateUseDifferentNodeOrders(t *testing.T) {
	a := qualifiedNode{GraphID: "orders", Node: store.Node{ID: 2}}
	b := qualifiedNode{GraphID: "orders", Node: store.Node{ID: 10}}
	if compare(a, b) >= 0 || comparePredicate(a, b) <= 0 {
		t.Fatal("sort must compare numeric IDs; relational predicate must compare elementId strings")
	}
	first := qualifiedNode{GraphID: "billing", Node: store.Node{ID: 100}}
	if compare(first, a) >= 0 {
		t.Fatal("graph ID must precede numeric node ID in sorting")
	}
}
func TestMethodOrderingUsesKotlinValueRendering(t *testing.T) {
	noArgs := qualifiedMethod{GraphID: "orders", Method: store.MethodDescriptor{DeclaringClass: "pkg.C", Name: "run", ReturnType: "void"}}
	withArg := noArgs
	withArg.Method.ParameterTypes = []string{"int"}
	if compare(withArg, noArgs) >= 0 {
		t.Fatal("Kotlin MethodValue rendering sorts a typed parameter before an empty parameter list; signature ordering would be wrong")
	}
}
func TestJVMStringAndFloatingSortOrder(t *testing.T) {
	if compare("😀", "\uE000") >= 0 {
		t.Fatal("JVM UTF-16 strings sort astral surrogate before U+E000")
	}
	if compare(math.Copysign(0, -1), 0.0) >= 0 {
		t.Fatal("JVM floating sort must distinguish negative zero")
	}
	if comparePredicate(math.Copysign(0, -1), 0.0) != 0 {
		t.Fatal("decimal numeric relational comparison treats signed zero equally")
	}
}

package javamath

import (
	"compress/gzip"
	"encoding/csv"
	"math"
	"os"
	"strconv"
	"testing"
)

func TestJava17MathBits(t *testing.T) {
	f, e := os.Open("testdata/java17-arm64.tsv.gz")
	if e != nil {
		t.Fatal(e)
	}
	defer f.Close()
	z, e := gzip.NewReader(f)
	if e != nil {
		t.Fatal(e)
	}
	defer z.Close()
	r := csv.NewReader(z)
	r.Comma = '\t'
	rows, e := r.ReadAll()
	if e != nil {
		t.Fatal(e)
	}
	if len(rows) != 30321 {
		t.Fatalf("oracle has %d rows; want header plus 30320 cases", len(rows))
	}
	bits := func(s string) uint64 {
		t.Helper()
		n, err := strconv.ParseUint(s, 16, 64)
		if err != nil {
			t.Fatal(err)
		}
		return n
	}
	functions := map[string]func(float64) float64{"sin": Sin, "cos": Cos, "exp": Exp, "tan": Tan, "asin": Asin, "acos": Acos, "atan": Atan, "log": Log, "log10": Log10, "sqrt": Sqrt}
	for _, row := range rows[1:] {
		fn, ok := functions[row[0]]
		if !ok && row[0] != "atan2" {
			t.Fatalf("unknown oracle function %q", row[0])
		}
		xb, yb, want := bits(row[1]), bits(row[2]), bits(row[3])
		if want != bits(row[5]) {
			t.Fatalf("oracle target changed after warm calls: %v", row)
		}
		x, y := math.Float64frombits(xb), math.Float64frombits(yb)
		var actual float64
		if row[0] == "atan2" {
			actual = Atan2(x, y)
		} else {
			actual = fn(x)
		}
		got := math.Float64bits(actual)
		if math.IsNaN(actual) && math.IsNaN(math.Float64frombits(want)) {
			continue
		}
		if got != want {
			t.Errorf("%s(%016x,%016x): want %016x got %016x", row[0], xb, yb, want, got)
		}
	}
}

func TestCypherScalarRegressions(t *testing.T) {
	if got := math.Float64bits(Exp(1)); got != 0x4005bf0a8b14576a {
		t.Errorf("exp(1) bits = %016x", got)
	}
	if got := math.Float64bits(1 / Tan(1)); got != 0x3fe48c05d04e1cfd {
		t.Errorf("cot(1) bits = %016x", got)
	}
}

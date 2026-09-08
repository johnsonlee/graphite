package store

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestJVMCompressionFlags(t *testing.T) {
	data, err := os.ReadFile("testdata/compression/expected.json")
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Name, Flags string
		Nodes       int32
		Arcs        int64
		Rows        [][]int32
	}
	if err := json.Unmarshal(data, &cases); err != nil {
		t.Fatal(err)
	}
	if len(cases) != 28 {
		t.Fatalf("got %d JVM fixtures, want 28", len(cases))
	}
	for _, tc := range cases {
		t.Run(tc.Name, func(t *testing.T) {
			g, err := LoadBVGraph(filepath.Join("testdata/compression", tc.Name, "forward"))
			if err != nil {
				t.Fatalf("flags %q: %v", tc.Flags, err)
			}
			if g.NodeSpan != tc.Nodes || g.ArcCount != tc.Arcs {
				t.Fatalf("nodes/arcs %d/%d, want %d/%d", g.NodeSpan, g.ArcCount, tc.Nodes, tc.Arcs)
			}
			expected := map[int32][]int32{}
			for _, row := range tc.Rows {
				expected[row[0]] = row[1:]
			}
			for id := int32(0); id < tc.Nodes; id++ {
				if got := g.Successors(id); !reflect.DeepEqual(got, expected[id]) {
					t.Errorf("node %d successors %v, JVM %v", id, got, expected[id])
				}
			}
		})
	}
}

func TestCompressionFlagValidation(t *testing.T) {
	for _, value := range []string{"RESIDUALS_UNKNOWN", "OUTDEGREES_GAMMA | OUTDEGREES_DELTA", "OFFSETS_GAMMA | OFFSETS_DELTA", "RESIDUALS_GAMMA | UNKNOWN"} {
		if _, err := parseCompressionFlags(value); err == nil {
			t.Errorf("accepted invalid flags %q", value)
		}
	}
	got, err := parseCompressionFlags(" OUTDEGREES_GAMMA | RESIDUALS_ZETA | REFERENCES_UNARY | OUTDEGREES_GAMMA ")
	want, _ := parseCompressionFlags("")
	if err != nil || got != want {
		t.Fatalf("explicit defaults %v, %v; want %v", got, err, want)
	}
	for _, flags := range []string{"GAMMA", "OUTDEGREES_GAMMA|", "|"} {
		if got, err := parseCompressionFlags(flags); err != nil || got != want {
			t.Errorf("Java flag syntax %q: %v, %v", flags, got, err)
		}
	}
}

func TestTruncatedIntegerCodings(t *testing.T) {
	for _, coding := range []int{codeGamma, codeDelta, codeUnary, codeZeta, codeGolomb, codeNibble} {
		b := &bitReader{data: []byte{0}}
		b.natural(coding, 3)
		if b.err == nil {
			t.Errorf("coding %d accepted truncated zero bits", coding)
		}
	}
}

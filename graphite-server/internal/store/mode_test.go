package store

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestLoadModesPreserveNodes(t *testing.T) {
	mapped, err := OpenMode("testdata/jvm-v3", "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	defer mapped.Close()
	if mapped.Mode != "MAPPED" || mapped.mappedData == nil || mapped.eagerNodes != nil {
		t.Fatal("MAPPED did not map the node file")
	}
	for _, mode := range []string{"EAGER", "AUTO"} {
		s, err := OpenMode("testdata/jvm-v3", mode)
		if err != nil {
			t.Fatal(err)
		}
		if s.Mode != "EAGER" || s.mappedData != nil || len(s.eagerNodes) != 16 {
			t.Fatalf("%s did not decode all nodes eagerly", mode)
		}
		for _, id := range mapped.NodeIDs() {
			want, err := mapped.Node(id)
			if err != nil {
				t.Fatal(err)
			}
			got, err := s.Node(id)
			if err != nil || !reflect.DeepEqual(got, want) {
				t.Fatalf("mode %s node %d got %#v want %#v error %v", mode, id, got, want, err)
			}
		}
		s.Close()
	}
	if _, err := OpenMode("testdata/jvm-v3", "invalid"); err == nil {
		t.Fatal("invalid mode accepted")
	}
}
func TestAutoModeBoundary(t *testing.T) {
	for _, tc := range []struct {
		count int
		want  string
	}{{0, "EAGER"}, {999999, "EAGER"}, {1000000, "MAPPED"}, {1000001, "MAPPED"}} {
		if got := resolveLoadMode("AUTO", tc.count); got != tc.want {
			t.Errorf("AUTO count %d: %s, want %s", tc.count, got, tc.want)
		}
	}
}

func TestEagerDoesNotDependOnOptionalNodeIndex(t *testing.T) {
	dir := t.TempDir()
	entries, err := os.ReadDir("testdata/jvm-v3")
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		data, err := os.ReadFile(filepath.Join("testdata/jvm-v3", entry.Name()))
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, entry.Name()), data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(dir, "graph.nodeindex"), []byte("corrupt"), 0600); err != nil {
		t.Fatal(err)
	}
	eager, err := OpenMode(dir, "EAGER")
	if err != nil {
		t.Fatal(err)
	}
	defer eager.Close()
	node, err := eager.Node(24)
	if err != nil || node.Caller.Signature() != "Example.run(int)" {
		t.Fatalf("eager node %#v, error %v", node, err)
	}
	if _, err := OpenMode(dir, "MAPPED"); err == nil {
		t.Fatal("MAPPED accepted corrupt node index")
	}
}

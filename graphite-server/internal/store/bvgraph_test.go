package store

import (
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"testing"
)

func TestJVMAdjacencyFixture(t *testing.T) {
	g, err := LoadBVGraph("testdata/bvgraph/forward")
	if err != nil {
		t.Fatal(err)
	}
	expected := map[int32][]int32{0: {1, 2, 3, 4, 5, 6, 20, 40, 63}, 1: {2, 3, 4, 5, 6, 7, 20, 40, 63}, 2: {3, 4, 5, 6, 7, 8, 20, 42, 63}, 3: {2, 3, 4, 5, 6, 7, 20, 40, 60, 61, 62, 63}, 7: {0, 63}, 63: {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}}
	if !reflect.DeepEqual(g.successors, expected) {
		t.Fatalf("adjacency got %v, want %v", g.successors, expected)
	}
	if g.NodeSpan != 64 || g.ArcCount != 58 {
		t.Fatalf("node span/arcs %d/%d", g.NodeSpan, g.ArcCount)
	}
	v := g.Successors(7)
	v[0] = 42
	if g.Successors(7)[0] != 0 {
		t.Fatal("successor result aliases graph")
	}
}
func TestRealBVGraph(t *testing.T) {
	dir := os.Getenv("GRAPHITE_TEST_GRAPH")
	if dir == "" {
		t.Skip("set GRAPHITE_TEST_GRAPH to a Kotlin-produced graph")
	}
	g, err := LoadBVGraph(filepath.Join(dir, "forward"))
	if err != nil {
		t.Fatal(err)
	}

	ids := make([]int, 0, len(g.successors))
	for id := range g.successors {
		ids = append(ids, int(id))
	}
	sort.Ints(ids)
	digest := sha256.New()
	var pair [8]byte
	for _, id := range ids {
		for _, target := range g.successors[int32(id)] {
			binary.BigEndian.PutUint32(pair[:4], uint32(id))
			binary.BigEndian.PutUint32(pair[4:], uint32(target))
			digest.Write(pair[:])
		}
	}
	hash := fmt.Sprintf("%x", digest.Sum(nil))
	if want := os.Getenv("GRAPHITE_TEST_EDGE_SHA256"); want != "" && hash != want {
		t.Fatalf("edge hash %s differs from JVM %s", hash, want)
	}
	t.Logf("ordered edge SHA-256: %s", hash)
	t.Logf("decoded %d arcs over node ID span %d (%d nonempty lists)", g.ArcCount, g.NodeSpan, len(g.successors))
}

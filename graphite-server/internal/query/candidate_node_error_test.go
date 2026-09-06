package query

import (
	"context"
	"fmt"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"os"
	"path/filepath"
	"testing"
)

// Force the candidate-consumption error boundary after preparation has completed.
// The sequential file mutation is fault injection, not a supported mutable-store
// contract or a performance fixture.
func TestIndexedCandidateConsumedNodeTagClassification(t *testing.T) {
	for _, tag := range []byte{16, 127, 128, 255} {
		t.Run(fmt.Sprint(tag), func(t *testing.T) {
			dir := t.TempDir()
			entries, err := os.ReadDir("testdata/candidate-index/clean")
			if err != nil {
				t.Fatal(err)
			}
			for _, entry := range entries {
				b, err := os.ReadFile(filepath.Join("testdata/candidate-index/clean", entry.Name()))
				if err != nil {
					t.Fatal(err)
				}
				if err = os.WriteFile(filepath.Join(dir, entry.Name()), b, 0600); err != nil {
					t.Fatal(err)
				}
			}
			g, err := store.OpenMode(dir, "MAPPED")
			if err != nil {
				t.Fatal(err)
			}
			defer g.Close()
			e := evaluator{ctx: context.Background(), indexFirst: true}
			clause := candidateClause(t, "MATCH (n:CallSite) WHERE n.caller_name CONTAINS '' RETURN id(n)")
			walk := e.indexedNodeWalker(g, clause, &candidateSlot{})
			if walk == nil {
				t.Fatal("candidate path unavailable")
			}
			ids := g.NodesOfKind("CallSiteNode")
			if len(ids) == 0 {
				t.Fatal("empty fixture")
			}
			file, err := os.OpenFile(filepath.Join(dir, "graph.nodedata"), os.O_WRONLY, 0)
			if err != nil {
				t.Fatal(err)
			}
			for _, id := range ids {
				raw, err := g.RawCallSiteStringIDs(context.Background(), id)
				if err != nil {
					file.Close()
					t.Fatal(err)
				}
				if _, err = file.WriteAt([]byte{tag}, raw.Offset+4); err != nil {
					file.Close()
					t.Fatal(err)
				}
			}
			if err = file.Close(); err != nil {
				t.Fatal(err)
			}
			accepted := 0
			defer func() {
				caught := recover()
				failure, ok := caught.(*Error)
				if !ok || failure.Class != "IllegalArgumentException" || failure.Message != fmt.Sprintf("Unknown node tag: %d", int8(tag)) {
					t.Fatalf("consumed tag %d: %#v", tag, caught)
				}
				if accepted != 0 {
					t.Fatalf("published %d nodes before consumed-node error", accepted)
				}
			}()
			walk(func(any) { accepted++ })
		})
	}
}

package store

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestDistinctIntegrationRawIdentityDoesNotCertifyNode(t *testing.T) {
	dir := copyIndexFixture(t)
	graph := openIndexFixture(t, dir, "MAPPED")
	offset := graph.locations[17].offset
	if err := graph.Close(); err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(dir, "graph.nodedata")
	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	data[offset+4] = 255
	if err = os.WriteFile(file, data, 0600); err != nil {
		t.Fatal(err)
	}
	if err = os.Remove(filepath.Join(dir, "graph.callsite-string-content.identity")); err != nil {
		t.Fatal(err)
	}
	graph = openIndexFixture(t, dir, "MAPPED")
	ctx := context.Background()
	index := requireIndex(t, graph)
	if _, ok, err := graph.PrepareDistinctStringIndex(ctx, DistinctProjectionOptions{SourceCount: 1, Limit: 1}); !ok || err != nil {
		t.Fatalf("selective initialization=%v/%v", ok, err)
	}
	if graph.candidateProof.completed {
		t.Fatal("selective DISTINCT initialization published a full-node proof")
	}
	if valid, err := graph.CertifyCallSiteCandidates(ctx, index); valid || err != nil {
		t.Fatalf("damaged node certificate=%v/%v", valid, err)
	}
	_, err = graph.CandidateNode(ctx, 17)
	var tag *UnknownNodeTagError
	if !errors.As(err, &tag) {
		t.Fatalf("actual node consumption lost typed tag error: %T %v", err, err)
	}
}

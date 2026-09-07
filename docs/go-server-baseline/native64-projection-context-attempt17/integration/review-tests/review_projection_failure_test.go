package store

import (
	"context"
	"encoding/binary"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestReviewProjectionSIDFailureStopsLaterCancelCheckpoint(t *testing.T) {
	dir := copyIndexFixture(t)
	s := openIndexFixture(t, dir, "MAPPED")
	offset := s.locations[17].offset
	invalid := int32(len(s.Strings) + 100)
	f, err := os.OpenFile(filepath.Join(dir, "graph.nodedata"), os.O_WRONLY, 0)
	if err != nil {
		t.Fatal(err)
	}
	var data [4]byte
	binary.BigEndian.PutUint32(data[:], uint32(invalid))
	_, err = f.WriteAt(data[:], offset+5)
	f.Close()
	if err != nil {
		t.Fatal(err)
	}
	parent, cancel := context.WithCancel(context.Background())
	defer cancel()
	ctx := &projectionCheckpointContext{Context: parent, target: ".ProjectionCandidateNode.func1", at: 4, action: cancel}
	_, ok, err := s.ProjectionCandidateNode(ctx, 17)
	var sid *StringTableReferenceError
	if ok || !errors.As(err, &sid) || sid.Index != invalid || sid.Size != len(s.Strings) || ctx.calls != 3 || parent.Err() != nil {
		t.Fatalf("ok=%v err=%v sid=%+v polls=%d context=%v", ok, err, sid, ctx.calls, parent.Err())
	}
	// A fresh already-canceled request still observes cancellation before this
	// malformed field; the prior core failure does not poison later requests.
	cancel()
	if _, ok, err = s.ProjectionCandidateNode(parent, 17); ok || err != context.Canceled {
		t.Fatal(ok, err)
	}
}

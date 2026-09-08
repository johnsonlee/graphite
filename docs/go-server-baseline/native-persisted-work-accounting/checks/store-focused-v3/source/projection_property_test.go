package store

import (
	"context"
	"encoding/binary"
	"errors"
	"math"
	"testing"
)

func TestProjectionPropertyStringIDActualJavaRecords(t *testing.T) {
	g, err := OpenMode("testdata/jvm-v3", "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	defer g.Close()
	ctx := context.Background()
	for _, test := range []struct {
		id                   int32
		kind, property, want string
	}{
		{14, "EnumConstant", "name", "RED"}, {16, "LocalVariable", "name", "x"},
		{18, "FieldNode", "class", "Example"}, {18, "FieldNode", "name", "field"},
	} {
		sid, present, err := g.ProjectionPropertyStringID(ctx, test.id, test.kind, test.property)
		if err != nil || !present || g.Strings[sid] != test.want {
			t.Fatal(test, sid, present, err)
		}
	}
	if _, present, err := g.ProjectionPropertyStringID(ctx, 18, "FieldNode", "type"); present || err != nil {
		t.Fatal("query compiler capability widened", present, err)
	}
	canceled, cancel := context.WithCancel(ctx)
	cancel()
	if _, _, err := g.ProjectionPropertyStringID(canceled, 18, "FieldNode", "name"); err != context.Canceled {
		t.Fatal(err)
	}
	if err := g.Close(); err != nil {
		t.Fatal(err)
	}
	if _, _, err := g.ProjectionPropertyStringID(ctx, 18, "FieldNode", "name"); err != ErrStoreClosed {
		t.Fatal(err)
	}
}

// Private offset injection isolates main's signed long -> int address rules;
// it does not edit the real Java fixture or claim mutable-store support.
func TestProjectionPropertyOffsetConsumption(t *testing.T) {
	g, err := OpenMode("testdata/jvm-v3", "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	defer g.Close()
	ctx := context.Background()
	if err = g.prepareProjectionOffsets(ctx); err != nil {
		t.Fatal(err)
	}
	g.distinctProjection.offsets = make([]byte, 8+19*8)
	set := func(offset int64) {
		binary.BigEndian.PutUint64(g.distinctProjection.offsets[8+18*8:], uint64(offset+1))
	}
	for _, offset := range []int64{-1, -2, math.MinInt64} {
		set(offset)
		if _, present, err := g.ProjectionPropertyStringID(ctx, 18, "FieldNode", "class"); err != nil || present {
			t.Fatal(offset, present, err)
		}
	}
	set(int64(len(g.mappedData)) - 5)
	_, _, err = g.ProjectionPropertyStringID(ctx, 18, "FieldNode", "class")
	var bounds *ProjectionReadError
	if !errors.As(err, &bounds) || bounds.Message != nil {
		t.Fatal(err)
	}
	// 2^32 + a valid record offset wraps to that same int address on the JVM.
	set((1 << 32) + 152)
	sid, present, err := g.ProjectionPropertyStringID(ctx, 18, "FieldNode", "name")
	if err != nil || !present || g.Strings[sid] != "field" {
		t.Fatal(sid, present, err)
	}
}

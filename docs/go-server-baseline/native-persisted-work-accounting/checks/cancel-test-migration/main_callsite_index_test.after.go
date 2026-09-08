package store

import (
	"context"
	"encoding/binary"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"sync/atomic"
	"testing"
)

func TestMainIndexPoliciesKeepStrictReaderSeparate(t *testing.T) {
	for _, mutation := range []string{"retained-bytes", "unused-id", "duplicate-id", "bad-crc", "bad-capacity"} {
		t.Run(mutation, func(t *testing.T) {
			dir := copyIndexFixture(t)
			p := filepath.Join(dir, callSiteIndexFile)
			data, err := os.ReadFile(p)
			if err != nil {
				t.Fatal(err)
			}
			r := indexRegions(data)[0]
			switch mutation {
			case "retained-bytes":
				binary.BigEndian.PutUint64(data[68:76], 1)
			case "unused-id":
				binary.BigEndian.PutUint32(data[r.nodes:], 85)
			case "duplicate-id":
				copy(data[r.nodes+4:r.nodes+8], data[r.nodes:r.nodes+4])
			case "bad-capacity":
				binary.BigEndian.PutUint32(data[r.nodes:], 91)
			}
			fixIndexCRC(data)
			if mutation == "bad-crc" {
				data[len(data)-1] ^= 1
			}
			if err := os.WriteFile(p, data, 0600); err != nil {
				t.Fatal(err)
			}
			s := openIndexFixture(t, dir, "MAPPED")
			if _, ok, err := s.TryCallSiteStringIndex(context.Background()); ok || err != nil {
				t.Fatal("strict reader", ok, err)
			}
			v, ok, err := s.tryMainCallSiteStringIndex(context.Background(), true)
			want := mutation != "bad-crc" && mutation != "bad-capacity"
			if ok != want || err != nil {
				t.Fatal("mapped policy", ok, err)
			}
			if want {
				if _, err := v.Directory(context.Background(), CallerClass); err != nil {
					t.Fatal(err)
				}
				if s.callSiteIndex.view != nil || !s.callSiteIndex.unavailable {
					t.Fatal("strict rejection changed")
				}
			}
		})
	}
}

func TestMainIndexOffsetsAreRepresentationSpecific(t *testing.T) {
	dir := copyIndexFixture(t)
	p := filepath.Join(dir, "graph.nodeoffsets")
	data, err := os.ReadFile(p)
	if err != nil {
		t.Fatal(err)
	}
	// ID17 is the first posting of each of its four property ranges. Its canonical offset is
	// negative, but still greater than Long.MIN_VALUE at the retained row start.
	binary.BigEndian.PutUint64(data[8+17*8:], ^uint64(0))
	if err := os.WriteFile(p, data, 0600); err != nil {
		t.Fatal(err)
	}
	s := openIndexFixture(t, dir, "MAPPED")
	for _, mapped := range []bool{false, true} {
		if _, ok, err := s.tryMainCallSiteStringIndex(context.Background(), mapped); !ok || err != nil {
			t.Fatal(mapped, ok, err)
		}
	}
	// A6 continues to use its original graph.nodeindex-based checks.
	requireIndex(t, s)
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	for _, state := range s.callSiteIndex.main {
		if state.view == nil || state.view.data != nil {
			t.Fatal("main mapping not released")
		}
		if _, err := state.view.Info(context.Background()); err != ErrStoreClosed {
			t.Fatal(err)
		}
	}
}

func TestMainIndexCancellationDoesNotPublish(t *testing.T) {
	for _, mapped := range []bool{false, true} {
		probe := openIndexFixture(t, indexFixture, "MAPPED")
		base, cancel := context.WithCancel(context.Background())
		counter := &cancelAtPoll{Context: base, cancel: cancel, at: 1 << 30}
		if _, ok, err := probe.tryMainCallSiteStringIndex(counter, mapped); !ok || err != nil {
			t.Fatal(ok, err)
		}
		last := counter.count.Load()
		if last <= 0 || base.Err() != nil {
			t.Fatalf("invalid successful poll probe: mapped=%v polls=%d err=%v", mapped, last, base.Err())
		}
		t.Logf("mapped=%v reachable Err-triggered cancellation polls=%d", mapped, last)
		cancel()
		probe.Close()
		// Count the actual reader schedule. Fixed historical indices can exceed
		// the final poll after a reader changes its chunk/checkpoint structure.
		for at := int32(1); at <= last; at++ {
			t.Run(strconv.FormatBool(mapped)+"/"+strconv.Itoa(int(at)), func(t *testing.T) {
				s := openIndexFixture(t, indexFixture, "MAPPED")
				base, cancel := context.WithCancel(context.Background())
				defer cancel()
				ctx := &cancelAtPoll{Context: base, cancel: cancel, at: at}
				if _, ok, err := s.tryMainCallSiteStringIndex(ctx, mapped); ok || !errors.Is(err, context.Canceled) {
					t.Fatal(ok, err)
				}
				if base.Err() != context.Canceled || ctx.count.Load() < at {
					t.Fatal("the real cancellation trigger was not reached", at, ctx.count.Load(), base.Err())
				}
				for _, state := range s.callSiteIndex.main {
					if state.view != nil || state.unavailable || state.loading != nil {
						t.Fatal("canceled publication")
					}
				}
				if _, ok, err := s.tryMainCallSiteStringIndex(context.Background(), mapped); !ok || err != nil {
					t.Fatal("fresh load", ok, err)
				}
			})
		}
		t.Run(strconv.FormatBool(mapped)+"/after-last-success", func(t *testing.T) {
			s := openIndexFixture(t, indexFixture, "MAPPED")
			base, cancel := context.WithCancel(context.Background())
			defer cancel()
			ctx := &cancelAtPoll{Context: base, cancel: cancel, at: last + 1}
			view, ok, err := s.tryMainCallSiteStringIndex(ctx, mapped)
			if err != nil || !ok || view == nil || base.Err() != nil || ctx.count.Load() != last {
				t.Fatalf("unreached trigger should succeed: ok=%v err=%v polls=%d want=%d context=%v", ok, err, ctx.count.Load(), last, base.Err())
			}
		})
	}
}

type mainIndexPause struct {
	context.Context
	count            atomic.Int32
	entered, release chan struct{}
}

func (c *mainIndexPause) Err() error {
	if c.count.Add(1) == 5 {
		close(c.entered)
		<-c.release
	}
	return c.Context.Err()
}

func TestMainIndexCloseJoinsBothIndependentLoads(t *testing.T) {
	s := openIndexFixture(t, indexFixture, "MAPPED")
	contexts := []*mainIndexPause{}
	results := make(chan error, 2)
	for _, mapped := range []bool{false, true} {
		ctx := &mainIndexPause{Context: context.Background(), entered: make(chan struct{}), release: make(chan struct{})}
		contexts = append(contexts, ctx)
		go func() { _, _, err := s.tryMainCallSiteStringIndex(ctx, mapped); results <- err }()
		<-ctx.entered
	}
	closed := make(chan error, 1)
	go func() { closed <- s.Close() }()
	<-s.callSiteIndex.closing
	select {
	case <-closed:
		t.Fatal("Close did not join loaders")
	default:
	}
	for _, ctx := range contexts {
		close(ctx.release)
	}
	for range contexts {
		if err := <-results; err != ErrStoreClosed {
			t.Fatal(err)
		}
	}
	if err := <-closed; err != nil {
		t.Fatal(err)
	}
	for _, state := range s.callSiteIndex.main {
		if state.view != nil || state.loading != nil {
			t.Fatal("publication after Close")
		}
	}
}

func TestMainMappedRangeColdWarmAndCancellation(t *testing.T) {
	ctx := context.Background()
	s := openIndexFixture(t, indexFixture, "MAPPED")
	index, ok, err := s.PrepareDistinctStringIndex(ctx, DistinctProjectionOptions{MainSource: true, SourceCount: 40, InitializeMappedView: true})
	if !ok || err != nil {
		t.Fatal(ok, err)
	}
	ids, orders, valid, err := index.MainMappedProjectionRange(ctx, CallerName, 7)
	if err != nil || !valid || len(ids) != 2 || ids[0] != 2 || ids[1] != 41 || len(orders) != 2 {
		t.Fatal(ids, orders, valid, err)
	}
	ids[0] = -99
	orders[0] = -99
	ids, orders, valid, err = index.MainMappedProjectionRange(ctx, CallerName, 7)
	if err != nil || !valid || len(ids) != 2 || ids[0] != 2 || orders != nil {
		t.Fatal("warm owned IDs, lazy orders", ids, orders, valid, err)
	}
	// Cancel during a different cold range's canonical-order validation. Its
	// partial orders must not become a warm validation certificate.
	base, cancel := context.WithCancel(ctx)
	defer cancel()
	// The old fifth Err poll was the second node's offset-preparation check.
	// Observe that same boundary via either API over a real cancellable context.
	poll := &projectionCheckpointContext{Context: base, target: ".prepareProjectionOffsets", at: 2, action: cancel}
	if _, _, _, err := index.MainMappedProjectionRange(poll, CallerClass, 3); err != context.Canceled || poll.calls != 2 || base.Err() != context.Canceled {
		t.Fatal(err)
	}
	ids, orders, valid, err = index.MainMappedProjectionRange(ctx, CallerClass, 3)
	if err != nil || !valid || len(ids) != 2 || len(orders) != 2 {
		t.Fatal("cold after cancellation", ids, orders, valid, err)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	if _, _, _, err := index.MainMappedProjectionRange(ctx, CallerName, 7); err != ErrStoreClosed {
		t.Fatal(err)
	}
	if ids[0] != 17 {
		t.Fatal("returned IDs lost ownership", ids)
	}
}

func TestMainMappedRangeInvalidConsumesEntireRangeBeforeCaching(t *testing.T) {
	s := openIndexFixture(t, indexFixture, "MAPPED")
	ctx := context.Background()
	index, ok, err := s.PrepareDistinctStringIndex(ctx, DistinctProjectionOptions{MainSource: true, SourceCount: 40, InitializeMappedView: true})
	if !ok || err != nil {
		t.Fatal(ok, err)
	}
	// Private test injection, not support for editing an open immutable store:
	// first order invalid, second access out of bounds. The later error wins and
	// no invalid-range cache entry may hide it on the next request.
	data := append([]byte(nil), s.distinctProjection.offsets...)
	binary.BigEndian.PutUint64(data[8+2*8:], 0)
	s.distinctProjection.offsets = data[:8+41*8]
	for attempt := 0; attempt < 2; attempt++ {
		if _, _, _, err := index.MainMappedProjectionRange(ctx, CallerName, 7); err == nil {
			t.Fatal("stopped after first invalid order")
		}
	}
	s.distinctProjection.offsets = data
	if _, _, valid, err := index.MainMappedProjectionRange(ctx, CallerName, 7); err != nil || valid {
		t.Fatal(valid, err)
	}
	// A fully validated invalid result is cached; future calls don't touch the
	// offsets. This injection distinguishes it from the failed partial attempt.
	s.distinctProjection.offsets = data[:8]
	if _, _, valid, err := index.MainMappedProjectionRange(ctx, CallerName, 7); err != nil || valid {
		t.Fatal("invalid cache", valid, err)
	}
}

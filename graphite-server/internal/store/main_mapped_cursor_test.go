package store

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"reflect"
	"testing"
)

func openMainMappedCursorFixture(t *testing.T) (*Store, *DistinctStringIndex, string) {
	t.Helper()
	dir := copyIndexFixture(t)
	s := openIndexFixture(t, dir, "MAPPED")
	index, ok, err := s.PrepareDistinctStringIndex(context.Background(), DistinctProjectionOptions{MainSource: true, SourceCount: 40, Limit: 1, InitializeMappedView: true})
	if err != nil || !ok {
		t.Fatal(ok, err)
	}
	return s, index, dir
}

func TestMainMappedCursorFinallyCancellationPublishesCompletedRange(t *testing.T) {
	for _, reject := range []bool{false, true} {
		s, index, _ := openMainMappedCursorFixture(t)
		base, cancel := context.WithCancel(context.Background())
		cause := errors.New("range finally rejected")
		var calls []int64
		cursor, valid, err := index.MainMappedCursorWithWork(base, CallerClass, 3, func(n int64) error {
			// Looking at state inside the callback also proves it runs outside
			// the Store lifetime lock.
			if _, err := s.PersistedIndexState(context.Background()); err != nil {
				return err
			}
			calls = append(calls, n)
			if len(calls) == 2 {
				cancel()
				if reject {
					return cause
				}
			}
			return nil
		})
		defer cancel()
		if !reflect.DeepEqual(calls, []int64{2, 2}) {
			t.Fatal("binary/validation boundaries", calls)
		}
		state, stateErr := s.StringPropertyIndexes(context.Background())
		if stateErr != nil {
			t.Fatal(stateErr)
		}
		if reject {
			if cursor != nil || valid || !errors.Is(err, cause) || state.MappedRangeCount != 0 {
				t.Fatal(cursor, valid, err, state)
			}
		} else {
			if err != nil || !valid || cursor == nil || !cursor.HasCurrent() || state.MappedRangeCount != 1 || base.Err() != context.Canceled {
				t.Fatal(cursor, valid, err, state, base.Err())
			}
			// A warm range performs the search but no validation or local-context
			// polling, even with that same already canceled request context.
			calls = nil
			warm, valid, err := index.MainMappedCursorWithWork(base, CallerClass, 3, func(n int64) error { calls = append(calls, n); return nil })
			if err != nil || !valid || warm == nil || !reflect.DeepEqual(calls, []int64{2}) {
				t.Fatal(warm, valid, err, calls)
			}
		}
	}
}

// Owned synthetic representation, used solely to place a range across absolute
// slot 1024. It is not a benchmark or a persisted-format oracle.
func mappedAbsoluteBoundaryIndex() (*Store, *DistinctStringIndex) {
	s := &Store{Strings: []string{"a", "b"}, locations: map[int32]nodeLocation{}}
	data := make([]byte, 16+1025*4)
	binary.BigEndian.PutUint32(data[4:], 1)
	binary.BigEndian.PutUint32(data[8:], 1023)
	binary.BigEndian.PutUint32(data[12:], 1025)
	for n := 0; n < 1025; n++ {
		binary.BigEndian.PutUint32(data[16+n*4:], uint32(n))
		s.locations[int32(n)] = nodeLocation{offset: int64(n * 10)}
	}
	v := &CallSiteStringIndex{owner: s, data: data, info: CallSiteStringIndexInfo{CallSiteCount: 1025, UniqueStringCounts: [4]int32{2}}, mainRanges: &mainPostingRangeCache{}}
	v.regions[0] = callSiteIndexRegion{strings: 0, ends: 8, nodes: 16}
	return s, &DistinctStringIndex{owner: s, view: v}
}

func TestMainMappedCursorPollsAbsolutePositionBeforeRequiredRead(t *testing.T) {
	for _, missingFirst := range []bool{false, true} {
		s, index := mappedAbsoluteBoundaryIndex()
		if missingFirst {
			delete(s.locations, 1023)
		}
		ctx, cancel := context.WithCancel(context.Background())
		cancel()
		var calls []int64
		cursor, valid, err := index.MainMappedCursorWithWork(ctx, CallerClass, 1, func(n int64) error { calls = append(calls, n); return nil })
		if cursor != nil || valid || !reflect.DeepEqual(calls, []int64{2, 1}) {
			t.Fatal(cursor, valid, err, calls)
		}
		if missingFirst {
			var raw *ProjectionReadError
			if !errors.As(err, &raw) {
				t.Fatal("position 1023 must read before the 1024 cancellation poll", err)
			}
		} else if !errors.Is(err, context.Canceled) {
			t.Fatal("position 1024 must stop before its read", err)
		}
		if index.view.mainRanges.states[1] != 0 {
			t.Fatal("incomplete range was cached")
		}
	}
}

func TestMainMappedCursorFinallyFailureOverridesReadFailure(t *testing.T) {
	s, index := mappedAbsoluteBoundaryIndex()
	delete(s.locations, 1023)
	cause := errors.New("final work overrides offset failure")
	calls := 0
	_, _, err := index.MainMappedCursorWithWork(context.Background(), CallerClass, 1, func(int64) error {
		calls++
		if calls == 2 {
			return cause
		}
		return nil
	})
	var aborted *WorkAbortedError
	if !errors.As(err, &aborted) || aborted.Cause != cause || index.view.mainRanges.states[1] != 0 {
		t.Fatal(err, calls)
	}
}

func TestMainMappedWarmCursorReadsEachOrderOnlyOnAdvance(t *testing.T) {
	s, index, _ := openMainMappedCursorFixture(t)
	cold, valid, err := index.MainMappedCursorWithWork(context.Background(), CallerClass, 3, nil)
	if err != nil || !valid || cold == nil {
		t.Fatal(cold, valid, err)
	}
	warm, valid, err := index.MainMappedCursorWithWork(context.Background(), CallerClass, 3, nil)
	if err != nil || !valid || warm == nil || warm.orders != nil || cold.orders == nil {
		t.Fatal(warm, valid, err)
	}
	secondID := index.view.intAt(index.view.regions[CallerClass].nodes + 4*(warm.position+1))
	// prepareProjectionOffsets owns an os.ReadFile copy. Change the actual
	// nodeOrder dependency under its lifetime lock; writing the backing file
	// cannot distinguish lazy reading from that already loaded copy.
	at := int64(8) + int64(secondID)*8
	s.callSiteIndex.mu.Lock()
	original := append([]byte(nil), s.distinctProjection.offsets[at:at+8]...)
	for k := int64(0); k < 8; k++ {
		s.distinctProjection.offsets[at+k] = 0
	}
	s.callSiteIndex.mu.Unlock()
	defer func() {
		s.callSiteIndex.mu.Lock()
		defer s.callSiteIndex.mu.Unlock()
		if !s.callSiteIndex.closed {
			copy(s.distinctProjection.offsets[at:at+8], original)
		}
	}()
	if ok, err := warm.Advance(); err != nil || !ok || warm.Order() != -1 {
		t.Fatal("warm order was read eagerly", ok, err, warm.Order())
	}
	if ok, err := cold.Advance(); err != nil || !ok || cold.Order() < 0 {
		t.Fatal("cold owned order changed", ok, err, cold.Order())
	}
	fresh, valid, err := index.MainMappedCursorWithWork(context.Background(), CallerClass, 3, nil)
	if err != nil || !valid {
		t.Fatal(err, valid)
	}
	ownedID, ownedOrder := fresh.NodeID(), fresh.Order()
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	if fresh.NodeID() != ownedID || fresh.Order() != ownedOrder {
		t.Fatal("owned scalar changed after Close")
	}
	if _, err := fresh.Advance(); !errors.Is(err, ErrStoreClosed) {
		t.Fatal("Advance accessed a closed mapping", err)
	}
}

func TestMainMappedSpanPreservesAbsolutePositionsAndClose(t *testing.T) {
	s, index, _ := openMainMappedCursorFixture(t)
	v := index.view
	position := int(v.info.TrigramPostingCount) / 2
	hash := int32(int64(binary.BigEndian.Uint64(v.data[v.trigrams+position*8:])) >> 32)
	start, end, found, err := index.MainMappedTrigramSpan(hash)
	if err != nil || !found || position < start || position >= end {
		t.Fatal(start, end, found, err)
	}
	for at := start; at < end; at++ {
		sid, err := index.MainMappedTrigramStringIDAt(at)
		if err != nil {
			t.Fatal(err)
		}
		text, err := s.MainMappedString(sid)
		if err != nil || text != s.Strings[sid] {
			t.Fatal(text, err)
		}
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	if _, _, _, err := index.MainMappedTrigramSpan(hash); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
	if _, err := index.MainMappedTrigramStringIDAt(start); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
	if _, err := s.MainMappedString(0); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
}

func TestMainMappedCursorEmptyRangeStillConstructsCurrent(t *testing.T) {
	for _, at := range []int{0, 1025} {
		t.Run(fmt.Sprint(at), func(t *testing.T) {
			_, index := mappedAbsoluteBoundaryIndex()
			// A deliberately malformed/hot representation: the second property row
			// is empty. Valid persisted loader input cannot contain equal ends.
			binary.BigEndian.PutUint32(index.view.data[8:], uint32(at))
			binary.BigEndian.PutUint32(index.view.data[12:], uint32(at))
			var charges []int64
			cursor, valid, err := index.MainMappedCursorWithWork(context.Background(), CallerClass, 1, func(n int64) error { charges = append(charges, n); return nil })
			var raw *ProjectionReadError
			if cursor != nil || valid || !errors.As(err, &raw) || !reflect.DeepEqual(charges, []int64{2}) {
				t.Fatal(cursor, valid, err, charges)
			}
			if at == 0 {
				if raw.Class != "ArrayIndexOutOfBoundsException" || raw.Message == nil || *raw.Message != "Index 0 out of bounds for length 0" {
					t.Fatal(raw)
				}
			} else if raw.Class != "" || raw.Message != nil {
				t.Fatal("posting buffer bounds precede empty orders", raw)
			}
			// Validation published before construction failed. A warm hit has no
			// validatedOrders array; it still reads posting and node order.
			if index.view.mainRanges.states[1] != 1 {
				t.Fatal("missing completed validation")
			}
			warm, valid, err := index.MainMappedCursorWithWork(context.Background(), CallerClass, 1, nil)
			if at == 0 {
				if err != nil || !valid || warm == nil || warm.HasCurrent() || warm.NodeID() != 0 || warm.Order() != 0 {
					t.Fatal(warm, valid, err)
				}
			} else if warm != nil || valid || !errors.As(err, &raw) || raw.Message != nil {
				t.Fatal(warm, valid, err)
			}
		})
	}
}

func TestMainMappedRangeNegativeArrayPrecedesValidation(t *testing.T) {
	_, index := mappedAbsoluteBoundaryIndex()
	binary.BigEndian.PutUint32(index.view.data[8:], 10)
	binary.BigEndian.PutUint32(index.view.data[12:], 9)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	var charges []int64
	_, _, err := index.MainMappedCursorWithWork(ctx, CallerClass, 1, func(n int64) error { charges = append(charges, n); return nil })
	var raw *ProjectionReadError
	if !errors.As(err, &raw) || raw.Class != "NegativeArraySizeException" || raw.Message == nil || *raw.Message != "-1" || !reflect.DeepEqual(charges, []int64{2}) || index.view.mainRanges.states[1] != 0 {
		t.Fatal(err, charges)
	}
}

func TestMainMappedRangeCacheRaceLoserKeepsItsOwnOrders(t *testing.T) {
	s, index := mappedAbsoluteBoundaryIndex()
	calls := 0
	cursor, valid, err := index.MainMappedCursorWithWork(context.Background(), CallerClass, 1, func(int64) error {
		calls++
		if calls == 2 {
			// Model another completed validation publishing while this request's
			// finally callback is outside the lifetime lock. Its successful put wins;
			// this request still returns its own already validated order array.
			s.callSiteIndex.mu.Lock()
			index.view.mainRanges.keys[1] = 1
			index.view.mainRanges.states[1] = 1
			loc := s.locations[1023]
			loc.offset = 99
			s.locations[1023] = loc
			s.callSiteIndex.mu.Unlock()
		}
		return nil
	})
	if err != nil || !valid || cursor == nil || cursor.Order() != 10230 || cursor.orders == nil {
		t.Fatal(cursor, valid, err)
	}
	warm, valid, err := index.MainMappedCursorWithWork(context.Background(), CallerClass, 1, nil)
	if err != nil || !valid || warm == nil || warm.Order() != 99 || warm.orders != nil {
		t.Fatal(warm, valid, err)
	}
}

func TestMainMappedCapabilityDistinguishesUnsupportedIndex(t *testing.T) {
	var absent *DistinctStringIndex
	_, mapped := mappedAbsoluteBoundaryIndex()
	for _, index := range []*DistinctStringIndex{absent, {}, {view: &CallSiteStringIndex{}}} {
		if index.MainMappedCapability() || index.MainMappedCallSiteCount() != 0 {
			t.Fatal(index)
		}
	}
	if !mapped.MainMappedCapability() || mapped.MainMappedCallSiteCount() != 1025 {
		t.Fatal("mapped representation not recognized")
	}
}

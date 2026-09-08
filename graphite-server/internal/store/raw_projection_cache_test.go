package store

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"sync"
	"testing"
)

func TestRawProjectionCacheOwnershipLRUAndLifecycle(t *testing.T) {
	s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
	ctx := context.Background()
	put := func(key string, ids []int32) {
		t.Helper()
		if err := s.CacheRawProjectionMatches(ctx, key, ids); err != nil {
			t.Fatal(err)
		}
	}
	get := func(key string, want []int32, hit bool) {
		t.Helper()
		ids, ok, err := s.RawProjectionMatches(ctx, key)
		if err != nil || ok != hit || !reflect.DeepEqual(ids, want) {
			t.Fatalf("%s: ids=%v hit=%v err=%v; want=%v hit=%v", key, ids, ok, err, want, hit)
		}
		if len(ids) > 0 {
			ids[0] = -99
		}
	}
	input := []int32{17, 2}
	put("owned", input)
	input[0] = 999
	get("owned", []int32{17, 2}, true)
	get("owned", []int32{17, 2}, true)
	put("empty", nil)
	get("empty", nil, true)
	get("absent", nil, false)
	if err := s.ReleaseDistinctStringIndex(ctx); err != nil {
		t.Fatal(err)
	}
	get("owned", []int32{17, 2}, true)
	if err := s.ClearStringPropertyIndexes(ctx); err != nil {
		t.Fatal(err)
	}
	get("owned", nil, false)
	for i := 0; i < 16; i++ {
		put(fmt.Sprint(i), []int32{int32(i)})
	}
	get("0", []int32{0}, true) // promotes the oldest entry
	put("16", []int32{16})
	get("1", nil, false)
	put("2", []int32{200}) // duplicate put must not promote
	put("17", []int32{17})
	get("2", nil, false)
	put("3", []int32{300})
	get("3", []int32{3}, true) // duplicate must not replace
	state, err := s.StringPropertyIndexes(ctx)
	if err != nil || state.RawProjectionCount != 16 || state.RawMatchCount != 0 {
		t.Fatalf("state %+v %v", state, err)
	}
	canceled, cancel := context.WithCancel(ctx)
	cancel()
	if err := s.CacheRawProjectionMatches(canceled, "canceled", []int32{1}); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	if _, _, err := s.RawProjectionMatches(canceled, "0"); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	get("canceled", nil, false)
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	if len(s.callSiteIndex.rawProjection) != 0 {
		t.Fatal("close retained raw matches")
	}
	if err := s.CacheRawProjectionMatches(ctx, "closed", nil); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
	if _, _, err := s.RawProjectionMatches(ctx, "0"); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
}

func TestRawProjectionConcurrentClose(t *testing.T) {
	s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
	var wg sync.WaitGroup
	for worker := 0; worker < 8; worker++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			for i := 0; i < 100; i++ {
				key := fmt.Sprint((i + id) % 20)
				if err := s.CacheRawProjectionMatches(context.Background(), key, []int32{int32(id)}); err != nil && !errors.Is(err, ErrStoreClosed) {
					t.Error(err)
				}
				if _, _, err := s.RawProjectionMatches(context.Background(), key); err != nil && !errors.Is(err, ErrStoreClosed) {
					t.Error(err)
				}
			}
		}(worker)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	wg.Wait()
}

package store

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

// These are source-derived boundary controls on the existing Java-written
// fixture, not new JVM observations or DISTINCT query/performance assertions.
func TestMainDistinctSplitOptionalReader(t *testing.T) {
	for _, preferred := range []bool{false, true} {
		for _, file := range []string{"valid", "missing", "directory", "corrupt"} {
			t.Run(fmt.Sprintf("mapped=%v/%s", preferred, file), func(t *testing.T) {
				dir := copyIndexFixture(t)
				path := filepath.Join(dir, callSiteIndexFile)
				original, err := os.ReadFile(path)
				if err != nil {
					t.Fatal(err)
				}
				switch file {
				case "missing", "directory":
					if err := os.Remove(path); err != nil {
						t.Fatal(err)
					}
					if file == "directory" {
						if err := os.Mkdir(path, 0700); err != nil {
							t.Fatal(err)
						}
					}
				case "corrupt":
					if err := os.WriteFile(path, []byte("bad"), 0600); err != nil {
						t.Fatal(err)
					}
				}
				s := openIndexFixture(t, dir, "MAPPED")
				calls := []int64{}
				consume := func(n int64) error { calls = append(calls, n); return nil }
				index, rep, err := s.PrepareMainDistinctSplitIndex(context.Background(), preferred, consume)
				if err != nil {
					t.Fatal(err)
				}
				if file == "valid" {
					want := MainDistinctIndexRetained
					if preferred {
						want = MainDistinctIndexMapped
					}
					if index == nil || rep != want || index.Raw || index.ParallelRaw || index.view == nil || len(calls) == 0 || calls[0] != 1 {
						t.Fatal(index, rep, calls)
					}
					if index.MainMappedCapability() != preferred {
						t.Fatal("wrong physical reader", index.MainMappedCapability())
					}
				} else if index != nil || rep != MainDistinctIndexNone {
					t.Fatal("optional decline built/raw-fell-back", index, rep)
				}
				if (file == "missing" || file == "directory") && len(calls) != 0 {
					t.Fatal("absent file consumed work", calls)
				}
				state, err := s.PersistedIndexState(context.Background())
				if err != nil {
					t.Fatal(err)
				}
				if state.RetainPreference != !preferred || state.LoadedFromPersistence != (file == "valid" && !preferred) || state.MappedViewUnavailable != (file == "corrupt" && preferred) {
					t.Fatal(state)
				}
				views, err := s.StringPropertyIndexes(context.Background())
				if err != nil {
					t.Fatal(err)
				}
				if views.Retained != (file == "valid" && !preferred) || views.MappedView != (file == "valid" && preferred) {
					t.Fatal(views)
				}
				if file == "corrupt" {
					// Restoring valid bytes retries retained rejection, but does not clear
					// main's sticky mapped-unavailable certificate.
					if err := os.WriteFile(path, original, 0600); err != nil {
						t.Fatal(err)
					}
					calls = nil
					retried, r, err := s.PrepareMainDistinctSplitIndex(context.Background(), preferred, consume)
					if err != nil {
						t.Fatal(err)
					}
					if preferred {
						if retried != nil || r != MainDistinctIndexNone || len(calls) != 0 {
							t.Fatal(retried, r, calls)
						}
					} else if retried == nil || r != MainDistinctIndexRetained || len(calls) == 0 {
						t.Fatal(retried, r, calls)
					}
				}
				before := distinctEntryFiles(t, dir)
				if err := s.Close(); err != nil {
					t.Fatal(err)
				}
				if after := distinctEntryFiles(t, dir); !reflect.DeepEqual(before, after) {
					t.Fatal("load-only Close changed fixture", before, after)
				}
			})
		}
	}
}

// Exact bytes and filenames include absent/replaced sidecars: a load-only
// rejection must not be silently converted into persistence at Close.
func distinctEntryFiles(t *testing.T, dir string) map[string]string {
	t.Helper()
	out := map[string]string{}
	err := filepath.WalkDir(dir, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if path == dir {
			return nil
		}
		rel, err := filepath.Rel(dir, path)
		if err != nil {
			return err
		}
		if d.IsDir() {
			out[rel] = "directory"
			return nil
		}
		b, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		out[rel] = "file:" + string(b)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func TestMainDistinctSplitRetainedPriorityAndRawHandle(t *testing.T) {
	s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
	first, r, err := s.PrepareMainDistinctSplitIndex(context.Background(), false, nil)
	if err != nil || first == nil || r != MainDistinctIndexRetained {
		t.Fatal(first, r, err)
	}
	if err := os.Remove(filepath.Join(s.dir, callSiteIndexFile)); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	for _, preferred := range []bool{true, false} {
		got, r, err := s.PrepareMainDistinctSplitIndex(ctx, preferred, func(int64) error { t.Fatal("retained hit read again"); return nil })
		if err != nil || got == nil || r != MainDistinctIndexRetained || got.view != first.view || got.ordinary != first.ordinary {
			t.Fatal(got, r, err)
		}
	}
	before, err := s.StringPropertyIndexes(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	for _, parallel := range []bool{true, false} {
		raw, err := s.MainDistinctRawIndex(parallel)
		if err != nil || raw.owner != s || !raw.Raw || raw.ParallelRaw != parallel || raw.view != nil || raw.ordinary != nil {
			t.Fatal(raw, err)
		}
	}
	after, err := s.StringPropertyIndexes(context.Background())
	if err != nil || !reflect.DeepEqual(before, after) {
		t.Fatal(before, after, err)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	for _, preferred := range []bool{true, false} {
		got, r, err := s.PrepareMainDistinctSplitIndex(ctx, preferred, nil)
		if got != nil || r != MainDistinctIndexNone || !errors.Is(err, ErrStoreClosed) {
			t.Fatal(got, r, err)
		}
	}
	if got, err := s.MainDistinctRawIndex(true); got != nil || !errors.Is(err, ErrStoreClosed) {
		t.Fatal(got, err)
	}
}

func TestMainDistinctSplitWarmMappedFileGate(t *testing.T) {
	dir := copyIndexFixture(t)
	path := filepath.Join(dir, callSiteIndexFile)
	original, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	s := openIndexFixture(t, dir, "MAPPED")
	first, r, err := s.PrepareMainDistinctSplitIndex(context.Background(), true, nil)
	if err != nil || first == nil || r != MainDistinctIndexMapped {
		t.Fatal(first, r, err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	noWork := func(int64) error { t.Fatal("warm file gate read again"); return nil }
	for _, directory := range []bool{false, true} {
		if !directory {
			if err := os.Remove(path); err != nil {
				t.Fatal(err)
			}
		} else if err := os.Mkdir(path, 0700); err != nil {
			t.Fatal(err)
		}
		got, r, err := s.PrepareMainDistinctSplitIndex(ctx, true, noWork)
		if got != nil || r != MainDistinctIndexNone || err != nil {
			t.Fatal(got, r, err)
		}
		state, err := s.PersistedIndexState(context.Background())
		if err != nil || state.MappedViewUnavailable || state.RetainPreference {
			t.Fatal(state, err)
		}
		views, err := s.StringPropertyIndexes(context.Background())
		if err != nil || !views.MappedView || views.Retained {
			t.Fatal(views, err)
		}
	}
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, original, 0600); err != nil {
		t.Fatal(err)
	}
	got, r, err := s.PrepareMainDistinctSplitIndex(ctx, true, noWork)
	if err != nil || got == nil || r != MainDistinctIndexMapped || got.view != first.view || got.ordinary != first.ordinary {
		t.Fatal(got, r, err)
	}
	// A subsequent nonpreferred Split must load retained, not reuse the mapped
	// representation, and must mark retention before its first callback.
	got, r, err = s.PrepareMainDistinctSplitIndex(context.Background(), false, func(int64) error {
		state, err := s.PersistedIndexState(context.Background())
		if err != nil || !state.RetainPreference {
			t.Fatal(state, err)
		}
		return nil
	})
	if err != nil || got == nil || r != MainDistinctIndexRetained || got.view == first.view {
		t.Fatal(got, r, err)
	}
}

func TestMainDistinctSplitZeroCountAdmission(t *testing.T) {
	for _, preferred := range []bool{false, true} {
		t.Run(fmt.Sprint(preferred), func(t *testing.T) {
			s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
			// Owned metadata control: ensure zero CallSites declines before offsets,
			// identity, or the reader. This is not a new persisted-fixture JVM case.
			s.byKind["CallSiteNode"] = nil
			if err := os.WriteFile(filepath.Join(s.dir, "graph.nodeoffsets"), []byte("bad"), 0600); err != nil {
				t.Fatal(err)
			}
			got, r, err := s.PrepareMainDistinctSplitIndex(context.Background(), preferred, func(int64) error { t.Fatal("zero nodes read identity"); return nil })
			if err != nil || got != nil || r != MainDistinctIndexNone {
				t.Fatal(got, r, err)
			}
			state, err := s.PersistedIndexState(context.Background())
			if err != nil || state.MappedViewUnavailable != preferred || state.RetainPreference != !preferred {
				t.Fatal(state, err)
			}
			if s.distinctProjection.offsetsLoaded || s.distinctProjection.index != nil || s.distinctProjection.mappedView != nil {
				t.Fatal("zero-node entry initialized storage")
			}
		})
	}
}

func TestMainDistinctSplitCancellationAndClose(t *testing.T) {
	for _, preferred := range []bool{false, true} {
		for _, mode := range []string{"worker", "callback", "close"} {
			t.Run(fmt.Sprintf("mapped=%v/%s", preferred, mode), func(t *testing.T) {
				s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
				ctx, cancel := context.WithCancel(context.Background())
				defer cancel()
				if mode == "worker" {
					cancel()
				}
				cause := errors.New("distinct request rejected identity")
				calls := []int64{}
				var closeDone chan error
				got, r, err := s.PrepareMainDistinctSplitIndex(ctx, preferred, func(n int64) error {
					calls = append(calls, n)
					if _, _, err := s.MainRetainedProjectionIndex(); err != nil {
						return err
					} // callback outside lifetime lock
					if mode == "callback" {
						return cause
					}
					if mode == "close" {
						closeDone = make(chan error, 1)
						go func() { closeDone <- s.Close() }()
						s.callSiteIndex.mu.RLock()
						closing := s.callSiteIndex.closing
						s.callSiteIndex.mu.RUnlock()
						<-closing
					}
					return nil
				})
				if got != nil || r != MainDistinctIndexNone || !reflect.DeepEqual(calls, []int64{1}) {
					t.Fatal(got, r, err, calls)
				}
				switch mode {
				case "worker":
					if !errors.Is(err, context.Canceled) {
						t.Fatal(err)
					}
				case "callback":
					var aborted *WorkAbortedError
					if !errors.As(err, &aborted) || aborted.Cause != cause {
						t.Fatal(err)
					}
				case "close":
					if !errors.Is(err, ErrStoreClosed) {
						t.Fatal(err)
					}
					if err := <-closeDone; err != nil {
						t.Fatal(err)
					}
					return
				}
				state, err := s.PersistedIndexState(context.Background())
				if err != nil || state.MappedViewUnavailable || state.LoadedFromPersistence {
					t.Fatal(state, err)
				}
				retried, r, err := s.PrepareMainDistinctSplitIndex(context.Background(), preferred, nil)
				if err != nil || retried == nil || r == MainDistinctIndexNone {
					t.Fatal(retried, r, err)
				}
			})
		}
	}
}

func TestMainDistinctProjectionStringIDsReadOrder(t *testing.T) {
	// Owned byte-layout controls make the failure position observable through
	// the partial output. They exercise the decoder, not a fabricated graph query.
	for _, c := range []struct {
		name   string
		size   int
		want   [4]int32
		failed bool
	}{
		{"count-before-fields", 13, [4]int32{}, true},
		{"callee-after-caller", 21, [4]int32{11, 22, 0, 0}, true},
		{"callee-name-last", 25, [4]int32{11, 22, 33, 0}, true},
		{"all-four", 29, [4]int32{11, 22, 33, 44}, false},
	} {
		t.Run(c.name, func(t *testing.T) {
			data := make([]byte, 29)
			for at, n := range map[int]uint32{5: 11, 9: 22, 13: 0, 21: 33, 25: 44} {
				binary.BigEndian.PutUint32(data[at:], n)
			}
			offsets := make([]byte, 16)
			binary.BigEndian.PutUint64(offsets[8:], 1)
			s := &Store{mappedData: data[:c.size], distinctProjection: distinctProjectionState{offsetsLoaded: true, offsets: offsets}}
			got, err := s.MainDistinctProjectionStringIDs(0)
			var read *ProjectionReadError
			if got != c.want || (c.failed && (!errors.As(err, &read) || read.Message != nil)) || (!c.failed && err != nil) {
				t.Fatal(got, err, c.want)
			}
			s.callSiteIndex.closed = true
			got, err = s.MainDistinctProjectionStringIDs(0)
			if got != [4]int32{} || !errors.Is(err, ErrStoreClosed) {
				t.Fatal(got, err)
			}
		})
	}
	s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
	got, err := s.MainDistinctProjectionStringIDs(17)
	if err != nil {
		t.Fatal(err)
	}
	if name, err := s.MainMappedString(got[CallerName]); err != nil || name != "caller" {
		t.Fatal(got, name, err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := s.ProjectionStringIDs(ctx, 17); !errors.Is(err, context.Canceled) {
		t.Fatal("legacy API lost cancellation", err)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := s.MainDistinctProjectionStringIDs(17); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
}

func TestMainDistinctFallbackRetryAndBuild(t *testing.T) {
	for _, kind := range []string{"missing", "corrupt", "valid"} {
		t.Run(kind, func(t *testing.T) {
			dir := copyIndexFixture(t)
			path := filepath.Join(dir, callSiteIndexFile)
			switch kind {
			case "missing":
				if err := os.Remove(path); err != nil {
					t.Fatal(err)
				}
			case "corrupt":
				if err := os.WriteFile(path, []byte("bad"), 0600); err != nil {
					t.Fatal(err)
				}
			}
			s := openIndexFixture(t, dir, "MAPPED")
			initial := []int64{}
			first, rep, err := s.PrepareMainDistinctSplitIndex(context.Background(), false, func(n int64) error { initial = append(initial, n); return nil })
			if err != nil {
				t.Fatal(err)
			}
			if kind == "valid" {
				if first == nil || rep != MainDistinctIndexRetained {
					t.Fatal(first, rep)
				}
			} else if first != nil || rep != MainDistinctIndexNone {
				t.Fatal(first, rep)
			}
			calls := []int64{}
			index, ok, err := s.PrepareMainDistinctFallbackIndex(context.Background(), func(n int64) error {
				calls = append(calls, n)
				if _, err := s.PersistedIndexState(context.Background()); err != nil {
					return err
				}
				return nil
			})
			if err != nil || !ok || index == nil || index.Raw || index.ParallelRaw {
				t.Fatal(index, ok, err)
			}
			// Retained rejection is deliberately retried once at this final stage.
			// For this small fixture, each of the two raw passes has one final flush.
			want := []int64{}
			if kind != "valid" {
				want = append(want, initial...)
				n := int64(len(s.byKind["CallSiteNode"]))
				want = append(want, n, n)
			}
			if !reflect.DeepEqual(calls, want) {
				t.Fatal("optional retry / two raw pass boundaries", calls, want)
			}
			if kind == "corrupt" && (len(initial) == 0 || initial[0] != 1) {
				t.Fatal("missing initial identity read", initial)
			}
			if kind == "missing" && len(initial) != 0 {
				t.Fatal(initial)
			}
			state, err := s.PersistedIndexState(context.Background())
			if err != nil || !state.RetainPreference || state.LoadedFromPersistence != (kind == "valid") {
				t.Fatal(state, err)
			}
			sid := -1
			for n, value := range s.Strings {
				if value == "caller" {
					sid = n
					break
				}
			}
			if sid < 0 {
				t.Fatal("fixture missing caller")
			}
			ids, err := index.Postings(context.Background(), CallerName, int32(sid))
			if err != nil || !reflect.DeepEqual(ids, []int32{17, 90}) {
				t.Fatal("complete retained postings", ids, err)
			}
			ctx, cancel := context.WithCancel(context.Background())
			cancel()
			warm, ok, err := s.PrepareMainDistinctFallbackIndex(ctx, func(int64) error { t.Fatal("warm fallback read again"); return nil })
			if err != nil || !ok || warm == nil || warm.ordinary != index.ordinary {
				t.Fatal(warm, ok, err)
			}
			if err := s.Close(); err != nil {
				t.Fatal(err)
			}
			if _, ok, err := s.PrepareMainDistinctFallbackIndex(ctx, nil); ok || !errors.Is(err, ErrStoreClosed) {
				t.Fatal(ok, err)
			}
		})
	}
}

func TestMainDistinctFallbackDoesNotUseMappedAsRetained(t *testing.T) {
	s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
	mapped, r, err := s.PrepareMainDistinctSplitIndex(context.Background(), true, nil)
	if err != nil || mapped == nil || r != MainDistinctIndexMapped {
		t.Fatal(mapped, r, err)
	}
	calls := []int64{}
	retained, ok, err := s.PrepareMainDistinctFallbackIndex(context.Background(), func(n int64) error { calls = append(calls, n); return nil })
	if err != nil || !ok || retained == nil || retained.view == mapped.view || retained.MainMappedCapability() || len(calls) == 0 || calls[0] != 1 {
		t.Fatal(retained, ok, err, calls)
	}
	state, err := s.PersistedIndexState(context.Background())
	if err != nil || state.RetainPreference || !state.LoadedFromPersistence {
		t.Fatal("fallback changed initial preferred policy", state, err)
	}
}

func TestMainDistinctFallbackRejectAndZero(t *testing.T) {
	for _, reject := range []int{1, 2} {
		t.Run(fmt.Sprint(reject), func(t *testing.T) {
			dir := copyIndexFixture(t)
			if err := os.Remove(filepath.Join(dir, callSiteIndexFile)); err != nil {
				t.Fatal(err)
			}
			s := openIndexFixture(t, dir, "MAPPED")
			cause := errors.New("fallback raw pass rejected")
			calls := []int64{}
			index, ok, err := s.PrepareMainDistinctFallbackIndex(context.Background(), func(n int64) error {
				calls = append(calls, n)
				if len(calls) == reject {
					return cause
				}
				return nil
			})
			var aborted *WorkAbortedError
			if index != nil || ok || !errors.As(err, &aborted) || aborted.Cause != cause {
				t.Fatal(index, ok, err)
			}
			want := []int64{int64(len(s.byKind["CallSiteNode"]))}
			if reject == 2 {
				want = append(want, want[0])
			}
			if !reflect.DeepEqual(calls, want) {
				t.Fatal(calls, want)
			}
			state, err := s.PersistedIndexState(context.Background())
			if err != nil || state.MatchingNodeIDsCount != nil || state.RetainPreference {
				t.Fatal("partial fallback publication", state, err)
			}
			if _, ok, err := s.PrepareMainDistinctFallbackIndex(context.Background(), nil); err != nil || !ok {
				t.Fatal("retry poisoned", ok, err)
			}
		})
	}
	t.Run("zero-before-offsets", func(t *testing.T) {
		s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
		s.byKind["CallSiteNode"] = nil
		if err := os.WriteFile(filepath.Join(s.dir, "graph.nodeoffsets"), []byte("bad"), 0600); err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithCancel(context.Background())
		cancel()
		index, ok, err := s.PrepareMainDistinctFallbackIndex(ctx, func(int64) error { t.Fatal("zero fallback consumed work"); return nil })
		if err != nil || ok || index != nil || s.distinctProjection.offsetsLoaded {
			t.Fatal(index, ok, err)
		}
	})
	t.Run("cold-identity-before-worker", func(t *testing.T) {
		s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
		ctx, cancel := context.WithCancel(context.Background())
		cancel()
		calls := []int64{}
		index, ok, err := s.PrepareMainDistinctFallbackIndex(ctx, func(n int64) error { calls = append(calls, n); return nil })
		if index != nil || ok || !errors.Is(err, context.Canceled) || !reflect.DeepEqual(calls, []int64{1}) {
			t.Fatal(index, ok, err, calls)
		}
	})
}

func TestMainDistinctMembershipRepresentations(t *testing.T) {
	for _, representation := range []string{"mapped", "retained", "built"} {
		t.Run(representation, func(t *testing.T) {
			dir := copyIndexFixture(t)
			if representation == "built" {
				if err := os.Remove(filepath.Join(dir, callSiteIndexFile)); err != nil {
					t.Fatal(err)
				}
			}
			s := openIndexFixture(t, dir, "MAPPED")
			var index *DistinctStringIndex
			var err error
			if representation == "built" {
				index, _, err = s.PrepareMainDistinctFallbackIndex(context.Background(), nil)
			} else {
				index, _, err = s.PrepareMainDistinctSplitIndex(context.Background(), representation == "mapped", nil)
			}
			if err != nil || index == nil {
				t.Fatal(index, err)
			}
			before, err := s.StringPropertyIndexes(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			// Actual Java fixture expected.json: caller_name keys [0,7], callee_name
			// keys [5,7]. Search probe counts follow main's inclusive binary search.
			for _, c := range []struct {
				p     CallSiteStringProperty
				sid   int32
				found bool
				units int64
			}{
				{CallerName, 0, true, 1}, {CallerName, 7, true, 2}, {CallerName, 1, false, 2},
				{CallerName, -1, false, 1}, {CallerName, 99, false, 2}, {CalleeName, 5, true, 1}, {9, 0, false, 0},
			} {
				calls := []int64{}
				got, err := index.MainDistinctContainsPropertyStringID(c.p, c.sid, func(n int64) error {
					calls = append(calls, n)
					_, err := s.PersistedIndexState(context.Background())
					return err
				})
				want := []int64{}
				if c.units != 0 {
					want = append(want, c.units)
				}
				if err != nil || got != c.found || !reflect.DeepEqual(calls, want) {
					t.Fatal(c, got, err, calls, want)
				}
			}
			cause := errors.New("membership request rejected")
			got, err := index.MainDistinctContainsPropertyStringID(CallerName, 7, func(int64) error { return cause })
			var aborted *WorkAbortedError
			if got || !errors.As(err, &aborted) || aborted.Cause != cause {
				t.Fatal(got, err)
			}
			after, err := s.StringPropertyIndexes(context.Background())
			if err != nil || !reflect.DeepEqual(before, after) {
				t.Fatal("membership changed cache/range state", before, after, err)
			}
			if err := s.Close(); err != nil {
				t.Fatal(err)
			}
			got, err = index.MainDistinctContainsPropertyStringID(CallerName, 0, nil)
			if got || !errors.Is(err, ErrStoreClosed) {
				t.Fatal(got, err)
			}
		})
	}
}

func TestMainDistinctMembershipReadsOnlyDirectoryAndFlushesFailure(t *testing.T) {
	for _, size := range []int{4, 2} {
		for _, reject := range []bool{false, true} {
			t.Run(fmt.Sprintf("bytes=%d/reject=%v", size, reject), func(t *testing.T) {
				// Owned directory-only view: any posting-end/node read is out of bounds.
				// Both retained and mapped physical views share this scalar accessor.
				s := &Store{}
				data := make([]byte, 4)
				binary.BigEndian.PutUint32(data, 7)
				view := &CallSiteStringIndex{owner: s, data: data[:size]}
				view.info.UniqueStringCounts[CallerName] = 1
				view.regions[CallerName] = callSiteIndexRegion{strings: 0, ends: 1000, nodes: 2000}
				index := &DistinctStringIndex{owner: s, view: view}
				calls := []int64{}
				cause := errors.New("flush replaces read error")
				got, err := index.MainDistinctContainsPropertyStringID(CallerName, 7, func(n int64) error {
					calls = append(calls, n)
					if _, _, err := s.MainRetainedProjectionIndex(); err != nil {
						return err
					}
					if reject {
						return cause
					}
					return nil
				})
				if !reflect.DeepEqual(calls, []int64{1}) {
					t.Fatal(calls)
				}
				if reject {
					var aborted *WorkAbortedError
					if got || !errors.As(err, &aborted) || aborted.Cause != cause {
						t.Fatal(got, err)
					}
				} else if size == 4 {
					if !got || err != nil {
						t.Fatal(got, err)
					}
				} else {
					var read *ProjectionReadError
					if got || !errors.As(err, &read) || read.Message != nil {
						t.Fatal(got, err)
					}
				}
			})
		}
	}
}

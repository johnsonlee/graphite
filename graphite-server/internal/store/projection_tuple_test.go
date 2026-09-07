package store

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/johnsonlee/graphite/graphite-server/internal/javastring"
	"math"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
)

const tupleFixtures = "../query/testdata/exact-tuple/fixtures"

func openTupleFixture(t *testing.T, name string, modifiers ...func(string)) (*Store, *DistinctStringIndex) {
	t.Helper()
	dir := t.TempDir()
	files, err := os.ReadDir(filepath.Join(tupleFixtures, name))
	if err != nil {
		t.Fatal(err)
	}
	for _, file := range files {
		if file.IsDir() {
			continue
		}
		data, err := os.ReadFile(filepath.Join(tupleFixtures, name, file.Name()))
		if err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(filepath.Join(dir, file.Name()), data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	if name == "built4096" {
		if err := os.Remove(filepath.Join(dir, callSiteIndexFile)); err != nil && !os.IsNotExist(err) {
			t.Fatal(err)
		}
	}
	for _, modify := range modifiers {
		modify(dir)
	}
	s, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	i, ok, err := s.PrepareDistinctStringIndex(context.Background(), DistinctProjectionOptions{SourceCount: 1, Limit: 1, SkipPreparedPreference: true})
	if err != nil || !ok || i.Raw {
		t.Fatalf("prepare %v %v", ok, err)
	}
	return s, i
}
func tupleStrings(t *testing.T, s *Store, id int32) [4]string {
	t.Helper()
	var out [4]string
	for p := CallerClass; p <= CalleeName; p++ {
		sid, err := s.ProjectionStringID(context.Background(), id, p)
		if err != nil {
			t.Fatal(err)
		}
		v, err := s.ProjectionString(context.Background(), sid)
		if err != nil {
			t.Fatal(err)
		}
		out[p] = v
	}
	return out
}
func TestExactProjectionThresholdStateMain(t *testing.T) {
	var oracle []struct {
		Before, After struct {
			Bytes          int64
			Tuple, Enabled bool
		}
	}
	data, err := os.ReadFile("../query/testdata/exact-tuple/main.json")
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(data, &oracle); err != nil {
		t.Fatal(err)
	}
	for _, tt := range []struct {
		name             string
		selected, oracle int
	}{{"n4095", 256, 0}, {"n4096", 255, 1}, {"n4096", 256, 2}, {"built4096", 256, 3}} {
		t.Run(fmt.Sprintf("%s-selected%d", tt.name, tt.selected), func(t *testing.T) {
			s, i := openTupleFixture(t, tt.name)
			ctx := context.Background()
			if err = i.PrepareProjectionTrigrams(ctx); err != nil {
				t.Fatal(err)
			}
			before, err := i.ProjectionPlannerBytes(ctx)
			if err != nil {
				t.Fatal(err)
			}
			if before != oracle[tt.oracle].Before.Bytes {
				t.Fatalf("before=%d main=%d", before, oracle[tt.oracle].Before.Bytes)
			}
			prepared, err := i.PrepareExactProjectionTuples(ctx, tt.selected)
			if err != nil {
				t.Fatal(err)
			}
			after, err := i.ProjectionPlannerBytes(ctx)
			if err != nil {
				t.Fatal(err)
			}
			want := oracle[tt.oracle].After
			if prepared != want.Tuple || after != want.Bytes {
				t.Fatalf("prepared=%v bytes=%d want=%+v", prepared, after, want)
			}
			if !prepared {
				return
			}
			// Every duplicate raw tuple resolves to the least persisted encounter order,
			// irrespective of its node ID or the CSR insertion/probe order.
			expected := tupleStrings(t, s, 0)
			probe, err := i.ExactProjectionProbe(ctx, expected)
			if err != nil {
				t.Fatal(err)
			}
			id, ok, err := probe.Next(ctx)
			if err != nil || !ok {
				t.Fatalf("probe=%d %v %v", id, ok, err)
			}
			var best int32
			bestOrder := int64(1<<63 - 1)
			for candidate := int32(0); candidate < 4096; candidate += 256 {
				order, err := s.ProjectionNodeOrder(ctx, candidate)
				if err != nil {
					t.Fatal(err)
				}
				if order < bestOrder {
					bestOrder = order
					best = candidate
				}
			}
			if id != best {
				t.Fatalf("node=%d want earliest=%d", id, best)
			}
			if _, ok, err = probe.Next(ctx); ok || err != nil {
				t.Fatalf("duplicate tuple retained %v %v", ok, err)
			}
			if err = i.CacheProjectionIDs(ctx, ProjectionStringMatches, "key", []int32{1}, 112); err != nil {
				t.Fatal(err)
			}
			if err = i.ClearProjectionQueryCaches(ctx); err != nil {
				t.Fatal(err)
			}
			size, _ := i.ProjectionPlannerBytes(ctx)
			if size != after {
				t.Fatalf("clear removed tuple: %d", size)
			}
			if prepared, err = i.PrepareExactProjectionTuples(ctx, 1); !prepared || err != nil {
				t.Fatalf("warm below threshold %v %v", prepared, err)
			}
			if err = s.Close(); err != nil {
				t.Fatal(err)
			}
			if i.ordinary.exactTuple != nil {
				t.Fatal("close retained table")
			}
			if _, _, err = probe.Next(ctx); !errors.Is(err, ErrStoreClosed) {
				t.Fatalf("closed probe=%v", err)
			}
		})
	}
}
func TestExactProjectionBadSIDRollsBack(t *testing.T) {
	_, i := openTupleFixture(t, "bad4096")
	ctx := context.Background()
	before, _ := i.ProjectionPlannerBytes(ctx)
	for repeat := 0; repeat < 2; repeat++ {
		prepared, err := i.PrepareExactProjectionTuples(ctx, 256)
		var raw *ProjectionReadError
		if prepared || !errors.As(err, &raw) || raw.Class != "ArrayIndexOutOfBoundsException" || raw.Message == nil || *raw.Message != "Index 2147483647 out of bounds for length 260" {
			t.Fatalf("prepare %v %v", prepared, err)
		}
		after, _ := i.ProjectionPlannerBytes(ctx)
		if after != before || i.ordinary.exactTuple != nil {
			t.Fatalf("published failed build: %d %d", before, after)
		}
	}
}

type tupleCancelContext struct {
	context.Context
	checks atomic.Int64
	at     int64
}

func (c *tupleCancelContext) Err() error {
	if c.checks.Add(1) >= c.at {
		return context.Canceled
	}
	return nil
}
func TestExactProjectionCancellationAndRetry(t *testing.T) {
	// Deterministic checkpoint count comes from this CSR and actual implementation;
	// only a few boundaries are exercised, with no timing or performance gate.
	_, base := openTupleFixture(t, "n4096")
	counter := &tupleCancelContext{Context: context.Background(), at: 1 << 62}
	if ok, err := base.PrepareExactProjectionTuples(counter, 256); !ok || err != nil {
		t.Fatal(ok, err)
	}
	last := counter.checks.Load()
	for _, at := range []int64{1, 5, 80, last - 1, last} {
		t.Run(fmt.Sprintf("check%d", at), func(t *testing.T) {
			_, i := openTupleFixture(t, "n4096")
			before, _ := i.ProjectionPlannerBytes(context.Background())
			ctx := &tupleCancelContext{Context: context.Background(), at: at}
			ok, err := i.PrepareExactProjectionTuples(ctx, 256)
			if ok || !errors.Is(err, context.Canceled) {
				t.Fatalf("at%d got %v %v", at, ok, err)
			}
			after, _ := i.ProjectionPlannerBytes(context.Background())
			if before != after || i.ordinary.exactTuple != nil {
				t.Fatal("canceled build published")
			}
			if ok, err = i.PrepareExactProjectionTuples(context.Background(), 256); !ok || err != nil {
				t.Fatal("retry", ok, err)
			}
		})
	}
}
func TestExactProjectionConcurrentClose(t *testing.T) {
	s, i := openTupleFixture(t, "n4096")
	ctx := context.Background()
	var wg sync.WaitGroup
	for n := 0; n < 8; n++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			ok, err := i.PrepareExactProjectionTuples(ctx, 256)
			if err != nil && !errors.Is(err, ErrStoreClosed) {
				t.Error(err)
			}
			if err == nil && !ok {
				t.Error("unexpected unavailable")
			}
		}()
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	wg.Wait()
	if i.ordinary.exactTuple != nil {
		t.Fatal("table published after close")
	}
}

func TestExactProjectionTupleHashMain(t *testing.T) {
	var cases []struct {
		Units  [4][]uint16
		Hashes [4]int32
		Hash   string
		Slot   int
	}
	data, err := os.ReadFile("../query/testdata/exact-tuple/hash-main.json")
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(data, &cases); err != nil {
		t.Fatal(err)
	}
	for at, want := range cases {
		var hashes [4]int32
		for p, units := range want.Units {
			got, err := projectionJavaHash(context.Background(), javastring.FromUTF16(units))
			if err != nil || got != want.Hashes[p] {
				t.Fatalf("case%d property%d hash=%d want=%d err=%v", at, p, got, want.Hashes[p], err)
			}
			hashes[p] = got
		}
		h := projectionTupleHash(hashes)
		if strconv.FormatUint(h, 10) != want.Hash || projectionTupleSlot(h, 8192) != want.Slot {
			t.Fatalf("case%d tupleHash=%d slot=%d want=%s %d", at, h, projectionTupleSlot(h, 8192), want.Hash, want.Slot)
		}
	}
}
func TestExactProjectionCollisionAndSIDIdentity(t *testing.T) {
	// These are storage-level representations, not a new persisted-oracle claim.
	// The two original caller-name SID groups keep their actual CSR and offsets.
	for _, tt := range []struct {
		name, a, b string
		equal      bool
	}{{"hash-collision", "Aa", "BB", false}, {"hash-zero", "", "\x00", false}, {"duplicate-SID-values", "Aa", "Aa", true}, {"UTF16-equivalent-SIDs", "😀", "\xed\xa0\xbd\xed\xb8\x80", true}} {
		t.Run(tt.name, func(t *testing.T) {
			s, i := openTupleFixture(t, "n4096")
			ctx := context.Background()
			sid0, err := s.ProjectionStringID(ctx, 0, CallerName)
			if err != nil {
				t.Fatal(err)
			}
			sid1, err := s.ProjectionStringID(ctx, 1, CallerName)
			if err != nil {
				t.Fatal(err)
			}
			s.Strings[sid0] = tt.a
			s.Strings[sid1] = tt.b
			if ok, err := i.PrepareExactProjectionTuples(ctx, 256); !ok || err != nil {
				t.Fatal(ok, err)
			}
			best := func(remainder int32) int32 {
				var id int32
				order := int64(math.MaxInt64)
				for n := remainder; n < 4096; n += 256 {
					o, err := s.ProjectionNodeOrder(ctx, n)
					if err != nil {
						t.Fatal(err)
					}
					if o < order {
						order = o
						id = n
					}
				}
				return id
			}
			for input := int32(0); input < 2; input++ {
				expected := tupleStrings(t, s, input)
				p, err := i.ExactProjectionProbe(ctx, expected)
				if err != nil {
					t.Fatal(err)
				}
				got := map[int32]bool{}
				for {
					id, ok, err := p.Next(ctx)
					if err != nil {
						t.Fatal(err)
					}
					if !ok {
						break
					}
					got[id] = true
				}
				want := map[int32]bool{best(input): true}
				if tt.equal {
					want[best(0)] = true
					want[best(1)] = true
				}
				if !reflect.DeepEqual(got, want) {
					t.Fatalf("input%d got=%v want=%v", input, got, want)
				}
			}
		})
	}
}

func TestExactProjectionReadOrderErrorsMain(t *testing.T) {
	var cases []struct {
		Property      int
		SID           int32
		BadRead       bool
		ErrorClass    string
		Error         *string
		Before, After struct {
			Bytes int64
			Tuple bool
		}
	}
	raw, err := os.ReadFile("../query/testdata/exact-tuple/bad-sid-main.json")
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(raw, &cases); err != nil {
		t.Fatal(err)
	}
	for n, want := range cases {
		t.Run(fmt.Sprint(n), func(t *testing.T) {
			_, i := openTupleFixture(t, "n4096", func(dir string) {
				data, err := os.ReadFile(filepath.Join(dir, "graph.nodedata"))
				if err != nil {
					t.Fatal(err)
				}
				offsets, err := os.ReadFile(filepath.Join(dir, "graph.nodeoffsets"))
				if err != nil {
					t.Fatal(err)
				}
				at := int(binary.BigEndian.Uint64(offsets[8+4095*8:])) - 1
				count := int(int32(binary.BigEndian.Uint32(data[at+13:])))
				field := at + 5 + want.Property*4
				if want.Property >= 2 {
					field = at + 5 + (4+count)*4 + (want.Property-2)*4
				}
				binary.BigEndian.PutUint32(data[field:], uint32(want.SID))
				if want.BadRead {
					binary.BigEndian.PutUint32(data[at+13:], 1000000)
				}
				if err = os.WriteFile(filepath.Join(dir, "graph.nodedata"), data, 0600); err != nil {
					t.Fatal(err)
				}
			})
			ctx := context.Background()
			before, err := i.ProjectionPlannerBytes(ctx)
			if err != nil || before != want.Before.Bytes {
				t.Fatalf("before=%d want=%d err=%v", before, want.Before.Bytes, err)
			}
			ok, err := i.PrepareExactProjectionTuples(ctx, 256)
			var actual *ProjectionReadError
			if ok || !errors.As(err, &actual) {
				t.Fatalf("got %v %v", ok, err)
			}
			class := actual.Class
			if class == "" {
				class = "IndexOutOfBoundsException"
			}
			if class != want.ErrorClass || !reflect.DeepEqual(actual.Message, want.Error) {
				t.Fatalf("error=%s %#v want=%s %#v", class, actual.Message, want.ErrorClass, want.Error)
			}
			after, _ := i.ProjectionPlannerBytes(ctx)
			if after != want.After.Bytes || i.ordinary.exactTuple != nil {
				t.Fatalf("failure published bytes=%d want=%d", after, want.After.Bytes)
			}
		})
	}
}

package store

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"sync/atomic"
	"testing"
)

// These controlled CSR inputs reproduce the actual JVM PostingAggregateOracle.
// The source is an existing tiny JVM store; only the counted-property raw reads
// are redirected to controlled records. No synthetic timings are collected.
func aggregationFixture(t *testing.T, postingCount int, invalidRaw bool) (*Store, *DistinctStringIndex) {
	t.Helper()
	dir := t.TempDir()
	source := "../query/testdata/candidate-index/clean"
	files, err := os.ReadDir(source)
	if err != nil {
		t.Fatal(err)
	}
	for _, file := range files {
		if file.IsDir() {
			continue
		}
		data, err := os.ReadFile(filepath.Join(source, file.Name()))
		if err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(filepath.Join(dir, file.Name()), data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	dataPath := filepath.Join(dir, "graph.nodedata")
	data, err := os.ReadFile(dataPath)
	if err != nil {
		t.Fatal(err)
	}
	sid := uint32(0)
	if invalidRaw {
		sid = 2147483647
	}
	binary.BigEndian.PutUint32(data[78:82], sid)
	// The second controlled property decodes string ID1; its unrelated tail is
	// deliberately absent. Sparse aggregation must consume only CallerName.
	secondOffset := len(data)
	data = append(data, make([]byte, 13)...)
	binary.BigEndian.PutUint32(data[secondOffset+9:], 1)
	if err = os.WriteFile(dataPath, data, 0600); err != nil {
		t.Fatal(err)
	}
	offsetsPath := filepath.Join(dir, "graph.nodeoffsets")
	original, err := os.ReadFile(offsetsPath)
	if err != nil {
		t.Fatal(err)
	}
	offsets := make([]byte, 8+128*8)
	copy(offsets[:4], original[:4])
	binary.BigEndian.PutUint32(offsets[4:8], 128)
	for id, offset := range map[int]int{1: 69, 3: secondOffset, 65: 69} {
		binary.BigEndian.PutUint64(offsets[8+id*8:], uint64(offset+1))
	}
	if err = os.WriteFile(offsetsPath, offsets, 0600); err != nil {
		t.Fatal(err)
	}
	s, err := OpenMode(dir, "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.Close() })
	index := &DistinctStringIndex{owner: s}
	ids := make([]int32, postingCount)
	for at := range ids {
		if at%2 == 0 {
			ids[at] = 1
		} else {
			ids[at] = 65
		}
	}
	index.entries[CallerName] = map[int32][]int32{0: ids}
	return s, index
}

func TestStringAggregationActualMainThresholdAndUnion(t *testing.T) {
	var oracle []struct {
		PostingCount     int
		FailRaw          bool
		Count            int64
		Values           []string
		InvalidPostingID *int32
		Error, Message   string
	}
	data, err := os.ReadFile("../../../docs/go-server-baseline/native-filtered-string-aggregation/posting-main.json")
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(data, &oracle); err != nil {
		t.Fatal(err)
	}
	if len(oracle) != 9 {
		t.Fatalf("actual JVM observations=%d", len(oracle))
	}
	property := CallerName
	for _, observation := range oracle[:6] {
		t.Run(fmt.Sprintf("postings%d-invalidraw%v", observation.PostingCount, observation.FailRaw), func(t *testing.T) {
			_, index := aggregationFixture(t, observation.PostingCount, observation.FailRaw)
			count, values, err := index.AggregateProjectionRanges(context.Background(), [][]int32{{1, 1, 3, 65}}, &property)
			if observation.FailRaw && observation.PostingCount >= 12 {
				var read *ProjectionReadError
				if !errors.As(err, &read) || read.Error() != "Index (2147483647) is greater than or equal to list size (9)" {
					t.Fatalf("sparse branch must consume invalid selected SID: count=%d values=%v err=%v", count, values, err)
				}
				return
			}
			if err != nil || count != observation.Count || !reflect.DeepEqual(values, observation.Values) {
				t.Fatalf("count=%d values=%v err=%v; JVM count=%d values=%v", count, values, err, observation.Count, observation.Values)
			}
			// Both duplicates within a range and across predicates count once.
			count, values, err = index.AggregateProjectionRanges(context.Background(), [][]int32{{1, 1, 3}, {3, 65, 1}}, nil)
			if err != nil || count != 3 || values != nil {
				t.Fatalf("OR union count=%d values=%v err=%v", count, values, err)
			}
		})
	}
	for _, observation := range oracle[6:] {
		t.Run(fmt.Sprintf("invalidPosting%d", *observation.InvalidPostingID), func(t *testing.T) {
			_, index := aggregationFixture(t, 11, false)
			_, _, err := index.AggregateProjectionRanges(context.Background(), [][]int32{{*observation.InvalidPostingID}}, nil)
			var read *ProjectionReadError
			if !errors.As(err, &read) || read.Class != "ArrayIndexOutOfBoundsException" || read.Message == nil || *read.Message != observation.Message {
				t.Fatalf("error=%#v; JVM=%s %s", err, observation.Error, observation.Message)
			}
		})
	}
}

type aggregationCancelContext struct {
	context.Context
	checks atomic.Int32
	cancel context.CancelFunc
}

func (c *aggregationCancelContext) Err() error {
	if c.checks.Add(1) == 4 {
		c.cancel()
	}
	return c.Context.Err()
}

func TestStringAggregationCancellationClosedStoreAndUnusedRaw(t *testing.T) {
	s, index := aggregationFixture(t, 12, true)
	// COUNT(*) does not consume the corrupt counted field even on the sparse side
	// of the DISTINCT threshold. There is no hidden full-node validation.
	count, values, err := index.AggregateProjectionRanges(context.Background(), [][]int32{{1, 3, 65}}, nil)
	if err != nil || count != 3 || values != nil {
		t.Fatalf("plain count=%d values=%v err=%v", count, values, err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, _, err = index.AggregateProjectionRanges(ctx, [][]int32{{1}}, nil); !errors.Is(err, context.Canceled) {
		t.Fatalf("pre-cancel=%v", err)
	}
	ctx, cancel = context.WithCancel(context.Background())
	defer cancel()
	controlled := &aggregationCancelContext{Context: ctx, cancel: cancel}
	property := CallerName
	if _, _, err = index.AggregateProjectionRanges(controlled, [][]int32{{1, 3, 65}}, &property); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancellation during selected-property consumption checks=%d err=%v", controlled.checks.Load(), err)
	}
	count, _, err = index.AggregateProjectionRanges(context.Background(), [][]int32{{1, 3, 65}}, nil)
	if err != nil || count != 3 {
		t.Fatalf("fresh request after cancellation count=%d err=%v", count, err)
	}
	if err = s.Close(); err != nil {
		t.Fatal(err)
	}
	if _, _, err = index.AggregateProjectionRanges(context.Background(), [][]int32{{1}}, nil); !errors.Is(err, ErrStoreClosed) {
		t.Fatalf("closed=%v", err)
	}
}

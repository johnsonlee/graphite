package store

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func buildOrdinaryTestIndex(t *testing.T) (*Store, *DistinctStringIndex, string) {
	t.Helper()
	dir := copyIndexFixture(t)
	if err := os.Remove(filepath.Join(dir, callSiteIndexFile)); err != nil {
		t.Fatal(err)
	}
	s := openIndexFixture(t, dir, "MAPPED")
	index, ok, err := s.PrepareDistinctStringIndex(context.Background(), DistinctProjectionOptions{SourceCount: 1, Limit: 10, SkipPreparedPreference: true})
	if err != nil || !ok || index.Raw {
		t.Fatalf("build %v %v", ok, err)
	}
	return s, index, dir
}
func TestProjectionPersistenceMatchesMainBytes(t *testing.T) {
	s, index, dir := buildOrdinaryTestIndex(t)
	if err := index.PrepareProjectionTrigrams(context.Background()); err != nil {
		t.Fatal(err)
	}
	if exists, err := s.PreparedProjectionFile(context.Background()); exists || err != nil {
		t.Fatalf("persisted before close: %v %v", exists, err)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	actual, err := os.ReadFile(filepath.Join(dir, callSiteIndexFile))
	if err != nil {
		t.Fatal(err)
	}
	expected, err := os.ReadFile(filepath.Join(indexFixture, callSiteIndexFile))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(actual, expected) {
		t.Fatalf("writer differs from main: %d vs %d bytes", len(actual), len(expected))
	}
	reopened := openIndexFixture(t, dir, "MAPPED")
	view, ok, err := reopened.TryCallSiteStringIndex(context.Background())
	if err != nil || !ok {
		t.Fatalf("reopen %v %v", ok, err)
	}
	postings, err := view.Postings(context.Background(), CallerName, 1)
	if err != nil {
		t.Fatal(err)
	}
	// Compare all directories/postings to the original JVM persisted index.
	reference := openIndexFixture(t, indexFixture, "MAPPED")
	original, ok, err := reference.TryCallSiteStringIndex(context.Background())
	if err != nil || !ok {
		t.Fatalf("original %v %v", ok, err)
	}
	originalPostings, err := original.Postings(context.Background(), CallerName, 1)
	if err != nil || fmt.Sprint(postings) != fmt.Sprint(originalPostings) {
		t.Fatalf("postings %v want %v error %v", postings, originalPostings, err)
	}
}
func TestProjectionPersistenceRequiresCompletedPreparation(t *testing.T) {
	for _, at := range []int32{1, 3, 8, 15} {
		t.Run(fmt.Sprint(at), func(t *testing.T) {
			s, index, dir := buildOrdinaryTestIndex(t)
			ctx, cancel := context.WithCancel(context.Background())
			counted := &cancelAtPoll{Context: ctx, cancel: cancel, at: at}
			if err := index.PrepareProjectionTrigrams(counted); !errors.Is(err, context.Canceled) {
				t.Fatalf("cancel at %d: %v", at, err)
			}
			cancel()
			if err := s.Close(); err != nil {
				t.Fatal(err)
			}
			if _, err := os.Stat(filepath.Join(dir, callSiteIndexFile)); !errors.Is(err, os.ErrNotExist) {
				t.Fatalf("incomplete preparation persisted: %v", err)
			}
		})
	}
	s, _, dir := buildOrdinaryTestIndex(t)
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, callSiteIndexFile)); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("unprepared index persisted: %v", err)
	}
}
func TestProjectionPersistenceBestEffortAndConcurrentClose(t *testing.T) {
	s, index, dir := buildOrdinaryTestIndex(t)
	if err := index.PrepareProjectionTrigrams(context.Background()); err != nil {
		t.Fatal(err)
	}
	// An existing directory forbids atomic replacement without depending on uid or chmod.
	if err := os.Mkdir(filepath.Join(dir, callSiteIndexFile), 0700); err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for j := 0; j < 8; j++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for n := 0; n < 20; n++ {
				err := index.PrepareProjectionTrigrams(context.Background())
				if err != nil && !errors.Is(err, ErrStoreClosed) {
					t.Errorf("read/close %v", err)
				}
			}
		}()
	}
	if err := s.Close(); err != nil {
		t.Fatalf("optional persistence changed close: %v", err)
	}
	wg.Wait()
	if _, err := index.Directory(context.Background(), CallerName); !errors.Is(err, ErrStoreClosed) {
		t.Fatalf("closed handle=%v", err)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		if filepath.Ext(entry.Name()) == ".tmp" {
			t.Errorf("temporary file leaked: %s", entry.Name())
		}
	}
}
func TestDecoderStringReferenceCausePreservesDiagnostics(t *testing.T) {
	for _, id := range []int32{-1, 3, 2147483647} {
		raw := binary.BigEndian.AppendUint32(nil, uint32(id))
		d := newDecoder(bytes.NewReader(raw), int64(len(raw)), []string{"zero", "one", "two"})
		if value := d.str(); value != "" {
			t.Fatalf("invalid SID produced %q", value)
		}
		var cause *StringTableReferenceError
		if !errors.As(d.err, &cause) || cause.Index != id || cause.Size != 3 {
			t.Fatalf("typed SID %d: %#v", id, d.err)
		}
		if d.err.Error() != fmt.Sprintf("string index %d outside table of 3", id) {
			t.Fatalf("diagnostic changed: %v", d.err)
		}
	}
	d := newDecoder(bytes.NewReader([]byte{255}), 1, []string{"zero"})
	d.str()
	var cause *StringTableReferenceError
	if errors.As(d.err, &cause) || d.err.Error() != "truncated data: need 4 bytes, have 1" {
		t.Fatalf("earlier format error replaced: %v", d.err)
	}
}

func TestProjectionCacheValuesAreOwnedAndAccessOrdered(t *testing.T) {
	_, index, _ := buildOrdinaryTestIndex(t)
	ctx := context.Background()
	ids := []int32{17, 2}
	if err := index.CacheProjectionIDs(ctx, ProjectionNodeMatches, "first", ids, 152); err != nil {
		t.Fatal(err)
	}
	ids[0] = 99
	returned, hit, err := index.ProjectionCachedIDs(ctx, ProjectionNodeMatches, "first")
	if err != nil || !hit || returned[0] != 17 {
		t.Fatalf("stored IDs %v %v %v", returned, hit, err)
	}
	returned[0] = 88
	rows := [][]string{{"original"}}
	if err := index.CacheProjectionRows(ctx, "row", rows, 200); err != nil {
		t.Fatal(err)
	}
	rows[0][0] = "modified"
	returnedRows, hit, err := index.ProjectionCachedRows(ctx, "row")
	if err != nil || !hit || returnedRows[0][0] != "original" {
		t.Fatalf("stored rows %v %v %v", returnedRows, hit, err)
	}
	returnedRows[0][0] = "second mutation"
	again, hit, err := index.ProjectionCachedRows(ctx, "row")
	if err != nil || !hit || again[0][0] != "original" {
		t.Fatalf("read rows escaped %v %v %v", again, hit, err)
	}
	for i := 0; i < 31; i++ {
		if err := index.CacheProjectionIDs(ctx, ProjectionNodeMatches, fmt.Sprint(i), []int32{int32(i)}, 148); err != nil {
			t.Fatal(err)
		}
	}
	if _, hit, err := index.ProjectionCachedIDs(ctx, ProjectionNodeMatches, "first"); err != nil || !hit {
		t.Fatalf("touch first %v %v", hit, err)
	}
	if err := index.CacheProjectionIDs(ctx, ProjectionNodeMatches, "new", []int32{31}, 148); err != nil {
		t.Fatal(err)
	}
	if _, hit, err := index.ProjectionCachedIDs(ctx, ProjectionNodeMatches, "0"); err != nil || hit {
		t.Fatalf("least-recent entry survived %v %v", hit, err)
	}
	returned, hit, err = index.ProjectionCachedIDs(ctx, ProjectionNodeMatches, "first")
	if err != nil || !hit || returned[0] != 17 {
		t.Fatalf("touched/owned IDs %v %v %v", returned, hit, err)
	}
}

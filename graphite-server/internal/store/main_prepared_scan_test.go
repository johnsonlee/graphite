package store

import (
	"bytes"
	"context"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestMainPreparedStringScanFilePresence(t *testing.T) {
	for _, mode := range []string{"valid", "malformed", "missing", "directory"} {
		t.Run(mode, func(t *testing.T) {
			dir := copyIndexFixture(t) // Actual pinned-main writer output.
			path := filepath.Join(dir, callSiteIndexFile)
			switch mode {
			case "malformed":
				if err := os.WriteFile(path, []byte("not an index"), 0600); err != nil {
					t.Fatal(err)
				}
			case "missing", "directory":
				if err := os.Remove(path); err != nil {
					t.Fatal(err)
				}
				if mode == "directory" {
					if err := os.Mkdir(path, 0700); err != nil {
						t.Fatal(err)
					}
				}
			}
			original, _ := os.ReadFile(path)
			s := openIndexFixture(t, dir, "MAPPED")
			before, err := s.StringPropertyIndexes(context.Background())
			if err != nil || before.Retained || before.MappedView {
				t.Fatal("fixture unexpectedly prepared", before, err)
			}
			persistedBefore, err := s.PersistedIndexState(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			for _, annotations := range []bool{false, true} {
				got, err := s.MainPreparedStringScan(annotations)
				want := mode == "valid" || mode == "malformed"
				if err != nil || got != want {
					t.Fatalf("includeAnnotations=%t: %t/%v, want %t/nil", annotations, got, err, want)
				}
			}
			after, err := s.StringPropertyIndexes(context.Background())
			if err != nil || !reflect.DeepEqual(after, before) {
				t.Fatal("presence check loaded/published index state", before, after, err)
			}
			persistedAfter, err := s.PersistedIndexState(context.Background())
			if err != nil || !reflect.DeepEqual(persistedAfter, persistedBefore) {
				t.Fatal("presence check changed persisted state", persistedBefore, persistedAfter, err)
			}
			if mode == "valid" || mode == "malformed" {
				afterBytes, err := os.ReadFile(path)
				if err != nil || !bytes.Equal(original, afterBytes) {
					t.Fatal("presence check changed sidecar", err)
				}
			}
			if err := s.Close(); err != nil {
				t.Fatal(err)
			}
			if got, err := s.MainPreparedStringScan(false); got || !errors.Is(err, ErrStoreClosed) {
				t.Fatal("closed capability", got, err)
			}
		})
	}
}

func TestMainPreparedStringScanOwnedMetadata(t *testing.T) {
	// Owned metadata controls exercise only the capability gate. No synthetic
	// graph is queried or used for performance measurements.
	for _, tc := range []struct {
		name                       string
		calls, annotations         bool
		retainedBytes              int64
		includeAnnotations, wanted bool
	}{
		{name: "empty bypasses missing file", wanted: true},
		{name: "generic empty bypasses missing file", includeAnnotations: true, wanted: true},
		{name: "callsite missing file", calls: true},
		{name: "retained small without sidecar", calls: true, retainedBytes: 512, wanted: true},
		{name: "retained exact serial boundary", calls: true, retainedBytes: 1024 * 1024, wanted: true},
		{name: "retained above serial boundary", calls: true, retainedBytes: 1024*1024 + 1},
		{name: "generic Annotation requires capability", annotations: true, includeAnnotations: true},
		{name: "CallSite label ignores Annotation", annotations: true, wanted: true},
		{name: "generic Annotation defeats retained", calls: true, annotations: true, retainedBytes: 512, includeAnnotations: true},
		{name: "CallSite retained ignores Annotation", calls: true, annotations: true, retainedBytes: 512, wanted: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s := &Store{Mode: "MAPPED", dir: filepath.Join(t.TempDir(), "absent"), byKind: map[string][]int32{}}
			if tc.calls {
				s.byKind["CallSiteNode"] = []int32{1}
			}
			if tc.annotations {
				s.byKind["AnnotationNode"] = []int32{2}
			}
			if tc.retainedBytes != 0 {
				s.distinctProjection.index = &DistinctStringIndex{owner: s, view: &CallSiteStringIndex{info: CallSiteStringIndexInfo{RetainedBytes: tc.retainedBytes}}}
			}
			got, err := s.MainPreparedStringScan(tc.includeAnnotations)
			if err != nil || got != tc.wanted {
				t.Fatalf("got %t/%v, want %t/nil", got, err, tc.wanted)
			}
		})
	}
}

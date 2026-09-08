package store

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"math"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// The fixture is the original Java-written all-types graph and its archived
// mutations from the 201-case public oracle. Never modify the frozen fixture.
func projectionEnumFixture(t *testing.T, variant string) string {
	t.Helper()
	f, err := os.Open("../../../docs/go-server-baseline/native-generic-string-disjunction/fixtures.tar.gz")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	gz, err := gzip.NewReader(f)
	if err != nil {
		t.Fatal(err)
	}
	defer gz.Close()
	tr := tar.NewReader(gz)
	dir := t.TempDir()
	count := 0
	for {
		h, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
		if !strings.HasPrefix(h.Name, variant+"/") {
			continue
		}
		name := strings.TrimPrefix(h.Name, variant+"/")
		if h.Typeflag != tar.TypeReg || filepath.Base(name) != name {
			t.Fatalf("unexpected fixture entry %q", h.Name)
		}
		data, err := io.ReadAll(tr)
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, name), data, 0600); err != nil {
			t.Fatal(err)
		}
		count++
	}
	if count != 12 {
		t.Fatalf("fixture %q files=%d, want 12", variant, count)
	}
	return dir
}

func TestProjectionEnumActualMainArgumentConsumption(t *testing.T) {
	readers := []struct {
		name string
		read func(*Store) (Node, bool, error)
	}{
		{"candidate", func(s *Store) (Node, bool, error) { return s.ProjectionCandidateNode(context.Background(), 14) }},
		{"main-ordinary", func(s *Store) (Node, bool, error) { return s.MainProjectionCandidateNode(14) }},
	}
	for _, reader := range readers {
		t.Run(reader.name, func(t *testing.T) {
			for _, variant := range []string{"clean", "enum-bad-tail", "enum-bad-sid-0", "enum-bad-sid-1", "enum-invalid-argument-tag"} {
				t.Run(variant, func(t *testing.T) {
					s, err := OpenMode(projectionEnumFixture(t, variant), "MAPPED")
					if err != nil {
						t.Fatal(err)
					}
					t.Cleanup(func() { s.Close() })
					n, present, err := reader.read(s)
					if strings.HasPrefix(variant, "enum-bad-") {
						want := int32(math.MaxInt32)
						if variant == "enum-bad-tail" {
							// Both original and repeated Java public executions read
							// across the serialized tail and first fail at this SID.
							want = 37
						}
						var sid *StringTableReferenceError
						if present || !errors.As(err, &sid) || sid.Index != want || sid.Size != 21 {
							t.Fatalf("node=%#v present=%v error=%#v, want SID %d/list 21", n, present, err, want)
						}
						if variant == "enum-bad-tail" {
							// Strict Node validation is deliberately a different API.
							if _, strictErr := s.Node(14); strictErr == nil || !strings.Contains(strictErr.Error(), "invalid collection count 2147483647 (334 bytes remain)") {
								t.Fatalf("strict validation changed: %v", strictErr)
							}
						}
						return
					}
					want := []any{[]any{int32(42), EnumReference{"Color", "RED"}, []any{nil, "hello\x00世界😀"}}}
					if variant == "enum-invalid-argument-tag" {
						want = []any{"Color"} // Java readAnyValue's unknown-tag fallback.
					}
					if err != nil || !present || n.ID != 14 || n.Kind != "EnumConstant" || n.EnumType != "Color" || n.EnumName != "RED" || !reflect.DeepEqual(n.EnumArguments, want) {
						t.Fatalf("node=%#v present=%v error=%v, want arguments %#v", n, present, err, want)
					}
					if err := s.Close(); err != nil {
						t.Fatal(err)
					}
					if !reflect.DeepEqual(n.EnumArguments, want) {
						t.Fatal("owned nested Enum values changed after Close")
					}
					if _, _, err := reader.read(s); err != ErrStoreClosed {
						t.Fatalf("read after Close=%v", err)
					}
				})
			}
		})
	}
}

// These additional controls follow NodeSerializer's (0 until argCount) source
// contract; they are not additional captured Java executions.
func TestProjectionEnumZeroAndNegativeArgumentCounts(t *testing.T) {
	for _, count := range []int32{0, -1, math.MinInt32} {
		dir := projectionEnumFixture(t, "clean")
		path := filepath.Join(dir, "graph.nodedata")
		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		binary.BigEndian.PutUint32(data[85:89], uint32(count))
		// An invalid immediate payload must remain unread for an empty range.
		data[89] = 2
		binary.BigEndian.PutUint32(data[90:94], math.MaxInt32)
		if err := os.WriteFile(path, data, 0600); err != nil {
			t.Fatal(err)
		}
		s, err := OpenMode(dir, "MAPPED")
		if err != nil {
			t.Fatal(err)
		}
		n, present, err := s.MainProjectionCandidateNode(14)
		if closeErr := s.Close(); closeErr != nil {
			t.Fatal(closeErr)
		}
		if err != nil || !present || n.ID != 14 || n.EnumType != "Color" || n.EnumName != "RED" || n.EnumArguments == nil || len(n.EnumArguments) != 0 {
			t.Fatalf("count=%d node=%#v present=%v err=%v", count, n, present, err)
		}
	}
}

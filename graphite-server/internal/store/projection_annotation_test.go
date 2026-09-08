package store

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

var projectionAnnotationReaders = []struct {
	name string
	read func(*Store) (Node, bool, error)
}{
	{"candidate", func(s *Store) (Node, bool, error) { return s.ProjectionCandidateNode(context.Background(), 26) }},
	{"main-ordinary", func(s *Store) (Node, bool, error) { return s.MainProjectionCandidateNode(26) }},
}

// The original Java fixture's values are specified by GenerateFixture.java:
// attrs writes key -> [42, EnumReference(Color, RED), [null, hello\0世界😀]].
// main/repeat-main's annotation-bad-tail public cases all fail reading SID 28;
// unlike Enum, even a predicate miss fully deserializes the Annotation first.
func TestProjectionAnnotationActualMainPairConsumption(t *testing.T) {
	for _, reader := range projectionAnnotationReaders {
		t.Run(reader.name, func(t *testing.T) {
			for _, variant := range []string{"clean", "annotation-bad-tail", "annotation-bad-sid-0", "annotation-bad-sid-1", "annotation-bad-sid-2", "annotation-invalid-value-tag"} {
				t.Run(variant, func(t *testing.T) {
					s, err := OpenMode(projectionEnumFixture(t, variant), "MAPPED")
					if err != nil {
						t.Fatal(err)
					}
					t.Cleanup(func() { s.Close() })
					n, present, err := reader.read(s)
					if strings.HasPrefix(variant, "annotation-bad-") {
						want := int32(math.MaxInt32)
						if variant == "annotation-bad-tail" {
							want = 28
						}
						var sid *StringTableReferenceError
						if present || !errors.As(err, &sid) || sid.Index != want || sid.Size != 21 {
							t.Fatalf("node=%#v present=%v err=%#v; want SID %d/list 21", n, present, err, want)
						}
						if variant == "annotation-bad-tail" {
							if _, strictErr := s.Node(26); strictErr == nil || !strings.Contains(strictErr.Error(), "invalid collection count 2147483647 (104 bytes remain)") {
								t.Fatalf("strict Node count validation changed: %v", strictErr)
							}
						}
						return
					}
					want := map[string]any{"key": []any{int32(42), EnumReference{"Color", "RED"}, []any{nil, "hello\x00世界😀"}}}
					if variant == "annotation-invalid-value-tag" {
						// The changed first value tag (127) falls back to a string;
						// the following original list count is SID 3, "Color".
						want = map[string]any{"key": "Color"}
					}
					if err != nil || !present || n.ID != 26 || n.Kind != "AnnotationNode" || n.Name != "Annotation" || n.ClassName != "Example" || n.MemberName != "run" || !reflect.DeepEqual(n.Values, want) || !reflect.DeepEqual(n.ValueOrder, []string{"key"}) {
						t.Fatalf("node=%#v present=%v err=%v, want values=%#v order=[key]", n, present, err, want)
					}
					if err := s.Close(); err != nil {
						t.Fatal(err)
					}
					if !reflect.DeepEqual(n.Values, want) || !reflect.DeepEqual(n.ValueOrder, []string{"key"}) {
						t.Fatalf("owned nested values/order changed after Close: %#v", n)
					}
					if _, _, err := reader.read(s); err != ErrStoreClosed {
						t.Fatalf("read after Close=%v", err)
					}
				})
			}
		})
	}
}

// Rewrite only a disposable copy. These controls use NodeSerializer's source
// contract; they are not new captured JVM observations or new persisted formats.
func projectionAnnotationPayload(t *testing.T, write func(*bytes.Buffer, func(string) int32)) *Store {
	t.Helper()
	dir := projectionEnumFixture(t, "clean")
	table, err := LoadStrings(filepath.Join(dir, "graph.strings"))
	if err != nil {
		t.Fatal(err)
	}
	sid := func(text string) int32 {
		for i, value := range table {
			if value == text {
				return int32(i)
			}
		}
		t.Fatalf("fixture string %q absent", text)
		return 0
	}
	var payload bytes.Buffer
	write(&payload, sid)
	path := filepath.Join(dir, "graph.nodedata")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	// prepare.py records Annotation's top-level pair count at byte 315.
	if payload.Len() > len(data)-315 {
		t.Fatalf("source-derived control exceeds existing suffix: %d", payload.Len())
	}
	copy(data[315:], payload.Bytes())
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	s, err := OpenMode(dir, "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func projectionAnnotationInt(b *bytes.Buffer, n int32) {
	var data [4]byte
	binary.BigEndian.PutUint32(data[:], uint32(n))
	b.Write(data[:])
}

func TestProjectionAnnotationSignedCountAndMapOrder(t *testing.T) {
	for _, reader := range projectionAnnotationReaders {
		for _, count := range []int32{0, -1, math.MinInt32} {
			t.Run(fmt.Sprintf("%s/empty-%d", reader.name, count), func(t *testing.T) {
				s := projectionAnnotationPayload(t, func(b *bytes.Buffer, sid func(string) int32) {
					projectionAnnotationInt(b, count)
					projectionAnnotationInt(b, math.MaxInt32) // invalid key must remain unread
				})
				n, present, err := reader.read(s)
				if err != nil || !present || n.ID != 26 || n.Name != "Annotation" || n.ClassName != "Example" || n.MemberName != "run" || n.Values == nil || len(n.Values) != 0 || n.ValueOrder == nil || len(n.ValueOrder) != 0 {
					t.Fatalf("count=%d node=%#v present=%v err=%v", count, n, present, err)
				}
			})
		}
		t.Run(reader.name+"/duplicate-key", func(t *testing.T) {
			s := projectionAnnotationPayload(t, func(b *bytes.Buffer, sid func(string) int32) {
				projectionAnnotationInt(b, 3)
				projectionAnnotationInt(b, sid("key"))
				b.WriteByte(2)
				projectionAnnotationInt(b, sid("RED"))
				projectionAnnotationInt(b, sid("field"))
				b.WriteByte(0)
				projectionAnnotationInt(b, 42)
				projectionAnnotationInt(b, sid("key"))
				b.WriteByte(2)
				projectionAnnotationInt(b, sid("Color"))
			})
			n, present, err := reader.read(s)
			if err != nil || !present || !reflect.DeepEqual(n.Values, map[string]any{"key": "Color", "field": int32(42)}) || !reflect.DeepEqual(n.ValueOrder, []string{"key", "field"}) {
				t.Fatalf("duplicate key did not overwrite at first position: %#v, %v, %v", n, present, err)
			}
		})
		t.Run(reader.name+"/key-before-value", func(t *testing.T) {
			s := projectionAnnotationPayload(t, func(b *bytes.Buffer, sid func(string) int32) {
				projectionAnnotationInt(b, 1)
				projectionAnnotationInt(b, 28)
				b.WriteByte(2)
				projectionAnnotationInt(b, 37)
			})
			_, present, err := reader.read(s)
			var failure *StringTableReferenceError
			if present || !errors.As(err, &failure) || failure.Index != 28 || failure.Size != 21 {
				t.Fatalf("key failure lost priority: present=%v err=%#v", present, err)
			}
		})
	}
}

func TestProjectionAnnotationLegacyValueContract(t *testing.T) {
	for _, reader := range projectionAnnotationReaders {
		t.Run(reader.name, func(t *testing.T) {
			s := projectionAnnotationPayload(t, func(b *bytes.Buffer, sid func(string) int32) {
				projectionAnnotationInt(b, 2)
				projectionAnnotationInt(b, sid("key"))
				projectionAnnotationInt(b, sid(""))
				projectionAnnotationInt(b, sid("field"))
				projectionAnnotationInt(b, sid("RED"))
			})
			// Isolate NodeSerializer's formatVersion argument after opening the
			// normal fixture; this is not a purported whole v1 persisted graph.
			// Legacy annotation values are plain SIDs, with empty string -> null.
			s.FormatVersion = 1
			n, present, err := reader.read(s)
			if err != nil || !present || !reflect.DeepEqual(n.Values, map[string]any{"key": nil, "field": "RED"}) || !reflect.DeepEqual(n.ValueOrder, []string{"key", "field"}) {
				t.Fatalf("legacy values/order: %#v, %v, %v", n, present, err)
			}
		})
	}
}

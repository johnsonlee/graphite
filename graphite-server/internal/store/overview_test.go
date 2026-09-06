package store

import (
	"bytes"
	"encoding/binary"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func writeOverviewFixture(t *testing.T, dir string) {
	t.Helper()
	var b bytes.Buffer
	for _, v := range []int32{0x47524f03, 77, 3, 0, 5, 1, 5, 2, 2, 3, 0, 2, 3, 0, 1, 4, 1, 0, 2} {
		if err := binary.Write(&b, binary.BigEndian, v); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(dir, "graph.classoverview"), b.Bytes(), 0600); err != nil {
		t.Fatal(err)
	}
}
func TestPersistedClassOverviewLimitFilteringAndCache(t *testing.T) {
	dir := t.TempDir()
	writeOverviewFixture(t, dir)
	s := &Store{Strings: []string{"Base", "Example", "Color"}, overview: lazyClassOverview{dir: dir}}
	one, err := s.ClassOverview(1)
	if err != nil {
		t.Fatal(err)
	}
	wantOne := &ClassOverview{ClassCounts: []ClassCount{{"Base", 5}}, ClassEdges: []ClassDependency{}, CallSiteCount: 77}
	if !reflect.DeepEqual(one, wantOne) {
		t.Fatalf("one %#v want %#v", one, wantOne)
	}
	two, err := s.ClassOverview(2)
	if err != nil {
		t.Fatal(err)
	}
	wantTwo := &ClassOverview{ClassCounts: []ClassCount{{"Base", 5}, {"Example", 5}}, ClassEdges: []ClassDependency{{"Base", "Example", 4}, {"Example", "Base", 2}}, CallSiteCount: 77}
	if !reflect.DeepEqual(two, wantTwo) {
		t.Fatalf("two %#v want %#v", two, wantTwo)
	}
	two.ClassCounts[0].Count = 999
	two.ClassEdges[0].Count = 999
	if err := os.WriteFile(filepath.Join(dir, "graph.classoverview"), []byte("corrupt"), 0600); err != nil {
		t.Fatal(err)
	}
	cached, err := s.ClassOverview(2)
	if err != nil || !reflect.DeepEqual(cached, wantTwo) {
		t.Fatalf("cached %#v error %v", cached, err)
	}
	if _, err := s.ClassOverview(3); err == nil {
		t.Fatal("larger limit did not re-read corrupt optional file")
	}
	cached, err = s.ClassOverview(1)
	if err != nil || !reflect.DeepEqual(cached, wantOne) {
		t.Fatalf("smaller cached %#v error %v", cached, err)
	}
}
func TestClassOverviewHTTPShapeAndStableTies(t *testing.T) {
	dir := t.TempDir()
	writeOverviewFixture(t, dir)
	s := &Store{Strings: []string{"Base", "Example", "Color"}, overview: lazyClassOverview{dir: dir}}
	got, err := s.Overview(2)
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]any{"nodes": []map[string]any{{"id": "Base", "type": "Class", "label": "Base", "fullName": "Base", "callSites": int32(5)}, {"id": "Example", "type": "Class", "label": "Example", "fullName": "Example", "callSites": int32(5)}}, "edges": []map[string]any{{"from": "Base", "to": "Example", "type": "Call", "weight": int32(4)}, {"from": "Example", "to": "Base", "type": "Call", "weight": int32(2)}}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %#v want %#v", got, want)
	}
	zero, err := s.Overview(0)
	if err != nil || !reflect.DeepEqual(zero, map[string]any{"nodes": []map[string]any{}, "edges": []map[string]any{}}) {
		t.Fatalf("zero %#v error %v", zero, err)
	}
}
func TestClassOverviewFallbackOnMissingAndCorruptOptionalFile(t *testing.T) {
	for _, corrupt := range []bool{false, true} {
		s, err := Open("testdata/jvm-v3")
		if err != nil {
			t.Fatal(err)
		}
		dir := t.TempDir()
		s.overview.dir = dir
		if corrupt {
			if err := os.WriteFile(filepath.Join(dir, "graph.classoverview"), []byte("corrupt"), 0600); err != nil {
				t.Fatal(err)
			}
		}
		got, err := s.Overview(10)
		s.Close()
		if err != nil {
			t.Fatal(err)
		}
		want := map[string]any{"nodes": []map[string]any{{"id": "Example", "type": "Class", "label": "Example", "fullName": "Example", "callSites": int32(2)}}, "edges": []map[string]any{}}
		if !reflect.DeepEqual(got, want) {
			t.Fatalf("corrupt=%v got %#v want %#v", corrupt, got, want)
		}
	}
}
func TestClassOverviewMissingRawResultAndLimitBounds(t *testing.T) {
	s := &Store{overview: lazyClassOverview{dir: t.TempDir()}}
	got, err := s.ClassOverview(100)
	if err != nil || got != nil {
		t.Fatalf("missing %#v error %v", got, err)
	}
	for _, tc := range []struct{ in, want int }{{-1, 0}, {0, 0}, {999, 999}, {1000, 1000}, {1001, 1000}} {
		if got := boundOverviewLimit(tc.in); got != tc.want {
			t.Errorf("limit %d got %d want %d", tc.in, got, tc.want)
		}
	}
}

package store

import (
	"io"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestJVMResources(t *testing.T) {
	s, err := Open("testdata/jvm-v3")
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	reason, err := s.ResourceUnavailableReason()
	if err != nil || reason != "" {
		t.Fatalf("reason %q, error %v", reason, err)
	}
	all := []ResourceEntry{{"config.yml", "app.jar"}, {"docs/readme.txt", "dependency.jar"}, {"application.properties", "app.jar"}}
	for _, tc := range []struct {
		pattern string
		want    []ResourceEntry
	}{{"**", all}, {"*", []ResourceEntry{all[0], all[2]}}, {"*.{yml,properties}", []ResourceEntry{all[0], all[2]}}, {"**/*.txt", []ResourceEntry{all[1]}}, {"nothing", []ResourceEntry{}}} {
		got, err := s.ResourceList(tc.pattern)
		if err != nil || !reflect.DeepEqual(got, tc.want) {
			t.Errorf("pattern %q got %v error %v; want %v", tc.pattern, got, err, tc.want)
		}
	}
	r, err := s.OpenResource("config.yml")
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	content, err := io.ReadAll(r)
	if err != nil || string(content) != "key: 世界\n" {
		t.Fatalf("content %q error %v", content, err)
	}
	if _, err := s.OpenResource("absent"); err == nil || err.Error() != "Resource not found: absent" {
		t.Fatalf("missing resource error %v", err)
	}
}
func TestMissingAndCorruptResources(t *testing.T) {
	s := &Store{resources: lazyResources{dir: t.TempDir()}}
	reason, err := s.ResourceUnavailableReason()
	if err != nil || reason != MissingResourcesReason {
		t.Fatalf("missing reason %q error %v", reason, err)
	}
	if list, err := s.ResourceList("["); err != nil || len(list) != 0 {
		t.Fatalf("missing store list %v error %v", list, err)
	}
	if _, err := s.OpenResource("any"); err == nil || err.Error() != reason {
		t.Fatalf("missing open error %v", err)
	}
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "graph.resources"), []byte{0x47, 0x52, 0x52, 2, 0, 0, 0, 0}, 0600); err != nil {
		t.Fatal(err)
	}
	s = &Store{resources: lazyResources{dir: dir}}
	if _, err := s.ResourceUnavailableReason(); err == nil || err.Error() != "Unsupported resource file version: 2" {
		t.Fatalf("bad resource error %v", err)
	}
}
func TestJavaGlobSemantics(t *testing.T) {
	for _, tc := range []struct {
		pattern, path string
		matches       bool
	}{{"**", "dir/file", true}, {"*", "dir/file", false}, {"**/*.txt", "file.txt", false}, {"**/*.txt", "dir/file.txt", true}, {"a?c", "abc", true}, {"a?c", "a/c", false}, {"[!a-z]", "/", false}, {"[!a-z]", "7", true}, {"[a-z]", "k", true}, {"[^a]", "^", true}, {"\\*", "*", true}, {"{a,b}", "a", true}, {"{a,b}", "c", false}, {"世界.*", "世界.yml", true}} {
		pattern, err := compileResourceGlob(tc.pattern)
		if err != nil {
			t.Fatal(err)
		}
		if got := pattern.MatchString(tc.path); got != tc.matches {
			t.Errorf("glob %q path %q = %v", tc.pattern, tc.path, got)
		}
	}
	for _, pattern := range []string{"[", "[a/]", "{a,{b,c}}", "{a,b", "\\"} {
		if _, err := compileResourceGlob(pattern); err == nil {
			t.Errorf("accepted invalid glob %q", pattern)
		}
	}
}

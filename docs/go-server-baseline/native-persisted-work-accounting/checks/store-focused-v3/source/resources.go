package store

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
)

const MissingResourcesReason = "Persisted resources are unavailable because graph.resources is missing; rebuild this graph with the current Graphite CLI"

type ResourceEntry struct {
	Path   string
	Source string
}
type persistedResource struct {
	ResourceEntry
	content []byte
}
type resourceStore struct {
	entries           []persistedResource
	byPath            map[string]int
	unavailableReason string
}
type lazyResources struct {
	dir   string
	once  sync.Once
	store resourceStore
	err   error
}

func (r *lazyResources) load() (*resourceStore, error) {
	r.once.Do(func() {
		r.store.byPath = map[string]int{}
		r.err = decodeFile(filepath.Join(r.dir, "graph.resources"), nil, func(d *decoder) {
			h := uint32(d.i32())
			if h&0xffffff00 != 0x47525200 {
				d.fail("Invalid resource file magic: 0x%x", h)
			}
			if h&255 != 1 {
				d.fail("Unsupported resource file version: %d", h&255)
			}
			n := d.count()
			for i := 0; i < n && d.err == nil; i++ {
				path := string(d.bytes(d.count()))
				source := string(d.bytes(d.count()))
				content := d.bytes(d.count())
				v := persistedResource{ResourceEntry{path, source}, content}
				if position, ok := r.store.byPath[path]; ok {
					r.store.entries[position] = v
				} else {
					r.store.byPath[path] = len(r.store.entries)
					r.store.entries = append(r.store.entries, v)
				}
			}
		})
		if errors.Is(r.err, os.ErrNotExist) {
			r.err = nil
			r.store.unavailableReason = MissingResourcesReason
		}
	})
	return &r.store, r.err
}
func (s *Store) ResourceUnavailableReason() (string, error) {
	r, err := s.resources.load()
	return r.unavailableReason, err
}
func (s *Store) ResourceList(pattern string) ([]ResourceEntry, error) {
	r, err := s.resources.load()
	if err != nil {
		return nil, err
	}
	if r.unavailableReason != "" {
		return []ResourceEntry{}, nil
	}
	matcher, err := compileResourceGlob(pattern)
	if err != nil {
		return nil, err
	}
	entries := make([]ResourceEntry, 0)
	for _, resource := range r.entries {
		if matcher.MatchString(resource.Path) {
			entries = append(entries, resource.ResourceEntry)
		}
	}
	return entries, nil
}
func (s *Store) OpenResource(path string) (io.ReadCloser, error) {
	r, err := s.resources.load()
	if err != nil {
		return nil, err
	}
	if r.unavailableReason != "" {
		return nil, errors.New(r.unavailableReason)
	}
	position, ok := r.byPath[path]
	if !ok {
		return nil, fmt.Errorf("Resource not found: %s", path)
	}
	return io.NopCloser(bytes.NewReader(r.entries[position].content)), nil
}

// Implements Unix FileSystem.getPathMatcher("glob:...") syntax, including
// braces, character classes, escaped metacharacters and segment-aware stars.
func compileResourceGlob(pattern string) (*regexp.Regexp, error) {
	chars := []rune(pattern)
	var out strings.Builder
	out.WriteString("^(?:")
	inGroup := false
	for i := 0; i < len(chars); i++ {
		c := chars[i]
		switch c {
		case '\\':
			i++
			if i == len(chars) {
				return nil, fmt.Errorf("No character to escape in glob %q", pattern)
			}
			out.WriteString(regexp.QuoteMeta(string(chars[i])))
		case '*':
			if i+1 < len(chars) && chars[i+1] == '*' {
				out.WriteString(".*")
				i++
			} else {
				out.WriteString("[^/]*")
			}
		case '?':
			out.WriteString("[^/]")
		case '{':
			if inGroup {
				return nil, fmt.Errorf("Cannot nest groups in glob %q", pattern)
			}
			inGroup = true
			out.WriteString("(?:")
		case '}':
			if inGroup {
				out.WriteByte(')')
				inGroup = false
			} else {
				out.WriteString("\\}")
			}
		case ',':
			if inGroup {
				out.WriteByte('|')
			} else {
				out.WriteByte(',')
			}
		case '[':
			out.WriteByte('[')
			i++
			if i >= len(chars) {
				return nil, fmt.Errorf("Missing ']' in glob %q", pattern)
			}
			if chars[i] == '!' {
				out.WriteString("^/")
				i++
			} else if chars[i] == '^' {
				out.WriteString("\\^")
				i++
			}
			if i < len(chars) && chars[i] == '-' {
				out.WriteByte('-')
				i++
			}
			found := false
			for ; i < len(chars); i++ {
				ch := chars[i]
				if ch == ']' {
					found = true
					out.WriteByte(']')
					break
				}
				if ch == '/' {
					return nil, fmt.Errorf("Explicit 'name separator' in class in glob %q", pattern)
				}
				if ch == '\\' || ch == '[' {
					out.WriteByte('\\')
				}
				out.WriteRune(ch)
			}
			if !found {
				return nil, fmt.Errorf("Missing ']' in glob %q", pattern)
			}
		default:
			out.WriteString(regexp.QuoteMeta(string(c)))
		}
	}
	if inGroup {
		return nil, fmt.Errorf("Missing '}' in glob %q", pattern)
	}
	out.WriteString(")$")
	return regexp.Compile(out.String())
}

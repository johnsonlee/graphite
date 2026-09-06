// Package server implements the Graphite HTTP service and graph lifetimes.
package server

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

type Stats struct {
	Nodes     int64 `json:"nodes"`
	Edges     int64 `json:"edges"`
	Methods   int64 `json:"methods"`
	CallSites int64 `json:"callSites"`
}

func (s Stats) Add(t Stats) Stats {
	return Stats{s.Nodes + t.Nodes, s.Edges + t.Edges, s.Methods + t.Methods, s.CallSites + t.CallSites}
}

// Graph is an owned, immutable snapshot. Loading and statistics must complete
// before publication; readers retain their snapshot across replacement.
type Graph interface {
	io.Closer
	Stats() Stats
}

type Loader func(path, mode string) (Graph, error)

type Descriptor struct {
	ID       string `json:"id"`
	Path     string `json:"path"`
	LoadMode string `json:"loadMode"`
	LoadedAt string `json:"loadedAt"`
	Stats
	Generation uint64 `json:"-"`
}

var graphIDPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`)
var ErrRegistryClosed = errors.New("Graph registry is closed")

func ValidateGraphID(id string) (string, error) {
	clean := strings.TrimSpace(id)
	if !graphIDPattern.MatchString(clean) {
		return "", fmt.Errorf("Invalid graph id '%s'. Use 1-128 chars: letters, digits, dot, underscore, or dash.", id)
	}
	return clean, nil
}

func ValidateLoadMode(mode string) error {
	switch mode {
	case "AUTO", "EAGER", "MAPPED":
		return nil
	default:
		return fmt.Errorf("Invalid load mode: %s", mode)
	}
}

type snapshot struct {
	graph      Graph
	descriptor Descriptor
	refs       int
	retired    bool
}

type Registry struct {
	DataDir         string
	DefaultLoadMode string
	loader          Loader
	mu              sync.Mutex
	graphs          map[string]*snapshot
	nextGeneration  uint64
	closed          bool
	// Rebuild runs under the catalog lock, with the candidate catalog. It must
	// publish its derived state only on success and must not call Registry.
	Rebuild func([]CatalogEntry) error
}

type CatalogEntry struct {
	Descriptor Descriptor
	Graph      Graph
}

func NewRegistry(dataDir, mode string, loader Loader) (*Registry, error) {
	if err := ValidateLoadMode(mode); err != nil {
		return nil, err
	}
	if loader == nil {
		return nil, errors.New("Graph loader is required")
	}
	root, err := filepath.Abs(dataDir)
	if err != nil {
		return nil, err
	}
	return &Registry{DataDir: root, DefaultLoadMode: mode, loader: loader, graphs: make(map[string]*snapshot)}, nil
}

func (r *Registry) ResolvePath(path string) string {
	if filepath.IsAbs(path) {
		return filepath.Clean(path)
	}
	return filepath.Join(r.DataDir, path)
}

func (r *Registry) Load(id, path, mode string) (Descriptor, error) {
	clean, err := ValidateGraphID(id)
	if err != nil {
		return Descriptor{}, err
	}
	if mode == "" {
		mode = r.DefaultLoadMode
	}
	if err = ValidateLoadMode(mode); err != nil {
		return Descriptor{}, err
	}
	resolved := r.ResolvePath(path)
	info, err := os.Stat(resolved)
	if err != nil || !info.IsDir() {
		return Descriptor{}, fmt.Errorf("Graph path is not a directory: %s", resolved)
	}
	graph, err := r.loader(resolved, mode)
	if err != nil {
		return Descriptor{}, err
	}
	d := Descriptor{ID: clean, Path: resolved, LoadMode: mode, LoadedAt: time.Now().UTC().Format(time.RFC3339Nano), Stats: graph.Stats()}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		_ = graph.Close()
		return Descriptor{}, ErrRegistryClosed
	}
	r.nextGeneration++
	d.Generation = r.nextGeneration
	served := &snapshot{graph: graph, descriptor: d}
	previous := r.graphs[clean]
	r.graphs[clean] = served
	if err = r.rebuild(); err != nil {
		if previous == nil {
			delete(r.graphs, clean)
		} else {
			r.graphs[clean] = previous
		}
		_ = graph.Close()
		return Descriptor{}, err
	}
	r.retire(previous)
	return d, nil
}

func (r *Registry) Unload(id string) (bool, error) {
	clean, err := ValidateGraphID(id)
	if err != nil {
		return false, err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return false, ErrRegistryClosed
	}
	previous := r.graphs[clean]
	if previous == nil {
		return false, nil
	}
	delete(r.graphs, clean)
	if err := r.rebuild(); err != nil {
		r.graphs[clean] = previous
		return false, err
	}
	r.retire(previous)
	return true, nil
}

func (r *Registry) catalog() []CatalogEntry {
	entries := make([]CatalogEntry, 0, len(r.graphs))
	for _, s := range r.graphs {
		entries = append(entries, CatalogEntry{s.descriptor, s.graph})
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Descriptor.ID < entries[j].Descriptor.ID })
	return entries
}

func (r *Registry) rebuild() error {
	if r.Rebuild != nil {
		return r.Rebuild(r.catalog())
	}
	return nil
}

func (r *Registry) List() []Descriptor {
	r.mu.Lock()
	defer r.mu.Unlock()
	result := make([]Descriptor, 0, len(r.graphs))
	for _, entry := range r.catalog() {
		result = append(result, entry.Descriptor)
	}
	return result
}

func (r *Registry) Describe(id string) (*Descriptor, error) {
	clean, err := ValidateGraphID(id)
	if err != nil {
		return nil, err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if s := r.graphs[clean]; s != nil {
		d := s.descriptor
		return &d, nil
	}
	return nil, nil
}

type Lease struct {
	ID       string
	Graph    Graph
	registry *Registry
	snapshot *snapshot
	once     sync.Once
}

func (l *Lease) Close() error {
	l.once.Do(func() {
		r := l.registry
		r.mu.Lock()
		defer r.mu.Unlock()
		l.snapshot.refs--
		if l.snapshot.refs == 0 && l.snapshot.retired {
			_ = l.snapshot.graph.Close()
		}
	})
	return nil
}

func (r *Registry) Acquire(id string) (*Lease, error) {
	leases, err := r.AcquireSelected([]string{id})
	if err != nil {
		return nil, err
	}
	return leases[0], nil
}

// AcquireSelected retains all selected snapshots atomically, preserving the
// caller's order and removing duplicates. nil selects the entire sorted catalog;
// an explicitly empty slice selects no graphs.
func (r *Registry) AcquireSelected(ids []string) ([]*Lease, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return nil, ErrRegistryClosed
	}
	if ids == nil {
		ids = make([]string, 0, len(r.graphs))
		for _, e := range r.catalog() {
			ids = append(ids, e.Descriptor.ID)
		}
	}
	selected := make([]*snapshot, 0, len(ids))
	seen := make(map[string]bool, len(ids))
	for _, id := range ids {
		clean, err := ValidateGraphID(id)
		if err != nil {
			return nil, err
		}
		if seen[clean] {
			continue
		}
		seen[clean] = true
		s := r.graphs[clean]
		if s == nil {
			return nil, fmt.Errorf("Graph not loaded: %s", clean)
		}
		selected = append(selected, s)
	}
	leases := make([]*Lease, 0, len(selected))
	for _, s := range selected {
		s.refs++
		leases = append(leases, &Lease{ID: s.descriptor.ID, Graph: s.graph, registry: r, snapshot: s})
	}
	return leases, nil
}

func (r *Registry) retire(s *snapshot) {
	if s == nil || s.retired {
		return
	}
	s.retired = true
	if s.refs == 0 {
		_ = s.graph.Close()
	}
}

func (r *Registry) Close() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !r.closed {
		r.closed = true
		for _, s := range r.graphs {
			r.retire(s)
		}
		r.graphs = make(map[string]*snapshot)
	}
	return nil
}

package server

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
	"unicode/utf16"

	"github.com/johnsonlee/graphite/graphite-server/internal/query"
)

type TopologyQuery struct{ Name, Cypher string }

func LoadTopologyQueries(path string) ([]TopologyQuery, error) {
	result := []TopologyQuery{}
	if path == "" {
		return result, nil
	}
	absolute, err := filepath.Abs(path)
	if err != nil {
		return nil, err
	}
	info, err := os.Stat(absolute)
	if err != nil {
		return nil, fmt.Errorf("Topology query path does not exist: %s", absolute)
	}
	files := []string{}
	if info.Mode().IsRegular() {
		files = append(files, absolute)
	}
	if info.IsDir() {
		entries, err := os.ReadDir(absolute)
		if err != nil {
			return nil, err
		}
		for _, entry := range entries {
			path := filepath.Join(absolute, entry.Name())
			info, err := os.Stat(path)
			if err == nil && info.Mode().IsRegular() && strings.HasSuffix(entry.Name(), ".cypher") {
				files = append(files, path)
			}
		}
	}
	sort.Strings(files)
	if len(files) == 0 {
		return nil, fmt.Errorf("No .cypher topology queries found at: %s", absolute)
	}
	for _, file := range files {
		data, err := os.ReadFile(file)
		if err != nil {
			return nil, err
		}
		cypher := strings.TrimSpace(string(data))
		if cypher == "" {
			return nil, fmt.Errorf("Topology query is empty: %s", file)
		}
		result = append(result, TopologyQuery{filepath.Base(file), cypher})
	}
	return result, nil
}

type topologyNode struct {
	ID      string `json:"id"`
	GraphID string `json:"graphId"`
	Type    string `json:"type"`
	Label   string `json:"label"`
	Stats
}
type topologyEdge struct {
	From       string   `json:"from"`
	To         string   `json:"to"`
	Type       string   `json:"type"`
	Protocol   string   `json:"protocol"`
	Weight     int64    `json:"weight"`
	Operations []string `json:"operations"`
	Evidence   []string `json:"evidence"`
}
type topologyGraph struct {
	Nodes         []topologyNode `json:"nodes"`
	Edges         []topologyEdge `json:"edges"`
	GraphCount    int            `json:"graphCount"`
	RelationCount int            `json:"relationCount"`
	MatchedRows   int            `json:"matchedRows"`
	BuiltAt       string         `json:"builtAt"`
	Rules         []string       `json:"rules"`
	Stale         bool           `json:"stale"`
	versions      map[string]uint64
}

// Topology snapshots are immutable and atomically replaced while the registry
// catalog lock is held. Failed builds leave both the catalog and topology intact.
type TopologyService struct {
	queries []TopologyQuery
	current atomic.Pointer[topologyGraph]
}

func NewTopologyService(r *Registry, queries []TopologyQuery) (*TopologyService, error) {
	t := &TopologyService{queries: append([]TopologyQuery(nil), queries...)}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return nil, ErrRegistryClosed
	}
	if r.Rebuild != nil {
		return nil, errors.New("Graph registry already has a rebuild callback")
	}
	if err := t.rebuild(r.catalog()); err != nil {
		return nil, err
	}
	r.Rebuild = t.rebuild
	return t, nil
}

func (t *TopologyService) rebuild(catalog []CatalogEntry) error {
	next, err := buildTopology(context.Background(), catalog, t.queries)
	if err != nil {
		return err
	}
	t.current.Store(next)
	return nil
}

func (s *Server) topology(w http.ResponseWriter, r *http.Request) {
	if s.Topology == nil {
		writeError(w, 500, errors.New("Topology has not been built"))
		return
	}
	// Read the published snapshot and catalog in one transaction, including
	// replacements that keep the same graph IDs but change their generation.
	s.Registry.mu.Lock()
	current := *s.Topology.current.Load()
	catalog := s.Registry.catalog()
	current.Stale = len(current.versions) != len(catalog)
	for _, entry := range catalog {
		if current.versions[entry.Descriptor.ID] != entry.Descriptor.Generation {
			current.Stale = true
		}
	}
	s.Registry.mu.Unlock()
	if current.Stale {
		writeJSON(w, 200, current)
		return
	}
	body, err := json.Marshal(current)
	if err != nil {
		writeError(w, 500, err)
		return
	}
	// main streams its immutable topology snapshot with an explicit length.
	w.Header().Set("Content-Type", "application/json;charset=utf-8")
	w.Header().Set("Content-Length", strconv.Itoa(len(body)))
	_, _ = w.Write(body)
}

func buildTopology(ctx context.Context, catalog []CatalogEntry, queries []TopologyQuery) (*topologyGraph, error) {
	out := &topologyGraph{Nodes: []topologyNode{}, Edges: []topologyEdge{}, Rules: []string{}, versions: map[string]uint64{}}
	graphs := []query.Graph{}
	for _, entry := range catalog {
		d := entry.Descriptor
		out.Nodes = append(out.Nodes, topologyNode{d.ID, d.ID, "Graph", d.ID, d.Stats})
		out.versions[d.ID] = d.Generation
		if len(queries) != 0 {
			g, ok := entry.Graph.(*NativeGraph)
			if !ok {
				return nil, errors.New("Native Cypher graph access is not implemented")
			}
			graphs = append(graphs, query.Graph{ID: d.ID, Store: g.Store})
		}
	}
	sort.Slice(out.Nodes, func(i, j int) bool { return out.Nodes[i].ID < out.Nodes[j].ID })
	type edgeKey struct{ from, to, protocol string }
	edges := map[edgeKey]*topologyEdge{}
	for _, rule := range queries {
		remaining := 100000 - out.MatchedRows
		result, err := query.ExecuteCross(ctx, graphs, rule.Cypher, nil, remaining+1)
		if err != nil {
			return nil, err
		}
		hasSource, hasTarget := false, false
		for _, column := range result.Columns {
			hasSource = hasSource || column == "source"
			hasTarget = hasTarget || column == "target"
		}
		if !hasSource || !hasTarget {
			return nil, fmt.Errorf("Topology query '%s' must return 'source' and 'target'", rule.Name)
		}
		if len(result.Rows) > remaining {
			return nil, fmt.Errorf("Topology queries exceeded the combined 100000 row limit at '%s'", rule.Name)
		}
		out.MatchedRows += len(result.Rows)
		out.Rules = append(out.Rules, rule.Name)
		for _, row := range result.Rows {
			id := func(column string) (string, error) {
				value := topologyText(row[column])
				if value == "" {
					return "", fmt.Errorf("Topology query '%s' returned a blank '%s'", rule.Name, column)
				}
				if _, ok := out.versions[value]; !ok {
					return "", fmt.Errorf("Topology query '%s' returned unknown graph '%s' in '%s'", rule.Name, value, column)
				}
				return value, nil
			}
			from, err := id("source")
			if err != nil {
				return nil, err
			}
			to, err := id("target")
			if err != nil {
				return nil, err
			}
			if from == to {
				continue
			}
			protocol := topologyText(row["protocol"])
			if protocol == "" {
				protocol = "call"
			}
			weight, err := topologyWeight(rule.Name, row["weight"])
			if err != nil {
				return nil, err
			}
			key := edgeKey{from, to, protocol}
			edge := edges[key]
			if edge == nil {
				edge = &topologyEdge{From: from, To: to, Type: "TopologyCall", Protocol: protocol, Operations: []string{}, Evidence: []string{}}
				edges[key] = edge
			}
			if weight > math.MaxInt64-edge.Weight {
				return nil, errors.New("long overflow")
			}
			edge.Weight += weight
			edge.Operations = addTopologyDetail(edge.Operations, topologyText(row["operation"]))
			edge.Evidence = addTopologyDetail(edge.Evidence, topologyText(row["evidence"]))
		}
	}
	for _, edge := range edges {
		sort.Slice(edge.Operations, func(i, j int) bool { return javaTextLess(edge.Operations[i], edge.Operations[j]) })
		sort.Slice(edge.Evidence, func(i, j int) bool { return javaTextLess(edge.Evidence[i], edge.Evidence[j]) })
		out.Edges = append(out.Edges, *edge)
	}
	sort.Slice(out.Edges, func(i, j int) bool {
		a, b := out.Edges[i], out.Edges[j]
		if a.From != b.From {
			return a.From < b.From
		}
		if a.To != b.To {
			return a.To < b.To
		}
		return javaTextLess(a.Protocol, b.Protocol)
	})
	out.GraphCount, out.RelationCount = len(out.Nodes), len(out.Edges)
	out.BuiltAt = time.Now().UTC().Format(time.RFC3339Nano)
	return out, nil
}

func javaTextLess(a, b string) bool {
	x, y := utf16.Encode([]rune(a)), utf16.Encode([]rune(b))
	for i := 0; i < len(x) && i < len(y); i++ {
		if x[i] != y[i] {
			return x[i] < y[i]
		}
	}
	return len(x) < len(y)
}

func addTopologyDetail(values []string, value string) []string {
	if value == "" || len(values) >= 100 {
		return values
	}
	for _, existing := range values {
		if existing == value {
			return values
		}
	}
	return append(values, value)
}

func topologyText(value any) string {
	if value == nil {
		return ""
	}
	switch v := value.(type) {
	case float64:
		return query.FormatJavaFloat(v, 64)
	case float32:
		return query.FormatJavaFloat(float64(v), 32)
	default:
		return strings.TrimSpace(fmt.Sprint(value))
	}
}

func topologyWeight(name string, value any) (int64, error) {
	if value == nil {
		return 1, nil
	}
	var n int64
	numeric := true
	switch v := value.(type) {
	case int:
		n = int64(v)
	case int32:
		n = int64(v)
	case int64:
		n = v
	case float32:
		return topologyWeight(name, float64(v))
	case float64:
		switch {
		case math.IsNaN(v):
			n = 0
		case v >= float64(math.MaxInt64):
			n = math.MaxInt64
		case v <= float64(math.MinInt64):
			n = math.MinInt64
		default:
			n = int64(v)
		}
		if n <= 0 || float64(n) != v {
			return 0, fmt.Errorf("Topology query '%s' returned a non-positive or fractional weight: %s", name, topologyText(value))
		}
	default:
		numeric = false
		var err error
		n, err = strconv.ParseInt(fmt.Sprint(value), 10, 64)
		if err != nil {
			return 0, fmt.Errorf("Topology query '%s' returned an invalid weight: %s", name, topologyText(value))
		}
	}
	if n <= 0 {
		if numeric {
			return 0, fmt.Errorf("Topology query '%s' returned a non-positive or fractional weight: %s", name, topologyText(value))
		}
		return 0, fmt.Errorf("Topology query '%s' returned a non-positive weight: %s", name, topologyText(value))
	}
	return n, nil
}

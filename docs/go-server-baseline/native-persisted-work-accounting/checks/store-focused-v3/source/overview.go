package store

import (
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
)

const maxOverviewClasses = 1000

type ClassCount struct {
	ClassName string
	Count     int32
}
type ClassDependency struct {
	CallerClass, CalleeClass string
	Count                    int32
}
type ClassOverview struct {
	ClassCounts   []ClassCount
	ClassEdges    []ClassDependency
	CallSiteCount int32
}
type lazyClassOverview struct {
	dir    string
	mu     sync.Mutex
	limit  int
	cached *ClassOverview
}

func boundOverviewLimit(limit int) int { return min(max(limit, 0), maxOverviewClasses) }

// ClassOverview loads the optional persisted aggregate. Missing/nonregular files
// return nil; malformed files return an error. Successful results are cached at
// the largest requested limit, matching PersistedClassOverviewProvider.
func (s *Store) ClassOverview(limit int) (*ClassOverview, error) {
	limit = boundOverviewLimit(limit)
	p := &s.overview
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.cached != nil && p.limit >= limit {
		return truncateOverview(p.cached, limit), nil
	}
	path := filepath.Join(p.dir, "graph.classoverview")
	info, err := os.Stat(path)
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, nil
	}
	result := &ClassOverview{ClassCounts: []ClassCount{}, ClassEdges: []ClassDependency{}}
	err = decodeFile(path, s.Strings, func(d *decoder) {
		d.header(0x47524f00)
		result.CallSiteCount = d.i32()
		n := d.count()
		selected := map[int32]bool{}
		positions := map[string]int{}
		for i := 0; i < n && d.err == nil; i++ {
			id := d.i32()
			count := d.i32()
			if i >= limit {
				continue
			}
			name := overviewString(d, id)
			selected[id] = true
			v := ClassCount{name, count}
			if position, exists := positions[name]; exists {
				result.ClassCounts[position] = v
			} else {
				positions[name] = len(result.ClassCounts)
				result.ClassCounts = append(result.ClassCounts, v)
			}
		}
		n = d.count()
		edgePositions := map[[2]string]int{}
		for i := 0; i < n && d.err == nil; i++ {
			from, to, count := d.i32(), d.i32(), d.i32()
			if !selected[from] || !selected[to] {
				continue
			}
			caller, callee := overviewString(d, from), overviewString(d, to)
			key := [2]string{caller, callee}
			v := ClassDependency{caller, callee, count}
			if position, exists := edgePositions[key]; exists {
				result.ClassEdges[position] = v
			} else {
				edgePositions[key] = len(result.ClassEdges)
				result.ClassEdges = append(result.ClassEdges, v)
			}
		}
	})
	if err != nil {
		return nil, err
	}
	p.limit = limit
	p.cached = result
	return truncateOverview(result, limit), nil
}
func overviewString(d *decoder, id int32) string {
	if id < 0 || int(id) >= len(d.strings) {
		d.fail("class overview string index %d outside table of %d", id, len(d.strings))
		return ""
	}
	return d.strings[id]
}
func truncateOverview(source *ClassOverview, limit int) *ClassOverview {
	count := min(max(limit, 0), len(source.ClassCounts))
	out := &ClassOverview{ClassCounts: append([]ClassCount{}, source.ClassCounts[:count]...), ClassEdges: []ClassDependency{}, CallSiteCount: source.CallSiteCount}
	selected := make(map[string]bool, count)
	for _, entry := range out.ClassCounts {
		selected[entry.ClassName] = true
	}
	for _, edge := range source.ClassEdges {
		if selected[edge.CallerClass] && selected[edge.CalleeClass] {
			out.ClassEdges = append(out.ClassEdges, edge)
		}
	}
	return out
}

// Overview returns the class overview response used by ExploreRoutes, including
// its fallback when the optional persisted aggregate is absent or invalid.
func (s *Store) Overview(limit int) (map[string]any, error) {
	limit = boundOverviewLimit(limit)
	overview, err := s.ClassOverview(limit)
	if err != nil || overview == nil {
		overview, err = s.scanClassOverview()
		if err != nil {
			return nil, err
		}
	}
	counts := append([]ClassCount(nil), overview.ClassCounts...)
	sort.SliceStable(counts, func(i, j int) bool { return counts[i].Count > counts[j].Count })
	counts = counts[:min(limit, len(counts))]
	nodes := make([]map[string]any, 0, len(counts))
	selected := make(map[string]bool, len(counts))
	for _, entry := range counts {
		selected[entry.ClassName] = true
		short := entry.ClassName
		if pos := strings.LastIndexByte(short, '.'); pos >= 0 {
			short = short[pos+1:]
		}
		nodes = append(nodes, map[string]any{"id": entry.ClassName, "type": "Class", "label": short, "fullName": entry.ClassName, "callSites": entry.Count})
	}
	edges := []map[string]any{}
	for _, edge := range overview.ClassEdges {
		if selected[edge.CallerClass] && selected[edge.CalleeClass] {
			edges = append(edges, map[string]any{"from": edge.CallerClass, "to": edge.CalleeClass, "type": "Call", "weight": edge.Count})
		}
	}
	return map[string]any{"nodes": nodes, "edges": edges}, nil
}
func (s *Store) scanClassOverview() (*ClassOverview, error) {
	result := &ClassOverview{ClassCounts: []ClassCount{}, ClassEdges: []ClassDependency{}}
	classPositions := map[string]int{}
	edgePositions := map[[2]string]int{}
	incrementClass := func(name string) {
		if position, exists := classPositions[name]; exists {
			result.ClassCounts[position].Count++
		} else if len(result.ClassCounts) < 20000 {
			classPositions[name] = len(result.ClassCounts)
			result.ClassCounts = append(result.ClassCounts, ClassCount{name, 1})
		}
	}
	ids := s.NodesOfKind("CallSiteNode")
	for i, id := range ids {
		if i >= 100000 {
			break
		}
		node, err := s.Node(id)
		if err != nil {
			return nil, err
		}
		result.CallSiteCount++
		caller, callee := node.Caller.DeclaringClass, node.Callee.DeclaringClass
		if caller != callee {
			key := [2]string{caller, callee}
			if position, exists := edgePositions[key]; exists {
				result.ClassEdges[position].Count++
			} else if len(result.ClassEdges) < 50000 {
				edgePositions[key] = len(result.ClassEdges)
				result.ClassEdges = append(result.ClassEdges, ClassDependency{caller, callee, 1})
			}
		}
		incrementClass(caller)
		incrementClass(callee)
	}
	return result, nil
}

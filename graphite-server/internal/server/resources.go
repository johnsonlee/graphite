package server

import (
	"errors"
	"io"
	"net/http"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

const maxResourceBytes = 1048576

var errResourceTooLarge = errors.New("Resource exceeds maximum response size")

func (s *Server) resourceLeases(w http.ResponseWriter, r *http.Request) ([]*Lease, bool) {
	if r.PathValue("graphId") != "" {
		l := s.acquire(w, r)
		if l == nil {
			return nil, false
		}
		return []*Lease{l}, true
	}
	ls, err := s.Registry.AcquireSelected(nil)
	if err != nil {
		writeError(w, 400, err)
		return nil, false
	}
	return ls, true
}
func closeLeases(leases []*Lease) {
	for _, l := range leases {
		_ = l.Close()
	}
}
func resourceSource(g Graph) *store.Store {
	if n, ok := g.(*NativeGraph); ok {
		return n.Store
	}
	return nil
}
func requireResources(w http.ResponseWriter, leases []*Lease, scoped bool) bool {
	unavailable := []map[string]any{}
	for _, l := range leases {
		g := resourceSource(l.Graph)
		if g == nil {
			writeError(w, 501, errors.New("Resource access is not implemented"))
			return false
		}
		reason, err := g.ResourceUnavailableReason()
		if err != nil {
			writeError(w, 500, err)
			return false
		}
		if reason != "" {
			unavailable = append(unavailable, map[string]any{"graphId": l.ID, "error": reason})
		}
	}
	if len(unavailable) == 0 {
		return true
	}
	if scoped {
		writeJSON(w, 409, map[string]any{"error": unavailable[0]["error"]})
	} else {
		writeJSON(w, 409, map[string]any{"error": "Persisted resources are unavailable for one or more graphs", "results": unavailable})
	}
	return false
}
func groupedResponse(leases []*Lease, results []map[string]any) map[string]any {
	return map[string]any{"graphCount": len(leases), "resultGraphCount": len(results), "results": results}
}
func (s *Server) resources(w http.ResponseWriter, r *http.Request) {
	leases, ok := s.resourceLeases(w, r)
	if !ok {
		return
	}
	defer closeLeases(leases)
	scoped := r.PathValue("graphId") != ""
	if !requireResources(w, leases, scoped) {
		return
	}
	pattern := r.URL.Query().Get("pattern")
	if _, ok := r.URL.Query()["pattern"]; !ok {
		pattern = "**"
	}
	limit := boundedInt(r.URL.Query().Get("limit"), 100, 1000)
	results := make([]map[string]any, 0, len(leases))
	for i, l := range leases {
		graphLimit := limit
		if !scoped {
			graphLimit = limit / len(leases)
			if i < limit%len(leases) {
				graphLimit++
			}
		}
		entries, err := resourceSource(l.Graph).ResourceList(pattern)
		if err != nil {
			writeError(w, 500, err)
			return
		}
		if len(entries) > graphLimit {
			entries = entries[:graphLimit]
		}
		resources := make([]map[string]any, 0, len(entries))
		for _, entry := range entries {
			resources = append(resources, map[string]any{"path": entry.Path, "source": entry.Source, "derived": false})
		}
		value := map[string]any{"pattern": pattern, "limit": graphLimit, "count": len(resources), "resources": resources}
		if scoped {
			writeJSON(w, 200, value)
			return
		}
		results = append(results, map[string]any{"graphId": l.ID, "data": value})
	}
	writeJSON(w, 200, groupedResponse(leases, results))
}
func readResource(g *store.Store, path string, limit int) (map[string]any, error) {
	entries, err := g.ResourceList("**")
	if err != nil {
		return nil, err
	}
	var found *store.ResourceEntry
	for _, entry := range entries {
		if entry.Path == path {
			copy := entry
			found = &copy
			break
		}
	}
	if found == nil {
		return nil, nil
	}
	in, err := g.OpenResource(path)
	if err != nil {
		return nil, nil
	}
	defer in.Close()
	content, err := io.ReadAll(io.LimitReader(in, int64(limit+1)))
	if err != nil {
		return nil, nil
	}
	if len(content) > limit {
		return nil, errResourceTooLarge
	}
	return map[string]any{"path": path, "source": found.Source, "derived": false, "size": len(content), "content": strings.ToValidUTF8(string(content), "\uFFFD")}, nil
}
func (s *Server) resource(w http.ResponseWriter, r *http.Request) {
	leases, ok := s.resourceLeases(w, r)
	if !ok {
		return
	}
	defer closeLeases(leases)
	scoped := r.PathValue("graphId") != ""
	if !requireResources(w, leases, scoped) {
		return
	}
	path := strings.TrimLeft(r.PathValue("path"), "/")
	if strings.TrimSpace(path) == "" {
		writeText(w, 404, "Resource not found")
		return
	}
	remaining := maxResourceBytes
	results := make([]map[string]any, 0, len(leases))
	for _, l := range leases {
		value, err := readResource(resourceSource(l.Graph), path, remaining)
		if errors.Is(err, errResourceTooLarge) {
			writeText(w, 413, errResourceTooLarge.Error()+": "+path)
			return
		}
		if err != nil {
			writeError(w, 500, err)
			return
		}
		if value == nil {
			continue
		}
		remaining -= value["size"].(int)
		if scoped {
			writeJSON(w, 200, value)
			return
		}
		results = append(results, map[string]any{"graphId": l.ID, "data": value})
	}
	if len(results) == 0 {
		writeText(w, 404, "Resource not found: "+path)
		return
	}
	writeJSON(w, 200, groupedResponse(leases, results))
}

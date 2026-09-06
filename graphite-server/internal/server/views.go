package server

import (
	"errors"
	"net/http"

	"github.com/johnsonlee/graphite/graphite-server/internal/analysis"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func (s *Server) overview(w http.ResponseWriter, r *http.Request) {
	s.limitedView(w, r, 200, 1000, func(graph *store.Store, limit int) (map[string]any, error) { return graph.Overview(limit) })
}
func (s *Server) endpoints(w http.ResponseWriter, r *http.Request) {
	s.limitedView(w, r, 200, 2000, func(graph *store.Store, limit int) (map[string]any, error) {
		all := analysis.ExtractEndpoints(graph)
		selected := make([]map[string]any, 0, min(limit, len(all)))
		class, present := r.URL.Query()["class"]
		for _, endpoint := range all {
			if len(selected) >= limit {
				break
			}
			if !present || len(class) > 0 && endpoint["class"] == class[0] {
				selected = append(selected, endpoint)
			}
		}
		return map[string]any{"framework": "spring-web", "count": len(selected), "endpoints": selected}, nil
	})
}
func (s *Server) limitedView(w http.ResponseWriter, r *http.Request, defaultLimit, maxLimit int, view func(*store.Store, int) (map[string]any, error)) {
	ls, ok := s.resourceLeases(w, r)
	if !ok {
		return
	}
	defer closeLeases(ls)
	scoped := r.PathValue("graphId") != ""
	limit := boundedInt(r.URL.Query().Get("limit"), defaultLimit, maxLimit)
	results := make([]map[string]any, 0, len(ls))
	for i, l := range ls {
		g := resourceSource(l.Graph)
		if g == nil {
			writeError(w, 501, errors.New("Native graph view is not implemented"))
			return
		}
		graphLimit := limit
		if !scoped {
			graphLimit = limit / len(ls)
			if i < limit%len(ls) {
				graphLimit++
			}
		}
		value, err := view(g, graphLimit)
		if err != nil {
			writeError(w, 500, err)
			return
		}
		if scoped {
			writeJSON(w, 200, value)
			return
		}
		results = append(results, map[string]any{"graphId": l.ID, "data": value})
	}
	writeJSON(w, 200, groupedResponse(ls, results))
}

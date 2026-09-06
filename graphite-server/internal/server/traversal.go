package server

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

type traversalGraph interface {
	Node(int32) (map[string]any, error)
	Outgoing(int32) []store.Edge
	Incoming(int32) []store.Edge
}

func edgeMap(e store.Edge) map[string]any {
	m := map[string]any{"from": e.From, "to": e.To, "type": strings.TrimSuffix(e.Family, "Edge")}
	if e.Family == "CallEdge" {
		m["virtual"] = e.IsVirtual
		m["dynamic"] = e.IsDynamic
	} else {
		m["kind"] = e.Kind
	}
	return m
}
func boundedInt(raw string, defaultValue, max int) int {
	n, err := strconv.ParseInt(raw, 10, 32)
	if err != nil {
		return defaultValue
	}
	if n < 0 {
		return 0
	}
	if n > int64(max) {
		return max
	}
	return int(n)
}
func (s *Server) outgoing(w http.ResponseWriter, r *http.Request) { s.edges(w, r, true) }
func (s *Server) incoming(w http.ResponseWriter, r *http.Request) { s.edges(w, r, false) }
func (s *Server) edges(w http.ResponseWriter, r *http.Request, outgoing bool) {
	l := s.acquire(w, r)
	if l == nil {
		return
	}
	defer l.Close()
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 32)
	if err != nil {
		writeText(w, 400, "Invalid node ID")
		return
	}
	g, ok := l.Graph.(traversalGraph)
	if !ok {
		writeError(w, 501, errors.New("Graph traversal is not implemented"))
		return
	}
	limit := boundedInt(r.URL.Query().Get("limit"), 200, 2000)
	var edges []store.Edge
	if outgoing {
		edges = g.Outgoing(int32(id))
	} else {
		edges = g.Incoming(int32(id))
	}
	if len(edges) > limit {
		edges = edges[:limit]
	}
	response := make([]map[string]any, 0, len(edges))
	for _, e := range edges {
		response = append(response, edgeMap(e))
	}
	writeJSON(w, 200, response)
}
func (s *Server) subgraph(w http.ResponseWriter, r *http.Request) {
	l := s.acquire(w, r)
	if l == nil {
		return
	}
	defer l.Close()
	center, err := strconv.ParseInt(r.URL.Query().Get("center"), 10, 32)
	if err != nil {
		writeText(w, 400, "Missing 'center' parameter")
		return
	}
	direction := strings.ToLower(r.URL.Query().Get("direction"))
	if _, present := r.URL.Query()["direction"]; !present {
		direction = "both"
	}
	if direction != "both" && direction != "outgoing" && direction != "incoming" {
		writeText(w, 400, "Invalid 'direction' parameter")
		return
	}
	depth := 2
	if n, err := strconv.ParseInt(r.URL.Query().Get("depth"), 10, 32); err == nil {
		depth = int(n)
	}
	if depth > 4 {
		depth = 4
	}
	g, ok := l.Graph.(traversalGraph)
	if !ok {
		writeError(w, 501, errors.New("Graph traversal is not implemented"))
		return
	}
	response, err := buildSubgraph(r.Context(), g, int32(center), depth, direction)
	if err != nil {
		writeError(w, 500, err)
		return
	}
	writeJSON(w, 200, response)
}
func buildSubgraph(ctx context.Context, g traversalGraph, center int32, depth int, direction string) (map[string]any, error) {
	visited := map[int32]bool{}
	nodes, edges := []map[string]any{}, []map[string]any{}
	var visit func(int32, int) error
	visit = func(id int32, remaining int) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		if len(nodes) >= 2000 || visited[id] {
			return nil
		}
		// Match Kotlin's visited-set update even when remaining depth is negative.
		visited[id] = true
		if remaining < 0 {
			return nil
		}
		n, err := g.Node(id)
		if errors.Is(err, store.ErrNodeNotFound) {
			return nil
		}
		if err != nil {
			return err
		}
		nodes = append(nodes, n)
		if remaining == 0 {
			return nil
		}
		traverse := func(candidates []store.Edge, out bool) error {
			for _, e := range candidates {
				if len(edges) >= 5000 {
					break
				}
				edges = append(edges, edgeMap(e))
				next := e.From
				if out {
					next = e.To
				}
				if err := visit(next, remaining-1); err != nil {
					return err
				}
			}
			return nil
		}
		if direction == "both" || direction == "outgoing" {
			if err := traverse(g.Outgoing(id), true); err != nil {
				return err
			}
		}
		if direction == "both" || direction == "incoming" {
			if err := traverse(g.Incoming(id), false); err != nil {
				return err
			}
		}
		return nil
	}
	if err := visit(center, depth); err != nil {
		return nil, err
	}
	return map[string]any{"nodes": nodes, "edges": edges}, nil
}

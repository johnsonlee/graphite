package server

import (
	"encoding/json"
	"errors"
	"io"
	"io/fs"
	"net/http"
	"reflect"
	"strconv"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"github.com/johnsonlee/graphite/graphite-server/internal/web"
)

type Server struct {
	Registry *Registry
	Guard    *Guard
	Version  string
	Topology *TopologyService
	Metrics  *PerformanceMetrics
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	if s.Metrics != nil {
		mux.Handle("GET /metrics", s.Metrics)
	}
	mux.HandleFunc("GET /openapi.json", s.openAPI)
	mux.HandleFunc("GET /swagger.json", s.openAPI)
	mux.HandleFunc("GET /api/topology", s.topology)
	mux.HandleFunc("GET /api/graphs", s.listGraphs)
	mux.HandleFunc("GET /api/graphs/{graphId}", s.describeGraph)
	mux.HandleFunc("PUT /api/graphs/{graphId}", s.loadGraph)
	mux.HandleFunc("POST /api/graphs/{graphId}", s.loadGraph)
	mux.HandleFunc("DELETE /api/graphs/{graphId}", s.unloadGraph)
	mux.HandleFunc("GET /api/graphs/{graphId}/node/{id}", s.node)
	mux.HandleFunc("GET /api/graphs/{graphId}/node/{id}/outgoing", s.outgoing)
	mux.HandleFunc("GET /api/graphs/{graphId}/node/{id}/incoming", s.incoming)
	mux.HandleFunc("GET /api/graphs/{graphId}/subgraph", s.subgraph)
	mux.HandleFunc("GET /api/graphs/{graphId}/annotations", s.annotations)
	mux.HandleFunc("GET /api/annotations", s.allAnnotations)
	mux.HandleFunc("GET /api/graphs/{graphId}/cypher", s.cypher)
	mux.HandleFunc("POST /api/graphs/{graphId}/cypher", s.cypher)
	mux.HandleFunc("GET /api/cypher", s.crossCypher)
	mux.HandleFunc("POST /api/cypher", s.crossCypher)
	mux.HandleFunc("GET /api/cypher/graphs", s.crossCypher)
	mux.HandleFunc("POST /api/cypher/graphs", s.crossCypher)
	mux.HandleFunc("GET /api/graphs/{graphId}/resources", s.resources)
	mux.HandleFunc("GET /api/graphs/{graphId}/resources/{path...}", s.resource)
	mux.HandleFunc("GET /api/resources", s.resources)
	mux.HandleFunc("GET /api/resources/{path...}", s.resource)
	mux.HandleFunc("GET /api/overview", s.overview)
	mux.HandleFunc("GET /api/graphs/{graphId}/overview", s.overview)
	mux.HandleFunc("GET /api/endpoints", s.endpoints)
	mux.HandleFunc("GET /api/graphs/{graphId}/endpoints", s.endpoints)
	mux.HandleFunc("GET /api/architecture/c4", s.architecture)
	mux.HandleFunc("GET /api/graphs/{graphId}/architecture/c4", s.architecture)
	assets := web.Files()
	static := http.FileServer(http.FS(assets))
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		path := strings.TrimPrefix(r.URL.Path, "/")
		if path == "" {
			path = "index.html"
		}
		if (r.Method == http.MethodGet || r.Method == http.MethodHead) && fs.ValidPath(path) {
			if info, err := fs.Stat(assets, path); err == nil && !info.IsDir() {
				static.ServeHTTP(w, r)
				return
			}
		}
		if strings.Contains(r.Header.Get("Accept"), "application/json") {
			writeJSON(w, 404, map[string]any{"title": "Endpoint " + r.Method + " " + r.URL.Path + " not found", "status": 404, "type": "https://javalin.io/documentation#endpointnotfound", "details": map[string]any{}})
		} else {
			writeText(w, 404, "Endpoint "+r.Method+" "+r.URL.Path+" not found")
		}
	})
	return s.instrumentHTTP(mux)
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	// Encode before committing status; malformed values must never produce a
	// truncated successful response.
	body, err := json.Marshal(omitNullFields(value))
	if err != nil {
		writeJSON(w, 500, map[string]any{"title": "Server Error", "status": 500, "type": "https://javalin.io/documentation#internalservererrorresponse", "details": map[string]any{}})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

// Gson omits null object members but retains null elements in arrays. Keep
// input snapshots immutable: response shaping must not alter shared graph data.
func omitNullFields(value any) any {
	if value == nil {
		return nil
	}
	switch n := value.(type) {
	case float64:
		return wireFloat{n, 64}
	case float32:
		return wireFloat{float64(n), 32}
	}
	rv := reflect.ValueOf(value)
	if rv.Kind() == reflect.Pointer {
		if rv.IsNil() {
			return nil
		}
		return omitNullFields(rv.Elem().Interface())
	}
	if rv.Kind() == reflect.Map && rv.Type().Key().Kind() == reflect.String {
		out := make(map[string]any, rv.Len())
		iter := rv.MapRange()
		for iter.Next() {
			v := omitNullFields(iter.Value().Interface())
			if v != nil {
				out[iter.Key().String()] = v
			}
		}
		return out
	}
	if rv.Kind() == reflect.Slice || rv.Kind() == reflect.Array {
		// Preserve []byte's standard base64 encoding.
		if rv.Type().Elem().Kind() == reflect.Uint8 {
			return value
		}
		out := make([]any, rv.Len())
		for i := range out {
			out[i] = omitNullFields(rv.Index(i).Interface())
		}
		return out
	}
	return value
}
func writeError(w http.ResponseWriter, status int, err error) {
	writeJSON(w, status, map[string]any{"error": err.Error()})
}
func writeText(w http.ResponseWriter, status int, text string) {
	w.Header().Set("Content-Type", "text/plain")
	w.WriteHeader(status)
	_, _ = io.WriteString(w, text)
}

func (s *Server) listGraphs(w http.ResponseWriter, r *http.Request) {
	descriptors := s.Registry.List()
	var totals Stats
	for _, d := range descriptors {
		totals = totals.Add(d.Stats)
	}
	writeJSON(w, 200, map[string]any{"data": s.Registry.DataDir, "loadMode": s.Registry.DefaultLoadMode, "count": len(descriptors), "totals": totals, "graphs": descriptors})
}
func (s *Server) describeGraph(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("graphId")
	d, err := s.Registry.Describe(id)
	if err != nil {
		writeError(w, 400, err)
		return
	}
	if d == nil {
		writeError(w, 404, errors.New("Graph not loaded: "+id))
		return
	}
	writeJSON(w, 200, map[string]any{"graph": d})
}
func (s *Server) loadGraph(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		writeError(w, 400, err)
		return
	}
	path, mode := r.URL.Query().Get("path"), r.URL.Query().Get("loadMode")
	_, hasPath := r.URL.Query()["path"]
	if len(strings.TrimSpace(string(body))) > 0 {
		var request struct {
			Path     *string `json:"path"`
			LoadMode *string `json:"loadMode"`
		}
		if err = json.Unmarshal(body, &request); err != nil {
			writeError(w, 400, err)
			return
		}
		hasPath = request.Path != nil
		if hasPath {
			path = *request.Path
		}
		mode = ""
		if request.LoadMode != nil {
			mode = *request.LoadMode
		}
	}
	if !hasPath {
		writeError(w, 400, errors.New("Missing 'path' field"))
		return
	}
	d, err := s.Registry.Load(r.PathValue("graphId"), path, strings.ToUpper(mode))
	if err != nil {
		writeError(w, 400, err)
		return
	}
	writeJSON(w, 200, map[string]any{"graph": d})
}
func (s *Server) unloadGraph(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("graphId")
	removed, err := s.Registry.Unload(id)
	if err != nil {
		writeError(w, 400, err)
		return
	}
	if !removed {
		writeError(w, 404, errors.New("Graph not loaded: "+id))
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
func (s *Server) acquire(w http.ResponseWriter, r *http.Request) *Lease {
	id, err := ValidateGraphID(r.PathValue("graphId"))
	if err != nil {
		writeError(w, 400, err)
		return nil
	}
	l, err := s.Registry.Acquire(id)
	if err != nil {
		writeError(w, 404, errors.New("Graph not loaded"))
		return nil
	}
	return l
}
func (s *Server) node(w http.ResponseWriter, r *http.Request) {
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
	g, ok := l.Graph.(interface {
		Node(int32) (map[string]any, error)
	})
	if !ok {
		writeError(w, 501, errors.New("Node access is not implemented"))
		return
	}
	n, err := g.Node(int32(id))
	if errors.Is(err, store.ErrNodeNotFound) {
		writeText(w, 404, "Node not found")
		return
	}
	if err != nil {
		writeError(w, 500, err)
		return
	}
	writeJSON(w, 200, n)
}
func annotations(g Graph, class, member string) (map[string]map[string]any, error) {
	if source, ok := g.(interface {
		Annotations(string, string) map[string]map[string]any
	}); ok {
		return source.Annotations(class, member), nil
	}
	return nil, errors.New("Annotation access is not implemented")
}
func annotationParams(w http.ResponseWriter, r *http.Request) (string, string, bool) {
	q := r.URL.Query()
	for _, key := range []string{"class", "member"} {
		if _, ok := q[key]; !ok {
			writeText(w, 400, "Missing '"+key+"' parameter")
			return "", "", false
		}
	}
	return q.Get("class"), q.Get("member"), true
}
func (s *Server) annotations(w http.ResponseWriter, r *http.Request) {
	l := s.acquire(w, r)
	if l == nil {
		return
	}
	defer l.Close()
	class, member, ok := annotationParams(w, r)
	if !ok {
		return
	}
	result, err := annotations(l.Graph, class, member)
	if err != nil {
		writeError(w, 501, err)
		return
	}
	writeJSON(w, 200, result)
}
func (s *Server) allAnnotations(w http.ResponseWriter, r *http.Request) {
	class, member, ok := annotationParams(w, r)
	if !ok {
		return
	}
	leases, err := s.Registry.AcquireSelected(nil)
	if err != nil {
		writeError(w, 400, err)
		return
	}
	defer func() {
		for _, l := range leases {
			_ = l.Close()
		}
	}()
	results := make([]map[string]any, 0, len(leases))
	for _, l := range leases {
		value, err := annotations(l.Graph, class, member)
		if err != nil {
			writeError(w, 501, err)
			return
		}
		results = append(results, map[string]any{"graphId": l.ID, "data": value})
	}
	writeJSON(w, 200, map[string]any{"graphCount": len(leases), "resultGraphCount": len(results), "results": results})
}

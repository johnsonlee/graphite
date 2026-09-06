package server

import (
	"errors"
	"net/http"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/analysis/c4"
)

var c4Levels = []string{"context", "container", "component", "all"}
var c4Formats = []string{"json", "mermaid", "plantuml", "dsl"}

func c4Format(accept, queryFormat string) string {
	accepted := map[string]bool{}
	for _, part := range strings.Split(accept, ",") {
		media, _, _ := strings.Cut(part, ";")
		accepted[strings.ToLower(strings.TrimSpace(media))] = true
	}
	for _, group := range []struct {
		format string
		types  []string
	}{
		{"plantuml", []string{"text/vnd.plantuml", "text/x-plantuml", "application/vnd.plantuml"}},
		{"mermaid", []string{"text/vnd.mermaid", "text/x-mermaid"}},
		{"dsl", []string{"text/vnd.structurizr.dsl", "text/x-structurizr", "application/vnd.structurizr.dsl"}},
		{"json", []string{"application/vnd.structurizr+json", "application/json"}},
	} {
		for _, media := range group.types {
			if accepted[media] {
				return group.format
			}
		}
	}
	if strings.TrimSpace(queryFormat) != "" {
		return strings.ToLower(queryFormat)
	}
	return "json"
}

func (s *Server) architecture(w http.ResponseWriter, r *http.Request) {
	scoped := r.PathValue("graphId") != ""
	var leases []*Lease
	if scoped {
		l := s.acquire(w, r)
		if l == nil {
			return
		}
		leases = []*Lease{l}
		defer closeLeases(leases)
	}
	level := "all"
	if values, ok := r.URL.Query()["level"]; ok {
		level = values[0]
	}
	format := c4Format(r.Header.Get("Accept"), r.URL.Query().Get("format"))
	contains := func(values []string, value string) bool {
		for _, v := range values {
			if v == value {
				return true
			}
		}
		return false
	}
	if !contains(c4Levels, level) {
		writeJSON(w, 400, map[string]any{"error": "Invalid 'level' parameter", "allowed": c4Levels})
		return
	}
	if !contains(c4Formats, format) {
		writeJSON(w, 400, map[string]any{"error": "Invalid 'format' parameter", "allowed": c4Formats})
		return
	}
	if !scoped {
		var err error
		leases, err = s.Registry.AcquireSelected(nil)
		if err != nil {
			writeError(w, 500, err)
			return
		}
		defer closeLeases(leases)
	}
	models := make([]map[string]any, 0, len(leases))
	for _, lease := range leases {
		g, ok := lease.Graph.(*NativeGraph)
		if !ok {
			writeError(w, 501, errors.New("Native C4 graph access is not implemented"))
			return
		}
		model, err := c4.BuildModel(g.Store, level)
		if err != nil {
			writeError(w, 500, err)
			return
		}
		models = append(models, model)
	}
	if format == "json" {
		if scoped {
			// Javalin's ctx.json overwrites the preceding vendor content type.
			writeJSON(w, 200, models[0])
			return
		}
		results := make([]map[string]any, 0, len(models))
		for i, model := range models {
			results = append(results, map[string]any{"graphId": leases[i].ID, "data": model})
		}
		writeJSON(w, 200, groupedResponse(leases, results))
		return
	}
	type renderer func(map[string]any) (string, error)
	var render renderer
	var contentType, prefix string
	switch format {
	case "mermaid":
		render = c4.RenderMermaid
		contentType = "text/vnd.mermaid; charset=utf-8"
		prefix = "%%"
	case "plantuml":
		render = c4.RenderPlantUML
		contentType = "text/vnd.plantuml; charset=utf-8"
		prefix = "'"
	case "dsl":
		render = c4.RenderStructurizrDSL
		contentType = "text/vnd.structurizr.dsl; charset=utf-8"
		prefix = "//"
	}
	texts := make([]string, 0, len(models))
	for i, model := range models {
		text, err := render(model)
		if err != nil {
			writeError(w, 500, err)
			return
		}
		if !scoped {
			text = prefix + " graphId: " + leases[i].ID + "\n" + text
		}
		texts = append(texts, text)
	}
	w.Header().Set("Content-Type", contentType)
	_, _ = w.Write([]byte(strings.Join(texts, "\n\n")))
}

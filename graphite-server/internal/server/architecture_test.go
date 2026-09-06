package server

import (
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"
)

func TestArchitectureFormatNegotiation(t *testing.T) {
	for _, tc := range []struct{ accept, query, want string }{
		{"application/json", "mermaid", "json"},
		{"text/vnd.mermaid;q=1,text/x-plantuml;q=0", "json", "plantuml"},
		{"application/vnd.structurizr.dsl,application/json", "mermaid", "dsl"},
		{"*/*", "MERMAID", "mermaid"},
		{"text/plain", "", "json"},
		{"", " JSON ", " json "},
	} {
		if got := c4Format(tc.accept, tc.query); got != tc.want {
			t.Errorf("%q %q: %q", tc.accept, tc.query, got)
		}
	}
}

func TestArchitectureEmptyCatalogAndValidationOrder(t *testing.T) {
	r := nativeRegistryForTest(t)
	h := (&Server{Registry: r}).Handler()
	response := request(t, h, "GET", "/api/architecture/c4", "", 200)
	if response.Header().Get("Content-Type") != "application/json" {
		t.Fatalf("main JSON content type: %v", response.Header())
	}
	got := responseJSON(t, response)
	want := map[string]any{"graphCount": float64(0), "resultGraphCount": float64(0), "results": []any{}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("empty C4 catalog: %v", got)
	}
	bad := responseJSON(t, request(t, h, "GET", "/api/architecture/c4?level=ALL&format=bad", "", 400))
	if bad["error"] != "Invalid 'level' parameter" || !reflect.DeepEqual(bad["allowed"], []any{"context", "container", "component", "all"}) {
		t.Fatalf("level validation: %v", bad)
	}
	bad = responseJSON(t, request(t, h, "GET", "/api/architecture/c4?format=bad", "", 400))
	if bad["error"] != "Invalid 'format' parameter" || !reflect.DeepEqual(bad["allowed"], []any{"json", "mermaid", "plantuml", "dsl"}) {
		t.Fatalf("format validation: %v", bad)
	}
	request(t, h, "GET", "/api/graphs/missing/architecture/c4?level=bad", "", 404)
	for _, format := range []string{"mermaid", "plantuml", "dsl"} {
		w := request(t, h, "GET", "/api/architecture/c4?format="+format, "", 200)
		if w.Body.Len() != 0 {
			t.Fatalf("empty %s: %q", format, w.Body.String())
		}
	}
}

func TestArchitectureGroupedModelsAndRenderedGraphs(t *testing.T) {
	r := nativeRegistryForTest(t, "z", "a")
	h := (&Server{Registry: r}).Handler()
	scoped := responseJSON(t, request(t, h, "GET", "/api/graphs/a/architecture/c4?level=context", "", 200))
	root := responseJSON(t, request(t, h, "GET", "/api/architecture/c4?level=context", "", 200))
	results := root["results"].([]any)
	if root["graphCount"] != float64(2) || len(results) != 2 {
		t.Fatalf("grouped C4: %v", root)
	}
	for i, id := range []string{"a", "z"} {
		result := results[i].(map[string]any)
		if result["graphId"] != id || !reflect.DeepEqual(result["data"], scoped) {
			t.Fatalf("graph %s has changed model or ordering", id)
		}
	}
	for _, tc := range []struct{ accept, prefix, content string }{
		{"text/x-mermaid", "%%", "text/vnd.mermaid; charset=utf-8"},
		{"application/vnd.plantuml", "'", "text/vnd.plantuml; charset=utf-8"},
		{"text/x-structurizr", "//", "text/vnd.structurizr.dsl; charset=utf-8"},
	} {
		w := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/api/architecture/c4?level=context&format=bad", nil)
		req.Header.Set("Accept", tc.accept)
		h.ServeHTTP(w, req)
		if w.Code != 200 || w.Header().Get("Content-Type") != tc.content {
			t.Fatalf("render response: %d %v %s", w.Code, w.Header(), w.Body.String())
		}
		if !strings.HasPrefix(w.Body.String(), tc.prefix+" graphId: a\n") || !strings.Contains(w.Body.String(), "\n\n"+tc.prefix+" graphId: z\n") {
			t.Fatalf("render grouping: %s", w.Body.String())
		}
	}
}

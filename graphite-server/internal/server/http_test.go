package server

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

type httpGraph struct{ testGraph }

func (g *httpGraph) Node(id int32) (map[string]any, error) {
	if id == 42 {
		return map[string]any{"id": int32(42), "type": "StringConstant", "value": "feature.enabled", "label": "\"feature.enabled\""}, nil
	}
	return nil, store.ErrNodeNotFound
}
func (g *httpGraph) Annotations(class, member string) map[string]map[string]any {
	if class == "com.example.Foo" && member == "bar" {
		return map[string]map[string]any{"example.Route": {"path": "/users"}}
	}
	return map[string]map[string]any{}
}
func request(t *testing.T, h http.Handler, method, path, body string, status int) *httptest.ResponseRecorder {
	t.Helper()
	w := httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest(method, path, strings.NewReader(body)))
	if w.Code != status {
		t.Fatalf("%s %s: %d != %d: %s", method, path, w.Code, status, w.Body.String())
	}
	return w
}
func responseJSON(t *testing.T, w *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	var result map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	return result
}

func TestCatalogHTTPAndAtomicFailedReplacement(t *testing.T) {
	var fail bool
	r := registryForTest(t, func(string, string) (Graph, error) {
		if fail {
			return nil, errors.New("broken graph")
		}
		return &httpGraph{testGraph{stats: Stats{Nodes: 8, Edges: 11, Methods: 2, CallSites: 3}}}, nil
	})
	h := (&Server{Registry: r}).Handler()
	empty := responseJSON(t, request(t, h, "GET", "/api/graphs", "", 200))
	if empty["count"] != float64(0) || !reflect.DeepEqual(empty["graphs"], []any{}) {
		t.Fatalf("empty catalog: %#v", empty)
	}
	before := responseJSON(t, request(t, h, "PUT", "/api/graphs/test", `{"path":".","loadMode":"mapped"}`, 200))["graph"].(map[string]any)
	if before["nodes"] != float64(8) || before["edges"] != float64(11) || before["id"] != "test" || before["loadMode"] != "MAPPED" {
		t.Fatalf("graph: %#v", before)
	}
	fail = true
	request(t, h, "POST", "/api/graphs/test", `{"path":"."}`, 400)
	after := responseJSON(t, request(t, h, "GET", "/api/graphs/test", "", 200))["graph"]
	if !reflect.DeepEqual(before, after) {
		t.Fatalf("failed replacement changed descriptor: %v", after)
	}
	list := responseJSON(t, request(t, h, "GET", "/api/graphs", "", 200))
	if list["totals"].(map[string]any)["callSites"] != float64(3) {
		t.Fatalf("wrong totals: %#v", list)
	}
	w := request(t, h, "DELETE", "/api/graphs/test", "", 204)
	if w.Body.Len() != 0 {
		t.Fatal("204 must have empty body")
	}
	request(t, h, "DELETE", "/api/graphs/test", "", 404)
}

func TestScopedNodesAndAnnotations(t *testing.T) {
	r := registryForTest(t, func(string, string) (Graph, error) { return &httpGraph{}, nil })
	_, _ = r.Load("a", ".", "")
	h := (&Server{Registry: r}).Handler()
	node := responseJSON(t, request(t, h, "GET", "/api/graphs/a/node/42", "", 200))
	want := map[string]any{"id": float64(42), "type": "StringConstant", "value": "feature.enabled", "label": "\"feature.enabled\""}
	if !reflect.DeepEqual(node, want) {
		t.Fatalf("node: %#v", node)
	}
	for _, tc := range []struct {
		path   string
		status int
		text   string
	}{
		{"/api/graphs/a/node/2147483648", 400, "Invalid node ID"},
		{"/api/graphs/a/node/-1", 404, "Node not found"},
		{"/api/graphs/a/node/nope", 400, "Invalid node ID"},
	} {
		w := request(t, h, "GET", tc.path, "", tc.status)
		if w.Body.String() != tc.text {
			t.Fatalf("wrong text: %q", w.Body.String())
		}
	}
	request(t, h, "GET", "/api/node/42", "", 404)
	request(t, h, "GET", "/api/graphs/missing/node/42", "", 404)
	request(t, h, "GET", "/api/graphs/a/annotations?class=Foo", "", 400)
	path := "/annotations?class=com.example.Foo&member=bar"
	scoped := responseJSON(t, request(t, h, "GET", "/api/graphs/a"+path, "", 200))
	if !reflect.DeepEqual(scoped, map[string]any{"example.Route": map[string]any{"path": "/users"}}) {
		t.Fatalf("annotations: %#v", scoped)
	}
	all := responseJSON(t, request(t, h, "GET", "/api"+path, "", 200))
	if all["graphCount"] != float64(1) || all["resultGraphCount"] != float64(1) {
		t.Fatalf("grouped counts: %#v", all)
	}
	entry := all["results"].([]any)[0].(map[string]any)
	if entry["graphId"] != "a" || !reflect.DeepEqual(entry["data"], scoped) {
		t.Fatalf("grouped annotations: %#v", entry)
	}
}

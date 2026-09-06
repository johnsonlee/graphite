package server

import (
	"path/filepath"
	"reflect"
	"testing"
	"time"
)

func nativeRegistryForTest(t *testing.T, ids ...string) *Registry {
	t.Helper()
	dir, err := filepath.Abs("../store/testdata/jvm-v3")
	if err != nil {
		t.Fatal(err)
	}
	r := registryForTest(t, OpenNativeGraph)
	for _, id := range ids {
		if _, err := r.Load(id, dir, ""); err != nil {
			t.Fatal(err)
		}
	}
	return r
}
func TestResourceHTTPGroupingAndDistributedLimit(t *testing.T) {
	r := nativeRegistryForTest(t, "z", "a")
	h := (&Server{Registry: r}).Handler()
	root := responseJSON(t, request(t, h, "GET", "/api/resources?limit=1", "", 200))
	results := root["results"].([]any)
	first := results[0].(map[string]any)
	second := results[1].(map[string]any)
	if root["graphCount"] != float64(2) || first["graphId"] != "a" || second["graphId"] != "z" {
		t.Fatalf("grouped resources: %#v", root)
	}
	a := first["data"].(map[string]any)
	z := second["data"].(map[string]any)
	if a["limit"] != float64(1) || a["count"] != float64(1) || z["limit"] != float64(0) || !reflect.DeepEqual(z["resources"], []any{}) {
		t.Fatalf("distributed cap: %#v", root)
	}
	value := responseJSON(t, request(t, h, "GET", "/api/graphs/a/resources/config.yml", "", 200))
	want := map[string]any{"path": "config.yml", "source": "app.jar", "derived": false, "size": float64(len("key: 世界\n")), "content": "key: 世界\n"}
	if !reflect.DeepEqual(value, want) {
		t.Fatalf("resource body: %#v", value)
	}
	all := responseJSON(t, request(t, h, "GET", "/api/resources/config.yml", "", 200))
	if all["resultGraphCount"] != float64(2) || !reflect.DeepEqual(all["results"].([]any)[1].(map[string]any)["data"], want) {
		t.Fatalf("colliding resource paths: %#v", all)
	}
	missing := request(t, h, "GET", "/api/resources/absent", "", 404)
	if missing.Body.String() != "Resource not found: absent" {
		t.Fatalf("missing resource: %s", missing.Body.String())
	}
}

func TestCrossAndFanoutHTTPShapes(t *testing.T) {
	r := nativeRegistryForTest(t, "a", "b")
	g, _ := NewGuard(4, time.Second)
	defer g.Close()
	h := (&Server{Registry: r, Guard: g}).Handler()
	root := responseJSON(t, request(t, h, "POST", "/api/cypher", `{"query":"RETURN 1 AS x"}`, 200))
	if root["graphCount"] != float64(2) || !reflect.DeepEqual(root["rows"], []any{map[string]any{"x": float64(1), "$metadata": map[string]any{"graphIds": []any{}}}}) {
		t.Fatalf("cross literal: %#v", root)
	}
	selected := responseJSON(t, request(t, h, "POST", "/api/cypher/graphs", `{"graphs":["b","a"],"query":"RETURN 1 AS x"}`, 200))
	if selected["mode"] != "cross-graph" || !reflect.DeepEqual(selected["graphs"], []any{"b", "a"}) {
		t.Fatalf("selected: %#v", selected)
	}
	fan := responseJSON(t, request(t, h, "POST", "/api/cypher/graphs", `{"graphs":["b","a"],"mode":"fanout","query":"RETURN 1 AS x","limit":1,"includeGraphRows":true}`, 200))
	if fan["graphCount"] != float64(2) || fan["queriedGraphCount"] != float64(1) || fan["truncated"] != true || !reflect.DeepEqual(fan["rows"], []any{map[string]any{"graphId": "b", "x": float64(1)}}) {
		t.Fatalf("fanout: %#v", fan)
	}
	request(t, h, "POST", "/api/cypher/graphs", `{"graphs":["a","a"],"query":"RETURN 1"}`, 400)
	request(t, h, "POST", "/api/cypher/graphs", `{"allGraphs":true,"graphs":["a"],"query":"RETURN 1"}`, 400)
	request(t, h, "POST", "/api/cypher/graphs", `{"graphs":["missing"],"query":"RETURN 1"}`, 404)
}

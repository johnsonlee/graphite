package server

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestOpenAPIAliasesAndVersion(t *testing.T) {
	h := (&Server{Version: " 1.2.3 "}).Handler()
	first := request(t, h, "GET", "/openapi.json", "", 200)
	alias := request(t, h, "GET", "/swagger.json", "", 200)
	if first.Body.String() != alias.Body.String() || first.Header().Get("Content-Type") != "application/json" {
		t.Fatal("schema aliases or content type differ")
	}
	document := responseJSON(t, first)
	if document["openapi"] != "3.0.3" || document["info"].(map[string]any)["version"] != "1.2.3" {
		t.Fatalf("wrong schema info: %v", document["info"])
	}
	paths := document["paths"].(map[string]any)
	if len(paths) != 24 {
		t.Fatalf("path count = %d", len(paths))
	}
	if _, present := paths["/api/node/{id}"]; present {
		t.Fatal("ambiguous unscoped node path is advertised")
	}
	node := paths["/api/graphs/{graphId}/node/{id}"].(map[string]any)["get"].(map[string]any)
	parameters := node["parameters"].([]any)
	if parameters[0].(map[string]any)["name"] != "graphId" || parameters[1].(map[string]any)["name"] != "id" {
		t.Fatalf("graph-qualified node parameters: %v", parameters)
	}
	post := paths["/api/cypher/graphs"].(map[string]any)["post"].(map[string]any)
	responseCodes := post["responses"].(map[string]any)
	for _, code := range []string{"200", "400", "404", "429", "503", "504"} {
		if responseCodes[code] == nil {
			t.Fatalf("missing Cypher response %s", code)
		}
	}
	unknown := responseJSON(t, request(t, (&Server{}).Handler(), "GET", "/openapi.json", "", 200))
	var golden map[string]any
	if err := json.Unmarshal(openAPIDocument, &golden); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(unknown, golden) {
		t.Fatal("unversioned document differs from main snapshot")
	}
}

func TestOpenAPISnapshotSourcesHaveNotDrifted(t *testing.T) {
	var manifest struct {
		SourceSHA256 map[string]string `json:"sourceSha256"`
	}
	data, err := os.ReadFile("spec/manifest.json")
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(data, &manifest); err != nil {
		t.Fatal(err)
	}
	for name, want := range manifest.SourceSHA256 {
		path := filepath.Join("../../..", "graphite-explore/src/main/kotlin/io/johnsonlee/graphite/cli", name)
		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		if got := fmt.Sprintf("%x", sha256.Sum256(data)); got != want {
			t.Errorf("%s changed: regenerate and compare OpenAPI snapshot (got %s, want %s)", name, got, want)
		}
	}
}

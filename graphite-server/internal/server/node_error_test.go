package server

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestNodeTagHTTPMainOracle(t *testing.T) {
	var cases []struct {
		Mode, Name string
		Tag        byte
		Request    struct {
			Path, Method string
			Body         map[string]any
		}
		Response struct {
			Status               int
			ContentType, RawBody string
		}
	}
	data, err := os.ReadFile("testdata/node-tag/main/observations.json")
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(data, &cases); err != nil {
		t.Fatal(err)
	}
	if len(cases) != 104 {
		t.Fatalf("incomplete oracle: %d", len(cases))
	}
	normalize := func(raw string) any {
		var value any
		if json.Unmarshal([]byte(raw), &value) != nil {
			return raw
		}
		if obj, ok := value.(map[string]any); ok {
			if graph, ok := obj["graph"].(map[string]any); ok {
				graph["path"] = "<fixture>"
				graph["loadedAt"] = "<dynamic>"
			}
		}
		return value
	}
	for _, mode := range []string{"MAPPED", "EAGER"} {
		t.Run(mode, func(t *testing.T) {
			fixture := t.TempDir()
			entries, err := os.ReadDir("../query/testdata/traversal")
			if err != nil {
				t.Fatal(err)
			}
			for _, entry := range entries {
				data, err := os.ReadFile(filepath.Join("../query/testdata/traversal", entry.Name()))
				if err != nil {
					t.Fatal(err)
				}
				if err = os.WriteFile(filepath.Join(fixture, entry.Name()), data, 0600); err != nil {
					t.Fatal(err)
				}
			}
			index, err := os.ReadFile("testdata/node-tag/prepared.nodeindex")
			if err != nil {
				t.Fatal(err)
			}
			if err = os.WriteFile(filepath.Join(fixture, "graph.nodeindex"), index, 0600); err != nil {
				t.Fatal(err)
			}
			registry, err := NewRegistry(t.TempDir(), mode, OpenNativeGraph)
			if err != nil {
				t.Fatal(err)
			}
			defer registry.Close()
			if _, err = registry.Load("a", fixture, mode); err != nil {
				t.Fatal(err)
			}
			guard, err := NewGuard(4, time.Second)
			if err != nil {
				t.Fatal(err)
			}
			defer guard.Close()
			handler := (&Server{Registry: registry, Guard: guard}).Handler()
			for _, spec := range cases {
				if spec.Mode != mode {
					continue
				}
				t.Run(fmt.Sprintf("%d/%s", spec.Tag, spec.Name), func(t *testing.T) {
					file, err := os.OpenFile(filepath.Join(fixture, "graph.nodedata"), os.O_WRONLY, 0)
					if err != nil {
						t.Fatal(err)
					}
					_, err = file.WriteAt([]byte{spec.Tag}, 75)
					closeErr := file.Close()
					if err != nil {
						t.Fatal(err)
					}
					if closeErr != nil {
						t.Fatal(closeErr)
					}
					body := ""
					if spec.Request.Body != nil {
						if _, ok := spec.Request.Body["path"]; ok {
							spec.Request.Body["path"] = fixture
						}
						encoded, err := json.Marshal(spec.Request.Body)
						if err != nil {
							t.Fatal(err)
						}
						body = string(encoded)
					}
					recorder := httptest.NewRecorder()
					req := httptest.NewRequest(spec.Request.Method, spec.Request.Path, strings.NewReader(body))
					req.Header.Set("Content-Type", "application/json")
					handler.ServeHTTP(recorder, req)
					if recorder.Code != spec.Response.Status || recorder.Header().Get("Content-Type") != spec.Response.ContentType || !reflect.DeepEqual(normalize(recorder.Body.String()), normalize(spec.Response.RawBody)) {
						t.Fatalf("main %d %s %s\nnative %d %s %s", spec.Response.Status, spec.Response.ContentType, spec.Response.RawBody, recorder.Code, recorder.Header().Get("Content-Type"), recorder.Body.String())
					}
				})
			}
		})
	}
}

func TestNodeTagBoundaryDoesNotReclassifyOtherErrors(t *testing.T) {
	for _, spec := range []struct {
		status int
		err    error
		want   string
	}{
		{400, fmt.Errorf("index: %w", &store.UnknownNodeTagError{Tag: 255}), `{"error":"Unknown node tag: -1"}`},
		{500, errors.New("truncated data"), `{"error":"truncated data"}`},
		{400, errors.New("unknown node tag 255"), `{"error":"unknown node tag 255"}`},
		{409, fmt.Errorf("node: %w", &store.UnknownNodeTagError{Tag: 255}), `{"error":"node: unknown node tag 255"}`},
	} {
		w := httptest.NewRecorder()
		writeError(w, spec.status, spec.err)
		if w.Code != spec.status || strings.TrimSpace(w.Body.String()) != spec.want {
			t.Fatalf("%d %v: %d %s", spec.status, spec.err, w.Code, w.Body.String())
		}
	}
}

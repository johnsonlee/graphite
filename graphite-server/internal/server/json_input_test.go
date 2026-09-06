package server

import (
	"encoding/json"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"
)

// These are full responses captured from an independent, unmodified main JVM.
// The persisted tiny fixture is for correctness only, never performance.
func TestHTTPJSONBoundariesAgainstMain(t *testing.T) {
	r := nativeRegistryForTest(t, "a")
	g, err := NewGuard(4, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer g.Close()
	h := (&Server{Registry: r, Guard: g}).Handler()
	data, err := os.ReadFile("testdata/http-boundaries.json")
	if err != nil {
		t.Fatal(err)
	}
	data = []byte(strings.ReplaceAll(string(data), "{dataDir}", r.DataDir))
	var golden struct {
		Cases []struct {
			Name, Path, Method string
			Body               json.RawMessage
			Expected           struct {
				Status      int
				ContentType string
				Body        json.RawMessage
			}
		}
	}
	if err = json.Unmarshal(data, &golden); err != nil {
		t.Fatal(err)
	}
	if len(golden.Cases) != 213 {
		t.Fatalf("incomplete oracle: %d", len(golden.Cases))
	}
	for _, tc := range golden.Cases {
		t.Run(tc.Name, func(t *testing.T) {
			response := request(t, h, tc.Method, tc.Path, string(tc.Body), tc.Expected.Status)
			if got := response.Header().Get("Content-Type"); got != tc.Expected.ContentType {
				t.Fatalf("content type %q != %q", got, tc.Expected.ContentType)
			}
			var want, got any
			if err := json.Unmarshal(tc.Expected.Body, &want); err != nil {
				t.Fatal(err)
			}
			if strings.Contains(tc.Expected.ContentType, "json") {
				if err := json.Unmarshal(response.Body.Bytes(), &got); err != nil {
					t.Fatal(err)
				}
			} else {
				got = response.Body.String()
			}
			if !reflect.DeepEqual(got, want) {
				t.Fatalf("response mismatch\ngot:  %#v\nwant: %#v", got, want)
			}
		})
	}
}

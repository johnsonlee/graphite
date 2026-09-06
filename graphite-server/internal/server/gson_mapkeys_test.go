package server

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"reflect"
	"sort"
	"testing"
	"time"
)

type jsonTestMember struct {
	Key   string
	Value any
}
type jsonTestObject []jsonTestMember

// Preserve duplicate member multiplicity and relative order. Distinct object
// keys are sorted because their order has no bearing on ordinary JSON lookup.
func readJSONMembers(d *json.Decoder) (any, error) {
	token, err := d.Token()
	if err != nil {
		return nil, err
	}
	delim, ok := token.(json.Delim)
	if !ok {
		return token, nil
	}
	if delim == '[' {
		a := []any{}
		for d.More() {
			v, e := readJSONMembers(d)
			if e != nil {
				return nil, e
			}
			a = append(a, v)
		}
		_, err = d.Token()
		return a, err
	}
	if delim == '{' {
		o := jsonTestObject{}
		for d.More() {
			k, e := d.Token()
			if e != nil {
				return nil, e
			}
			v, e := readJSONMembers(d)
			if e != nil {
				return nil, e
			}
			o = append(o, jsonTestMember{k.(string), v})
		}
		_, err = d.Token()
		sort.SliceStable(o, func(i, j int) bool { return o[i].Key < o[j].Key })
		return o, err
	}
	return nil, fmt.Errorf("unexpected delimiter %v", delim)
}
func TestHTTPSurrogateEscapedAgainstMain(t *testing.T) {
	testSurrogateHTTPGolden(t, "testdata/http-surrogate-escaped-main.json", 8)
}
func TestHTTPSurrogateMapKeysAgainstMain(t *testing.T) {
	testSurrogateHTTPGolden(t, "testdata/http-surrogate-mapkeys-main.json", 40)
}
func TestHTTPSurrogateUnionAgainstMain(t *testing.T) {
	testSurrogateHTTPGolden(t, "testdata/http-surrogate-union-main.json", 24)
}
func testSurrogateHTTPGolden(t *testing.T, fixture string, count int) {
	r := nativeRegistryForTest(t, "a")
	g, err := NewGuard(4, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer g.Close()
	h := (&Server{Registry: r, Guard: g}).Handler()
	data, err := os.ReadFile(fixture)
	if err != nil {
		t.Fatal(err)
	}
	var golden struct {
		Cases []struct {
			Name, Method, Path, BodyBase64 string
			Expected                       struct {
				Status                  int
				ContentType, BodyBase64 string
			}
		}
	}
	if err = json.Unmarshal(data, &golden); err != nil {
		t.Fatal(err)
	}
	if len(golden.Cases) != count {
		t.Fatalf("incomplete oracle %d", len(golden.Cases))
	}
	for _, c := range golden.Cases {
		t.Run(c.Name, func(t *testing.T) {
			raw, e := base64.StdEncoding.DecodeString(c.BodyBase64)
			if e != nil {
				t.Fatal(e)
			}
			wantBytes, e := base64.StdEncoding.DecodeString(c.Expected.BodyBase64)
			if e != nil {
				t.Fatal(e)
			}
			response := request(t, h, c.Method, c.Path, string(raw), c.Expected.Status)
			t.Logf("nativeHTTP status=%d contentType=%q bodyBase64=%s", response.Code, response.Header().Get("Content-Type"), base64.StdEncoding.EncodeToString(response.Body.Bytes()))
			if response.Header().Get("Content-Type") != c.Expected.ContentType {
				t.Fatal("content type mismatch")
			}
			gd := json.NewDecoder(bytes.NewReader(response.Body.Bytes()))
			gd.UseNumber()
			wd := json.NewDecoder(bytes.NewReader(wantBytes))
			wd.UseNumber()
			got, e := readJSONMembers(gd)
			if e != nil {
				t.Fatal(e)
			}
			want, e := readJSONMembers(wd)
			if e != nil {
				t.Fatal(e)
			}
			if !reflect.DeepEqual(got, want) {
				t.Fatalf("duplicate-preserving response mismatch\ngot %s\nwant %s", response.Body.Bytes(), wantBytes)
			}
		})
	}
}

func TestUnorderedGoMapPreservesAllCollidingMembers(t *testing.T) {
	input := map[string]any{"\xed\xa0\x80": 1, "\xed\xa0\x81": 2, "?": 3}
	for i := 0; i < 30; i++ {
		got, err := json.Marshal(omitNullFields(input))
		if err != nil {
			t.Fatal(err)
		}
		if string(got) != `{"?":3,"?":1,"?":2}` {
			t.Fatalf("unstable or discarded member: %s", got)
		}
	}
	if len(input) != 3 {
		t.Fatal("mutated original keys")
	}
}

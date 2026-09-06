package server

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"os"
	"reflect"
	"testing"
	"time"
)

func TestHTTPMalformedJSONAgainstMain(t *testing.T) {
	testGsonHTTPGolden(t, "testdata/http-malformed-json-main.json", 288)
}
func TestHTTPNonfiniteAgainstMain(t *testing.T) {
	testGsonHTTPGolden(t, "testdata/http-nonfinite-main.json", 30)
}
func testGsonHTTPGolden(t *testing.T, fixture string, count int) {
	t.Helper()
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
			body, err := base64.StdEncoding.DecodeString(c.BodyBase64)
			if err != nil {
				t.Fatal(err)
			}
			response := request(t, h, c.Method, c.Path, string(body), c.Expected.Status)
			if got := response.Header().Get("Content-Type"); got != c.Expected.ContentType {
				t.Fatalf("content type %q != %q", got, c.Expected.ContentType)
			}
			expected, err := base64.StdEncoding.DecodeString(c.Expected.BodyBase64)
			if err != nil {
				t.Fatal(err)
			}
			var got, want any
			gd := json.NewDecoder(bytes.NewReader(response.Body.Bytes()))
			gd.UseNumber()
			wd := json.NewDecoder(bytes.NewReader(expected))
			wd.UseNumber()
			if err = gd.Decode(&got); err != nil {
				t.Fatal(err)
			}
			if err = wd.Decode(&want); err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(got, want) {
				t.Fatalf("complete response mismatch\ngot: %#v\nwant:%#v", got, want)
			}
		})
	}
}

func TestGsonWireSurrogateBoundary(t *testing.T) {
	for _, c := range []struct{ input, want string }{
		{"\xed\xa0\x80", "?"}, {"\xed\xb0\x80", "?"}, {"\xed\xa0\x80\xed\xa0\x80", "??"},
		{"\xed\xa0\xbd\xed\xb8\x80", "😀"}, {"�", "�"}, {"😀", "😀"},
	} {
		if got := gsonWireString(c.input); got != c.want {
			t.Fatalf("input%q got%q want%q", c.input, got, c.want)
		}
	}
	got := omitNullFields(map[string]any{"\xed\xa0\x80": []any{"�", "\xed\xb0\x80"}})
	want := map[string]any{"?": []any{"�", "?"}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("nested/map-key wire conversion %#v", got)
	}
}

func gsonTestUnits(text string) []any {
	units := []any{}
	for i := 0; i < len(text); {
		r, n := gsonWireRune(text[i:])
		i += n
		if r > 0xFFFF {
			r -= 0x10000
			units = append(units, float64(0xD800+(r>>10)), float64(0xDC00+(r&1023)))
		} else {
			units = append(units, float64(r))
		}
	}
	return units
}
func gsonTestDescribe(v *gsonValue) map[string]any {
	d := map[string]any{}
	switch v.kind {
	case 'n':
		d["kind"] = "null"
	case 's':
		d["kind"] = "string"
		d["units"] = gsonTestUnits(v.text)
	case 'b':
		d["kind"] = "boolean"
		d["text"] = v.text
	case 'd':
		d["kind"] = "number"
		d["text"] = v.text
	case 'a':
		d["kind"] = "array"
		items := []any{}
		for _, c := range v.items {
			items = append(items, gsonTestDescribe(c))
		}
		d["items"] = items
	case 'o':
		d["kind"] = "object"
		members := []any{}
		for _, c := range v.members {
			members = append(members, map[string]any{"nameUnits": gsonTestUnits(c.name), "value": gsonTestDescribe(c.value)})
		}
		d["members"] = members
	}
	return d
}
func TestGsonParserAgainstJavaOracle(t *testing.T) {
	data, err := os.ReadFile("testdata/gson-json-oracle.json")
	if err != nil {
		t.Fatal(err)
	}
	var oracle struct {
		Cases []struct {
			Name, BodyBase64, Error string
			Expected                map[string]any
		}
	}
	if err = json.Unmarshal(data, &oracle); err != nil {
		t.Fatal(err)
	}
	if len(oracle.Cases) != 134 {
		t.Fatalf("incomplete Gson oracle %d", len(oracle.Cases))
	}
	for _, c := range oracle.Cases {
		t.Run(c.Name, func(t *testing.T) {
			body, err := base64.StdEncoding.DecodeString(c.BodyBase64)
			if err != nil {
				t.Fatal(err)
			}
			value, err := parseGsonJSON(body)
			if c.Error != "" {
				if err == nil || err.Error() != c.Error {
					t.Fatalf("input%q error want%q got%v", body, c.Error, err)
				}
				return
			}
			if err != nil {
				t.Fatalf("input%q unexpected%v", body, err)
			}
			got := gsonTestDescribe(value)
			if !reflect.DeepEqual(got, c.Expected) {
				t.Fatalf("input%q tree got%#v want%#v", body, got, c.Expected)
			}
		})
	}
}

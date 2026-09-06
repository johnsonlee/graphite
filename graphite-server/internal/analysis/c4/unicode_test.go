package c4

import (
	"encoding/json"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"unicode/utf16"
	"unicode/utf8"
)

func unicodeFromUnits(units []uint16) string {
	var b strings.Builder
	for i := 0; i < len(units); i++ {
		u := units[i]
		if u >= 0xd800 && u <= 0xdbff && i+1 < len(units) && units[i+1] >= 0xdc00 && units[i+1] <= 0xdfff {
			b.WriteRune(utf16.DecodeRune(rune(u), rune(units[i+1])))
			i++
		} else if u >= 0xd800 && u <= 0xdfff {
			b.WriteByte(0xe0 | byte(u>>12))
			b.WriteByte(0x80 | byte(u>>6&63))
			b.WriteByte(0x80 | byte(u&63))
		} else {
			b.WriteRune(rune(u))
		}
	}
	return b.String()
}
func unicodeUnits(s string) []uint16 {
	units := []uint16{}
	for i := 0; i < len(s); {
		if i+2 < len(s) && s[i] == 0xed && s[i+1] >= 0xa0 && s[i+1] <= 0xbf && s[i+2]&0xc0 == 0x80 {
			units = append(units, uint16(s[i]&15)<<12|uint16(s[i+1]&63)<<6|uint16(s[i+2]&63))
			i += 3
			continue
		}
		r, n := utf8.DecodeRuneInString(s[i:])
		i += n
		if r > 0xffff {
			a, b := utf16.EncodeRune(r)
			units = append(units, uint16(a), uint16(b))
		} else {
			units = append(units, uint16(r))
		}
	}
	return units
}
func TestMainUnicodeHelpers(t *testing.T) {
	data, err := os.ReadFile("testdata/unicode-helpers.json")
	if err != nil {
		t.Fatal(err)
	}
	var rows []struct {
		Function      string
		Input, Output []uint16
	}
	if err := json.Unmarshal(data, &rows); err != nil {
		t.Fatal(err)
	}
	functions := map[string]func(string) string{"slug": Slugify, "artifact": func(s string) string { return humanizeArtifact(s, true) }, "subjectArtifact": func(s string) string { return humanizeArtifact(s, false) }, "identifier": HumanizeIdentifier, "subjectBoundary": func(s string) string { return InferSubjectName(s, nil, nil) }, "artifactBase": ArtifactBaseName, "namespace": NamespaceGroup, "dominant": DominantNamespace, "diagramId": diagramID}
	for _, row := range rows {
		s := unicodeFromUnits(row.Input)
		var got []uint16
		if row.Function == "artifactKey" {
			v, ok := ArtifactKey(s)
			if ok {
				got = unicodeUnits(v)
			}
		} else {
			f, ok := functions[row.Function]
			if !ok {
				t.Fatalf("unknown function %s", row.Function)
			}
			got = unicodeUnits(f(s))
		}
		if !reflect.DeepEqual(got, row.Output) {
			t.Errorf("%s input=%04x: got %04x; main %04x", row.Function, row.Input, got, row.Output)
		}
	}
}

// String leaves are encoded as units before comparison; encoding/json would
// otherwise normalize the very isolated surrogates under test.
func unicodeModelValue(v any) any {
	switch x := v.(type) {
	case string:
		out := []any{}
		for _, u := range unicodeUnits(x) {
			out = append(out, float64(u))
		}
		return map[string]any{"$utf16": out}
	case map[string]any:
		out := map[string]any{}
		for k, v := range x {
			out[k] = unicodeModelValue(v)
		}
		return out
	case []any:
		out := []any{}
		for _, v := range x {
			out = append(out, unicodeModelValue(v))
		}
		return out
	default:
		return v
	}
}
func TestMainUnicodeModel(t *testing.T) {
	data, err := os.ReadFile("testdata/unicode-model/expected.json")
	if err != nil {
		t.Fatal(err)
	}
	var expected map[string]map[string]any
	if err := json.Unmarshal(data, &expected); err != nil {
		t.Fatal(err)
	}
	for _, mode := range []string{"MAPPED", "EAGER"} {
		t.Run(mode, func(t *testing.T) {
			g, err := store.OpenMode("testdata/unicode-model/store", mode)
			if err != nil {
				t.Fatal(err)
			}
			defer g.Close()
			for _, level := range []string{"all", "context", "container", "component"} {
				t.Run(level, func(t *testing.T) {
					m, err := BuildModel(g, level)
					if err != nil {
						t.Fatal(err)
					}
					if got := unicodeModelValue(m); !reflect.DeepEqual(got, expected[level]["model"]) {
						b, _ := json.MarshalIndent(got, "", " ")
						path := filepath.Join(t.TempDir(), "model-got.json")
						if err := os.WriteFile(path, b, 0600); err != nil {
							t.Fatal(err)
						}
						t.Error("complete model differs from main; diagnostic " + path)
					}
					for format, render := range map[string]func(map[string]any) (string, error){"mermaid": RenderMermaid, "plantuml": RenderPlantUML, "dsl": RenderStructurizrDSL} {
						s, err := render(m)
						if err != nil {
							t.Fatal(err)
						}
						if got := unicodeModelValue(s); !reflect.DeepEqual(got, expected[level][format]) {
							t.Errorf("%s differs from main", format)
						}
					}
				})
			}
		})
	}
}

func TestWorkspacePreservesSurrogatesAndKeys(t *testing.T) {
	high, low := unicodeFromUnits([]uint16{0xd800}), unicodeFromUnits([]uint16{0xdc00})
	original := Workspace{Name: "Unicode", Properties: map[string]string{high: "high", low: "low", "\ufffd": "replacement"}, Model: WorkspaceModel{SoftwareSystems: []*WorkspaceElement{{ID: high, Name: low, Properties: map[string]string{"evidence": prettyJSON(map[string]any{"name": high})}}}}}
	raw := EncodeWorkspace(original)
	restored, err := DecodeWorkspace(raw)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(original, restored) {
		t.Fatalf("workspace strings changed: original %#v restored %#v", original, restored)
	}
	if compactJSON([]string{high}) == compactJSON([]string{low}) || compactJSON([]string{high}) == compactJSON([]string{"\ufffd"}) {
		t.Fatal("identity serialization collapsed distinct surrogate/replacement strings")
	}
	if !lexical(high, low) || !lexical(low, "\ufffd") {
		t.Fatal("lexical comparison must order UTF16 units, including isolated surrogates")
	}
	if _, err := DecodeWorkspace(map[string]any{"name": 17}); err == nil {
		t.Fatal("string restoration must retain JSON type validation")
	}
}

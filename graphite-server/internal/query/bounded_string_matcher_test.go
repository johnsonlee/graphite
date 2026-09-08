package query

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestBoundedMatcherPublicMain(t *testing.T) {
	var oracle []map[string]any
	readDistinctJSON(t, "testdata/bounded-string-matcher/public-main.json", &oracle)
	if len(oracle) != 16 {
		t.Fatal("incomplete public main oracle")
	}
	var sources []Graph
	for _, want := range oracle {
		if want["repetition"] == float64(0) {
			sources = nil
			for i := 0; i < int(want["sourceCount"].(float64)); i++ {
				dir := mainSourceFixture(t, "clean")
				id := fmt.Sprintf("g%02d", i)
				if want["mutatedSource"] == id {
					file := filepath.Join(dir, want["mutationFile"].(string))
					data, err := os.ReadFile(file)
					if err != nil {
						t.Fatal(err)
					}
					binary.BigEndian.PutUint32(data[int(want["mutationByteOffset"].(float64)):], uint32(int32(want["mutationNewSID"].(float64))))
					if err = os.WriteFile(file, data, 0600); err != nil {
						t.Fatal(err)
					}
				}
				g, err := store.OpenMode(dir, "MAPPED")
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { g.Close() })
				sources = append(sources, Graph{ID: id, Store: g})
			}
		}
		result, err := ExecuteCrossWithOptions(context.Background(), sources, want["query"].(string), nil, -1, ExecutionOptions{WorkTrackingEnabled: true})
		got := distinctOracleResult(map[string]any{}, result, err)
		encoded, _ := json.Marshal(got)
		var actual map[string]any
		if err := json.Unmarshal(encoded, &actual); err != nil {
			t.Fatal(err)
		}
		for _, field := range []string{"columns", "rows", "error", "message"} {
			expected := want[field]
			if field == "error" && expected != nil {
				expected = strings.TrimPrefix(expected.(string), "java.lang.")
			}
			if !reflect.DeepEqual(actual[field], expected) {
				t.Fatalf("count=%v mutation=%v repeat=%v %s=%v want=%v", want["sourceCount"], want["fixtureMutation"], want["repetition"], field, actual[field], expected)
			}
		}
	}
}

func boundedMatcherSnapshot(m *boundedStringMatcher) map[string]any {
	populated := []any{}
	if m.dense != nil {
		for sid, state := range m.dense {
			if state != 0 {
				populated = append(populated, map[string]any{"sid": sid, "state": state})
			}
		}
	} else {
		for slot, key := range m.keys {
			if key != 0 {
				populated = append(populated, map[string]any{"slot": slot, "key": key, "state": m.values[slot]})
			}
		}
	}
	return map[string]any{"dense": m.dense != nil, "populated": populated}
}

func TestBoundedStringMatcherMain(t *testing.T) {
	var oracle []map[string]any
	readDistinctJSON(t, "testdata/bounded-string-matcher/main.json", &oracle)
	unicodeInputs := map[string]map[string]any{}
	for _, record := range oracle {
		if record["scenario"] == "unicode-input" {
			unicodeInputs[fmt.Sprintf("unicode-%s-capacity%.0f", record["name"], record["capacity"])] = record
		}
	}
	fromUnits := func(raw any) string {
		units := []uint16{}
		for _, v := range raw.([]any) {
			units = append(units, uint16(v.(float64)))
		}
		return javaFromUTF16(units)
	}
	e := evaluator{ctx: context.Background()}
	var matcher *boundedStringMatcher
	var table *store.Store
	reads, compared := 0, 0
	for _, want := range oracle {
		name, _ := want["scenario"].(string)
		if _, ok := want["sid"]; !ok {
			continue
		}
		state := want["state"].(map[string]any)
		count, capacity := int(state["stringCount"].(float64)), int(state["capacity"].(float64))
		if want["readsBefore"] == float64(0) {
			values := make([]string, count)
			for i := range values {
				prefix := "miss"
				if i%2 == 0 {
					prefix = "hit"
				}
				values[i] = fmt.Sprintf("%s-%d", prefix, i)
			}
			atom := distinctStringAtom{property: "caller_name", op: "CONTAINS", term: "hit"}
			if input, ok := unicodeInputs[name]; ok {
				values[0] = fromUnits(input["actualUtf16"])
				atom.term, atom.lower = fromUnits(input["expectedUtf16"]), input["lower"].(bool)
			} else if strings.HasPrefix(name, "mode-") {
				values[0] = "İX"
				atom.lower = true
				atom.op = strings.ReplaceAll(strings.TrimPrefix(name, "mode-"), "_", " ")
				atom.term = "i̇"
				if atom.op == "EQUALS" {
					atom.op = "="
					atom.term = "i̇x"
				}
				if atom.op == "ENDS WITH" {
					atom.term = "x"
				}
			}
			table = &store.Store{Strings: values}
			matcher = newBoundedStringMatcher(atom, count, capacity)
			reads = 0
		}
		if reads != int(want["readsBefore"].(float64)) {
			t.Fatalf("%s initial reads=%d", name, reads)
		}
		got := map[string]any{}
		func() {
			defer func() {
				if value := recover(); value != nil {
					err, ok := value.(*Error)
					if !ok {
						t.Fatalf("%s unexpected error %v", name, value)
					}
					got["error"], got["message"] = "java.lang."+err.Class, err.Message
				}
			}()
			got["matched"] = matcher.matches(e, int32(want["sid"].(float64)), func(sid int32) (string, error) {
				reads++
				if strings.Contains(name, "cached") {
					return "", fmt.Errorf("oracle backing read forbidden")
				}
				return table.ProjectionString(e.ctx, sid)
			})
		}()
		for _, field := range []string{"matched", "error", "message"} {
			if got[field] != want[field] {
				t.Fatalf("%s %s=%v want=%v", name, field, got[field], want[field])
			}
		}
		if reads != int(want["readsAfter"].(float64)) {
			t.Fatalf("%s reads=%d want=%v", name, reads, want["readsAfter"])
		}
		raw, _ := json.Marshal(boundedMatcherSnapshot(matcher))
		var normalized map[string]any
		_ = json.Unmarshal(raw, &normalized)
		if normalized["dense"] != state["dense"] || !reflect.DeepEqual(normalized["populated"], state["populated"]) {
			t.Fatalf("%s cache=%v want=%v", name, normalized, state)
		}
		compared++
	}
	if compared != 54 {
		t.Fatalf("incomplete JVM matcher observations: %d", compared)
	}
}

func TestBoundedMatcherMainPolicyAndCapacity(t *testing.T) {
	var oracle []map[string]any
	readDistinctJSON(t, "testdata/bounded-string-matcher/main.json", &oracle)
	policies, capacities := 0, 0
	for _, want := range oracle {
		switch want["scenario"] {
		case "storage-consumer-policy":
			p := &mainStringSourceSpec{sourceCount: int(want["sourceCount"].(float64)), forcePersisted: want["forceSerial"].(bool)}
			if p.boundedSerialRawMatcher(1) != want["serial"].(bool) {
				t.Fatalf("main consumer policy mismatch: %v", want)
			}
			if p.boundedSerialRawMatcher(int(^uint32(0)>>1)) || p.boundedSerialRawMatcher(0) {
				t.Fatal("unbounded/empty limit entered bounded scan")
			}
			policies++
		case "capacity-normalization":
			state := want["state"].(map[string]any)
			m := newBoundedStringMatcher(distinctStringAtom{}, int(state["stringCount"].(float64)), int(want["requested"].(float64)))
			if (m.dense != nil) != state["dense"].(bool) {
				t.Fatalf("main capacity normalization: %v", want)
			}
			// Capacity normalization precedes and is independent of table size.
			large := newBoundedStringMatcher(distinctStringAtom{}, 70000, int(want["requested"].(float64)))
			if len(large.keys) != int(state["capacity"].(float64)) {
				t.Fatalf("normalized capacity=%d want=%v", len(large.keys), state["capacity"])
			}
			capacities++
		}
	}
	if policies != 20 || capacities != 9 {
		t.Fatalf("incomplete main matrix: policies=%d capacities=%d", policies, capacities)
	}
}

func TestBoundedMatcherSharedIdentityAndRequestLifetime(t *testing.T) {
	a := distinctStringAtom{property: "caller_name", op: "CONTAINS", term: "hit"}
	b := a
	b.property = "callee_name"
	c := a
	c.lower = true
	d := a
	d.op = "="
	f := a
	f.term = "miss"
	atoms := []distinctStringAtom{a, b, c, d, f}
	matchers := sharedStringMatchers(atoms, 10, 4096)
	if matchers[0] != matchers[1] {
		t.Fatal("same string predicate across properties must share matcher")
	}
	for _, i := range []int{2, 3, 4} {
		if matchers[0] == matchers[i] {
			t.Fatal("distinct predicate identity aliased", i)
		}
	}
	reads := 0
	read := func(int32) (string, error) { reads++; return "hit", nil }
	e := evaluator{ctx: context.Background()}
	if !matchers[0].matches(e, 3, read) || !matchers[1].matches(e, 3, read) || reads != 1 {
		t.Fatal("shared match did not reuse decoded string", reads)
	}
	next := sharedStringMatchers(atoms, 10, 4096)
	if !next[0].matches(e, 3, read) || reads != 2 {
		t.Fatal("match state escaped query lifetime", reads)
	}
	a.term = "\U0001f600"
	b = a
	b.property = "callee_name"
	b.term = "\xed\xa0\xbd\xed\xb8\x80"
	paired := sharedStringMatchers([]distinctStringAtom{a, b}, 10, 4096)
	if paired[0] != paired[1] {
		t.Fatal("equivalent Java UTF16 predicate identity not shared")
	}
}

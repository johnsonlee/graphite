package query

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func unknownLabelJSON(t *testing.T, value any) any {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	var out any
	if err := json.Unmarshal(data, &out); err != nil {
		t.Fatal(err)
	}
	return out
}
func unknownLabelSHA(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

// Derive the damaged record from the original JVM node index. Never infer a
// node-data offset from the requested label, or modify the shared fixture.
func unknownLabelFixture(t *testing.T, spec map[string]any, sourceIndex int, sourceFiles map[string]map[string]any, mutations map[string]map[string]any, root string, additions map[string]map[string]any) string {
	t.Helper()
	source := "testdata/main-string-source/all-types"
	switch spec["fixture"] {
	case "all-types":
	case "node-encounter/mixed-sparse":
		source = "testdata/node-encounter/mixed-sparse"
	default:
		t.Fatal("unknown oracle fixture", spec["fixture"])
	}
	dir := t.TempDir()
	entries, err := os.ReadDir(source)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != len(sourceFiles) {
		t.Fatal("source fixture inventory changed")
	}
	for _, entry := range entries {
		expected, ok := sourceFiles[entry.Name()]
		if !ok || entry.IsDir() {
			t.Fatalf("unexpected fixture input %s", entry.Name())
		}
		data, err := os.ReadFile(filepath.Join(source, entry.Name()))
		if err != nil {
			t.Fatal(err)
		}
		if unknownLabelSHA(data) != expected["sha256"] || float64(len(data)) != expected["bytes"] {
			t.Fatalf("source fixture differs: %s", entry.Name())
		}
		if err := os.WriteFile(filepath.Join(dir, entry.Name()), data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	sourceSpec := spec["sources"].([]any)[sourceIndex].(map[string]any)
	if offsetSpec, ok := sourceSpec["nodeOffset"].(map[string]any); ok {
		unknownLabelOffsetFixture(t, dir, spec["name"].(string), sourceIndex, offsetSpec, root, additions, mutations)
	}
	if sourceSpec["mutationNode"] == nil {
		return dir
	}
	nodeID := int32(sourceSpec["mutationNode"].(float64))
	index, err := os.ReadFile(filepath.Join(dir, "graph.nodeindex"))
	if err != nil {
		t.Fatal(err)
	}
	if len(index) < 8 || binary.BigEndian.Uint32(index) != 0x47524903 || int64(len(index)) != 8+13*int64(binary.BigEndian.Uint32(index[4:])) {
		t.Fatal("unexpected JVM node-index format")
	}
	recordAt := -1
	var offset uint64
	var indexTag byte
	for at := 8; at < len(index); at += 13 {
		if int32(binary.BigEndian.Uint32(index[at:])) == nodeID {
			if recordAt >= 0 {
				t.Fatal("duplicate node ID in fixture index")
			}
			recordAt = at
			indexTag = index[at+4]
			offset = binary.BigEndian.Uint64(index[at+5:])
		}
	}
	if recordAt < 0 {
		t.Fatalf("node %d missing from fixture index", nodeID)
	}
	file := filepath.Join(dir, "graph.nodedata")
	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	if offset+5 > uint64(len(data)) || int32(binary.BigEndian.Uint32(data[offset:])) != nodeID || data[offset+4] != indexTag {
		t.Fatal("node index/data location or tag differs")
	}
	var mappedOffset any
	offsets, err := os.ReadFile(filepath.Join(dir, "graph.nodeoffsets"))
	if err == nil {
		at := int64(8) + 8*int64(nodeID)
		if at < 0 || at+8 > int64(len(offsets)) {
			t.Fatal("mapped offset outside fixture")
		}
		value := int64(binary.BigEndian.Uint64(offsets[at:])) - 1
		if value != int64(offset) {
			t.Fatal("mapped/index offset differs")
		}
		mappedOffset = value
	} else if !os.IsNotExist(err) {
		t.Fatal(err)
	}
	before := unknownLabelSHA(data)
	oldTag := data[offset+4]
	newTag := byte(255)
	if tag, ok := sourceSpec["mutationTag"].(float64); ok {
		if tag < 0 || tag > 255 || tag != float64(byte(tag)) {
			t.Fatal("invalid mutation tag", tag)
		}
		newTag = byte(tag)
	}
	data[offset+4] = newTag
	relative := fmt.Sprintf("fixtures/%s/store%d/graph.nodedata", spec["name"], sourceIndex)
	actual := map[string]any{"file": relative, "nodeID": nodeID, "indexRecordByteOffset": recordAt, "indexTag": indexTag, "nodeDataRecordOffset": offset, "tagByteOffset": offset + 4, "nodeOffsetsValue": mappedOffset, "oldTag": oldTag, "newTag": newTag, "originalSha256": before, "mutatedSha256": unknownLabelSHA(data)}
	if !reflect.DeepEqual(unknownLabelJSON(t, actual), mutations[relative]) {
		t.Fatalf("derived mutation differs: got=%v main=%v", actual, mutations[relative])
	}
	if err := os.WriteFile(file, data, 0600); err != nil {
		t.Fatal(err)
	}
	return dir
}

func unknownLabelOffsetFixture(t *testing.T, dir, name string, sourceIndex int, spec map[string]any, root string, additions, mutations map[string]map[string]any) {
	t.Helper()
	for _, filename := range []string{"graph.nodeoffsets", "graph.typeindex"} {
		relative := fmt.Sprintf("fixtures/%s/store%d/%s", name, sourceIndex, filename)
		reference := "mapped-index-reference/" + filename
		data, err := os.ReadFile(filepath.Join(root, reference))
		if err != nil {
			t.Fatal(err)
		}
		actual := map[string]any{"file": relative, "reference": reference, "bytes": len(data), "sha256": unknownLabelSHA(data)}
		if !reflect.DeepEqual(unknownLabelJSON(t, actual), additions[relative]) {
			t.Fatalf("reference addition differs: got=%v main=%v", actual, additions[relative])
		}
		if err := os.WriteFile(filepath.Join(dir, filename), data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	index, err := os.ReadFile(filepath.Join(dir, "graph.nodeindex"))
	if err != nil {
		t.Fatal(err)
	}
	if len(index) < 8 || binary.BigEndian.Uint32(index) != 0x47524903 || int64(len(index)) != 8+13*int64(binary.BigEndian.Uint32(index[4:])) {
		t.Fatal("unexpected JVM node-index format")
	}
	locations := map[int32]int64{}
	for at := 8; at < len(index); at += 13 {
		id := int32(binary.BigEndian.Uint32(index[at:]))
		if _, exists := locations[id]; exists {
			t.Fatal("duplicate legacy index node", id)
		}
		locations[id] = int64(binary.BigEndian.Uint64(index[at+5:]))
	}
	nodeID := int32(spec["nodeID"].(float64))
	oldOffset, exists := locations[nodeID]
	if !exists {
		t.Fatal("offset target absent from legacy index")
	}
	filename := filepath.Join(dir, "graph.nodeoffsets")
	data, err := os.ReadFile(filename)
	if err != nil {
		t.Fatal(err)
	}
	if len(data) < 8 || binary.BigEndian.Uint32(data) != 0x47524c03 || int64(len(data)) != 8+8*int64(binary.BigEndian.Uint32(data[4:])) {
		t.Fatal("unexpected JVM node-offset format")
	}
	// Check every generated sidecar entry against the original legacy index.
	for id, offset := range locations {
		at := 8 + 8*int64(id)
		if at < 8 || at+8 > int64(len(data)) || int64(binary.BigEndian.Uint64(data[at:]))-1 != offset {
			t.Fatal("reference offset differs from legacy index", id)
		}
	}
	newOffset := int64(-1)
	switch spec["mode"] {
	case "missing":
	case "negative2":
		newOffset = -2
	case "alias":
		var ok bool
		newOffset, ok = locations[int32(spec["aliasNode"].(float64))]
		if !ok {
			t.Fatal("alias target absent from legacy index")
		}
	default:
		t.Fatal("unknown offset mutation", spec)
	}
	before := unknownLabelSHA(data)
	at := int64(8) + 8*int64(nodeID)
	binary.BigEndian.PutUint64(data[at:], uint64(newOffset+1))
	relative := fmt.Sprintf("fixtures/%s/store%d/graph.nodeoffsets", name, sourceIndex)
	actual := map[string]any{"kind": "nodeOffset", "file": relative, "nodeID": nodeID, "slotByteOffset": at, "oldStoredValue": oldOffset + 1, "newStoredValue": newOffset + 1, "oldDecodedOffset": oldOffset, "newDecodedOffset": newOffset, "mode": spec["mode"], "aliasNode": spec["aliasNode"], "originalSha256": before, "mutatedSha256": unknownLabelSHA(data)}
	if !reflect.DeepEqual(unknownLabelJSON(t, actual), mutations[relative]) {
		t.Fatalf("derived offset mutation differs: got=%v main=%v", actual, mutations[relative])
	}
	if err := os.WriteFile(filename, data, 0600); err != nil {
		t.Fatal(err)
	}
}

func unknownLabelStates(t *testing.T, sources []Graph) any {
	t.Helper()
	out := []map[string]any{}
	for _, source := range sources {
		state, err := source.Store.StringPropertyIndexes(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		out = append(out, map[string]any{"id": source.ID, "retained": state.Retained, "mappedView": state.MappedView})
	}
	return unknownLabelJSON(t, out)
}

func unknownLabelObserve(t *testing.T, spec map[string]any, matchedContext bool, sourceFiles map[string]map[string]any, mutations map[string]map[string]any, root string, additions map[string]map[string]any) map[string]any {
	t.Helper()
	sources := []Graph{}
	defer func() {
		for _, source := range sources {
			source.Store.Close()
		}
	}()
	for i, input := range spec["sources"].([]any) {
		dir := unknownLabelFixture(t, spec, i, sourceFiles, mutations, root, additions)
		graph, err := store.OpenMode(dir, "MAPPED")
		if err != nil {
			t.Fatal(err)
		}
		sources = append(sources, Graph{ID: input.(map[string]any)["id"].(string), Store: graph})
	}
	before := unknownLabelStates(t, sources)
	var result Result
	var err error
	api := "Execute"
	if spec["cross"].(bool) {
		api = "ExecuteCrossWithOptions"
		result, err = ExecuteCrossWithOptions(context.Background(), sources, spec["query"].(string), nil, -1, ExecutionOptions{SourceScopeApplied: spec["scoped"].(bool), WorkTrackingEnabled: true})
	} else {
		if len(sources) != 1 {
			t.Fatal("single executor requires one fixture source")
		}
		if matchedContext {
			api = "executeSources(single, WorkTrackingEnabled=true)"
			result, err = executeSources(context.Background(), sources[0].Store, nil, false, spec["query"].(string), nil, -1, ExecutionOptions{WorkTrackingEnabled: true})
		} else {
			result, err = Execute(context.Background(), sources[0].Store, spec["query"].(string), nil, -1)
		}
	}
	got := distinctOracleResult(map[string]any{"name": spec["name"], "spec": spec, "api": api}, result, err)
	got["before"] = before
	got["after"] = unknownLabelStates(t, sources)
	got["outcome"] = "SUCCESS"
	got["nativeErrorType"] = fmt.Sprintf("%T", err)
	if err != nil {
		got["outcome"] = "FAILED"
		got["nativeErrorMessage"] = err.Error()
	} else {
		types := []map[string]any{}
		rawTypes := []map[string]any{}
		for _, row := range result.Rows {
			values := map[string]any{}
			raw := map[string]any{}
			for _, column := range result.Columns {
				values[column] = sourceConstructorGoType(t, row[column])
				raw[column] = fmt.Sprintf("%T", row[column])
			}
			types = append(types, values)
			rawTypes = append(rawTypes, raw)
		}
		got["publicTypes"] = unknownLabelJSON(t, types)
		got["nativeValueTypes"] = rawTypes
	}
	return got
}

func unknownLabelAssert(t *testing.T, got, want map[string]any) {
	t.Helper()
	for _, field := range []string{"outcome", "columns", "rows", "error", "message", "before", "after"} {
		if !reflect.DeepEqual(got[field], want[field]) {
			t.Errorf("%s %s got=%#v want=%#v", got["api"], field, got[field], want[field])
		}
	}
	if got["outcome"] == "SUCCESS" && want["outcome"] == "SUCCESS" {
		types := []map[string]any{}
		for _, row := range want["types"].([]any) {
			values := map[string]any{}
			for column, value := range row.(map[string]any) {
				values[column] = sourceConstructorMainType(t, value)
			}
			types = append(types, values)
		}
		if !reflect.DeepEqual(got["publicTypes"], unknownLabelJSON(t, types)) {
			t.Errorf("%s public types got=%v want=%v main raw=%v", got["api"], got["publicTypes"], types, want["types"])
		}
	}
}

func TestUnknownLabelActualMain(t *testing.T) {
	root := "../../../docs/go-server-baseline/native-unknown-label-routing"
	if input := os.Getenv("UNKNOWN_LABEL_INPUT_DIR"); input != "" {
		root = input
	}
	var specs, oracle, mutationRecords, additionRecords []map[string]any
	var fixture map[string]any
	readDistinctJSON(t, filepath.Join(root, "cases.json"), &specs)
	readDistinctJSON(t, filepath.Join(root, "main.json"), &oracle)
	readDistinctJSON(t, filepath.Join(root, "mutations.json"), &mutationRecords)
	readDistinctJSON(t, filepath.Join(root, "additions.json"), &additionRecords)
	readDistinctJSON(t, filepath.Join(root, "source-fixture.json"), &fixture)
	if len(specs) != 140 || len(oracle) != len(specs) || len(mutationRecords) != 97 || len(additionRecords) != 18 {
		t.Fatalf("incomplete unknown-label corpus specs=%d records=%d mutations=%d", len(specs), len(oracle), len(mutationRecords))
	}
	files := map[string]map[string]any{}
	for _, file := range fixture["files"].([]any) {
		entry := file.(map[string]any)
		files[entry["file"].(string)] = entry
	}
	mutations := map[string]map[string]any{}
	for _, entry := range mutationRecords {
		file := entry["file"].(string)
		if mutations[file] != nil {
			t.Fatal("duplicate mutation", file)
		}
		mutations[file] = entry
	}
	additions := map[string]map[string]any{}
	for _, entry := range additionRecords {
		file := entry["file"].(string)
		if additions[file] != nil {
			t.Fatal("duplicate addition", file)
		}
		additions[file] = entry
	}
	seen := map[string]bool{}
	outputs := []map[string]any{}
	for i, spec := range specs {
		want := oracle[i]
		name := spec["name"].(string)
		if seen[name] || want["name"] != name || !reflect.DeepEqual(want["spec"], spec) {
			t.Fatal("oracle inventory/order mismatch", i)
		}
		seen[name] = true
		t.Run(name, func(t *testing.T) {
			got := unknownLabelObserve(t, spec, false, files, mutations, root, additions)
			outputs = append(outputs, got)
			unknownLabelAssert(t, got, want)
			if !spec["cross"].(bool) {
				aux := unknownLabelObserve(t, spec, true, files, mutations, root, additions)
				got["matchedContextBoundary"] = aux
				unknownLabelAssert(t, aux, want)
			}
		})
	}
	if len(outputs) != len(specs) {
		t.Fatalf("incomplete native observations %d", len(outputs))
	}
	if output := os.Getenv("UNKNOWN_LABEL_OUTPUT"); output != "" {
		data, err := json.MarshalIndent(outputs, "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(output, append(data, '\n'), 0600); err != nil {
			t.Fatal(err)
		}
	}
}

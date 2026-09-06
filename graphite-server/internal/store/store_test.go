package store

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func ptr[T any](v T) *T { return &v }
func TestJVMFixtureAllNodesAndMetadata(t *testing.T) {
	s, err := Open("testdata/jvm-v3")
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if s.NodeCount != 16 || s.FormatVersion != 3 {
		t.Fatalf("count/version: %d/%d", s.NodeCount, s.FormatVersion)
	}
	method := MethodDescriptor{"Example", "run", []string{"int"}, "void"}
	nested := []any{int32(42), EnumReference{"Color", "RED"}, []any{nil, "hello\x00世界😀"}}
	expected := []Node{
		{ID: 0, Kind: "IntConstant", Value: int32(-17)}, {ID: 2, Kind: "StringConstant", Value: "hello\x00世界😀"}, {ID: 4, Kind: "LongConstant", Value: int64(9223372036854775806)}, {ID: 6, Kind: "FloatConstant", Value: float32(1.25)}, {ID: 8, Kind: "DoubleConstant", Value: float64(-2.75)}, {ID: 10, Kind: "BooleanConstant", Value: true}, {ID: 12, Kind: "NullConstant"},
		{ID: 14, Kind: "EnumConstant", EnumType: "Color", EnumName: "RED", EnumArguments: []any{nested}}, {ID: 16, Kind: "LocalVariable", Name: "x", Type: "int", Method: method}, {ID: 18, Kind: "FieldNode", DeclaringClass: "Example", Name: "field", Type: "int", IsStatic: true}, {ID: 20, Kind: "ParameterNode", Index: 2, Type: "int", Method: method}, {ID: 22, Kind: "ReturnNode", Method: method, ActualType: ptr("int")}, {ID: 24, Kind: "CallSiteNode", Caller: method, Callee: MethodDescriptor{"Example", "callee", []string{"int"}, "void"}, LineNumber: ptr(int32(37)), Arguments: []int32{0, 2}}, {ID: 26, Kind: "AnnotationNode", Name: "Annotation", ClassName: "Example", MemberName: "run", Values: map[string]any{"key": nested}}, {ID: 28, Kind: "ResourceValueNode", Path: "config.yml", Key: "key", Value: nested, Format: "yaml", Profile: ptr("prod")}, {ID: 30, Kind: "ResourceFileNode", Path: "config.yml", Source: "app.jar", Format: "yaml"},
	}
	for _, want := range expected {
		got, err := s.Node(want.ID)
		if err != nil {
			t.Fatal(err)
		}
		if !reflect.DeepEqual(got, want) {
			t.Errorf("node %d got %#v, want %#v", want.ID, got, want)
		}
	}
	want := Metadata{MemberAnnotationOrder: map[string][]string{"Example#run": {"Annotation"}}, MethodList: []MethodDescriptor{method}, Methods: map[string]MethodDescriptor{"Example.run(int)": method}, Supertypes: map[string][]string{"Example": {"Base"}}, Subtypes: map[string][]string{"Base": {"Example"}}, EnumValues: map[string][]any{"Color#RED": {nested}}, ClassOrigins: map[string]string{"Example": "app.jar"}, ArtifactDependencies: map[string]map[string]int32{"app.jar": {"dependency.jar": 7}}, MemberAnnotations: map[string]map[string]map[string]any{"Example#run": {"Annotation": {"key": nested}}}, BranchScopes: []BranchScope{{24, method, Comparison{2, 0}, []int32{2}, []int32{4}}}}
	if !reflect.DeepEqual(s.Metadata, want) {
		t.Errorf("metadata got %#v, want %#v", s.Metadata, want)
	}
	if !reflect.DeepEqual(s.Comparisons, map[uint64]Comparison{24<<32 | 2: {2, 0}}) {
		t.Errorf("comparisons: %#v", s.Comparisons)
	}
	if _, err := s.Node(1); !errors.Is(err, ErrNodeNotFound) {
		t.Errorf("missing node: %v", err)
	}
	if got := s.NodesOfKind("CallSiteNode"); !reflect.DeepEqual(got, []int32{24}) {
		t.Errorf("kind index: %v", got)
	}
	ids := s.NodeIDs()
	ids[0] = 99
	if s.NodeIDs()[0] != 0 {
		t.Fatal("node id result aliases store")
	}
}
func TestLegacyAnnotationsAndVersionRules(t *testing.T) {
	for _, version := range []int{1, 2, 3} {
		var b bytes.Buffer
		binary.Write(&b, binary.BigEndian, int32(7))
		b.WriteByte(13)
		for _, i := range []int32{0, 1, 2, 1, 3} {
			binary.Write(&b, binary.BigEndian, i)
		}
		if version != 1 {
			b.WriteByte(2)
		}
		binary.Write(&b, binary.BigEndian, int32(4))
		d := newDecoder(bytes.NewReader(b.Bytes()), int64(b.Len()), []string{"Annotation", "Class", "member", "key", ""})
		d.version = version
		n := d.node()
		if d.err != nil {
			t.Fatal(d.err)
		}
		var expected any = ""
		if version == 1 {
			expected = nil
		}
		if !reflect.DeepEqual(n.Values, map[string]any{"key": expected}) {
			t.Errorf("version %d annotations: %#v", version, n.Values)
		}
	}
	d := newDecoder(bytes.NewReader([]byte{8, 0, 0, 0, 0}), 5, nil)
	d.version = 1
	d.value(0)
	if d.err == nil {
		t.Fatal("v1 list accepted")
	}
	for _, header := range []uint32{0, 0x47524e00, 0x47524e04} {
		var b bytes.Buffer
		binary.Write(&b, binary.BigEndian, header)
		d := newDecoder(&b, 4, nil)
		d.header(0x47524e00)
		if d.err == nil {
			t.Errorf("accepted invalid header %#x", header)
		}
	}
}
func TestStringTableRejectsTruncation(t *testing.T) {
	b, err := os.ReadFile("testdata/jvm-v3/graph.strings")
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "graph.strings")
	for _, n := range []int{0, 4, 32, len(b) - 1} {
		if err := os.WriteFile(path, b[:n], 0600); err != nil {
			t.Fatal(err)
		}
		if _, err := LoadStrings(path); err == nil {
			t.Errorf("accepted %d bytes of %d", n, len(b))
		}
	}
}

// Correctness only: no timings are recorded.
func TestRealPersistedGraph(t *testing.T) {
	dir := os.Getenv("GRAPHITE_TEST_GRAPH")
	if dir == "" {
		t.Skip("set GRAPHITE_TEST_GRAPH to an existing Kotlin-produced persisted graph")
	}
	s, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if s.NodeCount == 0 || len(s.Metadata.Methods) == 0 {
		t.Fatal("real fixture unexpectedly empty")
	}
	if identity, err := os.ReadFile(filepath.Join(dir, "graph.strings.identity")); err == nil {
		digest := sha256.New()
		binary.Write(digest, binary.BigEndian, int32(len(s.Strings)))
		for _, value := range s.Strings {
			binary.Write(digest, binary.BigEndian, int32(len(value)))
			digest.Write([]byte(value))
		}
		if !bytes.Equal(digest.Sum(nil), identity) {
			t.Fatal("decoded string-table semantic identity differs from JVM")
		}
	}
	ids := s.NodeIDs()
	for i := 0; i < len(ids); i += max(1, len(ids)/1000) {
		node, err := s.Node(ids[i])
		if err != nil {
			t.Fatal(err)
		}
		if node.ID != ids[i] || node.Kind == "" {
			t.Fatalf("invalid node %#v", node)
		}
	}
	t.Logf("decoded %d strings, %d nodes, %d methods, %d branch scopes", len(s.Strings), s.NodeCount, len(s.Metadata.Methods), len(s.Metadata.BranchScopes))
}

func TestMetadataPreservesAnnotationInsertionOrderAcrossReplacement(t *testing.T) {
	var data bytes.Buffer
	// One method and one annotated member; B appears twice and replaces its
	// previous value without changing the first insertion order B, A.
	for _, v := range []int32{0x47524d03, 1, 0, 1, 0, 2, 0, 0, 0, 0, 0, 1, 5, 3, 3, 0, 4, 0, 3, 0, 0} {
		if err := binary.Write(&data, binary.BigEndian, v); err != nil {
			t.Fatal(err)
		}
	}
	d := newDecoder(bytes.NewReader(data.Bytes()), int64(data.Len()), []string{"C", "m", "void", "B", "A", "C#m"})
	m := d.metadata()
	if d.err != nil {
		t.Fatal(d.err)
	}
	if !reflect.DeepEqual(m.MemberAnnotationOrder, map[string][]string{"C#m": {"B", "A"}}) {
		t.Fatalf("annotation order %#v", m.MemberAnnotationOrder)
	}
	if !reflect.DeepEqual(m.MemberAnnotations, map[string]map[string]map[string]any{"C#m": {"B": {}, "A": {}}}) {
		t.Fatalf("annotations %#v", m.MemberAnnotations)
	}
}

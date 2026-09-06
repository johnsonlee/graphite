package analysis

import (
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func endpointRow(class, member, httpMethod, path, annotation string, parameters, annotations []string) map[string]any {
	method := store.MethodDescriptor{DeclaringClass: class, Name: member, ParameterTypes: parameters, ReturnType: "void"}
	return map[string]any{"class": class, "member": member, "signature": method.Signature(), "httpMethod": httpMethod, "path": path, "annotation": annotation, "returns": "void", "parameters": append([]string{}, parameters...), "annotations": append([]string{}, annotations...)}
}

// Mirrors the origin/main ExploreCommandTest RequestMapping fixture, asserting
// every returned field rather than only the number of expanded endpoints.
func TestEndpointRequestMappingCartesianExpansion(t *testing.T) {
	class := "com.example.RequestController"
	mapping := springMappingPrefix + "RequestMapping"
	graph := &store.Store{Metadata: store.Metadata{MethodList: []store.MethodDescriptor{{DeclaringClass: class, Name: "handle", ReturnType: "void"}, {DeclaringClass: class, Name: "fallback", ReturnType: "void"}}, MemberAnnotations: map[string]map[string]map[string]any{
		class + "#<class>":  {mapping: {"value": []any{"/v2", "/v1"}}},
		class + "#handle":   {mapping: {"path": []any{"/beta", "/alpha"}, "method": []any{"POST", "PATCH"}}},
		class + "#fallback": {mapping: {}},
	}, MemberAnnotationOrder: map[string][]string{class + "#handle": {mapping}, class + "#fallback": {mapping}}}}
	want := []map[string]any{}
	for _, base := range []string{"/v1", "/v2"} {
		want = append(want, endpointRow(class, "fallback", "REQUEST", base, mapping, nil, []string{mapping}))
		for _, path := range []string{"/alpha", "/beta"} {
			for _, verb := range []string{"PATCH", "POST"} {
				want = append(want, endpointRow(class, "handle", verb, base+path, mapping, nil, []string{mapping}))
			}
		}
	}
	if got := ExtractEndpoints(graph); !reflect.DeepEqual(got, want) {
		t.Fatalf("got %#v\nwant %#v", got, want)
	}
}
func TestEndpointAnnotationOrderSurvivesStableSortTies(t *testing.T) {
	request, get := springMappingPrefix+"RequestMapping", springMappingPrefix+"GetMapping"
	names := []string{"audit.Track", get, request}
	graph := &store.Store{Metadata: store.Metadata{MethodList: []store.MethodDescriptor{{DeclaringClass: "C", Name: "handle", ParameterTypes: []string{"int"}, ReturnType: "void"}}, MemberAnnotations: map[string]map[string]map[string]any{"C#handle": {request: {"path": "/x", "method": []any{"GET", "GET"}}, get: {"path": "/x"}, "audit.Track": {}}}, MemberAnnotationOrder: map[string][]string{"C#handle": {request, get, "audit.Track"}}}}
	want := []map[string]any{endpointRow("C", "handle", "GET", "/x", request, []string{"int"}, names), endpointRow("C", "handle", "GET", "/x", request, []string{"int"}, names), endpointRow("C", "handle", "GET", "/x", get, []string{"int"}, names)}
	if got := ExtractEndpoints(graph); !reflect.DeepEqual(got, want) {
		t.Fatalf("got %#v\nwant %#v", got, want)
	}
}
func TestEndpointFixedVerbsAndEnumReferenceFallback(t *testing.T) {
	suffixes := []string{"DeleteMapping", "GetMapping", "PatchMapping", "PostMapping", "PutMapping", "RequestMapping"}
	verbs := []string{"DELETE", "GET", "PATCH", "POST", "PUT", "REQUEST"}
	annotations := map[string]map[string]any{}
	names := []string{}
	for _, suffix := range suffixes {
		name := springMappingPrefix + suffix
		names = append(names, name)
		annotations[name] = map[string]any{"method": []any{store.EnumReference{EnumClass: "org.springframework.web.bind.annotation.RequestMethod", EnumName: "GET"}, int32(123)}}
	}
	graph := &store.Store{Metadata: store.Metadata{MethodList: []store.MethodDescriptor{{DeclaringClass: "C", Name: "run", ReturnType: "void"}}, MemberAnnotations: map[string]map[string]map[string]any{"C#run": annotations}, MemberAnnotationOrder: map[string][]string{"C#run": names}}}
	want := []map[string]any{}
	for i, name := range names {
		want = append(want, endpointRow("C", "run", verbs[i], "/", name, nil, names))
	}
	if got := ExtractEndpoints(graph); !reflect.DeepEqual(got, want) {
		t.Fatalf("got %#v\nwant %#v", got, want)
	}
}
func TestEndpointNormalizedPathsDeduplicateButHTTPMethodsDoNot(t *testing.T) {
	request := springMappingPrefix + "RequestMapping"
	graph := &store.Store{Metadata: store.Metadata{MethodList: []store.MethodDescriptor{{DeclaringClass: "C", Name: "run", ReturnType: "void"}}, MemberAnnotations: map[string]map[string]map[string]any{"C#<class>": {request: {"path": []string{" /api/ ", "/api"}}}, "C#run": {request: {"path": []any{" /x/ ", "x", ""}, "value": []any{"x", int32(42)}, "method": []string{"GET", "GET"}}}}, MemberAnnotationOrder: map[string][]string{"C#run": {request}}}}
	want := []map[string]any{}
	for _, path := range []string{"/api", "/api/x"} {
		for i := 0; i < 2; i++ {
			want = append(want, endpointRow("C", "run", "GET", path, request, nil, []string{request}))
		}
	}
	if got := ExtractEndpoints(graph); !reflect.DeepEqual(got, want) {
		t.Fatalf("got %#v\nwant %#v", got, want)
	}
}
func TestEndpointSortUsesJavaUTF16Order(t *testing.T) {
	get := springMappingPrefix + "GetMapping"
	graph := &store.Store{Metadata: store.Metadata{MethodList: []store.MethodDescriptor{{DeclaringClass: "C", Name: "run", ReturnType: "void"}}, MemberAnnotations: map[string]map[string]map[string]any{"C#run": {get: {"path": []string{"\ue000", "\U00010000"}}}}, MemberAnnotationOrder: map[string][]string{"C#run": {get}}}}
	want := []map[string]any{endpointRow("C", "run", "GET", "/\U00010000", get, nil, []string{get}), endpointRow("C", "run", "GET", "/\ue000", get, nil, []string{get})}
	if got := ExtractEndpoints(graph); !reflect.DeepEqual(got, want) {
		t.Fatalf("got %#v\nwant %#v", got, want)
	}
}
func TestEndpointEmptyAndUnmappedMethods(t *testing.T) {
	for _, graph := range []*store.Store{{}, {Metadata: store.Metadata{MethodList: []store.MethodDescriptor{{DeclaringClass: "C", Name: "run", ReturnType: "void"}}, MemberAnnotations: map[string]map[string]map[string]any{"C#run": {"other.Annotation": {"path": "/not-an-endpoint"}}}}}} {
		if got := ExtractEndpoints(graph); !reflect.DeepEqual(got, []map[string]any{}) {
			t.Fatalf("unexpected endpoints %#v", got)
		}
	}
}

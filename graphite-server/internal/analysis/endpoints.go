// Package analysis implements the server's graph analysis views.
package analysis

import (
	"sort"
	"strings"
	"unicode/utf16"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

const springMappingPrefix = "org.springframework.web.bind.annotation."

var mappingMethods = map[string]string{
	springMappingPrefix + "RequestMapping": "",
	springMappingPrefix + "GetMapping":     "GET",
	springMappingPrefix + "PostMapping":    "POST",
	springMappingPrefix + "PutMapping":     "PUT",
	springMappingPrefix + "DeleteMapping":  "DELETE",
	springMappingPrefix + "PatchMapping":   "PATCH",
}

// ExtractEndpoints reproduces EndpointExtractor's Spring mapping expansion.
// RequestMapping.method accepts string values only, as the Kotlin extractor
// does; typed EnumReference attributes are not silently interpreted as strings.
func ExtractEndpoints(graph *store.Store) []map[string]any {
	endpoints := []map[string]any{}
	for _, method := range graph.Metadata.MethodList {
		class := method.DeclaringClass
		classAnnotations := graph.Metadata.MemberAnnotations[class+"#<class>"]
		key := class + "#" + method.Name
		annotations := graph.Metadata.MemberAnnotations[key]
		bases := mappingPaths(classAnnotations[springMappingPrefix+"RequestMapping"])
		annotationNames := make([]string, 0, len(annotations))
		for name := range annotations {
			annotationNames = append(annotationNames, name)
		}
		sort.Slice(annotationNames, func(i, j int) bool { return javaStringLess(annotationNames[i], annotationNames[j]) })
		order := graph.Metadata.MemberAnnotationOrder[key]
		if len(order) == 0 {
			order = annotationNames
		}
		for _, annotation := range order {
			fixedMethod, recognized := mappingMethods[annotation]
			if !recognized {
				continue
			}
			values := annotations[annotation]
			paths := combineMappingPaths(bases, mappingPaths(values))
			methods := []string{fixedMethod}
			if fixedMethod == "" {
				methods = annotationStrings(values["method"])
				if len(methods) == 0 {
					methods = []string{"REQUEST"}
				}
			}
			for _, path := range paths {
				for _, httpMethod := range methods {
					endpoints = append(endpoints, map[string]any{"class": class, "member": method.Name, "signature": method.Signature(), "httpMethod": httpMethod, "path": path, "annotation": annotation, "returns": method.ReturnType, "parameters": append([]string{}, method.ParameterTypes...), "annotations": append([]string{}, annotationNames...)})
				}
			}
		}
	}
	sort.SliceStable(endpoints, func(i, j int) bool {
		for _, key := range []string{"path", "httpMethod", "signature"} {
			left, right := endpoints[i][key].(string), endpoints[j][key].(string)
			if left != right {
				return javaStringLess(left, right)
			}
		}
		return false
	})
	return endpoints
}
func annotationStrings(value any) []string {
	switch v := value.(type) {
	case string:
		return []string{v}
	case []string:
		return v
	case []any:
		out := []string{}
		for _, item := range v {
			if s, ok := item.(string); ok {
				out = append(out, s)
			}
		}
		return out
	}
	return nil
}
func mappingPaths(values map[string]any) []string {
	out := append([]string{}, annotationStrings(values["path"])...)
	out = append(out, annotationStrings(values["value"])...)
	if len(out) == 0 {
		return []string{"/"}
	}
	return out
}
func combineMappingPaths(bases, methods []string) []string {
	if len(bases) == 0 {
		bases = []string{"/"}
	}
	if len(methods) == 0 {
		methods = []string{"/"}
	}
	out := []string{}
	seen := map[string]bool{}
	for _, base := range bases {
		for _, method := range methods {
			path := normalizeMappingPath(base, method)
			if !seen[path] {
				seen[path] = true
				out = append(out, path)
			}
		}
	}
	return out
}
func normalizeMappingPath(base, method string) string {
	base = strings.Trim(strings.TrimSpace(base), "/")
	method = strings.Trim(strings.TrimSpace(method), "/")
	switch {
	case base == "" && method == "":
		return "/"
	case base == "":
		return "/" + method
	case method == "":
		return "/" + base
	default:
		return "/" + base + "/" + method
	}
}
func javaStringLess(left, right string) bool {
	a, b := utf16.Encode([]rune(left)), utf16.Encode([]rune(right))
	for i := 0; i < min(len(a), len(b)); i++ {
		if a[i] != b[i] {
			return a[i] < b[i]
		}
	}
	return len(a) < len(b)
}

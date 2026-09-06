package c4

import (
	"fmt"
	"regexp"
	"sort"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// WeightedClass is ordered evidence. Keeping this as a slice preserves Kotlin
// linked-map tie ordering, which would be lost in a Go map iteration.
type WeightedClass struct {
	ClassName string
	Weight    int
}

func ExternalDependencyKey(graph *store.Store, class string) string {
	if graph != nil {
		if origin, ok := graph.Metadata.ClassOrigins[class]; ok {
			if artifact, ok := ArtifactKey(origin); ok {
				return ArtifactIDPrefix + artifact
			}
		}
	}
	switch {
	case IsJavaRuntimeClass(class):
		return RuntimeIDPrefix + "java"
	case strings.HasPrefix(class, "kotlin."):
		return RuntimeIDPrefix + "kotlin"
	case strings.HasPrefix(class, "scala."):
		return RuntimeIDPrefix + "scala"
	default:
		return NamespaceIDPrefix + NamespaceGroup(class)
	}
}
func ExternalDependencyName(graph *store.Store, key string, classes []string) string {
	switch key {
	case "runtime:java":
		return "Java Runtime"
	case "runtime:kotlin":
		return "Kotlin Runtime"
	case "runtime:scala":
		return "Scala Runtime"
	}
	if strings.HasPrefix(key, ArtifactIDPrefix) {
		return strings.TrimPrefix(key, ArtifactIDPrefix)
	}
	if strings.HasPrefix(key, NamespaceIDPrefix) {
		return strings.TrimPrefix(key, NamespaceIDPrefix)
	}
	if graph != nil {
		for _, class := range classes {
			if origin, ok := graph.Metadata.ClassOrigins[class]; ok {
				return origin
			}
		}
	}
	return key
}
func ExternalDependencySource(key string) string {
	if strings.HasPrefix(key, ArtifactIDPrefix) {
		return "artifact"
	}
	if strings.HasPrefix(key, RuntimeIDPrefix) {
		return "runtime"
	}
	return "namespace"
}
func ClassifyDependencyKind(key string) DependencyKind {
	if strings.HasPrefix(key, RuntimeIDPrefix) {
		return RuntimeDependency
	}
	if strings.HasPrefix(key, ArtifactIDPrefix) {
		return LibraryDependency
	}
	return ExternalSystemDependency
}
func ExternalDependencyConfidence(key string) string {
	if strings.HasPrefix(key, ArtifactIDPrefix) || strings.HasPrefix(key, RuntimeIDPrefix) {
		return "high"
	}
	return "medium"
}
func ExternalDependencyResponsibility(kind DependencyKind) string {
	switch kind {
	case RuntimeDependency:
		return "Provides language and platform runtime services used by the application"
	case LibraryDependency:
		return "Provides reusable library capabilities linked from the application runtime"
	default:
		return "Represents an inferred external software system boundary grouped from referenced classes"
	}
}
func ExternalDependencyDescription(kind DependencyKind) string {
	switch kind {
	case RuntimeDependency:
		return "Language and platform runtime supporting the subject system"
	case LibraryDependency:
		return "Reusable third-party library capabilities referenced from the subject system"
	case ExternalSystemDependency:
		return "External software system candidate inferred from referenced-but-absent classes"
	default:
		return "External collaborator inferred from code graph evidence"
	}
}
func ArtifactKey(origin string) (string, bool) {
	candidate := strings.TrimRight(strings.TrimSpace(origin), "/")
	if i := strings.LastIndexByte(candidate, '/'); i >= 0 {
		candidate = candidate[i+1:]
	}
	candidate = strings.TrimSuffix(candidate, ".jar")
	return candidate, strings.TrimSpace(candidate) != ""
}
func ArtifactNameFromDependencyID(id string) (string, bool) {
	key := strings.TrimPrefix(id, DependencyIDPrefix)
	if !strings.HasPrefix(key, ArtifactIDPrefix) {
		return "", false
	}
	name := strings.TrimPrefix(key, ArtifactIDPrefix)
	return name, strings.TrimSpace(name) != ""
}

var artifactVersion = regexp.MustCompile(`-\d+(?:[.-][0-9A-Za-z]+)*$`)

func ArtifactBaseName(name string) string { return artifactVersion.ReplaceAllString(name, "") }
func NamespaceGroup(name string) string {
	segments := nonblankSegments(name)
	if len(segments) == 0 {
		return name
	}
	cutoff := -1
	for i, segment := range segments {
		first, _ := utf8.DecodeRuneInString(segment)
		if first <= 0xffff && unicode.IsUpper(first) {
			cutoff = i
			break
		}
	}
	packages := segments
	if cutoff > 0 {
		packages = segments[:cutoff]
	}
	root := NamespaceRootSegmentCount(packages)
	selected := min(3, len(packages))
	if root == 1 {
		selected = root
	}
	return strings.Join(packages[:selected], ".")
}
func namespaceFamily(key string) string {
	parts := nonblankSegments(strings.TrimPrefix(key, NamespaceIDPrefix))
	return strings.Join(parts[:min(3, len(parts))], ".")
}
func SummarizeExternalDependencies(graph *store.Store, weights []WeightedClass, limit int) ([]ExternalDependency, error) {
	if limit < 0 {
		return nil, fmt.Errorf("requested element count %d is less than zero", limit)
	}
	grouped := map[string][]WeightedClass{}
	order := []string{}
	for _, entry := range weights {
		key := ExternalDependencyKey(graph, entry.ClassName)
		if _, exists := grouped[key]; !exists {
			order = append(order, key)
		}
		grouped[key] = append(grouped[key], entry)
	}
	families := map[string]int{}
	for _, key := range order {
		if strings.HasPrefix(key, NamespaceIDPrefix) {
			families[namespaceFamily(key)]++
		}
	}
	merged := map[string][]WeightedClass{}
	mergedOrder := []string{}
	for _, key := range order {
		mergedKey := key
		if strings.HasPrefix(key, NamespaceIDPrefix) {
			family := namespaceFamily(key)
			if families[family] > 1 {
				mergedKey = NamespaceIDPrefix + family
			}
		}
		if _, exists := merged[mergedKey]; !exists {
			mergedOrder = append(mergedOrder, mergedKey)
		}
		merged[mergedKey] = append(merged[mergedKey], grouped[key]...)
	}
	out := make([]ExternalDependency, 0, len(mergedOrder))
	for _, key := range mergedOrder {
		weight := 0
		classes := []string{}
		for _, entry := range merged[key] {
			weight += entry.Weight
			classes = append(classes, entry.ClassName)
		}
		kind := ClassifyDependencyKind(key)
		out = append(out, ExternalDependency{ID: DependencyIDPrefix + key, Name: ExternalDependencyName(graph, key, classes), Weight: weight, Source: ExternalDependencySource(key), Kind: kind, Confidence: ExternalDependencyConfidence(key), Responsibility: ExternalDependencyResponsibility(kind), Artifacts: []string{}})
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Weight > out[j].Weight })
	return out[:min(limit, len(out))], nil
}

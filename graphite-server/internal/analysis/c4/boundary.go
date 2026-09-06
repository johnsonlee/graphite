package c4

import (
	"regexp"
	"sort"
	"strings"
	"unicode"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

var reverseDNSPrefixes = map[string]bool{"app": true, "biz": true, "co": true, "com": true, "dev": true, "edu": true, "gov": true, "io": true, "me": true, "mil": true, "net": true, "org": true}

func nonblankSegments(value string) []string {
	out := []string{}
	for _, part := range strings.Split(value, ".") {
		if strings.TrimSpace(part) != "" {
			out = append(out, part)
		}
	}
	return out
}
func IsReverseDNSNamespace(segments []string) bool {
	return len(segments) > 0 && reverseDNSPrefixes[strings.ToLower(segments[0])]
}
func NamespaceRootSegmentCount(segments []string) int {
	if len(segments) == 0 {
		return 0
	}
	if IsReverseDNSNamespace(segments) {
		return min(2, len(segments))
	}
	return 1
}
func DominantNamespace(packageName string) string {
	parts := nonblankSegments(packageName)
	return strings.Join(parts[:NamespaceRootSegmentCount(parts)], ".")
}
func IsSyntheticClass(name string) bool {
	return name == "sootup.dummy" || strings.HasPrefix(name, "sootup.dummy.")
}
func IsInternalClass(name, boundary string) bool {
	return !IsSyntheticClass(name) && (name == boundary || strings.HasPrefix(name, boundary+"."))
}
func IsJavaRuntimeClass(name string) bool {
	for _, prefix := range []string{"java.", "javax.", "jakarta.", "jdk."} {
		if strings.HasPrefix(name, prefix) {
			return true
		}
	}
	return false
}
func IsRuntimeClass(name string) bool {
	return IsJavaRuntimeClass(name) || strings.HasPrefix(name, "kotlin.") || strings.HasPrefix(name, "scala.")
}
func packageName(className string) string {
	if i := strings.LastIndexByte(className, '.'); i >= 0 {
		return className[:i]
	}
	return ""
}
func InternalPackageUnit(class, boundary string) string {
	pkg := packageName(class)
	if strings.TrimSpace(pkg) == "" {
		return DefaultSystemBoundary
	}
	if !IsInternalClass(class, boundary) {
		parts := strings.Split(pkg, ".")
		return strings.Join(parts[:NamespaceRootSegmentCount(parts)], ".")
	}
	suffix := strings.TrimLeft(strings.TrimPrefix(pkg, boundary), ".")
	if strings.TrimSpace(suffix) == "" {
		return boundary
	}
	return boundary + "." + strings.SplitN(suffix, ".", 2)[0]
}

type namedWeight struct {
	name   string
	weight int
}

func DeriveSystemBoundary(methods []store.MethodDescriptor, callSites []store.Node) string {
	classes := []string{}
	for _, method := range methods {
		classes = append(classes, method.DeclaringClass)
	}
	if len(classes) == 0 {
		for _, node := range callSites {
			classes = append(classes, node.Caller.DeclaringClass)
		}
	}
	packages := []string{}
	for _, class := range classes {
		if IsSyntheticClass(class) {
			continue
		}
		pkg := packageName(class)
		if strings.TrimSpace(pkg) != "" {
			packages = append(packages, pkg)
		}
	}
	if len(packages) == 0 {
		return DefaultSystemBoundary
	}
	packageWeights := map[string]int{}
	packageOrder := []string{}
	rootWeights := map[string]int{}
	rootOrder := []string{}
	for _, pkg := range packages {
		if _, ok := packageWeights[pkg]; !ok {
			packageOrder = append(packageOrder, pkg)
		}
		packageWeights[pkg]++
		root := DominantNamespace(pkg)
		if _, ok := rootWeights[root]; !ok {
			rootOrder = append(rootOrder, root)
		}
		rootWeights[root]++
	}
	prefixWeights := map[string]int{}
	prefixOrder := []string{}
	for _, pkg := range packageOrder {
		parts := nonblankSegments(pkg)
		for depth := NamespaceRootSegmentCount(parts); depth <= min(4, len(parts)); depth++ {
			prefix := strings.Join(parts[:depth], ".")
			if _, ok := prefixWeights[prefix]; !ok {
				prefixOrder = append(prefixOrder, prefix)
			}
			prefixWeights[prefix] += packageWeights[pkg]
		}
	}
	root := rootOrder[0]
	for _, candidate := range rootOrder[1:] {
		if rootWeights[candidate] > rootWeights[root] {
			root = candidate
		}
	}
	if root == DefaultSystemBoundary {
		return root
	}
	boundary := root
	for IsReverseDNSNamespace(nonblankSegments(boundary)) {
		weight, ok := prefixWeights[boundary]
		if !ok {
			break
		}
		depth := len(nonblankSegments(boundary)) + 1
		if depth > 4 {
			break
		}
		children := []namedWeight{}
		for _, prefix := range prefixOrder {
			if strings.Count(prefix, ".")+1 == depth && strings.HasPrefix(prefix, boundary+".") {
				children = append(children, namedWeight{prefix, prefixWeights[prefix]})
			}
		}
		sort.SliceStable(children, func(i, j int) bool { return children[i].weight > children[j].weight })
		if len(children) == 0 {
			break
		}
		runnerUp := 0
		if len(children) > 1 {
			runnerUp = children[1].weight
		}
		if float64(children[0].weight)/float64(weight) >= 0.85 && children[0].weight >= runnerUp*3 {
			boundary = children[0].name
		} else {
			break
		}
	}
	return boundary
}

var identifierBoundary = regexp.MustCompile(`([a-z0-9])([A-Z])`)

func HumanizeIdentifier(identifier string) string {
	value := identifierBoundary.ReplaceAllString(identifier, "$1 $2")
	value = strings.NewReplacer("-", " ", "_", " ").Replace(value)
	parts := []string{}
	for _, part := range strings.Split(value, " ") {
		if strings.TrimSpace(part) == "" {
			continue
		}
		runes := []rune(strings.ToLower(part))
		runes[0] = unicode.ToUpper(runes[0])
		parts = append(parts, string(runes))
	}
	return strings.Join(parts, " ")
}

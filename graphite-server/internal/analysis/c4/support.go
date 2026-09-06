package c4

import (
	"encoding/json"
	"fmt"
	"regexp"
	"sort"
	"strings"
	"unicode"
	"unicode/utf16"
)

func ptr[T any](v T) *T { return &v }
func value[T any](v *T) T {
	if v != nil {
		return *v
	}
	var z T
	return z
}
func unique(xs []string) []string {
	out := []string{}
	seen := map[string]bool{}
	for _, x := range xs {
		if !seen[x] {
			seen[x] = true
			out = append(out, x)
		}
	}
	return out
}
func lexical(a, b string) bool {
	aa, bb := utf16.Encode([]rune(a)), utf16.Encode([]rune(b))
	for i := 0; i < len(aa) && i < len(bb); i++ {
		if aa[i] != bb[i] {
			return aa[i] < bb[i]
		}
	}
	return len(aa) < len(bb)
}
func sorted(xs []string) []string {
	out := append([]string{}, xs...)
	sort.SliceStable(out, func(i, j int) bool { return lexical(out[i], out[j]) })
	return out
}
func keys[V any](m map[string]V) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	return sorted(out)
}
func take[T any](xs []T, n int) []T {
	if n < 0 {
		n = 0
	}
	if n < len(xs) {
		return xs[:n]
	}
	return xs
}
func orCount(m map[string]int, k string, fallback int) int {
	if v, ok := m[k]; ok {
		return v
	}
	return fallback
}
func simpleName(s string) string { return s[strings.LastIndex(s, ".")+1:] }

var slugPattern = regexp.MustCompile(`[^a-z0-9]+`)

func Slugify(s string) string {
	return strings.Trim(slugPattern.ReplaceAllString(strings.ToLower(s), "-"), "-")
}
func humanizeArtifact(s string, acronyms bool) string {
	tokens := strings.FieldsFunc(ArtifactBaseName(s), func(r rune) bool { return r == '-' || r == '_' })
	for i, t := range tokens {
		if acronyms && len([]rune(t)) <= 3 {
			tokens[i] = strings.ToUpper(t)
		} else {
			r := []rune(t)
			if len(r) > 0 {
				r[0] = unicode.ToTitle(r[0])
			}
			tokens[i] = string(r)
		}
	}
	return strings.Join(tokens, " ")
}
func metadata(v any) map[string]any {
	out := map[string]any{}
	if m, ok := v.(map[string]any); ok {
		for k, v := range m {
			if v == nil {
				continue
			}
			switch a := v.(type) {
			case []string:
				if len(a) == 0 {
					continue
				}
			case []any:
				if len(a) == 0 {
					continue
				}
			case []map[string]any:
				if len(a) == 0 {
					continue
				}
			}
			out[k] = v
		}
	}
	return out
}
func elementProperties(e Element) map[string]any {
	m := metadata(e.Metadata)
	for k, v := range e.ExtensionProperties {
		m[k] = v
	}
	return m
}
func stringProperty(m map[string]any, k string) string {
	if v := m[k]; v != nil {
		return fmt.Sprint(v)
	}
	return ""
}
func compactJSON(v any) string { b, _ := json.Marshal(v); return string(b) }

type pair struct{ From, To string }
type orderedCounts[K comparable] struct {
	Keys   []K
	Values map[K]int
}

func (m *orderedCounts[K]) add(k K, n int) {
	if m.Values == nil {
		m.Values = map[K]int{}
	}
	if _, ok := m.Values[k]; !ok {
		m.Keys = append(m.Keys, k)
	}
	m.Values[k] += n
}
func (m *orderedCounts[K]) ranked() []K {
	r := append([]K{}, m.Keys...)
	sort.SliceStable(r, func(i, j int) bool { return m.Values[r[i]] > m.Values[r[j]] })
	return r
}
func relationshipKind(r Relationship) RelationshipKind {
	if r.Kind != nil {
		return *r.Kind
	}
	return RelationshipKind(r.Type)
}

package query

import (
	"math"
	"reflect"
	"slices"
	"unicode/utf8"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// Java Map/List equality retains boxed numeric types, canonical NaNs and signed
// zero, unlike Cypher's value keys. Hashes need not be Java's numeric hashCode;
// they only select a bucket whose values are then compared structurally.
func genericJavaHash(value any) uint64 {
	mix := func(a, b uint64) uint64 { return (a ^ b) * 1099511628211 }
	text := func(s string) uint64 {
		if !utf8.ValidString(s) {
			s = javaFromUTF16(javaUTF16(s))
		}
		h := uint64(1469598103934665603)
		for i := range len(s) {
			h = mix(h, uint64(s[i]))
		}
		return h
	}
	switch v := value.(type) {
	case nil:
		return 1
	case bool:
		if v {
			return 2
		}
		return 3
	case string:
		return mix(4, text(v))
	case int:
		return mix(5, uint64(int64(v)))
	case int32:
		return mix(5, uint64(int64(v)))
	case int64:
		return mix(6, uint64(v))
	case float32:
		b := math.Float32bits(v)
		if math.IsNaN(float64(v)) {
			b = 0x7fc00000
		}
		return mix(7, uint64(b))
	case float64:
		b := math.Float64bits(v)
		if math.IsNaN(v) {
			b = 0x7ff8000000000000
		}
		return mix(8, b)
	case []any:
		h := uint64(9)
		for _, item := range v {
			h = mix(h, genericJavaHash(item))
		}
		return mix(h, uint64(len(v)))
	case orderedMap:
		return genericJavaHash(v.Values)
	case map[string]any:
		h := uint64(10)
		for k, item := range v {
			h += mix(text(k), genericJavaHash(item))
		}
		return mix(h, uint64(len(v)))
	case store.Node:
		return genericJavaHash(genericNodeFields(v))
	case store.MethodDescriptor:
		return genericJavaHash(genericMethodFields(v))
	case qualifiedNode:
		return mix(mix(12, text(v.GraphID)), uint64(v.Node.ID))
	case store.EnumReference:
		return mix(mix(11, text(v.EnumClass)), text(v.EnumName))
	default:
		return text(key(value))
	}
}
func genericJavaEqual(a, b any) bool {
	if a == nil || b == nil {
		return a == nil && b == nil
	}
	switch x := a.(type) {
	case store.Node:
		y, ok := b.(store.Node)
		return ok && genericJavaEqual(genericNodeFields(x), genericNodeFields(y))
	case store.MethodDescriptor:
		y, ok := b.(store.MethodDescriptor)
		return ok && genericJavaEqual(genericMethodFields(x), genericMethodFields(y))
	case store.EnumReference:
		y, ok := b.(store.EnumReference)
		return ok && genericJavaEqual(x.EnumClass, y.EnumClass) && genericJavaEqual(x.EnumName, y.EnumName)
	case qualifiedNode:
		y, ok := b.(qualifiedNode)
		return ok && genericJavaEqual(x.GraphID, y.GraphID) && x.Node.ID == y.Node.ID
	case int:
		switch y := b.(type) {
		case int:
			return x == y
		case int32:
			return int64(x) == int64(y)
		}
		return false
	case int32:
		switch y := b.(type) {
		case int:
			return int64(x) == int64(y)
		case int32:
			return x == y
		}
		return false
	case int64:
		y, ok := b.(int64)
		return ok && x == y
	case float32:
		y, ok := b.(float32)
		return ok && (math.Float32bits(x) == math.Float32bits(y) || math.IsNaN(float64(x)) && math.IsNaN(float64(y)))
	case float64:
		y, ok := b.(float64)
		return ok && (math.Float64bits(x) == math.Float64bits(y) || math.IsNaN(x) && math.IsNaN(y))
	case string:
		y, ok := b.(string)
		if !ok {
			return false
		}
		if x == y {
			return true
		}
		if utf8.ValidString(x) && utf8.ValidString(y) {
			return false
		}
		return slices.Equal(javaUTF16(x), javaUTF16(y))
	case []any:
		y, ok := b.([]any)
		if !ok || len(x) != len(y) {
			return false
		}
		for i := range x {
			if !genericJavaEqual(x[i], y[i]) {
				return false
			}
		}
		return true
	case orderedMap:
		return genericJavaEqual(x.Values, mapValues(b))
	case map[string]any:
		y, ok := mapValues(b).(map[string]any)
		if !ok || len(x) != len(y) {
			return false
		}
		for k, v := range x {
			w, present := y[k]
			if !present || !genericJavaEqual(v, w) {
				return false
			}
		}
		return true
	default:
		return reflect.DeepEqual(a, b)
	}
}

func genericMethodFields(m store.MethodDescriptor) []any {
	parameters := make([]any, len(m.ParameterTypes))
	for i, v := range m.ParameterTypes {
		parameters[i] = v
	}
	return []any{m.DeclaringClass, m.Name, parameters, m.ReturnType}
}

// Kotlin Node data classes compare their semantic constructor fields. Preserve
// boxed annotation values, but ignore Go's map emission-order bookkeeping.
func genericNodeFields(n store.Node) []any {
	values := []any{n.Kind, n.ID}
	optionalString := func(p *string) any {
		if p == nil {
			return nil
		}
		return *p
	}
	optionalInt := func(p *int32) any {
		if p == nil {
			return nil
		}
		return *p
	}
	switch n.Kind {
	case "EnumConstant":
		values = append(values, n.EnumType, n.EnumName, n.EnumArguments)
	case "LocalVariable":
		values = append(values, n.Name, n.Type, n.Method)
	case "FieldNode":
		values = append(values, n.DeclaringClass, n.Name, n.Type, n.IsStatic)
	case "ParameterNode":
		values = append(values, n.Index, n.Type, n.Method)
	case "ReturnNode":
		values = append(values, n.Method, optionalString(n.ActualType))
	case "CallSiteNode":
		arguments := make([]any, len(n.Arguments))
		for i, v := range n.Arguments {
			arguments[i] = v
		}
		values = append(values, n.Caller, n.Callee, optionalInt(n.LineNumber), optionalInt(n.Receiver), arguments)
	case "AnnotationNode":
		values = append(values, n.Name, n.ClassName, n.MemberName, n.Values)
	case "ResourceValueNode":
		values = append(values, n.Path, n.Key, n.Value, n.Format, optionalString(n.Profile))
	case "ResourceFileNode":
		values = append(values, n.Path, n.Source, n.Format)
	default:
		values = append(values, n.Value)
	}
	return values
}

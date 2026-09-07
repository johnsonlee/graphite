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

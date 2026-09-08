package benchmarkcase

import (
	"fmt"
	"reflect"
	"sort"
	"strconv"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/javastring"
	"github.com/johnsonlee/graphite/graphite-server/internal/query"
)

// CanonicalResult preserves main's benchmark framing, numeric runtime types,
// columns and row order. Only map entries are sorted, as in the original harness.
func CanonicalResult(result query.Result) (string, error) {
	columns := make([]string, len(result.Columns))
	for i, c := range result.Columns {
		columns[i] = frame("string", c)
	}
	rows := make([]string, len(result.Rows))
	for i, row := range result.Rows {
		keys := make([]string, 0, len(row))
		for key := range row {
			keys = append(keys, key)
		}
		sort.Slice(keys, func(a, b int) bool { return javaLess(keys[a], keys[b]) })
		values := make([]string, 0, 2*len(keys))
		for _, key := range keys {
			value, err := canonicalValue(row[key])
			if err != nil {
				return "", err
			}
			values = append(values, frame("string", key), value)
		}
		rows[i] = sequence("row", values)
	}
	return sequence("columns", columns) + sequence("rows", rows), nil
}

func frame(tag, value string) string {
	return tag + ":" + strconv.Itoa(len(javastring.WireString(value))) + ":" + javastring.WireString(value)
}
func sequence(tag string, values []string) string {
	var b strings.Builder
	b.WriteString(strconv.Itoa(len(values)))
	for _, value := range values {
		b.WriteString(frame("item", value))
	}
	return frame(tag, b.String())
}
func javaLess(a, b string) bool {
	x, y := javastring.UTF16(a), javastring.UTF16(b)
	for i := 0; i < len(x) && i < len(y); i++ {
		if x[i] != y[i] {
			return x[i] < y[i]
		}
	}
	return len(x) < len(y)
}
func canonicalValue(value any) (string, error) {
	number := func(kind, text string) (string, error) {
		return sequence("number", []string{frame("type", kind), frame("value", text)}), nil
	}
	switch v := value.(type) {
	case nil:
		return "null", nil
	case string:
		return frame("string", v), nil
	case bool:
		return frame("boolean", strconv.FormatBool(v)), nil
	case int8:
		return number("java.lang.Byte", strconv.FormatInt(int64(v), 10))
	case int16:
		return number("java.lang.Short", strconv.FormatInt(int64(v), 10))
	case int32:
		return number("java.lang.Integer", strconv.FormatInt(int64(v), 10))
	case int64:
		return number("java.lang.Long", strconv.FormatInt(v, 10))
	case int:
		return number("java.lang.Integer", strconv.Itoa(v))
	case float32:
		return number("java.lang.Float", query.FormatJavaFloat(float64(v), 32))
	case float64:
		return number("java.lang.Double", query.FormatJavaFloat(v, 64))
	case query.OutputObject:
		return canonicalValue(v.Values)
	}
	v := reflect.ValueOf(value)
	switch v.Kind() {
	case reflect.Map:
		type pair struct{ key, value string }
		pairs := make([]pair, 0, v.Len())
		for _, key := range v.MapKeys() {
			k, err := canonicalValue(key.Interface())
			if err != nil {
				return "", err
			}
			x, err := canonicalValue(v.MapIndex(key).Interface())
			if err != nil {
				return "", err
			}
			pairs = append(pairs, pair{k, x})
		}
		sort.Slice(pairs, func(a, b int) bool { return javaLess(pairs[a].key, pairs[b].key) })
		values := make([]string, 0, 2*len(pairs))
		for _, p := range pairs {
			values = append(values, p.key, p.value)
		}
		return sequence("map", values), nil
	case reflect.Slice, reflect.Array:
		values := make([]string, v.Len())
		for i := range values {
			x, err := canonicalValue(v.Index(i).Interface())
			if err != nil {
				return "", err
			}
			values[i] = x
		}
		tag := "iterable"
		if v.Kind() == reflect.Array {
			tag = "array"
		}
		return sequence(tag, values), nil
	default:
		return "", fmt.Errorf("unsupported benchmark canonical value %T", value)
	}
}

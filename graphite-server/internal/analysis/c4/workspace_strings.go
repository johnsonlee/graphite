package c4

import (
	"encoding/json"
	"reflect"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/javastring"
)

// workspaceJSONValue performs the typed workspace -> JSON-object conversion
// without encoding its strings as UTF-8. encoding/json normalizes isolated
// surrogates before the server's final JVM-compatible wire encoding can run.
func workspaceJSONValue(v reflect.Value) any {
	if !v.IsValid() {
		return nil
	}
	switch v.Kind() {
	case reflect.Pointer, reflect.Interface:
		if v.IsNil() {
			return nil
		}
		return workspaceJSONValue(v.Elem())
	case reflect.String:
		return v.String()
	case reflect.Bool:
		return v.Bool()
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		return float64(v.Int())
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		return float64(v.Uint())
	case reflect.Float32, reflect.Float64:
		return v.Float()
	case reflect.Slice, reflect.Array:
		if v.Kind() == reflect.Slice && v.IsNil() {
			return nil
		}
		out := make([]any, v.Len())
		for i := range out {
			out[i] = workspaceJSONValue(v.Index(i))
		}
		return out
	case reflect.Map:
		if v.IsNil() {
			return nil
		}
		out := map[string]any{}
		iter := v.MapRange()
		for iter.Next() {
			out[iter.Key().String()] = workspaceJSONValue(iter.Value())
		}
		return out
	case reflect.Struct:
		out := map[string]any{}
		typ := v.Type()
		for i := 0; i < v.NumField(); i++ {
			field := typ.Field(i)
			if !field.IsExported() {
				continue
			}
			tags := strings.Split(field.Tag.Get("json"), ",")
			name := tags[0]
			if name == "-" {
				continue
			}
			if name == "" {
				name = field.Name
			}
			if len(tags) > 1 && tags[1] == "omitempty" && v.Field(i).IsZero() {
				continue
			}
			out[name] = workspaceJSONValue(v.Field(i))
		}
		return out
	}
	return nil
}

// DecodeWorkspace retains encoding/json's validation/default behavior, then
// restores each original string into the validated shape. This avoids sentinel
// substitutions, including collisions between property keys with surrogates.
func restoreWorkspaceStrings(dst reflect.Value, src any) {
	if !dst.IsValid() || src == nil {
		return
	}
	switch dst.Kind() {
	case reflect.Pointer:
		if !dst.IsNil() {
			restoreWorkspaceStrings(dst.Elem(), src)
		}
	case reflect.String:
		if s, ok := src.(string); ok && dst.CanSet() {
			dst.SetString(s)
		}
	case reflect.Interface:
		if dst.CanSet() {
			dst.Set(reflect.ValueOf(src))
		}
	case reflect.Struct:
		m, ok := src.(map[string]any)
		if !ok {
			return
		}
		for i := 0; i < dst.NumField(); i++ {
			field := dst.Type().Field(i)
			name := strings.Split(field.Tag.Get("json"), ",")[0]
			if name == "" {
				name = field.Name
			}
			if v, ok := m[name]; ok {
				restoreWorkspaceStrings(dst.Field(i), v)
			}
		}
	case reflect.Slice:
		xs, ok := src.([]any)
		if !ok {
			return
		}
		for i := 0; i < dst.Len() && i < len(xs); i++ {
			restoreWorkspaceStrings(dst.Index(i), xs[i])
		}
	case reflect.Map:
		if dst.Type().Key().Kind() != reflect.String || !dst.CanSet() {
			return
		}
		srcValue := reflect.ValueOf(src)
		if srcValue.Kind() != reflect.Map {
			return
		}
		out := reflect.MakeMapWithSize(dst.Type(), srcValue.Len())
		iter := srcValue.MapRange()
		for iter.Next() {
			k := iter.Key().String()
			v := reflect.New(dst.Type().Elem()).Elem()
			if old := dst.MapIndex(reflect.ValueOf(k)); old.IsValid() {
				v.Set(old)
			}
			restoreWorkspaceStrings(v, iter.Value().Interface())
			out.SetMapIndex(reflect.ValueOf(k), v)
		}
		dst.Set(out)
	}
}

func jsonQuote(s string) string {
	var out strings.Builder
	out.WriteByte('"')
	for _, r := range javastring.CodePoints(s) {
		switch r {
		case '"':
			out.WriteString(`\"`)
		case '\\':
			out.WriteString(`\\`)
		case '\b':
			out.WriteString(`\b`)
		case '\f':
			out.WriteString(`\f`)
		case '\n':
			out.WriteString(`\n`)
		case '\r':
			out.WriteString(`\r`)
		case '\t':
			out.WriteString(`\t`)
		default:
			if r < 0x20 || r == '<' || r == '>' || r == '&' || r == '\'' || r == '=' || r == 0x2028 || r == 0x2029 {
				const hex = "0123456789abcdef"
				out.WriteString(`\u`)
				out.WriteByte(hex[r>>12&15])
				out.WriteByte(hex[r>>8&15])
				out.WriteByte(hex[r>>4&15])
				out.WriteByte(hex[r&15])
			} else if r >= 0xd800 && r <= 0xdfff {
				out.WriteString(javastring.FromUTF16([]uint16{uint16(r)}))
			} else {
				out.WriteRune(r)
			}
		}
	}
	out.WriteByte('"')
	return out.String()
}

func compactJSON(v any) string { return compactJSONValue(workspaceJSONValue(reflect.ValueOf(v))) }
func compactJSONValue(v any) string {
	switch x := v.(type) {
	case string:
		return jsonQuote(x)
	case map[string]any:
		fields := []string{}
		for _, k := range keys(x) {
			fields = append(fields, jsonQuote(k)+":"+compactJSONValue(x[k]))
		}
		return "{" + strings.Join(fields, ",") + "}"
	case []any:
		values := make([]string, len(x))
		for i, v := range x {
			values[i] = compactJSONValue(v)
		}
		return "[" + strings.Join(values, ",") + "]"
	default:
		b, _ := json.Marshal(x)
		return string(b)
	}
}

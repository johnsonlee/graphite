package server

import (
	"encoding/json"
	"sort"
	"strings"
	"unicode/utf16"
	"unicode/utf8"
)

// gsonWireString performs the final Java UTF16 -> UTF8 boundary. Unlike Go's
// JSON encoder, a lone surrogate is one unmappable unit and becomes one '?'.
// A genuine U+FFFD is valid text and is never treated as a surrogate.
func gsonWireString(text string) string {
	if utf8.ValidString(text) {
		return text
	}
	var out strings.Builder
	for i := 0; i < len(text); {
		r, n := gsonWireRune(text[i:])
		i += n
		if r >= 0xD800 && r <= 0xDBFF && i < len(text) {
			low, size := gsonWireRune(text[i:])
			if low >= 0xDC00 && low <= 0xDFFF {
				r = utf16.DecodeRune(r, low)
				i += size
			}
		}
		if utf16.IsSurrogate(r) {
			out.WriteByte('?')
		} else {
			out.WriteRune(r)
		}
	}
	return out.String()
}
func gsonWireRune(text string) (rune, int) {
	if len(text) >= 3 && text[0] == 0xED && text[1] >= 0xA0 && text[1] <= 0xBF && text[2]&0xC0 == 0x80 {
		return rune(text[0]&15)<<12 | rune(text[1]&63)<<6 | rune(text[2]&63), 3
	}
	return utf8.DecodeRuneInString(text)
}

// Serialization failures occur after execution in main's continuation and are
// handled by Javalin as server errors; query evaluation failures remain 400.
type gsonSerializationError struct{ cause error }

func (e *gsonSerializationError) Error() string { return e.cause.Error() }
func (e *gsonSerializationError) Unwrap() error { return e.cause }
func encodeCypherResponse(value any) ([]byte, error) {
	body, err := json.Marshal(omitNullFields(value))
	if err != nil {
		return nil, &gsonSerializationError{err}
	}
	return body, nil
}

// Write Java map members individually: distinct UTF16 names may have identical
// final UTF8 spelling. A Go map keyed by that spelling would discard values.
type gsonOutputObject struct {
	keys   []string
	values map[string]any
}

func (object gsonOutputObject) MarshalJSON() ([]byte, error) {
	var out strings.Builder
	out.WriteByte('{')
	first := true
	seen := map[string]bool{}
	for _, key := range object.keys {
		if seen[key] {
			continue
		}
		seen[key] = true
		value := omitNullFields(object.values[key])
		if value == nil {
			continue
		}
		encoded, err := json.Marshal(value)
		if err != nil {
			return nil, err
		}
		name, err := json.Marshal(gsonWireString(key))
		if err != nil {
			return nil, err
		}
		if !first {
			out.WriteByte(',')
		}
		first = false
		out.Write(name)
		out.WriteByte(':')
		out.Write(encoded)
	}
	out.WriteByte('}')
	return []byte(out.String()), nil
}

// Plain Go maps carry no insertion order. Preserve all entries in a stable
// fallback order; query objects with observable Java order use OutputObject.
func gsonWireMap(values map[string]any) any {
	converted := make(map[string]any, len(values))
	collision := false
	for key, value := range values {
		wire := gsonWireString(key)
		if _, present := converted[wire]; present {
			collision = true
		}
		converted[wire] = value
	}
	if !collision {
		return converted
	}
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return gsonOutputObject{keys, values}
}

package server

import (
	"encoding/json"
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

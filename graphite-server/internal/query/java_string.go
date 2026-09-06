package query

import (
	"github.com/johnsonlee/graphite/graphite-server/internal/javastring"
	"strconv"
	"strings"
)

func javaUTF16(s string) []uint16                        { return javastring.UTF16(s) }
func javaFromUTF16(units []uint16) string                { return javastring.FromUTF16(units) }
func javaWireString(s string) string                     { return javastring.WireString(s) }
func javaCodePoints(s string) []rune                     { return javastring.CodePoints(s) }
func javaWhitespace(r rune) bool                         { return javastring.Whitespace(r) }
func (e evaluator) javaCase(s string, upper bool) string { return javastring.Case(s, upper, e.check) }

func (e evaluator) unitIndex(haystack, needle []uint16, from int) int {
	for start := from; start+len(needle) <= len(haystack); start++ {
		e.check()
		found := true
		for j, u := range needle {
			if j&1023 == 0 {
				e.check()
			}
			if haystack[start+j] != u {
				found = false
				break
			}
		}
		if found {
			return start
		}
	}
	return -1
}
func (e evaluator) replaceString(source, old, replacement string) string {
	units, needle, repl := javaUTF16(source), javaUTF16(old), javaUTF16(replacement)
	out := []uint16{}
	pos := 0
	if len(needle) == 0 {
		for _, u := range units {
			e.check()
			out = append(out, repl...)
			out = append(out, u)
		}
		out = append(out, repl...)
		return javaFromUTF16(out)
	}
	for {
		index := e.unitIndex(units, needle, pos)
		if index < 0 {
			out = append(out, units[pos:]...)
			break
		}
		out = append(out, units[pos:index]...)
		out = append(out, repl...)
		pos = index + len(needle)
	}
	return javaFromUTF16(out)
}
func (e evaluator) splitString(source, separator string) []any {
	units, needle := javaUTF16(source), javaUTF16(separator)
	out := []any{}
	pos := 0
	if len(needle) == 0 {
		out = append(out, "")
		for _, u := range units {
			e.check()
			out = append(out, javaFromUTF16([]uint16{u}))
		}
		return append(out, "")
	}
	for {
		index := e.unitIndex(units, needle, pos)
		if index < 0 {
			out = append(out, javaFromUTF16(units[pos:]))
			break
		}
		out = append(out, javaFromUTF16(units[pos:index]))
		pos = index + len(needle)
	}
	return out
}
func (e evaluator) reverseString(source string) string {
	units := javaUTF16(source)
	out := make([]uint16, 0, len(units))
	for i := len(units) - 1; i >= 0; i-- {
		e.check()
		u := units[i]
		if u >= 0xdc00 && u <= 0xdfff && i > 0 && units[i-1] >= 0xd800 && units[i-1] <= 0xdbff {
			out = append(out, units[i-1], u)
			i--
		} else {
			out = append(out, u)
		}
	}
	return javaFromUTF16(out)
}

func parseJavaLong(value string) (int64, error) {
	var out strings.Builder
	for i, u := range javaUTF16(value) {
		if i == 0 && (u == '+' || u == '-') {
			out.WriteByte(byte(u))
			continue
		}
		digit, ok := javastring.Digit(rune(u))
		if !ok {
			return 0, strconv.ErrSyntax
		}
		out.WriteByte('0' + byte(digit))
	}
	return strconv.ParseInt(out.String(), 10, 64)
}

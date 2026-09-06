package query

import (
	"strconv"
	"strings"
	"unicode/utf16"
	"unicode/utf8"
)

// Java strings may retain isolated UTF-16 surrogates. Internally these are
// preserved in WTF-8; only the final UTF-8 wire conversion replaces them with
// '?', matching the JVM UTF-8 encoder used by the source server.
func javaUTF16(s string) []uint16 {
	out := []uint16{}
	for i := 0; i < len(s); {
		if i+2 < len(s) && s[i] == 0xed && s[i+1] >= 0xa0 && s[i+1] <= 0xbf && s[i+2]&0xc0 == 0x80 {
			out = append(out, uint16(s[i]&15)<<12|uint16(s[i+1]&63)<<6|uint16(s[i+2]&63))
			i += 3
			continue
		}
		r, n := utf8.DecodeRuneInString(s[i:])
		i += n
		if r > 0xffff {
			a, b := utf16.EncodeRune(r)
			out = append(out, uint16(a), uint16(b))
		} else {
			out = append(out, uint16(r))
		}
	}
	return out
}
func javaFromUTF16(units []uint16) string {
	var out strings.Builder
	for i := 0; i < len(units); i++ {
		u := units[i]
		if u >= 0xd800 && u <= 0xdbff && i+1 < len(units) && units[i+1] >= 0xdc00 && units[i+1] <= 0xdfff {
			out.WriteRune(utf16.DecodeRune(rune(u), rune(units[i+1])))
			i++
			continue
		}
		if utf16.IsSurrogate(rune(u)) {
			out.WriteByte(0xe0 | byte(u>>12))
			out.WriteByte(0x80 | byte(u>>6&63))
			out.WriteByte(0x80 | byte(u&63))
		} else {
			out.WriteRune(rune(u))
		}
	}
	return out.String()
}
func javaWireString(s string) string {
	if utf8.ValidString(s) {
		return s
	}
	units := javaUTF16(s)
	for i, u := range units {
		if u >= 0xd800 && u <= 0xdbff && i+1 < len(units) && units[i+1] >= 0xdc00 && units[i+1] <= 0xdfff {
			continue
		}
		if u >= 0xdc00 && u <= 0xdfff && i > 0 && units[i-1] >= 0xd800 && units[i-1] <= 0xdbff {
			continue
		}
		if utf16.IsSurrogate(rune(u)) {
			units[i] = '?'
		}
	}
	return string(utf16.Decode(units))
}
func (e evaluator) javaCase(s string, upper bool) string {
	points := javaCodePoints(s)
	var boundaries map[int]bool
	if !upper && strings.ContainsRune(s, 'Σ') {
		boundaries = e.javaWordBoundaries(points)
	}
	var result strings.Builder
	for i, r := range points {
		e.check()
		mapping := javaLower
		if upper {
			mapping = javaUpper
		}
		if !upper && r == 'Σ' {
			before, after := false, false
			for j := i; j > 0 && !boundaries[j]; {
				j--
				if javaCased[points[j]] {
					before = true
					break
				}
			}
			if before {
				for j := i + 1; j < len(points) && !boundaries[j]; j++ {
					if javaCased[points[j]] {
						after = true
						break
					}
				}
			}
			if before && !after {
				result.WriteRune('ς')
				continue
			}
		}
		if replacement, ok := mapping[r]; ok {
			result.WriteString(replacement)
		} else if utf16.IsSurrogate(r) {
			result.WriteString(javaFromUTF16([]uint16{uint16(r)}))
		} else {
			result.WriteRune(r)
		}
	}
	return result.String()
}

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

func javaCodePoints(s string) []rune {
	units := javaUTF16(s)
	result := []rune{}
	for i := 0; i < len(units); i++ {
		u := units[i]
		if u >= 0xd800 && u <= 0xdbff && i+1 < len(units) && units[i+1] >= 0xdc00 && units[i+1] <= 0xdfff {
			result = append(result, utf16.DecodeRune(rune(u), rune(units[i+1])))
			i++
		} else {
			result = append(result, rune(u))
		}
	}
	return result
}

// The category/state data is extracted from Java 17's Locale.ROOT word
// iterator. This generic DFA preserves its word-boundary behavior for Sigma.
func (e evaluator) javaWordBoundaries(points []rune) map[int]bool {
	boundaries := map[int]bool{0: true, len(points): true}
	category := func(r rune) int {
		lo, hi := 0, len(javaWordWordCategories)
		for lo+1 < hi {
			mid := (lo + hi) / 2
			if javaWordWordCategories[mid][0] <= int(r) {
				lo = mid
			} else {
				hi = mid
			}
		}
		return javaWordWordCategories[lo][1]
	}
	for start := 0; start < len(points); {
		result, lookahead, state := start+1, 0, 1
		position := start
		for position < len(points) && points[position] != 0xffff && state != 0 {
			e.check()
			cat := category(points[position])
			if cat != -1 {
				state = javaWordStateTable[state*javaWordNumCategories+cat]
			}
			if javaWordLookaheadStates[state] {
				if javaWordEndStates[state] {
					result = lookahead
				} else {
					lookahead = position + 1
				}
			} else if javaWordEndStates[state] {
				result = position + 1
			}
			position++
		}
		if (position >= len(points) || points[position] == 0xffff) && lookahead == len(points) {
			result = lookahead
		}
		if result <= start {
			result = start + 1
		}
		boundaries[result] = true
		start = result
	}
	return boundaries
}
func parseJavaLong(value string) (int64, error) {
	var out strings.Builder
	for i, u := range javaUTF16(value) {
		if i == 0 && (u == '+' || u == '-') {
			out.WriteByte(byte(u))
			continue
		}
		digit, ok := javaDigits[rune(u)]
		if !ok {
			return 0, strconv.ErrSyntax
		}
		out.WriteByte('0' + byte(digit))
	}
	return strconv.ParseInt(out.String(), 10, 64)
}

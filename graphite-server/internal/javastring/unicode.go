package javastring

import (
	"strings"
	"unicode/utf16"
)

// Case applies Java 17 Locale.ROOT casing and its contextual word boundaries.
// A non-nil check is invoked at the same cancellation points as query execution.
func Case(s string, upper bool, check func()) string {
	if check == nil {
		check = func() {}
	}
	// Java ROOT lowercase is byte-local for an entirely ASCII input. Validate
	// the complete input first so non-ASCII contextual mappings still use the
	// original implementation. Preserve one callback per consumed code point.
	if !upper {
		ascii, changed := true, false
		for i := 0; i < len(s); i++ {
			if s[i] >= 0x80 {
				ascii = false
				break
			}
			changed = changed || s[i] >= 'A' && s[i] <= 'Z'
		}
		if ascii {
			if !changed {
				for i := 0; i < len(s); i++ {
					check()
				}
				return s
			}
			var result strings.Builder
			result.Grow(len(s))
			for i := 0; i < len(s); i++ {
				check()
				c := s[i]
				if c >= 'A' && c <= 'Z' {
					c += 'a' - 'A'
				}
				result.WriteByte(c)
			}
			return result.String()
		}
	}
	points := CodePoints(s)
	var boundaries map[int]bool
	if !upper && strings.ContainsRune(s, 'Σ') {
		boundaries = wordBoundaries(points, check)
	}
	var result strings.Builder
	for i, r := range points {
		check()
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
			result.WriteString(FromUTF16([]uint16{uint16(r)}))
		} else {
			result.WriteRune(r)
		}
	}
	return result.String()
}

// The category/state data is extracted from Java 17's Locale.ROOT word
// iterator. This generic DFA preserves its word-boundary behavior for Sigma.
func wordBoundaries(points []rune, check func()) map[int]bool {
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
			check()
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

// Lower applies Java 17 Locale.ROOT lowercase mappings.
func Lower(s string) string { return Case(s, false, nil) }

// Upper applies Java 17 Locale.ROOT uppercase mappings.
func Upper(s string) string { return Case(s, true, nil) }

// Digit returns Java 17 Character.digit(r, 10).
func Digit(r rune) (int, bool) { digit, ok := javaDigits[r]; return digit, ok }

// Whitespace matches Kotlin Char.isWhitespace: Java whitespace or space-char.
func Whitespace(r rune) bool { return javaWhitespace[r] }

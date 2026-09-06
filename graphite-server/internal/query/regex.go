package query

import (
	"errors"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/javaregex"
)

type compiledRegex struct {
	literal *string
	prefix  bool
	ranges  [][2]uint16
	pattern *javaregex.Pattern
}
type regexLRU struct {
	entries map[string]compiledRegex
	order   []string
}

func regexLiteral(pattern string) (string, bool) {
	const meta = `\.^$|?*+()[]{}`
	var result strings.Builder
	for i := 0; i < len(pattern); i++ {
		c := pattern[i]
		if c == '\\' {
			i++
			if i == len(pattern) || !strings.ContainsRune(meta, rune(pattern[i])) {
				return "", false
			}
			result.WriteByte(pattern[i])
		} else {
			if strings.ContainsRune(meta, rune(c)) {
				return "", false
			}
			result.WriteByte(c)
		}
	}
	return result.String(), true
}
func asciiRanges(pattern string) [][2]uint16 {
	ranges := [][2]uint16{}
	ascii := func(c byte) bool {
		return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_'
	}
	for i := 0; i < len(pattern); {
		if i+3 >= len(pattern) || pattern[i] != '[' || !ascii(pattern[i+1]) {
			return nil
		}
		start, end := pattern[i+1], pattern[i+1]
		i += 2
		if pattern[i] == '-' {
			if i+1 >= len(pattern) || !ascii(pattern[i+1]) {
				return nil
			}
			end = pattern[i+1]
			i += 2
		}
		if start > end || i+1 >= len(pattern) || pattern[i] != ']' || pattern[i+1] != '+' {
			return nil
		}
		i += 2
		for _, old := range ranges {
			if uint16(start) <= old[1] && uint16(end) >= old[0] {
				return nil
			}
		}
		ranges = append(ranges, [2]uint16{uint16(start), uint16(end)})
	}
	if len(ranges) == 0 {
		return nil
	}
	return ranges
}
func (e evaluator) compileRegex(pattern string) compiledRegex {
	e.check()
	if e.regexes != nil {
		if compiled, ok := e.regexes.entries[pattern]; ok {
			for i, k := range e.regexes.order {
				if k == pattern {
					e.regexes.order = append(append(e.regexes.order[:i], e.regexes.order[i+1:]...), pattern)
					break
				}
			}
			return compiled
		}
	}
	compiled := compiledRegex{prefix: strings.HasSuffix(pattern, ".*")}
	literalSource := pattern
	if compiled.prefix {
		literalSource = pattern[:len(pattern)-2]
	}
	if literal, ok := regexLiteral(literalSource); ok {
		compiled.literal = &literal
	} else if ranges := asciiRanges(pattern); ranges != nil {
		compiled.ranges = ranges
	} else {
		parsed, err := javaregex.CompileContext(e.ctx, pattern)
		if err != nil {
			regexError(err)
		}
		compiled.pattern = parsed
	}
	if e.regexes != nil {
		if len(e.regexes.order) == 256 {
			delete(e.regexes.entries, e.regexes.order[0])
			e.regexes.order = e.regexes.order[1:]
		}
		e.regexes.entries[pattern] = compiled
		e.regexes.order = append(e.regexes.order, pattern)
	}
	return compiled
}
func regexError(err error) {
	var syntax *javaregex.SyntaxError
	if errors.As(err, &syntax) {
		functionError("PatternSyntaxException", javaWireString(syntax.Error()))
	}
	var unsupported *javaregex.UnsupportedError
	if errors.As(err, &unsupported) {
		fail(unsupported.Error())
	}
	panic(err)
}
func (e evaluator) regexMatch(pattern, value string) bool {
	compiled := e.compileRegex(pattern)
	if compiled.literal != nil {
		text, literal := javaUTF16(value), javaUTF16(*compiled.literal)
		if len(text) < len(literal) || !compiled.prefix && len(text) != len(literal) {
			return false
		}
		for i, u := range literal {
			e.check()
			if text[i] != u {
				return false
			}
		}
		if compiled.prefix {
			for _, u := range text[len(literal):] {
				e.check()
				switch u {
				case '\n', '\r', 0x85, 0x2028, 0x2029:
					return false
				}
			}
		}
		e.check()
		return true
	}
	if compiled.ranges != nil {
		units := javaUTF16(value)
		index := 0
		for _, r := range compiled.ranges {
			start := index
			for index < len(units) && units[index] >= r[0] && units[index] <= r[1] {
				e.check()
				index++
			}
			if index == start {
				return false
			}
		}
		e.check()
		return index == len(units)
	}
	matched, err := compiled.pattern.MatchesContext(e.ctx, value)
	if err != nil {
		regexError(err)
	}
	e.check()
	return matched
}

package cypher

import (
	"fmt"
	"strconv"
	"strings"
	"unicode/utf16"
)

type token struct {
	kind, text string
	pos, end   int
}
type ParseError struct {
	Position int
	Message  string
}

func (e *ParseError) Error() string {
	return fmt.Sprintf("Cypher parse error at byte %d: %s", e.Position, e.Message)
}
func stringValue(t token) (string, error) {
	s := t.text[1 : len(t.text)-1]
	quote := t.text[0]
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c == quote && i+1 < len(s) && s[i+1] == quote {
			b.WriteByte(c)
			i++
			continue
		}
		if c != '\\' || i+1 >= len(s) {
			b.WriteByte(c)
			continue
		}
		i++
		switch s[i] {
		case '\\', '\'', '"':
			b.WriteByte(s[i])
		case 'n':
			b.WriteByte('\n')
		case 'r':
			b.WriteByte('\r')
		case 't':
			b.WriteByte('\t')
		case 'b':
			b.WriteByte('\b')
		case 'u':
			if i+4 >= len(s) {
				return "", &ParseError{t.pos + i, "incomplete unicode escape"}
			}
			n, e := strconv.ParseUint(s[i+1:i+5], 16, 16)
			if e != nil {
				return "", &ParseError{t.pos + i, "invalid unicode escape"}
			}
			i += 4
			r := rune(n)
			if utf16.IsSurrogate(r) && i+6 < len(s) && s[i+1:i+3] == "\\u" {
				m, e := strconv.ParseUint(s[i+3:i+7], 16, 16)
				if e == nil && utf16.DecodeRune(r, rune(m)) != '\uFFFD' {
					r = utf16.DecodeRune(r, rune(m))
					i += 6
				}
			}
			b.WriteRune(r)
		default:
			b.WriteByte('\\')
			b.WriteByte(s[i])
		}
	}
	return b.String(), nil
}

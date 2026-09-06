package cypher

import (
	"fmt"
	"strconv"
	"strings"
)

func numberValue(t token) (any, error) {
	s := t.text
	lo := strings.ToLower(s)
	if strings.HasPrefix(lo, "0x") || strings.HasPrefix(lo, "0o") {
		base := 16
		if lo[1] == 'o' {
			base = 8
		}
		n, e := strconv.ParseInt(s[2:], base, 64)
		if e != nil {
			return nil, &ParseError{t.pos, fmt.Sprintf("For input string: %q under radix %d", s[2:], base)}
		}
		return n, nil
	}
	if strings.ContainsAny(s, ".eE") {
		n, e := strconv.ParseFloat(s, 64)
		if e != nil {
			if value, ok := e.(*strconv.NumError); !ok || value.Err != strconv.ErrRange {
				return nil, &ParseError{t.pos, "invalid floating point literal"}
			}
		}
		return n, nil
	}
	n, e := strconv.ParseInt(s, 10, 64)
	if e != nil {
		return nil, &ParseError{t.pos, fmt.Sprintf("For input string: %q", s)}
	}
	if n >= -2147483648 && n <= 2147483647 {
		return int32(n), nil
	}
	return n, nil
}

package query

import (
	"strings"
	"unicode/utf8"
)

// stringPredicate receives evaluated string operands. Both callers retain the
// original operand checks before entering this unchanged matching algorithm.
func (e evaluator) stringPredicate(l, r, operator string) bool {
	op := strings.TrimPrefix(operator, "NOT ")
	var result bool
	e.check()
	// With two well-formed strings, a UTF-16 match cannot start or end
	// inside a surrogate pair. UTF-8 byte matches have the same complete
	// code-point boundaries. Isolated units still require the old path.
	if utf8.ValidString(l) && utf8.ValidString(r) {
		switch op {
		case "STARTS WITH":
			result = strings.HasPrefix(l, r)
		case "ENDS WITH":
			result = strings.HasSuffix(l, r)
		case "CONTAINS":
			result = strings.Contains(l, r)
		}
		e.check()
	} else {
		switch op {
		case "STARTS WITH":
			result = e.unitIndex(javaUTF16(l), javaUTF16(r), 0) == 0
		case "ENDS WITH":
			left, right := javaUTF16(l), javaUTF16(r)
			result = len(left) >= len(right) && e.unitIndex(left, right, len(left)-len(right)) >= 0
		case "CONTAINS":
			result = e.unitIndex(javaUTF16(l), javaUTF16(r), 0) >= 0
		}
	}
	if op != operator {
		return !result
	}
	return result
}

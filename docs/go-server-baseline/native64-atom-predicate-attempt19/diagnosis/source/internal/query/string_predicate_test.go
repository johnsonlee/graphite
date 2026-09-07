package query

import (
	"context"
	"errors"
	"fmt"
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"strings"
	"testing"
	"unicode/utf8"
)

func TestStringPredicatesMainJVMOracle(t *testing.T) {
	testFunctionsOracle(t, "testdata/string-predicate-jvm-oracle.json", "../store/testdata/jvm-v3")
}

// Arbitrary malformed Go strings have no corresponding Java input bytes. For
// those cases the established UTF16 decoding, rather than byte matching, stays.
func TestStringPredicateEncodingBoundaries(t *testing.T) {
	values := []string{"", "a", "abc", "甲中文乙", "中文", "日本語", "한국어", "😀", "a😀b", "😀😁", "😁", "é", "e\u0301", "\u0301", "\x00", "�", "\xed\xa0\xbd", "\xed\xb8\x80", "\xed\xa0\xbd\xed\xb8\x80", "\xed\xb8\x80\xed\xa0\xbd", "\xff", "a\xc0\xafb", "\xf0\x9f", "\x80"}
	e := evaluator{ctx: context.Background()}
	for _, left := range values {
		for _, right := range values {
			for _, op := range stringPredicateOps {
				want := utf16PredicateDefinition(left, right, op)
				got := e.binary(cypher.Binary{Left: cypher.Literal{Value: left}, Op: op, Right: cypher.Literal{Value: right}}, nil)
				if got != want {
					t.Fatalf("%q %s %q (valid %v/%v): got %v, want %v", left, op, right, utf8.ValidString(left), utf8.ValidString(right), got, want)
				}
			}
		}
	}
}

var stringPredicateOps = []string{"STARTS WITH", "ENDS WITH", "CONTAINS", "NOT STARTS WITH", "NOT ENDS WITH", "NOT CONTAINS"}

func utf16PredicateDefinition(left, right, op string) bool {
	l, r := javaUTF16(left), javaUTF16(right)
	at := func(i int) bool {
		if i < 0 || i+len(r) > len(l) {
			return false
		}
		for j := range r {
			if l[i+j] != r[j] {
				return false
			}
		}
		return true
	}
	base := strings.TrimPrefix(op, "NOT ")
	var result bool
	switch base {
	case "STARTS WITH":
		result = at(0)
	case "ENDS WITH":
		result = at(len(l) - len(r))
	case "CONTAINS":
		for i := 0; i <= len(l); i++ {
			if at(i) {
				result = true
				break
			}
		}
	}
	if base != op {
		return !result
	}
	return result
}
func TestStringPredicateParametersRetainUTF16Units(t *testing.T) {
	for _, c := range []struct {
		name, left, right        string
		prefix, suffix, contains bool
	}{
		{"high-half", "😀", "\xed\xa0\xbd", true, false, true},
		{"low-half", "😀", "\xed\xb8\x80", false, true, true},
		{"wtf8-pair", "\xed\xa0\xbd\xed\xb8\x80", "😀", true, true, true},
		{"replacement-distinct", "�", "\xed\xa0\xbd", false, false, false},
		{"malformed-retained", "\xff", "�", true, true, true},
	} {
		t.Run(c.name, func(t *testing.T) {
			result, err := Execute(context.Background(), nil, "RETURN $l STARTS WITH $r AS prefix, $l ENDS WITH $r AS suffix, $l CONTAINS $r AS contains", map[string]any{"l": c.left, "r": c.right}, -1)
			if err != nil {
				t.Fatal(err)
			}
			if len(result.Rows) != 1 || result.Rows[0]["prefix"] != c.prefix || result.Rows[0]["suffix"] != c.suffix || result.Rows[0]["contains"] != c.contains {
				t.Fatalf("got %#v, want prefix=%v suffix=%v contains=%v", result, c.prefix, c.suffix, c.contains)
			}
		})
	}
}
func TestStringPredicateCancellationAtFastPathBoundaries(t *testing.T) {
	for _, op := range stringPredicateOps {
		for _, cancelAt := range []int{3, 4} {
			t.Run(fmt.Sprintf("%s/%d", op, cancelAt), func(t *testing.T) {
				ctx := newTraversalCancelContext(t, context.Background(), cancelAt)
				e := evaluator{ctx: ctx}
				defer func() {
					failure, ok := recover().(error)
					if !ok || !errors.Is(failure, context.Canceled) || ctx.checks != cancelAt {
						t.Fatalf("got %v at check %d, want canceled at %d", failure, ctx.checks, cancelAt)
					}
				}()
				// Two operand checks precede the fast path's entry/exit checks.
				e.binary(cypher.Binary{Left: cypher.Literal{Value: "甲😀乙"}, Op: op, Right: cypher.Literal{Value: "😀"}}, nil)
				t.Fatal("canceled predicate returned a value")
			})
		}
	}
}
func TestStringPredicateCancellationDuringUTF16Fallback(t *testing.T) {
	ctx := newTraversalCancelContext(t, context.Background(), 6)
	e := evaluator{ctx: ctx}
	defer func() {
		failure, ok := recover().(error)
		if !ok || !errors.Is(failure, context.Canceled) || ctx.checks != 6 {
			t.Fatalf("got %v at check %d, want canceled at 6", failure, ctx.checks)
		}
	}()
	e.binary(cypher.Binary{Left: cypher.Literal{Value: "abcdef\xed\xa0\xbd"}, Op: "CONTAINS", Right: cypher.Literal{Value: "\xed\xa0\xbd"}}, nil)
	t.Fatal("canceled fallback returned a value")
}

package query

import (
	"context"
	"fmt"
	"reflect"
	"slices"
	"strings"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
)

// Exact pre-A19 helper: the source receipt separately checks this body against
// the committed original, rather than assuming the reference stayed unchanged.
func originalDistinctAtomMatches(e evaluator, atom distinctStringAtom, value any) bool {
	s, ok := value.(string)
	if !ok {
		return false
	}
	if atom.lower {
		s = e.javaCase(s, false)
	}
	return e.binary(cypher.Binary{Left: cypher.Literal{Value: s}, Op: atom.op, Right: cypher.Literal{Value: atom.term}}, nil) == true
}

func TestDistinctAtomPredicateEncodingAndFallback(t *testing.T) {
	values := []string{"", "a", "abc", "GET", "甲中文乙", "中文", "한국어", "😀", "a😀b", "😁", "é", "e\u0301", "\x00", "�", "\xed\xa0\xbd", "\xed\xb8\x80", "\xed\xa0\xbd\xed\xb8\x80", "\xed\xb8\x80\xed\xa0\xbd", "\xff", "a\xc0\xafb", "\xf0\x9f", "\x80", "ΟΣ", "οσ", "İ", "I", "\u212a"}
	e := evaluator{ctx: context.Background()}
	for _, lower := range []bool{false, true} {
		for _, left := range values {
			for _, right := range values {
				for _, op := range []string{"CONTAINS", "STARTS WITH", "ENDS WITH", "=", "NOT CONTAINS", "<>"} {
					atom := distinctStringAtom{op: op, term: right, lower: lower}
					got, want := e.distinctAtomMatches(atom, left), originalDistinctAtomMatches(e, atom, left)
					if got != want {
						t.Fatalf("%q %s %q lower=%v: %v vs %v", left, op, right, lower, got, want)
					}
					if op == "CONTAINS" || op == "STARTS WITH" || op == "ENDS WITH" {
						text := left
						if lower {
							text = e.javaCase(text, false)
						}
						if want := utf16PredicateDefinition(text, right, op); got != want {
							t.Fatalf("UTF16 definition: %q %s %q lower=%v: %v vs %v", left, op, right, lower, got, want)
						}
					}
				}
			}
		}
	}
}

func TestDistinctAtomPredicateEveryCancellationBoundary(t *testing.T) {
	for _, input := range []struct{ text, term string }{
		{"getName", "get"},
		{"甲😀乙", "😀"},
		{"abcdef\xed\xa0\xbd", "\xed\xa0\xbd"},
		{strings.Repeat("ABC", 2000), "abc"},
		{"ΟΣ İ", "ο"},
	} {
		for _, lower := range []bool{false, true} {
			for _, op := range []string{"CONTAINS", "STARTS WITH", "ENDS WITH", "=", "NOT CONTAINS", "UNSUPPORTED"} {
				t.Run(fmt.Sprintf("%x/%s/%v", input.text[:min(12, len(input.text))], op, lower), func(t *testing.T) {
					atom := distinctStringAtom{op: op, term: input.term, lower: lower}
					count := newTraversalCancelContext(t, context.Background(), 1<<30)
					findIDCaught(func() { originalDistinctAtomMatches(evaluator{ctx: count}, atom, input.text) })
					if count.checks < 2 {
						t.Fatal("original operand checks not reached", count.checks)
					}
					ats := []int{}
					if count.checks <= 128 {
						for at := 1; at <= count.checks+1; at++ {
							ats = append(ats, at)
						}
					} else {
						lowerCount := newTraversalCancelContext(t, context.Background(), 1<<30)
						if lower {
							(evaluator{ctx: lowerCount}).javaCase(input.text, false)
						}
						n := lowerCount.checks
						for _, at := range []int{1, 2, n / 2, n - 1, n, n + 1, n + 2, n + 3, count.checks - 1, count.checks, count.checks + 1} {
							if at > 0 && at <= count.checks+1 {
								ats = append(ats, at)
							}
						}
						slices.Sort(ats)
						ats = slices.Compact(ats)
						t.Logf("long-input original checks=%d, lowercase=%d, sampled cancellation checkpoints=%v", count.checks, n, ats)
					}
					for _, at := range ats {
						oldCtx := newTraversalCancelContext(t, context.Background(), at)
						newCtx := newTraversalCancelContext(t, context.Background(), at)
						var oldValue, newValue bool
						oldFailure := findIDCaught(func() { oldValue = originalDistinctAtomMatches(evaluator{ctx: oldCtx}, atom, input.text) })
						newFailure := findIDCaught(func() { newValue = (evaluator{ctx: newCtx}).distinctAtomMatches(atom, input.text) })
						if oldValue != newValue || !reflect.DeepEqual(oldFailure, newFailure) || oldCtx.checks != newCtx.checks {
							t.Fatalf("at%d: value %v/%v failure %#v/%#v checks %d/%d", at, oldValue, newValue, oldFailure, newFailure, oldCtx.checks, newCtx.checks)
						}
						if at <= count.checks && (newFailure != context.Canceled || newCtx.Context.Err() != context.Canceled || newCtx.checks != at) {
							t.Fatalf("real cancellation did not fire at%d: %v, %d", at, newFailure, newCtx.checks)
						}
						if at > count.checks && newCtx.Context.Err() != nil {
							t.Fatal("comparison introduced a later checkpoint")
						}
					}
					if _, err := Execute(context.Background(), nil, "RETURN 1 AS fresh", nil, -1); err != nil {
						t.Fatal("canceled helper poisoned fresh request", err)
					}
				})
			}
		}
	}
}

func TestDistinctAtomPredicateNonstringReturnsBeforeCancellation(t *testing.T) {
	for _, value := range []any{nil, true, int32(1), int64(2), float64(3), []any{"get"}, map[string]any{"s": "get"}} {
		for _, lower := range []bool{false, true} {
			ctx := newTraversalCancelContext(t, context.Background(), 1)
			atom := distinctStringAtom{op: "CONTAINS", term: "get", lower: lower}
			if (evaluator{ctx: ctx}).distinctAtomMatches(atom, value) || ctx.checks != 0 || ctx.Context.Err() != nil {
				t.Fatalf("nonstring boundary changed for %#v lower=%v", value, lower)
			}
		}
	}
}

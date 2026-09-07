package query

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"os"
	"testing"
)

// This public expression boundary must remain outside the extracted matcher.
// A nonstring left avoids evaluating the right; a string left evaluates right
// before testing its type. Output is compared again with original eval.go.
func TestReviewStringPredicateOperandOrder(t *testing.T) {
	type namedString string
	throw := cypher.Call{Name: "left", Arguments: []cypher.Expr{cypher.Literal{Value: "x"}, cypher.Literal{Value: "bad"}}}
	records := []any{}
	for _, op := range stringPredicateOps {
		for i, left := range []any{nil, true, int32(1), []any{"x"}, map[string]any{"x": "x"}, namedString("x")} {
			ctx := newTraversalCancelContext(t, context.Background(), 1<<30)
			var got any
			failure := findIDCaught(func() {
				got = (evaluator{ctx: ctx}).binary(cypher.Binary{Left: cypher.Literal{Value: left}, Op: op, Right: throw}, nil)
			})
			if got != nil || failure != nil || ctx.checks != 1 {
				t.Fatal("nonstring left evaluated right", op, i, got, failure, ctx.checks)
			}
			records = append(records, map[string]any{"op": op, "leftCase": i, "result": got, "checks": ctx.checks})
		}
		for i, right := range []any{nil, true, int32(1), namedString("x")} {
			ctx := newTraversalCancelContext(t, context.Background(), 1<<30)
			got := (evaluator{ctx: ctx}).binary(cypher.Binary{Left: cypher.Literal{Value: "x"}, Op: op, Right: cypher.Literal{Value: right}}, nil)
			if got != nil || ctx.checks != 2 {
				t.Fatal("nonstring right entered matcher", op, i, got, ctx.checks)
			}
			records = append(records, map[string]any{"op": op, "rightCase": i, "result": got, "checks": ctx.checks})
		}
		ctx := newTraversalCancelContext(t, context.Background(), 1<<30)
		failure := findIDCaught(func() {
			(evaluator{ctx: ctx}).binary(cypher.Binary{Left: cypher.Literal{Value: "x"}, Op: op, Right: throw}, nil)
		})
		err, ok := failure.(*Error)
		if !ok || err.Class != "ClassCastException" {
			t.Fatalf("right failure suppressed: %s %T %v", op, failure, failure)
		}
		records = append(records, map[string]any{"op": op, "error": err.Class, "message": err.Message, "checks": ctx.checks})
	}
	// A named Go string is not the ordinary string accepted by the private atom.
	for _, op := range []string{"CONTAINS", "STARTS WITH", "ENDS WITH", "=", "UNSUPPORTED"} {
		ctx := newTraversalCancelContext(t, context.Background(), 1)
		if (evaluator{ctx: ctx}).distinctAtomMatches(distinctStringAtom{op: op, term: "x", lower: true}, namedString("x")) || ctx.checks != 0 || ctx.Context.Err() != nil {
			t.Fatal("private nonstring boundary", op)
		}
	}
	if len(records) != 66 {
		t.Fatal(len(records))
	}
	if path := os.Getenv("REVIEW_OPERAND_OUTPUT"); path != "" {
		b, err := json.MarshalIndent(records, "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(path, append(b, '\n'), 0600); err != nil {
			t.Fatal(err)
		}
	}
	t.Log(fmt.Sprintf("%d complete operand-order/error/checkpoint records", len(records)))
}

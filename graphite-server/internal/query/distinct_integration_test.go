package query

import (
	"context"
	"reflect"
	"testing"
)

func TestDistinctIntegrationEmptyStore(t *testing.T) {
	const q = "MATCH (n:CallSite) WHERE n.caller_class CONTAINS 'a' RETURN DISTINCT n.caller_class AS x LIMIT 1"
	got, err := Execute(context.Background(), nil, q, nil, -1)
	if err != nil || !reflect.DeepEqual(got.Columns, []string{"x"}) || len(got.Rows) != 0 {
		t.Fatalf("nil store: %#v, %v", got, err)
	}
	got, err = ExecuteCross(context.Background(), nil, q, nil, -1)
	if err != nil || !reflect.DeepEqual(got.Columns, []string{"x"}) || len(got.Rows) != 0 {
		t.Fatalf("empty sources: %#v, %v", got, err)
	}
}

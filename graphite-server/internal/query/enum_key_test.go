package query

import (
	"context"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestEnumReferenceDistinctMainJVMOracle(t *testing.T) {
	testFunctionsOracle(t, "testdata/enum-key-jvm-oracle.json", "../store/testdata/jvm-v3")
}

func TestEnumReferenceKeysPreserveClassAndNameBoundaries(t *testing.T) {
	parameters := map[string]any{"values": []any{
		store.EnumReference{EnumClass: "A:B", EnumName: "C"},
		store.EnumReference{EnumClass: "A", EnumName: "B:C"},
		store.EnumReference{EnumClass: "A:B", EnumName: "C"},
		store.EnumReference{EnumClass: "A:B", EnumName: "D"},
		store.EnumReference{EnumClass: "A", EnumName: "B:C"},
	}}
	result, err := Execute(context.Background(), nil, "UNWIND $values AS value RETURN DISTINCT value", parameters, -1)
	want := []map[string]any{
		{"value": map[string]any{"enumClass": "A:B", "enumName": "C"}},
		{"value": map[string]any{"enumClass": "A", "enumName": "B:C"}},
		{"value": map[string]any{"enumClass": "A:B", "enumName": "D"}},
	}
	if err != nil || !reflect.DeepEqual(result.Columns, []string{"value"}) || !reflect.DeepEqual(result.Rows, want) {
		t.Fatalf("got %#v, %v; want %#v", result, err, want)
	}
}

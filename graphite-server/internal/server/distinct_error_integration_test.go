package server

import (
	"encoding/json"
	"net/http/httptest"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/query"
)

func TestDistinctIntegrationNullableErrorWire(t *testing.T) {
	for _, tc := range []struct {
		err     *query.Error
		message string
	}{
		{&query.Error{Class: "IndexOutOfBoundsException", NullMessage: true}, "Query execution failed"},
		{&query.Error{Class: "IndexOutOfBoundsException", Message: ""}, ""},
		{&query.Error{Class: "IndexOutOfBoundsException", Message: "required read failed"}, "required read failed"},
	} {
		w := httptest.NewRecorder()
		writeQueryError(w, tc.err)
		var got map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
			t.Fatal(err)
		}
		want := map[string]any{"error": tc.message, "code": "cypher_query_failed"}
		if w.Code != 400 || !reflect.DeepEqual(got, want) {
			t.Fatalf("status %d body %#v want %#v", w.Code, got, want)
		}
	}
}

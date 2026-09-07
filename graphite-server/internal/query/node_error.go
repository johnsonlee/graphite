package query

import (
	"errors"
	"fmt"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// Classify only a node record actually consumed by the evaluator. Index/header,
// truncation and other storage errors keep their existing handling.
func failNodeRead(err error) {
	var tag *store.UnknownNodeTagError
	if errors.As(err, &tag) {
		functionError("IllegalArgumentException", fmt.Sprintf("Unknown node tag: %d", int8(tag.Tag)))
	}
	var reference *store.StringTableReferenceError
	if errors.As(err, &reference) {
		if reference.Index < 0 {
			functionError("IndexOutOfBoundsException", fmt.Sprintf("Index (%d) is negative", reference.Index))
		}
		functionError("IndexOutOfBoundsException", fmt.Sprintf("Index (%d) is greater than or equal to list size (%d)", reference.Index, reference.Size))
	}
	fail(err.Error())
}

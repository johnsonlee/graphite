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
	fail(err.Error())
}

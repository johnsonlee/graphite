package server

import (
	"errors"
	"net/http"
	"strconv"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// Loader errors are explicit 400 bodies in main. Uncaught node/subgraph errors
// instead use Javalin's generic 500 problem response, without decoder details.
func writeNodeTagError(w http.ResponseWriter, status int, err error) bool {
	var tag *store.UnknownNodeTagError
	if !errors.As(err, &tag) {
		return false
	}
	switch status {
	case http.StatusInternalServerError:
		writeServerError(w)
	case http.StatusBadRequest:
		writeJSON(w, status, map[string]any{"error": "Unknown node tag: " + strconv.Itoa(int(int8(tag.Tag)))})
	default:
		return false
	}
	return true
}

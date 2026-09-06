package server

import (
	_ "embed"
	"encoding/json"
	"net/http"
	"strings"
)

// The API document is static in main except for the artifact version. Keep its
// snapshot with source identities instead of maintaining a second schema builder.
//
//go:embed spec/openapi.json
var openAPIDocument []byte

func (s *Server) openAPI(w http.ResponseWriter, r *http.Request) {
	var document map[string]any
	if err := json.Unmarshal(openAPIDocument, &document); err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	version := strings.TrimSpace(s.Version)
	if version == "" {
		version = "unknown"
	}
	document["info"].(map[string]any)["version"] = version
	writeJSON(w, http.StatusOK, document)
}

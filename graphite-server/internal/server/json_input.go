package server

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
)

// Gson's JSON accessors accept numeric/boolean primitives and singleton arrays
// as strings. Null and object access failures are distinct from missing members.
func gsonString(value json.RawMessage) (string, error) {
	value = bytes.TrimSpace(value)
	if len(value) == 0 || bytes.Equal(value, []byte("null")) {
		return "", errors.New("JsonNull")
	}
	switch value[0] {
	case '"':
		var text string
		err := json.Unmarshal(value, &text)
		return text, err
	case '{':
		return "", errors.New("JsonObject")
	case '[':
		var values []json.RawMessage
		if err := json.Unmarshal(value, &values); err != nil {
			return "", err
		}
		if len(values) != 1 {
			return "", fmt.Errorf("Array must have size 1, but has size %d", len(values))
		}
		return gsonString(values[0])
	default:
		return string(value), nil
	}
}

func jsonObject(body []byte) (map[string]json.RawMessage, error) {
	body = bytes.TrimSpace(body)
	if len(body) == 0 {
		return nil, nil
	}
	if !json.Valid(body) {
		var value any
		err := json.Unmarshal(body, &value)
		return nil, err
	}
	if body[0] != '{' {
		var compact bytes.Buffer
		_ = json.Compact(&compact, body)
		return nil, fmt.Errorf("Not a JSON Object: %s", compact.String())
	}
	var object map[string]json.RawMessage
	err := json.Unmarshal(body, &object)
	return object, err
}

type jsonAccessorFailure struct{ error }

func jsonString(object map[string]json.RawMessage, key string) (string, bool) {
	value, ok := object[key]
	if !ok || bytes.Equal(bytes.TrimSpace(value), []byte("null")) {
		return "", false
	}
	text, err := gsonString(value)
	if err != nil {
		panic(jsonAccessorFailure{err})
	}
	return text, true
}

func recoverJSONAccessor(w http.ResponseWriter) {
	if value := recover(); value != nil {
		if err, ok := value.(jsonAccessorFailure); ok {
			writeQueryError(w, err.error)
			return
		}
		panic(value)
	}
}

func writeServerError(w http.ResponseWriter) {
	writeJSON(w, 500, map[string]any{"title": "Server Error", "status": 500, "type": "https://javalin.io/documentation#internalservererrorresponse", "details": map[string]any{}})
}

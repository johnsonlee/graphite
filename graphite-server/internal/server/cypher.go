package server

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"time"

	"github.com/johnsonlee/graphite/graphite-server/internal/query"
)

func (s *Server) cypher(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		writeQueryError(w, err)
		return
	}
	object, bodyErr := jsonObject(body)
	text := r.URL.Query().Get("query")
	_, present := r.URL.Query()["query"]
	if r.Method == http.MethodPost && bodyErr == nil {
		if value, ok := object["query"]; ok {
			if parsed, e := gsonString(value); e == nil {
				text, present = parsed, true
			}
		}
	}
	if !present {
		writeText(w, 400, "Missing 'query' parameter")
		return
	}
	if bodyErr != nil {
		if json.Valid(body) {
			writeServerError(w)
		} else {
			writeQueryError(w, bodyErr)
		}
		return
	}
	timeout, err := clientTimeout(r, object)
	if err != nil {
		writeQueryError(w, err)
		return
	}
	limit := 1000
	if raw := r.URL.Query().Get("limit"); raw != "" {
		if n, err := strconv.ParseInt(raw, 10, 32); err == nil {
			limit = int(n)
		}
	}
	if limit < 0 {
		limit = 0
	}
	if limit > 5000 {
		limit = 5000
	}
	l := s.acquire(w, r)
	if l == nil {
		return
	}
	defer l.Close()
	g, ok := l.Graph.(*NativeGraph)
	if !ok {
		writeQueryError(w, errors.New("Native Cypher graph access is not implemented"))
		return
	}
	encoded, err := s.Guard.Execute(queryContext(r), timeout, func(ctx context.Context) (any, error) {
		result, err := query.Execute(ctx, g.Store, text, nil, limit)
		if err != nil {
			return nil, err
		}
		response := map[string]any{"columns": result.Columns, "rows": result.Rows, "rowCount": len(result.Rows)}
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		return json.Marshal(omitNullFields(response))
	})
	if err != nil {
		writeQueryError(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(200)
	_, _ = w.Write(encoded.([]byte))
}

func clientTimeout(r *http.Request, object map[string]json.RawMessage) (*time.Duration, error) {
	raw := r.URL.Query().Get("timeoutMs")
	_, present := r.URL.Query()["timeoutMs"]
	if b, ok := object["timeoutMs"]; ok && string(b) != "null" {
		present = true
		if err := json.Unmarshal(b, &raw); err != nil {
			raw = string(b)
		}
	}
	if !present {
		return nil, nil
	}
	ms, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || ms <= 0 {
		return nil, errors.New("'timeoutMs' must be a positive integer")
	}
	d := time.Duration(ms) * time.Millisecond
	if ms > int64(time.Duration(1<<63-1)/time.Millisecond) {
		d = time.Duration(1<<63 - 1)
	}
	return &d, nil
}

func writeQueryError(w http.ResponseWriter, err error) {
	var qe *QueryError
	if !errors.As(err, &qe) {
		qe = &QueryError{Message: err.Error(), Code: "cypher_query_failed", Status: 400}
	}
	if qe.Status == 429 {
		w.Header().Set("Retry-After", "1")
	}
	writeJSON(w, qe.Status, qe)
}

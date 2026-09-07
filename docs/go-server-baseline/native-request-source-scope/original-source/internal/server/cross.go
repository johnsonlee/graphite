package server

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/query"
)

func truthy(value string) bool {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "true", "1", "yes", "on":
		return true
	}
	return false
}
func (s *Server) crossCypher(w http.ResponseWriter, r *http.Request) {
	defer recoverJSONAccessor(w)
	body, err := io.ReadAll(r.Body)
	if err != nil {
		writeQueryError(w, err)
		return
	}
	explicit := r.URL.Path == "/api/cypher/graphs"
	object, bodyErr := jsonObject(body)
	if explicit && bodyErr != nil {
		writeQueryError(w, bodyErr)
		return
	}
	get := func(keys ...string) (string, bool) {
		for _, k := range keys {
			if v, ok := jsonString(object, k); ok {
				return v, true
			}
		}
		for _, k := range keys {
			if v, ok := r.URL.Query()[k]; ok && len(v) > 0 {
				return v[0], true
			}
		}
		return "", false
	}
	text, present := r.URL.Query().Get("query"), false
	_, present = r.URL.Query()["query"]
	if explicit && object != nil {
		value, ok := object["query"]
		if !ok {
			writeQueryError(w, errors.New("Missing 'query' parameter"))
			return
		}
		text, err = gsonString(value)
		if err != nil {
			writeQueryError(w, err)
			return
		}
		present = true
	} else if !explicit && bodyErr == nil {
		if value, ok := object["query"]; ok {
			if parsed, e := gsonString(value); e == nil {
				text, present = parsed, true
			}
		}
	}
	text = strings.TrimSpace(text)
	if !present || !explicit && text == "" {
		if explicit {
			writeQueryError(w, errors.New("Missing 'query' parameter"))
		} else {
			writeText(w, 400, "Missing 'query' parameter")
		}
		return
	}
	if bodyErr != nil {
		writeServerError(w)
		return
	}
	limit := boundedInt(r.URL.Query().Get("limit"), 1000, 5000)
	var ids []string
	mode := "cross-graph"
	perGraph := -1
	includeRows := false
	if explicit {
		ids = []string{}
		appendIDs := func(text string) {
			for _, id := range strings.Split(text, ",") {
				id = strings.TrimSpace(id)
				if id != "" {
					ids = append(ids, id)
				}
			}
		}
		if object != nil {
			v, ok := object["graphs"]
			if !ok {
				v = object["graph"]
			}
			if v != nil {
				if len(v) > 0 && v[0] == '[' {
					var list []json.RawMessage
					if err := json.Unmarshal(v, &list); err != nil {
						writeQueryError(w, errors.New("Invalid 'graphs' field"))
						return
					}
					for _, rawID := range list {
						id, err := gsonString(rawID)
						if err != nil {
							writeQueryError(w, err)
							return
						}
						id = strings.TrimSpace(id)
						if id != "" {
							ids = append(ids, id)
						}
					}
				} else {
					if string(v) == "null" || len(v) > 0 && v[0] == '{' {
						writeQueryError(w, errors.New("Invalid 'graphs' field"))
						return
					}
					list, err := gsonString(v)
					if err != nil {
						writeQueryError(w, err)
						return
					}
					appendIDs(list)
				}
			}
		} else {
			for _, key := range []string{"graph", "graphs"} {
				for _, value := range r.URL.Query()[key] {
					appendIDs(value)
				}
			}
		}
		seen := map[string]bool{}
		for _, id := range ids {
			if seen[id] {
				writeQueryError(w, errors.New("Graph ids must be unique"))
				return
			}
			seen[id] = true
		}
		all, _ := get("allGraphs")
		if truthy(all) == (len(ids) > 0) {
			writeQueryError(w, errors.New("Specify exactly one of 'allGraphs=true' or a non-empty 'graphs' list"))
			return
		}
		if truthy(all) {
			ids = nil
		}
		rawMode, _ := get("mode")
		switch strings.ToLower(strings.TrimSpace(rawMode)) {
		case "", "cross-graph", "cross_graph", "crossgraph":
		case "fanout", "fan-out", "fan_out":
			mode = "fanout"
		default:
			writeQueryError(w, fmt.Errorf("Invalid query mode '%s'. Expected 'cross-graph' or 'fanout'", rawMode))
			return
		}
		if raw, ok := get("perGraphLimit", "per_graph_limit"); ok {
			if _, err := strconv.ParseInt(raw, 10, 32); err == nil {
				perGraph = boundedInt(raw, 0, 5000)
			}
		}
		if raw, ok := get("limit"); ok {
			limit = boundedInt(raw, 1000, 5000)
		}
		inc, _ := get("includeGraphRows")
		includeRows = truthy(inc)
	}
	timeout, err := clientTimeout(r, object)
	if err != nil {
		writeQueryError(w, err)
		return
	}
	leases, err := s.Registry.AcquireSelected(ids)
	if err != nil {
		status := 400
		if strings.HasPrefix(err.Error(), "Graph not loaded:") {
			status = 404
		}
		writeError(w, status, err)
		return
	}
	defer closeLeases(leases)
	ids = make([]string, 0, len(leases))
	graphs := make([]query.Graph, 0, len(leases))
	for _, l := range leases {
		g, ok := l.Graph.(*NativeGraph)
		if !ok {
			writeQueryError(w, errors.New("Native graph query is not implemented"))
			return
		}
		ids = append(ids, l.ID)
		graphs = append(graphs, query.Graph{ID: l.ID, Store: g.Store})
	}
	encoded, err := s.Guard.Execute(queryContext(r), timeout, func(ctx context.Context) (any, error) {
		if mode == "cross-graph" && explicit {
			if perGraph >= 0 {
				return nil, errors.New("perGraphLimit is only valid in fanout mode")
			}
			if includeRows {
				return nil, errors.New("includeGraphRows is only valid in fanout mode")
			}
		}
		var response map[string]any
		if mode == "fanout" {
			if perGraph < 0 {
				perGraph = 0
				if len(graphs) > 0 {
					perGraph = (limit + len(graphs) - 1) / len(graphs)
				}
			}
			response, err = fanout(ctx, graphs, text, limit, perGraph, includeRows)
		} else {
			var result query.Result
			result, err = query.ExecuteCross(ctx, graphs, text, nil, limit)
			response = map[string]any{"columns": result.Columns, "rows": result.ResponseRows(), "rowCount": len(result.Rows), "graphCount": len(graphs)}
			if explicit {
				response["mode"] = mode
				response["graphs"] = ids
				response["limit"] = limit
			}
		}
		if err != nil {
			return nil, err
		}
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		return encodeCypherResponse(response)
	})
	if err != nil {
		writeQueryError(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(200)
	_, _ = w.Write(encoded.([]byte))
}

func fanout(ctx context.Context, graphs []query.Graph, text string, limit, perGraph int, includeRows bool) (map[string]any, error) {
	rows := []any{}
	columns := []string{"graphId"}
	seen := map[string]bool{"graphId": true}
	results := []map[string]any{}
	remaining := limit
	truncated := false
	for _, graph := range graphs {
		if remaining <= 0 {
			truncated = true
			break
		}
		result, err := query.Execute(ctx, graph.Store, text, nil, min(perGraph, remaining))
		if err != nil {
			return nil, err
		}
		for _, c := range result.ColumnKeys() {
			if !seen[c] {
				columns = append(columns, c)
				seen[c] = true
			}
		}
		for index, row := range result.Rows {
			row["graphId"] = graph.ID
			rows = append(rows, result.ResponseRow(index))
		}
		remaining -= len(result.Rows)
		truncated = truncated || remaining <= 0
		entry := map[string]any{"graphId": graph.ID, "columns": result.Columns, "rowCount": len(result.Rows)}
		if includeRows {
			entry["rows"] = result.ResponseRows()
		}
		results = append(results, entry)
	}
	return map[string]any{"columns": columns, "rows": rows, "rowCount": len(rows), "graphCount": len(graphs), "queriedGraphCount": len(results), "perGraphLimit": perGraph, "limit": limit, "truncated": truncated, "graphs": results, "mode": "fanout"}, nil
}

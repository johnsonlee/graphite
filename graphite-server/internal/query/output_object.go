package query

import (
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"sort"
	"unicode/utf8"
)

// OutputObject retains distinct Java String keys until the final JSON writer.
// Converting keys to UTF8 before writing can collapse lone surrogates to '?'.
// It is used only where conversion would collide; ordinary maps stay maps.
type OutputObject struct {
	Keys   []string
	Values map[string]any
}

func wireKeyCollision(keys []string) bool {
	if !hasNonUTF8Keys(keys) {
		return false
	}
	seen := map[string]string{}
	for _, key := range keys {
		wire := javaWireString(key)
		if old, ok := seen[wire]; ok && old != key {
			return true
		}
		seen[wire] = key
	}
	return false
}
func hasNonUTF8Keys(keys []string) bool {
	for _, key := range keys {
		if !utf8.ValidString(key) {
			return true
		}
	}
	return false
}
func materializedObject(keys []string, values map[string]any) any {
	if !hasNonUTF8Keys(keys) {
		return values
	}
	if wireKeyCollision(keys) {
		return OutputObject{Keys: keys, Values: values}
	}
	out := make(map[string]any, len(values))
	for key, value := range values {
		out[javaWireString(key)] = value
	}
	return out
}

// ColumnKeys exposes original Java identities for fanout column deduplication.
// Public Columns keeps the historical UTF8-facing result API.
func (r Result) ColumnKeys() []string {
	if r.rawColumns != nil {
		return r.rawColumns
	}
	return r.Columns
}
func (r Result) ResponseRow(index int) any {
	row := r.Rows[index]
	if r.rawRowKeys == nil || r.rawRowKeys[index] == nil {
		return row
	}
	keys := make([]string, 0, len(row))
	seen := map[string]bool{}
	if _, ok := row["graphId"]; ok {
		keys = append(keys, "graphId")
		seen["graphId"] = true
	}
	for _, key := range r.rawRowKeys[index] {
		if _, ok := row[key]; ok && !seen[key] {
			keys = append(keys, key)
			seen[key] = true
		}
	}
	extras := []string{}
	for key := range row {
		if !seen[key] {
			extras = append(extras, key)
		}
	}
	sort.Strings(extras)
	keys = append(keys, extras...)
	return OutputObject{Keys: keys, Values: row}
}
func (r Result) ResponseRows() any {
	if r.rawRowKeys == nil {
		return r.Rows
	}
	rows := make([]any, len(r.Rows))
	for i := range rows {
		rows[i] = r.ResponseRow(i)
	}
	return rows
}

// Escaped literals can introduce Java UTF16 units into generated projection
// names even when every byte of the original query is valid UTF8.
func needsJavaOutputOrder(q *cypher.Query) bool {
	for _, branch := range q.Branches {
		for _, clause := range branch.Clauses {
			projection, ok := clause.(cypher.ProjectionClause)
			if !ok {
				continue
			}
			for _, item := range projection.Items {
				name := item.Alias
				if name == "" {
					name = columnName(item.Expression)
				}
				if !utf8.ValidString(name) {
					return true
				}
			}
		}
	}
	return false
}

package query

import (
	"sort"
	"strconv"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// Graph is one named immutable source in the query union. Joins and aggregates
// execute over all sources once, not as independent per-graph queries.
type Graph struct {
	ID    string
	Store *store.Store
}
type qualifiedNode struct {
	GraphID string
	Graph   *store.Store
	Node    store.Node
}
type qualifiedMethod struct {
	GraphID string
	Method  store.MethodDescriptor
}

const provenanceKey = "\x00graphite.graphIds"

// The namespace can be empty. Qualification is a property of the value type,
// independent of the characters in its graph ID.
func valueGraphID(value any) (string, bool) {
	switch v := value.(type) {
	case *candidateSlot:
		return v.graphID, v.qualified
	case qualifiedEdge:
		return v.GraphID, true
	case pathValue:
		return v.GraphID, v.Qualified
	case qualifiedNode:
		return v.GraphID, true
	case qualifiedMethod:
		return v.GraphID, true
	}
	return "", false
}
func qualifiedProperty(value any, key string) any {
	switch v := value.(type) {
	case qualifiedNode:
		switch key {
		case "graphId":
			return v.GraphID
		case "elementId", "qualifiedId":
			return v.GraphID + ":" + strconv.FormatInt(int64(v.Node.ID), 10)
		}
		return NodeProperty(v.Node, key)
	case qualifiedMethod:
		if key == "graphId" {
			return v.GraphID
		}
		return methodProperty(v.Method, key)
	}
	return nil
}
func provenance(row map[string]any) []string {
	ids, _ := row[provenanceKey].([]string)
	return append([]string{}, ids...)
}
func addProvenance(row map[string]any, ids ...string) {
	all := provenance(row)
	for _, id := range ids {
		found := false
		for _, old := range all {
			if old == id {
				found = true
				break
			}
		}
		if !found {
			all = append(all, id)
		}
	}
	if len(all) > 0 {
		sort.Strings(all)
		row[provenanceKey] = all
	}
}
func mergeProvenance(target, source map[string]any) { addProvenance(target, provenance(source)...) }

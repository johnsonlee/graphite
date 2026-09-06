package query

import (
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"strconv"
	"strings"
)

type qualifiedEdge struct {
	GraphID string
	Graph   *store.Store
	Edge    store.Edge
}
type pathValue struct {
	Qualified    bool
	GraphID      string
	Nodes, Edges []any
}

// edgeIdentity is structural, matching Kotlin edge data-class equality rather
// than pointer identity (including branch comparison fields).
type edgeIdentity struct {
	GraphID, Family, Kind           string
	From, To                        int32
	Virtual, Dynamic, HasComparison bool
	Operator, Comparand             int32
}

func edgeID(value any) edgeIdentity {
	var e store.Edge
	id := edgeIdentity{}
	switch v := value.(type) {
	case store.Edge:
		e = v
	case qualifiedEdge:
		id.GraphID = v.GraphID
		e = v.Edge
	default:
		fail("expected relationship value")
	}
	id.Family = e.Family
	id.Kind = e.Kind
	id.From = e.From
	id.To = e.To
	id.Virtual = e.IsVirtual
	id.Dynamic = e.IsDynamic
	if e.Comparison != nil {
		id.HasComparison = true
		id.Operator = e.Comparison.Operator
		id.Comparand = e.Comparison.ComparandNodeID
	}
	return id
}
func (e evaluator) relationshipBindings(value any) map[edgeIdentity]bool {
	result := map[edgeIdentity]bool{}
	var add func(any)
	add = func(value any) {
		e.check()
		switch v := value.(type) {
		case store.Edge, qualifiedEdge:
			result[edgeID(v)] = true
		case pathValue:
			for _, edge := range v.Edges {
				add(edge)
			}
		case []any:
			for _, entry := range v {
				add(entry)
			}
		}
	}
	add(value)
	return result
}
func edgeType(edge store.Edge) string {
	switch edge.Family {
	case "DataFlowEdge":
		return "DATAFLOW"
	case "CallEdge":
		return "CALL"
	case "TypeEdge":
		return "TYPE"
	case "ControlFlowEdge":
		return "CONTROL_FLOW"
	case "ResourceEdge":
		switch edge.Kind {
		case "OPENS":
			return "RESOURCE_OPEN"
		case "LOADS":
			return "RESOURCE_LOAD"
		case "BUNDLE_CANDIDATE":
			return "RESOURCE_BUNDLE_CANDIDATE"
		case "LOOKUP":
			return "RESOURCE_LOOKUP"
		case "ENUMERATES":
			return "RESOURCE_KEYS"
		}
	}
	return ""
}
func edgeFamily(label string) string {
	switch strings.ToUpper(label) {
	case "DATAFLOW", "DATA_FLOW":
		return "DataFlowEdge"
	case "CALL":
		return "CallEdge"
	case "TYPE":
		return "TypeEdge"
	case "CONTROL_FLOW", "CONTROLFLOW":
		return "ControlFlowEdge"
	case "RESOURCE", "RESOURCE_OPEN", "RESOURCE_LOAD", "RESOURCE_BUNDLE_CANDIDATE", "RESOURCE_LOOKUP", "RESOURCE_KEYS":
		return "ResourceEdge"
	}
	return ""
}
func matchesEdgeType(edge store.Edge, label string) bool {
	family := edgeFamily(label)
	if family == "" || family != edge.Family {
		return false
	}
	return family != "ResourceEdge" || strings.EqualFold(label, "RESOURCE") || strings.EqualFold(label, edgeType(edge))
}
func edgeProperty(edge store.Edge, key string) any {
	if key == "type" {
		return edgeType(edge)
	}
	if edge.Family == "CallEdge" {
		switch key {
		case "virtual":
			return edge.IsVirtual
		case "dynamic":
			return edge.IsDynamic
		}
		return nil
	}
	if key == "kind" {
		return edge.Kind
	}
	return nil
}
func (e evaluator) materializeEdge(value any) map[string]any {
	var edge store.Edge
	var graphID string
	qualified := false
	switch v := value.(type) {
	case store.Edge:
		edge = v
	case qualifiedEdge:
		edge = v.Edge
		graphID = v.GraphID
		qualified = true
	}
	result := map[string]any{"from": edge.From, "to": edge.To}
	if qualified {
		result["graphId"] = graphID
		result["fromElementId"] = graphID + ":" + strconv.FormatInt(int64(edge.From), 10)
		result["toElementId"] = graphID + ":" + strconv.FormatInt(int64(edge.To), 10)
		result["type"] = edgeType(edge)
	}
	if edge.Family == "CallEdge" {
		if qualified {
			result["virtual"] = edge.IsVirtual
			result["dynamic"] = edge.IsDynamic
		} else {
			result["isVirtual"] = edge.IsVirtual
			result["isDynamic"] = edge.IsDynamic
		}
	} else {
		result["kind"] = edge.Kind
	}
	if !qualified && edge.Family == "ControlFlowEdge" {
		result["comparison"] = nil
		if edge.Comparison != nil {
			result["comparison"] = map[string]any{"operator": comparisonName(edge.Comparison.Operator), "comparandNodeId": edge.Comparison.ComparandNodeID}
		}
	}
	return result
}
func pathElements(path pathValue) []any {
	result := make([]any, 0, len(path.Nodes)+len(path.Edges))
	for i, node := range path.Nodes {
		result = append(result, node)
		if i < len(path.Edges) {
			result = append(result, path.Edges[i])
		}
	}
	return result
}

func edgeString(edge store.Edge) string {
	result := edge.Family + "(from=node#" + strconv.FormatInt(int64(edge.From), 10) + ", to=node#" + strconv.FormatInt(int64(edge.To), 10)
	if edge.Family == "CallEdge" {
		result += ", isVirtual=" + strconv.FormatBool(edge.IsVirtual) + ", isDynamic=" + strconv.FormatBool(edge.IsDynamic)
	} else {
		result += ", kind=" + edge.Kind
	}
	if edge.Family == "ControlFlowEdge" {
		result += ", comparison="
		if edge.Comparison == nil {
			result += "null"
		} else {
			result += "BranchComparison(operator=" + comparisonName(edge.Comparison.Operator) + ", comparandNodeId=node#" + strconv.FormatInt(int64(edge.Comparison.ComparandNodeID), 10) + ")"
		}
	}
	return result + ")"
}
func comparisonName(operator int32) string {
	names := [...]string{"EQ", "NE", "LT", "GE", "GT", "LE"}
	if operator < 0 || int(operator) >= len(names) {
		fail("invalid relationship comparison operator")
	}
	return names[operator]
}

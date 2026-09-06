package query

import (
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"sort"
	"unicode/utf8"
)

func (e evaluator) materialize(value any) any {
	e.check()
	switch v := value.(type) {
	case string:
		return javaWireString(v)
	case store.EnumReference:
		return map[string]any{"enumClass": v.EnumClass, "enumName": v.EnumName}
	case orderedMap:
		out := map[string]any{}
		for _, key := range v.Keys {
			out[key] = e.materialize(v.Values[key])
		}
		return materializedObject(v.Keys, out)
	case store.Edge, qualifiedEdge:
		return e.materializeEdge(v)
	case pathValue:
		if v.Qualified {
			return map[string]any{"graphId": v.GraphID, "length": int32(len(v.Edges)), "nodes": e.materialize(v.Nodes), "relationships": e.materialize(v.Edges)}
		}
		return e.materialize(pathElements(v))
	case qualifiedNode:
		materialized := e.materialize(v.Node)
		r, ok := materialized.(map[string]any)
		if !ok {
			object := materialized.(OutputObject)
			r = object.Values
			for _, key := range []string{"graphId", "elementId", "qualifiedId"} {
				if _, exists := r[key]; !exists {
					object.Keys = append(object.Keys, key)
				}
			}
			r["graphId"] = v.GraphID
			r["elementId"] = qualifiedProperty(v, "elementId")
			r["qualifiedId"] = r["elementId"]
			return object
		}
		r["graphId"] = v.GraphID
		r["elementId"] = qualifiedProperty(v, "elementId")
		r["qualifiedId"] = r["elementId"]
		return r
	case qualifiedMethod:
		r := e.materialize(v.Method).(map[string]any)
		r["graphId"] = v.GraphID
		return r
	case store.MethodDescriptor:
		r := map[string]any{}
		for _, k := range []string{"signature", "class", "name", "parameter_types", "return_type"} {
			r[k] = methodProperty(v, k)
		}
		return r
	case store.Node:
		r := map[string]any{"id": v.ID, "type": v.Kind}
		keys := []string{}
		switch v.Kind {
		case "CallSiteNode":
			keys = []string{"callee_class", "callee_name", "caller_class", "caller_name", "line"}
		case "IntConstant", "StringConstant", "LongConstant", "FloatConstant", "DoubleConstant", "BooleanConstant", "NullConstant":
			keys = []string{"value"}
		case "EnumConstant":
			keys = []string{"enum_type", "name", "value"}
		case "LocalVariable":
			keys = []string{"name", "type"}
		case "FieldNode":
			keys = []string{"name", "type", "class", "static"}
		case "ParameterNode":
			keys = []string{"index", "type", "method"}
		case "ReturnNode":
			keys = []string{"method", "actual_type"}
		case "ResourceFileNode":
			keys = []string{"path", "source", "format", "profile"}
		case "ResourceValueNode":
			keys = []string{"path", "key", "value", "format", "profile"}
		case "AnnotationNode":
			keys = []string{"name", "class", "member"}
		}
		for _, k := range keys {
			r[k] = e.materialize(NodeProperty(v, k))
		}
		if v.Kind == "AnnotationNode" {
			invalid := false
			for key, value := range v.Values {
				r[key] = e.materialize(value)
				invalid = invalid || !utf8.ValidString(key)
			}
			if !invalid {
				return r
			}
			order := append([]string{"id", "type"}, keys...)
			seen := map[string]bool{}
			for _, key := range order {
				seen[key] = true
			}
			for _, key := range v.ValueOrder {
				if !seen[key] {
					order = append(order, key)
					seen[key] = true
				}
			}
			extras := []string{}
			for key := range v.Values {
				if !seen[key] {
					extras = append(extras, key)
				}
			}
			sort.Strings(extras)
			order = append(order, extras...)
			return materializedObject(order, r)
		}
		return r
	case []any:
		r := make([]any, len(v))
		for i, value := range v {
			r[i] = e.materialize(value)
		}
		return r
	case map[string]any:
		r := make(map[string]any, len(v))
		invalid := false
		for k, value := range v {
			r[k] = e.materialize(value)
			invalid = invalid || !utf8.ValidString(k)
		}
		if !invalid {
			return r
		}
		keys := make([]string, 0, len(r))
		for key := range r {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		return materializedObject(keys, r)
	default:
		return value
	}
}

package query

import (
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"sort"
)

type orderedMap struct {
	Values map[string]any
	Keys   []string
}

func mapValues(v any) any {
	if m, ok := v.(orderedMap); ok {
		return m.Values
	}
	return v
}
func (e evaluator) propertyFunction(value any, keysOnly bool) any {
	var keys []string
	values := map[string]any{}
	switch v := value.(type) {
	case qualifiedNode:
		plain := e.propertyFunction(v.Node, false).(orderedMap)
		keys = append(plain.Keys, "graphId", "elementId", "qualifiedId")
		values = plain.Values
		values["graphId"] = v.GraphID
		values["elementId"] = qualifiedProperty(v, "elementId")
		values["qualifiedId"] = values["elementId"]
	case store.MethodDescriptor, qualifiedMethod:
		keys = []string{"signature", "class", "name", "parameter_types", "return_type"}
		method, ok := v.(store.MethodDescriptor)
		if !ok {
			m := v.(qualifiedMethod)
			method = m.Method
			if m.GraphID != "" {
				keys = append(keys, "graphId")
				values["graphId"] = m.GraphID
			}
		}
		for _, k := range keys {
			if k != "graphId" {
				values[k] = methodProperty(method, k)
			}
		}
	case store.Node:
		keys = []string{"id"}
		switch v.Kind {
		case "CallSiteNode":
			keys = append(keys, "callee_class", "callee_name", "callee_signature", "caller_class", "caller_name", "caller_signature", "line")
		case "IntConstant", "StringConstant", "LongConstant", "FloatConstant", "DoubleConstant", "BooleanConstant", "NullConstant":
			keys = append(keys, "value")
		case "EnumConstant":
			keys = append(keys, "value", "name", "enum_type")
		case "LocalVariable":
			keys = append(keys, "name", "type", "method")
		case "FieldNode":
			keys = append(keys, "name", "type", "class", "static")
		case "ParameterNode":
			keys = append(keys, "index", "type", "method")
		case "ReturnNode":
			keys = append(keys, "method", "actual_type")
		case "ResourceFileNode":
			keys = append(keys, "path", "source", "format", "profile")
		case "ResourceValueNode":
			keys = append(keys, "path", "key", "value", "format", "profile")
		case "AnnotationNode":
			keys = append(keys, "name", "class", "member")
		}
		for _, k := range keys {
			e.check()
			values[k] = NodeProperty(v, k)
		}
		if v.Kind == "AnnotationNode" {
			extra := append([]string{}, v.ValueOrder...)
			if len(extra) == 0 {
				for k := range v.Values {
					extra = append(extra, k)
				}
				sort.Strings(extra)
			}
			for _, k := range extra {
				if _, exists := values[k]; !exists {
					keys = append(keys, k)
				}
				values[k] = v.Values[k]
			}
		}
	default:
		return nil
	}
	if keysOnly {
		out := make([]any, len(keys))
		for i, k := range keys {
			out[i] = k
		}
		return out
	}
	return orderedMap{values, keys}
}

func nodeValueMap(node store.Node) any {
	if len(node.ValueOrder) > 0 {
		return orderedMap{node.Values, node.ValueOrder}
	}
	return node.Values
}

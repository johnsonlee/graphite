package query

import "github.com/johnsonlee/graphite/graphite-server/internal/store"

func (e evaluator) materialize(value any) any {
	e.check()
	switch v := value.(type) {
	case qualifiedNode:
		r := e.materialize(v.Node).(map[string]any)
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
			for k, value := range v.Values {
				r[k] = e.materialize(value)
			}
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
		for k, value := range v {
			r[k] = e.materialize(value)
		}
		return r
	default:
		return value
	}
}

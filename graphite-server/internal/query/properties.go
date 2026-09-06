package query

import (
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"strings"
)

// NodeProperty follows NodePropertyAccessor's specific-property precedence.
func NodeProperty(n store.Node, key string) any {
	if key == "id" {
		return n.ID
	}
	var value any
	switch n.Kind {
	case "CallSiteNode":
		switch key {
		case "callee_class":
			value = n.Callee.DeclaringClass
		case "callee_name":
			value = n.Callee.Name
		case "callee_signature":
			value = n.Callee.Signature()
		case "caller_class":
			value = n.Caller.DeclaringClass
		case "caller_name":
			value = n.Caller.Name
		case "caller_signature":
			value = n.Caller.Signature()
		case "line":
			if n.LineNumber != nil {
				value = *n.LineNumber
			}
		}
	case "IntConstant", "StringConstant", "LongConstant", "FloatConstant", "DoubleConstant", "BooleanConstant":
		if key == "value" {
			value = n.Value
		}
	case "EnumConstant":
		switch key {
		case "value":
			if len(n.EnumArguments) > 0 {
				value = n.EnumArguments[0]
			}
		case "name":
			value = n.EnumName
		case "enum_type":
			value = n.EnumType
		}
	case "LocalVariable":
		switch key {
		case "name":
			value = n.Name
		case "type":
			value = n.Type
		case "method":
			value = n.Method.Signature()
		}
	case "FieldNode":
		switch key {
		case "name":
			value = n.Name
		case "type":
			value = n.Type
		case "class":
			value = n.DeclaringClass
		case "static":
			value = n.IsStatic
		}
	case "ParameterNode":
		switch key {
		case "index":
			value = n.Index
		case "type":
			value = n.Type
		case "method":
			value = n.Method.Signature()
		}
	case "ReturnNode":
		switch key {
		case "method":
			value = n.Method.Signature()
		case "actual_type":
			if n.ActualType != nil {
				value = *n.ActualType
			}
		}
	case "ResourceFileNode", "ResourceValueNode":
		switch key {
		case "path":
			value = n.Path
		case "format":
			value = n.Format
		case "profile":
			if n.Profile != nil {
				value = *n.Profile
			}
		case "source":
			if n.Kind == "ResourceFileNode" {
				value = n.Source
			}
		case "key":
			if n.Kind == "ResourceValueNode" {
				value = n.Key
			}
		case "value":
			if n.Kind == "ResourceValueNode" {
				value = n.Value
			}
		}
	case "AnnotationNode":
		switch key {
		case "name":
			value = n.Name
		case "class":
			value = n.ClassName
		case "member":
			value = n.MemberName
		case "values":
			value = scalarString(nodeValueMap(n))
		default:
			value = n.Values[key]
		}
	}
	if value != nil {
		return value
	}
	if key == "type" {
		return n.Kind
	}
	return nil
}
func matchesLabel(n store.Node, label string) bool {
	label = strings.ToLower(label)
	if label == "node" {
		return true
	}
	if label == "constant" || label == "constantnode" {
		return strings.HasSuffix(n.Kind, "Constant")
	}
	aliases := map[string]string{"callsite": "CallSiteNode", "field": "FieldNode", "parameter": "ParameterNode", "return": "ReturnNode", "resourcefile": "ResourceFileNode", "resourcevalue": "ResourceValueNode", "resource": "ResourceValueNode", "local": "LocalVariable", "annotation": "AnnotationNode"}
	if kind, ok := aliases[label]; ok {
		return n.Kind == kind
	}
	return strings.EqualFold(n.Kind, label)
}

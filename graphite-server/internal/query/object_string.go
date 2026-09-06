package query

import (
	"fmt"
	"hash/fnv"
	"sort"
	"strconv"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func (e evaluator) stringify(value any) string { return objectString(value, e.check) }
func objectString(value any, check func()) string {
	if check != nil {
		check()
	}
	recur := func(v any) string { return objectString(v, check) }
	switch v := value.(type) {
	case nil:
		return "null"
	case string:
		return v
	case bool:
		return strconv.FormatBool(v)
	case int, int32, int64:
		return fmt.Sprint(v)
	case float32:
		return javaFloatString(float64(v), 32)
	case float64:
		return javaFloatString(v, 64)
	case store.EnumReference:
		return v.EnumClass + "." + v.EnumName
	case store.Edge:
		return edgeString(v)
	case store.Node:
		return nodeString(v, recur)
	case store.MethodDescriptor:
		return "MethodValue(graphId=null, method=" + methodString(v) + ")"
	case qualifiedMethod:
		return "MethodValue(graphId=" + v.GraphID + ", method=" + methodString(v.Method) + ")"
	case qualifiedNode:
		return "QualifiedNode(graphId=" + v.GraphID + ", graph=" + graphString(v.Graph) + ", node=" + recur(v.Node) + ")"
	case qualifiedEdge:
		return "QualifiedEdge(graphId=" + v.GraphID + ", graph=" + graphString(v.Graph) + ", edge=" + edgeString(v.Edge) + ")"
	case pathValue:
		prefix := "Path("
		if v.Qualified {
			prefix = "QualifiedPath(graphId=" + v.GraphID + ", "
		}
		return prefix + "nodes=" + recur(v.Nodes) + ", edges=" + recur(v.Edges) + ")"
	case []string:
		items := make([]any, len(v))
		for i, s := range v {
			items[i] = s
		}
		return recur(items)
	case []any:
		items := make([]string, len(v))
		for i, item := range v {
			items[i] = recur(item)
		}
		return "[" + strings.Join(items, ", ") + "]"
	case orderedMap:
		items := make([]string, 0, len(v.Keys))
		for _, k := range v.Keys {
			items = append(items, k+"="+recur(v.Values[k]))
		}
		return "{" + strings.Join(items, ", ") + "}"
	case map[string]any:
		keys := make([]string, 0, len(v))
		for k := range v {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		return recur(orderedMap{v, keys})
	}
	fail(fmt.Sprintf("unsupported JVM object string value %T", value))
	return ""
}
func graphString(graph *store.Store) string {
	hash := fnv.New32a()
	_, _ = hash.Write([]byte(fmt.Sprintf("%p", graph)))
	class := "WebGraphBackedGraph"
	if graph.Mode == "MAPPED" {
		class = "MappedWebGraphBackedGraph"
	}
	return "io.johnsonlee.graphite.webgraph." + class + "@" + strconv.FormatUint(uint64(hash.Sum32()), 16)
}
func typeString(name string) string {
	return "TypeDescriptor(className=" + name + ", typeArguments=[])"
}
func methodString(method store.MethodDescriptor) string {
	args := make([]string, len(method.ParameterTypes))
	for i, v := range method.ParameterTypes {
		args[i] = typeString(v)
	}
	return "MethodDescriptor(declaringClass=" + typeString(method.DeclaringClass) + ", name=" + method.Name + ", parameterTypes=[" + strings.Join(args, ", ") + "], returnType=" + typeString(method.ReturnType) + ")"
}
func nodeString(n store.Node, recur func(any) string) string {
	fields := []string{"id=node#" + strconv.FormatInt(int64(n.ID), 10)}
	optional := func(v *string) string {
		if v == nil {
			return "null"
		}
		return *v
	}
	nodeID := func(v *int32) string {
		if v == nil {
			return "null"
		}
		return "node#" + strconv.FormatInt(int64(*v), 10)
	}
	switch n.Kind {
	case "IntConstant", "StringConstant", "LongConstant", "FloatConstant", "DoubleConstant", "BooleanConstant":
		fields = append(fields, "value="+recur(n.Value))
	case "NullConstant":
	case "EnumConstant":
		fields = append(fields, "enumType="+typeString(n.EnumType), "enumName="+n.EnumName, "constructorArgs="+recur(n.EnumArguments))
	case "LocalVariable":
		fields = append(fields, "name="+n.Name, "type="+typeString(n.Type), "method="+methodString(n.Method))
	case "FieldNode":
		fields = append(fields, "descriptor=FieldDescriptor(declaringClass="+typeString(n.DeclaringClass)+", name="+n.Name+", type="+typeString(n.Type)+")", "isStatic="+recur(n.IsStatic))
	case "ParameterNode":
		fields = append(fields, "index="+recur(n.Index), "type="+typeString(n.Type), "method="+methodString(n.Method))
	case "ReturnNode":
		typ := "null"
		if n.ActualType != nil {
			typ = typeString(*n.ActualType)
		}
		fields = append(fields, "method="+methodString(n.Method), "actualType="+typ)
	case "ResourceFileNode":
		fields = append(fields, "path="+n.Path, "source="+n.Source, "format="+n.Format, "profile="+optional(n.Profile))
	case "ResourceValueNode":
		fields = append(fields, "path="+n.Path, "key="+n.Key, "value="+recur(n.Value), "format="+n.Format, "profile="+optional(n.Profile))
	case "AnnotationNode":
		fields = append(fields, "name="+n.Name, "className="+n.ClassName, "memberName="+n.MemberName, "values="+recur(nodeValueMap(n)))
	case "CallSiteNode":
		line := "null"
		if n.LineNumber != nil {
			line = recur(*n.LineNumber)
		}
		args := make([]string, len(n.Arguments))
		for i, v := range n.Arguments {
			args[i] = nodeID(&v)
		}
		fields = append(fields, "caller="+methodString(n.Caller), "callee="+methodString(n.Callee), "lineNumber="+line, "receiver="+nodeID(n.Receiver), "arguments=["+strings.Join(args, ", ")+"]")
	}
	return n.Kind + "(" + strings.Join(fields, ", ") + ")"
}

package server

import (
	"fmt"
	"math"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/query"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

type NativeGraph struct {
	Store *store.Store
	stats Stats
}

func OpenNativeGraph(path, mode string) (Graph, error) {
	if err := ValidateLoadMode(mode); err != nil {
		return nil, err
	}
	s, err := store.OpenMode(path, mode)
	if err != nil {
		return nil, err
	}
	return &NativeGraph{Store: s, stats: Stats{Nodes: int64(s.NodeCount), Edges: s.EdgeCount, Methods: int64(len(s.Metadata.Methods)), CallSites: int64(len(s.NodesOfKind("CallSiteNode")))}}, nil
}
func (g *NativeGraph) Stats() Stats                   { return g.stats }
func (g *NativeGraph) Close() error                   { return g.Store.Close() }
func (g *NativeGraph) Outgoing(id int32) []store.Edge { return g.Store.Outgoing(id) }
func (g *NativeGraph) Incoming(id int32) []store.Edge { return g.Store.Incoming(id) }
func (g *NativeGraph) Node(id int32) (map[string]any, error) {
	n, err := g.Store.Node(id)
	if err != nil {
		return nil, err
	}
	return nodeMap(n), nil
}
func (g *NativeGraph) Annotations(class, member string) map[string]map[string]any {
	result := g.Store.Metadata.MemberAnnotations[class+"#"+member]
	if result == nil {
		return map[string]map[string]any{}
	}
	return result
}

func simpleName(name string) string { i := strings.LastIndex(name, "."); return name[i+1:] }

// nodeMap is the Explorer's nodeToMap wire format, distinct from Cypher values.
func nodeMap(n store.Node) map[string]any {
	m := map[string]any{"id": n.ID, "type": n.Kind}
	switch n.Kind {
	case "CallSiteNode":
		m["caller"] = n.Caller.Signature()
		m["callee"] = n.Callee.Signature()
		m["label"] = simpleName(n.Callee.DeclaringClass) + "." + n.Callee.Name
	case "IntConstant", "StringConstant", "LongConstant", "FloatConstant", "DoubleConstant", "BooleanConstant":
		m["value"] = n.Value
		label := fmt.Sprint(n.Value)
		switch n.Kind {
		case "StringConstant":
			label = "\"" + label + "\""
		case "LongConstant":
			label += "L"
		case "FloatConstant":
			label = javaFloat(float64(n.Value.(float32)), 32) + "f"
		case "DoubleConstant":
			label = javaFloat(n.Value.(float64), 64) + "d"
		}
		m["label"] = label
	case "NullConstant":
		m["label"] = "null"
	case "EnumConstant":
		m["enumType"] = n.EnumType
		m["enumName"] = n.EnumName
		m["label"] = simpleName(n.EnumType) + "." + n.EnumName
	case "FieldNode":
		m["class"] = n.DeclaringClass
		m["name"] = n.Name
		m["fieldType"] = n.Type
		m["label"] = simpleName(n.DeclaringClass) + "." + n.Name
	case "ParameterNode":
		m["index"] = n.Index
		m["paramType"] = n.Type
		m["method"] = n.Method.Signature()
		m["label"] = fmt.Sprintf("param#%d", n.Index)
	case "ReturnNode":
		m["method"] = n.Method.Signature()
		m["label"] = "return"
	case "LocalVariable":
		m["name"] = n.Name
		m["varType"] = n.Type
		m["method"] = n.Method.Signature()
		m["label"] = n.Name
	case "AnnotationNode":
		m["name"] = n.Name
		m["class"] = n.ClassName
		m["member"] = n.MemberName
		m["label"] = "@" + simpleName(n.Name)
		for k, v := range n.Values {
			m[k] = v
		}
	case "ResourceFileNode", "ResourceValueNode":
		m["path"] = n.Path
		m["format"] = n.Format
		m["profile"] = n.Profile
		if n.Kind == "ResourceFileNode" {
			m["source"] = n.Source
			m["label"] = n.Path
		} else {
			m["key"] = n.Key
			m["value"] = n.Value
			m["label"] = n.Key + "=" + fmt.Sprint(n.Value)
		}
	}
	return m
}

func javaFloat(value float64, bits int) string { return query.FormatJavaFloat(value, bits) }

type wireFloat struct {
	value float64
	bits  int
}

func (f wireFloat) MarshalJSON() ([]byte, error) {
	if math.IsNaN(f.value) || math.IsInf(f.value, 0) {
		return nil, fmt.Errorf("non-finite JSON number")
	}
	return []byte(javaFloat(f.value, f.bits)), nil
}

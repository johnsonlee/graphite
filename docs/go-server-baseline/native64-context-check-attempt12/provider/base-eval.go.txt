package query

import (
	"context"
	"fmt"
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"math"
	"strconv"
	"strings"
	"unicode/utf8"
)

type Error struct {
	Message     string
	Class       string
	NullMessage bool
}

func (e *Error) Error() string {
	if e.NullMessage {
		return "Query execution failed"
	}
	return e.Message
}
func (e *Error) JavaMessage() any {
	if e.NullMessage {
		return nil
	}
	return e.Message
}
func fail(message string) { panic(&Error{Message: message, Class: "CypherException"}) }

type evaluator struct {
	indexFirst bool
	ctx        context.Context
	parameters map[string]any
	graphs     []Graph
	cross      bool
	rowOrders  map[string]rowOrder
	regexes    *regexLRU
}

func (e evaluator) check() {
	if err := e.ctx.Err(); err != nil {
		panic(err)
	}
}
func clone(row map[string]any) map[string]any {
	r := make(map[string]any, len(row)+1)
	for k, v := range row {
		r[k] = v
	}
	return r
}
func asBool(v any) any {
	if b, ok := v.(bool); ok {
		return b
	}
	return nil
}
func (e evaluator) eval(expression cypher.Expr, row map[string]any) any {
	e.check()
	switch x := expression.(type) {
	case nil:
		return nil
	case cypher.Literal:
		return x.Value
	case cypher.Variable:
		return row[x.Name]
	case cypher.Parameter:
		return e.parameters[x.Name]
	case cypher.Property:
		obj := e.eval(x.Object, row)
		switch o := obj.(type) {
		case *candidateSlot:
			return o.property(x.Key)
		case orderedMap:
			return o.Values[x.Key]
		case qualifiedNode, qualifiedMethod:
			return qualifiedProperty(o, x.Key)
		case qualifiedEdge:
			if x.Key == "graphId" {
				return o.GraphID
			}
			return edgeProperty(o.Edge, x.Key)
		case store.Edge:
			return edgeProperty(o, x.Key)
		case pathValue:
			if x.Key == "length" {
				return int32(len(o.Edges))
			}
			if x.Key == "graphId" && o.Qualified {
				return o.GraphID
			}
			return nil
		case store.Node:
			return NodeProperty(o, x.Key)
		case store.MethodDescriptor:
			return methodProperty(o, x.Key)
		case map[string]any:
			return o[x.Key]
		}
		return nil
	case cypher.Unary:
		v := e.eval(x.Operand, row)
		switch x.Op {
		case "IS NULL":
			return v == nil
		case "IS NOT NULL":
			return v != nil
		case "NOT":
			if v == nil {
				return nil
			}
			b, ok := v.(bool)
			if !ok {
				castError(v, "java.lang.Boolean")
			}
			return !b
		case "+", "DISTINCT":
			return v
		case "-":
			if v == nil {
				return nil
			}
			switch n := v.(type) {
			case int32:
				return -n
			case int64:
				return -n
			case int:
				return -n
			case float32:
				return -n
			case float64:
				return -n
			}
			return -toDouble(v)
		}
	case cypher.Binary:
		return e.binary(x, row)
	case cypher.List:
		r := make([]any, len(x.Elements))
		for i, v := range x.Elements {
			r[i] = freezeCandidate(e.eval(v, row))
		}
		return r
	case cypher.Map:
		r := make(map[string]any, len(x.Entries))
		for _, k := range x.Keys {
			r[k] = freezeCandidate(e.eval(x.Entries[k], row))
		}
		return orderedMap{r, append([]string{}, x.Keys...)}
	case cypher.Case:
		v := e.eval(x.Test, row)
		for _, w := range x.Whens {
			condition := e.eval(w.Condition, row)
			if x.Test != nil {
				condition = equal(v, condition)
			}
			if condition == true {
				return e.eval(w.Result, row)
			}
		}
		return e.eval(x.Else, row)
	case cypher.ListComprehension:
		values, ok := e.eval(x.List, row).([]any)
		if !ok {
			return nil
		}
		out := []any{}
		for _, v := range values {
			r := e.cloneRow(row)
			e.bind(r, x.Variable, v)
			if x.Where != nil && e.eval(x.Where, r) != true {
				continue
			}
			if x.Projection != nil {
				v = e.eval(x.Projection, r)
			}
			out = append(out, freezeCandidate(v))
		}
		return out
	case cypher.Predicate:
		values, ok := e.eval(x.List, row).([]any)
		if !ok {
			return nil
		}
		yes, no, unknown := 0, 0, 0
		for _, v := range values {
			r := e.cloneRow(row)
			e.bind(r, x.Variable, v)
			if x.Where != nil {
				v = e.eval(x.Where, r)
			}
			switch asBool(v) {
			case true:
				yes++
			case false:
				no++
			default:
				unknown++
			}
		}
		switch x.Name {
		case "any":
			if yes > 0 {
				return true
			}
			if unknown > 0 {
				return nil
			}
			return false
		case "all":
			if no > 0 {
				return false
			}
			if unknown > 0 {
				return nil
			}
			return true
		case "none":
			if yes > 0 {
				return false
			}
			if unknown > 0 {
				return nil
			}
			return true
		case "single":
			if yes > 1 {
				return false
			}
			if unknown > 0 {
				return nil
			}
			return yes == 1
		}
	case cypher.Index:
		v := e.eval(x.Object, row)
		indexValue := e.eval(x.Index, row)
		_, ok := number(indexValue)
		if !ok {
			return nil
		}
		idx := int(numberInt([]any{indexValue}, 0))
		switch v := v.(type) {
		case []any:
			if idx < 0 {
				idx += len(v)
			}
			if idx >= 0 && idx < len(v) {
				return v[idx]
			}
		case string:
			u := javaUTF16(v)
			if idx < 0 {
				idx += len(u)
			}
			if idx >= 0 && idx < len(u) {
				return javaFromUTF16(u[idx : idx+1])
			}
		}
		return nil
	case cypher.Slice:
		v := e.eval(x.Object, row)
		from := 0
		var size int
		switch v := v.(type) {
		case []any:
			size = len(v)
		case string:
			size = len(javaUTF16(v))
		default:
			return nil
		}
		to := size
		if v := e.eval(x.From, row); v != nil {
			if _, ok := number(v); ok {
				from = int(numberInt([]any{v}, 0))
			}
		}
		if v := e.eval(x.To, row); v != nil {
			if _, ok := number(v); ok {
				to = int(numberInt([]any{v}, 0))
			}
		}
		from = max(0, from)
		to = min(size, to)
		if from > to || from > size || to < 0 {
			if _, isString := v.(string); isString {
				functionError("StringIndexOutOfBoundsException", fmt.Sprintf("begin %d, end %d, length %d", from, to, size))
			}
			functionError("IllegalArgumentException", fmt.Sprintf("fromIndex(%d) > toIndex(%d)", from, to))
		}
		switch v := v.(type) {
		case []any:
			return append([]any{}, v[from:to]...)
		case string:
			u := javaUTF16(v)[from:to]

			return javaFromUTF16(u)
		}
	case cypher.Call:
		args := make([]any, len(x.Arguments))
		for i, a := range x.Arguments {
			args[i] = e.eval(a, row)
		}
		return e.call(x.Name, args)
	}
	fail(fmt.Sprintf("unsupported expression %T", expression))
	return nil
}
func toDouble(v any) float64 {
	if n, ok := number(v); ok {
		return n
	}
	if s, ok := v.(string); ok {
		n, ok := parseJavaDouble(s)
		if ok {
			return n
		}
	}
	return 0
}
func (e evaluator) binary(x cypher.Binary, row map[string]any) any {
	if x.Op == "=~" {
		left, ok := e.eval(x.Left, row).(string)
		if !ok {
			return nil
		}
		pattern, ok := e.eval(x.Right, row).(string)
		if !ok {
			return nil
		}
		return e.regexMatch(pattern, left)
	}
	a := e.eval(x.Left, row)
	switch x.Op {
	case "STARTS WITH", "ENDS WITH", "CONTAINS", "NOT STARTS WITH", "NOT ENDS WITH", "NOT CONTAINS":
		if _, ok := a.(string); !ok {
			return nil
		}
	}
	b := e.eval(x.Right, row)
	switch x.Op {
	case "AND":
		a, b = asBool(a), asBool(b)
		if a == false || b == false {
			return false
		}
		if a == nil || b == nil {
			return nil
		}
		return true
	case "OR":
		a, b = asBool(a), asBool(b)
		if a == true || b == true {
			return true
		}
		if a == nil || b == nil {
			return nil
		}
		return false
	case "XOR":
		a, b = asBool(a), asBool(b)
		if a == nil || b == nil {
			return nil
		}
		return a != b
	case "=":
		return equal(a, b)
	case "<>":
		v := equal(a, b)
		if v == nil {
			return nil
		}
		return v == false
	case "IN":
		list, ok := b.([]any)
		if !ok {
			return nil
		}
		unknown := false
		for _, v := range list {
			e.check()
			eq := equal(a, v)
			if eq == true {
				return true
			}
			unknown = unknown || eq == nil
		}
		if unknown {
			return nil
		}
		return false
	}
	if a == nil || b == nil {
		return nil
	}
	switch x.Op {
	case "STARTS WITH", "ENDS WITH", "CONTAINS", "NOT STARTS WITH", "NOT ENDS WITH", "NOT CONTAINS":
		l, ok := a.(string)
		if !ok {
			return nil
		}
		r, ok := b.(string)
		if !ok {
			return nil
		}
		op := strings.TrimPrefix(x.Op, "NOT ")
		var result bool
		e.check()
		// With two well-formed strings, a UTF-16 match cannot start or end
		// inside a surrogate pair. UTF-8 byte matches have the same complete
		// code-point boundaries. Isolated units still require the old path.
		if utf8.ValidString(l) && utf8.ValidString(r) {
			switch op {
			case "STARTS WITH":
				result = strings.HasPrefix(l, r)
			case "ENDS WITH":
				result = strings.HasSuffix(l, r)
			case "CONTAINS":
				result = strings.Contains(l, r)
			}
			e.check()
		} else {
			switch op {
			case "STARTS WITH":
				result = e.unitIndex(javaUTF16(l), javaUTF16(r), 0) == 0
			case "ENDS WITH":
				left, right := javaUTF16(l), javaUTF16(r)
				result = len(left) >= len(right) && e.unitIndex(left, right, len(left)-len(right)) >= 0
			case "CONTAINS":
				result = e.unitIndex(javaUTF16(l), javaUTF16(r), 0) >= 0
			}
		}
		if op != x.Op {
			return !result
		}
		return result
	case "<":
		return comparePredicate(a, b) < 0
	case ">":
		return comparePredicate(a, b) > 0
	case "<=":
		return comparePredicate(a, b) <= 0
	case ">=":
		return comparePredicate(a, b) >= 0
	case "+":
		if _, ok := a.(string); ok {
			return javaFromUTF16(javaUTF16(e.stringify(a) + e.stringify(b)))
		}
		if _, ok := b.(string); ok {
			return javaFromUTF16(javaUTF16(e.stringify(a) + e.stringify(b)))
		}
		if l, ok := a.([]any); ok {
			out := append([]any{}, l...)
			if r, ok := b.([]any); ok {
				return append(out, r...)
			}
			return append(out, freezeCandidate(b))
		}
		if r, ok := b.([]any); ok {
			return append([]any{freezeCandidate(a)}, r...)
		}
	}
	l, r := toDouble(a), toDouble(b)
	var result float64
	switch x.Op {
	case "+":
		result = l + r
	case "-":
		result = l - r
	case "*":
		result = l * r
	case "/":
		if r == 0 {
			fail("Division by zero")
		}
		result = l / r
	case "%":
		result = math.Mod(l, r)
	case "^":
		result = math.Pow(l, r)
	default:
		fail("unsupported operator " + x.Op)
	}
	sameIntegers := false
	switch a.(type) {
	case int32:
		_, sameIntegers = b.(int32)
	case int64:
		_, sameIntegers = b.(int64)
	case int:
		_, sameIntegers = b.(int)
	}
	if sameIntegers {
		n := jvmLong(result)
		if result == float64(n) {
			return n
		}
	}
	return result
}
func scalarString(v any) string { return objectString(v, nil) }
func (e evaluator) callLegacy(name string, args []any) any {
	if name == "coalesce" {
		for _, v := range args {
			if v != nil {
				return v
			}
		}
		return nil
	}
	v := freezeCandidate(argument(args, 0))
	if name == "graphid" {
		if id := valueGraphID(v); id != "" {
			return id
		}
		return nil
	}
	if n, ok := v.(qualifiedNode); ok {
		if name == "elementid" {
			return qualifiedProperty(n, "elementId")
		}
		if name == "id" {
			return n.Node.ID
		}
		if name == "labels" {
			v = n.Node
		}
	}
	if m, ok := v.(qualifiedMethod); ok {
		if name == "elementid" {
			return m.GraphID + ":Method:" + m.Method.Signature()
		}
		if name == "labels" {
			v = m.Method
		}
	}
	switch name {
	case "exists":
		return v != nil
	case "id":
		if n, ok := v.(store.Node); ok {
			return n.ID
		}
		return nil
	case "elementid":
		if m, ok := v.(store.MethodDescriptor); ok {
			return "Method:" + m.Signature()
		}
		if n, ok := v.(store.Node); ok {
			return strconv.FormatInt(int64(n.ID), 10)
		}
		return nil
	case "labels":
		if _, ok := v.(store.MethodDescriptor); ok {
			return []any{"Method"}
		}
		r := []any{}
		if n, ok := v.(store.Node); ok {
			r = append(r, n.Kind)
			if strings.HasSuffix(n.Kind, "Constant") {
				r = append(r, "Constant")
			}
			if n.Kind == "AnnotationNode" {
				r = append(r, "Annotation")
			}
			if n.Kind == "ResourceFileNode" {
				r = append(r, "ResourceFile")
			}
			if n.Kind == "ResourceValueNode" {
				r = append(r, "ResourceValue", "Resource")
			}
		}
		return r
	case "tolower", "tolowercase":
		if value, ok := v.(string); ok {
			return e.javaCase(value, false)
		}
		return nil
	case "type":
		switch edge := v.(type) {
		case store.Edge:
			return edgeType(edge)
		case qualifiedEdge:
			return edgeType(edge.Edge)
		}
		return nil
	case "nodes", "relationships":
		if path, ok := v.(pathValue); ok {
			if name == "nodes" {
				return path.Nodes
			}
			return path.Edges
		}
		if list, ok := v.([]any); ok {
			result := []any{}
			for _, value := range list {
				e.check()
				switch value.(type) {
				case store.Node, qualifiedNode:
					if name == "nodes" {
						result = append(result, value)
					}
				case store.Edge, qualifiedEdge:
					if name == "relationships" {
						result = append(result, value)
					}
				}
			}
			return result
		}
		return nil
	case "size", "length":
		switch v := v.(type) {
		case pathValue:
			return int32(len(v.Edges))
		case string:
			return int32(len(javaUTF16(v)))
		case []any:
			return int32(len(v))
		}
		return nil
	case "head", "last", "tail":
		list, ok := v.([]any)
		if !ok {
			return nil
		}
		if name == "tail" {
			if len(list) == 0 {
				return []any{}
			}
			out := make([]any, len(list)-1)
			for i, v := range list[1:] {
				e.check()
				out[i] = v
			}
			return out
		}
		if len(list) == 0 {
			return nil
		}
		if name == "last" {
			return list[len(list)-1]
		}
		return list[0]
	case "tostring":
		if v == nil {
			return nil
		}
		return e.stringify(v)
	case "tointeger", "toint":
		if n, ok := integer(v); ok {
			return n
		}
		if n, ok := number(v); ok {
			if math.IsNaN(n) {
				return int64(0)
			}
			if n >= math.MaxInt64 {
				return int64(math.MaxInt64)
			}
			if n <= math.MinInt64 {
				return int64(math.MinInt64)
			}
			return int64(n)
		}
		if s, ok := v.(string); ok {
			n, err := parseJavaLong(s)
			if err == nil {
				return n
			}
		}
		if b, ok := v.(bool); ok {
			if b {
				return int64(1)
			}
			return int64(0)
		}
		return nil
	}
	fail("unsupported function " + name)
	return nil
}

// jvmLong implements Kotlin/Java floating-point-to-long saturation.
func jvmLong(n float64) int64 {
	if math.IsNaN(n) {
		return 0
	}
	if n >= math.MaxInt64 {
		return math.MaxInt64
	}
	if n <= math.MinInt64 {
		return math.MinInt64
	}
	return int64(n)
}

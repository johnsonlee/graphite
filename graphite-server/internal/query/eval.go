package query

import (
	"context"
	"fmt"
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"math"
	"strconv"
	"strings"
	"unicode/utf16"
)

type Error struct{ Message string }

func (e *Error) Error() string { return e.Message }
func fail(message string)      { panic(&Error{Message: message}) }

type evaluator struct {
	ctx        context.Context
	parameters map[string]any
	graphs     []Graph
	cross      bool
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
		v, ok := e.parameters[x.Name]
		if !ok {
			fail("Missing parameter: " + x.Name)
		}
		return v
	case cypher.Property:
		obj := e.eval(x.Object, row)
		switch o := obj.(type) {
		case qualifiedNode, qualifiedMethod:
			return qualifiedProperty(o, x.Key)
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
				fail("NOT requires a boolean")
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
			r[i] = e.eval(v, row)
		}
		return r
	case cypher.Map:
		r := make(map[string]any, len(x.Entries))
		for k, v := range x.Entries {
			r[k] = e.eval(v, row)
		}
		return r
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
			r := clone(row)
			r[x.Variable] = v
			if x.Where != nil && e.eval(x.Where, r) != true {
				continue
			}
			if x.Projection != nil {
				v = e.eval(x.Projection, r)
			}
			out = append(out, v)
		}
		return out
	case cypher.Predicate:
		values, ok := e.eval(x.List, row).([]any)
		if !ok {
			return nil
		}
		yes, no, unknown := 0, 0, 0
		for _, v := range values {
			r := clone(row)
			r[x.Variable] = v
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
		n, ok := number(e.eval(x.Index, row))
		if !ok {
			return nil
		}
		idx := int(n)
		switch v := v.(type) {
		case []any:
			if idx < 0 {
				idx += len(v)
			}
			if idx >= 0 && idx < len(v) {
				return v[idx]
			}
		case string:
			u := utf16.Encode([]rune(v))
			if idx < 0 {
				idx += len(u)
			}
			if idx >= 0 && idx < len(u) {
				if utf16.IsSurrogate(rune(u[idx])) {
					fail("isolated UTF-16 surrogate indexing is not supported yet")
				}
				return string(rune(u[idx]))
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
			size = len(utf16.Encode([]rune(v)))
		default:
			return nil
		}
		to := size
		if n, ok := number(e.eval(x.From, row)); ok {
			from = int(n)
		}
		if n, ok := number(e.eval(x.To, row)); ok {
			to = int(n)
		}
		from = max(0, from)
		to = min(size, to)
		if from > to || from > size || to < 0 {
			fail("slice bounds out of range")
		}
		switch v := v.(type) {
		case []any:
			return append([]any{}, v[from:to]...)
		case string:
			u := utf16.Encode([]rune(v))[from:to]
			if len(u) > 0 && (u[0] >= 0xdc00 && u[0] <= 0xdfff || u[len(u)-1] >= 0xd800 && u[len(u)-1] <= 0xdbff) {
				fail("isolated UTF-16 surrogate slicing is not supported yet")
			}
			return string(utf16.Decode(u))
		}
	case cypher.Call:
		args := make([]any, len(x.Arguments))
		for i, a := range x.Arguments {
			args[i] = e.eval(a, row)
		}
		return e.call(strings.ToLower(x.Name), args)
	}
	fail(fmt.Sprintf("unsupported expression %T", expression))
	return nil
}
func toDouble(v any) float64 {
	if n, ok := number(v); ok {
		return n
	}
	if s, ok := v.(string); ok {
		n, err := strconv.ParseFloat(s, 64)
		if err == nil {
			return n
		}
	}
	return 0
}
func (e evaluator) binary(x cypher.Binary, row map[string]any) any {
	a, b := e.eval(x.Left, row), e.eval(x.Right, row)
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
	case "=~":
		fail("Java regular expression matching is not supported yet")
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
		switch op {
		case "STARTS WITH":
			result = strings.HasPrefix(l, r)
		case "ENDS WITH":
			result = strings.HasSuffix(l, r)
		case "CONTAINS":
			result = strings.Contains(l, r)
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
			return scalarString(a) + scalarString(b)
		}
		if _, ok := b.(string); ok {
			return scalarString(a) + scalarString(b)
		}
		if l, ok := a.([]any); ok {
			out := append([]any{}, l...)
			if r, ok := b.([]any); ok {
				return append(out, r...)
			}
			return append(out, b)
		}
		if r, ok := b.([]any); ok {
			return append([]any{a}, r...)
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
func scalarString(v any) string {
	switch n := v.(type) {
	case nil:
		return "null"
	case string:
		return n
	case bool:
		return strconv.FormatBool(n)
	case int, int32, int64:
		return fmt.Sprint(n)
	case float32:
		return javaFloatString(float64(n), 32)
	case float64:
		return javaFloatString(n, 64)
	}
	fail("JVM object string rendering is not supported yet")
	return ""
}
func (e evaluator) call(name string, args []any) any {
	if name == "coalesce" {
		for _, v := range args {
			if v != nil {
				return v
			}
		}
		return nil
	}
	if len(args) != 1 {
		fail(name + " requires one argument")
	}
	v := args[0]
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
			return strings.ToLower(value)
		}
		return nil
	case "size", "length":
		switch v := v.(type) {
		case string:
			return int32(len(utf16.Encode([]rune(v))))
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
			return append([]any{}, list[1:]...)
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
		return scalarString(v)
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
			n, err := strconv.ParseInt(s, 10, 64)
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

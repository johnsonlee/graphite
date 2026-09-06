package query

import (
	"fmt"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"math"
	"math/big"
	"reflect"
	"sort"
	"strconv"
	"strings"
)

func number(v any) (float64, bool) {
	switch n := v.(type) {
	case int:
		return float64(n), true
	case int32:
		return float64(n), true
	case int64:
		return float64(n), true
	case float32:
		return float64(n), true
	case float64:
		return n, true
	}
	return 0, false
}
func integer(v any) (int64, bool) {
	switch n := v.(type) {
	case int:
		return int64(n), true
	case int32:
		return int64(n), true
	case int64:
		return n, true
	}
	return 0, false
}
func numericText(v any) string {
	switch n := v.(type) {
	case float32:
		return javaFloatString(float64(n), 32)
	case float64:
		return javaFloatString(n, 64)
	default:
		return fmt.Sprint(v)
	}
}
func compareNumbers(a, b any) int {
	if x, ok := integer(a); ok {
		if y, ok := integer(b); ok {
			if x < y {
				return -1
			}
			if x > y {
				return 1
			}
			return 0
		}
	}
	x, _ := number(a)
	y, _ := number(b)
	if math.IsNaN(x) {
		if math.IsNaN(y) {
			return 0
		}
		return 1
	}
	if math.IsNaN(y) {
		return -1
	}
	if math.IsInf(x, 0) || math.IsInf(y, 0) {
		if x < y {
			return -1
		}
		if x > y {
			return 1
		}
		return 0
	}
	l, _ := new(big.Rat).SetString(numericText(a))
	r, _ := new(big.Rat).SetString(numericText(b))
	return l.Cmp(r)
}

// equal is three-valued Cypher equality, including nested lists/maps.
func equal(a, b any) any {
	a, b = mapValues(a), mapValues(b)
	if a == nil || b == nil {
		return nil
	}
	switch x := a.(type) {
	case store.Edge:
		y, ok := b.(store.Edge)
		return ok && edgeID(x) == edgeID(y)
	case qualifiedEdge:
		y, ok := b.(qualifiedEdge)
		return ok && edgeID(x) == edgeID(y)
	case pathValue:
		y, ok := b.(pathValue)
		return ok && x.Qualified == y.Qualified && x.GraphID == y.GraphID && equal(x.Nodes, y.Nodes) == true && equal(x.Edges, y.Edges) == true
	}
	if x, ok := a.(qualifiedNode); ok {
		y, ok := b.(qualifiedNode)
		return ok && x.GraphID == y.GraphID && x.Node.ID == y.Node.ID
	}
	if x, ok := a.(qualifiedMethod); ok {
		y, ok := b.(qualifiedMethod)
		return ok && x.GraphID == y.GraphID && reflect.DeepEqual(x.Method, y.Method)
	}
	if x, ok := number(a); ok {
		if y, ok := number(b); ok {
			if math.IsNaN(x) || math.IsNaN(y) {
				return false
			}
			return compareNumbers(a, b) == 0
		}
	}
	if x, ok := a.([]any); ok {
		if y, ok := b.([]any); ok {
			if len(x) != len(y) {
				return false
			}
			unknown := false
			for i := range x {
				v := equal(x[i], y[i])
				if v == false {
					return false
				}
				unknown = unknown || v == nil
			}
			if unknown {
				return nil
			}
			return true
		}
	}
	if x, ok := a.(map[string]any); ok {
		if y, ok := b.(map[string]any); ok {
			if len(x) != len(y) {
				return false
			}
			unknown := false
			for k, v := range x {
				r, ok := y[k]
				if !ok {
					return false
				}
				e := equal(v, r)
				if e == false {
					return false
				}
				unknown = unknown || e == nil
			}
			if unknown {
				return nil
			}
			return true
		}
	}
	return reflect.DeepEqual(a, b)
}

// compare orders projected values; Cypher relational predicates deliberately
// use a different comparison for qualified node identity strings.
func compare(a, b any) int {
	a, b = mapValues(a), mapValues(b)
	if a == nil {
		if b == nil {
			return 0
		}
		return 1
	}
	if b == nil {
		return -1
	}
	if x, ok := number(a); ok {
		if y, ok := number(b); ok {
			sameFloating := false
			switch a.(type) {
			case float32:
				_, sameFloating = b.(float32)
			case float64:
				_, sameFloating = b.(float64)
			}
			if sameFloating && x == 0 && y == 0 && math.Signbit(x) != math.Signbit(y) {
				if math.Signbit(x) {
					return -1
				}
				return 1
			}
			return compareNumbers(a, b)
		}
	}
	ar, br := orderRank(a), orderRank(b)
	if ar != br {
		if ar < br {
			return -1
		}
		return 1
	}
	if x, ok := a.(string); ok {
		if y, ok := b.(string); ok {
			return compareUTF16(x, y)
		}
	}
	if x, ok := a.(bool); ok {
		if y, ok := b.(bool); ok {
			if x == y {
				return 0
			}
			if !x {
				return -1
			}
			return 1
		}
	}
	if ar == 2 {
		x, y := edgeID(a), edgeID(b)
		if c := compareUTF16(x.GraphID, y.GraphID); c != 0 {
			return c
		}
		if c := compareNumbers(x.From, y.From); c != 0 {
			return c
		}
		if c := compareNumbers(x.To, y.To); c != 0 {
			return c
		}
		return compareUTF16(x.Family, y.Family)
	}
	if ar == 4 {
		return compare(pathElements(a.(pathValue)), pathElements(b.(pathValue)))
	}
	if ar == 1 {
		ag, ai := nodeOrderIdentity(a)
		bg, bi := nodeOrderIdentity(b)
		if c := compareUTF16(ag, bg); c != 0 {
			return c
		}
		if ai < bi {
			return -1
		}
		if ai > bi {
			return 1
		}
		return 0
	}
	if x, ok := a.([]any); ok {
		y := b.([]any)
		for i := 0; i < min(len(x), len(y)); i++ {
			if c := compare(x[i], y[i]); c != 0 {
				return c
			}
		}
		if len(x) < len(y) {
			return -1
		}
		if len(x) > len(y) {
			return 1
		}
		return 0
	}
	if x, ok := a.(map[string]any); ok {
		y := b.(map[string]any)
		if len(x) < len(y) {
			return -1
		}
		if len(x) > len(y) {
			return 1
		}
		ak, bk := []string{}, []string{}
		for k := range x {
			ak = append(ak, k)
		}
		for k := range y {
			bk = append(bk, k)
		}
		less := func(keys []string) func(int, int) bool {
			return func(i, j int) bool { return compareUTF16(keys[i], keys[j]) < 0 }
		}
		sort.Slice(ak, less(ak))
		sort.Slice(bk, less(bk))
		for i := range ak {
			if c := compareUTF16(ak[i], bk[i]); c != 0 {
				return c
			}
		}
		for _, k := range ak {
			if c := compare(x[k], y[k]); c != 0 {
				return c
			}
		}
		return 0
	}
	return compareUTF16(comparableString(a), comparableString(b))
}
func orderRank(value any) int {
	switch value.(type) {
	case map[string]any:
		return 0
	case store.Node, qualifiedNode:
		return 1
	case store.Edge, qualifiedEdge:
		return 2
	case pathValue:
		return 4
	case []any:
		return 3
	case bool:
		return 6
	}
	if _, ok := number(value); ok {
		return 7
	}
	return 5
}
func nodeOrderIdentity(value any) (string, int32) {
	switch n := value.(type) {
	case store.Node:
		return "", n.ID
	case qualifiedNode:
		return n.GraphID, n.Node.ID
	}
	fail("not a node")
	return "", 0
}
func compareUTF16(a, b string) int {
	if a == b {
		return 0
	}
	left, right := javaUTF16(a), javaUTF16(b)
	for i := 0; i < min(len(left), len(right)); i++ {
		if left[i] < right[i] {
			return -1
		}
		if left[i] > right[i] {
			return 1
		}
	}
	if len(left) < len(right) {
		return -1
	}
	return 1
}
func comparableString(value any) string {
	method := func(graphID string, m store.MethodDescriptor) string {
		typ := func(name string) string { return "TypeDescriptor(className=" + name + ", typeArguments=[])" }
		args := make([]string, len(m.ParameterTypes))
		for i, v := range m.ParameterTypes {
			args[i] = typ(v)
		}
		return "MethodValue(graphId=" + graphID + ", method=MethodDescriptor(declaringClass=" + typ(m.DeclaringClass) + ", name=" + m.Name + ", parameterTypes=[" + strings.Join(args, ", ") + "], returnType=" + typ(m.ReturnType) + "))"
	}
	switch v := value.(type) {
	case qualifiedMethod:
		return method(v.GraphID, v.Method)
	case store.MethodDescriptor:
		return method("null", v)
	}
	return scalarString(value)
}
func comparePredicate(a, b any) int {
	if x, ok := a.(qualifiedEdge); ok {
		if y, ok := b.(qualifiedEdge); ok {
			if equal(x, y) == true {
				return 0
			}
			return compareUTF16(x.GraphID+":"+edgeString(x.Edge), y.GraphID+":"+edgeString(y.Edge))
		}
	}

	if x, ok := a.(qualifiedNode); ok {
		if y, ok := b.(qualifiedNode); ok {
			return compareUTF16(x.GraphID+":"+strconv.FormatInt(int64(x.Node.ID), 10), y.GraphID+":"+strconv.FormatInt(int64(y.Node.ID), 10))
		}
	}
	if _, ok := number(a); ok {
		if _, ok := number(b); ok {
			return compareNumbers(a, b)
		}
	}
	if x, ok := a.(bool); ok {
		if y, ok := b.(bool); ok {
			if x == y {
				return 0
			}
			if !x {
				return -1
			}
			return 1
		}
	}
	return compareUTF16(comparableString(a), comparableString(b))
}

// key normalizes numeric types for DISTINCT/GROUP/UNION and distinguishes null
// from missing map entries. Length prefixes prevent concatenation collisions.
func key(v any) string {
	v = mapValues(v)
	if v == nil {
		return "null"
	}
	if n, ok := number(v); ok {
		if math.IsNaN(n) {
			return "num:NaN"
		}
		if math.IsInf(n, 0) {
			return "num:" + numericText(v)
		}
		r, _ := new(big.Rat).SetString(numericText(v))
		return "num:" + r.RatString()
	}
	switch x := v.(type) {
	case store.EnumReference:
		return "enum:" + strconv.Quote(x.EnumClass) + ":" + strconv.Quote(x.EnumName)
	case store.Edge, qualifiedEdge:
		return fmt.Sprintf("edge:%#v:%T", edgeID(x), x)
	case pathValue:
		return fmt.Sprintf("path:%t:%s:%s:%s", x.Qualified, strconv.Quote(x.GraphID), key(x.Nodes), key(x.Edges))
	case qualifiedNode:
		return "qualified-node:" + strconv.Quote(x.GraphID) + ":" + strconv.FormatInt(int64(x.Node.ID), 10)
	case qualifiedMethod:
		return "qualified-method:" + strconv.Quote(x.GraphID) + ":" + key(x.Method)
	case string:
		return "str:" + strconv.Quote(x)
	case bool:
		return fmt.Sprint(x)
	case store.MethodDescriptor:
		return "method:" + x.Signature()
	case store.Node:
		return fmt.Sprintf("node:%d", x.ID)
	case []any:
		s := "list:"
		for _, v := range x {
			k := key(v)
			s += fmt.Sprintf("%d:%s", len(k), k)
		}
		return s
	case map[string]any:
		keys := make([]string, 0, len(x))
		for k := range x {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		s := "map:"
		for _, k := range keys {
			v := key(x[k])
			s += strconv.Quote(k) + fmt.Sprintf("%d:%s", len(v), v)
		}
		return s
	}
	fail(fmt.Sprintf("unsupported value type %T", v))
	return ""
}

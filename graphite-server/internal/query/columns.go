package query

import (
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"strconv"
	"strings"
)

// columnName matches the Kotlin AST's toCypherString naming of projections.
func columnName(expr cypher.Expr) string {
	switch x := expr.(type) {
	case nil:
		return ""
	case cypher.Literal:
		if x.Value == nil {
			return "null"
		}
		switch v := x.Value.(type) {
		case string:
			return "'" + strings.ReplaceAll(v, "'", "\\'") + "'"
		case float64:
			s := strconv.FormatFloat(v, 'f', -1, 64)
			if !strings.Contains(s, ".") {
				s += ".0"
			}
			return s
		}
		return scalarString(x.Value)
	case cypher.Variable:
		return x.Name
	case cypher.Parameter:
		return "$" + x.Name
	case cypher.Property:
		return columnName(x.Object) + "." + x.Key
	case cypher.Binary:
		return columnName(x.Left) + " " + x.Op + " " + columnName(x.Right)
	case cypher.Unary:
		s := columnName(x.Operand)
		switch x.Op {
		case "IS NULL", "IS NOT NULL":
			return s + " " + x.Op
		case "NOT", "DISTINCT":
			return x.Op + " " + s
		}
		return x.Op + s
	case cypher.Call:
		if x.Star {
			return "count(*)"
		}
		args := []string{}
		for _, a := range x.Arguments {
			args = append(args, columnName(a))
		}
		s := ""
		if x.Distinct {
			s = "DISTINCT "
		}
		return x.Name + "(" + s + strings.Join(args, ", ") + ")"
	case cypher.List:
		a := []string{}
		for _, v := range x.Elements {
			a = append(a, columnName(v))
		}
		return "[" + strings.Join(a, ", ") + "]"
	case cypher.Index:
		return columnName(x.Object) + "[" + columnName(x.Index) + "]"
	case cypher.Slice:
		return columnName(x.Object) + "[" + columnName(x.From) + ".." + columnName(x.To) + "]"
	case cypher.Case:
		s := "CASE"
		if x.Test != nil {
			s += " " + columnName(x.Test)
		}
		for _, w := range x.Whens {
			s += " WHEN " + columnName(w.Condition) + " THEN " + columnName(w.Result)
		}
		if x.Else != nil {
			s += " ELSE " + columnName(x.Else)
		}
		return s + " END"
	case cypher.ListComprehension:
		s := "[" + x.Variable + " IN " + columnName(x.List)
		if x.Where != nil {
			s += " WHERE " + columnName(x.Where)
		}
		if x.Projection != nil {
			s += " | " + columnName(x.Projection)
		}
		return s + "]"
	case cypher.Predicate:
		s := x.Name + "(" + x.Variable + " IN " + columnName(x.List)
		if x.Where != nil {
			s += " WHERE " + columnName(x.Where)
		}
		return s + ")"
	case cypher.Map:
		items := make([]string, 0, len(x.Keys))
		for _, k := range x.Keys {
			items = append(items, k+": "+columnName(x.Entries[k]))
		}
		return "{" + strings.Join(items, ", ") + "}"
	}
	fail("unsupported projection name")
	return ""
}

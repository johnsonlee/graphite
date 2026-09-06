package query

import (
	"math"
	"sort"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/javamath"
)

func aggregationName(name string) bool {
	switch strings.ToLower(name) {
	case "count", "sum", "avg", "min", "max", "collect", "percentilecont", "percentiledisc", "stdev", "stdevp":
		return true
	}
	return false
}
func isAggregate(expr cypher.Expr) bool {
	v, ok := expr.(cypher.Call)
	return ok && aggregationName(v.Name)
}

// Main only descends through these expression categories. CASE, lists, unary
// operators and boolean/string operators intentionally do not trigger grouping.
func containsAggregate(expr cypher.Expr) bool {
	switch x := expr.(type) {
	case cypher.Call:
		if isAggregate(x) {
			return true
		}
		for _, a := range x.Arguments {
			if containsAggregate(a) {
				return true
			}
		}
	case cypher.Property:
		return containsAggregate(x.Object)
	case cypher.Predicate:
		return containsAggregate(x.List) || containsAggregate(x.Where)
	case cypher.Binary:
		switch x.Op {
		case "+", "-", "*", "/", "%", "^", "=", "<>", "<", ">", "<=", ">=":
			return containsAggregate(x.Left) || containsAggregate(x.Right)
		}
	case cypher.Unary:
		if x.Op == "DISTINCT" {
			return containsAggregate(x.Operand)
		}
	}
	return false
}
func (e evaluator) evaluateAggregate(expr cypher.Expr, rows []map[string]any) any {
	if x, ok := expr.(cypher.Call); ok && isAggregate(x) {
		return e.aggregate(x, rows)
	}
	if x, ok := expr.(cypher.Unary); ok && x.Op == "DISTINCT" {
		return e.evaluateAggregate(x.Operand, rows)
	}
	row := map[string]any{}
	if len(rows) > 0 {
		row = rows[0]
	}
	return e.eval(expr, row)
}
func (e evaluator) aggregate(c cypher.Call, rows []map[string]any) any {
	if c.Star {
		return int64(len(rows))
	}
	values := []any{}
	seen := map[string]bool{}
	for _, row := range rows {
		e.check()
		var value any
		if len(c.Arguments) > 0 {
			value = e.eval(c.Arguments[0], row)
		} else {
			value = orderedMap{row, e.rowKeys(row)}
		}
		if value == nil {
			continue
		}
		if c.Distinct {
			k := key(value)
			if seen[k] {
				continue
			}
			seen[k] = true
		}
		values = append(values, value)
	}
	name := strings.ToLower(c.Name)
	switch name {
	case "count":
		return int64(len(values))
	case "collect":
		return values
	}
	nums := make([]float64, len(values))
	sum := 0.0
	for i, v := range values {
		e.check()
		nums[i] = toDouble(v)
		sum += nums[i]
	}
	switch name {
	case "sum":
		return sum
	case "avg":
		if len(nums) == 0 {
			return nil
		}
		return sum / float64(len(nums))
	case "min", "max":
		if len(nums) == 0 {
			return nil
		}
		selected := 0
		for i := 1; i < len(nums); i++ {
			e.check()
			comparison := javaDoubleCompare(nums[i], nums[selected])
			if name == "min" && comparison < 0 || name == "max" && comparison > 0 {
				selected = i
			}
		}
		return values[selected]
	case "percentilecont", "percentiledisc":
		if len(nums) == 0 {
			return nil
		}
		sort.SliceStable(nums, func(i, j int) bool { e.check(); return javaDoubleCompare(nums[i], nums[j]) < 0 })
		if name == "percentiledisc" {
			return nums[max(0, int(math.Ceil(0.5*float64(len(nums))))-1)]
		}
		idx := 0.5 * float64(len(nums)-1)
		lower, upper := nums[int(idx)], nums[min(int(idx)+1, len(nums)-1)]
		return lower + (idx-float64(int(idx)))*(upper-lower)
	case "stdev", "stdevp":
		if len(nums) < 2 {
			return nil
		}
		mean := sum / float64(len(nums))
		square := 0.0
		for _, n := range nums {
			e.check()
			square += (n - mean) * (n - mean)
		}
		divisor := len(nums)
		if name == "stdev" {
			divisor--
		}
		return javamath.Sqrt(square / float64(divisor))
	}
	fail("Unknown aggregation: " + c.Name)
	return nil
}
func javaDoubleCompare(a, b float64) int {
	if a < b {
		return -1
	}
	if a > b {
		return 1
	}
	if a == b {
		if a == 0 && math.Signbit(a) != math.Signbit(b) {
			if math.Signbit(a) {
				return -1
			}
			return 1
		}
		return 0
	}
	if math.IsNaN(a) {
		if math.IsNaN(b) {
			return 0
		}
		return 1
	}
	return -1
}

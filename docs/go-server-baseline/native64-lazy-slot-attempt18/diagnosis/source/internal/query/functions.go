package query

import (
	"github.com/johnsonlee/graphite/graphite-server/internal/javamath"
	"math"
	"math/rand"
	"strconv"
	"strings"
	"time"
)

func functionError(class, message string) { panic(&Error{Class: class, Message: message}) }
func argument(args []any, index int) any {
	if index >= len(args) {
		functionError("IndexOutOfBoundsException", "Index "+strconv.Itoa(index)+" out of bounds for length "+strconv.Itoa(len(args)))
	}
	return args[index]
}
func numericArgument(args []any, index int) float64 {
	v := argument(args, index)
	if v == nil {
		functionError("NullPointerException", "null cannot be cast to non-null type kotlin.Number")
	}
	n, ok := number(v)
	if !ok {
		castError(v, "java.lang.Number")
	}
	return n
}
func stringArgument(args []any, index int) string {
	v := argument(args, index)
	if v == nil {
		functionError("NullPointerException", "null cannot be cast to non-null type kotlin.String")
	}
	s, ok := v.(string)
	if !ok {
		castError(v, "java.lang.String")
	}
	return s
}
func javaInt(n float64) int32 {
	if math.IsNaN(n) {
		return 0
	}
	if n >= math.MaxInt32 {
		return math.MaxInt32
	}
	if n <= math.MinInt32 {
		return math.MinInt32
	}
	return int32(n)
}
func numberInt(args []any, index int) int32 {
	v := argument(args, index)
	if n, ok := integer(v); ok {
		return int32(n)
	}
	return javaInt(numericArgument(args, index))
}
func numberLong(args []any, index int) int64 {
	v := argument(args, index)
	if n, ok := integer(v); ok {
		return n
	}
	return jvmLong(numericArgument(args, index))
}
func parseJavaDouble(s string) (float64, bool) {
	s = strings.Trim(s, "\x00\x01\x02\x03\x04\x05\x06\x07\x08\t\n\v\f\r\x0e\x0f\x10\x11\x12\x13\x14\x15\x16\x17\x18\x19\x1a\x1b\x1c\x1d\x1e\x1f ")
	switch s {
	case "NaN", "+NaN", "-NaN":
		return math.NaN(), true
	case "Infinity", "+Infinity":
		return math.Inf(1), true
	case "-Infinity":
		return math.Inf(-1), true
	}
	if strings.ContainsAny(s, "_iInNaA") && !strings.ContainsAny(s, "xX") {
		return 0, false
	}
	if len(s) > 0 && strings.ContainsRune("fFdD", rune(s[len(s)-1])) {
		s = s[:len(s)-1]
	}
	n, err := strconv.ParseFloat(s, 64)
	if err != nil {
		if value, ok := err.(*strconv.NumError); ok && value.Err == strconv.ErrRange {
			return n, true
		}
		return 0, false
	}
	return n, true
}
func (e evaluator) call(name string, args []any) any {
	originalName := name
	name = strings.ToLower(name)
	e.check()
	if aggregationName(name) {
		functionError("CypherAggregationException", "Aggregation function '"+originalName+"' must be used in RETURN or WITH clause")
	}
	if name == "qualifiedid" {
		name = "elementid"
	}
	switch name {
	case "timestamp":
		return time.Now().UnixMilli()
	case "rand":
		return rand.Float64()
	case "pi":
		return math.Pi
	case "e":
		return math.E
	case "coalesce":
		return e.callLegacy(name, args)
	case "range":
		start, end := numberLong(args, 0), numberLong(args, 1)
		step := int64(1)
		if len(args) > 2 {
			step = numberLong(args, 2)
		}
		if step == 0 {
			fail("Step cannot be zero in range()")
		}
		if step == math.MinInt64 {
			functionError("IllegalArgumentException", "Step must be greater than Long.MIN_VALUE to avoid overflow on negation.")
		}
		result := []any{}
		for n := start; (step > 0 && n <= end) || (step < 0 && n >= end); {
			e.check()
			result = append(result, n)
			next := n + step
			if (step > 0 && next < n) || (step < 0 && next > n) {
				break
			}
			n = next
		}
		return result
	}
	known := scalarFunctionNames[name]
	if !known {
		fail("Unknown function: " + originalName)
	}
	value := argument(args, 0)
	switch name {
	case "properties", "keys":
		return e.propertyFunction(value, name == "keys")
	case "tofloat":
		if n, ok := number(value); ok {
			return n
		}
		if s, ok := value.(string); ok {
			if n, ok := parseJavaDouble(s); ok {
				return n
			}
		}
		return nil
	case "toboolean":
		if b, ok := value.(bool); ok {
			return b
		}
		if s, ok := value.(string); ok {
			switch strings.ToLower(s) {
			case "true":
				return true
			case "false":
				return false
			}
		}
		return nil
	case "toupper", "touppercase":
		if s, ok := value.(string); ok {
			return e.javaCase(s, true)
		}
		return nil
	case "trim", "ltrim", "rtrim":
		s, ok := value.(string)
		if !ok {
			return nil
		}
		if name == "ltrim" {
			return strings.TrimLeftFunc(s, func(r rune) bool { return javaWhitespace(r) })
		}
		if name == "rtrim" {
			return strings.TrimRightFunc(s, func(r rune) bool { return javaWhitespace(r) })
		}
		return strings.TrimFunc(s, func(r rune) bool { return javaWhitespace(r) })
	case "replace", "split", "substring", "left", "right":
		s, ok := value.(string)
		if !ok {
			return nil
		}
		switch name {
		case "replace":
			return e.replaceString(s, stringArgument(args, 1), stringArgument(args, 2))
		case "split":
			separator := argument(args, 1)
			if separator != nil {
				if _, ok := separator.(string); !ok {
					functionError("ArrayStoreException", javaClassName(separator))
				}
			}
			return e.splitString(s, stringArgument(args, 1))
		}
		units := javaUTF16(s)
		count := int(numberInt(args, 1))
		start, end := 0, len(units)
		if name == "substring" {
			start = count
			if len(args) > 2 {
				end = min(int(int32(start)+numberInt(args, 2)), len(units))
			}
		} else {
			if count < 0 {
				functionError("IllegalArgumentException", "Requested character count "+strconv.Itoa(count)+" is less than zero.")
			}
			if name == "left" {
				end = min(count, len(units))
			} else {
				start = max(0, len(units)-count)
			}
		}
		if start < 0 || end > len(units) || start > end {
			functionError("StringIndexOutOfBoundsException", "begin "+strconv.Itoa(start)+", end "+strconv.Itoa(end)+", length "+strconv.Itoa(len(units)))
		}
		return javaFromUTF16(units[start:end])
	case "reverse":
		switch v := value.(type) {
		case string:
			return e.reverseString(v)
		case []any:
			r := make([]any, len(v))
			for i := range v {
				e.check()
				r[len(v)-i-1] = v[i]
			}
			return r
		}
		return nil
	case "abs":
		switch v := value.(type) {
		case int32:
			if v < 0 {
				return -v
			}
			return v
		case int64:
			if v < 0 {
				return -v
			}
			return v
		case float32:
			return float32(math.Abs(float64(v)))
		case float64:
			return math.Abs(v)
		}
		return nil
	case "sign":
		d := toDouble(value)
		if d > 0 {
			return int32(1)
		}
		if d < 0 {
			return int32(-1)
		}
		return int32(0)
	case "ceil":
		return math.Ceil(toDouble(value))
	case "floor":
		return math.Floor(toDouble(value))
	case "round":
		return math.Floor(toDouble(value) + 0.5)
	case "sqrt":
		return javamath.Sqrt(toDouble(value))
	case "exp":
		return javamath.Exp(toDouble(value))
	case "log":
		return javamath.Log(toDouble(value))
	case "log10":
		return javamath.Log10(toDouble(value))
	case "sin":
		return javamath.Sin(toDouble(value))
	case "cos":
		return javamath.Cos(toDouble(value))
	case "tan":
		return javamath.Tan(toDouble(value))
	case "asin":
		return javamath.Asin(toDouble(value))
	case "acos":
		return javamath.Acos(toDouble(value))
	case "atan":
		return javamath.Atan(toDouble(value))
	case "atan2":
		return javamath.Atan2(toDouble(value), toDouble(argument(args, 1)))
	case "cot":
		return 1 / javamath.Tan(toDouble(value))
	case "degrees":
		return toDouble(value) * 57.29577951308232
	case "radians":
		return toDouble(value) * 0.017453292519943295
	}
	return e.callLegacy(name, args)
}

var scalarFunctionNames = func() map[string]bool {
	r := map[string]bool{}
	for _, n := range strings.Fields("id elementid qualifiedid graphid coalesce timestamp tointeger toint tofloat toboolean tostring properties keys labels type tolower tolowercase toupper touppercase trim ltrim rtrim replace substring split size length left right reverse head tail last range nodes relationships abs ceil floor round sign rand sqrt exp log log10 e sin cos tan asin acos atan atan2 cot pi degrees radians exists") {
		r[n] = true
	}
	return r
}()

func castError(value any, target string) {
	source := javaClassName(value)
	functionError("ClassCastException", "class "+source+" cannot be cast to class "+target+" ("+source+" and "+target+" are in module java.base of loader 'bootstrap')")
}
func javaClassName(value any) string {
	value = freezeCandidate(value)
	source := "java.lang.Object"
	switch value.(type) {
	case string:
		source = "java.lang.String"
	case bool:
		source = "java.lang.Boolean"
	case int32, int:
		source = "java.lang.Integer"
	case int64:
		source = "java.lang.Long"
	case float32:
		source = "java.lang.Float"
	case float64:
		source = "java.lang.Double"
	case []any:
		source = "java.util.ArrayList"
	case map[string]any, orderedMap:
		source = "java.util.LinkedHashMap"
	}
	return source
}

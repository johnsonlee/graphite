package query

// cypherCountValue follows QueryPipeline.evaluateToInt: Number.toLong then
// clamp to int32, String.toLongOrNull then clamp, and zero for all other values.
// Negative values are permitted here: the literal planner guard tests their
// sign, whereas general SKIP/LIMIT raises at the subsequent drop/take step.
func cypherCountValue(value any) int32 {
	if n, numeric := number(value); numeric {
		return javaInt(n)
	}
	if s, stringValue := value.(string); stringValue {
		if n, err := parseJavaLong(s); err == nil {
			return javaInt(float64(n))
		}
	}
	return 0
}

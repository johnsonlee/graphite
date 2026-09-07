package query

// A probe resolves only strings needed by its already selected visible rows.
// Advance at the original lookup point, retaining the first SID of other wanted
// strings encountered on the way. No table scan precedes the first lookup, and
// no Store read, posting lookup, or projected-field validation is moved earlier.
// The cursor belongs to one synchronous source probe, not the Store or a worker.
type distinctTargetStrings struct {
	table     []string
	wanted    map[string]int32
	next      int
	check     func()
	checkMask int
}

func newDistinctTargetStrings(table []string, plan *indexedDistinctPlan, rows []map[string]any, check func(), checkMask int) *distinctTargetStrings {
	wanted := map[string]int32{}
	for _, row := range rows {
		for i, property := range plan.properties {
			if _, raw := distinctCallSiteProperties[property]; !raw {
				continue
			}
			if value, ok := row[plan.columns[i]].(string); ok {
				wanted[value] = -1
			}
		}
	}
	return &distinctTargetStrings{table: table, wanted: wanted, check: check, checkMask: checkMask}
}

func (s *distinctTargetStrings) find(text string) int32 {
	// Cached and exhausted lookups must still observe cancellation at their
	// original field boundary. Uncached scanning retains periodic checks.
	if len(s.table) > 0 {
		s.check()
	}
	if id, wanted := s.wanted[text]; wanted && id >= 0 {
		return id
	}
	start := s.next
	for s.next < len(s.table) {
		i := s.next
		if i != start && i&s.checkMask == 0 {
			s.check()
		}
		value := s.table[i]
		s.next++
		if id, wanted := s.wanted[value]; wanted && id < 0 {
			s.wanted[value] = int32(i)
		}
		if value == text {
			return int32(i)
		}
	}
	return -1
}

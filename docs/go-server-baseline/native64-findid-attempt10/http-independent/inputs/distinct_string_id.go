package query

import "slices"

// Loaded StringTable.findId on main searches by Java UTF-16 ordering and returns
// the first equal midpoint it visits. Preserve that behavior even for accepted
// serialized tables with duplicate or unsorted entries; do not sort/deduplicate
// or substitute the first linear match. This helper runs at each original lookup
// point, leaving selected-field validation and posting consumption in place.
func (e evaluator) distinctStringTableID(table []string, text string) int32 {
	if len(table) == 0 {
		return -1
	}
	e.check()
	target := javaUTF16(text)
	low, high := 0, len(table)-1
	for low <= high {
		e.check()
		middle := low + (high-low)/2
		comparison := slices.Compare(javaUTF16(table[middle]), target)
		switch {
		case comparison < 0:
			low = middle + 1
		case comparison > 0:
			high = middle - 1
		default:
			return int32(middle)
		}
	}
	return -1
}

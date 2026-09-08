package query

import (
	"fmt"
	"math/bits"
)

const (
	rawProjectionMatcherCapacity = 4096
	serialRawMatcherCapacity     = 65536
)

// A matcher belongs to one raw scan or projection probe. Properties with the same
// transform, operator and expected Java string share it. Collisions replace a
// single slot and only cause another deterministic comparison.
type boundedStringMatcher struct {
	atom   distinctStringAtom
	dense  []byte
	keys   []int32
	values []byte
}

func newBoundedStringMatcher(atom distinctStringAtom, stringCount, capacity int) *boundedStringMatcher {
	capacity = 1 << (bits.Len(uint(max(1, capacity))) - 1)
	m := &boundedStringMatcher{atom: atom}
	if stringCount <= capacity {
		m.dense = make([]byte, stringCount)
	} else {
		m.keys = make([]int32, capacity)
		m.values = make([]byte, capacity)
	}
	return m
}

func sharedStringMatchers(atoms []distinctStringAtom, stringCount, capacity int) []*boundedStringMatcher {
	shared := map[string]*boundedStringMatcher{}
	matchers := make([]*boundedStringMatcher, len(atoms))
	for i, atom := range atoms {
		key := ordinaryStringKey(atom)
		matcher := shared[key]
		if matcher == nil {
			matcher = newBoundedStringMatcher(atom, stringCount, capacity)
			shared[key] = matcher
		}
		matchers[i] = matcher
	}
	return matchers
}

// With main's default worker configuration, only preferred-persisted storage
// and an ordinary 2..39-source consumer carry the serial marker. A generic raw
// fallback under a single-source or split consumer uses a different state array.
func (p *mainStringSourceSpec) boundedSerialRawMatcher(limit int) bool {
	return limit > 0 && limit < int(^uint32(0)>>1) && (p.forcePersisted || p.sourceCount > 1 && p.sourceCount < 40)
}

func (m *boundedStringMatcher) matches(e evaluator, sid int32, read func(int32) (string, error)) bool {
	var state byte
	slot := 0
	if m.dense != nil {
		// Main indexes the dense state array before it reads the string table.
		if sid < 0 || int64(sid) >= int64(len(m.dense)) {
			functionError("ArrayIndexOutOfBoundsException", fmt.Sprintf("Index %d out of bounds for length %d", sid, len(m.dense)))
		}
		state = m.dense[sid]
	} else {
		slot = int((uint32(sid) ^ (uint32(sid) >> 16)) & uint32(len(m.keys)-1))
		if m.keys[slot] == sid+1 {
			state = m.values[slot]
		}
	}
	if state == 2 {
		return true
	}
	if state == 1 {
		return false
	}
	text, err := read(sid)
	failProjectionRead(err)
	matched := e.distinctAtomMatches(m.atom, text)
	state = 1
	if matched {
		state = 2
	}
	if m.dense != nil {
		m.dense[sid] = state
	} else {
		m.keys[slot], m.values[slot] = sid+1, state
	}
	return matched
}

package query

import (
	"context"
	"fmt"
	"slices"
	"strings"
	"sync/atomic"
	"unicode/utf8"

	"github.com/johnsonlee/graphite/graphite-server/internal/javastring"
)

// The caller establishes Split storage and the absence of a retained index.
// Admission completes before creating the buffered consumer or polling a worker.
// This deliberately does not change ordinary/serial distinctCannotMatch.
func (e evaluator) distinctSplitCannotMatch(source Graph, atoms []distinctStringAtom) (bool, bool) {
	callsite := make([]distinctStringAtom, 0, len(atoms))
	for _, atom := range atoms {
		if _, ok := distinctCallSiteProperties[atom.property]; ok {
			callsite = append(callsite, atom)
		}
	}
	if len(callsite) == 0 || len(source.Store.NodesOfKind("CallSiteNode")) < 4096 {
		return false, false
	}
	for _, atom := range callsite {
		if atom.op != "CONTAINS" || len(javaUTF16(atom.term)) < 16 {
			return false, false
		}
	}
	seen := make(map[string]bool, len(callsite))
	accounting := bufferedGraphWork{work: e.work}
	defer accounting.flush()
	for _, atom := range callsite {
		predicate := ordinaryStringKey(atom)
		if seen[predicate] {
			continue
		}
		seen[predicate] = true
		distinctSplitPoll(e.ctx, nil)
		for sid := range source.Store.Strings {
			if sid&1023 == 0 {
				distinctSplitPoll(e.ctx, nil)
			}
			accounting.consume()
			text, err := source.Store.MainMappedString(int32(sid))
			failMainStringRead(err)
			if mainMappedContains(atom, text) {
				return false, true
			}
		}
	}
	return true, true
}

func distinctSplitPoll(ctx context.Context, abort *atomic.Bool) {
	// Keep the short circuit: a sibling failure need not read worker.Err().
	if abort != nil && abort.Load() {
		panic(&Error{Class: "CancellationException", Message: "Mapped string-property scan interrupted", cause: context.Canceled})
	}
	if err := ctx.Err(); err != nil {
		panic(&Error{Class: "CancellationException", Message: "Mapped string-property scan interrupted", cause: err})
	}
}

// Main's shared ByteArray values are monotonic unknown->match/miss. Pack sixteen
// two-bit states into one atomic word so concurrent Go workers have no data race
// and do not serialize predicate evaluation. Duplicate evaluation on a cold SID
// is permitted, as with main's shared arrays; it has no separate work charge.
type distinctSplitStates struct {
	length int
	words  []uint32
}

func (s *distinctSplitStates) get(sid int32) uint32 {
	if sid < 0 || int64(sid) >= int64(s.length) {
		functionError("ArrayIndexOutOfBoundsException", fmt.Sprintf("Index %d out of bounds for length %d", sid, s.length))
	}
	return atomic.LoadUint32(&s.words[int(sid)/16]) >> (uint(sid) % 16 * 2) & 3
}

func (s *distinctSplitStates) put(sid int32, value uint32) {
	word := &s.words[int(sid)/16]
	shift := uint(sid) % 16 * 2
	for {
		old := atomic.LoadUint32(word)
		if old>>shift&3 != 0 || atomic.CompareAndSwapUint32(word, old, old|value<<shift) {
			return
		}
	}
}

type distinctSplitMatchStates struct {
	byPredicate map[string]*distinctSplitStates
}

func newDistinctSplitMatchStates(atoms []distinctStringAtom, stringCount int) *distinctSplitMatchStates {
	result := &distinctSplitMatchStates{byPredicate: make(map[string]*distinctSplitStates)}
	for _, atom := range atoms {
		if _, callsite := distinctCallSiteProperties[atom.property]; !callsite {
			continue
		}
		key := ordinaryStringKey(atom)
		if result.byPredicate[key] == nil {
			result.byPredicate[key] = &distinctSplitStates{length: stringCount, words: make([]uint32, (stringCount+15)/16)}
		}
	}
	return result
}

// Pure storage predicate: work and interruption belong to the scan, not to
// expression evaluation. Preserve UTF16 equality and the original expected case.
func distinctSplitStringMatches(atom distinctStringAtom, text string) bool {
	if atom.op == "CONTAINS" {
		return mainMappedContains(atom, text)
	}
	if atom.lower {
		text = javastring.Case(text, false, nil)
	}
	if utf8.ValidString(text) && utf8.ValidString(atom.term) {
		switch atom.op {
		case "=":
			return text == atom.term
		case "STARTS WITH":
			return strings.HasPrefix(text, atom.term)
		case "ENDS WITH":
			return strings.HasSuffix(text, atom.term)
		}
	}
	actual, expected := javaUTF16(text), javaUTF16(atom.term)
	switch atom.op {
	case "=":
		return slices.Equal(actual, expected)
	case "STARTS WITH":
		return len(expected) <= len(actual) && slices.Equal(actual[:len(expected)], expected)
	case "ENDS WITH":
		return len(expected) <= len(actual) && slices.Equal(actual[len(actual)-len(expected):], expected)
	}
	panic("unsupported compiled Split string predicate")
}

// One actual storage segment. The owner creates one states/abort pair for the
// whole invocation and owns task submission, result ordering, cancellation/join.
// selectedIDs contains the already validated raw SID tuples; nil means no probe.
func (e evaluator) distinctSplitRawRange(source Graph, plan *indexedDistinctPlan, nodeIDs []int32, exact map[int]map[int32]bool, selectedIDs map[string]bool, targetSize int, states *distinctSplitMatchStates, abort *atomic.Bool) []distinctProjectedRow {
	rows := []distinctProjectedRow{}
	if targetSize <= 0 {
		return rows
	}
	defer func() {
		if failure := recover(); failure != nil {
			if abort != nil {
				abort.Store(true)
			}
			panic(failure)
		}
	}()
	accounting := bufferedGraphWork{work: e.work}
	defer accounting.flush()
	seenValues := make(map[string]bool)
	var matchStates []*distinctSplitStates
	if exact == nil {
		matchStates = make([]*distinctSplitStates, len(plan.atoms))
		for i, atom := range plan.atoms {
			if _, callsite := distinctCallSiteProperties[atom.property]; callsite {
				matchStates[i] = states.byPredicate[ordinaryStringKey(atom)]
			}
		}
	}
	for inspected, id := range nodeIDs {
		if inspected&1023 == 0 {
			distinctSplitPoll(e.ctx, abort)
		}
		accounting.consume()
		sids, err := source.Store.MainDistinctProjectionStringIDs(id)
		failMainStringRead(err)
		matched := false
		for atomIndex, atom := range plan.atoms {
			property, callsite := distinctCallSiteProperties[atom.property]
			if !callsite {
				continue
			}
			sid := sids[property]
			if exact != nil {
				matched = exact[property][sid]
			} else {
				cache := matchStates[atomIndex]
				state := cache.get(sid)
				if state == 0 {
					text, err := source.Store.MainMappedString(sid)
					failMainStringRead(err)
					state = 2
					if distinctSplitStringMatches(atom, text) {
						state = 1
					}
					cache.put(sid, state)
				}
				matched = state == 1
			}
			if matched {
				break
			}
		}
		if !matched {
			continue
		}
		if selectedIDs != nil {
			values := make([]any, len(plan.properties))
			for i, property := range plan.properties {
				values[i] = int32(-1)
				if raw, ok := distinctCallSiteProperties[property]; ok {
					values[i] = sids[raw]
				}
			}
			if !selectedIDs[key(values)] {
				continue
			}
		}
		values := make([]any, len(plan.properties))
		row := make(map[string]any, len(plan.properties)+1)
		for i, property := range plan.properties {
			var value any
			if raw, ok := distinctCallSiteProperties[property]; ok {
				value, err = source.Store.MainMappedString(sids[raw])
				failMainStringRead(err)
				values[i] = ordinaryJavaKeyString(value.(string))
			} else if property == "graphId" {
				value = source.ID
			}
			row[plan.columns[i]] = value
		}
		visible := key(values)
		if seenValues[visible] {
			continue
		}
		seenValues[visible] = true
		order, err := source.Store.MainProjectionNodeOrder(id)
		failMainStringRead(err)
		addProvenance(row, source.ID)
		rows = append(rows, distinctProjectedRow{order: order, row: row, storageKey: visible})
		if len(rows) >= targetSize {
			break
		}
	}
	return rows
}

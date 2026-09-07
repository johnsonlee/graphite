package query

import (
	"strconv"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// candidateSlot is a borrowed view of one decoded scan candidate. Only the
// single-node WHERE scanner borrows it; no published row or container may keep
// this pointer. Node reads stay in the original enumeration phase. Properties
// avoid boxing the complete node; operations needing its whole value freeze it.
type candidateSlot struct {
	graph               *store.Store
	graphID             string
	qualified, isMethod bool
	node                store.Node
	method              store.MethodDescriptor
}

func freezeCandidate(value any) any {
	if slot, ok := value.(*candidateSlot); ok {
		if slot.isMethod {
			if slot.qualified {
				return qualifiedMethod{slot.graphID, slot.method}
			}
			return slot.method
		}
		if slot.qualified {
			return qualifiedNode{slot.graphID, slot.graph, slot.node}
		}
		return slot.node
	}
	return value
}

func (s *candidateSlot) matchesLabel(label string) bool {
	if s.isMethod {
		return strings.EqualFold(label, "Method")
	}
	return matchesLabel(s.node, label)
}

func (s *candidateSlot) property(key string) any {
	if s.qualified {
		if key == "graphId" {
			return s.graphID
		}
		if !s.isMethod && (key == "elementId" || key == "qualifiedId") {
			return s.graphID + ":" + strconv.FormatInt(int64(s.node.ID), 10)
		}
	}
	if s.isMethod {
		return methodProperty(s.method, key)
	}
	return NodeProperty(s.node, key)
}

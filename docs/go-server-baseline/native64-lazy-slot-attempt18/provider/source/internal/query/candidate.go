package query

import (
	"strconv"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// candidateSlot is a borrowed view of one decoded scan candidate. The single-node
// WHERE scanner and unregistered generic lazy bindings may borrow it; published
// rows and containers must not keep this pointer. Node reads stay in the original
// enumeration phase. Properties avoid boxing the complete node; whole-value
// operations freeze it.
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

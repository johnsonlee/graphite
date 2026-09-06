package query

import (
	"errors"
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"strings"
)

type matchState struct {
	row          map[string]any
	used         map[edgeIdentity]bool
	current      any
	nodes, edges []any
}

func (e evaluator) match(graph *store.Store, rows []map[string]any, clause cypher.MatchClause) []map[string]any {
	if clause.Where != nil && len(clause.Patterns) == 1 && len(clause.Patterns[0].Nodes) == 1 && len(clause.Patterns[0].Relationships) == 0 && clause.Patterns[0].PathVariable == "" {
		return e.matchSingleNode(graph, rows, clause)
	}
	return e.matchMaterialized(graph, rows, clause)
}
func (e evaluator) matchMaterialized(graph *store.Store, rows []map[string]any, clause cypher.MatchClause) []map[string]any {
	result := []map[string]any{}
	for _, row := range rows {
		e.check()
		matches := []matchState{{row: row}}
		if len(clause.Patterns) > 1 {
			matches[0].used = map[edgeIdentity]bool{}
		}
		for _, pattern := range clause.Patterns {
			next := []matchState{}
			for _, state := range matches {
				next = append(next, e.matchPattern(graph, pattern, state)...)
			}
			matches = next
		}
		accepted := 0
		for _, state := range matches {
			if clause.Where == nil || e.eval(clause.Where, state.row) == true {
				result = append(result, state.row)
				accepted++
			}
		}
		if clause.Optional && accepted == 0 {
			bound := e.cloneRow(row)
			for _, pattern := range clause.Patterns {
				variables := []string{pattern.PathVariable}
				for i, node := range pattern.Nodes {
					variables = append(variables, node.Variable)
					if i < len(pattern.Relationships) {
						variables = append(variables, pattern.Relationships[i].Variable)
					}
				}
				for _, name := range variables {
					if name != "" {
						if _, exists := bound[name]; !exists {
							e.bind(bound, name, nil)
						}
					}
				}
			}
			result = append(result, bound)
		}
	}
	return result
}
func (e evaluator) matchPattern(graph *store.Store, pattern cypher.Pattern, initial matchState) []matchState {
	if len(pattern.Nodes) == 0 {
		return []matchState{initial}
	}
	tracked := initial.used != nil || len(pattern.Relationships) > 1
	if tracked && initial.used == nil {
		initial.used = map[edgeIdentity]bool{}
	}
	reserved := map[edgeIdentity]bool{}
	if tracked {
		for _, rel := range pattern.Relationships {
			for edge := range e.relationshipBindings(initial.row[rel.Variable]) {
				reserved[edge] = true
			}
		}
	}
	states := []matchState{}
	e.nodeCandidates(graph, pattern.Nodes[0], initial.row, func(value any) {
		e.check()
		if !e.matches(value, pattern.Nodes[0], initial.row) {
			return
		}
		bound := e.cloneRow(initial.row)
		if name := pattern.Nodes[0].Variable; name != "" {
			e.bind(bound, name, value)
			if id := valueGraphID(value); id != "" {
				addProvenance(bound, id)
			}
		}
		states = append(states, matchState{row: bound, used: initial.used, current: value, nodes: []any{value}})
	})
	for index, rel := range pattern.Relationships {
		next := []matchState{}
		for _, state := range states {
			next = append(next, e.matchRelationship(graph, state, rel, pattern.Nodes[index+1], reserved)...)
		}
		states = next
	}
	for i := range states {
		if pattern.PathVariable != "" {
			path := e.makePath(states[i].nodes, states[i].edges)
			e.bind(states[i].row, pattern.PathVariable, path)
			if id := valueGraphID(path); id != "" {
				addProvenance(states[i].row, id)
			}
		}
	}
	return states
}
func (e evaluator) nodeCandidates(graph *store.Store, pattern cypher.NodePattern, bindings map[string]any, accept func(any)) {
	e.walkNodeCandidates(graph, pattern, bindings, nil, accept)
}

func (e evaluator) walkNodeCandidates(graph *store.Store, pattern cypher.NodePattern, bindings map[string]any, slot *candidateSlot, accept func(any)) {
	if pattern.Variable != "" {
		if value, bound := bindings[pattern.Variable]; bound {
			accept(value)
			return
		}
	}
	methods := false
	for _, label := range pattern.Labels {
		methods = methods || strings.EqualFold(label, "Method")
	}
	sources := e.graphs
	if !e.cross && graph != nil {
		sources = []Graph{{Store: graph}}
	}
	for _, source := range sources {
		e.check()
		if methods {
			for _, method := range source.Store.Metadata.MethodList {
				if slot != nil {
					slot.graph, slot.graphID, slot.qualified = source.Store, source.ID, e.cross
					slot.isMethod, slot.method = true, method
					accept(slot)
					continue
				}
				if e.cross {
					accept(qualifiedMethod{source.ID, method})
				} else {
					accept(method)
				}
			}
			continue
		}
		for _, id := range source.Store.NodeIDs() {
			e.check()
			node, err := source.Store.Node(id)
			if err != nil {
				fail(err.Error())
			}
			if slot != nil {
				slot.graph, slot.graphID, slot.qualified = source.Store, source.ID, e.cross
				slot.isMethod, slot.node = false, node
				accept(slot)
				continue
			}
			if e.cross {
				accept(qualifiedNode{source.ID, source.Store, node})
			} else {
				accept(node)
			}
		}
	}
}
func (e evaluator) makePath(nodes, edges []any) any {
	path := pathValue{Qualified: e.cross, Nodes: []any{}, Edges: append([]any{}, edges...)}
	for _, node := range nodes {
		switch v := node.(type) {
		case store.Node:
			path.Nodes = append(path.Nodes, v)
		case qualifiedNode:
			path.Nodes = append(path.Nodes, v)
			path.GraphID = v.GraphID
		}
	}
	if e.cross && len(path.Nodes) == 0 {
		return append([]any{}, nodes...)
	}
	return path
}
func (e evaluator) cursor(graph *store.Store, value any) (*store.Store, string, store.Node, bool) {
	switch v := value.(type) {
	case qualifiedNode:
		return v.Graph, v.GraphID, v.Node, true
	case store.Node:
		if graph != nil {
			return graph, "", v, true
		}
	}
	return nil, "", store.Node{}, false
}
func (e evaluator) edgeValue(source *store.Store, id string, edge store.Edge) any {
	if e.cross {
		return qualifiedEdge{id, source, edge}
	}
	return edge
}
func (e evaluator) nodeValue(source *store.Store, id string, node store.Node) any {
	if e.cross {
		return qualifiedNode{id, source, node}
	}
	return node
}
func (e evaluator) targetNode(pattern cypher.NodePattern, value any, row map[string]any) (map[string]any, bool) {
	if name := pattern.Variable; name != "" {
		if existing, bound := row[name]; bound && equal(existing, value) != true {
			return nil, false
		}
	}
	if !e.matches(value, pattern, row) {
		return nil, false
	}
	bound := e.cloneRow(row)
	if name := pattern.Variable; name != "" {
		e.bind(bound, name, value)
		if id := valueGraphID(value); id != "" {
			addProvenance(bound, id)
		}
	}
	return bound, true
}
func (e evaluator) edgeConstraints(edge store.Edge, rel cypher.RelationshipPattern, row map[string]any) bool {
	if len(rel.Types) > 0 {
		matches := false
		for _, label := range rel.Types {
			matches = matches || matchesEdgeType(edge, label)
		}
		if !matches {
			return false
		}
	}
	for _, key := range rel.PropertyKeys {
		var actual any
		if key != "type" {
			actual = edgeProperty(edge, key)
		}
		if equal(actual, e.eval(rel.Properties[key], row)) != true {
			return false
		}
	}
	return true
}
func (e evaluator) edgesForDirection(source *store.Store, node int32, direction cypher.Direction, variable bool) []store.Edge {
	e.check()
	switch direction {
	case cypher.Outgoing:
		return source.Outgoing(node)
	case cypher.Incoming:
		return source.Incoming(node)
	}
	result := source.Outgoing(node)
	for _, edge := range source.Incoming(node) {
		if variable && edge.From == node && edge.To == node {
			continue
		}
		result = append(result, edge)
	}
	return result
}
func targetID(edge store.Edge, current int32, direction cypher.Direction) int32 {
	switch direction {
	case cypher.Outgoing:
		return edge.To
	case cypher.Incoming:
		return edge.From
	}
	if edge.From == current {
		return edge.To
	}
	return edge.From
}
func (e evaluator) matchRelationship(graph *store.Store, state matchState, rel cypher.RelationshipPattern, target cypher.NodePattern, reserved map[edgeIdentity]bool) []matchState {
	source, graphID, start, ok := e.cursor(graph, state.current)
	if !ok {
		return nil
	}
	if len(rel.Types) > 0 {
		known := false
		for _, label := range rel.Types {
			known = known || edgeFamily(label) != ""
		}
		if !known {
			return nil
		}
	}
	available := e.relationshipBindings(state.row[rel.Variable])
	blocked := func(edge any) bool { id := edgeID(edge); return state.used[id] || reserved[id] && !available[id] }
	result := []matchState{}
	emit := func(end any, nodes, edges []any) {
		e.check()
		bound, ok := e.targetNode(target, end, state.row)
		if !ok {
			return
		}
		var relationship any
		if rel.VariableLength {
			relationship = append([]any{}, edges...)
		} else {
			relationship = edges[0]
		}
		if rel.Variable != "" {
			if value, exists := state.row[rel.Variable]; exists && equal(value, relationship) != true {
				return
			}
			e.bind(bound, rel.Variable, relationship)
			for edge := range e.relationshipBindings(relationship) {
				if edge.GraphID != "" {
					addProvenance(bound, edge.GraphID)
				}
			}
		}
		used := state.used
		if used != nil {
			used = make(map[edgeIdentity]bool, len(state.used)+len(edges))
			for edge := range state.used {
				used[edge] = true
			}
			for _, edge := range edges {
				used[edgeID(edge)] = true
			}
		}
		pathNodes := append(append([]any{}, state.nodes...), nodes[1:]...)
		pathEdges := append(append([]any{}, state.edges...), edges...)
		result = append(result, matchState{row: bound, used: used, current: end, nodes: pathNodes, edges: pathEdges})
	}
	load := func(id int32) (any, bool) {
		e.check()
		node, err := source.Node(id)
		if errors.Is(err, store.ErrNodeNotFound) {
			return nil, false
		}
		if err != nil {
			fail(err.Error())
		}
		return e.nodeValue(source, graphID, node), true
	}
	if !rel.VariableLength {
		for _, edge := range e.edgesForDirection(source, start.ID, rel.Direction, false) {
			e.check()
			value := e.edgeValue(source, graphID, edge)
			if blocked(value) || !e.edgeConstraints(edge, rel, state.row) {
				continue
			}
			end, ok := load(targetID(edge, start.ID, rel.Direction))
			if !ok {
				continue
			}
			emit(end, []any{state.current, end}, []any{value})
		}
		return result
	}
	minHops := 1
	if rel.MinHops != nil {
		minHops = *rel.MinHops
	}
	if minHops <= 0 {
		emit(state.current, []any{state.current}, nil)
	}
	if rel.MaxHops != nil && *rel.MaxHops <= 0 {
		return result
	}
	type frame struct {
		id       int32
		edges    []store.Edge
		next     int
		incoming edgeIdentity
	}
	stack := []frame{{id: start.ID, edges: e.edgesForDirection(source, start.ID, rel.Direction, true)}}
	nodes := []any{state.current}
	edges := []any{}
	visited := map[edgeIdentity]bool{}
	for len(stack) > 0 {
		e.check()
		top := &stack[len(stack)-1]
		if top.next == len(top.edges) {
			if len(stack) > 1 {
				delete(visited, top.incoming)
				nodes = nodes[:len(nodes)-1]
				edges = edges[:len(edges)-1]
			}
			stack = stack[:len(stack)-1]
			continue
		}
		edge := top.edges[top.next]
		top.next++
		value := e.edgeValue(source, graphID, edge)
		identity := edgeID(value)
		if visited[identity] || blocked(value) || !e.edgeConstraints(edge, rel, state.row) {
			continue
		}
		id := targetID(edge, top.id, rel.Direction)
		end, ok := load(id)
		if !ok {
			continue
		}
		visited[identity] = true
		nodes = append(nodes, end)
		edges = append(edges, value)
		if len(edges) >= minHops {
			emit(end, nodes, edges)
		}
		if rel.MaxHops == nil || len(edges) < *rel.MaxHops {
			stack = append(stack, frame{id: id, edges: e.edgesForDirection(source, id, rel.Direction, true), incoming: identity})
		} else {
			delete(visited, identity)
			nodes = nodes[:len(nodes)-1]
			edges = edges[:len(edges)-1]
		}
	}
	return result
}

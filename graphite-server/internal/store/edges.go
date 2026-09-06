package store

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
)

type Edge struct {
	From, To             int32
	Family, Kind         string
	IsVirtual, IsDynamic bool
	Comparison           *Comparison
}

var edgeKinds = [][]string{
	{"ASSIGN", "PARAMETER_PASS", "RETURN_VALUE", "FIELD_STORE", "FIELD_LOAD", "ARRAY_STORE", "ARRAY_LOAD", "CAST", "PHI"},
	nil,
	{"EXTENDS", "IMPLEMENTS"},
	{"SEQUENTIAL", "BRANCH_TRUE", "BRANCH_FALSE", "SWITCH_CASE", "SWITCH_DEFAULT", "EXCEPTION", "RETURN"},
	{"OPENS", "LOADS", "BUNDLE_CANDIDATE", "LOOKUP", "ENUMERATES"},
}
var edgeFamilies = []string{"DataFlowEdge", "CallEdge", "TypeEdge", "ControlFlowEdge", "ResourceEdge"}

func decodeEdge(label byte, version int, from, to int32, comparison *Comparison) (Edge, error) {
	mask, shift := byte(3), uint(2)
	if version >= 3 {
		mask = 7
		shift = 3
	}
	family := int(label & mask)
	if family >= len(edgeFamilies) {
		return Edge{}, fmt.Errorf("unknown edge family %d", family)
	}
	edge := Edge{From: from, To: to, Family: edgeFamilies[family]}
	if family == 1 {
		edge.IsVirtual = (label>>shift)&1 != 0
		edge.IsDynamic = (label>>(shift+1))&1 != 0
		return edge, nil
	}
	kind := int(label >> shift & 15)
	if kind >= len(edgeKinds[family]) {
		return Edge{}, fmt.Errorf("invalid %s kind %d", edge.Family, kind)
	}
	edge.Kind = edgeKinds[family][kind]
	if family == 3 {
		edge.Comparison = comparison
	}
	return edge, nil
}
func (s *Store) loadEdges(dir string) error {
	g, err := LoadBVGraph(filepath.Join(dir, "forward"))
	if err != nil {
		return err
	}
	labels, err := os.ReadFile(filepath.Join(dir, "graph.labels"))
	if err != nil {
		return err
	}
	if int64(len(labels)) != g.ArcCount {
		return fmt.Errorf("label count %d differs from arc count %d", len(labels), g.ArcCount)
	}
	s.NodeSpan = g.NodeSpan
	s.EdgeCount = g.ArcCount
	s.outgoing = make(map[int32][]Edge, len(g.successors))
	s.incoming = map[int32][]Edge{}
	ids := make([]int, 0, len(g.successors))
	for id := range g.successors {
		ids = append(ids, int(id))
	}
	sort.Ints(ids)
	offset := 0
	for _, rawID := range ids {
		id := int32(rawID)
		targets := g.successors[id]
		edges := make([]Edge, len(targets))
		for i, to := range targets {
			var comparison *Comparison
			if c, ok := s.Comparisons[uint64(uint32(id))<<32|uint64(uint32(to))]; ok {
				comparison = &c
			}
			e, err := decodeEdge(labels[offset], s.FormatVersion, id, to, comparison)
			if err != nil {
				return fmt.Errorf("edge %d -> %d: %w", id, to, err)
			}
			offset++
			edges[i] = e
			s.incoming[to] = append(s.incoming[to], e)
		}
		s.outgoing[id] = edges
	}
	return nil
}
func copyEdges(edges []Edge) []Edge {
	out := append([]Edge(nil), edges...)
	for i := range out {
		if out[i].Comparison != nil {
			c := *out[i].Comparison
			out[i].Comparison = &c
		}
	}
	return out
}
func (s *Store) Outgoing(id int32) []Edge { return copyEdges(s.outgoing[id]) }
func (s *Store) Incoming(id int32) []Edge { return copyEdges(s.incoming[id]) }

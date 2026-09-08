package store

import (
	"reflect"
	"testing"
)

func TestLabeledForwardAndReverseEdges(t *testing.T) {
	s, err := Open("testdata/jvm-v3")
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if s.EdgeCount != 4 || s.NodeSpan != 31 {
		t.Fatalf("edge count/span %d/%d", s.EdgeCount, s.NodeSpan)
	}
	want := []Edge{{From: 24, To: 0, Family: "CallEdge"}, {From: 24, To: 2, Family: "ControlFlowEdge", Kind: "BRANCH_TRUE", Comparison: &Comparison{2, 0}}}
	if !reflect.DeepEqual(s.Outgoing(24), want) {
		t.Fatalf("outgoing: %#v", s.Outgoing(24))
	}
	incoming := []Edge{{From: 0, To: 2, Family: "DataFlowEdge", Kind: "ASSIGN"}, want[1]}
	if !reflect.DeepEqual(s.Incoming(2), incoming) {
		t.Fatalf("incoming: %#v", s.Incoming(2))
	}
	copy := s.Outgoing(24)
	copy[1].Comparison.Operator = 5
	if s.Outgoing(24)[1].Comparison.Operator != 2 {
		t.Fatal("edge comparison aliases graph")
	}
}
func TestEdgeLabelVersions(t *testing.T) {
	for _, version := range []int{1, 2, 3} {
		shift := uint(2)
		if version == 3 {
			shift = 3
		}
		for family, kinds := range edgeKinds {
			if family == 4 && version != 3 {
				continue
			}
			for kind, name := range kinds {
				e, err := decodeEdge(byte(family)|byte(kind<<shift), version, 1, 2, nil)
				if err != nil {
					t.Fatal(err)
				}
				if e.Family != edgeFamilies[family] || e.Kind != name || e.From != 1 || e.To != 2 {
					t.Fatalf("version %d edge: %#v", version, e)
				}
			}
		}
		call, err := decodeEdge(1|1<<shift|1<<(shift+1), version, 1, 2, nil)
		if err != nil || !call.IsVirtual || !call.IsDynamic {
			t.Fatalf("version %d call: %#v %v", version, call, err)
		}
	}
	if _, err := decodeEdge(7, 3, 0, 0, nil); err == nil {
		t.Fatal("invalid family accepted")
	}
	if _, err := decodeEdge(15<<3, 3, 0, 0, nil); err == nil {
		t.Fatal("invalid kind accepted")
	}
}

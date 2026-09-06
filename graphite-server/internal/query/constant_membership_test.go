package query

import (
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"testing"
)

func TestConstantMembershipMainJVMOracle(t *testing.T) {
	testFunctionsOracle(t, "testdata/constant-membership-jvm-oracle.json", "../store/testdata/jvm-v3")
}

func TestConstantMembershipAllNodeKinds(t *testing.T) {
	graph, err := store.OpenMode("../store/testdata/jvm-v3", "EAGER")
	if err != nil {
		t.Fatal(err)
	}
	defer graph.Close()
	for _, id := range graph.NodeIDs() {
		node, err := graph.Node(id)
		if err != nil {
			t.Fatal(err)
		}
		// Exact members from main ConstantNode's concrete implementation classes.
		want := map[string]bool{"IntConstant": true, "StringConstant": true, "LongConstant": true, "FloatConstant": true, "DoubleConstant": true, "BooleanConstant": true, "NullConstant": true, "EnumConstant": true, "ResourceValueNode": true}[node.Kind]
		for _, label := range []string{"Constant", "ConstantNode", "CONSTANT", "constantnode"} {
			if got := matchesLabel(node, label); got != want {
				t.Fatalf("%s matches %s: got %v want %v", node.Kind, label, got, want)
			}
		}
	}
}

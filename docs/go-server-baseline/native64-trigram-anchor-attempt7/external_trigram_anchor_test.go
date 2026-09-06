package query

import (
	"context"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"testing"
)

// Independent necessary-condition proof on the JVM-written Unicode fixture.
// Every actual substring admitted by the planner must keep its source SID in
// the shortest anchor. These are semantic assertions, never performance data.
func TestExternalTrigramAnchorPreservesEveryAdmittedSubstring(t *testing.T) {
	ctx := context.Background()
	g, err := store.OpenMode("testdata/trigram-index/store", "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	defer g.Close()
	v, ok, err := g.TryCallSiteStringIndex(ctx)
	if err != nil || !ok {
		t.Fatalf("index %v/%v", ok, err)
	}
	ok, err = g.CertifyCallSiteTrigrams(ctx, v)
	if err != nil || !ok {
		t.Fatalf("certificate %v/%v", ok, err)
	}
	used := map[int32]bool{}
	for property := store.CallerClass; property <= store.CalleeName; property++ {
		entries, err := v.Directory(ctx, property)
		if err != nil {
			t.Fatal(err)
		}
		for _, entry := range entries {
			used[entry.StringID] = true
		}
	}
	e := evaluator{ctx: ctx, indexFirst: true}
	raw, lowered := 0, 0
	for sid := range used {
		for _, lower := range []bool{false, true} {
			actual := g.Strings[sid]
			if lower {
				actual = e.javaCase(actual, false)
			}
			units := javaUTF16(actual)
			for start := 0; start+3 <= len(units); start++ {
				for end := start + 3; end <= len(units); end++ {
					term := javaFromUTF16(units[start:end])
					atom := stringCandidateAtom{operand: stringCandidateOperand{lower: lower}, op: "CONTAINS", term: term}
					hashes := e.candidateTrigrams(atom)
					if len(hashes) == 0 {
						continue
					}
					anchor, err := v.TrigramAnchor(ctx, hashes)
					if err != nil {
						t.Fatal(err)
					}
					present := false
					for _, id := range anchor {
						if id == sid {
							present = true
							break
						}
					}
					if !present {
						t.Fatalf("false negative SID=%d lower=%v substring units=%x hashes=%v anchor=%v", sid, lower, units[start:end], hashes, anchor)
					}
					if lower {
						lowered++
					} else {
						raw++
					}
				}
			}
		}
	}
	if raw < 100 || lowered < 100 {
		t.Fatalf("vacuous coverage raw=%d lower=%d", raw, lowered)
	}
	t.Logf("all admitted substring anchors retained their source SID: raw=%d lower=%d usedSIDs=%d", raw, lowered, len(used))
}

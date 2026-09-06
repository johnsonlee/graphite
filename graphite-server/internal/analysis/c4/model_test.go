package c4

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// Full fixtures and goldens were emitted by main's C4ArchitectureService. These
// are correctness fixtures, not workloads suitable for performance measurement.
func TestMainWorkspaceAndRendererGoldens(t *testing.T) {
	for _, fixture := range []string{"checkout", "artifact-families", "library", "checkout-limit-0", "checkout-limit-1", "dense"} {
		t.Run(fixture, func(t *testing.T) {
			for _, mode := range []string{"MAPPED", "EAGER"} {
				t.Run(mode, func(t *testing.T) {
					g, err := store.OpenMode(filepath.Join("testdata", fixture, "store"), mode)
					if err != nil {
						t.Fatal(err)
					}
					defer g.Close()
					for _, level := range []string{"all", "context", "container", "component"} {
						t.Run(level, func(t *testing.T) {
							limit := UnboundedModelElements
							if fixture == "checkout-limit-0" {
								limit = 0
							}
							if fixture == "checkout-limit-1" {
								limit = 1
							}
							model, err := BuildModel(g, level, limit)
							if err != nil {
								t.Fatal(err)
							}
							b, err := os.ReadFile(filepath.Join("testdata", fixture, level+".json"))
							if err != nil {
								t.Fatal(err)
							}
							var expected map[string]any
							if err = json.Unmarshal(b, &expected); err != nil {
								t.Fatal(err)
							}
							if diff := firstDifference("workspace", expected, model); diff != "" {
								t.Fatal(diff)
							}
							for format, render := range map[string]func(map[string]any) (string, error){"mermaid": RenderMermaid, "plantuml": RenderPlantUML, "dsl": RenderStructurizrDSL} {
								t.Run(format, func(t *testing.T) {
									actual, err := render(model)
									if err != nil {
										t.Fatal(err)
									}
									expected, err := os.ReadFile(filepath.Join("testdata", fixture, level+"."+format))
									if err != nil {
										t.Fatal(err)
									}
									if actual != string(expected) {
										t.Fatalf("%s output differs\nexpected:\n%s\nactual:\n%s", format, expected, actual)
									}
								})
							}
						})
					}
				})
			}
		})
	}
}
func firstDifference(path string, want, got any) string {
	if reflect.DeepEqual(want, got) {
		return ""
	}
	switch w := want.(type) {
	case map[string]any:
		g, ok := got.(map[string]any)
		if !ok {
			break
		}
		for _, k := range keys(w) {
			if d := firstDifference(path+"."+k, w[k], g[k]); d != "" {
				return d
			}
		}
		for _, k := range keys(g) {
			if _, ok := w[k]; !ok {
				return path + " unexpected key " + k
			}
		}
	case []any:
		g, ok := got.([]any)
		if !ok {
			break
		}
		if len(w) != len(g) {
			return fmt.Sprintf("%s length: want %d got %d", path, len(w), len(g))
		}
		for i := range w {
			if d := firstDifference(fmt.Sprintf("%s[%d]", path, i), w[i], g[i]); d != "" {
				return d
			}
		}
	}
	return fmt.Sprintf("%s: want %#v got %#v", path, want, got)
}

func TestManifestContinuationAndSubjectEvidence(t *testing.T) {
	m := ParseManifest("Manifest-Version: 1.0\r\nMain-Class: org.springframework.boot.loader.\r\n launch.JarLauncher\r\nStart-Class: com.acme.CheckoutApplication\r\n\r\nignored continuation\r\n")
	if value(m.MainClass) != "org.springframework.boot.loader.launch.JarLauncher" || value(m.StartClass) != "com.acme.CheckoutApplication" {
		t.Fatalf("manifest %+v", m)
	}
	if got := InferSubjectName("com.acme", nil, m.StartClass); got != "Checkout" {
		t.Fatal(got)
	}
	if got := InferSubjectName("com.acme", ptr("BOOT-INF/lib/checkout-api-1.0.jar"), m.StartClass); got != "Checkout Api" {
		t.Fatal(got)
	}
	if got := inferSubjectEvidence(nil, nil, 2, "com.acme", m, true, ptr("BOOT-INF/classes/")); got.Role != "library" || got.ActorID == nil || *got.ActorID != "person:host-applications" {
		t.Fatalf("incomplete boot reachability promoted to application: %+v", got)
	}
}
func TestComponentRepresentativeHelperEvidence(t *testing.T) {
	helper, ordinary := "com.acme.support.PaymentHelper", "com.acme.service.PaymentService"
	if a, b := ClassScore(helper, "com.acme", 20, 4, 0, 0, 0, 4), ClassScore(ordinary, "com.acme", 20, 4, 0, 0, 0, 4); a != 6 || b != 24 {
		t.Fatalf("helper=%d ordinary=%d", a, b)
	}
	if got := ClassScore(helper, "com.acme", 20, 4, 1, 0, 0, 4); got != 124 {
		t.Fatal(got)
	}
	if got := ClassScore(helper, "com.acme", 20, 4, 0, 2, 0, 4); got != 424 {
		t.Fatal(got)
	}
}
func TestTransitiveEvidenceAndHierarchy(t *testing.T) {
	edge := func(a, b string, w int, k RelationshipKind) Relationship {
		return Relationship{From: a, To: b, Weight: ptr(w), Kind: ptr(k)}
	}
	edges := []Relationship{edge("a", "c", 9, Uses), edge("a", "b", 3, Uses), edge("b", "c", 3, Uses)}
	if len(ReduceTransitiveEdges(edges, false)) != 3 {
		t.Fatal("strong direct evidence removed")
	}
	edges[0].Kind = ptr(BuildsOn)
	got := ReduceTransitiveEdges(edges, false)
	if len(got) != 2 || got[0].To != "b" || got[1].From != "b" {
		t.Fatalf("hierarchy not reduced: %+v", got)
	}
	edges[0].Kind = ptr(RunsOn)
	if len(ReduceTransitiveEdges(edges, true)) != 3 {
		t.Fatal("preserved runtime removed")
	}
}
func TestDSLEscapingAndIdentifierCollision(t *testing.T) {
	a := newWorkspaceElement("person:a-b", "Name \"with\"\nline", "path\\value", "Person")
	b := newWorkspaceElement("person:a_b", "Second", "", "Person")
	a.Relationships = append(a.Relationships, WorkspaceRelationship{DestinationID: b.ID, Description: "calls\r\nnext"})
	w := Workspace{Name: "Test", Model: WorkspaceModel{People: []*WorkspaceElement{a, b}}}
	got := renderDSL(w)
	for _, want := range []string{`g_person_a_b = person "Name \"with\" line" "path\\value"`, `g_person_a_b_2 = person "Second" ""`, `g_person_a_b -> g_person_a_b_2 "calls  next"`} {
		if !strings.Contains(got, want) {
			t.Fatalf("missing %q in %s", want, got)
		}
	}
}

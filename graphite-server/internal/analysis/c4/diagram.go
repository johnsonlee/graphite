package c4

import (
	"fmt"
	"sort"
	"strconv"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/javastring"
)

type diagramLayer struct {
	ID, Title string
	Elements  []*WorkspaceElement
	Children  []diagramLayer
}
type diagramPlan struct {
	Layers    []diagramLayer
	Edges     []Relationship
	Truncated int
}

// Java Pattern consumes each code point (or isolated surrogate) once.
func diagramID(s string) string {
	var out strings.Builder
	for _, r := range javastring.CodePoints(s) {
		if r >= 'A' && r <= 'Z' || r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '_' {
			out.WriteRune(r)
		} else {
			out.WriteByte('_')
		}
	}
	return out.String()
}
func diagramArchitecture(e *WorkspaceElement) string {
	if s := e.Properties["graphite.architectureType"]; s != "" {
		for _, valid := range []string{"actor", "software-system", "library", "runtime-platform", "external-library", "external-system", "application-runtime", "application-service", "application-component"} {
			if s == valid {
				return s
			}
		}
	}
	if strings.HasPrefix(e.ID, "person:") {
		return "actor"
	}
	if strings.HasPrefix(e.ID, "dependency:") {
		return "external-library"
	}
	return "application-component"
}
func diagramLabel(e *WorkspaceElement) string {
	switch diagramArchitecture(e) {
	case "external-library", "library":
		return humanizeArtifact(e.Name, true)
	case "runtime-platform":
		return e.Name
	default:
		return simpleName(e.Name)
	}
}
func diagramRelationshipLabel(kind RelationshipKind) string {
	switch kind {
	case RoutesTo:
		return "routes to"
	case Orchestrates:
		return "orchestrates"
	case BuildsOn:
		return "builds on"
	case RunsOn:
		return "runs on"
	case CollaboratesWith:
		return "collaborates with"
	default:
		return "uses"
	}
}
func diagramLayerIndex(e *WorkspaceElement) int {
	switch diagramArchitecture(e) {
	case "actor":
		return 0
	case "external-system":
		return 2
	case "external-library", "library":
		return 3
	case "runtime-platform":
		return 4
	default:
		return 1
	}
}
func applicationLayerIndex(e *WorkspaceElement) int {
	switch ElementKind(e.Properties["graphite.kind"]) {
	case ApplicationRuntime, ApplicationService:
		return 0
	case Interface:
		return 1
	case Orchestrator, Integration:
		return 2
	case SharedCapability:
		return 4
	default:
		return 3
	}
}
func diagramLayers(elements []*WorkspaceElement, split bool) []diagramLayer {
	layers := []diagramLayer{{ID: "actors", Title: "Actors"}, {ID: "application", Title: "Application Layer"}, {ID: "external-systems", Title: "External Systems"}, {ID: "libraries", Title: "Library Layer"}, {ID: "technology", Title: "Technology Layer"}}
	for _, e := range elements {
		i := diagramLayerIndex(e)
		layers[i].Elements = append(layers[i].Elements, e)
	}
	if split {
		children := []diagramLayer{{ID: "runtime-boundary", Title: "Runtime Boundary"}, {ID: "interface-adapters", Title: "Interface Adapters"}, {ID: "coordination", Title: "Coordination"}, {ID: "internal-capabilities", Title: "Internal Capabilities"}, {ID: "shared-foundation", Title: "Shared Foundation"}}
		for _, e := range layers[1].Elements {
			i := applicationLayerIndex(e)
			children[i].Elements = append(children[i].Elements, e)
		}
		layers[1].Elements = nil
		layers[1].Children = children
	}
	return layers
}
func allowedElements(es []*WorkspaceElement) map[string]bool {
	m := map[string]bool{}
	for _, e := range es {
		m[e.ID] = true
	}
	return m
}
func rawDiagramEdges(e *WorkspaceElement, allowed map[string]bool) []Relationship {
	out := []Relationship{}
	for _, r := range e.Relationships {
		if !allowed[e.ID] || !allowed[r.DestinationID] {
			continue
		}
		kind, ok := ParseRelationshipKind(r.Properties["graphite.relationshipKind"])
		if !ok {
			kind = Uses
		}
		w, _ := strconv.Atoi(r.Properties["graphite.weight"])
		out = append(out, Relationship{From: e.ID, To: r.DestinationID, Kind: ptr(kind), Weight: ptr(w), Description: ptr(diagramRelationshipLabel(kind))})
	}
	return out
}
func dedupeEdges(es []Relationship) []Relationship {
	out := []Relationship{}
	seen := map[string]bool{}
	for _, e := range es {
		k := e.From + ":" + e.To + ":" + string(relationshipKind(e))
		if !seen[k] {
			seen[k] = true
			out = append(out, e)
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return value(out[i].Weight) > value(out[j].Weight) })
	return out
}
func visibleSlice(es []*WorkspaceElement, edges []Relationship, limit int) ([]*WorkspaceElement, []Relationship, int) {
	if limit <= 0 {
		return nil, nil, len(edges)
	}
	if len(edges) == 0 {
		return take(es, limit), nil, 0
	}
	allowed := allowedElements(es)
	visible := map[string]bool{}
	for _, e := range edges {
		if !allowed[e.From] || !allowed[e.To] {
			continue
		}
		missing := 0
		if !visible[e.From] {
			missing++
		}
		if e.To != e.From && !visible[e.To] {
			missing++
		}
		if len(visible)+missing <= limit {
			visible[e.From] = true
			visible[e.To] = true
		}
	}
	if len(visible) == 0 {
		for _, e := range take(es, limit) {
			visible[e.ID] = true
		}
	}
	elements := []*WorkspaceElement{}
	kept := []Relationship{}
	for _, e := range es {
		if visible[e.ID] {
			elements = append(elements, e)
		}
	}
	for _, e := range edges {
		if visible[e.From] && visible[e.To] {
			kept = append(kept, e)
		}
	}
	return elements, kept, len(edges) - len(kept)
}
func contextDiagram(w Workspace) diagramPlan {
	all := append(append([]*WorkspaceElement{}, w.Model.People...), w.Model.SoftwareSystems...)
	allowed := allowedElements(all)
	edges := []Relationship{}
	for _, e := range all {
		edges = append(edges, rawDiagramEdges(e, allowed)...)
	}
	edges = ReduceTransitiveEdges(dedupeEdges(edges), false)
	truncated := max(0, len(edges)-200)
	elements, kept, omitted := visibleSlice(all, take(edges, 200), 12)
	return diagramPlan{Layers: diagramLayers(elements, false), Edges: kept, Truncated: truncated + omitted}
}
func primarySystem(w Workspace) *WorkspaceElement {
	for _, e := range w.Model.SoftwareSystems {
		if strings.HasPrefix(e.ID, "system:") {
			return e
		}
	}
	return nil
}
func reduceFanIn(edges []Relationship, library bool) []Relationship {
	cap := 3
	if library {
		cap = 2
	}
	targets := map[string][]Relationship{}
	for _, e := range edges {
		if !strings.HasPrefix(e.From, ContainerIDPrefix) {
			continue
		}
		eligible := strings.HasPrefix(e.To, ContainerIDPrefix)
		if library {
			eligible = strings.HasPrefix(e.To, DependencyIDPrefix) && relationshipKind(e) != RunsOn
		}
		if eligible {
			targets[e.To] = append(targets[e.To], e)
		}
	}
	shared := map[string]bool{}
	kept := map[string]bool{}
	for target, candidates := range targets {
		if len(candidates) <= cap {
			continue
		}
		shared[target] = true
		sort.SliceStable(candidates, func(i, j int) bool { return value(candidates[i].Weight) > value(candidates[j].Weight) })
		for _, e := range take(candidates, cap) {
			kept[compactJSON(e)] = true
		}
	}
	out := []Relationship{}
	for _, e := range edges {
		if !shared[e.To] || kept[compactJSON(e)] {
			out = append(out, e)
		}
	}
	return out
}
func containerDiagram(w Workspace) *diagramPlan {
	app := primarySystem(w)
	if app == nil {
		return nil
	}
	all := append(append([]*WorkspaceElement{}, w.Model.People...), app.Containers...)
	for _, s := range w.Model.SoftwareSystems {
		if s.ID != app.ID {
			all = append(all, s)
		}
	}
	allowed := allowedElements(all)
	byID := map[string]*WorkspaceElement{}
	for _, e := range all {
		byID[e.ID] = e
	}
	allEdges := []Relationship{}
	for _, e := range app.Containers {
		allEdges = append(allEdges, rawDiagramEdges(e, allowed)...)
	}
	selected := []Relationship{}
	for _, c := range app.Containers {
		outgoing := rawDiagramEdges(c, allowed)
		sort.SliceStable(outgoing, func(i, j int) bool { return value(outgoing[i].Weight) > value(outgoing[j].Weight) })
		for _, e := range outgoing {
			if strings.HasPrefix(e.To, ContainerIDPrefix) {
				selected = append(selected, e)
				break
			}
		}
		for _, e := range outgoing {
			target := byID[e.To]
			if target != nil && strings.HasPrefix(target.ID, DependencyIDPrefix) && target.Properties["graphite.kind"] != "runtime" {
				selected = append(selected, e)
				break
			}
		}
		if applicationLayerIndex(c) == 4 {
			var best *Relationship
			for _, e := range allEdges {
				if e.To == c.ID && strings.HasPrefix(e.From, ContainerIDPrefix) && (best == nil || value(e.Weight) > value(best.Weight)) {
					best = ptr(e)
				}
			}
			if best != nil {
				selected = append(selected, *best)
			}
		}
	}
	edges := reduceFanIn(reduceFanIn(ReduceTransitiveEdges(dedupeEdges(selected), false), false), true)
	connected := map[string]bool{}
	for _, e := range edges {
		connected[e.From] = true
		connected[e.To] = true
	}
	elements := []*WorkspaceElement{}
	if len(connected) > 0 {
		for _, e := range all {
			if connected[e.ID] {
				elements = append(elements, e)
			}
		}
	} else {
		elements = take(all, 12)
	}
	elements, edges, omitted := visibleSlice(elements, edges, 12)
	return &diagramPlan{Layers: diagramLayers(elements, true), Edges: edges, Truncated: omitted}
}
func componentDiagram(w Workspace) *diagramPlan {
	app := primarySystem(w)
	if app == nil {
		return nil
	}
	all := []*WorkspaceElement{}
	for _, c := range app.Containers {
		all = append(all, c.Components...)
	}
	allowed := allowedElements(all)
	edges := []Relationship{}
	for _, e := range all {
		edges = append(edges, rawDiagramEdges(e, allowed)...)
	}
	sort.SliceStable(edges, func(i, j int) bool { return value(edges[i].Weight) > value(edges[j].Weight) })
	elements, kept, omitted := visibleSlice(all, take(edges, 200), 16)
	visible := allowedElements(elements)
	if len(visible) == 0 {
		visible = allowedElements(take(all, 16))
	}
	layer := diagramLayer{ID: "application", Title: "Application Layer"}
	for _, c := range app.Containers {
		children := []*WorkspaceElement{}
		for _, e := range c.Components {
			if visible[e.ID] {
				children = append(children, e)
			}
		}
		if len(children) == 0 {
			continue
		}
		title := c.Name
		if javastring.Trim(title) == "" {
			title = "Container"
		}
		id := c.ID
		if javastring.Trim(id) == "" {
			id = c.Name
			if javastring.Trim(id) == "" {
				id = "container"
			}
		}
		layer.Children = append(layer.Children, diagramLayer{ID: id, Title: title, Elements: children})
	}
	return &diagramPlan{Layers: []diagramLayer{layer}, Edges: kept, Truncated: max(0, len(edges)-200) + omitted}
}
func RenderMermaid(raw map[string]any) (string, error)  { return renderText(raw, false) }
func RenderPlantUML(raw map[string]any) (string, error) { return renderText(raw, true) }
func renderText(raw map[string]any, plant bool) (string, error) {
	w, err := DecodeWorkspace(raw)
	if err != nil {
		return "", err
	}
	render := func(level Level) string {
		switch level {
		case Context:
			p := contextDiagram(w)
			return renderDiagram(&p, plant)
		case Container:
			return renderDiagram(containerDiagram(w), plant)
		default:
			return renderDiagram(componentDiagram(w), plant)
		}
	}
	level := ParseLevel(w.Properties["graphite.level"])
	if level != All {
		return render(level), nil
	}
	heading := "%% "
	if plant {
		heading = "' "
	}
	return strings.Join([]string{heading + "Context", render(Context), "", heading + "Container", render(Container), "", heading + "Component", render(Component)}, "\n"), nil
}
func renderDiagram(plan *diagramPlan, plant bool) string {
	if plan == nil {
		if plant {
			return "@startuml\n@enduml"
		}
		return "graph TD"
	}
	lines := []string{"graph TD"}
	if plant {
		lines = []string{"@startuml", "top to bottom direction", "skinparam shadowing false"}
	}
	escape := func(s string) string { return strings.NewReplacer(`"`, "'", "\n", `\n`).Replace(s) }
	quote := func(s string) string { return `"` + strings.NewReplacer(`"`, "'", "\n", "<br/>").Replace(s) + `"` }
	indent := func(depth int) string {
		if plant {
			return strings.Repeat("  ", depth)
		}
		return strings.Repeat("    ", depth+1)
	}
	element := func(e *WorkspaceElement, depth int) {
		id, label, arch := diagramID(e.ID), diagramLabel(e), diagramArchitecture(e)
		if plant {
			kind := "component"
			switch arch {
			case "actor":
				kind = "actor"
			case "runtime-platform":
				kind = "node"
			case "software-system":
				kind = "rectangle"
			}
			lines = append(lines, indent(depth)+kind+` "`+escape(label)+`" as `+id)
			return
		}
		shape := "[" + quote(label) + "]"
		raw := strings.TrimSuffix(strings.TrimPrefix(quote(label), `"`), `"`)
		switch arch {
		case "actor", "application-service":
			shape = "([" + raw + "])"
		case "software-system":
			shape = "[[" + raw + "]]"
		case "runtime-platform":
			shape = "[(" + raw + ")]"
		}
		lines = append(lines, indent(depth)+id+shape)
	}
	var nonempty func(diagramLayer) bool
	nonempty = func(l diagramLayer) bool {
		if len(l.Elements) > 0 {
			return true
		}
		for _, c := range l.Children {
			if nonempty(c) {
				return true
			}
		}
		return false
	}
	var layer func(diagramLayer, int)
	layer = func(l diagramLayer, depth int) {
		if !nonempty(l) {
			return
		}
		group := !(plant && depth == 0 && l.ID == "actors")
		childDepth := depth
		if group {
			if plant {
				lines = append(lines, indent(depth)+`package "`+escape(l.Title)+`" {`)
			} else {
				lines = append(lines, indent(depth)+"subgraph "+diagramID(javastring.Lower(l.ID))+"["+quote(l.Title)+"]")
			}
			childDepth++
		}
		for _, e := range l.Elements {
			element(e, childDepth)
		}
		for _, c := range l.Children {
			layer(c, childDepth)
		}
		if group {
			suffix := "end"
			if plant {
				suffix = "}"
			}
			lines = append(lines, indent(depth)+suffix)
		}
	}
	for _, l := range plan.Layers {
		layer(l, 0)
	}
	for _, e := range plan.Edges {
		label := value(e.Description)
		if plant {
			lines = append(lines, diagramID(e.From)+" --> "+diagramID(e.To)+" : "+escape(label))
		} else {
			label = strings.NewReplacer(`"`, "'", "|", "/", "(", "", ")", "", "[", "", "]", "", "{", "", "}", "").Replace(label)
			lines = append(lines, "    "+diagramID(e.From)+" -->|"+label+"| "+diagramID(e.To))
		}
	}
	if plan.Truncated > 0 {
		if plant {
			lines = append(lines, "note as N1", fmt.Sprintf("PlantUML view truncated: %d edges omitted", plan.Truncated), "end note")
		} else {
			lines = append(lines, "    graph_note["+quote(fmt.Sprintf("Mermaid view truncated: %d edges omitted", plan.Truncated))+"]")
		}
	}
	if plant {
		lines = append(lines, "@enduml")
	}
	return strings.Join(lines, "\n")
}

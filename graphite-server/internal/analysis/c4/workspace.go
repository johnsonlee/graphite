package c4

import (
	"encoding/json"
	"fmt"
	"sort"
	"strconv"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

type Workspace struct {
	Name        string            `json:"name"`
	Description string            `json:"description"`
	Properties  map[string]string `json:"properties"`
	Model       WorkspaceModel    `json:"model"`
	Views       WorkspaceViews    `json:"views"`
}
type WorkspaceModel struct {
	People          []*WorkspaceElement `json:"people"`
	SoftwareSystems []*WorkspaceElement `json:"softwareSystems"`
}
type WorkspaceElement struct {
	ID            string                  `json:"id"`
	Name          string                  `json:"name"`
	Description   string                  `json:"description"`
	Technology    *string                 `json:"technology,omitempty"`
	Tags          string                  `json:"tags"`
	Properties    map[string]string       `json:"properties"`
	Relationships []WorkspaceRelationship `json:"relationships"`
	Containers    []*WorkspaceElement     `json:"containers"`
	Components    []*WorkspaceElement     `json:"components"`
}
type WorkspaceRelationship struct {
	ID            string            `json:"id"`
	DestinationID string            `json:"destinationId"`
	Description   string            `json:"description"`
	Technology    *string           `json:"technology,omitempty"`
	Tags          string            `json:"tags"`
	Properties    map[string]string `json:"properties"`
}
type WorkspaceViews struct {
	SystemContextViews []WorkspaceView `json:"systemContextViews"`
	ContainerViews     []WorkspaceView `json:"containerViews"`
	ComponentViews     []WorkspaceView `json:"componentViews"`
	Configuration      map[string]any  `json:"configuration"`
}
type Reference struct {
	ID string `json:"id"`
}
type WorkspaceView struct {
	Key              string            `json:"key"`
	Description      string            `json:"description"`
	SoftwareSystemID *string           `json:"softwareSystemId,omitempty"`
	ContainerID      *string           `json:"containerId,omitempty"`
	Elements         []Reference       `json:"elements"`
	Relationships    []Reference       `json:"relationships"`
	Properties       map[string]string `json:"properties"`
}

// BuildModel returns a Structurizr workspace suitable for the topology HTTP JSON
// response and the renderers below. The default model is unbounded, as in main.
func BuildModel(g *store.Store, level string, limit ...int) (map[string]any, error) {
	v, err := InferViewModel(g, level, limit...)
	if err != nil {
		return nil, err
	}
	return EncodeWorkspace(ToWorkspace(v)), nil
}
func EncodeWorkspace(w Workspace) map[string]any {
	b, _ := json.Marshal(w)
	var m map[string]any
	_ = json.Unmarshal(b, &m)
	return m
}
func DecodeWorkspace(m map[string]any) (Workspace, error) {
	b, err := json.Marshal(m)
	if err != nil {
		return Workspace{}, err
	}
	w := Workspace{Name: "Graphite C4 Workspace"}
	err = json.Unmarshal(b, &w)
	return w, err
}
func newWorkspaceElement(id, name, description, tags string) *WorkspaceElement {
	return &WorkspaceElement{ID: id, Name: name, Description: description, Tags: tags, Properties: map[string]string{}, Relationships: []WorkspaceRelationship{}, Containers: []*WorkspaceElement{}, Components: []*WorkspaceElement{}}
}

// Gson pretty-prints structured properties as embedded JSON strings. Preserve
// reference field order and Double spelling because these strings are wire data.
func prettyJSON(v any) string { return prettyValue(v, 0) }
func jsonQuote(s string) string {
	b, _ := json.Marshal(s)
	return strings.NewReplacer("'", `\u0027`, "=", `\u003d`).Replace(string(b))
}
func prettyValue(v any, depth int) string {
	indent := func(n int) string { return strings.Repeat("  ", n) }
	switch x := v.(type) {
	case map[string]any:
		if len(x) == 0 {
			return "{}"
		}
		ks := keys(x)
		order := []string{}
		switch {
		case x["packageUnits"] != nil:
			order = []string{"name", "kind", "packageUnits", "methods", "callSites", "endpoints", "primaryClasses", "whySelected"}
		case x["crossContainerCalls"] != nil:
			order = []string{"crossContainerCalls", "source", "confidence", "artifacts"}
		case x["crossBoundaryCalls"] != nil:
			order = []string{"crossBoundaryCalls", "source", "confidence"}
		case x["observedReferences"] != nil:
			order = []string{"observedReferences", "source", "confidence"}
		case x["source"] != nil:
			order = []string{"source", "kind"}
		}
		ranks := map[string]int{}
		for i, k := range order {
			ranks[k] = i + 1
		}
		sort.SliceStable(ks, func(i, j int) bool {
			a, b := ranks[ks[i]], ranks[ks[j]]
			if a == 0 {
				a = 100
			}
			if b == 0 {
				b = 100
			}
			return a < b
		})
		lines := []string{}
		for _, k := range ks {
			if x[k] != nil {
				lines = append(lines, indent(depth+1)+jsonQuote(k)+": "+prettyValue(x[k], depth+1))
			}
		}
		return "{\n" + strings.Join(lines, ",\n") + "\n" + indent(depth) + "}"
	case []string:
		a := make([]any, len(x))
		for i, s := range x {
			a[i] = s
		}
		return prettyValue(a, depth)
	case []Level:
		a := make([]any, len(x))
		for i, s := range x {
			a[i] = string(s)
		}
		return prettyValue(a, depth)
	case []map[string]any:
		a := make([]any, len(x))
		for i, s := range x {
			a[i] = s
		}
		return prettyValue(a, depth)
	case []any:
		if len(x) == 0 {
			return "[]"
		}
		lines := []string{}
		for _, item := range x {
			lines = append(lines, indent(depth+1)+prettyValue(item, depth+1))
		}
		return "[\n" + strings.Join(lines, ",\n") + "\n" + indent(depth) + "]"
	case string:
		return jsonQuote(x)
	case float64:
		return structValue(x)
	default:
		b, _ := json.Marshal(x)
		return string(b)
	}
}
func metadataNumbers(v any) any {
	switch x := v.(type) {
	case int:
		return float64(x)
	case map[string]any:
		m := map[string]any{}
		for k, v := range x {
			m[k] = metadataNumbers(v)
		}
		return m
	case []map[string]any:
		a := make([]any, len(x))
		for i, v := range x {
			a[i] = metadataNumbers(v)
		}
		return a
	case []any:
		a := make([]any, len(x))
		for i, v := range x {
			a[i] = metadataNumbers(v)
		}
		return a
	}
	return v
}

func structValue(v any) string {
	switch x := v.(type) {
	case string:
		return x
	case Level:
		return string(x)
	case ElementKind:
		return string(x)
	case ElementType:
		return string(x)
	case ArchitectureType:
		return string(x)
	case DependencyKind:
		return string(x)
	case RelationshipKind:
		return string(x)
	case int:
		return strconv.Itoa(x)
	case float64:
		s := strconv.FormatFloat(x, 'f', -1, 64)
		if !strings.ContainsAny(s, ".eE") {
			s += ".0"
		}
		return s
	case bool:
		return strconv.FormatBool(x)
	default:
		return prettyJSON(v)
	}
}
func workspaceElement(e Element, tag string) *WorkspaceElement {
	description := value(e.Description)
	if e.Description == nil {
		description = value(e.Responsibility)
	}
	tags := tag + ",Graphite"
	if e.Kind != nil {
		tags += "," + string(*e.Kind)
	}
	w := newWorkspaceElement(e.ID, e.Name, description, tags)
	p := metadataNumbers(metadata(e.Metadata)).(map[string]any)
	for k, v := range e.ExtensionProperties {
		p[k] = v
	}
	if v, ok := p["technology"]; ok {
		w.Technology = ptr(fmt.Sprint(v))
	}
	put := func(k string, v any) {
		if v != nil {
			w.Properties["graphite."+k] = structValue(v)
		}
	}
	put("type", e.Type)
	if e.Kind != nil {
		put("kind", *e.Kind)
	}
	if e.ArchitectureType != nil {
		put("architectureType", *e.ArchitectureType)
	}
	if e.Responsibility != nil {
		put("responsibility", *e.Responsibility)
	}
	for _, k := range []string{"whySelected", "container", "containerId", "fullName", "methods", "callSites", "endpoints", "entrypoints", "classes", "packageUnits", "primaryClasses", "internalCapabilities", "evidence", "selectors", "owners", "links", "constraints"} {
		v := p[k]
		if _, extension := e.ExtensionProperties[k]; !extension {
			if n, ok := v.(int); ok {
				v = float64(n)
			}
		}
		put(k, v)
	}
	if evidence, ok := p["evidence"].(map[string]any); ok {
		put("matchedClassCount", evidence["matchedClassCount"])
	}
	return w
}
func refs(ids []string) []Reference {
	out := []Reference{}
	for _, id := range unique(ids) {
		out = append(out, Reference{id})
	}
	return out
}
func ToWorkspace(m ViewModel) Workspace {
	context, container, component := m.Context, m.Container, m.Component
	if m.Level != All {
		context, container, component = nil, nil, nil
		switch m.Level {
		case Context:
			context = m.View
		case Container:
			container = m.View
		case Component:
			component = m.View
		}
	}
	primaryID := SubjectFallbackID
	if context != nil {
		for _, e := range context.Elements {
			if e.Type == SoftwareSystem && strings.HasPrefix(e.ID, "system:") {
				primaryID = e.ID
				break
			}
		}
	}
	primary := newWorkspaceElement(primaryID, "Subject", "Derived from the Graphite code graph", "Software System,Graphite,Application")
	people, systems, containers, components := []*WorkspaceElement{}, []*WorkspaceElement{primary}, []*WorkspaceElement{}, []*WorkspaceElement{}
	byID := map[string]*WorkspaceElement{primaryID: primary}
	replaceOrAppend := func(e *WorkspaceElement, list *[]*WorkspaceElement) {
		if old := byID[e.ID]; old != nil {
			*old = *e
		} else {
			byID[e.ID] = e
			*list = append(*list, e)
		}
	}
	if context != nil {
		for _, e := range context.Elements {
			switch e.Type {
			case Person:
				replaceOrAppend(workspaceElement(e, "Person"), &people)
			case SoftwareSystem:
				replaceOrAppend(workspaceElement(e, "Software System"), &systems)
			}
		}
	}
	if container != nil {
		for _, d := range container.ExternalDependencies {
			system := byID[d.ID]
			if system == nil {
				system = newWorkspaceElement(d.ID, d.Name, "External dependency inferred from code graph evidence", "Software System,Graphite,External Dependency")
				byID[d.ID] = system
				systems = append(systems, system)
			}
			for k, v := range map[string]string{"kind": string(d.Kind), "architectureType": string(d.Kind.ArchitectureType()), "source": d.Source, "confidence": d.Confidence, "responsibility": d.Responsibility} {
				system.Properties["graphite."+k] = v
			}
		}
		for _, e := range container.Elements {
			replaceOrAppend(workspaceElement(e, "Container"), &containers)
		}
		if container.SystemBoundary != nil {
			primary.Properties["graphite.systemBoundary"] = *container.SystemBoundary
		}
	}
	if component != nil {
		for _, e := range component.Elements {
			replaceOrAppend(workspaceElement(e, "Component"), &components)
			p := elementProperties(e)
			id := stringProperty(p, "containerId")
			name := stringProperty(p, "container")
			if _, ok := p["containerId"]; !ok {
				id = ContainerIDPrefix + name
			}
			if byID[id] == nil {
				c := newWorkspaceElement(id, name, "Inferred runtime container synthesized for component view", "Container,Graphite,Internal Container")
				c.Technology = ptr("JVM bytecode")
				byID[id] = c
				containers = append(containers, c)
			}
		}
	}
	type relKey struct {
		from, to string
		kind     RelationshipKind
	}
	ids := map[relKey]string{}
	register := func(v *View, level string) {
		if v == nil {
			return
		}
		for _, r := range v.Relationships {
			kind := relationshipKind(r)
			key := relKey{r.From, r.To, kind}
			id := ids[key]
			if id == "" {
				id = fmt.Sprintf("rel-%d", len(ids)+1)
				ids[key] = id
			}
			source := byID[r.From]
			if source == nil {
				continue
			}
			duplicate := false
			for _, old := range source.Relationships {
				duplicate = duplicate || old.ID == id
			}
			if duplicate {
				continue
			}
			description := string(r.Type)
			if r.Description != nil {
				description = *r.Description
			}
			p := map[string]string{"graphite.view": level, "graphite.relationshipKind": string(kind)}
			if r.Evidence != nil {
				p["graphite.evidence"] = prettyJSON(r.Evidence)
			}
			if r.Weight != nil {
				p["graphite.weight"] = strconv.Itoa(*r.Weight)
			}
			source.Relationships = append(source.Relationships, WorkspaceRelationship{ID: id, DestinationID: r.To, Description: description, Technology: ptr(string(r.Type)), Tags: "Relationship,Graphite," + string(kind), Properties: p})
		}
	}
	register(context, "context")
	register(container, "container")
	register(component, "component")
	for _, c := range containers {
		for _, e := range components {
			if e.Properties["graphite.containerId"] == c.ID || ContainerIDPrefix+e.Properties["graphite.container"] == c.ID {
				c.Components = append(c.Components, e)
			}
		}
	}
	primary.Containers = containers
	relIDs := func(v *View) []string {
		out := []string{}
		for _, r := range v.Relationships {
			if id := ids[relKey{r.From, r.To, relationshipKind(r)}]; id != "" {
				out = append(out, id)
			}
		}
		return out
	}
	views := WorkspaceViews{SystemContextViews: []WorkspaceView{}, ContainerViews: []WorkspaceView{}, ComponentViews: []WorkspaceView{}, Configuration: map[string]any{"scope": "softwareSystem", "properties": map[string]string{"graphite.level": string(m.Level), "graphite.availableLevels": prettyJSON(m.AvailableLevels)}}}
	if context != nil {
		es := []string{}
		for _, e := range context.Elements {
			es = append(es, e.ID)
		}
		views.SystemContextViews = append(views.SystemContextViews, WorkspaceView{Key: "graphite-context", Description: "Graphite-derived C4 system context view", SoftwareSystemID: ptr(primaryID), Elements: refs(es), Relationships: refs(relIDs(context)), Properties: map[string]string{"graphite.level": string(m.Level)}})
	}
	if container != nil {
		es := []string{}
		for _, e := range container.Elements {
			es = append(es, e.ID)
		}
		for _, d := range container.ExternalDependencies {
			es = append(es, d.ID)
		}
		for _, p := range people {
			es = append(es, p.ID)
		}
		props := map[string]string{"graphite.level": string(m.Level)}
		if container.SystemBoundary != nil {
			props["graphite.systemBoundary"] = *container.SystemBoundary
		}
		views.ContainerViews = append(views.ContainerViews, WorkspaceView{Key: "graphite-container", Description: "Graphite-derived C4 container view", SoftwareSystemID: ptr(primaryID), Elements: refs(es), Relationships: refs(relIDs(container)), Properties: props})
	}
	if component != nil {
		groups := map[string][]Element{}
		groupIDs := []string{}
		for _, e := range component.Elements {
			p := elementProperties(e)
			id := stringProperty(p, "containerId")
			if _, ok := p["containerId"]; !ok {
				if name := stringProperty(p, "container"); strings.TrimSpace(name) != "" {
					id = ContainerIDPrefix + name
				}
			}
			if strings.TrimSpace(id) == "" {
				continue
			}
			if _, ok := groups[id]; !ok {
				groupIDs = append(groupIDs, id)
			}
			groups[id] = append(groups[id], e)
		}
		sourceIDs := map[string]string{}
		for _, c := range components {
			for _, r := range c.Relationships {
				sourceIDs[r.ID] = c.ID
			}
		}
		for _, id := range groupIDs {
			group := groups[id]
			name := stringProperty(elementProperties(group[0]), "container")
			es := []string{}
			allowed := map[string]bool{}
			for _, e := range group {
				es = append(es, e.ID)
				allowed[e.ID] = true
			}
			rs := []string{}
			for _, r := range relIDs(component) {
				if allowed[sourceIDs[r]] {
					rs = append(rs, r)
				}
			}
			views.ComponentViews = append(views.ComponentViews, WorkspaceView{Key: "graphite-component-" + Slugify(id), Description: "Graphite-derived C4 component view for " + name, ContainerID: ptr(id), Elements: refs(es), Relationships: refs(rs), Properties: map[string]string{"graphite.level": string(m.Level), "graphite.containerId": id, "graphite.container": name}})
		}
	}
	return Workspace{Name: "Graphite C4 Workspace", Description: "Structurizr workspace derived from the Graphite code graph", Properties: map[string]string{"graphite.level": string(m.Level), "graphite.availableLevels": prettyJSON(m.AvailableLevels), "graphite.format": "structurizr-workspace"}, Model: WorkspaceModel{People: people, SoftwareSystems: systems}, Views: views}
}

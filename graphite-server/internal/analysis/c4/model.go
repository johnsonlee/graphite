package c4

import (
	"fmt"
	"sort"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/analysis"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// InferViewModel produces the full inference model. A missing limit uses the
// reference's unbounded machine-readable model default.
func InferViewModel(g *store.Store, level string, limits ...int) (ViewModel, error) {
	limit := UnboundedModelElements
	if len(limits) > 0 {
		limit = limits[0]
	}
	if limit < 0 {
		return ViewModel{}, fmt.Errorf("C4 limit must be non-negative")
	}
	if g == nil {
		return ViewModel{}, fmt.Errorf("C4 graph is nil")
	}
	methods := g.Metadata.MethodList
	calls := []store.Node{}
	for _, id := range g.NodesOfKind("CallSiteNode") {
		node, err := g.Node(id)
		if err != nil {
			return ViewModel{}, err
		}
		calls = append(calls, node)
	}
	endpoints := []EndpointEvidence{}
	for _, e := range analysis.ExtractEndpoints(g) {
		class, _ := e["class"].(string)
		path, _ := e["path"].(string)
		if strings.TrimSpace(class) != "" {
			endpoints = append(endpoints, EndpointEvidence{ClassName: class, Path: path})
		}
	}
	return inferFromEvidence(g, methods, calls, endpoints, ParseLevel(level), limit)
}
func inferFromEvidence(g *store.Store, methods []store.MethodDescriptor, calls []store.Node, endpoints []EndpointEvidence, level Level, limit int) (ViewModel, error) {
	boundary := DeriveSystemBoundary(methods, calls)
	subject := InferSubject(g, methods, calls, len(endpoints), boundary)
	model := ViewModel{Level: level, AvailableLevels: ModelLevels()}
	classes := []string{}
	for _, m := range methods {
		classes = append(classes, m.DeclaringClass)
	}
	for _, c := range calls {
		classes = append(classes, c.Caller.DeclaringClass, c.Callee.DeclaringClass)
	}
	count := 0
	for _, c := range unique(classes) {
		if !IsSyntheticClass(c) {
			count++
		}
	}
	containers := 0
	if subject.Role == "application" {
		containers = 1
	}
	if level == Context {
		v, err := buildContextView(g, count, len(methods), len(endpoints), calls, boundary, subject, containers, limit)
		model.View = &v
		return model, err
	}
	layoutLimit := limit
	if level == Component {
		layoutLimit = max(limit, 8)
	}
	layout, err := InferContainerLayout(g, methods, calls, endpoints, boundary, layoutLimit)
	if err != nil {
		return model, err
	}
	if level == Container {
		v := buildContainerView(g, calls, subject, layout)
		model.View = &v
		return model, nil
	}
	component := BuildComponentView(methods, calls, endpoints, boundary, limit, subject, layout)
	if level == Component {
		model.View = &component
		return model, nil
	}
	container := buildContainerView(g, calls, subject, layout)
	context, err := buildContextView(g, count, len(methods), len(endpoints), calls, boundary, subject, len(container.Elements), limit)
	model.Context = &context
	model.Container = &container
	model.Component = &component
	return model, err
}
func CollapseContextDependencies(deps []ExternalDependency) ContextDependencyCollapse {
	out := ContextDependencyCollapse{Dependencies: []ExternalDependency{}, DependencyIDToContextID: map[string]string{}, ArtifactToContextID: map[string]string{}}
	prefixCounts := map[string]int{}
	for _, d := range deps {
		if a, ok := ArtifactNameFromDependencyID(d.ID); ok {
			base := ArtifactBaseName(a)
			if strings.Contains(base, "-") {
				prefix := strings.Split(base, "-")[0]
				if strings.TrimSpace(prefix) != "" {
					prefixCounts[prefix]++
				}
			}
		}
	}
	groups := map[string][]ExternalDependency{}
	ids := []string{}
	for _, d := range deps {
		id := d.ID
		if a, ok := ArtifactNameFromDependencyID(d.ID); ok {
			prefix := strings.Split(ArtifactBaseName(a), "-")[0]
			if prefixCounts[prefix] > 1 {
				id = DependencyLibraryIDPrefix + prefix
			}
			out.ArtifactToContextID[a] = id
		}
		out.DependencyIDToContextID[d.ID] = id
		if _, ok := groups[id]; !ok {
			ids = append(ids, id)
		}
		groups[id] = append(groups[id], d)
	}
	for _, id := range ids {
		members := groups[id]
		if len(members) == 1 && id == members[0].ID {
			d := members[0]
			d.Artifacts = []string{d.Name}
			out.Dependencies = append(out.Dependencies, d)
			continue
		}
		name := strings.TrimPrefix(id, DependencyLibraryIDPrefix)
		d := ExternalDependency{ID: id, Name: name, Source: "artifact-family", Kind: LibraryDependency, Confidence: "high", Responsibility: "Provides the " + humanizeArtifact(name, true) + " third-party library capabilities used by the application", Artifacts: []string{}}
		for _, member := range members {
			d.Weight += member.Weight
			d.Artifacts = append(d.Artifacts, member.Name)
			if member.Confidence != "high" {
				d.Confidence = "medium"
			}
		}
		d.Artifacts = sorted(d.Artifacts)
		out.Dependencies = append(out.Dependencies, d)
	}
	sort.SliceStable(out.Dependencies, func(i, j int) bool { return out.Dependencies[i].Weight > out.Dependencies[j].Weight })
	return out
}
func contextLibraryRelationships(g *store.Store, collapse ContextDependencyCollapse, deps []ExternalDependency, limit int) []Relationship {
	out := []Relationship{}
	if g == nil || len(collapse.ArtifactToContextID) == 0 {
		return out
	}
	names := map[string]string{}
	for _, d := range deps {
		names[d.ID] = d.Name
	}
	weights := orderedCounts[pair]{} // Persisted insertion order breaks equal-weight relationship ties.
	artifactOrder := g.Metadata.ArtifactDependencyOrder
	if len(artifactOrder) == 0 {
		artifactOrder = keys(g.Metadata.ArtifactDependencies)
	}
	for _, a := range artifactOrder {
		from := collapse.ArtifactToContextID[a]
		if from == "" {
			continue
		}
		targetOrder := g.Metadata.ArtifactDependencyTargetOrder[a]
		if len(targetOrder) == 0 {
			targetOrder = keys(g.Metadata.ArtifactDependencies[a])
		}
		for _, b := range targetOrder {
			to := collapse.ArtifactToContextID[b]
			weight := int(g.Metadata.ArtifactDependencies[a][b])
			if to != "" && from != to && weight > 0 {
				weights.add(pair{from, to}, weight)
			}
		}
	}
	for _, p := range take(weights.ranked(), limit) {
		a, b := names[p.From], names[p.To]
		if a == "" {
			a = p.From
		}
		if b == "" {
			b = p.To
		}
		w := weights.Values[p]
		out = append(out, Relationship{From: p.From, To: p.To, Type: RelationshipType(Uses), Kind: ptr(BuildsOn), Description: ptr(a + " builds on " + b), Weight: ptr(w), Evidence: map[string]any{"observedReferences": w, "source": "artifact-metadata", "confidence": "high"}})
	}
	return out
}
func buildContextView(g *store.Store, classCount, methodCount, endpointCount int, calls []store.Node, boundary string, subject SubjectDescriptor, containerCount, limit int) (View, error) {
	v := View{Type: Context, Elements: []Element{}, Relationships: []Relationship{}}
	weights := orderedCounts[string]{}
	for _, c := range calls {
		a, b := c.Caller.DeclaringClass, c.Callee.DeclaringClass
		if !IsSyntheticClass(a) && !IsSyntheticClass(b) && IsInternalClass(a, boundary) && !IsInternalClass(b, boundary) {
			weights.add(b, 1)
		}
	}
	weighted := []WeightedClass{}
	for _, c := range weights.Keys {
		weighted = append(weighted, WeightedClass{ClassName: c, Weight: weights.Values[c]})
	}
	deps, err := SummarizeExternalDependencies(g, weighted, limit)
	if err != nil {
		return v, err
	}
	runtimes, other := []ExternalDependency{}, []ExternalDependency{}
	for _, d := range deps {
		if d.Kind == RuntimeDependency {
			runtimes = append(runtimes, d)
		} else {
			other = append(other, d)
		}
	}
	collapse := CollapseContextDependencies(other)
	libraries, services := []ExternalDependency{}, []ExternalDependency{}
	for _, d := range collapse.Dependencies {
		if d.Kind == LibraryDependency {
			libraries = append(libraries, d)
		} else if d.Kind == ExternalSystemDependency {
			services = append(services, d)
		}
	}
	libraryRels := contextLibraryRelationships(g, collapse, libraries, limit)
	runtimeLibraries := runtimeBoundaryLibraries(libraries, libraryRels)
	if subject.ActorID != nil {
		v.Elements = append(v.Elements, Element{ID: *subject.ActorID, Type: Person, Name: value(subject.ActorName), Description: subject.ActorDescription, Kind: ptr(Actor), ArchitectureType: ptr(ArchitectureType("actor")), Responsibility: subject.ActorResponsibility})
	}
	others := append(append([]ExternalDependency{}, libraries...), services...)
	for _, d := range others {
		v.Elements = append(v.Elements, Element{ID: d.ID, Type: SoftwareSystem, Name: d.Name, Description: ptr(ExternalDependencyDescription(d.Kind)), Kind: ptr(d.Kind.ElementKind()), ArchitectureType: ptr(d.Kind.ArchitectureType()), Responsibility: ptr(d.Responsibility), Metadata: map[string]any{"confidence": d.Confidence, "source": d.Source, "artifacts": d.Artifacts}})
	}
	for _, d := range runtimes {
		v.Elements = append(v.Elements, Element{ID: d.ID, Type: SoftwareSystem, Name: d.Name, Description: ptr("Language and platform runtime supporting the application and its libraries"), Kind: ptr(d.Kind.ElementKind()), ArchitectureType: ptr(d.Kind.ArchitectureType()), Responsibility: ptr(d.Responsibility), Metadata: map[string]any{"confidence": d.Confidence, "source": d.Source}})
	}
	kind := ElementKind(subject.Role)
	v.Elements = append(v.Elements, Element{ID: subject.ID, Type: SoftwareSystem, Name: subject.Name, Description: ptr(subject.Description), Kind: ptr(kind), ArchitectureType: ptr(kind.ArchitectureType()), Responsibility: ptr(subject.Responsibility), Metadata: map[string]any{"classes": classCount, "methods": methodCount, "containers": containerCount, "endpoints": endpointCount, "systemBoundary": boundary, "whySelected": "Dominant namespace boundary inferred from internal classes and call-site traffic"}})
	if subject.ActorID != nil {
		v.Relationships = append(v.Relationships, Relationship{From: *subject.ActorID, To: subject.ID, Type: RelationshipType(Uses), Kind: ptr(Uses), Description: ptr(describeInvocation(subject, endpointCount)), Evidence: invocationEvidence(subject, endpointCount)})
	}
	for _, d := range others {
		v.Relationships = append(v.Relationships, Relationship{From: subject.ID, To: d.ID, Type: RelationshipType(Uses), Kind: ptr(Uses), Description: ptr(subject.Name + " uses " + d.Name), Weight: ptr(d.Weight), Evidence: metadata(map[string]any{"crossContainerCalls": d.Weight, "source": d.Source, "confidence": d.Confidence, "artifacts": d.Artifacts})})
	}
	v.Relationships = append(v.Relationships, libraryRels...)
	if len(runtimeLibraries) > 0 {
		for _, library := range runtimeLibraries {
			for _, runtime := range runtimes {
				v.Relationships = append(v.Relationships, Relationship{From: library.ID, To: runtime.ID, Type: RelationshipType(RunsOn), Kind: ptr(RunsOn), Description: ptr(library.Name + " runs on " + runtime.Name), Evidence: runtimeEvidence(runtime)})
			}
		}
	} else {
		for _, runtime := range runtimes {
			v.Relationships = append(v.Relationships, Relationship{From: subject.ID, To: runtime.ID, Type: RelationshipType(RunsOn), Kind: ptr(RunsOn), Description: ptr(subject.Name + " runs on " + runtime.Name), Evidence: runtimeEvidence(runtime)})
		}
	}
	return v, nil
}
func buildContainerView(g *store.Store, calls []store.Node, subject SubjectDescriptor, capabilities ContainerLayout) View {
	runtime := InferOperationalLayout(subject, capabilities)
	v := View{Type: Container, Elements: []Element{}, Relationships: BuildRuntimeDependencyRelationships(g, calls, runtime), ExternalDependencies: runtime.ExternalDependencies, SystemBoundary: ptr(runtime.SystemBoundary)}
	internals := []map[string]any{}
	for _, c := range capabilities.Containers {
		internals = append(internals, map[string]any{"name": c.Name, "kind": string(ContainerKind(c)), "packageUnits": sorted(c.PackageUnits), "methods": c.MethodCount, "callSites": c.CallSiteCount, "endpoints": c.EndpointCount, "primaryClasses": c.PrimaryClasses, "whySelected": c.Rationale})
	}
	for _, c := range runtime.Containers {
		kind := ContainerKind(c)
		v.Elements = append(v.Elements, Element{ID: c.ID, Type: ContainerElement, Name: c.Name, Description: ptr("Executable/deployable runtime container inferred from entrypoint, endpoint, and archive evidence"), Kind: ptr(kind), ArchitectureType: ptr(ArchitectureType("application-service")), Responsibility: ptr("Runs the subject software system and owns the deployable JVM execution boundary"), Metadata: map[string]any{"technology": "JVM bytecode", "methods": c.MethodCount, "callSites": c.CallSiteCount, "endpoints": c.EndpointCount, "entrypoints": c.Entrypoints, "primaryClasses": c.PrimaryClasses, "packageUnits": sorted(c.PackageUnits), "internalCapabilities": internals, "whySelected": c.Rationale}})
	}
	return v
}

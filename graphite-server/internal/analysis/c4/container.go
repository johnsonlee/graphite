package c4

import (
	"fmt"
	"sort"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"

	"github.com/johnsonlee/graphite/graphite-server/internal/javastring"
)

func InferContainerLayout(g *store.Store, methods []store.MethodDescriptor, calls []store.Node, endpoints []EndpointEvidence, boundary string, limit int) (ContainerLayout, error) {
	if limit < 0 {
		return ContainerLayout{}, fmt.Errorf("C4 limit must be non-negative")
	}
	classes := []string{}
	methodCounts, endpointCounts := orderedCounts[string]{}, orderedCounts[string]{}
	paths, classesByUnit := map[string][]string{}, map[string][]string{}
	for _, m := range methods {
		classes = append(classes, m.DeclaringClass)
		if IsInternalClass(m.DeclaringClass, boundary) {
			methodCounts.add(InternalPackageUnit(m.DeclaringClass, boundary), 1)
		}
	}
	for _, e := range endpoints {
		classes = append(classes, e.ClassName)
		if IsInternalClass(e.ClassName, boundary) {
			u := InternalPackageUnit(e.ClassName, boundary)
			endpointCounts.add(u, 1)
			if javastring.Trim(e.Path) != "" {
				paths[u] = append(paths[u], e.Path)
			}
		}
	}
	for _, c := range calls {
		classes = append(classes, c.Caller.DeclaringClass, c.Callee.DeclaringClass)
	}
	classUnits := []string{}
	for _, c := range unique(classes) {
		if IsInternalClass(c, boundary) {
			u := InternalPackageUnit(c, boundary)
			if _, ok := classesByUnit[u]; !ok {
				classUnits = append(classUnits, u)
			}
			classesByUnit[u] = append(classesByUnit[u], c)
		}
	}
	traffic := orderedCounts[pair]{}
	external := orderedCounts[string]{}
	for _, c := range calls {
		a, b := c.Caller.DeclaringClass, c.Callee.DeclaringClass
		if IsSyntheticClass(a) || IsSyntheticClass(b) {
			continue
		}
		ai, bi := IsInternalClass(a, boundary), IsInternalClass(b, boundary)
		if ai && bi {
			au, bu := InternalPackageUnit(a, boundary), InternalPackageUnit(b, boundary)
			if au != bu {
				if lexical(bu, au) {
					au, bu = bu, au
				}
				traffic.add(pair{au, bu}, 1)
			}
		} else if ai && !bi {
			external.add(b, 1)
		}
	}
	byUnit := map[string]int{}
	for _, p := range traffic.Keys {
		byUnit[p.From] += traffic.Values[p]
		byUnit[p.To] += traffic.Values[p]
	}
	units := unique(append(append(append([]string{}, methodCounts.Keys...), endpointCounts.Keys...), classUnits...))
	frequency := map[string]int{}
	for _, u := range units {
		for _, t := range unique(unitTokens(u, boundary)) {
			frequency[t]++
		}
	}
	clusters := clusterPackageUnits(units, traffic)
	clusterScore := func(c []string) (int, int) {
		n, traffic := 0, 0
		for _, u := range c {
			n += methodCounts.Values[u] + endpointCounts.Values[u]*10 + byUnit[u]
			traffic += byUnit[u]
		}
		return n, traffic
	}
	sort.SliceStable(clusters, func(i, j int) bool {
		a, at := clusterScore(clusters[i])
		b, bt := clusterScore(clusters[j])
		if a != b {
			return a > b
		}
		if at != bt {
			return at > bt
		}
		return lexical(clusters[i][0], clusters[j][0])
	})
	layout := ContainerLayout{SystemBoundary: boundary, Containers: []ContainerDescriptor{}, UnitToContainerID: map[string]string{}}
	for _, cluster := range take(clusters, limit) {
		scores := map[string]int{}
		representative := cluster[0]
		classes, entrypoints := []string{}, []string{}
		d := ContainerDescriptor{PackageUnits: cluster}
		for _, u := range cluster {
			scores[u] = methodCounts.Values[u]*2 + endpointCounts.Values[u]*10 + byUnit[u]
			if scores[u] > scores[representative] {
				representative = u
			}
			classes = append(classes, classesByUnit[u]...)
			entrypoints = append(entrypoints, paths[u]...)
			d.MethodCount += methodCounts.Values[u]
			d.EndpointCount += endpointCounts.Values[u]
			d.CallSiteCount += byUnit[u]
		}
		d.Name = inferContainerName(cluster, scores, frequency, boundary)
		name := d.Name
		if javastring.Trim(name) == "" {
			name = representative
		}
		d.ID = ContainerIDPrefix + Slugify(name)
		d.Entrypoints = take(sorted(unique(entrypoints)), 5)
		counts := orderedCounts[string]{}
		for _, c := range classes {
			counts.add(c, 1)
		}
		d.PrimaryClasses = []string{}
		for _, c := range take(counts.ranked(), 3) {
			d.PrimaryClasses = append(d.PrimaryClasses, simpleName(c))
		}
		switch {
		case d.EndpointCount > 0:
			d.Rationale = fmt.Sprintf("Grouped from %d tightly-coupled package unit(s) with visible inbound entrypoints; anchored by %s", len(cluster), representative)
		case len(cluster) > 1:
			d.Rationale = fmt.Sprintf("Grouped from %d mutually dependent package units around the structural center %s", len(cluster), representative)
		default:
			d.Rationale = "Derived from the dominant internal package unit " + representative
		}
		layout.Containers = append(layout.Containers, d)
		for _, u := range cluster {
			layout.UnitToContainerID[u] = d.ID
		}
	}
	callCounts, inbound, outbound, externalCounts := map[string]int{}, map[string]int{}, map[string]int{}, map[string]int{}
	for _, c := range calls {
		a, b := c.Caller.DeclaringClass, c.Callee.DeclaringClass
		if IsSyntheticClass(a) || IsSyntheticClass(b) {
			continue
		}
		ac, bc := "", ""
		if IsInternalClass(a, boundary) {
			ac = layout.UnitToContainerID[InternalPackageUnit(a, boundary)]
		}
		if IsInternalClass(b, boundary) {
			bc = layout.UnitToContainerID[InternalPackageUnit(b, boundary)]
		}
		if ac != "" {
			callCounts[ac]++
		}
		if bc != "" {
			callCounts[bc]++
		}
		if ac != "" && bc != "" && ac != bc {
			outbound[ac]++
			inbound[bc]++
		} else if ac != "" && !IsInternalClass(b, boundary) && !IsRuntimeClass(b) {
			externalCounts[ac]++
		}
	}
	for i := range layout.Containers {
		d := &layout.Containers[i]
		d.CallSiteCount = orCount(callCounts, d.ID, d.CallSiteCount)
		d.InboundCrossContainer = inbound[d.ID]
		d.OutboundCrossContainer = outbound[d.ID]
		d.ExternalCallCount = externalCounts[d.ID]
	}
	weights := []WeightedClass{}
	for _, c := range external.Keys {
		weights = append(weights, WeightedClass{ClassName: c, Weight: external.Values[c]})
	}
	var err error
	layout.ExternalDependencies, err = SummarizeExternalDependencies(g, weights, UnboundedModelElements)
	return layout, err
}

func unitTokens(u, boundary string) []string {
	ts := strings.Split(strings.TrimLeft(strings.TrimPrefix(u, boundary), "."), ".")
	out := []string{}
	for _, t := range ts {
		if javastring.Trim(t) != "" {
			out = append(out, t)
		}
	}
	return out
}
func clusterPackageUnits(units []string, traffic orderedCounts[pair]) [][]string {
	adj := map[string]*orderedCounts[string]{}
	for _, p := range traffic.Keys {
		for _, edge := range []pair{p, {p.To, p.From}} {
			if adj[edge.From] == nil {
				adj[edge.From] = &orderedCounts[string]{}
			}
			adj[edge.From].add(edge.To, traffic.Values[p])
		}
	}
	strongest := map[string]string{}
	parents := map[string]string{}
	for _, u := range units {
		parents[u] = u
		if a := adj[u]; a != nil {
			for _, v := range a.Keys {
				best, ok := strongest[u]
				if !ok || a.Values[v] > a.Values[best] || (a.Values[v] == a.Values[best] && lexical(v, best)) {
					strongest[u] = v
				}
			}
		}
	}
	var find func(string) string
	find = func(s string) string {
		if parents[s] != s {
			parents[s] = find(parents[s])
		}
		return parents[s]
	}
	union := func(a, b string) {
		ar, br := find(a), find(b)
		if ar != br {
			parents[br] = ar
		}
	}
	for _, u := range units {
		if n, ok := strongest[u]; ok && strongest[n] == u {
			union(u, n)
		}
	}
	for _, u := range units {
		if find(u) != u {
			continue
		}
		n, ok := strongest[u]
		if !ok {
			continue
		}
		sum := 0
		for _, v := range adj[u].Values {
			sum += v
		}
		if find(n) != u && sum > 0 && adj[u].Values[n]*2 >= sum {
			union(u, n)
		}
	}
	groups := map[string][]string{}
	for _, u := range units {
		r := find(u)
		groups[r] = append(groups[r], u)
	}
	out := [][]string{}
	for _, g := range groups {
		out = append(out, sorted(unique(g)))
	}
	sort.Slice(out, func(i, j int) bool { return lexical(out[i][0], out[j][0]) })
	return out
}
func inferContainerName(units []string, scores, frequency map[string]int, boundary string) string {
	tokens := []string{}
	weights := map[string]float64{}
	for _, u := range units {
		for _, t := range unitTokens(u, boundary) {
			if _, ok := weights[t]; !ok {
				tokens = append(tokens, t)
			}
			weights[t] += float64(orCount(scores, u, 1)) / float64(orCount(frequency, t, 1))
		}
	}
	sort.SliceStable(tokens, func(i, j int) bool { return weights[tokens[i]] > weights[tokens[j]] })
	if len(tokens) == 0 {
		return boundary
	}
	names := []string{HumanizeIdentifier(tokens[0])}
	if len(tokens) >= 2 && weights[tokens[0]] > 0 && weights[tokens[1]] >= weights[tokens[0]]*.7 {
		names = append(names, HumanizeIdentifier(tokens[1]))
	}
	return strings.Join(unique(names), " and ")
}
func InferOperationalLayout(subject SubjectDescriptor, capabilities ContainerLayout) ContainerLayout {
	out := ContainerLayout{SystemBoundary: capabilities.SystemBoundary, Containers: []ContainerDescriptor{}, UnitToContainerID: map[string]string{}, ExternalDependencies: capabilities.ExternalDependencies}
	if subject.Role != "application" {
		return out
	}
	d := ContainerDescriptor{ID: "container:application-runtime", Name: subject.Name + " Runtime", DeclaredKind: ptr("application-runtime"), Rationale: "Selected from C4 semantics as the executable/deployable JVM runtime boundary; package clusters remain internal capability evidence"}
	for _, c := range capabilities.Containers {
		d.PackageUnits = append(d.PackageUnits, c.PackageUnits...)
		d.MethodCount += c.MethodCount
		d.CallSiteCount += c.CallSiteCount
		d.EndpointCount += c.EndpointCount
		d.ExternalCallCount += c.ExternalCallCount
		d.Entrypoints = append(d.Entrypoints, c.Entrypoints...)
		d.PrimaryClasses = append(d.PrimaryClasses, c.PrimaryClasses...)
	}
	if d.EndpointCount > 0 {
		d.DeclaredKind = ptr("application-service")
	}
	d.PackageUnits = sorted(unique(d.PackageUnits))
	d.Entrypoints = take(sorted(unique(d.Entrypoints)), 5)
	d.PrimaryClasses = take(unique(d.PrimaryClasses), 3)
	for _, u := range d.PackageUnits {
		out.UnitToContainerID[u] = d.ID
	}
	out.Containers = append(out.Containers, d)
	return out
}
func ContainerKind(c ContainerDescriptor) ElementKind {
	if c.DeclaredKind != nil {
		return ElementKind(*c.DeclaredKind)
	}
	if c.EndpointCount > 0 {
		return Interface
	}
	dominant := max(c.InboundCrossContainer, c.OutboundCrossContainer, c.ExternalCallCount)
	if c.ExternalCallCount > 0 && c.ExternalCallCount == dominant {
		return Integration
	}
	if c.OutboundCrossContainer > c.InboundCrossContainer {
		return Orchestrator
	}
	if c.InboundCrossContainer > c.OutboundCrossContainer {
		return SharedCapability
	}
	return Capability
}
func dependencyLayer(kind ElementKind) int {
	switch kind {
	case Interface, Entrypoint:
		return 0
	case Orchestrator, Integration:
		return 1
	case SharedCapability:
		return 3
	}
	return 2
}
func canonicalPair(a, b string, ak, bk ElementKind) pair {
	if dependencyLayer(ak) > dependencyLayer(bk) {
		return pair{b, a}
	}
	return pair{a, b}
}
func runtimeBoundaryLibraries(deps []ExternalDependency, rels []Relationship) []ExternalDependency {
	sources := map[string]bool{}
	for _, r := range rels {
		sources[r.From] = true
	}
	out := []ExternalDependency{}
	for _, d := range deps {
		if !sources[d.ID] {
			out = append(out, d)
		}
	}
	if len(out) == 0 {
		return deps
	}
	return out
}
func runtimeBoundaryLibraryIDs(g *store.Store, deps []ExternalDependency) map[string]bool {
	byArtifact := map[string]string{}
	all := map[string]bool{}
	for _, d := range deps {
		if d.Kind != RuntimeDependency {
			if a, ok := ArtifactNameFromDependencyID(d.ID); ok {
				byArtifact[a] = d.ID
				all[d.ID] = true
			}
		}
	}
	hasDownstream := map[string]bool{}
	if g != nil {
		for a, downstream := range g.Metadata.ArtifactDependencies {
			from := byArtifact[a]
			if from == "" {
				continue
			}
			for b, w := range downstream {
				to := byArtifact[b]
				if to != "" && to != from && w > 0 {
					hasDownstream[from] = true
				}
			}
		}
	}
	out := map[string]bool{}
	for id := range all {
		if !hasDownstream[id] {
			out[id] = true
		}
	}
	if len(out) == 0 {
		return all
	}
	return out
}
func BuildRuntimeDependencyRelationships(g *store.Store, calls []store.Node, layout ContainerLayout) []Relationship {
	out := []Relationship{}
	if len(layout.Containers) != 1 {
		return out
	}
	container := layout.Containers[0]
	deps := map[string]ExternalDependency{}
	for _, d := range layout.ExternalDependencies {
		deps[d.ID] = d
	}
	weights := orderedCounts[string]{}
	for _, c := range calls {
		a, b := c.Caller.DeclaringClass, c.Callee.DeclaringClass
		if IsSyntheticClass(a) || IsSyntheticClass(b) || !IsInternalClass(a, layout.SystemBoundary) || IsInternalClass(b, layout.SystemBoundary) {
			continue
		}
		id := DependencyIDPrefix + ExternalDependencyKey(g, b)
		if _, ok := deps[id]; ok {
			weights.add(id, 1)
		}
	}
	for _, id := range weights.ranked() {
		d := deps[id]
		kind, typ, verb := DependsOn, RelationshipType(Uses), " uses "
		if d.Kind == RuntimeDependency {
			kind, typ, verb = RunsOn, RelationshipType(RunsOn), " runs on "
		}
		out = append(out, Relationship{From: container.ID, To: id, Type: typ, Kind: ptr(kind), Description: ptr(container.Name + verb + d.Name), Weight: ptr(weights.Values[id]), Evidence: map[string]any{"crossBoundaryCalls": weights.Values[id], "source": d.Source, "confidence": d.Confidence}})
	}
	boundary := runtimeBoundaryLibraryIDs(g, layout.ExternalDependencies)
	for _, library := range layout.ExternalDependencies {
		if library.Kind == RuntimeDependency || !boundary[library.ID] {
			continue
		}
		for _, runtime := range layout.ExternalDependencies {
			if runtime.Kind == RuntimeDependency {
				out = append(out, Relationship{From: library.ID, To: runtime.ID, Type: RelationshipType(RunsOn), Kind: ptr(RunsOn), Description: ptr(library.Name + " runs on " + runtime.Name), Evidence: runtimeEvidence(runtime)})
			}
		}
	}
	return out
}
func runtimeEvidence(d ExternalDependency) map[string]any {
	return map[string]any{"source": d.Source, "kind": string(d.Kind)}
}

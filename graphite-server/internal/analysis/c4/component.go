package c4

import (
	"sort"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func ComponentID(id string) string {
	return ComponentIDPrefix + strings.TrimPrefix(id, ContainerIDPrefix)
}
func ComponentKind(endpoints, inbound, outbound, external int) ElementKind {
	if endpoints > 0 {
		return Entrypoint
	}
	if external > max(inbound, outbound) {
		return Integration
	}
	if outbound > inbound {
		return Orchestrator
	}
	if inbound > outbound {
		return SharedCapability
	}
	if inbound > 0 || outbound > 0 {
		return Coordination
	}
	return DomainComponent
}
func componentResponsibility(name string, endpoints, inbound, outbound, external int) string {
	switch ComponentKind(endpoints, inbound, outbound, external) {
	case Entrypoint:
		return "Accepts external requests and translates them into internal application operations"
	case Integration:
		return "Connects the " + name + " capability to external collaborators and dependency boundaries"
	case Orchestrator:
		return "Coordinates work across neighboring capabilities inside " + name
	case SharedCapability:
		return "Provides a shared internal capability that other containers depend on through " + name
	case Coordination:
		return "Sits on a coordination path inside " + name + " and participates in cross-container flows"
	default:
		return "Implements a structurally central part of the " + name + " capability"
	}
}
func componentDependencyKind(source, target ElementKind) RelationshipKind {
	if source == Interface || source == Entrypoint {
		return RoutesTo
	}
	if source == Orchestrator {
		return Orchestrates
	}
	if target == SharedCapability || target == Integration {
		return Uses
	}
	return CollaboratesWith
}
func componentDependencyDescription(kind RelationshipKind, a, b string) string {
	switch kind {
	case RoutesTo:
		return a + " routes work to " + b
	case Orchestrates:
		return a + " orchestrates " + b
	case Uses:
		return a + " uses " + b
	}
	return a + " collaborates with " + b
}
func ClassScore(class, boundary string, methods, calls, endpoints, inbound, outbound, classCount int) int {
	score := (inbound+outbound)*200 + endpoints*100 + calls + methods
	relative := strings.TrimPrefix(class, boundary+".")
	pkg := ""
	if i := strings.LastIndex(relative, "."); i >= 0 {
		pkg = relative[:i]
	}
	signal := false
	for _, token := range strings.Split(pkg, ".") {
		switch token {
		case "common", "shared", "support", "util", "utils":
			signal = true
		}
	}
	for _, suffix := range []string{"Helper", "Support", "Util", "Utils"} {
		signal = signal || strings.HasSuffix(simpleName(class), suffix)
	}
	if (ClassUtilityEvidence{HasNamingSignal: signal, HasEntrypointEvidence: endpoints > 0, HasCrossCapabilityEvidence: inbound > 0 || outbound > 0, IsSoleCapabilityClass: classCount <= 1}).IsLowSignalHelper() {
		score /= 4
	}
	return score
}
func BuildComponentView(methods []store.MethodDescriptor, calls []store.Node, endpoints []EndpointEvidence, boundary string, limit int, subject SubjectDescriptor, layout ContainerLayout) View {
	runtime := InferOperationalLayout(subject, layout)
	view := View{Type: Component, Elements: []Element{}, Relationships: []Relationship{}}
	if len(runtime.Containers) == 0 {
		view.SystemBoundary = ptr(boundary)
		view.SkippedReason = ptr("No C4 runtime container inferred; library/package code is not promoted to component scope without a runtime boundary")
		return view
	}
	runtimeContainer := runtime.Containers[0]
	byID := map[string]ContainerDescriptor{}
	for _, c := range layout.Containers {
		byID[c.ID] = c
	}
	methodsByClass, endpointCounts, callCounts := orderedCounts[string]{}, orderedCounts[string]{}, orderedCounts[string]{}
	for _, m := range methods {
		if IsInternalClass(m.DeclaringClass, boundary) {
			methodsByClass.add(m.DeclaringClass, 1)
		}
	}
	for _, e := range endpoints {
		if IsInternalClass(e.ClassName, boundary) {
			endpointCounts.add(e.ClassName, 1)
		}
	}
	external, capabilityCalls := map[string]int{}, map[string]int{}
	relationships := orderedCounts[pair]{}
	for _, call := range calls {
		a, b := call.Caller.DeclaringClass, call.Callee.DeclaringClass
		ai, bi := IsInternalClass(a, boundary), IsInternalClass(b, boundary)
		if !ai && !bi {
			continue
		}
		ac, bc := "", ""
		if ai {
			callCounts.add(a, 1)
			ac = layout.UnitToContainerID[InternalPackageUnit(a, boundary)]
		}
		if bi {
			callCounts.add(b, 1)
			bc = layout.UnitToContainerID[InternalPackageUnit(b, boundary)]
		}
		if ac != "" {
			capabilityCalls[ac]++
		}
		if bc != "" {
			capabilityCalls[bc]++
		}
		if !ai || !bi {
			if ai && !IsRuntimeClass(b) && ac != "" {
				external[ac]++
			}
			continue
		}
		if ac != "" && bc != "" && ac != bc {
			relationships.add(canonicalPair(ComponentID(ac), ComponentID(bc), ContainerKind(byID[ac]), ContainerKind(byID[bc])), 1)
		}
	}
	classes := unique(append(append(append([]string{}, methodsByClass.Keys...), endpointCounts.Keys...), callCounts.Keys...))
	byCapability := map[string][]string{}
	endpointsByCapability := map[string]int{}
	for _, class := range classes {
		id := layout.UnitToContainerID[InternalPackageUnit(class, boundary)]
		if _, ok := byID[id]; !ok {
			continue
		}
		byCapability[id] = append(byCapability[id], class)
		if count, ok := endpointCounts.Values[class]; ok {
			endpointsByCapability[id] += count
		}
	}
	capScore := func(c ContainerDescriptor) int {
		return orCount(endpointsByCapability, c.ID, c.EndpointCount)*300 + (c.InboundCrossContainer+c.OutboundCrossContainer)*200 + orCount(external, c.ID, c.ExternalCallCount)*100 + orCount(capabilityCalls, c.ID, c.CallSiteCount) + c.MethodCount
	}
	ranked := append([]ContainerDescriptor{}, layout.Containers...)
	sort.SliceStable(ranked, func(i, j int) bool {
		a, b := capScore(ranked[i]), capScore(ranked[j])
		if a != b {
			return a > b
		}
		return lexical(ranked[i].Name, ranked[j].Name)
	})
	ranked = take(ranked, limit)
	selected := map[string]bool{}
	kinds := map[string]ElementKind{}
	for _, c := range ranked {
		selected[c.ID] = true
		ep, ext := orCount(endpointsByCapability, c.ID, c.EndpointCount), orCount(external, c.ID, c.ExternalCallCount)
		kind := ComponentKind(ep, c.InboundCrossContainer, c.OutboundCrossContainer, ext)
		kinds[c.ID] = kind
		reps := append([]string{}, byCapability[c.ID]...)
		sort.SliceStable(reps, func(i, j int) bool {
			score := func(class string) int {
				return ClassScore(class, boundary, methodsByClass.Values[class], callCounts.Values[class], endpointCounts.Values[class], c.InboundCrossContainer, c.OutboundCrossContainer, len(byCapability[c.ID]))
			}
			return score(reps[i]) > score(reps[j])
		})
		if len(reps) == 0 {
			reps = c.PrimaryClasses
		}
		reps = take(reps, 5)
		reasons := []string{}
		if ep > 0 {
			reasons = append(reasons, "entrypoint-facing capability")
		}
		if c.InboundCrossContainer > 0 {
			reasons = append(reasons, "used by neighboring capabilities")
		}
		if c.OutboundCrossContainer > 0 {
			reasons = append(reasons, "depends on neighboring capabilities")
		}
		if ext > 0 {
			reasons = append(reasons, "external dependency touchpoint")
		}
		if len(reasons) == 0 {
			reasons = append(reasons, "cohesive internal capability")
		}
		view.Elements = append(view.Elements, Element{ID: ComponentID(c.ID), Type: ComponentElement, Name: c.Name, Kind: ptr(kind), ArchitectureType: ptr(kind.ArchitectureType()), Responsibility: ptr(componentResponsibility(runtimeContainer.Name, ep, c.InboundCrossContainer, c.OutboundCrossContainer, ext)), Metadata: map[string]any{"fullName": strings.Join(sorted(c.PackageUnits), ","), "container": runtimeContainer.Name, "containerId": runtimeContainer.ID, "methods": c.MethodCount, "callSites": orCount(capabilityCalls, c.ID, c.CallSiteCount), "endpoints": ep, "incomingCrossContainerCalls": c.InboundCrossContainer, "outgoingCrossContainerCalls": c.OutboundCrossContainer, "packageUnits": sorted(c.PackageUnits), "classes": reps, "entrypoints": c.Entrypoints, "whySelected": reasons}})
	}
	for _, p := range relationships.ranked() {
		a, b := ContainerIDPrefix+strings.TrimPrefix(p.From, ComponentIDPrefix), ContainerIDPrefix+strings.TrimPrefix(p.To, ComponentIDPrefix)
		if !selected[a] || !selected[b] {
			continue
		}
		kind := componentDependencyKind(kinds[a], kinds[b])
		weight := relationships.Values[p]
		view.Relationships = append(view.Relationships, Relationship{From: p.From, To: p.To, Type: RelationshipType(kind), Kind: ptr(kind), Description: ptr(componentDependencyDescription(kind, byID[a].Name, byID[b].Name)), Weight: ptr(weight), Evidence: map[string]any{"calls": weight}})
	}
	view.Relationships = SelectReadableRelationships(view.Relationships)
	return view
}

// Package c4 contains the native architecture inference model. Its lower layers
// mirror Graphite's Kotlin C4 implementation; workspace generation and rendering
// are separate layers and are not implied by these inference primitives.
package c4

const (
	DefaultSystemBoundary     = "(default)"
	SubjectApplicationID      = "system:application"
	SubjectLibraryID          = "system:library"
	SubjectFallbackID         = "system:subject"
	ArtifactIDPrefix          = "artifact:"
	ComponentIDPrefix         = "component:"
	ContainerIDPrefix         = "container:"
	DependencyIDPrefix        = "dependency:"
	DependencyLibraryIDPrefix = "dependency:library:"
	NamespaceIDPrefix         = "namespace:"
	RuntimeIDPrefix           = "runtime:"
	UnboundedModelElements    = 2147483647
)

type Level string

const (
	Context   Level = "context"
	Container Level = "container"
	Component Level = "component"
	All       Level = "all"
)

func ModelLevels() []Level { return []Level{Context, Container, Component, All} }
func ParseLevel(value string) Level {
	for _, level := range ModelLevels() {
		if string(level) == value {
			return level
		}
	}
	return All
}

type ElementType string

const (
	Person           ElementType = "person"
	SoftwareSystem   ElementType = "softwareSystem"
	ContainerElement ElementType = "container"
	ComponentElement ElementType = "component"
)

func ParseElementType(value string) ElementType {
	switch ElementType(value) {
	case Person, SoftwareSystem, ContainerElement, ComponentElement:
		return ElementType(value)
	}
	return SoftwareSystem
}

type ElementKind string

const (
	Actor              ElementKind = "actor"
	Application        ElementKind = "application"
	Library            ElementKind = "library"
	Runtime            ElementKind = "runtime"
	ExternalSystem     ElementKind = "external-system"
	ApplicationRuntime ElementKind = "application-runtime"
	ApplicationService ElementKind = "application-service"
	Interface          ElementKind = "interface"
	Integration        ElementKind = "integration"
	Orchestrator       ElementKind = "orchestrator"
	SharedCapability   ElementKind = "shared-capability"
	Capability         ElementKind = "capability"
	Entrypoint         ElementKind = "entrypoint"
	Coordination       ElementKind = "coordination"
	DomainComponent    ElementKind = "domain-component"
)

func ParseElementKind(value string) (ElementKind, bool) {
	switch ElementKind(value) {
	case Actor, Application, Library, Runtime, ExternalSystem, ApplicationRuntime, ApplicationService, Interface, Integration, Orchestrator, SharedCapability, Capability, Entrypoint, Coordination, DomainComponent:
		return ElementKind(value), true
	}
	return "", false
}

type ArchitectureType string

func (kind ElementKind) ArchitectureType() ArchitectureType {
	switch kind {
	case Actor:
		return "actor"
	case Application:
		return "software-system"
	case Library:
		return "library"
	case Runtime:
		return "runtime-platform"
	case ExternalSystem:
		return "external-system"
	case ApplicationRuntime:
		return "application-runtime"
	case ApplicationService, Interface, Integration, Orchestrator, Coordination, Entrypoint:
		return "application-service"
	case SharedCapability, Capability, DomainComponent:
		return "application-component"
	}
	return ""
}

type DependencyKind string

const (
	RuntimeDependency        DependencyKind = "runtime"
	LibraryDependency        DependencyKind = "library"
	ExternalSystemDependency DependencyKind = "external-system"
)

func ParseDependencyKind(value string) DependencyKind {
	switch DependencyKind(value) {
	case RuntimeDependency, LibraryDependency, ExternalSystemDependency:
		return DependencyKind(value)
	}
	return ExternalSystemDependency
}
func (kind DependencyKind) ArchitectureType() ArchitectureType {
	switch kind {
	case RuntimeDependency:
		return "runtime-platform"
	case LibraryDependency:
		return "external-library"
	default:
		return "external-system"
	}
}
func (kind DependencyKind) ElementKind() ElementKind {
	switch kind {
	case RuntimeDependency:
		return Runtime
	case LibraryDependency:
		return Library
	default:
		return ExternalSystem
	}
}

type RelationshipType string
type RelationshipKind string

const (
	Uses             RelationshipKind = "uses"
	RunsOn           RelationshipKind = "runs-on"
	BuildsOn         RelationshipKind = "builds-on"
	DependsOn        RelationshipKind = "depends-on"
	RoutesTo         RelationshipKind = "routes-to"
	Orchestrates     RelationshipKind = "orchestrates"
	CollaboratesWith RelationshipKind = "collaborates-with"
)

func ParseRelationshipKind(value string) (RelationshipKind, bool) {
	switch RelationshipKind(value) {
	case Uses, RunsOn, BuildsOn, DependsOn, RoutesTo, Orchestrates, CollaboratesWith:
		return RelationshipKind(value), true
	}
	return "", false
}
func ParseRelationshipType(value string) RelationshipType {
	if kind, ok := ParseRelationshipKind(value); ok && kind != DependsOn {
		return RelationshipType(value)
	}
	return RelationshipType(Uses)
}

type EndpointEvidence struct{ ClassName, Path string }
type ExternalDependency struct {
	ID, Name                   string
	Weight                     int
	Source                     string
	Kind                       DependencyKind
	Confidence, Responsibility string
	Artifacts                  []string
}
type ContainerDescriptor struct {
	ID, Name                                                                                                    string
	PackageUnits                                                                                                []string
	MethodCount, CallSiteCount, EndpointCount, InboundCrossContainer, OutboundCrossContainer, ExternalCallCount int
	Entrypoints, PrimaryClasses                                                                                 []string
	Rationale                                                                                                   string
	DeclaredKind                                                                                                *string
}
type ContainerLayout struct {
	SystemBoundary       string
	Containers           []ContainerDescriptor
	UnitToContainerID    map[string]string
	ExternalDependencies []ExternalDependency
}
type SubjectDescriptor struct {
	ID, Name, Role, Description, Responsibility               string
	ActorID, ActorName, ActorDescription, ActorResponsibility *string
}
type MainReachability struct{ MainMethodCount, ReachableInternalMethodCount, ReachableInternalClassCount, ReachableExternalTargetCount int }
type ClassUtilityEvidence struct{ HasNamingSignal, HasEntrypointEvidence, HasCrossCapabilityEvidence, IsSoleCapabilityClass bool }

func (e ClassUtilityEvidence) IsLowSignalHelper() bool {
	return e.HasNamingSignal && !e.HasEntrypointEvidence && !e.HasCrossCapabilityEvidence && !e.IsSoleCapabilityClass
}

type ManifestMetadata struct{ MainClass, StartClass *string }
type ContextDependencyCollapse struct {
	Dependencies                                 []ExternalDependency
	DependencyIDToContextID, ArtifactToContextID map[string]string
}
type ViewModel struct {
	Level                               Level
	AvailableLevels                     []Level
	Context, Container, Component, View *View
}
type Element struct {
	ID                  string
	Type                ElementType
	Name                string
	Description         *string
	Kind                *ElementKind
	ArchitectureType    *ArchitectureType
	Responsibility      *string
	Metadata            any
	ExtensionProperties map[string]any
}
type Relationship struct {
	From, To    string
	Type        RelationshipType
	Kind        *RelationshipKind
	Description *string
	Weight      *int
	Evidence    any
	Properties  map[string]any
}
type View struct {
	Type                          Level
	Elements                      []Element
	Relationships                 []Relationship
	ExternalDependencies          []ExternalDependency
	SystemBoundary, SkippedReason *string
	Properties                    map[string]any
}

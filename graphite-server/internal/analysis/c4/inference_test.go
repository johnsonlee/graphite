package c4

import (
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func method(class, name string) store.MethodDescriptor {
	return store.MethodDescriptor{DeclaringClass: class, Name: name, ReturnType: "void"}
}
func call(caller, callee store.MethodDescriptor) store.Node {
	return store.Node{Kind: "CallSiteNode", Caller: caller, Callee: callee}
}

func TestSystemBoundarySingleRootAndDominantNamespace(t *testing.T) {
	public := method("okhttp3.OkHttpClient", "newCall")
	internal := method("okhttp3.internal.connection.RealConnection", "connect")
	if got := DeriveSystemBoundary([]store.MethodDescriptor{public, internal}, []store.Node{call(public, internal)}); got != "okhttp3" {
		t.Fatalf("boundary %s", got)
	}
	if DominantNamespace("org.apache.lucene") != "org.apache" || DominantNamespace("okhttp3.internal.connection") != "okhttp3" {
		t.Fatal("namespace roots differ from Kotlin")
	}
	if got := InternalPackageUnit(internal.DeclaringClass, "okhttp3"); got != "okhttp3.internal" {
		t.Fatalf("unit %s", got)
	}
	if !IsInternalClass(public.DeclaringClass, "okhttp3") || IsInternalClass("okhttp30.Client", "okhttp3") {
		t.Fatal("class boundary containment mismatch")
	}
}
func TestSystemBoundaryDominanceThresholdAndStableRootTies(t *testing.T) {
	makeMethods := func(api, service, catalog int) []store.MethodDescriptor {
		methods := []store.MethodDescriptor{}
		for _, group := range []struct {
			class string
			count int
		}{{"com.acme.checkout.api.Controller", api}, {"com.acme.checkout.service.Service", service}, {"com.acme.catalog.Catalog", catalog}} {
			for i := 0; i < group.count; i++ {
				methods = append(methods, method(group.class, "run"))
			}
		}
		return methods
	}
	if got := DeriveSystemBoundary(makeMethods(9, 8, 3), nil); got != "com.acme.checkout" {
		t.Fatalf("85 percent threshold: %s", got)
	}
	if got := DeriveSystemBoundary(makeMethods(8, 8, 4), nil); got != "com.acme" {
		t.Fatalf("80 percent boundary: %s", got)
	}
	if got := DeriveSystemBoundary([]store.MethodDescriptor{method("com.first.C", "run"), method("org.second.C", "run")}, nil); got != "com.first" {
		t.Fatalf("first tied root: %s", got)
	}
}
func TestBoundaryEmptySyntheticFallbackAndPackageUnits(t *testing.T) {
	caller := method("com.acme.checkout.Main", "main")
	calls := []store.Node{call(caller, method("java.lang.System", "run"))}
	if got := DeriveSystemBoundary(nil, calls); got != "com.acme.checkout" {
		t.Fatalf("caller fallback: %s", got)
	}
	for _, methods := range [][]store.MethodDescriptor{nil, {method("Main", "run")}, {method("sootup.dummy.Main", "run")}} {
		if got := DeriveSystemBoundary(methods, nil); got != DefaultSystemBoundary {
			t.Fatalf("default: %s", got)
		}
	}
	if got := DeriveSystemBoundary([]store.MethodDescriptor{method("sootup.dummy.Main", "run")}, calls); got != DefaultSystemBoundary {
		t.Fatalf("synthetic methods must not trigger caller fallback: %s", got)
	}
	cases := map[string]string{"com.acme.checkout.api.v2.Controller": "com.acme.checkout.api", "com.acme.checkout.Main": "com.acme.checkout", "org.postgresql.Driver": "org.postgresql", "Plain": "(default)"}
	for class, want := range cases {
		if got := InternalPackageUnit(class, "com.acme.checkout"); got != want {
			t.Errorf("%s unit %s want %s", class, got, want)
		}
	}
	if !IsSyntheticClass("sootup.dummy.Main") || IsSyntheticClass("sootup.dummyish.Main") || !IsRuntimeClass("jakarta.servlet.Servlet") || !IsRuntimeClass("kotlin.Unit") || IsJavaRuntimeClass("javafx.Node") {
		t.Fatal("runtime/synthetic classification mismatch")
	}
	if got := HumanizeIdentifier("checkoutHTTP_service-name"); got != "Checkout Http Service Name" {
		t.Fatalf("humanized %q", got)
	}
}
func TestExternalDependenciesPreferArtifactAndAggregateRuntimeEvidence(t *testing.T) {
	graph := &store.Store{Metadata: store.Metadata{ClassOrigins: map[string]string{"org.postgresql.Driver": "BOOT-INF/lib/postgresql-42.7.3.jar", "java.special.Type": "/libs/platform-shim-1.jar"}}}
	if got := ExternalDependencyKey(graph, "java.special.Type"); got != "artifact:platform-shim-1" {
		t.Fatalf("artifact must precede runtime classification: %s", got)
	}
	got, err := SummarizeExternalDependencies(graph, []WeightedClass{{"org.postgresql.Driver", 3}, {"java.util.List", 1}, {"java.lang.String", 1}, {"com.partner.payment.PaymentGateway", 1}}, UnboundedModelElements)
	if err != nil {
		t.Fatal(err)
	}
	want := []ExternalDependency{
		{ID: "dependency:artifact:postgresql-42.7.3", Name: "postgresql-42.7.3", Weight: 3, Source: "artifact", Kind: LibraryDependency, Confidence: "high", Responsibility: "Provides reusable library capabilities linked from the application runtime", Artifacts: []string{}},
		{ID: "dependency:runtime:java", Name: "Java Runtime", Weight: 2, Source: "runtime", Kind: RuntimeDependency, Confidence: "high", Responsibility: "Provides language and platform runtime services used by the application", Artifacts: []string{}},
		{ID: "dependency:namespace:com.partner.payment", Name: "com.partner.payment", Weight: 1, Source: "namespace", Kind: ExternalSystemDependency, Confidence: "medium", Responsibility: "Represents an inferred external software system boundary grouped from referenced classes", Artifacts: []string{}},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %#v want %#v", got, want)
	}
}
func TestExternalDependencyTieOrderLimitAndNamespaceRules(t *testing.T) {
	weights := []WeightedClass{{"scala.Option", 2}, {"kotlin.Unit", 2}, {"okhttp3.internal.connection.RealConnection", 1}}
	got, err := SummarizeExternalDependencies(nil, weights, 1)
	if err != nil || len(got) != 1 || got[0].ID != "dependency:runtime:scala" || got[0].Name != "Scala Runtime" {
		t.Fatalf("stable tie result %#v error %v", got, err)
	}
	zero, err := SummarizeExternalDependencies(nil, weights, 0)
	if err != nil || len(zero) != 0 {
		t.Fatalf("zero limit %#v error %v", zero, err)
	}
	if _, err := SummarizeExternalDependencies(nil, weights, -1); err == nil {
		t.Fatal("negative limit accepted")
	}
	for _, tc := range []struct{ in, want string }{{"okhttp3.internal.connection.RealConnection", "okhttp3"}, {"com.partner.payment.PaymentGateway", "com.partner.payment"}, {"org.postgresql.Driver", "org.postgresql"}, {"PlainClass", "PlainClass"}, {"org..apache.lucene.Index", "org.apache.lucene"}} {
		if got := NamespaceGroup(tc.in); got != tc.want {
			t.Errorf("namespace %s got %s want %s", tc.in, got, tc.want)
		}
	}
	for _, tc := range []struct {
		in, want string
		ok       bool
	}{{" /libs/api-2.0.jar/ ", "api-2.0", true}, {"/", "", false}, {"  ", "", false}, {"library.JAR", "library.JAR", true}} {
		got, ok := ArtifactKey(tc.in)
		if got != tc.want || ok != tc.ok {
			t.Errorf("artifact %q = %q,%v", tc.in, got, ok)
		}
	}
	if ArtifactBaseName("postgresql-42.7.3") != "postgresql" || ArtifactBaseName("http-client") != "http-client" {
		t.Fatal("artifact version stripping differs")
	}
	if got, ok := ArtifactNameFromDependencyID("dependency:artifact:lib-1"); !ok || got != "lib-1" {
		t.Fatalf("artifact id %q,%v", got, ok)
	}
}
func TestMainReachabilityCyclesMissingMethodsAndSyntheticTargets(t *testing.T) {
	main := method("com.acme.Main", "main")
	main.ParameterTypes = []string{"java.lang.String[]"}
	worker := method("com.acme.Worker", "run")
	absent := method("com.acme.Absent", "missing")
	external := method("java.util.List", "size")
	synthetic := method("sootup.dummy.Entry", "main")
	calls := []store.Node{call(main, worker), call(worker, main), call(worker, external), call(main, external), call(worker, absent), call(main, synthetic)}
	want := MainReachability{MainMethodCount: 1, ReachableInternalMethodCount: 2, ReachableInternalClassCount: 2, ReachableExternalTargetCount: 2}
	if got := AnalyzeMainReachability([]store.MethodDescriptor{main, worker}, calls, "com.acme", nil); got != want {
		t.Fatalf("got %#v want %#v", got, want)
	}
	if got := AnalyzeMainReachability([]store.MethodDescriptor{worker}, calls, "com.acme", nil); got != (MainReachability{}) {
		t.Fatalf("non-main roots %#v", got)
	}
	wrongReturn := worker
	wrongReturn.ReturnType = "int"
	if got := AnalyzeMainReachability([]store.MethodDescriptor{main, wrongReturn}, calls, "com.acme", nil); got != (MainReachability{1, 1, 1, 2}) {
		t.Fatalf("method return type identity ignored: %#v", got)
	}
}
func TestPreferredStartClassAndOutsideBoundaryMainAccounting(t *testing.T) {
	a := method("com.acme.Main", "main")
	a.ParameterTypes = []string{"java.lang.String[]"}
	b := method("org.other.Main", "main")
	b.ParameterTypes = []string{"java.lang.String[]"}
	preferred := "org.other.Main"
	got := AnalyzeMainReachability([]store.MethodDescriptor{a, b}, nil, "com.acme", &preferred)
	if got != (MainReachability{1, 1, 0, 0}) {
		t.Fatalf("outside-boundary main accounting %#v", got)
	}
	preferred = "missing.Start"
	got = AnalyzeMainReachability([]store.MethodDescriptor{a, b}, nil, "com.acme", &preferred)
	if got != (MainReachability{2, 2, 1, 0}) {
		t.Fatalf("preferred fallback %#v", got)
	}
}
func TestWireDefaultsAndArchitectureMappings(t *testing.T) {
	if ParseLevel("unknown") != All || ParseLevel("CONTEXT") != All || ParseLevel("context") != Context || ParseElementType("unknown") != SoftwareSystem {
		t.Fatal("wire defaults mismatch")
	}
	if ParseDependencyKind("unknown") != ExternalSystemDependency || ParseRelationshipType("depends-on") != "uses" {
		t.Fatal("dependency/relationship defaults mismatch")
	}
	if _, ok := ParseElementKind("unknown"); ok {
		t.Fatal("unknown element kind accepted")
	}
	if Runtime.ArchitectureType() != "runtime-platform" || Library.ArchitectureType() != "library" || LibraryDependency.ArchitectureType() != "external-library" || Entrypoint.ArchitectureType() != "application-service" || Capability.ArchitectureType() != "application-component" {
		t.Fatal("architecture mappings mismatch")
	}
	if !(ClassUtilityEvidence{HasNamingSignal: true}).IsLowSignalHelper() || (ClassUtilityEvidence{HasNamingSignal: true, HasEntrypointEvidence: true}).IsLowSignalHelper() {
		t.Fatal("utility evidence classification mismatch")
	}
}

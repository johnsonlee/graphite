package c4

import (
	"io"
	"strings"
	"unicode"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func ParseManifest(content string) ManifestMetadata {
	attrs := map[string]string{}
	key := ""
	for _, line := range strings.Split(strings.NewReplacer("\r\n", "\n", "\r", "\n").Replace(content), "\n") {
		switch {
		case strings.TrimSpace(line) == "":
			key = ""
		case strings.HasPrefix(line, " ") && key != "":
			attrs[key] += line[1:]
		case strings.Contains(line, ":"):
			kv := strings.SplitN(line, ":", 2)
			key = strings.TrimSpace(kv[0])
			attrs[key] = strings.TrimSpace(kv[1])
		}
	}
	m := ManifestMetadata{}
	if v, ok := attrs["Main-Class"]; ok {
		m.MainClass = ptr(v)
	}
	if v, ok := attrs["Start-Class"]; ok {
		m.StartClass = ptr(v)
	}
	return m
}
func ReadManifest(g *store.Store) ManifestMetadata {
	if g == nil {
		return ManifestMetadata{}
	}
	entries, err := g.ResourceList("META-INF/MANIFEST.MF")
	if err != nil || len(entries) == 0 {
		return ManifestMetadata{}
	}
	r, err := g.OpenResource(entries[0].Path)
	if err != nil {
		return ManifestMetadata{}
	}
	defer r.Close()
	b, err := io.ReadAll(r)
	if err != nil {
		return ManifestMetadata{}
	}
	return ParseManifest(string(b))
}
func InferSubjectName(boundary string, origin, startClass *string) string {
	if key, ok := ArtifactKey(value(origin)); ok {
		return humanizeArtifact(key, false)
	}
	if s := simpleName(value(startClass)); strings.TrimSpace(s) != "" {
		out := strings.TrimSuffix(strings.TrimSuffix(s, "Application"), "App")
		if strings.TrimSpace(out) != "" {
			return out
		}
		return s
	}
	s := strings.TrimSuffix(strings.TrimPrefix(boundary, "("), ")")
	leaf := simpleName(s)
	if strings.TrimSpace(leaf) == "" {
		leaf = s
	}
	r := []rune(leaf)
	if len(r) > 0 && unicode.IsLower(r[0]) {
		r[0] = unicode.ToTitle(r[0])
	}
	return string(r)
}
func InferSubject(g *store.Store, methods []store.MethodDescriptor, calls []store.Node, endpointCount int, boundary string) SubjectDescriptor {
	manifest := ReadManifest(g)
	hasBoot := false
	if g != nil {
		for _, pattern := range []string{"BOOT-INF/**", "WEB-INF/**"} {
			entries, _ := g.ResourceList(pattern)
			hasBoot = hasBoot || len(entries) > 0
		}
	}
	var origin *string
	start := manifest.StartClass
	if strings.TrimSpace(value(start)) == "" {
		start = nil
	}
	if g != nil && start != nil {
		if s, ok := g.Metadata.ClassOrigins[*start]; ok {
			origin = ptr(s)
		}
	}
	return inferSubjectEvidence(methods, calls, endpointCount, boundary, manifest, hasBoot, origin)
}
func inferSubjectEvidence(methods []store.MethodDescriptor, calls []store.Node, endpoints int, boundary string, manifest ManifestMetadata, hasBoot bool, origin *string) SubjectDescriptor {
	start := manifest.StartClass
	if strings.TrimSpace(value(start)) == "" {
		start = nil
	}
	reach := AnalyzeMainReachability(methods, calls, boundary, start)
	hasMain := false
	for _, m := range methods {
		hasMain = hasMain || IsMainMethod(m)
	}
	main := value(manifest.MainClass)
	bootLauncher := false
	for _, prefix := range []string{"org.springframework.boot.loader.", "org.springframework.boot.loader.launch."} {
		for _, name := range []string{"JarLauncher", "WarLauncher", "PropertiesLauncher"} {
			bootLauncher = bootLauncher || main == prefix+name
		}
	}
	o := value(origin)
	bootOrigin := o == "BOOT-INF/classes/" || o == "WEB-INF/classes/" || strings.HasPrefix(o, "BOOT-INF/lib/") || strings.HasPrefix(o, "WEB-INF/lib/")
	application := (hasBoot && bootLauncher && bootOrigin && reach.MainMethodCount > 0 && reach.ReachableInternalMethodCount > 1 && reach.ReachableInternalClassCount > 1) || (strings.TrimSpace(main) != "" && start != nil && reach.MainMethodCount > 0) || (hasMain && endpoints > 0 && reach.ReachableInternalMethodCount > 1) || (hasMain && reach.ReachableInternalMethodCount >= reach.ReachableExternalTargetCount && reach.ReachableInternalClassCount > 1)
	name := InferSubjectName(boundary, origin, start)
	if application {
		if strings.TrimSpace(name) == "" {
			name = "Application"
		}
		s := SubjectDescriptor{ID: SubjectApplicationID, Name: name, Role: "application", Description: "Executable software system inferred from the Graphite code graph", Responsibility: "Owns the internal runtime containers and orchestrates the primary execution flows", ActorID: ptr("person:operators"), ActorName: ptr("Operators"), ActorDescription: ptr("Operators or launchers starting the executable artifact"), ActorResponsibility: ptr("Starts and operates the executable artifact")}
		if endpoints > 0 {
			s.ActorID = ptr("person:http-clients")
			s.ActorName = ptr("HTTP Clients")
			s.ActorDescription = ptr("External clients invoking detected HTTP endpoints")
			s.ActorResponsibility = ptr("Initiates synchronous request flows into the application boundary")
		}
		return s
	}
	return SubjectDescriptor{ID: SubjectLibraryID, Name: name + " Library", Role: "library", Description: "A reusable library artifact inferred from the analyzed code graph", Responsibility: "Provides reusable capabilities that are linked and invoked by host applications", ActorID: ptr("person:host-applications"), ActorName: ptr("Host Applications"), ActorDescription: ptr("Applications or services that embed and invoke the library"), ActorResponsibility: ptr("Calls into the library and composes it into a larger runnable system")}
}
func describeInvocation(s SubjectDescriptor, n int) string {
	if s.Role == "library" {
		return "Uses " + s.Name + " from a host application context"
	}
	if n > 0 {
		return "Invokes " + s.Name + " through its HTTP interface"
	}
	return "Starts and operates " + s.Name
}
func invocationEvidence(s SubjectDescriptor, n int) map[string]any {
	if s.Role == "library" {
		return map[string]any{"role": "library-consumer"}
	}
	return map[string]any{"endpoints": n}
}

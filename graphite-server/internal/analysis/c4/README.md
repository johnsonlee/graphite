# Native C4 inference and rendering

This package ports the graph-to-C4 pipeline from `origin/main` revision `4e328b0109e13c896b74004823fb049fcb19251a`. It constructs context, container, component, and combined Structurizr workspaces, and renders Mermaid, PlantUML, and Structurizr DSL.

## Public API

```go
BuildModel(graph *store.Store, level string, limit ...int) (map[string]any, error)
InferViewModel(graph *store.Store, level string, limit ...int) (ViewModel, error)
ToWorkspace(model ViewModel) Workspace
EncodeWorkspace(workspace Workspace) map[string]any
DecodeWorkspace(raw map[string]any) (Workspace, error)
RenderMermaid(workspace map[string]any) (string, error)
RenderPlantUML(workspace map[string]any) (string, error)
RenderStructurizrDSL(workspace map[string]any) (string, error)
```

`BuildModel` returns the Structurizr JSON response object. Omitted limits use `2147483647`, matching the reference's unbounded model default. Negative limits return an error. Unknown levels resolve to `all` in the service; HTTP validation belongs to the caller. Text renderers retain the separate reference readability rules: 12 context/container elements, 16 component elements, 200 context/component diagram edges, and component inference degree/edge caps. Rendering does not mutate or truncate the model.

## Source mapping

| Kotlin source | Native implementation |
| --- | --- |
| `C4Types`, `C4ModelConstants`, `C4InferenceConstants` | `types.go` and constants at their inference/rendering consumers |
| `SystemBoundaryDetector` | `boundary.go`: ordered dominance, namespace roots, package units, runtime/synthetic classification |
| `ExternalSystemClassifier` | `external.go`: artifact provenance, namespace/runtime summaries, stable weighted dependency grouping |
| `SubjectDetector` | `subject.go`, `reachability.go`: manifest continuations, Boot archive layout, preferred main reachability, application/library role and invocation evidence |
| `ContainerClusterer` | `container.go`: mutual strongest-neighbor clustering, scoring/naming, runtime layout and boundary library/runtime relationships |
| `ComponentSelector` | `component.go`: representative classes, helper penalties, capability ranking/kinds, directional relationship selection |
| `C4ModelInferer` | `model.go`: all view orchestration, context artifact-family collapse, dependency relationships and metadata |
| `C4Metadata` | Metadata maps in model/container/component builders; omission, numeric spelling and embedded property serialization in `support.go`/`workspace.go` |
| `C4StructurizrModel`, `C4StructurizrMapper` | `workspace.go`: workspace hierarchy, tags/properties, relationship registration, view references and scopes |
| `C4Support` | `support.go`, `reduction.go`: naming, capacity-sensitive transitive reduction and readable component edge selection |
| `C4RenderingPlan`, `C4TextDiagramRenderer`, `C4MermaidRenderer`, `C4PlantUmlRenderer` | `diagram.go`: visible slices, layered plans, fan-in reductions, truncation notes and format-specific syntax |
| `C4StructurizrDslRenderer` | `dsl.go`: nested model, collision-safe identifiers, relationship deduplication, escaping and scoped views |
| `C4ArchitectureService` | `BuildModel` and the three render functions |

Persisted artifact dependency insertion order comes from `Metadata.ArtifactDependencyOrder` and `ArtifactDependencyTargetOrder`; manually constructed metadata without order uses deterministic lexical order. Method order comes from `MethodList`, and CallSites retain persisted node order.

## Verification

`testdata/MainOracle.java` derives its checkout graph from main's `C4InferenceTest.checkoutFixture`, adds an HTTP endpoint, and emits persisted stores and complete outputs using the unchanged main jar. Six fixture cases cover executable applications, reusable libraries, collapsed artifact families, reverse-lexical equal-weight artifact references, explicit limits 0/1, and a larger correctness graph exceeding text readability caps. Every case checks all four levels under both MAPPED and EAGER loading. Each check compares the entire Structurizr object and exact Mermaid, PlantUML and DSL text, including embedded JSON property strings. See [oracle provenance](testdata/README.md) for regeneration.

Additional tests assert manifest parsing, incomplete Boot evidence, helper scoring, strong direct evidence versus hierarchy reduction, runtime-edge preservation and DSL escaping/identifier collisions. Existing lower-layer tests cover namespace thresholds, external dependency records and main-method identity/reachability.

Validation commands are `go test -race ./internal/analysis/c4` and `go vet ./internal/analysis/c4`; Linux/amd64 test compilation is also checked. These are bounded synthetic correctness fixtures. No performance claim or real-corpus performance comparison is implied.

## Remaining compatibility limits

- Main's internal `C4WireCodec` legacy view-map encode/decode overload is not exposed; the server pipeline uses typed inference directly into the Structurizr mapper. The standalone `DecodeWorkspace` accepts normal Structurizr fields with string properties and reports JSON type errors; it does not reproduce every Gson coercion/default for malformed or non-string external workspace properties. The topology HTTP routes generate their own workspace, so this is outside their normal graph-to-output path.
- Unicode naming still inherits the native string model's isolated-surrogate replacement and Go/Kotlin Unicode case-conversion differences. The checked fixtures do not establish all such naming cases.
- Manifest parsing and Boot classification have source-based tests; the complete persisted-store differential fixtures do not include an actual nested Spring Boot archive. Real-archive integration and full-corpus C4 parity remain additional validation work.

The verified fixtures show no remaining graph-to-workspace or renderer mismatch. They do not establish universal parity or performance on the production graph corpus.

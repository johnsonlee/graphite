# Native C4 port: lower-layer milestone

This package contains working lower-layer inference primitives, not a completed C4 service. No placeholder workspace/model response is exposed. The reference is `origin/main` revision `4e328b0109e13c896b74004823fb049fcb19251a`, whose C4 implementation comprises 20 Kotlin files and 4,948 lines.

Implemented and tested:

- Model data types, wire-level defaults, dependency/element architecture classifications, and helper-evidence classification.
- `DeriveSystemBoundary`, namespace root selection, internal package units, synthetic/runtime detection and identifier naming. Boundary selection preserves input tie ordering, the 0.85 dominance threshold, threefold runner-up separation and four-segment descent cap. Single-root libraries retain their public root.
- Artifact-first external dependency keys, runtime and namespace grouping, naming, confidence/responsibility metadata, artifact name/version handling, and ordered weighted summaries. `SummarizeExternalDependencies` accepts ordered `[]WeightedClass` so tied outputs retain Kotlin linked-map ordering.
- `AnalyzeMainReachability`, including preferred-start fallback, method descriptor identity with return types, cycle handling, declared internal method membership, deduplicated external target classes and synthetic-target exclusion.

These primitives use `store.MethodDescriptor`, CallSite `store.Node` records and `Store.Metadata.ClassOrigins`. Existing native endpoint extraction and resource access are available for the next inference layers.

## Source mapping and remaining work

| Kotlin source | Native status |
| --- | --- |
| `C4Types.kt` | Core enums/descriptors/view structures in `types.go`; metadata-derived property flattening and complete wire conversions await the codec/metadata layer. |
| `C4ModelConstants.kt` | Core identity prefixes/defaults ported; remaining Structurizr property/tag constants await mapping. |
| `C4InferenceConstants.kt` | Namespace/boundary rules applied; remaining view, scoring, clustering and rendering rules await their consumers. |
| `SystemBoundaryDetector.kt` | Implemented in `boundary.go`, with direct source-based behavioral tests. |
| `ExternalSystemClassifier.kt` | Implemented in `external.go`, with artifact precedence, weighting and ordering tests. |
| `SubjectDetector.kt` | Main-method reachability implemented in `reachability.go`; manifest parsing, boot-layout detection, application/library role decision, subject naming and invocation evidence remain. |
| `ContainerClusterer.kt` | Not yet ported: package clustering, capability scoring/naming, operational container layout, artifact dependency collapse and relationship inference. |
| `ComponentSelector.kt` | Not yet ported: representative capabilities/classes, utility penalties, responsibility/kind inference, readable relationship selection. |
| `C4ModelInferer.kt` | Not yet ported: context/container/component/all model orchestration, runtime boundaries and complete view inference. |
| `C4Metadata.kt` | Not yet ported: typed metadata/evidence serialization, omission rules and properties. |
| `C4WireCodec.kt` | Not yet ported: full wire encode/decode, extension properties and numeric/string coercions. |
| `C4StructurizrModel.kt` | Not yet ported: workspace element/view builders and property conversions. |
| `C4StructurizrMapper.kt` | Not yet ported: complete workspace hierarchy, relationship registration, IDs, tags and view scopes. |
| `C4Support.kt` | Not yet ported: diagram naming, transitive reduction, capacity-sensitive edge pruning and shared fan-in reduction. |
| `C4RenderingPlan.kt` | Not yet ported: layered/component layout and bounded diagram plans. |
| `C4TextDiagramRenderer.kt` | Not yet ported: common text rendering orchestration. |
| `C4MermaidRenderer.kt` | Not yet ported. |
| `C4PlantUmlRenderer.kt` | Not yet ported. |
| `C4StructurizrDslRenderer.kt` | Not yet ported. |
| `C4ArchitectureService.kt` | Not yet ported: complete public model/render orchestration. |

The next coherent milestone is subject inference plus container/component inference and complete typed view models, followed by Structurizr mapping/codec and finally all renderers. JSON/DSL must preserve the reference's unbounded model default; text formats have separate readability caps. Implementing only one format or returning guessed models would not satisfy C4 parity.

## Validation and limits

`go test ./internal/analysis/c4` and `go vet ./internal/analysis/c4` pass. Tests cover concrete boundary values, complete dependency records, stable ties, threshold boundaries, duplicate external evidence, method identity, preferred starts and exact reachability counts. These are source-based correctness checks using small constructed inputs; they are not performance evidence or a complete JVM differential oracle.

No HTTP requests, JVM oracle processes, performance runs or concurrent benchmark probes were launched for this milestone. Complete C4 workspace/renderer output remains unverified and unimplemented as itemized above. General isolated-surrogate/Unicode case-conversion fidelity inherited from the native string model also remains outside this milestone's evidence.

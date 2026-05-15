# C4 Architecture Modeling

## Purpose

Graphite derives C4 architecture views from a saved code graph without
user-authored architecture files. The generated model must start from C4
semantics, then use Java archive layout, bytecode metadata, calls, resources,
and artifact provenance as evidence. Packages, jars, and call clusters are not
C4 concepts by themselves.

The output is intended for two users:

- AI agents that need a structured architecture model they can query.
- Engineers that need readable C4 diagrams for review.

## Design Decisions

This section records the main modeling decisions and rejected alternatives.

Graphite makes every modeling decision in this order:

1. **C4 semantic fit**: can the element legally be a Context, Container, or
   Component concept?
2. **Evidence strength**: is there direct graph/archive evidence, or only a
   weak namespace/name signal?
3. **Architectural readability**: will the view explain the system structure,
   or just mirror raw call/dependency volume?
4. **Performance safety**: can the decision be made from persisted graph
   summaries without re-parsing bytecode or expanding the full graph?

When these forces conflict, semantic correctness wins over completeness. It is
better to omit a questionable C4 element than to draw an authoritative-looking
diagram from weak evidence.

| Decision | Chosen Direction | Rejected Alternative | Rationale |
|----------|------------------|----------------------|-----------|
| Start from C4 semantics | Define Context, Container, and Component by C4 meaning first, then map Java evidence onto them | Treat jars, packages, or call clusters as C4 elements directly | The same jar can be a library, an executable, or a nested dependency. C4 concepts must remain stable across archive layouts. |
| Keep one architecture endpoint | Use `GET /api/architecture/c4` with `level` and content negotiation | Separate endpoints for context/container/component/model | Agents discover one capability and can request the level/format they need. This avoids fragmented API discovery. |
| Prefer artifact provenance | Use class origins and artifact dependency metadata before package names | Infer library identity from package prefixes such as `org.apache` | Package prefixes are ambiguous across projects. Artifact provenance ties dependency identity to the actual archive that supplied classes. |
| Treat plain library jars as systems, not containers | Model a library jar as the subject software system at context level | Invent an application/container around every analyzed jar | A C4 container is a runtime boundary. A library jar has no runtime boundary until a host application embeds it. |
| Collapse dependency detail at context level | Show library families when sub-artifacts form one high-level dependency story | Draw every sub-artifact in the context diagram | Context diagrams should explain system landscape. Artifact detail remains in model evidence and lower-level views. |
| Keep generated views automatic | Do not require user-authored architecture model files | Ask users to patch missing C4 elements by hand | The feature is useful to agents only if the saved graph remains the source of truth. Missing precision should be fixed by better evidence extraction/inference. |
| Render diagrams as summaries | Apply transitive reduction, fan-in reduction, labels, and edge caps | Render every relationship from the model | Diagrams are for human review. Full evidence remains available in Structurizr JSON. |
| Reuse persisted graph evidence | Do not re-run bytecode analysis during C4 rendering | Parse all jars again for architecture output | C4 must preserve the 4G heap gate and scale with selected architecture elements, not the full bytecode corpus. |

## Official C4 References

Graphite aligns its model with the official C4 diagram levels and abstractions:

| Graphite View | C4 References |
|---------------|---------------|
| Context | [System context diagram](https://c4model.com/diagrams/system-context), [Software system abstraction](https://c4model.com/abstractions/software-system) |
| Container | [Container diagram](https://c4model.com/diagrams/container), [Container abstraction](https://c4model.com/abstractions/container) |
| Component | [Component diagram](https://c4model.com/diagrams/component), [Component abstraction](https://c4model.com/abstractions/component) |

## C4 Semantics

### Context

Context is the system-landscape view. It shows one subject software system, the
people that interact with it, and directly connected external software systems.
It is not a dependency inventory or package list.

This follows the official C4 system context diagram guidance: context focuses
on the software system in scope, directly connected people, and directly
connected software systems.

Graphite maps context elements as follows:

| C4 Element | Meaning | Evidence |
|------------|---------|----------|
| Person | A human or calling role, such as operators, HTTP clients, or a host application for a library | executable role, endpoint evidence, library role |
| Subject software system | The application or library being analyzed | entrypoint evidence, manifest metadata, dominant internal boundary, class provenance |
| External software system | A directly referenced collaborator outside the graph that is not proven to be a packaged library | referenced-but-absent classes, API/resource evidence |
| External library system | A third-party software dependency relevant to the subject | class origins, artifact dependency metadata, direct calls into dependency artifacts |
| Runtime platform | Java/Kotlin/Scala runtime services used by the subject or libraries | runtime namespace references |

For an executable jar, war, or distribution, the subject is the runnable system
discovered from entrypoints, manifest metadata, reachability, and provenance.
For a plain library jar, the subject is the library itself; Graphite does not
invent an application container around it. The missing host application is
represented as an external consumer role when evidence supports that view.

### Container

Container is the runtime/deployment boundary view. A C4 container is an
application or data store that needs to be running for the software system to
work. It is not Docker-specific, and it is not equivalent to a jar, package,
namespace, module, or utility cluster.

This follows the official C4 container diagram and abstraction: containers are
runtime boundaries such as applications or data stores, not packaging units.

Graphite maps Java archive shapes to containers only when runtime-boundary
evidence exists:

| Java Shape | Container Interpretation |
|------------|--------------------------|
| Executable application jar or distribution | A JVM application/service process |
| WAR | A deployed web application boundary |
| Spring Boot archive | The real application runtime from `Start-Class` or reachable entrypoint evidence, not the boot launcher |
| Batch/console executable | A process boundary rooted at `main(String[])` |
| Serverless function | A function runtime boundary when handler/entrypoint evidence exists |
| Owned database/schema/blob store | A data-store container when code or configuration evidence proves ownership |
| Plain library jar | No C4 container; the jar is code organization embedded in a host runtime |
| Third-party dependency jar | External library context, not a subject container |

Package clusters can explain responsibility inside a runtime boundary, but they
must not be promoted to containers without independent runtime/deployment
evidence.

### Component

Component is the code-level building-block view inside a container. A component
is a cohesive responsibility implemented by classes/interfaces inside one
runtime container. It is not a raw class, package, jar, or namespace.

This follows the official C4 component abstraction: components are related
functionality behind a defined interface inside a container, not separately
deployable units.

Graphite selects representative capability groups as components. Classes and
packages remain evidence for naming, responsibility, and relationships; they do
not become component identity by default.

Component views are emitted only when a container exists. A plain library-only
graph can still have context and dependency evidence, but it does not have a C4
component view until a host/runtime boundary is known.

## Evidence Model

C4 inference consumes persisted graph evidence:

| Evidence | Role |
|----------|------|
| Method nodes | Internal class/package ownership, public API surface, entrypoints |
| Call-site nodes | Internal responsibilities, external dependency usage, relationship weights |
| Member annotations | HTTP/API endpoints, framework entrypoints, interface hints |
| Class origins | Artifact-level dependency identity |
| Artifact dependencies | Library-to-library relationships |
| Resource paths | Manifest/config/API-resource hints |
| Referenced-but-absent classes | External systems or dependencies outside the saved graph |

Artifact provenance is preferred over namespace guessing. During graph build,
the SootUp loader records each class source from `JavaSootClass.classSource`
and the `AnalysisInputLocation` created for the archive or directory. That
lets C4 inference identify `lucene-core-9.12.0.jar` as an artifact dependency
instead of deriving identity from package fragments such as `org` or
`org.apache`.

When `--include` filters prevent every dependency class from being loaded,
Graphite still uses lightweight archive/resource evidence where available so
dependency identity is not reduced to package-name fallback.

## Inference Rules

### Subject Boundary

Graphite first decides whether the graph is an executable system or a library.

Executable evidence includes:

- `main(String[])`.
- Manifest or launcher metadata such as `Start-Class`.
- Framework endpoint evidence.
- A reachable internal graph rooted at an entrypoint.

Library evidence includes:

- No useful runtime entrypoint.
- A public API surface with incomplete runtime closure.
- Dependencies inferred from referenced-but-absent classes that are expected to
  be supplied by a host.

The internal system boundary is then inferred from dominant class provenance,
package-prefix ownership, and call evidence. Prefixes are refined downward while
a child prefix dominates its parent, so unrelated projects under a shared
namespace are separated by evidence rather than hardcoded names.

### External Systems And Libraries

External context candidates come from internal-to-external calls and
referenced-but-absent classes.

Identity is assigned in this order:

1. Artifact provenance.
2. Runtime platform namespaces.
3. Referenced-but-absent class families.
4. Namespace fallback when no stronger evidence exists.

Third-party artifacts can be collapsed into a library family at context level
when that is the clearer architectural statement. For example, Lucene Core,
Queries, and Highlighter can appear as one `Lucene` external library in context
while remaining available as artifact-level evidence.

Artifact dependency metadata is used to preserve library-to-library direction,
so high-level diagrams can show:

```text
Application -> Lucene -> Java Runtime
```

instead of flattening every dependency directly under the application.

### Container Responsibilities

When a runtime boundary exists, internal evidence is grouped into
responsibility areas. The grouping uses method volume, cohesion, incoming and
outgoing calls, endpoint evidence, and external dependency usage.

Typical responsibility categories are:

| Category | Signal |
|----------|--------|
| Interface | HTTP or framework entrypoint evidence |
| Orchestration | High fan-out into internal capabilities |
| Integration | High external dependency usage |
| Capability | Cohesive domain or internal responsibility |
| Shared foundation | High fan-in from multiple internal areas |

These categories describe responsibilities inside the subject runtime. They are
not independent deployment units unless runtime/deployment evidence also exists.

Text renderers use these responsibility categories as Graphite-specific
diagram sublayers:

| Renderer Sublayer | Meaning |
|-------------------|---------|
| Runtime Boundary | The inferred executable/deployable runtime boundary for the subject |
| Interface Adapters | Entrypoint-facing responsibilities such as HTTP or framework adapters |
| Coordination | Responsibilities with high outbound fan-out, including orchestration and integration work |
| Internal Capabilities | Cohesive domain/internal responsibilities without stronger interface/shared signals |
| Shared Foundation | Responsibilities with high inbound fan-in from other internal areas |

These sublayers are layout aids, not official C4 abstractions. They exist to
make container/component diagrams readable while preserving the C4 element
semantics.

### Relationships

Raw call direction is evidence, not always the final architecture direction.
Graphite canonicalizes relationships so diagrams communicate ownership and
dependency flow:

| Case | Direction |
|------|-----------|
| Interface/orchestrator uses capability | upper layer -> lower capability |
| Capability uses shared foundation | capability -> shared foundation |
| Library uses another library | library user -> depended-on library |
| Application/library uses runtime | application/library -> runtime |

Callback-heavy code can still hide ownership direction. In those cases the
relationship is best treated as lower confidence evidence.

## Output Contract

Single API:

```http
GET /api/architecture/c4?level=context|container|component|all&format=json|dsl|mermaid|plantuml
```

`Accept` content negotiation has priority over `format=`.

All formats are rendered from the same inferred Structurizr workspace. JSON and
DSL expose the full structured model. Mermaid and PlantUML render a readable
text-diagram slice of that model and may omit nodes/edges from the diagram
output without removing them from the workspace model.

| Format | Content Type | Use |
|--------|--------------|-----|
| Structurizr workspace JSON | `application/vnd.structurizr+json` | Agent-readable architecture model |
| Structurizr DSL | `text/vnd.structurizr.dsl` | Structurizr-compatible text workspace |
| Mermaid | `text/vnd.mermaid` | Quick Markdown/browser preview |
| PlantUML | `text/vnd.plantuml` | Diagram rendering |

The JSON and DSL outputs are the architecture model. Mermaid and PlantUML are
rendered views optimized for readability.

Graphite-specific evidence is stored in element and relationship properties,
for example:

| Property | Meaning |
|----------|---------|
| `graphite.kind` | inferred element kind |
| `graphite.architectureType` | rendering/type classification |
| `graphite.responsibility` | inferred responsibility statement |
| `graphite.whySelected` | reason the element appears in the view |
| `graphite.weight` | relationship evidence count |
| `graphite.evidence` | structured evidence summary |

## Diagram Readability

Rendered diagrams are intentionally smaller than the underlying model.

Text renderers apply:

- Top-down layering for architecture reading order.
- Short labels.
- Transitive edge reduction, so `A -> B -> C` suppresses redundant `A -> C`.
- Fan-in reduction for shared targets while keeping the strongest evidence
  edge.
- Edge caps for component views and Mermaid rendering limits.

Agents that need complete evidence should query the Structurizr JSON model
rather than relying on diagram text.

## Performance Constraints

C4 inference runs after graph loading and must not re-run bytecode analysis or
parse every dependency jar again.

Design constraints:

| Constraint | Design Response |
|------------|-----------------|
| 4G heap gate | operate on persisted graph metadata and bounded summaries |
| large call graphs | aggregate calls by selected architecture element pairs |
| large dependency sets | select top-weighted dependencies and collapse families |
| large diagrams | cap and reduce text-rendered edges |
| artifact identity | derive provenance during graph build, reuse it during C4 rendering |

The renderer should scale with the selected architecture model, not with every
class or every raw call in the analyzed application.

## View Budgets And Heuristics

Numeric limits are renderer budgets, not C4 semantic definitions. Structurizr
workspace JSON and Structurizr DSL are agent-readable structured models and are
not capped for diagram readability. Mermaid and PlantUML are text diagram
renderings, so they apply node/edge budgets after the full model has been
inferred.

| Budget | Default | Decision Basis |
|--------|---------|----------------|
| Text context/container visible elements | 12 | Enough for subject, primary actors, runtime, and strongest collaborators while fitting on one screen. |
| Text component visible elements | 16 | Component views are one zoom level deeper, so they allow slightly more density without becoming class diagrams. |
| Text diagram edges | 200 | Safely below GitHub Mermaid's 500-edge rendering limit and shared by PlantUML for consistent density. |
| Component relationship edges | 12 | Keeps component views representative; full relationship evidence remains in Structurizr JSON. |
| Per-component incoming/outgoing caps | 2 each | Prevents one hub component from visually dominating the diagram. |
| Namespace fallback depth | 2-3 segments | Avoids broad names like `org.apache` while not overfitting deep implementation packages. Artifact provenance wins when available. |
| System-boundary descent | max 4 segments, 85% dominance, 3x runner-up separation | Separates sibling projects under shared namespaces while preferring stable, slightly broader software-system boundaries over overfitting. |

If these defaults produce a misleading view, the preferred fix is to improve
the evidence or heuristic, not to encode a project-specific exception.

## Current Semantic Limits

- Business personas, user journeys, deployment topology, owned data stores, and
  serverless functions cannot be inferred precisely if they leave no code,
  resource, manifest, or runtime-entrypoint evidence.
- Namespace fallback is lower confidence than artifact provenance.
- Package-unit clustering is heuristic and should be treated as internal
  responsibility evidence, not deployment truth.
- Component views are representative architecture views, not class diagrams.

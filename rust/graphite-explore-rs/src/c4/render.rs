//! Mermaid, PlantUML and Structurizr DSL renderers over the workspace JSON.

use super::util::{diagram_id, humanize_artifact_label, slugify};
use serde_json::{json, Value as J};

/// Elements are grouped into layers in a fixed order: (match key, layer id, title).
/// The id is what diagrams use to name the group, and differs from the match key.
const LAYERS: [(&str, &str, &str); 5] = [
    ("actor", "actors", "Actors"),
    ("application", "application", "Application Layer"),
    ("external-system", "external-systems", "External Systems"),
    ("library", "libraries", "Library Layer"),
    ("technology", "technology", "Technology Layer"),
];

/// How the application layer is subdivided at `level=container`.
///
/// The context diagram puts everything application-shaped in one box; the container
/// diagram splits that box by what each container *is*, so a runtime boundary reads
/// differently from an interface adapter.
const APPLICATION_LAYERS: [(&str, &str); 5] = [
    ("runtime-boundary", "Runtime Boundary"),
    ("interface-adapters", "Interface Adapters"),
    ("coordination", "Coordination"),
    ("internal-capabilities", "Internal Capabilities"),
    ("shared-foundation", "Shared Foundation"),
];

/// Diagram budgets, from `C4ViewLimits`.
const DEFAULT_CONTEXT_DIAGRAM_ELEMENTS: usize = 12;
const DEFAULT_CONTAINER_DIAGRAM_ELEMENTS: usize = 12;
const DEFAULT_COMPONENT_DIAGRAM_ELEMENTS: usize = 16;
/// Edges a text diagram draws at most; the rest are counted in a note.
const MAX_TEXT_DIAGRAM_EDGES: usize = 200;
const MAX_INTERNAL_EDGES_PER_CONTAINER: usize = 1;
const MAX_CONTAINER_ENTRYPOINTS_PER_SHARED_DEPENDENCY: usize = 2;
const MAX_ENTRYPOINTS_PER_SHARED_CONTAINER: usize = 3;

#[derive(Clone)]
struct Element {
    id: String,
    label: String,
    architecture_type: String,
    /// `graphite.kind`, which decides the application sub-layer.
    kind: String,
    relationships: Vec<Relationship>,
}

#[derive(Clone)]
struct Relationship {
    destination: String,
    label: String,
    kind: String,
    weight: i64,
}

/// One edge selected for a diagram.
#[derive(Clone)]
struct Edge {
    from: String,
    to: String,
    label: String,
    kind: String,
    weight: i64,
}

fn application_layer_of(kind: &str) -> &'static str {
    match kind {
        "application-runtime" | "application-service" => "runtime-boundary",
        "interface" => "interface-adapters",
        "orchestrator" | "integration" => "coordination",
        "shared-capability" => "shared-foundation",
        _ => "internal-capabilities",
    }
}

fn architecture_type(e: &J) -> String {
    e.get("properties")
        .and_then(|p| p.get("graphite.architectureType"))
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
        .unwrap_or_else(|| {
            let id = e.get("id").and_then(|v| v.as_str()).unwrap_or("");
            if id.starts_with("person:") {
                "actor".into()
            } else if id.starts_with("dependency:") {
                "external-library".into()
            } else {
                "application-component".into()
            }
        })
}

/// Library names are humanized; runtimes keep their name; everything else is shortened.
fn diagram_label(name: &str, architecture_type: &str) -> String {
    match architecture_type {
        "external-library" | "library" => humanize_artifact_label(name),
        "runtime-platform" => name.to_string(),
        _ => name.rsplit('.').next().unwrap_or(name).to_string(),
    }
}

fn layer_of(architecture_type: &str) -> &'static str {
    match architecture_type {
        "actor" => "actor",
        "runtime-platform" => "technology",
        "external-library" | "library" => "library",
        "external-system" => "external-system",
        _ => "application",
    }
}

fn relationship_label(kind: &str) -> &'static str {
    match kind {
        "routes-to" => "routes to",
        "orchestrates" => "orchestrates",
        "builds-on" => "builds on",
        "runs-on" => "runs on",
        "collaborates-with" => "collaborates with",
        _ => "uses",
    }
}

/// Element ids in the order the first view lists them, when a view declares one.
/// Diagrams follow that order; the model's own order exists for the DSL.
fn view_order(workspace: &J) -> Option<Vec<String>> {
    let views = workspace.get("views")?;
    for key in ["systemContextViews", "containerViews", "componentViews"] {
        if let Some(v) = views
            .get(key)
            .and_then(|v| v.as_array())
            .and_then(|a| a.first())
        {
            let ids: Vec<String> = v
                .get("elements")?
                .as_array()?
                .iter()
                .filter_map(|e| e.get("id")?.as_str().map(|s| s.to_string()))
                .collect();
            if !ids.is_empty() {
                return Some(ids);
            }
        }
    }
    None
}

fn collect(workspace: &J) -> Vec<Element> {
    let mut out = Vec::new();
    let model = match workspace.get("model") {
        Some(m) => m,
        None => return out,
    };
    let mut push = |e: &J| {
        let id = match e.get("id").and_then(|v| v.as_str()) {
            Some(i) => i.to_string(),
            None => return,
        };
        let name = e
            .get("name")
            .and_then(|v| v.as_str())
            .unwrap_or(&id)
            .to_string();
        let at = architecture_type(e);
        let relationships = e
            .get("relationships")
            .and_then(|r| r.as_array())
            .map(|rs| {
                rs.iter()
                    .filter_map(|r| {
                        let destination = r.get("destinationId")?.as_str()?.to_string();
                        let props = r.get("properties");
                        let kind = props
                            .and_then(|p| p.get("graphite.relationshipKind"))
                            .and_then(|v| v.as_str())
                            .unwrap_or("uses");
                        // Weights arrive as strings, since Structurizr properties are
                        // all strings; an absent weight sorts as zero.
                        let weight = props
                            .and_then(|p| p.get("graphite.weight"))
                            .and_then(|v| {
                                v.as_str()
                                    .and_then(|s| s.parse().ok())
                                    .or_else(|| v.as_i64())
                            })
                            .unwrap_or(0);
                        Some(Relationship {
                            destination,
                            label: relationship_label(kind).to_string(),
                            kind: kind.to_string(),
                            weight,
                        })
                    })
                    .collect()
            })
            .unwrap_or_default();
        let kind = e
            .get("properties")
            .and_then(|p| p.get("graphite.kind"))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        out.push(Element {
            label: diagram_label(&name, &at),
            id,
            architecture_type: at,
            kind,
            relationships,
        });
    };
    for key in ["people", "softwareSystems"] {
        if let Some(arr) = model.get(key).and_then(|v| v.as_array()) {
            for e in arr {
                push(e);
            }
        }
    }
    if let Some(order) = view_order(workspace) {
        out.sort_by_key(|e| {
            order
                .iter()
                .position(|id| *id == e.id)
                .unwrap_or(usize::MAX)
        });
    }
    out
}

/// The workspace's declared level, which decides how the diagram is planned.
fn level_of(workspace: &J) -> String {
    workspace
        .get("properties")
        .and_then(|p| p.get("graphite.level"))
        .and_then(|v| v.as_str())
        .unwrap_or("context")
        .to_string()
}

/// Elements of the primary system's containers, plus the external systems around them.
///
/// At `level=container` the diagram is not drawn from the model's top level at all: the
/// subject system is a placeholder that stands for the boundary, and what is drawn are
/// the containers inside it.
fn container_elements(workspace: &J) -> Option<(Vec<Element>, Vec<Element>)> {
    let model = workspace.get("model")?;
    let systems = model.get("softwareSystems")?.as_array()?;
    let primary = systems.iter().find(|s| {
        s.get("id")
            .and_then(|v| v.as_str())
            .is_some_and(|i| i.starts_with("system:"))
    })?;
    let primary_id = primary.get("id")?.as_str()?.to_string();
    let mut inner = J::Object(serde_json::Map::new());
    if let Some(o) = inner.as_object_mut() {
        o.insert(
            "softwareSystems".into(),
            primary.get("containers").cloned().unwrap_or(json!([])),
        );
        o.insert(
            "people".into(),
            model.get("people").cloned().unwrap_or(json!([])),
        );
    }
    let containers = collect(&json!({ "model": inner }));
    let externals_json: Vec<J> = systems
        .iter()
        .filter(|s| s.get("id").and_then(|v| v.as_str()) != Some(primary_id.as_str()))
        .cloned()
        .collect();
    let externals =
        collect(&json!({ "model": { "people": [], "softwareSystems": externals_json } }));
    Some((containers, externals))
}

/// Edges of one element whose endpoints are both in `allowed`.
fn raw_edges(e: &Element, allowed: &std::collections::HashSet<String>) -> Vec<Edge> {
    if !allowed.contains(&e.id) {
        return Vec::new();
    }
    e.relationships
        .iter()
        .filter(|r| allowed.contains(&r.destination))
        .map(|r| Edge {
            from: e.id.clone(),
            to: r.destination.clone(),
            label: r.label.clone(),
            kind: r.kind.clone(),
            weight: r.weight,
        })
        .collect()
}

fn dedupe_and_sort(edges: Vec<Edge>) -> Vec<Edge> {
    let mut seen = std::collections::HashSet::new();
    let mut out: Vec<Edge> = edges
        .into_iter()
        .filter(|e| seen.insert((e.from.clone(), e.to.clone(), e.kind.clone())))
        .collect();
    out.sort_by(|a, b| b.weight.cmp(&a.weight));
    out
}

impl super::edges::DirectedEdge for Edge {
    fn from(&self) -> &str {
        &self.from
    }
    fn to(&self) -> &str {
        &self.to
    }
    fn kind(&self) -> &str {
        &self.kind
    }
    fn weight(&self) -> Option<i64> {
        Some(self.weight)
    }
}

fn reduce_transitive(edges: Vec<Edge>, preserve_runtime: bool) -> Vec<Edge> {
    super::edges::reduce_transitive(edges, preserve_runtime)
}

/// Keep only the strongest few edges into a target many containers share.
fn reduce_fan_in(
    edges: Vec<Edge>,
    target_prefix: &str,
    keep: usize,
    skip_runtime: bool,
) -> Vec<Edge> {
    let qualifies = |e: &Edge| {
        e.from.starts_with("container:")
            && e.to.starts_with(target_prefix)
            && !(skip_runtime && e.kind == "runs-on")
    };
    let mut by_target: indexmap::IndexMap<String, Vec<usize>> = indexmap::IndexMap::new();
    for (i, e) in edges.iter().enumerate() {
        if qualifies(e) {
            by_target.entry(e.to.clone()).or_default().push(i);
        }
    }
    by_target.retain(|_, v| v.len() > keep);
    if by_target.is_empty() {
        return edges;
    }
    let mut kept: std::collections::HashSet<usize> = std::collections::HashSet::new();
    for group in by_target.values() {
        let mut sorted = group.clone();
        sorted.sort_by(|a, b| edges[*b].weight.cmp(&edges[*a].weight));
        kept.extend(sorted.into_iter().take(keep));
    }
    let shared: std::collections::HashSet<String> = by_target.keys().cloned().collect();
    edges
        .into_iter()
        .enumerate()
        .filter(|(i, e)| !shared.contains(&e.to) || kept.contains(i))
        .map(|(_, e)| e)
        .collect()
}

/// Which elements and edges the container diagram shows.
///
/// Far less than the view lists. Each container contributes at most one internal edge
/// and its single strongest non-runtime dependency, and only elements an edge actually
/// touches are drawn -- so a system with six dependencies draws the one that matters.
fn container_plan(workspace: &J) -> Option<(Vec<Element>, Vec<Edge>)> {
    let (containers, externals) = container_elements(workspace)?;
    let mut all: Vec<Element> = Vec::new();
    all.extend(containers);
    let container_range = 0..all.len();
    all.extend(externals);
    let allowed: std::collections::HashSet<String> = all.iter().map(|e| e.id.clone()).collect();
    let by_id: std::collections::HashMap<&str, &Element> =
        all.iter().map(|e| (e.id.as_str(), e)).collect();

    let every_container_edge: Vec<Edge> = all[container_range.clone()]
        .iter()
        .flat_map(|c| raw_edges(c, &allowed))
        .collect();
    let mut selected: Vec<Edge> = Vec::new();
    for c in &all[container_range] {
        let mut outgoing = raw_edges(c, &allowed);
        outgoing.sort_by(|a, b| b.weight.cmp(&a.weight));
        selected.extend(
            outgoing
                .iter()
                .filter(|e| e.to.starts_with("container:"))
                .take(MAX_INTERNAL_EDGES_PER_CONTAINER)
                .cloned(),
        );
        // The strongest dependency that is not the language runtime: a "runs on Java"
        // edge is true of everything and says nothing about this system.
        if let Some(e) = outgoing.iter().find(|e| {
            e.to.starts_with("dependency:")
                && by_id
                    .get(e.to.as_str())
                    .is_some_and(|d| d.kind != "runtime")
        }) {
            selected.push(e.clone());
        }
        if application_layer_of(&c.kind) == "shared-foundation" {
            if let Some(e) = every_container_edge
                .iter()
                .filter(|e| e.to == c.id && e.from.starts_with("container:"))
                .max_by_key(|e| e.weight)
            {
                selected.push(e.clone());
            }
        }
    }
    let edges = reduce_fan_in(
        reduce_fan_in(
            reduce_transitive(dedupe_and_sort(selected), false),
            "container:",
            MAX_ENTRYPOINTS_PER_SHARED_CONTAINER,
            false,
        ),
        "dependency:",
        MAX_CONTAINER_ENTRYPOINTS_PER_SHARED_DEPENDENCY,
        true,
    );
    let connected: std::collections::HashSet<&str> = edges
        .iter()
        .flat_map(|e| [e.from.as_str(), e.to.as_str()])
        .collect();
    let visible: Vec<Element> = if connected.is_empty() {
        all.into_iter()
            .take(DEFAULT_CONTAINER_DIAGRAM_ELEMENTS)
            .collect()
    } else {
        all.into_iter()
            .filter(|e| connected.contains(e.id.as_str()))
            .collect()
    };
    Some((visible, edges))
}

/// The elements and edges a diagram has room for.
struct VisibleSlice {
    elements: Vec<Element>,
    edges: Vec<Edge>,
    omitted: usize,
}

/// Admit edges strongest first while both ends fit within `max_elements`; an element
/// is drawn only if an admitted edge touches it, and with no edges at all the first
/// `max_elements` are drawn instead. Edges left out are counted for the note.
fn select_visible_slice(
    elements: Vec<Element>,
    edges: Vec<Edge>,
    max_elements: usize,
) -> VisibleSlice {
    if max_elements == 0 {
        return VisibleSlice {
            elements: Vec::new(),
            omitted: edges.len(),
            edges: Vec::new(),
        };
    }
    if edges.is_empty() {
        return VisibleSlice {
            elements: elements.into_iter().take(max_elements).collect(),
            edges: Vec::new(),
            omitted: 0,
        };
    }
    let ids: std::collections::HashSet<&str> = elements.iter().map(|e| e.id.as_str()).collect();
    let mut visible: Vec<&str> = Vec::new();
    for e in &edges {
        if !ids.contains(e.from.as_str()) || !ids.contains(e.to.as_str()) {
            continue;
        }
        let mut missing: Vec<&str> = Vec::new();
        for end in [e.from.as_str(), e.to.as_str()] {
            if !visible.contains(&end) && !missing.contains(&end) {
                missing.push(end);
            }
        }
        if visible.len() + missing.len() <= max_elements {
            visible.extend(missing);
        }
    }
    if visible.is_empty() {
        visible = elements
            .iter()
            .take(max_elements)
            .map(|e| e.id.as_str())
            .collect();
    }
    let visible: std::collections::HashSet<String> =
        visible.into_iter().map(str::to_string).collect();
    let elements: Vec<Element> = elements
        .into_iter()
        .filter(|e| visible.contains(&e.id))
        .collect();
    let total = edges.len();
    let edges: Vec<Edge> = edges
        .into_iter()
        .filter(|e| visible.contains(&e.from) && visible.contains(&e.to))
        .collect();
    VisibleSlice {
        elements,
        omitted: total - edges.len(),
        edges,
    }
}

/// A group of elements in a diagram, possibly containing further groups.
///
/// Mirrors the baseline's layer tree, which is what lets one walk render a flat context
/// diagram, a container diagram whose application layer is subdivided by container kind,
/// and a component diagram grouped by container.
struct Layer {
    id: String,
    title: String,
    elements: Vec<Element>,
    children: Vec<Layer>,
}

impl Layer {
    fn is_empty(&self) -> bool {
        self.elements.is_empty() && self.children.iter().all(Layer::is_empty)
    }
}

/// What a diagram draws.
struct Plan {
    layers: Vec<Layer>,
    edges: Vec<Edge>,
    /// Edges the diagram had no room for, reported in a closing note.
    truncated: usize,
}

/// Group elements into the five top-level layers, optionally subdividing the application
/// layer by what each container is.
fn top_level_layers(elements: Vec<Element>, split_application: bool) -> Vec<Layer> {
    let mut by_layer: indexmap::IndexMap<&str, Vec<Element>> = indexmap::IndexMap::new();
    for e in elements {
        by_layer
            .entry(layer_of(&e.architecture_type))
            .or_default()
            .push(e);
    }
    LAYERS
        .into_iter()
        .map(|(layer, layer_id, title)| {
            let members = by_layer.shift_remove(layer).unwrap_or_default();
            if layer == "application" && split_application {
                let mut remaining = members;
                let children = APPLICATION_LAYERS
                    .iter()
                    .map(|(id, sub_title)| {
                        let (mine, rest): (Vec<Element>, Vec<Element>) = remaining
                            .drain(..)
                            .partition(|e| application_layer_of(&e.kind) == *id);
                        remaining = rest;
                        Layer {
                            id: id.to_string(),
                            title: sub_title.to_string(),
                            elements: mine,
                            children: Vec::new(),
                        }
                    })
                    .filter(|l| !l.is_empty())
                    .collect();
                Layer {
                    id: layer_id.to_string(),
                    title: title.to_string(),
                    elements: Vec::new(),
                    children,
                }
            } else {
                Layer {
                    id: layer_id.to_string(),
                    title: title.to_string(),
                    elements: members,
                    children: Vec::new(),
                }
            }
        })
        .filter(|l| !l.is_empty())
        .collect()
}

/// The component diagram: one group per container, holding that container's components.
fn component_plan(workspace: &J) -> Option<Plan> {
    let model = workspace.get("model")?;
    let systems = model.get("softwareSystems")?.as_array()?;
    let primary = systems.iter().find(|s| {
        s.get("id")
            .and_then(|v| v.as_str())
            .is_some_and(|i| i.starts_with("system:"))
    })?;
    // Every container's components, in model order, before any selection.
    let mut groups: Vec<(String, String, Vec<Element>)> = Vec::new();
    for container in primary
        .get("containers")
        .and_then(|v| v.as_array())
        .into_iter()
        .flatten()
    {
        let name = container.get("name").and_then(|v| v.as_str()).unwrap_or("");
        let id = container.get("id").and_then(|v| v.as_str()).unwrap_or("");
        let components = collect(&json!({
            "model": {
                "people": [],
                "softwareSystems": container.get("components").cloned().unwrap_or(json!([])),
            }
        }));
        groups.push((id.to_string(), name.to_string(), components));
    }
    let every: Vec<&Element> = groups.iter().flat_map(|(_, _, c)| c.iter()).collect();
    let allowed: std::collections::HashSet<String> = every.iter().map(|e| e.id.clone()).collect();
    let mut all_edges: Vec<Edge> = every.iter().flat_map(|e| raw_edges(e, &allowed)).collect();
    all_edges.sort_by(|a, b| b.weight.cmp(&a.weight));
    let total = all_edges.len();
    all_edges.truncate(MAX_TEXT_DIAGRAM_EDGES);
    let over_cap = total - all_edges.len();
    // The slice decides which components are connected enough to draw; with none
    // connected the first few are drawn instead.
    let slice = select_visible_slice(
        every.iter().map(|e| (*e).clone()).collect::<Vec<Element>>(),
        all_edges,
        DEFAULT_COMPONENT_DIAGRAM_ELEMENTS,
    );
    let visible: std::collections::HashSet<String> = if slice.elements.is_empty() {
        every
            .iter()
            .take(DEFAULT_COMPONENT_DIAGRAM_ELEMENTS)
            .map(|e| e.id.clone())
            .collect()
    } else {
        slice.elements.iter().map(|e| e.id.clone()).collect()
    };
    // No components is still a plan: the baseline frames an empty component document.
    let children: Vec<Layer> = groups
        .into_iter()
        .filter_map(|(id, name, components)| {
            let components: Vec<Element> = components
                .into_iter()
                .filter(|c| visible.contains(&c.id))
                .collect();
            if components.is_empty() {
                return None;
            }
            Some(Layer {
                id: if id.is_empty() {
                    if name.is_empty() { "container" } else { &name }.to_string()
                } else {
                    id
                },
                title: if name.is_empty() { "Container" } else { &name }.to_string(),
                elements: components,
                children: Vec::new(),
            })
        })
        .collect();
    Some(Plan {
        layers: vec![Layer {
            id: "application".to_string(),
            title: "Application Layer".to_string(),
            elements: Vec::new(),
            children,
        }],
        edges: slice.edges,
        truncated: over_cap + slice.omitted,
    })
}

/// `None` when the workspace has no primary system to plan from, which the baseline
/// renders as its empty document; a plan with nothing in it still gets the document
/// frame.
fn plan_for(workspace: &J, level: &str) -> Option<Plan> {
    Some(match level {
        "container" => {
            let (elements, edges) = container_plan(workspace)?;
            let slice = select_visible_slice(elements, edges, DEFAULT_CONTAINER_DIAGRAM_ELEMENTS);
            Plan {
                layers: top_level_layers(slice.elements, true),
                edges: slice.edges,
                truncated: slice.omitted,
            }
        }
        "component" => component_plan(workspace)?,
        _ => {
            // Actors and systems: every edge among them, deduplicated and strongest
            // first, transitively reduced, capped, then cut to what twelve elements
            // can show.
            let elements = collect(workspace);
            let allowed: std::collections::HashSet<String> =
                elements.iter().map(|e| e.id.clone()).collect();
            let raw: Vec<Edge> = elements
                .iter()
                .flat_map(|e| raw_edges(e, &allowed))
                .collect();
            let reduced = reduce_transitive(dedupe_and_sort(raw), false);
            let total = reduced.len();
            let kept: Vec<Edge> = reduced.into_iter().take(MAX_TEXT_DIAGRAM_EDGES).collect();
            let over_cap = total - kept.len();
            let slice = select_visible_slice(elements, kept, DEFAULT_CONTEXT_DIAGRAM_ELEMENTS);
            Plan {
                layers: top_level_layers(slice.elements, false),
                edges: slice.edges,
                truncated: over_cap + slice.omitted,
            }
        }
    })
}

/// Walk a layer tree into lines, the way the baseline's document builder does: a group
/// opens, its own elements are emitted one level in, its children recurse, it closes.
///
/// `group` decides whether a layer is drawn as a group at all -- PlantUML leaves the top
/// level actors ungrouped.
fn walk_layers(
    layers: &[Layer],
    depth: usize,
    lines: &mut Vec<String>,
    group: &dyn Fn(&Layer, usize) -> bool,
    open: &dyn Fn(&Layer, usize) -> String,
    close: &dyn Fn(usize) -> String,
    element: &dyn Fn(&Element, usize) -> String,
) {
    for layer in layers {
        if layer.is_empty() {
            continue;
        }
        if !group(layer, depth) {
            for e in &layer.elements {
                lines.push(element(e, depth));
            }
            walk_layers(&layer.children, depth, lines, group, open, close, element);
            continue;
        }
        lines.push(open(layer, depth));
        for e in &layer.elements {
            lines.push(element(e, depth + 1));
        }
        walk_layers(
            &layer.children,
            depth + 1,
            lines,
            group,
            open,
            close,
            element,
        );
        lines.push(close(depth));
    }
}

/// `level=all` renders all three diagrams in one document, each behind a comment
/// heading, in the language's own comment syntax.
fn render_all_sections(
    workspace: &J,
    heading: &dyn Fn(&str) -> String,
    one: &dyn Fn(&J, &str) -> String,
) -> String {
    [
        heading("Context"),
        one(workspace, "context"),
        String::new(),
        heading("Container"),
        one(workspace, "container"),
        String::new(),
        heading("Component"),
        one(workspace, "component"),
    ]
    .join("\n")
}

fn mermaid_at(workspace: &J, level: &str) -> String {
    let Some(p) = plan_for(workspace, level) else {
        return "graph TD".to_string();
    };
    let mut lines = vec!["graph TD".to_string()];
    walk_layers(
        &p.layers,
        0,
        &mut lines,
        &|_, _| true,
        &|layer, depth| {
            format!(
                "{}subgraph {}[\"{}\"]",
                "    ".repeat(depth + 1),
                diagram_id(&layer.id.to_lowercase()),
                layer.title.replace('"', "'").replace('\n', "<br/>")
            )
        },
        &|depth| format!("{}end", "    ".repeat(depth + 1)),
        &|e, depth| {
            format!(
                "{}{}{}",
                "    ".repeat(depth + 1),
                diagram_id(&e.id),
                node_shape(e)
            )
        },
    );
    for e in &p.edges {
        lines.push(format!(
            "    {} -->|{}| {}",
            diagram_id(&e.from),
            edge_label(&e.label),
            diagram_id(&e.to)
        ));
    }
    if p.truncated > 0 {
        lines.push(format!(
            "    graph_note[\"Mermaid view truncated: {} edges omitted\"]",
            p.truncated
        ));
    }
    lines.join("\n")
}

pub fn render_mermaid(workspace: &J) -> String {
    if level_of(workspace) == "all" {
        return render_all_sections(workspace, &|t| format!("%% {t}"), &mermaid_at);
    }
    mermaid_at(workspace, &level_of(workspace))
}

fn node_shape(e: &Element) -> String {
    let raw = e.label.replace('"', "'").replace('\n', "<br/>");
    match e.architecture_type.as_str() {
        "actor" | "application-service" => format!("([{raw}])"),
        "software-system" => format!("[[{raw}]]"),
        "runtime-platform" => format!("[({raw})]"),
        _ => format!("[\"{raw}\"]"),
    }
}

fn edge_label(t: &str) -> String {
    t.replace('"', "'")
        .replace('|', "/")
        .replace(['(', ')', '[', ']', '{', '}'], "")
}

fn plantuml_keyword(e: &Element) -> &'static str {
    match e.architecture_type.as_str() {
        "actor" => "actor",
        "runtime-platform" => "node",
        "software-system" => "rectangle",
        _ => "component",
    }
}

fn plantuml_at(workspace: &J, level: &str) -> String {
    let Some(p) = plan_for(workspace, level) else {
        return "@startuml\n@enduml".to_string();
    };
    let mut lines = vec![
        "@startuml".to_string(),
        "top to bottom direction".to_string(),
        "skinparam shadowing false".to_string(),
    ];
    walk_layers(
        &p.layers,
        0,
        &mut lines,
        // Actors at the top level are emitted ungrouped.
        &|layer, depth| !(depth == 0 && layer.id == "actors"),
        &|layer, depth| {
            format!(
                "{}package \"{}\" {{",
                "  ".repeat(depth),
                escape(&layer.title)
            )
        },
        &|depth| format!("{}}}", "  ".repeat(depth)),
        &|e, depth| {
            format!(
                "{}{} \"{}\" as {}",
                "  ".repeat(depth),
                plantuml_keyword(e),
                escape(&e.label),
                diagram_id(&e.id)
            )
        },
    );
    for e in &p.edges {
        lines.push(format!(
            "{} --> {} : {}",
            diagram_id(&e.from),
            diagram_id(&e.to),
            escape(&e.label)
        ));
    }
    if p.truncated > 0 {
        lines.push("note as N1".to_string());
        lines.push(format!(
            "PlantUML view truncated: {} edges omitted",
            p.truncated
        ));
        lines.push("end note".to_string());
    }
    lines.push("@enduml".to_string());
    lines.join("\n")
}

pub fn render_plantuml(workspace: &J) -> String {
    if level_of(workspace) == "all" {
        return render_all_sections(workspace, &|t| format!("' {t}"), &plantuml_at);
    }
    plantuml_at(workspace, &level_of(workspace))
}

fn escape(text: &str) -> String {
    text.replace('"', "'").replace('\n', "\\n")
}

pub fn render_dsl(workspace: &J) -> String {
    let name = workspace
        .get("name")
        .and_then(|v| v.as_str())
        .unwrap_or("Graphite C4 Workspace");
    let model = workspace.get("model");
    let mut lines = vec![format!("workspace \"{}\" {{", dsl_string(name))];
    lines.push("    model {".to_string());
    let mut identifiers: Vec<(String, String)> = Vec::new();
    let ident = |id: &str, identifiers: &mut Vec<(String, String)>| -> String {
        if let Some((_, v)) = identifiers.iter().find(|(k, _)| k == id) {
            return v.clone();
        }
        let base = format!("g_{}", slugify(id).replace('-', "_"));
        let base = if base == "g_" {
            "g_element".to_string()
        } else {
            base
        };
        let mut candidate = base.clone();
        let mut n = 1;
        while identifiers.iter().any(|(_, v)| *v == candidate) {
            candidate = format!("{base}_{n}");
            n += 1;
        }
        identifiers.push((id.to_string(), candidate.clone()));
        candidate
    };
    if let Some(model) = model {
        if let Some(people) = model.get("people").and_then(|v| v.as_array()) {
            for p in people {
                let id = p.get("id").and_then(|v| v.as_str()).unwrap_or("");
                let name = p.get("name").and_then(|v| v.as_str()).unwrap_or("");
                let desc = p.get("description").and_then(|v| v.as_str()).unwrap_or("");
                lines.push(format!(
                    "        {} = person \"{}\" \"{}\"",
                    ident(id, &mut identifiers),
                    dsl_string(name),
                    dsl_string(desc)
                ));
            }
        }
        if let Some(systems) = model.get("softwareSystems").and_then(|v| v.as_array()) {
            for sys in systems {
                let id = sys.get("id").and_then(|v| v.as_str()).unwrap_or("");
                let name = sys.get("name").and_then(|v| v.as_str()).unwrap_or("");
                let desc = sys
                    .get("description")
                    .and_then(|v| v.as_str())
                    .unwrap_or("");
                let sid = ident(id, &mut identifiers);
                let containers = sys.get("containers").and_then(|v| v.as_array());
                let has_containers = containers.map(|c| !c.is_empty()).unwrap_or(false);
                if !has_containers {
                    lines.push(format!(
                        "        {sid} = softwareSystem \"{}\" \"{}\"",
                        dsl_string(name),
                        dsl_string(desc)
                    ));
                    continue;
                }
                lines.push(format!(
                    "        {sid} = softwareSystem \"{}\" \"{}\" {{",
                    dsl_string(name),
                    dsl_string(desc)
                ));
                for c in containers.unwrap() {
                    let cid = c.get("id").and_then(|v| v.as_str()).unwrap_or("");
                    let cname = c.get("name").and_then(|v| v.as_str()).unwrap_or("");
                    let cdesc = c.get("description").and_then(|v| v.as_str()).unwrap_or("");
                    let tech = c.get("technology").and_then(|v| v.as_str()).unwrap_or("");
                    let cident = ident(cid, &mut identifiers);
                    let comps = c.get("components").and_then(|v| v.as_array());
                    let has_comps = comps.map(|x| !x.is_empty()).unwrap_or(false);
                    let head = format!(
                        "            {cident} = container \"{}\" \"{}\" \"{}\"",
                        dsl_string(cname),
                        dsl_string(cdesc),
                        dsl_string(tech)
                    );
                    if !has_comps {
                        lines.push(head);
                        continue;
                    }
                    lines.push(format!("{head} {{"));
                    for comp in comps.unwrap() {
                        let pid = comp.get("id").and_then(|v| v.as_str()).unwrap_or("");
                        let pname = comp.get("name").and_then(|v| v.as_str()).unwrap_or("");
                        let pdesc = comp
                            .get("description")
                            .and_then(|v| v.as_str())
                            .unwrap_or("");
                        let ptech = comp
                            .get("technology")
                            .and_then(|v| v.as_str())
                            .unwrap_or("");
                        lines.push(format!(
                            "                {} = component \"{}\" \"{}\" \"{}\"",
                            ident(pid, &mut identifiers),
                            dsl_string(pname),
                            dsl_string(pdesc),
                            dsl_string(ptech)
                        ));
                    }
                    lines.push("            }".to_string());
                }
                lines.push("        }".to_string());
            }
        }
        // Relationships come last, and only when both ends are registered. The walk
        // descends: every person, then every system followed by each of its containers
        // and each container's components -- a container's dependency edges live on the
        // container, so a walk that stopped at the top level emitted none of them.
        let mut sources: Vec<&J> = Vec::new();
        if let Some(arr) = model.get("people").and_then(|v| v.as_array()) {
            sources.extend(arr.iter());
        }
        if let Some(arr) = model.get("softwareSystems").and_then(|v| v.as_array()) {
            for system in arr {
                sources.push(system);
                for container in system
                    .get("containers")
                    .and_then(|v| v.as_array())
                    .into_iter()
                    .flatten()
                {
                    sources.push(container);
                    for component in container
                        .get("components")
                        .and_then(|v| v.as_array())
                        .into_iter()
                        .flatten()
                    {
                        sources.push(component);
                    }
                }
            }
        }
        let mut emitted: std::collections::HashSet<(String, String, String)> =
            std::collections::HashSet::new();
        for e in sources {
            let src = e.get("id").and_then(|v| v.as_str()).unwrap_or("");
            let src_ident = match identifiers.iter().find(|(k, _)| k == src) {
                Some((_, v)) => v.clone(),
                None => continue,
            };
            for r in e
                .get("relationships")
                .and_then(|v| v.as_array())
                .into_iter()
                .flatten()
            {
                let dest = r
                    .get("destinationId")
                    .and_then(|v| v.as_str())
                    .unwrap_or("");
                let desc = r
                    .get("description")
                    .and_then(|v| v.as_str())
                    .unwrap_or("uses");
                if !emitted.insert((src.to_string(), dest.to_string(), desc.to_string())) {
                    continue;
                }
                let dest_ident = match identifiers.iter().find(|(k, _)| k == dest) {
                    Some((_, v)) => v.clone(),
                    None => continue,
                };
                lines.push(format!(
                    "        {src_ident} -> {dest_ident} \"{}\"",
                    dsl_string(desc)
                ));
            }
        }
    }
    lines.push("    }".to_string());
    lines.push("    views {".to_string());
    for (key, kind) in [
        ("systemContextViews", "systemContext"),
        ("containerViews", "container"),
        ("componentViews", "component"),
    ] {
        let views = workspace
            .get("views")
            .and_then(|v| v.get(key))
            .and_then(|v| v.as_array());
        for v in views.into_iter().flatten() {
            let scope_key = if kind == "component" {
                "containerId"
            } else {
                "softwareSystemId"
            };
            let scope = match v.get(scope_key).and_then(|s| s.as_str()) {
                Some(s) => s,
                None => continue,
            };
            let scope_ident = match identifiers.iter().find(|(k, _)| k == scope) {
                Some((_, i)) => i.clone(),
                None => continue,
            };
            let view_key = v
                .get("key")
                .and_then(|s| s.as_str())
                .unwrap_or("graphite-view");
            lines.push(format!("        {kind} {scope_ident} \"{view_key}\" {{"));
            lines.push("            include *".to_string());
            lines.push("            autolayout tb".to_string());
            lines.push("        }".to_string());
        }
    }
    lines.push("        theme default".to_string());
    lines.push("    }".to_string());
    lines.push("}".to_string());
    lines.join("\n")
}

fn dsl_string(v: &str) -> String {
    v.replace('\\', "\\\\")
        .replace('"', "\\\"")
        .replace(['\r', '\n'], " ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn workspace() -> J {
        json!({
            "name": "Graphite C4 Workspace",
            "model": {
                "people": [{
                    "id": "person:operators", "name": "Operators", "description": "d",
                    "properties": {"graphite.architectureType": "actor"},
                    "relationships": [{"destinationId": "system:library", "description": "Uses Foo", "properties": {}}]
                }],
                "softwareSystems": [{
                    "id": "system:library", "name": "Foo Library", "description": "d",
                    "properties": {"graphite.architectureType": "library"},
                    "relationships": [], "containers": []
                }]
            },
            "views": {"systemContextViews": [{"key": "graphite-context", "softwareSystemId": "system:library"}]}
        })
    }

    #[test]
    fn mermaid_starts_with_a_graph_declaration() {
        let out = render_mermaid(&workspace());
        assert!(out.starts_with("graph TD"));
        assert!(out.contains("system_library"));
    }

    #[test]
    fn plantuml_is_wrapped_in_start_and_end() {
        let out = render_plantuml(&workspace());
        assert!(out.starts_with("@startuml"));
        assert!(out.trim_end().ends_with("@enduml"));
    }

    #[test]
    fn dsl_declares_a_workspace_with_model_and_views() {
        let out = render_dsl(&workspace());
        assert!(out.starts_with("workspace \"Graphite C4 Workspace\""));
        assert!(out.contains("model {"));
        assert!(out.contains("views {"));
        assert!(out.contains("softwareSystem"));
        assert!(out.contains("systemContext"));
    }

    #[test]
    fn empty_models_render_framed_documents() {
        // A context diagram always has a plan, so an empty model still gets the
        // document frame, as the baseline's renderer emits its header and footer.
        let empty = json!({"name": "x", "model": {"people": [], "softwareSystems": []}});
        assert_eq!(render_mermaid(&empty), "graph TD");
        assert_eq!(
            render_plantuml(&empty),
            "@startuml\ntop to bottom direction\nskinparam shadowing false\n@enduml"
        );
        // Without a primary system there is no container plan at all: the empty
        // document, not a frame.
        let mut container = empty.clone();
        container["properties"] = json!({"graphite.level": "container"});
        assert_eq!(render_plantuml(&container), "@startuml\n@enduml");
    }

    #[test]
    fn labels_are_shortened_or_humanized_by_type() {
        assert_eq!(
            diagram_label("com.acme.Controller", "application-component"),
            "Controller"
        );
        assert_eq!(
            diagram_label("lucene-core-9.12.0", "external-library"),
            "Lucene Core"
        );
        assert_eq!(
            diagram_label("Java Runtime", "runtime-platform"),
            "Java Runtime"
        );
    }
}

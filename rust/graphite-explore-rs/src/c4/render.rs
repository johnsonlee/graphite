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
const DEFAULT_CONTAINER_DIAGRAM_ELEMENTS: usize = 12;
const MAX_INTERNAL_EDGES_PER_CONTAINER: usize = 1;
/// Above this many edges transitive reduction is skipped outright.
const MAX_TRANSITIVE_REDUCTION_EDGES: usize = 200;
const MAX_CONTAINER_ENTRYPOINTS_PER_SHARED_DEPENDENCY: usize = 2;
const MAX_ENTRYPOINTS_PER_SHARED_CONTAINER: usize = 3;
/// Kinds where plain reachability is enough to call a direct edge redundant: `A -> B -> C`
/// already communicates the layering.
const HIERARCHY_REDUCTION_KINDS: [&str; 2] = ["runs-on", "builds-on"];

struct Element {
    id: String,
    label: String,
    architecture_type: String,
    /// `graphite.kind`, which decides the application sub-layer.
    kind: String,
    relationships: Vec<Relationship>,
}

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

/// Edges across all elements, deduplicated and ordered by descending weight.
fn ordered_edges(elements: &[Element]) -> Vec<(&str, &Relationship)> {
    let mut seen: std::collections::HashSet<(String, String, String)> =
        std::collections::HashSet::new();
    let mut out: Vec<(&str, &Relationship)> = Vec::new();
    for e in elements {
        for r in &e.relationships {
            let key = (e.id.clone(), r.destination.clone(), r.label.clone());
            if seen.insert(key) {
                out.push((e.id.as_str(), r));
            }
        }
    }
    // A stable sort keeps collection order among equal weights.
    out.sort_by(|a, b| b.1.weight.cmp(&a.1.weight));
    out
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
    let primary = systems
        .iter()
        .find(|s| {
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
    let externals = collect(&json!({ "model": { "people": [], "softwareSystems": externals_json } }));
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

/// Drop a direct edge that another path already carries.
///
/// For a hierarchy edge plain reachability settles it. For an evidence-bearing edge the
/// alternate path must be at least as strong at its narrowest point, so a heavy direct
/// dependency is not hidden behind a thin indirect one.
fn reduce_transitive(edges: Vec<Edge>, preserve_runtime: bool) -> Vec<Edge> {
    let reducible: Vec<usize> = (0..edges.len())
        .filter(|&i| !(preserve_runtime && edges[i].kind == "runs-on"))
        .collect();
    if reducible.len() > MAX_TRANSITIVE_REDUCTION_EDGES {
        return edges;
    }
    let weight = |e: &Edge| e.weight.max(1);
    // Reachability from `source`, ignoring the edge under test.
    let has_path = |source: &str, destination: &str, omit: usize| -> bool {
        let mut queue = std::collections::VecDeque::from([source.to_string()]);
        let mut visited = std::collections::HashSet::new();
        while let Some(current) = queue.pop_front() {
            if !visited.insert(current.clone()) {
                continue;
            }
            for &i in &reducible {
                if i == omit || edges[i].from != current {
                    continue;
                }
                if edges[i].to == destination {
                    return true;
                }
                if !visited.contains(&edges[i].to) {
                    queue.push_back(edges[i].to.clone());
                }
            }
        }
        false
    };
    // Widest-path capacity, so an alternate route is only "as good" if its bottleneck is.
    let capacity = |source: &str, destination: &str, omit: usize| -> i64 {
        let mut queue = std::collections::VecDeque::from([(source.to_string(), i64::MAX)]);
        let mut best: std::collections::HashMap<String, i64> = std::collections::HashMap::new();
        while let Some((current, cap)) = queue.pop_front() {
            if best.get(&current).is_some_and(|&b| b >= cap) {
                continue;
            }
            best.insert(current.clone(), cap);
            for &i in &reducible {
                if i == omit || edges[i].from != current {
                    continue;
                }
                let next_cap = cap.min(weight(&edges[i]));
                if edges[i].to == destination {
                    return next_cap;
                }
                if best.get(&edges[i].to).is_none_or(|&b| b < next_cap) {
                    queue.push_back((edges[i].to.clone(), next_cap));
                }
            }
        }
        -1
    };
    let redundant: std::collections::HashSet<usize> = reducible
        .iter()
        .copied()
        .filter(|&i| {
            let e = &edges[i];
            if HIERARCHY_REDUCTION_KINDS.contains(&e.kind.as_str()) {
                has_path(&e.from, &e.to, i)
            } else {
                capacity(&e.from, &e.to, i) >= weight(e)
            }
        })
        .collect();
    edges
        .into_iter()
        .enumerate()
        .filter(|(i, _)| !redundant.contains(i))
        .map(|(_, e)| e)
        .collect()
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
    let container_count = all.len();
    all.extend(externals);
    let allowed: std::collections::HashSet<String> = all.iter().map(|e| e.id.clone()).collect();
    let by_id: std::collections::HashMap<&str, &Element> =
        all.iter().map(|e| (e.id.as_str(), e)).collect();

    let every_container_edge: Vec<Edge> = all[..container_count]
        .iter()
        .flat_map(|c| raw_edges(c, &allowed))
        .collect();
    let mut selected: Vec<Edge> = Vec::new();
    for c in &all[..container_count] {
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
                && by_id.get(e.to.as_str()).is_some_and(|d| d.kind != "runtime")
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
    let visible_ids: std::collections::HashSet<&str> =
        visible.iter().map(|e| e.id.as_str()).collect();
    let edges = edges
        .into_iter()
        .filter(|e| visible_ids.contains(e.from.as_str()) && visible_ids.contains(e.to.as_str()))
        .collect();
    Some((visible, edges))
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
}

impl Plan {
    fn is_empty(&self) -> bool {
        self.layers.iter().all(Layer::is_empty)
    }
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
    let mut children: Vec<Layer> = Vec::new();
    let mut every: Vec<Element> = Vec::new();
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
        if components.is_empty() {
            continue;
        }
        every.extend(components.iter().map(|e| Element {
            id: e.id.clone(),
            label: e.label.clone(),
            architecture_type: e.architecture_type.clone(),
            kind: e.kind.clone(),
            relationships: Vec::new(),
        }));
        children.push(Layer {
            id: if id.is_empty() {
                if name.is_empty() { "container" } else { name }.to_string()
            } else {
                id.to_string()
            },
            title: if name.is_empty() { "Container" } else { name }.to_string(),
            elements: components,
            children: Vec::new(),
        });
    }
    if children.is_empty() {
        return None;
    }
    let allowed: std::collections::HashSet<String> = every.iter().map(|e| e.id.clone()).collect();
    let mut edges: Vec<Edge> = children
        .iter()
        .flat_map(|l| l.elements.iter())
        .flat_map(|e| raw_edges(e, &allowed))
        .collect();
    edges.sort_by(|a, b| b.weight.cmp(&a.weight));
    Some(Plan {
        layers: vec![Layer {
            id: "application".to_string(),
            title: "Application Layer".to_string(),
            elements: Vec::new(),
            children,
        }],
        edges,
    })
}

fn plan_for(workspace: &J, level: &str) -> Plan {
    match level {
        "container" => container_plan(workspace)
            .map(|(elements, edges)| Plan {
                layers: top_level_layers(elements, true),
                edges,
            })
            .unwrap_or(Plan {
                layers: Vec::new(),
                edges: Vec::new(),
            }),
        "component" => component_plan(workspace).unwrap_or(Plan {
            layers: Vec::new(),
            edges: Vec::new(),
        }),
        _ => {
            let elements = collect(workspace);
            let edges = ordered_edges(&elements)
                .into_iter()
                .map(|(from, r)| Edge {
                    from: from.to_string(),
                    to: r.destination.clone(),
                    label: r.label.clone(),
                    kind: r.kind.clone(),
                    weight: r.weight,
                })
                .collect();
            Plan {
                layers: top_level_layers(elements, false),
                edges,
            }
        }
    }
}

fn plan(workspace: &J) -> Plan {
    plan_for(workspace, &level_of(workspace))
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
fn render_all_sections(workspace: &J, heading: &dyn Fn(&str) -> String, one: &dyn Fn(&J, &str) -> String) -> String {
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
    let p = plan_for(workspace, level);
    if p.is_empty() {
        return "graph TD".to_string();
    }
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
        &|e, depth| format!("{}{}{}", "    ".repeat(depth + 1), diagram_id(&e.id), node_shape(e)),
    );
    for e in &p.edges {
        lines.push(format!(
            "    {} -->|{}| {}",
            diagram_id(&e.from),
            edge_label(&e.label),
            diagram_id(&e.to)
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
    let p = plan_for(workspace, level);
    if p.is_empty() {
        return "@startuml\n@enduml".to_string();
    }
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
        .replace('\r', " ")
        .replace('\n', " ")
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
    fn empty_models_render_minimal_documents() {
        let empty = json!({"name": "x", "model": {"people": [], "softwareSystems": []}});
        assert_eq!(render_mermaid(&empty), "graph TD");
        assert_eq!(render_plantuml(&empty), "@startuml\n@enduml");
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

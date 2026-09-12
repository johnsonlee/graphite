//! Mermaid, PlantUML and Structurizr DSL renderers over the workspace JSON.

use super::util::{diagram_id, humanize_artifact_label, slugify};
use serde_json::Value as J;

/// Elements are grouped into layers in a fixed order: (match key, layer id, title).
/// The id is what diagrams use to name the group, and differs from the match key.
const LAYERS: [(&str, &str, &str); 5] = [
    ("actor", "actors", "Actors"),
    ("application", "application", "Application Layer"),
    ("external-system", "external-systems", "External Systems"),
    ("library", "libraries", "Library Layer"),
    ("technology", "technology", "Technology Layer"),
];

struct Element {
    id: String,
    label: String,
    architecture_type: String,
    relationships: Vec<Relationship>,
}

struct Relationship {
    destination: String,
    label: String,
    weight: i64,
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
                            weight,
                        })
                    })
                    .collect()
            })
            .unwrap_or_default();
        out.push(Element {
            label: diagram_label(&name, &at),
            id,
            architecture_type: at,
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

pub fn render_mermaid(workspace: &J) -> String {
    let elements = collect(workspace);
    if elements.is_empty() {
        return "graph TD".to_string();
    }
    let mut lines = vec!["graph TD".to_string()];
    for (layer, layer_id, title) in LAYERS {
        let members: Vec<&Element> = elements
            .iter()
            .filter(|e| layer_of(&e.architecture_type) == layer)
            .collect();
        if members.is_empty() {
            continue;
        }
        lines.push(format!(
            "    subgraph {}[\"{}\"]",
            diagram_id(&layer_id.to_lowercase()),
            title
        ));
        for e in members {
            lines.push(format!("        {}{}", diagram_id(&e.id), node_shape(e)));
        }
        lines.push("    end".to_string());
    }
    for (from, r) in ordered_edges(&elements) {
        lines.push(format!(
            "    {} -->|{}| {}",
            diagram_id(from),
            edge_label(&r.label),
            diagram_id(&r.destination)
        ));
    }
    lines.join("\n")
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

pub fn render_plantuml(workspace: &J) -> String {
    let elements = collect(workspace);
    if elements.is_empty() {
        return "@startuml\n@enduml".to_string();
    }
    let mut lines = vec![
        "@startuml".to_string(),
        "top to bottom direction".to_string(),
        "skinparam shadowing false".to_string(),
    ];
    for (layer, _layer_id, title) in LAYERS {
        let members: Vec<&Element> = elements
            .iter()
            .filter(|e| layer_of(&e.architecture_type) == layer)
            .collect();
        if members.is_empty() {
            continue;
        }
        // Actors are emitted ungrouped at the top level.
        let grouped = layer != "actor";
        if grouped {
            lines.push(format!("package \"{}\" {{", escape(title)));
        }
        for e in members {
            let indent = if grouped { "  " } else { "" };
            let keyword = match e.architecture_type.as_str() {
                "actor" => "actor",
                "runtime-platform" => "node",
                "software-system" => "rectangle",
                _ => "component",
            };
            lines.push(format!(
                "{indent}{keyword} \"{}\" as {}",
                escape(&e.label),
                diagram_id(&e.id)
            ));
        }
        if grouped {
            lines.push("}".to_string());
        }
    }
    for (from, r) in ordered_edges(&elements) {
        lines.push(format!(
            "{} --> {} : {}",
            diagram_id(from),
            diagram_id(&r.destination),
            escape(&r.label)
        ));
    }
    lines.push("@enduml".to_string());
    lines.join("\n")
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
        // Relationships come last, and only when both ends are registered.
        for key in ["people", "softwareSystems"] {
            if let Some(arr) = model.get(key).and_then(|v| v.as_array()) {
                for e in arr {
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
                        let dest_ident = match identifiers.iter().find(|(k, _)| k == dest) {
                            Some((_, v)) => v.clone(),
                            None => continue,
                        };
                        let desc = r
                            .get("description")
                            .and_then(|v| v.as_str())
                            .unwrap_or("uses");
                        lines.push(format!(
                            "        {src_ident} -> {dest_ident} \"{}\"",
                            dsl_string(desc)
                        ));
                    }
                }
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

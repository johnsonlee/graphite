//! Assemble the inferred architecture into a Structurizr workspace.

use super::boundary;
use super::constants::*;
use super::containers;
use super::external::DependencyKind;
use super::subject;
use super::util::slugify;
use graphite_storage::Graph;
use serde_json::{json, Map, Value as J};

fn tags(base: &str, kind: &str) -> String {
    format!("{base},{GRAPHITE_TAG},{kind}")
}

/// Structurizr properties are `Map<String, String>`, so every value is rendered as a
/// string: scalars via their own text, anything structured as pretty-printed JSON.
fn props(entries: Vec<(&str, J)>) -> J {
    let mut m = Map::new();
    for (k, v) in entries {
        if v.is_null() {
            continue;
        }
        m.insert(k.to_string(), J::String(property_string(&v)));
    }
    J::Object(m)
}

fn property_string(v: &J) -> String {
    match v {
        J::String(s) => s.clone(),
        // Metadata passes through a Gson round-trip into Map<String, Any?>, which turns
        // every number into a Double, so integers render with a trailing ".0".
        J::Number(n) => match n.as_i64() {
            Some(i) => format!("{i}.0"),
            None => n.to_string(),
        },
        J::Bool(b) => b.to_string(),
        other => serde_json::to_string_pretty(other).unwrap_or_default(),
    }
}

fn element(id: &str, name: &str, description: &str, tag_set: String, properties: J) -> J {
    json!({
        "id": id,
        "name": name,
        "description": description,
        "tags": tag_set,
        "properties": properties,
        "relationships": [],
        "containers": [],
        "components": [],
    })
}

/// Build the Structurizr workspace for one graph at the requested level.
pub fn build_model(g: &Graph, level: &str) -> J {
    let endpoints = crate::endpoints::extract_endpoints(g);
    let endpoint_classes: Vec<String> = endpoints
        .iter()
        .filter_map(|e| e.get("class").and_then(|v| v.as_str()).map(|s| s.to_string()))
        .collect();
    let mut endpoint_paths: indexmap::IndexMap<String, Vec<String>> = indexmap::IndexMap::new();
    for e in &endpoints {
        if let (Some(c), Some(p)) = (
            e.get("class").and_then(|v| v.as_str()),
            e.get("path").and_then(|v| v.as_str()),
        ) {
            endpoint_paths.entry(c.to_string()).or_default().push(p.to_string());
        }
    }

    // Classes seen anywhere in the graph drive boundary detection.
    let mut primary_classes: Vec<String> = g
        .methods()
        .iter()
        .map(|m| g.str(m.declaring_class).to_string())
        .collect();
    if primary_classes.is_empty() {
        for &id in g.ids_by_tag(graphite_storage::node::TAG_CALL_SITE_NODE) {
            if let Some(s) = g.call_site_strings(id) {
                primary_classes.push(g.str(s.caller_class).to_string());
            }
        }
    }
    let system_boundary = boundary::derive(&primary_classes);
    let subject = subject::infer(g, &system_boundary, endpoint_classes.len());
    let capability_layout = containers::infer_layout(
        g,
        &system_boundary,
        &endpoint_classes,
        &endpoint_paths,
        usize::MAX,
    );
    let runtime_layout =
        containers::infer_operational_layout(&subject.role, &subject.name, &capability_layout);

    let want_context = level == "context" || level == "all";
    let want_container = level == "container" || level == "all";
    let want_component = level == "component" || level == "all";

    let mut people: Vec<J> = Vec::new();
    let mut systems: Vec<J> = Vec::new();
    let mut relationships: Vec<J> = Vec::new();
    // Ids are handed out in view-registration order, which starts at the actor.
    let mut rel_id = usize::from(want_context);
    let mut next_rel = || {
        rel_id += 1;
        format!("rel-{rel_id}")
    };

    if want_context {
        people.push(element(
            &subject.actor_id,
            &subject.actor_name,
            &subject.actor_description,
            tags("Person", "actor"),
            props(vec![
                ("graphite.type", json!("person")),
                ("graphite.kind", json!("actor")),
                ("graphite.architectureType", json!("actor")),
                ("graphite.responsibility", json!(subject.actor_responsibility)),
            ]),
        ));
    }

    // External dependencies become software systems: libraries and external systems
    // first, then runtimes, with the subject appended last.
    let deps = &capability_layout.external_dependencies;
    let ordered_deps: Vec<&crate::c4::external::ExternalDependency> = deps
        .iter()
        .filter(|d| d.kind != DependencyKind::Runtime)
        .chain(deps.iter().filter(|d| d.kind == DependencyKind::Runtime))
        .collect();
    for d in &ordered_deps {
        let description = if d.kind == DependencyKind::Runtime {
            // The context view describes runtimes in terms of what they support.
            "Language and platform runtime supporting the application and its libraries"
        } else {
            d.kind.description()
        };
        systems.push(element(
            &d.id,
            &d.name,
            description,
            tags("Software System", d.kind.wire()),
            props(vec![
                ("graphite.type", json!("softwareSystem")),
                ("graphite.kind", json!(d.kind.wire())),
                ("graphite.architectureType", json!(d.kind.architecture_type())),
                ("graphite.responsibility", json!(d.responsibility)),
            ]),
        ));
    }

    // The subject system carries the containers.
    let containers_json: Vec<J> = if want_container || want_component {
        runtime_layout
            .containers
            .iter()
            .map(|c| {
                let kind = containers::container_kind(c);
                let components: Vec<J> = if want_component {
                    capability_layout
                        .containers
                        .iter()
                        .map(|cap| {
                            let ck = containers::container_kind(cap);
                            let mut e = element(
                                &format!("{COMPONENT_ID_PREFIX}{}", slugify(&cap.name)),
                                &cap.name,
                                "Internal capability evidence derived from code graph structure",
                                tags("Component", &ck),
                                props(vec![
                                    ("graphite.type", json!("component")),
                                    ("graphite.kind", json!(ck)),
                                    ("graphite.architectureType", json!(containers::architecture_type(&ck))),
                                    (
                                        "graphite.responsibility",
                                        json!(containers::infer_responsibility(
                                            cap.endpoint_count,
                                            cap.method_count,
                                            cap.inbound,
                                            cap.outbound,
                                            cap.external_calls
                                        )),
                                    ),
                                    ("graphite.container", json!(c.name)),
                                    ("graphite.containerId", json!(c.id)),
                                    ("graphite.methods", json!(cap.method_count)),
                                    ("graphite.callSites", json!(cap.call_site_count)),
                                    ("graphite.endpoints", json!(cap.endpoint_count)),
                                    ("graphite.packageUnits", json!(cap.package_units)),
                                    ("graphite.classes", json!(cap.primary_classes)),
                                    ("graphite.entrypoints", json!(cap.entrypoints)),
                                    ("graphite.whySelected", json!(cap.rationale)),
                                ]),
                            );
                            if let Some(o) = e.as_object_mut() {
                                o.insert("technology".into(), json!(TECHNOLOGY_JVM_BYTECODE));
                            }
                            e
                        })
                        .collect()
                } else {
                    Vec::new()
                };
                let mut e = element(
                    &c.id,
                    &c.name,
                    containers::description(&kind),
                    tags("Container", &kind),
                    props(vec![
                        ("graphite.type", json!("container")),
                        ("graphite.kind", json!(kind)),
                        ("graphite.architectureType", json!(containers::architecture_type(&kind))),
                        (
                            "graphite.responsibility",
                            containers::operational_responsibility(&kind)
                                .map(|r| json!(r))
                                .unwrap_or(J::Null),
                        ),
                        ("graphite.methods", json!(c.method_count)),
                        ("graphite.callSites", json!(c.call_site_count)),
                        ("graphite.endpoints", json!(c.endpoint_count)),
                        ("graphite.entrypoints", json!(c.entrypoints)),
                        ("graphite.primaryClasses", json!(c.primary_classes)),
                        ("graphite.packageUnits", json!(c.package_units)),
                        ("graphite.whySelected", json!(c.rationale)),
                    ]),
                );
                if let Some(o) = e.as_object_mut() {
                    o.insert("technology".into(), json!(TECHNOLOGY_JVM_BYTECODE));
                    o.insert("components".into(), json!(components));
                }
                e
            })
            .collect()
    } else {
        Vec::new()
    };

    let mut subject_element = element(
        &subject.id,
        &subject.name,
        &subject.description,
        tags(
            "Software System",
            if subject.role == "application" { "application" } else { "library" },
        ),
        props(vec![
            ("graphite.type", json!("softwareSystem")),
            ("graphite.kind", json!(subject.role)),
            (
                "graphite.architectureType",
                json!(if subject.role == "application" { "software-system" } else { "library" }),
            ),
            ("graphite.responsibility", json!(subject.responsibility)),
            (
                "graphite.whySelected",
                json!("Dominant namespace boundary inferred from internal classes and call-site traffic"),
            ),
            ("graphite.methods", json!(g.method_count())),
            ("graphite.endpoints", json!(endpoint_classes.len())),
            ("graphite.classes", json!(distinct_class_count(g))),
        ]),
    );
    if let Some(o) = subject_element.as_object_mut() {
        o.insert("containers".into(), json!(containers_json));
        // The actor and the dependencies hang off the subject.
        let mut rels: Vec<J> = Vec::new();
        for d in &ordered_deps {
            let runtime = d.kind == DependencyKind::Runtime;
            let (kind, verb) = if runtime {
                ("runs-on", "runs on")
            } else {
                ("uses", "uses")
            };
            let mut properties = Map::new();
            properties.insert("graphite.view".into(), json!("context"));
            properties.insert("graphite.relationshipKind".into(), json!(kind));
            let evidence = if runtime {
                json!({ "source": d.source, "kind": d.kind.wire() })
            } else {
                json!({
                    "crossContainerCalls": d.weight,
                    "source": d.source,
                    "confidence": d.confidence,
                    // A dependency that stands alone lists itself as its one artifact.
                    "artifacts": if d.artifacts.is_empty() {
                        vec![d.name.clone()]
                    } else {
                        d.artifacts.clone()
                    },
                })
            };
            properties.insert(
                "graphite.evidence".into(),
                json!(serde_json::to_string_pretty(&evidence).unwrap_or_default()),
            );
            // A "runs on" edge carries no weight, so it sorts last in diagrams.
            if !runtime {
                properties.insert("graphite.weight".into(), json!(d.weight.to_string()));
            }
            rels.push(json!({
                "id": next_rel(),
                "destinationId": d.id,
                "description": format!("{} {verb} {}", subject.name, d.name),
                "technology": kind,
                "tags": format!("Relationship,{GRAPHITE_TAG},{kind}"),
                "properties": J::Object(properties),
            }));
        }
        o.insert("relationships".into(), json!(rels));
    }
    // The Structurizr model declares the subject first (the mapper seeds it before
    // walking the view), while diagrams follow the view's own element order.
    systems.insert(0, subject_element);

    if want_context {
        if let Some(actor) = people.first_mut() {
            if let Some(o) = actor.as_object_mut() {
                o.insert(
                    "relationships".into(),
                    json!([{
                        "id": "rel-1",
                        "destinationId": subject.id,
                        "description": subject::describe_invocation(&subject, endpoint_classes.len()),
                        "technology": "uses",
                        "tags": format!("Relationship,{GRAPHITE_TAG},uses"),
                        "properties": {
                            "graphite.view": "context",
                            "graphite.relationshipKind": "uses",
                            "graphite.evidence": serde_json::to_string_pretty(
                                &json!({ "endpoints": endpoint_classes.len() })
                            ).unwrap_or_default(),
                        },
                    }]),
                );
            }
        }
    }
    relationships.clear();

    let mut views = Map::new();
    let level_prop = json!(level);
    let available = json!(super::LEVELS);
    // Diagram order: actor, then dependencies as ordered above, then the subject.
    let context_order: Vec<String> = people
        .iter()
        .chain(systems.iter().skip(1))
        .chain(systems.iter().take(1))
        .filter_map(|e| e.get("id").and_then(|i| i.as_str()).map(|s| s.to_string()))
        .collect();
    if want_context {
        views.insert(
            "systemContextViews".into(),
            json!([{
                "key": "graphite-context",
                "description": "Graphite-derived C4 system context view",
                "softwareSystemId": subject.id,
                "elements": context_order.iter().map(|id| json!({"id": id})).collect::<Vec<_>>(),
                "relationships": collect_relationship_ids(&people, &systems),
                "properties": { "graphite.level": level_prop },
            }]),
        );
    } else {
        views.insert("systemContextViews".into(), json!([]));
    }
    if want_container {
        views.insert(
            "containerViews".into(),
            json!([{
                "key": "graphite-container",
                "description": "Graphite-derived C4 container view",
                "softwareSystemId": subject.id,
                "elements": container_refs(&containers_json, deps.iter().map(|d| d.id.clone()).collect()),
                "relationships": [],
                "properties": {
                    "graphite.level": level_prop,
                    "graphite.systemBoundary": system_boundary,
                },
            }]),
        );
    } else {
        views.insert("containerViews".into(), json!([]));
    }
    if want_component && !containers_json.is_empty() {
        let component_views: Vec<J> = containers_json
            .iter()
            .filter_map(|c| {
                let o = c.as_object()?;
                let id = o.get("id")?.as_str()?;
                let name = o.get("name")?.as_str()?;
                let comps = o.get("components")?.as_array()?;
                if comps.is_empty() {
                    return None;
                }
                Some(json!({
                    "key": format!("graphite-component-{}", slugify(id)),
                    "description": format!("Graphite-derived C4 component view for {name}"),
                    "containerId": id,
                    "elements": comps.iter().filter_map(|c| c.get("id").map(|i| json!({"id": i}))).collect::<Vec<_>>(),
                    "relationships": [],
                    "properties": {
                        "graphite.level": level_prop,
                        "graphite.containerId": id,
                        "graphite.container": name,
                    },
                }))
            })
            .collect();
        views.insert("componentViews".into(), json!(component_views));
    } else {
        views.insert("componentViews".into(), json!([]));
    }
    views.insert(
        "configuration".into(),
        json!({
            "scope": "softwareSystem",
            "properties": {
                "graphite.level": level_prop,
                "graphite.availableLevels": property_string(&available),
            },
        }),
    );

    let _ = relationships;
    json!({
        "name": "Graphite C4 Workspace",
        "description": "Structurizr workspace derived from the Graphite code graph",
        "properties": {
            "graphite.level": level_prop,
            "graphite.availableLevels": property_string(&available),
            "graphite.format": "structurizr-workspace",
        },
        "model": {
            "people": people,
            "softwareSystems": systems,
        },
        "views": J::Object(views),
    })
}

/// Ids of every relationship declared on the model's elements, in walk order.
fn collect_relationship_ids(people: &[J], systems: &[J]) -> Vec<J> {
    people
        .iter()
        .chain(systems.iter())
        .filter_map(|e| e.get("relationships")?.as_array())
        .flatten()
        .filter_map(|r| r.get("id").map(|i| json!({ "id": i })))
        .collect()
}

/// Distinct non-synthetic classes named anywhere in the graph, as subject evidence.
fn distinct_class_count(g: &Graph) -> usize {
    let mut seen: std::collections::HashSet<graphite_storage::StrId> =
        std::collections::HashSet::new();
    let add = |id: graphite_storage::StrId, seen: &mut std::collections::HashSet<_>| {
        if !boundary::is_synthetic_class(g.str(id)) {
            seen.insert(id);
        }
    };
    for m in g.methods() {
        add(m.declaring_class, &mut seen);
    }
    for &id in g.ids_by_tag(graphite_storage::node::TAG_CALL_SITE_NODE) {
        if let Some(cs) = g.call_site_strings(id) {
            add(cs.caller_class, &mut seen);
            add(cs.callee_class, &mut seen);
        }
    }
    seen.len()
}

fn container_refs(containers: &[J], dependency_ids: Vec<String>) -> Vec<J> {
    let mut out: Vec<J> = containers
        .iter()
        .filter_map(|e| e.get("id").map(|i| json!({ "id": i })))
        .collect();
    out.extend(dependency_ids.into_iter().map(|id| json!({ "id": id })));
    out
}

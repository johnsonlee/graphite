//! Component selection: which internal capabilities become components, and why.
//!
//! A container is a runtime boundary; the components inside it are the capabilities the
//! code actually splits into. Every package unit is a candidate, so the work is ranking
//! them by architectural evidence and keeping the diagram readable — endpoints first,
//! then cross-capability traffic, then integration with external dependencies, with
//! local size only breaking ties.

use super::boundary::{internal_package_unit, is_internal_class, is_runtime_class};
use super::constants::component_limits::{
    MAX_INCOMING_EDGES_PER_COMPONENT, MAX_OUTGOING_EDGES_PER_COMPONENT, MAX_VIEW_EDGES,
    MIN_EDGES_AFTER_CAP_RELAXATION,
};
use super::constants::container_layer_ranks;
use super::containers::{self, Container, ContainerLayout};
use super::edges::{reduce_transitive, DirectedEdge};
use graphite_storage::Graph;
use indexmap::IndexMap;

/// Weights, from `C4ComponentScoring`. Separated by an order of magnitude so a weaker
/// category cannot outrank a stronger architectural signal on volume alone.
const ENDPOINT_WEIGHT: i64 = 300;
const CROSS_CAPABILITY_WEIGHT: i64 = 200;
const EXTERNAL_CALL_WEIGHT: i64 = 100;
const METHOD_WEIGHT: i64 = 1;
const CALL_WEIGHT: i64 = 1;
const REPRESENTATIVE_CROSS_CAPABILITY_WEIGHT: i64 = 200;
const REPRESENTATIVE_ENDPOINT_WEIGHT: i64 = 100;
/// A name-only helper can still win on evidence; this only stops it winning on its name.
const LOW_SIGNAL_HELPER_PENALTY: i64 = 4;

const MAX_CLASSES_PER_COMPONENT: usize = 5;

const UTILITY_PACKAGE_SIGNALS: [&str; 5] = ["common", "shared", "support", "util", "utils"];
const HELPER_CLASS_SIGNALS: [&str; 4] = ["Helper", "Support", "Util", "Utils"];

/// One selected component.
pub struct Component {
    pub id: String,
    pub name: String,
    pub kind: String,
    pub architecture_type: &'static str,
    pub responsibility: String,
    pub full_name: String,
    pub container: String,
    pub container_id: String,
    pub methods: i64,
    pub call_sites: i64,
    pub endpoints: i64,
    pub package_units: Vec<String>,
    pub classes: Vec<String>,
    pub entrypoints: Vec<String>,
    pub why_selected: Vec<String>,
}

/// A call dependency between two selected components.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ComponentRelationship {
    pub from: String,
    pub to: String,
    /// `routes-to`, `orchestrates`, `uses` or `collaborates-with`; also the wire type.
    pub kind: String,
    pub description: String,
    /// Cross-capability call sites behind the edge.
    pub weight: i64,
}

impl DirectedEdge for ComponentRelationship {
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

/// The component view, or `None` when no runtime container was inferred.
///
/// Library and package code is deliberately not promoted to component scope without a
/// runtime boundary to hold it.
pub struct ComponentView {
    pub components: Vec<Component>,
    pub container: Container,
    /// The readable subset of the cross-capability call edges, strongest first.
    pub relationships: Vec<ComponentRelationship>,
}

/// Where a capability sits in the dependency layering, by its container kind.
fn dependency_layer_rank(kind: &str) -> i64 {
    match kind {
        "interface" | "entrypoint" => container_layer_ranks::INTERFACE,
        "orchestrator" | "integration" => container_layer_ranks::ORCHESTRATION,
        "shared-capability" => container_layer_ranks::SHARED_CAPABILITY,
        _ => container_layer_ranks::CAPABILITY,
    }
}

/// Orient an edge downward through the layers: whichever end sits higher is the source.
fn canonical_pair(
    source: String,
    target: String,
    source_kind: &str,
    target_kind: &str,
) -> (String, String) {
    if dependency_layer_rank(source_kind) > dependency_layer_rank(target_kind) {
        (target, source)
    } else {
        (source, target)
    }
}

pub fn infer_dependency_kind(source_kind: &str, target_kind: &str) -> &'static str {
    if source_kind == "interface" || source_kind == "entrypoint" {
        "routes-to"
    } else if source_kind == "orchestrator" {
        "orchestrates"
    } else if target_kind == "shared-capability" || target_kind == "integration" {
        "uses"
    } else {
        "collaborates-with"
    }
}

pub fn describe_dependency(kind: &str, source: &str, target: &str) -> String {
    match kind {
        "routes-to" => format!("{source} routes work to {target}"),
        "orchestrates" => format!("{source} orchestrates {target}"),
        "uses" => format!("{source} uses {target}"),
        _ => format!("{source} collaborates with {target}"),
    }
}

/// Keep the edges a component diagram can show: architectural kinds over plain
/// collaboration, transitively reduced, at most two out of and into each component,
/// twelve in all -- and the caps relaxed when they would leave the diagram too sparse.
pub fn select_readable_relationships(
    relationships: Vec<ComponentRelationship>,
) -> Vec<ComponentRelationship> {
    if relationships.len() <= 1 {
        return relationships;
    }
    let mut architectural: Vec<ComponentRelationship> = relationships
        .iter()
        .filter(|r| r.kind != "collaborates-with")
        .cloned()
        .collect();
    if architectural.is_empty() {
        architectural = relationships;
    }
    let mut seen = std::collections::HashSet::new();
    architectural.retain(|r| seen.insert(format!("{}:{}:{}", r.from, r.to, r.kind)));
    architectural.sort_by_key(|r| std::cmp::Reverse(r.weight));
    let reduced = reduce_transitive(architectural, false);

    let mut selected: Vec<ComponentRelationship> = Vec::new();
    let mut outgoing: std::collections::HashMap<String, i64> = std::collections::HashMap::new();
    let mut incoming: std::collections::HashMap<String, i64> = std::collections::HashMap::new();
    let mut try_add = |selected: &mut Vec<ComponentRelationship>,
                       edge: &ComponentRelationship,
                       enforce_caps: bool| {
        if edge.from.trim().is_empty() || edge.to.trim().is_empty() {
            return;
        }
        if selected
            .iter()
            .any(|s| s.from == edge.from && s.to == edge.to && s.kind == edge.kind)
        {
            return;
        }
        if enforce_caps
            && (outgoing.get(&edge.from).copied().unwrap_or(0) >= MAX_OUTGOING_EDGES_PER_COMPONENT
                || incoming.get(&edge.to).copied().unwrap_or(0) >= MAX_INCOMING_EDGES_PER_COMPONENT)
        {
            return;
        }
        selected.push(edge.clone());
        *outgoing.entry(edge.from.clone()).or_default() += 1;
        *incoming.entry(edge.to.clone()).or_default() += 1;
    };
    for edge in &reduced {
        if selected.len() >= MAX_VIEW_EDGES {
            break;
        }
        try_add(&mut selected, edge, true);
    }
    if selected.len()
        < MAX_VIEW_EDGES
            .min(reduced.len())
            .min(MIN_EDGES_AFTER_CAP_RELAXATION)
    {
        for edge in &reduced {
            if selected.len() >= MAX_VIEW_EDGES {
                break;
            }
            try_add(&mut selected, edge, false);
        }
    }
    selected
}

pub fn architecture_type(kind: &str) -> &'static str {
    match kind {
        "entrypoint" | "orchestrator" | "integration" | "coordination" => "application-service",
        _ => "application-component",
    }
}

pub fn infer_kind(endpoints: i64, inbound: i64, outbound: i64, external: i64) -> &'static str {
    if endpoints > 0 {
        return "entrypoint";
    }
    if external > inbound.max(outbound) {
        "integration"
    } else if outbound > inbound {
        "orchestrator"
    } else if inbound > outbound {
        "shared-capability"
    } else if inbound > 0 || outbound > 0 {
        "coordination"
    } else {
        "domain-component"
    }
}

pub fn responsibility(
    container_name: &str,
    endpoints: i64,
    inbound: i64,
    outbound: i64,
    external: i64,
) -> String {
    if endpoints > 0 {
        return "Accepts external requests and translates them into internal application operations"
            .to_string();
    }
    if external > inbound.max(outbound) {
        format!("Connects the {container_name} capability to external collaborators and dependency boundaries")
    } else if outbound > inbound {
        format!("Coordinates work across neighboring capabilities inside {container_name}")
    } else if inbound > outbound {
        format!("Provides a shared internal capability that other containers depend on through {container_name}")
    } else if inbound > 0 || outbound > 0 {
        format!("Sits on a coordination path inside {container_name} and participates in cross-container flows")
    } else {
        format!("Implements a structurally central part of the {container_name} capability")
    }
}

fn selection_reasons(c: &Container, endpoints: i64, external_calls: i64) -> Vec<String> {
    let mut out = Vec::new();
    if endpoints > 0 {
        out.push("entrypoint-facing capability".to_string());
    }
    if c.inbound > 0 {
        out.push("used by neighboring capabilities".to_string());
    }
    if c.outbound > 0 {
        out.push("depends on neighboring capabilities".to_string());
    }
    if external_calls > 0 {
        out.push("external dependency touchpoint".to_string());
    }
    if out.is_empty() {
        out.push("structural size within the runtime container".to_string());
    }
    out
}

fn capability_score(c: &Container, endpoints: i64, calls: i64, external: i64) -> i64 {
    endpoints * ENDPOINT_WEIGHT
        + (c.inbound + c.outbound) * CROSS_CAPABILITY_WEIGHT
        + external * EXTERNAL_CALL_WEIGHT
        + calls * CALL_WEIGHT
        + c.method_count * METHOD_WEIGHT
}

/// True when a class reads like a helper and has no graph evidence to back it up.
fn is_utility_like(
    class_name: &str,
    boundary: &str,
    endpoints: i64,
    inbound: i64,
    outbound: i64,
    capability_class_count: usize,
) -> bool {
    let relative = class_name
        .strip_prefix(&format!("{boundary}."))
        .unwrap_or(class_name);
    let package = match relative.rfind('.') {
        Some(i) => &relative[..i],
        None => "",
    };
    let package_signal = package
        .split('.')
        .any(|s| UTILITY_PACKAGE_SIGNALS.contains(&s));
    let simple = class_name.rsplit('.').next().unwrap_or(class_name);
    let helper_signal = HELPER_CLASS_SIGNALS.iter().any(|s| simple.ends_with(s));
    let naming = package_signal || helper_signal;
    naming && endpoints == 0 && inbound == 0 && outbound == 0 && capability_class_count <= 1
}

#[allow(clippy::too_many_arguments)]
fn class_score(
    class_name: &str,
    boundary: &str,
    methods: i64,
    calls: i64,
    endpoints: i64,
    inbound: i64,
    outbound: i64,
    capability_class_count: usize,
) -> i64 {
    let base = (inbound + outbound) * REPRESENTATIVE_CROSS_CAPABILITY_WEIGHT
        + endpoints * REPRESENTATIVE_ENDPOINT_WEIGHT
        + calls * CALL_WEIGHT
        + methods * METHOD_WEIGHT;
    let penalty = if is_utility_like(
        class_name,
        boundary,
        endpoints,
        inbound,
        outbound,
        capability_class_count,
    ) {
        LOW_SIGNAL_HELPER_PENALTY
    } else {
        1
    };
    base / penalty
}

/// Select the components of the runtime container, ranked by architectural evidence.
pub fn build_view(
    g: &Graph,
    boundary: &str,
    endpoint_classes: &[String],
    subject_role: &str,
    subject_name: &str,
    capability_layout: &ContainerLayout,
    limit: usize,
) -> Option<ComponentView> {
    let runtime_layout =
        containers::infer_operational_layout(subject_role, subject_name, capability_layout);
    let runtime_container = runtime_layout.containers.first()?.clone();

    let mut method_counts: IndexMap<String, i64> = IndexMap::new();
    for m in g.methods() {
        let c = g.str(m.declaring_class);
        if is_internal_class(c, boundary) {
            *method_counts.entry(c.to_string()).or_default() += 1;
        }
    }
    let mut endpoint_counts: IndexMap<String, i64> = IndexMap::new();
    for c in endpoint_classes {
        if is_internal_class(c, boundary) {
            *endpoint_counts.entry(c.clone()).or_default() += 1;
        }
    }

    let capability_by_id: IndexMap<&str, &Container> = capability_layout
        .containers
        .iter()
        .map(|c| (c.id.as_str(), c))
        .collect();
    let component_id = |capability_id: &str| {
        format!(
            "component:{}",
            capability_id
                .strip_prefix("container:")
                .unwrap_or(capability_id)
        )
    };
    let mut call_counts: IndexMap<String, i64> = IndexMap::new();
    let mut external_by_capability: IndexMap<String, i64> = IndexMap::new();
    let mut calls_by_capability: IndexMap<String, i64> = IndexMap::new();
    // Cross-capability call sites per canonical (source, target) pair, first seen first.
    let mut relationship_weights: IndexMap<(String, String), i64> = IndexMap::new();
    for &id in g.ids_by_tag(graphite_storage::node::TAG_CALL_SITE_NODE) {
        let Some(s) = g.call_site_strings(id) else {
            continue;
        };
        let caller = g.str(s.caller_class);
        let callee = g.str(s.callee_class);
        let caller_internal = is_internal_class(caller, boundary);
        let callee_internal = is_internal_class(callee, boundary);
        if !caller_internal && !callee_internal {
            continue;
        }
        if caller_internal {
            *call_counts.entry(caller.to_string()).or_default() += 1;
        }
        if callee_internal {
            *call_counts.entry(callee.to_string()).or_default() += 1;
        }
        let caller_capability = caller_internal
            .then(|| {
                capability_layout
                    .unit_to_container
                    .get(&internal_package_unit(caller, boundary))
                    .cloned()
            })
            .flatten();
        let callee_capability = callee_internal
            .then(|| {
                capability_layout
                    .unit_to_container
                    .get(&internal_package_unit(callee, boundary))
                    .cloned()
            })
            .flatten();
        if let Some(c) = &caller_capability {
            *calls_by_capability.entry(c.clone()).or_default() += 1;
        }
        if let Some(c) = &callee_capability {
            *calls_by_capability.entry(c.clone()).or_default() += 1;
        }
        if !caller_internal || !callee_internal {
            // A call out of the boundary is integration evidence, unless it is the
            // language runtime, which every capability touches.
            if caller_internal && !is_runtime_class(callee) {
                if let Some(c) = &caller_capability {
                    *external_by_capability.entry(c.clone()).or_default() += 1;
                }
            }
            continue;
        }
        if let (Some(from), Some(to)) = (&caller_capability, &callee_capability) {
            if from != to {
                let kind_of = |id: &str| {
                    capability_by_id
                        .get(id)
                        .map(|c| containers::container_kind(c))
                        .unwrap_or_else(|| "capability".to_string())
                };
                let pair = canonical_pair(
                    component_id(from),
                    component_id(to),
                    &kind_of(from),
                    &kind_of(to),
                );
                *relationship_weights.entry(pair).or_default() += 1;
            }
        }
    }

    let capability_ids: Vec<String> = capability_layout
        .containers
        .iter()
        .map(|c| c.id.clone())
        .collect();
    let mut classes_by_capability: IndexMap<String, Vec<String>> = IndexMap::new();
    let mut candidates: Vec<String> = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for name in method_counts
        .keys()
        .chain(endpoint_counts.keys())
        .chain(call_counts.keys())
    {
        if seen.insert(name.clone()) {
            candidates.push(name.clone());
        }
    }
    let mut endpoints_by_capability: IndexMap<String, i64> = IndexMap::new();
    for class_name in &candidates {
        let Some(cap) = capability_layout
            .unit_to_container
            .get(&internal_package_unit(class_name, boundary))
        else {
            continue;
        };
        if !capability_ids.contains(cap) {
            continue;
        }
        classes_by_capability
            .entry(cap.clone())
            .or_default()
            .push(class_name.clone());
        if let Some(n) = endpoint_counts.get(class_name) {
            *endpoints_by_capability.entry(cap.clone()).or_default() += n;
        }
    }

    let endpoints_of = |c: &Container| {
        endpoints_by_capability
            .get(&c.id)
            .copied()
            .unwrap_or(c.endpoint_count)
    };
    let calls_of = |c: &Container| {
        calls_by_capability
            .get(&c.id)
            .copied()
            .unwrap_or(c.call_site_count)
    };
    let external_of = |c: &Container| {
        external_by_capability
            .get(&c.id)
            .copied()
            .unwrap_or(c.external_calls)
    };

    let mut ranked: Vec<&Container> = capability_layout.containers.iter().collect();
    // Highest score first, name ascending as the tie-break -- a stable sort over a
    // name-ordered list, which is how the baseline's comparator reads.
    ranked.sort_by(|a, b| a.name.cmp(&b.name));
    ranked.sort_by(|a, b| {
        capability_score(b, endpoints_of(b), calls_of(b), external_of(b)).cmp(&capability_score(
            a,
            endpoints_of(a),
            calls_of(a),
            external_of(a),
        ))
    });
    ranked.truncate(limit);
    let kind_by_id: IndexMap<&str, &'static str> = ranked
        .iter()
        .map(|c| {
            (
                c.id.as_str(),
                infer_kind(endpoints_of(c), c.inbound, c.outbound, external_of(c)),
            )
        })
        .collect();

    let capability_of = |component: &str| {
        format!(
            "container:{}",
            component.strip_prefix("component:").unwrap_or(component)
        )
    };
    let mut candidate_edges: Vec<(&(String, String), i64)> = relationship_weights
        .iter()
        .filter(|((from, to), _)| {
            kind_by_id.contains_key(capability_of(from).as_str())
                && kind_by_id.contains_key(capability_of(to).as_str())
        })
        .map(|(pair, w)| (pair, *w))
        .collect();
    candidate_edges.sort_by_key(|e| std::cmp::Reverse(e.1));
    let relationships = select_readable_relationships(
        candidate_edges
            .into_iter()
            .map(|((from, to), weight)| {
                let source_capability = capability_of(from);
                let target_capability = capability_of(to);
                let source_kind = kind_by_id
                    .get(source_capability.as_str())
                    .copied()
                    .unwrap_or("domain-component");
                let target_kind = kind_by_id
                    .get(target_capability.as_str())
                    .copied()
                    .unwrap_or("domain-component");
                let kind = infer_dependency_kind(source_kind, target_kind);
                let name_of = |capability: &str, component: &str| {
                    capability_by_id
                        .get(capability)
                        .map(|c| c.name.clone())
                        .unwrap_or_else(|| {
                            component
                                .strip_prefix("component:")
                                .unwrap_or(component)
                                .to_string()
                        })
                };
                ComponentRelationship {
                    from: from.clone(),
                    to: to.clone(),
                    kind: kind.to_string(),
                    description: describe_dependency(
                        kind,
                        &name_of(&source_capability, from),
                        &name_of(&target_capability, to),
                    ),
                    weight,
                }
            })
            .collect(),
    );

    let components = ranked
        .into_iter()
        .map(|c| {
            let endpoints = endpoints_of(c);
            let external = external_of(c);
            let kind = kind_by_id[c.id.as_str()];
            let mut classes = classes_by_capability
                .get(&c.id)
                .cloned()
                .unwrap_or_default();
            let class_count = classes.len();
            classes.sort_by(|a, b| {
                class_score(
                    b,
                    boundary,
                    method_counts.get(b).copied().unwrap_or(0),
                    call_counts.get(b).copied().unwrap_or(0),
                    endpoint_counts.get(b).copied().unwrap_or(0),
                    c.inbound,
                    c.outbound,
                    class_count,
                )
                .cmp(&class_score(
                    a,
                    boundary,
                    method_counts.get(a).copied().unwrap_or(0),
                    call_counts.get(a).copied().unwrap_or(0),
                    endpoint_counts.get(a).copied().unwrap_or(0),
                    c.inbound,
                    c.outbound,
                    class_count,
                ))
            });
            if classes.is_empty() {
                classes = c.primary_classes.clone();
            }
            classes.truncate(MAX_CLASSES_PER_COMPONENT);
            let mut units = c.package_units.clone();
            units.sort();
            Component {
                id: component_id(&c.id),
                name: c.name.clone(),
                kind: kind.to_string(),
                architecture_type: architecture_type(kind),
                responsibility: responsibility(
                    &runtime_container.name,
                    endpoints,
                    c.inbound,
                    c.outbound,
                    external,
                ),
                full_name: units.join(","),
                container: runtime_container.name.clone(),
                container_id: runtime_container.id.clone(),
                methods: c.method_count,
                call_sites: calls_of(c),
                endpoints,
                package_units: units,
                classes,
                entrypoints: c.entrypoints.clone(),
                why_selected: selection_reasons(c, endpoints, external),
            }
        })
        .collect();

    Some(ComponentView {
        components,
        container: runtime_container,
        relationships,
    })
}

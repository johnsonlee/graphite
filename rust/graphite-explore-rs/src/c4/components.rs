//! Component selection: which internal capabilities become components, and why.
//!
//! A container is a runtime boundary; the components inside it are the capabilities the
//! code actually splits into. Every package unit is a candidate, so the work is ranking
//! them by architectural evidence and keeping the diagram readable — endpoints first,
//! then cross-capability traffic, then integration with external dependencies, with
//! local size only breaking ties.

use super::boundary::{internal_package_unit, is_internal_class, is_runtime_class};
use super::containers::{self, Container, ContainerLayout};
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
pub const MIN_CAPABILITY_LAYOUT_CANDIDATES: usize = 8;

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

/// The component view, or `None` when no runtime container was inferred.
///
/// Library and package code is deliberately not promoted to component scope without a
/// runtime boundary to hold it.
pub struct ComponentView {
    pub components: Vec<Component>,
    pub container: Container,
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
    let package_signal = package.split('.').any(|s| UTILITY_PACKAGE_SIGNALS.contains(&s));
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

    let mut call_counts: IndexMap<String, i64> = IndexMap::new();
    let mut external_by_capability: IndexMap<String, i64> = IndexMap::new();
    let mut calls_by_capability: IndexMap<String, i64> = IndexMap::new();
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
        capability_score(b, endpoints_of(b), calls_of(b), external_of(b))
            .cmp(&capability_score(a, endpoints_of(a), calls_of(a), external_of(a)))
    });
    ranked.truncate(limit);

    let components = ranked
        .into_iter()
        .map(|c| {
            let endpoints = endpoints_of(c);
            let external = external_of(c);
            let kind = infer_kind(endpoints, c.inbound, c.outbound, external);
            let mut classes = classes_by_capability.get(&c.id).cloned().unwrap_or_default();
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
                id: format!("component:{}", c.id.trim_start_matches("container:")),
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
    })
}

//! Container clustering: group tightly-coupled package units into capabilities.

use super::boundary::*;
use super::constants::*;
use super::external::{summarize, ExternalDependency};
use super::util::{humanize_identifier, slugify};
use graphite_storage::node::TAG_CALL_SITE_NODE;
use graphite_storage::Graph;
use indexmap::{IndexMap, IndexSet};

#[derive(Debug, Clone)]
pub struct Container {
    pub id: String,
    pub name: String,
    pub package_units: Vec<String>,
    pub method_count: i64,
    pub call_site_count: i64,
    pub endpoint_count: i64,
    pub inbound: i64,
    pub outbound: i64,
    pub external_calls: i64,
    pub entrypoints: Vec<String>,
    pub primary_classes: Vec<String>,
    pub rationale: String,
    pub declared_kind: Option<String>,
}

#[derive(Debug, Clone)]
pub struct ContainerLayout {
    pub system_boundary: String,
    pub containers: Vec<Container>,
    /// Package unit to container id. Part of the ported layout; the renderers use
    /// the containers directly.
    #[allow(dead_code)]
    pub unit_to_container: IndexMap<String, String>,
    pub external_dependencies: Vec<ExternalDependency>,
}

pub fn container_kind(c: &Container) -> String {
    if let Some(k) = &c.declared_kind {
        return k.clone();
    }
    infer_kind(c.endpoint_count, c.inbound, c.outbound, c.external_calls)
}

pub fn infer_kind(endpoints: i64, inbound: i64, outbound: i64, external: i64) -> String {
    let dominant = inbound.max(outbound).max(external);
    if endpoints > 0 {
        "interface".into()
    } else if external > 0 && external == dominant {
        "integration".into()
    } else if outbound > inbound {
        "orchestrator".into()
    } else if inbound > outbound {
        "shared-capability".into()
    } else {
        "capability".into()
    }
}

pub fn architecture_type(kind: &str) -> &'static str {
    match kind {
        // A runtime container is an application *service*, not an "application-runtime":
        // the baseline folds the runtime kind in with the service kinds here, and the
        // distinction only surfaces at `level=container`, where the container element is
        // emitted at all.
        "application-runtime"
        | "application-service"
        | "interface"
        | "orchestrator"
        | "integration" => "application-service",
        _ => "application-component",
    }
}

pub fn description(kind: &str) -> &'static str {
    match kind {
        "application-runtime" | "application-service" => {
            "Executable/deployable runtime container inferred from entrypoint, endpoint, and archive evidence"
        }
        _ => "Internal capability evidence derived from code graph structure",
    }
}

pub fn operational_responsibility(kind: &str) -> Option<&'static str> {
    match kind {
        "application-runtime" | "application-service" => {
            Some("Runs the subject software system and owns the deployable JVM execution boundary")
        }
        _ => None,
    }
}

/// Union-find over package units, joined by mutual or dominant traffic.
fn cluster_units(units: &[String], traffic: &IndexMap<(String, String), i64>) -> Vec<Vec<String>> {
    if units.is_empty() {
        return Vec::new();
    }
    let index: IndexMap<&str, usize> = units
        .iter()
        .enumerate()
        .map(|(i, u)| (u.as_str(), i))
        .collect();
    let mut adjacency: Vec<IndexMap<usize, i64>> = vec![IndexMap::new(); units.len()];
    for ((l, r), w) in traffic {
        if let (Some(&li), Some(&ri)) = (index.get(l.as_str()), index.get(r.as_str())) {
            *adjacency[li].entry(ri).or_insert(0) += w;
            *adjacency[ri].entry(li).or_insert(0) += w;
        }
    }
    // Strongest neighbour: highest weight, ties broken by lexicographically smallest unit.
    let strongest: Vec<Option<usize>> = adjacency
        .iter()
        .map(|nbrs| {
            nbrs.iter()
                .fold(None::<(usize, i64)>, |best, (&n, &w)| match best {
                    Some((bn, bw)) if bw > w || (bw == w && units[bn] <= units[n]) => best,
                    _ => Some((n, w)),
                })
                .map(|(n, _)| n)
        })
        .collect();
    let mut parents: Vec<usize> = (0..units.len()).collect();
    fn find(parents: &mut Vec<usize>, mut i: usize) -> usize {
        while parents[i] != i {
            parents[i] = parents[parents[i]];
            i = parents[i];
        }
        i
    }
    fn union(parents: &mut Vec<usize>, l: usize, r: usize) {
        let (a, b) = (find(parents, l), find(parents, r));
        parents[b] = a;
    }
    for i in 0..units.len() {
        if let Some(n) = strongest[i] {
            if strongest[n] == Some(i) {
                union(&mut parents, i, n);
            }
        }
    }
    for i in 0..units.len() {
        if find(&mut parents, i) != i {
            continue;
        }
        if let Some(n) = strongest[i] {
            if find(&mut parents, n) == i {
                continue;
            }
            let total: i64 = adjacency[i].values().sum();
            let strongest_weight = adjacency[i].get(&n).copied().unwrap_or(0);
            if total > 0 && strongest_weight * 2 >= total {
                union(&mut parents, i, n);
            }
        }
    }
    let mut groups: IndexMap<usize, Vec<String>> = IndexMap::new();
    for i in 0..units.len() {
        let root = find(&mut parents, i);
        groups.entry(root).or_default().push(units[i].clone());
    }
    let mut out: Vec<Vec<String>> = groups
        .into_values()
        .map(|mut g| {
            g.sort();
            g
        })
        .collect();
    out.sort_by(|a, b| {
        a.first()
            .cloned()
            .unwrap_or_default()
            .cmp(&b.first().cloned().unwrap_or_default())
    });
    out
}

/// TF-IDF naming: rare tokens in a cluster's package units make the best name.
fn infer_name(
    package_units: &[String],
    unit_scores: &IndexMap<String, i64>,
    token_df: &IndexMap<String, usize>,
    boundary: &str,
) -> String {
    let mut token_scores: IndexMap<String, f64> = IndexMap::new();
    for unit in package_units {
        let suffix = unit
            .strip_prefix(boundary)
            .unwrap_or(unit)
            .trim_start_matches('.');
        let weight = unit_scores.get(unit).copied().unwrap_or(1) as f64;
        for token in suffix.split('.').filter(|t| !t.is_empty()) {
            let rarity = 1.0 / token_df.get(token).copied().unwrap_or(1) as f64;
            *token_scores.entry(token.to_string()).or_insert(0.0) += weight * rarity;
        }
    }
    let mut ranked: Vec<(&String, f64)> = token_scores.iter().map(|(k, v)| (k, *v)).collect();
    ranked.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
    let mut selected: Vec<String> = Vec::new();
    if let Some((first, first_score)) = ranked.first() {
        selected.push(humanize_identifier(first));
        if let Some((second, second_score)) = ranked.get(1) {
            if *first_score > MIN_POSITIVE_TOKEN_SCORE
                && *second_score >= *first_score * SECOND_TOKEN_MIN_SCORE_RATIO
            {
                let h = humanize_identifier(second);
                if !selected.contains(&h) {
                    selected.push(h);
                }
            }
        }
    }
    match selected.len() {
        0 => boundary.to_string(),
        1 => selected.remove(0),
        _ => selected.join(" and "),
    }
}

fn build_rationale(unit_count: usize, representative: &str, has_entrypoints: bool) -> String {
    if has_entrypoints {
        format!("Grouped from {unit_count} tightly-coupled package unit(s) with visible inbound entrypoints; anchored by {representative}")
    } else if unit_count > 1 {
        format!("Grouped from {unit_count} mutually dependent package units around the structural center {representative}")
    } else {
        format!("Derived from the dominant internal package unit {representative}")
    }
}

/// Cluster internal package units into containers and classify external dependencies.
pub fn infer_layout(
    g: &Graph,
    boundary: &str,
    endpoint_classes: &[String],
    endpoint_paths: &IndexMap<String, Vec<String>>,
    limit: usize,
) -> ContainerLayout {
    let mut internal_classes: IndexSet<String> = IndexSet::new();
    let mut method_counts: IndexMap<String, i64> = IndexMap::new();
    for m in g.methods() {
        let c = g.str(m.declaring_class).to_string();
        if is_internal_class(&c, boundary) {
            internal_classes.insert(c.clone());
            *method_counts
                .entry(internal_package_unit(&c, boundary))
                .or_insert(0) += 1;
        }
    }
    for c in endpoint_classes {
        if is_internal_class(c, boundary) {
            internal_classes.insert(c.clone());
        }
    }
    let mut endpoint_counts: IndexMap<String, i64> = IndexMap::new();
    let mut endpoint_paths_by_unit: IndexMap<String, IndexSet<String>> = IndexMap::new();
    for c in endpoint_classes {
        if !is_internal_class(c, boundary) {
            continue;
        }
        let unit = internal_package_unit(c, boundary);
        *endpoint_counts.entry(unit.clone()).or_insert(0) += 1;
        if let Some(paths) = endpoint_paths.get(c) {
            let e = endpoint_paths_by_unit.entry(unit).or_default();
            for p in paths {
                if !p.trim().is_empty() {
                    e.insert(p.clone());
                }
            }
        }
    }

    let mut traffic: IndexMap<(String, String), i64> = IndexMap::new();
    let mut external_weights: IndexMap<String, i64> = IndexMap::new();
    for &id in g.ids_by_tag(TAG_CALL_SITE_NODE) {
        let s = match g.call_site_strings(id) {
            Some(s) => s,
            None => continue,
        };
        let caller = g.str(s.caller_class);
        let callee = g.str(s.callee_class);
        if is_synthetic_class(caller) || is_synthetic_class(callee) {
            continue;
        }
        let caller_internal = is_internal_class(caller, boundary);
        let callee_internal = is_internal_class(callee, boundary);
        if caller_internal {
            internal_classes.insert(caller.to_string());
        }
        if callee_internal {
            internal_classes.insert(callee.to_string());
        }
        if caller_internal && callee_internal {
            let cu = internal_package_unit(caller, boundary);
            let du = internal_package_unit(callee, boundary);
            if cu != du {
                let key = if cu <= du { (cu, du) } else { (du, cu) };
                *traffic.entry(key).or_insert(0) += 1;
            }
        } else if caller_internal {
            // Runtime classes are included here, unlike the per-container counters.
            *external_weights.entry(callee.to_string()).or_insert(0) += 1;
        }
    }
    let mut traffic_by_unit: IndexMap<String, i64> = IndexMap::new();
    for ((l, r), w) in &traffic {
        *traffic_by_unit.entry(l.clone()).or_insert(0) += w;
        *traffic_by_unit.entry(r.clone()).or_insert(0) += w;
    }

    let mut classes_by_unit: IndexMap<String, Vec<String>> = IndexMap::new();
    for c in &internal_classes {
        classes_by_unit
            .entry(internal_package_unit(c, boundary))
            .or_default()
            .push(c.clone());
    }
    let mut all_units: Vec<String> = Vec::new();
    for k in method_counts
        .keys()
        .chain(endpoint_counts.keys())
        .chain(classes_by_unit.keys())
    {
        if !all_units.contains(k) {
            all_units.push(k.clone());
        }
    }
    let mut token_df: IndexMap<String, usize> = IndexMap::new();
    for unit in &all_units {
        let suffix = unit
            .strip_prefix(boundary)
            .unwrap_or(unit)
            .trim_start_matches('.');
        let mut seen: IndexSet<&str> = IndexSet::new();
        for t in suffix.split('.').filter(|t| !t.is_empty()) {
            seen.insert(t);
        }
        for t in seen {
            *token_df.entry(t.to_string()).or_insert(0) += 1;
        }
    }

    let clusters = cluster_units(&all_units, &traffic);
    let mut scored: Vec<(Vec<String>, i64, i64)> = clusters
        .into_iter()
        .map(|units| {
            let methods: i64 = units
                .iter()
                .map(|u| method_counts.get(u).copied().unwrap_or(0))
                .sum();
            let endpoints: i64 = units
                .iter()
                .map(|u| endpoint_counts.get(u).copied().unwrap_or(0))
                .sum();
            let calls: i64 = units
                .iter()
                .map(|u| traffic_by_unit.get(u).copied().unwrap_or(0))
                .sum();
            let score =
                methods * METHOD_WEIGHT + endpoints * ENDPOINT_WEIGHT + calls * TRAFFIC_WEIGHT;
            (units, score, calls)
        })
        .collect();
    scored.sort_by(|a, b| {
        b.1.cmp(&a.1).then(b.2.cmp(&a.2)).then(
            a.0.first()
                .cloned()
                .unwrap_or_default()
                .cmp(&b.0.first().cloned().unwrap_or_default()),
        )
    });
    scored.truncate(limit);

    let mut containers: Vec<Container> = Vec::new();
    for (units, _, calls) in scored {
        let mut unit_scores: IndexMap<String, i64> = IndexMap::new();
        for u in &units {
            let score = method_counts.get(u).copied().unwrap_or(0) * REPRESENTATIVE_METHOD_WEIGHT
                + endpoint_counts.get(u).copied().unwrap_or(0) * ENDPOINT_WEIGHT
                + traffic_by_unit.get(u).copied().unwrap_or(0) * TRAFFIC_WEIGHT;
            unit_scores.insert(u.clone(), score);
        }
        let representative = unit_scores
            .iter()
            .fold(None::<(&String, i64)>, |best, (k, v)| match best {
                Some((_, bv)) if bv >= *v => best,
                _ => Some((k, *v)),
            })
            .map(|(k, _)| k.clone())
            .unwrap_or_else(|| units[0].clone());
        let mut classes: Vec<String> = Vec::new();
        for u in &units {
            if let Some(cs) = classes_by_unit.get(u) {
                classes.extend(cs.iter().cloned());
            }
        }
        let mut entrypoints: IndexSet<String> = IndexSet::new();
        for u in &units {
            if let Some(ps) = endpoint_paths_by_unit.get(u) {
                entrypoints.extend(ps.iter().cloned());
            }
        }
        let mut entrypoints: Vec<String> = entrypoints.into_iter().collect();
        entrypoints.sort();
        entrypoints.truncate(MAX_ENTRYPOINTS_PER_CONTAINER);
        let methods: i64 = units
            .iter()
            .map(|u| method_counts.get(u).copied().unwrap_or(0))
            .sum();
        let endpoints: i64 = units
            .iter()
            .map(|u| endpoint_counts.get(u).copied().unwrap_or(0))
            .sum();
        let name = infer_name(&units, &unit_scores, &token_df, boundary);
        let mut class_counts: IndexMap<&String, usize> = IndexMap::new();
        for c in &classes {
            *class_counts.entry(c).or_insert(0) += 1;
        }
        let mut ranked: Vec<(&&String, usize)> =
            class_counts.iter().map(|(k, v)| (k, *v)).collect();
        ranked.sort_by(|a, b| b.1.cmp(&a.1));
        let primary_classes: Vec<String> = ranked
            .iter()
            .take(MAX_PRIMARY_CLASSES_PER_CONTAINER)
            .map(|(c, _)| c.rsplit('.').next().unwrap_or(c).to_string())
            .collect();
        let id = format!(
            "{CONTAINER_ID_PREFIX}{}",
            slugify(if name.trim().is_empty() {
                &representative
            } else {
                &name
            })
        );
        containers.push(Container {
            id,
            name,
            package_units: units.clone(),
            method_count: methods,
            call_site_count: calls,
            endpoint_count: endpoints,
            inbound: 0,
            outbound: 0,
            external_calls: 0,
            entrypoints: entrypoints.clone(),
            primary_classes,
            rationale: build_rationale(units.len(), &representative, !entrypoints.is_empty()),
            declared_kind: None,
        });
    }

    let mut unit_to_container: IndexMap<String, String> = IndexMap::new();
    for c in &containers {
        for u in &c.package_units {
            unit_to_container.insert(u.clone(), c.id.clone());
        }
    }
    // Second pass for accurate per-container counters.
    let mut call_site_counts: IndexMap<String, i64> = IndexMap::new();
    let mut inbound: IndexMap<String, i64> = IndexMap::new();
    let mut outbound: IndexMap<String, i64> = IndexMap::new();
    let mut external_calls: IndexMap<String, i64> = IndexMap::new();
    for &id in g.ids_by_tag(TAG_CALL_SITE_NODE) {
        let s = match g.call_site_strings(id) {
            Some(s) => s,
            None => continue,
        };
        let caller = g.str(s.caller_class);
        let callee = g.str(s.callee_class);
        if is_synthetic_class(caller) || is_synthetic_class(callee) {
            continue;
        }
        let caller_internal = is_internal_class(caller, boundary);
        let callee_internal = is_internal_class(callee, boundary);
        let caller_container = caller_internal
            .then(|| {
                unit_to_container
                    .get(&internal_package_unit(caller, boundary))
                    .cloned()
            })
            .flatten();
        let callee_container = callee_internal
            .then(|| {
                unit_to_container
                    .get(&internal_package_unit(callee, boundary))
                    .cloned()
            })
            .flatten();
        if let Some(c) = &caller_container {
            *call_site_counts.entry(c.clone()).or_insert(0) += 1;
        }
        if let Some(c) = &callee_container {
            *call_site_counts.entry(c.clone()).or_insert(0) += 1;
        }
        match (&caller_container, &callee_container) {
            (Some(a), Some(b)) if a != b => {
                *outbound.entry(a.clone()).or_insert(0) += 1;
                *inbound.entry(b.clone()).or_insert(0) += 1;
            }
            (Some(a), _) if !callee_internal && !is_runtime_class(callee) => {
                *external_calls.entry(a.clone()).or_insert(0) += 1;
            }
            _ => {}
        }
    }
    for c in &mut containers {
        c.call_site_count = call_site_counts
            .get(&c.id)
            .copied()
            .unwrap_or(c.call_site_count);
        c.inbound = inbound.get(&c.id).copied().unwrap_or(0);
        c.outbound = outbound.get(&c.id).copied().unwrap_or(0);
        c.external_calls = external_calls.get(&c.id).copied().unwrap_or(0);
    }

    ContainerLayout {
        system_boundary: boundary.to_string(),
        containers,
        unit_to_container,
        external_dependencies: summarize(g, &external_weights),
    }
}

/// The C4 runtime container. A library has no deployable runtime, so it gets none.
pub fn infer_operational_layout(
    subject_role: &str,
    subject_name: &str,
    layout: &ContainerLayout,
) -> ContainerLayout {
    if subject_role != "application" {
        return ContainerLayout {
            system_boundary: layout.system_boundary.clone(),
            containers: Vec::new(),
            unit_to_container: IndexMap::new(),
            external_dependencies: layout.external_dependencies.clone(),
        };
    }
    let has_endpoints = layout.containers.iter().any(|c| c.endpoint_count > 0);
    let mut package_units: Vec<String> = layout
        .containers
        .iter()
        .flat_map(|c| c.package_units.iter().cloned())
        .collect();
    package_units.sort();
    package_units.dedup();
    let mut entrypoints: Vec<String> = layout
        .containers
        .iter()
        .flat_map(|c| c.entrypoints.iter().cloned())
        .collect();
    entrypoints.sort();
    entrypoints.dedup();
    entrypoints.truncate(MAX_ENTRYPOINTS_PER_CONTAINER);
    let mut primary_classes: Vec<String> = Vec::new();
    for c in &layout.containers {
        for p in &c.primary_classes {
            if !primary_classes.contains(p) {
                primary_classes.push(p.clone());
            }
        }
    }
    primary_classes.truncate(MAX_PRIMARY_CLASSES_PER_CONTAINER);
    let runtime = Container {
        id: format!("{CONTAINER_ID_PREFIX}application-runtime"),
        name: format!("{subject_name} Runtime"),
        package_units: package_units.clone(),
        method_count: layout.containers.iter().map(|c| c.method_count).sum(),
        call_site_count: layout.containers.iter().map(|c| c.call_site_count).sum(),
        endpoint_count: layout.containers.iter().map(|c| c.endpoint_count).sum(),
        inbound: 0,
        outbound: 0,
        external_calls: layout.containers.iter().map(|c| c.external_calls).sum(),
        entrypoints,
        primary_classes,
        rationale: "Selected from C4 semantics as the executable/deployable JVM runtime boundary; package clusters remain internal capability evidence".into(),
        declared_kind: Some(if has_endpoints { "application-service".into() } else { "application-runtime".into() }),
    };
    let unit_to_container = package_units
        .into_iter()
        .map(|u| (u, runtime.id.clone()))
        .collect();
    ContainerLayout {
        system_boundary: layout.system_boundary.clone(),
        containers: vec![runtime],
        unit_to_container,
        external_dependencies: layout.external_dependencies.clone(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_unit_list_yields_no_clusters() {
        assert!(cluster_units(&[], &IndexMap::new()).is_empty());
    }

    #[test]
    fn mutually_strongest_units_merge() {
        let units: Vec<String> = ["a", "b", "c"].iter().map(|s| s.to_string()).collect();
        let mut traffic = IndexMap::new();
        traffic.insert(("a".to_string(), "b".to_string()), 10);
        let clusters = cluster_units(&units, &traffic);
        assert!(clusters
            .iter()
            .any(|c| c.len() == 2 && c.contains(&"a".to_string())));
    }

    #[test]
    fn kind_follows_the_dominant_interaction() {
        assert_eq!(infer_kind(1, 0, 0, 0), "interface");
        assert_eq!(infer_kind(0, 0, 0, 5), "integration");
        assert_eq!(infer_kind(0, 1, 5, 0), "orchestrator");
        assert_eq!(infer_kind(0, 5, 1, 0), "shared-capability");
        assert_eq!(infer_kind(0, 0, 0, 0), "capability");
    }

    #[test]
    fn a_library_has_no_runtime_container() {
        let layout = ContainerLayout {
            system_boundary: "x".into(),
            containers: vec![],
            unit_to_container: IndexMap::new(),
            external_dependencies: vec![],
        };
        assert!(infer_operational_layout("library", "Foo", &layout)
            .containers
            .is_empty());
        assert_eq!(
            infer_operational_layout("application", "Foo", &layout)
                .containers
                .len(),
            1
        );
    }
}

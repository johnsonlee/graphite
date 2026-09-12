//! External dependency classification: artifact, runtime or namespace grouping.

use super::boundary::*;
use super::constants::*;
use graphite_storage::Graph;
use indexmap::IndexMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DependencyKind {
    Runtime,
    Library,
    ExternalSystem,
}

impl DependencyKind {
    pub fn wire(self) -> &'static str {
        match self {
            DependencyKind::Runtime => "runtime",
            DependencyKind::Library => "library",
            DependencyKind::ExternalSystem => "external-system",
        }
    }
    pub fn architecture_type(self) -> &'static str {
        match self {
            DependencyKind::Runtime => "runtime-platform",
            DependencyKind::Library => "external-library",
            DependencyKind::ExternalSystem => "external-system",
        }
    }
    pub fn description(self) -> &'static str {
        match self {
            DependencyKind::Library => {
                "Reusable third-party library capabilities referenced from the subject system"
            }
            DependencyKind::ExternalSystem => {
                "External software system candidate inferred from referenced-but-absent classes"
            }
            DependencyKind::Runtime => {
                "Language and platform runtime supporting the subject system"
            }
        }
    }
    pub fn responsibility(self) -> &'static str {
        match self {
            DependencyKind::Runtime => {
                "Provides language and platform runtime services used by the application"
            }
            DependencyKind::Library => {
                "Provides reusable library capabilities linked from the application runtime"
            }
            DependencyKind::ExternalSystem => {
                "Represents an inferred external software system boundary grouped from referenced classes"
            }
        }
    }
}

#[derive(Debug, Clone)]
pub struct ExternalDependency {
    pub id: String,
    pub name: String,
    pub weight: i64,
    pub source: String,
    pub kind: DependencyKind,
    pub confidence: String,
    pub responsibility: String,
    /// Member artifacts when several collapse into one family entry.
    #[allow(dead_code)]
    pub artifacts: Vec<String>,
}

/// `lib/postgresql-42.7.3.jar` -> `postgresql-42.7.3`.
pub fn artifact_key(origin: &str) -> Option<String> {
    let candidate = origin
        .trim()
        .trim_end_matches('/')
        .rsplit('/')
        .next()
        .unwrap_or("")
        .strip_suffix(".jar")
        .unwrap_or_else(|| {
            origin
                .trim()
                .trim_end_matches('/')
                .rsplit('/')
                .next()
                .unwrap_or("")
        });
    if candidate.trim().is_empty() {
        None
    } else {
        Some(candidate.to_string())
    }
}

/// Group a class name down to its namespace, stopping before the first type segment.
pub fn namespace_group(name: &str) -> String {
    let segments: Vec<&str> = name.split('.').filter(|s| !s.is_empty()).collect();
    if segments.is_empty() {
        return name.to_string();
    }
    let cutoff = segments
        .iter()
        .position(|s| s.chars().next().map(|c| c.is_uppercase()).unwrap_or(false));
    let package_segments: &[&str] = match cutoff {
        Some(i) if i > 0 => &segments[..i],
        _ => &segments,
    };
    let root = namespace_root_segment_count(package_segments);
    let take = if root == 1 {
        1
    } else {
        DEFAULT_SEGMENTS.min(package_segments.len())
    };
    package_segments[..take].join(".")
}

/// Stable identity for an external class: artifact, then runtime, then namespace.
pub fn key(g: &Graph, class_name: &str) -> String {
    if let Some(origin) = g.class_origin(class_name) {
        if let Some(k) = artifact_key(origin) {
            return format!("{ARTIFACT_ID_PREFIX}{k}");
        }
    }
    if is_java_runtime_class(class_name) {
        return format!("{RUNTIME_ID_PREFIX}java");
    }
    if class_name.starts_with("kotlin.") {
        return format!("{RUNTIME_ID_PREFIX}kotlin");
    }
    if class_name.starts_with("scala.") {
        return format!("{RUNTIME_ID_PREFIX}scala");
    }
    format!("{NAMESPACE_ID_PREFIX}{}", namespace_group(class_name))
}

fn namespace_family(k: &str) -> String {
    k.strip_prefix(NAMESPACE_ID_PREFIX)
        .unwrap_or(k)
        .split('.')
        .filter(|s| !s.is_empty())
        .take(FAMILY_SEGMENTS)
        .collect::<Vec<_>>()
        .join(".")
}

fn dependency_kind(k: &str) -> DependencyKind {
    if k.starts_with(RUNTIME_ID_PREFIX) {
        DependencyKind::Runtime
    } else if k.starts_with(ARTIFACT_ID_PREFIX) {
        DependencyKind::Library
    } else {
        DependencyKind::ExternalSystem
    }
}

fn source_of(k: &str) -> &'static str {
    if k.starts_with(ARTIFACT_ID_PREFIX) {
        "artifact"
    } else if k.starts_with(RUNTIME_ID_PREFIX) {
        "runtime"
    } else {
        "namespace"
    }
}

fn confidence_of(k: &str) -> &'static str {
    if k.starts_with(ARTIFACT_ID_PREFIX) || k.starts_with(RUNTIME_ID_PREFIX) {
        "high"
    } else {
        "medium"
    }
}

fn name_of(g: &Graph, k: &str, class_names: &[String]) -> String {
    match k {
        "runtime:java" => return "Java Runtime".into(),
        "runtime:kotlin" => return "Kotlin Runtime".into(),
        "runtime:scala" => return "Scala Runtime".into(),
        _ => {}
    }
    if let Some(rest) = k.strip_prefix(ARTIFACT_ID_PREFIX) {
        return rest.to_string();
    }
    if let Some(rest) = k.strip_prefix(NAMESPACE_ID_PREFIX) {
        return rest.to_string();
    }
    class_names
        .iter()
        .find_map(|c| g.class_origin(c))
        .map(|s| s.to_string())
        .unwrap_or_else(|| k.to_string())
}

/// Collapse per-class weights into dependencies, merging namespace families that
/// contribute more than one distinct namespace.
pub fn summarize(g: &Graph, external_weights: &IndexMap<String, i64>) -> Vec<ExternalDependency> {
    let mut grouped: IndexMap<String, Vec<(String, i64)>> = IndexMap::new();
    for (class_name, weight) in external_weights {
        grouped
            .entry(key(g, class_name))
            .or_default()
            .push((class_name.clone(), *weight));
    }
    let mut family_counts: IndexMap<String, usize> = IndexMap::new();
    for k in grouped.keys() {
        if k.starts_with(NAMESPACE_ID_PREFIX) {
            *family_counts.entry(namespace_family(k)).or_insert(0) += 1;
        }
    }
    let mut merged: IndexMap<String, Vec<(String, i64)>> = IndexMap::new();
    for (k, entries) in grouped {
        let target = if k.starts_with(NAMESPACE_ID_PREFIX) {
            let family = namespace_family(&k);
            if family_counts.get(&family).copied().unwrap_or(0) > 1 {
                format!("{NAMESPACE_ID_PREFIX}{family}")
            } else {
                k.clone()
            }
        } else {
            k.clone()
        };
        merged.entry(target).or_default().extend(entries);
    }
    let mut out: Vec<ExternalDependency> = merged
        .into_iter()
        .map(|(k, entries)| {
            let class_names: Vec<String> = entries.iter().map(|(c, _)| c.clone()).collect();
            let kind = dependency_kind(&k);
            ExternalDependency {
                id: format!("{DEPENDENCY_ID_PREFIX}{k}"),
                name: name_of(g, &k, &class_names),
                weight: entries.iter().map(|(_, w)| *w).sum(),
                source: source_of(&k).to_string(),
                kind,
                confidence: confidence_of(&k).to_string(),
                responsibility: kind.responsibility().to_string(),
                artifacts: Vec::new(),
            }
        })
        .collect();
    // Stable sort keeps insertion order for equal weights.
    out.sort_by(|a, b| b.weight.cmp(&a.weight));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn artifact_key_strips_path_and_extension() {
        assert_eq!(
            artifact_key("lib/postgresql-42.7.3.jar").unwrap(),
            "postgresql-42.7.3"
        );
        assert_eq!(artifact_key("BOOT-INF/classes/").unwrap(), "classes");
        assert!(artifact_key("").is_none());
    }

    #[test]
    fn namespace_group_stops_before_the_type_segment() {
        assert_eq!(
            namespace_group("com.partner.payment.PaymentGateway"),
            "com.partner.payment"
        );
        assert_eq!(
            namespace_group("okhttp3.internal.connection.RealConnection"),
            "okhttp3"
        );
    }

    #[test]
    fn dependency_kinds_follow_the_id_prefix() {
        assert_eq!(dependency_kind("artifact:x"), DependencyKind::Library);
        assert_eq!(dependency_kind("runtime:java"), DependencyKind::Runtime);
        assert_eq!(
            dependency_kind("namespace:com.x"),
            DependencyKind::ExternalSystem
        );
    }
}

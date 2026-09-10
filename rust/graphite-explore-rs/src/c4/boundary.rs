//! System boundary detection: the dominant package prefix of the analysed code.

use super::constants::*;
use indexmap::IndexMap;

pub fn is_synthetic_class(c: &str) -> bool {
    c.starts_with("sootup.dummy.") || c == "sootup.dummy"
}

pub fn is_java_runtime_class(c: &str) -> bool {
    c.starts_with("java.") || c.starts_with("javax.") || c.starts_with("jakarta.") || c.starts_with("jdk.")
}

pub fn is_runtime_class(c: &str) -> bool {
    is_java_runtime_class(c) || c.starts_with("kotlin.") || c.starts_with("scala.")
}

pub fn is_reverse_dns_namespace(segments: &[&str]) -> bool {
    segments
        .first()
        .map(|s| REVERSE_DNS_PREFIXES.contains(&s.to_ascii_lowercase().as_str()))
        .unwrap_or(false)
}

pub fn namespace_root_segment_count(segments: &[&str]) -> usize {
    if segments.is_empty() {
        return 0;
    }
    let want = if is_reverse_dns_namespace(segments) {
        REVERSE_DNS_ROOT_SEGMENTS
    } else {
        NON_REVERSE_DNS_ROOT_SEGMENTS
    };
    want.min(segments.len())
}

/// The root namespace a package belongs to (`org.apache.lucene` -> `org.apache`).
pub fn dominant_namespace(pkg: &str) -> String {
    let segments: Vec<&str> = pkg.split('.').filter(|s| !s.is_empty()).collect();
    let take = namespace_root_segment_count(&segments);
    segments[..take].join(".")
}

pub fn is_internal_class(class_name: &str, boundary: &str) -> bool {
    !is_synthetic_class(class_name)
        && (class_name == boundary || class_name.starts_with(&format!("{boundary}.")))
}

/// The package unit a class belongs to, relative to the boundary.
pub fn internal_package_unit(class_name: &str, boundary: &str) -> String {
    let package_name = match class_name.rfind('.') {
        Some(i) => &class_name[..i],
        None => "",
    };
    if package_name.is_empty() {
        return DEFAULT_SYSTEM_BOUNDARY.to_string();
    }
    let segments: Vec<&str> = package_name.split('.').filter(|s| !s.is_empty()).collect();
    if !is_internal_class(class_name, boundary) {
        let take = namespace_root_segment_count(&segments);
        return segments[..take].join(".");
    }
    let suffix = package_name
        .strip_prefix(boundary)
        .unwrap_or(package_name)
        .trim_start_matches('.');
    if suffix.is_empty() {
        boundary.to_string()
    } else {
        format!("{boundary}.{}", suffix.split('.').next().unwrap_or(suffix))
    }
}

/// Derive the boundary: pick the dominant root namespace, then descend while one child
/// both dominates its parent and clearly separates from its peers.
pub fn derive(primary_classes: &[String]) -> String {
    let packages: Vec<String> = primary_classes
        .iter()
        .filter(|c| !is_synthetic_class(c))
        .filter_map(|c| c.rfind('.').map(|i| c[..i].to_string()))
        .filter(|p| !p.trim().is_empty())
        .collect();
    if packages.is_empty() {
        return DEFAULT_SYSTEM_BOUNDARY.to_string();
    }
    let mut package_weights: IndexMap<String, usize> = IndexMap::new();
    for p in &packages {
        *package_weights.entry(p.clone()).or_insert(0) += 1;
    }
    let mut prefix_weights: IndexMap<String, usize> = IndexMap::new();
    for (package_name, weight) in &package_weights {
        let segments: Vec<&str> = package_name.split('.').filter(|s| !s.is_empty()).collect();
        let root_depth = namespace_root_segment_count(&segments);
        for depth in root_depth..=MAX_PREFIX_DEPTH.min(segments.len()) {
            if depth == 0 {
                continue;
            }
            *prefix_weights.entry(segments[..depth].join(".")).or_insert(0) += weight;
        }
    }
    let mut root_counts: IndexMap<String, usize> = IndexMap::new();
    for p in &packages {
        *root_counts.entry(dominant_namespace(p)).or_insert(0) += 1;
    }
    // Kotlin's maxByOrNull keeps the FIRST maximum in encounter order.
    let root = root_counts
        .iter()
        .fold(None::<(&String, usize)>, |best, (k, v)| match best {
            Some((_, bv)) if bv >= *v => best,
            _ => Some((k, *v)),
        })
        .map(|(k, _)| k.clone())
        .unwrap_or_else(|| DEFAULT_SYSTEM_BOUNDARY.to_string());
    if root == DEFAULT_SYSTEM_BOUNDARY {
        return root;
    }
    let mut boundary = root;
    loop {
        let segments: Vec<&str> = boundary.split('.').filter(|s| !s.is_empty()).collect();
        // Only reverse-DNS namespaces are descended into.
        if !is_reverse_dns_namespace(&segments) {
            break;
        }
        let boundary_weight = match prefix_weights.get(&boundary) {
            Some(w) => *w,
            None => break,
        };
        let next_depth = segments.len() + 1;
        if next_depth > MAX_PREFIX_DEPTH {
            break;
        }
        let mut children: Vec<(&String, usize)> = prefix_weights
            .iter()
            .filter(|(p, _)| {
                p.matches('.').count() + 1 == next_depth && p.starts_with(&format!("{boundary}."))
            })
            .map(|(p, w)| (p, *w))
            .collect();
        children.sort_by(|a, b| b.1.cmp(&a.1));
        let strongest = match children.first() {
            Some(c) => (c.0.clone(), c.1),
            None => break,
        };
        let runner_up = children.get(1).map(|c| c.1).unwrap_or(0);
        let dominance = strongest.1 as f64 / boundary_weight as f64;
        let separates = strongest.1 as i64 >= runner_up as i64 * RUNNER_UP_SEPARATION;
        if dominance >= DOMINANCE_THRESHOLD && separates {
            boundary = strongest.0;
        } else {
            break;
        }
    }
    boundary
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dominant_namespace_uses_reverse_dns_depth() {
        assert_eq!(dominant_namespace("org.apache.lucene"), "org.apache");
        assert_eq!(dominant_namespace("okhttp3.internal.connection"), "okhttp3");
    }

    #[test]
    fn internal_package_unit_keeps_one_segment_below_the_boundary() {
        assert_eq!(
            internal_package_unit("okhttp3.internal.connection.RealConnection", "okhttp3"),
            "okhttp3.internal"
        );
        assert_eq!(internal_package_unit("Foo", "okhttp3"), "(default)");
    }

    #[test]
    fn boundary_descends_into_a_dominant_child() {
        let classes: Vec<String> = ["com.acme.checkout.Api", "com.acme.checkout.api.A",
            "com.acme.checkout.service.S", "com.acme.checkout.repository.R"]
            .iter()
            .map(|s| s.to_string())
            .collect();
        assert_eq!(derive(&classes), "com.acme.checkout");
    }

    #[test]
    fn boundary_stops_at_a_non_reverse_dns_root() {
        let classes: Vec<String> = ["okhttp3.internal.Foo".to_string(), "okhttp3.Bar".to_string()].into();
        assert_eq!(derive(&classes), "okhttp3");
    }

    #[test]
    fn empty_input_falls_back_to_default() {
        assert_eq!(derive(&[]), "(default)");
    }
}

//! HTTP endpoint discovery from Spring Web annotations.

use graphite_storage::{AnyValue, Graph, StrId};
use serde_json::{json, Map, Value as J};

const MAPPING_ANNOTATIONS: [&str; 6] = [
    "org.springframework.web.bind.annotation.RequestMapping",
    "org.springframework.web.bind.annotation.GetMapping",
    "org.springframework.web.bind.annotation.PostMapping",
    "org.springframework.web.bind.annotation.PutMapping",
    "org.springframework.web.bind.annotation.DeleteMapping",
    "org.springframework.web.bind.annotation.PatchMapping",
];

/// Glob matcher supporting `**` (crosses `/`) and `*` (within one segment).
pub fn glob_match(pattern: &str, path: &str) -> bool {
    fn go(p: &[u8], t: &[u8]) -> bool {
        if p.is_empty() {
            return t.is_empty();
        }
        if p[0] == b'*' {
            if p.len() > 1 && p[1] == b'*' {
                // `**` matches any run of characters, separators included.
                let rest = if p.len() > 2 && p[2] == b'/' { &p[3..] } else { &p[2..] };
                for i in 0..=t.len() {
                    if go(rest, &t[i..]) {
                        return true;
                    }
                }
                return false;
            }
            // `*` stops at a separator.
            for i in 0..=t.len() {
                if t[..i].contains(&b'/') {
                    break;
                }
                if go(&p[1..], &t[i..]) {
                    return true;
                }
            }
            return false;
        }
        if p[0] == b'?' {
            return !t.is_empty() && t[0] != b'/' && go(&p[1..], &t[1..]);
        }
        !t.is_empty() && p[0] == t[0] && go(&p[1..], &t[1..])
    }
    go(pattern.as_bytes(), path.as_bytes())
}

fn string_values(g: &Graph, v: Option<&AnyValue>) -> Vec<String> {
    match v {
        Some(AnyValue::Str(s)) => vec![g.str(*s).to_string()],
        Some(AnyValue::List(items)) => items
            .iter()
            .filter_map(|i| match i {
                AnyValue::Str(s) => Some(g.str(*s).to_string()),
                _ => None,
            })
            .collect(),
        _ => vec![],
    }
}

fn attr<'a>(
    g: &Graph,
    attrs: &'a [(StrId, AnyValue)],
    name: &str,
) -> Option<&'a AnyValue> {
    attrs.iter().find(|(k, _)| g.str(*k) == name).map(|(_, v)| v)
}

/// Paths declared by a mapping annotation; `["/"]` when it declares none.
fn extract_paths(g: &Graph, attrs: &[(StrId, AnyValue)]) -> Vec<String> {
    let mut out = string_values(g, attr(g, attrs, "path"));
    out.extend(string_values(g, attr(g, attrs, "value")));
    if out.is_empty() {
        vec!["/".to_string()]
    } else {
        out
    }
}

fn extract_http_methods(g: &Graph, fqn: &str, attrs: &[(StrId, AnyValue)]) -> Vec<String> {
    let simple = fqn.rsplit('.').next().unwrap_or(fqn);
    match simple {
        "GetMapping" => vec!["GET".into()],
        "PostMapping" => vec!["POST".into()],
        "PutMapping" => vec!["PUT".into()],
        "DeleteMapping" => vec!["DELETE".into()],
        "PatchMapping" => vec!["PATCH".into()],
        _ => {
            let m = string_values(g, attr(g, attrs, "method"));
            if m.is_empty() {
                vec!["REQUEST".into()]
            } else {
                m
            }
        }
    }
}

fn normalize_path(base: &str, method: &str) -> String {
    let b = base.trim().trim_matches('/');
    let m = method.trim().trim_matches('/');
    match (b.is_empty(), m.is_empty()) {
        (true, true) => "/".to_string(),
        (true, false) => format!("/{m}"),
        (false, true) => format!("/{b}"),
        (false, false) => format!("/{b}/{m}"),
    }
}

fn combine_paths(bases: &[String], methods: &[String]) -> Vec<String> {
    let default = vec!["/".to_string()];
    let bases = if bases.is_empty() { &default } else { bases };
    let methods = if methods.is_empty() { &default } else { methods };
    let mut out: Vec<String> = Vec::new();
    for b in bases {
        for m in methods {
            let p = normalize_path(b, m);
            if !out.contains(&p) {
                out.push(p);
            }
        }
    }
    out
}

/// Every (method, annotation, path, HTTP verb) combination, sorted by path then verb then signature.
pub fn extract_endpoints(g: &Graph) -> Vec<Map<String, J>> {
    let mut out: Vec<Map<String, J>> = Vec::new();
    // Most graphs carry no Spring annotations at all. Resolving the mapping names once
    // tells us immediately whether any method can be an endpoint, and lets the per-method
    // loop skip the string formatting and dictionary lookup that dominate this scan.
    let mapping_ids: Vec<StrId> = MAPPING_ANNOTATIONS
        .iter()
        .filter_map(|fqn| g.strings.index_of(fqn).map(|i| i as StrId))
        .collect();
    if mapping_ids.is_empty() {
        return out;
    }
    // Keys ("class#member") whose annotations include a mapping annotation.
    let annotated: std::collections::HashSet<StrId> = g
        .metadata
        .member_annotations
        .iter()
        .filter(|(_, anns)| anns.iter().any(|(fqn, _)| mapping_ids.contains(fqn)))
        .map(|(key, _)| *key)
        .collect();
    if annotated.is_empty() {
        return out;
    }
    let mut key_buf = String::new();
    for m in g.methods() {
        let class_name = g.str(m.declaring_class);
        let method_name = g.str(m.name);
        key_buf.clear();
        key_buf.push_str(class_name);
        key_buf.push('#');
        key_buf.push_str(method_name);
        // Skip the lookup entirely unless this member carries a mapping annotation.
        match g.strings.index_of(&key_buf) {
            Some(i) if annotated.contains(&(i as StrId)) => {}
            _ => continue,
        }
        let class_name = class_name.to_string();
        let method_name = method_name.to_string();
        let class_annotations = g.member_annotations(&class_name, "<class>");
        let class_bases: Vec<String> = class_annotations
            .iter()
            .find(|(fqn, _)| g.str(*fqn) == MAPPING_ANNOTATIONS[0])
            .map(|(_, attrs)| extract_paths(g, attrs))
            .unwrap_or_default();
        let member = g.member_annotations(&class_name, &method_name);
        if member.is_empty() {
            continue;
        }
        let mut all_names: Vec<String> = member.iter().map(|(f, _)| g.str(*f).to_string()).collect();
        all_names.sort();
        for (fqn_id, attrs) in member {
            let fqn = g.str(*fqn_id);
            if !MAPPING_ANNOTATIONS.contains(&fqn) {
                continue;
            }
            let method_paths = extract_paths(g, attrs);
            let verbs = extract_http_methods(g, fqn, attrs);
            for path in combine_paths(&class_bases, &method_paths) {
                for verb in &verbs {
                    let mut e = Map::new();
                    e.insert("class".into(), json!(class_name));
                    e.insert("member".into(), json!(method_name));
                    e.insert("signature".into(), json!(m.signature(&g.strings)));
                    e.insert("httpMethod".into(), json!(verb));
                    e.insert("path".into(), json!(path));
                    e.insert("annotation".into(), json!(fqn));
                    e.insert("returns".into(), json!(g.str(m.return_type)));
                    e.insert(
                        "parameters".into(),
                        json!(m
                            .parameter_types
                            .iter()
                            .map(|p| g.str(*p))
                            .collect::<Vec<_>>()),
                    );
                    e.insert("annotations".into(), json!(all_names));
                    out.push(e);
                }
            }
        }
    }
    out.sort_by(|a, b| {
        let k = |m: &Map<String, J>, key: &str| {
            m.get(key).and_then(|v| v.as_str()).unwrap_or("").to_string()
        };
        k(a, "path")
            .cmp(&k(b, "path"))
            .then(k(a, "httpMethod").cmp(&k(b, "httpMethod")))
            .then(k(a, "signature").cmp(&k(b, "signature")))
    });
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn glob_double_star_crosses_separators() {
        assert!(glob_match("**", "a/b/c.txt"));
        assert!(glob_match("BOOT-INF/**", "BOOT-INF/classes/x.properties"));
        assert!(glob_match("META-INF/MANIFEST.MF", "META-INF/MANIFEST.MF"));
        assert!(!glob_match("BOOT-INF/**", "WEB-INF/classes/x"));
    }

    #[test]
    fn glob_single_star_stops_at_separator() {
        assert!(glob_match("*.txt", "a.txt"));
        assert!(!glob_match("*.txt", "a/b.txt"));
        assert!(glob_match("a/*.txt", "a/b.txt"));
    }

    #[test]
    fn paths_are_normalized_and_joined() {
        assert_eq!(normalize_path("", ""), "/");
        assert_eq!(normalize_path("/v1/", "/users/"), "/v1/users");
        assert_eq!(normalize_path("", "users"), "/users");
        assert_eq!(normalize_path("v1", ""), "/v1");
    }

    #[test]
    fn combine_paths_deduplicates() {
        let bases = vec!["/v1".to_string()];
        let methods = vec!["a".to_string(), "a".to_string()];
        assert_eq!(combine_paths(&bases, &methods), vec!["/v1/a"]);
        assert_eq!(combine_paths(&[], &[]), vec!["/"]);
    }
}

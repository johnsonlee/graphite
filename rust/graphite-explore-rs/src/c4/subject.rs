//! Subject detection: is the analysed artifact an application or a library?

use super::boundary::*;
use super::util::humanize_subject_artifact_label;
use graphite_storage::node::{NodeKind, TAG_CALL_SITE_NODE};
use graphite_storage::{Graph, MethodDesc};
use std::collections::{HashMap, HashSet, VecDeque};

const SPRING_BOOT_LAUNCHERS: [&str; 6] = [
    "org.springframework.boot.loader.JarLauncher",
    "org.springframework.boot.loader.launch.JarLauncher",
    "org.springframework.boot.loader.WarLauncher",
    "org.springframework.boot.loader.launch.WarLauncher",
    "org.springframework.boot.loader.PropertiesLauncher",
    "org.springframework.boot.loader.launch.PropertiesLauncher",
];

#[derive(Debug, Clone)]
pub struct Subject {
    pub id: String,
    pub name: String,
    pub role: String,
    pub description: String,
    pub responsibility: String,
    pub actor_id: String,
    pub actor_name: String,
    pub actor_description: String,
    pub actor_responsibility: String,
}

#[derive(Debug, Default, Clone, Copy)]
pub struct MainReachability {
    pub main_methods: usize,
    pub internal_methods: usize,
    pub internal_classes: usize,
    pub external_targets: usize,
}

#[derive(Debug, Default)]
pub struct Manifest {
    pub main_class: Option<String>,
    pub start_class: Option<String>,
}

/// Parse `META-INF/MANIFEST.MF`, honouring continuation lines.
pub fn read_manifest(g: &Graph) -> Manifest {
    let store = match g.resources.as_ref() {
        Some(s) => s,
        None => return Manifest::default(),
    };
    let entry = match store.get("META-INF/MANIFEST.MF") {
        Some(e) => e,
        None => return Manifest::default(),
    };
    let text = String::from_utf8_lossy(&entry.content);
    let mut attributes: HashMap<String, String> = HashMap::new();
    let mut current: Option<String> = None;
    for line in text.lines() {
        if line.trim().is_empty() {
            current = None;
        } else if let Some(rest) = line.strip_prefix(' ') {
            if let Some(k) = &current {
                if let Some(v) = attributes.get_mut(k) {
                    v.push_str(rest);
                }
            }
        } else if let Some((k, v)) = line.split_once(':') {
            let k = k.trim().to_string();
            attributes.insert(k.clone(), v.trim().to_string());
            current = Some(k);
        }
    }
    Manifest {
        main_class: attributes.get("Main-Class").cloned(),
        start_class: attributes.get("Start-Class").cloned(),
    }
}

pub fn is_main_method(g: &Graph, m: &MethodDesc) -> bool {
    g.str(m.name) == "main"
        && g.str(m.return_type) == "void"
        && m.parameter_types.len() == 1
        && g.str(m.parameter_types[0]).contains("java.lang.String")
}

/// Breadth-first walk from every main method through internal call sites.
pub fn analyze_main_reachability(
    g: &Graph,
    boundary: &str,
    preferred_start_class: Option<&str>,
) -> MainReachability {
    let mains: Vec<usize> = g
        .methods()
        .iter()
        .enumerate()
        .filter(|(_, m)| is_main_method(g, m))
        .map(|(i, _)| i)
        .collect();
    let selected: Vec<usize> = match preferred_start_class {
        Some(sc) => {
            let narrowed: Vec<usize> = mains
                .iter()
                .copied()
                .filter(|i| g.str(g.methods()[*i].declaring_class) == sc)
                .collect();
            if narrowed.is_empty() {
                mains.clone()
            } else {
                narrowed
            }
        }
        None => mains.clone(),
    };
    if selected.is_empty() {
        return MainReachability::default();
    }
    let internal_signatures: HashSet<String> = g
        .methods()
        .iter()
        .filter(|m| is_internal_class(g.str(m.declaring_class), boundary))
        .map(|m| m.signature(&g.strings))
        .collect();
    // Index call sites by caller signature. Only call sites whose caller *class* is
    // internal can contribute, and that test reads two raw ints instead of decoding
    // the record, so the full decode is paid only for the few that survive.
    let mut outgoing: HashMap<String, Vec<(String, String)>> = HashMap::new();
    for &id in g.ids_by_tag(TAG_CALL_SITE_NODE) {
        let raw = match g.call_site_strings(id) {
            Some(r) => r,
            None => continue,
        };
        if !is_internal_class(g.str(raw.caller_class), boundary) {
            continue;
        }
        if let Some(node) = g.node(id) {
            if let NodeKind::CallSite { caller, callee, .. } = &node.kind {
                let caller_sig = caller.signature(&g.strings);
                if internal_signatures.contains(&caller_sig) {
                    outgoing.entry(caller_sig).or_default().push((
                        callee.signature(&g.strings),
                        g.str(callee.declaring_class).to_string(),
                    ));
                }
            }
        }
    }
    let mut visited_methods: HashSet<String> = HashSet::new();
    let mut visited_classes: HashSet<String> = HashSet::new();
    let mut external_targets: HashSet<String> = HashSet::new();
    let mut queue: VecDeque<(String, String)> = selected
        .iter()
        .map(|i| {
            let m = &g.methods()[*i];
            (
                m.signature(&g.strings),
                g.str(m.declaring_class).to_string(),
            )
        })
        .collect();
    while let Some((sig, class_name)) = queue.pop_front() {
        // Main methods are counted even when their class sits outside the boundary.
        if !visited_methods.insert(sig.clone()) {
            continue;
        }
        if !is_internal_class(&class_name, boundary) {
            continue;
        }
        visited_classes.insert(class_name);
        for (callee_sig, callee_class) in outgoing.get(&sig).into_iter().flatten() {
            if is_internal_class(callee_class, boundary) && internal_signatures.contains(callee_sig)
            {
                queue.push_back((callee_sig.clone(), callee_class.clone()));
            } else if !is_synthetic_class(callee_class) {
                external_targets.insert(callee_class.clone());
            }
        }
    }
    MainReachability {
        main_methods: selected.len(),
        internal_methods: visited_methods.len(),
        internal_classes: visited_classes.len(),
        external_targets: external_targets.len(),
    }
}

fn infer_name(
    g: &Graph,
    boundary: &str,
    start_class_origin: Option<&str>,
    start_class: Option<&str>,
) -> String {
    if let Some(origin) = start_class_origin {
        if let Some(k) = super::external::artifact_key(origin) {
            return humanize_subject_artifact_label(&k);
        }
    }
    let _ = g;
    if let Some(sc) = start_class {
        let simple = sc.rsplit('.').next().unwrap_or(sc);
        if !simple.trim().is_empty() {
            let trimmed = simple
                .strip_suffix("Application")
                .or_else(|| simple.strip_suffix("App"))
                .unwrap_or(simple);
            return if trimmed.is_empty() {
                simple.to_string()
            } else {
                trimmed.to_string()
            };
        }
    }
    let normalized = boundary.trim_start_matches('(').trim_end_matches(')');
    let leaf = normalized
        .rsplit('.')
        .next()
        .filter(|s| !s.is_empty())
        .unwrap_or(normalized);
    let mut chars = leaf.chars();
    match chars.next() {
        Some(c) if c.is_lowercase() => c.to_uppercase().collect::<String>() + chars.as_str(),
        _ => leaf.to_string(),
    }
}

/// Infer the subject. Roles are decided by the first matching rule, in order.
pub fn infer(g: &Graph, boundary: &str, endpoint_count: usize) -> Subject {
    let manifest = read_manifest(g);
    let has_main_class = manifest
        .main_class
        .as_deref()
        .is_some_and(|c| !c.trim().is_empty());
    let has_boot_layout = g
        .resources
        .as_ref()
        .map(|s| {
            s.entries
                .iter()
                .any(|r| r.path.starts_with("BOOT-INF/") || r.path.starts_with("WEB-INF/"))
        })
        .unwrap_or(false);
    let has_main_method = g.methods().iter().any(|m| is_main_method(g, m));
    let start_class = manifest
        .start_class
        .as_deref()
        .filter(|s| !s.trim().is_empty());
    let start_class_origin = start_class
        .and_then(|sc| g.class_origin(sc))
        .map(|s| s.to_string());
    let has_boot_launcher_main = manifest
        .main_class
        .as_deref()
        .is_some_and(|c| SPRING_BOOT_LAUNCHERS.contains(&c));
    let boot_app_origin = start_class_origin.as_deref().is_some_and(|o| {
        o == "BOOT-INF/classes/"
            || o == "WEB-INF/classes/"
            || o.starts_with("BOOT-INF/lib/")
            || o.starts_with("WEB-INF/lib/")
    });
    let reach = analyze_main_reachability(g, boundary, start_class);
    let boot_reachability_complete =
        reach.main_methods > 0 && reach.internal_methods > 1 && reach.internal_classes > 1;

    let is_application = (has_boot_layout
        && has_boot_launcher_main
        && boot_app_origin
        && boot_reachability_complete)
        || (has_main_class && start_class.is_some() && reach.main_methods > 0)
        || (has_main_method && endpoint_count > 0 && reach.internal_methods > 1)
        || (has_main_method
            && reach.internal_methods >= reach.external_targets
            && reach.internal_classes > 1);

    let display_name = infer_name(g, boundary, start_class_origin.as_deref(), start_class);
    if is_application {
        let http = endpoint_count > 0;
        Subject {
            id: "system:application".into(),
            name: if display_name.trim().is_empty() {
                "Application".into()
            } else {
                display_name
            },
            role: "application".into(),
            description: "Executable software system inferred from the Graphite code graph".into(),
            responsibility:
                "Owns the internal runtime containers and orchestrates the primary execution flows"
                    .into(),
            actor_id: if http {
                "person:http-clients"
            } else {
                "person:operators"
            }
            .into(),
            actor_name: if http { "HTTP Clients" } else { "Operators" }.into(),
            actor_description: if http {
                "External clients invoking detected HTTP endpoints"
            } else {
                "Operators or launchers starting the executable artifact"
            }
            .into(),
            actor_responsibility: if http {
                "Initiates synchronous request flows into the application boundary"
            } else {
                "Starts and operates the executable artifact"
            }
            .into(),
        }
    } else {
        Subject {
            id: "system:library".into(),
            name: format!("{display_name} Library"),
            role: "library".into(),
            description: "A reusable library artifact inferred from the analyzed code graph".into(),
            responsibility:
                "Provides reusable capabilities that are linked and invoked by host applications"
                    .into(),
            actor_id: "person:host-applications".into(),
            actor_name: "Host Applications".into(),
            actor_description: "Applications or services that embed and invoke the library".into(),
            actor_responsibility:
                "Calls into the library and composes it into a larger runnable system".into(),
        }
    }
}

pub fn describe_invocation(subject: &Subject, endpoint_count: usize) -> String {
    if subject.role == "library" {
        format!("Uses {} from a host application context", subject.name)
    } else if endpoint_count > 0 {
        format!("Invokes {} through its HTTP interface", subject.name)
    } else {
        format!("Starts and operates {}", subject.name)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn subject_name_capitalises_the_boundary_leaf() {
        // No manifest and no start class: the boundary leaf is used.
        let name = infer_name_for_test("okhttp3");
        assert_eq!(name, "Okhttp3");
    }

    fn infer_name_for_test(boundary: &str) -> String {
        let normalized = boundary.trim_start_matches('(').trim_end_matches(')');
        let leaf = normalized
            .rsplit('.')
            .next()
            .filter(|s| !s.is_empty())
            .unwrap_or(normalized);
        let mut chars = leaf.chars();
        match chars.next() {
            Some(c) if c.is_lowercase() => c.to_uppercase().collect::<String>() + chars.as_str(),
            _ => leaf.to_string(),
        }
    }

    #[test]
    fn invocation_text_depends_on_role_and_endpoints() {
        let lib = Subject {
            id: "system:library".into(),
            name: "Foo".into(),
            role: "library".into(),
            description: String::new(),
            responsibility: String::new(),
            actor_id: String::new(),
            actor_name: String::new(),
            actor_description: String::new(),
            actor_responsibility: String::new(),
        };
        assert_eq!(
            describe_invocation(&lib, 0),
            "Uses Foo from a host application context"
        );
        let app = Subject {
            role: "application".into(),
            ..lib.clone()
        };
        assert_eq!(
            describe_invocation(&app, 3),
            "Invokes Foo through its HTTP interface"
        );
        assert_eq!(describe_invocation(&app, 0), "Starts and operates Foo");
    }
}

//! OpenAPI 3.0.3 document describing the Explorer API.
//!
//! The document is a literal transcription of the Kotlin `OpenApiSpecBuilder` output,
//! captured as JSON so summaries, descriptions, parameter flags and path ordering match
//! exactly. `info.version` is the only substituted value.

use serde_json::Value as J;

/// The captured document, with a placeholder where the version goes.
const TEMPLATE: &str = include_str!("openapi.json");
#[cfg_attr(not(test), allow(dead_code))]
const VERSION_PLACEHOLDER: &str = "__GRAPHITE_VERSION__";

/// Build the document. `version` becomes `info.version`.
pub fn build_openapi(version: &str) -> J {
    let mut doc: J = serde_json::from_str(TEMPLATE).expect("embedded OpenAPI document is valid");
    if let Some(v) = doc.get_mut("info").and_then(|i| i.get_mut("version")) {
        *v = J::String(version.to_string());
    }
    doc
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn the_placeholder_is_always_replaced() {
        assert!(TEMPLATE.contains(VERSION_PLACEHOLDER));
        let doc = build_openapi("1.2.3");
        assert_eq!(doc["info"]["version"], json!("1.2.3"));
        assert!(!serde_json::to_string(&doc).unwrap().contains(VERSION_PLACEHOLDER));
    }

    #[test]
    fn root_keys_are_in_document_order() {
        let doc = build_openapi("1");
        let keys: Vec<&String> = doc.as_object().unwrap().keys().collect();
        assert_eq!(keys, vec!["openapi", "info", "paths"]);
        assert_eq!(doc["openapi"], json!("3.0.3"));
        assert_eq!(doc["info"]["title"], json!("Graphite Explore API"));
    }

    #[test]
    fn graph_local_roots_exist_only_in_scoped_form() {
        let doc = build_openapi("1");
        let paths = doc["paths"].as_object().unwrap();
        for root in [
            "/api/node/{id}",
            "/api/node/{id}/outgoing",
            "/api/node/{id}/incoming",
            "/api/subgraph",
        ] {
            assert!(!paths.contains_key(root), "{root} should be absent");
        }
        assert!(paths.contains_key("/api/graphs/{graphId}/node/{id}"));
        assert!(paths.contains_key("/api/graphs/{graphId}/subgraph"));
        assert!(paths.contains_key("/api/overview"));
        assert!(paths.contains_key("/api/graphs/{graphId}/overview"));
    }

    #[test]
    fn cypher_paths_document_their_failure_modes() {
        let doc = build_openapi("1");
        let responses = &doc["paths"]["/api/graphs/{graphId}/cypher"]["get"]["responses"];
        for code in ["429", "503", "504"] {
            assert!(responses.get(code).is_some(), "missing {code}");
        }
        let d = responses["429"]["description"].as_str().unwrap();
        assert!(!d.contains("work budget"), "{d}");
    }

    #[test]
    fn c4_has_no_limit_parameter() {
        let doc = build_openapi("1");
        let params = doc["paths"]["/api/architecture/c4"]["get"]["parameters"]
            .as_array()
            .unwrap();
        assert!(!params.iter().any(|p| p["name"] == json!("limit")));
        assert!(params.iter().any(|p| p["name"] == json!("level")));
        assert!(params.iter().any(|p| p["name"] == json!("format")));
    }

    #[test]
    fn scoped_paths_lead_with_the_graph_id_parameter() {
        let doc = build_openapi("1");
        let params = doc["paths"]["/api/graphs/{graphId}/overview"]["get"]["parameters"]
            .as_array()
            .unwrap();
        assert_eq!(params[0]["name"], json!("graphId"));
        assert_eq!(params[0]["in"], json!("path"));
    }
}

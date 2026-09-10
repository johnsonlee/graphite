//! HTTP surface of the Explorer.
//!
//! Two shapes exist for most endpoints. Under `/api/graphs/{graphId}/…` a route serves
//! one graph and returns its payload directly. Under `/api/…` the same route serves
//! every loaded graph and wraps the per-graph payloads in a grouped envelope.

use crate::guard::*;
use crate::helpers::*;
use crate::registry::*;
use axum::body::Body;
use axum::extract::{Path as AxPath, Query, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::Router;
use graphite_cypher::engine::{Executor, QueryResult, Source};
use graphite_cypher::materialize::materialize;
use graphite_cypher::CypherError;
use serde_json::{json, Map, Value as J};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Instant;

pub const DEFAULT_RESOURCE_LIMIT: i64 = 100;
pub const MAX_RESOURCE_LIMIT: i64 = 1000;
pub const DEFAULT_ENDPOINT_LIMIT: i64 = 200;
pub const MAX_ENDPOINT_LIMIT: i64 = 2000;
pub const MAX_RESOURCE_BYTES: usize = 1_048_576;

pub struct AppState {
    pub registry: Arc<GraphRegistry>,
    pub guard: Arc<CypherGuard>,
    pub version: String,
    pub metrics_enabled: bool,
    pub started: Instant,
    /// Built C4 workspaces, keyed by graph identity and level. Inference walks the
    /// whole graph, and a loaded graph never changes, so the result is worth keeping.
    /// Replacing a graph produces a new `Arc`, which misses and re-infers.
    c4_cache: parking_lot::Mutex<HashMap<(usize, String), Arc<J>>>,
}

impl AppState {
    pub fn new(
        registry: Arc<GraphRegistry>,
        guard: Arc<CypherGuard>,
        version: String,
        metrics_enabled: bool,
    ) -> AppState {
        AppState {
            registry,
            guard,
            version,
            metrics_enabled,
            started: Instant::now(),
            c4_cache: parking_lot::Mutex::new(HashMap::new()),
        }
    }

    fn c4_model(&self, lease: &GraphLease, level: &str) -> Arc<J> {
        if graphite_cypher::engine::optimizations_disabled() {
            return Arc::new(crate::c4::build_model(&lease.graph, level));
        }
        let key = (Arc::as_ptr(&lease.graph) as usize, level.to_string());
        if let Some(hit) = self.c4_cache.lock().get(&key).cloned() {
            return hit;
        }
        let model = Arc::new(crate::c4::build_model(&lease.graph, level));
        self.c4_cache.lock().insert(key, model.clone());
        model
    }
}

pub type St = State<Arc<AppState>>;
type Params = HashMap<String, String>;

// ---------------------------------------------------------------------------
// Responses
// ---------------------------------------------------------------------------

/// Pretty-printed JSON, matching Gson's `setPrettyPrinting()`.
fn pretty(value: &J) -> String {
    serde_json::to_string_pretty(value).unwrap_or_else(|_| "null".into())
}

fn json_response(status: StatusCode, value: J) -> Response {
    (
        status,
        [(header::CONTENT_TYPE, "application/json")],
        pretty(&value),
    )
        .into_response()
}

fn ok_json(value: J) -> Response {
    json_response(StatusCode::OK, value)
}

fn text(status: StatusCode, body: &str) -> Response {
    (
        status,
        [(header::CONTENT_TYPE, "text/plain")],
        body.to_string(),
    )
        .into_response()
}

fn error_json(status: StatusCode, message: &str) -> Response {
    json_response(status, json!({ "error": message }))
}

fn grouped(graph_id: &str, data: J) -> J {
    json!({ "graphId": graph_id, "data": data })
}

fn grouped_envelope(selected: usize, results: Vec<J>) -> J {
    json!({
        "graphCount": results.len(),
        "resultGraphCount": selected,
        "results": results,
    })
}

// ---------------------------------------------------------------------------
// Router
// ---------------------------------------------------------------------------

pub fn router(state: Arc<AppState>) -> Router {
    let mut app = Router::new()
        // registry
        .route("/api/graphs", get(list_graphs))
        .route(
            "/api/graphs/{graphId}",
            get(describe_graph).put(load_graph).post(load_graph).delete(unload_graph),
        )
        .route("/api/topology", get(topology))
        // all-graph (grouped) routes
        .route("/api/annotations", get(all_annotations))
        .route("/api/resources", get(all_resources))
        .route("/api/resources/{*path}", get(all_resource_content))
        .route("/api/endpoints", get(all_endpoints))
        .route("/api/architecture/c4", get(all_c4))
        .route("/api/overview", get(all_overview))
        .route("/api/cypher", get(cypher_all).post(cypher_all))
        .route("/api/cypher/graphs", get(cypher_graphs).post(cypher_graphs))
        // graph-scoped routes
        .route("/api/graphs/{graphId}/annotations", get(graph_annotations))
        .route("/api/graphs/{graphId}/resources", get(graph_resources))
        .route(
            "/api/graphs/{graphId}/resources/{*path}",
            get(graph_resource_content),
        )
        .route("/api/graphs/{graphId}/endpoints", get(graph_endpoints))
        .route("/api/graphs/{graphId}/architecture/c4", get(graph_c4))
        .route("/api/graphs/{graphId}/overview", get(graph_overview))
        .route("/api/graphs/{graphId}/cypher", get(cypher_one).post(cypher_one))
        .route("/api/graphs/{graphId}/node/{id}", get(node))
        .route("/api/graphs/{graphId}/node/{id}/outgoing", get(node_outgoing))
        .route("/api/graphs/{graphId}/node/{id}/incoming", get(node_incoming))
        .route("/api/graphs/{graphId}/subgraph", get(subgraph))
        // spec + static
        .route("/openapi.json", get(openapi))
        .route("/swagger.json", get(openapi))
        .route("/", get(index))
        .route("/index.html", get(index))
        .route("/app.js", get(app_js))
        .route("/ui-state.js", get(ui_state_js))
        .route("/style.css", get(style_css));
    if state.metrics_enabled {
        app = app.route("/metrics", get(metrics));
    }
    app.with_state(state)
}

// ---------------------------------------------------------------------------
// Registry routes
// ---------------------------------------------------------------------------

async fn list_graphs(State(s): St) -> Response {
    let graphs: Vec<J> = s.registry.list().iter().map(|g| g.to_api_map()).collect();
    ok_json(json!({
        "data": s.registry.data_dir().display().to_string(),
        "loadMode": s.registry.default_mode().name(),
        "count": graphs.len(),
        "totals": s.registry.totals().to_api_map(),
        "graphs": graphs,
    }))
}

async fn describe_graph(State(s): St, AxPath(id): AxPath<String>) -> Response {
    match s.registry.describe(&id) {
        Ok(Some(g)) => ok_json(json!({ "graph": g.to_api_map() })),
        Ok(None) => error_json(StatusCode::NOT_FOUND, &format!("Graph not loaded: {id}")),
        Err(e) => error_json(StatusCode::BAD_REQUEST, &e),
    }
}

async fn load_graph(
    State(s): St,
    AxPath(id): AxPath<String>,
    Query(q): Query<Params>,
    body: String,
) -> Response {
    let parsed: Option<J> = if body.trim().is_empty() {
        None
    } else {
        serde_json::from_str(&body).ok()
    };
    let field = |name: &str| -> Option<String> {
        parsed
            .as_ref()
            .and_then(|j| j.get(name))
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
            .or_else(|| q.get(name).cloned())
    };
    let path = match field("path") {
        Some(p) if !p.trim().is_empty() => p,
        _ => return error_json(StatusCode::BAD_REQUEST, "Missing 'path' field"),
    };
    let mode = match field("loadMode") {
        Some(m) => match LoadMode::parse(&m) {
            Ok(m) => Some(m),
            Err(e) => return error_json(StatusCode::BAD_REQUEST, &e),
        },
        None => None,
    };
    match s.registry.load(&id, std::path::Path::new(&path), mode) {
        Ok(g) => ok_json(json!({ "graph": g.to_api_map() })),
        Err(e) => error_json(StatusCode::BAD_REQUEST, &e),
    }
}

async fn unload_graph(State(s): St, AxPath(id): AxPath<String>) -> Response {
    match s.registry.unload(&id) {
        Ok(true) => StatusCode::NO_CONTENT.into_response(),
        Ok(false) => error_json(StatusCode::NOT_FOUND, &format!("Graph not loaded: {id}")),
        Err(e) => error_json(StatusCode::BAD_REQUEST, &e),
    }
}

async fn topology(State(s): St) -> Response {
    let leases = s.registry.acquire_all();
    let nodes: Vec<J> = leases
        .iter()
        .map(|l| {
            json!({
                "id": l.id,
                "graphId": l.id,
                "type": "Graph",
                "label": l.id,
                "nodes": l.stats.nodes,
                "edges": l.stats.edges,
                "methods": l.stats.methods,
                "callSites": l.stats.call_sites,
            })
        })
        .collect();
    ok_json(json!({
        "nodes": nodes,
        "edges": [],
        "graphCount": leases.len(),
        "relationCount": 0,
        "matchedRows": 0,
        "builtAt": now_iso8601(),
        "rules": [],
        "stale": false,
    }))
}

// ---------------------------------------------------------------------------
// Graph-scoped lookups
// ---------------------------------------------------------------------------

/// Resolve `{graphId}` to a lease, or produce the matching error response.
fn lease(s: &AppState, id: &str) -> Result<GraphLease, Response> {
    match s.registry.acquire(id) {
        Ok(Some(l)) => Ok(l),
        Ok(None) => Err(error_json(
            StatusCode::NOT_FOUND,
            &format!("Graph not loaded: {id}"),
        )),
        Err(e) => Err(error_json(StatusCode::BAD_REQUEST, &e)),
    }
}

async fn node(State(s): St, AxPath((gid, id)): AxPath<(String, String)>) -> Response {
    let l = match lease(&s, &gid) {
        Ok(l) => l,
        Err(r) => return r,
    };
    let node_id: u32 = match id.parse() {
        Ok(v) => v,
        Err(_) => return text(StatusCode::BAD_REQUEST, "Invalid node ID"),
    };
    match l.graph.node(node_id) {
        Some(n) => ok_json(node_to_map(&l.graph, &n)),
        None => text(StatusCode::NOT_FOUND, "Node not found"),
    }
}

async fn node_outgoing(
    State(s): St,
    AxPath((gid, id)): AxPath<(String, String)>,
    Query(q): Query<Params>,
) -> Response {
    node_edges(&s, &gid, &id, &q, true)
}

async fn node_incoming(
    State(s): St,
    AxPath((gid, id)): AxPath<(String, String)>,
    Query(q): Query<Params>,
) -> Response {
    node_edges(&s, &gid, &id, &q, false)
}

fn node_edges(s: &AppState, gid: &str, id: &str, q: &Params, outgoing: bool) -> Response {
    let l = match lease(s, gid) {
        Ok(l) => l,
        Err(r) => return r,
    };
    let node_id: u32 = match id.parse() {
        Ok(v) => v,
        Err(_) => return text(StatusCode::BAD_REQUEST, "Invalid node ID"),
    };
    let limit = bounded_limit(q.get("limit").map(|s| s.as_str()), DEFAULT_EDGE_LIMIT, MAX_EDGE_LIMIT)
        .max(0) as usize;
    let edges: Vec<J> = if outgoing {
        l.graph.outgoing(node_id).take(limit).map(|e| edge_to_map(&e)).collect()
    } else {
        l.graph.incoming(node_id).take(limit).map(|e| edge_to_map(&e)).collect()
    };
    ok_json(J::Array(edges))
}

async fn subgraph(State(s): St, AxPath(gid): AxPath<String>, Query(q): Query<Params>) -> Response {
    let l = match lease(&s, &gid) {
        Ok(l) => l,
        Err(r) => return r,
    };
    let center: u32 = match q.get("center").and_then(|c| c.parse().ok()) {
        Some(c) => c,
        None => return text(StatusCode::BAD_REQUEST, "Missing 'center' parameter"),
    };
    let depth = q
        .get("depth")
        .and_then(|d| d.parse::<i64>().ok())
        .unwrap_or(DEFAULT_SUBGRAPH_DEPTH)
        .min(MAX_SUBGRAPH_DEPTH);
    let direction = match Direction::parse(q.get("direction").map(|s| s.as_str())) {
        Some(d) => d,
        None => return text(StatusCode::BAD_REQUEST, "Invalid 'direction' parameter"),
    };
    ok_json(build_subgraph(&l.graph, center, depth, direction))
}

async fn graph_overview(
    State(s): St,
    AxPath(gid): AxPath<String>,
    Query(q): Query<Params>,
) -> Response {
    let l = match lease(&s, &gid) {
        Ok(l) => l,
        Err(r) => return r,
    };
    let limit = bounded_limit(
        q.get("limit").map(|s| s.as_str()),
        DEFAULT_OVERVIEW_LIMIT,
        MAX_OVERVIEW_LIMIT,
    );
    ok_json(build_class_overview(&l.graph, limit))
}

async fn all_overview(State(s): St, Query(q): Query<Params>) -> Response {
    let leases = s.registry.acquire_all();
    let total = bounded_limit(
        q.get("limit").map(|s| s.as_str()),
        DEFAULT_OVERVIEW_LIMIT,
        MAX_OVERVIEW_LIMIT,
    );
    let limits = distributed_limits(leases.len(), total);
    let results: Vec<J> = leases
        .iter()
        .zip(limits)
        .map(|(l, lim)| grouped(&l.id, build_class_overview(&l.graph, lim)))
        .collect();
    ok_json(grouped_envelope(leases.len(), results))
}

async fn graph_annotations(
    State(s): St,
    AxPath(gid): AxPath<String>,
    Query(q): Query<Params>,
) -> Response {
    let l = match lease(&s, &gid) {
        Ok(l) => l,
        Err(r) => return r,
    };
    match annotations_payload(&l, &q) {
        Ok(v) => ok_json(v),
        Err(r) => r,
    }
}

async fn all_annotations(State(s): St, Query(q): Query<Params>) -> Response {
    let leases = s.registry.acquire_all();
    let mut results = Vec::with_capacity(leases.len());
    for l in &leases {
        match annotations_payload(l, &q) {
            Ok(v) => results.push(grouped(&l.id, v)),
            Err(r) => return r,
        }
    }
    ok_json(grouped_envelope(leases.len(), results))
}

fn annotations_payload(l: &GraphLease, q: &Params) -> Result<J, Response> {
    let class = match q.get("class") {
        Some(c) => c,
        None => return Err(text(StatusCode::BAD_REQUEST, "Missing 'class' parameter")),
    };
    let member = match q.get("member") {
        Some(m) => m,
        None => return Err(text(StatusCode::BAD_REQUEST, "Missing 'member' parameter")),
    };
    let mut out = Map::new();
    for (fqn, attrs) in l.graph.member_annotations(class, member) {
        let mut inner = Map::new();
        for (k, v) in attrs {
            inner.insert(
                l.graph.str(*k).to_string(),
                crate::c4::any_value_json(&l.graph, v),
            );
        }
        out.insert(l.graph.str(*fqn).to_string(), J::Object(inner));
    }
    Ok(J::Object(out))
}

async fn graph_endpoints(
    State(s): St,
    AxPath(gid): AxPath<String>,
    Query(q): Query<Params>,
) -> Response {
    let l = match lease(&s, &gid) {
        Ok(l) => l,
        Err(r) => return r,
    };
    let limit = bounded_limit(
        q.get("limit").map(|s| s.as_str()),
        DEFAULT_ENDPOINT_LIMIT,
        MAX_ENDPOINT_LIMIT,
    );
    ok_json(endpoints_payload(&l, limit, q.get("class").map(|s| s.as_str())))
}

async fn all_endpoints(State(s): St, Query(q): Query<Params>) -> Response {
    let leases = s.registry.acquire_all();
    let total = bounded_limit(
        q.get("limit").map(|s| s.as_str()),
        DEFAULT_ENDPOINT_LIMIT,
        MAX_ENDPOINT_LIMIT,
    );
    let limits = distributed_limits(leases.len(), total);
    let class = q.get("class").map(|s| s.as_str());
    let results: Vec<J> = leases
        .iter()
        .zip(limits)
        .map(|(l, lim)| grouped(&l.id, endpoints_payload(l, lim, class)))
        .collect();
    ok_json(grouped_envelope(leases.len(), results))
}

fn endpoints_payload(l: &GraphLease, limit: i64, class: Option<&str>) -> J {
    let all = crate::endpoints::extract_endpoints(&l.graph);
    let filtered: Vec<J> = all
        .into_iter()
        .filter(|e| match class {
            Some(c) => e.get("class").and_then(|v| v.as_str()) == Some(c),
            None => true,
        })
        .take(limit.max(0) as usize)
        .map(J::Object)
        .collect();
    json!({
        "framework": "spring-web",
        "count": filtered.len(),
        "endpoints": filtered,
    })
}

async fn graph_resources(
    State(s): St,
    AxPath(gid): AxPath<String>,
    Query(q): Query<Params>,
) -> Response {
    let l = match lease(&s, &gid) {
        Ok(l) => l,
        Err(r) => return r,
    };
    match resources_payload(&l, &q, None) {
        Ok(v) => ok_json(v),
        Err(r) => r,
    }
}

async fn all_resources(State(s): St, Query(q): Query<Params>) -> Response {
    let leases = s.registry.acquire_all();
    // Store availability is checked for every graph before any payload is produced.
    let mut unavailable = Vec::new();
    for l in &leases {
        if l.graph.resources.is_none() {
            unavailable.push(json!({ "graphId": l.id, "error": RESOURCE_STORE_MISSING }));
        }
    }
    if !unavailable.is_empty() {
        return json_response(
            StatusCode::CONFLICT,
            json!({
                "error": "Persisted resources are unavailable for one or more graphs",
                "results": unavailable,
            }),
        );
    }
    let total = bounded_limit(
        q.get("limit").map(|s| s.as_str()),
        DEFAULT_RESOURCE_LIMIT,
        MAX_RESOURCE_LIMIT,
    );
    let limits = distributed_limits(leases.len(), total);
    let mut results = Vec::with_capacity(leases.len());
    for (l, lim) in leases.iter().zip(limits) {
        match resources_payload(l, &q, Some(lim)) {
            Ok(v) => results.push(grouped(&l.id, v)),
            Err(r) => return r,
        }
    }
    ok_json(grouped_envelope(leases.len(), results))
}

const RESOURCE_STORE_MISSING: &str =
    "Persisted resources are unavailable because graph.resources is missing; rebuild this graph with the current Graphite CLI";

fn resources_payload(l: &GraphLease, q: &Params, limit_override: Option<i64>) -> Result<J, Response> {
    let store = match l.graph.resources.as_ref() {
        Some(r) => r,
        None => return Err(error_json(StatusCode::CONFLICT, RESOURCE_STORE_MISSING)),
    };
    let pattern = q.get("pattern").map(|s| s.as_str()).unwrap_or("**");
    let limit = limit_override.unwrap_or_else(|| {
        bounded_limit(
            q.get("limit").map(|s| s.as_str()),
            DEFAULT_RESOURCE_LIMIT,
            MAX_RESOURCE_LIMIT,
        )
    });
    let matched: Vec<J> = store
        .entries
        .iter()
        .filter(|r| crate::endpoints::glob_match(pattern, &r.path))
        .take(limit.max(0) as usize)
        .map(|r| json!({ "path": r.path, "source": r.source, "derived": false }))
        .collect();
    Ok(json!({
        "pattern": pattern,
        "limit": limit,
        "count": matched.len(),
        "resources": matched,
    }))
}

async fn graph_resource_content(
    State(s): St,
    AxPath((gid, path)): AxPath<(String, String)>,
) -> Response {
    let l = match lease(&s, &gid) {
        Ok(l) => l,
        Err(r) => return r,
    };
    let path = path.trim_start_matches('/').to_string();
    if path.is_empty() {
        return text(StatusCode::NOT_FOUND, "Resource not found");
    }
    let store = match l.graph.resources.as_ref() {
        Some(r) => r,
        None => return error_json(StatusCode::CONFLICT, RESOURCE_STORE_MISSING),
    };
    match store.get(&path) {
        Some(r) if r.content.len() > MAX_RESOURCE_BYTES => text(
            StatusCode::PAYLOAD_TOO_LARGE,
            &format!("Resource exceeds maximum response size: {path}"),
        ),
        Some(r) => ok_json(json!({
            "path": r.path,
            "source": r.source,
            "derived": false,
            "size": r.content.len(),
            "content": String::from_utf8_lossy(&r.content),
        })),
        None => text(
            StatusCode::NOT_FOUND,
            &format!("Resource not found: {path}"),
        ),
    }
}

async fn all_resource_content(State(s): St, AxPath(path): AxPath<String>) -> Response {
    let leases = s.registry.acquire_all();
    let path = path.trim_start_matches('/').to_string();
    if path.is_empty() {
        return text(StatusCode::NOT_FOUND, "Resource not found");
    }
    let mut remaining: i64 = MAX_RESOURCE_BYTES as i64;
    let mut results = Vec::new();
    for l in &leases {
        let store = match l.graph.resources.as_ref() {
            Some(r) => r,
            None => continue,
        };
        if let Some(r) = store.get(&path) {
            if r.content.len() as i64 > remaining {
                return text(
                    StatusCode::PAYLOAD_TOO_LARGE,
                    &format!("Resource exceeds maximum response size: {path}"),
                );
            }
            remaining -= r.content.len() as i64;
            results.push(grouped(
                &l.id,
                json!({
                    "path": r.path,
                    "source": r.source,
                    "derived": false,
                    "size": r.content.len(),
                    "content": String::from_utf8_lossy(&r.content),
                }),
            ));
        }
    }
    if results.is_empty() {
        return text(
            StatusCode::NOT_FOUND,
            &format!("Resource not found: {path}"),
        );
    }
    ok_json(grouped_envelope(leases.len(), results))
}

// ---------------------------------------------------------------------------
// C4
// ---------------------------------------------------------------------------

const C4_LEVELS: [&str; 4] = ["context", "container", "component", "all"];
const C4_FORMATS: [&str; 4] = ["json", "mermaid", "plantuml", "dsl"];

/// `Accept` wins over `?format=`.
fn resolve_c4_format(accept: Option<&str>, query_format: Option<&str>) -> String {
    for raw in accept.unwrap_or("").split(',') {
        let m = raw.split(';').next().unwrap_or("").trim().to_ascii_lowercase();
        if m.is_empty() || m == "*/*" {
            continue;
        }
        match m.as_str() {
            "text/vnd.plantuml" | "text/x-plantuml" | "application/vnd.plantuml" => {
                return "plantuml".into()
            }
            "text/vnd.mermaid" | "text/x-mermaid" => return "mermaid".into(),
            "text/vnd.structurizr.dsl" | "text/x-structurizr" | "application/vnd.structurizr.dsl" => {
                return "dsl".into()
            }
            "application/vnd.structurizr+json" | "application/json" => return "json".into(),
            _ => {}
        }
    }
    match query_format {
        Some(f) if !f.trim().is_empty() => f.to_ascii_lowercase(),
        _ => "json".into(),
    }
}

fn c4_params(headers: &HeaderMap, q: &Params) -> Result<(String, String), Response> {
    let level = q.get("level").map(|s| s.as_str()).unwrap_or("all").to_string();
    if !C4_LEVELS.contains(&level.as_str()) {
        return Err(json_response(
            StatusCode::BAD_REQUEST,
            json!({ "error": "Invalid 'level' parameter", "allowed": C4_LEVELS }),
        ));
    }
    let accept = headers.get(header::ACCEPT).and_then(|v| v.to_str().ok());
    let format = resolve_c4_format(accept, q.get("format").map(|s| s.as_str()));
    if !C4_FORMATS.contains(&format.as_str()) {
        return Err(json_response(
            StatusCode::BAD_REQUEST,
            json!({ "error": "Invalid 'format' parameter", "allowed": C4_FORMATS }),
        ));
    }
    Ok((level, format))
}

fn c4_content_type(format: &str) -> &'static str {
    match format {
        "mermaid" => "text/vnd.mermaid; charset=utf-8",
        "plantuml" => "text/vnd.plantuml; charset=utf-8",
        "dsl" => "text/vnd.structurizr.dsl; charset=utf-8",
        _ => "application/vnd.structurizr+json; charset=utf-8",
    }
}

async fn graph_c4(
    State(s): St,
    AxPath(gid): AxPath<String>,
    Query(q): Query<Params>,
    headers: HeaderMap,
) -> Response {
    let l = match lease(&s, &gid) {
        Ok(l) => l,
        Err(r) => return r,
    };
    let (level, format) = match c4_params(&headers, &q) {
        Ok(v) => v,
        Err(r) => return r,
    };
    let workspace = s.c4_model(&l, &level);
    render_c4(&workspace, &format)
}

fn render_c4(workspace: &J, format: &str) -> Response {
    let body = match format {
        "mermaid" => crate::c4::render_mermaid(workspace),
        "plantuml" => crate::c4::render_plantuml(workspace),
        "dsl" => crate::c4::render_dsl(workspace),
        _ => pretty(workspace),
    };
    (
        StatusCode::OK,
        [(header::CONTENT_TYPE, c4_content_type(format))],
        body,
    )
        .into_response()
}

async fn all_c4(State(s): St, Query(q): Query<Params>, headers: HeaderMap) -> Response {
    let (level, format) = match c4_params(&headers, &q) {
        Ok(v) => v,
        Err(r) => return r,
    };
    let leases = s.registry.acquire_all();
    let models: Vec<(String, Arc<J>)> = leases
        .iter()
        .map(|l| (l.id.clone(), s.c4_model(l, &level)))
        .collect();
    if format == "json" {
        let results: Vec<J> = models
            .iter()
            .map(|(id, m)| grouped(id, (**m).clone()))
            .collect();
        return (
            StatusCode::OK,
            [(header::CONTENT_TYPE, "application/json; charset=utf-8")],
            pretty(&grouped_envelope(leases.len(), results)),
        )
            .into_response();
    }
    let (prefix, render): (&str, fn(&J) -> String) = match format.as_str() {
        "mermaid" => ("%% graphId: ", crate::c4::render_mermaid),
        "plantuml" => ("' graphId: ", crate::c4::render_plantuml),
        _ => ("// graphId: ", crate::c4::render_dsl),
    };
    let body = models
        .iter()
        .map(|(id, m)| format!("{prefix}{id}\n{}", render(m)))
        .collect::<Vec<_>>()
        .join("\n\n");
    (
        StatusCode::OK,
        [(header::CONTENT_TYPE, c4_content_type(&format))],
        body,
    )
        .into_response()
}

// ---------------------------------------------------------------------------
// Cypher
// ---------------------------------------------------------------------------

/// POST prefers a JSON body field, falling back to the query parameter.
fn read_query(body: &str, q: &Params) -> Option<String> {
    if !body.trim().is_empty() {
        if let Ok(J::Object(o)) = serde_json::from_str::<J>(body) {
            if let Some(s) = o.get("query").and_then(|v| v.as_str()) {
                return Some(s.to_string());
            }
        }
    }
    q.get("query").cloned()
}

fn read_timeout(body: &str, q: &Params) -> Result<Option<u64>, Response> {
    const BAD: &str = "'timeoutMs' must be a positive integer";
    let raw: Option<String> = if !body.trim().is_empty() {
        match serde_json::from_str::<J>(body) {
            Ok(J::Object(o)) => match o.get("timeoutMs") {
                Some(J::Null) | None => q.get("timeoutMs").cloned(),
                Some(J::Number(n)) => Some(n.to_string()),
                Some(J::String(s)) => Some(s.clone()),
                Some(_) => return Err(error_json(StatusCode::BAD_REQUEST, BAD)),
            },
            _ => q.get("timeoutMs").cloned(),
        }
    } else {
        q.get("timeoutMs").cloned()
    };
    match raw {
        None => Ok(None),
        Some(s) => match s.parse::<i64>() {
            Ok(v) if v > 0 => Ok(Some(v as u64)),
            _ => Err(error_json(StatusCode::BAD_REQUEST, BAD)),
        },
    }
}

fn cypher_error_response(e: &CypherError, timeout_millis: u64) -> Response {
    let (status, code) = match e {
        CypherError::Timeout(_) => (StatusCode::GATEWAY_TIMEOUT, "cypher_query_timeout"),
        CypherError::Cancelled => (StatusCode::SERVICE_UNAVAILABLE, "cypher_query_cancelled"),
        CypherError::BudgetExceeded(_) => {
            (StatusCode::TOO_MANY_REQUESTS, "cypher_work_budget_exceeded")
        }
        _ => (StatusCode::BAD_REQUEST, "cypher_query_failed"),
    };
    let mut body = Map::new();
    body.insert("error".into(), J::String(e.to_string()));
    body.insert("code".into(), J::String(code.into()));
    if matches!(e, CypherError::Timeout(_)) {
        body.insert("timeoutMs".into(), json!(timeout_millis));
    }
    json_response(status, J::Object(body))
}

/// Render a result set as `{columns, rows, rowCount}`.
fn result_json(r: &QueryResult, ex: &Executor, extra: Vec<(&str, J)>) -> J {
    let rows: Vec<J> = r
        .rows
        .iter()
        .map(|row| {
            let mut obj = Map::new();
            for c in &r.columns {
                obj.insert(
                    c.clone(),
                    row.get(c).map(|v| materialize(v, ex)).unwrap_or(J::Null),
                );
            }
            if ex.cross {
                let ids = QueryResult::graph_ids(row);
                obj.insert("$metadata".into(), json!({ "graphIds": ids }));
            }
            J::Object(obj)
        })
        .collect();
    let mut out = Map::new();
    out.insert("columns".into(), json!(r.columns));
    out.insert("rows".into(), J::Array(rows));
    out.insert("rowCount".into(), json!(r.rows.len()));
    for (k, v) in extra {
        out.insert(k.to_string(), v);
    }
    J::Object(out)
}

async fn cypher_one(
    State(s): St,
    AxPath(gid): AxPath<String>,
    Query(q): Query<Params>,
    body: String,
) -> Response {
    let l = match s.registry.acquire(&gid) {
        Ok(Some(l)) => l,
        Ok(None) => return error_json(StatusCode::NOT_FOUND, "Graph not loaded"),
        Err(e) => return error_json(StatusCode::BAD_REQUEST, &e),
    };
    let query = match read_query(&body, &q) {
        Some(query) => query,
        None => return text(StatusCode::BAD_REQUEST, "Missing 'query' parameter"),
    };
    let timeout = match read_timeout(&body, &q) {
        Ok(t) => t,
        Err(r) => return r,
    };
    let limit = bounded_row_limit(q.get("limit").map(|s| s.as_str()));
    run_cypher(s, vec![l], false, query, limit, timeout, vec![]).await
}

async fn cypher_all(State(s): St, Query(q): Query<Params>, body: String) -> Response {
    let query = match read_query(&body, &q) {
        Some(query) if !query.trim().is_empty() => query.trim().to_string(),
        _ => return text(StatusCode::BAD_REQUEST, "Missing 'query' parameter"),
    };
    let timeout = match read_timeout(&body, &q) {
        Ok(t) => t,
        Err(r) => return r,
    };
    let leases = s.registry.acquire_all();
    let count = leases.len();
    let limit = bounded_row_limit(q.get("limit").map(|s| s.as_str()));
    run_cypher(
        s,
        leases,
        true,
        query,
        limit,
        timeout,
        vec![("graphCount", json!(count))],
    )
    .await
}

async fn cypher_graphs(State(s): St, Query(q): Query<Params>, body: String) -> Response {
    let parsed: Option<J> = serde_json::from_str(&body).ok();
    let obj = parsed.as_ref().and_then(|j| j.as_object());
    let query = match read_query(&body, &q) {
        Some(query) if !query.trim().is_empty() => query.trim().to_string(),
        _ => return cypher_request_error("Missing 'query' parameter"),
    };
    let all_graphs = obj
        .and_then(|o| o.get("allGraphs"))
        .map(json_truthy)
        .unwrap_or_else(|| q.get("allGraphs").map(|s| truthy(s)).unwrap_or(false));
    let mut ids: Vec<String> = Vec::new();
    if let Some(o) = obj {
        match o.get("graphs") {
            Some(J::Array(a)) => {
                for v in a {
                    match v.as_str() {
                        Some(s) => ids.push(s.to_string()),
                        None => return cypher_request_error("Invalid 'graphs' field"),
                    }
                }
            }
            Some(J::String(s)) => ids.extend(split_ids(s)),
            Some(_) => return cypher_request_error("Invalid 'graphs' field"),
            None => {}
        }
        if let Some(J::String(s)) = o.get("graph") {
            ids.push(s.clone());
        }
    }
    if ids.is_empty() {
        for key in ["graph", "graphs"] {
            if let Some(raw) = q.get(key) {
                ids.extend(split_ids(raw));
            }
        }
    }
    let mut dedup = std::collections::HashSet::new();
    if !ids.iter().all(|i| dedup.insert(i.clone())) {
        return cypher_request_error("Graph ids must be unique");
    }
    if all_graphs == !ids.is_empty() {
        return cypher_request_error(
            "Specify exactly one of 'allGraphs=true' or a non-empty 'graphs' list",
        );
    }
    let leases = if all_graphs {
        s.registry.acquire_all()
    } else {
        match s.registry.acquire_ids(&ids) {
            Ok(l) => l,
            Err(GraphAcquireError::NotLoaded(id)) => {
                return error_json(StatusCode::NOT_FOUND, &format!("Graph not loaded: {id}"))
            }
            Err(GraphAcquireError::Invalid(e)) => return cypher_request_error(&e),
        }
    };
    let mode = obj
        .and_then(|o| o.get("mode"))
        .and_then(|v| v.as_str())
        .or_else(|| q.get("mode").map(|s| s.as_str()))
        .unwrap_or("cross-graph")
        .to_string();
    let fanout = match mode.as_str() {
        "" | "cross-graph" | "cross_graph" | "crossgraph" => false,
        "fanout" | "fan-out" | "fan_out" => true,
        other => {
            return cypher_request_error(&format!(
                "Invalid query mode '{other}'. Expected 'cross-graph' or 'fanout'"
            ))
        }
    };
    let timeout = match read_timeout(&body, &q) {
        Ok(t) => t,
        Err(r) => return r,
    };
    let limit = bounded_row_limit(
        obj.and_then(|o| o.get("limit"))
            .map(|v| v.to_string())
            .as_deref()
            .or(q.get("limit").map(|s| s.as_str())),
    );
    let graph_ids: Vec<String> = leases.iter().map(|l| l.id.clone()).collect();
    if !fanout {
        if obj.and_then(|o| o.get("perGraphLimit")).is_some() || q.contains_key("perGraphLimit") {
            return cypher_request_error("perGraphLimit is only valid in fanout mode");
        }
        if obj.and_then(|o| o.get("includeGraphRows")).is_some()
            || q.contains_key("includeGraphRows")
        {
            return cypher_request_error("includeGraphRows is only valid in fanout mode");
        }
        let count = leases.len();
        return run_cypher(
            s,
            leases,
            true,
            query,
            limit,
            timeout,
            vec![
                ("mode", json!("cross-graph")),
                ("graphs", json!(graph_ids)),
                ("graphCount", json!(count)),
                ("limit", json!(limit)),
            ],
        )
        .await;
    }
    let per_graph = obj
        .and_then(|o| o.get("perGraphLimit"))
        .and_then(|v| v.as_i64())
        .or_else(|| q.get("perGraphLimit").and_then(|s| s.parse().ok()))
        .unwrap_or_else(|| default_per_graph_limit(leases.len(), limit))
        .clamp(0, MAX_CYPHER_ROW_LIMIT);
    let include_rows = obj
        .and_then(|o| o.get("includeGraphRows"))
        .map(json_truthy)
        .unwrap_or_else(|| q.get("includeGraphRows").map(|s| truthy(s)).unwrap_or(false));
    run_fanout(s, leases, query, limit, per_graph, include_rows, timeout).await
}

fn cypher_request_error(message: &str) -> Response {
    json_response(
        StatusCode::BAD_REQUEST,
        json!({ "error": message, "code": "cypher_query_failed" }),
    )
}

fn truthy(v: &str) -> bool {
    matches!(v.trim().to_ascii_lowercase().as_str(), "true" | "1" | "yes" | "on")
}

fn json_truthy(v: &J) -> bool {
    match v {
        J::Bool(b) => *b,
        J::String(s) => truthy(s),
        J::Number(n) => n.as_i64() == Some(1),
        _ => false,
    }
}

fn split_ids(raw: &str) -> Vec<String> {
    raw.split(',')
        .map(|s| s.trim())
        .filter(|s| !s.is_empty())
        .map(|s| s.to_string())
        .collect()
}

fn default_per_graph_limit(graph_count: usize, limit: i64) -> i64 {
    if graph_count == 0 {
        return limit;
    }
    (limit + graph_count as i64 - 1) / graph_count as i64
}

/// Run a query on the blocking pool under a guard permit.
async fn run_cypher(
    s: Arc<AppState>,
    leases: Vec<GraphLease>,
    cross: bool,
    query: String,
    limit: i64,
    timeout: Option<u64>,
    extra: Vec<(&'static str, J)>,
) -> Response {
    let permit = match s.guard.try_acquire(timeout) {
        Ok(p) => p,
        Err(limit) => {
            return (
                StatusCode::TOO_MANY_REQUESTS,
                [
                    (header::CONTENT_TYPE, "application/json"),
                    (header::RETRY_AFTER, "1"),
                ],
                pretty(&json!({
                    "error": limit.message(),
                    "code": "cypher_concurrency_limit",
                })),
            )
                .into_response();
        }
    };
    let timeout_millis = permit.timeout_millis;
    let guard = s.guard.clone();
    let started = Instant::now();
    let result = tokio::task::spawn_blocking(move || {
        let sources: Vec<Source> = leases
            .into_iter()
            .map(|l| Source {
                id: Arc::from(l.id.as_str()),
                graph: l.graph,
            })
            .collect();
        let ex = Executor::new(sources, cross).with_cancel(permit.cancel.clone());
        let out = ex.execute(&query, Some(limit.max(0) as usize));
        (out, ex)
    })
    .await;
    let elapsed = started.elapsed().as_nanos() as u64;
    match result {
        Ok((Ok(r), ex)) => {
            guard.finish(Outcome::Success, elapsed);
            ok_json(result_json(&r, &ex, extra))
        }
        Ok((Err(e), _)) => {
            guard.finish(Outcome::of(Some(&e)), elapsed);
            cypher_error_response(&e, timeout_millis)
        }
        Err(_) => {
            guard.finish(Outcome::Failed, elapsed);
            error_json(StatusCode::INTERNAL_SERVER_ERROR, "Query execution failed")
        }
    }
}

/// Fanout: run the query once per graph and concatenate, injecting a `graphId` column.
async fn run_fanout(
    s: Arc<AppState>,
    leases: Vec<GraphLease>,
    query: String,
    limit: i64,
    per_graph: i64,
    include_rows: bool,
    timeout: Option<u64>,
) -> Response {
    let permit = match s.guard.try_acquire(timeout) {
        Ok(p) => p,
        Err(l) => {
            return (
                StatusCode::TOO_MANY_REQUESTS,
                [
                    (header::CONTENT_TYPE, "application/json"),
                    (header::RETRY_AFTER, "1"),
                ],
                pretty(&json!({ "error": l.message(), "code": "cypher_concurrency_limit" })),
            )
                .into_response();
        }
    };
    let timeout_millis = permit.timeout_millis;
    let guard = s.guard.clone();
    let graph_count = leases.len();
    let started = Instant::now();
    let outcome = tokio::task::spawn_blocking(move || {
        let mut remaining = limit;
        let mut truncated = false;
        let mut per_graph_out: Vec<J> = Vec::new();
        let mut all_rows: Vec<J> = Vec::new();
        let mut columns: Vec<String> = vec!["graphId".to_string()];
        let mut queried = 0usize;
        for l in leases {
            if remaining <= 0 {
                truncated = true;
                break;
            }
            let ex = Executor::single(l.id.as_str(), l.graph.clone())
                .with_cancel(permit.cancel.clone());
            let take = per_graph.min(remaining).max(0) as usize;
            let r = ex.execute(&query, Some(take))?;
            queried += 1;
            for c in &r.columns {
                if !columns.contains(c) {
                    columns.push(c.clone());
                }
            }
            let rows: Vec<J> = r
                .rows
                .iter()
                .map(|row| {
                    // `graphId` is always the first column and always the group's id.
                    let mut obj = Map::new();
                    obj.insert("graphId".into(), J::String(l.id.clone()));
                    for c in &r.columns {
                        obj.insert(
                            c.clone(),
                            row.get(c).map(|v| materialize(v, &ex)).unwrap_or(J::Null),
                        );
                    }
                    obj.insert("graphId".into(), J::String(l.id.clone()));
                    J::Object(obj)
                })
                .collect();
            remaining -= rows.len() as i64;
            truncated = truncated || remaining <= 0;
            let mut entry = Map::new();
            entry.insert("graphId".into(), J::String(l.id.clone()));
            entry.insert("columns".into(), json!(r.columns));
            entry.insert("rowCount".into(), json!(rows.len()));
            if include_rows {
                entry.insert("rows".into(), J::Array(rows.clone()));
            }
            per_graph_out.push(J::Object(entry));
            all_rows.extend(rows);
        }
        Ok::<_, CypherError>(json!({
            "columns": columns,
            "rows": all_rows,
            "rowCount": all_rows.len(),
            "graphCount": graph_count,
            "queriedGraphCount": queried,
            "perGraphLimit": per_graph,
            "limit": limit,
            "truncated": truncated,
            "graphs": per_graph_out,
            "mode": "fanout",
        }))
    })
    .await;
    let elapsed = started.elapsed().as_nanos() as u64;
    match outcome {
        Ok(Ok(body)) => {
            guard.finish(Outcome::Success, elapsed);
            ok_json(body)
        }
        Ok(Err(e)) => {
            guard.finish(Outcome::of(Some(&e)), elapsed);
            cypher_error_response(&e, timeout_millis)
        }
        Err(_) => {
            guard.finish(Outcome::Failed, elapsed);
            error_json(StatusCode::INTERNAL_SERVER_ERROR, "Query execution failed")
        }
    }
}

// ---------------------------------------------------------------------------
// Spec, metrics, static assets
// ---------------------------------------------------------------------------

async fn openapi(State(s): St) -> Response {
    ok_json(crate::openapi::build_openapi(&s.version))
}

async fn metrics(State(s): St) -> Response {
    let m = &s.guard.metrics;
    let mut out = String::new();
    out.push_str("# HELP graphite_cypher_queries_rejected_total Cypher queries rejected by the concurrency guard\n");
    out.push_str("# TYPE graphite_cypher_queries_rejected_total counter\n");
    out.push_str(&format!(
        "graphite_cypher_queries_rejected_total {}\n",
        m.rejected.load(std::sync::atomic::Ordering::Relaxed)
    ));
    out.push_str("# HELP graphite_cypher_queries_active Accepted Cypher queries that have not completed\n");
    out.push_str("# TYPE graphite_cypher_queries_active gauge\n");
    out.push_str(&format!(
        "graphite_cypher_queries_active {}\n",
        m.active.load(std::sync::atomic::Ordering::Relaxed)
    ));
    out.push_str("# HELP graphite_cypher_queries_limit Maximum concurrent Cypher queries\n");
    out.push_str("# TYPE graphite_cypher_queries_limit gauge\n");
    out.push_str(&format!(
        "graphite_cypher_queries_limit {}\n",
        s.guard.max_concurrent
    ));
    out.push_str("# HELP graphite_cypher_query_duration_seconds Cypher query execution time\n");
    out.push_str("# TYPE graphite_cypher_query_duration_seconds summary\n");
    for (outcome, count, nanos) in m.snapshot() {
        out.push_str(&format!(
            "graphite_cypher_query_duration_seconds_count{{outcome=\"{}\"}} {}\n",
            outcome.tag(),
            count
        ));
        out.push_str(&format!(
            "graphite_cypher_query_duration_seconds_sum{{outcome=\"{}\"}} {}\n",
            outcome.tag(),
            nanos as f64 / 1e9
        ));
    }
    out.push_str("# HELP process_uptime_seconds Process uptime\n");
    out.push_str("# TYPE process_uptime_seconds gauge\n");
    out.push_str(&format!(
        "process_uptime_seconds {}\n",
        s.started.elapsed().as_secs_f64()
    ));
    (
        StatusCode::OK,
        [(header::CONTENT_TYPE, "text/plain; version=0.0.4; charset=utf-8")],
        out,
    )
        .into_response()
}

/// The web UI is compiled into the binary so the server is a single file.
const INDEX_HTML: &str = include_str!("../web/index.html");
const APP_JS: &str = include_str!("../web/app.js");
const UI_STATE_JS: &str = include_str!("../web/ui-state.js");
const STYLE_CSS: &str = include_str!("../web/style.css");

/// A weak validator for an embedded asset, derived from its bytes.
///
/// Ktor serves these from the jar with an ETag and `Cache-Control: max-age=0`, so a
/// browser revalidates on every load and gets a 304 rather than the body. Without one
/// the same reload re-downloads all four assets, about 58 KB. The value is opaque, so
/// it need not — and cannot — match Ktor's; only the revalidation behaviour matters.
fn asset_etag(body: &'static str) -> String {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};
    let mut h = DefaultHasher::new();
    body.hash(&mut h);
    format!("W/\"{:x}-{:x}\"", body.len(), h.finish())
}

fn asset(headers: &HeaderMap, content_type: &'static str, body: &'static str) -> Response {
    let etag = asset_etag(body);
    // `If-None-Match` may carry a list; a match on any entry means unchanged.
    let unchanged = headers
        .get(header::IF_NONE_MATCH)
        .and_then(|v| v.to_str().ok())
        .is_some_and(|v| v.split(',').any(|c| c.trim() == etag));
    let builder = Response::builder()
        // No charset: Ktor sends a bare `text/html`, `text/javascript`, `text/css`.
        .header(header::CONTENT_TYPE, content_type)
        .header(header::CACHE_CONTROL, "max-age=0")
        .header(header::ETAG, &etag);
    if unchanged {
        return builder
            .status(StatusCode::NOT_MODIFIED)
            .body(Body::empty())
            .unwrap();
    }
    builder
        .status(StatusCode::OK)
        .body(Body::from(body))
        .unwrap()
}

async fn index(headers: HeaderMap) -> Response {
    asset(&headers, "text/html", INDEX_HTML)
}
async fn app_js(headers: HeaderMap) -> Response {
    asset(&headers, "text/javascript", APP_JS)
}
async fn ui_state_js(headers: HeaderMap) -> Response {
    asset(&headers, "text/javascript", UI_STATE_JS)
}
async fn style_css(headers: HeaderMap) -> Response {
    asset(&headers, "text/css", STYLE_CSS)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accept_header_beats_the_format_parameter() {
        assert_eq!(resolve_c4_format(Some("text/vnd.mermaid"), Some("json")), "mermaid");
        assert_eq!(resolve_c4_format(Some("*/*"), Some("dsl")), "dsl");
        assert_eq!(resolve_c4_format(None, None), "json");
        assert_eq!(resolve_c4_format(Some("text/html"), None), "json");
        assert_eq!(
            resolve_c4_format(Some("application/json;q=0.9"), Some("dsl")),
            "json"
        );
    }

    #[test]
    fn truthy_matches_kotlin_parse_boolean() {
        for v in ["true", "TRUE", " 1 ", "yes", "on"] {
            assert!(truthy(v), "{v}");
        }
        for v in ["false", "0", "no", "", "maybe"] {
            assert!(!truthy(v), "{v}");
        }
    }

    #[test]
    fn per_graph_limit_rounds_up() {
        assert_eq!(default_per_graph_limit(21, 1000), 48);
        assert_eq!(default_per_graph_limit(4, 1000), 250);
        assert_eq!(default_per_graph_limit(0, 1000), 1000);
    }

    #[test]
    fn query_is_read_from_body_then_query_string() {
        let mut q = Params::new();
        q.insert("query".into(), "RETURN 2".into());
        assert_eq!(read_query(r#"{"query":"RETURN 1"}"#, &q).unwrap(), "RETURN 1");
        assert_eq!(read_query("", &q).unwrap(), "RETURN 2");
        assert_eq!(read_query("not json", &q).unwrap(), "RETURN 2");
        assert!(read_query("", &Params::new()).is_none());
    }

    #[test]
    fn timeout_must_be_a_positive_integer() {
        let q = Params::new();
        assert_eq!(read_timeout("", &q).ok().unwrap(), None);
        assert_eq!(read_timeout(r#"{"timeoutMs":25}"#, &q).ok().unwrap(), Some(25));
        assert!(read_timeout(r#"{"timeoutMs":0}"#, &q).is_err());
        assert!(read_timeout(r#"{"timeoutMs":"x"}"#, &q).is_err());
        assert!(read_timeout(r#"{"timeoutMs":true}"#, &q).is_err());
    }

    #[test]
    fn split_ids_trims_and_drops_blanks() {
        assert_eq!(split_ids("a, b ,,c"), vec!["a", "b", "c"]);
    }
}

//! The Model Context Protocol server: the Explorer API as MCP tools.
//!
//! Thirteen tools, each a thin mapping onto one HTTP route of the API in `routes.rs`
//! (the same routes the web UI and `curl` use). A tool call is turned into an HTTP
//! request and dispatched to the router in-process, so it goes through the same
//! handlers, the same Cypher guard and the same error text as a network request, with
//! no socket in between. Two transports carry the JSON-RPC messages:
//!
//! - stdio (`graphite mcp`): one JSON-RPC message per line on stdin, one per line on
//!   stdout, logs on stderr. This is what Claude Desktop, Cursor and the other local
//!   clients speak. Requests are handled concurrently: stdin keeps being read while a
//!   long Cypher call runs, so a `ping` or a cancellation sent meanwhile is answered at
//!   once; only stdout writes are serialized, one message per line.
//! - Streamable HTTP (`POST /mcp` on `graphite serve`): one JSON-RPC message per
//!   request, the response as JSON. The server never opens a stream of its own, so
//!   `GET /mcp` answers 405 as the protocol allows for that case. Every `/mcp` request
//!   passes the [`OriginPolicy`] first (DNS-rebinding protection), and a request that
//!   names an unsupported revision in `MCP-Protocol-Version` is refused with 400.
//!
//! The tools, their names, descriptions, argument schemas and defaults are those of
//! the former `graphite-mcp` npm package, so an existing client configuration only
//! changes its `command`.

use crate::metrics::McpMetrics;
use crate::serve::{open, report_loaded, runtime, warn_if_debug_build, GraphArgs, VERSION};
use axum::body::{to_bytes, Body, Bytes};
use axum::extract::Extension;
use axum::http::{header, HeaderMap, Method, Request, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::post;
use axum::Router;
use serde_json::{json, Map, Value};
use std::sync::Arc;
use std::time::Instant;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt};
use tower::ServiceExt;

/// The protocol revision answered when the client asks for one this server does not
/// know: the latest one supported, as the specification asks. Every revision listed in
/// [`SUPPORTED_PROTOCOLS`] is echoed back as requested; the list carries every revision
/// the retired npm package's SDK negotiated, so no existing client is turned away.
pub const PROTOCOL_VERSION: &str = "2025-11-25";
pub const SUPPORTED_PROTOCOLS: &[&str] = &["2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"];
/// The header a Streamable HTTP client sends after initialization to say which revision
/// it negotiated. Absent, the request is treated as `2025-03-26` per the specification;
/// present and unsupported, it is refused with 400.
pub const PROTOCOL_VERSION_HEADER: &str = "mcp-protocol-version";
pub const SERVER_NAME: &str = "graphite";

// JSON-RPC 2.0 error codes.
const PARSE_ERROR: i64 = -32700;
const INVALID_REQUEST: i64 = -32600;
const METHOD_NOT_FOUND: i64 = -32601;
const INVALID_PARAMS: i64 = -32602;

/// One tool as advertised by `tools/list`.
pub struct ToolDef {
    pub name: &'static str,
    pub description: &'static str,
    pub input_schema: Value,
}

fn schema(properties: Value, required: &[&str]) -> Value {
    let mut s = Map::new();
    s.insert("type".into(), json!("object"));
    s.insert("properties".into(), properties);
    if !required.is_empty() {
        s.insert("required".into(), json!(required));
    }
    s.insert("additionalProperties".into(), json!(false));
    s.insert(
        "$schema".into(),
        json!("http://json-schema.org/draft-07/schema#"),
    );
    Value::Object(s)
}

fn graph_id_property(description: &str) -> Value {
    json!({"type": "string", "description": description})
}

/// The tools, in the order the npm package registered them.
pub fn tools() -> Vec<ToolDef> {
    vec![
        ToolDef {
            name: "graphs",
            description: "List all graphs with aggregate statistics, or get one graph by id",
            input_schema: schema(
                json!({"graph_id": graph_id_property("Explicit graph id; omit to list all graphs and totals")}),
                &[],
            ),
        },
        ToolDef {
            name: "openapi",
            description: "Fetch the machine-readable OpenAPI document for the explore server",
            input_schema: schema(json!({}), &[]),
        },
        ToolDef {
            name: "cypher",
            description: "Execute a true cross-graph Cypher query across all graphs by default, one explicit graph with graph_id, or an explicit graph set with graphs/all_graphs. Call schema first to learn the labels, property keys, relationship types and patterns a graph holds, rather than discovering them with exploratory queries",
            input_schema: schema(
                json!({
                    "query": {"type": "string", "description": "Cypher query string"},
                    "graph_id": graph_id_property("Explicit single graph id; mutually exclusive with graphs and all_graphs"),
                    "all_graphs": {"type": "boolean", "default": false, "description": "Explicitly select all loaded graphs for /api/cypher/graphs"},
                    "graphs": {"type": "array", "items": {"type": "string"}, "description": "Explicit graph ids for /api/cypher/graphs"},
                    "mode": {"type": "string", "enum": ["cross-graph", "fanout"], "default": "cross-graph", "description": "One union query across graphs, or independent per-graph fan-out"},
                    "limit": {"type": "number", "description": "Maximum total result rows for the request"},
                    "timeout_ms": {"type": "integer", "exclusiveMinimum": 0, "description": "Client timeout in milliseconds, capped by the server maximum (60 seconds by default)"},
                    "per_graph_limit": {"type": "number", "description": "Optional maximum result rows per graph for multi-graph queries"},
                    "include_graph_rows": {"type": "boolean", "default": false, "description": "Include duplicate per-graph row arrays in multi-graph responses"}
                }),
                &["query"],
            ),
        },
        ToolDef {
            name: "node",
            description: "Get a node by its graph-local ID in one graph",
            input_schema: schema(
                json!({
                    "id": {"type": "number", "description": "Graph-local node ID"},
                    "graph_id": graph_id_property("The graph the node ID belongs to (node IDs are local to a graph)")
                }),
                &["id", "graph_id"],
            ),
        },
        ToolDef {
            name: "outgoing",
            description: "Get outgoing edges for a graph-local node ID in one graph",
            input_schema: schema(
                json!({
                    "id": {"type": "number", "description": "Graph-local node ID"},
                    "graph_id": graph_id_property("The graph the node ID belongs to (node IDs are local to a graph)")
                }),
                &["id", "graph_id"],
            ),
        },
        ToolDef {
            name: "incoming",
            description: "Get incoming edges for a graph-local node ID in one graph",
            input_schema: schema(
                json!({
                    "id": {"type": "number", "description": "Graph-local node ID"},
                    "graph_id": graph_id_property("The graph the node ID belongs to (node IDs are local to a graph)")
                }),
                &["id", "graph_id"],
            ),
        },
        ToolDef {
            name: "annotations",
            description: "Get annotations for a class member across all graphs, grouped by graph, or in one explicit graph",
            input_schema: schema(
                json!({
                    "graph_id": graph_id_property("Explicit graph id; omit to query all graphs"),
                    "class_name": {"type": "string", "description": "Fully qualified class name"},
                    "member_name": {"type": "string", "description": "Method or field name"}
                }),
                &["class_name", "member_name"],
            ),
        },
        ToolDef {
            name: "endpoints",
            description: "Extract framework API endpoints across all graphs, grouped by graph, or from one explicit graph",
            input_schema: schema(
                json!({
                    "graph_id": graph_id_property("Explicit graph id; omit to query all graphs"),
                    "class_name": {"type": "string", "description": "Optional controller class filter"},
                    "limit": {"type": "number", "default": 200, "description": "Max endpoints to return"}
                }),
                &[],
            ),
        },
        ToolDef {
            name: "resources",
            description: "List persisted resources across all graphs, grouped by graph, or in one explicit graph",
            input_schema: schema(
                json!({
                    "graph_id": graph_id_property("Explicit graph id; omit to query all graphs"),
                    "pattern": {"type": "string", "description": "Glob pattern filter, defaults to **"},
                    "limit": {"type": "number", "default": 100, "description": "Max results"}
                }),
                &[],
            ),
        },
        ToolDef {
            name: "resource",
            description: "Read every matching resource path grouped by graph, or read it from one explicit graph",
            input_schema: schema(
                json!({
                    "graph_id": graph_id_property("Explicit graph id; omit to query every graph"),
                    "path": {"type": "string", "description": "Resource path inside the saved graph"}
                }),
                &["path"],
            ),
        },
        ToolDef {
            name: "subgraph",
            description: "Get subgraphs for a graph-local center ID across all graphs, or from one explicit graph",
            input_schema: schema(
                json!({
                    "graph_id": graph_id_property("Explicit graph id; omit to query every graph"),
                    "center": {"type": "number", "description": "Center node ID"},
                    "depth": {"type": "number", "default": 2, "description": "Traversal depth"}
                }),
                &["center"],
            ),
        },
        ToolDef {
            name: "overview",
            description: "Get class-level dependency overviews across all graphs, grouped by graph, or for one explicit graph",
            input_schema: schema(
                json!({
                    "graph_id": graph_id_property("Explicit graph id; omit to query all graphs"),
                    "limit": {"type": "number", "default": 200, "description": "Max classes"}
                }),
                &[],
            ),
        },
        ToolDef {
            name: "c4",
            description: "Get C4 views for all graphs, grouped by graph for JSON, or for one explicit graph",
            input_schema: schema(
                json!({
                    "graph_id": graph_id_property("Explicit graph id; omit to query all graphs"),
                    "level": {"type": "string", "enum": ["context", "container", "component", "all"], "default": "all", "description": "C4 view level"},
                    "format": {"type": "string", "enum": ["json", "dsl", "mermaid", "plantuml"], "default": "json", "description": "Output format"},
                    "limit": {"type": "number", "default": 200, "description": "Max containers or components"}
                }),
                &[],
            ),
        },
        ToolDef {
            name: "schema",
            description: "Describe what a graph holds before writing Cypher against it: every label set with its node count and property keys, every relationship type with its count, and the most frequent (labels)-[type]->(labels) patterns, for one graph with graph_id or for every loaded graph, grouped by graphId. Answered per type in milliseconds; prefer it to MATCH (n) RETURN labels(n), keys(n) ... exploration",
            input_schema: schema(
                json!({
                    "graph_id": graph_id_property("Explicit graph id; omit to describe all graphs"),
                    "limit": {"type": "integer", "minimum": 0, "maximum": 1000, "default": 50, "description": "Maximum number of (labels)-[type]->(labels) patterns per graph, most frequent first"}
                }),
                &[],
            ),
        },
    ]
}

/// The API request a tool call turns into. Built separately from sending it so the
/// mapping is testable without a server.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ApiCall {
    pub method: Method,
    pub path: String,
    pub params: Vec<(String, String)>,
    pub body: Option<Value>,
    /// The response is returned as text rather than re-serialised as pretty JSON.
    pub text: bool,
}

impl ApiCall {
    fn get(path: String) -> Self {
        ApiCall {
            method: Method::GET,
            path,
            params: Vec::new(),
            body: None,
            text: false,
        }
    }

    fn post(path: String, body: Value) -> Self {
        ApiCall {
            method: Method::POST,
            path,
            params: Vec::new(),
            body: Some(body),
            text: false,
        }
    }

    /// Add a query parameter unless the value is empty, as the npm package's
    /// `if (v) url.searchParams.set(k, v)` did.
    fn param(mut self, key: &str, value: String) -> Self {
        if !value.is_empty() {
            self.params.push((key.to_string(), value));
        }
        self
    }

    /// The path with the query string, encoded as `URLSearchParams` encodes.
    pub fn uri(&self) -> String {
        if self.params.is_empty() {
            return self.path.clone();
        }
        let query: Vec<String> = self
            .params
            .iter()
            .map(|(k, v)| format!("{}={}", form_encode(k), form_encode(v)))
            .collect();
        format!("{}?{}", self.path, query.join("&"))
    }
}

/// `encodeURIComponent`: everything but `A-Z a-z 0-9 - _ . ! ~ * ' ( )` is percent-encoded.
pub fn encode_uri_component(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for b in text.bytes() {
        match b {
            b'A'..=b'Z'
            | b'a'..=b'z'
            | b'0'..=b'9'
            | b'-'
            | b'_'
            | b'.'
            | b'!'
            | b'~'
            | b'*'
            | b'\''
            | b'('
            | b')' => out.push(b as char),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

/// `URLSearchParams` encoding: space becomes `+`, `* - . _` and alphanumerics stay.
pub fn form_encode(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for b in text.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'*' | b'-' | b'.' | b'_' => {
                out.push(b as char)
            }
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

/// `encodeResourcePath`: each `/`-separated part encoded, empty parts dropped.
fn encode_resource_path(path: &str) -> String {
    path.split('/')
        .filter(|p| !p.is_empty())
        .map(encode_uri_component)
        .collect::<Vec<_>>()
        .join("/")
}

/// `/api/graphs/<id><suffix>` for one graph, `/api<suffix>` for all.
fn graph_api_path(graph_id: Option<&str>, path: &str) -> String {
    let suffix = if path.starts_with('/') {
        path.to_string()
    } else {
        format!("/{path}")
    };
    match graph_id {
        Some(id) => format!("/api/graphs/{}{}", encode_uri_component(id), suffix),
        None => format!("/api{suffix}"),
    }
}

/// A JSON number rendered as JavaScript's `String(n)` renders it: integers without a
/// fraction, others in their shortest form.
fn number_text(value: &Value) -> String {
    match value {
        Value::Number(n) => {
            if let Some(i) = n.as_i64() {
                i.to_string()
            } else if let Some(u) = n.as_u64() {
                u.to_string()
            } else {
                n.as_f64().map(|f| f.to_string()).unwrap_or_default()
            }
        }
        other => other.to_string(),
    }
}

struct ToolArgs<'a>(&'a Map<String, Value>);

impl ToolArgs<'_> {
    fn present(&self, key: &str) -> Option<&Value> {
        self.0.get(key).filter(|v| !v.is_null())
    }

    fn string(&self, key: &str) -> Result<Option<String>, String> {
        match self.present(key) {
            None => Ok(None),
            Some(Value::String(s)) => Ok(Some(s.clone())),
            Some(_) => Err(format!("{key} must be a string")),
        }
    }

    fn required_string(&self, key: &str) -> Result<String, String> {
        self.string(key)?
            .ok_or_else(|| format!("{key} is required"))
    }

    fn number(&self, key: &str) -> Result<Option<Value>, String> {
        match self.present(key) {
            None => Ok(None),
            Some(v @ Value::Number(_)) => Ok(Some(v.clone())),
            Some(_) => Err(format!("{key} must be a number")),
        }
    }

    fn required_number(&self, key: &str) -> Result<Value, String> {
        self.number(key)?
            .ok_or_else(|| format!("{key} is required"))
    }

    /// An integer argument within `min..=max`, or `default` when absent. A fraction
    /// or a value out of range is refused rather than silently replaced: the route
    /// parses the parameter as an integer and would fall back to its default.
    fn integer_in(&self, key: &str, min: i64, max: i64, default: i64) -> Result<String, String> {
        let value = match self.present(key) {
            None => return Ok(default.to_string()),
            Some(Value::Number(n)) => n
                .as_i64()
                .or_else(|| n.as_f64().filter(|f| f.fract() == 0.0).map(|f| f as i64)),
            Some(_) => None,
        };
        match value {
            Some(i) if (min..=max).contains(&i) => Ok(i.to_string()),
            _ => Err(format!("{key} must be an integer between {min} and {max}")),
        }
    }

    fn positive_integer(&self, key: &str) -> Result<Option<i64>, String> {
        match self.present(key) {
            None => Ok(None),
            Some(Value::Number(n)) => match n.as_i64() {
                Some(i) if i > 0 => Ok(Some(i)),
                _ => Err(format!("{key} must be a positive integer")),
            },
            Some(_) => Err(format!("{key} must be a positive integer")),
        }
    }

    fn bool(&self, key: &str) -> Result<bool, String> {
        match self.present(key) {
            None => Ok(false),
            Some(Value::Bool(b)) => Ok(*b),
            Some(_) => Err(format!("{key} must be a boolean")),
        }
    }

    fn string_list(&self, key: &str) -> Result<Option<Vec<String>>, String> {
        match self.present(key) {
            None => Ok(None),
            Some(Value::Array(items)) => items
                .iter()
                .map(|v| {
                    v.as_str()
                        .map(str::to_string)
                        .ok_or_else(|| format!("{key} must be an array of strings"))
                })
                .collect::<Result<Vec<_>, _>>()
                .map(Some),
            Some(_) => Err(format!("{key} must be an array of strings")),
        }
    }

    fn one_of(&self, key: &str, allowed: &[&str], default: &str) -> Result<String, String> {
        match self.string(key)? {
            None => Ok(default.to_string()),
            Some(v) if allowed.contains(&v.as_str()) => Ok(v),
            Some(v) => Err(format!(
                "{key} must be one of {}; got '{v}'",
                allowed.join(", ")
            )),
        }
    }

    /// A numeric argument with the npm package's default, rendered for a URL.
    fn number_or(&self, key: &str, default: i64) -> Result<String, String> {
        Ok(self
            .number(key)?
            .map(|v| number_text(&v))
            .unwrap_or_else(|| default.to_string()))
    }
}

/// Is the rendered number truthy in JavaScript terms (`if (limit)`)?
fn truthy_number(text: &str) -> bool {
    text != "0" && text != "-0" && text != "NaN"
}

/// Plan the API call for `tool` with `args`. Errors are the messages the npm package
/// threw for the same input, where it validated at all.
pub fn plan(tool: &str, args: &Map<String, Value>) -> Result<ApiCall, String> {
    let a = ToolArgs(args);
    match tool {
        "graphs" => Ok(ApiCall::get(match a.string("graph_id")? {
            Some(id) => format!("/api/graphs/{}", encode_uri_component(&id)),
            None => "/api/graphs".to_string(),
        })),
        "openapi" => Ok(ApiCall::get("/openapi.json".to_string())),
        "cypher" => plan_cypher(&a),
        "node" | "outgoing" | "incoming" => {
            let id = number_text(&a.required_number("id")?);
            // Node IDs are local to a graph and the API has no all-graph node route (the
            // Kotlin server has none either), so the graph is required here rather than
            // advertised as optional and answered with a 404.
            let graph_id = a.required_string("graph_id")?;
            let suffix = match tool {
                "node" => format!("/node/{id}"),
                "outgoing" => format!("/node/{id}/outgoing"),
                _ => format!("/node/{id}/incoming"),
            };
            Ok(ApiCall::get(graph_api_path(Some(&graph_id), &suffix)))
        }
        "annotations" => {
            let graph_id = a.string("graph_id")?;
            let class_name = a.required_string("class_name")?;
            let member_name = a.required_string("member_name")?;
            Ok(
                ApiCall::get(graph_api_path(graph_id.as_deref(), "/annotations"))
                    .param("class", class_name)
                    .param("member", member_name),
            )
        }
        "endpoints" => {
            let graph_id = a.string("graph_id")?;
            let mut call = ApiCall::get(graph_api_path(graph_id.as_deref(), "/endpoints"));
            if let Some(class_name) = a.string("class_name")? {
                call = call.param("class", class_name);
            }
            let limit = a.number_or("limit", 200)?;
            if truthy_number(&limit) {
                call = call.param("limit", limit);
            }
            Ok(call)
        }
        "resources" => {
            let graph_id = a.string("graph_id")?;
            let mut call = ApiCall::get(graph_api_path(graph_id.as_deref(), "/resources"));
            if let Some(pattern) = a.string("pattern")? {
                call = call.param("pattern", pattern);
            }
            let limit = a.number_or("limit", 100)?;
            if truthy_number(&limit) {
                call = call.param("limit", limit);
            }
            Ok(call)
        }
        "resource" => {
            let graph_id = a.string("graph_id")?;
            let path = a.required_string("path")?;
            Ok(ApiCall::get(graph_api_path(
                graph_id.as_deref(),
                &format!("/resources/{}", encode_resource_path(&path)),
            )))
        }
        "subgraph" => {
            let graph_id = a.string("graph_id")?;
            let center = number_text(&a.required_number("center")?);
            let depth = a.number_or("depth", 2)?;
            Ok(
                ApiCall::get(graph_api_path(graph_id.as_deref(), "/subgraph"))
                    .param("center", center)
                    .param("depth", depth),
            )
        }
        "overview" => {
            let graph_id = a.string("graph_id")?;
            let limit = a.number_or("limit", 200)?;
            Ok(
                ApiCall::get(graph_api_path(graph_id.as_deref(), "/overview"))
                    .param("limit", limit),
            )
        }
        "c4" => {
            let graph_id = a.string("graph_id")?;
            let level = a.one_of(
                "level",
                &["context", "container", "component", "all"],
                "all",
            )?;
            let format = a.one_of("format", &["json", "dsl", "mermaid", "plantuml"], "json")?;
            let limit = a.number_or("limit", 200)?;
            let mut call = ApiCall::get(graph_api_path(graph_id.as_deref(), "/architecture/c4"))
                .param("level", level)
                .param("format", format.clone())
                .param("limit", limit);
            call.text = format != "json";
            Ok(call)
        }
        "schema" => {
            let graph_id = a.string("graph_id")?;
            let limit = a.integer_in("limit", 0, 1000, 50)?;
            Ok(ApiCall::get(graph_api_path(graph_id.as_deref(), "/schema")).param("limit", limit))
        }
        other => Err(format!("Tool {other} not found")),
    }
}

fn plan_cypher(a: &ToolArgs) -> Result<ApiCall, String> {
    let query = a.required_string("query")?;
    let graph_id = a.string("graph_id")?;
    let all_graphs = a.bool("all_graphs")?;
    let selected: Option<Vec<String>> = a
        .string_list("graphs")?
        .map(|g| g.into_iter().filter(|s| !s.trim().is_empty()).collect());
    let has_selected = selected.as_ref().is_some_and(|g| !g.is_empty());
    let mode = a.one_of("mode", &["cross-graph", "fanout"], "cross-graph")?;
    let limit = a.number("limit")?;
    let timeout_ms = a.positive_integer("timeout_ms")?;
    let per_graph_limit = a.number("per_graph_limit")?;
    let include_graph_rows = a.bool("include_graph_rows")?;

    if graph_id.is_some() && (all_graphs || has_selected) {
        return Err("graph_id is mutually exclusive with all_graphs and graphs".into());
    }
    if all_graphs && has_selected {
        return Err("all_graphs and graphs are mutually exclusive".into());
    }
    let has_explicit_set = all_graphs || has_selected;
    if graph_id.is_some() && mode != "cross-graph" {
        return Err("mode is only valid with graphs or all_graphs".into());
    }
    if !has_explicit_set && graph_id.is_none() && mode == "fanout" {
        return Err("fanout mode requires graphs or all_graphs=true".into());
    }
    if mode == "cross-graph" && (per_graph_limit.is_some() || include_graph_rows) {
        return Err("per_graph_limit and include_graph_rows are only valid in fanout mode".into());
    }

    let with_params = |mut call: ApiCall| {
        if let Some(limit) = &limit {
            call = call.param("limit", number_text(limit));
        }
        if let Some(t) = timeout_ms {
            call = call.param("timeoutMs", t.to_string());
        }
        if let Some(p) = &per_graph_limit {
            call = call.param("perGraphLimit", number_text(p));
        }
        if include_graph_rows {
            call = call.param("includeGraphRows", "true".to_string());
        }
        call
    };

    if let Some(id) = graph_id {
        return Ok(with_params(ApiCall::post(
            graph_api_path(Some(&id), "/cypher"),
            json!({"query": query}),
        )));
    }
    if has_explicit_set {
        let mut body = Map::new();
        body.insert("query".into(), json!(query));
        body.insert("mode".into(), json!(mode));
        if has_selected {
            body.insert("graphs".into(), json!(selected.unwrap_or_default()));
        }
        if all_graphs {
            body.insert("allGraphs".into(), json!(true));
        }
        return Ok(with_params(ApiCall::post(
            "/api/cypher/graphs".to_string(),
            Value::Object(body),
        )));
    }
    Ok(with_params(ApiCall::post(
        "/api/cypher".to_string(),
        json!({"query": query}),
    )))
}

/// The MCP server: JSON-RPC dispatch over an API router.
pub struct McpServer {
    api: Router,
    /// Where requests and tool calls are recorded; `None` keeps the transport free of
    /// instrumentation, as `graphite serve` without `--metrics` and `graphite mcp` are.
    metrics: Option<Arc<McpMetrics>>,
}

/// The text of a tool result, or the error text of a failed one.
enum ToolOutcome {
    Ok(String),
    Err(String),
}

impl McpServer {
    pub fn new(api: Router) -> Self {
        McpServer { api, metrics: None }
    }

    /// A server that records every request and tool call into `metrics`.
    pub fn with_metrics(api: Router, metrics: Arc<McpMetrics>) -> Self {
        McpServer {
            api,
            metrics: Some(metrics),
        }
    }

    /// Send `call` through the API router and return the response body as text.
    async fn dispatch(&self, call: &ApiCall) -> ToolOutcome {
        let mut builder = Request::builder()
            .method(call.method.clone())
            .uri(call.uri());
        let body = match &call.body {
            Some(json) => {
                builder = builder.header(header::CONTENT_TYPE, "application/json");
                Body::from(json.to_string())
            }
            None => Body::empty(),
        };
        let request = match builder.body(body) {
            Ok(r) => r,
            Err(e) => return ToolOutcome::Err(format!("invalid request: {e}")),
        };
        let response = match self.api.clone().oneshot(request).await {
            Ok(r) => r,
            Err(e) => return ToolOutcome::Err(format!("request failed: {e}")),
        };
        let status = response.status();
        let bytes = match to_bytes(response.into_body(), usize::MAX).await {
            Ok(b) => b,
            Err(e) => return ToolOutcome::Err(format!("could not read the response: {e}")),
        };
        let text = String::from_utf8_lossy(&bytes).into_owned();
        if !status.is_success() {
            // `${res.status} ${res.statusText}: ${text}`
            return ToolOutcome::Err(format!(
                "{} {}: {}",
                status.as_u16(),
                status.canonical_reason().unwrap_or(""),
                text
            ));
        }
        if call.text {
            return ToolOutcome::Ok(text);
        }
        // `JSON.stringify(data, null, 2)`: parsed and re-rendered with two-space indent.
        match serde_json::from_str::<Value>(&text) {
            Ok(value) => ToolOutcome::Ok(
                serde_json::to_string_pretty(&value).unwrap_or_else(|_| text.clone()),
            ),
            Err(e) => ToolOutcome::Err(format!("the API returned invalid JSON: {e}")),
        }
    }

    /// Handle one JSON-RPC message (or a batch). `None` for notifications, which get
    /// no response.
    pub async fn handle(&self, message: &Value) -> Option<Value> {
        if let Value::Array(batch) = message {
            if batch.is_empty() {
                return Some(error_response(
                    Value::Null,
                    INVALID_REQUEST,
                    "Invalid Request: empty batch",
                ));
            }
            let mut responses = Vec::new();
            for item in batch {
                if let Some(r) = Box::pin(self.handle(item)).await {
                    responses.push(r);
                }
            }
            return (!responses.is_empty()).then_some(Value::Array(responses));
        }
        let Some(object) = message.as_object() else {
            return Some(error_response(
                Value::Null,
                INVALID_REQUEST,
                "Invalid Request: expected an object",
            ));
        };
        // The envelope decides what a message is, not the method's spelling: a message
        // with no `id` is a notification and gets no response whatever its method; a
        // message with an `id` is a request even if its method says "notifications/",
        // and is answered (with "Method not found" in that case).
        let id = object.get("id").cloned().unwrap_or(Value::Null);
        let is_notification = !object.contains_key("id");
        // A request id is a string or a number (the MCP RequestId type); `null`, a
        // boolean or a structured value is not an id, and such a message is neither a
        // request nor a notification, so it is refused with a null response id.
        if !is_notification && !(id.is_string() || id.is_number()) {
            return Some(error_response(
                Value::Null,
                INVALID_REQUEST,
                "Invalid Request: id must be a string or a number",
            ));
        }
        if object.get("jsonrpc").and_then(Value::as_str) != Some("2.0") {
            return Some(error_response(
                id,
                INVALID_REQUEST,
                "Invalid Request: jsonrpc must be \"2.0\"",
            ));
        }
        let Some(method) = object.get("method").and_then(Value::as_str) else {
            return Some(error_response(
                id,
                INVALID_REQUEST,
                "Invalid Request: missing method",
            ));
        };
        let params = object.get("params").cloned().unwrap_or(Value::Null);
        if is_notification {
            return None;
        }
        if let Some(metrics) = &self.metrics {
            metrics.record_request(method);
        }
        let result = match method {
            "initialize" => Ok(self.initialize(&params)),
            "ping" => Ok(json!({})),
            "tools/list" => Ok(json!({
                "tools": tools().into_iter().map(|t| json!({
                    "name": t.name,
                    "description": t.description,
                    "inputSchema": t.input_schema,
                })).collect::<Vec<_>>()
            })),
            "tools/call" => self.call_tool(&params).await,
            other => Err((METHOD_NOT_FOUND, format!("Method not found: {other}"))),
        };
        Some(match result {
            Ok(result) => json!({"jsonrpc": "2.0", "id": id, "result": result}),
            Err((code, message)) => error_response(id, code, &message),
        })
    }

    fn initialize(&self, params: &Value) -> Value {
        let requested = params
            .get("protocolVersion")
            .and_then(Value::as_str)
            .unwrap_or("");
        let version = if SUPPORTED_PROTOCOLS.contains(&requested) {
            requested
        } else {
            PROTOCOL_VERSION
        };
        json!({
            "protocolVersion": version,
            "capabilities": {"tools": {"listChanged": false}},
            "serverInfo": {"name": SERVER_NAME, "version": VERSION},
        })
    }

    async fn call_tool(&self, params: &Value) -> Result<Value, (i64, String)> {
        let name = params.get("name").and_then(Value::as_str).ok_or((
            INVALID_PARAMS,
            "tools/call requires a tool name".to_string(),
        ))?;
        // The tool's own `&'static str` name is the metric label, never the client's.
        let Some(tool) = tools().iter().map(|t| t.name).find(|t| *t == name) else {
            return Err((INVALID_PARAMS, format!("Tool {name} not found")));
        };
        let empty = Map::new();
        let arguments = match params.get("arguments") {
            None | Some(Value::Null) => &empty,
            Some(Value::Object(m)) => m,
            Some(_) => {
                return Err((
                    INVALID_PARAMS,
                    "tools/call arguments must be an object".to_string(),
                ))
            }
        };
        let started = Instant::now();
        let outcome = match plan(tool, arguments) {
            Ok(call) => self.dispatch(&call).await,
            Err(message) => ToolOutcome::Err(message),
        };
        if let Some(metrics) = &self.metrics {
            let ok = matches!(outcome, ToolOutcome::Ok(_));
            metrics.record_tool(tool, ok, started.elapsed().as_nanos() as u64);
        }
        Ok(match outcome {
            ToolOutcome::Ok(text) => json!({"content": [{"type": "text", "text": text}]}),
            ToolOutcome::Err(text) => {
                json!({"content": [{"type": "text", "text": text}], "isError": true})
            }
        })
    }

    /// Handle one line of the stdio transport. Unparseable input gets a parse error
    /// response; a notification gets none.
    pub async fn handle_text(&self, line: &str) -> Option<String> {
        let message = match serde_json::from_str::<Value>(line) {
            Ok(v) => v,
            Err(e) => {
                return Some(
                    error_response(Value::Null, PARSE_ERROR, &format!("Parse error: {e}"))
                        .to_string(),
                )
            }
        };
        self.handle(&message).await.map(|v| v.to_string())
    }
}

fn error_response(id: Value, code: i64, message: &str) -> Value {
    json!({"jsonrpc": "2.0", "id": id, "error": {"code": code, "message": message}})
}

/// Which browser origins may reach `/mcp`.
///
/// The Streamable HTTP transport requires the server to validate `Origin` on every
/// request: `graphite serve` binds all interfaces, and without the check a page on any
/// site could resolve a name to the user's Explorer and call every tool, Cypher
/// included, against the loaded graphs. A request without `Origin` (a CLI, an MCP
/// client) is accepted; a request with one is accepted only when the origin is a
/// loopback origin (`http://localhost`, `127.0.0.1`, `[::1]`, any port, http or https)
/// or one the operator listed with `--mcp-allowed-origin`. `*` in that list allows any
/// origin. The `null` origin of sandboxed pages is never accepted.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct OriginPolicy {
    pub allowed: Vec<String>,
}

impl OriginPolicy {
    pub fn new(allowed: Vec<String>) -> Self {
        OriginPolicy {
            allowed: allowed
                .into_iter()
                .map(|o| o.trim().trim_end_matches('/').to_ascii_lowercase())
                .filter(|o| !o.is_empty())
                .collect(),
        }
    }

    pub fn allows(&self, origin: &str) -> bool {
        let origin = origin.trim().trim_end_matches('/').to_ascii_lowercase();
        if origin.is_empty() || origin == "null" {
            return false;
        }
        if self.allowed.iter().any(|o| o == "*" || *o == origin) {
            return true;
        }
        is_loopback_origin(&origin)
    }
}

/// `http(s)://localhost`, `127.0.0.1`, or `[::1]`, with or without a port.
fn is_loopback_origin(origin: &str) -> bool {
    let rest = match origin
        .strip_prefix("http://")
        .or_else(|| origin.strip_prefix("https://"))
    {
        Some(r) => r,
        None => return false,
    };
    if rest.contains('/') {
        return false;
    }
    let host = if let Some(after) = rest.strip_prefix("[::1]") {
        return after.is_empty() || is_port(after);
    } else {
        rest.split(':').next().unwrap_or("")
    };
    let port = &rest[host.len()..];
    (host == "localhost" || host == "127.0.0.1") && (port.is_empty() || is_port(port))
}

fn is_port(text: &str) -> bool {
    text.strip_prefix(':')
        .is_some_and(|p| !p.is_empty() && p.len() <= 5 && p.bytes().all(|b| b.is_ascii_digit()))
}

/// The 403 for a request whose `Origin` the policy does not allow; `None` when the
/// request may proceed.
fn forbidden_origin(policy: &OriginPolicy, headers: &HeaderMap) -> Option<Response> {
    let origin = headers.get(header::ORIGIN)?;
    let text = origin.to_str().unwrap_or("");
    if policy.allows(text) {
        return None;
    }
    Some(
        (
            StatusCode::FORBIDDEN,
            axum::Json(error_response(
                Value::Null,
                INVALID_REQUEST,
                "Origin not allowed: /mcp accepts loopback origins and those listed with --mcp-allowed-origin",
            )),
        )
            .into_response(),
    )
}

/// The API router with the MCP endpoint mounted at `/mcp`, guarded by `policy`.
pub fn with_mcp_route(
    api: Router,
    policy: OriginPolicy,
    metrics: Option<Arc<McpMetrics>>,
) -> Router {
    let server = Arc::new(match metrics {
        Some(m) => McpServer::with_metrics(api.clone(), m),
        None => McpServer::new(api.clone()),
    });
    api.route("/mcp", post(mcp_post).get(mcp_get).delete(mcp_delete))
        .layer(Extension(server))
        .layer(Extension(Arc::new(policy)))
}

async fn mcp_post(
    Extension(server): Extension<Arc<McpServer>>,
    Extension(policy): Extension<Arc<OriginPolicy>>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    if let Some(forbidden) = forbidden_origin(&policy, &headers) {
        return forbidden;
    }
    if let Some(unsupported) = unsupported_protocol_version(&headers) {
        return unsupported;
    }
    let message = match serde_json::from_slice::<Value>(&body) {
        Ok(v) => v,
        Err(e) => {
            return (
                StatusCode::BAD_REQUEST,
                axum::Json(error_response(
                    Value::Null,
                    PARSE_ERROR,
                    &format!("Parse error: {e}"),
                )),
            )
                .into_response()
        }
    };
    match server.handle(&message).await {
        Some(response) => (StatusCode::OK, axum::Json(response)).into_response(),
        // A notification (or a batch of them) is accepted and answered with nothing.
        None => StatusCode::ACCEPTED.into_response(),
    }
}

/// A 400 when the request names a protocol revision this server does not speak in
/// `MCP-Protocol-Version`. A request without the header is fine: the specification
/// treats it as `2025-03-26`, which is supported, and the `initialize` request
/// legitimately carries none.
fn unsupported_protocol_version(headers: &HeaderMap) -> Option<Response> {
    let value = headers.get(PROTOCOL_VERSION_HEADER)?;
    let named = value.to_str().unwrap_or("").trim();
    if SUPPORTED_PROTOCOLS.contains(&named) {
        return None;
    }
    Some(
        (
            StatusCode::BAD_REQUEST,
            axum::Json(error_response(
                Value::Null,
                INVALID_REQUEST,
                &format!(
                    "Unsupported MCP-Protocol-Version '{named}'; supported: {}",
                    SUPPORTED_PROTOCOLS.join(", ")
                ),
            )),
        )
            .into_response(),
    )
}

/// The server opens no stream of its own; the protocol lets it answer 405 here.
async fn mcp_get(Extension(policy): Extension<Arc<OriginPolicy>>, headers: HeaderMap) -> Response {
    if let Some(forbidden) = forbidden_origin(&policy, &headers) {
        return forbidden;
    }
    if let Some(unsupported) = unsupported_protocol_version(&headers) {
        return unsupported;
    }
    (
        StatusCode::METHOD_NOT_ALLOWED,
        [(header::ALLOW, "POST, DELETE")],
        "This server does not open server-to-client streams; POST JSON-RPC messages to /mcp.",
    )
        .into_response()
}

/// Sessions are stateless here, so ending one is a no-op.
async fn mcp_delete(
    Extension(policy): Extension<Arc<OriginPolicy>>,
    headers: HeaderMap,
) -> Response {
    if let Some(forbidden) = forbidden_origin(&policy, &headers) {
        return forbidden;
    }
    if let Some(unsupported) = unsupported_protocol_version(&headers) {
        return unsupported;
    }
    StatusCode::OK.into_response()
}

/// `graphite mcp`: serve the tools over stdio until stdin closes.
pub fn run_stdio(cli: GraphArgs) -> Result<(), String> {
    warn_if_debug_build();
    let opened = open(&cli, false)?;
    report_loaded(&opened, &cli);
    eprintln!("MCP server ready on stdio ({} tools)", tools().len());
    let runtime = runtime(cli.max_concurrent_cypher)?;
    runtime.block_on(async move {
        let server = Arc::new(McpServer::new(crate::routes::router(opened.state.clone())));
        serve_lines(server, tokio::io::stdin(), tokio::io::stdout()).await
    })
}

/// The stdio transport over any line reader and writer: every non-empty line is a
/// message handled on its own task, so reading never waits for a request to finish (a
/// Cypher call may take a minute; a `ping` sent behind it must not). Responses are
/// written as they complete, one per line, through one lock so lines never interleave.
/// Returns once the reader is exhausted and every in-flight request has been answered.
pub async fn serve_lines<R, W>(server: Arc<McpServer>, reader: R, writer: W) -> Result<(), String>
where
    R: tokio::io::AsyncRead + Unpin,
    W: tokio::io::AsyncWrite + Unpin + Send + 'static,
{
    let writer = Arc::new(tokio::sync::Mutex::new(writer));
    let mut in_flight = tokio::task::JoinSet::new();
    let mut lines = tokio::io::BufReader::new(reader).lines();
    let read_error = loop {
        let line = match lines.next_line().await {
            Ok(Some(line)) => line,
            Ok(None) => break None,
            Err(e) => break Some(format!("stdin: {e}")),
        };
        if line.trim().is_empty() {
            continue;
        }
        let (server, writer) = (server.clone(), writer.clone());
        in_flight.spawn(async move {
            match server.handle_text(&line).await {
                Some(response) => write_line(&writer, &response).await,
                None => Ok(()),
            }
        });
        // Reap finished requests so a write failure surfaces without waiting for EOF.
        while let Some(done) = in_flight.try_join_next() {
            finished(done)?;
        }
    };
    while let Some(done) = in_flight.join_next().await {
        finished(done)?;
    }
    read_error.map_or(Ok(()), Err)
}

async fn write_line<W: tokio::io::AsyncWrite + Unpin>(
    writer: &tokio::sync::Mutex<W>,
    response: &str,
) -> Result<(), String> {
    let mut out = writer.lock().await;
    // One message per line, flushed at once so the client sees it now.
    let written = async {
        out.write_all(response.as_bytes()).await?;
        out.write_all(b"\n").await?;
        out.flush().await
    }
    .await;
    written.map_err(|e| format!("stdout: {e}"))
}

fn finished(done: Result<Result<(), String>, tokio::task::JoinError>) -> Result<(), String> {
    done.map_err(|e| format!("request task failed: {e}"))?
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::guard::CypherGuard;
    use crate::registry::{GraphRegistry, LoadMode};
    use crate::routes::{router, AppState};

    fn args(pairs: &[(&str, Value)]) -> Map<String, Value> {
        pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.clone()))
            .collect()
    }

    #[test]
    fn the_thirteen_npm_tools_come_first_in_order_then_schema() {
        let names: Vec<&str> = tools().iter().map(|t| t.name).collect();
        assert_eq!(
            names,
            [
                "graphs",
                "openapi",
                "cypher",
                "node",
                "outgoing",
                "incoming",
                "annotations",
                "endpoints",
                "resources",
                "resource",
                "subgraph",
                "overview",
                "c4",
                "schema"
            ]
        );
        for tool in tools() {
            assert_eq!(tool.input_schema["type"], "object", "{}", tool.name);
            assert_eq!(tool.input_schema["additionalProperties"], false);
        }
        assert_eq!(tools()[2].input_schema["required"], json!(["query"]));
        assert_eq!(
            tools()[12].input_schema["properties"]["level"]["default"],
            "all"
        );
        assert!(tools()[13].input_schema.get("required").is_none());
        assert!(tools()[2].description.contains("Call schema first"));
    }

    #[test]
    fn schema_describes_one_graph_or_all_with_a_pattern_limit() {
        assert_eq!(
            plan("schema", &args(&[])).unwrap().uri(),
            "/api/schema?limit=50"
        );
        assert_eq!(
            plan(
                "schema",
                &args(&[("graph_id", json!("a/b")), ("limit", json!(5))])
            )
            .unwrap()
            .uri(),
            "/api/graphs/a%2Fb/schema?limit=5"
        );
        assert_eq!(
            plan("schema", &args(&[("graph_id", json!(1))])).unwrap_err(),
            plan("overview", &args(&[("graph_id", json!(1))])).unwrap_err()
        );
        // A whole-number float is the integer it names; anything else is refused
        // rather than silently replaced by the route's default.
        assert_eq!(
            plan("schema", &args(&[("limit", json!(3.0))]))
                .unwrap()
                .uri(),
            "/api/schema?limit=3"
        );
        for bad in [json!(1.5), json!(-1), json!(1001), json!("5")] {
            assert_eq!(
                plan("schema", &args(&[("limit", bad)])).unwrap_err(),
                "limit must be an integer between 0 and 1000"
            );
        }
        assert_eq!(
            tools()[13].input_schema["properties"]["limit"]["type"],
            "integer"
        );
    }

    #[test]
    fn encoders_match_the_javascript_ones() {
        assert_eq!(
            encode_uri_component("com.acme/x y?&"),
            "com.acme%2Fx%20y%3F%26"
        );
        assert_eq!(encode_uri_component("a-b_c.d!~*'()"), "a-b_c.d!~*'()");
        assert_eq!(form_encode("a b+c/d*e"), "a+b%2Bc%2Fd*e");
        assert_eq!(
            encode_resource_path("/META-INF/a b/c.xml"),
            "META-INF/a%20b/c.xml"
        );
        assert_eq!(
            graph_api_path(Some("my app"), "node/3"),
            "/api/graphs/my%20app/node/3"
        );
        assert_eq!(graph_api_path(None, "/overview"), "/api/overview");
    }

    #[test]
    fn simple_tools_map_to_their_routes() {
        assert_eq!(plan("graphs", &args(&[])).unwrap().uri(), "/api/graphs");
        assert_eq!(
            plan("graphs", &args(&[("graph_id", json!("a/b"))]))
                .unwrap()
                .uri(),
            "/api/graphs/a%2Fb"
        );
        assert_eq!(plan("openapi", &args(&[])).unwrap().uri(), "/openapi.json");
        assert_eq!(
            plan(
                "node",
                &args(&[("id", json!(42)), ("graph_id", json!("g"))])
            )
            .unwrap()
            .uri(),
            "/api/graphs/g/node/42"
        );
        // Node IDs are graph-local and the API has no all-graph node route, so the
        // graph is required instead of being answered with a 404.
        for tool in ["node", "outgoing", "incoming"] {
            assert_eq!(
                plan(tool, &args(&[("id", json!(42))])).unwrap_err(),
                "graph_id is required"
            );
        }
        assert_eq!(
            plan(
                "outgoing",
                &args(&[("id", json!(42)), ("graph_id", json!("g"))])
            )
            .unwrap()
            .uri(),
            "/api/graphs/g/node/42/outgoing"
        );
        assert_eq!(
            plan(
                "incoming",
                &args(&[("id", json!(7.5)), ("graph_id", json!("g"))])
            )
            .unwrap()
            .uri(),
            "/api/graphs/g/node/7.5/incoming"
        );
        assert!(plan("node", &args(&[]))
            .unwrap_err()
            .contains("id is required"));
        assert_eq!(
            plan(
                "annotations",
                &args(&[("class_name", json!("com.A")), ("member_name", json!("m"))])
            )
            .unwrap()
            .uri(),
            "/api/annotations?class=com.A&member=m"
        );
        assert_eq!(
            plan(
                "resource",
                &args(&[
                    ("path", json!("/META-INF/a b.txt")),
                    ("graph_id", json!("g"))
                ])
            )
            .unwrap()
            .uri(),
            "/api/graphs/g/resources/META-INF/a%20b.txt"
        );
        assert_eq!(
            plan("subgraph", &args(&[("center", json!(9))]))
                .unwrap()
                .uri(),
            "/api/subgraph?center=9&depth=2"
        );
        assert_eq!(
            plan("overview", &args(&[("limit", json!(0))]))
                .unwrap()
                .uri(),
            "/api/overview?limit=0"
        );
        assert!(plan("bogus", &args(&[])).unwrap_err().contains("not found"));
    }

    #[test]
    fn falsy_limits_are_dropped_as_the_npm_package_dropped_them() {
        assert_eq!(
            plan("endpoints", &args(&[])).unwrap().uri(),
            "/api/endpoints?limit=200"
        );
        assert_eq!(
            plan(
                "endpoints",
                &args(&[("limit", json!(0)), ("class_name", json!("C"))])
            )
            .unwrap()
            .uri(),
            "/api/endpoints?class=C"
        );
        assert_eq!(
            plan("resources", &args(&[("pattern", json!("**/*.xml"))]))
                .unwrap()
                .uri(),
            "/api/resources?pattern=**%2F*.xml&limit=100"
        );
        assert_eq!(
            plan(
                "resources",
                &args(&[("pattern", json!("")), ("limit", json!(0))])
            )
            .unwrap()
            .uri(),
            "/api/resources"
        );
    }

    #[test]
    fn c4_text_formats_return_text_and_json_returns_json() {
        let json_call = plan("c4", &args(&[])).unwrap();
        assert_eq!(
            json_call.uri(),
            "/api/architecture/c4?level=all&format=json&limit=200"
        );
        assert!(!json_call.text);
        let text_call = plan(
            "c4",
            &args(&[
                ("graph_id", json!("g")),
                ("format", json!("mermaid")),
                ("level", json!("container")),
            ]),
        )
        .unwrap();
        assert_eq!(
            text_call.uri(),
            "/api/graphs/g/architecture/c4?level=container&format=mermaid&limit=200"
        );
        assert!(text_call.text);
        assert!(plan("c4", &args(&[("level", json!("floor"))]))
            .unwrap_err()
            .contains("level must be one of"));
    }

    #[test]
    fn cypher_routes_by_graph_selection_and_validates_like_the_npm_package() {
        let single = plan(
            "cypher",
            &args(&[
                ("query", json!("RETURN 1")),
                ("graph_id", json!("g")),
                ("limit", json!(5)),
                ("timeout_ms", json!(100)),
            ]),
        )
        .unwrap();
        assert_eq!(single.method, Method::POST);
        assert_eq!(single.uri(), "/api/graphs/g/cypher?limit=5&timeoutMs=100");
        assert_eq!(single.body, Some(json!({"query": "RETURN 1"})));

        let all = plan("cypher", &args(&[("query", json!("RETURN 1"))])).unwrap();
        assert_eq!(all.uri(), "/api/cypher");
        assert_eq!(all.body, Some(json!({"query": "RETURN 1"})));

        let set = plan(
            "cypher",
            &args(&[
                ("query", json!("RETURN 1")),
                ("graphs", json!(["a", " ", "b"])),
                ("mode", json!("fanout")),
                ("per_graph_limit", json!(3)),
                ("include_graph_rows", json!(true)),
            ]),
        )
        .unwrap();
        assert_eq!(
            set.uri(),
            "/api/cypher/graphs?perGraphLimit=3&includeGraphRows=true"
        );
        assert_eq!(
            set.body,
            Some(json!({"query": "RETURN 1", "mode": "fanout", "graphs": ["a", "b"]}))
        );
        let every = plan(
            "cypher",
            &args(&[("query", json!("RETURN 1")), ("all_graphs", json!(true))]),
        )
        .unwrap();
        assert_eq!(
            every.body,
            Some(json!({"query": "RETURN 1", "mode": "cross-graph", "allGraphs": true}))
        );

        let err = |pairs: &[(&str, Value)]| plan("cypher", &args(pairs)).unwrap_err();
        assert_eq!(
            err(&[
                ("query", json!("x")),
                ("graph_id", json!("g")),
                ("all_graphs", json!(true))
            ]),
            "graph_id is mutually exclusive with all_graphs and graphs"
        );
        assert_eq!(
            err(&[
                ("query", json!("x")),
                ("graphs", json!(["a"])),
                ("all_graphs", json!(true))
            ]),
            "all_graphs and graphs are mutually exclusive"
        );
        assert_eq!(
            err(&[
                ("query", json!("x")),
                ("graph_id", json!("g")),
                ("mode", json!("fanout"))
            ]),
            "mode is only valid with graphs or all_graphs"
        );
        assert_eq!(
            err(&[("query", json!("x")), ("mode", json!("fanout"))]),
            "fanout mode requires graphs or all_graphs=true"
        );
        assert_eq!(
            err(&[("query", json!("x")), ("include_graph_rows", json!(true))]),
            "per_graph_limit and include_graph_rows are only valid in fanout mode"
        );
        assert!(
            err(&[("query", json!("x")), ("timeout_ms", json!(0))]).contains("positive integer")
        );
        assert!(err(&[]).contains("query is required"));
    }

    /// A directory of its own per call: the process id separates test processes and a
    /// counter separates the tests one process runs in parallel (a timestamp did not,
    /// two tests could draw the same nanosecond and one would remove the other's root).
    static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

    fn empty_state(metrics_enabled: bool) -> (Arc<AppState>, std::path::PathBuf) {
        let root = std::env::temp_dir().join(format!(
            "graphite-mcp-test-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ));
        std::fs::create_dir_all(&root).unwrap();
        let registry = Arc::new(GraphRegistry::new(
            root.clone(),
            LoadMode::parse("MAPPED").unwrap(),
        ));
        let guard = Arc::new(CypherGuard::new(2, 10_000));
        let state = Arc::new(AppState::new(
            registry,
            guard,
            "test".into(),
            metrics_enabled,
        ));
        (state, root)
    }

    fn empty_server() -> (McpServer, std::path::PathBuf) {
        let (state, root) = empty_state(false);
        (McpServer::new(router(state)), root)
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn json_rpc_lifecycle_over_an_empty_registry() {
        let (server, root) = empty_server();
        let init = server
            .handle(&json!({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                "params": {"protocolVersion": "2024-11-05", "capabilities": {}, "clientInfo": {"name": "t", "version": "0"}}}))
            .await
            .unwrap();
        assert_eq!(init["result"]["protocolVersion"], "2024-11-05");
        assert_eq!(init["result"]["serverInfo"]["name"], "graphite");
        assert!(init["result"]["capabilities"]["tools"].is_object());
        let init = server
            .handle(&json!({"jsonrpc": "2.0", "id": 2, "method": "initialize", "params": {"protocolVersion": "1999-01-01"}}))
            .await
            .unwrap();
        assert_eq!(init["result"]["protocolVersion"], PROTOCOL_VERSION);
        // The revision the retired npm SDK negotiated is still spoken.
        for supported in SUPPORTED_PROTOCOLS {
            let init = server
                .handle(&json!({"jsonrpc": "2.0", "id": 2, "method": "initialize", "params": {"protocolVersion": supported}}))
                .await
                .unwrap();
            assert_eq!(init["result"]["protocolVersion"], *supported);
        }
        assert!(SUPPORTED_PROTOCOLS.contains(&"2025-11-25"));
        assert_eq!(PROTOCOL_VERSION, *SUPPORTED_PROTOCOLS.last().unwrap());

        assert!(server
            .handle(&json!({"jsonrpc": "2.0", "method": "notifications/initialized"}))
            .await
            .is_none());
        assert_eq!(
            server
                .handle(&json!({"jsonrpc": "2.0", "id": 3, "method": "ping"}))
                .await
                .unwrap()["result"],
            json!({})
        );
        let list = server
            .handle(&json!({"jsonrpc": "2.0", "id": 4, "method": "tools/list"}))
            .await
            .unwrap();
        assert_eq!(list["result"]["tools"].as_array().unwrap().len(), 14);
        assert_eq!(list["result"]["tools"][0]["name"], "graphs");
        assert!(list["result"]["tools"][2]["inputSchema"]["properties"]["query"].is_object());

        let graphs = server
            .handle(&json!({"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {"name": "graphs"}}))
            .await
            .unwrap();
        assert!(graphs["result"]["isError"].is_null(), "{graphs}");
        let text = graphs["result"]["content"][0]["text"].as_str().unwrap();
        let parsed: Value = serde_json::from_str(text).unwrap();
        assert!(parsed.is_object() || parsed.is_array());
        assert!(text.contains('\n'), "pretty-printed: {text}");

        let missing = server
            .handle(&json!({"jsonrpc": "2.0", "id": 6, "method": "tools/call",
                "params": {"name": "graphs", "arguments": {"graph_id": "nope"}}}))
            .await
            .unwrap();
        assert_eq!(missing["result"]["isError"], true);
        assert!(missing["result"]["content"][0]["text"]
            .as_str()
            .unwrap()
            .starts_with("404 Not Found: "));

        let invalid = server
            .handle(&json!({"jsonrpc": "2.0", "id": 7, "method": "tools/call",
                "params": {"name": "cypher", "arguments": {"query": "RETURN 1", "mode": "fanout"}}}))
            .await
            .unwrap();
        assert_eq!(invalid["result"]["isError"], true);
        assert_eq!(
            invalid["result"]["content"][0]["text"],
            "fanout mode requires graphs or all_graphs=true"
        );

        let unknown_tool = server
            .handle(&json!({"jsonrpc": "2.0", "id": 8, "method": "tools/call", "params": {"name": "bogus"}}))
            .await
            .unwrap();
        assert_eq!(unknown_tool["error"]["code"], INVALID_PARAMS);
        let unknown_method = server
            .handle(&json!({"jsonrpc": "2.0", "id": 9, "method": "resources/list"}))
            .await
            .unwrap();
        assert_eq!(unknown_method["error"]["code"], METHOD_NOT_FOUND);

        let parse = server.handle_text("{not json").await.unwrap();
        assert!(parse.contains("-32700"));
        assert!(server
            .handle_text(r#"{"jsonrpc":"2.0","method":"notifications/cancelled"}"#)
            .await
            .is_none());
        let batch = server
            .handle(&json!([
                {"jsonrpc": "2.0", "id": 10, "method": "ping"},
                {"jsonrpc": "2.0", "method": "notifications/initialized"}
            ]))
            .await
            .unwrap();
        assert_eq!(batch.as_array().unwrap().len(), 1);
        std::fs::remove_dir_all(root).unwrap();
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn the_envelope_decides_what_a_message_is() {
        let (server, root) = empty_server();
        // No `id`: a notification, whatever the method; nothing goes back.
        assert!(server
            .handle(&json!({"jsonrpc": "2.0", "method": "tools/list"}))
            .await
            .is_none());
        assert!(server
            .handle_text(r#"{"jsonrpc":"2.0","method":"tools/call","params":{"name":"graphs"}}"#)
            .await
            .is_none());
        // An `id` makes a request, even under a "notifications/" method.
        let bogus = server
            .handle(&json!({"jsonrpc": "2.0", "id": 11, "method": "notifications/bogus"}))
            .await
            .unwrap();
        assert_eq!(bogus["id"], 11);
        assert_eq!(bogus["error"]["code"], METHOD_NOT_FOUND);
        // The version field is checked; the error carries the request's id when it has one.
        let missing = server
            .handle(&json!({"id": 12, "method": "ping"}))
            .await
            .unwrap();
        assert_eq!(missing["id"], 12);
        assert_eq!(missing["error"]["code"], INVALID_REQUEST);
        let wrong = server
            .handle_text(r#"{"jsonrpc":"1.0","method":"ping"}"#)
            .await
            .unwrap();
        assert!(
            wrong.contains("-32600") && wrong.contains(r#""id":null"#),
            "{wrong}"
        );
        // An id must be a string or a number: `null`, a boolean or a structured value
        // is refused as an Invalid Request whose response id is null.
        for bad_id in [json!(null), json!(true), json!([1]), json!({"n": 1})] {
            let refused = server
                .handle(&json!({"jsonrpc": "2.0", "id": bad_id, "method": "ping"}))
                .await
                .unwrap();
            assert_eq!(refused["id"], Value::Null, "{bad_id}");
            assert_eq!(refused["error"]["code"], INVALID_REQUEST, "{bad_id}");
            assert!(refused["error"]["message"]
                .as_str()
                .unwrap()
                .contains("id must be a string or a number"));
        }
        for good_id in [json!("abc"), json!(7), json!(2.5)] {
            let answered = server
                .handle(&json!({"jsonrpc": "2.0", "id": good_id, "method": "ping"}))
                .await
                .unwrap();
            assert_eq!(answered["id"], good_id);
            assert_eq!(answered["result"], json!({}));
        }
        std::fs::remove_dir_all(root).unwrap();
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn stdio_keeps_reading_while_a_request_runs() {
        use axum::routing::get;
        // A router whose `graphs` route takes a while: the call issued first must not
        // hold up the `ping` sent behind it.
        let slow = Router::new().route(
            "/api/graphs",
            get(|| async {
                tokio::time::sleep(std::time::Duration::from_millis(400)).await;
                axum::Json(json!([]))
            }),
        );
        let server = Arc::new(McpServer::new(slow));
        let input = concat!(
            r#"{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"graphs"}}"#,
            "\n",
            "\n",
            r#"{"jsonrpc":"2.0","method":"notifications/initialized"}"#,
            "\n",
            r#"{"jsonrpc":"2.0","id":2,"method":"ping"}"#,
            "\n",
        );
        let (mut client, server_side) = tokio::io::duplex(64 * 1024);
        let started = Instant::now();
        serve_lines(server, input.as_bytes(), server_side)
            .await
            .unwrap();
        assert!(started.elapsed() >= std::time::Duration::from_millis(400));
        let mut out = String::new();
        tokio::io::AsyncReadExt::read_to_string(&mut client, &mut out)
            .await
            .unwrap();
        let lines: Vec<Value> = out
            .lines()
            .map(|l| serde_json::from_str(l).unwrap())
            .collect();
        assert_eq!(lines.len(), 2, "{out}");
        assert_eq!(lines[0]["id"], 2, "the ping is answered first: {out}");
        assert_eq!(lines[0]["result"], json!({}));
        assert_eq!(lines[1]["id"], 1);
        assert_eq!(lines[1]["result"]["content"][0]["text"], "[]");
    }

    #[test]
    fn origin_policy_allows_loopback_and_listed_origins_only() {
        let p = OriginPolicy::default();
        for ok in [
            "http://localhost",
            "http://localhost:3000",
            "https://localhost:8443/",
            "http://127.0.0.1:8080",
            "http://[::1]",
            "http://[::1]:5173",
            "HTTP://LocalHost:80",
        ] {
            assert!(p.allows(ok), "{ok}");
        }
        for bad in [
            "http://evil.example",
            "http://localhost.evil.example",
            "http://localhost:abc",
            "http://127.0.0.1:8080/path",
            "http://127.0.0.2",
            "null",
            "",
            "ftp://localhost",
            "http://[::2]",
        ] {
            assert!(!p.allows(bad), "{bad}");
        }
        let p = OriginPolicy::new(vec!["https://Tools.Example.com/".into(), " ".into()]);
        assert_eq!(p.allowed, vec!["https://tools.example.com".to_string()]);
        assert!(p.allows("https://tools.example.com"));
        assert!(!p.allows("https://tools.example.com:444"));
        assert!(!p.allows("http://tools.example.com"));
        assert!(p.allows("http://localhost:9"));
        let any = OriginPolicy::new(vec!["*".into()]);
        assert!(any.allows("http://evil.example"));
        assert!(!any.allows("null"));
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn origins_are_validated_on_every_mcp_request() {
        let (server, root) = empty_server();
        let app = with_mcp_route(
            server.api.clone(),
            OriginPolicy::new(vec!["https://tools.example.com".into()]),
            None,
        );
        let list = r#"{"jsonrpc":"2.0","id":1,"method":"tools/list"}"#;
        let request = |method: Method, origin: Option<&str>| {
            let mut b = Request::builder().method(method).uri("/mcp");
            if let Some(o) = origin {
                b = b.header(header::ORIGIN, o);
            }
            b.header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(list.to_string()))
                .unwrap()
        };
        let status = |r: Response| r.status();
        assert_eq!(
            status(
                app.clone()
                    .oneshot(request(Method::POST, None))
                    .await
                    .unwrap()
            ),
            StatusCode::OK
        );
        assert_eq!(
            status(
                app.clone()
                    .oneshot(request(Method::POST, Some("http://localhost:3000")))
                    .await
                    .unwrap()
            ),
            StatusCode::OK
        );
        assert_eq!(
            status(
                app.clone()
                    .oneshot(request(Method::POST, Some("https://tools.example.com")))
                    .await
                    .unwrap()
            ),
            StatusCode::OK
        );
        let forbidden = app
            .clone()
            .oneshot(request(Method::POST, Some("http://evil.example")))
            .await
            .unwrap();
        assert_eq!(forbidden.status(), StatusCode::FORBIDDEN);
        let body: Value =
            serde_json::from_slice(&to_bytes(forbidden.into_body(), usize::MAX).await.unwrap())
                .unwrap();
        assert_eq!(body["error"]["code"], INVALID_REQUEST);
        assert_eq!(
            status(
                app.clone()
                    .oneshot(request(Method::POST, Some("null")))
                    .await
                    .unwrap()
            ),
            StatusCode::FORBIDDEN
        );
        assert_eq!(
            status(
                app.clone()
                    .oneshot(request(Method::GET, Some("http://evil.example")))
                    .await
                    .unwrap()
            ),
            StatusCode::FORBIDDEN
        );
        assert_eq!(
            status(
                app.clone()
                    .oneshot(request(Method::DELETE, Some("http://evil.example")))
                    .await
                    .unwrap()
            ),
            StatusCode::FORBIDDEN
        );
        assert_eq!(
            status(
                app.clone()
                    .oneshot(request(Method::DELETE, None))
                    .await
                    .unwrap()
            ),
            StatusCode::OK
        );
        // The REST API beside it is not affected by the MCP origin policy.
        let api = app
            .oneshot(
                Request::builder()
                    .uri("/api/graphs")
                    .header(header::ORIGIN, "http://evil.example")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(api.status(), StatusCode::OK);
        std::fs::remove_dir_all(root).unwrap();
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn the_http_endpoint_carries_the_same_dispatch() {
        let (server, root) = empty_server();
        let app = with_mcp_route(server.api.clone(), OriginPolicy::default(), None);
        let post = |body: &str| {
            Request::builder()
                .method(Method::POST)
                .uri("/mcp")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(body.to_string()))
                .unwrap()
        };
        let response = app
            .clone()
            .oneshot(post(r#"{"jsonrpc":"2.0","id":1,"method":"tools/list"}"#))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body: Value =
            serde_json::from_slice(&to_bytes(response.into_body(), usize::MAX).await.unwrap())
                .unwrap();
        assert_eq!(body["result"]["tools"].as_array().unwrap().len(), 14);

        let accepted = app
            .clone()
            .oneshot(post(
                r#"{"jsonrpc":"2.0","method":"notifications/initialized"}"#,
            ))
            .await
            .unwrap();
        assert_eq!(accepted.status(), StatusCode::ACCEPTED);
        // The envelope rules hold over HTTP too: no id means no response body.
        let silent = app
            .clone()
            .oneshot(post(r#"{"jsonrpc":"2.0","method":"tools/list"}"#))
            .await
            .unwrap();
        assert_eq!(silent.status(), StatusCode::ACCEPTED);
        let invalid = app
            .clone()
            .oneshot(post(r#"{"id":5,"method":"ping"}"#))
            .await
            .unwrap();
        assert_eq!(invalid.status(), StatusCode::OK);
        let body: Value =
            serde_json::from_slice(&to_bytes(invalid.into_body(), usize::MAX).await.unwrap())
                .unwrap();
        assert_eq!(body["error"]["code"], INVALID_REQUEST);

        let bad = app.clone().oneshot(post("nope")).await.unwrap();
        assert_eq!(bad.status(), StatusCode::BAD_REQUEST);

        // Initialization over HTTP negotiates the latest revision the npm SDK spoke.
        let init = app
            .clone()
            .oneshot(post(
                r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}"#,
            ))
            .await
            .unwrap();
        assert_eq!(init.status(), StatusCode::OK);
        let body: Value =
            serde_json::from_slice(&to_bytes(init.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert_eq!(body["result"]["protocolVersion"], "2025-11-25");
        // Subsequent requests name their revision; an unsupported one is refused.
        let with_version = |version: &str| {
            Request::builder()
                .method(Method::POST)
                .uri("/mcp")
                .header(header::CONTENT_TYPE, "application/json")
                .header(PROTOCOL_VERSION_HEADER, version)
                .body(Body::from(
                    r#"{"jsonrpc":"2.0","id":3,"method":"ping"}"#.to_string(),
                ))
                .unwrap()
        };
        for version in SUPPORTED_PROTOCOLS {
            let ok = app.clone().oneshot(with_version(version)).await.unwrap();
            assert_eq!(ok.status(), StatusCode::OK, "{version}");
        }
        let unsupported = app
            .clone()
            .oneshot(with_version("1999-01-01"))
            .await
            .unwrap();
        assert_eq!(unsupported.status(), StatusCode::BAD_REQUEST);
        let body: Value =
            serde_json::from_slice(&to_bytes(unsupported.into_body(), usize::MAX).await.unwrap())
                .unwrap();
        assert_eq!(body["error"]["code"], INVALID_REQUEST);
        assert!(body["error"]["message"]
            .as_str()
            .unwrap()
            .contains("1999-01-01"));
        let garbage = app
            .clone()
            .oneshot(with_version("not a version"))
            .await
            .unwrap();
        assert_eq!(garbage.status(), StatusCode::BAD_REQUEST);
        // The header is validated on every method of the transport, not only POST.
        let with_method_and_version = |method: Method, version: &str| {
            Request::builder()
                .method(method)
                .uri("/mcp")
                .header(PROTOCOL_VERSION_HEADER, version)
                .body(Body::empty())
                .unwrap()
        };
        for method in [Method::GET, Method::DELETE] {
            let refused = app
                .clone()
                .oneshot(with_method_and_version(method.clone(), "1999-01-01"))
                .await
                .unwrap();
            assert_eq!(refused.status(), StatusCode::BAD_REQUEST, "{method}");
            let body: Value =
                serde_json::from_slice(&to_bytes(refused.into_body(), usize::MAX).await.unwrap())
                    .unwrap();
            assert_eq!(body["error"]["code"], INVALID_REQUEST, "{method}");
        }
        let get_ok = app
            .clone()
            .oneshot(with_method_and_version(Method::GET, "2025-11-25"))
            .await
            .unwrap();
        assert_eq!(get_ok.status(), StatusCode::METHOD_NOT_ALLOWED);
        let delete_ok = app
            .clone()
            .oneshot(with_method_and_version(Method::DELETE, "2025-06-18"))
            .await
            .unwrap();
        assert_eq!(delete_ok.status(), StatusCode::OK);

        let get = app
            .clone()
            .oneshot(Request::builder().uri("/mcp").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(get.status(), StatusCode::METHOD_NOT_ALLOWED);

        // The REST API is still there beside it.
        let api = app
            .oneshot(
                Request::builder()
                    .uri("/api/graphs")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(api.status(), StatusCode::OK);
        std::fs::remove_dir_all(root).unwrap();
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn tool_calls_are_timed_by_tool_and_outcome_and_requests_counted_by_method() {
        let (server, root) = empty_server();
        let metrics = Arc::new(McpMetrics::default());
        let server = McpServer::with_metrics(server.api.clone(), metrics.clone());
        let call = |id: u64, method: &str, params: Value| json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params});
        server.handle(&call(1, "initialize", json!({}))).await;
        server.handle(&call(2, "tools/list", Value::Null)).await;
        server.handle(&call(3, "tools/list", Value::Null)).await;
        server.handle(&call(4, "resources/list", Value::Null)).await;
        // A notification is not a request and is not counted.
        server
            .handle(&json!({"jsonrpc": "2.0", "method": "notifications/initialized"}))
            .await;
        // Answered: `graphs` over an empty registry.
        server
            .handle(&call(5, "tools/call", json!({"name": "graphs"})))
            .await;
        // Refused by the tool: a graph that is not loaded.
        server
            .handle(&call(
                6,
                "tools/call",
                json!({"name": "graphs", "arguments": {"graph_id": "nope"}}),
            ))
            .await;
        // Refused before any tool runs: neither is timed.
        server
            .handle(&call(7, "tools/call", json!({"name": "bogus"})))
            .await;
        server
            .handle(&call(
                8,
                "tools/call",
                json!({"name": "graphs", "arguments": 3}),
            ))
            .await;

        let requests: std::collections::BTreeMap<_, _> = metrics.requests().into_iter().collect();
        assert_eq!(requests.get("initialize"), Some(&1));
        assert_eq!(requests.get("tools/list"), Some(&2));
        assert_eq!(requests.get("tools/call"), Some(&4));
        assert_eq!(requests.get("other"), Some(&1));
        assert_eq!(requests.get("ping"), None);

        let tools = metrics.tools();
        let series = |ok: bool| {
            tools
                .iter()
                .find(|(k, _)| k.tool == "graphs" && k.ok == ok)
                .map(|(_, s)| s.clone())
                .unwrap_or_else(|| panic!("no graphs series with ok={ok}"))
        };
        assert_eq!(series(true).count, 1);
        assert_eq!(series(false).count, 1);
        assert_eq!(
            tools.len(),
            2,
            "only the calls that reached the tool: {tools:?}"
        );
        assert!(tools.iter().all(|(k, _)| k.tool == "graphs"));
        std::fs::remove_dir_all(root).ok();
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_server_without_metrics_records_nothing() {
        let (server, root) = empty_server();
        server
            .handle(&json!({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "graphs"}}))
            .await;
        assert!(server.metrics.is_none());
        std::fs::remove_dir_all(root).ok();
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn the_metrics_route_exposes_the_mcp_families_after_a_tool_call() {
        let (state, root) = empty_state(true);
        let app = crate::routes::instrumented(
            with_mcp_route(
                router(state.clone()),
                OriginPolicy::default(),
                Some(state.mcp_metrics.clone()),
            ),
            state.clone(),
        );
        let scrape = || async {
            let response = app
                .clone()
                .oneshot(
                    Request::builder()
                        .method(Method::GET)
                        .uri("/metrics")
                        .body(Body::empty())
                        .unwrap(),
                )
                .await
                .unwrap();
            let bytes = to_bytes(response.into_body(), usize::MAX).await.unwrap();
            String::from_utf8(bytes.to_vec()).unwrap()
        };
        // The families are present before any MCP traffic, with no series.
        let before = scrape().await;
        assert!(before.contains("# TYPE graphite_mcp_requests_total counter\n"));
        assert!(before.contains("# TYPE graphite_mcp_tool_duration_seconds histogram\n"));
        assert!(before.contains("# TYPE graphite_mcp_tool_duration_seconds_max gauge\n"));
        assert!(!before.contains("graphite_mcp_tool_duration_seconds_count"));

        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .method(Method::POST)
                    .uri("/mcp")
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(
                        r#"{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"graphs"}}"#,
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);

        let after = scrape().await;
        assert!(after.contains("graphite_mcp_requests_total{method=\"tools/call\"} 1\n"));
        assert!(after.contains(
            "graphite_mcp_tool_duration_seconds_count{outcome=\"ok\",tool=\"graphs\"} 1\n"
        ));
        assert!(after.contains(
            "graphite_mcp_tool_duration_seconds_bucket{outcome=\"ok\",tool=\"graphs\",le=\"+Inf\"} 1\n"
        ));
        assert!(after
            .contains("graphite_mcp_tool_duration_seconds_max{outcome=\"ok\",tool=\"graphs\"} "));
        // The tool's inner API hop is not a second HTTP request: only `/mcp` is counted.
        assert!(after.contains("uri=\"/mcp\""));
        assert!(!after.contains("uri=\"/api/graphs\""));
        std::fs::remove_dir_all(root).ok();
    }
}

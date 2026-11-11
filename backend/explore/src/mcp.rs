//! The Model Context Protocol server: the Explorer API as MCP tools.
//!
//! Thirteen analysis tools map to HTTP routes in `routes.rs`; `help` provides local
//! investigation guidance on demand. Each analysis call becomes an HTTP request
//! dispatched to the router in-process (the routes the web UI and `curl` use),
//! so it goes through the same
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
//! The retained analysis tools preserve their argument schemas and defaults from
//! the former `graphite-mcp` npm package. OpenAPI discovery is HTTP-only.
//! Descriptions teach graph discovery and investigation
//! through concise `tools/list` metadata and on-demand `help`, without a separate skill.

use crate::metrics::McpMetrics;
use crate::serve::{
    open, report_c4_warmup, report_loaded, runtime, warn_if_debug_build, GraphArgs, VERSION,
};
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

// Detailed guidance is returned only on demand, never in tools/list.
const WORKFLOW_HELP: &str = r#"Start with graphs to choose scope, then schema to discover the actual labels/properties/edge patterns. Use cypher for callers, API usage, dataflow, configuration/feature flags and change impact; resolve exact signatures from Method declarations rather than guessing language conventions. Query bounded paths with selective anchors, DISTINCT/aggregation and LIMIT, and retain graph provenance.

Use endpoints for Spring Web handlers; annotations for emitted metadata; resources then resource for saved configuration; overview/c4 for inferred architecture; node/incoming/outgoing/subgraph to inspect concrete local evidence. Call help with any analysis tool name for its detailed guidance; help(topic="cypher") includes executable query shapes and Java/Kotlin/Android, JS/TS/ES and Swift/Apple caveats.

Graphs describe analyzed artifacts/build indexes, not live working-tree contents. Confirm coverage and rebuild externally when needed. Separate returned facts from inference, cite caller/callee signatures and values, and interpret empty results as not found in the analyzed inputs rather than proof of absence."#;
const TOOL_HELP: &[(&str, &str)] = &[
    (
        "graphs",
        r#"Discover saved program graphs from the available language frontends (Java/Kotlin/Android, JS/TS/ES and Swift/Apple). Call this first to obtain graph IDs and aggregate statistics, then schema before writing Cypher. Omit graph_id to list all loaded graphs and totals; set it to inspect one. Use cypher for callers, callees, dataflow, configuration/feature-flag usage and change impact; endpoints for Spring HTTP entry points; overview or c4 for architecture; resources/resource for packaged configuration. Graphs describe their analyzed artifacts/build indexes, not live working-tree contents: rebuild externally when newer code must be analyzed. Missing results only mean not found in the analyzed artifact/packages; reflection, dependency injection, dynamic loading and runtime configuration may be absent. Cite exact signatures/values and distinguish graph facts from inference."#,
    ),
    (
        "cypher",
        r#"Investigate code with read-only Cypher over Graphite's shared program graph: find entry points/API usage, trace callers and values, inspect configuration/feature flags, or assess change impact. The same tools query Java/Kotlin/Android, JavaScript/TypeScript/ECMAScript artifacts and Swift/Apple graphs. Call schema first and use the loaded graph's actual labels, properties and patterns; available evidence depends on the frontend and analyzed inputs.

Discover function/method identities with MATCH (m:Method) RETURN m.class, m.name, m.signature LIMIT 20, then narrow by an observed class/name. Method is virtual declaration metadata, including uncalled declarations; functions may be owned by a module rather than a class. Copy returned signatures exactly instead of guessing language-specific spelling. Find direct callers with MATCH (cs:CallSiteNode) WHERE cs.callee_signature = '<observed-signature>' RETURN DISTINCT cs.caller_signature, cs.callee_signature, cs.line LIMIT 50. Filter caller_signature instead for callees. For change impact, repeat the caller query on each frontier, tracking visited signatures and a depth budget. For chains, join one site's callee_signature to the next site's caller_signature. Do not assume CALL edges connect methods: inspect schema patterns; some frontends emit call-site self-loops and others omit CALL edges. To locate boundaries, group a scoped set of call sites by callee_class/callee_name, then inspect calls to the relevant platform APIs. Resolve entry points from declarations, registration calls or emitted annotations; endpoints is a Spring-specific shortcut.

DATAFLOW points from values toward uses. Trace a configuration/flag literal with MATCH (key:StringConstant {value: 'checkout.enabled'})-[:DATAFLOW*1..6]->(cs:CallSiteNode) RETURN DISTINCT key.value, cs.caller_signature, cs.callee_signature LIMIT 50. Replace the value with one observed in the graph or source. For origins of a selected call, match (value:Constant)-[:DATAFLOW*1..6]->(cs:CallSiteNode) and anchor cs.callee_signature; also inspect FieldNode paths when keys come from fields rather than literals. Follow outgoing paths from a call to trace its result. Inspect relationship kind (for example PARAMETER_PASS, RETURN_VALUE, FIELD_STORE or FIELD_LOAD) before interpreting a path. Interprocedural coverage varies: follow emitted edges first; where boundary links are absent, join ParameterNode.method or ReturnNode.method to the call's callee_signature, without claiming that the join proves a specific argument/parameter pairing. Receiver flows and missing argument-position edge metadata can make a path ambiguous. Confirm branch meaning and uncertain resource lookups in source; do not infer an enabled branch or a particular configuration file from reachability alone.

Frontend details: Java/Kotlin/Android graphs use JVM-style signatures, nested-class $, <init>/<clinit>, declared dispatch targets and CALL self-loops; inlined constants lose their field names, booleans may be 0/1, and call-boundary dataflow needs signature joins. JS/TS/ES graphs analyze emitted .js/.mjs/.cjs artifacts, not original TypeScript source; module/file owners and <module> top-level functions are normal. They emit CALL self-loops and direct argument-to-parameter/return-to-call dataflow for resolved calls; BooleanConstant values are booleans. Swift/Apple graphs preserve module/type names and argument labels in method names; indexed calls have call sites without requiring CALL edges, with limited syntax-derived argument/parameter/return flows. Missing edges are not proof that code is unreachable; do not assume JVM conventions apply to other frontends.

With no selector, execute one union query over all loaded graphs; graph_id selects one, graphs selects a named set, all_graphs=true explicitly selects all. cross-graph permits global joins/aggregation; fanout runs independently per graph. In cross-graph results return graphId(n) with id(n); add graphId(a) = graphId(b) to signature joins that must stay within one graph. In single-graph/fanout execution graphId(n) is null: retain the requested graph_id or fanout response provenance instead. Start paths at labeled, selective anchors using equality, IN or STARTS WITH; bound path depth and returned rows with DISTINCT/aggregation and LIMIT. Avoid unlabeled variable-length starts, ubiquitous constants and broad CONTAINS/regex scans. The graphite dialect uses Rust regex syntax (no look-around/backreferences). On timeout/work-budget errors narrow anchors, graph scope or depth before retrying. Results contain columns, rows and total; total.relation='gte' is a lower bound. Cite exact caller/callee signatures, values and graph provenance; line numbers may be absent or refer to emitted artifacts. Separate graph facts from source-confirmed interpretation."#,
    ),
    (
        "node",
        r#"Inspect a concrete stored node's display-oriented properties after discovering its ID with cypher or subgraph. Supply the graph-local numeric id and its graph_id together; identical IDs in different graphs are unrelated. Returns type/id/label and node-specific fields; use cypher RETURN n for raw query properties. Virtual Method results are not stored numeric nodes. Continue with incoming/outgoing for adjacent edges, or subgraph for a bounded neighborhood."#,
    ),
    (
        "outgoing",
        r#"Inspect edges leaving one stored node, using its numeric id and graph_id from cypher/subgraph. DATAFLOW edges point toward uses, so follow these to trace a value forward; edge kind distinguishes assignment, parameter passing, field stores/loads and returns. Returns at most 200 edges, not a complete transitive slice; use bounded cypher for filtering or counting high-degree nodes. For function/method callees, query CallSiteNode.caller_signature with cypher; CALL edges may be self-loops or absent depending on the frontend."#,
    ),
    (
        "incoming",
        r#"Inspect edges entering one stored node, using its numeric id and graph_id from cypher/subgraph. Follow incoming DATAFLOW to investigate value origins or call arguments; receiver flows may also reach calls, and edges do not identify argument positions. Interprocedural coverage depends on the frontend: inspect emitted paths rather than assuming every call boundary is linked. Returns at most 200 edges, not a complete backward slice; use bounded cypher for filtering or counting high-degree nodes. To find callers of a method, query CallSiteNode.callee_signature with cypher instead of following CALL edges."#,
    ),
    (
        "annotations",
        r#"Inspect annotations emitted by the frontend on a known class/type member, for example framework routing, injection or configuration metadata. Check schema for Annotation availability first; not every language/frontend emits annotation metadata. Supply an exact fully qualified class_name and member_name; use member_name='<class>' for class-level annotations. Omit graph_id for results grouped by graph, or select one graph. Discover unknown annotated classes/members with schema then cypher over Annotation nodes; use endpoints for Spring HTTP route extraction. Annotation metadata alone does not prove runtime wiring."#,
    ),
    (
        "endpoints",
        r#"Find Spring Web HTTP entry points from persisted mapping annotations, including class-level and method-level mappings. Returns framework, count and endpoints with class, member, signature, path and httpMethod; class_name is an exact controller filter. Omit graph_id to query all graphs grouped by graph, or select one. Use each returned signature with cypher on CallSiteNode.caller_signature to trace the handler's calls. This is Spring Web extraction, not an inventory of every framework, listener or main method; an empty result does not establish that the application has no entry points."#,
    ),
    (
        "resources",
        r#"Discover persisted resource paths and their sources, such as configuration files, manifests, bundles or service descriptors, when the frontend saved a resource store. Filter with a glob (for example **/*.properties), then call resource with an exact returned path to read content. Omit graph_id for results grouped by graph; preserve the returned graph ID when reading one artifact. Lists saved resources, not files from the working tree. A frontend or older graph may lack a persisted resource store; rebuilding helps only if the frontend supports it; missing resources do not prove missing runtime configuration. Use schema/cypher to trace Resource/ResourceValue or RESOURCE_LOOKUP only when those labels/edges exist."#,
    ),
    (
        "resource",
        r#"Read the saved content of an exact packaged resource path discovered with resources, for example a configuration file or manifest. Returns path, source, size and content decoded as UTF-8 (invalid bytes are replaced); this is not a binary download or arbitrary filesystem reader. Supply graph_id for one artifact; omit it to read every matching path grouped by graph. The response has a 1 MiB resource-content budget. Contents reflect the analyzed inputs, not runtime overrides. Confirm RESOURCE_LOOKUP fan-out before claiming a file/key is actually read."#,
    ),
    (
        "subgraph",
        r#"Inspect a bounded neighborhood around a concrete stored node, traversing both incoming and outgoing edges. Use cypher to find a center ID and normally supply its graph_id. If graph_id is omitted, the same numeric center is looked up independently in every graph; those nodes need not represent the same entity. Returns nodes and edges, capped at 2,000 nodes and 5,000 edges, so this is a local exploration view rather than exhaustive reachability. Use bounded cypher for precise paths and CallSiteNode signature joins for inter-method call chains."#,
    ),
    (
        "overview",
        r#"Explore dependencies grouped by the graph's class/type/module owners to identify central code and subsystem boundaries before drilling into code. Returns class-owner nodes and call-derived dependency edges; limit bounds the class view, so it is not a complete dependency audit. Omit graph_id for separate views grouped by graph, or select one graph. Follow up with cypher on CallSiteNode caller/callee classes and signatures to substantiate a dependency or assess change impact. Use c4 for higher-level architecture diagrams."#,
    ),
    (
        "c4",
        r#"Build inferred C4 architecture views from the saved program graph: context for the subject and external systems, container for deployable/runtime groupings, component for internal structure, or all. Use JSON for inspection and Mermaid/PlantUML/Structurizr DSL for diagrams. Omit graph_id for all graphs (JSON is grouped by graph), or select one graph. These are inferred code-based views, not verified deployment topology: confirm important relationships with overview, endpoints, resources and exact cypher call sites before reporting them as architectural facts."#,
    ),
    (
        "schema",
        r#"Discover the actual graph vocabulary before writing cypher: node label sets with counts and property keys, virtual_nodes including declared Method metadata, relationship types with counts, and the most frequent (from-labels)-[type]->(to-labels) patterns. Prefer this to broad MATCH/keys/labels exploration. Omit graph_id for separate schemas grouped by graphId, or select one. limit caps only the pattern list, not node/relationship inventories; a missing pattern may simply fall below that cap. Labels and available edges depend on the frontend, builder version and included packages; do not assume all examples exist in every graph.

Relationship properties are not enumerated here. DATAFLOW.kind may include ASSIGN, PARAMETER_PASS, RETURN_VALUE, FIELD_STORE, FIELD_LOAD, ARRAY_STORE, ARRAY_LOAD, CAST or PHI; the subset and interprocedural links depend on the frontend. CONTROL_FLOW.kind and branch coverage are frontend-specific. CALL exposes virtual and dynamic where emitted; its absence does not mean there are no call sites. Inspect sampled edges with cypher rather than transferring one language's graph conventions to another. Generic type metadata on Method/FieldNode/ParameterNode/ReturnNode can be null in older graphs. Use help(topic="cypher") for examples and semantic caveats to trace callers, values and configuration after checking this schema."#,
    ),
];

fn help_text(arguments: &Map<String, Value>) -> Result<&'static str, String> {
    let topic = ToolArgs(arguments)
        .string("topic")?
        .unwrap_or_else(|| "workflow".into());
    if topic == "workflow" {
        return Ok(WORKFLOW_HELP);
    }
    TOOL_HELP
        .iter()
        .find(|(name, _)| *name == topic)
        .map(|(_, text)| *text)
        .ok_or_else(|| {
            format!("Unknown help topic '{topic}'; use workflow or an analysis tool name")
        })
}

/// Analysis tools in stable discovery order; HTTP API discovery stays outside MCP.
pub fn tools() -> Vec<ToolDef> {
    vec![
        ToolDef {
            name: "graphs",
            description: r#"Discover loaded program graphs and statistics; omit graph_id to list all. Start here, then schema; use help for investigation workflows and frontend caveats."#,
            input_schema: schema(
                json!({"graph_id": graph_id_property("Explicit graph id; omit to list all graphs and totals")}),
                &[],
            ),
        },
        ToolDef {
            name: "cypher",
            description: r#"Query code, callers, values and change impact across selected graphs. Call schema first; use selective labeled anchors, bounded paths and LIMIT. See help(topic="cypher") for recipes, graph provenance and frontend-specific semantics."#,
            input_schema: schema(
                json!({
                    "query": {"type": "string", "description": "Read-only Cypher with explicit RETURN columns and LIMIT; help(topic=cypher) provides recipes."},
                    "dialect": {"type": "string", "enum": ["graphite", "opencypher-m23"], "default": "graphite", "description": "Graphite extensions or supported openCypher M23 compatibility; not arbitrary Neo4j/APOC."},
                    "graph_id": graph_id_property("Explicit single graph id; mutually exclusive with graphs and all_graphs"),
                    "all_graphs": {"type": "boolean", "default": false, "description": "Select all graphs explicitly; exclusive with graph_id or non-empty graphs. Required for fanout without graphs."},
                    "graphs": {"type": "array", "items": {"type": "string"}, "description": "Unique loaded graph IDs; non-empty and exclusive with graph_id or all_graphs=true."},
                    "mode": {"type": "string", "enum": ["cross-graph", "fanout"], "default": "cross-graph", "description": "cross-graph: global joins/aggregation; fanout: independent queries. Set mode with graphs or all_graphs=true."},
                    "limit": {"type": "number", "description": "Total row cap: default 1000, maximum 5000; total.relation=gte indicates a lower bound."},
                    "timeout_ms": {"type": "integer", "exclusiveMinimum": 0, "description": "Positive timeout in milliseconds, capped by server maximum (default 60000); narrow queries before retrying."},
                    "per_graph_limit": {"type": "number", "description": "Per-graph row cap; fanout only, with graphs or all_graphs=true."},
                    "include_graph_rows": {"type": "boolean", "default": false, "description": "Include per-graph row arrays; true requires fanout with graphs or all_graphs=true."}
                }),
                &["query"],
            ),
        },
        ToolDef {
            name: "node",
            description: r#"Inspect one stored node by its graph-local numeric ID and graph_id. Use cypher for raw properties or virtual Method declarations, and incoming/outgoing for edges."#,
            input_schema: schema(
                json!({
                    "id": {"type": "number", "description": "Graph-local numeric stored-node ID; not a virtual Method or qualified ID."},
                    "graph_id": graph_id_property("Graph owning this local node ID")
                }),
                &["id", "graph_id"],
            ),
        },
        ToolDef {
            name: "outgoing",
            description: r#"Inspect edges leaving a stored node (up to 200); DATAFLOW points toward uses. For function callees, query CallSiteNode.caller_signature with cypher."#,
            input_schema: schema(
                json!({
                    "id": {"type": "number", "description": "Graph-local numeric stored-node ID; not a virtual Method or qualified ID."},
                    "graph_id": graph_id_property("Graph owning this local node ID")
                }),
                &["id", "graph_id"],
            ),
        },
        ToolDef {
            name: "incoming",
            description: r#"Inspect edges entering a stored node (up to 200); incoming DATAFLOW traces value origins. For function callers, query CallSiteNode.callee_signature with cypher."#,
            input_schema: schema(
                json!({
                    "id": {"type": "number", "description": "Graph-local numeric stored-node ID; not a virtual Method or qualified ID."},
                    "graph_id": graph_id_property("Graph owning this local node ID")
                }),
                &["id", "graph_id"],
            ),
        },
        ToolDef {
            name: "annotations",
            description: r#"Inspect emitted annotations for an exact class/type member; use member_name="<class>" for type annotations. Omit graph_id for results grouped by graph; availability depends on the frontend."#,
            input_schema: schema(
                json!({
                    "graph_id": graph_id_property("Explicit graph id; omit to query all graphs"),
                    "class_name": {"type": "string", "description": "Exact class/type owner discovered in the graph"},
                    "member_name": {"type": "string", "description": "Exact method/field name or <class>; not a full signature."}
                }),
                &["class_name", "member_name"],
            ),
        },
        ToolDef {
            name: "endpoints",
            description: r#"Find Spring Web HTTP endpoints with handler signatures from mapping annotations. Omit graph_id for results grouped by graph; use cypher for other frameworks and entry points."#,
            input_schema: schema(
                json!({
                    "graph_id": graph_id_property("Explicit graph id; omit to query all graphs"),
                    "class_name": {"type": "string", "description": "Exact Spring controller class, not a prefix/glob"},
                    "limit": {"type": "number", "default": 200, "description": "Max endpoints to return"}
                }),
                &[],
            ),
        },
        ToolDef {
            name: "resources",
            description: r#"List persisted resource paths, optionally filtered by glob, then read an exact path with resource. Omit graph_id for results grouped by graph; requires a frontend-provided resource store."#,
            input_schema: schema(
                json!({
                    "graph_id": graph_id_property("Explicit graph id; omit to query all graphs"),
                    "pattern": {"type": "string", "description": "Path glob (default **), e.g. **/*.properties; not regex."},
                    "limit": {"type": "number", "default": 100, "description": "Max results"}
                }),
                &[],
            ),
        },
        ToolDef {
            name: "resource",
            description: r#"Read an exact persisted resource path as UTF-8 text, within a 1 MiB content budget. Omit graph_id to read matches grouped by graph; content reflects analyzed inputs, not runtime overrides."#,
            input_schema: schema(
                json!({
                    "graph_id": graph_id_property("Explicit graph id; omit to query every graph"),
                    "path": {"type": "string", "description": "Exact path returned by resources; not a host path or URL."}
                }),
                &["path"],
            ),
        },
        ToolDef {
            name: "subgraph",
            description: r#"Inspect a bounded bidirectional neighborhood (at most 2,000 nodes/5,000 edges). Supply graph_id with the graph-local center: omitting it looks up the same numeric ID independently in every graph."#,
            input_schema: schema(
                json!({
                    "graph_id": graph_id_property("Explicit graph id; omit to query every graph"),
                    "center": {"type": "number", "description": "Graph-local numeric center ID; normally supply graph_id."},
                    "depth": {"type": "number", "default": 2, "description": "Bidirectional hop depth: default 2, maximum 4"}
                }),
                &["center"],
            ),
        },
        ToolDef {
            name: "overview",
            description: r#"Explore dependencies grouped by class/type/module owners, limited to the requested class view. Omit graph_id for views grouped by graph; substantiate dependencies with cypher call sites."#,
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
            description: r#"Generate inferred C4 architecture views as JSON or diagram text. Omit graph_id for all graphs; confirm deployment interpretations against call sites and configuration."#,
            input_schema: schema(
                json!({
                    "graph_id": graph_id_property("Explicit graph id; omit to query all graphs"),
                    "level": {"type": "string", "enum": ["context", "container", "component", "all"], "default": "all", "description": "context: subject/external systems; container: runtime groupings; component: internal structure; all: all three views."},
                    "format": {"type": "string", "enum": ["json", "dsl", "mermaid", "plantuml"], "default": "json", "description": "json for structured inspection; dsl for Structurizr; mermaid or plantuml for diagram text."},
                    "limit": {"type": "number", "default": 200, "description": "Compatibility option; currently ignored by C4 renderer."}
                }),
                &[],
            ),
        },
        ToolDef {
            name: "schema",
            description: r#"Discover labels, properties, virtual Method metadata, relationship types and frequent edge patterns before writing Cypher. Omit graph_id for schemas grouped by graphId; limit caps only patterns. See help(topic="schema") for property and frontend caveats."#,
            input_schema: schema(
                json!({
                    "graph_id": graph_id_property("Explicit graph id; omit to describe all graphs"),
                    "limit": {"type": "integer", "minimum": 0, "maximum": 1000, "default": 50, "description": "Maximum number of (labels)-[type]->(labels) patterns per graph, most frequent first"}
                }),
                &[],
            ),
        },
        ToolDef {
            name: "help",
            description: "Get on-demand investigation workflows, query recipes and frontend caveats. Omit topic for the workflow, or pass an analysis tool name.",
            input_schema: schema(
                json!({"topic": {"type": "string", "default": "workflow", "enum": std::iter::once("workflow").chain(TOOL_HELP.iter().map(|(name, _)| *name)).collect::<Vec<_>>(), "description": "workflow or the analysis tool to learn about"}}),
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
    let dialect = a.string("dialect")?;
    if let Some(dialect) = &dialect {
        dialect.parse::<graphite_cypher::CypherDialect>()?;
    }
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
        if let Some(dialect) = &dialect {
            call = call.param("dialect", dialect.clone());
        }
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
    /// The server state whose request activity every handled message counts toward,
    /// so MCP control traffic (a `ping`, `initialize`, `tools/list`) keeps the C4
    /// warm-up's idle gate closed like any API request. `None` in tests.
    activity: Option<Arc<crate::routes::AppState>>,
}

/// The text of a tool result, or the error text of a failed one.
enum ToolOutcome {
    Ok(String),
    Err(String),
}

impl McpServer {
    pub fn new(api: Router) -> Self {
        McpServer {
            api,
            metrics: None,
            activity: None,
        }
    }

    /// A server that records every request and tool call into `metrics`.
    pub fn with_metrics(api: Router, metrics: Arc<McpMetrics>) -> Self {
        McpServer {
            api,
            metrics: Some(metrics),
            activity: None,
        }
    }

    /// Count every message this server handles as request activity on `state`.
    pub fn with_activity(mut self, state: Arc<crate::routes::AppState>) -> Self {
        self.activity = Some(state);
        self
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
        let _in_flight = self
            .activity
            .as_ref()
            .map(|state| crate::routes::InFlight::enter(state.clone()));
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
        if !(is_notification || id.is_string() || id.is_number()) {
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
        let outcome = if tool == "help" {
            match help_text(arguments) {
                Ok(text) => ToolOutcome::Ok(text.to_string()),
                Err(message) => ToolOutcome::Err(message),
            }
        } else {
            match plan(tool, arguments) {
                Ok(call) => self.dispatch(&call).await,
                Err(message) => ToolOutcome::Err(message),
            }
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
        // Counted from the first byte, so a line that fails to parse is a request too.
        let _in_flight = self
            .activity
            .as_ref()
            .map(|state| crate::routes::InFlight::enter(state.clone()));
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
    activity: Option<Arc<crate::routes::AppState>>,
) -> Router {
    let server = match metrics {
        Some(m) => McpServer::with_metrics(api.clone(), m),
        None => McpServer::new(api.clone()),
    };
    let mut endpoint = post(mcp_post).get(mcp_get).delete(mcp_delete);
    let server = Arc::new(match activity {
        Some(state) => {
            // Every request to the endpoint counts, whatever becomes of it: an origin
            // or version refusal, a body that fails to parse, a GET or DELETE.
            endpoint = endpoint.layer(axum::middleware::from_fn_with_state(
                state.clone(),
                crate::routes::note_request,
            ));
            server.with_activity(state)
        }
        None => server,
    });
    api.route("/mcp", endpoint)
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
    report_c4_warmup(opened.state.warm_c4());
    eprintln!("MCP server ready on stdio ({} tools)", tools().len());
    let runtime = runtime(cli.max_concurrent_cypher)?;
    runtime.block_on(async move {
        let server = Arc::new(
            McpServer::new(crate::routes::router(opened.state.clone()))
                .with_activity(opened.state.clone()),
        );
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
    fn cypher_dialect_is_validated_and_forwarded_for_every_graph_scope() {
        for scope in [
            vec![],
            vec![("graph_id", json!("one"))],
            vec![("graphs", json!(["a", "b"]))],
            vec![("all_graphs", json!(true)), ("mode", json!("fanout"))],
        ] {
            let mut values = vec![
                ("query", json!("RETURN 1")),
                ("dialect", json!("opencypher-m23")),
            ];
            values.extend(scope);
            let call = plan("cypher", &args(&values)).unwrap();
            assert!(call.uri().contains("dialect=opencypher-m23"));
        }
        for value in [json!("unknown"), json!(false)] {
            assert!(plan(
                "cypher",
                &args(&[("query", json!("RETURN 1")), ("dialect", value)])
            )
            .is_err());
        }
    }

    #[test]
    fn analysis_tools_and_help_have_stable_order_and_schemas() {
        let names: Vec<&str> = tools().iter().map(|t| t.name).collect();
        assert_eq!(
            names,
            [
                "graphs",
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
                "schema",
                "help"
            ]
        );
        for tool in tools() {
            assert_eq!(tool.input_schema["type"], "object", "{}", tool.name);
            assert_eq!(tool.input_schema["additionalProperties"], false);
        }
        assert_eq!(tools()[1].input_schema["required"], json!(["query"]));
        assert_eq!(
            tools()[11].input_schema["properties"]["level"]["default"],
            "all"
        );
        assert!(tools()[12].input_schema.get("required").is_none());
        assert!(tools()[1].description.contains("Call schema first"));
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
            tools()[12].input_schema["properties"]["limit"]["type"],
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
        assert_eq!(
            plan("openapi", &args(&[])).unwrap_err(),
            "Tool openapi not found"
        );
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

    #[tokio::test]
    async fn discovery_stays_lean_while_every_help_topic_is_available_without_graphs() {
        // No REST routes: guidance must not depend on an HTTP endpoint or a loaded graph.
        let server = McpServer::new(Router::new());
        let list = server
            .handle(&json!({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}))
            .await
            .unwrap();
        let bytes = serde_json::to_vec(&list).unwrap().len();
        assert!(
            bytes <= 11_000,
            "Discovery metadata grew to {bytes} bytes; keep recipes in help"
        );
        eprintln!("tools/list serialized response: {bytes} bytes");
        let topics = list["result"]["tools"]
            .as_array()
            .unwrap()
            .iter()
            .find(|tool| tool["name"] == "help")
            .unwrap()["inputSchema"]["properties"]["topic"]["enum"]
            .as_array()
            .unwrap();
        assert_eq!(topics.len(), tools().len()); // workflow plus every analysis tool
        for topic in topics {
            let response = server
                .handle(&json!({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                "params": {"name": "help", "arguments": {"topic": topic}}}))
                .await
                .unwrap();
            assert!(response["error"].is_null(), "{response}");
            assert!(response["result"]["isError"].is_null(), "{response}");
            let text = response["result"]["content"][0]["text"].as_str().unwrap();
            if topic == "workflow" {
                assert_eq!(text, WORKFLOW_HELP);
            } else {
                let tool = tools()
                    .into_iter()
                    .find(|tool| tool.name == topic.as_str().unwrap())
                    .unwrap();
                assert!(
                    text.len() > tool.description.len(),
                    "Missing detailed guidance for {}",
                    tool.name
                );
            }
        }
        let recipes = help_text(&args(&[("topic", json!("cypher"))])).unwrap();
        assert!(recipes.contains("MATCH (m:Method) RETURN m.class, m.name, m.signature LIMIT 20"));
        assert!(recipes.contains("DATAFLOW*1..6"));
        for frontend in ["Java/Kotlin/Android", "JS/TS/ES", "Swift/Apple"] {
            assert!(recipes.contains(frontend));
        }
    }

    #[tokio::test]
    async fn local_help_preserves_tool_errors_and_metric_accounting() {
        let metrics = Arc::new(McpMetrics::default());
        let server = McpServer::with_metrics(Router::new(), metrics.clone());
        let call = |arguments| {
            json!({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": {"name": "help", "arguments": arguments}})
        };
        let default = server.handle(&call(json!({}))).await.unwrap();
        assert_eq!(default["result"]["content"][0]["text"], WORKFLOW_HELP);
        for arguments in [json!({"topic": "unknown"}), json!({"topic": 7})] {
            let invalid = server.handle(&call(arguments)).await.unwrap();
            assert!(invalid["error"].is_null());
            assert_eq!(invalid["result"]["isError"], true);
            assert!(!invalid["result"]["content"][0]["text"]
                .as_str()
                .unwrap()
                .is_empty());
        }
        let invalid_shape = server.handle(&call(json!([]))).await.unwrap();
        assert_eq!(invalid_shape["error"]["code"], INVALID_PARAMS);
        let series = metrics.tools();
        assert_eq!(series.len(), 2);
        for (key, stats) in series {
            assert_eq!(key.tool, "help");
            assert_eq!(stats.count, if key.ok { 1 } else { 2 });
        }
    }

    #[tokio::test]
    async fn help_has_identical_http_and_stdio_responses() {
        let server = McpServer::new(Router::new());
        let app = with_mcp_route(server.api.clone(), OriginPolicy::default(), None, None);
        for arguments in [
            json!({}),
            json!({"topic": "cypher"}),
            json!({"topic": "unknown"}),
        ] {
            let request = json!({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                "params": {"name": "help", "arguments": arguments}})
            .to_string();
            let stdio: Value =
                serde_json::from_str(&server.handle_text(&request).await.unwrap()).unwrap();
            let response = app
                .clone()
                .oneshot(
                    Request::builder()
                        .method(Method::POST)
                        .uri("/mcp")
                        .header(header::CONTENT_TYPE, "application/json")
                        .body(Body::from(request))
                        .unwrap(),
                )
                .await
                .unwrap();
            assert_eq!(response.status(), StatusCode::OK);
            let http: Value =
                serde_json::from_slice(&to_bytes(response.into_body(), usize::MAX).await.unwrap())
                    .unwrap();
            assert_eq!(http, stdio);
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn openapi_is_http_only() {
        let (server, root) = empty_server();
        let rejected = server
            .handle(&json!({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                "params": {"name": "openapi", "arguments": {}}}))
            .await
            .unwrap();
        assert_eq!(rejected["error"]["code"], INVALID_PARAMS);
        assert_eq!(rejected["error"]["message"], "Tool openapi not found");

        let response = server
            .api
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/openapi.json")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let document: Value =
            serde_json::from_slice(&to_bytes(response.into_body(), usize::MAX).await.unwrap())
                .unwrap();
        assert!(document["openapi"].as_str().unwrap().starts_with("3."));
        assert!(document["paths"]["/api/graphs"]["get"].is_object());
        std::fs::remove_dir_all(root).unwrap();
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
        assert!(list["result"]["tools"][1]["inputSchema"]["properties"]["query"].is_object());

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
        let app = with_mcp_route(server.api.clone(), OriginPolicy::default(), None, None);
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

    /// A message the router never sees (a `ping` over stdio or `/mcp`) still counts as
    /// request activity, so MCP control traffic keeps the C4 warm-up waiting.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn every_mcp_message_counts_as_request_activity() {
        let (state, root) = empty_state(false);
        let server = McpServer::new(router(state.clone())).with_activity(state.clone());
        tokio::time::sleep(std::time::Duration::from_millis(30)).await;
        assert!(state.idle_for() >= std::time::Duration::from_millis(30));
        let pong = server
            .handle_text(r#"{"jsonrpc":"2.0","id":7,"method":"ping"}"#)
            .await
            .unwrap();
        assert!(pong.contains(r#""id":7"#), "{pong}");
        assert!(state.idle_for() < std::time::Duration::from_millis(30));
        // And one over the HTTP endpoint, mounted with the same activity.
        let app = with_mcp_route(
            router(state.clone()),
            OriginPolicy::default(),
            None,
            Some(state.clone()),
        );
        tokio::time::sleep(std::time::Duration::from_millis(30)).await;
        assert!(state.idle_for() >= std::time::Duration::from_millis(30));
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .method(Method::POST)
                    .uri("/mcp")
                    .header(header::CONTENT_TYPE, "application/json")
                    .header(header::ACCEPT, "application/json, text/event-stream")
                    .body(Body::from(r#"{"jsonrpc":"2.0","id":8,"method":"ping"}"#))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert!(state.idle_for() < std::time::Duration::from_millis(30));
        // Refused or unparseable traffic is traffic: a line that fails to parse over
        // stdio, a GET the endpoint does not serve, a POST whose body is not JSON.
        tokio::time::sleep(std::time::Duration::from_millis(30)).await;
        assert!(state.idle_for() >= std::time::Duration::from_millis(30));
        assert!(server
            .handle_text("not json")
            .await
            .unwrap()
            .contains("Parse error"));
        assert!(state.idle_for() < std::time::Duration::from_millis(30));
        for (method, body) in [(Method::GET, ""), (Method::POST, "nope")] {
            tokio::time::sleep(std::time::Duration::from_millis(30)).await;
            assert!(state.idle_for() >= std::time::Duration::from_millis(30));
            let response = app
                .clone()
                .oneshot(
                    Request::builder()
                        .method(method.clone())
                        .uri("/mcp")
                        .header(header::CONTENT_TYPE, "application/json")
                        .header(header::ACCEPT, "application/json, text/event-stream")
                        .body(Body::from(body))
                        .unwrap(),
                )
                .await
                .unwrap();
            assert!(!response.status().is_success(), "{method} was refused");
            assert!(state.idle_for() < std::time::Duration::from_millis(30));
        }
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
                None,
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

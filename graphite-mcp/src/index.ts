#!/usr/bin/env node

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import packageMetadata from "../package.json";

const GRAPHITE_URL = process.env.GRAPHITE_URL || "http://localhost:8080";

async function graphiteGet(path: string, params?: Record<string, string>): Promise<unknown> {
  const url = new URL(path, GRAPHITE_URL);
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      if (v) url.searchParams.set(k, v);
    }
  }
  const res = await fetch(url.toString());
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`${res.status} ${res.statusText}: ${text}`);
  }
  return res.json();
}

async function graphiteGetText(path: string, params?: Record<string, string>): Promise<string> {
  const url = new URL(path, GRAPHITE_URL);
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      if (v) url.searchParams.set(k, v);
    }
  }
  const res = await fetch(url.toString());
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`${res.status} ${res.statusText}: ${text}`);
  }
  return res.text();
}

async function graphitePost(path: string, body: unknown, params?: Record<string, string>): Promise<unknown> {
  const url = new URL(path, GRAPHITE_URL);
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      if (v) url.searchParams.set(k, v);
    }
  }
  const res = await fetch(url.toString(), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`${res.status} ${res.statusText}: ${text}`);
  }
  return res.json();
}

function encodeResourcePath(path: string): string {
  return path
    .split("/")
    .filter((part) => part.length > 0)
    .map((part) => encodeURIComponent(part))
    .join("/");
}

function graphApiPath(graphId: string | undefined, path: string): string {
  const suffix = path.startsWith("/") ? path : `/${path}`;
  return graphId
    ? `/api/graphs/${encodeURIComponent(graphId)}${suffix}`
    : `/api${suffix}`;
}

const server = new McpServer({
  name: "graphite",
  version: packageMetadata.version,
});

// Graph registry and cached statistics
server.tool("graphs", "List all graphs with aggregate statistics, or get one graph by id", {
  graph_id: z.string().optional().describe("Explicit graph id; omit to list all graphs and totals"),
}, async ({ graph_id }) => {
  const data = await graphiteGet(
    graph_id ? `/api/graphs/${encodeURIComponent(graph_id)}` : "/api/graphs"
  );
  return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
});

// Explore API discovery
server.tool(
  "openapi",
  "Fetch the machine-readable OpenAPI document for the explore server",
  {},
  async () => {
    const data = await graphiteGet("/openapi.json");
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// Cypher query
server.tool(
  "cypher",
  "Execute a true cross-graph Cypher query across all graphs by default, one explicit graph with graph_id, or an explicit graph set with graphs/all_graphs",
  {
    query: z.string().describe("Cypher query string"),
    graph_id: z.string().optional()
      .describe("Explicit single graph id; mutually exclusive with graphs and all_graphs"),
    all_graphs: z.boolean().optional().default(false)
      .describe("Explicitly select all loaded graphs for /api/cypher/graphs"),
    graphs: z.array(z.string()).optional()
      .describe("Explicit graph ids for /api/cypher/graphs"),
    mode: z.enum(["cross-graph", "fanout"]).optional().default("cross-graph")
      .describe("One union query across graphs, or independent per-graph fan-out"),
    limit: z.number().optional()
      .describe("Maximum total result rows for the request"),
    timeout_ms: z.number().int().positive().optional()
      .describe("Client timeout in milliseconds, capped by the server maximum (60 seconds by default)"),
    per_graph_limit: z.number().optional()
      .describe("Optional maximum result rows per graph for multi-graph queries"),
    include_graph_rows: z.boolean().optional().default(false)
      .describe("Include duplicate per-graph row arrays in multi-graph responses"),
  },
  async ({ query, graph_id, all_graphs, graphs, mode, limit, timeout_ms, per_graph_limit, include_graph_rows }) => {
    const selectedGraphs = graphs?.filter((graph: string) => graph.trim().length > 0);
    if (graph_id && (all_graphs || (selectedGraphs && selectedGraphs.length > 0))) {
      throw new Error("graph_id is mutually exclusive with all_graphs and graphs");
    }
    if (all_graphs && selectedGraphs && selectedGraphs.length > 0) {
      throw new Error("all_graphs and graphs are mutually exclusive");
    }
    const hasExplicitGraphSet = all_graphs || Boolean(selectedGraphs && selectedGraphs.length > 0);
    if (graph_id && mode !== "cross-graph") {
      throw new Error("mode is only valid with graphs or all_graphs");
    }
    if (!hasExplicitGraphSet && !graph_id && mode === "fanout") {
      throw new Error("fanout mode requires graphs or all_graphs=true");
    }
    if (mode === "cross-graph" && (per_graph_limit !== undefined || include_graph_rows)) {
      throw new Error("per_graph_limit and include_graph_rows are only valid in fanout mode");
    }
    const queryParams: Record<string, string> = {};
    if (limit !== undefined) queryParams.limit = String(limit);
    if (timeout_ms !== undefined) queryParams.timeoutMs = String(timeout_ms);
    if (per_graph_limit !== undefined) queryParams.perGraphLimit = String(per_graph_limit);
    if (include_graph_rows) queryParams.includeGraphRows = "true";
    if (graph_id) {
      const data = await graphitePost(graphApiPath(graph_id, "/cypher"), { query }, queryParams);
      return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
    }
    const body: Record<string, unknown> = { query, mode };
    if (selectedGraphs && selectedGraphs.length > 0) {
      body.graphs = selectedGraphs;
    }
    if (all_graphs) body.allGraphs = true;
    if (all_graphs || (selectedGraphs && selectedGraphs.length > 0)) {
      const data = await graphitePost("/api/cypher/graphs", body, queryParams);
      return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
    }
    const data = await graphitePost("/api/cypher", { query }, queryParams);
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// Get node by ID
server.tool(
  "node",
  "Get every node with a local ID across all graphs, grouped by graph, or the node in one explicit graph",
  {
    id: z.number().describe("Graph-local node ID"),
    graph_id: z.string().optional().describe("Explicit graph id; omit to query every graph"),
  },
  async ({ id, graph_id }) => {
    const data = await graphiteGet(graphApiPath(graph_id, `/node/${id}`));
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// Outgoing edges
server.tool(
  "outgoing",
  "Get outgoing edges for a graph-local node ID across all graphs, or in one explicit graph",
  {
    id: z.number().describe("Graph-local node ID"),
    graph_id: z.string().optional().describe("Explicit graph id; omit to query every graph"),
  },
  async ({ id, graph_id }) => {
    const data = await graphiteGet(graphApiPath(graph_id, `/node/${id}/outgoing`));
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// Incoming edges
server.tool(
  "incoming",
  "Get incoming edges for a graph-local node ID across all graphs, or in one explicit graph",
  {
    id: z.number().describe("Graph-local node ID"),
    graph_id: z.string().optional().describe("Explicit graph id; omit to query every graph"),
  },
  async ({ id, graph_id }) => {
    const data = await graphiteGet(graphApiPath(graph_id, `/node/${id}/incoming`));
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// Get annotations
server.tool(
  "annotations",
  "Get annotations for a class member across all graphs, grouped by graph, or in one explicit graph",
  {
    graph_id: z.string().optional().describe("Explicit graph id; omit to query all graphs"),
    class_name: z.string().describe("Fully qualified class name"),
    member_name: z.string().describe("Method or field name"),
  },
  async ({ graph_id, class_name, member_name }) => {
    const data = await graphiteGet(graphApiPath(graph_id, "/annotations"), {
      class: class_name,
      member: member_name,
    });
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// Extract framework API endpoints
server.tool(
  "endpoints",
  "Extract framework API endpoints across all graphs, grouped by graph, or from one explicit graph",
  {
    graph_id: z.string().optional().describe("Explicit graph id; omit to query all graphs"),
    class_name: z.string().optional().describe("Optional controller class filter"),
    limit: z.number().optional().default(200).describe("Max endpoints to return"),
  },
  async ({ graph_id, class_name, limit }) => {
    const params: Record<string, string> = {};
    if (class_name) params.class = class_name;
    if (limit) params.limit = String(limit);
    const data = await graphiteGet(graphApiPath(graph_id, "/endpoints"), params);
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// List resources
server.tool(
  "resources",
  "List persisted resources across all graphs, grouped by graph, or in one explicit graph",
  {
    graph_id: z.string().optional().describe("Explicit graph id; omit to query all graphs"),
    pattern: z.string().optional().describe("Glob pattern filter, defaults to **"),
    limit: z.number().optional().default(100).describe("Max results"),
  },
  async ({ graph_id, pattern, limit }) => {
    const params: Record<string, string> = {};
    if (pattern) params.pattern = pattern;
    if (limit) params.limit = String(limit);
    const data = await graphiteGet(graphApiPath(graph_id, "/resources"), params);
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// Read raw resource content
server.tool(
  "resource",
  "Read every matching resource path grouped by graph, or read it from one explicit graph",
  {
    graph_id: z.string().optional().describe("Explicit graph id; omit to query every graph"),
    path: z.string().describe("Resource path inside the saved graph"),
  },
  async ({ graph_id, path }) => {
    const encodedPath = encodeResourcePath(path);
    const data = await graphiteGet(graphApiPath(graph_id, `/resources/${encodedPath}`));
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// Subgraph
server.tool(
  "subgraph",
  "Get subgraphs for a graph-local center ID across all graphs, or from one explicit graph",
  {
    graph_id: z.string().optional().describe("Explicit graph id; omit to query every graph"),
    center: z.number().describe("Center node ID"),
    depth: z.number().optional().default(2).describe("Traversal depth"),
  },
  async ({ graph_id, center, depth }) => {
    const data = await graphiteGet(graphApiPath(graph_id, "/subgraph"), {
      center: String(center),
      depth: String(depth),
    });
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// Overview
server.tool(
  "overview",
  "Get class-level dependency overviews across all graphs, grouped by graph, or for one explicit graph",
  {
    graph_id: z.string().optional().describe("Explicit graph id; omit to query all graphs"),
    limit: z.number().optional().default(200).describe("Max classes"),
  },
  async ({ graph_id, limit }) => {
    const data = await graphiteGet(graphApiPath(graph_id, "/overview"), { limit: String(limit) });
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// C4 architecture model
server.tool(
  "c4",
  "Get C4 views for all graphs, grouped by graph for JSON, or for one explicit graph",
  {
    graph_id: z.string().optional().describe("Explicit graph id; omit to query all graphs"),
    level: z.enum(["context", "container", "component", "all"]).optional().default("all")
      .describe("C4 view level"),
    format: z.enum(["json", "dsl", "mermaid", "plantuml"]).optional().default("json")
      .describe("Output format"),
    limit: z.number().optional().default(200).describe("Max containers or components"),
  },
  async ({ graph_id, level, format, limit }) => {
    const path = graphApiPath(graph_id, "/architecture/c4");
    if (format === "mermaid" || format === "plantuml" || format === "dsl") {
      const text = await graphiteGetText(path, {
        level,
        format,
        limit: String(limit),
      });
      return { content: [{ type: "text", text }] };
    }
    const data = await graphiteGet(path, {
      level,
      format,
      limit: String(limit),
    });
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});

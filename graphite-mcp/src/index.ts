#!/usr/bin/env node

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

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

async function graphitePost(path: string, body: unknown): Promise<unknown> {
  const url = new URL(path, GRAPHITE_URL);
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

const server = new McpServer({
  name: "graphite",
  version: "1.0.0",
});

// Graph info
server.tool("info", "Get graph statistics (node count, edge count, methods, call sites)", {}, async () => {
  const data = await graphiteGet("/api/info");
  return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
});

// Cypher query
server.tool(
  "cypher",
  "Execute a Cypher query against the graph",
  { query: z.string().describe("Cypher query string") },
  async ({ query }) => {
    const data = await graphitePost("/api/cypher", { query });
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// List nodes
server.tool(
  "nodes",
  "List nodes by type",
  {
    type: z.string().optional().describe("Node type filter (e.g., CallSiteNode, IntConstant, Annotation)"),
    limit: z.number().optional().default(50).describe("Max results"),
  },
  async ({ type, limit }) => {
    const params: Record<string, string> = {};
    if (type) params.type = type;
    if (limit) params.limit = String(limit);
    const data = await graphiteGet("/api/nodes", params);
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// Get node by ID
server.tool(
  "node",
  "Get a single node by ID",
  { id: z.number().describe("Node ID") },
  async ({ id }) => {
    const data = await graphiteGet(`/api/node/${id}`);
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// Outgoing edges
server.tool(
  "outgoing",
  "Get outgoing edges from a node",
  { id: z.number().describe("Node ID") },
  async ({ id }) => {
    const data = await graphiteGet(`/api/node/${id}/outgoing`);
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// Incoming edges
server.tool(
  "incoming",
  "Get incoming edges to a node",
  { id: z.number().describe("Node ID") },
  async ({ id }) => {
    const data = await graphiteGet(`/api/node/${id}/incoming`);
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// Find call sites
server.tool(
  "call_sites",
  "Find call sites matching a pattern",
  {
    class_pattern: z.string().optional().describe("Class name pattern"),
    method_pattern: z.string().optional().describe("Method name pattern"),
    limit: z.number().optional().default(50).describe("Max results"),
  },
  async ({ class_pattern, method_pattern, limit }) => {
    const params: Record<string, string> = {};
    if (class_pattern) params.class = class_pattern;
    if (method_pattern) params.method = method_pattern;
    if (limit) params.limit = String(limit);
    const data = await graphiteGet("/api/call-sites", params);
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// List methods
server.tool(
  "methods",
  "List methods matching a pattern",
  {
    class_pattern: z.string().optional().describe("Class name pattern"),
    name_pattern: z.string().optional().describe("Method name pattern"),
    limit: z.number().optional().default(50).describe("Max results"),
  },
  async ({ class_pattern, name_pattern, limit }) => {
    const params: Record<string, string> = {};
    if (class_pattern) params.class = class_pattern;
    if (name_pattern) params.name = name_pattern;
    if (limit) params.limit = String(limit);
    const data = await graphiteGet("/api/methods", params);
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// Get annotations
server.tool(
  "annotations",
  "Get annotations for a class member",
  {
    class_name: z.string().describe("Fully qualified class name"),
    member_name: z.string().describe("Method or field name"),
  },
  async ({ class_name, member_name }) => {
    const data = await graphiteGet("/api/annotations", { class: class_name, member: member_name });
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// Subgraph
server.tool(
  "subgraph",
  "Get subgraph around a center node",
  {
    center: z.number().describe("Center node ID"),
    depth: z.number().optional().default(2).describe("Traversal depth"),
  },
  async ({ center, depth }) => {
    const data = await graphiteGet("/api/subgraph", { center: String(center), depth: String(depth) });
    return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
  }
);

// Overview
server.tool(
  "overview",
  "Get class-level dependency graph overview",
  { limit: z.number().optional().default(200).describe("Max classes") },
  async ({ limit }) => {
    const data = await graphiteGet("/api/overview", { limit: String(limit) });
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

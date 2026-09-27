# Data flow

`DATAFLOW` edges point from where a value comes from to where it is used:

| kind | from → to |
|---|---|
| `ASSIGN` | value → local; parameter → its local; receiver object → call site |
| `PARAMETER_PASS` | argument → call site |
| `RETURN_VALUE` | call site → local receiving the result; returned value → `ReturnNode` |
| `FIELD_STORE` / `FIELD_LOAD` | value → `FieldNode`; `FieldNode` → local |
| `ARRAY_STORE` / `ARRAY_LOAD`, `CAST`, `PHI` | as named |

Within a method, and through fields, `DATAFLOW*` paths connect directly. Across a
method call you must join (see "Crossing method boundaries").

## Backward: what values reach this call?

```cypher
MATCH (c:Constant)-[:DATAFLOW*1..8]->(cs:CallSiteNode)
WHERE cs.callee_class = 'com.example.PaymentGateway' AND cs.callee_name = 'charge'
RETURN DISTINCT c.value, labels(c)[0] AS type, cs.caller_signature
```

This finds literals reaching any argument (and the receiver). Replace
`Constant` by `StringConstant`, `IntConstant`, ... to narrow. Enum values and
non-constant static fields are not `Constant`s; they arrive as static
`FieldNode`s. To include them, add a second half
`MATCH (key:FieldNode)-[:DATAFLOW*1..8]->(cs) WHERE key.static ...` with
`UNION` (see `feature-flags.md` step 2); do not start from an unlabeled node.

Show the path, not just the endpoints:

```cypher
MATCH p = (c:StringConstant {value: 'checkout.new_flow'})-[:DATAFLOW*1..6]->(n)
RETURN [x IN nodes(p) | coalesce(x.callee_name, x.name, toString(x.value))] AS path,
       [r IN relationships(p) | r.kind] AS kinds
```

## Forward: where does this value go?

```cypher
// Where does the result of System.getenv flow inside its method?
MATCH (src:CallSiteNode {callee_class: 'java.lang.System', callee_name: 'getenv'})
      -[:DATAFLOW*1..5]->(n)
RETURN DISTINCT src.caller_signature, labels(n)[0] AS kind,
       coalesce(n.callee_signature, n.class + '.' + n.name, n.method, n.name) AS where
```

Reaching a `ReturnNode` means the value leaves via the method's return; reaching a
`FieldNode` means it is stored in state; both need a hop (below) to continue.

## Crossing method boundaries

Dataflow ends at a call site's `PARAMETER_PASS` and does not enter the callee.
The callee's `ParameterNode` and `ReturnNode` carry the method signature, so join:

```cypher
// Into the callee: argument -> callee parameter -> where the callee stores it
MATCH (cs:CallSiteNode {callee_class: 'com.example.PaymentGateway', callee_name: '<init>'})
MATCH (p:ParameterNode) WHERE p.method = cs.callee_signature
MATCH (p)-[:DATAFLOW*1..3]->(f:FieldNode)
RETURN cs.caller_signature, p.index, f.class + '.' + f.name AS field
```

```cypher
// Out of the callee: value -> callee return -> call sites of that method -> next sink
MATCH (src:CallSiteNode {callee_name: 'getenv'})-[:DATAFLOW*1..4]->(ret:ReturnNode)
MATCH (use:CallSiteNode) WHERE use.callee_signature = ret.method
MATCH (use)-[:DATAFLOW*1..4]->(sink:CallSiteNode)
RETURN DISTINCT ret.method AS via, use.caller_signature AS in_method,
       sink.callee_signature AS sink
```

The call-site edge does not say which argument position feeds which parameter.
When a call has several arguments, match `p.index` against `p.type` and the
argument's type, or confirm in source.

## Through fields (state shared across methods)

Fields connect writers and readers across methods and classes without a join:

```cypher
MATCH (f:FieldNode {class: 'com.example.PaymentGateway', name: 'endpoint'})
OPTIONAL MATCH (w)-[:DATAFLOW {kind: 'FIELD_STORE'}]->(f)
OPTIONAL MATCH (f)-[:DATAFLOW*1..4]->(use:CallSiteNode)
RETURN collect(DISTINCT w.method) AS writers,
       collect(DISTINCT use.caller_signature + ' -> ' + use.callee_signature) AS uses
```

If the inline `{kind: ...}` map is not accepted on relationships, use
`-[e:DATAFLOW]->` with `WHERE e.kind = 'FIELD_STORE'`.

## Kotlin API for full slices

When the question needs a complete interprocedural slice (all constants reaching
an argument through any number of calls), the Kotlin API does the method hops for
you:

```kotlin
val graph = GraphStore.load(Path.of("app-graph"))   // `graphite unpack app.graphite app-graph`
val slice = DataFlowAnalysis(graph, AnalysisConfig(maxDepth = 50, interProcedural = true))
    .backwardSlice(NodeId(callSiteId))
slice.constants()
```

and `Graphite.from(graph).query { findArgumentConstants { method { ... }; argumentIndex = 0 } }`
returns constants per argument position.

# More recipes

## Blast radius of a change

Before changing a method, list everything that depends on it:

1. Direct and transitive callers (`control-flow.md`, BFS upward), stopping at
   entry points; the entry points reached are the user-visible surface to test.
2. If the method implements an interface, repeat for the interface signature.
3. Fields it writes and who reads them:

```cypher
MATCH (v)-[e:DATAFLOW]->(f:FieldNode)
WHERE e.kind = 'FIELD_STORE' AND v.method = 'com.example.Cart.add(com.example.Item)'
MATCH (f)-[:DATAFLOW*1..3]->(use:CallSiteNode)
RETURN DISTINCT f.class + '.' + f.name AS field, use.caller_signature
```

4. For a return type or parameter change, the call sites of the method and where
   their results flow (`data-flow.md`, "Forward").

Report as: entry points affected, call sites to update, state touched.

## Architecture overview for an unfamiliar codebase

1. `schema` for size and shape.
2. `c4` with `format=mermaid` for containers/components, `overview` for class
   dependencies.
3. Hotspots: most-called application methods.

```cypher
MATCH (cs:CallSiteNode)
WHERE cs.callee_class STARTS WITH 'com.example'
RETURN cs.callee_signature, count(*) AS calls, count(DISTINCT cs.caller_class) AS fromClasses
ORDER BY fromClasses DESC
LIMIT 30
```

4. Entry points and outbound boundaries (`entrypoints-and-boundaries.md`).

## Library / API usage audit (upgrades, deprecations, migrations)

```cypher
// Every use of a library, grouped by API
MATCH (cs:CallSiteNode)
WHERE cs.callee_class STARTS WITH 'org.apache.commons.lang.'   // e.g. lang -> lang3 migration
RETURN cs.callee_signature, count(*) AS sites, collect(DISTINCT cs.caller_class) AS callers
ORDER BY sites DESC
```

Build with `--include-libs` if you need calls *inside* bundled jars as well.

## Dead code candidates

- Methods with no in-graph caller: the query in
  `entrypoints-and-boundaries.md` ("methods nothing in the graph calls"). Exclude
  entry points, framework callbacks, interface implementations and reflection
  targets before reporting.
- Code made dead by a constant (a fully rolled-out flag, a `BuildConfig` value):
  the Kotlin API computes it.

```kotlin
val result = BranchReachabilityAnalysis(graph).analyze(listOf(
    Assumption(
        methodPattern = MethodPattern(declaringClass = "com.example.ab.FlagClient", name = "isEnabled"),
        argumentIndex = 0,
        argumentValue = "checkout.new_flow",
        assumedValue = true,
    )
))
result.deadCallSites   // call sites in branches that can no longer run
result.deadMethods     // methods only reachable from those branches
```

## Security-oriented sinks

Constants and inputs reaching sensitive APIs are ordinary dataflow queries:

```cypher
// Hard-coded strings reaching crypto, credential or SQL APIs
MATCH (s:StringConstant)-[:DATAFLOW*1..6]->(cs:CallSiteNode)
WHERE cs.callee_class IN ['javax.crypto.Cipher', 'java.security.MessageDigest',
                          'javax.crypto.spec.SecretKeySpec', 'java.sql.Statement',
                          'java.sql.Connection', 'java.sql.DriverManager']
RETURN cs.callee_signature, s.value, cs.caller_signature
```

`Cipher.getInstance("AES/ECB/...")`, `MessageDigest.getInstance("MD5")` and
literal passwords in `DriverManager.getConnection` show up directly. For
injection, trace from endpoint parameters (`ParameterNode` of methods returned
by `endpoints`) forward to `Statement.execute*`, `Runtime.exec`,
`ProcessBuilder`, joining across methods as in `data-flow.md`.

## Explaining one specific node

Run the `cypher` tool with `graph_id` and `RETURN id(n)` (ids are local to one
graph). Then `node`, `incoming`, `outgoing` or `subgraph(center=id, depth=2)`,
with the same `graph_id`, show the raw neighborhood. Use
this when a recipe returns something surprising.

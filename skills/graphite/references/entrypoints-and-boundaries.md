# Entry points and boundaries

Goal: list how control enters the application (inbound) and where it leaves the
application for other systems (outbound). Replace `com.example` with the
application's package prefix throughout.

## Inbound: HTTP endpoints

Spring Web (`@RequestMapping`, `@GetMapping`, `@PostMapping`, `@PutMapping`,
`@DeleteMapping`, `@PatchMapping`) is extracted for you, including class-level
prefixes and controller inheritance:

- MCP: `endpoints` (optional `class_name`, `limit`)
- HTTP: `GET /api/endpoints` or `GET /api/graphs/{id}/endpoints`

Each entry has `httpMethod`, `path`, `signature`, `parameters`, `returns`.

For other frameworks, query annotations directly. `member` is the method or field
name, or `<class>` for a class-level annotation; attributes are properties
(`value`, `path`, `topics`, ...):

```cypher
MATCH (a:Annotation)
WHERE a.name IN [
  'javax.ws.rs.Path', 'jakarta.ws.rs.Path',
  'org.springframework.kafka.annotation.KafkaListener',
  'org.springframework.amqp.rabbit.annotation.RabbitListener',
  'org.springframework.jms.annotation.JmsListener',
  'org.springframework.scheduling.annotation.Scheduled',
  'org.springframework.context.event.EventListener',
  'org.springframework.messaging.handler.annotation.MessageMapping',
  'org.springframework.graphql.data.method.annotation.QueryMapping',
  'org.springframework.graphql.data.method.annotation.MutationMapping'
]
RETURN a.name, a.class, a.member, a.value
ORDER BY a.class, a.member
```

Unknown framework? Get the annotation vocabulary first, then pick the entry-point ones:

```cypher
MATCH (a:Annotation)
RETURN a.name, count(*) AS uses
ORDER BY uses DESC
LIMIT 100
```

The `annotations` tool returns everything on one member:
`annotations(class_name='com.example.Controller', member_name='checkout')`.

## Inbound: main methods, framework callbacks, Android components

```cypher
// Process entry point candidates: the JVM launcher shapes, not every method named main
MATCH (m:Method {name: 'main'})
WHERE m.class STARTS WITH 'com.example'
  AND m.return_type = 'void'
  AND m.parameter_types IN [[], ['java.lang.String[]']]
RETURN m.signature
```

`Method` does not expose `static` or visibility, so these are candidates.
Confirm in source or in the manifest (`Main-Class`, Spring Boot `Start-Class`).
A Kotlin `companion object` `main` also shows up as an instance method on
`Outer$Companion`. With `@JvmStatic`, the launchable one is the static
`Outer.main`.

```cypher
// Android lifecycle / framework callbacks (overrides are invoked by the platform)
MATCH (m:Method)
WHERE m.class STARTS WITH 'com.example'
  AND m.name IN ['onCreate', 'onStartCommand', 'onReceive', 'onHandleIntent',
                 'onBind', 'doWork', 'handleMessage', 'run', 'call']
RETURN m.class, m.name, m.parameter_types
ORDER BY m.class
```

For Android, also read the manifest: `resource(path='AndroidManifest.xml')`
(list candidates with `resources(pattern='**/AndroidManifest.xml')`).
gRPC services implement `*ImplBase` classes; match their overriding methods by
name across `m.class STARTS WITH 'com.example'`.

## Inbound: methods nothing in the graph calls

Methods with no in-graph caller are either invoked by a framework/container
(entry points), reached reflectively, or dead. This is a strong candidate list:

```cypher
MATCH (m:Method)
WHERE m.class STARTS WITH 'com.example'
  AND NOT m.name IN ['<init>', '<clinit>']
OPTIONAL MATCH (c:CallSiteNode) WHERE c.callee_signature = m.signature
WITH m, count(c) AS callers
WHERE callers = 0
RETURN m.signature
ORDER BY m.signature
```

Interface implementations will show up here because callers target the interface
method (see SKILL.md section 3). Cross-check against the annotation and callback
lists above before calling anything dead.

## Outbound: what the application calls outside itself

Group every call from app code to non-app code by target class. This is the fastest
map of external dependencies and I/O boundaries:

```cypher
MATCH (cs:CallSiteNode)
WHERE cs.caller_class STARTS WITH 'com.example'
  AND NOT cs.callee_class STARTS WITH 'com.example'
  AND NOT cs.callee_class STARTS WITH 'java.lang.'
  AND NOT cs.callee_class STARTS WITH 'java.util.'
  AND NOT cs.callee_class STARTS WITH 'kotlin.'
RETURN cs.callee_class, collect(DISTINCT cs.callee_name) AS apis, count(*) AS sites
ORDER BY sites DESC
LIMIT 200
```

Then zoom into known boundary families:

```cypher
MATCH (cs:CallSiteNode)
WHERE cs.caller_class STARTS WITH 'com.example'
  AND (cs.callee_class STARTS WITH 'java.net.'            // raw HTTP/sockets
    OR cs.callee_class STARTS WITH 'okhttp3.'
    OR cs.callee_class STARTS WITH 'retrofit2.'
    OR cs.callee_class STARTS WITH 'org.springframework.web.client.'
    OR cs.callee_class STARTS WITH 'org.springframework.web.reactive.function.client.'
    OR cs.callee_class STARTS WITH 'io.grpc.'
    OR cs.callee_class STARTS WITH 'java.sql.'            // databases
    OR cs.callee_class STARTS WITH 'javax.persistence.'
    OR cs.callee_class STARTS WITH 'jakarta.persistence.'
    OR cs.callee_class STARTS WITH 'org.springframework.jdbc.'
    OR cs.callee_class STARTS WITH 'org.apache.kafka.'    // messaging
    OR cs.callee_class STARTS WITH 'org.springframework.kafka.'
    OR cs.callee_class STARTS WITH 'redis.clients.'       // caches
    OR cs.callee_class STARTS WITH 'io.lettuce.'
    OR cs.callee_class STARTS WITH 'java.io.File'         // filesystem / processes
    OR cs.callee_class STARTS WITH 'java.nio.file.'
    OR cs.callee_class = 'java.lang.ProcessBuilder'
    OR cs.callee_class = 'java.lang.Runtime')
RETURN cs.callee_class, cs.callee_name, cs.caller_signature
ORDER BY cs.callee_class, cs.caller_signature
```

To learn *which endpoint or host* each outbound call targets, trace the string
constants that reach it (see `data-flow.md`):

```cypher
MATCH (s:StringConstant)-[:DATAFLOW*1..6]->(cs:CallSiteNode)
WHERE cs.callee_class STARTS WITH 'org.springframework.web.client.'
RETURN DISTINCT cs.caller_signature, cs.callee_name, s.value
```

## Internal module boundaries and architecture

- `overview` tool: class-level dependency overview (classes with call-site counts
  and the edges between them).
- `c4` tool (`level=context|container|component|all`, `format=mermaid|dsl|plantuml|json`):
  inferred C4 views; `mermaid` is convenient to paste into a report.
- Package-to-package dependencies:

```cypher
MATCH (cs:CallSiteNode)
WHERE cs.caller_class STARTS WITH 'com.example'
  AND cs.callee_class STARTS WITH 'com.example'
WITH split(cs.caller_class, '.')[2] AS fromPkg, split(cs.callee_class, '.')[2] AS toPkg
WHERE fromPkg <> toPkg
RETURN fromPkg, toPkg, count(*) AS calls
ORDER BY calls DESC
```

Adjust the `split(...)[n]` index to the depth that corresponds to modules.

## Service-to-service boundaries (multiple graphs)

When a server loads several service graphs, `GET /api/topology` returns the
graph-to-graph call topology derived from the server's `--topology` rules.
Without rules, find candidate RPC clients per graph:

```cypher
MATCH (cs:CallSiteNode)
WHERE cs.callee_class =~ '.*(Client|Stub|Api|Service)$'
  AND NOT cs.callee_class STARTS WITH 'java.'
RETURN graphId(cs) AS graph, cs.callee_class, count(*) AS sites
ORDER BY sites DESC
LIMIT 100
```

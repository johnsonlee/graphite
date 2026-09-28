# Control flow

Graphite has two levels of control flow:

1. **Inter-procedural**: which method calls which, recovered by joining call
   sites on signatures (there are no method-to-method edges).
2. **Intra-procedural branches**: `CONTROL_FLOW` edges from an `if` condition to the
   first node of each branch.

## Resolve the exact signature first

Every recipe below matches on exact signatures. Find them first:

```cypher
MATCH (m:Method)
WHERE m.class = 'com.example.CheckoutService' AND m.name = 'checkout'
RETURN m.signature
```

## Direct callers and callees

```cypher
// Who calls it?
MATCH (cs:CallSiteNode)
WHERE cs.callee_signature = 'com.example.CheckoutService.checkout(java.lang.String)'
RETURN DISTINCT cs.caller_signature
```

```cypher
// What does it call? (id order approximates statement order)
MATCH (cs:CallSiteNode)
WHERE cs.caller_signature = 'com.example.CheckoutService.checkout(java.lang.String)'
RETURN cs.callee_signature, cs.line
ORDER BY id(cs)
```

If the method implements an interface or overrides a superclass method, callers
usually target the *declared* method. Query the interface signature too:

```cypher
MATCH (cs:CallSiteNode)
WHERE cs.callee_name = 'send'
  AND cs.callee_class IN ['com.example.Channel', 'com.example.EmailChannel']
RETURN cs.callee_signature, cs.caller_signature
```

## Call chains of fixed depth

Upward (who eventually reaches this method), three levels:

```cypher
MATCH (c1:CallSiteNode)
WHERE c1.callee_signature = 'com.example.PaymentGateway.charge(java.lang.String,long)'
MATCH (c2:CallSiteNode) WHERE c2.callee_signature = c1.caller_signature
MATCH (c3:CallSiteNode) WHERE c3.callee_signature = c2.caller_signature
RETURN DISTINCT c3.caller_signature AS d3, c2.caller_signature AS d2, c1.caller_signature AS d1
```

Use `OPTIONAL MATCH` for the outer levels to keep chains that end early (a root
has no caller). Downward is symmetric: join `cN.caller_signature = c(N-1).callee_signature`.

## Unbounded reachability: iterate

For "is X reachable from entry point E" or deep call trees, run a breadth-first
search yourself, one query per frontier, and stop at a depth budget (8-10 hops is
plenty for most questions):

```cypher
// one BFS step upward, with the current frontier as a list literal
MATCH (cs:CallSiteNode)
WHERE cs.callee_signature IN [
  'com.example.CheckoutService.newFlow(java.lang.String)',
  'com.example.CheckoutService.legacyFlow(java.lang.String)'
]
RETURN DISTINCT cs.caller_signature
```

Keep a visited set, record the edge that discovered each method (to reconstruct
the path), and drop JDK/library callers unless they matter. Stop expanding at
entry points (see `entrypoints-and-boundaries.md`). When a frontier method has no
callers and belongs to a synthetic class (`Outer$method$1`), don't stop there:
re-attach it to its enclosing method first (see "Lambda and coroutine bodies"
below).

## Branches: what does a condition guard?

`CONTROL_FLOW` edges start at the condition operand (usually a local holding a
call result) and point to the first node of each branch. Joining that node back to
the call site that produced it names the guarded call:

```cypher
MATCH (gate:CallSiteNode {callee_name: 'isEnabled'})
      -[:DATAFLOW*1..3]->(cond)-[b:CONTROL_FLOW]->(first)
      <-[:DATAFLOW]-(guarded:CallSiteNode)
RETURN gate.caller_signature, b.kind, guarded.callee_signature
```

`first` may also be a local assigned from a constant or a call site itself; drop
the final hop and return `labels(first)`, `first.name`, `first.callee_signature`
to see it raw.

**Polarity:** `BRANCH_TRUE`/`BRANCH_FALSE` describe the JVM comparison
(`if $z == 0 goto L`), which javac usually emits inverted relative to the source
`if`. In the verified example, `if (flags.isEnabled(...)) newFlow() else legacyFlow()`
yields `BRANCH_TRUE -> legacyFlow`. Report "guarded by X" confidently; report which
side is the enabled side only after reading the source.

The JVM frontend emits only `if` branches (`BRANCH_TRUE`/`BRANCH_FALSE`):
`switch`, ternaries folded into locals, loops and exception handlers have no
edges. Other frontends may also emit `SEQUENTIAL`, `SWITCH_CASE`,
`SWITCH_DEFAULT`, `EXCEPTION` and `RETURN`; check with
`MATCH ()-[r:CONTROL_FLOW]->() RETURN r.kind, count(*)`. The Kotlin API has more
(`BranchReachabilityAnalysis`, which kills the branches that a constant assumption
makes dead; see `more-recipes.md`).

## Lambdas and method references

A lambda or method reference resolved by Graphite appears as an extra call site
in the enclosing method whose `callee_signature` is the target, marked by a
`CALL` self-loop with `dynamic = true`:

```cypher
MATCH (cs:CallSiteNode)-[r:CALL]->(cs)
WHERE r.dynamic AND cs.caller_class STARTS WITH 'com.example'
RETURN cs.caller_signature, cs.callee_signature
```

These rows are part of the call graph above, so chains pass through callbacks
(`list.forEach(this::deliver)` links the caller to `deliver`).

## Lambda and coroutine bodies: re-attach to the enclosing method

Kotlin lambdas, `suspend` lambdas (`launch { }`, `sequence { }`, `flow { }`) and Java
anonymous classes compile to synthetic classes such as `Outer$method$1` or
`Outer$method$inner$1`. Their bodies live in `invoke`, `invokeSuspend`, `run`,
`call`, `apply`, and similar methods that are invoked through `Function1.invoke`
or the framework, not by a call site naming them. Upward chains therefore stop
there: in the Kotlin compiler graph, 5,043 call sites sit in synthetic `invoke`
bodies and 390 in `invokeSuspend` bodies, and 31 of 63 synthetic `invoke` methods
sampled in the CLI packages have no caller.

When a chain reaches a `caller_class` containing `$` and the caller is such a
body, hop to the method that creates the synthetic class instead:

```cypher
// Capturing lambdas, coroutine bodies and anonymous classes are constructed by the
// enclosing method. Non-capturing Kotlin lambdas are singletons built in their own
// <clinit>, and the enclosing method reads their INSTANCE field.
MATCH (init:CallSiteNode)
WHERE init.callee_class = 'com.example.Checkout$process$1' AND init.callee_name = '<init>'
  AND init.caller_class <> init.callee_class
RETURN DISTINCT init.caller_signature AS enclosing, 'constructed' AS via
UNION
MATCH (f:FieldNode {class: 'com.example.Checkout$process$1', name: 'INSTANCE'})-[e:DATAFLOW]->(v)
WHERE e.kind = 'FIELD_LOAD' AND v.method <> '' AND NOT v.method STARTS WITH f.class + '.'
RETURN DISTINCT v.method AS enclosing, 'singleton' AS via
```

[verified: Kotlin compiler. Examples: `ContainerUtilsKt$bfs$1` (a `sequence { }`
coroutine) → `ContainerUtilsKt.bfs(…)`, constructed;
`JvmCompilerPipelineKt$convertToIrAndActualizeForJvm$1` →
`convertToIrAndActualizeForJvm(…)`, singleton;
`FirMetadataSerializer$analyze$outputs$1$firFiles$1` → `FirMetadataSerializer.analyze()`.]

- Exclude constructions inside the synthetic class itself (`caller_class <>
  callee_class`). Coroutine lambdas re-create themselves in `create(…,
  Continuation)`, and singletons construct themselves in `<clinit>`.
- If the enclosing method is itself a synthetic body (nested lambdas), repeat the
  hop, then continue the normal caller join from the first non-synthetic method.
- The hop proves that the enclosing method *creates* the lambda. Whether and when
  the body runs depends on who receives it (`forEach`, `launch`, a listener
  registration); report it that way.
- A `suspend fun`'s own continuation class (`Outer$foo$1`) is constructed by
  `Outer.foo(…, Continuation)` itself, so the same hop lands back on `foo`.
- The `INSTANCE` path also matches Kotlin `object` singletons that aren't lambdas.
  That's still the right "who uses this" answer, but it isn't a lambda body.

## Visual neighborhood

Node ids are local to one graph: run the `cypher` tool with `graph_id` and
`RETURN id(cs)`, then pass that id and the same `graph_id` to `subgraph`
(`center`, `depth`) or `incoming`/`outgoing` to see its immediate edges. The Explorer UI (`graphite serve`) renders the same.

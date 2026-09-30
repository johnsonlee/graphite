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
entry points (see `entrypoints-and-boundaries.md`). Lambda and anonymous-class
bodies have callers like any other method (see "Lambdas and function values"
below); in a graph built by Graphite 2.8.0, a frontier method in a synthetic class
(`Outer$method$1`) with no callers needs re-attaching to its enclosing method
first (see "Lambda and coroutine bodies" below).

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

## Lambdas and function values

Every way the JVM compilers produce a function value is linked: Java lambdas and
method references, Kotlin lambdas (`invokedynamic` or one class per lambda), `suspend`
lambdas, callable and property references (`::f`, `obj::f`, `C::prop`), `fun
interface` and Java SAM conversions, anonymous classes, `object :` expressions, and
lambdas desugared by D8/R8 (`Outer$$ExternalSyntheticLambda0`). Each adds call sites
marked by a `CALL` self-loop with `dynamic = true`:

- **Creation**: the method that creates the function value gets a call site whose
  `callee_signature` is the body (`Outer.method$lambda$0`, `Outer$method$1.invoke`,
  `invokeSuspend`, `run`, …). The body stays reachable even when it runs inside a
  library (`lazy { }`, `launch { }`, `executor.execute { }`).
- **Dispatch**: a call through the function value (`fn.apply(x)`, Kotlin `fn(x)`,
  i.e. `Function1.invoke`) gets a sibling call site whose `callee_signature` is the
  implementation it resolves to, with the same caller. This follows the value across
  methods: through parameters (including forwarded ones), return values, fields
  (including constructor injection), arrays, captures and casts, and across interface
  and override boundaries (a value passed to `Invoker.invoke` resolves inside every
  implementation, inherited ones included). A resolved call carries function values
  on: `invoker.apply(seed())` resolved to `run(fn)` resolves `fn.apply` inside `run`.
  Resolution is context-insensitive: a helper called with several function values
  dispatches to all of them, so a dispatch call site in a shared helper is the union
  of what its callers pass. A function value re-bound through more than 32 distinct
  `fn::apply` sites is not followed further.

```cypher
MATCH (cs:CallSiteNode)-[r:CALL]->(cs)
WHERE r.dynamic AND cs.caller_class STARTS WITH 'com.example'
RETURN cs.caller_signature, cs.callee_signature
```

These rows are part of the call graph above, so chains pass through callbacks
(`list.forEach(this::deliver)` links the caller to `deliver`) and through lambda
bodies in both directions. [verified: Kotlin compiler. `ContainerUtilsKt$bfs$1`
(a `sequence { }` coroutine) has `ContainerUtilsKt.bfs(…)` as a caller;
`JvmCompilerPipelineKt$convertToIrAndActualizeForJvm$1.invoke` has both
`convertToIrAndActualizeForJvm(…)`, which creates it, and
`Fir2IrPipeline.runFir2IrConversion(…)`, which calls it through the function value. In the
CLI packages 4 of 63 synthetic `invoke` methods have no caller, all erased bridges
(`invoke(Object)`) whose typed sibling has one.]

- Arguments of a dispatch call site line up with the implementation's parameters:
  a capturing lambda's captured values come first, an unbound method reference
  (`String::toUpperCase`) takes its first argument as the receiver, and a static
  method or constructor reference has no receiver.
- A creation call site exists for each method the class implements for a supertype
  (`run`, `invoke`, `toString`), not for helpers the class adds on its own.
- A creation call site proves that the method *creates* the function value, not
  that the body runs; whether and when it runs depends on who receives it
  (`forEach`, `launch`, a listener registration). Report it that way.
- A function value stored in a collection and read back (`listOf(fn)[0](x)`) is not
  followed; the creation call site still links the body to its creator.

## Lambda and coroutine bodies: re-attach to the enclosing method

This applies to graphs built by Graphite 2.8.0, before
[#162](https://github.com/johnsonlee/graphite/pull/162). There, only
`invokedynamic` lambdas were linked. Kotlin lambdas, `suspend` lambdas
(`launch { }`, `sequence { }`, `flow { }`) and Java anonymous classes compile to
synthetic classes such as `Outer$method$1` or `Outer$method$inner$1`. Their
bodies live in `invoke`, `invokeSuspend`, `run`, `call`, `apply`, and similar
methods that are invoked through `Function1.invoke` or the framework, and no call
site named them. Upward chains therefore stopped there: in the Kotlin compiler
graph, 31 of 63 synthetic `invoke` methods sampled in the CLI packages had no
caller.

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

[verified: Kotlin compiler, Graphite 2.8.0. Examples: `ContainerUtilsKt$bfs$1` →
`ContainerUtilsKt.bfs(…)`, constructed;
`JvmCompilerPipelineKt$convertToIrAndActualizeForJvm$1` →
`convertToIrAndActualizeForJvm(…)`, singleton;
`FirMetadataSerializer$analyze$outputs$1$firFiles$1` → `FirMetadataSerializer.analyze()`.]

- Exclude constructions inside the synthetic class itself (`caller_class <>
  callee_class`). Coroutine lambdas re-create themselves in `create(…,
  Continuation)`, and singletons construct themselves in `<clinit>`.
- If the enclosing method is itself a synthetic body (nested lambdas), repeat the
  hop, then continue the normal caller join from the first non-synthetic method.
- A `suspend fun`'s own continuation class (`Outer$foo$1`) is constructed by
  `Outer.foo(…, Continuation)` itself, so the same hop lands back on `foo`.
- The `INSTANCE` path also matches Kotlin `object` singletons that aren't lambdas.
  That's still the right "who uses this" answer, but it isn't a lambda body.

## Visual neighborhood

Node ids are local to one graph: run the `cypher` tool with `graph_id` and
`RETURN id(cs)`, then pass that id and the same `graph_id` to `subgraph`
(`center`, `depth`) or `incoming`/`outgoing` to see its immediate edges. The Explorer UI (`graphite serve`) renders the same.

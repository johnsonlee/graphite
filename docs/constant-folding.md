# Constant Folding at Build Time

`graphite build --fold <file>` (`LoaderConfig.folding`, a `FoldPlan`, in the Kotlin API) replaces every
call matching the listed call-site patterns by a constant while SootUp builds each method body,
before the graph has a single node. It exists for one job: a diff of two graphs built from a base and a
head must not see the code a feature flag or experiment keeps switched off.

## Why on the body, not on the graph

The graph records the two sides of a branch as node sets (`BranchScope`). Three kinds of
statement produce no node of their own: a `return` (one `ReturnNode` per method), a constant
(deduplicated graph-wide) and a field access (one `FieldNode` per field). A gated block that
consists only of those, the guard clause `if (!gate()) return;` first among them, is therefore
empty on both sides and nothing downstream can remove it. On a large corpus a third of all
branches look like that. Folding the body avoids the representation: the dead side is removed
before it could become nodes.

## What the passes do

The fold passes go after whatever body interceptors the input location runs without rules:
none for a jar or a class directory (`PathBasedAnalysisInputLocation.create(path, type)` runs
the ASM frontend's Jimple as emitted, locals typed and named by it), SootUp's `Default` chain
for the Android platform jar and the dex chain for an APK. A body no rule touches is therefore
the one a build without rules produces, byte for byte. For a body in which a rule matched,
and only there, the chain continues:

1. **Fold**: every call expression a rule matches, call-site properties and argument constants
   both, becomes the rule's constant. An assignment keeps its local (`$z = 0`); a call whose
   result is discarded becomes a `nop`. A call whose return type cannot carry the value (a
   `java.lang.Boolean` or a `void` for a boolean rule, say) is left alone and reported, the
   discarded call too: the rule names another call, and deleting this one would delete its
   effects. A folded call throws nothing, so its exceptional edges go with it and a handler
   only it could reach is removed; neighbouring calls retain their exceptional edges even
   when they share a basic block with the folded call. A value written as `EnumConstant {enum_type, name}` becomes
   the read of that static field, which is what the constant is in bytecode; the method must
   return that enum and the enum must declare that constant. A call whose return type is
   erased to `java.lang.Object` (`Supplier.get`, `Function.apply`, Kotlin's `Function1.invoke`)
   becomes the value boxed the way the compiler boxes it: `Boolean.TRUE` or `Boolean.FALSE`,
   `Integer.valueOf(3)`, a string as itself, an enum constant as the read of its field. It is
   boxed as the call's result type when the rule carries one (`result_type`, below), else as
   the type the constant names (`false` is a `boolean`, `3` an `int`).
2. **FoldBranches**: a local with exactly one definition, that definition a constant, is read
   as the constant; a `cmp`/`cmpl`/`cmpg` on two constants (how `long`, `float` and `double`
   comparisons compile) becomes its sign; an `if` whose condition then compares two constants,
   or two locals whose one definition each reads an enum constant (the same constant is `==`,
   different ones are not, neither is `null`), is resolved by wiring
   its predecessors to the side the condition takes and dropping the `if`; a `switch` whose
   key is then a constant (`switch (Experiments.variant("k"))`, a `lookupswitch` or a
   `tableswitch`) is resolved the same way, to the case listing the value or else to the
   default; the statements no longer reachable from the method's entry are removed. The two steps repeat until nothing
   changes, so `x = a && gate()` folds through `x`. Removal happens inside the pass because
   SootUp validates the statement graph after every interceptor and a statement without a
   predecessor fails that check. An erased call folded to a box reaches its test through a
   cast and an unboxing call (`$z = (Boolean) $r; $b = $z.booleanValue()`): the unboxing of a
   local whose one definition is a constant box, directly or through casts, becomes the
   constant, and a cast of an enum constant read compares as the read, so the branch on an
   erased call folds as one on a primitive call does. SootUp's own `ConditionalBranchFolder` is not used: on 2.0.0
   it kept the side a constant condition rules out, and 3.0.1, which fixed that sign, still
   prunes the join point behind the dropped side whenever the `if` was its only other
   predecessor, so `if (gate) work(); tail();` loses `tail()` and fails SootUp's own
   validation. `ConditionalBranchFolderTest` pins both facts and will fail when an upgrade
   fixes the pruning.
3. `DeadAssignmentEliminator` drops the `$z = 0` nothing reads any more, `NopEliminator` the
   `nop`s.

Supported constants per return type: `boolean`, `int`, `short`, `byte`, `char` from a boolean
or integer; `long` from an integer; `float` and `double` from any number; `java.lang.String`
from a string; any reference or array type from `null`; an enum type from one of its
constants; `java.lang.Object` from any of these, boxed as above. A `java.lang.Boolean` or
other boxed return type is not an erased one and is reported. `null == null` and `null != null` fold; `null` against a non-null reference, such
as `Boolean.TRUE` read from a field, does not, because a field is not a constant. An enum
constant folds `==` and `!=` against another enum constant or `null`. `equals(Object)` also
folds when its receiver is a known enum constant and its argument is another enum constant
or `null`: Java's final `Enum.equals` compares identity. An unknown or null receiver, an
unknown argument, and custom `equals` implementations or overloads remain calls. A `switch` on it goes
through `ordinal()` and a synthetic lookup array and is not folded, where a `switch` on a
folded `int` is.

## Partially constant experiment predicates

For a predicate with a runtime condition, put the rule on the option lookup whose value is
known for the experiment, rather than on the whole predicate:

```java
boolean isGroupA() { return inScope() && ABTestOption.A.equals(getAbOption()); }
boolean isGroupB() { return inScope() && ABTestOption.B.equals(getAbOption()); }
```

```yaml
version: 1
folds:
  - match:
      CallSite: { callee_class: com.example.Experiment, callee_name: getAbOption }
    value: { EnumConstant: { enum_type: com.example.ABTestOption, name: A } }
```

With this assumption, `isGroupA` still depends on `inScope()`; its result cannot be replaced
by either boolean constant. The comparison in `isGroupB` becomes false, but evaluation of
`inScope()` is retained, including its possible effects and exceptions. Branches in the same
body that depend on the folded comparison are simplified. Calls to these wrapper methods
in other bodies are not automatically inlined or folded.

The rule asserts the lookup's value; Graphite does not infer which enum represents control
or which experiment a shared lookup serves. Scope a rule with call-site properties, constant
arguments, or a `select` query when only some lookups have that value. A direct rule on
`isGroupA()` with `value: true` or `value: false` would replace the entire call and lose its
runtime condition.

## The file

A rule is a node pattern in the graph's own vocabulary, the way a Cypher `MATCH` names a node,
or, when a pattern on one call cannot say which calls to fold, the Cypher query itself. The
file's keys (`version`, `folds`, `match`, `args`, `select`, `selected`, `value`, `frontend`) are
one contract for every frontend; the labels and property names inside `match`, `args` and
`value`, and the query under `select`, are the frontend's node schema, the same names its
graphs answer queries with. The Rust CLI hands `--fold <file>` to whichever frontend builds
the graph, after resolving the `select` rules (below).

```yaml
version: 1
folds:
  - match:
      CallSite: { callee_class: com.example.Flags, callee_name: isEnabled }
    args:
      0: new_checkout
    value: false
  - match:
      CallSite: { callee_class: com.example.Flags, callee_name: isEnabled, caller_class: "com.example.checkout.*" }
    args:
      0: { EnumConstant: { enum_type: com.example.Flag, name: DARK_MODE } }
    value: true
  - match:
      CallSite: { callee_signature: com.example.Experiments.variant(java.lang.String,long) }
    args:
      0: { StringConstant: {} }
      1: { LongConstant: { value: 7 } }
    value: { IntConstant: { value: 0 } }
  - match:
      CallSite: { callee_name: isEnabled }
    frontend: swift
    value: false
```

| Key | Meaning |
|---|---|
| `version` | `1` |
| `folds` | The rules, in order; the first rule a call matches is the one applied |
| `match` | One label, `CallSite`, with the properties the call site must have |
| `args` | Optional. Argument index to the constant node that must flow into that argument; an index the call does not have never matches |
| `select` | Instead of `match`: a Cypher query over a graph built without rules, returning one column of `CallSite` nodes; `args` then says what the key is at those calls |
| `selected` | Optional, under `select`: the keys of the call sites the query returned (`caller_signature`, `caller_descriptor`, `callee_signature`, `callee_descriptor`, `ordinal`, and `result_type` where the callee's return type is erased), written by the CLI once it has run the query |
| `provenance` | Required with `selected`: the input and frontend the keys were read off (`input_sha256`, `frontend_version`); a build of anything else refuses the keys |
| `value` | The constant the call becomes |
| `frontend` | Optional. A rule naming another frontend is skipped; a rule without one must parse on every frontend, and a frontend that cannot parse it fails the build rather than ignoring it |

JSON carries the same keys. On the JVM:

- `CallSite` properties are `callee_class`, `callee_name`, `callee_signature`
  (`pkg.Cls.name(p1,p2)`), `callee_descriptor` (the JVM descriptor, `(Ljava/lang/String;)Z`),
  `caller_class`, `caller_name`, `caller_signature`, `caller_descriptor` and `ordinal`, as
  queries name them. The signature leaves the return type out, the descriptor keeps it: a
  bridge method and the covariant override it forwards to share a signature, and only the
  descriptor tells their calls apart; the core names of `docs/architecture-frontend-backend.md` (`callee.owner`,
  `caller.signature`, ...) are accepted as aliases. A value containing `*` is a glob: `*`
  matches any run of characters. Every listed property must match. `ordinal` is the rank of the
  call among the invokes of the same callee in the calling method's bytecode, in statement
  order from `0`, every invoke, including the boxing and unboxing calls the graph shows as
  dataflow rather than as call sites; it is the number the graph's `CallSite.ordinal` holds, and
  the adapter and the fold pass compute it with one function over the unfiltered body, so
  `{caller_signature, caller_descriptor, callee_signature, callee_descriptor, ordinal}` names
  one call site and a key read off a graph built without rules folds exactly that call
  (`CallSiteOrdinalTest`, `FoldSelectionTest`). `callee_class` is the class
  that declares the method, as the graph's `CallSite` names it: bytecode spells the receiver's
  static type (`Sub.isEnabled()`), and the fold pass resolves it through the superclasses, then
  the interfaces, the way the adapter does for the node.
- A constant node is a scalar or `{Label: {properties}}`. A scalar is `Constant {value: x}`:
  it matches a constant of any label with that value, numbers compared as numbers whatever
  their width (`7` matches `7L`): integers exactly, so a `long` keeps every bit, and
  floating-point numbers as IEEE 754 does (`-0.0` matches `0.0`, `NaN` matches nothing, a
  `FloatConstant` is compared at `float` precision). `null` is `NullConstant`. A label pins the kind:
  `BooleanConstant`, `IntConstant`, `LongConstant`, `FloatConstant`, `DoubleConstant` and
  `StringConstant` take `value`, `NullConstant` nothing, `EnumConstant` takes `enum_type` and
  `name` (an enum constant is a field the enum declares as one, `ACC_ENUM`; `static E alias = A`
  is a `FieldNode`), `FieldNode` (alias `Field`)
  takes `class` and `name` (any other static field read). A label without properties matches every constant of
  that kind. String values are globs too.
- An argument matches when it is a constant, or a local with one definition that is a
  constant or a static field read, which is what a key written as a literal, a `static final`
  the compiler inlined, or an enum constant looks like in the frontend's Jimple (`$stack1 =
  "key"; Flags.isEnabled($stack1)`). A key computed at run time does not: the
  call is reported as unsupported (`argument 0 of ... is not a constant`) under the first rule
  whose call site matched.
- `value` as a scalar is carried by whatever the return type is, when it can be (the table
  above); as a labelled constant it must fit the return type exactly (`LongConstant` on a
  `long`, `StringConstant` on a `java.lang.String`), otherwise the call is reported. A
  `float` carries a number at `float` precision only while it stays finite: `1e308` is a
  finite double and an infinity as a float, so the call is reported, not folded to a value
  nobody wrote. A `byte`, a `short` and a `char` carry only their own range (`128`, `32768`
  and `-1` are reported), although Jimple spells all three as an `int`. An argument pattern
  is held to the same float range: `FloatConstant {value: 1e308}` matches no float, not the
  infinity it would narrow to.
- YAML 1.1 reads bare `on`, `off`, `yes` and `no` as booleans: quote a method or key of that
  name.

## Select rules

A `match` rule sees one call: its properties and the constants that reach its arguments.
Where the key reaches the gate through helpers, collections or constructors, the thing that
names the calls to fold is a path through the graph, and the graph's query language already
expresses it. A `select` rule is that query, written against a graph built without rules:

```yaml
version: 1
folds:
  - select: >
      MATCH (k:IntConstant {value: 1234})-[:DATAFLOW*1..8]->(cs:CallSite {callee_name: 'getAbTestOption'})
      RETURN cs
    value: { EnumConstant: { enum_type: com.example.ABTestOption, name: CONTROL } }
```

Strings inside the query are Cypher strings and need quotes (`'getAbTestOption'`); only the
rule's own `match` properties are bare YAML scalars. A `select` rule is an assumption about
the call sites it names, not about a key: every selected call becomes the value, whoever
calls the method around it, and the fold then propagates the constant and resolves the
branches it decides. The query is may-flow: a key followed into a helper every key shares
(`gate(key) { return isEnabled(key); }`) reaches the one `isEnabled(key)` inside it, and
selecting that call folds `gate` for every key. The report says so (`Flags.isEnabled(...)
folds for every caller of Gate.gate(java.lang.String): argument 0 is parameter 0 of
Gate.gate(java.lang.String)`), and a rule meant for one key selects the call boundary that
tells the keys apart instead: the `gate("new_checkout")` call in the caller, not the call
inside the helper. Returning a `CallSite` keeps nothing of the path the query walked to it;
a per-key fold of a shared call needs a boundary that is per key, or a context-sensitive
fold, which this pass is not.

`args` and `receiver_args` are extra match conditions on a selected call, as `args` is on
a `match` rule: `args: {0: 1234}` holds argument 0 of the call to that constant where the
call stands (a literal, a local with one definition reading it, or the constant boxed the
way `fn.apply(3)` boxes `3`), and `receiver_args: {0: 1234}` holds the call that produced
the receiver (`Box a = boxed(1234); if (a.isOn())`: `boxed(1234)`, or the constructor call
of a `new`) the same way. A call the condition cannot be shown to hold on, because the
argument is another constant, a parameter, a field or a call's result, is reported with
what it is (`argument 0 of Flags.isEnabled(java.lang.String) is not a constant here: it is
parameter 0 of Gate.gate(java.lang.String)`; `the receiver of Box.isOn() comes from
Shapes.boxed(int), whose argument 0 is 5678 here, not 1234`) and left alone. That is a
filter on the selection, not a proof of the key: a query that reached two calls through the
shared return of `id(1234)` and `id(5678)` is told apart by `args: {0: 1234}` only because
the constants differ where the calls stand. A key
names one caller and one callee by signature and descriptor, both written with an array as
its base type and one `[]`, so overloads that differ only in array dimensions (`run(int[])`,
`run(int[][])`) share a key: a selection naming such a caller or callee is refused, naming
both methods, rather than folding the calls of both. The query runs where the graph is, in the Rust CLI. `graphite build --fold <file>`:

1. asks the frontend to validate the file and print its rules as JSON for this input
   (`graphite.jar fold plan <file> --input <input>`), so an error in the file reads exactly
   as it does from `build --fold`. The plan carries the build's `provenance`: the SHA-256 of
   the input (the jar, or the class directory's files), the frontend's version, and the
   `analysis` identity of the build (`include`, `exclude`, `include_libs`, `lib_filter`, each
   sorted, and `android_platform_sha256`, the content of the platform jar an APK build reads
   as a library), since those decide which classes the graph has and so which calls a query
   can select; `fold plan` takes the same options as `build` and the CLI passes them on. A
   `select` rule whose `selected` keys carry another provenance, or none, comes back
   unresolved, with a line naming what differs;
2. when a rule has `select` and no `selected`, builds the graph without rules into a staging
   directory next to the output (`<output>.unfolded-<pid>`), with the same input and
   options and `--interprocedural`: every call is linked to its callee's body, a `DATAFLOW`
   edge from each argument to the callee's `ParameterNode` and from its `ReturnNode` to the
   call's result, for the declared callee and every override on a virtual call. Without
   those edges a value stops at a helper's return, so the query above could not follow
   `helper() { return Box.of(1234); }` into `helper().enabled()`. The graph the user named
   is built without them;
3. runs every `select` on that graph with the Rust engine. Each row must have one column
   holding a `CallSite` node; its `caller_signature`, `caller_descriptor`,
   `callee_signature`, `callee_descriptor` and `ordinal` are the call site's key. A row that
   is a derived call site (a call on a function value resolved to the lambda body it holds,
   numbered below zero) is replaced by the call it was resolved from, the `get`, `apply` or
   `invoke` on the value, which the graph records as the derived site's origin (in the
   `graph.callsite-ordinals` sidecar). When that call's callee returns an erased
   `java.lang.Object`, the key carries `result_type`, the return type of the lambda bodies it
   runs, so the frontend boxes the value to it; bodies returning different types are an
   error, since one constant cannot stand for all. Folding that call folds it for every body
   it runs, so a query that selected some of those bodies and not the call itself asked for
   a narrower rewrite than the call can carry: it is refused, naming the bodies it did not
   select, rather than widened to them (`run(() -> a())` and `run(() -> b())` share one
   `gate.get()`; select both bodies, the call, or a call that runs only one). A derived row
   with no origin (a lambda body reached where the lambda is created, a method a function
   object implements) is no call at all: a query naming a lambda body matches its creation
   too, so such rows are skipped with one line counting them. A property, several columns, another node, or a
   graph without ordinals (built by an older frontend) is an error naming the rule, the row
   and what it held;
4. writes the plan with every `selected` filled in, each with the plan's `provenance`
   (`graph.folds.plan.json` in the staging directory), and builds again with `--fold`
   pointing at it, into the output the user named; the staging directory is removed either
   way.

In YAML, write the query as a block scalar (`select: >` or `select: |`) or quote it: a plain
scalar may not contain `: `, which every property map in a pattern does.

A file of `match` rules alone builds once, as before. A `select` rule with `selected` filled
in builds once too, on the CLI and on `graphite.jar build` alike, as long as its `provenance`
is the input's: the report `graph.folds.json` is such a file (`--fold graph.folds.json` reads
its rules and drops the diagnostics beside them). A key names one invoke only in the bytecode
it was read from: insert another call of the same callee before it and its ordinal names a
different call; and a build that reads other classes (another package filter, other
libraries, another platform jar) may hold other calls, so a key selected on the strength of
a helper that build had may be applied where the helper is gone. The CLI therefore runs the
query again for keys read off another input, by another frontend version or under other
analysis options, and `graphite.jar build` refuses them, naming what differs and the
`provenance` to copy beside keys written by hand from a graph of this very input built with
these options.
`graphite.jar build` alone also refuses an unresolved `select` with the command to run
instead, since it has no graph to run the query on.

The key names the same call in both builds because the second build reads the same bytecode
and the fold pass numbers the invokes of each body before it changes anything, exactly as the
adapter numbered the `CallSite` nodes of the first graph. The folded graph keeps that numbering
too: a body a rule folded in is numbered as it was before the fold, so a call the fold removed
leaves a gap and the calls that survive keep their ordinal, and a key read off the folded graph
(the report's `selected`, or a query on it) names the same call as one read off the unfolded
graph. Only a body no rule touched is numbered as it stands, which is the same numbering. A
`selected` key with an ordinal below zero, a derived call that no bytecode invoke carries,
is refused by the frontend rather than folding nothing. Folding the call a function value
was resolved from folds it for every value it runs: resolution is context-insensitive, so
a helper called with several lambdas folds as one call. Rules apply in file order, so a
`match` rule earlier in the file that folds a selected call takes it; the `select` rule then
reports that key as not in the build.

## What the build tells you

The file is validated before any bytecode is read; every error names the file, the rule
index, the key path, what was found and what to write instead, with the closest known name
where a key or label looks like a typo. The build then prints one line per rule. A rule that
folded nothing gets a warning saying why (no call site has these properties; every call it
matched is unsupported; or the nearest calls, with the argument constants they were seen with
or the property that did not match), and the Cypher query that previews the rule on a graph
built without it. When no rule folded anything the build says that the graph is the same as
one built without `--fold`; `--fold-strict` makes a rule that matched no call a failure.

A build without `--fold` deletes a `graph.folds.json` an earlier build left in the output
directory: the report describes the build that wrote it.

The report `graph.folds.json` in the output directory repeats each rule (`match`, `args`, or
`select` and `selected`; `value`, `frontend`) and adds `cypher`, the preview query (for a
`match`, an approximation of the rule: an argument pattern walks one or two `DATAFLOW` hops
into the call, the shapes the fold pass accepts, a constant passed directly and a constant
held in a local with one definition; the edge carries no argument index, so the pattern
matches a constant reaching any argument, and a local with several definitions is not told
apart; for a `select`, the query itself),
`matched` (calls folded), `sites` (per calling method: `caller`, `calls`), `unsupported` (per
call: `caller`, `reason`) and `hints` (the near misses, at most eight per rule; for a
`select`, the selected call sites the build did not contain). A rule whose only matches were
unsupported is not unmatched. Statements are counted per method, beside `folds`:
`statementsRemoved` and `methods` (`method`, `statementsBefore`, `statementsAfter`), one entry
per method any rule folded in. The two numbers bracket every fold in the method and the one
clean-up pass after all of them, so a method two rules folded in is counted once and no
removal is split between rules, which no measurement could do. The report reads back as a
fold file: the keys it adds beside a rule and beside `folds` are dropped on load.

Numbers are read exactly or refused, in JSON as in YAML: an integer literal is an `int` or a
`long` (one outside a `long` is an error, since no JVM constant carries it and a value that
wrapped would fold a call to a constant nobody wrote), a literal with a fraction or an
exponent is a `double` and must be finite (YAML's `.inf` and `.nan` are errors). These are the
JVM's limits and apply to the rules the JVM reads: a rule scoped to another `frontend` is skipped
before its numbers are read, so a Swift rule's `UInt64` in a shared file is no error here. A property,
argument index or constant property named twice after its aliases are resolved
(`callee_class` beside `callee.owner`, `0` beside `"0"`, `enum_type` beside `constant_type`)
is an error rather than a choice between the two, and so is the same key spelled twice in one
map: the YAML and the JSON reader refuse a duplicate member before the tree is built, where a
tree parser would have kept the last one.

## Limits

- The gate must be a call. A flag read from a field or a constant from a config file is not
  folded; name the accessor method instead.
- An argument is matched where it is a constant. A key that reaches the call through a
  parameter, a field or a computation is not one, and the call is reported, not folded.
- Folding is per call site. A gate stored in a local and tested later folds through the
  local; a gate stored in a field and read in another method does not.
- The staging graph's call-to-body edges are context-insensitive: a helper called from two
  places passes either caller's arguments to either caller's result, so a `select` path
  through a shared helper can name a gate the key never reaches at run time. Read the
  report's `selected` before trusting a broad query.
- Both Cypher engines split a query on every `;` before parsing it, inside string literals
  too, so a descriptor naming an object type (`(Ljava/lang/String;)Z`) cannot be written as
  a query literal, and the preview of a `match` rule that names such a descriptor does not
  parse. The keys a `select` resolves are read off the nodes and are not affected; in a
  query, compare a descriptor with `STARTS WITH` or `ENDS WITH` on a part without `;`.
- The graph names an array type by its base type and one `[]` whatever its dimensions
  (`int[][]` is `int[]`), in signatures and descriptors alike, so two overloads that differ
  only in an array's dimensions share a key. The fold pass names types by the same rule.
- Both graphs of a comparison must be built with the same file. A base built without the
  rule keeps the call sites the head loses.

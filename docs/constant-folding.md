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
   only it could reach is removed. A value written as `EnumConstant {enum_type, name}` becomes
   the read of that static field, which is what the constant is in bytecode; the method must
   return that enum and the enum must declare that constant.
2. **FoldBranches**: a local with exactly one definition, that definition a constant, is read
   as the constant; a `cmp`/`cmpl`/`cmpg` on two constants (how `long`, `float` and `double`
   comparisons compile) becomes its sign; an `if` whose condition then compares two constants,
   or two locals whose one definition each reads an enum constant (the same constant is `==`,
   different ones are not, neither is `null`), is resolved by wiring
   its predecessors to the side the condition takes and dropping the `if`; the statements no
   longer reachable from the method's entry are removed. The two steps repeat until nothing
   changes, so `x = a && gate()` folds through `x`. Removal happens inside the pass because
   SootUp validates the statement graph after every interceptor and a statement without a
   predecessor fails that check. SootUp's own `ConditionalBranchFolder` is not used: on 2.0.0
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
constants. `null == null` and `null != null` fold; `null` against a non-null reference, such
as `Boolean.TRUE` read from a field, does not, because a field is not a constant. An enum
constant folds `==` and `!=` against another enum constant or `null`; a `switch` on it goes
through `ordinal()` and a synthetic lookup array and is not folded.

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
| `select` | Instead of `match` and `args`: a Cypher query over a graph built without rules, returning one column of `CallSite` nodes |
| `selected` | Optional, under `select`: the keys of the call sites the query returned (`caller_signature`, `callee_signature`, `ordinal`), written by the CLI once it has run the query |
| `value` | The constant the call becomes |
| `frontend` | Optional. A rule naming another frontend is skipped; a rule without one must parse on every frontend, and a frontend that cannot parse it fails the build rather than ignoring it |

JSON carries the same keys. On the JVM:

- `CallSite` properties are `callee_class`, `callee_name`, `callee_signature`
  (`pkg.Cls.name(p1,p2)`), `caller_class`, `caller_name`, `caller_signature` and `ordinal`, as
  queries name them; the core names of `docs/architecture-frontend-backend.md` (`callee.owner`,
  `caller.signature`, ...) are accepted as aliases. A value containing `*` is a glob: `*`
  matches any run of characters. Every listed property must match. `ordinal` is the rank of the
  call among the invokes of the same callee in the calling method's bytecode, in statement
  order from `0`, every invoke, including the boxing and unboxing calls the graph shows as
  dataflow rather than as call sites; it is the number the graph's `CallSite.ordinal` holds, and
  the adapter and the fold pass compute it with one function over the unfiltered body, so
  `{caller_signature, callee_signature, ordinal}` names one call site and a triple read off a
  graph built without rules
  folds exactly that call (`CallSiteOrdinalTest`). `callee_class` is the class
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
rule's own `match` properties are bare YAML scalars. The query runs where the graph is, in the Rust CLI. `graphite build --fold <file>`:

1. asks the frontend to validate the file and print its rules as JSON (`graphite.jar fold
   plan <file>`), so an error in the file reads exactly as it does from `build --fold`;
2. when a rule has `select` and no `selected`, builds the graph without rules into a staging
   directory next to the output (`<output>.unfolded-<pid>`), with the same input and
   options;
3. runs every `select` on that graph with the Rust engine. Each row must have one column
   holding a `CallSite` node; its `caller_signature`, `callee_signature` and `ordinal` are
   the call site's key. A property, several columns, another node, or a graph without
   ordinals (built by an older frontend) is an error naming the rule, the row and what it
   held;
4. writes the plan with every `selected` filled in (`graph.folds.plan.json` in the staging
   directory) and builds again with `--fold` pointing at it, into the output the user
   named; the staging directory is removed either way.

In YAML, write the query as a block scalar (`select: >` or `select: |`) or quote it: a plain
scalar may not contain `: `, which every property map in a pattern does.

A file of `match` rules alone builds once, as before. A `select` rule with `selected` filled
in builds once too, on the CLI and on `graphite.jar build` alike: the report `graph.folds.json`
is such a file (`--fold graph.folds.json` reads its rules and drops the diagnostics beside
them), and so are keys written by hand from `RETURN cs.caller_signature,
cs.callee_signature, cs.ordinal`. `graphite.jar build` alone refuses an unresolved `select`
with the command to run instead, since it has no graph to run the query on.

The key names the same call in both builds because the second build reads the same bytecode
and the fold pass numbers the invokes of each body before it changes anything, exactly as the
adapter numbered the `CallSite` nodes of the first graph. The folded graph keeps that numbering
too: a body a rule folded in is numbered as it was before the fold, so a call the fold removed
leaves a gap and the calls that survive keep their ordinal, and a key read off the folded graph
(the report's `selected`, or a query on it) names the same call as one read off the unfolded
graph. Only a body no rule touched is numbered as it stands, which is the same numbering. A derived call (a lambda body
reached through a function value, numbered from `-1` downwards) has no invoke of its own and
is never a gate: a query that returns one is refused naming the row and the call site, and a
`selected` key with an ordinal below zero is refused by the frontend, rather than written
into a plan that would fold nothing. Select the call the function value was resolved from
(the `apply`, `invoke` or `get` on the value) instead. Rules apply in file order, so a
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
`matched` (calls folded), `statementsRemoved`, `sites` (per calling method: `caller`,
`calls`, `statementsBefore`, `statementsAfter`), `unsupported` (per call: `caller`, `reason`)
and `hints` (the near misses, at most eight per rule; for a `select`, the selected call sites
the build did not contain). A rule whose only matches were unsupported is not unmatched. The
report reads back as a fold file: the keys it adds beside a rule are dropped on load.

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
- Both graphs of a comparison must be built with the same file. A base built without the
  rule keeps the call sites the head loses.

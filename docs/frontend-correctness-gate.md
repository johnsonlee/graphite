# Frontend correctness gate

The frontend correctness gate detects semantic changes in the graphs produced from Java class
files, Kotlin class files, and Android DEX. It uses small source programs with deliberately chosen
values and a separately authored oracle. The oracle is not generated from SootUp or Graphite
output, so an importer regression cannot silently redefine the expected result.

## What counts as sufficient coverage

Coverage is an explicit, reviewable contract rather than a line or case count:

1. `schema-coverage.tsv` must contain every concrete Graphite node type and every edge-kind
   variant exactly once. Each obligation is either `covered`, with one or more executable semantic
   cases, or `gap`, with evidence explaining why the current producer cannot emit it.
2. Every frontend must cover the core semantic families: control flow, data flow, and invocation.
3. Every case in `coverage.tsv` must have at least one positive (`require`) and one negative
   (`forbid`) assertion in `expectations.tsv`. Negative assertions are important because a graph
   may retain the expected edge while also adding a wrong edge.
4. Assertions use stable semantic facts: method signatures, constants, branch membership, call
   arguments, and typed data-flow direction. Node IDs, local names, and statement order are
   intentionally excluded.
5. Known production defects and high-risk compiler lowerings get dedicated cases. A frontend bug
   fix is incomplete until its reproducer and semantic expectation are added permanently.
6. Fault-injection tests prove that the oracle rejects an inverted branch and a dropped call
   argument. This prevents a gate that is well populated but accidentally fail-open.

The test enforces both catalogues as closed sets. The schema inventory must exactly match the node
and edge kinds declared by the Graphite model, and the semantic catalogue and oracle must contain
exactly the same case IDs. Removing a schema obligation, removing a case, removing its positive or
negative half, or removing a core family from a frontend fails the build.

Verified defects in the current frontend are recorded separately in `known-deviations.tsv`; they
do not weaken or replace the ground-truth expectation. The gate accepts exactly those mismatches.
A new mismatch fails, and an allowlisted mismatch that starts passing also fails until its entry is
reviewed and removed. This makes an upgrade surface both regressions and upstream fixes.

## Current contract

The current gate has 60 executable semantic cases: 20 Java/JVM, 22 Kotlin/JVM, and 18
Android/DEX. These cases are evidence for 42 schema obligations: 28 are covered end to end and 14
are explicit producer gaps. The numbers are a summary, not the acceptance criterion; the exact
case-to-schema mapping is checked from the TSV catalogues.

| Frontend | Control flow | Data flow | Invocation and lowering |
| --- | --- | --- | --- |
| Java/JVM | relational branches, dense and sparse switches, exception path, returns | parameters, null and numeric constants, static/instance fields, arrays, resources | static/virtual/interface/constructor/recursive calls, capturing and non-capturing lambdas, annotations |
| Kotlin/JVM | relational branches, integer and string `when`, null/Elvis/safe-call, try/catch | parameters, numeric constants, object/instance fields, arrays | static/virtual/interface/constructor calls, capturing and non-capturing lambdas, extension lowering, suspend ABI, default-argument bridge |
| Android/DEX | boolean and relational branches, packed and sparse switches, returns | parameters, null and numeric constants, static/instance fields, arrays | static/virtual/interface/constructor/recursive calls, narrow and wide argument positions |

All 16 concrete node types are inventoried. Thirteen are currently emitted and covered:
`IntConstant`, `StringConstant`, `LongConstant`, `FloatConstant`, `DoubleConstant`, `NullConstant`,
`LocalVariable`, `FieldNode`, `ParameterNode`, `ReturnNode`, `ResourceFileNode`, `CallSiteNode`, and
`AnnotationNode`. The three node gaps are:

- `EnumConstant`: enum constants are retained only as annotation metadata.
- `BooleanConstant`: JVM and DEX booleans currently materialize as `IntConstant`.
- `ResourceValueNode`: resource values remain accessor metadata.

All 26 edge-kind variants are also inventoried. Fifteen are emitted and covered. The eleven edge
gaps are:

- `DataFlowEdge.CAST` and `PHI`: casts are flattened to `ASSIGN`, and the frontend is not in SSA.
- `CallEdge.STATIC` and `VIRTUAL`: ordinary calls create `CallSiteNode` and parameter-flow edges,
  but no call edge. `DYNAMIC` is covered by lambda targets.
- `TypeEdge.EXTENDS` and `IMPLEMENTS`: relations exist only in `TypeHierarchy` metadata.
- `ControlFlowEdge.SEQUENTIAL`, `SWITCH_CASE`, `SWITCH_DEFAULT`, `EXCEPTION`, and `RETURN`: the
  adapter currently emits only branch-membership control-flow edges.

These gaps are not test exclusions. They are machine-checked obligations documenting missing
Graphite producers. When support is implemented, the same change must replace `gap` with `covered`
and add source fixtures plus positive and negative oracle facts.

On SootUp 2.0.0, exact Android mismatches are explicit known deviations. Inspection of the
materialized Jimple shows static calls with empty narrow and wide argument lists, `sput`/`aput`
converted to loads, `iput` dropped, `const-null` represented as integer zero, and float/double
literals exposed as raw integer bit patterns. They remain ground-truth expectations, so a SootUp
upgrade must either fix them or continue to account for them explicitly.

This is a schema-complete inventory and an executable regression contract, not a claim that 60
programs cover every language behavior. Generic signatures, monitors, the complete Kotlin
coroutine state machine, and Android resource/component metadata still need separate semantic
matrices if those surfaces are used as upgrade criteria. New frontend features and every fixed
schema gap must add focused cases rather than relying on the current count.

## Adding or changing a case

1. Add the smallest source method that makes the semantic distinction observable.
2. Add a row to `coverage.tsv` naming the semantic family and the concrete risk.
3. Hand-author both `require` and `forbid` facts in `expectations.tsv` from the source semantics.
4. Reference the new case from the relevant `schema-coverage.tsv` obligations. Do not mark an
   obligation covered merely because its class exists in the model; the case must assert observable
   semantics produced by the adapter.
5. Demonstrate that the relevant historical defect or a local equivalent mutation makes the test
   fail, then verify the unmodified frontend passes.

If the unmodified frontend is already wrong, add the exact failing expectation to
`known-deviations.tsv` with evidence from the frontend IR. Never change the expectation to match
the defect. Remove the deviation in the same change that fixes it.

Never bulk-refresh `expectations.tsv` from captured output. Captured facts are printed only on
failure to help diagnose whether the implementation or the hand-authored expectation is wrong.

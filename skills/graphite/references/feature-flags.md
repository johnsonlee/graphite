# A/B test and feature flag touch points

A touch point is a call to a flag/experiment API: which flag (the key), where
(the calling method), and what it controls (the branch or value it feeds).

Recipes marked **[verified: ...]** were run on real graphs built from the named
artifacts. Unmarked recipes are syntax-checked only; confirm their rows in
source before relying on them.

## 1. Find the flag API

If you already know the client class, skip to step 2. Otherwise:

```cypher
// Methods whose names look like flag/experiment reads
MATCH (m:Method)
WHERE m.name IN ['isEnabled', 'isFeatureEnabled', 'isOn', 'enabled', 'getFlag',
                 'getVariant', 'getVariation', 'getTreatment', 'getExperiment',
                 'getBoolean', 'getBooleanValue', 'getStringValue', 'getIntValue',
                 'boolVariation', 'stringVariation', 'getFeatureValue', 'getOption',
                 'supportsFeature']
RETURN m.class, m.name, m.parameter_types, m.return_type
ORDER BY m.class
```

Known platform and vendor APIs among the call targets:

```cypher
MATCH (cs:CallSiteNode)
WHERE cs.callee_class IN ['android.util.FeatureFlagUtils',       // Android platform
                          'android.provider.DeviceConfig']
   OR cs.callee_class STARTS WITH 'com.launchdarkly.'           // vendors
   OR cs.callee_class STARTS WITH 'io.split.'
   OR cs.callee_class STARTS WITH 'com.optimizely.'
   OR cs.callee_class STARTS WITH 'io.getunleash.'
   OR cs.callee_class STARTS WITH 'dev.openfeature.'
   OR cs.callee_class STARTS WITH 'io.flagsmith.'
   OR cs.callee_class STARTS WITH 'com.statsig.'
   OR cs.callee_class STARTS WITH 'growthbook.'
   OR cs.callee_class STARTS WITH 'com.google.firebase.remoteconfig.'
   OR cs.callee_class =~ '(?i).*(feature|flag|toggle|experiment|abtest|ab\\.).*'
RETURN cs.callee_class, collect(DISTINCT cs.callee_name) AS apis, count(*) AS sites
ORDER BY sites DESC
```

[verified: Android `DeviceConfig`, 21 call sites.] Flags are also checked
through in-house APIs that match none of these names; the Kotlin compiler uses
`LanguageVersionSettings.supportsFeature(LanguageFeature)`. Once you find one
call, run "direct callers" (`control-flow.md`) on it to find the wrapper that
the rest of the code calls, and use the wrapper as the flag API.

## 2. Every touch point with its flag key

Keys arrive in two shapes, so run both halves. A `Constant` is a string/int
literal (including inlined `static final` values); a static `FieldNode` is an
enum value or a non-constant static field. Keep the two labeled halves joined by
`UNION`: a single unlabeled start node (`MATCH (key)-[:DATAFLOW*1..6]->...`)
scans the whole graph and timed out after 200 s on a 300k-node Android graph,
while this form answered in 0.4 s.

```cypher
MATCH (key:Constant)-[:DATAFLOW*1..6]->(cs:CallSiteNode)
WHERE cs.callee_class = 'com.example.ab.FlagClient'
  AND cs.callee_name IN ['isEnabled', 'getVariant']
RETURN cs.caller_signature AS touchPoint, id(cs) AS site, cs.callee_name AS api,
       collect(DISTINCT toString(key.value)) AS args
UNION
MATCH (key:FieldNode)-[:DATAFLOW*1..6]->(cs:CallSiteNode)
WHERE cs.callee_class = 'com.example.ab.FlagClient'
  AND cs.callee_name IN ['isEnabled', 'getVariant']
  AND key.static AND NOT key.name IN ['INSTANCE', 'Companion']
RETURN cs.caller_signature AS touchPoint, id(cs) AS site, cs.callee_name AS api,
       collect(DISTINCT key.class + '.' + key.name) AS args
```

[verified: fixture string/int keys; Kotlin compiler `LanguageFeature.*` enum
keys (all arrive as `FieldNode`, none as `Constant`); Android `DeviceConfig`.]

Reading the result:

- **One row per call site, all constant arguments together.** The graph does not
  say which argument position a value came from, so multi-argument APIs mix
  namespace, key and default: `DeviceConfig.getBoolean` gives
  `['0', 'privacy', 'safety_protection_enabled']`. Tell them apart by the API's
  signature (`m.parameter_types`) and by value shape.
- **JVM booleans are ints**: a `false`/`true` default shows up as `0`/`1`.
- `INSTANCE` / `Companion` fields are Kotlin objects used as the receiver, not
  keys, hence the filter.
- Inlined `static final` keys come back as the literal. Map a literal to its
  constant name in source if needed.

Call sites with no key at all get it at runtime (a parameter, a map, a remote
list). List them:

```cypher
MATCH (cs:CallSiteNode)
WHERE cs.callee_class = 'com.example.ab.FlagClient' AND cs.callee_name = 'isEnabled'
OPTIONAL MATCH (c:Constant)-[:DATAFLOW*1..6]->(cs)
WITH cs, count(c) AS constants
OPTIONAL MATCH (f:FieldNode)-[:DATAFLOW*1..6]->(cs)
WHERE f.static AND NOT f.name IN ['INSTANCE', 'Companion']
WITH cs, constants, count(f) AS fields
WHERE constants + fields = 0
RETURN cs.caller_signature
```

The `INSTANCE`/`Companion` filter matters here too: without it, a singleton
receiver counts as a key and wrappers such as
`wrapper(String key) { return INSTANCE.isEnabled(key); }` disappear.

[verified: Android.] These are usually the wrapper layer itself (on Android,
`DeviceConfig.getBoolean(String,String,boolean)` calling `getProperty`). Treat
the wrapper as the flag API and rerun step 2 on it; otherwise follow the
caller's `ParameterNode` up to its callers (`data-flow.md`, "Crossing method
boundaries"), or use the Kotlin `findArgumentConstants` query, which does the
interprocedural slice for you.

## 3. Inventory: flags per class, classes per flag

Aggregate the step 2 rows by key: the number of distinct touch points and
calling classes per flag. Flags used in many places are the riskiest to change
or remove.

## 4. What does each flag control?

Anchor on the flag call and follow its result into branches:

```cypher
MATCH (gate:CallSiteNode)-[:DATAFLOW*1..3]->(cond)-[b:CONTROL_FLOW]->(first)
WHERE gate.callee_class = 'com.example.ab.FlagClient' AND gate.callee_name = 'isEnabled'
OPTIONAL MATCH (first)<-[:DATAFLOW]-(guarded:CallSiteNode)
RETURN gate.caller_signature, id(gate) AS site, b.kind,
       labels(first)[0] AS firstNode, guarded.callee_signature AS guardedCall
```

[verified: fixture; Kotlin compiler `LanguageFeature.MultiPlatformProjects`
guards `getModuleData` in `prepareJvm/Js/Native/Wasm/CommonSessions`.] Join
`site` to the step 2 result to label each row with its flag.

Limits you must state when reporting:

- **Only the first node of each branch is in Cypher.** The result names the first
  call in the branch, not everything the branch runs. The full branch contents
  exist only in the Kotlin API (`BranchReachabilityAnalysis`, see `more-recipes.md`);
  otherwise read the method's source around the call.
- **Polarity is JVM-level**: `BRANCH_TRUE` is often the source `else`. State
  "guarded by flag X" as a fact; state which path is the treatment only after
  reading the source.
- A variant that is only compared (`variant == 1 ? a : b`) shows up with a
  `LocalVariable` as `firstNode` and no `guardedCall`.

Variants passed on as values rather than branched on:

```cypher
MATCH (gate:CallSiteNode {callee_class: 'com.example.ab.FlagClient', callee_name: 'getVariant'})
      -[:DATAFLOW*1..6]->(use:CallSiteNode)
RETURN gate.caller_signature, use.callee_signature
```

When the flag result is returned from a helper (`boolean useNewCheckout()`), hop
through the `ReturnNode` to the helper's callers (`data-flow.md`) and repeat.

## 5. Flags held in fields and options

Many switches are read once into a field (an injected property, a parsed
command-line option, a cached flag) and then branched on. Find boolean fields
that guard branches:

```cypher
MATCH (f:FieldNode)-[e:DATAFLOW]->(v)-[:DATAFLOW*0..2]->(cond)-[b:CONTROL_FLOW]->(first)
WHERE e.kind = 'FIELD_LOAD' AND f.type = 'boolean' AND f.class STARTS WITH 'com.example'
RETURN f.class + '.' + f.name AS field, count(DISTINCT cond.method) AS guardedMethods
ORDER BY guardedMethods DESC
```

[verified: Kotlin compiler, e.g. `K2JVMCompilerArguments.useOldBackend`,
`CommonCompilerArguments.suppressVersionWarnings`.] Kotlin properties are often
read through getters (`getReportPerf()`) instead of the field; for those, anchor
on the getter call site as the `gate` in step 4. To tie a field back to the
switch that sets it (a `@Value` key, a `-X` option), see `configuration.md`
steps 4 and 5.

Annotation-driven toggles (Spring `@ConditionalOnProperty`,
`@ConditionalOnExpression`, `@Profile`, in-house `@FeatureToggle`) switch whole
beans on or off, with no branch in code:

```cypher
MATCH (a:Annotation)
WHERE a.name IN ['org.springframework.boot.autoconfigure.condition.ConditionalOnProperty',
                 'org.springframework.boot.autoconfigure.condition.ConditionalOnExpression',
                 'org.springframework.context.annotation.Profile']
   OR a.name =~ '(?i).*(feature|toggle|flag|experiment).*'
RETURN a.name, a.class, a.member, a.value, a.prefix, a.havingValue
```

## 6. Flags defined in configuration

Some flags are plain config keys rather than SDK calls. Run
`configuration.md` step 3 and filter the keys:

```cypher
MATCH (k:StringConstant)-[:DATAFLOW*1..3]->(cs:CallSiteNode)
WHERE k.value =~ '(?i).*(feature|flag|toggle|enable|experiment|rollout).*'
RETURN k.value, cs.callee_signature, cs.caller_signature
```

Also search packaged config files (`resources` + `resource`) for the same words.
Values served remotely (a flag service, `DeviceConfig`, Firebase Remote Config)
are not in the artifact: the graph shows where they are read, never their values.

## 7. Cleanup: dead or fully rolled-out flags

Given a flag assumed permanently on/off, the Kotlin
`BranchReachabilityAnalysis` computes the dead branches, dead call sites and the
methods reachable only from them. From Cypher, step 4 lists the first guarded
call per branch; list its other callers (`control-flow.md`) to see whether it
becomes unreachable once the flag is removed.

## Output template

| Flag | API | Touch point (method) | Controls | Evidence |
|---|---|---|---|---|
| `checkout.new_flow` | `FlagClient.isEnabled` | `CheckoutService.checkout(String)` | branch → `newFlow` / `legacyFlow` | query + rows |

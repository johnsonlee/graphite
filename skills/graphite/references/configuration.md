# Configuration

Recipes marked **[verified: ...]** were run on real graphs built from the named
artifacts. Unmarked recipes are syntax-checked only; confirm their rows in
source before relying on them.

Configuration reaches code through several doors, each with a different graph
shape. Check all that apply:

| Source | Graph evidence |
|---|---|
| Packaged files (`.properties`, `.yml`, `.json`, `.xml`, resource bundles) | `ResourceFile` nodes and `RESOURCE_*` edges to the call sites that open, load or read them; file contents via `resource` |
| Environment variables, JVM system properties | key constant → `System.getenv` / `System.getProperty` / `Boolean.getBoolean` ... |
| Config APIs (Spring `Environment`, Typesafe, Hadoop/Hive `Configuration`, Android `SystemProperties`, `Settings`, `SharedPreferences`) | key constant or key field → getter call site |
| Framework injection (Spring `@Value`, `@ConfigurationProperties`, Micronaut) | `Annotation` nodes carrying the key |
| Command-line options (annotated option fields, hand-written `args[]` parsing) | `Annotation` on the option field → getter/field reads |

## 1. Inventory packaged config files

```cypher
MATCH (f:ResourceFile)
WHERE f.format IN ['properties', 'yaml', 'json', 'xml']
RETURN f.path, f.format, f.profile, f.source
ORDER BY f.path
```

`profile` is filled for profile-specific files such as `application-prod.yml`.
Read one with the `resource` tool (`path='application.yml'`) or list with
`resources(pattern='**/*.yml')`.

If the graph has `ResourceValue` nodes (check `schema`; graphs from graphite
2.8.0 have none), keys are queryable directly:

```cypher
MATCH (r:ResourceValue)
WHERE r.key STARTS WITH 'payment.'
RETURN r.path, r.key, r.value, r.profile
```

Otherwise read the file with `resource` and search it for the key; flatten
nested YAML/JSON keys yourself. Which file wins at runtime (profiles, external
overrides, precedence) is not modeled.

## 2. Which code opens and reads each file

```cypher
MATCH (f:ResourceFile)-[e]->(cs:CallSiteNode)
RETURN f.path, type(e) AS access, cs.caller_signature, cs.callee_signature
ORDER BY f.path
```

`RESOURCE_OPEN` is the `getResource*` call, `RESOURCE_LOAD` the parser/loader
(`Properties.load`, `ResourceBundle.getBundle`), `RESOURCE_LOOKUP` a concrete key
read (`getProperty`, `getString`, `getObject`), `RESOURCE_KEYS` an enumeration.

**Check these edges before reporting them.** On graphs from graphite 2.8.0,
`System.getProperty(...)`, which reads a JVM system property, is linked by
`RESOURCE_LOOKUP` to packaged `.properties` files. [verified: on the Kotlin
compiler (195 edges) and Tika (704 edges) graphs, every `RESOURCE_LOOKUP` edge
was such a call; Tika's `System.getProperty("tika.config")` is linked to 352
files.] Graphs rebuilt with a frontend that includes
[#160](https://github.com/johnsonlee/graphite/pull/160) have no such edges; a
graph persisted earlier keeps them until it is rebuilt. Dropping lookups whose
`callee_class` is `java.lang.System` is correct on both. Unbound `Properties`
lookups still link to every configuration resource in all versions, so treat a
call site linked to many files as a candidate set, not a fact:

```cypher
MATCH (f:ResourceFile)-[e]->(cs:CallSiteNode)
WHERE cs.callee_class <> 'java.lang.System'
WITH cs, type(e) AS access, collect(DISTINCT f.path) AS files
RETURN cs.caller_signature, cs.callee_signature, access, size(files) AS fanOut, files[0..5]
ORDER BY fanOut
```

## 3. Key → reader, for a specific key

```cypher
// Packaged files: which key is read where
MATCH (f:ResourceFile)-[:RESOURCE_LOOKUP]->(cs:CallSiteNode)<-[:DATAFLOW]-(k:StringConstant)
WHERE cs.callee_class <> 'java.lang.System'
RETURN f.path, k.value AS key, cs.caller_signature
```

```cypher
// Every string-keyed config read, whatever the backing store
MATCH (k:StringConstant)-[:DATAFLOW*1..3]->(cs:CallSiteNode)
WHERE (cs.callee_class = 'java.lang.System' AND cs.callee_name IN ['getenv', 'getProperty'])
   OR (cs.callee_class IN ['java.lang.Boolean', 'java.lang.Integer', 'java.lang.Long']
       AND cs.callee_name IN ['getBoolean', 'getInteger', 'getLong'])
   OR (cs.callee_class IN ['java.util.Properties', 'java.util.ResourceBundle']
       AND cs.callee_name IN ['getProperty', 'getString', 'getObject'])
   OR (cs.callee_class IN ['android.os.SystemProperties', 'android.provider.DeviceConfig',
                           'android.provider.Settings$Global', 'android.provider.Settings$Secure',
                           'android.provider.Settings$System', 'android.content.SharedPreferences']
       AND cs.callee_name STARTS WITH 'get')
   OR (cs.callee_class STARTS WITH 'org.springframework.core.env.'
       AND cs.callee_name IN ['getProperty', 'getRequiredProperty', 'containsProperty'])
   OR (cs.callee_class STARTS WITH 'com.typesafe.config.'
       AND cs.callee_name STARTS WITH 'get')
   OR (cs.callee_class = 'org.apache.hadoop.conf.Configuration'
       AND cs.callee_name STARTS WITH 'get')
RETURN cs.callee_class, cs.callee_name, k.value AS key, cs.caller_signature
ORDER BY key
```

[verified: fixture; Kotlin compiler (`KOTLIN_REPORT_PERF`,
`kotlin.incremental.compilation`, `kotlin.home`, ...); Android
(`ro.sf.lcd_density`, `persist.sys.binary_xml`, `Settings$Global` `ntp_server`,
`Settings$System` `font_scale`, ...).] Multi-argument getters return the
namespace and default with the key, as in `feature-flags.md` step 2.

Add the project's own config facade (e.g. `com.example.config.AppConfig.get*`)
to the `WHERE`: find it with the outbound grouping query in
`entrypoints-and-boundaries.md` or by searching `Method` names such as
`getConfig`, `getString`, `getInt`.

**Keys held in enums.** Some config APIs take an enum constant instead of a string
(Hive `HiveConf.getVar(conf, ConfVars.X)`). The key then reaches the getter as a
static `FieldNode`:

```cypher
MATCH (key:FieldNode)-[:DATAFLOW*1..4]->(cs:CallSiteNode)
WHERE cs.callee_class = 'org.apache.hadoop.hive.conf.HiveConf'
  AND cs.callee_name IN ['getVar', 'getBoolVar', 'getIntVar', 'getLongVar', 'getTimeVar']
  AND key.static AND key.class = 'org.apache.hadoop.hive.conf.HiveConf$ConfVars'
WITH key.name AS confVar, collect(DISTINCT cs.caller_signature) AS readers
RETURN confVar, size(readers) AS readerCount, readers[0..5] AS sample
ORDER BY readerCount DESC
```

[verified: Hive, e.g. `HIVE_IN_TEST`, `LOCAL_SCRATCH_DIR`, `HIVE_SESSION_ID`.]
The key string (`hive.in.test`) is the enum's constructor argument; read it in
source.

Some keys are built at runtime (`prefix + name`); the concatenation shows up as a
`makeConcatWithConstants` call site. Trace the pieces backward with
`data-flow.md` recipes and report the key as a pattern.

## 4. Framework-injected configuration

```cypher
MATCH (a:Annotation)
WHERE a.name IN ['org.springframework.beans.factory.annotation.Value',
                 'org.springframework.boot.context.properties.ConfigurationProperties',
                 'io.micronaut.context.annotation.Value',
                 'io.micronaut.context.annotation.Property']
RETURN a.name, a.class, a.member, a.value, a.prefix
```

`@Value("${payment.timeout:3000}")` on a field gives the key and default in
`a.value`, and `a.class` + `a.member` is the field that holds it; continue with
step 6 from that field. `@ConfigurationProperties(prefix = "payment")` binds
every `payment.*` key to the fields of `a.class` by name.

## 5. Command-line options

Option parsers declare options with annotations on fields: picocli `@Option`,
JCommander `@Parameter`, args4j `@Option`, kotlinx-cli, or an in-house one (the
Kotlin compiler's `@Argument`). Find the vocabulary with the annotation histogram
in `entrypoints-and-boundaries.md`, then map each option to its field and to the
code that reads it:

```cypher
MATCH (a:Annotation {name: 'org.jetbrains.kotlin.cli.common.arguments.Argument'})
WHERE a.value IN ['-Xreport-perf', '-language-version']
WITH a, 'get' + toUpper(substring(a.member, 0, 1)) + substring(a.member, 1) AS getter
MATCH (cs:CallSiteNode)
WHERE cs.callee_name = getter
  AND cs.callee_class ENDS WITH 'CompilerArguments'
  AND NOT cs.caller_name STARTS WITH 'copy'
RETURN DISTINCT a.value AS option, a.class + '.' + a.member AS field, cs.caller_signature
```

[verified: Kotlin compiler, 317 `@Argument` options; `-Xreport-perf` →
`CommonCompilerArguments.reportPerf` → read in `CLICompiler.execImpl`, where it
guards `getDumpPerf()`.]

- Kotlin properties are read through getters (`getReportPerf()`, `isX()` for
  booleans named `isX`); Java fields may be read directly
  (`MATCH (f:FieldNode {class: ..., name: ...})-[:DATAFLOW*1..3]->(use)`).
- **Always constrain the getter's class.** Matching the getter name alone is
  wrong: `getLanguageVersion` also matched `LanguageVersionSettingsImpl`. Use the
  annotated class, or a pattern covering its subclasses as above (type
  hierarchy is not queryable).
- Generated copy/serialization code (`copy*`, `toArgumentStrings`) reads every
  field; exclude it.

Hand-written parsing (`args[i].equals("--verbose")`) shows up as string
constants flowing into `String.equals`/`startsWith` call sites in the entry
method; start from `main` and trace forward.

## 6. Where does a config value go?

Take a reader call site (or the field holding the value) and trace forward
(`data-flow.md`, "Forward"); join through `ReturnNode` when it is wrapped in a
getter. The usual destinations are a field (long-lived setting), a branch
condition (a toggle, see `feature-flags.md` steps 4 and 5) or an outbound call
(endpoint URL, timeout).

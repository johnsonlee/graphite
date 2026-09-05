# Verified multi-keyword CallSite workload

Unlabeled MATCH (n): frozen-main NodePropertyAccessor.kt:73-102 and 123-132 expose these four fields on CallSiteNode only, except AnnotationNode dynamic values at 216-221. The independent annotation census verified 0 AnnotationNodes across all 64 graphs. All other node types return null; coalesce(null, '') and nonempty CONTAINS keywords are false.

Each keyword matches any of four lowercased CallSite properties; AND operands bind to the same node. Complete per-graph counts are BEFORE LIMIT. DISTINCT globally deduplicates the four projected strings across graphs; expected rows separately record all contributing graph IDs for each selected distinct tuple. Order is manifest order then Graph.nodes(CallSiteNode) order.

Frozen main: `4e328b0109e13c896b74004823fb049fcb19251a`. Scanned 5,046,935 real CallSite nodes across 64 distinct persisted graph paths; export 30,944,773 bytes gzip. No Cypher engine used for derivation or ground truth.

18 logical predicates, expanded into 36 queries with DISTINCT/non-DISTINCT and LIMIT 200. Single-hit positions are zero-based 0, 31, 63; each full hit set was asserted before writing this catalog. No primary predicate contains duplicate or substring-related keywords.

| Case | Logic | Hit positions | Matches before LIMIT | DISTINCT tuples |
|---|---|---|---:|---:|
| or-single-early | ['or', 0, 1] | 0 | 679 | 425 |
| and-single-early | ['and', 0, 1] | 0 | 200 | 50 |
| or-single-middle | ['or', 0, 1] | 31 | 2553 | 2333 |
| and-single-middle | ['and', 0, 1] | 31 | 604 | 468 |
| or-single-late | ['or', 0, 1] | 63 | 293 | 201 |
| and-single-late | ['and', 0, 1] | 63 | 29 | 21 |
| or-few-early-late | ['or', 0, 1] | 0,63 | 962 | 618 |
| or-few-early-middle | ['or', 0, 1] | 0,31 | 3220 | 2748 |
| or-broad-all | ['or', 0, 1] | 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63 | 2002394 | 1504861 |
| and-broad-all | ['and', 0, 1] | 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63 | 261292 | 195156 |
| mixed-four-few | ['or', ['and', 0, 1], ['and', 2, 3]] | 0,63 | 229 | 71 |
| and-zero-disjoint-graphs | ['and', 0, 1] | none | 0 | 0 |
| or-four-broad | ['or', 0, 1, 2, 3] | 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,54,55,56,57,58,62,63 | 50461 | 18915 |
| or-four-single-early | ['or', 0, 1, 2, 3] | 0 | 704 | 444 |
| or-four-single-middle | ['or', 0, 1, 2, 3] | 31 | 2646 | 2356 |
| or-four-single-late | ['or', 0, 1, 2, 3] | 63 | 299 | 206 |
| or-four-few-early-late | ['or', 0, 1, 2, 3] | 0,63 | 972 | 626 |
| or-four-all | ['or', 0, 1, 2, 3] | 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63 | 2455554 | 1771173 |

## Keywords and full query text

### or-single-early

- `com.android.internal.app.iappopsservice$stub$proxy`
- `com.android.server.permission.jarjar.kotlin.io.path.pathskt__pathrecursivefunctionskt$copytorecursively$3`

```cypher
MATCH (n) WHERE ((toLower(coalesce(n.caller_class, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'com.android.server.permission.jarjar.kotlin.io.path.pathskt__pathrecursivefunctionskt$copytorecursively$3' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'com.android.server.permission.jarjar.kotlin.io.path.pathskt__pathrecursivefunctionskt$copytorecursively$3' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'com.android.server.permission.jarjar.kotlin.io.path.pathskt__pathrecursivefunctionskt$copytorecursively$3' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'com.android.server.permission.jarjar.kotlin.io.path.pathskt__pathrecursivefunctionskt$copytorecursively$3')) RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200
```

### and-single-early

- `com.android.internal.app.iappopsservice$stub$proxy`
- `recycle`

```cypher
MATCH (n) WHERE ((toLower(coalesce(n.caller_class, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy') AND (toLower(coalesce(n.caller_class, '')) CONTAINS 'recycle' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'recycle' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'recycle' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'recycle')) RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200
```

### or-single-middle

- `org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl`
- `net.bytebuddy.dynamic.scaffold.typewriter$default$forinlining$withfullprocessing$initializationhandler$appending$framewriter$expanding`

```cypher
MATCH (n) WHERE ((toLower(coalesce(n.caller_class, '')) CONTAINS 'org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'net.bytebuddy.dynamic.scaffold.typewriter$default$forinlining$withfullprocessing$initializationhandler$appending$framewriter$expanding' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'net.bytebuddy.dynamic.scaffold.typewriter$default$forinlining$withfullprocessing$initializationhandler$appending$framewriter$expanding' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'net.bytebuddy.dynamic.scaffold.typewriter$default$forinlining$withfullprocessing$initializationhandler$appending$framewriter$expanding' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'net.bytebuddy.dynamic.scaffold.typewriter$default$forinlining$withfullprocessing$initializationhandler$appending$framewriter$expanding')) RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200
```

### and-single-middle

- `org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl`
- `get_store`

```cypher
MATCH (n) WHERE ((toLower(coalesce(n.caller_class, '')) CONTAINS 'org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl') AND (toLower(coalesce(n.caller_class, '')) CONTAINS 'get_store' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'get_store' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'get_store' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'get_store')) RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200
```

### or-single-late

- `org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1`
- `org.jetbrains.kotlin.fir.backend.generators.fir2irlazyfakeoverridegenerator$choosemostspecificoverridden$lambda$7$lambda$6$$inlined$anyoverriddenof$1`

```cypher
MATCH (n) WHERE ((toLower(coalesce(n.caller_class, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'org.jetbrains.kotlin.fir.backend.generators.fir2irlazyfakeoverridegenerator$choosemostspecificoverridden$lambda$7$lambda$6$$inlined$anyoverriddenof$1' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'org.jetbrains.kotlin.fir.backend.generators.fir2irlazyfakeoverridegenerator$choosemostspecificoverridden$lambda$7$lambda$6$$inlined$anyoverriddenof$1' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'org.jetbrains.kotlin.fir.backend.generators.fir2irlazyfakeoverridegenerator$choosemostspecificoverridden$lambda$7$lambda$6$$inlined$anyoverriddenof$1' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'org.jetbrains.kotlin.fir.backend.generators.fir2irlazyfakeoverridegenerator$choosemostspecificoverridden$lambda$7$lambda$6$$inlined$anyoverriddenof$1')) RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200
```

### and-single-late

- `org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1`
- `visitstatementcontainer`

```cypher
MATCH (n) WHERE ((toLower(coalesce(n.caller_class, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1') AND (toLower(coalesce(n.caller_class, '')) CONTAINS 'visitstatementcontainer' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'visitstatementcontainer' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'visitstatementcontainer' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'visitstatementcontainer')) RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200
```

### or-few-early-late

- `com.android.internal.app.iappopsservice$stub$proxy`
- `org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1`

```cypher
MATCH (n) WHERE ((toLower(coalesce(n.caller_class, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1')) RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200
```

### or-few-early-middle

- `com.android.internal.app.iappopsservice$stub$proxy`
- `org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl`

```cypher
MATCH (n) WHERE ((toLower(coalesce(n.caller_class, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl')) RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200
```

### or-broad-all

- `get`
- `set`

```cypher
MATCH (n) WHERE ((toLower(coalesce(n.caller_class, '')) CONTAINS 'get' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'get' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'get' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'get') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'set' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'set' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'set' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'set')) RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200
```

### and-broad-all

- `java.lang`
- `<init>`

```cypher
MATCH (n) WHERE ((toLower(coalesce(n.caller_class, '')) CONTAINS 'java.lang' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'java.lang' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'java.lang' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'java.lang') AND (toLower(coalesce(n.caller_class, '')) CONTAINS '<init>' OR toLower(coalesce(n.caller_name, '')) CONTAINS '<init>' OR toLower(coalesce(n.callee_class, '')) CONTAINS '<init>' OR toLower(coalesce(n.callee_name, '')) CONTAINS '<init>')) RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200
```

### mixed-four-few

- `com.android.internal.app.iappopsservice$stub$proxy`
- `recycle`
- `org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1`
- `visitstatementcontainer`

```cypher
MATCH (n) WHERE (((toLower(coalesce(n.caller_class, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy') AND (toLower(coalesce(n.caller_class, '')) CONTAINS 'recycle' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'recycle' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'recycle' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'recycle')) OR ((toLower(coalesce(n.caller_class, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1') AND (toLower(coalesce(n.caller_class, '')) CONTAINS 'visitstatementcontainer' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'visitstatementcontainer' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'visitstatementcontainer' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'visitstatementcontainer'))) RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200
```

### and-zero-disjoint-graphs

- `com.android.internal.app.iappopsservice$stub$proxy`
- `org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1`

```cypher
MATCH (n) WHERE ((toLower(coalesce(n.caller_class, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy') AND (toLower(coalesce(n.caller_class, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1')) RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200
```

### or-four-broad

- `com.android.internal.app.iappopsservice$stub$proxy`
- `recycle`
- `org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1`
- `visitstatementcontainer`

```cypher
MATCH (n) WHERE ((toLower(coalesce(n.caller_class, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'recycle' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'recycle' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'recycle' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'recycle') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'visitstatementcontainer' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'visitstatementcontainer' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'visitstatementcontainer' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'visitstatementcontainer')) RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200
```

### or-four-single-early

- `com.android.internal.app.iappopsservice$stub$proxy`
- `com.android.server.permission.jarjar.kotlin.io.path.pathskt__pathrecursivefunctionskt$copytorecursively$3`
- `com.android.internal.org.bouncycastle.jcajce.provider.asymmetric.dsa.algorithmparametergeneratorspi`
- `android.media.internal.guava_common.util.concurrent.closingfuture$combiner$asynccombiningcallable`

```cypher
MATCH (n) WHERE ((toLower(coalesce(n.caller_class, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'com.android.server.permission.jarjar.kotlin.io.path.pathskt__pathrecursivefunctionskt$copytorecursively$3' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'com.android.server.permission.jarjar.kotlin.io.path.pathskt__pathrecursivefunctionskt$copytorecursively$3' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'com.android.server.permission.jarjar.kotlin.io.path.pathskt__pathrecursivefunctionskt$copytorecursively$3' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'com.android.server.permission.jarjar.kotlin.io.path.pathskt__pathrecursivefunctionskt$copytorecursively$3') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'com.android.internal.org.bouncycastle.jcajce.provider.asymmetric.dsa.algorithmparametergeneratorspi' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'com.android.internal.org.bouncycastle.jcajce.provider.asymmetric.dsa.algorithmparametergeneratorspi' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'com.android.internal.org.bouncycastle.jcajce.provider.asymmetric.dsa.algorithmparametergeneratorspi' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'com.android.internal.org.bouncycastle.jcajce.provider.asymmetric.dsa.algorithmparametergeneratorspi') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'android.media.internal.guava_common.util.concurrent.closingfuture$combiner$asynccombiningcallable' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'android.media.internal.guava_common.util.concurrent.closingfuture$combiner$asynccombiningcallable' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'android.media.internal.guava_common.util.concurrent.closingfuture$combiner$asynccombiningcallable' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'android.media.internal.guava_common.util.concurrent.closingfuture$combiner$asynccombiningcallable')) RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200
```

### or-four-single-middle

- `org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl`
- `net.bytebuddy.dynamic.scaffold.typewriter$default$forinlining$withfullprocessing$initializationhandler$appending$framewriter$expanding`
- `net.bytebuddy.dynamic.classfilelocator$forinstrumentation$classloadingdelegate$fordelegatingclassloader$dispatcher$creationaction`
- `net.bytebuddy.agent.builder.agentbuilder$lambdainstrumentationstrategy$lambdametafactoryfactory$loader$usingmethodhandlelookup`

```cypher
MATCH (n) WHERE ((toLower(coalesce(n.caller_class, '')) CONTAINS 'org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'org.openxmlformats.schemas.spreadsheetml.x2006.main.impl.ctpivottabledefinitionimpl') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'net.bytebuddy.dynamic.scaffold.typewriter$default$forinlining$withfullprocessing$initializationhandler$appending$framewriter$expanding' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'net.bytebuddy.dynamic.scaffold.typewriter$default$forinlining$withfullprocessing$initializationhandler$appending$framewriter$expanding' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'net.bytebuddy.dynamic.scaffold.typewriter$default$forinlining$withfullprocessing$initializationhandler$appending$framewriter$expanding' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'net.bytebuddy.dynamic.scaffold.typewriter$default$forinlining$withfullprocessing$initializationhandler$appending$framewriter$expanding') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'net.bytebuddy.dynamic.classfilelocator$forinstrumentation$classloadingdelegate$fordelegatingclassloader$dispatcher$creationaction' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'net.bytebuddy.dynamic.classfilelocator$forinstrumentation$classloadingdelegate$fordelegatingclassloader$dispatcher$creationaction' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'net.bytebuddy.dynamic.classfilelocator$forinstrumentation$classloadingdelegate$fordelegatingclassloader$dispatcher$creationaction' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'net.bytebuddy.dynamic.classfilelocator$forinstrumentation$classloadingdelegate$fordelegatingclassloader$dispatcher$creationaction') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'net.bytebuddy.agent.builder.agentbuilder$lambdainstrumentationstrategy$lambdametafactoryfactory$loader$usingmethodhandlelookup' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'net.bytebuddy.agent.builder.agentbuilder$lambdainstrumentationstrategy$lambdametafactoryfactory$loader$usingmethodhandlelookup' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'net.bytebuddy.agent.builder.agentbuilder$lambdainstrumentationstrategy$lambdametafactoryfactory$loader$usingmethodhandlelookup' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'net.bytebuddy.agent.builder.agentbuilder$lambdainstrumentationstrategy$lambdametafactoryfactory$loader$usingmethodhandlelookup')) RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200
```

### or-four-single-late

- `org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1`
- `org.jetbrains.kotlin.fir.backend.generators.fir2irlazyfakeoverridegenerator$choosemostspecificoverridden$lambda$7$lambda$6$$inlined$anyoverriddenof$1`
- `org.jetbrains.kotlin.ir.backend.js.export.exportmodelgeneratorkt$isallowedfakeoverriddendeclaration$$inlined$filterisinstance$1`
- `org.jetbrains.kotlin.ir.backend.js.lower.booleanpropertyinexternallowering$externalbooleanpropertyprocessor$whenmappings`

```cypher
MATCH (n) WHERE ((toLower(coalesce(n.caller_class, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'org.jetbrains.kotlin.fir.backend.generators.fir2irlazyfakeoverridegenerator$choosemostspecificoverridden$lambda$7$lambda$6$$inlined$anyoverriddenof$1' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'org.jetbrains.kotlin.fir.backend.generators.fir2irlazyfakeoverridegenerator$choosemostspecificoverridden$lambda$7$lambda$6$$inlined$anyoverriddenof$1' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'org.jetbrains.kotlin.fir.backend.generators.fir2irlazyfakeoverridegenerator$choosemostspecificoverridden$lambda$7$lambda$6$$inlined$anyoverriddenof$1' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'org.jetbrains.kotlin.fir.backend.generators.fir2irlazyfakeoverridegenerator$choosemostspecificoverridden$lambda$7$lambda$6$$inlined$anyoverriddenof$1') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'org.jetbrains.kotlin.ir.backend.js.export.exportmodelgeneratorkt$isallowedfakeoverriddendeclaration$$inlined$filterisinstance$1' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'org.jetbrains.kotlin.ir.backend.js.export.exportmodelgeneratorkt$isallowedfakeoverriddendeclaration$$inlined$filterisinstance$1' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'org.jetbrains.kotlin.ir.backend.js.export.exportmodelgeneratorkt$isallowedfakeoverriddendeclaration$$inlined$filterisinstance$1' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'org.jetbrains.kotlin.ir.backend.js.export.exportmodelgeneratorkt$isallowedfakeoverriddendeclaration$$inlined$filterisinstance$1') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'org.jetbrains.kotlin.ir.backend.js.lower.booleanpropertyinexternallowering$externalbooleanpropertyprocessor$whenmappings' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'org.jetbrains.kotlin.ir.backend.js.lower.booleanpropertyinexternallowering$externalbooleanpropertyprocessor$whenmappings' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'org.jetbrains.kotlin.ir.backend.js.lower.booleanpropertyinexternallowering$externalbooleanpropertyprocessor$whenmappings' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'org.jetbrains.kotlin.ir.backend.js.lower.booleanpropertyinexternallowering$externalbooleanpropertyprocessor$whenmappings')) RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200
```

### or-four-few-early-late

- `com.android.internal.app.iappopsservice$stub$proxy`
- `com.android.server.permission.jarjar.kotlin.io.path.pathskt__pathrecursivefunctionskt$copytorecursively$3`
- `org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1`
- `org.jetbrains.kotlin.fir.backend.generators.fir2irlazyfakeoverridegenerator$choosemostspecificoverridden$lambda$7$lambda$6$$inlined$anyoverriddenof$1`

```cypher
MATCH (n) WHERE ((toLower(coalesce(n.caller_class, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'com.android.internal.app.iappopsservice$stub$proxy') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'com.android.server.permission.jarjar.kotlin.io.path.pathskt__pathrecursivefunctionskt$copytorecursively$3' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'com.android.server.permission.jarjar.kotlin.io.path.pathskt__pathrecursivefunctionskt$copytorecursively$3' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'com.android.server.permission.jarjar.kotlin.io.path.pathskt__pathrecursivefunctionskt$copytorecursively$3' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'com.android.server.permission.jarjar.kotlin.io.path.pathskt__pathrecursivefunctionskt$copytorecursively$3') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'org.jetbrains.kotlin.backend.jvm.lower.jvmmultifieldvalueclassloweringkt$extractvariablessetterstoouterpossibleblock$1') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'org.jetbrains.kotlin.fir.backend.generators.fir2irlazyfakeoverridegenerator$choosemostspecificoverridden$lambda$7$lambda$6$$inlined$anyoverriddenof$1' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'org.jetbrains.kotlin.fir.backend.generators.fir2irlazyfakeoverridegenerator$choosemostspecificoverridden$lambda$7$lambda$6$$inlined$anyoverriddenof$1' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'org.jetbrains.kotlin.fir.backend.generators.fir2irlazyfakeoverridegenerator$choosemostspecificoverridden$lambda$7$lambda$6$$inlined$anyoverriddenof$1' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'org.jetbrains.kotlin.fir.backend.generators.fir2irlazyfakeoverridegenerator$choosemostspecificoverridden$lambda$7$lambda$6$$inlined$anyoverriddenof$1')) RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200
```

### or-four-all

- `get`
- `set`
- `read`
- `write`

```cypher
MATCH (n) WHERE ((toLower(coalesce(n.caller_class, '')) CONTAINS 'get' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'get' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'get' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'get') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'set' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'set' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'set' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'set') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'read' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'read' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'read' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'read') OR (toLower(coalesce(n.caller_class, '')) CONTAINS 'write' OR toLower(coalesce(n.caller_name, '')) CONTAINS 'write' OR toLower(coalesce(n.callee_class, '')) CONTAINS 'write' OR toLower(coalesce(n.callee_name, '')) CONTAINS 'write')) RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200
```

## Reproduce

See the versioned wide-query-profile README for authenticated export and derive.py commands. Derivation fails closed if an advertised hit distribution differs. `catalog.json` contains all 64 counts per predicate and ordered expected rows for both projections. `totalMatches` in TSV always means raw matching nodes before LIMIT, including for DISTINCT queries; `totalDistinctMatches` is explicit in JSON.

#!/usr/bin/env python3
"""Differential test: run the same requests against the Kotlin and Rust servers and diff.

Volatile fields (timestamps, absolute paths) are normalised before comparison.
"""
import json, os, sys, urllib.request, urllib.error

KOTLIN = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:18081"
RUST = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:18080"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

VOLATILE = {"loadedAt", "builtAt", "version"}  # build identity, not behaviour

# Known divergence, reported rather than hidden.
#
# On the single-graph route the Kotlin server tags some rows with
# {"$metadata": {"graphIds": ["single"]}}. "single" is an internal placeholder
# (QueryPipeline.SINGLE_GRAPH_ID), not the id of any loaded graph -- the graph here
# is "app". It also appears only for query shapes that take an index-assisted path,
# so the same query emits it or not depending on whether the index is warm. The Rust
# server never emits provenance on a single-graph route. Strip that exact placeholder
# so real differences stay visible, and count how often it fires.
PLACEHOLDER_PROVENANCE = {"graphIds": ["single"]}
placeholder_hits = 0

def strip_placeholder(o):
    global placeholder_hits
    if isinstance(o, dict):
        if o.get("$metadata") == PLACEHOLDER_PROVENANCE:
            o = {k: v for k, v in o.items() if k != "$metadata"}
            placeholder_hits += 1
        return {k: strip_placeholder(v) for k, v in o.items()}
    if isinstance(o, list):
        return [strip_placeholder(v) for v in o]
    return o

def norm(o):
    if isinstance(o, dict):
        return {k: ("<volatile>" if k in VOLATILE else norm(v)) for k, v in o.items()}
    if isinstance(o, list):
        return [norm(v) for v in o]
    return o

# Response headers observed on the last `fetch`, keyed by (base, method, path).
# A client that only ever looks at the body will not notice a JSON route answering
# `text/plain`, and a browser or a generated SDK will.
seen_headers = {}


def fetch(base, method, path, body=None, headers=None):
    url = base + path
    data = body.encode() if body else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    key = (base, method, path)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            seen_headers[key] = {k.lower(): v for k, v in r.headers.items()}
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        seen_headers[key] = {k.lower(): v for k, v in e.headers.items()}
        return e.code, e.read().decode()
    except Exception as e:
        seen_headers.pop(key, None)
        return -1, str(e)

CASES = [
    ("GET", "/api/graphs", None),
    ("GET", "/api/graphs/app", None),
    ("GET", "/api/graphs/missing", None),
    ("GET", "/api/graphs/bad%20id", None),
    ("GET", "/api/topology", None),
    ("GET", "/api/graphs/app/overview?limit=20", None),
    ("GET", "/api/overview?limit=20", None),
    ("GET", "/api/graphs/app/node/4090", None),
    ("GET", "/api/graphs/app/node/999999999", None),
    ("GET", "/api/graphs/app/node/notanumber", None),
    ("GET", "/api/graphs/app/node/4090/outgoing?limit=10", None),
    ("GET", "/api/graphs/app/node/4090/incoming?limit=10", None),
    ("GET", "/api/graphs/app/subgraph?center=4090&depth=1&direction=outgoing", None),
    ("GET", "/api/graphs/app/subgraph?depth=1", None),
    ("GET", "/api/graphs/app/subgraph?center=4090&direction=sideways", None),
    ("GET", "/api/graphs/app/endpoints?limit=5", None),
    ("GET", "/api/graphs/app/resources?limit=5", None),
    ("GET", "/api/graphs/app/annotations?class=java.lang.Object&member=toString", None),
    ("GET", "/api/graphs/app/annotations?class=java.lang.Object", None),
    # C4: every level in every format, byte for byte. `app` is a library graph, whose
    # component level is empty; `acme` (backend/bench/fixtures/acme) is an application
    # with several capabilities calling each other, so its component and `all` levels
    # carry component relationships.
    *[
        ("GET", f"/api/graphs/{graph}/architecture/c4?level={level}&format={fmt}", None)
        for graph in ("app", "acme")
        for level in ("context", "container", "component", "all")
        for fmt in ("json", "mermaid", "plantuml", "dsl")
    ],
    ("GET", "/api/graphs/acme/cypher?query=MATCH%20(n:CallSite)%20WHERE%20n.callee_class%20CONTAINS%20%22acme%22%20RETURN%20count(*)", None),
    ("GET", "/api/graphs/app/architecture/c4?level=nosuch", None),
    ("GET", "/api/graphs/app/architecture/c4?format=nosuch", None),
    ("GET", "/openapi.json", None),
    ("GET", "/swagger.json", None),
]

QUERIES = [
    "MATCH (n:CallSiteNode) RETURN n.callee_class, n.callee_name LIMIT 5",
    "MATCH (n:CallSiteNode) RETURN count(*)",
    "MATCH (n:IntConstant) RETURN n.value ORDER BY n.value LIMIT 10",
    "MATCH (n:StringConstant) RETURN DISTINCT n.value ORDER BY n.value LIMIT 10",
    "MATCH (n:StringConstant) RETURN DISTINCT n.value AS v ORDER BY v LIMIT 10",
    "MATCH (n:StringConstant) RETURN DISTINCT n.value LIMIT 10",
    "MATCH (n:FieldNode) RETURN DISTINCT n.name AS nm ORDER BY nm LIMIT 10",
    "MATCH (n:CallSiteNode) RETURN DISTINCT n.callee_class AS c ORDER BY c LIMIT 10",
    "MATCH (n:CallSiteNode) RETURN n.callee_class, count(*) AS c ORDER BY c DESC LIMIT 10",
    "MATCH (n:IntConstant) RETURN count(n)",
    "MATCH (n) RETURN count(*)",
    # A count over a broad predicate: the shape whose per-row input used to be a copy
    # of the whole row, and the one that could exhaust memory on a large graph.
    'MATCH (n) WHERE n.caller_class CONTAINS "a" OR n.callee_name CONTAINS "get" RETURN count(*)',
    'MATCH (n) WHERE n.caller_class CONTAINS "a" RETURN count(*) AS total, count(DISTINCT n.callee_class) AS classes',
    "MATCH (n:Method) RETURN count(*)",
    "MATCH (n:NoSuchLabel) RETURN count(*)",
    "MATCH (n) WHERE n.callee_class CONTAINS 'javalin' RETURN n.callee_class, n.callee_name LIMIT 20",
    "MATCH (n) WHERE n.caller_class CONTAINS 'javalin' OR n.caller_name CONTAINS 'javalin' OR n.callee_class CONTAINS 'javalin' OR n.callee_name CONTAINS 'javalin' RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200",
    "MATCH (n) WHERE toLower(coalesce(n.callee_class, '')) CONTAINS 'javalin' RETURN n.callee_class LIMIT 50",
    "MATCH (n:CallSiteNode) WHERE n.callee_name = 'toString' RETURN n.caller_class LIMIT 10",
    "MATCH (n:CallSiteNode) WHERE n.callee_class STARTS WITH 'java.util' RETURN n.callee_class LIMIT 10",
    "MATCH (n:CallSiteNode) WHERE n.callee_class ENDS WITH 'Objects' RETURN n.callee_class LIMIT 10",
    "MATCH (n:Method) RETURN n.signature LIMIT 5",
    "MATCH (n:CallSiteNode) RETURN n.callee_class, count(*) ORDER BY count(*) DESC LIMIT 10",
    "RETURN 1 + 2 * 3",
    "RETURN toUpper('abc'), size([1,2,3]), abs(-5), round(2.4)",
    "RETURN null IS NULL, 1 = 1.0, 'a' IN ['a','b'], [1,2,3][1]",
    "RETURN CASE WHEN 1 > 2 THEN 'x' ELSE 'y' END",
    "UNWIND [1,2,3] AS x RETURN x * 2 ORDER BY x DESC",
    "MATCH (n:CallSiteNode) RETURN n LIMIT 2",
    "MATCH (a:CallSiteNode)-[r]->(b) RETURN a.callee_name, type(r), b.type LIMIT 10",
    "MATCH (n:CallSiteNode) RETURN labels(n) LIMIT 1",
    "MATCH (n:NoSuchLabel) RETURN n",
    "MATCH (n:CallSiteNode) RETURN n.callee_class AS c ORDER BY c LIMIT 5",
    "MATCH (n:CallSiteNode) RETURN n.callee_class SKIP 3 LIMIT 3",
    "MATCH (n:IntConstant) RETURN sum(n.value), avg(n.value), min(n.value), max(n.value)",
    "MATCH (n:IntConstant) RETURN collect(n.value) LIMIT 1",
    "RETURN nosuchfunction(1)",
    "MATCH (n RETURN n",
    "CREATE (n:Foo)",
    # LIMIT 0 on the compact path must return no rows.
    'MATCH (n) WHERE n.caller_class CONTAINS "a" RETURN n.caller_class LIMIT 0',
    'MATCH (n) WHERE n.caller_class CONTAINS "a" RETURN n.caller_class, n.callee_class LIMIT 0',
    # Ordered DISTINCT: the winning values occur after the first LIMIT's worth of
    # distinct values in scan order, so an early drop at the budget loses them.
    'MATCH (n) WHERE n.callee_class CONTAINS "a" RETURN DISTINCT n.callee_class ORDER BY n.callee_class LIMIT 5',
    'MATCH (n) WHERE n.callee_class CONTAINS "a" RETURN DISTINCT n.callee_class AS c ORDER BY c DESC LIMIT 5',
    # Non-ASCII literals must not prune a graph by trigrams the JVM would have
    # lowercased differently: a leaf, and a conjunction whose other side matches.
    'MATCH (n) WHERE n.callee_class CONTAINS "AΟΣ" RETURN n.callee_class LIMIT 5',
    'MATCH (n) WHERE n.callee_class CONTAINS "Ärger" OR n.caller_name CONTAINS "toString" RETURN n.caller_name LIMIT 5',
    'MATCH (n) WHERE n.callee_class CONTAINS "java" AND n.caller_name CONTAINS "ΣΑΣ" RETURN n.caller_name LIMIT 5',
    # A projected property that is null has no key in the row.
    "MATCH (n:CallSiteNode) RETURN n.nonexistent, n.callee_class LIMIT 3",
    # Unlabelled, so ordered: the baseline visits node types in a hash order that
    # differs between JVM runs, and the first three nodes it reaches are not stable.
    "MATCH (n) RETURN n.value, id(n) AS i ORDER BY i LIMIT 5",
    "MATCH (n:IntConstant) RETURN n.value LIMIT 3",
    "MATCH (c)-[r:DATAFLOW]->(n) RETURN c.value, n.callee_class ORDER BY id(c), id(n) LIMIT 3",
    'MATCH (n) WHERE n.value CONTAINS "java" RETURN DISTINCT n.value ORDER BY n.value LIMIT 5',
    # Cross-graph grouping: one row per value across graphs, counts summed, both
    # graphs in the provenance; without ORDER BY the groups come first-seen.
    "MATCH (n:CallSiteNode) RETURN n.callee_class, count(*) AS c LIMIT 5",
    "MATCH (n:CallSiteNode) WHERE n.callee_class CONTAINS \"java.util\" RETURN n.callee_class, count(*) AS c ORDER BY c DESC LIMIT 5",
    "MATCH (n:StringConstant) RETURN DISTINCT n.value AS v ORDER BY v LIMIT 12",
    # Synthetic identifiers: folded per graph from the graph id, the node id deciding
    # only when the literal has digits; null outside cross-graph mode.
    'MATCH (n) WHERE n.qualifiedId CONTAINS "acme:" RETURN count(*)',
    'MATCH (n) WHERE n.qualifiedId CONTAINS "acm" RETURN count(*)',
    'MATCH (n) WHERE n.qualifiedId STARTS WITH "acm" RETURN n.qualifiedId ORDER BY n.qualifiedId LIMIT 5',
    'MATCH (n) WHERE n.qualifiedId CONTAINS "app:" AND n.callee_class CONTAINS "java" RETURN count(*)',
    'MATCH (n) WHERE n.qualifiedId CONTAINS "app:" OR n.value CONTAINS "order" RETURN count(*)',
    'MATCH (n) WHERE n.qualifiedId CONTAINS "38" RETURN n.qualifiedId ORDER BY n.qualifiedId LIMIT 5',
    'MATCH (n) WHERE n.qualifiedId ENDS WITH ":38" RETURN n.qualifiedId ORDER BY n.qualifiedId LIMIT 5',
    'MATCH (n) WHERE n.qualifiedId = "acme:38" RETURN n',
    'MATCH (n) WHERE n.elementId STARTS WITH "acme:" RETURN count(*)',
    'MATCH (n) WHERE n.graphId = "acme" RETURN count(*)',
    'MATCH (n) WHERE toLower(n.qualifiedId) STARTS WITH "acme" RETURN count(*)',
    'MATCH (n:CallSite) WHERE n.qualifiedId CONTAINS "nosuchgraph:" RETURN count(*)',
    # The all-properties search. Before #128 the baseline evaluated `n[k]` on a node
    # to null and returned no rows for this shape; main now reads the property.
    'MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS "java.util") RETURN count(*)',
    'MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS "Ids") RETURN n ORDER BY id(n) LIMIT 10',
    'MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS "10") RETURN count(*)',
    'MATCH (n) WHERE any(k IN keys(n) WHERE n[k] = "toString") RETURN count(*)',
    'MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) STARTS WITH "acme") RETURN count(*)',
    'MATCH (n) WHERE any(k IN keys(n) WHERE toString(properties(n)[k]) CONTAINS "Ids") RETURN count(*)',
    'MATCH (n:CallSite) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS "String)") RETURN count(*)',
    'MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS "zzqqxxvv") RETURN count(*)',
    'MATCH (n) RETURN n.id AS id, any(k IN keys(n) WHERE toString(n[k]) CONTAINS "Ids") AS matched ORDER BY id LIMIT 10',
    'MATCH (n:CallSite) WHERE n.callee_class CONTAINS "a" RETURN DISTINCT n.callee_class ORDER BY n.callee_class DESC LIMIT 5',
    'MATCH (n:CallSite) WHERE n.callee_class CONTAINS "a" RETURN DISTINCT n.callee_class AS c ORDER BY c LIMIT 5',
    'MATCH (n:LocalVariable) RETURN n LIMIT 3',
    'MATCH (n:LocalVariable) RETURN n.method LIMIT 3',
    'MATCH (n:Parameter) RETURN n LIMIT 3',
    # Non-CallSite string properties take the column path.
    # Unlabelled scans over several node types are ordered here, since the Kotlin
    # server visits the types it has no direct index for in a hash order that varies
    # between runs.
    'MATCH (n) WHERE n.value CONTAINS "java" RETURN n.value ORDER BY n.value LIMIT 10',
    'MATCH (n) WHERE n.name CONTAINS "size" AND n.type CONTAINS "int" RETURN n.name, n.type, labels(n) ORDER BY id(n) LIMIT 10',
    'MATCH (n:StringConstant) WHERE n.value STARTS WITH "java" RETURN n.value LIMIT 10',
    'MATCH (n) WHERE n.value CONTAINS "java" RETURN count(*)',
    # A single hop anchored on the end the predicate names.
    'MATCH (c)-[r:DATAFLOW]->(n) WHERE n.callee_class CONTAINS "java" RETURN c, n ORDER BY id(c), id(n) LIMIT 10',
    'MATCH (c:StringConstant)-[r:DATAFLOW]->(n) WHERE n.callee_class CONTAINS "java" RETURN c, n LIMIT 10',
    'MATCH (c:StringConstant)-[r:DATAFLOW]->(n) WHERE c.value CONTAINS "java" RETURN c.value, n.callee_class LIMIT 10',
    'MATCH (c)-[r:DATAFLOW]->(n:CallSite) WHERE n.callee_class CONTAINS "nosuchclass" RETURN c, n LIMIT 10',
    'MATCH (c:StringConstant)-[r]->(n) WHERE n.callee_class CONTAINS "java" AND c.value CONTAINS "a" RETURN c.value, n.callee_class LIMIT 10',
    'MATCH (c)-[r:DATAFLOW]->(n) WHERE n.callee_class CONTAINS "java" RETURN count(*)',
    # `=~` with a simple pattern is answered from the trigram index, with the pattern
    # itself deciding each candidate; the rest go through the evaluator as before.
    "MATCH (n) WHERE n.caller_class =~ '.*java.*' OR n.callee_class =~ '.*java.*' RETURN n LIMIT 10",
    "MATCH (n) WHERE n.callee_class =~ 'java\\\\.util\\\\..*' RETURN n.callee_class LIMIT 10",
    "MATCH (n) WHERE n.callee_class =~ 'java.util.*' RETURN count(*)",
    "MATCH (n) WHERE n.value =~ '.*java.*' RETURN n.value ORDER BY n.value LIMIT 10",
    "MATCH (n:StringConstant) WHERE n.value =~ '.*a.*' RETURN count(*)",
    "MATCH (n:CallSite) WHERE n.callee_name =~ '[a-z]+' RETURN count(*)",
    "MATCH (n) WHERE n.callee_name =~ 'toString' RETURN count(*)",
    "MATCH (n) WHERE n.callee_class =~ '.*Nosuchclass.*' RETURN count(*)",
    "MATCH (n) WHERE toLower(n.callee_class) =~ '.*java.*' RETURN count(*)",
    "MATCH (c)-[r:DATAFLOW]->(n) WHERE n.callee_class =~ '.*java.*' RETURN count(*)",
    # A property no type stores is null everywhere but on an annotation, whose value
    # pairs can spell any key: the scan skips every other type outright.
    'MATCH (n) WHERE n.fullName CONTAINS "x" OR n.class CONTAINS "Ids" RETURN count(*)',
    'MATCH (n) WHERE n.name CONTAINS "a" AND n.code CONTAINS "b" RETURN count(*)',
    'MATCH (n) WHERE n.name CONTAINS "a" OR n.code CONTAINS "b" OR n.graph_id = "app" RETURN count(*)',
    # Numeric equality: true only where the property holds a number.
    'MATCH (n) WHERE n.value = 105873 RETURN count(*)',
    'MATCH (n) WHERE n.value = 0 RETURN n.value, labels(n) ORDER BY id(n) LIMIT 10',
    'MATCH (n) WHERE n.value = 1 OR n.value CONTAINS "java" RETURN count(*)',
    'MATCH (n) WHERE 1 = n.value RETURN count(*)',
    'MATCH (n) WHERE n.value = 1.5 RETURN count(*)',
    'MATCH (n) WHERE n.line = 10 RETURN count(*)',
    'MATCH (n) WHERE n.id = 5 RETURN n',
    'MATCH (n) WHERE n.value = 105873 AND n.callee_class CONTAINS "java" RETURN count(*)',
    # Membership in a property: null, hence no row, unless the property is a list.
    'MATCH (n) WHERE "app" IN n.graphIds RETURN count(*)',
    'MATCH (n) WHERE "x" IN n.value RETURN count(*)',
    'MATCH (n) WHERE "app" IN n.graphIds OR n.value CONTAINS "java" RETURN count(*)',
    # `type` falls back to the node's type name where nothing is stored under it.
    'MATCH (n) WHERE n.type = "CallSiteNode" RETURN count(*)',
    'MATCH (n) WHERE toLower(n.type) CONTAINS "constant" RETURN count(*)',
    'MATCH (n) WHERE n.type CONTAINS "int" RETURN count(*)',
    'MATCH (n) WHERE n.type CONTAINS "Node" AND n.callee_class CONTAINS "java" RETURN count(*)',
    'MATCH (n:Annotation) WHERE n.type = "AnnotationNode" RETURN count(*)',
    'MATCH (n:Annotation) WHERE n.type CONTAINS "Annotation" RETURN count(*)',
    'MATCH (n:LocalVariable) WHERE n.type = "LocalVariable" RETURN count(*)',
    # A CallSite's signatures, line and id are read off the record without decoding it.
    'MATCH (n) WHERE n.callee_signature CONTAINS "java.lang.String)" RETURN count(*)',
    'MATCH (n) WHERE n.callee_signature CONTAINS "(java.lang.String" RETURN n.callee_signature ORDER BY n.callee_signature LIMIT 10',
    'MATCH (n) WHERE toLower(n.caller_signature) CONTAINS "main(" RETURN count(*)',
    'MATCH (n) WHERE n.caller_signature CONTAINS "x" OR n.callee_name = "toString" RETURN count(*)',
    'MATCH (n) WHERE n.callee_signature CONTAINS "String" AND n.callee_class CONTAINS "java" RETURN count(*)',
    'MATCH (n) WHERE n.callee_signature STARTS WITH "java.lang.String." RETURN count(*)',
    'MATCH (n) WHERE n.callee_signature = "java.lang.String.length()" RETURN count(*)',
    'MATCH (n:CallSite) WHERE n.callee_signature =~ ".*String.*" RETURN count(*)',
    'MATCH (n) WHERE toString(n.line) STARTS WITH "1" RETURN count(*)',
    'MATCH (n) WHERE n.line = 10 OR n.callee_class CONTAINS "java" RETURN count(*)',
    'MATCH (n) WHERE toString(n.id) CONTAINS "12" RETURN count(*)',
    'MATCH (n:CallSite) WHERE n.id = 5 RETURN n',
    # The method of a local, a parameter or a return node, a return node's actual
    # type, and the integers and booleans at a fixed place in a record.
    'MATCH (n) WHERE n.method CONTAINS "main(" RETURN count(*)',
    'MATCH (n:LocalVariable) WHERE n.method CONTAINS "java.lang.String)" RETURN n.name, n.method ORDER BY id(n) LIMIT 10',
    'MATCH (n:Parameter) WHERE toLower(n.method) STARTS WITH "java." RETURN count(*)',
    'MATCH (n:Return) WHERE n.method CONTAINS "toString()" RETURN count(*)',
    'MATCH (n:Return) WHERE n.actual_type CONTAINS "String" RETURN count(*)',
    'MATCH (n) WHERE n.actual_type CONTAINS "String" OR n.method CONTAINS "nosuchmethod(" RETURN count(*)',
    'MATCH (n) WHERE n.method CONTAINS "toString()" AND n.name CONTAINS "a" RETURN count(*)',
    'MATCH (n) WHERE toString(n.index) = "0" RETURN count(*)',
    'MATCH (n) WHERE toString(n.static) = "true" RETURN count(*)',
    'MATCH (n) WHERE toString(n.value) STARTS WITH "-" RETURN count(*)',
    'MATCH (n) WHERE toString(n.value) = "0" RETURN count(*)',
    'MATCH (n) WHERE n.index = 1 RETURN count(*)',
    'MATCH (n) WHERE n.value = -1 RETURN count(*)',
    'MATCH (n:LongConstant) WHERE n.value = 0 RETURN count(*)',
    'MATCH (n) WHERE toString(n.id) = "5" RETURN n',
    # A property map in the pattern is planned like `n.k = v` conjuncts.
    'MATCH (n:CallSite {callee_class: "java.lang.String"}) RETURN count(*)',
    'MATCH (n:CallSite {callee_class: "java.lang.String", callee_name: "length"}) RETURN n.caller_class ORDER BY id(n) LIMIT 10',
    'MATCH (n:CallSite {callee_name: "length"}) WHERE n.caller_class CONTAINS "java" RETURN count(*)',
    'MATCH (n {value: "UNKNOWN"}) RETURN n ORDER BY id(n) LIMIT 5',
    'MATCH (n:IntConstant {value: 0}) RETURN count(*)',
    'MATCH (n {value: 0}) RETURN labels(n)[0] AS l, count(*) ORDER BY l',
    'MATCH (n:CallSite {nosuch: "x"}) RETURN count(*)',
    'MATCH (n:CallSite {callee_class: "java.lang.String"}) RETURN n LIMIT 0',
    'MATCH (n:StringConstant {value: "UNKNOWN"})-[r:DATAFLOW]->(m) RETURN count(*)',
    # A relationship pattern or a property map is never a plain type count.
    'MATCH (c)-[r:DATAFLOW]->(n) RETURN count(*)',
    'MATCH (c:StringConstant)-[r:DATAFLOW]->(n) RETURN count(*)',
    'MATCH (:CallSite) RETURN count(*)',
    'MATCH p = (c)-[r:DATAFLOW]->(n) RETURN count(*)',
]
for q in QUERIES:
    CASES.append(("POST", "/api/graphs/app/cypher", json.dumps({"query": q})))
    CASES.append(("POST", "/api/cypher", json.dumps({"query": q})))

# The cross-graph forms of the grouped routes, and the GET spellings of the Cypher
# routes. These are declared in the OpenAPI document and were previously untested; the
# spec is checked against this list below so the gap cannot silently return.
CASES += [
    ("GET", "/api/annotations?class=java.lang.Object&member=toString", None),
    ("GET", "/api/annotations?class=java.lang.Object", None),
    *[
        ("GET", f"/api/architecture/c4?level={level}&format={fmt}", None)
        for level in ("context", "container", "component", "all")
        for fmt in ("json", "mermaid", "plantuml", "dsl")
    ],
    ("GET", "/api/architecture/c4?level=nosuch", None),
    ("GET", "/api/endpoints?limit=5", None),
    ("GET", "/api/resources?limit=5", None),
    ("GET", "/api/resources/nosuch/resource.txt", None),
    ("GET", "/api/graphs/app/resources/nosuch/resource.txt", None),
    ("GET", "/api/cypher/graphs", None),
    ("POST", "/api/cypher/graphs", json.dumps({"query": "MATCH (n) RETURN count(*)"})),
    # `?query=` is the GET spelling of the same endpoints.
    ("GET", "/api/cypher?query=MATCH+(n)+RETURN+count(*)", None),
    ("GET", "/api/graphs/app/cypher?query=MATCH+(n)+RETURN+count(*)", None),
    ("GET", "/api/cypher?query=MATCH+(n+RETURN+n", None),
    ("GET", "/api/cypher", None),
]

passed = failed = 0
failures = []


def check_content_type(method, path, label):
    """The `Content-Type` both servers put on the same response, errors included.

    Only this one header is compared. `Date`, `Content-Length`, `Server` and the
    connection headers are either volatile or the web framework's business; the media
    type is the server's own contract, and it is the one a generated client dispatches
    on -- including on an error body, where it is easiest to get wrong.
    """
    global passed, failed
    k = seen_headers.get((KOTLIN, method, path), {}).get("content-type")
    r = seen_headers.get((RUST, method, path), {}).get("content-type")
    if k == r:
        passed += 1
    else:
        failed += 1
        failures.append((f"{label} [content-type]", "differs", str(k), str(r)))


for method, path, body in CASES:
    ks, kb = fetch(KOTLIN, method, path, body)
    rs, rb = fetch(RUST, method, path, body)
    label = f"{method} {path}" + (f" {body[:90]}" if body else "")
    check_content_type(method, path, label)
    if ks != rs:
        failed += 1
        failures.append((label, f"status {ks} != {rs}", kb[:300], rb[:300]))
        continue
    try:
        kj = strip_placeholder(norm(json.loads(kb)))
        rj = strip_placeholder(norm(json.loads(rb)))
        same = kj == rj
        kd, rd = json.dumps(kj, sort_keys=True)[:400], json.dumps(rj, sort_keys=True)[:400]
    except Exception:
        same = kb.strip() == rb.strip()
        kd, rd = kb[:400], rb[:400]
    if same:
        passed += 1
    else:
        failed += 1
        failures.append((label, "body differs", kd, rd))

# Registry mutation, last: these change server state. A graph is loaded under a fresh
# id, described, replaced, then unloaded, and both servers must agree at every step --
# including on the errors for a bad path and for unloading something already gone.
GRAPH_PATH = "/home/user/fixtures/explore-graph"
CASES_MUTATING = []
for method in ("PUT", "POST"):
    # Both spellings load a graph, so both are walked through the same lifecycle.
    round_trip = [
        (method, "/api/graphs/tmpcopy", json.dumps({"path": GRAPH_PATH})),
        ("GET", "/api/graphs/tmpcopy", None),
        ("GET", "/api/graphs", None),
        (method, "/api/graphs/tmpcopy", json.dumps({"path": "/nonexistent"})),
        (method, "/api/graphs/tmpcopy", json.dumps({})),
        (method, "/api/graphs/bad id", json.dumps({"path": GRAPH_PATH})),
        ("DELETE", "/api/graphs/tmpcopy", None),
        ("DELETE", "/api/graphs/tmpcopy", None),
        ("GET", "/api/graphs/tmpcopy", None),
    ]
    CASES_MUTATING += round_trip
    for m, path, body in round_trip:
        ks, kb = fetch(KOTLIN, m, path, body)
        rs, rb = fetch(RUST, m, path, body)
        label = f"{m} {path}" + (f" {body}" if body else "")
        check_content_type(m, path, label)
        try:
            same = ks == rs and strip_placeholder(norm(json.loads(kb))) == strip_placeholder(norm(json.loads(rb)))
        except Exception:
            same = ks == rs and kb.strip() == rb.strip()
        if same:
            passed += 1
        else:
            failed += 1
            failures.append((label, f"status {ks} != {rs}" if ks != rs else "body differs",
                             kb[:300], rb[:300]))

# The web UI. These are served straight from the binary on one side and out of the jar
# on the other, so both the bytes and the caching contract are worth checking: without
# a validator a browser re-downloads all four assets on every load.
UI_ASSETS = ["/", "/index.html", "/app.js", "/ui-state.js", "/style.css"]

def head(base, path):
    req = urllib.request.Request(base + path, method="HEAD")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, {k.lower(): v for k, v in r.headers.items()}
    except Exception as e:
        return -1, {"error": str(e)}

for path in UI_ASSETS:
    ks, kb = fetch(KOTLIN, "GET", path)
    rs, rb = fetch(RUST, "GET", path)
    if (ks, kb) == (rs, rb):
        passed += 1
    else:
        failed += 1
        failures.append((f"GET {path}", f"status {ks} != {rs}" if ks != rs else "body differs",
                         kb[:200], rb[:200]))
    kh, rh = head(KOTLIN, path)[1], head(RUST, path)[1]
    # Content-Type must match exactly; the ETag value is opaque, so only its presence
    # and the 304 it enables are compared.
    if kh.get("content-type") == rh.get("content-type"):
        passed += 1
    else:
        failed += 1
        failures.append((f"HEAD {path}", "content-type differs",
                         str(kh.get("content-type")), str(rh.get("content-type"))))
    codes = []
    for base, hdrs in ((KOTLIN, kh), (RUST, rh)):
        etag = hdrs.get("etag")
        codes.append(fetch(base, "GET", path, None, {"If-None-Match": etag})[0] if etag else None)
    if codes[0] == codes[1] == 304:
        passed += 1
    else:
        failed += 1
        failures.append((f"GET {path} If-None-Match", "revalidation differs",
                         f"kotlin {codes[0]}", f"rust {codes[1]}"))

# `/metrics` is the Prometheus exposition, and it is not in the OpenAPI document, so the
# checklist below cannot reach it. It is compared structurally rather than byte for byte,
# for two reasons that are worth stating rather than leaving implicit:
#
#   * Most of what the Kotlin server exposes describes a JVM -- garbage collection,
#     class loading, JIT compilation, Jetty's thread pool. Those families have no
#     counterpart in a Rust binary and never will. Only the `graphite_`-prefixed
#     families are this port's to reproduce.
#   * Every value is wall-clock or workload dependent. A count, a sum, an uptime will
#     differ between two processes by construction.
#
# What must match is everything a dashboard binds to: the family names, their TYPE and
# HELP lines, the label sets, the histogram bucket boundaries, and whether a given
# series renders as an integer or a double -- Micrometer writes bucket and `_count`
# through `writeLong` and everything else through `Double.toString`, and a scrape that
# gets that backwards is a scrape someone will diff one day.
def get_headers(base, path):
    try:
        with urllib.request.urlopen(base + path, timeout=30) as r:
            return {k.lower(): v for k, v in r.headers.items()}
    except Exception as e:
        return {"error": str(e)}


def parse_exposition(text):
    families, series = {}, {}
    for line in text.splitlines():
        if line.startswith("# HELP "):
            name, _, help_text = line[len("# HELP "):].partition(" ")
            families.setdefault(name, {})["help"] = help_text
        elif line.startswith("# TYPE "):
            name, _, kind = line[len("# TYPE "):].partition(" ")
            families.setdefault(name, {})["type"] = kind
        elif line and not line.startswith("#"):
            key, _, value = line.rpartition(" ")
            # "integral" or "decimal" -- the rendering, never the value itself.
            series[key] = "decimal" if ("." in value or "e" in value.lower()) else "integral"
    return families, series


def check_metrics():
    global passed, failed
    ks, kb = fetch(KOTLIN, "GET", "/metrics", None)
    rs, rb = fetch(RUST, "GET", "/metrics", None)
    if ks != 200 or rs != 200:
        failed += 1
        failures.append(("GET /metrics", f"status {ks} != {rs}",
                         f"kotlin {ks}", f"rust {rs}"))
        return
    # Compared on the GET, not a HEAD. Javalin answers `HEAD /metrics` with a bare
    # `text/plain`, dropping the `version=0.0.4; charset=utf-8` its own GET sends -- an
    # artifact of how the handler's content type survives a bodyless response, not a
    # contract a scraper relies on. Prometheus issues GETs.
    kct = get_headers(KOTLIN, "/metrics").get("content-type")
    rct = get_headers(RUST, "/metrics").get("content-type")
    if kct == rct:
        passed += 1
    else:
        failed += 1
        failures.append(("GET /metrics content-type", "differs", str(kct), str(rct)))

    kf, kser = parse_exposition(kb)
    rf, rser = parse_exposition(rb)
    own = lambda d: {n: v for n, v in d.items() if n.startswith("graphite_")}
    kf, rf = own(kf), own(rf)
    missing = sorted(set(kf) - set(rf))
    extra = sorted(set(rf) - set(kf))
    if missing or extra:
        failed += 1
        failures.append(("GET /metrics families", "graphite_* families differ",
                         f"only in kotlin: {missing}", f"only in rust: {extra}"))
    else:
        passed += 1
    for name in sorted(set(kf) & set(rf)):
        if kf[name] == rf[name]:
            passed += 1
        else:
            failed += 1
            failures.append((f"GET /metrics {name}", "TYPE or HELP differs",
                             str(kf[name]), str(rf[name])))
    own_series = lambda d: {k: v for k, v in d.items() if k.startswith("graphite_")}
    kser, rser = own_series(kser), own_series(rser)
    if set(kser) == set(rser):
        passed += 1
    else:
        failed += 1
        failures.append(("GET /metrics series", "graphite_* series differ",
                         f"only in kotlin: {sorted(set(kser) - set(rser))[:6]}",
                         f"only in rust: {sorted(set(rser) - set(kser))[:6]}"))
    shape_diff = [k for k in set(kser) & set(rser) if kser[k] != rser[k]]
    if shape_diff:
        failed += 1
        failures.append(("GET /metrics value rendering", "integer/double differs",
                         f"kotlin {[(k, kser[k]) for k in sorted(shape_diff)[:4]]}",
                         f"rust {[(k, rser[k]) for k in sorted(shape_diff)[:4]]}"))
    else:
        passed += 1


check_metrics()

# Every method and path the OpenAPI document advertises must be exercised above. The
# document is the server's public contract, so an endpoint it declares and this suite
# never calls is an untested promise -- and that is how the cross-graph routes and the
# whole of registry mutation went unchecked until someone thought to compare the two.
def spec_coverage():
    import re
    spec_path = os.path.join(ROOT, "backend/explore/src/openapi.json")
    spec = json.load(open(spec_path))
    declared = {
        (m.upper(), path)
        for path, ops in spec["paths"].items()
        for m in ops
        if m.lower() in ("get", "post", "put", "delete", "patch", "head")
    }
    exercised = set()
    for m, path, _ in CASES + [(m, p, b) for m, p, b in CASES_MUTATING]:
        p = path.split("?")[0]
        p = re.sub(r"/node/[^/]+", "/node/{id}", p)
        p = re.sub(r"/api/graphs/[^/]+/resources/.+", "/api/graphs/{graphId}/resources/{path}", p)
        p = re.sub(r"/api/resources/.+", "/api/resources/{path}", p)
        p = re.sub(r"/api/graphs/(?!\{)[^/]+", "/api/graphs/{graphId}", p)
        exercised.add((m, p))
    return sorted(declared - exercised)

uncovered = spec_coverage()
if uncovered:
    failed += 1
    failures.append((
        "openapi.json declares endpoints this suite never calls",
        "; ".join(f"{m} {p}" for m, p in uncovered), "", "",
    ))
else:
    passed += 1

print(f"parity: {passed} passed, {failed} failed of {passed+failed}")
if placeholder_hits:
    print(f'note: ignored {placeholder_hits} rows carrying the baseline\'s '
          f'internal "single" graphId placeholder (known divergence)')
for label, why, k, r in failures:
    print(f"\n--- {label}\n  {why}\n  kotlin: {k}\n  rust:   {r}")
# A differential that found a difference is a failed run; CI must go red on it.
sys.exit(1 if failed else 0)

"""JVM request definitions and full typed legal-LIMIT response validation.

This module does not derive graph expectations, read fixtures or execute HTTP.
A caller must independently derive and bind the complete projected row universe
from the actual persisted graphs and JVM source rules. Validating against a
supplied universe is not proof of its authority or of fresh64 acceptance.

Request text retains the existing workload queries; the explicit JVM route is
ExploreRoutes.queryLoadedGraphsFromRequest CROSS_GRAPH. Its seven fields differ
from the native response schema. No native expected response is consumed.
"""
from collections import Counter
import hashlib
import json
import math
from types import MappingProxyType

FAMILIES = ('global-dynamic-miss', 'global-callsite-dynamic-miss',
            'wrapped-discovery', 'graph-routing', 'full-slow-shape-catalog', 'feature-presence')
POLICY = 'jvm-complete-legal-limit-multiset-no-order-by'
ENVELOPE = {'mode', 'graphs', 'graphCount', 'columns', 'rows', 'rowCount', 'limit'}
# Literal workload definitions only, copied from the reviewed catalog query text.
# No expected rows, Native total or Native collector behavior is imported.
FIXTURE_GRAPH_IDS = ('fixture-android-00',
 'fixture-android-01',
 'fixture-android-02',
 'fixture-android-03',
 'fixture-android-04',
 'fixture-android-05',
 'fixture-android-06',
 'fixture-android-07',
 'fixture-android-08',
 'fixture-android-09',
 'fixture-android-10',
 'fixture-android-11',
 'fixture-android-12',
 'fixture-android-13',
 'fixture-android-14',
 'fixture-android-15',
 'fixture-tika-00',
 'fixture-tika-01',
 'fixture-tika-02',
 'fixture-tika-03',
 'fixture-tika-04',
 'fixture-tika-05',
 'fixture-tika-06',
 'fixture-tika-07',
 'fixture-tika-08',
 'fixture-tika-09',
 'fixture-tika-10',
 'fixture-tika-11',
 'fixture-tika-12',
 'fixture-tika-13',
 'fixture-tika-14',
 'fixture-tika-15',
 'fixture-hive-00',
 'fixture-hive-01',
 'fixture-hive-02',
 'fixture-hive-03',
 'fixture-hive-04',
 'fixture-hive-05',
 'fixture-hive-06',
 'fixture-hive-07',
 'fixture-hive-08',
 'fixture-hive-09',
 'fixture-hive-10',
 'fixture-hive-11',
 'fixture-hive-12',
 'fixture-hive-13',
 'fixture-hive-14',
 'fixture-hive-15',
 'fixture-kotlin-compiler-00',
 'fixture-kotlin-compiler-01',
 'fixture-kotlin-compiler-02',
 'fixture-kotlin-compiler-03',
 'fixture-kotlin-compiler-04',
 'fixture-kotlin-compiler-05',
 'fixture-kotlin-compiler-06',
 'fixture-kotlin-compiler-07',
 'fixture-kotlin-compiler-08',
 'fixture-kotlin-compiler-09',
 'fixture-kotlin-compiler-10',
 'fixture-kotlin-compiler-11',
 'fixture-kotlin-compiler-12',
 'fixture-kotlin-compiler-13',
 'fixture-kotlin-compiler-14',
 'fixture-kotlin-compiler-15')

_DEFINITIONS = (('dynamic-miss',
  'global-dynamic-miss',
  "MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'GraphiteDeclaredAbsent293746X') RETURN "
  'id(n) AS id, labels(n) AS labels, n.value AS value, n.caller_class AS caller, n.graphId AS graphId LIMIT '
  '50',
  ['id', 'labels', 'value', 'caller', 'graphId'],
  50,
  None),
 ('callsite-dynamic-miss',
  'global-callsite-dynamic-miss',
  'MATCH (n:CallSiteNode) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS '
  "'GraphiteDeclaredAbsent293746X') RETURN id(n) AS id, labels(n) AS labels, n.value AS value, "
  'n.caller_class AS caller, n.graphId AS graphId LIMIT 50',
  ['id', 'labels', 'value', 'caller', 'graphId'],
  50,
  None),
 ('slow-valueHit',
  'full-slow-shape-catalog',
  "MATCH (n) WHERE n.value CONTAINS 'android.permission.INTERNET' RETURN id(n) AS id, labels(n) AS labels, "
  'n.value AS value, n.caller_class AS caller, n.graphId AS graphId LIMIT 50',
  ['id', 'labels', 'value', 'caller', 'graphId'],
  50,
  None),
 ('slow-qualifiedIdHit',
  'full-slow-shape-catalog',
  "MATCH (n) WHERE n.qualifiedId CONTAINS '938826' RETURN id(n) AS id, labels(n) AS labels, n.qualifiedId AS "
  'qualifiedId, n.graphId AS graphId LIMIT 50',
  ['id', 'labels', 'qualifiedId', 'graphId'],
  50,
  None),
 ('slow-dynamicHit',
  'full-slow-shape-catalog',
  "MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'android.permission.INTERNET') RETURN "
  'id(n) AS id, labels(n) AS labels, n.value AS value, n.caller_class AS caller, n.graphId AS graphId LIMIT '
  '50',
  ['id', 'labels', 'value', 'caller', 'graphId'],
  50,
  None),
 ('slow-wrappedCallerHit',
  'full-slow-shape-catalog',
  "MATCH (n) WHERE toString(n.caller_class) CONTAINS 'android.app.Activity' RETURN id(n) AS id, labels(n) AS "
  'labels, n.value AS value, n.caller_class AS caller, n.graphId AS graphId LIMIT 50',
  ['id', 'labels', 'value', 'caller', 'graphId'],
  50,
  None),
 ('slow-dataflowSourceHit',
  'full-slow-shape-catalog',
  "MATCH (c)-[r:DATAFLOW]->(n) WHERE c.value CONTAINS 'android.permission.INTERNET' RETURN id(c) AS source, "
  'id(n) AS target, type(r) AS relationship, c.value AS value, n.caller_class AS caller, n.graphId AS '
  'graphId LIMIT 50',
  ['source', 'target', 'relationship', 'value', 'caller', 'graphId'],
  50,
  None),
 ('slow-dataflowTargetHit',
  'full-slow-shape-catalog',
  "MATCH (c)-[r:DATAFLOW]->(n) WHERE n.caller_class CONTAINS 'android.app.Activity' RETURN id(c) AS source, "
  'id(n) AS target, type(r) AS relationship, c.value AS value, n.caller_class AS caller, n.graphId AS '
  'graphId LIMIT 50',
  ['source', 'target', 'relationship', 'value', 'caller', 'graphId'],
  50,
  None),
 ('slow-valueMiss',
  'full-slow-shape-catalog',
  "MATCH (n) WHERE n.value CONTAINS 'GraphiteSlowShapeAbsent293746X' RETURN id(n) AS id, labels(n) AS "
  'labels, n.value AS value, n.caller_class AS caller, n.graphId AS graphId LIMIT 50',
  ['id', 'labels', 'value', 'caller', 'graphId'],
  50,
  None),
 ('slow-qualifiedIdMiss',
  'full-slow-shape-catalog',
  "MATCH (n) WHERE n.qualifiedId CONTAINS '93882699' RETURN id(n) AS id, labels(n) AS labels, n.qualifiedId "
  'AS qualifiedId, n.graphId AS graphId LIMIT 50',
  ['id', 'labels', 'qualifiedId', 'graphId'],
  50,
  None),
 ('slow-dynamicMiss',
  'full-slow-shape-catalog',
  "MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'GraphiteSlowShapeAbsent293746X') RETURN "
  'id(n) AS id, labels(n) AS labels, n.value AS value, n.caller_class AS caller, n.graphId AS graphId LIMIT '
  '50',
  ['id', 'labels', 'value', 'caller', 'graphId'],
  50,
  None),
 ('slow-wrappedCallerMiss',
  'full-slow-shape-catalog',
  "MATCH (n) WHERE toString(n.caller_class) CONTAINS 'GraphiteSlowShapeAbsent293746X' RETURN id(n) AS id, "
  'labels(n) AS labels, n.value AS value, n.caller_class AS caller, n.graphId AS graphId LIMIT 50',
  ['id', 'labels', 'value', 'caller', 'graphId'],
  50,
  None),
 ('slow-dataflowSourceMiss',
  'full-slow-shape-catalog',
  "MATCH (c)-[r:DATAFLOW]->(n) WHERE c.value CONTAINS 'GraphiteSlowShapeAbsent293746X' RETURN id(c) AS "
  'source, id(n) AS target, type(r) AS relationship, c.value AS value, n.caller_class AS caller, n.graphId '
  'AS graphId LIMIT 50',
  ['source', 'target', 'relationship', 'value', 'caller', 'graphId'],
  50,
  None),
 ('slow-dataflowTargetMiss',
  'full-slow-shape-catalog',
  "MATCH (c)-[r:DATAFLOW]->(n) WHERE n.caller_class CONTAINS 'GraphiteSlowShapeAbsent293746X' RETURN id(c) "
  'AS source, id(n) AS target, type(r) AS relationship, c.value AS value, n.caller_class AS caller, '
  'n.graphId AS graphId LIMIT 50',
  ['source', 'target', 'relationship', 'value', 'caller', 'graphId'],
  50,
  None),
 ('wrapped-zero_hit_query',
  'wrapped-discovery',
  '\n'
  'MATCH (n)\n'
  "WHERE toLower(coalesce(n.caller_class, '')) CONTAINS 'graphite_latency_no_such_symbol_9f36'\n"
  "   OR toLower(coalesce(n.caller_name, '')) CONTAINS 'graphite_latency_no_such_symbol_9f36'\n"
  "   OR toLower(coalesce(n.callee_class, '')) CONTAINS 'graphite_latency_no_such_symbol_9f36'\n"
  "   OR toLower(coalesce(n.callee_name, '')) CONTAINS 'graphite_latency_no_such_symbol_9f36'\n"
  'RETURN DISTINCT n.graph_id, n.caller_class AS caller, n.caller_name AS callerMethod,\n'
  '    n.callee_class AS callee, n.callee_name AS calleeMethod\n'
  'LIMIT 250\n',
  ['n.graph_id', 'caller', 'callerMethod', 'callee', 'calleeMethod'],
  250,
  None),
 ('wrapped-dense_distributed_method_query',
  'wrapped-discovery',
  '\n'
  'MATCH (n)\n'
  "WHERE toLower(coalesce(n.caller_name, '')) CONTAINS 'get'\n"
  "   OR toLower(coalesce(n.callee_name, '')) CONTAINS 'get'\n"
  'RETURN DISTINCT n.graph_id, n.caller_class AS caller, n.caller_name AS callerMethod,\n'
  '    n.callee_class AS callee, n.callee_name AS calleeMethod\n'
  'LIMIT 50\n',
  ['n.graph_id', 'caller', 'callerMethod', 'callee', 'calleeMethod'],
  50,
  None),
 ('wrapped-early_graph_prefix_query',
  'wrapped-discovery',
  '\n'
  'MATCH (n)\n'
  "WHERE toLower(coalesce(n.caller_class, '')) STARTS WITH 'android.'\n"
  "   OR toLower(coalesce(n.callee_class, '')) STARTS WITH 'android.'\n"
  'RETURN DISTINCT n.graph_id, n.caller_class AS caller, n.caller_name AS callerMethod,\n'
  '    n.callee_class AS callee, n.callee_name AS calleeMethod\n'
  'LIMIT 1\n',
  ['n.graph_id', 'caller', 'callerMethod', 'callee', 'calleeMethod'],
  1,
  None),
 ('wrapped-middle_graphs_prefix_query',
  'wrapped-discovery',
  '\n'
  'MATCH (n)\n'
  "WHERE toLower(coalesce(n.caller_class, '')) STARTS WITH 'org.apache.tika.'\n"
  "   OR toLower(coalesce(n.callee_class, '')) STARTS WITH 'org.apache.tika.'\n"
  "   OR toLower(coalesce(n.caller_class, '')) STARTS WITH 'org.apache.hadoop.hive.'\n"
  "   OR toLower(coalesce(n.callee_class, '')) STARTS WITH 'org.apache.hadoop.hive.'\n"
  'RETURN DISTINCT n.graph_id, n.caller_class AS caller, n.caller_name AS callerMethod,\n'
  '    n.callee_class AS callee, n.callee_name AS calleeMethod\n'
  'LIMIT 250\n',
  ['n.graph_id', 'caller', 'callerMethod', 'callee', 'calleeMethod'],
  250,
  None),
 ('wrapped-late_graph_prefix_query',
  'wrapped-discovery',
  '\n'
  'MATCH (n)\n'
  "WHERE toLower(coalesce(n.caller_class, '')) STARTS WITH 'org.jetbrains.kotlin.'\n"
  "   OR toLower(coalesce(n.callee_class, '')) STARTS WITH 'org.jetbrains.kotlin.'\n"
  'RETURN DISTINCT n.graph_id, n.caller_class AS caller, n.caller_name AS callerMethod,\n'
  '    n.callee_class AS callee, n.callee_name AS calleeMethod\n'
  'LIMIT 50\n',
  ['n.graph_id', 'caller', 'callerMethod', 'callee', 'calleeMethod'],
  50,
  None),
 ('wrapped-broadly_distributed_prefix_query',
  'wrapped-discovery',
  '\n'
  'MATCH (n)\n'
  "WHERE toLower(coalesce(n.caller_class, '')) STARTS WITH 'java.'\n"
  "   OR toLower(coalesce(n.callee_class, '')) STARTS WITH 'java.'\n"
  'RETURN DISTINCT n.graph_id, n.caller_class AS caller, n.caller_name AS callerMethod,\n'
  '    n.callee_class AS callee, n.callee_name AS calleeMethod\n'
  'LIMIT 250\n',
  ['n.graph_id', 'caller', 'callerMethod', 'callee', 'calleeMethod'],
  250,
  None),
 ('wrapped-first_last_graph_bimodal_query',
  'wrapped-discovery',
  '\n'
  'MATCH (n)\n'
  "WHERE toLower(coalesce(n.caller_class, '')) STARTS WITH 'android.'\n"
  "   OR toLower(coalesce(n.callee_class, '')) STARTS WITH 'android.'\n"
  "   OR toLower(coalesce(n.caller_class, '')) STARTS WITH 'org.jetbrains.kotlin.'\n"
  "   OR toLower(coalesce(n.callee_class, '')) STARTS WITH 'org.jetbrains.kotlin.'\n"
  'RETURN DISTINCT n.graph_id, n.caller_class AS caller, n.caller_name AS callerMethod,\n'
  '    n.callee_class AS callee, n.callee_name AS calleeMethod\n'
  'LIMIT 250\n',
  ['n.graph_id', 'caller', 'callerMethod', 'callee', 'calleeMethod'],
  250,
  None),
 ('wrapped-skewed_mixed_operator_query',
  'wrapped-discovery',
  '\n'
  'MATCH (n)\n'
  "WHERE toLower(coalesce(n.caller_class, '')) STARTS WITH 'android.'\n"
  "   OR toLower(coalesce(n.callee_class, '')) STARTS WITH 'android.'\n"
  "   OR toLower(coalesce(n.caller_name, '')) ENDS WITH 'provider'\n"
  "   OR toLower(coalesce(n.callee_name, '')) ENDS WITH 'provider'\n"
  'RETURN DISTINCT n.graph_id, n.caller_class AS caller, n.caller_name AS callerMethod,\n'
  '    n.callee_class AS callee, n.callee_name AS calleeMethod\n'
  'LIMIT 250\n',
  ['n.graph_id', 'caller', 'callerMethod', 'callee', 'calleeMethod'],
  250,
  None),
 ('routing-pair-dense',
  'graph-routing',
  'MATCH (n)\n'
  "WHERE n.graphId IN ['fixture-android-00', 'fixture-kotlin-compiler-15']\n"
  "  AND (toLower(coalesce(n.caller_class, '')) CONTAINS 'get'\n"
  "    OR toLower(coalesce(n.caller_name, '')) CONTAINS 'get'\n"
  "    OR toLower(coalesce(n.callee_class, '')) CONTAINS 'get'\n"
  "    OR toLower(coalesce(n.callee_name, '')) CONTAINS 'get')\n"
  'RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name\n'
  'LIMIT 200',
  ['n.caller_class', 'n.caller_name', 'n.callee_class', 'n.callee_name'],
  200,
  ['fixture-android-00', 'fixture-kotlin-compiler-15']),
 ('routing-pair-miss',
  'graph-routing',
  'MATCH (n)\n'
  "WHERE n.graphId IN ['fixture-android-00', 'fixture-kotlin-compiler-15']\n"
  "  AND (toLower(coalesce(n.caller_class, '')) CONTAINS 'GraphitePressureAbsentRoutingPairX'\n"
  "    OR toLower(coalesce(n.caller_name, '')) CONTAINS 'GraphitePressureAbsentRoutingPairX'\n"
  "    OR toLower(coalesce(n.callee_class, '')) CONTAINS 'GraphitePressureAbsentRoutingPairX'\n"
  "    OR toLower(coalesce(n.callee_name, '')) CONTAINS 'GraphitePressureAbsentRoutingPairX')\n"
  'RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name\n'
  'LIMIT 200',
  ['n.caller_class', 'n.caller_name', 'n.callee_class', 'n.callee_name'],
  200,
  ['fixture-android-00', 'fixture-kotlin-compiler-15']),
 ('feature-nodes',
  'feature-presence',
  "MATCH (n:FieldNode) RETURN n.graphId AS graphId, 'FieldNode' AS kind, n.generic_type IS NOT NULL AS "
  'rendered, n.type_info IS NOT NULL AS structured, count(*) AS members\n'
  'UNION ALL\n'
  "MATCH (n:ParameterNode) RETURN n.graphId AS graphId, 'ParameterNode' AS kind, n.generic_type IS NOT NULL "
  'AS rendered, n.type_info IS NOT NULL AS structured, count(*) AS members\n'
  'UNION ALL\n'
  "MATCH (n:ReturnNode) RETURN n.graphId AS graphId, 'ReturnNode' AS kind, n.generic_type IS NOT NULL AS "
  'rendered, n.type_info IS NOT NULL AS structured, count(*) AS members',
  ['graphId', 'kind', 'rendered', 'structured', 'members'],
  512,
  None),
 ('feature-methods',
  'feature-presence',
  'MATCH (m:Method) RETURN m.graphId AS graphId, m.generic_return_type IS NOT NULL AS returnRendered, '
  'm.return_type_info IS NOT NULL AS returnStructured, m.generic_parameter_types IS NOT NULL AS '
  'parametersRendered, m.parameter_type_info IS NOT NULL AS parametersStructured, m.type_parameters IS NOT '
  'NULL AS formalParameters, count(*) AS members',
  ['graphId',
   'returnRendered',
   'returnStructured',
   'parametersRendered',
   'parametersStructured',
   'formalParameters',
   'members'],
  512,
  None))


def need(condition, message):
    if not condition:
        raise ValueError(message)


def typed(value):
    """Exact JSON type/value identity; object order is immaterial, arrays are not."""
    if value is None:
        return ['null']
    if type(value) in (bool, int, str):
        return [type(value).__name__, value]
    if type(value) is float:
        need(math.isfinite(value), 'nonfinite JVM JSON scalar needs separate serializer authority')
        return ['float', value.hex()]
    if type(value) is list:
        return ['array', [typed(item) for item in value]]
    need(type(value) is dict and all(type(k) is str for k in value), 'JSON object with string keys')
    return ['object', [[k, typed(value[k])] for k in sorted(value)]]


def key(value):
    return json.dumps(typed(value), ensure_ascii=False, separators=(',', ':'), allow_nan=False)


def digest(value):
    return hashlib.sha256(key(value).encode('utf-8')).hexdigest()


def cases(graph_ids=FIXTURE_GRAPH_IDS):
    """Fresh fixture64 definitions, preserving registry/request/predicate scopes."""
    ids = list(graph_ids)
    need(len(ids) == 64 and all(type(g) is str for g in ids) and
         len(set(ids)) == 64 and set(ids) == set(FIXTURE_GRAPH_IDS), 'exact unique real64 graph identities')
    result = []
    for name, family, query, columns, limit, routed in _DEFINITIONS:
        requested = list(ids)
        target = list(routed) if routed is not None else list(ids)
        request = {'endpoint': '/api/cypher/graphs', 'body': {
            'graphs': requested, 'mode': 'cross-graph', 'query': query,
            'limit': limit, 'timeoutMs': 60000}}
        result.append({'id': name, 'family': family, 'request': request,
                       'registeredGraphIds': list(ids), 'requestedGraphIds': list(ids),
                       'targetGraphIds': target, 'columns': list(columns), 'effectiveLimit': limit,
                       'querySha256': hashlib.sha256(query.encode('utf-8')).hexdigest(),
                       'requestSha256': digest(request)})
    return result


def validate_case(case):
    need(type(case) is dict and type(case.get('requestedGraphIds')) is list, 'explicit requested scope')
    matches = [c for c in cases(case['requestedGraphIds']) if c['id'] == case.get('id')]
    need(len(matches) == 1 and typed(matches[0]) == typed(case), 'exact JVM request definition and three scopes')
    return matches[0]


def gson_value(value):
    """Default Gson map-null omission; array nulls remain values.

    This accepts already projected JSON-like scalars only. It is not a Java
    object/property/toString interpreter or an independent float32 formatter.
    """
    if type(value) is dict:
        need(all(type(k) is str for k in value), 'projected object keys')
        return {k: gson_value(v) for k, v in value.items() if v is not None}
    if type(value) is list:
        return [gson_value(v) for v in value]
    typed(value)
    return value


def validate_row(row, columns, targets, family):
    need(type(row) is dict and set(row) <= set(columns) | {'$metadata'}, 'complete projected column keys')
    need(typed(gson_value(row)) == typed(row), 'JVM map nulls must be omitted, not substituted')
    metadata = row.get('$metadata')
    need(type(metadata) is dict and set(metadata) == {'graphIds'}, 'exact JVM row provenance object')
    ids = metadata['graphIds']
    need(type(ids) is list and ids and all(type(g) is str for g in ids) and
         ids == sorted(set(ids)) and set(ids) <= set(targets), 'sorted unique predicate-scoped provenance')
    if 'graphId' in columns:
        need(type(row.get('graphId')) is str and ids == [row['graphId']], 'projected graph ID and provenance agree')
    if family == 'graph-routing':
        need(len(ids) == 1, 'non-distinct routed row has one source graph')
    typed(row)


def projected_row(values, graph_ids):
    """Build a JVM projected row from independently derived values/provenance."""
    need(type(values) is dict and '$metadata' not in values, 'projected columns cannot supply reserved provenance')
    need(type(graph_ids) in (list, tuple) and graph_ids and
         all(type(g) is str and g for g in graph_ids), 'explicit row graph provenance')
    return {**gson_value(values), '$metadata': {'graphIds': sorted(set(graph_ids))}}


def parse_response(raw):
    """Reject duplicate JSON keys and nonstandard NaN/Infinity before validation."""
    def pairs(entries):
        result = {}
        for name, value in entries:
            need(name not in result, 'duplicate response JSON key')
            result[name] = value
        return result
    def constant(value):
        raise ValueError('nonstandard JSON constant: ' + value)
    need(type(raw) in (bytes, str), 'raw complete JSON response')
    return json.loads(raw, object_pairs_hook=pairs, parse_constant=constant)


class CompiledLegalLimitOracle:
    """Precompile a complete supplied JVM projected-row multiset once.

    Caller-owned derivation/source/input receipts remain mandatory upstream.
    This verifier establishes response agreement only, never oracle authority.
    No expected-universe traversal or recompilation occurs during validate().
    """
    def __init__(self, oracle, case):
        case = validate_case(case)
        grouped = type(oracle) is dict and oracle.get('schema') == 'graphite.jvm-distinct-row-universe.v2'
        if grouped:
            from jvm_pressure_distinct import SCHEMA, EQUALITY, CompiledGroups
        required = {'schema', 'policy', 'caseId', 'requestSha256', 'querySha256',
                    'registeredGraphIds', 'requestedGraphIds', 'targetGraphIds',
                    'columns', 'effectiveLimit', 'allGraphScansComplete',
                    'exactEncounterOrderClaim', 'totalMatches', 'rows'}
        if grouped: required = required - {'rows'} | {'groups', 'equalityPolicy'}
        need(type(oracle) is dict and set(oracle) == required, 'complete supplied JVM oracle shape')
        need(oracle['schema'] == (SCHEMA if grouped else 'graphite.jvm-legal-row-universe.v1') and
             oracle['policy'] == POLICY and oracle['caseId'] == case['id'], 'explicit JVM multiset policy and case')
        for name in ('requestSha256', 'querySha256', 'registeredGraphIds', 'requestedGraphIds',
                     'targetGraphIds', 'columns', 'effectiveLimit'):
            need(typed(oracle[name]) == typed(case[name]), 'exact expected request binding: ' + name)
        need(oracle['allGraphScansComplete'] is True and oracle['exactEncounterOrderClaim'] is False,
             'complete scan and explicit no-encounter-order contract')
        need(type(oracle['totalMatches']) is int and oracle['totalMatches'] >= 0 and
             type(oracle['groups'] if grouped else oracle['rows']) is list, 'complete row universe count')
        self.distinct = None
        if grouped:
            need(case['family'] == 'wrapped-discovery' and oracle['equalityPolicy'] == EQUALITY,
                 'v2 only for the eight wrapped DISTINCT cases')
            self.distinct = CompiledGroups(oracle['groups'], case)
            need(self.distinct.total == oracle['totalMatches'], 'complete DISTINCT group total')
        allowed = Counter()
        distinct_visible = set()
        for entry in ([] if grouped else oracle['rows']):
            need(type(entry) is dict and set(entry) == {'value', 'multiplicity'} and
                 type(entry['multiplicity']) is int and entry['multiplicity'] > 0, 'positive exact row multiplicity')
            row = entry['value']
            validate_row(row, case['columns'], case['targetGraphIds'], case['family'])
            if case['family'] == 'wrapped-discovery':
                need(entry['multiplicity'] == 1, 'DISTINCT projected row multiplicity must be one')
                visible = key({k: v for k, v in row.items() if k != '$metadata'})
                need(visible not in distinct_visible, 'DISTINCT visible projection must be unique across provenance')
                distinct_visible.add(visible)
            identity = key(row)
            need(identity not in allowed, 'unique encoded universe row')
            allowed[identity] = entry['multiplicity']
        if not grouped: need(sum(allowed.values()) == oracle['totalMatches'], 'complete multiset total')
        self.allowed = MappingProxyType(dict(allowed))
        self.total = oracle['totalMatches']
        self.limit = case['effectiveLimit']
        self.graphs = tuple(case['requestedGraphIds'])
        self.targets = tuple(case['targetGraphIds'])
        self.columns = tuple(case['columns'])
        self.family = case['family']

    def validate(self, value):
        need(type(value) is dict and set(value) == ENVELOPE, 'complete JVM seven-field response envelope')
        need(value['mode'] == 'cross-graph' and type(value['graphs']) is list and
             tuple(value['graphs']) == self.graphs, 'exact requested graphs and cross-graph mode')
        need(type(value['graphCount']) is int and value['graphCount'] == len(self.graphs), 'requested graph count')
        need(type(value['limit']) is int and value['limit'] == self.limit, 'exact HTTP limit')
        need(type(value['columns']) is list and tuple(value['columns']) == self.columns and
             type(value['rows']) is list, 'ordered columns and rows array')
        count = min(self.total, self.limit)
        need(type(value['rowCount']) is int and value['rowCount'] == len(value['rows']) == count, 'exact legal LIMIT cardinality')
        for row in value['rows']:
            validate_row(row, self.columns, self.targets, self.family)
        if self.distinct is not None:
            self.distinct.validate(value['rows'])
        else:
            actual = Counter(key(row) for row in value['rows'])
            need(all(n <= self.allowed.get(k, 0) for k, n in actual.items()), 'typed projected values/provenance/multiplicity mismatch')
        return {'status': 'PASS_RESPONSE_AGAINST_SUPPLIED_JVM_UNIVERSE', 'rows': count,
                'completeMatches': self.total, 'policy': POLICY,
                'exactEncounterOrderClaim': False, 'oracleAuthorityVerified': False,
                'fresh64Acceptance': False, 'performanceAcceptance': False}

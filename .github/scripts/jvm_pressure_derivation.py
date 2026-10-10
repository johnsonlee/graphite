"""Independent JVM properties and the exact 34 pressure-case row universes.

Only raw persisted payloads enter this module, never an HTTP response or native
collector result. Source/runtime authority and actual-input receipt replay remain
the caller's job. Unsupported JDK text conversions fail closed; they must be
supplied by a separately bound runtime-semantics authority, not learned results.
"""
from collections import Counter
from dataclasses import dataclass
import math
import struct

import jvm_pressure_inputs as inputs
import jvm_pressure_oracles as model
import jvm_primitive_facts as primitive
import jvm_pressure_distinct as distinct

need = model.need
LABELS = (('IntConstant', 'Constant'), ('StringConstant', 'Constant'), ('LongConstant', 'Constant'),
          ('FloatConstant', 'Constant'), ('DoubleConstant', 'Constant'), ('BooleanConstant', 'Constant'),
          ('NullConstant', 'Constant'), ('EnumConstant', 'Constant'), ('LocalVariable',), ('FieldNode',),
          ('ParameterNode',), ('ReturnNode',), ('CallSiteNode',), ('AnnotationNode', 'Annotation'),
          ('ResourceValueNode', 'ResourceValue', 'Resource'), ('ResourceFileNode', 'ResourceFile'))
HIT = 'android.permission.INTERNET'
MISS = 'GraphiteSlowShapeAbsent293746X'
DECLARED_MISS = 'GraphiteDeclaredAbsent293746X'
ACTIVITY = 'android.app.Activity'
TEXT_NEEDLES = {HIT, MISS, DECLARED_MISS, ACTIVITY}
CALL_PROPERTIES = ('caller_class', 'caller_name', 'callee_class', 'callee_name')
DATAFLOW_IDS = tuple('slow-dataflow' + side + suffix for side in ('Source', 'Target') for suffix in ('Hit', 'Miss'))


class MissingJvmSemantics(ValueError):
    """An actual required runtime transformation has no bound authority yet."""


@dataclass(frozen=True)
class MapText:
    """Annotation.values is Map.toString(), not a Cypher map value."""
    values: dict


def projected_value(value, primitive_facts=None):
    """Cypher materialization followed by default Gson's typed JSON shape."""
    if isinstance(value, inputs.FloatBits):
        if primitive_facts is not None: return primitive_facts.number(value)
        if value.width == 32:
            raise MissingJvmSemantics('projected Float32 needs bound JDK Float.toString authority: ' + value.bits.hex())
        need(value.width == 64 and len(value.bits) == 8, 'raw Double bits')
        result = struct.unpack('>d', value.bits)[0]
        need(math.isfinite(result), 'default Gson rejects projected nonfinite Double')
        # Any Java Double.toString decimal parses back to the same binary64.
        # This does not substitute a binary64 decimal for a Float32 decimal.
        return result
    if isinstance(value, inputs.EnumReference):
        # CypherExecutor materializeValue leaves this ordinary object intact;
        # Gson reflects its two fields, rather than calling its toString().
        return {'enumClass': value.owner, 'enumName': value.name}
    if isinstance(value, MapText):
        raise MissingJvmSemantics('projected complete Map.toString needs bound JDK numeric text authority')
    if type(value) is list: return [projected_value(v, primitive_facts) for v in value]
    if type(value) is dict: return {k: projected_value(v, primitive_facts) for k, v in value.items()}
    need(value is None or type(value) in (bool, int, str), 'supported JVM projected raw value')
    return value


def contains_text(value, needle):
    """Exact CONTAINS for the finite alphabetic/dotted toString probes.

    Kotlin/JDK list/map separators include commas/spaces/= and brackets, none
    of which occur in these needles. Thus matches cannot span entry boundaries.
    Numeric/boolean/null texts cannot contain these probes. Enum's dot CAN join
    the needle and is explicitly rendered before matching. This is not a general
    toString implementation, and is never used to construct a projected string.
    """
    need(needle in TEXT_NEEDLES, 'only reviewed literal text probes')
    if type(value) is str: return needle in value
    if isinstance(value, inputs.EnumReference): return needle in value.owner + '.' + value.name
    if isinstance(value, MapText): return contains_text(value.values, needle)
    if type(value) is dict:
        need(all(type(k) is str for k in value), 'JVM map string keys')
        return any(needle in k or contains_text(v, needle) for k, v in value.items())
    if type(value) is list: return any(contains_text(v, needle) for v in value)
    need(value is None or type(value) in (bool, int) or isinstance(value, inputs.FloatBits), 'known JVM text value')
    return False


def lowered(value, primitive_facts=None):
    # coalesce(x, '') does not coerce a non-null Number/list to String. Kotlin's
    # lowercase() is locale-independent, but its JDK Unicode version matters.
    if value is None: return ''
    if type(value) is not str: return None
    if not value.isascii():
        if primitive_facts is not None: return primitive_facts.lowercase(value)
        raise MissingJvmSemantics('non-ASCII lowercase needs bound JDK Locale.ROOT authority: ' + repr(value))
    return value.lower()


def declaration_id(row, table):
    tag = row['tag']
    if tag == 9:
        return table.fields.get((row['owner'], row['name'], inputs.wire.descriptor(row['type'])))
    if tag not in (10, 11): return None
    declaration = table.methods.get(row['method'].key)
    if declaration is None: return None
    if tag == 11: return declaration['result']
    index = row['index']
    return declaration['parameters'][index] if 0 <= index < len(declaration['parameters']) else None


def node_properties(row, graph_id, table, type_cache=None):
    """JVM accessor values and keys(n), retaining their different precedence."""
    node, tag = row['id'], row['tag']; need(0 <= tag < len(LABELS), 'known JVM node label')
    values = {'id': node}; keys = None
    if tag <= 6: values['value'] = row['value']
    elif tag == 7:
        values.update(value=row['arguments'][0] if row['arguments'] else None,
                      name=row['name'], enum_type=row['enum_type'])
    elif tag == 8: values.update(name=row['name'], type=row['type'], method=row['method'].signature)
    elif tag == 9: values.update(name=row['name'], type=row['type'], **{'class': row['owner'], 'static': row['static']})
    elif tag == 10: values.update(index=row['index'], type=row['type'], method=row['method'].signature)
    elif tag == 11: values.update(method=row['method'].signature, actual_type=row['actual_type'])
    elif tag == 12:
        for prefix in ('callee', 'caller'):
            method = row[prefix]
            values.update({prefix+'_class': method.owner, prefix+'_name': method.name,
                           prefix+'_signature': method.signature, prefix+'_descriptor': method.key[2]})
        values.update(line=None if row['line'] == -1 else row['line'], ordinal=row['ordinal'])
    elif tag == 13:
        fixed = {'id': node, 'name': row['name'], 'class': row['owner'], 'member': row['member']}
        keys = list(dict.fromkeys([*fixed, *row['values']]))
        values.update(row['values']); values.update(fixed)
        values['values'] = MapText(row['values'])
    elif tag == 14:
        values.update(path=row['path'], key=row['key'], value=row['value'], format=row['format'], profile=row['profile'])
    elif tag == 15:
        values.update(path=row['path'], source=row['source'], format=row['format'], profile=row['profile'])
    if tag in (9, 10, 11):
        type_id = declaration_id(row, table)
        if type_id is not None:
            if type_cache is None: projection = table.render(type_id), table.info(type_id)
            else:
                if type_id not in type_cache: type_cache[type_id] = table.render(type_id), table.info(type_id)
                projection = type_cache[type_id]
            values['generic_type'], values['type_info'] = projection
    if keys is None: keys = list(values)
    # A virtual type fallback is visible through n.type, but not newly in keys.
    if values.get('type') is None: values['type'] = LABELS[tag][0]
    values.update(graphId=graph_id, elementId=f'{graph_id}:{node}', qualifiedId=f'{graph_id}:{node}')
    keys = list(dict.fromkeys([*keys, 'graphId', 'elementId', 'qualifiedId']))
    return values, keys



def full_projection_matches(case, row, graph_id):
    """The fixed two-source prefixes, before projection or LIMIT."""
    if graph_id not in case['targetGraphIds']: return False
    kind = case['id'].split('-')[0]
    if kind != 'method' and row['tag'] != {'field': 9, 'parameter': 10, 'return': 11}[kind]: return False
    kotlin = graph_id == 'fixture-kotlin-compiler-15'
    if kind == 'field':
        value = row['owner']; prefixes = ('org.jetbrains.kotlin.com.intellij.psi.', 'org.jetbrains.kotlin.backend.common.') if kotlin else ('org.openxmlformats.',)
    elif kind == 'parameter':
        value = row['type']; prefixes = ('java.util.',) if kotlin else ('com.',)
    elif kind == 'return':
        value = row['method'].signature; prefixes = ('org.jetbrains.kotlin.backend.jvm.lower.',) if kotlin else ('org.apache.logging.',)
    else:
        value = row.owner; prefixes = ('org.jetbrains.kotlin.ir.backend.js.lower.',) if kotlin else ('org.apache.logging.',)
    return any(value.startswith(prefix) for prefix in prefixes)


def full_node_projection(row, graph_id, table, *, properties):
    """Independent nodeToMap/getAllProperties rules; never serialize accessor fallbacks."""
    tag = row['tag']; value = {'id': row['id']}
    if tag == 9:
        value.update(name=row['name'], type=row['type'], **{'class': row['owner'], 'static': row['static']})
    elif tag == 10:
        value.update(index=row['index'], type=row['type'], method=row['method'].signature)
    else:
        need(tag == 11, 'full projection field/parameter/return kind')
        if not properties: value['type'] = 'ReturnNode'
        value.update(method=row['method'].signature, actual_type=row['actual_type'])
    type_id = declaration_id(row, table)
    if type_id is not None:
        value.update(generic_type=table.render(type_id), type_info=table.info(type_id))
    value.update(graphId=graph_id, elementId=f"{graph_id}:{row['id']}", qualifiedId=f"{graph_id}:{row['id']}")
    return value


def full_method_projection(method, graph_id, table):
    """MethodValue.properties uses full metadata descriptors, including return type."""
    value = dict(signature=method.signature, **{'class': method.owner}, name=method.name,
                 parameter_types=list(method.parameters), return_type=method.result)
    declaration = table.methods.get(method.key)
    if declaration is not None:
        value.update(generic_return_type=table.render(declaration['result']),
                     generic_parameter_types=[table.render(i) for i in declaration['parameters']],
                     return_type_info=table.info(declaration['result']),
                     parameter_type_info=[table.info(i) for i in declaration['parameters']],
                     type_parameters=[dict(name=f['name'], scope=f['scope'],
                         bounds=[table.render(i) for i in f['bounds']],
                         bound_info=[table.info(i) for i in f['bounds']]) for f in declaration['formals']])
    value['graphId'] = graph_id
    return value

def _string_contains(value, needle): return type(value) is str and needle in value


def _discovery(case_id, properties, primitive_facts=None):
    lower = {key: lowered(properties.get(key), primitive_facts) for key in CALL_PROPERTIES}
    def matches(keys, op, word):
        return any(v is not None and (word in v if op == 'contains' else v.startswith(word) if op == 'starts' else v.endswith(word))
                   for v in (lower[k] for k in keys))
    classes = ('caller_class', 'callee_class'); names = ('caller_name', 'callee_name')
    if case_id == 'wrapped-zero_hit_query': return matches(CALL_PROPERTIES, 'contains', 'graphite_latency_no_such_symbol_9f36')
    if case_id == 'wrapped-dense_distributed_method_query': return matches(names, 'contains', 'get')
    if case_id == 'wrapped-early_graph_prefix_query': return matches(classes, 'starts', 'android.')
    if case_id == 'wrapped-middle_graphs_prefix_query':
        return matches(classes, 'starts', 'org.apache.tika.') or matches(classes, 'starts', 'org.apache.hadoop.hive.')
    if case_id == 'wrapped-late_graph_prefix_query': return matches(classes, 'starts', 'org.jetbrains.kotlin.')
    if case_id == 'wrapped-broadly_distributed_prefix_query': return matches(classes, 'starts', 'java.')
    if case_id == 'wrapped-first_last_graph_bimodal_query':
        return matches(classes, 'starts', 'android.') or matches(classes, 'starts', 'org.jetbrains.kotlin.')
    if case_id == 'wrapped-skewed_mixed_operator_query':
        return matches(classes, 'starts', 'android.') or matches(names, 'ends', 'provider')
    if case_id == 'routing-pair-dense': return matches(CALL_PROPERTIES, 'contains', 'get')
    if case_id == 'routing-pair-miss': return matches(CALL_PROPERTIES, 'contains', 'GraphitePressureAbsentRoutingPairX')
    raise ValueError('unknown discovery request')


def node_matches(case, row, properties, keys, primitive_facts=None):
    case_id = case['id']
    if properties['graphId'] not in case['targetGraphIds']: return False
    if case['family'] in ('wrapped-discovery', 'graph-routing'): return _discovery(case_id, properties, primitive_facts)
    if case_id == 'callsite-dynamic-miss' and row['tag'] != 12: return False
    if case_id in ('dynamic-miss', 'callsite-dynamic-miss', 'slow-dynamicHit', 'slow-dynamicMiss'):
        needle = HIT if case_id == 'slow-dynamicHit' else MISS if case_id == 'slow-dynamicMiss' else DECLARED_MISS
        return any(contains_text(properties.get(k), needle) for k in keys)
    if case_id in ('slow-valueHit', 'slow-valueMiss'):
        return _string_contains(properties.get('value'), HIT if case_id.endswith('Hit') else MISS)
    if case_id in ('slow-qualifiedIdHit', 'slow-qualifiedIdMiss'):
        return ('938826' if case_id.endswith('Hit') else '93882699') in properties['qualifiedId']
    if case_id in ('slow-wrappedCallerHit', 'slow-wrappedCallerMiss'):
        return contains_text(properties.get('caller_class'), ACTIVITY if case_id.endswith('Hit') else MISS)
    raise ValueError('not a node predicate case: ' + case_id)


def raw_node_projection(case, row, values):
    """Original JVM values, before DISTINCT comparison or Gson projection."""
    if case['family'] == 'wrapped-discovery':
        result = dict(zip(case['columns'], [values.get('graph_id'), *(values.get(k) for k in CALL_PROPERTIES)]))
    elif case['family'] == 'graph-routing': result = dict(zip(case['columns'], (values.get(k) for k in CALL_PROPERTIES)))
    else:
        result = {'id': row['id'], 'labels': list(LABELS[row['tag']]), 'graphId': values['graphId']}
        if 'qualifiedId' in case['columns']: result['qualifiedId'] = values['qualifiedId']
        else: result.update(value=values.get('value'), caller=values.get('caller_class'))
    return result


def node_projection(case, row, values, primitive_facts=None):
    return {k: projected_value(v, primitive_facts) for k, v in raw_node_projection(case, row, values).items()}


class Collector:
    """One all64 traversal builds exact multisets, without LIMIT truncation."""
    def __init__(self, graph_ids=model.FIXTURE_GRAPH_IDS, primitive_facts=None):
        self.cases = model.cases(graph_ids); self.graph_ids = tuple(graph_ids)
        need(primitive_facts is None or isinstance(primitive_facts, primitive.PrimitiveFacts), 'primitive facts consumer type')
        self.primitive_facts = primitive_facts
        self.counts = {c['id']: Counter() for c in self.cases}; self.rows = {c['id']: {} for c in self.cases}
        self.distinct = {c['id']: distinct.DistinctGroups(self._number_text)
                         for c in self.cases if c['family'] == 'wrapped-discovery'}
        self.projection_bindings = {c['id']: Counter() for c in self.cases if c['family'] == 'full-projection'}
        self.seen = []; self.graph_counts = []; self.failed = False

    def _number_text(self, raw):
        if self.primitive_facts is None:
            raise MissingJvmSemantics('wrapped numeric DISTINCT needs bound JDK number text authority')
        return self.primitive_facts.text('float32' if raw.width == 32 else 'double', raw.bits.hex())

    def _add(self, case, values, graph_id, multiplicity=1):
        need(case['family'] != 'wrapped-discovery', 'DISTINCT requires raw pre-Gson values')
        row = model.projected_row(values, [graph_id]); key = model.key(row)
        self.counts[case['id']][key] += multiplicity; self.rows[case['id']][key] = row

    def add_graph(self, graph, raw_edges, edge_count):
        need(not self.failed, 'previous graph derivation failed')
        try: self._add_graph(graph, raw_edges, edge_count)
        except BaseException:
            self.failed = True
            raise

    def _add_graph(self, graph, raw_edges, edge_count):
        gid = graph['id']; need(gid in self.graph_ids and gid not in self.seen, 'unique registered graph derivation')
        need(graph['fullNodeScanConsumed'] is True, 'complete raw node scan required')
        if self.primitive_facts is not None: self.primitive_facts.bind_graph(graph)
        nodes = graph['nodes']; table = graph['declarations']; cache = {}; properties = {}; groups = Counter()
        node_cases = [c for c in self.cases if c['id'] not in DATAFLOW_IDS and c['family'] not in ('feature-presence', 'full-projection')]
        projection_cases = [c for c in self.cases if c['family'] == 'full-projection']
        for node_id, row in nodes.items():
            need(node_id == row['id'], 'actual node ID binding')
            values, keys = node_properties(row, gid, table, cache); properties[node_id] = values
            for case in node_cases:
                if node_matches(case, row, values, keys, self.primitive_facts):
                    if case['id'] in self.distinct:
                        self.distinct[case['id']].add(raw_node_projection(case, row, values), gid)
                    else:
                        self._add(case, node_projection(case, row, values, self.primitive_facts), gid)
            if row['tag'] in (9, 10, 11):
                for case in projection_cases:
                    if not case['id'].startswith('method-') and full_projection_matches(case, row, gid):
                        self.projection_bindings[case['id']][gid] += int(declaration_id(row, table) is not None)
                        self._add(case, {'value': full_node_projection(row, gid, table,
                                  properties=case['id'].endswith('-properties'))}, gid)
                present = declaration_id(row, table) is not None
                groups[(LABELS[row['tag']][0], present)] += 1
        feature = next(c for c in self.cases if c['id'] == 'feature-nodes')
        for (kind, present), count in groups.items():
            self._add(feature, dict(graphId=gid, kind=kind, rendered=present, structured=present, members=count), gid)
        for method in graph['methods'].values():
            for case in projection_cases:
                if case['id'].startswith('method-') and full_projection_matches(case, method, gid):
                    self.projection_bindings[case['id']][gid] += int(method.key in table.methods)
                    self._add(case, {'value': full_method_projection(method, gid, table)}, gid)
        methods = Counter(method.key in table.methods for method in graph['methods'].values())
        feature = next(c for c in self.cases if c['id'] == 'feature-methods')
        for present, count in methods.items():
            self._add(feature, dict(graphId=gid, returnRendered=present, returnStructured=present,
                                   parametersRendered=present, parametersStructured=present, formalParameters=present,
                                   members=count), gid)
        edge_cases = [c for c in self.cases if c['id'] in DATAFLOW_IDS]; scanned = 0; dataflow = 0
        for source, target, label in inputs.edge_records(raw_edges, nodes, graph['nodeSlots'], edge_count):
            scanned += 1
            if label & 7: continue
            # Actual V3 decoder indexes the low four kind bits. Preserve raw
            # octet parsing; invalid enum ordinals must not become legal rows.
            need(((label >> 3) & 15) <= 8, 'valid JVM DATAFLOW kind ordinal')
            dataflow += 1; src = properties[source]; dst = properties[target]
            for case in edge_cases:
                source_case = 'Source' in case['id']; hit = case['id'].endswith('Hit')
                matches = _string_contains(src.get('value'), HIT if hit else MISS) if source_case else _string_contains(dst.get('caller_class'), ACTIVITY if hit else MISS)
                if matches:
                    self._add(case, dict(source=source, target=target, relationship='DATAFLOW',
                                         value=projected_value(src.get('value'), self.primitive_facts),
                                         caller=projected_value(dst.get('caller_class'), self.primitive_facts), graphId=gid), gid)
        self.seen.append(gid); self.graph_counts.append({'id': gid, 'nodes': len(nodes), 'methods': len(graph['methods']),
                                                      'edges': scanned, 'dataflowEdges': dataflow})

    def finish(self):
        need(not self.failed and len(self.seen) == 64 and set(self.seen) == set(self.graph_ids), 'complete successful all64 derivation')
        results = {}
        for case in self.cases:
            case_id = case['id']
            universe = {'policy': model.POLICY, 'caseId': case_id,
                        **{k: case[k] for k in ('requestSha256', 'querySha256', 'registeredGraphIds', 'requestedGraphIds',
                                              'targetGraphIds', 'columns', 'effectiveLimit')},
                        'allGraphScansComplete': True, 'exactEncounterOrderClaim': False}
            if case_id in self.distinct:
                groups = self.distinct[case_id].finish()
                universe.update(schema=distinct.SCHEMA, equalityPolicy=distinct.EQUALITY,
                                totalMatches=len(groups), groups=groups)
            else:
                entries = [{'value': self.rows[case_id][key], 'multiplicity': count}
                           for key, count in self.counts[case_id].items()]
                universe.update(schema='graphite.jvm-legal-row-universe.v1',
                                totalMatches=sum(e['multiplicity'] for e in entries), rows=entries)
            model.CompiledLegalLimitOracle(universe, case)
            results[case_id] = universe
        return {'universes': results, 'graphs': self.graph_counts,
                'fullProjectionBindings': {c['id']: {gid: self.projection_bindings[c['id']][gid] for gid in c['targetGraphIds']}
                                           for c in self.cases if c['family'] == 'full-projection'},
                'scope': 'JVM_SOURCE_MODEL_DERIVATION_FROM_SUPPLIED_RAW_INPUTS_NOT_AUDIT_AUTHORITY',
                'sourceRuntimeBindingsRequired': True, 'oracleAuthorityVerified': False,
                'fresh64Acceptance': False, 'performanceAcceptance': False}

"""All64 primitive request collection and consumption; no Java execution here.

The future owner compiles/executes JvmPrimitiveFacts once per arm through the
existing producer.phase lifecycle, then binds that actual phase/source/class/JDK
evidence. Parsing the packet below alone does NOT establish execution authority.
"""
import copy
import hashlib
import math
from pathlib import Path
import re
import struct

import jvm_pressure_inputs as inputs
import jvm_pressure_oracles as model

need = model.need
JDK_FILES = ('bin/java', 'release', 'lib/modules')


def _ref(value):
    need(type(value) is dict and set(value) == {'path', 'sha256'} and type(value['path']) is str and
         Path(value['path']).is_absolute() and type(value['sha256']) is str and
         re.fullmatch('[0-9a-f]{64}', value['sha256']), 'exact primitive authority reference')


def _graph_binding(graph):
    need(graph['fullNodeScanConsumed'] is True and type(graph['revision']) is str and
         re.fullmatch('[0-9a-f]{40}', graph['revision']), 'complete source graph/revision binding')
    pins = graph['consumedPins']; need(type(pins) is dict and pins, 'consumed raw graph input pins')
    for path, digest in pins.items(): _ref({'path': path, 'sha256': digest})
    return {'id': graph['id'], 'revision': graph['revision'], 'consumedPins': copy.deepcopy(pins)}


def _operation(operation):
    need(type(operation) is dict and set(operation) == {'operation', 'input'} and
         type(operation['input']) is str, 'exact primitive operation')
    kind, value = operation['operation'], operation['input']
    need(kind in ('float32', 'double', 'lowercaseRoot'), 'allowed JDK primitive operation')
    if kind != 'lowercaseRoot':
        need(re.fullmatch('[0-9a-f]{' + ('8' if kind == 'float32' else '16') + '}', value), 'exact primitive bits')
    else: value.encode('utf-8', errors='strict')
    return kind, value


def validate_request(packet):
    need(type(packet) is dict and set(packet) == {'schema', 'scope', 'sourceGraphs', 'operations',
                                                'helperSource', 'helperClass', 'jdkImage'} and
         packet['schema'] == 'graphite.jvm-primitive-requests.v1' and
         packet['scope'] == 'RAW_INPUT_CONVERSIONS_NOT_QUERY_RESULTS', 'exact primitive request schema')
    graphs = packet['sourceGraphs']
    need(type(graphs) is list and len(graphs) == 64, 'complete primitive graph scope')
    model.cases([g['id'] for g in graphs])
    for graph in graphs:
        need(set(graph) == {'id', 'revision', 'consumedPins'}, 'exact primitive source binding fields')
        _graph_binding({**graph, 'fullNodeScanConsumed': True})
    need(len({g['revision'] for g in graphs}) == 1, 'single actual writer revision per primitive batch')
    pins = {}
    for graph in graphs:
        for path, digest in graph['consumedPins'].items():
            need(path not in pins or pins[path] == digest, 'conflicting raw source input binding')
            pins[path] = digest
    for key in ('helperSource', 'helperClass'): _ref(packet[key])
    need(Path(packet['helperSource']['path']).name == 'JvmPrimitiveFacts.java' and
         Path(packet['helperClass']['path']).name == 'JvmPrimitiveFacts.class', 'specific primitive helper identity')
    jdk = packet['jdkImage']
    need(type(jdk) is dict and set(jdk) == {'home', 'files'} and type(jdk['home']) is str and
         Path(jdk['home']).is_absolute() and type(jdk['files']) is dict and
         set(jdk['files']) == set(JDK_FILES), 'complete actual JDK image identity')
    for name in JDK_FILES:
        _ref(jdk['files'][name]); need(jdk['files'][name]['path'] == str(Path(jdk['home'])/name), 'JDK image path binding')
    operations = packet['operations']; need(type(operations) is list, 'complete primitive operation list')
    identities = [_operation(op) for op in operations]
    need(len(set(identities)) == len(identities), 'deduplicated primitive operations')
    return packet


class RequestCollector:
    """Collect only primitive facts, retaining no full graph objects."""
    def __init__(self, graph_ids=model.FIXTURE_GRAPH_IDS):
        model.cases(graph_ids); self.graph_ids = tuple(graph_ids); self.graphs = {}; self.operations = {}
        self.failed = False

    def _visit(self, value):
        if isinstance(value, inputs.FloatBits):
            need(value.width in (32, 64) and len(value.bits) == value.width//8, 'exact raw floating bits')
            key = ('float32' if value.width == 32 else 'double', value.bits.hex())
        elif type(value) is str:
            if value.isascii(): return
            key = ('lowercaseRoot', value)
        elif isinstance(value, inputs.wire.Method):
            for item in (value.owner, value.name, *value.parameters, value.result): self._visit(item)
            return
        elif isinstance(value, inputs.EnumReference):
            self._visit(value.owner); self._visit(value.name); return
        elif type(value) is dict:
            for name, item in value.items(): self._visit(name); self._visit(item)
            return
        elif type(value) in (list, tuple):
            for item in value: self._visit(item)
            return
        else:
            need(value is None or type(value) in (bool, int), 'known primitive raw payload')
            return
        self.operations.setdefault(key, {'operation': key[0], 'input': key[1]})

    def add_graph(self, graph):
        need(not self.failed, 'primitive request collection already failed')
        try:
            gid = graph['id']; need(gid in self.graph_ids and gid not in self.graphs, 'unique primitive source graph')
            binding = _graph_binding(graph)
            self._visit(graph['nodes']); self.graphs[gid] = binding
        except BaseException:
            self.failed = True
            raise

    def finish(self, helper_source, helper_class, jdk_image):
        need(not self.failed and set(self.graphs) == set(self.graph_ids), 'complete successful all64 primitive collection')
        packet = {'schema': 'graphite.jvm-primitive-requests.v1', 'scope': 'RAW_INPUT_CONVERSIONS_NOT_QUERY_RESULTS',
                  'sourceGraphs': [self.graphs[gid] for gid in self.graph_ids],
                  'operations': list(self.operations.values()), 'helperSource': helper_source,
                  'helperClass': helper_class, 'jdkImage': jdk_image}
        validate_request(packet)
        return copy.deepcopy(packet)


def _number(kind, bits, text):
    width = 32 if kind == 'float32' else 64; fmt = '>f' if width == 32 else '>d'
    original = struct.unpack(fmt, bytes.fromhex(bits))[0]
    if not math.isfinite(original):
        expected = 'NaN' if math.isnan(original) else '-Infinity' if original < 0 else 'Infinity'
        need(text == expected, 'nonfinite JDK primitive spelling/bits')
        return None
    need(re.fullmatch(r'-?[0-9]+\.[0-9]+(?:E-?[0-9]+)?', text), 'JDK floating text grammar')
    number = float(text)
    need(math.isfinite(number), 'finite JDK decimal')
    try: actual = struct.pack(fmt, number).hex()
    except (OverflowError, struct.error): raise ValueError('JDK decimal roundtrip overflow')
    need(actual == bits, 'JDK decimal must roundtrip to exact original bits')
    return number


class PrimitiveFacts:
    """Consume a complete packet; caller still must audit its owned JDK phase."""
    def __init__(self, request_raw, request_path, result_raw):
        self.request = copy.deepcopy(validate_request(model.parse_response(request_raw)))
        result = model.parse_response(result_raw)
        need(type(result) is dict and set(result) == {'schema', 'scope', 'request', 'helperSource', 'helperClass',
            'jdkImage', 'graphCount', 'operationCount', 'results', 'sourceGraphBytesVerified', 'queryImplementationUsed'} and
            result['schema'] == 'graphite.jvm-primitive-facts.v1' and
            result['scope'] == 'JDK_PRIMITIVE_CONVERSIONS_NOT_GRAPH_ORACLE', 'exact JDK primitive result schema')
        raw = request_raw.encode('utf-8') if type(request_raw) is str else request_raw
        need(Path(request_path).is_absolute() and result['request'] ==
             {'path': str(request_path), 'sha256': hashlib.sha256(raw).hexdigest()}, 'exact executed primitive request bytes')
        for name in ('helperSource', 'helperClass', 'jdkImage'):
            need(model.typed(result[name]) == model.typed(self.request[name]), 'actual JDK/helper identity: ' + name)
        need(type(result['graphCount']) is int and result['graphCount'] == 64 and
             type(result['operationCount']) is int and result['operationCount'] == len(self.request['operations']) and
             type(result['results']) is list and len(result['results']) == result['operationCount'] and
             result['sourceGraphBytesVerified'] is False and result['queryImplementationUsed'] is False,
             'complete primitive result count/scope')
        self.facts = {}
        for index, (op, actual) in enumerate(zip(self.request['operations'], result['results'])):
            need(type(actual) is dict and set(actual) == {'index', 'operation', 'input', 'output'} and
                 type(actual['index']) is int and actual['index'] == index and
                 actual['operation'] == op['operation'] and actual['input'] == op['input'] and
                 type(actual['output']) is str, 'ordered complete primitive input/output binding')
            kind, value = _operation(op)
            if kind != 'lowercaseRoot': _number(kind, value, actual['output'])
            else: actual['output'].encode('utf-8', errors='strict')
            self.facts[(kind, value)] = actual['output']
        self.graphs = {g['id']: copy.deepcopy(g) for g in self.request['sourceGraphs']}
        self.executionAuthorityVerified = False

    def bind_graph(self, graph):
        need(self.graphs.get(graph['id']) == _graph_binding(graph), 'primitive facts actual graph input binding')

    def text(self, kind, value):
        need((kind, value) in self.facts, 'missing actual primitive fact: ' + kind)
        return self.facts[(kind, value)]

    def number(self, raw):
        need(isinstance(raw, inputs.FloatBits) and raw.width in (32, 64), 'raw floating fact request')
        kind = 'float32' if raw.width == 32 else 'double'; value = raw.bits.hex()
        number = _number(kind, value, self.text(kind, value))
        need(number is not None, 'default Gson rejects projected nonfinite floating value')
        return number

    def lowercase(self, value): return self.text('lowercaseRoot', value)

"""Pre-Gson JVM DISTINCT keys and observed whole-row representatives.

Numeric text is injected by the caller's separately bound JDK facts. These pure
helpers neither acquire those facts nor establish source/runtime authority.
"""
import copy
import math
import re
import struct

import jvm_pressure_inputs as inputs
import jvm_pressure_oracles as model

SCHEMA = 'graphite.jvm-distinct-row-universe.v2'
EQUALITY = 'cypher-value-key'
need = model.need
DECIMAL = re.compile(r'(-?)([0-9]+)(?:\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?\Z')


def decimal_key(text):
    """BigDecimal.stripTrailingZeros identity without a decimal context."""
    need(type(text) is str and len(text) <= 128, 'bounded JDK decimal text')
    match = DECIMAL.fullmatch(text)
    need(match is not None, 'finite JDK decimal text')
    sign, integral, fraction, exponent = match.groups(); fraction = fraction or ''
    digits = (integral + fraction).lstrip('0')
    if not digits: return ['number', '0', 0]
    power = int(exponent or '0') - len(fraction)
    trailing = len(digits) - len(digits.rstrip('0'))
    digits = digits.rstrip('0'); power += trailing
    return ['number', sign + digits, power]


def float_text(value, number_text):
    need(callable(number_text), 'bound JDK number_text facts required')
    need(value.width in (32, 64) and len(value.bits) == value.width // 8, 'raw Float/Double bits')
    text = number_text(value)
    need(type(text) is str, 'actual JDK number text')
    fmt = '>f' if value.width == 32 else '>d'
    original = struct.unpack(fmt, value.bits)[0]
    if math.isnan(original): need(text == 'NaN', 'JDK NaN text')
    elif math.isinf(original): need(text == ('Infinity' if original > 0 else '-Infinity'), 'JDK infinity text')
    else:
        decimal_key(text)
        try: encoded = struct.pack(fmt, float(text))
        except (ValueError, OverflowError): raise ValueError('JDK decimal does not roundtrip raw bits')
        need(encoded == value.bits, 'JDK decimal does not roundtrip raw bits')
    return text


def semantic_key(value, number_text=None):
    if value is None: return ['null']
    if type(value) is bool: return ['bool', value]
    if type(value) is int: return decimal_key(str(value))
    if type(value) is str: return ['str', value]
    if isinstance(value, inputs.FloatBits):
        text = float_text(value, number_text)
        return ['nonfinite', text] if text in ('NaN', 'Infinity', '-Infinity') else decimal_key(text)
    if isinstance(value, inputs.EnumReference): return ['enum', value.owner, value.name]
    if type(value) is list: return ['list', [semantic_key(v, number_text) for v in value]]
    need(type(value) is dict and all(type(k) is str for k in value), 'raw JVM property map with string keys')
    return ['map', [[k, semantic_key(value[k], number_text)] for k in sorted(value)]]


class NonfiniteProjection(ValueError):
    """Default Gson cannot serialize this observed representative."""


def projected_value(value, number_text=None):
    if isinstance(value, inputs.FloatBits):
        text = float_text(value, number_text)
        if text in ('NaN', 'Infinity', '-Infinity'): raise NonfiniteProjection('default Gson nonfinite number')
        return float(text)
    if isinstance(value, inputs.EnumReference): return {'enumClass': value.owner, 'enumName': value.name}
    if type(value) is list: return [projected_value(v, number_text) for v in value]
    if type(value) is dict:
        need(all(type(k) is str for k in value), 'raw JVM property map with string keys')
        return {k: projected_value(v, number_text) for k, v in value.items()}
    need(value is None or type(value) in (bool, int, str), 'raw JVM scalar projection')
    return value


def validate_key(value):
    """Canonical key grammar; derivation replay establishes its actual authority."""
    need(type(value) is list and value and type(value[0]) is str, 'canonical semantic key')
    tag = value[0]
    if tag == 'null': need(len(value) == 1, 'null semantic key')
    elif tag in ('str', 'bool'):
        need(len(value) == 2 and type(value[1]) is (str if tag == 'str' else bool), 'typed semantic scalar')
    elif tag == 'number':
        need(len(value) == 3 and type(value[1]) is str and type(value[2]) is int and
             re.fullmatch(r'-?(?:0|[1-9][0-9]*)', value[1]) and
             value[1] != '-0' and (value[1] == '0' and value[2] == 0 or value[1] != '0' and not value[1].endswith('0')),
             'canonical exact decimal coefficient/exponent')
    elif tag == 'nonfinite': need(len(value) == 2 and value[1] in ('NaN','Infinity','-Infinity'), 'nonfinite key')
    elif tag == 'enum': need(len(value) == 3 and all(type(v) is str for v in value[1:]), 'enum object key')
    elif tag == 'list':
        need(len(value) == 2 and type(value[1]) is list, 'list semantic key')
        for item in value[1]: validate_key(item)
    elif tag == 'map':
        need(len(value) == 2 and type(value[1]) is list, 'map semantic key')
        keys = []
        for item in value[1]:
            need(type(item) is list and len(item) == 2 and type(item[0]) is str, 'map key entry')
            keys.append(item[0]); validate_key(item[1])
        need(keys == sorted(set(keys)), 'canonical map key ordering')
    else: raise ValueError('unknown semantic key tag')


class DistinctGroups:
    """Full source groups; no LIMIT and no per-field representative combinations."""
    def __init__(self, number_text=None):
        self.number_text = number_text; self.groups = {}; self.facts = {}; self.failed = False

    def _text(self, value):
        key = value.width, value.bits
        if key not in self.facts:
            need(callable(self.number_text), 'bound JDK number_text facts required')
            self.facts[key] = self.number_text(value)
        return self.facts[key]

    def add(self, raw_values, graph_id):
        need(not self.failed, 'previous DISTINCT derivation failed')
        try:
            need(type(raw_values) is dict and '$metadata' not in raw_values and type(graph_id) is str and graph_id,
                 'raw projected columns and explicit graph identity')
            semantic = semantic_key(raw_values, self._text); key = model.key(semantic)
            entry = self.groups.setdefault(key, {'semanticKey': semantic, 'graphIds': set(), 'variants': {}})
            entry['graphIds'].add(graph_id)
            try: visible = model.gson_value(projected_value(raw_values, self._text))
            except NonfiniteProjection: return  # Keep its semantic group in the complete count.
            entry['variants'][model.key(visible)] = visible
        except BaseException:
            self.failed = True
            raise

    def finish(self):
        need(not self.failed, 'previous DISTINCT derivation failed')
        return copy.deepcopy([{'semanticKey': v['semanticKey'], 'graphIds': sorted(v['graphIds']),
                               'variants': list(v['variants'].values())} for v in self.groups.values()])


class CompiledGroups:
    """JSON collisions are a capacity-one group matching problem, not a Counter."""
    def __init__(self, groups, case):
        from types import MappingProxyType
        need(type(groups) is list, 'complete DISTINCT groups')
        identities = set(); index = {}
        for group_id, group in enumerate(groups):
            need(type(group) is dict and set(group) == {'semanticKey','graphIds','variants'}, 'exact DISTINCT group shape')
            semantic = group['semanticKey']; validate_key(semantic)
            need(semantic[0] == 'map' and {p[0] for p in semantic[1]} == set(case['columns']), 'complete pre-Gson projected semantic columns')
            identity = model.key(semantic)
            need(identity not in identities, 'unique DISTINCT semantic group'); identities.add(identity)
            ids = group['graphIds']
            need(type(ids) is list and ids and all(type(g) is str for g in ids) and
                 ids == sorted(set(ids)) and set(ids) <= set(case['targetGraphIds']), 'complete group provenance')
            variants = group['variants']; need(type(variants) is list, 'observed whole-row variants')
            # An actual nonfinite group remains counted, even though Gson cannot emit it.
            def nonfinite(key):
                return key[0] == 'nonfinite' or (key[0] == 'list' and any(nonfinite(x) for x in key[1])) or (key[0] == 'map' and any(nonfinite(x[1]) for x in key[1]))
            need(bool(variants) != bool(nonfinite(semantic)), 'serializable group variants or explicit nonfinite group')
            seen = set()
            for visible in variants:
                need(type(visible) is dict and '$metadata' not in visible, 'visible whole-row variant')
                row = model.projected_row(visible, ids)
                need(model.typed(model.gson_value(visible)) == model.typed(visible), 'canonical Gson variant')
                model.validate_row(row, case['columns'], case['targetGraphIds'], case['family'])
                key = model.key(row)
                need(key not in seen, 'unique whole-row variants'); seen.add(key)
                index.setdefault(key, []).append(group_id)
        self.index = MappingProxyType({key: tuple(ids) for key, ids in index.items()})
        self.total = len(groups)

    def validate(self, rows):
        # Iterative augmenting paths avoid recursion depth depending on LIMIT.
        candidates = [self.index.get(model.key(row), ()) for row in rows]
        matched = {}; row_group = {}
        for start in range(len(rows)):
            queue = [start]; seen_rows = {start}; seen_groups = set(); back = {}; free = None
            for row_id in queue:
                for group_id in candidates[row_id]:
                    if group_id in seen_groups: continue
                    seen_groups.add(group_id); back[group_id] = row_id
                    if group_id not in matched:
                        free = group_id; break
                    previous = matched[group_id]
                    if previous not in seen_rows: seen_rows.add(previous); queue.append(previous)
                if free is not None: break
            need(free is not None, 'DISTINCT typed representative/provenance/group capacity mismatch')
            while True:
                row_id = back[free]
                old = row_group.get(row_id)
                matched[free] = row_id; row_group[row_id] = free
                if old is None: break
                del matched[old]; free = old

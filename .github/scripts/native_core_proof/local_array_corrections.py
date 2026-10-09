"""Explicit Local.type legacy projection; never normalize arbitrary strings.

The two pinned writer functions establish the type-name change. The first
observed synthetic local is also checked against its exact source bytecode.
This is a source-backed correction rule, not an independent SootUp inference
oracle for every synthetic local, and never a strict-equivalence claim.
"""
import hashlib
import json
from pathlib import Path
from .legacy_wire import Reader, need
from .source_bindings import load_rule


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def legacy_array_type(actual, reference):
    dimensions = 0
    base = actual
    while base.endswith('[]'):
        dimensions += 1
        base = base[:-2]
    need(2 <= dimensions <= 255 and base and '[' not in base and ']' not in base,
         'Local correction requires a valid multidimensional array')
    need(reference == base + '[]', 'Local difference is not exact legacy array collapse')
    return dimensions


def local_slot(data):
    r = Reader(data)
    node = r.i()
    need(r.byte() == 8, 'Local correction cannot apply to another node tag')

    def text():
        n = r.i()
        need(n >= 0, 'negative canonical text length')
        return r.take(n).decode('utf-8', errors='strict')

    name = text()
    start = r.pos
    value = text()
    return node, name, value, start, r.pos


def compare(actual, reference, method, occurrences):
    a, b = local_slot(actual), local_slot(reference)
    need(a[:2] == b[:2], 'Local identity/name changed')
    if a[2] == b[2]:
        return actual
    dimensions = legacy_array_type(a[2], b[2])
    converted = actual[:a[3]] + reference[b[3]:b[4]] + actual[a[4]:]
    # Method spans have already been independently checked. All remaining
    # bytes, including name, method identity, arity, order and scalars, are exact.
    need(converted == reference, 'Local payload differs beyond the typed array slot')
    occurrences.append({'nodeId': a[0], 'name': a[1], 'method': list(method),
                        'B': a[2], 'C': b[2], 'dimensions': dimensions})
    return converted


class Authority:
    def __init__(self, out, source_rule, producer_spec):
        self.out = out
        self.occurrences = []
        self.complete = False
        rule, self.pins = load_rule(source_rule, producer_spec)
        for arm, line in (
            ('C', '    is ArrayType -> "${graphTypeName(type.baseType)}[]"'),
            ('B', '    is ArrayType -> graphTypeName(type.baseType) + "[]".repeat(type.dimension)'),
        ):
            item = rule['arms'][arm]
            expected = ('internal fun graphTypeName(type: Type): String = when (type) {\n'
                        '    is ClassType -> type.fullyQualifiedName\n' + line + '\n'
                        '    else -> type.toString()\n}')
            need(Path(item['folding']).read_text().count(expected) == 1, 'exact type-name rule')
        self.rule = rule

    def save(self):
        need(all(sha(p) == h for p, h in self.pins.items()), 'Local authority changed during comparison')
        value = {'status': 'PASS_EXPLICIT_SOURCE_LOCAL_ARRAY_CORRECTIONS' if self.complete else
                 'PARTIAL_SOURCE_LOCAL_ARRAY_CORRECTIONS_NOT_CORE_PASS',
                 'strictEquivalence': False, 'syntheticLocalInferenceOracleClaim': False,
                 'scope': 'Local.type only; exact source-backed legacy dimension collapse; other bytes exact',
                 'exceptionCount': len(self.occurrences), 'occurrences': self.occurrences,
                 'inputs': self.pins}
        (self.out / 'local-array-corrections.json').write_text(json.dumps(value, indent=2) + '\n')
        return value

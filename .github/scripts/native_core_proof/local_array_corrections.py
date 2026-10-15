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


def compare(actual, reference, method, occurrences, collapses=True):
    a, b = local_slot(actual), local_slot(reference)
    need(a[:2] == b[:2], 'Local identity/name changed')
    if a[2] == b[2]:
        return actual
    # Two writers with the same renderer (the parent arm of a rebased PR and the accepted
    # baseline) have no licensed Local.type difference: a collapse there is a real difference.
    need(collapses, 'Local difference is not licensed by the formatter pair')
    dimensions = legacy_array_type(a[2], b[2])
    converted = actual[:a[3]] + reference[b[3]:b[4]] + actual[a[4]:]
    # Method spans have already been independently checked. All remaining
    # bytes, including name, method identity, arity, order and scalars, are exact.
    need(converted == reference, 'Local payload differs beyond the typed array slot')
    occurrences.append({'nodeId': a[0], 'name': a[1], 'method': list(method),
                        'B': a[2], 'C': b[2], 'dimensions': dimensions})
    return converted


LEGACY_FLAT = 'legacy-flat'
RANK_PRESERVING = 'rank-preserving'
ARRAY_LINES = {
    LEGACY_FLAT: '    is ArrayType -> "${graphTypeName(type.baseType)}[]"',
    RANK_PRESERVING: '    is ArrayType -> graphTypeName(type.baseType) + "[]".repeat(type.dimension)',
}


class Authority:
    def __init__(self, out, source_rule, producer_spec):
        self.out = out
        self.occurrences = []
        self.complete = False
        rule, self.pins = load_rule(source_rule, producer_spec)
        self.kinds = {}
        for arm in ('C', 'B'):
            item = rule['arms'][arm]
            kind = item.get('formatterKind')
            need(kind in ARRAY_LINES, 'exact type-name rule')
            expected = ('internal fun graphTypeName(type: Type): String = when (type) {\n'
                        '    is ClassType -> type.fullyQualifiedName\n' + ARRAY_LINES[kind] + '\n'
                        '    else -> type.toString()\n}')
            need(Path(item['folding']).read_text().count(expected) == 1, 'exact type-name rule')
            self.kinds[arm] = kind
        need(self.kinds['C'] == LEGACY_FLAT, 'exact type-name rule')
        # The collapse is licensed only from the rank-preserving renderer back to the flat one.
        self.collapses = self.kinds['B'] == RANK_PRESERVING
        self.rule = rule

    def save(self):
        need(all(sha(p) == h for p, h in self.pins.items()), 'Local authority changed during comparison')
        value = {'status': 'PASS_EXPLICIT_SOURCE_LOCAL_ARRAY_CORRECTIONS' if self.complete else
                 'PARTIAL_SOURCE_LOCAL_ARRAY_CORRECTIONS_NOT_CORE_PASS',
                 'strictEquivalence': False, 'syntheticLocalInferenceOracleClaim': False,
                 'scope': 'Local.type only; exact source-backed legacy dimension collapse; other bytes exact',
                 'formatterKinds': self.kinds, 'collapseLicensed': self.collapses,
                 'exceptionCount': len(self.occurrences), 'occurrences': self.occurrences,
                 'inputs': self.pins}
        (self.out / 'local-array-corrections.json').write_text(json.dumps(value, indent=2) + '\n')
        return value

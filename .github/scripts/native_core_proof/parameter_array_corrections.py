"""Parameter.type duplicates a classfile-proved method parameter descriptor."""
import json
from .local_array_corrections import legacy_array_type, sha
from .legacy_wire import Reader, need


def parameter_slot(data):
    r = Reader(data)
    node = r.i()
    need(r.byte() == 10, 'Parameter correction cannot apply to another tag')
    index = r.i()
    start = r.pos
    n = r.i()
    need(n >= 0, 'negative canonical parameter text length')
    value = r.take(n).decode('utf-8', errors='strict')
    return node, index, value, start, r.pos


def compare(actual, reference, actual_method, reference_method, authority, occurrences):
    a, b = parameter_slot(actual), parameter_slot(reference)
    need(a[:2] == b[:2], 'Parameter identity/index changed')
    if a[2] == b[2]:
        return actual
    dimensions = legacy_array_type(a[2], b[2])
    index = a[1]
    need(0 <= index < len(actual_method.parameters) and index < len(reference_method.parameters),
         'Parameter correction index outside descriptor')
    need(a[2] == actual_method.parameters[index] and b[2] == reference_method.parameters[index],
         'Parameter typed column differs from its exact descriptor slot')
    need(authority.mapping.get(actual_method.key) == reference_method.key,
         'Parameter correction requires already proved classfile method correction')
    converted = actual[:a[3]] + reference[b[3]:b[4]] + actual[a[4]:]
    need(converted == reference, 'Parameter payload differs beyond typed array slot')
    occurrences.append({'nodeId':a[0], 'index':index, 'B':a[2], 'C':b[2],
                        'actualMethod':list(actual_method.key),
                        'referenceMethod':list(reference_method.key), 'dimensions':dimensions})
    return converted


def save(out, occurrences, complete):
    value = {'status':'PASS_EXPLICIT_METHOD_BOUND_PARAMETER_ARRAY_CORRECTIONS' if complete else
             'PARTIAL_PARAMETER_ARRAY_CORRECTIONS_NOT_CORE_PASS', 'strictEquivalence':False,
             'scope':'Parameter.type equals the indexed slot of an already classfile-proved method correction',
             'exceptionCount':len(occurrences), 'occurrences':occurrences,
             'methodCorrectionProofSha256':sha(out/'method-corrections.json')}
    (out/'parameter-array-corrections.json').write_text(json.dumps(value, indent=2)+'\n')
    return value

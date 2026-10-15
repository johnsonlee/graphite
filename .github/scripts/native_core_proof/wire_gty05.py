"""Independent GTY04/05 byte reader layered over the frozen GTY01/02/03 oracle.

No Graphite/JVM/query code is imported. Key-only reachability does not prove when
rows were appended, and wire validity does not prove source completeness.
"""
from . import legacy_schema_wire as legacy

Invalid = legacy.Invalid
need = legacy.need
Reader = legacy.Reader
Types = legacy.Types
VerifiedStrings = legacy.VerifiedStrings
verified_strings_export = legacy.verified_strings_export
KINDS = ('class', 'primitive', 'array', 'variable', 'wildcard')
VARIANCES = ('', 'extends', 'super', 'unbounded')
PRIMITIVES = dict(boolean='Z', byte='B', char='C', short='S', int='I', long='J', float='F', double='D', void='V')
# The retained oracle already has a 1MB projection expansion bound. Keep an
# explicit descriptor allocation bound too, rather than accepting hostile counts.
MAX_DESCRIPTOR_BYTES = 1_000_000


class StructuralTypes(Types):
    def __init__(self, rows, fields, methods, classes, version, signatures, field_ids, method_ids):
        super().__init__(rows, fields, methods, classes)
        self.wire_version = version
        self.erased_signatures = signatures
        self.field_key_type_ids = field_ids
        self.method_key_signature_ids = method_ids

    def usage(self):
        def closure(roots):
            seen = set(); pending = list(roots)
            while pending:
                i = pending.pop()
                if i in seen: continue
                seen.add(i); t = self.rows[i]
                pending.extend(t['arguments'])
                pending.extend(x for x in (t['owner'], t['component']) if x is not None)
            return seen
        projections = list(self.fields.values())
        for method in self.methods.values():
            projections.extend(method['parameters']); projections.append(method['result'])
            for formal in method['formals']: projections.extend(formal['bounds'])
        for cls in self.classes.values():
            if cls['super'] is not None: projections.append(cls['super'])
            projections.extend(cls['interfaces'])
            for formal in cls['formals']: projections.extend(formal['bounds'])
        projection_ids = closure(projections)
        key_roots = list(self.field_key_type_ids.values())
        for sig_id in self.method_key_signature_ids.values():
            sig = self.erased_signatures[sig_id]
            key_roots.extend(sig['parameters']); key_roots.append(sig['result'])
        key_ids = closure(key_roots)
        return dict(projectionTypeIds=sorted(projection_ids), erasedKeyTypeIds=sorted(key_ids),
                    keyOnlyTypeIds=sorted(key_ids-projection_ids),
                    unusedTypeIds=sorted(set(range(len(self.rows)))-projection_ids-key_ids),
                    appendedOriginClaim=False)

    def prove_append_only(self, prior):
        """Optional stronger comparison, never inferred solely from row reachability."""
        n = len(prior.rows)
        need(self.rows[:n] == prior.rows and len(self.rows) >= n, 'original type prefix changed')
        need(self.fields == prior.fields and self.methods == prior.methods and self.classes == prior.classes,
             'declaration binding changed')
        appended = set(range(n, len(self.rows)))
        need(appended <= set(self.usage()['keyOnlyTypeIds']), 'appended row is not raw-key-only')
        return dict(status='PASS_PREFIX_AND_BINDINGS_WITH_RAW_KEY_ONLY_APPEND', originalTypes=n,
                    appendedTypeIds=sorted(appended), appendedOriginClaim=True)


def declared_types(data, metadata_sha, expected_version, global_strings=None):
    if expected_version in (1, 2, 3):
        return legacy.declared_types(data, metadata_sha, expected_version, global_strings)
    need(expected_version in (4, 5), 'unsupported requested GTY version')
    r = Reader(data)
    need(r.i() == (0x47545900 | expected_version), 'unexpected GTY version')
    need(r.take(32).hex() == metadata_sha, 'metadata digest binding')
    need(isinstance(global_strings, VerifiedStrings), 'structural GTY requires verified global strings')
    need(r.take(32).hex() == global_strings.serialized_sha, 'serialized strings digest binding')
    r.strings = global_strings.values
    def text(optional=False):
        i = r.i()
        if optional and i == -1: return ''
        need(0 <= i < len(r.strings), 'dictionary ID')
        value = r.strings[i]; value.encode('utf-8', errors='strict')
        return value
    count = r.count(24)
    def ref(optional=False):
        i = r.i()
        if optional and i == -1: return None
        need(0 <= i < count, 'type reference range')
        return i
    def refs(): return [ref() for _ in range(r.count())]
    def scope_target(tag, target):
        need(tag in range(5), 'scope tag')
        need(target == -1 if tag == 0 else target >= 0, 'scope target')
        return tag, target
    rows = []; scopes = []
    for _ in range(count):
        kind, variance, scope, reserved = (r.byte() for _ in range(4))
        need(kind < len(KINDS) and variance < len(VARIANCES), 'type enum tag')
        need(reserved == 0, 'reserved type byte')
        name = text(True); target = r.i(); scopes.append(scope_target(scope, target))
        rows.append(dict(kind=KINDS[kind], name=name, scope='', owner=ref(True), component=ref(True),
                         variance=VARIANCES[variance], arguments=refs()))
    raw_cache = {}
    def raw_descriptor(i, allow_void=False):
        root = i
        if root not in raw_cache:
            dimensions = 0; visited = set()
            while True:
                need(i not in visited, 'erased type cycle'); visited.add(i)
                row = rows[i]
                need(scopes[i] == (0, -1) and row['owner'] is None and not row['arguments'] and not row['variance'],
                     'noncanonical erased type')
                if row['kind'] != 'array': break
                dimensions += 1
                need(not row['name'] and dimensions < 256 and row['component'] is not None, 'erased array shape/depth')
                i = row['component']
            need(row['component'] is None, 'erased leaf component')
            if row['kind'] == 'primitive':
                need(row['name'] in PRIMITIVES, 'erased primitive')
                void = row['name'] == 'void'
                need(not void or dimensions == 0, 'void erased array')
                base = PRIMITIVES[row['name']]
            else:
                name = row['name']; need(row['kind'] == 'class' and name and not name.startswith('.') and
                    not name.endswith('.') and '..' not in name and not any(c in name for c in '/;['), 'erased class name')
                void = False; base = 'L' + name.replace('.', '/') + ';'
            need(dimensions + len(base.encode('utf-8')) <= MAX_DESCRIPTOR_BYTES, 'descriptor bound')
            raw_cache[root] = ('['*dimensions + base, void)
        descriptor, void = raw_cache[root]
        need(not void or allow_void, 'void erased field or parameter')
        return descriptor
    signatures = []; signature_text = []
    if expected_version == 5:
        seen = set()
        for _ in range(r.count(8)):
            params = refs(); result = ref()
            parts = [raw_descriptor(i) for i in params] + [raw_descriptor(result, True)]
            need(2 + sum(len(p.encode('utf-8')) for p in parts) <= MAX_DESCRIPTOR_BYTES, 'method descriptor bound')
            desc = '(' + ''.join(parts[:-1]) + ')' + parts[-1]
            need(desc not in seen, 'duplicate erased method signature'); seen.add(desc)
            signatures.append(dict(parameters=params, result=result)); signature_text.append(desc)
    pending_formals = []
    def formals():
        answer = []
        for _ in range(r.count(16)):
            name = text(); tag = r.byte()
            need(r.take(3) == b'\0\0\0', 'reserved formal bytes')
            scope = scope_target(tag, r.i())
            value = dict(name=name, scope='', bounds=refs())
            pending_formals.append((value, scope)); answer.append(value)
        return answer
    fields = {}; methods = {}; classes = {}; field_ids = {}; method_ids = {}
    for _ in range(r.count(16)):
        owner, name = text(), text()
        if expected_version == 5:
            raw_id = ref(); desc = raw_descriptor(raw_id)
        else: desc = text()
        key = (owner, name, desc)
        legacy.unique_put(fields, key, ref(), 'declared field')
        if expected_version == 5: field_ids[key] = raw_id
    method_order = []
    for _ in range(r.count(24)):
        owner, name = text(), text()
        if expected_version == 5:
            sid = r.i(); need(0 <= sid < len(signature_text), 'erased signature reference'); desc = signature_text[sid]
        else: desc = text()
        key = (owner, name, desc)
        value = dict(parameters=refs(), result=ref(), formals=formals())
        legacy.unique_put(methods, key, value, 'declared method'); method_order.append(key)
        if expected_version == 5: method_ids[key] = sid
    class_order = []
    for _ in range(r.count(16)):
        name = text(); value = dict(formals=formals(), super=ref(True), interfaces=refs())
        legacy.unique_put(classes, name, value, 'declared class'); class_order.append(name)
    r.done()
    def render_scope(scope):
        tag, target = scope
        if tag == 0: return ''
        if tag in (1, 3):
            need(target < len(class_order), 'class scope reference'); value = 'class:' + class_order[target]
        else:
            need(target < len(method_order), 'method scope reference')
            owner, name, desc = method_order[target]; value = 'method:' + owner + '#' + name + desc
        return ('unresolved:' if tag in (3, 4) else '') + value
    for row, scope in zip(rows, scopes): row['scope'] = render_scope(scope)
    for formal, scope in pending_formals: formal['scope'] = render_scope(scope)
    table = StructuralTypes(rows, fields, methods, classes, expected_version, signatures, field_ids, method_ids)
    table.validate()
    return table

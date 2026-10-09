"""Consume raw SootUp Local.type exports; no expected rank comes from saved strings.

The existing owned producer runner binds exporter/JDK/writer/shard inputs. This
module checks exact output hashes and inventories every persisted candidate Local,
including unchanged rank-one strings. It never resolves a conflict by selecting a
candidate-matching type. Legacy correction policy and global claims stay intact.
"""
import hashlib
import json
from pathlib import Path
from .legacy_wire import need
from .local_array_corrections import local_slot


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def pairs(items):
    out = {}
    for key, value in items:
        need(key not in out, 'duplicate raw export JSON key')
        out[key] = value
    return out


def render(raw):
    need(isinstance(raw, dict), 'raw type object')
    kind = raw.get('kind')
    if kind == 'array':
        need(set(raw) == {'kind', 'dimension', 'base'} and type(raw['dimension']) is int and
             1 <= raw['dimension'] <= 255, 'raw array dimension')
        need(raw['base'].get('kind') in ('class', 'primitive'), 'raw array leaf type')
        return render(raw['base']) + '[]' * raw['dimension']
    if kind == 'class':
        need(set(raw) == {'kind', 'name'} and isinstance(raw['name'], str) and raw['name'] and
             not any(c in raw['name'] for c in '/;['), 'raw class name')
        return raw['name']
    if kind == 'primitive':
        need(set(raw) == {'kind', 'name'} and raw['name'] in
             ('boolean', 'byte', 'char', 'short', 'int', 'long', 'float', 'double'), 'raw primitive')
        return raw['name']
    need(kind == 'unsupported' and set(raw) == {'kind', 'runtimeClass'} and
         isinstance(raw['runtimeClass'], str), 'raw unsupported type')
    return None


def load(ref, graph_id, bytecode_sha, class_count):
    path = Path(ref['path'])
    need(path.is_absolute() and not path.is_symlink() and sha(path) == ref['sha256'], 'raw Local export pin')
    rows = [json.loads(line, object_pairs_hook=pairs) for line in path.read_text().splitlines()]
    need(len(rows) >= 2 and rows[0].get('folding') is False and type(rows[0].get('classCount')) is int and rows[0] == {'record': 'header', 'graphId': graph_id,
         'shardBytecodeSha256': bytecode_sha, 'classCount': class_count,
         'scope': 'fixture64-no-fold', 'folding': False}, 'raw Local graph/shard/no-fold identity')
    need(type(rows[-1].get('methodCount')) is int and rows[-1] == {'record': 'complete', 'methodCount': len(rows)-2}, 'complete raw method inventory')
    methods = {}
    for row in rows[1:-1]:
        need(set(row) == {'record', 'method', 'locals', 'typedAllocations', 'statementCount'} and
             row['record'] == 'method' and len(row['method']) == 3 and
             all(isinstance(s, str) and s for s in row['method']), 'raw method identity')
        key = tuple(row['method'])
        need(key not in methods, 'duplicate complete raw method identity')
        need(type(row['statementCount']) is int and row['statementCount'] >= 0 and
             isinstance(row['locals'], dict) and isinstance(row['typedAllocations'], dict), 'raw method shape')
        for name, values in row['locals'].items():
            need(isinstance(name, str) and name and isinstance(values, list) and values, 'all same-name Local occurrences')
            for item in values:
                need(set(item) == {'type', 'origin'} and isinstance(item['origin'], str), 'raw Local occurrence')
                render(item['type'])
        for name, values in row['typedAllocations'].items():
            need(name in row['locals'] and isinstance(values, list) and values, 'typed allocation Local identity')
            for item in values:
                need(set(item) == {'ordinal', 'type'} and type(item['ordinal']) is int and
                     0 <= item['ordinal'] < row['statementCount'] and item['type'].get('kind') == 'class',
                     'typed allocation shape')
                render(item['type'])
        methods[key] = row
    return methods


class Authority:
    def __init__(self, spec, out):
        need(set(spec) == {'graphId', 'shardBytecodeSha256', 'classCount', 'exports'}, 'raw Local binding fields')
        need(set(spec['exports']) == {'C', 'B'}, 'both actual writer raw exports required')
        self.spec = spec
        self.out = Path(out)
        self.methods = {arm: load(ref, spec['graphId'], spec['shardBytecodeSha256'], spec['classCount'])
                        for arm, ref in spec['exports'].items()}
        self.pins = {ref['path']: ref['sha256'] for ref in spec['exports'].values()}
        self.rows = []
        self.seen = set()
        self.duplicates = set()
        self.complete = False

    def observe(self, canonical, method):
        node, name, actual, _, _ = local_slot(canonical)
        key = (tuple(method), name)
        result = {'graphId': self.spec['graphId'], 'nodeId': node, 'method': list(method),
                  'name': name, 'savedType': actual, 'status': 'PENDING', 'arms': {}}
        issues = []
        if key in self.seen:
            self.duplicates.add(key)
            issues.append('duplicate persisted full method/local identity')
        self.seen.add(key)
        unique = {}
        relevant = actual.endswith('[]')
        for arm in ('C', 'B'):
            row = self.methods[arm].get(tuple(method))
            values = row['locals'].get(name) if row else None
            allocations = row['typedAllocations'].get(name, []) if row else []
            result['arms'][arm] = {'occurrences': values, 'typedAllocations': allocations}
            if not values:
                issues.append(arm + ':missing raw method/local')
                continue
            relevant = relevant or any(v['type']['kind'] == 'array' for v in values)
            candidates = {json.dumps(v['type'], sort_keys=True, separators=(',', ':')) for v in values}
            if len(candidates) != 1:
                issues.append(arm + ':conflicting same-name raw types')
            else:
                unique[arm] = json.loads(next(iter(candidates)))
            if allocations:
                issues.append(arm + ':typed allocation requires creation-order proof')
        if len(unique) == 2:
            if unique['C'] != unique['B']:
                issues.append('actual writer raw inference differs')
            expected = render(unique['B'])
            if unique['B']['kind'] == 'array':
                result['rawDimension'] = unique['B']['dimension']
                result['expectedType'] = expected
                if actual != expected:
                    issues.append('persisted array differs from raw Local.type')
            elif actual.endswith('[]'):
                issues.append('persisted array has no raw array authority')
        if not relevant and all(result['arms'][a]['occurrences'] for a in ('C','B')) and key not in self.duplicates:
            result['outsideArrayScopeNotes'] = issues
            issues = []
        result['issues'] = issues
        result['status'] = 'UNPROVED' if issues else ('PASS_ARRAY' if 'rawDimension' in result else 'NON_ARRAY')
        self.rows.append(result)

    def save(self):
        need(all(sha(p) == h for p, h in self.pins.items()), 'raw Local exports changed')
        failures = [r for r in self.rows if r['status'] == 'UNPROVED']
        result = {'status': 'PASS_ALL_PERSISTED_ARRAY_LOCALS_RAW_TYPE' if self.complete and not failures else
                  'PARTIAL_RAW_LOCAL_TYPE_PROOF', 'completeNodeInventory': self.complete,
                  'scope': 'fixture64-no-fold; raw SootUp type result, not independent inference algorithm',
                  'localCount': len(self.rows), 'arrayCount': sum(r['status'] == 'PASS_ARRAY' for r in self.rows),
                  'unprovedCount': len(failures), 'occurrences': self.rows, 'inputs': self.pins,
                  'strictEquivalence': False, 'completeSemanticEquivalence': False, 'performanceAcceptance': False}
        (self.out/'raw-local-type-proof.json').write_text(json.dumps(result, indent=2)+'\n')
        return result

    def finish(self):
        self.complete = True
        result = self.save()
        need(result['unprovedCount'] == 0, 'raw Local conflicts/missing/mismatches retained in raw-local-type-proof.json')
        return result

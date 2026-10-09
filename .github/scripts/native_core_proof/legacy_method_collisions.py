"""Opt-in correction proofs for array overloads collapsed by the old writer.

These helpers do not enable themselves in the core runner. The caller must bind
both actual producer sources before using the ordinal algorithm. Exact source
class declarations, every group member, and all remaining bytes are required.
"""
from collections import Counter, defaultdict
import struct
from . import method_authority
from . import method_classfile
from . import callsite_ordinals
from . import metadata_signature_collisions
from .legacy_wire import need


def project(key):
    return (key[0], key[1], method_classfile.legacy_descriptor(key[2]))


def signature(key):
    # Validate the full descriptor before dropping its return type. The actual
    # writer counts calls by owner, name and parameters, excluding the return.
    method_classfile.legacy_descriptor(key[2])
    return (key[0], key[1], key[2].split(')', 1)[0] + ')')


def enabled(authority):
    need(getattr(authority, 'allow_legacy_collisions', False), 'collision correction requires explicit opt-in')


def declared(authority, key, allow_inherited=False):
    proof = authority.direct(key)
    if proof is None and allow_inherited and getattr(authority,'allow_inherited_class_methods',False):
        proof=authority.inherited_class_method(key)
    need(proof is not None, 'collision group requires exact source declaration')
    return proof


def compare_ordinals(actual, reference, authority, evidence):
    enabled(authority)
    a, b = actual['rows'], reference['rows']
    need(a.keys() == b.keys(), 'ordinal CallSite ID set changed')
    converted = {}
    changed = set()
    groups = defaultdict(list)
    for node, row in a.items():
        old = b[node]
        need(all(row[k] == old[k] for k in ('origin', 'originMember')), 'origin value/member mismatch')
        for role in ('caller', 'callee'):
            authority.pair(row[role], old[role], role, 'ordinal:' + str(node) + ':' + role,
                           row['caller'] if role == 'callee' else None)
        converted[node] = dict(row, caller=old['caller'], callee=old['callee'])
        group = (tuple(row['caller']), signature(old['callee']))
        groups[group].append(node)
        if row['ordinal'] != old['ordinal']:
            changed.add(group)
    for group in sorted(changed):
        members = groups[group]
        # Missing, derived (negative), duplicate and gapped ordinals cannot be
        # explained by this rule. Never omit an inconvenient group member.
        need(all(type(x[n]['ordinal']) is int and x[n]['ordinal'] >= 0
                 for n in members for x in (a, b)), 'collision requires complete nonnegative ordinals')
        members = sorted(members, key=lambda n: b[n]['ordinal'])
        need([b[n]['ordinal'] for n in members] == list(range(len(members))), 'legacy group ordinals not dense and unique')
        targets = {tuple(a[n]['callee']) for n in members}
        need(len({signature(t) for t in targets}) > 1, 'ordinal change without distinct parameter overloads')
        need(all(signature(project(t)) == group[1] for t in targets), 'collision targets do not share legacy signature')
        caller_proof = declared(authority, group[0])
        target_proofs = [{'method': list(t), 'proof': declared(authority, t, allow_inherited=True)} for t in sorted(targets)]
        counts = Counter()
        records = []
        for node in members:
            target = signature(a[node]['callee'])
            need(a[node]['ordinal'] == counts[target], 'new ordinal is not exact rank within recovered overload')
            counts[target] += 1
            converted[node]['ordinal'] = b[node]['ordinal']
            records.append({'nodeId': node, 'B': a[node], 'C': b[node]})
        evidence.append({'actualCaller': group[0], 'legacyCalleeSignature': group[1],
                         'callerDeclaration': caller_proof, 'targetDeclarations': target_proofs,
                         'allMembers': records})
    result = callsite_ordinals.compare(dict(actual, rows=converted), reference)
    result.update(scope='COMPLETE_CALLSITES_WITH_EXPLICIT_DECLARED_OVERLOAD_COUNTER_REPARTITION',
                  strictEquivalence=False, correctedOrdinalGroups=len(changed))
    return result


def table(data, spans):
    need(len(data) >= 8 and data[:4] == struct.pack('>i', 0x47524d03), 'metadata header')
    count = struct.unpack_from('>i', data, 4)[0]
    need(count >= 0, 'negative method count')
    rows = [s for s in spans if s.get('tableRow')]
    need(len(rows) == count and spans[:count] == rows, 'initial method table spans/count')
    end = 8
    for s in rows:
        need(s['start'] == end and s['end'] > end and s['end'] <= len(data), 'method table contiguous spans')
        end = s['end']
    keys = [s['method'].key for s in rows]
    need(len(set(keys)) == len(keys), 'duplicate full method')
    return rows, end


def compare_metadata(actual, reference, a_spans, b_spans, authority, evidence, ordinal_inputs=None, binding_evidence=None, return_evidence=None):
    enabled(authority)
    a, a_end = table(actual, a_spans)
    b, b_end = table(reference, b_spans)
    if return_evidence is not None:
        old_end=a_end;old_count=len(a)
        actual,rows=metadata_signature_collisions.reshape(actual,reference,a,a_end,b,authority,return_evidence)
        new_end=rows[-1]['end'] if rows else 8;delta=new_end-old_end
        a_spans=rows+[dict(s,start=s['start']+delta,end=s['end']+delta) for s in a_spans[old_count:]]
        a,a_end=table(actual,a_spans)
    by_key = {s['method'].key: s for s in b}
    projected = []
    groups = defaultdict(list)
    for i, s in enumerate(a):
        key = s['method'].key
        old = project(key)
        need(old in by_key, 'projected method missing from reference table')
        authority.pair(key, old, 'definition', 'metadata:table:' + str(i))
        # Unchanged methods must serialize identically too. A key comparison
        # alone cannot authorize rewriting arbitrary bytes inside a table row.
        target = by_key[old]
        row = actual[s['start']:s['end']]
        expected = reference[target['start']:target['end']]
        if key == old:
            need(row == expected, 'unchanged method serialization differs')
        groups[old].append(key)
        projected.append(old)
    need(list(dict.fromkeys(projected)) == [s['method'].key for s in b], 'projected method table order/content differs')
    for old, keys in groups.items():
        if len(keys) > 1:
            evidence.append({'C': old, 'B': keys,
                             'declarations': [declared(authority, k) for k in keys]})
    def rest(spans, count, offset):
        return [dict(s, start=s['start']-offset, end=s['end']-offset) for s in spans[count:]]
    suffix = method_authority.compare_spans(actual[a_end:], reference[b_end:],
        rest(a_spans, len(a), a_end), rest(b_spans, len(b), b_end), authority, 'metadata:remaining')
    # A changed stored ordinal necessarily changes the metadata-bound index
    # digest. Re-decode and verify ALL blocks and ALL semantic rows before
    # projecting only that final digest. Merely seeing a hash is not sufficient.
    if suffix != reference[b_end:] and ordinal_inputs is not None:
        need(binding_evidence is not None, 'binding correction evidence required')
        suffix = compare_binding(suffix, reference[b_end:], ordinal_inputs, authority, binding_evidence)
    # Table projection never authorizes changing any other metadata content.
    need(suffix == reference[b_end:], 'metadata differs outside method table/corrected method spans')
    return reference[:b_end] + suffix


class SourceRules:
    """Bind the counter and signature rule to both actual producer manifests."""
    def __init__(self, out):
        from pathlib import Path
        from . import local_array_corrections
        self.types = local_array_corrections.Authority(out)
        counter = '''internal fun callOrdinals(statements: Iterable<Stmt>, declaring: (MethodSignature) -> String): Map<Stmt, Int> {
    val counts = HashMap<String, Int>()
    val ordinals = IdentityHashMap<Stmt, Int>()
    for (stmt in statements) {
        val invoke = invokeExprOf(stmt) ?: continue
        ordinals[stmt] = counts.merge(declaring(invoke.methodSignature), 1, Int::plus)!! - 1
    }
    return ordinals
}'''
        rendering = '''private fun render(signature: MethodSignature): String {
    val parameters = signature.parameterTypes.joinToString(",", transform = ::graphTypeName)
    return "${signature.declClassType.fullyQualifiedName}.${signature.name}($parameters)"
}'''
        adapter = '''        bodyCallOrdinals = preFoldOrdinals(method.signature) ?: callOrdinals(statements) { signature ->
            val resolved = resolveMethodDefiningClass(signature)
            bodyResolvedCallees[signature] = resolved
            renderedDefiningClass.getOrPut(resolved) { renderSignature(resolved) }
        }'''
        for item in self.types.rule['arms'].values():
            folding = Path(item['folding']).read_text()
            need(folding.count(counter) == 1 and folding.count(rendering) == 1,
                 'actual producer counter/rendering differs from correction rule')
            need(Path(item['adapter']).read_text().count(adapter) == 1,
                 'actual adapter counter binding differs from correction rule')
        self.pins = dict(self.types.pins)
        self.pins.update(metadata_signature_collisions.bind_sources(self.types))

    def finish(self):
        from . import local_array_corrections
        need(all(local_array_corrections.sha(p) == h for p,h in self.pins.items()),
             'collision source rule changed during validation')


class Corrections:
    def __init__(self, out):
        self.out = out
        self.source = SourceRules(out)
        self.ordinal_groups = []
        self.metadata_groups = []
        self.return_groups = []
        self.binding_evidence = []
        self.complete = False

    def save(self):
        import json
        import hashlib
        self.source.finish()
        method_file = self.out / 'method-corrections.json'
        result = {
            'status': 'PASS_EXPLICIT_LEGACY_METHOD_COLLISION_CORRECTIONS' if self.complete else
                      'PARTIAL_LEGACY_METHOD_COLLISION_EVIDENCE_NOT_CORE_PASS',
            'strictEquivalence': False,
            'independentInvocationOrderOracleClaim': False,
            'scope': 'Source-bound legacy method projection and complete dense ordinal group repartition; independently verified ordinal-sidecar binding digests; exact source-linked-set and return-omitted map collision recovery; all other metadata bytes remain exact.',
            'ordinalGroupCount': len(self.ordinal_groups),
            'metadataGroupCount': len(self.metadata_groups),
            'ordinalGroups': self.ordinal_groups,
            'metadataGroups': self.metadata_groups,
            'returnOmittedMetadataGroups': self.return_groups,
            'returnOmittedMetadataGroupCount': len(self.return_groups),
            'recoveredMetadataMethods': sum(g['recoveredMethods'] for g in self.return_groups),
            'methodReturnNormalization': False,
            'ordinalBindingCorrections': self.binding_evidence,
            'inputs': self.source.pins,
            'methodCorrectionProofSha256': hashlib.sha256(method_file.read_bytes()).hexdigest(),
        }
        (self.out / 'legacy-method-collisions.json').write_text(json.dumps(result, indent=2) + '\n')
        return result


def compare_binding(actual, reference, ordinal_inputs, authority, evidence):
    need(len(actual) == len(reference) and actual[:-32] == reference[:-32],
         'metadata difference extends beyond final ordinal binding digest')
    need(len(ordinal_inputs) == 2, 'both raw ordinal inputs required')
    proofs=[]
    for data, (raw, members) in zip((actual, reference), ordinal_inputs):
        binding=callsite_ordinals.bind_metadata(data)
        proofs.append(callsite_ordinals.decode(raw,binding,members))
    groups=[]
    semantic=compare_ordinals(*proofs,authority,groups)
    need(semantic['status']=='PASS' and groups, 'binding change needs proven ordinal repartition')
    evidence.append({'scope':'FINAL_GRB02_DIGEST_ONLY_ALL_SIDECAR_BLOCKS_AND_SEMANTIC_ROWS_VERIFIED',
                     'semanticProof':semantic,'ordinalGroups':groups})
    return actual[:-32]+reference[-32:]

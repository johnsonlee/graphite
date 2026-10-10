"""Tiny actual file/manifest bindings; no graph, classfile, JVM or corpus access."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from . import core_semantics as core
from . import legacy_method_collisions as collisions
from . import local_array_corrections as local
from . import source_bindings as binding
from . import synthetic_collisions as synthetic
from .legacy_wire import Invalid

FOLDING = 'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/ConstantFolding.kt'
ADAPTER = 'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/SootUpAdapter.kt'
IDENTITY = 'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/SyntheticIdentity.kt'
ASM = 'frontend/jvm/sootup/src/main/kotlin/sootup/java/bytecode/frontend/conversion/GraphiteAsmClassSource.kt'
SERIALIZER = 'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/NodeSerializer.kt'
BUILDER = 'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/MmapGraphBuilder.kt'
NODE = 'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/core/Node.kt'
COUNTER = '''internal fun callOrdinals(statements: Iterable<Stmt>, declaring: (MethodSignature) -> String): Map<Stmt, Int> {
    val counts = HashMap<String, Int>()
    val ordinals = IdentityHashMap<Stmt, Int>()
    for (stmt in statements) {
        val invoke = invokeExprOf(stmt) ?: continue
        ordinals[stmt] = counts.merge(declaring(invoke.methodSignature), 1, Int::plus)!! - 1
    }
    return ordinals
}'''
RENDERING = '''private fun render(signature: MethodSignature): String {
    val parameters = signature.parameterTypes.joinToString(",", transform = ::graphTypeName)
    return "${signature.declClassType.fullyQualifiedName}.${signature.name}($parameters)"
}'''
COUNTER_BINDING = '''        bodyCallOrdinals = preFoldOrdinals(method.signature) ?: callOrdinals(statements) { signature ->
            val resolved = resolveMethodDefiningClass(signature)
            bodyResolvedCallees[signature] = resolved
            renderedDefiningClass.getOrPut(resolved) { renderSignature(resolved) }
        }'''


class SourceBindingTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.out = self.root / 'out'; self.out.mkdir()
        self.rule = {'schema': 'graphite.local-array-source-rule.v1', 'arms': {}, 'diagnosis': {}}
        self.spec = {'schema': 'graphite.classfile-field-authority.v1', 'arms': {}}
        self.manifests = {}
        for arm, revision, expression in [('C', 'c'*40, '"${graphTypeName(type.baseType)}[]"'),
                                         ('B', 'b'*40, 'graphTypeName(type.baseType) + "[]".repeat(type.dimension)')]:
            source = self.root / (arm + '-source')
            contents = {
                FOLDING: ('internal fun graphTypeName(type: Type): String = when (type) {\n'
                          '    is ClassType -> type.fullyQualifiedName\n'
                          '    is ArrayType -> ' + expression + '\n'
                          '    else -> type.toString()\n}\n' + COUNTER + '\n' + RENDERING),
                ADAPTER: ('resolveMethodsOrEmpty(sootClass).sortedBy { it.signature.toString() }.forEach(action)\n'
                          '.sortedWith(compareBy({ (it.bodySource as? MethodNode)?.name ?: it.name }, { (it.bodySource as? MethodNode)?.desc ?: it.signature.toString() }))\n'
                          'syntheticIdentities.addMethod(method, methodDescriptor.signature, syntheticMethod)\n' + COUNTER_BINDING),
                IDENTITY: 'return members.associate { it.key to it.fingerprint!! }',
                ASM: 'node.methods.map { it as AsmMethodSource }.sortedWith(compareBy({ it.name }, { it.desc }))',
                SERIALIZER: 'for ((member, fingerprint) in metadata.syntheticIdentities.toSortedMap())',
                BUILDER: ('private val methods = linkedSetOf<MethodDescriptor>()\nmethods.add(method)\n'
                          'val methodIndex = LinkedHashMap<String, MethodDescriptor>(methods.size)\n'
                          'methods.forEach { methodIndex[it.signature] = it }'),
                NODE: ('data class MethodDescriptor(\n'
                       'val signature: String get() = "${declaringClass.className}.$name(${parameterTypes.joinToString(",") { it.className }})"'),
            }
            for relative, text in contents.items():
                path = source / relative; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(text)
            self.manifests[arm] = {'revision': revision, 'root': str(source),
                                   'files': {name: binding.sha(source/name) for name in contents}}
            manifest_path = self.root / (arm + '-source.json')
            manifest_path.write_text(json.dumps(self.manifests[arm]))
            self.rule['arms'][arm] = {'revision': revision, 'manifest': str(manifest_path),
                                     'manifestSha256': binding.sha(manifest_path),
                                     'folding': str(source/FOLDING), 'foldingSha256': binding.sha(source/FOLDING),
                                     'adapter': str(source/ADAPTER), 'adapterSha256': binding.sha(source/ADAPTER)}
            self.spec['arms'][arm] = {'fixtureManifest': self.write(arm+'-fixture.json', {
                'writerRevision': revision, 'sourceManifestSha256': binding.sha(manifest_path)})}
        diagnosis = self.root/'historical-first-local-evidence'; diagnosis.write_bytes(b'historical witness only')
        self.rule['diagnosis'][str(diagnosis)] = binding.sha(diagnosis)
        self.rule_ref = self.write('source-rule.json', self.rule)

    def write(self, name, value):
        path = self.root / name; path.write_text(json.dumps(value))
        return {'path': str(path), 'sha256': binding.sha(path)}

    def repin_rule(self):
        self.rule_ref = self.write('source-rule.json', self.rule)

    def replace_source(self, arm, relative, text):
        manifest = self.manifests[arm]
        path = Path(manifest['root']) / relative; path.write_text(text)
        manifest['files'][relative] = binding.sha(path)
        item = self.rule['arms'][arm]
        ref = self.write(arm+'-source.json', manifest); item['manifestSha256'] = ref['sha256']
        for field in ('folding', 'adapter'):
            if Path(item[field]) == path: item[field+'Sha256'] = binding.sha(path)
        self.spec['arms'][arm]['fixtureManifest'] = self.write(arm+'-fixture.json', {
            'writerRevision': item['revision'], 'sourceManifestSha256': ref['sha256']})
        self.repin_rule()

    def test_actual_dynamic_pair_binds_all_inputs_without_historical_revision_constants(self):
        authority = local.Authority(self.out, self.rule_ref, self.spec)
        self.assertEqual(self.rule, authority.rule)
        self.assertEqual({'B', 'C'}, set(authority.rule['arms']))
        for arm in ('C', 'B'):
            ref = self.spec['arms'][arm]['fixtureManifest']
            self.assertEqual(ref['sha256'], authority.pins[ref['path']])
            item = self.rule['arms'][arm]
            for name in ('manifest', 'folding', 'adapter'):
                self.assertEqual(item[name+'Sha256'], authority.pins[item[name]])
        self.assertEqual(self.rule_ref['sha256'], authority.pins[self.rule_ref['path']])
        self.assertEqual(binding.sha(binding.__file__), authority.pins[binding.__file__])
        self.assertTrue(set(self.rule['diagnosis']).issubset(authority.pins))
        self.assertEqual('PARTIAL_SOURCE_LOCAL_ARRAY_CORRECTIONS_NOT_CORE_PASS', authority.save()['status'])
        authority.complete = True
        receipt = authority.save()
        self.assertEqual('PASS_EXPLICIT_SOURCE_LOCAL_ARRAY_CORRECTIONS', receipt['status'])
        self.assertEqual([], receipt['occurrences'])
        self.assertFalse(receipt['strictEquivalence'])
        self.assertFalse(receipt['syntheticLocalInferenceOracleClaim'])

    def test_source_rule_requires_exact_pin(self):
        for ref in (None, {}, {'path': self.rule_ref['path'], 'sha256': '0'*64}):
            with self.subTest(ref=ref), self.assertRaises(Invalid):
                local.Authority(self.out, ref, self.spec)

    def test_swapped_actual_writer_manifests_rejected(self):
        self.spec['arms']['B'], self.spec['arms']['C'] = self.spec['arms']['C'], self.spec['arms']['B']
        with self.assertRaisesRegex(Invalid, 'actual writer revision'):
            local.Authority(self.out, self.rule_ref, self.spec)

    def test_same_revision_other_source_manifest_rejected(self):
        self.spec['arms']['B']['fixtureManifest'] = self.write('B-fixture.json', {
            'writerRevision': 'b'*40, 'sourceManifestSha256': '0'*64})
        with self.assertRaisesRegex(Invalid, 'actual writer source manifest'):
            local.Authority(self.out, self.rule_ref, self.spec)

    def test_rule_and_fixture_agreement_does_not_override_actual_source_revision(self):
        self.rule['arms']['B']['revision'] = 'a'*40
        self.spec['arms']['B']['fixtureManifest'] = self.write('B-fixture.json', {
            'writerRevision': 'a'*40, 'sourceManifestSha256': self.rule['arms']['B']['manifestSha256']})
        self.repin_rule()
        with self.assertRaisesRegex(Invalid, 'actual writer revision'):
            local.Authority(self.out, self.rule_ref, self.spec)

    def test_short_revision_and_incomplete_pair_rejected(self):
        self.rule['arms']['B']['revision'] = 'b'*7; self.repin_rule()
        with self.assertRaisesRegex(Invalid, 'full source rule revision'):
            local.Authority(self.out, self.rule_ref, self.spec)
        del self.rule['arms']['C']; self.repin_rule()
        with self.assertRaisesRegex(Invalid, 'schema and pair'):
            local.Authority(self.out, self.rule_ref, self.spec)

    def test_source_mutation_and_diagnosis_mutation_rejected(self):
        authority = local.Authority(self.out, self.rule_ref, self.spec)
        path = next(iter(self.rule['diagnosis'])); Path(path).write_bytes(b'changed')
        with self.assertRaisesRegex(Invalid, 'authority changed during comparison'):
            authority.save()
        with self.assertRaisesRegex(Invalid, 'pin changed'):
            local.Authority(self.out, self.rule_ref, self.spec)

    def test_repinning_wrong_formatter_does_not_relax_policy(self):
        path = Path(self.rule['arms']['B']['folding'])
        self.replace_source('B', FOLDING, path.read_text().replace('repeat(type.dimension)', 'repeat(1)'))
        with self.assertRaisesRegex(Invalid, 'exact type-name rule'):
            local.Authority(self.out, self.rule_ref, self.spec)

    def test_repinning_duplicate_formatter_does_not_relax_policy(self):
        path = Path(self.rule['arms']['C']['folding'])
        self.replace_source('C', FOLDING, path.read_text()*2)
        with self.assertRaisesRegex(Invalid, 'exact type-name rule'):
            local.Authority(self.out, self.rule_ref, self.spec)

    def test_source_file_outside_writer_manifest_rejected(self):
        self.manifests['B']['files'].pop(FOLDING)
        item = self.rule['arms']['B']; ref = self.write('B-source.json', self.manifests['B'])
        item['manifestSha256'] = ref['sha256']
        self.spec['arms']['B']['fixtureManifest'] = self.write('B-fixture.json', {
            'writerRevision': 'b'*40, 'sourceManifestSha256': ref['sha256']})
        self.repin_rule()
        with self.assertRaisesRegex(Invalid, 'not in actual writer manifest'):
            local.Authority(self.out, self.rule_ref, self.spec)

    def test_core_requires_explicit_rule_before_graph_access(self):
        output = self.root/'never-created'
        with self.assertRaisesRegex(Invalid, 'explicit pinned writer source rule'):
            core.prove(self.root/'missing', self.root/'missing', None, None, output,
                       True, self.spec, method_array_corrections=True, source_local_arrays=True)
        self.assertFalse(output.exists())

    def test_synthetic_rules_reuse_bound_pair_and_preserve_policy(self):
        rules = synthetic.SourceRules(self.rule_ref, self.spec)
        rules.verify()
        self.assertTrue(set(self.rule['diagnosis']).issubset(rules.pins))
        for arm in ('B', 'C'):
            root = Path(self.manifests[arm]['root'])
            for relative in (IDENTITY, ASM, ADAPTER, SERIALIZER):
                self.assertEqual(binding.sha(root/relative), rules.pins[str(root/relative)])
        Path(self.manifests['B']['root'], IDENTITY).write_text('changed')
        with self.assertRaisesRegex(Invalid, 'source changed'):
            rules.verify()

    def test_repinning_distinct_fingerprint_collector_rejected(self):
        path = Path(self.manifests['B']['root'], IDENTITY)
        self.replace_source('B', IDENTITY, path.read_text()+'\nchanged collector')
        with self.assertRaisesRegex(Invalid, 'fingerprint collector changed'):
            synthetic.SourceRules(self.rule_ref, self.spec)

    def test_repinning_wrong_visitation_order_rejected(self):
        path = Path(self.manifests['B']['root'], ASM)
        self.replace_source('B', ASM, path.read_text().replace('it.desc', 'it.name'))
        with self.assertRaisesRegex(Invalid, 'streamed raw descriptor order'):
            synthetic.SourceRules(self.rule_ref, self.spec)

    def test_legacy_collision_constructor_binds_both_actual_writers_and_lifecycle(self):
        correction = collisions.Corrections(self.out, self.rule_ref, self.spec)
        self.assertEqual(self.rule, correction.source.types.rule)
        for arm in ('C', 'B'):
            fixture = self.spec['arms'][arm]['fixtureManifest']
            self.assertEqual(fixture['sha256'], correction.source.pins[fixture['path']])
            for relative in self.manifests[arm]['files']:
                path = str(Path(self.manifests[arm]['root']) / relative)
                # Synthetic identity/serializer rules belong to the separate checker.
                if relative not in (IDENTITY, SERIALIZER):
                    self.assertEqual(binding.sha(path), correction.source.pins[path])
        proof = self.out / 'method-corrections.json'; proof.write_text('{}')
        partial = correction.save()
        self.assertEqual('PARTIAL_LEGACY_METHOD_COLLISION_EVIDENCE_NOT_CORE_PASS', partial['status'])
        self.assertEqual(binding.sha(proof), partial['methodCorrectionProofSha256'])
        correction.complete = True
        complete = correction.save()
        self.assertEqual('PASS_EXPLICIT_LEGACY_METHOD_COLLISION_CORRECTIONS', complete['status'])
        self.assertEqual(0, complete['ordinalGroupCount'])
        self.assertEqual([], complete['ordinalBindingCorrections'])
        self.assertFalse(complete['strictEquivalence'])
        self.assertFalse(complete['independentInvocationOrderOracleClaim'])

    def test_legacy_collision_constructor_requires_pinned_rule(self):
        for ref in (None, {}, dict(self.rule_ref, sha256='0'*64)):
            with self.subTest(ref=ref), self.assertRaises(Invalid):
                collisions.Corrections(self.out, ref, self.spec)

    def test_legacy_collision_constructor_rejects_other_actual_writer(self):
        self.spec['arms']['B']['fixtureManifest'] = self.write('B-fixture.json', {
            'writerRevision': 'b'*40, 'sourceManifestSha256': '0'*64})
        with self.assertRaisesRegex(Invalid, 'actual writer source manifest'):
            collisions.Corrections(self.out, self.rule_ref, self.spec)

    def test_repinning_changed_legacy_counter_is_not_authority(self):
        path = Path(self.rule['arms']['B']['folding'])
        self.replace_source('B', FOLDING, path.read_text().replace('!! - 1', '!! - 2'))
        with self.assertRaisesRegex(Invalid, 'counter/rendering differs'):
            collisions.Corrections(self.out, self.rule_ref, self.spec)

    def test_repinning_changed_legacy_metadata_order_is_not_authority(self):
        path = Path(self.manifests['B']['root']) / BUILDER
        self.replace_source('B', BUILDER, path.read_text().replace('linkedSetOf', 'hashSetOf'))
        with self.assertRaisesRegex(Invalid, 'metadata insertion/iteration rule changed'):
            collisions.Corrections(self.out, self.rule_ref, self.spec)

    def test_legacy_collision_finish_rejects_source_mutation(self):
        correction = collisions.Corrections(self.out, self.rule_ref, self.spec)
        (Path(self.manifests['C']['root']) / NODE).write_text('changed')
        with self.assertRaisesRegex(Invalid, 'source rule changed during validation'):
            correction.save()

    def invoke_corrected_core(self, output, compare):
        # Only graph/classfile I/O is replaced. Every source-bound correction
        # constructor and receipt writer executes against actual tiny manifests.
        with patch.object(core.method_authority, 'Authority') as authority, patch.object(core, '_prove', side_effect=compare):
            entered = authority.return_value.__enter__.return_value
            entered.save.side_effect = lambda: (output/'method-corrections.json').write_text('{}')
            return core.prove(self.root/'missing-B', self.root/'missing-C', None, None, output,
                              True, self.spec, method_array_corrections=True, source_local_arrays=True,
                              parameter_arrays=True, legacy_overload_collisions=True,
                              synthetic_method_keys=True, inherited_fields=True, source_rule=self.rule_ref)

    def test_corrected_core_propagates_actual_source_pair_before_comparison(self):
        output = self.root/'corrected-core'
        def compare(*args):
            local_rules, legacy, synthetic_rules = args[8], args[10], args[11]
            self.assertIsInstance(legacy, collisions.Corrections)
            self.assertEqual(self.rule, local_rules.rule)
            self.assertEqual(self.rule, legacy.source.types.rule)
            self.assertTrue(args[12])  # inherited-field mode remains enabled
            for authority in (local_rules, legacy.source, synthetic_rules.source):
                self.assertEqual(self.rule_ref['sha256'], authority.pins[self.rule_ref['path']])
                for arm in ('C', 'B'):
                    ref = self.spec['arms'][arm]['fixtureManifest']
                    self.assertEqual(ref['sha256'], authority.pins[ref['path']])
            return {'unitTestComparison': 'complete'}
        self.assertEqual({'unitTestComparison': 'complete'}, self.invoke_corrected_core(output, compare))
        receipt = json.loads((output/'legacy-method-collisions.json').read_text())
        self.assertEqual('PASS_EXPLICIT_LEGACY_METHOD_COLLISION_CORRECTIONS', receipt['status'])
        self.assertFalse(receipt['strictEquivalence'])

    def test_corrected_core_failure_retains_partial_correction_receipts(self):
        output = self.root/'failed-core'
        def compare(*args):
            self.assertIsInstance(args[10], collisions.Corrections)
            raise Invalid('unit test semantic mismatch')
        with self.assertRaisesRegex(Invalid, 'unit test semantic mismatch'):
            self.invoke_corrected_core(output, compare)
        receipt = json.loads((output/'legacy-method-collisions.json').read_text())
        self.assertEqual('PARTIAL_LEGACY_METHOD_COLLISION_EVIDENCE_NOT_CORE_PASS', receipt['status'])
        self.assertFalse(receipt['strictEquivalence'])
        self.assertTrue(json.loads((output/'synthetic-method-key-corrections.json').read_text())['status'].startswith('PARTIAL_'))

    def test_corrected_core_rejects_wrong_writer_before_comparison(self):
        self.spec['arms']['B']['fixtureManifest'] = self.write('B-fixture.json', {
            'writerRevision': 'b'*40, 'sourceManifestSha256': '0'*64})
        def compare(*args):
            self.fail('unbound writer must not enter graph comparison')
        output = self.root/'unbound-core'
        with self.assertRaisesRegex(Invalid, 'actual writer source manifest'):
            self.invoke_corrected_core(output, compare)
        self.assertFalse((output/'legacy-method-collisions.json').exists())


if __name__ == '__main__':
    unittest.main()

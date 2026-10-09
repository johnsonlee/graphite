"""Tiny actual file/manifest bindings; no graph, classfile, JVM or corpus access."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from . import core_semantics as core
from . import local_array_corrections as local
from . import source_bindings as binding
from . import synthetic_collisions as synthetic
from .legacy_wire import Invalid

FOLDING = 'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/ConstantFolding.kt'
ADAPTER = 'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/SootUpAdapter.kt'
IDENTITY = 'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/SyntheticIdentity.kt'
ASM = 'frontend/jvm/sootup/src/main/kotlin/sootup/java/bytecode/frontend/conversion/GraphiteAsmClassSource.kt'
SERIALIZER = 'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/NodeSerializer.kt'


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
                          '    else -> type.toString()\n}'),
                ADAPTER: ('resolveMethodsOrEmpty(sootClass).sortedBy { it.signature.toString() }.forEach(action)\n'
                          '.sortedWith(compareBy({ (it.bodySource as? MethodNode)?.name ?: it.name }, { (it.bodySource as? MethodNode)?.desc ?: it.signature.toString() }))\n'
                          'syntheticIdentities.addMethod(method, methodDescriptor.signature, syntheticMethod)'),
                IDENTITY: 'return members.associate { it.key to it.fingerprint!! }',
                ASM: 'node.methods.map { it as AsmMethodSource }.sortedWith(compareBy({ it.name }, { it.desc }))',
                SERIALIZER: 'for ((member, fingerprint) in metadata.syntheticIdentities.toSortedMap())',
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


if __name__ == '__main__':
    unittest.main()

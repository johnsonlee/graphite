"""Tiny raw-byte protocol tests; upstream/child execution is explicitly modeled."""
import copy
import json
from pathlib import Path
import struct
import unittest
from unittest.mock import patch

import derive_jvm_pressure_oracles as p
import test_jvm_pressure_inputs as raw_fixture


def write_json(path, value):
    # Test fixtures intentionally mutate bytes to exercise rejection paths.
    Path(path).write_text(json.dumps(value, indent=2) + '\n')


def add_projection_raw_fixture(raw):
    """Add six real tiny wire nodes and complete Method metadata for both prefixes."""
    raw.strings += [s for s in ('org.jetbrains.kotlin.com.intellij.psi.F', 'org.openxmlformats.F',
        'org.jetbrains.kotlin.backend.jvm.lower.R', 'org.apache.logging.R',
        'org.jetbrains.kotlin.ir.backend.js.lower.M', 'org.apache.logging.M', 'com.Argument') if s not in raw.strings]
    raw.strings.sort()
    raw.records, raw.method = raw_fixture.all_payloads(raw.strings)
    I = raw_fixture.I; sid = lambda s: I(raw.strings.index(s))
    method = lambda owner: sid(owner)+sid('m')+I(1)+sid('java.util.List' if owner.startswith('org.jetbrains.') else 'com.Argument')+sid('void')
    kret, tret = method('org.jetbrains.kotlin.backend.jvm.lower.R'), method('org.apache.logging.R')
    raw.records += [(33, 9, sid('org.jetbrains.kotlin.com.intellij.psi.F')+sid('items')+sid('Example')+b'\x00'),
                    (35, 9, sid('org.openxmlformats.F')+sid('items')+sid('Example')+b'\x01'),
                    (37, 10, I(0)+sid('java.util.List')+kret),
                    (39, 10, I(0)+sid('com.Argument')+tret),
                    (41, 11, kret+b'\x00'), (43, 11, tret+b'\x00')]
    data, offsets, index = raw_fixture.node_files(raw.records)
    ordinal, binding = raw_fixture.ord_fixture.encode([(25, -3)])
    methods = [raw.method, kret, tret, method('org.jetbrains.kotlin.ir.backend.js.lower.M'), method('org.apache.logging.M')]
    metadata = I(0x47524d03)+I(len(methods))+b''.join(methods)+I(0)*7+I(0x47524202)+binding
    for name, data in {'graph.nodedata': data, 'graph.nodeoffsets': offsets, 'graph.typeindex': index,
                       'graph.metadata': metadata, 'graph.callsite-ordinals': ordinal}.items():
        (raw.graph/name).write_bytes(data)
    exported = I(0x47534f01)+I(len(raw.strings))+b''.join(I(len(s.encode()))+s.encode() for s in raw.strings)
    raw.export.write_bytes(exported)
    semantic = raw_fixture.p.wire.strings_export(exported)[1]
    (raw.graph/'graph.strings.identity').write_bytes(bytes.fromhex(semantic))
    receipt = json.loads(raw.receipt.read_text()); receipt.update(outputSha256=raw_fixture.sha(exported),
        entryCount=len(raw.strings), semanticSha256=semantic)
    write_json(raw.receipt, receipt)
    raw.row['stringsExport'] = raw.ref(raw.export); raw.row['stringsReceipt'] = raw.ref(raw.receipt)
    raw.refresh()
    add_projection_table(raw)


def add_projection_table(raw):
    I = raw_fixture.I; sid = lambda value: I(raw.strings.index(value))
    row = lambda kind, name: bytes([kind, 0, 0, 0])+sid(name)+I(-1)*3+I(0)
    table = I(0x47545905)+bytes.fromhex(raw_fixture.sha((raw.graph/'graph.metadata').read_bytes()))+bytes.fromhex(raw_fixture.sha((raw.graph/'graph.strings').read_bytes()))
    table += I(4)+row(0, 'Example')+row(1, 'void')+row(0, 'java.util.List')+row(0, 'com.Argument')
    table += I(2)+I(1)+I(2)+I(1)+I(1)+I(3)+I(1)  # two return-inclusive erased signatures
    owners = ['org.jetbrains.kotlin.com.intellij.psi.F', 'org.openxmlformats.F', 'Example']
    table += I(len(owners))+b''.join(sid(owner)+sid('items')+I(0)+I(2) for owner in owners)
    methods = [('org.jetbrains.kotlin.backend.jvm.lower.R', 0, 2), ('org.apache.logging.R', 1, 3),
               ('org.jetbrains.kotlin.ir.backend.js.lower.M', 0, 2), ('org.apache.logging.M', 1, 3)]
    table += I(len(methods))+b''.join(sid(owner)+sid('m')+I(signature)+I(1)+I(param)+I(1)+I(0) for owner, signature, param in methods)
    table += I(0)
    (raw.graph/'graph.types').write_bytes(table)
    (raw.graph/'forward.properties').write_text('graphite.declaredTypes.sha256='+raw_fixture.sha(table)+'\n')
    raw.refresh()


class DeriveProtocolTests(unittest.TestCase):
    def setUp(self):
        self.raw = raw_fixture.RawInputsTests(); self.raw.setUp(); self.addCleanup(self.raw.doCleanups)
        add_projection_raw_fixture(self.raw)
        self.root = self.raw.root; self.producer = self.root/'producer'; self.producer.mkdir()
        self.proof = self.root/'core-proof'; self.proof.mkdir(); self.out = self.root/'derived'
        self.ref = p.artifacts.ref; self.pins = dict(self.raw.spec['files'])
        self.edge, helper = self.raw.edge_receipt(); self.pins.update(helper)
        # Use actual original IDs, with valid V3 DATAFLOW kind octets.
        Path(self.edge['output']['path']).write_bytes(struct.pack('>iiq', 0x47534501, 44, 2) +
             struct.pack('>iiB', 1, 3, 0) + struct.pack('>iiB', 3, 25, 8))
        self.edge['nodeSlots'] = 44
        self.edge['output'] = self.ref(self.edge['output']['path'])
        self.pins[self.edge['output']['path']] = self.edge['output']['sha256']
        self.home = self.root/'jdk'
        for name in (*p.primitive.JDK_FILES, 'bin/javac'):
            path = self.home/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(('tiny '+name).encode())
            self.pins[str(path)] = p.common.sha(path)
        writer = self.producer/'runtime/writer.jar'; writer.parent.mkdir(); writer.write_bytes(b'tiny Gson dependency identity')
        self.pins[str(writer)] = p.common.sha(writer)
        self.writer = str(writer)
        self.make_upstream()
        self.audit = patch.object(p.core, 'audit', side_effect=lambda root: copy.deepcopy(self.audited))
        self.audit_mock = self.audit.start(); self.addCleanup(self.audit.stop)

    def save(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True); write_json(path, value)
        ref = self.ref(path); self.pins[ref['path']] = ref['sha256']; return ref

    def make_upstream(self, revision='b'*40, role='candidate'):
        # 64 identities sharing this tiny byte fixture test protocol traversal;
        # this is not a real corpus or an independently generated core proof.
        ids = p.model.FIXTURE_GRAPH_IDS; self.pins.update(self.raw.spec['files'])
        self.edge['sourceInputs'] = {name: self.ref(self.raw.graph/name) for name in p.inputs.TOPOLOGY_FILES}
        fixture = {'writerRevision': revision, 'graphs': [dict(id=gid, path=str(self.raw.graph), nodes=len(self.raw.records), callSites=1) for gid in ids],
                   'files': self.raw.spec['files']}
        fixture_ref = self.save(self.producer/'fixture.json', fixture)
        source_ref = self.save(self.producer/'source.json', {'root': str(self.root/'checkout')})
        runtime_ref = self.save(self.producer/'runtime.json', {'scope': 'tiny modeled identity'})
        artifact = {'revision': revision, 'role': role, 'fixtureManifest': fixture_ref,
                    'sourceManifest': source_ref, 'runtimeManifest': runtime_ref}
        artifact_ref = self.save(self.producer/'artifact-audit.json', artifact)
        export_plan = {'java': str(self.home/'bin/java'), 'javac': str(self.home/'bin/javac'), 'writerJar': self.writer}
        export_plan_ref = self.save(self.producer/'core-string-exports/plan.json', export_plan)
        export_pins = {r['path']: r['sha256'] for r in (self.raw.row['stringsExport'], self.raw.row['stringsReceipt'])}
        self.pins.update(export_pins)
        exports = {'schema': 'graphite.native-core-string-export-audit.v1',
                   'status': 'PASS_ALL64_STRING_EXPORTS_INDEPENDENT_RAW_AUDIT', 'revision': revision,
                   'artifactAudit': artifact_ref, 'plan': export_plan_ref, 'pins': export_pins,
                   'graphs': [dict(self.raw.row, id=gid) for gid in ids]}
        export_ref = self.save(self.producer/'core-string-exports/audit.json', exports)
        topology = {'rawEdgeExports': {'actual': self.edge, 'reference': self.edge}}
        top_ref = self.save(self.proof/'topology.json', topology)
        self.core_plan = {'referenceRoot': str(self.producer), 'actualRoot': str(self.producer),
                          'rawEdgeExports': True, 'revisions': {'C': revision, 'B': revision},
                          'fixtureManifests': {'C': fixture_ref, 'B': fixture_ref},
                          'upstream': {'CStrings': export_ref, 'BStrings': export_ref},
                          'graphs': [dict(id=gid, C=str(self.raw.graph), B=str(self.raw.graph)) for gid in ids]}
        self.save(self.proof/'plan.json', self.core_plan)
        self.audited = {'status': p.core.AUDIT_PASS, 'completeCoreTopologyIndexComparison': True,
                        'graphs': [dict(id=gid, topology=top_ref) for gid in ids], 'pins': dict(self.pins)}
        write_json(self.proof/'audit.json', self.audited)

    def fake_phase(self, name, argv, cwd, env, out, timeout):
        # No JVM is executed. Exercise real check_phase on modeled terminal
        # ownership/log/command records, not a boolean PASS shortcut.
        directory = out/name; directory.mkdir()
        for leaf in ('stdout.log', 'stderr.log'): (directory/leaf).write_bytes(b'')
        write_json(directory/'owner.json', {'runnerPid': 1234, 'group': 5678, 'argv': argv})
        record = {'name': name, 'argv': argv, 'cwd': str(cwd), 'timeoutSeconds': timeout,
                  'status': 'PASS', 'errors': [], 'exit': 0,
                  'cleanup': {'group': 5678, 'after': [], 'errors': [], 'exit': 0},
                  'logs': {str(directory/n): p.common.sha(directory/n) for n in ('stdout.log', 'stderr.log')}}
        if name == 'compile-primitives': (out/'classes/JvmPrimitiveFacts.class').write_bytes(b'modeled class bytes')
        else:
            request_path = out/'requests.json'; request = p.common.read(request_path)
            texts = {('float32', '80000000'): '-0.0', ('double', '7ff8000000000042'): 'NaN'}
            facts = {'schema': 'graphite.jvm-primitive-facts.v1', 'scope': 'JDK_PRIMITIVE_CONVERSIONS_NOT_GRAPH_ORACLE',
                     'request': self.ref(request_path), **{k: request[k] for k in ('helperSource', 'helperClass', 'jdkImage')},
                     'graphCount': 64, 'operationCount': len(request['operations']),
                     'results': [dict(index=i, **op, output=texts[(op['operation'], op['input'])])
                                 for i, op in enumerate(request['operations'])],
                     'sourceGraphBytesVerified': False, 'queryImplementationUsed': False}
            write_json(out/'primitive-facts.json', facts)
        write_json(directory/'record.json', record)
        return record

    def execute(self, arm='B'):
        with patch.object(p.producer, 'phase', side_effect=self.fake_phase) as phases:
            result = p.run(self.producer, self.proof, arm, self.out)
        return result, phases

    def test_real_raw_decoders_full34_two_phases_and_independent_replay(self):
        result, phases = self.execute()
        self.assertEqual(p.PASS, result['status'], result['errors']); self.assertEqual(1, self.audit_mock.call_count)
        self.assertEqual(['compile-primitives', 'primitive-facts'], [c.args[0] for c in phases.call_args_list])
        self.assertEqual(34, len(result['universes'])); self.assertEqual(64, len(result['derivation']['graphs']))
        self.assertTrue(all(g['nodes'] == len(self.raw.records) and g['edges'] == 2 and g['dataflowEdges'] == 2 for g in result['derivation']['graphs']))
        universe = p.common.read(result['universes']['slow-dataflowSourceHit']['path'])
        self.assertEqual(64, universe['totalMatches'])
        self.assertEqual((3, 25), (universe['rows'][0]['value']['source'], universe['rows'][0]['value']['target']))
        audit = p.replay(self.out); self.assertEqual(2, self.audit_mock.call_count)
        self.assertTrue(audit['actualPrimitiveExecutionVerified']); self.assertTrue(audit['completeRawDerivationReplayed'])
        self.assertTrue(all(result[k] is audit[k] is False for k in p.FALSE_CLAIMS))

    def test_candidate_requires_declared_projection_bindings_in_each_target_graph(self):
        read_graph = p.inputs.read_graph
        def one_erased_target(spec):
            graph = read_graph(spec)
            if spec['id'] == 'fixture-kotlin-compiler-15': graph['declarations'] = p.inputs.wire.Types()
            return graph
        # Model one target without bindings, while using the real raw reader and
        # collector for every field/method and preserving two nonempty results.
        with patch.object(p.inputs, 'read_graph', side_effect=one_erased_target):
            result, _ = self.execute()
        self.assertEqual('FAIL', result['status'])
        self.assertTrue(any('both target graphs' in error for error in result['errors']))
        self.assertNotIn('universes', result)

    def test_reference_arm_selects_reference_original_edges(self):
        (self.raw.graph/'graph.types').unlink()
        self.pins.pop(str(self.raw.graph/'graph.types'), None)
        (self.raw.graph/'forward.properties').write_bytes(b'graphclass=tiny.CorrectnessFixture\n')
        self.raw.refresh()
        self.make_upstream(p.artifacts.ACCEPTED, 'accepted-baseline')
        top = p.common.read(self.proof/'topology.json'); top['rawEdgeExports']['actual']['mappingApplied'] = True
        ref = self.save(self.proof/'topology.json', top)
        self.audited['pins'].update(self.pins)
        for row in self.audited['graphs']: row['topology'] = ref
        write_json(self.proof/'audit.json', self.audited)
        result, _ = self.execute('C'); self.assertEqual(p.PASS, result['status'], result['errors'])
        self.assertEqual('C', p.common.read(self.out/'plan.json')['arm'])

    def test_gty05_binding_is_consumed_for_feature_universe(self):
        add_projection_table(self.raw); self.make_upstream()
        result, _ = self.execute(); self.assertEqual(p.PASS, result['status'], result['errors'])
        universe = p.common.read(result['universes']['feature-nodes']['path'])
        self.assertTrue(any(row['value']['kind'] == 'FieldNode' and row['value']['rendered'] for row in universe['rows']))

    def test_stored_pass_cannot_bypass_raw_upstream_replay(self):
        self.audit_mock.side_effect = ValueError('raw topology phase failed')
        with self.assertRaisesRegex(ValueError, 'raw topology phase failed'):
            p.bind(self.producer, self.proof, 'B', self.out)
        self.assertFalse(self.out.exists())
        self.audit_mock.side_effect = lambda root: dict(self.audited, completeCoreTopologyIndexComparison=False)
        with self.assertRaisesRegex(ValueError, 'differs from actual owned'):
            p.bind(self.producer, self.proof, 'B', self.out)

    def test_wrong_arm_root_missing_export_mode_and_output_inside_graph_rejected(self):
        with self.assertRaisesRegex(ValueError, 'selected producer'):
            p.bind(self.root/'unrelated', self.proof, 'B', self.out)
        with self.assertRaisesRegex(ValueError, 'outside source'):
            p.bind(self.producer, self.proof, 'B', self.producer/'graphs/oracles')
        self.core_plan['rawEdgeExports'] = False; self.save(self.proof/'plan.json', self.core_plan)
        self.audited['pins'].update(self.pins); write_json(self.proof/'audit.json', self.audited)
        with self.assertRaisesRegex(ValueError, 'actual raw edge export mode'):
            p.bind(self.producer, self.proof, 'B', self.out)

    def test_primitive_phase_failure_retains_logs_and_prevents_derivation(self):
        def fail(name, argv, cwd, env, out, timeout):
            value = self.fake_phase(name, argv, cwd, env, out, timeout)
            if name == 'primitive-facts':
                value.update(status='FAIL', errors=['modeled conversion failure'], exit=1)
                write_json(out/name/'record.json', value)
                raise ValueError('modeled conversion failure')
            return value
        with patch.object(p.producer, 'phase', side_effect=fail): result = p.run(self.producer, self.proof, 'B', self.out)
        self.assertEqual('FAIL', result['status']); self.assertIn('modeled conversion failure', result['errors'][0])
        self.assertFalse((self.out/'universes').exists()); self.assertTrue((self.out/'primitive-facts/record.json').is_file())
        self.assertEqual([], result['unissued']); self.assertEqual('PASS', result['finalIdentity'])

    def test_mapped_edge_or_changed_raw_input_is_not_accepted(self):
        top = p.common.read(self.proof/'topology.json'); top['rawEdgeExports']['actual']['mappingApplied'] = True
        ref = self.save(self.proof/'topology.json', top); self.audited['pins'].update(self.pins)
        for row in self.audited['graphs']: row['topology'] = ref
        write_json(self.proof/'audit.json', self.audited)
        result, _ = self.execute(); self.assertEqual('FAIL', result['status'])
        self.assertIn('original-ID', result['errors'][0]); self.assertNotIn('universes', result)

    def test_replay_rejects_changed_result_and_missing_cleanup_without_running_java(self):
        result, _ = self.execute(); self.assertEqual(p.PASS, result['status'], result['errors'])
        path = Path(result['universes']['slow-valueHit']['path']); raw = path.read_bytes()
        changed = json.loads(raw); changed['totalMatches'] += 1; write_json(path, changed)
        with self.assertRaisesRegex(ValueError, 'independently derived universe'): p.replay(self.out)
        path.write_bytes(raw)
        phase_path = self.out/'primitive-facts/record.json'; phase = p.common.read(phase_path)
        phase['cleanup']['after'] = [5678]; write_json(phase_path, phase)
        with self.assertRaisesRegex(ValueError, 'terminal cleanup'): p.replay(self.out)

    def test_same_raw_source_rechecked_on_second_pass_and_final_identity(self):
        def mutate(name, argv, cwd, env, out, timeout):
            result = self.fake_phase(name, argv, cwd, env, out, timeout)
            if name == 'primitive-facts': (self.raw.graph/'graph.nodedata').write_bytes(b'changed between passes')
            return result
        with patch.object(p.producer, 'phase', side_effect=mutate): result = p.run(self.producer, self.proof, 'B', self.out)
        self.assertEqual('FAIL', result['status']); self.assertEqual('FAIL', result['finalIdentity'])
        self.assertIn('same-read raw input changed', result['errors'][0])

    def test_command_shape_bounded_jdk_no_query_or_per_graph_jvms(self):
        plan = p.bind(self.producer, self.proof, 'B', self.out); commands = p.commands(plan)
        self.assertEqual(2, len(commands)); self.assertIn('-J-Xmx4g', commands[0][1]); self.assertIn('-Xmx4g', commands[1][1])
        self.assertIn('-XX:ActiveProcessorCount=4', commands[1][1]); self.assertIn('JvmPrimitiveFacts', commands[1][1])
        self.assertEqual(str(self.home), p.environment(plan)['JAVA_HOME'])
        self.assertNotIn('JAVA_TOOL_OPTIONS', p.environment(plan))


if __name__ == '__main__': unittest.main()

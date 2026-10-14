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
        self.audit = patch.object(p.core, 'audit_consumable', side_effect=lambda root: copy.deepcopy(self.audited))
        self.audit_mock = self.audit.start(); self.addCleanup(self.audit.stop)
        full = patch.object(p.core, 'audit', side_effect=AssertionError('consumer must use bound shard audit API'))
        full.start(); self.addCleanup(full.stop)

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

    def test_fresh_owned_derivation_once_and_private_lifetime_ends(self):
        plan = p.bind(self.producer, self.proof, 'B', self.out)
        checks = []; saved = []
        def consume(evidence, check):
            self.assertEqual(35, len(evidence['universes']))
            self.assertEqual({'record', 'universes'}, set(evidence))
            self.assertEqual(p.artifacts.ref(self.out/'record.json'), evidence['record'])
            saved.append(check); check()
            return 'consumed'
        with patch.object(p, 'bind_raw', return_value=plan), \
             patch.object(p, '_fresh_input_closure', return_value=lambda: checks.append(True)), \
             patch.object(p.producer, 'phase', side_effect=self.fake_phase), \
             patch.object(p, '_derive_and_consume', wraps=p._derive_and_consume) as actual, \
             patch.object(p, 'replay', side_effect=AssertionError('fresh path cannot replay')):
            self.assertEqual('consumed', p._run_and_consume_raw(self.producer, self.proof, 'B', self.out, consume))
            self.assertEqual(1, actual.call_count)
        self.assertEqual(2, len(checks))
        with self.assertRaisesRegex(ValueError, 'expired'): saved[0]()

    def test_fresh_failure_never_consumes_and_consumer_failure_expires(self):
        plan = p.bind(self.producer, self.proof, 'B', self.out)
        with patch.object(p, 'bind_raw', return_value=plan), \
             patch.object(p, '_fresh_input_closure', return_value=lambda: None), \
             patch.object(p.producer, 'phase', side_effect=ValueError('owned primitive failed')):
            with self.assertRaisesRegex(ValueError, 'successful fresh'):
                p._run_and_consume_raw(self.producer, self.proof, 'B', self.out,
                                      lambda *args: self.fail('failed derivation consumed'))
        self.assertEqual('FAIL', p.common.read(self.out/'record.json')['status'])

    def test_fresh_new_output_and_consumer_failure_invalidate(self):
        plan = p.bind(self.producer, self.proof, 'B', self.out); saved = []
        def consume(evidence, check):
            saved.append(check); (self.out/'new-empty-directory').mkdir(); check()
        with patch.object(p, 'bind_raw', return_value=plan), \
             patch.object(p, '_fresh_input_closure', return_value=lambda: None), \
             patch.object(p.producer, 'phase', side_effect=self.fake_phase):
            with self.assertRaisesRegex(ValueError, 'output closure changed'):
                p._run_and_consume_raw(self.producer, self.proof, 'B', self.out, consume)
        with self.assertRaisesRegex(ValueError, 'expired'): saved[0]()

    def test_real_raw_decoders_full34_two_phases_and_digest_bound_replay(self):
        result, phases = self.execute()
        self.assertEqual(p.PASS, result['status'], result['errors']); self.assertEqual(1, self.audit_mock.call_count)
        self.assertEqual(['compile-primitives', 'primitive-facts'], [c.args[0] for c in phases.call_args_list])
        self.assertEqual(35, len(result['universes'])); self.assertEqual(64, len(result['derivation']['graphs']))
        self.assertTrue(all(g['nodes'] == len(self.raw.records) and g['edges'] == 2 and g['dataflowEdges'] == 2 for g in result['derivation']['graphs']))
        universe = p.common.read(result['universes']['slow-dataflowSourceHit']['path'])
        self.assertEqual(64, universe['totalMatches'])
        self.assertEqual((3, 25), (universe['rows'][0]['value']['source'], universe['rows'][0]['value']['target']))
        # The replay binds the recorded outputs by digest; it derives nothing a second time.
        with patch.object(p, '_derive_and_consume', side_effect=AssertionError('replay must not derive again')), \
             patch.object(p, 'collect_requests', side_effect=AssertionError('replay must not recollect requests')):
            audit = p.replay(self.out)
        # The core-root binding (not the CI path) still re-audits its upstream on rebind.
        self.assertEqual(2, self.audit_mock.call_count)
        self.assertTrue(audit['actualPrimitiveExecutionVerified']); self.assertTrue(audit['rawDerivationBoundByDigest'])
        self.assertTrue(all(result[k] is audit[k] is False for k in p.FALSE_CLAIMS))

    def test_owned_derivation_streams_every_case_without_snapshot(self):
        actual=p.derivation.Collector._consume_cases;seen=[];collectors=[]
        def consume(collector,emit):
            self.assertEqual(64,len(collector.seen));names=[];collectors.append(collector)
            def sink(name,universe):names.append(name);return emit(name,universe)
            result=actual(collector,sink);seen.append(names)
            self.assertTrue(collector.failed);self.assertEqual({},collector.distinct)
            self.assertFalse(hasattr(collector,'rows'));self.assertIsNone(collector.primitive_facts)
            return result
        with patch.object(p.derivation.Collector,'_consume_cases',new=consume), \
             patch.object(p.derivation.Collector,'_consume_finish',side_effect=AssertionError('owned derive must stream')), \
             patch.object(p.derivation.Collector,'finish',side_effect=AssertionError('owned derive must not copy snapshot')):
            result,_=self.execute()
            self.assertEqual(p.PASS,result['status'],result['errors'])
            audit=p.replay(self.out)
        names=[case['id'] for case in p.model.cases()]
        self.assertEqual([names],seen);self.assertEqual(p.PASS,audit['status'])
        plan=p.common.read(self.out/'plan.json');public=p.derive(plan,p.consume_facts(plan))
        self.assertEqual(p.common.typed(public.pop('universes')),p.common.typed({name:p.common.read(ref['path']) for name,ref in result['universes'].items()}))
        self.assertEqual(p.common.typed(public),p.common.typed(result['derivation']))

    def test_stream_save_failure_retains_partial_outputs_fail_receipt_and_cleanup(self):
        real=p.common.save;collectors=[];actual=p.derivation.Collector._consume_cases
        def consume(collector,emit):collectors.append(collector);return actual(collector,emit)
        def save(path,value):
            if Path(path).parent.name=='universes' and len(list(Path(path).parent.iterdir()))==2:
                raise OSError('injected universe write failure')
            return real(path,value)
        with patch.object(p.common,'save',new=save),patch.object(p.derivation.Collector,'_consume_cases',new=consume):
            result,_=self.execute()
        self.assertEqual('FAIL',result['status']);self.assertEqual('PASS',result['finalIdentity'])
        self.assertIn('injected universe write failure',result['errors'][0]);self.assertEqual(2,len(result['universes']))
        self.assertEqual('FAIL',p.common.read(self.out/'record.json')['status']);self.assertNotIn('derivation',result)
        self.assertTrue(collectors[0].failed);self.assertEqual({},collectors[0].counts);self.assertEqual({},collectors[0].distinct)
        with self.assertRaisesRegex(ValueError,'complete scoped'):p.replay(self.out)

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

    def test_flat_parent_actual_arm_without_table_needs_no_declared_bindings(self):
        # A rebased PR's parent arm (main) is derived as the actual arm of its core pair, but its
        # graphs carry no declared-type table: the candidate's binding requirement is not its.
        self.assertTrue(p.common.read(self.out/'plan.json')['declaredBindings']) if (self.out/'plan.json').exists() else None
        (self.raw.graph/'graph.types').unlink()
        self.pins.pop(str(self.raw.graph/'graph.types'), None)
        (self.raw.graph/'forward.properties').write_bytes(b'graphclass=tiny.CorrectnessFixture\n')
        self.raw.refresh()
        self.make_upstream('a'*40, 'parent')
        self.audited['pins'].update(self.pins)
        write_json(self.proof/'audit.json', self.audited)
        result, _ = self.execute(); self.assertEqual(p.PASS, result['status'], result['errors'])
        plan = p.common.read(self.out/'plan.json')
        self.assertEqual(('B', False, 'parent'), (plan['arm'], plan['declaredBindings'], plan['role']))

    def test_candidate_plan_records_the_declared_binding_requirement(self):
        result, _ = self.execute(); self.assertEqual(p.PASS, result['status'], result['errors'])
        self.assertTrue(p.common.read(self.out/'plan.json')['declaredBindings'])

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
        with self.assertRaisesRegex(ValueError, 'derived universe bytes'): p.replay(self.out)
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


class TypedJsonEqualityTests(unittest.TestCase):
    @staticmethod
    def outcome(compare, left, right):
        try: return ('value', compare(left, right))
        except Exception as error: return ('error', type(error), str(error))

    def assert_original(self, left, right):
        original=lambda a,b:p.common.typed(a)==p.common.typed(b)
        self.assertEqual(self.outcome(original,left,right), self.outcome(p._same_typed_json,left,right))

    def test_scalar_types_signed_zero_maps_lists_and_nested_mismatches(self):
        values=[None,False,True,0,1,-1,0.0,-0.0,1.0,-1.0,1e300,'','0','雪',[],{},
                [False,0,0.0], [0,False,0.0], {'b':[True,{'z':-0.0}],'a':None},
                {'a':None,'b':[True,{'z':0.0}]}, {'a':[1]}, {'a':[1.0]}, {'a':[True]},
                {'a':[],'b':{}}, {'a':{}}, [1,2], [2,1], [[1]], [1]]
        for left in values:
            for right in values:
                with self.subTest(left=left,right=right):self.assert_original(left,right)
        self.assertTrue(p._same_typed_json(+0.0,-0.0))
        self.assertFalse(p._same_typed_json(False,0));self.assertFalse(p._same_typed_json(1,1.0))

    def test_generated_json_matches_original_without_allocated_typed_representation(self):
        import random
        rng=random.Random(260)
        scalars=[None,True,False,0,1,-1,0.0,-0.0,3.25,'雪','']
        def value(depth):
            if depth==0 or rng.randrange(3)==0:return rng.choice(scalars)
            if rng.randrange(2):return [value(depth-1) for _ in range(rng.randrange(4))]
            return {key:value(depth-1) for key in rng.sample(['a','b','雪','0'],rng.randrange(5))}
        for _ in range(100):
            left=value(4);right=value(4);self.assert_original(left,right);self.assert_original(left,copy.deepcopy(left))
        with patch.object(p.common,'typed',side_effect=AssertionError('must not build tagged tree')):
            self.assertTrue(p._same_typed_json({'a':[[1,True,-0.0]]},{'a':[[1,True,+0.0]]}))

    def test_deep_valid_json_accepted_by_original_comparison_still_passes(self):
        for depth in (500,600):
            for shape in ('list','object','mixed'):
                a,b='1','2'
                for level in range(depth):
                    if shape=='list' or (shape=='mixed' and level%2):a,b='['+a+']','['+b+']'
                    else:a,b='{"a":'+a+'}','{"a":'+b+'}'
                left=p.common.parse(a);same=p.common.parse(a);different=p.common.parse(b)
                with self.subTest(depth=depth,shape=shape):
                    self.assertTrue(p.common.typed(left)==p.common.typed(same))
                    self.assertFalse(p.common.typed(left)==p.common.typed(different))
                    self.assertTrue(p._same_typed_json(left,same))
                    self.assertFalse(p._same_typed_json(left,different))

    def test_invalid_tails_validate_in_original_left_then_right_order(self):
        invalid=[float('nan'),float('inf'),-float('inf'),(),object(),[1,{'z':float('inf')}]]
        for bad in invalid:
            for left,right in (([0,bad],[1,None]),([0,None],[1,bad]),(bad,{}),({},bad)):
                with self.subTest(left=left,right=right):self.assert_original(left,right)
        self.assert_original([float('inf')], [object()])
        self.assert_original([object()], [float('inf')])
        self.assert_original({'z':float('inf'),'a':object()}, {})
        # Raw dict keys follow the original sorted-key operation, not new rules.
        self.assert_original({1:'x'}, {True:'x'})
        self.assert_original({1:'x','a':'y'}, {})
        self.assert_original({}, {1:'x','a':'y'})
        class ListSubclass(list): pass
        self.assert_original(ListSubclass([1]),[1])

    def test_original_json_reader_still_rejects_duplicate_keys_and_nonfinite(self):
        import tempfile
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'invalid.json'
            for raw in ('{"a":1,"a":2}', '{"a":NaN}', '{"a":Infinity}', '{"a":1e9999}'):
                path.write_text(raw)
                def old():return p.common.typed(p.common.read(path))==p.common.typed({})
                def new():return p._same_typed_json(p.common.read(path),{})
                def result(call):
                    try:return ('value',call())
                    except Exception as error:return ('error',type(error),str(error))
                self.assertEqual(result(old),result(new));self.assertEqual('error',result(new)[0])


class FreshInputClosureTests(unittest.TestCase):
    def test_actual_producer_closure_rejects_new_inputs_configs_and_exports(self):
        from test_audit_native_pressure_artifacts import ProducerReplayContextTests
        for mutation in ('source', 'config', 'graph-directory', 'runtime-file', 'raw-directory', 'string-symlink', 'writer'):
            with self.subTest(mutation=mutation):
                fixture = ProducerReplayContextTests(); fixture.setUp()
                try:
                    f = fixture.f
                    report = p.artifacts.audit(*fixture.args)
                    path = f.out/'artifact-audit.json'; write_json(path, report)
                    raw = f.out/'jvm-raw-edges'; raw.mkdir()
                    strings = f.out/'core-string-exports'; strings.mkdir()
                    plan = {'producerRoot': str(f.out), 'rawExportRoot': str(raw),
                            'pins': {**report['pins'], str(path): p.common.sha(path)}}
                    check = p._fresh_input_closure(plan); check()
                    if mutation == 'source': fixture.source.return_value = {'unexpected': 'source'}
                    elif mutation == 'config':
                        packet = p.common.read(report['producerPacket']['path'])
                        packet['configurationBefore']['unexpected'] = 'config'
                        # Captured expected configs stay immutable; change the actual environment view.
                        with patch.object(p.artifacts, 'configs', return_value=packet['configurationBefore']):
                            with self.assertRaisesRegex(ValueError, 'configuration'): check()
                        continue
                    elif mutation == 'graph-directory': (f.out/'graphs/extra').mkdir()
                    elif mutation == 'runtime-file': (f.out/'runtime/extra').write_bytes(b'new')
                    elif mutation == 'raw-directory': (raw/'extra').mkdir()
                    elif mutation == 'string-symlink': (strings/'link').symlink_to(raw)
                    elif mutation == 'writer':
                        (f.checkout/'frontend/jvm/webgraph/build/libs/extra-jmh.jar').write_bytes(b'extra')
                    with self.assertRaises((ValueError, OSError)): check()
                finally: fixture.doCleanups()


if __name__ == '__main__': unittest.main()

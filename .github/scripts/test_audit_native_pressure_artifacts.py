"""Small artifact fixtures exercise independent audit failures, never performance."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import audit_native_pressure_artifacts as auditor
import test_produce_native_pressure_artifacts as fixtures


class ArtifactAuditTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        producer_fixture = fixtures.ArtifactCompositionTests()
        self.packet, calls, self.out = producer_fixture.execute(self.root)
        self.checkout = self.root / 'checkout'
        self.inputs_path = self.root / 'inputs.json'
        self.runtime = auditor.common.read(self.out / 'runtime-manifest.json')
        self.fixture = auditor.common.read(self.out / 'fixture-manifest.json')
        self.tools = {key: self.runtime['toolchainIdentity'][key] for key in ('java', 'cargo', 'rustc')}
        provenance = self.out / 'graphs/fixture-provenance.tsv'
        rows = [line.split('\t') for line in provenance.read_text().splitlines()[1:]]
        for row, record in zip(rows, self.fixture['graphs']):
            for i in (5, 12, 14, 15, 17):
                row[i] = auditor.common.digest_bytes((row[0] + str(i)).encode())
            record['workloadIdentitySha256'] = row[15]
            record['querySemanticSha256'] = row[12]
        provenance.write_text(auditor.PROVENANCE_HEADER + '\n' + '\n'.join('\t'.join(r) for r in rows) + '\n')
        (self.out / 'graphs/graphs.tsv').write_text('\n'.join(
            '\t'.join([r[0], r[18], r[7], r[8], 'x', r[15]]) for r in rows) + '\n')
        reference = self.root / 'reference.tsv'
        reference.write_bytes(provenance.read_bytes())
        self.inputs = {'schema': 'graphite.fixture64-source-inputs.v1', 'jars': self.fixture['inputJars'],
                       'referenceProvenance': auditor.ref(reference)}
        self.write(self.inputs_path, self.inputs)
        pins = auditor.common.read(self.out / 'inputs-before.json')
        pins.update({str(self.inputs_path): auditor.common.sha(self.inputs_path),
                     str(reference): auditor.common.sha(reference),
                     **{j['path']: j['sha256'] for j in self.inputs['jars']}})
        self.write(self.out / 'inputs-before.json', pins)
        self.fixture['files'] = auditor.inventory(self.out / 'graphs')
        self.write(self.out / 'fixture-manifest.json', self.fixture)
        self.packet['graphs'] = self.fixture['graphs']
        self.packet['phases'] = []
        self.packet['phaseReceipts'] = {}
        for i, (name, argv, _) in enumerate(calls):
            directory = self.out / name
            (directory / 'stderr.log').write_text('')
            owner = {'group': 10000 + i, 'runnerPid': 9000, 'argv': argv}
            self.write(directory / 'owner.json', owner)
            phase = {'name': name, 'status': 'PASS', 'errors': [], 'argv': argv,
                     'cwd': str(self.checkout), 'timeoutSeconds': 30, 'exit': 0,
                     'cleanup': {'group': owner['group'], 'after': [], 'errors': [], 'exit': 0},
                     'logs': {str(directory / n): auditor.common.sha(directory / n)
                              for n in ('stdout.log', 'stderr.log')}}
            self.write(directory / 'record.json', phase)
            self.packet['phases'].append(phase)
            self.packet['phaseReceipts'][str(directory / 'record.json')] = auditor.common.sha(directory / 'record.json')
        self.write(self.out / 'packet.json', self.packet)
        self.source = auditor.common.read(self.out / 'source-manifest.json')['files']

    def write(self, path, value):
        path.write_text(json.dumps(value))

    def run_audit(self, require_construction=False):
        with patch.object(auditor, 'source_inventory', return_value=self.source), \
             patch('subprocess.Popen', side_effect=AssertionError('no build or server allowed')):
            return auditor.audit(self.out / 'packet.json', self.checkout, 'b' * 40,
                                 'candidate', self.inputs_path, self.tools, require_construction)

    def test_complete_raw_artifacts_are_verified_without_claiming_query_or_semantics(self):
        result = self.run_audit()
        self.assertEqual(auditor.STATUS, result['status'])
        self.assertEqual(len(result['graphs']), 64)
        self.assertEqual(result['phaseCount'], 7)
        self.assertNotIn('constructionResources', result)
        self.assertEqual(result['binarySha256'], auditor.common.sha(self.out / 'runtime/graphite'))
        for key in ('readinessVerified', 'queryCorrectnessVerified', 'completeSemanticEquivalence',
                    'performanceAcceptance', 'acceptanceEligible'):
            self.assertIs(result[key], False)

    def construction_capture(self):
        phase=self.packet['phases'][5];directory=self.out/'prepare-real64'
        self.assertEqual('prepare-real64',phase['name'])
        time_path=directory/'time-v.log';time_path.write_text(fixtures.ConstructionResourceTests.RAW)
        phase['launchArgv']=['/usr/bin/time','-v','-o',str(time_path),'--',*phase['argv']]
        phase['environmentOverrides']={'LC_ALL':'C'}
        phase['constructionResources']=fixtures.producer.construction_resources(time_path,0)
        phase['logs'][str(time_path)]=auditor.common.sha(time_path)
        owner=auditor.common.read(directory/'owner.json');owner['argv']=phase['launchArgv'];self.write(directory/'owner.json',owner)
        self.write(directory/'record.json',phase)
        self.packet['phaseReceipts'][str(directory/'record.json')]=auditor.common.sha(directory/'record.json')
        version=self.out/'construction-time-version.txt';version.write_text('time (GNU Time) test-only\n')
        self.packet['performanceMeasurement']=True
        self.packet['constructionMeasurement']={'tool':'/usr/bin/time','versionFile':str(version),'scope':auditor.CONSTRUCTION_SCOPE,'performanceAcceptance':False}
        pins=auditor.common.read(self.out/'inputs-before.json');pins.update({'/usr/bin/time':auditor.common.sha('/usr/bin/time'),str(version):auditor.common.sha(version)})
        self.write(self.out/'inputs-before.json',pins);self.write(self.out/'packet.json',self.packet)

    def test_explicit_required_capture_rejects_old_unmeasured_packet(self):
        with self.assertRaisesRegex(ValueError,'required real64 construction resources missing'):self.run_audit(True)

    def test_complete_captured_packet_retains_resources_without_acceptance(self):
        self.construction_capture();result=self.run_audit(True)
        self.assertEqual(63.5,result['constructionResources']['totalCpuSeconds'])
        self.assertEqual(2097152,result['constructionResources']['peakRssBytes'])
        self.assertEqual(64,len(result['graphs']));self.assertFalse(result['acceptanceEligible']);self.assertFalse(result['performanceAcceptance'])
        self.assertIn(str(self.out/'prepare-real64/time-v.log'),result['pins'])
        self.assertIn('/usr/bin/time',result['pins'])

    def test_capture_tool_version_and_measurement_flag_cannot_be_forged(self):
        self.construction_capture();self.packet['performanceMeasurement']=False;self.write(self.out/'packet.json',self.packet)
        with self.assertRaisesRegex(ValueError,'explicit construction measurement scope'):self.run_audit(True)
        self.packet['performanceMeasurement']=True;self.write(self.out/'packet.json',self.packet)
        (self.out/'construction-time-version.txt').write_text('time other implementation')
        with self.assertRaises(ValueError):self.run_audit(True)

    def test_runtime_corruption_despite_producer_pass_is_rejected(self):
        (self.out / 'runtime/graphite').write_text('different runtime')
        with self.assertRaisesRegex(ValueError, 'actual runtime bytes'):
            self.run_audit()

    def test_original_output_cannot_change_after_copy(self):
        (self.out / 'native-target/x86_64-unknown-linux-gnu/release/graphite').write_text('changed')
        with self.assertRaisesRegex(ValueError, 'pinned input changed'):
            self.run_audit()

    def test_added_graph_file_is_not_hidden_by_recorded_inventory(self):
        (self.out / 'graphs/unrecorded').write_text('extra')
        with self.assertRaisesRegex(ValueError, 'closed complete fixture output'):
            self.run_audit()

    def test_forged_writer_revision_rejected(self):
        self.fixture['writerRevision'] = auditor.ACCEPTED
        self.write(self.out / 'fixture-manifest.json', self.fixture)
        with self.assertRaisesRegex(ValueError, 'own revision writer'):
            self.run_audit()

    def test_graph_cannot_point_to_another_arms_output(self):
        self.fixture['graphs'][0]['path'] = str(self.root / 'foreign-graph')
        self.write(self.out / 'fixture-manifest.json', self.fixture)
        with self.assertRaisesRegex(ValueError, 'own graph/provenance identity'):
            self.run_audit()

    def test_changed_input_jar_rejected(self):
        Path(self.inputs['jars'][0]['path']).write_text('another version')
        with self.assertRaisesRegex(ValueError, 'pinned input changed'):
            self.run_audit()

    def mutate_phase(self, name, change):
        path = self.out / name / 'record.json'
        record = auditor.common.read(path)
        change(record)
        self.write(path, record)
        self.packet['phaseReceipts'][str(path)] = auditor.common.sha(path)
        self.packet['phases'] = [record if p['name'] == name else p for p in self.packet['phases']]
        self.write(self.out / 'packet.json', self.packet)

    def test_missing_verifier_phase_rejected(self):
        del self.packet['phaseReceipts'][str(self.out / 'verify-real64/record.json')]
        self.write(self.out / 'packet.json', self.packet)
        with self.assertRaisesRegex(ValueError, 'exact seven'):
            self.run_audit()

    def test_heap_increase_in_actual_command_rejected(self):
        self.mutate_phase('prepare-real64', lambda r: r['argv'].__setitem__(1, '-Xmx16g'))
        with self.assertRaisesRegex(ValueError, 'actual command'):
            self.run_audit()

    def test_live_child_or_failure_cannot_be_success(self):
        self.mutate_phase('build-native', lambda r: r['cleanup'].__setitem__('after', [999]))
        with self.assertRaisesRegex(ValueError, 'owned terminal cleanup'):
            self.run_audit()

    def test_raw_log_mutation_rejected(self):
        (self.out / 'build-native/stdout.log').write_text('different log')
        with self.assertRaisesRegex(ValueError, 'raw phase logs closed'):
            self.run_audit()

    def test_wrong_requested_toolchain_rejected(self):
        self.tools = dict(self.tools, rustc='/wrong/rustc')
        with self.assertRaisesRegex(ValueError, 'requested direct toolchain'):
            self.run_audit()

    def test_producer_pass_requires_all_final_checks(self):
        self.packet['finalIdentity']['fixtures'] = 'NOT_REACHED'
        self.write(self.out / 'packet.json', self.packet)
        with self.assertRaisesRegex(ValueError, 'producer final identities'):
            self.run_audit()

    def test_metadata_mutation_during_audit_is_detected(self):
        original = auditor.verify_pins
        def changed(pins):
            if str(self.out / 'packet.json') in pins:
                self.packet['role'] = 'accepted-baseline'
                self.write(self.out / 'packet.json', self.packet)
            return original(pins)
        with patch.object(auditor, 'verify_pins', side_effect=changed):
            with self.assertRaisesRegex(ValueError, 'pinned input changed'):
                self.run_audit()


class ConstructionAuditTests(unittest.TestCase):
    def phase(self):
        case=fixtures.ConstructionResourceTests();self.addCleanup(case.doCleanups)
        return case.resource_phase(case.RAW)

    def test_independent_raw_resource_receipt_verified(self):
        record,root,argv,_=self.phase()
        self.assertEqual(record,auditor.check_phase(root/'prepare-real64/record.json',argv,root,True))
        with self.assertRaises(ValueError):auditor.check_phase(root/'prepare-real64/record.json',argv,root,False)

    def test_changed_summary_or_child_command_rejected(self):
        for kind in ('cpu','command','locale','rss'):
            with self.subTest(kind=kind):
                record,root,argv,_=self.phase();path=root/'prepare-real64/record.json'
                if kind=='cpu':record['constructionResources']['totalCpuSeconds']+=1
                elif kind=='command':record['launchArgv'][-1]='other-corpus'
                elif kind=='locale':record['environmentOverrides']={'LC_ALL':'foreign'}
                else:record['constructionResources']['peakRssBytes']*=1024
                path.write_text(json.dumps(record))
                with self.assertRaises(ValueError):auditor.check_phase(path,argv,root,True)

    def test_missing_nonzero_or_changed_raw_time_rejected(self):
        for kind in ('missing','exit','changed'):
            with self.subTest(kind=kind):
                _,root,argv,_=self.phase();path=root/'prepare-real64/time-v.log'
                if kind=='missing':path.unlink()
                else:path.write_text(path.read_text().replace('Exit status: 0','Exit status: 2') if kind=='exit' else path.read_text().replace('61.20','71.20'))
                with self.assertRaises((ValueError,OSError)):auditor.check_phase(root/'prepare-real64/record.json',argv,root,True)


if __name__ == '__main__':
    unittest.main()

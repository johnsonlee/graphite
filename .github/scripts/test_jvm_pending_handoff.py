"""Small original-byte transport/authority contracts; never performance evidence."""
import copy
import json
from pathlib import Path
import shutil
import struct
import tempfile
import unittest
from unittest.mock import patch
import native_pressure_checkpoint as checkpoint
import test_native_pressure_checkpoint as transport_fixtures
import jvm_pressure_inputs as inputs
import prepare_jvm_pressure_plan as prepare
import test_prepare_jvm_pressure_plan as preparation_fixtures
import export_jvm_raw_edges as raw_export


class RawExportAudit(unittest.TestCase):
    """Real export run/audit and owned phase replay over tiny persisted64 bytes.

    Only the upstream producer binder and Java execution are substituted; the
    exporter audit, phase/owner/log checks and all original-edge reads are real.
    """
    def setUp(self):
        folder=tempfile.TemporaryDirectory();self.addCleanup(folder.cleanup)
        self.root=Path(folder.name).resolve();self.out=self.root/'exports'
        self.producer=self.root/'producer';self.producer.mkdir()
        pins={str(raw_export.SOURCE):raw_export.common.sha(raw_export.SOURCE)}
        for name in ('java','javac','writer.jar','authority.json'):
            p=self.root/name;p.write_text('{}');pins[str(p)]=raw_export.common.sha(p)
        authority=raw_export.artifacts.ref(self.root/'authority.json')
        graphs=[]
        for gid in raw_export.model.FIXTURE_GRAPH_IDS:
            graph=self.producer/'graphs'/gid;graph.mkdir(parents=True)
            sources={}
            for name in inputs.TOPOLOGY_FILES:
                p=graph/name;p.write_bytes(b'tiny bound original topology')
                sources[name]=raw_export.artifacts.ref(p);pins[str(p)]=sources[name]['sha256']
            graphs.append({'id':gid,'root':str(graph),'sourceInputs':sources})
        self.plan={'schema':'graphite.jvm-raw-edge-export-plan.v1','producerRoot':str(self.producer),
            'output':str(self.out),'graphs':graphs,'pins':pins,'revision':'b'*40,'role':'candidate',
            'helperSource':raw_export.artifacts.ref(raw_export.SOURCE),
            'java':str(self.root/'java'),'javac':str(self.root/'javac'),'writerJar':str(self.root/'writer.jar'),
            **{key:authority for key in ('sourceManifest','runtimeManifest','fixtureManifest','artifactAudit','stringAudit')}}
        binder=patch.object(raw_export,'bind',side_effect=lambda *args:copy.deepcopy(self.plan))
        binder.start();self.addCleanup(binder.stop)
        child=patch.object(raw_export.producer,'phase',side_effect=self.phase)
        child.start();self.addCleanup(child.stop)
        self.record=raw_export.run(self.producer,self.out)
        self.assertEqual(raw_export.PASS,self.record['status'],self.record['errors'])

    def phase(self,name,argv,cwd,env,out,timeout):
        directory=out/name;directory.mkdir()
        for log in ('stdout.log','stderr.log'):(directory/log).write_text('tiny owned phase\n')
        raw_export.common.save(directory/'owner.json',{'argv':argv,'group':12345,'runnerPid':1234})
        phase={'name':name,'argv':argv,'cwd':str(cwd),'timeoutSeconds':timeout,'status':'PASS','errors':[],
            'exit':0,'cleanup':{'group':12345,'after':[],'errors':[],'exit':0},
            'logs':{str(directory/log):raw_export.common.sha(directory/log) for log in ('stdout.log','stderr.log')}}
        raw_export.common.save(directory/'record.json',phase)
        clazz=out/'classes/ExportJvmRawEdges.class'
        if name=='compile-raw-edges':clazz.write_bytes(b'tiny compiled helper')
        else:
            for row in self.plan['graphs']:
                graph_out=out/'exports'/row['id'];graph_out.mkdir()
                edges=graph_out/'edges.bin';edges.write_bytes(struct.pack('>iiqiiB',0x47534501,2,1,0,1,3))
                receipt={'schema':'graphite.raw-labeled-edge-export.v1','format':'GSE01',
                    'graphRoot':row['root'],'sourceInputs':row['sourceInputs'],'output':raw_export.artifacts.ref(edges),
                    'nodeSlots':2,'labeledEdges':1,'helperSource':self.plan['helperSource'],
                    'helperClass':raw_export.artifacts.ref(clazz),'mappingApplied':False,
                    **{key:True for key in ('allSequentialOffsetsChecked','allRandomAccessOffsetsChecked',
                        'allLabelPrefixOffsetsChecked','rawNodeIdsPreserved','fullScanConsumed')}}
                raw_export.common.save(graph_out/'receipt.json',receipt)

    def test_actual_export_audit_replays_phase_owners_and_original_bytes(self):
        audit=raw_export.audit(self.out)
        self.assertEqual(raw_export.AUDIT,audit['status'])
        self.assertEqual(list(raw_export.model.FIXTURE_GRAPH_IDS),[row['id'] for row in audit['graphs']])
        for row in audit['graphs']:
            raw=Path(row['edges']['output']['path']).read_bytes()
            self.assertEqual([(0,1,3)],list(inputs.edge_records(raw,range(2),2,1)))
        self.assertFalse(audit['performanceAcceptance']);self.assertFalse(audit['completeCoreTopologyIndexComparison'])

    def test_actual_phase_audit_rejects_changed_owner_and_logs(self):
        phase=self.out/'export-raw-edges'
        for leaf,value in (('owner.json','{}'),('stdout.log','changed raw output')):
            with self.subTest(leaf=leaf):
                path=phase/leaf;original=path.read_bytes();path.write_text(value)
                try:
                    with self.assertRaises((ValueError,KeyError)):raw_export.audit(self.out)
                finally:path.write_bytes(original)
        (phase/'owner.json').unlink()
        with self.assertRaises(FileNotFoundError):raw_export.audit(self.out)

    def test_actual_phase_audit_rejects_rebound_failed_receipt(self):
        path=self.out/'export-raw-edges/record.json';phase=raw_export.common.read(path)
        phase.update(status='FAIL',exit=1,errors=['owned export failed'])
        path.write_text(json.dumps(phase)+'\n')
        self.record['phases'][1]=raw_export.artifacts.ref(path)
        (self.out/'record.json').write_text(json.dumps(self.record)+'\n')
        with self.assertRaisesRegex(ValueError,'raw phase failed'):raw_export.audit(self.out)


class PendingHandoff(unittest.TestCase):
    def test_real_pending_checkpoint_restore_then_original_edge_consumption(self):
        f=transport_fixtures.CheckpointTests();f.setUp();self.addCleanup(f.doCleanups)
        _,_,_,prep_args,prep=f.sharded_core_fixture()
        producer=checkpoint.read(f.directory/'manifest.json')
        destination=f.workspace/'pressure-checkpoints/native-checkpoint-core-prepare-B-1234-2'
        shutil.move(prep_args.directory,destination)
        refs=[f.directory/'manifest.json',destination/'manifest.json']
        manifests=[]
        original=struct.pack('>iiq',0x47534501,44,2)+struct.pack('>iiB',1,3,0)+struct.pack('>iiB',3,25,8)
        for arm in ('C','B'):
            root=f.root/arm
            for name in ('jvm-raw-derivation','jvm-query-correctness','jvm-raw-edges'):
                f.json(root/name/'plan.json',{'pins':{str(f.writer):checkpoint.sha(f.writer)},'coreComparabilityPending':True})
            edge=root/'jvm-raw-edges/edges.bin';edge.write_bytes(original)
            f.json(root/'jvm-raw-derivation/record.json',{'edge':{'path':str(edge),'sha256':checkpoint.sha(edge)}})
            args=f.args('jvm-pending',arm,f.workspace/f'pressure-checkpoints/native-checkpoint-jvm-pending-{arm}-1234-2',refs)
            manifest=checkpoint.seal(args);manifests.append(manifest)
            self.assertEqual([('core-prepare','B'),('producer','producer')],[(r['stage'],r['arm']) for r in manifest['upstream']])
            self.assertFalse(any(r['stage']=='core' for r in manifest['upstream']))
            f.remove_payload(manifest)
            with self.assertRaisesRegex(ValueError,'exact upstream stages'):
                checkpoint.restore(f.args('jvm-pending',arm,args.directory,refs[:1]))
        f.remove_payload(prep);f.remove_payload(producer)
        result=checkpoint.restore_consumer_chain(f.args('jvm-pending','B',f.workspace/'pressure-checkpoints'))
        self.assertFalse(result['proofAuthority']);self.assertFalse(result['performanceAcceptance'])
        for arm in ('C','B'):
            record=checkpoint.read(f.root/arm/'jvm-raw-derivation/record.json');edge=Path(record['edge']['path'])
            self.assertEqual(record['edge']['sha256'],checkpoint.sha(edge));self.assertEqual(original,edge.read_bytes())
            self.assertEqual([(1,3,0),(3,25,8)],list(inputs.edge_records(edge.read_bytes(),range(44),44,2)))
        for manifest in manifests:checkpoint.check_files(manifest)
        # The full core checkpoint remains self-contained and repeats preparation
        # payload. Its explicit final join must preserve identical bytes.
        f.json(f.root/'B/core-equivalence/audit.json',{'tiny':'complete-proof-transport-only'})
        core_args=f.args('core','B',f.workspace/'pressure-checkpoints/native-checkpoint-core-B-1234-2',[refs[0]])
        complete=checkpoint.seal(core_args)
        self.assertTrue(set(complete['payload']) & set(prep['files']))
        for name in set().union(*(set(m['payload']) for m in [producer,prep,*manifests,complete])):Path(name).unlink(missing_ok=True)
        final_args=f.args('jvm-final','B',f.workspace/'pressure-checkpoints')
        result=checkpoint.restore_consumer_chain(final_args)
        self.assertFalse(result['performanceAcceptance'])
        for manifest in [producer,prep,*manifests,complete]:checkpoint.check_files(manifest)
        core_path=Path(core_args.directory)/'manifest.json';original_manifest=core_path.read_bytes()
        shared=next(iter(set(complete['payload']) & set(prep['files'])))
        for key,value in (('sha256','0'*64),('mode',0o700)):
            bad=copy.deepcopy(complete);bad['files'][shared][key]=value;checkpoint.save(core_path,bad)
            with self.assertRaisesRegex(ValueError,'conflicting file attributes'):checkpoint.restore_consumer_chain(final_args)
            core_path.write_bytes(original_manifest)
        # Cross-run transport must reject before recreating removed pending data.
        f.remove_payload(manifests[-1]);bad=f.args('jvm-pending','B',f.workspace/'pressure-checkpoints');bad.run_attempt='3'
        with self.assertRaises((ValueError,FileNotFoundError)):checkpoint.restore_consumer_chain(bad)

    def test_pending_plan_preserves_all35_oracles_and_cannot_promote_fixture_proof(self):
        f=preparation_fixtures.JvmPreparationTests();f.setUp();self.addCleanup(f.doCleanups)
        for directory,audit in f.observed.items():
            query=prepare.common.read(audit['plan']['path']);raw_path=Path(query['rawDerivationRoot'])/'plan.json'
            raw=prepare.common.read(raw_path);raw.pop('coreRoot');raw.update(coreComparabilityPending=True,rawExportRoot=str(raw_path.parent/'exports'))
            ref=f.write(raw_path.parent/'exports/audit.json',{'status':'modeled independently owned raw boundary'})
            raw['upstreamRawExportAudit']=ref;raw_ref=f.write(raw_path,raw)
            audit['pins'][raw_ref['path']]=raw_ref['sha256'];audit['pins'][ref['path']]=ref['sha256'];f.write(Path(directory)/'audit.json',audit)
        packet=prepare.assemble(f.roots,'a'*40,'b'*40,measurement_only=True)
        self.assertEqual([],f.comparison_calls);self.assertEqual(prepare.PENDING_STATUS,packet['status'])
        self.assertNotIn('correctedComparisons',packet)
        out=f.root/'pending';out.mkdir();ref=f.write(out/'packet.json',packet)
        plan=prepare.bind_plan(packet,ref,out,prepare.CATALOG)
        self.assertEqual(35,len(plan['cases']));self.assertEqual(list('CABBAC'),[c['arm'] for c in plan['cells']])
        self.assertEqual({'concurrency':4,'warmupPerCase':2,'measuredPerCase':20},plan['schedule'])
        self.assertTrue(plan['comparabilityPending']);self.assertEqual(ref,plan['pendingJvmComparabilityAuthority'])
        prepare.common.validate_plan(plan)
        self.assertEqual([],f.comparison_calls)
        for change in (lambda p:p.pop('comparabilityPending'),lambda p:p.update(comparabilityPending=False),
                       lambda p:p.update(jvmProducerAuthority=p['pendingJvmComparabilityAuthority'])):
            changed=copy.deepcopy(plan);change(changed)
            with self.assertRaises(ValueError):prepare.common.validate_plan(changed)


class RawDerivationHandoff(unittest.TestCase):
    def test_pending_raw_bytes_derive_and_replay_every35_case_without_core(self):
        import derive_jvm_pressure_oracles as derive
        import export_jvm_raw_edges as edges
        import test_derive_jvm_pressure_oracles as fixtures
        f=fixtures.DeriveProtocolTests();f.setUp();self.addCleanup(f.doCleanups)
        root=f.root/'independent-raw-edges';root.mkdir()
        artifact=derive.common.read(f.producer/'artifact-audit.json')
        plan={'producerRoot':str(f.producer),'java':str(f.home/'bin/java'),'javac':str(f.home/'bin/javac'),'writerJar':f.writer}
        plan_ref=f.save(root/'plan.json',plan)
        receipt=f.save(root/'receipt.json',f.edge)
        observed={'schema':'graphite.jvm-raw-edge-export-audit.v1','status':edges.AUDIT,
            'completeCoreTopologyIndexComparison':False,'plan':plan_ref,'pins':dict(f.pins),
            'artifactAudit':f.ref(f.producer/'artifact-audit.json'),'stringAudit':f.ref(f.producer/'core-string-exports/audit.json'),
            'graphs':[{'id':gid,'edges':copy.deepcopy(f.edge),'receipt':receipt} for gid in derive.model.FIXTURE_GRAPH_IDS]}
        f.save(root/'audit.json',observed)
        with patch.object(edges,'audit',return_value=observed),patch.object(derive.core,'audit_consumable',side_effect=AssertionError('no core calculation or replay before measurement')),patch.object(derive.producer,'phase',side_effect=f.fake_phase):
            result=derive.run(f.producer,None,'B',f.out,raw_export_root=root)
            self.assertEqual(derive.PASS,result['status'],result['errors'])
            replay=derive.replay(f.out)
        self.assertEqual(35,len(replay['universes']));self.assertTrue(replay['completeRawDerivationReplayed'])
        stored=derive.common.read(f.out/'plan.json');self.assertNotIn('coreRoot',stored);self.assertTrue(stored['coreComparabilityPending'])
        self.assertFalse(result['performanceAcceptance']);self.assertFalse(replay['oracleAuthorityVerified'])
        self.assertEqual(64,len(result['derivation']['graphs']))
        self.assertTrue(all(value>0 for counts in result['derivation']['fullProjectionBindings'].values() for value in counts.values()))


class ExecutionJoinHandoff(unittest.TestCase):
    def test_actual_jvm_execution_record_can_join_and_missing_core_stays_failed(self):
        import tempfile
        from types import SimpleNamespace
        import run_prepared_native_pressure as runner
        import jvm_pressure_join as join
        for mode in ('pass','missing-core','regression','artifacts-pass','artifacts-missing-core','artifacts-regression'):
            with self.subTest(mode=mode),tempfile.TemporaryDirectory() as folder:
                artifacts_mode=mode.startswith('artifacts-'); outcome=mode.removeprefix('artifacts-')
                root=Path(folder).resolve();prep=root/'prep';prep.mkdir();out=root/'cells'
                def put(path,value):
                    runner.pressure.save(path,value)
                    return {'path':str(path),'sha256':runner.pressure.sha(path)}
                pending={'arms':{'A':{'revision':'a'*40},'B':{'revision':'b'*40}},'pins':{}}
                pending_ref=put(root/'pending.json',pending)
                corrected={**pending,'correctedComparisons':{}}
                corrected_ref=put(root/'corrected.json',corrected)
                plan={'engine':'jvm','operation':'query','comparabilityPending':True,'pendingJvmComparabilityAuthority':pending_ref,
                      'arms':pending['arms'],'pins':{pending_ref['path']:pending_ref['sha256']},
                      'cells':[{'id':str(i),'arm':arm} for i,arm in enumerate('CABBAC')]}
                plan_ref=put(prep/'plan.json',plan)
                put(prep/'preparation-status.json',{'schema':'graphite.jvm-pressure.preparation.v1','engine':'jvm','operation':'query',
                    'status':'PLAN_READY_NOT_MEASURED','planSha256':plan_ref['sha256'],'comparabilityPending':True})
                events=[]
                def execute_cell(cell,directory):
                    events.append(('execute',cell));directory=Path(directory);directory.mkdir()
                    result={'status':'PASS','id':cell};ref=put(directory/'result.json',result)
                    return result,{'status':'PASS','resultSha256':ref['sha256']}
                def audit_cell(directory):
                    events.append(('audit',Path(directory).name));return runner.pressure.read(Path(directory)/'audit.json')
                context=SimpleNamespace(plan=plan,run_and_audit_cell=execute_cell,audit_cell=audit_cell,_check_unchanged=lambda:None)
                def compare(command,**kwargs):
                    if '--progress' in command:raise RuntimeError('diagnostic deliberately unavailable; all six cells must still run')
                    final='--comparability-receipt' in command;prefix=Path(command[4]);passed=final and outcome!='regression'
                    put(Path(str(prefix)+'-status.json'),{'schema':'graphite.multigraph-pressure.comparison.v1','engine':'jvm','operation':'query',
                        'planSha256':plan_ref['sha256'],'passed':passed,'status':('PASS' if passed else 'FAIL') if final else runner.PENDING,
                        'comparabilityPending':not final,'coreComparabilityVerified':final})
                    Path(str(prefix)+'-report.md').write_text('retained comparison')
                    return SimpleNamespace(returncode=0 if passed else 1,stdout='',stderr='')
                def verify(packet):
                    if outcome=='missing-core':raise ValueError('missing full core')
                    return corrected,pending
                def complete(root_path):
                    self.assertEqual(root/'artifacts', root_path)
                    return verify(pending)
                context._complete_jvm_core=complete
                with patch.object(runner.pressure._VerifiedPressureExecution,'open',return_value=context),patch.object(runner.subprocess,'run',side_effect=compare),patch.object(join,'verify',side_effect=AssertionError('combined path cannot re-audit pending packet') if artifacts_mode else verify),patch.object(join,'metadata',return_value=[]):
                    measured=runner.execute(prep,out,root/'measurement',engine='jvm')
                    self.assertEqual('graphite.jvm-pressure.execution.v1',measured['schema']);self.assertEqual(runner.PENDING,measured['status']);self.assertFalse(measured['performanceAcceptance'])
                    original={str(p):p.read_bytes() for p in out.rglob('*') if p.is_file()}
                    result=runner.finalize(prep,out,root/'final',None if artifacts_mode else Path(corrected_ref['path']),
                                           engine='jvm',artifacts_root=root/'artifacts' if artifacts_mode else None)
                self.assertEqual(outcome=='pass',result['performanceAcceptance'],result['errors'])
                if artifacts_mode:
                    self.assertFalse(context._valid)
                    if outcome != 'missing-core':
                        self.assertEqual(corrected, runner.pressure.read(root/'final-corrected-producer.json'))
                self.assertEqual('graphite.jvm-pressure.finalization.v1',result['schema'])
                self.assertEqual([('execute',str(i)) for i in range(6)]+[('audit',str(i)) for i in range(6)],events)
                self.assertEqual(original,{str(p):p.read_bytes() for p in out.rglob('*') if p.is_file()})


if __name__=='__main__':unittest.main()

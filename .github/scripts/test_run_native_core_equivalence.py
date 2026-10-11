"""Tiny protocol/lifecycle fixtures; never invoke a JVM or real graph reader."""
import copy
import csv
import io
import json
from pathlib import Path
import tempfile
import struct
import unittest
from unittest.mock import patch
import run_native_core_equivalence as r
import test_bind_native_core_formatter as formatter_fixtures
from native_core_proof import check_graph as check
from native_core_proof import declarations_test


class PropertiesTests(unittest.TestCase):
    def test_only_valid_encoding_statistics_may_differ(self):
        a=b'graphclass=it.unimi.BVGraph\nnodes=4\nbitsforblocks=7\nresidualavggap=1.2\ngraphite.declaredTypes.sha256=abc\n'
        b=b'graphclass=it.unimi.BVGraph\nnodes=4\nbitsforblocks=8\nresidualavggap=1.5\n'
        value=check.properties(a,b,'abc')
        self.assertEqual('PASS_REQUIRES_COMPLETE_TOPOLOGY',value['status'])
        self.assertEqual(['bitsforblocks','residualavggap'],value['compressionExceptions'])
        self.assertEqual({},value['semanticDifferences'])
        bad=check.properties(a.replace(b'nodes=4',b'nodes=5'),b,'abc')
        self.assertEqual({'nodes':{'actual':'5','reference':'4'}},bad['semanticDifferences'])
        self.assertEqual('FAIL',bad['status'])
        for before,after in ((b'1.2',b'NaN'),(b'1.2',b'-1'),(b'bitsforblocks=7',b'bitsforblocks=-1')):
            with self.assertRaises(ValueError):check.properties(a.replace(before,after),b,'abc')

    def hosted_gap_properties(self):
        # Exact differing statistics retained by hosted 5a2 android-08 failure.
        values={
            'bitsforresiduals':('2181785','2181788'),
            'residualavgloggap':('3.094255886783927','3.0942584635959753'),
            'residualexpstats':('75970,89836,94030,47550,27853,15300,10151,7023,5021,3345,2563,1950,1329,873,785,506,458,443,162,35',
                                '75970,89836,94030,47550,27853,15299,10152,7023,5021,3345,2563,1950,1329,873,785,506,458,443,162,35'),
            'successoravgloggap':('3.120472257978582','3.1204745680797217'),
            'successorexpstats':('80490,101233,105551,54623,31039,16958,11546,8151,6007,4307,2909,2076,1435,904,775,530,509,418,162,31',
                                 '80490,101233,105551,54623,31039,16957,11547,8151,6007,4307,2909,2076,1435,904,775,530,509,418,162,31')}
        prefix='graphclass=it.unimi.dsi.webgraph.BVGraph\nnodes=4\narcs=5\n'
        actual=(prefix+''.join(k+'='+v[0]+'\n' for k,v in values.items())+'graphite.declaredTypes.sha256=abc\n').encode()
        reference=(prefix+''.join(k+'='+v[1]+'\n' for k,v in values.items())).encode()
        return actual,reference,values

    def test_hosted_successor_gap_deltas_retained_and_still_require_topology(self):
        actual,reference,values=self.hosted_gap_properties()
        with patch.object(check,'ENCODING_KEYS',check.ENCODING_KEYS-{'successoravggap','successoravgloggap','successorexpstats'}):
            previous=check.properties(actual,reference,'abc')
        self.assertEqual('FAIL',previous['status'])
        self.assertEqual({'successoravgloggap','successorexpstats'},set(previous['semanticDifferences']))
        result=check.properties(actual,reference,'abc')
        self.assertEqual('PASS_REQUIRES_COMPLETE_TOPOLOGY',result['status'])
        self.assertEqual({k:{'actual':v[0],'reference':v[1]} for k,v in values.items()},result['differingProperties'])
        self.assertEqual(sorted(values),result['compressionExceptions'])
        self.assertEqual({},result['semanticDifferences'])
        self.assertTrue(result['requiresCompleteTopology']);self.assertFalse(result['independentStatisticsRecomputeClaim'])

    def test_both_successor_decimal_summaries_accept_only_finite_nonnegative_values(self):
        for key in ('successoravggap','successoravgloggap'):
            actual=('graphite.declaredTypes.sha256=abc\n'+key+'=1.25').encode();reference=(key+'=2.5').encode()
            self.assertEqual({key:{'actual':'1.25','reference':'2.5'}},check.properties(actual,reference,'abc')['differingProperties'])
            for bad in ('NaN','sNaN','Infinity','-Infinity','-1','-0.001','','garbage'):
                for side in ('actual','reference'):
                    with self.subTest(key=key,bad=bad,side=side),self.assertRaises(ValueError):
                        check.properties(actual.replace(b'1.25',bad.encode()) if side=='actual' else actual,
                                         reference.replace(b'2.5',bad.encode()) if side=='reference' else reference,'abc')

    def test_both_histograms_reject_bad_bin_syntax_on_either_arm(self):
        for key in ('successorexpstats','residualexpstats'):
            actual=('graphite.declaredTypes.sha256=abc\n'+key+'=1,2').encode();reference=(key+'=2,1').encode()
            self.assertEqual('PASS_REQUIRES_COMPLETE_TOPOLOGY',check.properties(actual,reference,'abc')['status'])
            for bad in ('','-1,2','1,-2','1,,2','1,','1, 2','1.0,2','NaN','+1,2'):
                for side in ('actual','reference'):
                    with self.subTest(key=key,bad=bad,side=side),self.assertRaises(ValueError):
                        check.properties(actual.replace(b'1,2',bad.encode()) if side=='actual' else actual,
                                         reference.replace(b'2,1',bad.encode()) if side=='reference' else reference,'abc')

    def test_successor_missing_keys_still_rejected_and_unknown_keys_not_exempted(self):
        for key in ('successoravggap','successoravgloggap','successorexpstats'):
            for actual,reference in ((('graphite.declaredTypes.sha256=abc\n'+key+'=1').encode(),b''),
                                     (b'graphite.declaredTypes.sha256=abc',(key+'=1').encode())):
                with self.subTest(key=key),self.assertRaisesRegex(ValueError,'key set differs'):
                    check.properties(actual,reference,'abc')
        actual,reference,_=self.hosted_gap_properties()
        result=check.properties(actual+b'successorFutureStatistic=1\n',reference+b'successorFutureStatistic=2\n','abc')
        self.assertEqual('FAIL',result['status'])
        self.assertEqual({'successorFutureStatistic':{'actual':'1','reference':'2'}},result['semanticDifferences'])

    def test_node_arc_and_decoder_parameters_still_block_with_gap_changes(self):
        actual,reference,_=self.hosted_gap_properties()
        for key,before,after in (('nodes','4','6'),('arcs','5','7'),('graphclass','it.unimi.dsi.webgraph.BVGraph','other.Graph'),
                                  ('compressionflags','0','1'),('windowsize','7','8'),('version','0','1')):
            a,b=actual,reference
            if key not in ('nodes','arcs','graphclass'):
                a+=(key+'='+before+'\n').encode();b+=(key+'='+before+'\n').encode()
            result=check.properties(a.replace((key+'='+before).encode(),(key+'='+after).encode()),b,'abc')
            self.assertEqual('FAIL',result['status'])
            self.assertEqual({key:{'actual':after,'reference':before}},result['semanticDifferences'])

    def test_gap_statistics_do_not_bypass_core_proof_or_write_completed_receipt(self):
        f=declarations_test.DeclarationBindingTests();f.setUp();self.addCleanup(f.doCleanups)
        reference=f.root/'reference';reference.mkdir()
        (reference/'forward.properties').write_text('graphclass=example.Graph\nsuccessoravgloggap=2.5\nsuccessorexpstats=2,1\n')
        props=f.graph/'forward.properties';props.write_text(props.read_text()+'successoravgloggap=1.25\nsuccessorexpstats=1,2\n')
        with patch.object(check.core,'prove',side_effect=ValueError('independent core mismatch')) as prove:
            with self.assertRaisesRegex(ValueError,'independent core mismatch'):
                check.execute(f.graph,reference,f.output,f.output,f.root/'proof',f.row,{}, {})
        prove.assert_called_once()
        self.assertEqual('PASS_REQUIRES_COMPLETE_TOPOLOGY',r.common.read(f.root/'proof/properties.json')['status'])
        self.assertFalse((f.root/'proof/record.json').exists())

    def test_property_authority_and_duplicate_key_fail_closed(self):
        for actual,reference,digest in [(b'graphite.declaredTypes.sha256=x',b'', 'wrong'),
            (b'graphite.declaredTypes.sha256=x',b'graphite.declaredTypes.sha256=x','x'),
            (b'x=1\nx=2\ngraphite.declaredTypes.sha256=x',b'x=1','x'),
            (b'x\\x=1\ngraphite.declaredTypes.sha256=x',b'x=1','x')]:
            with self.assertRaises(ValueError):check.properties(actual,reference,digest)

    def test_actual_structural_declarations_and_exact_original_core_modes(self):
        f=declarations_test.DeclarationBindingTests();f.setUp();self.addCleanup(f.doCleanups)
        reference=f.root/'reference';reference.mkdir();(reference/'forward.properties').write_text('graphclass=example.Graph\n')
        counts={k:0 for k in r.COUNTS};authority={'exact':'authority'};source={'exact':'source'}
        with patch.object(check.core,'prove',return_value=counts) as prove:
            check.execute(f.graph,reference,f.output,f.output,f.root/'proof',f.row,authority,source)
        args,kwargs=prove.call_args
        self.assertEqual((f.graph,reference,f.output,f.output,f.root/'proof/core',True),args)
        self.assertEqual({'field_authority_spec':authority,'method_array_corrections':True,'source_local_arrays':True,
            'parameter_arrays':True,'legacy_overload_collisions':True,'synthetic_method_keys':True,
            'inherited_fields':True,'source_rule':source},kwargs)
        proof=r.common.read(f.root/'proof/record.json')
        self.assertEqual(5,proof['declarations']['wireVersion']);self.assertEqual(1,proof['declarations']['types'])
        self.assertFalse(proof['declarations']['queryExpectedAuthority'])
        self.assertFalse(proof['declarations']['sourceToDeclarationCompletenessClaim'])
        self.assertFalse(proof['strictEquivalence'])

    def test_orphan_table_rejected_before_core(self):
        f=declarations_test.DeclarationBindingTests();f.setUp();self.addCleanup(f.doCleanups)
        (f.graph/'forward.properties').write_text('graphclass=example.Graph\n')
        with patch.object(check.core,'prove') as prove:
            with self.assertRaisesRegex(ValueError,'orphan'):check.execute(f.graph,f.graph,f.output,f.output,f.root/'proof',f.row,{}, {})
        prove.assert_not_called()


class SourceReplayTests(unittest.TestCase):
    def setUp(self):
        self.fixture=formatter_fixtures.FormatterAdapterTests();self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
        f=self.fixture;r.formatter.execute(f.path,f.out)

    def test_reconstructs_existing_rule_and_upstream_exactly(self):
        path=self.fixture.out/'source-binding.json';self.assertEqual(r.common.read(path),r.source_evidence(path))

    def test_full_marker_replay_result_is_shared_without_mutable_aliases(self):
        f=self.fixture;path=f.out/'source-binding.json'
        with patch.object(r.marker,'audit',side_effect=lambda root:copy.deepcopy(f.audit)) as replay:
            public=r.source_evidence(path)
            report,marked=r._source_evidence_with_marker(path,r.artifacts.ref(f.path))
        self.assertEqual(2,replay.call_count);self.assertEqual(public,report);self.assertEqual(f.audit,marked)
        marked['arms']['B']['artifactAudit']['path']='foreign'
        self.assertEqual(public,report);self.assertEqual(f.audit,r.common.read(f.path))

    def test_wrong_marker_pair_rejected_before_replay(self):
        f=self.fixture;wrong={**r.artifacts.ref(f.path),'path':str(f.path.parent/'foreign.json')}
        with patch.object(r.marker,'audit',side_effect=AssertionError('wrong pair must not replay')):
            with self.assertRaisesRegex(ValueError,'same actual formatter marker pair'):
                r._source_evidence_with_marker(f.out/'source-binding.json',wrong)

    def test_source_mutation_after_marker_replay_still_rejected(self):
        f=self.fixture
        def change(root):
            Path(f.f.sources['B']['path']).write_text('{}')
            return copy.deepcopy(f.audit)
        with patch.object(r.marker,'audit',side_effect=change):
            with self.assertRaises(ValueError):r.source_evidence(f.out/'source-binding.json')

    def test_status_or_rule_repin_cannot_authorize_changed_source_policy(self):
        path=self.fixture.out/'source-binding.json';value=r.common.read(path);value['productionFormatterTestsVerified']=True
        path.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError,'differs from actual source'):r.source_evidence(path)


class AuthorityTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name).resolve()
        jar=self.root/'corpus.jar';jar.write_bytes(b'actual input');self.jar={'corpus':'corpus','path':str(jar),'sha256':r.common.sha(jar)}
        self.roots={};self.graphs={};self.fixtures={};self.refs={}
        for arm in ('C','B'):
            root=self.root/arm;(root/'graphs').mkdir(parents=True);self.roots[arm]=root
            self.graphs[arm]={'id':'graph','path':str(root/'graphs/graph')}
            path=root/'graphs/fixture-provenance.tsv'
            path.write_text('graphId\tgraphPath\tcorpus\tsourceJarSha256\tsourceJar\n'+
                '\t'.join(['graph',self.graphs[arm]['path'],'corpus',self.jar['sha256'],jar.name])+'\n')
            self.fixtures[arm]={'files':{str(path):r.common.sha(path)},'inputJars':[self.jar]}
            r.common.save(root/'fixture-manifest.json',self.fixtures[arm]);self.refs[arm]=r.artifacts.ref(root/'fixture-manifest.json')

    def bind(self):return r.field_authority('graph',self.graphs,self.roots,self.fixtures,self.refs,{'path':'marker','sha256':'a'*64},{})
    def test_exact_shared_jar_and_per_graph_identity(self):
        value=self.bind();self.assertEqual(self.jar,value['jar']);self.assertEqual('graph',value['graphId'])
        self.assertEqual(self.refs['C'],value['arms']['C']['fixtureManifest'])
        self.assertEqual({'path':'marker','sha256':'a'*64},value['platformMarker'])
    def test_different_jar_path_even_equal_bytes_rejected(self):
        jar=self.root/'other.jar';jar.write_bytes(b'actual input');self.fixtures['B']['inputJars']=[{**self.jar,'path':str(jar)}]
        with self.assertRaisesRegex(ValueError,'exact graph corpus JAR'):self.bind()
    def test_wrong_graph_path_and_duplicate_provenance_rejected(self):
        self.graphs['B']['path']+='wrong'
        with self.assertRaisesRegex(ValueError,'graph provenance identity'):self.bind()


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name).resolve()
        self.out=self.root/'proof';self.input=self.root/'input';self.input.write_bytes(b'pinned actual input')
        self.plan={'maxOwnedPhases':129,'output':str(self.out),'referenceRoot':str(self.root/'C'),'actualRoot':str(self.root/'B'),
            'java':str(self.root/'jdk/bin/java'),'javac':str(self.root/'jdk/bin/javac'),'writerJar':str(self.root/'writer.jar'),
            'python':'/exact/python','graphs':[{'id':f'fixture-{i:02d}','B':f'/actual/{i}','C':f'/reference/{i}'} for i in range(64)],
            'pins':{str(self.input):r.common.sha(self.input)},'revisions':{'C':'c'*40,'B':'b'*40},
            'fixtureManifests':{'C':{},'B':{}},'upstream':{}}
        self.calls=[];self.failed_phase=None;self.bad_count=False
        self.progress=io.StringIO()
        p=patch.object(r.sys,'stderr',self.progress);p.start();self.addCleanup(p.stop)
        p=patch.object(r,'rebind',side_effect=lambda plan:copy.deepcopy(self.plan));p.start();self.addCleanup(p.stop)
        p=patch.object(r.producer,'phase',side_effect=self.phase);p.start();self.addCleanup(p.stop)
        p=patch('subprocess.Popen',side_effect=AssertionError('no subprocess permitted'));p.start();self.addCleanup(p.stop)

    def events(self,stage,event):
        values=[json.loads(line.removeprefix('CORE_PROGRESS ')) for line in self.progress.getvalue().splitlines()]
        self.assertTrue(all(value['diagnosticOnly'] is True for value in values))
        return [value for value in values if (value['stage'],value['event'])==(stage,event)]

    def phase(self,name,argv,cwd,env,out,timeout):
        self.calls.append((name,argv,env));path=out/name;path.mkdir()
        for leaf in ('stdout.log','stderr.log'):(path/leaf).write_text('raw tiny fixture')
        r.common.save(path/'owner.json',{'group':12345,'runnerPid':1234,'argv':argv})
        phase={'name':name,'argv':argv,'cwd':str(cwd),'timeoutSeconds':timeout,'errors':[], 'status':'PASS','exit':0,
            'cleanup':{'group':12345,'after':[],'errors':[],'exit':0},
            'logs':{str(path/n):r.common.sha(path/n) for n in ('stdout.log','stderr.log')}}
        if name==self.failed_phase:phase.update(status='FAIL',exit=17,errors=['intentional failure'])
        r.common.save(path/'record.json',phase)
        if name==self.failed_phase:raise ValueError('intentional phase failure')
        if name=='compile-topology':(out/'classes/VerifyTopology.class').write_bytes(b'compiled tiny helper')
        elif name!='compile-topology' and name.endswith('-topology'):
            graph_id=name.removesuffix('-topology');row=next(x for x in self.plan['graphs'] if x['id']==graph_id)
            proof=out/'graphs'/graph_id;(proof/'core').mkdir(parents=True)
            (proof/'core/field-bijection.tsv').write_text('mapping')
            counts={k:0 for k in r.COUNTS}
            receipt={'actual':row['B'],'reference':row['C'],'inputs':self.plan['pins'],
                     'mappingSha256':r.common.sha(proof/'core/field-bijection.tsv'),**counts}
            r.common.save(proof/'core/receipt.json',receipt)
            declaration={'status':'PASS_ADDITIVE_DECLARATION_WIRE_VALIDITY','sourceToDeclarationCompletenessClaim':False,'queryExpectedAuthority':False}
            props={'status':'PASS_REQUIRES_COMPLETE_TOPOLOGY'}
            for leaf,value in [('declarations.json',declaration),('properties.json',props)]:r.common.save(proof/leaf,value)
            r.common.save(proof/'record.json',{'status':r.CORE_PASS,'strictEquivalence':False,'core':receipt,
                        'declarations':declaration,'properties':props,**counts})
            r.common.save(proof/'topology.json',{'status':r.TOPOLOGY_PASS,'strictEquivalence':False,
                'coreReceiptSha256':r.common.sha(proof/'core/receipt.json'),'mappingSha256':receipt['mappingSha256'],
                'inputPins':self.plan['pins'],'actual':row['B'],'reference':row['C'],**counts,
                'fieldCorrectionCount':1 if self.bad_count else 0})

    def test_raw_local_scope_and_claims_are_part_of_independent_graph_audit(self):
        second=self.root/'second-input';second.write_text('second exact raw export')
        self.plan['pins'][str(second)]=r.common.sha(second);r.execute(self.plan)
        plan={**self.plan,'rawLocalExports':[]};row=plan['graphs'][0]
        path=self.out/'graphs'/row['id']/'core/raw-local-type-proof.json'
        report={'status':'PASS_ALL_PERSISTED_ARRAY_LOCALS_RAW_TYPE','completeNodeInventory':True,'unprovedCount':0,
                'strictEquivalence':False,'completeSemanticEquivalence':False,'performanceAcceptance':False,
                'localCount':1,'arrayCount':0,'occurrences':[{'graphId':row['id'],'status':'NON_ARRAY','issues':[],
                'savedType':'java.lang.Object','arms':{arm:{'occurrences':[],'typedAllocations':[]} for arm in ('C','B')}}],
                'inputs':dict(self.plan['pins'])}
        exports={arm:{'path':name,'sha256':digest} for arm,(name,digest) in zip(('C','B'),report['inputs'].items())}
        with patch.object(r.raw_local_export,'binding',return_value={'exports':exports}):
            path.write_text(json.dumps(report));self.assertEqual(row['id'],r.graph_result(plan,row)['id'])
            changes=[lambda v:v.update(strictEquivalence=True),lambda v:v.update(completeSemanticEquivalence=True),
                     lambda v:v.update(performanceAcceptance=True),lambda v:v['occurrences'][0].update(graphId='foreign'),
                     lambda v:v['inputs'].pop(str(second))]
            for change in changes:
                value=copy.deepcopy(report);change(value);path.write_text(json.dumps(value))
                with self.assertRaisesRegex(ValueError,'graph-scoped raw Local proof'):r.graph_result(plan,row)

    def test_complete_sequential129_phases_and_independent_raw_audit(self):
        record=r.execute(self.plan);self.assertEqual(r.PASS,record['status']);self.assertEqual(129,len(self.calls))
        audit=r.audit(self.out);self.assertEqual(r.AUDIT_PASS,audit['status']);self.assertEqual(64,len(audit['graphs']))
        self.assertEqual([x['id'] for x in self.plan['graphs']],[x['id'] for x in audit['graphs']])
        for key in r.FALSE_CLAIMS:self.assertFalse(audit[key])
        names=[name for name,_,_ in self.calls]
        for event in ('start','child-complete','complete'):
            self.assertEqual(names,[value['phase'] for value in self.events('phase',event)])
        self.assertEqual(list(range(1,130)),[value['ordinal'] for value in self.events('phase','start')])
        self.assertEqual(list(range(1,65)),[value['completedGraphs'] for value in self.events('graph-validation','complete')])
        self.assertEqual(64,self.events('final-validation','complete')[0]['completedGraphs'])
        self.assertEqual(64,self.events('audit','complete')[0]['completedGraphs'])
        self.assertNotIn('PASS',self.progress.getvalue())
        for name,argv,env in self.calls:
            self.assertEqual('-Xmx4g -XX:ActiveProcessorCount=4',env['JAVA_TOOL_OPTIONS'])
            if name!='compile-topology' and name.endswith('-topology'):
                self.assertEqual(str(r.PACKAGE),argv[-1]);self.assertIn('-Xmx4g',argv)
            elif name.endswith('-core'):
                self.assertEqual(['-B','-m','native_core_proof.check_graph'],argv[1:4]);self.assertEqual(r.common.sha(self.out/'plan.json'),argv[-2])

    def test_stops_at_first_failure_and_retains_raw_phase(self):
        self.failed_phase='fixture-00-topology';result=r.execute(self.plan)
        self.assertEqual('FAIL',result['status']);self.assertEqual(3,len(self.calls));self.assertEqual([],result['graphs'])
        self.assertEqual(17,r.common.read(self.out/self.failed_phase/'record.json')['exit'])
        with self.assertRaisesRegex(ValueError,'completed owned'):r.audit(self.out)
        self.assertEqual(self.failed_phase,self.events('phase','failed')[0]['phase'])
        self.assertNotIn(self.failed_phase,[x['phase'] for x in self.events('phase','child-complete')])
        self.assertEqual([],self.events('graph-validation','complete'))
        self.assertEqual([],self.events('execution','complete'))
        self.assertEqual([],self.events('audit','complete'))
        self.assertEqual(1,len(self.events('audit','failed')))

    def test_missing_pair_or_topology_count_disagreement_never_passes(self):
        self.bad_count=True;result=r.execute(self.plan)
        self.assertEqual('FAIL',result['status']);self.assertEqual(3,len(self.calls))
        self.assertIn('matching correction counts',result['errors'][0])
        self.assertEqual('fixture-00-topology',self.events('phase','child-complete')[-1]['phase'])
        self.assertEqual('fixture-00',self.events('graph-validation','start')[-1]['graph'])
        self.assertEqual([],self.events('graph-validation','complete'))
        self.assertNotIn('fixture-00-topology',[x['phase'] for x in self.events('phase','complete')])

    def test_actual_input_drift_after_execution_fails(self):
        original=self.phase
        def drift(*args):
            value=original(*args)
            if args[0]=='compile-topology':self.input.write_bytes(b'changed')
            return value
        with patch.object(r.producer,'phase',side_effect=drift):
            # The real binder validates all pins; this fixture binder mirrors that check.
            def rebind(plan):r.artifacts.verify_pins(plan['pins']);return copy.deepcopy(self.plan)
            with patch.object(r,'rebind',side_effect=rebind):record=r.execute(self.plan)
        self.assertEqual('FAIL',record['status']);self.assertEqual('FAIL',record['finalIdentity'])
        self.assertEqual(1,len(self.events('final-validation','failed')))
        self.assertEqual([],self.events('final-validation','complete'))
        self.assertEqual([],self.events('execution','complete'))

    def test_broken_stderr_does_not_change_execution_audit_or_saved_evidence(self):
        class Broken:
            def write(self,value):raise OSError('diagnostic stream closed')
            def flush(self):raise OSError('diagnostic flush closed')
        with patch.object(r.sys,'stderr',Broken()):
            record=r.execute(self.plan);first=r.audit(self.out)
        self.assertEqual(r.PASS,record['status'])
        before={str(p):p.read_bytes() for p in self.out.rglob('*') if p.is_file()}
        second=r.audit(self.out)
        self.assertEqual(first,second)
        self.assertEqual(before,{str(p):p.read_bytes() for p in self.out.rglob('*') if p.is_file()})

    def test_progress_flushes_each_event_and_ignores_flush_failure(self):
        class Stream(io.StringIO):
            flushes=0
            def flush(self):
                self.flushes+=1
                raise OSError('flush unavailable')
        stream=Stream()
        with patch.object(r.sys,'stderr',stream):r.progress('phase','start',phase='fixture-00-core')
        self.assertEqual(1,stream.flushes)
        self.assertEqual({'diagnosticOnly':True,'stage':'phase','event':'start','phase':'fixture-00-core'},
                         json.loads(stream.getvalue().removeprefix('CORE_PROGRESS ')))

    def test_repin_modified_raw_command_cannot_hide_unbounded_heap(self):
        r.execute(self.plan);path=self.out/'compile-topology/record.json';phase=r.common.read(path)
        phase['argv'][1]='-J-Xmx16g';path.write_text(json.dumps(phase))
        record=r.common.read(self.out/'record.json');record['phases'][0]=r.artifacts.ref(path);(self.out/'record.json').write_text(json.dumps(record))
        with self.assertRaisesRegex(ValueError,'raw actual command'):r.audit(self.out)

    def test_closed_proof_outputs_and_existing_output_rejected(self):
        r.execute(self.plan);(self.out/'graphs/foreign').mkdir()
        with self.assertRaisesRegex(ValueError,'closed all64'):r.audit(self.out)
        with self.assertRaises(FileExistsError):r.execute(self.plan)

    def test_mutable_topology_receipt_cannot_swap_graphs_even_when_repinning(self):
        r.execute(self.plan);path=self.out/'graphs/fixture-00/topology.json';top=r.common.read(path)
        top['reference']='/foreign';path.write_text(json.dumps(top))
        with self.assertRaisesRegex(ValueError,'proof identity'):r.audit(self.out)



class ShardLifecycleTests(unittest.TestCase):
    """The same tiny raw receipt checks across independent preparation/workers/join."""
    def setUp(self):
        self.f=LifecycleTests();self.f.setUp();self.addCleanup(self.f.doCleanups)
        self.plan=self.f.plan;self.out=self.f.out
        # This lifecycle fixture stubs upstream semantics; real input closure
        # mutation behavior is covered in test_native_core_worker_inputs.py.
        closure=patch.object(r,'_worker_input_closure',return_value={});closure.start();self.addCleanup(closure.stop)
        self.plan.update(execution=r.shard_execution(8),stopAtFirstFailure=False)
        for i,row in enumerate(self.plan['graphs']):row['fieldAuthority']={'corpus':f'corpus-{i//16}'}

    def worker(self,index,count):
        # Workers see independent graph directories at the same canonical path;
        # put earlier workers' outputs back only when simulating the final join.
        graphs=self.out/'graphs';saved=self.f.root/'other-worker-graphs'
        graphs.rename(saved);graphs.mkdir()
        try:return r.run_shard(self.out,index,count)
        finally:
            for path in saved.iterdir():path.rename(graphs/path.name)
            saved.rmdir()

    def complete(self,count=8):
        self.plan['execution']=r.shard_execution(count)
        self.assertEqual(r.PREPARATION_PASS,r.prepare(self.plan)['status'])
        preparation=(self.out/'preparation.json').read_bytes();plan=(self.out/'plan.json').read_bytes()
        for index in reversed(range(count)):
            self.assertEqual(r.SHARD_PASS,self.worker(index,count)['status'])
            self.assertFalse((self.out/'record.json').exists());self.assertFalse((self.out/'audit.json').exists())
        self.assertEqual(preparation,(self.out/'preparation.json').read_bytes())
        self.assertEqual(plan,(self.out/'plan.json').read_bytes())
        return r.join_shards(self.out,count)

    def test_eight_balanced_workers_preserve_original_complete_audit(self):
        result=self.complete()
        self.assertEqual(r.PASS,result['status']);self.assertEqual(129,len(self.f.calls))
        self.assertEqual([r.artifacts.ref(self.out/name/'record.json') for name,_,_ in r.commands(self.plan)],result['phases'])
        self.assertEqual([row['id'] for row in self.plan['graphs']],[row['id'] for row in result['graphs']])
        audit=r.audit(self.out);self.assertEqual(r.AUDIT_PASS,audit['status']);self.assertEqual(129,audit['phases'])
        for i in range(8):
            rows=r.shard_rows(self.plan,i)
            self.assertEqual(8,len(rows))
            self.assertEqual({f'corpus-{j}':2 for j in range(4)},
                             {corpus:sum(row['fieldAuthority']['corpus']==corpus for row in rows)
                              for corpus in {row['fieldAuthority']['corpus'] for row in rows}})
        self.assertEqual(1,sum(name=='compile-topology' for name,_,_ in self.f.calls))

    def test_four_workers_cover_same64_graphs_exactly_once(self):
        result=self.complete(4)
        self.assertEqual(64,len(result['graphs']));self.assertEqual(129,len(result['phases']))
        self.assertTrue(all(len(r.shard_rows(self.plan,i))==16 for i in range(4)))

    def test_full139_command_chain_prepares_eleven_once(self):
        self.plan['maxOwnedPhases']=139
        self.plan['rawLocalExports']=[{'arm':arm,'corpus':f'corpus-{i}',
            'java':self.plan['java'],'javac':self.plan['javac'],'writerJar':self.plan['writerJar'],
            'sourceJar':f'/source/corpus-{i}.jar','provenance':f'/source/{arm}.tsv'}
            for arm in ('C','B') for i in range(4)]
        original=self.f.phase
        def phase(name,argv,cwd,env,out,timeout):
            original(name,argv,cwd,env,out,timeout)
            if name.startswith('compile-raw-'):
                root=out/'classes'/('raw-'+name[-1]);root.mkdir();(root/'ExportRawLocals.class').write_bytes(b'tiny raw helper')
            elif name.startswith('raw-'):
                root=out/'raw-locals'/name;root.mkdir(parents=True);(root/'tiny.jsonl').write_text('{}\n')
        # Raw Local semantics have dedicated tests; this exercises the real139
        # commands, sealed phase receipts and fan-in without executing a JVM.
        graph_result=r.graph_result
        with patch.object(r.producer,'phase',side_effect=phase),patch.object(r,'graph_result',
                side_effect=lambda plan,row:graph_result({k:v for k,v in plan.items() if k!='rawLocalExports'},row)):
            result=self.complete()
            self.assertEqual(139,len(self.f.calls));self.assertEqual(139,len(result['phases']))
            self.assertEqual(11,len(r.common.read(self.out/'preparation.json')['phases']))
            self.assertEqual(139,r.audit(self.out)['phases'])

    def test_partial_shards_never_write_final_acceptance(self):
        r.prepare(self.plan);r.run_shard(self.out,0,8)
        with self.assertRaisesRegex(ValueError,'complete shard receipt inventory'):r.join_shards(self.out,8)
        self.assertFalse((self.out/'record.json').exists());self.assertFalse((self.out/'audit.json').exists())

    def test_duplicate_worker_rejected_without_overwriting_original_receipt(self):
        r.prepare(self.plan);r.run_shard(self.out,0,8)
        path=self.out/'shards/0/record.json';before=path.read_bytes()
        with self.assertRaises(FileExistsError):r.run_shard(self.out,0,8)
        self.assertEqual(before,path.read_bytes())

    def test_failed_worker_keeps_failure_and_blocks_join(self):
        r.prepare(self.plan);self.f.failed_phase='fixture-00-core'
        failed=r.run_shard(self.out,0,8)
        self.assertEqual('FAIL',failed['status']);self.assertEqual([],failed['graphs'])
        self.assertEqual(2,len(self.f.calls))
        self.f.failed_phase=None
        for index in range(1,8):self.worker(index,8)
        with self.assertRaisesRegex(ValueError,'successful shard'):r.join_shards(self.out,8)
        self.assertFalse((self.out/'audit.json').exists())

    def test_changed_preparation_blocks_worker_before_start(self):
        r.prepare(self.plan);(self.out/'classes/VerifyTopology.class').write_bytes(b'foreign')
        with self.assertRaisesRegex(ValueError,'prepared helper'):r.run_shard(self.out,0,8)
        self.assertEqual(1,len(self.f.calls));self.assertFalse((self.out/'shards/0').exists())

    def test_fresh_worker_creates_graph_parent_omitted_from_preparation_checkpoint(self):
        r.prepare(self.plan);(self.out/'graphs').rmdir();original=self.f.phase
        def phase(*args):
            if args[0].endswith('-core'):
                self.assertTrue((self.out/'graphs').is_dir())
                # Match check_graph.execute's mkdir without parents=True.
                probe=self.out/'graphs'/'parent-required';probe.mkdir();probe.rmdir()
            return original(*args)
        with patch.object(r.producer,'phase',side_effect=phase):result=r.run_shard(self.out,0,8)
        self.assertEqual(r.SHARD_PASS,result['status']);self.assertEqual(8,len(result['graphs']))

    def test_worker_rejects_file_or_symlink_as_graph_parent(self):
        r.prepare(self.plan);graphs=self.out/'graphs';graphs.rmdir()
        graphs.write_text('wrong type')
        with self.assertRaisesRegex(ValueError,'real worker graph directory'):r.run_shard(self.out,0,8)
        graphs.unlink();target=self.f.root/'external-graphs';target.mkdir();graphs.symlink_to(target,target_is_directory=True)
        with self.assertRaisesRegex(ValueError,'real worker graph directory'):r.run_shard(self.out,0,8)
        self.assertEqual(1,len(self.f.calls));self.assertFalse((self.out/'shards/0').exists())

    def test_extra_graph_cannot_be_hidden_by_selective_shard_checkpoint(self):
        r.prepare(self.plan);original=self.f.phase
        def phase(*args):
            result=original(*args)
            if args[0]=='fixture-00-core':(self.out/'graphs'/'foreign-graph').mkdir()
            return result
        with patch.object(r.producer,'phase',side_effect=phase):result=r.run_shard(self.out,0,8)
        self.assertEqual('FAIL',result['status']);self.assertEqual(8,len(result['graphs']))
        self.assertIn('closed assigned worker graph inventory',result['errors'][0])
        self.assertFalse((self.out/'audit.json').exists())

    def test_changed_plan_count_and_bad_partition_fail_before_execution(self):
        r.prepare(self.plan)
        for count,index in ((4,0),(8,-1),(8,8)):
            with self.assertRaises(ValueError):self.worker(index,count)
        self.assertEqual(1,len(self.f.calls))
        self.plan['graphs'][0]['fieldAuthority']['corpus']='foreign'
        with self.assertRaisesRegex(ValueError,'four complete16'):r.shard_rows(self.plan,0)

    def test_swapped_shard_receipt_is_rejected_even_when_files_exist(self):
        r.prepare(self.plan)
        for index in range(8):self.worker(index,8)
        a=self.out/'shards/0/record.json';b=self.out/'shards/1/record.json'
        a.write_bytes(b.read_bytes())
        with self.assertRaisesRegex(ValueError,'successful shard'):r.join_shards(self.out,8)

    def test_after_join_raw_phase_or_shard_drift_is_not_accepted(self):
        self.complete();path=self.out/'shards/0/record.json';value=r.common.read(path)
        value['graphs'].pop();path.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError,'assigned shard graph'):r.audit(self.out)

    def test_sequential_runner_cannot_claim_sharded_first_failure_policy(self):
        with self.assertRaisesRegex(ValueError,'sharded plans require'):r.execute(self.plan)
        self.assertFalse(self.out.exists())

    def test_changed_input_during_worker_preserves_failure_without_final_acceptance(self):
        r.prepare(self.plan);original=self.f.phase
        def phase(*args):
            result=original(*args)
            if args[0]=='fixture-00-core':self.f.input.write_bytes(b'changed during worker')
            return result
        def rebind(plan):r.artifacts.verify_pins(plan['pins']);return copy.deepcopy(self.plan)
        with patch.object(r.producer,'phase',side_effect=phase),patch.object(r,'rebind',side_effect=rebind):
            result=r.run_shard(self.out,0,8)
        self.assertEqual('FAIL',result['status']);self.assertEqual('FAIL',result['finalIdentity'])
        self.assertTrue(result['errors']);self.assertFalse((self.out/'audit.json').exists())

    def test_join_final_audit_failure_cannot_leave_pass_record(self):
        r.prepare(self.plan)
        for index in range(8):self.worker(index,8)
        with patch.object(r,'_audit',side_effect=ValueError('final independent audit rejected')):
            with self.assertRaisesRegex(ValueError,'independent audit'):r.join_shards(self.out,8)
        record=r.common.read(self.out/'record.json')
        self.assertEqual('FAIL',record['status']);self.assertEqual('FAIL',record['finalIdentity'])
        self.assertIn('independent audit',record['errors'][0]);self.assertFalse((self.out/'audit.json').exists())

    def prepared_workers(self):
        r.prepare(self.plan)
        for index in range(8):self.worker(index,8)

    def test_join_consumes_worker_audits_and_audit_only_revalidates_all(self):
        self.prepared_workers()
        original=r.graph_result
        with patch.object(r,'graph_result',wraps=original) as checked:
            result=r.join_shards(self.out,8)
            self.assertEqual(r.PASS,result['status'])
            checked.assert_not_called()
            completed=r.common.read(self.out/'audit.json')
            self.assertEqual(completed,r.audit_consumable(self.out))
            checked.assert_not_called()
            audited=r.audit(self.out)
            self.assertEqual(completed,audited)
            self.assertEqual(r.AUDIT_PASS,audited['status'])
            self.assertEqual([row['id'] for row in self.plan['graphs']],
                             [call.args[1]['id'] for call in checked.call_args_list])

    def test_join_rejects_missing_or_duplicate_graph_claim_before_raw_audit(self):
        self.prepared_workers();path=self.out/'shards/0/record.json';original=r.common.read(path)
        for duplicate in (False,True):
            with self.subTest(duplicate=duplicate):
                value=copy.deepcopy(original)
                if duplicate:value['graphs'][-1]=copy.deepcopy(value['graphs'][0])
                else:value['graphs'].pop()
                path.write_text(json.dumps(value))
                with patch.object(r,'graph_result',side_effect=AssertionError('receipt validation must reject first')):
                    with self.assertRaisesRegex(ValueError,'assigned shard graph receipt inventory'):r.join_shards(self.out,8)
                self.assertFalse((self.out/'record.json').exists());self.assertFalse((self.out/'audit.json').exists())
        path.write_text(json.dumps(original))

    def test_join_corrupt_raw_topology_cannot_be_hidden_by_shard_claims(self):
        self.prepared_workers();path=self.out/'graphs/fixture-00/topology.json';value=r.common.read(path)
        value['reference']='/foreign';path.write_text(json.dumps(value))
        # Repinning the claim does not make the changed raw graph semantically valid.
        shard_path=self.out/'shards/0/record.json';shard=r.common.read(shard_path)
        row=shard['graphs'][0];row['topology']=r.artifacts.ref(path);row['files'][str(path)]=r.common.sha(path)
        shard_path.write_text(json.dumps(shard))
        with self.assertRaisesRegex(ValueError,'independent shard audit'):r.join_shards(self.out,8)
        record=r.common.read(self.out/'record.json')
        self.assertEqual('FAIL',record['status']);self.assertEqual('FAIL',record['finalIdentity'])
        self.assertIn('join audit',record['errors'][0]);self.assertFalse((self.out/'audit.json').exists())

    def test_join_missing_raw_graph_output_leaves_failed_record(self):
        self.prepared_workers();(self.out/'graphs/fixture-00/topology.json').unlink()
        with self.assertRaises(FileNotFoundError):r.join_shards(self.out,8)
        record=r.common.read(self.out/'record.json')
        self.assertEqual('FAIL',record['status']);self.assertEqual('FAIL',record['finalIdentity'])
        self.assertFalse((self.out/'audit.json').exists())

    def test_join_interrupted_provisional_record_write_is_marked_failed(self):
        self.prepared_workers();save=r.common.save
        def interrupted(path,value):
            save(path,value)
            if path==self.out/'record.json':raise KeyboardInterrupt('interrupted provisional write')
        with patch.object(r.common,'save',side_effect=interrupted):
            with self.assertRaisesRegex(KeyboardInterrupt,'interrupted provisional write'):r.join_shards(self.out,8)
        record=r.common.read(self.out/'record.json')
        self.assertEqual('FAIL',record['status']);self.assertEqual('FAIL',record['finalIdentity'])
        self.assertFalse((self.out/'audit.json').exists())

    def test_join_rejects_graph_claim_count_drift_in_final_raw_audit(self):
        self.prepared_workers();path=self.out/'shards/0/record.json';value=r.common.read(path)
        value['graphs'][0][r.COUNTS[0]]+=1;path.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError,'independent shard audit'):r.join_shards(self.out,8)
        record=r.common.read(self.out/'record.json')
        self.assertEqual('FAIL',record['status']);self.assertFalse((self.out/'audit.json').exists())

    def test_independent_worker_audit_failure_cannot_publish_success(self):
        r.prepare(self.plan)
        with patch.object(r,'_audit_shard',side_effect=ValueError('independent semantics rejected')):
            with self.assertRaisesRegex(ValueError,'independent semantics'):self.worker(0,8)
        record=r.common.read(self.out/'shards/0/record.json')
        self.assertEqual('FAIL',record['status']);self.assertEqual('FAIL',record['finalIdentity'])
        self.assertFalse((self.out/'shards/0/audit.json').exists())

    def test_missing_or_changed_independent_audit_cannot_be_consumed(self):
        self.prepared_workers();path=self.out/'shards/0/audit.json';original=r.common.read(path)
        changes=[lambda v:v.update(status='FAIL'),lambda v:v.update(shardIndex=1),
                 lambda v:v['checker'].update(sha256='a'*64),lambda v:v['graphs'].pop(),
                 lambda v:v['pins'].pop(str(self.f.input))]
        for change in [None,*changes]:
            with self.subTest(change=change):
                if change is None:path.unlink()
                else:
                    value=copy.deepcopy(original);change(value);path.write_text(json.dumps(value))
                with self.assertRaises((FileNotFoundError,ValueError)):r.join_shards(self.out,8)
                self.assertEqual('FAIL',r.common.read(self.out/'record.json')['status'])
                self.assertFalse((self.out/'audit.json').exists())
                (self.out/'record.json').unlink();path.write_text(json.dumps(original))

    def test_extra_file_after_worker_audit_cannot_escape_closed_output_inventory(self):
        self.prepared_workers();(self.out/'graphs/fixture-00/extra.txt').write_text('unexpected output')
        with self.assertRaisesRegex(ValueError,'closed audited shard graph files'):r.join_shards(self.out,8)
        self.assertEqual('FAIL',r.common.read(self.out/'record.json')['status'])
        self.assertFalse((self.out/'audit.json').exists())

    def test_cli_routes_prepare_worker_join_without_reexecuting_sequential_proof(self):
        with (patch.object(r,'_prepare_fresh',return_value={'status':r.PREPARATION_PASS}) as prepare,
                patch.object(r.sys,'argv',['core','--reference','C','--actual','B','--output',str(self.out),'--prepare-only','--shard-count','8'])):
            self.assertEqual(0,r.main());prepare.assert_called_once_with(Path('C'),Path('B'),self.out,False,False,8)
        with (patch.object(r,'run_shard',return_value={'status':r.SHARD_PASS}) as worker,
                patch.object(r.sys,'argv',['core','--output',str(self.out),'--shard-index','3','--shard-count','8'])):
            self.assertEqual(0,r.main());worker.assert_called_once_with(self.out,3,8)
        with (patch.object(r,'join_shards') as join,
                patch.object(r.sys,'argv',['core','--output',str(self.out),'--join-shards','8'])):
            self.assertEqual(0,r.main());join.assert_called_once_with(self.out,8)


class FreshPreparationTests(unittest.TestCase):
    """Actual tiny Git/input closures with the existing owned-phase fixture."""
    def setUp(self):
        import subprocess
        from test_native_core_worker_inputs import WorkerInputTests
        inputs=WorkerInputTests(methodName='runTest')
        self.inputs,closure_plan,_=inputs.fixture();self.addCleanup(inputs.doCleanups)
        popen=subprocess.Popen
        self.f=LifecycleTests();self.f.setUp();self.addCleanup(self.f.doCleanups)
        # Only fixture Git inventory runs; the phase runner remains the tiny fake.
        patcher=patch('subprocess.Popen',popen);patcher.start();self.addCleanup(patcher.stop)
        self.plan=self.f.plan;self.out=self.f.out
        for key in ('upstream','revisions','referenceRoot','actualRoot'):
            self.plan[key]=closure_plan[key]
        self.plan['pins'].update(closure_plan['pins'])
        self.plan.update(execution=r.shard_execution(8),stopAtFirstFailure=False)
        for i,row in enumerate(self.plan['graphs']):row['fieldAuthority']={'corpus':f'corpus-{i//16}'}
        def bind(*args):
            self.bound=copy.deepcopy(self.plan);return self.bound
        patcher=patch.object(r,'bind',side_effect=bind)
        self.bind=patcher.start();self.addCleanup(patcher.stop)
        patcher=patch.object(r,'rebind',side_effect=lambda plan:copy.deepcopy(self.plan))
        self.rebind=patcher.start();self.addCleanup(patcher.stop)

    def fresh(self):
        return r._prepare_fresh(self.plan['referenceRoot'],self.plan['actualRoot'],self.out,shard_count=8)

    def assert_failed_receipt(self,result):
        self.assertEqual('FAIL',result['status'])
        self.assertEqual(result,r.common.read(self.out/'preparation.json'))
        self.assertTrue(result['errors'])

    def test_one_initial_bind_fresh_boundaries_and_unchanged_public_receipt(self):
        import shutil
        with patch.object(r.artifacts,'verify_pins',wraps=r.artifacts.verify_pins) as verify, \
                patch.object(r,'_worker_input_closure',wraps=r._worker_input_closure) as closure:
            fresh=self.fresh()
        self.assertEqual(r.PREPARATION_PASS,fresh['status'])
        self.assertEqual((1,0),(self.bind.call_count,self.rebind.call_count))
        self.assertEqual(4,sum(call.args[0]==self.plan['pins'] for call in verify.call_args_list))
        self.assertEqual(3,closure.call_count)
        self.assertEqual(self.plan,r.common.read(self.out/'plan.json'))
        self.assertEqual(['before-execution','after-execution'],
                         [row['boundary'] for row in self.f.events('preparation-inputs','complete')])
        shutil.rmtree(self.out);self.rebind.reset_mock()
        with patch.object(r.artifacts,'verify_pins',wraps=r.artifacts.verify_pins) as verify, \
                patch.object(r,'_worker_input_closure',wraps=r._worker_input_closure) as closure:
            standalone=r.prepare(self.plan)
        self.assertEqual(1,self.rebind.call_count)
        self.assertEqual(4,sum(call.args[0]==self.plan['pins'] for call in verify.call_args_list))
        self.assertEqual(3,closure.call_count)
        self.assertEqual(fresh,standalone)

    def test_before_initialization_rejects_real_input_source_inventory_and_plan_changes(self):
        for kind in ('input','source','directory','plan'):
            with self.subTest(kind=kind):
                case=FreshPreparationTests(methodName='runTest');case.setUp()
                try:
                    original=r._worker_input_closure;calls=0
                    def closure(plan):
                        nonlocal calls
                        value=original(plan);calls+=1
                        if calls==1:
                            if kind=='input':case.f.input.write_bytes(b'changed pinned input')
                            elif kind=='source':(case.inputs.checkout/'source.kt').write_text('changed source')
                            elif kind=='directory':(case.inputs.root/'marker/new-empty').mkdir()
                            else:plan['maxOwnedPhases']+=1
                        return value
                    with patch.object(r,'_worker_input_closure',side_effect=closure),self.assertRaises(ValueError):
                        case.fresh()
                    self.assertFalse(case.out.exists());self.assertEqual([],case.f.calls)
                finally:case.doCleanups()

    def test_phase_and_terminal_mutations_leave_failed_owned_receipt(self):
        import os
        for kind in ('input','same-size-restored-mtime','source','directory','config','unpinned','plan-file','plan-object','prepared-files','phase','terminal'):
            with self.subTest(kind=kind):
                case=FreshPreparationTests(methodName='runTest');case.setUp()
                try:
                    unpinned=case.inputs.root/'marker/unpinned.txt';unpinned.write_text('original')
                    def phase(*args):
                        case.f.phase(*args)
                        if kind=='input':case.f.input.write_bytes(b'changed pinned input')
                        elif kind=='same-size-restored-mtime':
                            p=case.f.input;before=p.stat();data=p.read_bytes()
                            p.write_bytes(bytes([data[0]^1])+data[1:]);os.utime(p,ns=(before.st_atime_ns,before.st_mtime_ns))
                        elif kind=='source':(case.inputs.checkout/'source.kt').write_text('changed source')
                        elif kind=='directory':(case.inputs.root/'marker/new-empty').mkdir()
                        elif kind=='config':(Path(case.inputs.env['GRADLE_USER_HOME'])/'init.d').mkdir(parents=True)
                        elif kind=='unpinned':unpinned.write_text('modified')
                        elif kind=='plan-file':(case.out/'plan.json').write_text('{}')
                        elif kind=='plan-object':case.bound['maxOwnedPhases']+=1
                        elif kind=='phase':raise ValueError('owned phase failure')
                    original_check=r._VerifiedWorkerInputs.check
                    def terminal(authority,plan):
                        if kind=='terminal':raise ValueError('terminal byte boundary failure')
                        original_check(authority,plan)
                    files=r.preparation_files;file_checks=0
                    def prepared_files(plan):
                        nonlocal file_checks
                        file_checks+=1
                        if kind=='prepared-files' and file_checks==2:
                            (case.out/'classes/VerifyTopology.class').write_bytes(b'changed prepared helper')
                        return files(plan)
                    with patch.object(r.producer,'phase',side_effect=phase),patch.object(r._VerifiedWorkerInputs,'check',new=terminal), \
                            patch.object(r,'preparation_files',side_effect=prepared_files):result=case.fresh()
                    case.assert_failed_receipt(result)
                    self.assertEqual(0,case.rebind.call_count)
                    if kind!='phase':self.assertEqual('FAIL',result['finalIdentity'])
                finally:case.doCleanups()

    def test_preparation_authority_expires_on_success_failure_and_publication_exception(self):
        for mode in ('pass','phase-failure','interrupt','publication'):
            with self.subTest(mode=mode):
                case=FreshPreparationTests(methodName='runTest');case.setUp()
                try:
                    opened=[];factory=r._VerifiedWorkerInputs._unwritten_preparation;save=r.common.save
                    def capture(plan):
                        value=factory(plan);opened.append(value);return value
                    def phase(*args):
                        case.f.phase(*args)
                        if mode=='phase-failure':raise ValueError('owned phase failed')
                        if mode=='interrupt':raise KeyboardInterrupt('owned phase interrupted')
                    def publish(path,value):
                        save(path,value)
                        if mode=='publication' and path==case.out/'plan.json':raise KeyboardInterrupt('publication interrupted')
                    before={sig:r.signal.getsignal(sig) for sig in (r.signal.SIGINT,r.signal.SIGTERM)}
                    with patch.object(r._VerifiedWorkerInputs,'_unwritten_preparation',side_effect=capture), \
                            patch.object(r.producer,'phase',side_effect=phase),patch.object(r.common,'save',side_effect=publish):
                        if mode=='publication':
                            with self.assertRaisesRegex(KeyboardInterrupt,'publication interrupted'):case.fresh()
                        else:
                            record=case.fresh()
                            self.assertEqual(r.PREPARATION_PASS if mode=='pass' else 'FAIL',record['status'])
                            self.assertEqual(record,r.common.read(case.out/'preparation.json'))
                    self.assertEqual(before,{sig:r.signal.getsignal(sig) for sig in before})
                    self.assertEqual(1,len(opened))
                    with self.assertRaisesRegex(ValueError,'invalidated'):opened[0].check(case.plan)
                finally:case.doCleanups()

    def test_terminal_byte_boundary_follows_final_output_consumption(self):
        files=r.preparation_files;calls=0
        def consume(plan):
            nonlocal calls
            value=files(plan);calls+=1
            if calls==2:(self.inputs.root/'marker/late-empty').mkdir()
            return value
        with patch.object(r,'preparation_files',side_effect=consume):result=self.fresh()
        self.assert_failed_receipt(result);self.assertEqual('FAIL',result['finalIdentity'])
        self.assertIn('closure changed',result['errors'][0])

    def test_independent_preparation_replay_detects_mid_replay_inventory_change(self):
        def changed(plan):
            (self.inputs.root/'marker/changed-in-replay').mkdir()
            return copy.deepcopy(plan)
        self.rebind.side_effect=changed
        with self.assertRaisesRegex(ValueError,'during complete binding'):r.prepare(self.plan)
        self.assertEqual(1,self.rebind.call_count);self.assertFalse(self.out.exists())

    def test_binding_failure_never_initializes_output(self):
        self.bind.side_effect=ValueError('complete semantic binding failure')
        with self.assertRaisesRegex(ValueError,'semantic binding'):self.fresh()
        self.assertFalse(self.out.exists());self.rebind.assert_not_called()


class PairBindingTests(unittest.TestCase):
    """Join boundaries only; each upstream raw auditor has its own byte tests."""
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name).resolve()
        self.roots={a:self.root/a for a in ('C','B')};self.values={};self.artifacts={};self.fixture_refs={};self.sources={}
        self.ids=[f'fixture-{i:02d}' for i in range(64)]
        jar=self.root/'corpus.jar';jar.write_bytes(b'tiny corpus identity');input_jar={'corpus':'corpus','path':str(jar),'sha256':r.common.sha(jar)}
        source_inputs=self.root/'inputs.json';source_inputs.write_text('{}')
        def put(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value));return r.artifacts.ref(path)
        self.put=put
        for arm,root in self.roots.items():
            revision=r.artifacts.ACCEPTED if arm=='C' else 'b'*40;role='accepted-baseline' if arm=='C' else 'candidate'
            graphs=[{'id':key,'path':str(root/'graphs'/key)} for key in self.ids]
            source=put(root/'source.json',{'root':str(root/'source')});self.sources[arm]=source
            prov=root/'graphs/fixture-provenance.tsv';prov.parent.mkdir()
            prov.write_text('graphId\tgraphPath\tcorpus\tsourceJarSha256\tsourceJar\n'+''.join(
                '\t'.join([g['id'],g['path'],'corpus',input_jar['sha256'],jar.name])+'\n' for g in graphs))
            fixture=put(root/'fixture.json',{'graphs':graphs,'files':{str(prov):r.common.sha(prov)},'inputJars':[input_jar]})
            self.fixture_refs[arm]=fixture
            artifact={'role':role,'revision':revision,'sourceManifest':source,'fixtureManifest':fixture,
                'producerPacket':{'path':str(root/'packet.json'),'sha256':'a'*64},
                'graphs':graphs,'pins':{ref['path']:ref['sha256'] for ref in (source,fixture)}}
            artifact_ref=put(root/'artifact-audit.json',artifact);self.artifacts[arm]=artifact_ref
            export={'artifactAudit':artifact_ref,'graphs':[{'id':key,'input':{'path':str(root/'graphs'/key/'graph.strings')}} for key in self.ids],
                    'pins':{**artifact['pins'],artifact_ref['path']:artifact_ref['sha256']}}
            path=root/'core-string-exports/audit.json';put(path,export);self.values[str(path.parent)]=export
            put(path.parent/'plan.json',{'sourceInputs':r.artifacts.ref(source_inputs)})
        marked={'arms':{a:{'artifactAudit':ref,'revision':r.artifacts.ACCEPTED if a=='C' else 'b'*40} for a,ref in self.artifacts.items()},
                'pins':{ref['path']:ref['sha256'] for ref in self.artifacts.values()}}
        path=self.roots['B']/'core-marker/audit.json';marker_ref=put(path,marked);self.values[str(path.parent)]=marked
        put(path.parent/'plan.json',{'java':'/actual/jdk/bin/java','javac':'/actual/jdk/bin/javac','writerJar':'/actual/writer.jar'})
        tested={'artifactAudit':self.artifacts['B'],'productionFormatterTestsVerified':True,'pins':{}}
        path=self.roots['B']/'formatter-tests/audit.json';put(path,tested);self.values[str(path.parent)]=tested
        self.bound={'upstream':{'markerAudit':marker_ref},'fixtureManifests':self.fixture_refs,
                    'sourceManifests':self.sources,'rule':{'path':'/actual/rule','sha256':'a'*64},'pins':{}}
        put(self.roots['B']/'core-formatter-source/source-binding.json',self.bound)
        for module in (r.strings,r.marker,r.tests):
            p=patch.object(module,'audit',side_effect=lambda root,**kwargs:copy.deepcopy(self.values[str(root)]));p.start();self.addCleanup(p.stop)
        def source_evidence(path,expected,**kwargs):
            r.require(self.bound['upstream']['markerAudit']==expected,'same actual formatter marker pair')
            _,marked=r.formatter._bind_with_marker(expected['path'],Path(path).parent,**kwargs)
            return copy.deepcopy(self.bound),marked
        p=patch.object(r,'_source_evidence_with_marker',side_effect=source_evidence);p.start();self.addCleanup(p.stop)
        p=patch.object(r.formatter,'preparation_control_pins',return_value={str(Path(r.formatter.__file__).resolve()):r.common.sha(r.formatter.__file__)});p.start();self.addCleanup(p.stop)
        p=patch.object(r,'preparation_control_pins',return_value={str(p):r.common.sha(p) for p in
                (Path(r.__file__).resolve(),r.PACKAGE/'check_graph.py',r.PACKAGE/'VerifyTopology.java')});p.start();self.addCleanup(p.stop)

    def bind(self):return r.bind(self.roots['C'],self.roots['B'],self.root/'proof')
    def test_exact64_ordered_pair_and_explicit_source_authority(self):
        plan=self.bind();self.assertEqual(self.ids,[g['id'] for g in plan['graphs']])
        self.assertEqual({'C':r.artifacts.ACCEPTED,'B':'b'*40},plan['revisions'])
        self.assertEqual(self.bound['rule'],plan['sourceRule']);self.assertEqual(129,plan['maxOwnedPhases'])
        self.assertEqual(self.fixture_refs['B'],plan['graphs'][-1]['fieldAuthority']['arms']['B']['fixtureManifest'])
    def test_complete_bind_replays_marker_once_and_keeps_exact_public_plan(self):
        composed=r._source_evidence_with_marker
        def legacy_double_replay(path,expected_ref,**kwargs):
            r.replay(expected_ref['path'],r.marker.audit)
            return composed(path,expected_ref,**kwargs)
        with patch.object(r.marker,'audit',side_effect=lambda root,**kwargs:copy.deepcopy(self.values[str(root)])) as replay:
            with patch.object(r,'_source_evidence_with_marker',side_effect=legacy_double_replay):
                expected=self.bind()
            self.assertEqual(2,replay.call_count);replay.reset_mock()
            actual=self.bind()
            self.assertEqual(1,replay.call_count)
        self.assertEqual(r.common.typed(expected),r.common.typed(actual))
        self.assertEqual(self.bound['upstream']['markerAudit'],actual['upstream']['marker'])

    def test_complete_bind_shares_one_private_context_and_discards_it(self):
        seen=[]
        def replay(root,*,_artifact_context):
            self.assertIs(type(_artifact_context),r.artifacts._ProducerReplayContext)
            seen.append(_artifact_context)
            return copy.deepcopy(self.values[str(root)])
        with patch.object(r.strings,'audit',side_effect=replay),patch.object(r.tests,'audit',side_effect=replay), \
                patch.object(r.marker,'audit',side_effect=replay):
            first=self.bind();second=self.bind()
        self.assertEqual(8,len(seen))
        self.assertTrue(all(value is seen[0] for value in seen[:4]))
        self.assertTrue(all(value is seen[4] for value in seen[4:]))
        self.assertIsNot(seen[0],seen[4]);self.assertEqual(first,second)
        for context in (seen[0],seen[4]):
            with self.assertRaisesRegex(ValueError,'inactive'):context.replay(None,None,None,None,None,None)

    def test_marker_failure_cannot_be_replaced_by_source_report(self):
        with patch.object(r.marker,'audit',side_effect=ValueError('actual marker failed')):
            with self.assertRaisesRegex(ValueError,'actual marker failed'):self.bind()

    def test_marker_pin_mutation_after_binding_fails_final_identity(self):
        real=r.field_authority;changed=False
        def mutate(*args):
            nonlocal changed
            if not changed:
                changed=True
                path=Path(self.artifacts['B']['path']);path.write_text(path.read_text()+' ')
            return real(*args)
        with patch.object(r,'field_authority',side_effect=mutate):
            with self.assertRaisesRegex(ValueError,'actual pinned input changed'):self.bind()

    def test_reordered_export_row_rejected_before_any_child(self):
        path=self.roots['B']/'core-string-exports/audit.json';value=self.values[str(path.parent)]
        value['graphs'].reverse();self.put(path,value)
        with self.assertRaisesRegex(ValueError,'ordered matched64'):self.bind()
    def test_foreign_formatter_test_runtime_cannot_be_joined(self):
        path=self.roots['B']/'formatter-tests/audit.json';value=self.values[str(path.parent)]
        value['artifactAudit']=self.artifacts['C'];self.put(path,value)
        with self.assertRaisesRegex(ValueError,'compiled formatter test'):self.bind()
    def test_foreign_source_rule_pair_cannot_be_joined(self):
        self.bound['fixtureManifests']={**self.fixture_refs,'B':self.fixture_refs['C']}
        with self.assertRaisesRegex(ValueError,'same actual formatter source pair'):self.bind()


class RawEdgeExportTests(unittest.TestCase):
    """Tiny raw files bind the optional format; Java execution remains a separate check."""
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name).resolve()
        self.proof=self.root/'proof';self.proof.mkdir();(self.root/'classes').mkdir()
        (self.root/'classes/VerifyTopology.class').write_bytes(b'tiny compiled identity')
        self.plan={'output':str(self.root),'rawEdgeExports':True,'pins':{},'graphs':[],
                   'java':'/jdk/java','javac':'/jdk/javac','writerJar':'/writer.jar','python':'/python'}
        source=r.artifacts.ref(r.PACKAGE/'VerifyTopology.java');self.plan['pins'][source['path']]=source['sha256']
        self.row={'id':'g'};self.top={'nodeSlots':3,'labeledEdges':2,'rawEdgeExports':{},'inputPins':{}}
        for name,arm,edges in [('actual','B',[(0,2,255),(2,1,2)]),('reference','C',[(0,1,2),(2,2,255)])]:
            graph=self.root/arm;graph.mkdir();self.row[arm]=str(graph);inputs={}
            for leaf in r.EDGE_INPUTS:
                path=graph/leaf;path.write_bytes((arm+leaf).encode());ref=r.artifacts.ref(path)
                inputs[leaf]=ref;self.plan['pins'][ref['path']]=ref['sha256'];self.top['inputPins'][ref['path']]=ref['sha256']
            output=self.proof/(name+'-edges.bin');output.write_bytes(struct.pack('>iiq',0x47534501,3,2)+b''.join(struct.pack('>iiB',*edge) for edge in edges))
            self.top['rawEdgeExports'][name]={'schema':'graphite.raw-labeled-edge-export.v1','format':'GSE01','graphRoot':str(graph),
                'sourceInputs':inputs,'output':r.artifacts.ref(output),'nodeSlots':3,'labeledEdges':2,'helperSource':source,
                'helperClass':r.artifacts.ref(self.root/'classes/VerifyTopology.class'),'allSequentialOffsetsChecked':True,
                'allRandomAccessOffsetsChecked':True,'allLabelPrefixOffsetsChecked':True,'rawNodeIdsPreserved':True,
                'mappingApplied':False,'fullScanConsumed':True}
        self.plan['graphs']=[self.row]
        (self.root/'plan.json').write_text('{}')

    def verify(self):r.raw_edge_exports(self.plan,self.row,self.top,self.proof)

    def test_optional_command_adds_two_paths_without_an_extra_phase(self):
        legacy=copy.deepcopy(self.plan);legacy.pop('rawEdgeExports')
        old=list(r.commands(legacy));new=list(r.commands(self.plan))
        self.assertEqual(3,len(old));self.assertEqual(3,len(new));self.assertEqual(old[:2],new[:2])
        self.assertEqual(old[-1][1],new[-1][1][:-2]);self.assertEqual(old[-1][2],new[-1][2])
        self.assertEqual([str(self.root/'graphs/g/actual-edges.bin'),str(self.root/'graphs/g/reference-edges.bin')],new[-1][1][-2:])
        self.verify()
        data=(self.proof/'actual-edges.bin').read_bytes()
        self.assertEqual((0x47534501,3,2),struct.unpack('>iiq',data[:16]))
        self.assertEqual([(0,2,255),(2,1,2)],list(struct.iter_unpack('>iiB',data[16:])))

    def test_repin_truncated_header_extra_record_and_wrong_counts_still_reject(self):
        file=self.proof/'actual-edges.bin';original=file.read_bytes()
        for data in (original[:15],original+b'\0',struct.pack('>iiq',0x47534501,4,2)+original[16:],
                     struct.pack('>iiq',0x47534502,3,2)+original[16:]):
            file.write_bytes(data);self.top['rawEdgeExports']['actual']['output']=r.artifacts.ref(file)
            with self.subTest(size=len(data)),self.assertRaisesRegex(ValueError,'GSE01'):self.verify()

    def test_foreign_root_input_output_helper_and_mapping_claim_reject(self):
        original=copy.deepcopy(self.top)
        for changed in ('root','input','output','class','source','mapping','incomplete','missing'):
            self.top=copy.deepcopy(original);value=self.top['rawEdgeExports']['actual']
            if changed=='root':value['graphRoot']=self.row['C']
            elif changed=='input':value['sourceInputs']['graph.labels']=self.top['rawEdgeExports']['reference']['sourceInputs']['graph.labels']
            elif changed=='output':value['output']=self.top['rawEdgeExports']['reference']['output']
            elif changed=='class':value['helperClass']={'path':'/foreign/class','sha256':value['helperClass']['sha256']}
            elif changed=='source':value['helperSource']={**value['helperSource'],'sha256':'0'*64}
            elif changed=='mapping':value['mappingApplied']=True
            elif changed=='incomplete':value['fullScanConsumed']=False
            else:self.top['rawEdgeExports'].pop('reference')
            with self.subTest(changed=changed),self.assertRaises(ValueError):self.verify()

    def test_changed_export_or_compiled_helper_cannot_reuse_receipt(self):
        file=self.proof/'actual-edges.bin';original=file.read_bytes();file.write_bytes(original[:-1]+b'\1')
        with self.assertRaisesRegex(ValueError,'output pin'):self.verify()
        file.write_bytes(original);(self.root/'classes/VerifyTopology.class').write_bytes(b'changed helper')
        with self.assertRaisesRegex(ValueError,'compiled raw edge helper'):self.verify()

    def test_raw_mode_is_rebound_instead_of_silently_dropped(self):
        self.plan.update(referenceRoot=self.row['C'],actualRoot=self.row['B'])
        with patch.object(r,'bind',return_value={}) as binder:r.rebind(self.plan)
        self.assertEqual((self.row['C'],self.row['B'],str(self.root),False,True),binder.call_args.args)




class FreshJoinTests(unittest.TestCase):
    """Actual tiny Git/input closures and persisted64 shard evidence at join."""
    def setUp(self):
        FreshPreparationTests.setUp(self)

    def prepared(self):
        self.assertEqual(r.PREPARATION_PASS,r.prepare(self.plan)['status'])
        for index in range(8):
            self.assertEqual(r.SHARD_PASS,ShardLifecycleTests.worker(self,index,8)['status'])
        self.rebind.reset_mock()

    def failed(self):
        record=r.common.read(self.out/'record.json')
        self.assertEqual(('FAIL','FAIL'),(record['status'],record['finalIdentity']))
        self.assertTrue(record['errors']);self.assertFalse((self.out/'audit.json').exists())

    def test_join_replays_once_and_standalone_audits_replay_independently(self):
        self.prepared();opened=[];open_=r._VerifiedWorkerInputs.open
        def capture(plan):
            value=open_(plan);opened.append(value);return value
        with patch.object(r._VerifiedWorkerInputs,'open',side_effect=capture), \
                patch.object(r.artifacts,'verify_pins',wraps=r.artifacts.verify_pins) as verify:
            result=r.join_shards(self.out,8)
        # Direct byte boundaries, excluding the mocked semantic rebind's own work:
        # open before/after + audit entry/exit, and one full merged result audit.
        self.assertEqual(4,sum(call.args[0]==self.plan['pins'] for call in verify.call_args_list))
        self.assertEqual(1,sum(str(self.out/'record.json') in call.args[0] for call in verify.call_args_list))
        self.assertEqual(r.PASS,result['status']);self.assertEqual(1,self.rebind.call_count)
        completed=r.common.read(self.out/'audit.json')
        self.assertEqual(completed,r.audit_shards(self.out));self.assertEqual(2,self.rebind.call_count)
        self.assertEqual(completed,r.audit(self.out));self.assertEqual(3,self.rebind.call_count)
        with self.assertRaisesRegex(ValueError,'invalidated'):opened[0].check(self.plan)
        with self.assertRaisesRegex(ValueError,'private worker'):
            r._audit(self.out,audited_shards=True,_input_authority=object())
        with self.assertRaisesRegex(ValueError,'requires shard audits'):
            r._audit(self.out,_input_authority=opened[0])

    def test_real_input_plan_and_raw_output_mutation_fail_after_summary(self):
        import os
        for kind in ('same-size-restored-mtime','directory','source','plan-file','raw-output'):
            with self.subTest(kind=kind):
                case=FreshJoinTests(methodName='runTest');case.setUp()
                try:
                    case.prepared();save=r.common.save;opened=[];open_=r._VerifiedWorkerInputs.open
                    def capture(plan):
                        value=open_(plan);opened.append(value);return value
                    def changed(path,value):
                        save(path,value)
                        if path!=case.out/'record.json':return
                        if kind=='same-size-restored-mtime':
                            p=case.f.input;before=p.stat();data=p.read_bytes()
                            p.write_bytes(bytes([data[0]^1])+data[1:]);os.utime(p,ns=(before.st_atime_ns,before.st_mtime_ns))
                        elif kind=='directory':(case.inputs.producer/'graphs/new-empty').mkdir()
                        elif kind=='source':(case.inputs.checkout/'source.kt').write_text('changed')
                        elif kind=='plan-file':(case.out/'plan.json').write_text('{}')
                        else:
                            p=case.out/'graphs/fixture-00/topology.json';v=r.common.read(p)
                            v['reference']='/changed';p.write_text(json.dumps(v))
                    with patch.object(r.common,'save',side_effect=changed),patch.object(r._VerifiedWorkerInputs,'open',side_effect=capture):
                        with self.assertRaises((ValueError,KeyError)):r.join_shards(case.out,8)
                    case.failed();self.assertEqual(1,case.rebind.call_count)
                    with self.assertRaisesRegex(ValueError,'invalidated'):opened[0].check(case.plan)
                finally:case.doCleanups()

    def test_terminal_closure_catches_drift_after_final_pin_verification(self):
        self.prepared();verify=r.artifacts.verify_pins;mutated=[]
        def changed(pins):
            verify(pins)
            if str(self.out/'record.json') in pins:
                (self.inputs.root/'marker/late-empty').mkdir();mutated.append(True)
        with patch.object(r.artifacts,'verify_pins',side_effect=changed):
            with self.assertRaisesRegex(ValueError,'closure changed'):r.join_shards(self.out,8)
        self.assertEqual([True],mutated);self.failed()

    def test_audit_publication_interruption_removes_partial_success(self):
        self.prepared();save=r.common.save;opened=[];open_=r._VerifiedWorkerInputs.open
        def capture(plan):
            value=open_(plan);opened.append(value);return value
        def interrupted(path,value):
            save(path,value)
            if path==self.out/'audit.json':raise KeyboardInterrupt('interrupted audit publication')
        with patch.object(r.common,'save',side_effect=interrupted),patch.object(r._VerifiedWorkerInputs,'open',side_effect=capture):
            with self.assertRaisesRegex(KeyboardInterrupt,'publication'):r.join_shards(self.out,8)
        self.failed()
        with self.assertRaisesRegex(ValueError,'invalidated'):opened[0].check(self.plan)

    def test_initial_replay_failure_creates_no_join_receipt(self):
        self.prepared();self.rebind.side_effect=ValueError('independent initial replay failure')
        with self.assertRaisesRegex(ValueError,'initial replay failure'):r.join_shards(self.out,8)
        self.assertFalse((self.out/'record.json').exists());self.assertFalse((self.out/'audit.json').exists())


if __name__=='__main__':unittest.main()

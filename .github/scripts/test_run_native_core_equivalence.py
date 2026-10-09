"""Tiny protocol/lifecycle fixtures; never invoke a JVM or real graph reader."""
import copy
import csv
import json
from pathlib import Path
import tempfile
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
        self.plan={'output':str(self.out),'referenceRoot':str(self.root/'C'),'actualRoot':str(self.root/'B'),
            'java':str(self.root/'jdk/bin/java'),'javac':str(self.root/'jdk/bin/javac'),'writerJar':str(self.root/'writer.jar'),
            'python':'/exact/python','graphs':[{'id':f'fixture-{i:02d}','B':f'/actual/{i}','C':f'/reference/{i}'} for i in range(64)],
            'pins':{str(self.input):r.common.sha(self.input)},'revisions':{'C':'c'*40,'B':'b'*40},
            'fixtureManifests':{'C':{},'B':{}},'upstream':{}}
        self.calls=[];self.failed_phase=None;self.bad_count=False
        p=patch.object(r,'rebind',side_effect=lambda plan:copy.deepcopy(self.plan));p.start();self.addCleanup(p.stop)
        p=patch.object(r.producer,'phase',side_effect=self.phase);p.start();self.addCleanup(p.stop)
        p=patch('subprocess.Popen',side_effect=AssertionError('no subprocess permitted'));p.start();self.addCleanup(p.stop)

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

    def test_complete_sequential129_phases_and_independent_raw_audit(self):
        record=r.execute(self.plan);self.assertEqual(r.PASS,record['status']);self.assertEqual(129,len(self.calls))
        audit=r.audit(self.out);self.assertEqual(r.AUDIT_PASS,audit['status']);self.assertEqual(64,len(audit['graphs']))
        self.assertEqual([x['id'] for x in self.plan['graphs']],[x['id'] for x in audit['graphs']])
        for key in r.FALSE_CLAIMS:self.assertFalse(audit[key])
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

    def test_missing_pair_or_topology_count_disagreement_never_passes(self):
        self.bad_count=True;result=r.execute(self.plan)
        self.assertEqual('FAIL',result['status']);self.assertEqual(3,len(self.calls))
        self.assertIn('matching correction counts',result['errors'][0])

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
                'graphs':graphs,'pins':{ref['path']:ref['sha256'] for ref in (source,fixture)}}
            artifact_ref=put(root/'artifact-audit.json',artifact);self.artifacts[arm]=artifact_ref
            export={'artifactAudit':artifact_ref,'graphs':[{'id':key,'input':{'path':str(root/'graphs'/key/'graph.strings')}} for key in self.ids],
                    'pins':{**artifact['pins'],artifact_ref['path']:artifact_ref['sha256']}}
            path=root/'core-string-exports/audit.json';put(path,export);self.values[str(path.parent)]=export
            put(path.parent/'plan.json',{'sourceInputs':r.artifacts.ref(source_inputs)})
        marked={'arms':{a:{'artifactAudit':ref} for a,ref in self.artifacts.items()},'pins':{}}
        path=self.roots['B']/'core-marker/audit.json';marker_ref=put(path,marked);self.values[str(path.parent)]=marked
        put(path.parent/'plan.json',{'java':'/actual/jdk/bin/java','javac':'/actual/jdk/bin/javac','writerJar':'/actual/writer.jar'})
        tested={'artifactAudit':self.artifacts['B'],'productionFormatterTestsVerified':True,'pins':{}}
        path=self.roots['B']/'formatter-tests/audit.json';put(path,tested);self.values[str(path.parent)]=tested
        self.bound={'upstream':{'markerAudit':marker_ref},'fixtureManifests':self.fixture_refs,
                    'sourceManifests':self.sources,'rule':{'path':'/actual/rule','sha256':'a'*64},'pins':{}}
        put(self.roots['B']/'core-formatter-source/source-binding.json',self.bound)
        for module in (r.strings,r.marker,r.tests):
            p=patch.object(module,'audit',side_effect=lambda root:copy.deepcopy(self.values[str(root)]));p.start();self.addCleanup(p.stop)
        p=patch.object(r,'source_evidence',side_effect=lambda path:copy.deepcopy(self.bound));p.start();self.addCleanup(p.stop)
        p=patch.object(r,'preparation_control_pins',return_value={str(p):r.common.sha(p) for p in
                (Path(r.__file__).resolve(),r.PACKAGE/'check_graph.py',r.PACKAGE/'VerifyTopology.java')});p.start();self.addCleanup(p.stop)

    def bind(self):return r.bind(self.roots['C'],self.roots['B'],self.root/'proof')
    def test_exact64_ordered_pair_and_explicit_source_authority(self):
        plan=self.bind();self.assertEqual(self.ids,[g['id'] for g in plan['graphs']])
        self.assertEqual({'C':r.artifacts.ACCEPTED,'B':'b'*40},plan['revisions'])
        self.assertEqual(self.bound['rule'],plan['sourceRule']);self.assertEqual(129,plan['maxOwnedPhases'])
        self.assertEqual(self.fixture_refs['B'],plan['graphs'][-1]['fieldAuthority']['arms']['B']['fixtureManifest'])
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


if __name__=='__main__':unittest.main()

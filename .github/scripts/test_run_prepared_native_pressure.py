import contextlib,json,tempfile,unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import run_prepared_native_pressure as r


class Execution(unittest.TestCase):
    def exercise(self,mode='pass',preparation_status=None,engine='native',loading_mode=None):
        with tempfile.TemporaryDirectory() as folder,contextlib.ExitStack() as stack:
            root=Path(folder);prep=root/'preparation';prep.mkdir();output=root/'execution';prefix=root/'reports/native-query'
            plan={'engine':'native' if mode=='wrong-engine' else engine,'operation':'query','cells':[{'id':str(i),'arm':arm} for i,arm in enumerate('CABBAC')]}
            if mode.startswith('pending'):
                plan.update(pendingComparabilityAuthority={'path':str(root/'producer.json'),'sha256':'a'*64},comparabilityPending=True)
            loading_prefix=root/'reports/native-loading' if loading_mode is not None else None
            if loading_mode not in (None,'unavailable'):plan['loadingMeasurement']={'schema':'test-declaration'}
            plan_path=prep/'plan.json';plan_path.write_text(json.dumps(plan));digest=r.pressure.sha(plan_path)
            ready={'status':'UNAVAILABLE' if mode=='unavailable' else 'PLAN_READY_NOT_MEASURED','planSha256':'0'*64 if mode=='plan-drift' else digest}
            if engine=='jvm':ready.update(schema='graphite.jvm-pressure.preparation.v1',engine=engine,operation='query')
            if preparation_status is not None:ready=preparation_status
            (prep/'preparation-status.json').write_text(json.dumps(ready));events=[]
            def run(plan_file,cell,directory):
                events.append(('run',cell));p=Path(directory);p.mkdir()
                result={'status':'FAIL' if mode=='cell-failure' and cell=='1' else 'PASS'}
                (p/'result.json').write_text(json.dumps(result));return result
            def audit(plan_file,directory):
                cell=Path(directory).name;events.append(('audit',cell))
                if mode=='audit-failure' and cell=='1':raise ValueError('invalid complete body')
                return {'status':'PASS'}
            def compare(command,**kwargs):
                if Path(command[1]).name=='benchmark-native-loading.mjs':
                    events.append(('loading-compare',None))
                    passed=loading_mode=='pass' and mode!='pending'
                    pending=mode=='pending' and loading_mode!='unavailable'
                    value={'schema':'graphite.native64-loading.comparison.v1','engine':'native','operation':'loading',
                           'passed':passed,'status':'UNAVAILABLE' if loading_mode=='unavailable' else r.PENDING if pending else 'PASS' if passed else 'FAIL',
                           'planSha256':digest,'comparabilityPending':pending,'coreComparabilityVerified':not pending}
                    loading_prefix.parent.mkdir(parents=True,exist_ok=True)
                    Path(str(loading_prefix)+'-status.json').write_text(json.dumps(value))
                    Path(str(loading_prefix)+'-report.md').write_text('independent loading')
                    return SimpleNamespace(returncode=0 if passed else 1,stdout='',stderr='')
                if '--progress' in command:
                    count=int(command[-1]);events.append(('progress',str(count)))
                    if mode=='progress-timeout':raise r.subprocess.TimeoutExpired(command,60)
                    if mode=='progress-missing':return SimpleNamespace(returncode=1,stdout='',stderr='no report')
                    progress_prefix=command[-3]
                    report={'schema':'graphite.multigraph-pressure.progress.v1','status':'PROVISIONAL',
                        'engine':engine,'operation':'query','planSha256':digest,
                        'completedCells':[str(i) for i in range(count)],'passed':False,
                        'performanceAcceptance':mode=='progress-forged-pass','queryEvidenceComplete':False,
                        'otherOperationsEligible':False}
                    Path(progress_prefix+'-status.json').write_text(json.dumps(report))
                    Path(progress_prefix+'-report.md').write_text('PROVISIONAL fixed-pair feedback')
                    return SimpleNamespace(returncode=0,stdout='',stderr='')
                events.append(('compare',None));self.assertEqual(kwargs['timeout'],60);self.assertFalse(kwargs['check'])
                passed=mode not in ('regression','bad-verdict','pending')
                prefix.parent.mkdir(parents=True,exist_ok=True)
                Path(str(prefix)+'-status.json').write_text(json.dumps({'schema':'graphite.multigraph-pressure.comparison.v1','engine':'native' if mode=='wrong-verdict-engine' else engine,'operation':'query','passed':passed,'status':r.PENDING if mode=='pending' else 'PASS' if passed else 'FAIL','planSha256':digest,**({'comparabilityPending':True} if mode=='pending' else {})}))
                Path(str(prefix)+'-report.md').write_text('actual comparison test verdict')
                return SimpleNamespace(returncode=0 if mode=='bad-verdict' or passed else 1,stdout='done',stderr='')
            if mode.startswith('pending'):
                stack.enter_context(patch.object(r.pressure,'_execution_closure',return_value={'trees':{}}))
                def combined_run(p,c,o,v,*,_before_inputs=None):
                    if _before_inputs is not None:_before_inputs()
                    return run(p,c,o)
                stack.enter_context(patch.object(r.pressure,'_run_cell',side_effect=combined_run))
                stack.enter_context(patch.object(r.pressure,'_audit',side_effect=lambda p,o,v:audit(p,o)))
            stack.enter_context(patch.object(r.pressure,'validate_plan',side_effect=lambda p:p))
            stack.enter_context(patch.object(r.pressure,'verify_inputs'))
            stack.enter_context(patch.object(r.pressure,'run_cell',side_effect=run))
            stack.enter_context(patch.object(r.pressure,'audit',side_effect=audit))
            stack.enter_context(patch.object(r.subprocess,'run',side_effect=compare))
            stack.enter_context(patch.object(r.shutil,'which',return_value='/usr/bin/node'))
            stack.enter_context(patch('subprocess.Popen',side_effect=AssertionError('no actual child allowed')))
            if mode=='control-drift':
                sha=r.pressure.sha;calls={}
                def changed(path):
                    p=Path(path);calls[str(p)]=calls.get(str(p),0)+1
                    return '0'*64 if p.name=='run_prepared_native_pressure.py' and calls[str(p)]>1 else sha(path)
                stack.enter_context(patch.object(r.pressure,'sha',side_effect=changed))
            result=r.execute(prep,output,prefix,engine=engine,loading_prefix=loading_prefix)
            stored=json.loads((output/'execution.json').read_text());self.assertEqual(result,stored)
            verdict=json.loads(Path(str(prefix)+'-status.json').read_text())
            self.assertFalse(result['otherOperationsEligible'])
            if loading_prefix is not None:self.loading_verdict=json.loads(Path(str(loading_prefix)+'-status.json').read_text())
            return result,verdict,events

    def test_progress_distinguishes_context_verification_run_audit_and_terminal_check(self):
        with patch('builtins.print') as progress:
            result, _, _ = self.exercise('pending')
        self.assertEqual(r.PENDING, result['status'])
        all_messages = [call.args[0] for call in progress.call_args_list]
        self.assertEqual(all_messages.count('PROVISIONAL fixed-pair feedback'),2)
        messages = [m for m in all_messages if not m.startswith('PROVISIONAL')]
        self.assertEqual(messages[0], 'pressure context: verifying authority and input closure')
        self.assertTrue(messages[1].startswith('pressure context: verified elapsed='))
        phases = ('verifying execution boundary', 'running cell including launch and cleanup verification',
                  'auditing completed raw evidence', 'verifying terminal execution boundary',
                  'run and independent audit complete')
        self.assertEqual(len(messages), 2 + 6 * len(phases))
        for message, (cell, phase) in zip(messages[2:], ((i, phase) for i in range(6) for phase in phases)):
            self.assertTrue(message.startswith(f'pressure cell {cell}: {phase} elapsed='), message)
        self.assertTrue(all(call.kwargs == {'flush': True} for call in progress.call_args_list))

    def test_pending_runs_every_original_cell_and_audit_without_acceptance(self):
        result,verdict,events=self.exercise('pending')
        self.assertEqual(r.PENDING,result['status']);self.assertFalse(result['performanceAcceptance'])
        self.assertTrue(result['comparabilityPending']);self.assertEqual([],result['unissued'])
        self.assertEqual(self.expected_events(),events)
        self.assertFalse(verdict['passed'])

    def test_pending_comparator_cannot_silently_claim_acceptance(self):
        result,verdict,events=self.exercise('pending-forged-pass')
        self.assertEqual('FAIL',result['status']);self.assertFalse(result['performanceAcceptance'])
        self.assertIn('unjoined measurement',result['errors'][0]);self.assertFalse(verdict['passed'])

    def test_all_six_cells_run_then_audit_sequentially_before_comparison(self):
        result,verdict,events=self.exercise()
        self.assertEqual(events,self.expected_events())
        self.assertTrue(result['performanceAcceptance']);self.assertTrue(verdict['passed']);self.assertEqual(result['unissued'],[])
        self.assertTrue(all(c['status']=='PASS' and c['auditSha256'] for c in result['cells']))

    @staticmethod
    def expected_events():
        events=[]
        for i in range(6):
            events.extend([('run',str(i)),('audit',str(i))])
            if i+1 in (3,5):events.append(('progress',str(i+1)))
        return events+[('compare',None)]

    def test_progress_is_after_complete_pair_audits_and_never_replaces_final_comparison(self):
        result,verdict,events=self.exercise('regression')
        self.assertEqual(events,self.expected_events())
        self.assertEqual([3,5],[p['completedCells'] for p in result['progress']])
        self.assertTrue(all(p['sha256'] for p in result['progress']))
        self.assertFalse(verdict['passed']);self.assertFalse(result['performanceAcceptance'])

    def test_failed_or_forged_diagnostic_is_retained_without_losing_remaining_samples(self):
        for mode in ('progress-forged-pass','progress-timeout','progress-missing'):
            with self.subTest(mode=mode):
                result,verdict,events=self.exercise(mode)
                self.assertEqual(self.expected_events(),events)
                self.assertEqual([3,5],[r['completedCells'] for r in result['progressErrors']])
                self.assertNotIn('progress',result)
                self.assertEqual([],result['unissued'])
                self.assertEqual('PASS',result['status']);self.assertTrue(verdict['passed'])
                self.assertEqual([],result['errors'])

    def test_missing_authority_cannot_launch_or_compare(self):
        result,verdict,events=self.exercise('unavailable')
        self.assertEqual(events,[]);self.assertEqual(result['status'],'UNAVAILABLE');self.assertFalse(verdict['passed'])

    def test_actual_unavailable_shape_preserves_original_missing_authority(self):
        reason='Complete C/A/B core, topology and index semantic equivalence or independently proven source corrections; fresh39 response correctness does not establish this.'
        ready={'schema':'graphite.native-pressure.preparation.v1','engine':'native','operation':'query',
            'passed':False,'status':'UNAVAILABLE','performanceAcceptance':False,
            'unavailableOperations':['construction','loading'],'unavailableFamilies':[],
            'missingProducers':[reason]}
        result,verdict,events=self.exercise(preparation_status=ready)
        self.assertEqual([],events);self.assertEqual('UNAVAILABLE',result['status'])
        self.assertEqual([reason],result['missingProducers']);self.assertEqual([reason],verdict['missingProducers'])
        self.assertEqual(['Matched producer plan is not ready: UNAVAILABLE',reason],verdict['errors'])
        self.assertFalse(result['performanceAcceptance']);self.assertFalse(verdict['passed'])
        self.assertFalse(verdict['queryEvidenceComplete']);self.assertNotIn('evidence',verdict)

    def test_preparation_failure_retains_actual_error_and_cannot_start(self):
        result,verdict,events=self.exercise(preparation_status={'status':'FAIL','errors':['Exact writer digest changed']})
        self.assertEqual([],events);self.assertEqual('FAIL',verdict['status'])
        self.assertEqual(['Matched producer plan is not ready: FAIL','Exact writer digest changed'],result['errors'])
        self.assertFalse(verdict['passed'])

    def test_malformed_missing_reasons_fail_without_launching(self):
        for value in ('text', [None], ['']):
            with self.subTest(value=value):
                result,verdict,events=self.exercise(preparation_status={'status':'UNAVAILABLE','missingProducers':value})
                self.assertEqual([],events);self.assertEqual('FAIL',result['status'])
                self.assertIn('malformed preparation missingProducers',result['errors'][0])
                self.assertFalse(verdict['passed'])

    def test_changed_plan_prevents_execution(self):
        result,verdict,events=self.exercise('plan-drift')
        self.assertEqual(events,[]);self.assertEqual(result['status'],'FAIL');self.assertIn('digest changed',result['errors'][0])

    def test_failed_cell_preserves_failure_and_unissued_suffix(self):
        result,verdict,events=self.exercise('cell-failure')
        self.assertEqual(events,[('run','0'),('audit','0'),('run','1')]);self.assertEqual(result['unissued'],['2','3','4','5'])
        self.assertEqual(result['cells'][-1]['status'],'FAIL');self.assertFalse(verdict['passed'])

    def test_audit_failure_stops_before_next_cell(self):
        result,verdict,events=self.exercise('audit-failure')
        self.assertEqual(events[-1],('audit','1'));self.assertEqual(result['unissued'],['2','3','4','5']);self.assertFalse(verdict['queryEvidenceComplete'])

    def test_real_comparison_regression_is_retained(self):
        result,verdict,events=self.exercise('regression')
        self.assertEqual(result['status'],'FAIL');self.assertFalse(result['performanceAcceptance']);self.assertEqual(result['comparisonExit'],1)
        self.assertEqual(result['errors'],[]);self.assertEqual(events[-1],('compare',None))

    def test_comparison_exit_must_match_verdict(self):
        result,verdict,events=self.exercise('bad-verdict')
        self.assertEqual(result['status'],'FAIL');self.assertIn('exit/verdict mismatch',result['errors'][0]);self.assertFalse(verdict['passed'])

    def test_control_drift_invalidates_success(self):
        result,verdict,events=self.exercise('control-drift')
        self.assertEqual(result['status'],'FAIL');self.assertFalse(result['performanceAcceptance']);self.assertFalse(verdict['passed'])

    def test_jvm_reuses_all_six_owned_cells_and_retains_engine_identity(self):
        result,verdict,events=self.exercise(engine='jvm')
        self.assertEqual('graphite.jvm-pressure.execution.v1',result['schema'])
        self.assertEqual('jvm',result['engine']);self.assertEqual('jvm',verdict['engine'])
        self.assertEqual(self.expected_events(),events)
        self.assertTrue(result['performanceAcceptance'])
        self.assertTrue(all(str(r.SCRIPTS/name) in result['controls'] for name in
                            ('jvm_pressure_oracles.py','jvm_pressure_distinct.py','jvm_pressure_inputs.py')))

    def test_jvm_requires_explicit_preparation_and_matching_plan_engine(self):
        for mode,ready in [('wrong-engine',None),('pass',{'status':'UNAVAILABLE'})]:
            result,verdict,events=self.exercise(mode,preparation_status=ready,engine='jvm')
            self.assertEqual([],events);self.assertEqual('FAIL',result['status'])
            self.assertEqual('jvm',verdict['engine']);self.assertFalse(verdict['passed'])

    def test_jvm_retains_failed_cell_and_unissued_suffix(self):
        result,verdict,events=self.exercise('cell-failure',engine='jvm')
        self.assertEqual([('run','0'),('audit','0'),('run','1')],events)
        self.assertEqual(['2','3','4','5'],result['unissued']);self.assertFalse(result['performanceAcceptance'])
        self.assertEqual('jvm',verdict['engine'])

    def test_jvm_rejects_wrong_engine_comparison_even_with_matching_plan_digest(self):
        result,verdict,events=self.exercise('wrong-verdict-engine',engine='jvm')
        self.assertEqual(('compare',None),events[-1]);self.assertEqual('FAIL',result['status'])
        self.assertIn('comparison engine binding',result['errors'][0]);self.assertFalse(verdict['passed'])

    def test_direct_complete_plan_loading_preserves_independent_query_verdict(self):
        for query,loading in [('pass','pass'),('regression','pass'),('pass','regression'),('pass','unavailable')]:
            with self.subTest(query=query,loading=loading):
                result,verdict,events=self.exercise(query,loading_mode=loading)
                self.assertEqual(query=='pass',verdict['passed'])
                self.assertEqual(loading=='pass',self.loading_verdict['passed'])
                self.assertEqual(query==loading=='pass',result['performanceAcceptance'])
                self.assertEqual(6,sum(kind=='run' for kind,_ in events))
                self.assertEqual(6,sum(kind=='audit' for kind,_ in events))

class Finalization(unittest.TestCase):
    """Only external authority replay/Node execution are mocked, never disk pins.

    Existing bundle/raw audit suites exercise those authorities independently;
    these tests exercise the join and immutable original measurement binding.
    """
    def exercise(self,mode='pass',loading_mode=None,measurement_reports=False,stale_loading=None):
        import assemble_native_pressure_producers as bundle
        with tempfile.TemporaryDirectory() as folder,contextlib.ExitStack() as stack:
            root=Path(folder).resolve();prep=root/'prep';prep.mkdir();out=root/'cells';out.mkdir();prefix=root/'final/query'
            def put(name,value):
                p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(value))
                return bundle.artifacts.ref(p)
            control=put('control.json',{'actual':'control'})
            coreplan=put('core/plan.json',{'scope':'core fixture'})
            phase=put('core/phase/record.json',{'status':'PASS','exit':0,'errors':[]})
            core_record=put('core/record.json',{'plan':coreplan,'phases':[phase]})
            core_audit=put('core/audit.json',{'record':core_record})
            pins={item['path']:item['sha256'] for item in (control,)}
            pending={'arms':{'A':{'revision':'a'*40},'B':{'revision':'b'*40}},'pins':pins}
            pending_ref=put('pending.json',pending)
            corrected=dict(pending,artifactsRoot=str(root/'artifacts'),baseRevision='a'*40,candidateRevision='b'*40,
                           correctedComparisons={'B':{'audit':core_audit}},
                           pins={**pins,**{item['path']:item['sha256'] for item in (coreplan,phase,core_record,core_audit)}})
            corrected_ref=put('corrected.json',corrected)
            plan={'engine':'native','comparabilityPending':True,'pendingComparabilityAuthority':pending_ref,
                  'pins':{**pins,pending_ref['path']:pending_ref['sha256']},'arms':pending['arms'],
                  'cells':[{'id':str(i),'arm':arm} for i,arm in enumerate('CABBAC')]}
            if loading_mode is not None:
                from native_loading import DECLARATION
                plan['loadingMeasurement']=dict(DECLARATION)
            loading_prefix=root/'final/multigraph-native-loading' if loading_mode is not None else None
            plan_ref=put('prep/plan.json',plan)
            ready=put('prep/preparation-status.json',{'status':'PLAN_READY_NOT_MEASURED',
                      'comparabilityPending':True,'planSha256':plan_ref['sha256']})
            cells=[];audits={}
            for cell in plan['cells']:
                result=put('cells/'+cell['id']+'/result.json',{'status':'PASS','marker':cell['id']})
                audit=put('cells/'+cell['id']+'/audit.json',{'status':'PASS','resultSha256':result['sha256']})
                audits[cell['id']]=r.pressure.read(audit['path'])
                cells.append({'id':cell['id'],'status':'PASS','resultSha256':result['sha256'],'auditSha256':audit['sha256']})
            execution={'schema':'graphite.native-pressure.execution.v1','engine':'native','status':r.PENDING,
                       'comparabilityPending':True,'performanceAcceptance':False,'otherOperationsEligible':False,
                       'errors':[],'unissued':[],'plan':plan_ref,'preparation':ready,'controls':pins,'cells':cells}
            if mode=='missing-cell':execution['cells'].pop()
            if mode=='execution-failed':execution['errors']=['actual failure']
            put('cells/execution.json',execution)
            if mode=='changed-cell':put('cells/0/result.json',{'status':'PASS','marker':'changed'})
            original={str(p):p.read_bytes() for parent in (prep,out) for p in parent.rglob('*') if p.is_file()}
            events=[]
            def audit(directory):
                events.append(('audit',Path(directory).name))
                if mode=='raw-body-failed':raise ValueError('independent full response mismatch')
                return audits[Path(directory).name]
            context=SimpleNamespace(plan=plan,audit_cell=audit,_check_unchanged=lambda:None)
            stack.enter_context(patch.object(r.pressure._VerifiedPressureExecution,'open',return_value=context))
            def verify(*args):
                events.append(('core',None))
                if mode in ('missing-core','failed-core'):raise ValueError(mode)
                return corrected
            stack.enter_context(patch.object(bundle,'verify_bundle',side_effect=verify))
            stack.enter_context(patch.object(bundle,'corrected_fixture_bindings',return_value={}))
            def reassemble(*args,**kwargs):
                self.assertEqual({'measurement_only':True},kwargs)
                return dict(pending,arms={}) if mode=='different-producer' else pending
            stack.enter_context(patch.object(bundle,'assemble',side_effect=reassemble))
            def compare(command,**kwargs):
                is_loading=Path(command[1]).name=='benchmark-native-loading.mjs'
                events.append(('loading-compare' if is_loading else 'compare',None));self.assertEqual('--comparability-receipt',command[-2])
                receipt=r.pressure.read(command[-1]);self.assertEqual(pending_ref['sha256'],receipt['measurementInputsSha256'])
                self.assertEqual(6,len(receipt['cells']));self.assertFalse(receipt['completeSemanticEquivalence'])
                self.assertFalse(receipt['performanceAcceptance']);self.assertTrue(receipt['coreComparabilityVerified'])
                self.assertIn(phase['path'],[row['path'] for row in receipt['metadata']])
                for row in receipt['metadata']:
                    self.assertEqual(row['sha256'],r.pressure.sha(prefix.parent/row['relativePath']))
                if is_loading:
                    passed=loading_mode!='regression'
                    value={'schema':'graphite.native64-loading.comparison.v1','planSha256':plan_ref['sha256'],
                           'engine':'native','operation':'loading','passed':passed,'status':'PASS' if passed else 'FAIL',
                           'coreComparabilityVerified':True,'comparabilityPending':False}
                    Path(str(loading_prefix)+'-status.json').write_text(json.dumps(value))
                    Path(str(loading_prefix)+'-report.md').write_text('independent raw loading comparison')
                    return SimpleNamespace(returncode=0 if passed else 1,stdout='',stderr='')
                passed=mode!='regression'
                Path(str(prefix)+'-status.json').write_text(json.dumps({'schema':'graphite.multigraph-pressure.comparison.v1',
                    'planSha256':plan_ref['sha256'],'engine':'native','operation':'query','passed':passed,
                    'status':'PASS' if passed else 'FAIL','coreComparabilityVerified':True,'comparabilityPending':False}))
                Path(str(prefix)+'-report.md').write_text('raw comparator report')
                if mode=='post-join-drift':Path(control['path']).write_text('changed')
                return SimpleNamespace(returncode=0 if passed else 1,stdout='raw stdout',stderr='')
            stack.enter_context(patch.object(r.subprocess,'run',side_effect=compare))
            stack.enter_context(patch.object(r.shutil,'which',return_value='/usr/bin/node'))
            stack.enter_context(patch('subprocess.Popen',side_effect=AssertionError('no server permitted')))
            original_reports={}
            if measurement_reports:
                loading_prefix.parent.mkdir(parents=True,exist_ok=True)
                for suffix in ('-status.json','-report.md','-comparison.stdout.log','-comparison.stderr.log'):
                    path=Path(str(loading_prefix)+'-measurement'+suffix)
                    path.write_text(json.dumps({'status':r.PENDING,'passed':False,'planSha256':plan_ref['sha256']})
                                    if suffix=='-status.json' else 'original measured '+suffix)
                    original_reports[path]=path.read_bytes()
            if stale_loading is not None:
                path=Path(str(loading_prefix)+stale_loading);path.parent.mkdir(parents=True,exist_ok=True)
                if stale_loading=='-status.json':path.symlink_to(root/'missing-original-status')
                else:path.write_text('original final output')
                with self.assertRaisesRegex(ValueError,'fresh separate Native loading report prefix'):
                    r.finalize(prep,out,prefix,Path(corrected_ref['path']),loading_prefix)
                self.assertEqual([],events)
                self.assertFalse(Path(str(prefix)+'-status.json').exists())
                return
            result=r.finalize(prep,out,prefix,Path(corrected_ref['path']),loading_prefix)
            self.assertEqual(original_reports,{path:path.read_bytes() for path in original_reports})
            self.assertEqual(original,{str(p):p.read_bytes() for parent in (prep,out) for p in parent.rglob('*') if p.is_file()})
            self.assertEqual(result,r.pressure.read(Path(str(prefix)+'-finalization.json')))
            verdict=r.pressure.read(Path(str(prefix)+'-status.json'))
            if mode=='post-join-drift':
                raw=r.pressure.read(Path(str(prefix)+'-comparison-original-status.json'))
                self.assertTrue(raw['passed']);self.assertFalse(verdict['passed'])
            if loading_prefix is not None:self.loading_verdict=r.pressure.read(str(loading_prefix)+'-status.json')
            return result,events,verdict

    def test_downloaded_loading_measurement_reports_survive_pending_to_final_join(self):
        result,events,verdict=self.exercise(loading_mode='pass',measurement_reports=True)
        self.assertTrue(result['performanceAcceptance']);self.assertTrue(verdict['passed'])
        self.assertTrue(self.loading_verdict['passed'])
        self.assertEqual(6,sum(kind=='audit' for kind,_ in events))

    def test_actual_loading_outputs_including_dangling_link_are_never_overwritten(self):
        for suffix in ('','-status.json','-report.md','-comparison.stdout.log','-comparison.stderr.log',
                       '-original-status.json','-original-report.md'):
            with self.subTest(suffix=suffix):self.exercise(loading_mode='pass',stale_loading=suffix)

    def test_join_reaudits_six_original_cells_and_preserves_every_measurement_byte(self):
        result,events,verdict=self.exercise()
        self.assertEqual([('audit',str(i)) for i in range(6)]+[('core',None),('compare',None)],events)
        self.assertTrue(result['performanceAcceptance']);self.assertTrue(verdict['passed'])

    def test_missing_or_failed_core_cannot_compare_or_accept(self):
        for mode in ('missing-core','failed-core'):
            with self.subTest(mode=mode):
                result,events,verdict=self.exercise(mode)
                self.assertEqual('FAIL',result['status']);self.assertNotIn(('compare',None),events)
                self.assertFalse(verdict['passed']);self.assertIn(mode,result['errors'][0])

    def test_input_mismatch_or_incomplete_original_cells_cannot_join(self):
        for mode in ('different-producer','changed-cell','missing-cell','execution-failed','raw-body-failed'):
            with self.subTest(mode=mode):
                result,events,verdict=self.exercise(mode)
                self.assertEqual('FAIL',result['status']);self.assertFalse(verdict['passed'])
                self.assertNotIn(('compare',None),events)

    def test_core_join_does_not_override_original_performance_constraints(self):
        result,events,verdict=self.exercise('regression')
        self.assertEqual('FAIL',result['status']);self.assertFalse(result['performanceAcceptance'])
        self.assertEqual([],result['errors']);self.assertEqual(('compare',None),events[-1])

    def test_post_comparison_drift_keeps_raw_verdict_but_final_result_fails(self):
        result,events,verdict=self.exercise('post-join-drift')
        self.assertEqual('FAIL',result['status']);self.assertFalse(result['performanceAcceptance'])
        self.assertIn('joined input drift',result['errors'][0]);self.assertFalse(verdict['passed'])

    def test_native_loading_and_query_verdicts_are_independent_without_second_raw_replay(self):
        for query, loading in [('pass','pass'),('regression','pass'),('pass','regression'),('regression','regression')]:
            with self.subTest(query=query,loading=loading):
                result,events,verdict=self.exercise(query,loading)
                self.assertEqual(6,sum(kind=='audit' for kind,_ in events))
                self.assertEqual(query=='pass',verdict['passed'])
                self.assertEqual(loading=='pass',self.loading_verdict['passed'])
                self.assertEqual(query==loading=='pass',result['performanceAcceptance'])
                self.assertEqual([('compare',None),('loading-compare',None)],events[-2:])

    def test_missing_core_or_post_join_drift_cannot_leave_native_loading_pass(self):
        for mode in ('missing-core','failed-core','different-producer','post-join-drift'):
            result,events,verdict=self.exercise(mode,'pass')
            self.assertFalse(result['performanceAcceptance']);self.assertFalse(verdict['passed'])
            self.assertFalse(self.loading_verdict['passed']);self.assertEqual('UNAVAILABLE',self.loading_verdict['status'])

if __name__=='__main__':unittest.main()

import contextlib,json,tempfile,unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import run_prepared_native_pressure as r


class Execution(unittest.TestCase):
    def exercise(self,mode='pass',preparation_status=None,engine='native'):
        with tempfile.TemporaryDirectory() as folder,contextlib.ExitStack() as stack:
            root=Path(folder);prep=root/'preparation';prep.mkdir();output=root/'execution';prefix=root/'reports/native-query'
            plan={'engine':'native' if mode=='wrong-engine' else engine,'operation':'query','cells':[{'id':str(i),'arm':arm} for i,arm in enumerate('CABBAC')]}
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
                events.append(('compare',None));self.assertEqual(kwargs['timeout'],60);self.assertFalse(kwargs['check'])
                passed=mode not in ('regression','bad-verdict')
                prefix.parent.mkdir(parents=True,exist_ok=True)
                Path(str(prefix)+'-status.json').write_text(json.dumps({'schema':'graphite.multigraph-pressure.comparison.v1','engine':'native' if mode=='wrong-verdict-engine' else engine,'operation':'query','passed':passed,'status':'PASS' if passed else 'FAIL','planSha256':digest}))
                Path(str(prefix)+'-report.md').write_text('actual comparison test verdict')
                return SimpleNamespace(returncode=0 if mode=='bad-verdict' or passed else 1,stdout='done',stderr='')
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
            result=r.execute(prep,output,prefix,engine=engine)
            stored=json.loads((output/'execution.json').read_text());self.assertEqual(result,stored)
            verdict=json.loads(Path(str(prefix)+'-status.json').read_text())
            self.assertFalse(result['otherOperationsEligible'])
            return result,verdict,events

    def test_all_six_cells_run_then_audit_sequentially_before_comparison(self):
        result,verdict,events=self.exercise()
        self.assertEqual(events,[(kind,str(i)) for i in range(6) for kind in ('run','audit')]+[('compare',None)])
        self.assertTrue(result['performanceAcceptance']);self.assertTrue(verdict['passed']);self.assertEqual(result['unissued'],[])
        self.assertTrue(all(c['status']=='PASS' and c['auditSha256'] for c in result['cells']))

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
        self.assertEqual([(kind,str(i)) for i in range(6) for kind in ('run','audit')]+[('compare',None)],events)
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

if __name__=='__main__':unittest.main()

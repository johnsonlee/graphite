"""Tiny Native-loading boundary contracts; no servers or performance samples."""
import copy
from contextlib import ExitStack
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import native_loading as loading
import multigraph_pressure as pressure
import prepare_native_pressure_plan as preparation
from test_real64_loading import fixture, sample


def record_fixture():
    record, samples = fixture()
    record.update(schema='graphite.native64-loading.cell.v1',graphCount=64,queriesIssuedDuringLoading=0,
                  initialSample=samples[0])
    stages={name:{'cpuStart':sample(3000000000,3000000010),
                  'requests':[{'startNs':3100000000+i} for i in range(n)]}
            for name,n in [('oracle',39),('warmup',78),('pressure',780)]}
    return record, {'readyNs':2000000100,'stages':stages}, samples, {'peakRssBytes':999999999}


class NativeLoading(unittest.TestCase):
    def test_original_startup_cpu_and_ready_rss_exclude_later_query_peak(self):
        record,result,samples,lifecycle=record_fixture()
        samples.append(sample(4_000_000_000,4_000_000_010,500,900000))
        observed=loading.validate_record(record,result,samples,lifecycle,2002)
        self.assertEqual(1000,observed['wallMs']);self.assertEqual([.98,1.12],observed['cpuBoundsSeconds'])
        self.assertEqual([1048576,2097152],observed['rssBoundsBytes'])
        lifecycle['peakRssBytes'] *= 2
        self.assertEqual(observed,loading.validate_record(record,result,samples,lifecycle,2002))

    def test_lower_lifetime_rss_is_preserved_without_changing_ready_resources(self):
        record,result,samples,lifecycle=record_fixture()
        expected=loading.validate_record(record,result,samples,lifecycle,2002)
        lifecycle['peakRssBytes']=record['readyWatermark']['peakRssBytes']-36*1024
        self.assertEqual(expected,loading.validate_record(record,result,samples,lifecycle,2002))
        self.assertEqual(2060288,lifecycle['peakRssBytes'])
        for invalid in (0,-1,True,1.5,float('inf')):
            lifecycle['peakRssBytes']=invalid
            with self.subTest(invalid=invalid),self.assertRaisesRegex(ValueError,'positive lifetime RSS'):
                loading.validate_record(record,result,samples,lifecycle,2002)

    def test_missing_or_forged_identity_watermark_and_early_query_reject(self):
        for kind in ('missing','pid','start','reused','forged','duplicate','queryCount','oracle','warmup','pressure'):
            record,result,samples,lifecycle=record_fixture()
            if kind=='missing': del record['readyWatermark']
            if kind=='pid': record['initialSample']['pid']+=1
            if kind=='start': record['startNs']=record['readyNs']+1
            if kind=='reused':record['readyWatermark']['identityAfter']['startTicks']+=1
            if kind=='forged':record['readyWatermark']['peakRssBytes']+=1
            if kind=='duplicate':record['readyWatermark']['raw']+='VmHWM:\t2048 kB\n'
            if kind=='queryCount':record['queriesIssuedDuringLoading']=1
            if kind in result['stages']:result['stages'][kind]['requests'][-1]['startNs']=2000000059
            with self.subTest(kind=kind),self.assertRaises((KeyError,ValueError)):
                loading.validate_record(record,result,samples,lifecycle,2002)

    def test_declaration_is_prospective_linux_only_and_darwin_query_shape_survives(self):
        catalog={'engines':{'native':{'coveredFamilies':['f'],'requiredFamilies':['f']}}}
        for platform,present in [('linux',True),('darwin',False)]:
            with patch.object(preparation.sys,'platform',platform),patch.object(preparation.pressure,'sha',return_value='f'*64):
                plan=preparation.pressure_plan({'pins':{},'arms':{}},[],{},[],catalog,
                                               pendingComparabilityAuthority={'path':'/pending','sha256':'f'*64},comparabilityPending=True)
            self.assertEqual(present,'loadingMeasurement' in plan)
            self.assertEqual('query',plan['operation']);self.assertEqual('native',plan['engine'])
            self.assertEqual(list('CABBAC'),[c['arm'] for c in plan['cells']])
        with self.assertRaisesRegex(ValueError,'declaration'):loading.validate_declaration(plan)
        with patch.object(preparation.sys,'platform','linux'),patch.object(preparation.pressure,'sha',return_value='f'*64):
            strict=preparation.pressure_plan({'pins':{},'arms':{}},[],{},[],catalog,correctedProducerAuthority={})
        self.assertNotIn('loadingMeasurement',strict)

    def test_raw_audit_reuses_supplied_stream_and_never_reads_response_bodies(self):
        record,result,samples,lifecycle=record_fixture();record['resources']=loading.validate_record(record,result,samples,lifecycle,2002)
        result['loading']=record
        graphs=[{'id':str(i)} for i in range(64)]
        plan={'engine':'native','operation':'query','loadingMeasurement':copy.deepcopy(loading.DECLARATION),
              'arms':{name:{'graphs':graphs,'readiness':{'expected':{'count':64}}} for name in 'CAB'}}
        with patch.object(loading.common,'sha',return_value='f'*64) as digest, \
             patch.object(Path,'read_bytes',side_effect=AssertionError('no additional body or stream read')):
            audit=loading.audit(plan,result,Path('/raw'),samples,lifecycle,2002)
        self.assertEqual(6,digest.call_count);self.assertEqual(record['resources'],audit['resources'])
        self.assertEqual('PASS_RAW_NATIVE64_LOADING_AUDIT',audit['status'])

    def test_capture_is_inside_existing_process_before_all_query_stages(self):
        from test_multigraph_pressure import make_plan
        plan=make_plan('native');plan['loadingMeasurement']=copy.deepcopy(loading.DECLARATION)
        cell=plan['cells'][0];events=[]
        initial=sample(1100000000,1100000010,25,pid=1002)
        after=sample(2000000010,2000000020,100,pid=1002)
        high={'pid':1002,'startTicks':3000,'readStartNs':2000000030,'readEndNs':2000000040,
              'raw':'Pid:\t1002\nVmHWM:\t2048 kB\n','peakRssBytes':2097152,
              'identityAfter':sample(2000000050,2000000060,101,pid=1002)}
        samples=[initial,sample(1500000000,1500000010,100,pid=1002)]
        proc=MagicMock(pid=1001);proc.poll.return_value=None
        monitor=SimpleNamespace(samples=samples,thread=SimpleNamespace(start=lambda:events.append('monitor')),finish=lambda:None)
        def launch(command,**kwargs):
            events.append('launch');Path(command[command.index('--pid-file')+1]).write_text('1002');return proc
        def clock():
            if not events:events.append('setup-clock');return 100
            if events[-1]=='ready':events.append('ready-clock');return 2000000000
            if events[-1]=='watermark':return 2000000100
            return 1000000000 if 'launch' not in events else 5000000000
        def ready(*args):events.append('ready');return b'complete readiness body already validated'
        def watermark(*args):events.append('watermark');return high
        def stage(*args,**kwargs):
            self.assertIn('watermark',events);events.append('stage')
            return {'status':'PASS','requests':[]}
        with tempfile.TemporaryDirectory() as temp,ExitStack() as stack:
            root=Path(temp);file=root/'plan.json';pressure.save(file,plan)
            stack.enter_context(patch.object(pressure.sys,'platform','linux'))
            stack.enter_context(patch.object(pressure,'verify_inputs',return_value={'status':'PASS','pins':plan['pins']}))
            stack.enter_context(patch.object(pressure,'compile_response_validator'))
            stack.enter_context(patch.object(pressure.socket,'socket'))
            stack.enter_context(patch.object(pressure.subprocess,'Popen',side_effect=launch))
            stack.enter_context(patch.object(pressure.os,'getpgid',return_value=1001))
            sample_call=stack.enter_context(patch.object(pressure,'proc_sample',side_effect=[initial,after]))
            stack.enter_context(patch.object(pressure,'Monitor',return_value=monitor))
            stack.enter_context(patch.object(pressure,'readiness',side_effect=ready))
            stack.enter_context(patch.object(loading,'ready_watermark',side_effect=watermark))
            stack.enter_context(patch.object(pressure,'HTTPTransport'))
            stages=stack.enter_context(patch.object(pressure,'run_stage',side_effect=stage))
            stack.enter_context(patch.object(pressure,'resource_summary',return_value={}))
            stack.enter_context(patch.object(pressure,'evidence_eligible',return_value=False))
            stack.enter_context(patch.object(pressure,'stop_owned',return_value={'group':1001,'errors':[],'after':[],'signals':[],'exit':143}))
            stack.enter_context(patch.object(pressure,'lifecycle_time',return_value={'peakRssBytes':999999999}))
            stack.enter_context(patch.object(pressure.time,'perf_counter_ns',side_effect=clock))
            result=pressure._run_cell(file,cell['id'],root/'out',plan)
        self.assertEqual('PASS',result['status'],result['errors'])
        self.assertEqual(1,events.count("launch"));self.assertEqual(4,stages.call_count);self.assertEqual(2,sample_call.call_count)
        self.assertLess(events.index('ready-clock'),events.index('watermark'));self.assertLess(events.index('watermark'),events.index('stage'))
        self.assertEqual(1000000000,result['loading']['startNs']);self.assertEqual(2000000000,result['loading']['readyNs'])
        self.assertEqual(2000000100,result['readyNs']);self.assertEqual(initial,result['loading']['initialSample'])


    def test_workflow_reuses_native_measurement_job_and_requires_separate_final_loading(self):
        workflow=(Path(__file__).parents[1]/'workflows/benchmark.yml').read_text()
        measured=workflow.split('  rust-query-native-measurement:',1)[1].split('  rust-query-native:',1)[0]
        final=workflow.split('  rust-query-native:',1)[1].split('  rust-query-legacy:',1)[0]
        self.assertEqual(1,measured.count('run_prepared_native_pressure.py'))
        self.assertIn('--loading-prefix benchmark-results/multigraph-native-loading-measurement',measured)
        self.assertIn('--loading-prefix benchmark-results/multigraph-native-loading',final)
        self.assertIn("jq -e '.passed == true' benchmark-results/multigraph-native-loading-status.json",final)
        self.assertIn('benchmark-results/multigraph-native-loading*',final)
        self.assertNotIn('run_real64_loading.py',measured+final)
        self.assertNotIn('rust-native-loading:',workflow)

if __name__=='__main__':unittest.main()

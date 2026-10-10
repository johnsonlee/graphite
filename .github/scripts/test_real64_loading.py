"""Tiny raw fixtures verify loading boundaries; no JVM or timing benchmark runs."""
import copy
from contextlib import ExitStack
import json
from pathlib import Path
import signal
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

import run_real64_loading as runner
import audit_real64_loading as auditor
p = runner.common


def sample(start, end, ticks=100, pages=256, pid=2002, identity=3000):
    tail = ['0']*22; tail[0] = 'S'
    for index, value in ((11,ticks),(12,0),(19,identity),(21,pages)): tail[index] = str(value)
    return {'backend':'linux-proc','pid':pid,'startTicks':identity,'ticksPerSecond':100,'pageSize':4096,
            'userTicks':ticks,'systemTicks':0,'cpuSeconds':ticks/100,'rssBytes':pages*4096,
            'readStartNs':start,'readEndNs':end,'raw':str(pid)+' (java worker) '+' '.join(tail)}


def fixture():
    after = sample(2000000010,2000000020,110)
    high = {'pid':2002,'startTicks':3000,'readStartNs':2000000030,'readEndNs':2000000040,
            'raw':'Name:\tjava\nPid:\t2002\nVmHWM:\t2048 kB\n','peakRssBytes':2097152,
            'identityAfter':sample(2000000050,2000000060,112)}
    record = {'startNs':1000000000,'readyNs':2000000000,'cpuAfterReady':after,'readyWatermark':high}
    return record, [sample(1100000000,1100000010,25),sample(1500000000,1500000010),
                    sample(1999999999,2000000001,109,384)]


class LoadingTests(unittest.TestCase):
    def setUp(self):
        tmp=tempfile.TemporaryDirectory();self.addCleanup(tmp.cleanup);self.root=Path(tmp.name).resolve()
        self.no_child=patch('subprocess.Popen',side_effect=AssertionError('real children prohibited'))
        self.no_child.start();self.addCleanup(self.no_child.stop)

    def test_cpu_starts_at_zero_and_straddling_sample_is_excluded(self):
        record,samples=fixture()
        expected={'wallMs':1000,'cpuBoundsSeconds':[.98,1.12],'rssBoundsBytes':[1048576,2097152],
                  'cpuQuantizationSeconds':.02,'insideSamples':2,'excludedSamples':1,
                  'cpuReadAfterReadyNs':20,'rssReadAfterReadyNs':40}
        self.assertEqual(expected,runner.bounds(record['startNs'],record['readyNs'],record['cpuAfterReady'],samples,record['readyWatermark']))
        self.assertEqual(expected,auditor.reconstruct(record,samples))
        # Subtracting the first sample would incorrectly remove 0.25s startup CPU.
        self.assertEqual(.98,expected['cpuBoundsSeconds'][0])
        record['lifecycle']={'peakRssBytes':999999999}
        self.assertEqual(expected,auditor.reconstruct(record,samples))

    def test_forged_watermark_duplicate_fields_and_pid_reuse_reject(self):
        for change in ('forged','duplicate','malformedDuplicate','pid','reuse','rawticks','units','boundary'):
            record,samples=fixture();high=record['readyWatermark']
            if change=='forged': high['peakRssBytes']+=1024
            elif change=='duplicate': high['raw']+='VmHWM:\t2048 kB\n'
            elif change=='malformedDuplicate': high['raw']+='VmHWM:\tbroken\n'
            elif change=='pid': high['raw']=high['raw'].replace('2002','2003')
            elif change=='reuse': high['identityAfter']=sample(2000000050,2000000060,112,identity=3001)
            elif change=='rawticks': samples[1]['cpuSeconds']=.01
            elif change=='units': high['raw']=high['raw'].replace('kB','MB')
            elif change=='boundary': record['cpuAfterReady']['readStartNs']=1999999999
            with self.subTest(change=change),self.assertRaises(ValueError): auditor.reconstruct(record,samples)

    def make_plan(self):
        graphs=[{'id':f'g{i}','path':f'/saved/g{i}'} for i in range(64)]
        expected={'graphs':[{'id':g['id']} for g in graphs],'count':64}
        arms={name:{'revision':name.lower()*40,'serverJar':{'path':'/sealed/graphite.jar','sha256':'a'*64},
                    'serverArgv':['/jdk/java','-Xmx4g','-XX:ActiveProcessorCount=4','-cp','/sealed/graphite.jar',
                                  'io.johnsonlee.graphite.cli.MainKt','serve'],
                    'graphs':graphs,'readiness':{'path':'/api/graphs','expected':expected}} for name in 'CAB'}
        return {'schema':runner.SCHEMA,'arms':arms,'pins':{},'python':'/python','producers':{},
                'comparisonModel':'complete-core-topology-index-with-source-corrections-and-additive-declarations-v1',
                'limits':{'rssIntervalSeconds':.01,'readinessSeconds':900},
                'cells':[{'id':f'0{i+1}-{name}','arm':name,'port':0} for i,name in enumerate('CABBAC')]}

    def execute(self, failure=None):
        plan=self.make_plan();file=self.root/'plan.json';file.write_text(json.dumps(plan))
        processes=[]
        def launch(argv,**kwargs):
            out=Path(argv[8]).parent;number=len(processes);group=2001+10*number;pid=group+1
            (out/'server.pid').write_text(str(pid))
            (out/'time-v.log').write_text('User time (seconds): 2.00\nSystem time (seconds): 0.50\nElapsed (wall clock) time (h:mm:ss or m:ss): 0:03.00\nMaximum resident set size (kbytes): 4096\n')
            proc=SimpleNamespace(pid=group,poll=lambda:None);processes.append(proc);return proc
        def proc_sample(pid,identity=None):
            return sample(1100000000,1100000010,25,pid=pid) if identity is None else sample(2000000010,2000000020,110,pid=pid)
        def watermark(pid,identity):
            high=fixture()[0]['readyWatermark'];high['pid']=pid;high['raw']=high['raw'].replace('2002',str(pid));high['identityAfter']['pid']=pid
            high['identityAfter']['raw']=high['identityAfter']['raw'].replace('2002',str(pid));return high
        def monitor(fn,interval,path):
            rows=fixture()[1];pid=processes[-1].pid+1
            for row in rows: row['pid']=pid;row['raw']=row['raw'].replace('2002',str(pid))
            Path(path).write_text(''.join(json.dumps(r)+'\n' for r in rows))
            return SimpleNamespace(samples=rows,thread=SimpleNamespace(start=lambda:None),finish=lambda:None)
        def ready(port,arm,data,*args):
            if failure=='interrupt': signal.getsignal(signal.SIGTERM)(signal.SIGTERM,None)
            if failure: raise ValueError('readiness failure')
            value=copy.deepcopy(arm['readiness']['expected']);value['data']=str(data)
            for graph in value['graphs']:graph['loadedAt']='2026-10-10T00:00:00Z'
            raw=json.dumps(value).encode();p.validate_readiness_body(raw,arm,data);return raw
        cleanups=[]
        def stop(proc,pid,identity):
            cleanups.append(pid)
            return {'group':proc.pid,'exit':143,'after':[],'errors':[],'signals':[{'signal':'SIGTERM'}]}
        with ExitStack() as stack:
            for obj,name,value in [(runner,'validate',lambda x:x),(runner,'verify_inputs',lambda _: {'pins':{}}),
                    (runner.subprocess,'Popen',launch),(runner.os,'getpgid',lambda pid:pid-1),
                    (p,'proc_sample',proc_sample),(p,'Monitor',monitor),(p,'readiness',ready),
                    (p,'stop_owned',stop),(runner,'ready_watermark',watermark)]:stack.enter_context(patch.object(obj,name,side_effect=value))
            stack.enter_context(patch.object(runner.sys,'platform','linux'))
            stack.enter_context(patch.object(runner.time,'perf_counter_ns',side_effect=[1000000000,2000000000]*6))
            result=runner.run(file,self.root/'cells')
            audit=None
            if not failure:audit=auditor.audit(file,self.root/'cells')
        return plan,result,audit,cleanups

    def test_complete_fixed_six_processes_audit_readiness_and_never_queries(self):
        plan,result,audit,cleanups=self.execute()
        self.assertEqual('PASS_ALL_SIX_ZERO_QUERY_READY64',result['status']);self.assertEqual([],result['unissued'])
        self.assertEqual(list('CABBAC'),[r['cell']['arm'] for r in result['cells']]);self.assertEqual(6,len(set(cleanups)))
        self.assertEqual('PASS_RAW_ZERO_QUERY_LOADING_AUDIT',audit['status'])
        self.assertEqual([1048576,2097152],audit['rows'][0]['resources']['rssBoundsBytes'])
        record=p.read(self.root/'cells/01-C/result.json');self.assertEqual(4194304,record['lifecycle']['peakRssBytes'])
        self.assertEqual(0,record['queriesIssued']);self.assertTrue(all(audit[k] is False for k in runner.FALSE))
        # Preserve raw hashes and summary agreement: independent raw replay still rejects forgery.
        record['readyWatermark']['raw']+='Pid:\t2002\n';path=self.root/'cells/01-C/result.json';path.write_text(json.dumps(record))
        execution=p.read(self.root/'cells/execution.json');execution['cells'][0]['resultSha256']=p.sha(path)
        (self.root/'cells/execution.json').write_text(json.dumps(execution))
        with patch.object(runner,'validate',side_effect=lambda x:x),self.assertRaisesRegex(ValueError,'original ready VmHWM'):
            auditor.audit(self.root/'plan.json',self.root/'cells')

    def test_readiness_failure_keeps_cleanup_and_five_unissued(self):
        _,result,_,cleanups=self.execute('failure')
        self.assertEqual('FAIL',result['status']);self.assertEqual([2002],cleanups)
        self.assertEqual(['02-A','03-B','04-B','05-A','06-C'],result['unissued'])
        record=p.read(self.root/'cells/01-C/result.json');self.assertIn('readiness failure',record['errors'][0])
        self.assertEqual([],record['cleanup']['after'])

    def test_signal_is_caught_cleanup_saved_and_handlers_restored(self):
        old={sig:signal.getsignal(sig) for sig in (signal.SIGINT,signal.SIGTERM)}
        _,result,_,cleanups=self.execute('interrupt')
        self.assertEqual('FAIL',result['status']);self.assertEqual([2002],cleanups)
        self.assertEqual(5,len(result['unissued']));self.assertEqual(old,{sig:signal.getsignal(sig) for sig in old})
        self.assertIn('InterruptedError',p.read(self.root/'cells/01-C/result.json')['errors'][0])

    def test_packaged_entry_requires_main_serve_options_and_dependencies(self):
        jar=self.root/'tiny.jar'
        names=['io/johnsonlee/graphite/cli/'+n+'.class' for n in ('MainKt','GraphiteCommand','ServeCommand','GraphRegistry','ExploreRoutes')]
        names+=['picocli/CommandLine.class','io/javalin/Javalin.class']
        def write_jar(missing=None):
            with zipfile.ZipFile(jar,'w') as z:
                for name in names:
                    if name!=missing:z.writestr(name,b'Lio/johnsonlee/graphite/cli/ServeCommand; --graph --data --port --load-mode --max-concurrent-cypher --cypher-max-timeout-ms --metrics')
        write_jar()
        with patch.object(runner,'parse_class',return_value={'methods':[{'name':'main','descriptor':'([Ljava/lang/String;)V','accessFlags':9}]}):
            self.assertEqual(set(names),set(runner.entrypoint(jar)))
            write_jar(names[2])
            with self.assertRaisesRegex(ValueError,'entrypoint'):runner.entrypoint(jar)


if __name__=='__main__':unittest.main()

#!/usr/bin/env python3
"""Fixed12 unprofiled JVM replays: both reset states ×3 B/C pairs, all38 queries."""
from common import *
import argparse,csv,statistics,traceback

RESET_STATES=('per-query-cold','replay-cold')
PAIR_ORDER=(('base','candidate'),('candidate','base'),('base','candidate'))
def protocol():
    return [{'resetMode':mode,'pair':pair,'side':side,'id':f'{mode}-pair-{pair}-{side}'} for mode in RESET_STATES for pair,sides in enumerate(PAIR_ORDER,1) for side in sides]
def compare_pairs(observations):
    result={}
    for mode in RESET_STATES:
        rows=[]
        for index in range(38):
            pairs=[];non_time=[]
            for pair in range(1,4):
                b=observations[f'{mode}-pair-{pair}-base'][index];c=observations[f'{mode}-pair-{pair}-candidate'][index]
                require(b['id']==c['id'],'Pair query identity differs')
                require(set(b)==set(c),'Pair observation fields differ')
                bv,cv=int(b['latencyNanos']),int(c['latencyNanos'])
                pairs.append({'pair':pair,'baseLatencyNanos':bv,'candidateLatencyNanos':cv,'candidateOverBase':cv/bv,'deltaNanos':cv-bv})
                non_time.append({'pair':pair,'differences':{k:{'base':b[k],'candidate':c[k]} for k in b if k!='latencyNanos' and b[k]!=c[k]}})
            rows.append({'ordinal':index+1,'id':b['id'],'sampleCountPerSide':3,'pairs':pairs,'baseMedianLatencyNanos':statistics.median(p['baseLatencyNanos'] for p in pairs),'candidateMedianLatencyNanos':statistics.median(p['candidateLatencyNanos'] for p in pairs),'empiricalP95LatencyNanos':None,'allNonTimeDifferences':non_time})
        result[mode]=rows
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--prepared',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    require_linux();check_sources();a.prepared=a.prepared.resolve();a.output=a.output.resolve()
    require(not a.output.exists(),'Fresh paired output required');a.output.mkdir(parents=True);out=a.output
    setup=read(a.prepared);require(setup['status']=='complete' and setup['graphInputsUnchanged'] is True,'Incomplete/changed setup')
    require(setup['sourcePinsSha256']==sha(ROOT/'pins.json'),'Preparation source pins differ')
    require(setup['productionRefs']=={s:read(ROOT/'pins.json')[s] for s in ('base','candidate')},'Production refs differ')
    state={'schema':'graphite-multi-linux-paired-v1','status':'running','performanceAcceptance':False,'profilingCompleted':False,'queryCount':38,'samplesPerQueryPerSidePerResetState':3,'perQueryP95':None,'protocol':protocol(),'completed':[],'preparedReceiptSha256':sha(a.prepared),'runnerCpuCount':os.cpu_count(),'jvmArgs':['-Xmx8g','-XX:ActiveProcessorCount=4'],'coldMeaning':{'per-query-cold':'Call unchanged setupInvocation before each of38 queries; fresh JVM each replay.','replay-cold':'Call unchanged setupInvocation before the first query only; remaining37 retain indexes and do not repeat its other setup operations in that fresh JVM.','setupInvocation':'Clears engine string indexes, resets CallSite scan metrics, calls System.gc and System.runFinalization then sleeps100ms in each of3 iterations, and calls sampler.start to reset peaks and enable sampling. GC calls are requests, not proof of completed collections.','timingBoundary':'All setupInvocation operations precede that query timer; whole-process CPU/RSS/elapsed observations include setup and its continuing effects.','crossStateLimit':'Changing reset state changes index retention, explicit GC/finalization/sleep frequency, metrics resets and sampler resets together; cross-state differences cannot be attributed solely to index caching. Within each state both B/C use the identical adapter protocol.','both':'No JIT warmup added; OS page cache is not reset. Build/export/hash setup reads inputs before captures.'},'resourceMeaning':'GNU time user+system CPU and maximum RSS cover the entire JVM, including setup,38 queries,serialization and teardown; not per-query CPU/RSS. GNU time precision is retained.','commands':[]}
    def save():write(out/'run.json',state)
    before=None;observations={};save()
    try:
        require(Path('/usr/bin/time').is_file(),'GNU time required for per-process resource receipt')
        version=subprocess.check_output(['/usr/bin/time','--version'],stderr=subprocess.STDOUT,text=True);require('GNU' in version,'GNU time required');state['resourceToolVersion']=version
        unchanged(setup['boundInputs']);manifest=Path(setup['manifest']);oracle=Path(setup['oracle']);classes=Path(setup['classes'])
        compare_catalogs(oracle,ROOT/'frozen-v4')
        before=graph_identity(manifest);write(out/'graph-content-before.json',before)
        require(before==read(a.prepared.parent/'graph-content-after.json'),'Fixture changed after preparation')
        quick=identity([Path(setup['jars'][s]['path']) for s in ('base','candidate')]+list(classes.glob('*.class'))+[oracle/'catalog.json',oracle/'workloads.tsv',manifest,Path(setup['provenance'])])
        for item in state['protocol']:
            label=item['id'];prefix=out/label;unchanged(quick)
            boundary=no_java();jar=Path(setup['jars'][item['side']]['path'])
            command=[setup['java'],'-Xmx8g','-XX:ActiveProcessorCount=4','-cp',str(classes)+':'+str(jar),'MultiKeywordProfileRunner',str(manifest),str(oracle/'workloads.tsv'),str(prefix),'all',item['resetMode']]
            resource_path=Path(str(prefix)+'-resources.json')
            fmt='{"userSeconds":%U,"systemSeconds":%S,"elapsedSeconds":%e,"maxRssKiB":%M,"exitCode":%x}'
            timed=['/usr/bin/time','-f',fmt,'-o',str(resource_path),'--',*command]
            receipt={**item,'javaCommand':command,'executedCommand':timed,'beforeNoJava':boundary};state['commands'].append(receipt);save()
            write(Path(str(prefix)+'-command.json'),receipt)
            run_command(timed,Path(str(prefix)+'.log'),timeout=38*330+120)
            receipt['afterNoJava']=no_java()
            resource=read(resource_path);require(resource['exitCode']==0,'Resource command failed');receipt['processResources']=resource
            check=verify_run(oracle/'catalog.json',oracle/'workloads.tsv',prefix,expected_reset=item['resetMode']);write(Path(str(prefix)+'-reference-check.json'),check)
            require(check['passed'] and check['queryCount']==38 and check['verifiedFullRowsAndProvenance'],'Independent complete oracle check failed: '+str(check))
            observations[label]=read_tsv(str(prefix)+'.tsv');state['completed'].append(label);save()
        unchanged(setup['boundInputs']);unchanged(quick)
        results=compare_pairs(observations);write(out/'all38-paired.json',{'performanceAcceptance':False,'profilingCompleted':False,'states':results})
        with (out/'all38-paired.csv').open('w',newline='') as f:
            w=csv.writer(f);w.writerow(['resetMode','ordinal','id','pair','baseLatencyNanos','candidateLatencyNanos','candidateOverBase','deltaNanos'])
            for mode,rows in results.items():
                for row in rows:
                    for pair in row['pairs']:w.writerow([mode,row['ordinal'],row['id'],*[pair[k] for k in ['pair','baseLatencyNanos','candidateLatencyNanos','candidateOverBase','deltaNanos']]])
        state['artifactHashes']=identity([p for p in out.glob('*') if p.is_file() and p.name!='run.json']);state['status']='complete';save()
    except BaseException:state.update(status='failed',error=traceback.format_exc());save();raise
    finally:
        if before is not None:
            try:
                after=graph_identity(manifest);write(out/'graph-content-after.json',after);state['graphInputsUnchanged']=before==after
                unchanged(setup['boundInputs']);require(before==after,'Fixture changed during paired replay')
            except BaseException:
                state['integrityError']=traceback.format_exc()
                if state['status']=='complete':state['status']='failed';save();raise
            finally:save()
if __name__=='__main__':main()

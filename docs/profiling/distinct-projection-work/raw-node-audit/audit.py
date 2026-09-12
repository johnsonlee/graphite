from pathlib import Path
from collections import Counter
import json, hashlib, subprocess

ROOT=Path('/Users/johnsonlee/.codex/worktrees/ac7b5da2-2450-48c5-894c-5fd84ab6cb7d/graphite')
INPUT=Path('/private/tmp/graphite-distinct-phase-details')
OUT=Path(__file__).resolve().parent
FROZEN='4e328b0109e13c896b74004823fb049fcb19251a'
PREFIX='io.johnsonlee.graphite.webgraph.'
RAW=PREFIX+'MappedWebGraphBackedGraph.parallelRawDistinctCallSiteStringProjection'
APP=('broad-query-pressure-worker [','graphite-cypher-scan-','graphite-callsite-scan-','graphite-callsite-segment-')
JIT=('Java: C1 CompilerThread','Java: C2 CompilerThread')
SUFFIXES=('MappedWebGraphBackedGraph.kt','NodeTypeIndex.kt','NodeOffsetIndex.kt','StringTable.kt')

def sha(b): return hashlib.sha256(b).hexdigest()
def git(*args): return subprocess.check_output(['git','-C',str(ROOT),*args])
def group(t): return 'application' if t.startswith(APP) else 'jit' if t.startswith(JIT) else 'other'
def families(f):
 return {
  'perNodeCallback':f.startswith(RAW+'$lambda$32$lambda$31$lambda$30('),
  'nodeTypeTraversal':f.startswith(PREFIX+'MappedNodeTypeIndex.forEachIdWhile('),
  'nodeOffsetAddressing':f.startswith(PREFIX+'MappedNodeOffsetIndex.offset('),
  'exactSetContains':f.startswith('it.unimi.dsi.fastutil.ints.IntOpenHashSet.contains('),
  'exactSetHashMix':f.startswith('it.unimi.dsi.fastutil.HashCommon.mix('),
  'listGetOrIntUnbox':f.startswith(('java.util.ArrayList.get(','java.lang.Integer.intValue(')),
  'integerBox':f.startswith('java.lang.Integer.valueOf('),
  'selectedSetContains':f.startswith('java.util.HashSet.contains('),
  'rangeIterator':f.startswith(('kotlin.collections.CollectionsKt__CollectionsKt.getIndices(', 'kotlin.ranges.IntProgression.iterator(', 'kotlin.ranges.IntProgressionIterator.')),
  'mappedReadImplementation':f.startswith(('java.nio.DirectByteBuffer.getInt(', 'java.nio.DirectByteBuffer.getLong(', 'java.nio.DirectByteBuffer.ix(', 'jdk.internal.misc.ScopedMemoryAccess.getInt', 'jdk.internal.misc.ScopedMemoryAccess.getLong', 'jdk.internal.misc.Unsafe.getInt', 'jdk.internal.misc.Unsafe.getLong', 'jdk.internal.misc.Unsafe.convEndian(')),
 }
summary=json.loads((INPUT/'application-summary.json').read_text())
rows=[]; inputs=[]; conservation=0; summary_matches=0
for recording in range(1,4):
 path=INPUT/f'phase-{recording}.json'; data=json.loads(path.read_text())
 assert data['queryCount']==34 and data['phaseTraceCount']==192
 inputs.append({'path':str(path),'sha256':sha(path.read_bytes())})
 for q in data['queries']:
  for phase,metrics in q['metrics'].items():
   for metric,m in metrics.items():
    groups=Counter(); alltotal=0
    for t,st in m['threadStacks'].items():
     total=sum(st.values()); assert total==m['summary']['threads'][t]['weight']; alltotal+=total; groups[group(t)]+=total
    assert alltotal==m['summary']['weight']; conservation+=1
    stated=next((r for r in summary['rows'] if (r['recording'],r['query'],r['phase'],r['metric'])==(recording,q['id'],phase,metric)),None)
    if stated:
     assert stated['total']==alltotal
     for g,w in groups.items(): assert stated['counts'].get(g,0)==w
     summary_matches+=1
    short='targeted' if q['id'].endswith('distinct-targeted') else 'dense' if q['id'].endswith('distinct-dense') else None
    if not short or phase not in ('initial','provenance') or metric not in ('cpuSamples','allocationSampledBytes'): continue
    if not groups['application']: continue
    leaf=Counter(); inclusive=Counter(); categories={}; examples={}; rawtotal=0; pernodebox=0
    for t,st in m['threadStacks'].items():
     if group(t)!='application': continue
     for stack,w in st.items():
      fs=stack.split(';')
      if not any(f.startswith(RAW) for f in fs): continue
      rawtotal+=w; leaf[fs[-1]]+=w
      for f in set(fs): inclusive[f]+=w
      flags={}
      for f in fs:
       for k,v in families(f).items(): flags[k]=flags.get(k,False) or v
      for k,yes in flags.items():
       c=categories.setdefault(k,{'inclusive':0,'leaf':0})
       if yes:
        c['inclusive']+=w
        if families(fs[-1])[k]: c['leaf']+=w
        if k not in examples: examples[k]={'thread':t,'weight':w,'stack':stack}
      if families(fs[-1])['integerBox'] and flags['perNodeCallback']: pernodebox+=w
    assert sum(leaf.values())==rawtotal
    if stated: assert stated['counts'].get('applicationRawProjectionInclusive',0)==rawtotal
    rows.append({'recording':recording,'query':short,'queryId':q['id'],'phase':phase,'metric':metric,'allThreadsWeight':alltotal,'threadClassWeights':dict(groups),'applicationRawInclusive':rawtotal,'categories':categories,'integerBoxLeafUnderPerNodeCallback':pernodebox,'rawLeafFrames':dict(leaf.most_common()),'rawInclusiveFrames':dict(inclusive.most_common()),'categoryExampleStacks':examples})
sources=[]
for name in SUFFIXES:
 p='graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/'+name
 frozen=git('show',FROZEN+':'+p); current=(ROOT/p).read_bytes(); assert frozen==current
 sources.append({'path':p,'sha256':sha(current),'byteEqualFrozenMain':True})
output={'result':'verified read-only source and existing serialized samples','head':git('rev-parse','HEAD').decode().strip(),'frozenMain':FROZEN,'sourceFiles':sources,'inputs':inputs,'applicationSummarySha256':sha((INPUT/'application-summary.json').read_bytes()),'metricWeightPartitionsVerified':conservation,'applicationSummaryRowsVerified':summary_matches,'applicationThreadPrefixes':APP,'jitThreadPrefixes':JIT,'scope':'Existing phase threadStacks only; no JFR re-decoding, Java, new measurement, or production edit. Phase time partition is inherited, not reinterpreted as causality.','units':{'cpuSamples':'event/sample count, not time','allocationSampledBytes':'TLAB/outside-TLAB sampled byte weights, not precise allocated bytes or object counts'},'counting':'Each stack contributes once to each matching inclusive family. Inclusive families overlap and cannot be added. Leaf frames partition raw-family weight exactly. Per-node callback leaf includes all inlined source work; it does not isolate source lines.','rows':rows,'conclusion':'Repeated raw projection CPU presence is clear. More specific costs include primitive hash membership, mapped addressing/read chains, boxed-list access/unboxing, and selected-only projection tuple construction. Samples do not establish a new dominant cost, cache-miss cause, or speedup. Attempts086/136/138 already cover major tempting directions and remain rejected.'}
(OUT/'README.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
print('verified partitions',conservation,'summary rows',summary_matches)
for r in rows:
 if r['phase']=='initial' and r['query']=='targeted' or r['phase']=='provenance' and r['query']=='dense':
  print(r['recording'],r['query'],r['metric'],r['applicationRawInclusive'],{k:v for k,v in r['categories'].items() if v['inclusive']},'perNodeIntegerBox',r['integerBoxLeafUnderPerNodeCallback'])

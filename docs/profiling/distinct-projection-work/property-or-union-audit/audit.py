from pathlib import Path
from collections import Counter
import json,re,hashlib,subprocess
R=Path('/Users/johnsonlee/.codex/worktrees/ac7b5da2-2450-48c5-894c-5fd84ab6cb7d/graphite');P=Path(__file__).resolve().parent
files=['docs/wrapped-case-insensitive-query-optimization-attempts.md','docs/cross-graph-cypher-optimization-attempts.md','docs/webgraph-optimization-attempts.md']
patterns=[r'per.property',r'same.property',r'group.*predicat',r'predicat.*group',r'union.*string',r'union.*predicat',r'fuse',r'bitset',r'bit.?map',r'归并',r'同属性',r'shared.*match']
def sha(b):return hashlib.sha256(b).hexdigest()
search=[]
for f in files:
 b=(R/f).read_bytes();lines=b.decode().splitlines();hits=[{'line':i,'text':line} for i,line in enumerate(lines,1) if any(re.search(p,line,re.I) for p in patterns)]
 search.append({'path':f,'sha256':sha(b),'matches':hits})
workloads=[]
old=Path('/private/tmp/graphite-main-profiling-n50joikp/query-catalog.json');v3=Path('/private/tmp/graphite-main-profiling-n50joikp/oracle-v3/catalog.json')
for p in (old,v3):
 d=json.loads(p.read_text())
 for q in d['queries']:
  qid=q['id']
  if qid in ['global-wide-wrapped-case-insensitive-distinct-targeted','global-wide-wrapped-case-insensitive-distinct-dense'] or qid.startswith('or-four-') and qid.endswith('-distinct'):
   where=q['query'].split('WHERE',1)[1].split('RETURN',1)[0];counts=Counter(re.findall(r'n\.(caller_class|caller_name|callee_class|callee_name)',where));assert len(counts)==4
   workloads.append({'id':qid,'query':q['query'],'source':str(p),'querySha256':sha(q['query'].encode()),'predicateCountsByProperty':dict(counts),'totalPredicates':sum(counts.values()),'properties':len(counts),'maxChecksBeforeGrouping':sum(counts.values()),'maxChecksAfterGrouping':len(counts),'boundsNotMeasurements':True})
assert len(workloads)==8 and [w['totalPredicates'] for w in workloads[:2]]==[4,4] and all(w['totalPredicates']==16 for w in workloads[2:])
sources=['graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedWebGraphBackedGraph.kt','graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndexView.kt','graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndex.kt','graphite-core/src/main/kotlin/io/johnsonlee/graphite/graph/Graph.kt']
source_hashes=[]
for f in sources:
 b=(R/f).read_bytes();frozen=subprocess.check_output(['git','-C',str(R),'show','4e328b0109e13c896b74004823fb049fcb19251a:'+f]);assert b==frozen;source_hashes.append({'path':f,'sha256':sha(b),'byteEqualFrozenMain':True})
result={'scope':'Read-only feasibility/history/catalog audit; no implementation,Java,build,timing,capture or141.','searchPatterns':patterns,'historicalLogs':search,'sources':source_hashes,'catalogHashes':[{'path':str(p),'sha256':sha(p.read_bytes())} for p in (old,v3)],'workloadPredicateCounts':workloads,'historyConclusion':'No exact per-property union implementation found in searched logs/current source. Existing006 node fusion,053 exact-ID raw scans and056 same-key discovery reuse already retained;086/103/130 alternatives/136/138/140 impose non-repetition boundaries. Absence of a textual match is not proof no unarchived variant ever existed.','decision':'Exclude as the next candidate for frozen old34 P95/10x: both determining DISTINCT queries have one predicate per property, so grouping removes no membership checks or visited nodes. New profiling is not justified; optional future offline multi-term cardinality/short-circuit evidence is diagnostic only.'}
(P/'audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print('checked catalogs',len(workloads),'frozen sources',len(sources),'logs',len(files))

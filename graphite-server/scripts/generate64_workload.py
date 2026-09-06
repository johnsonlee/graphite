#!/usr/bin/env python3
"""Freeze real64 HTTP workloads derived from pinned repository benchmark sources."""
import argparse, hashlib, json, pathlib, re, subprocess
REVISION='4e328b0109e13c896b74004823fb049fcb19251a'
p=argparse.ArgumentParser();p.add_argument('--fixture-root',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
source_paths=['graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/'+n for n in ['AllFixtureWrappedDiscoveryLatencyBenchmark.kt','LargeBroadQueryPressureBenchmark.kt']]
sources={path:subprocess.check_output(['git','show',REVISION+':'+path],text=True) for path in source_paths}
rows=[line.split('\t') for line in (a.fixture_root/'graphs.tsv').read_text().splitlines() if line and not line.startswith('#')]
assert len(rows)==64 and len({r[0] for r in rows})==64
cases=[]
def add(name,q,family,**extra):
 cases.append(dict(name=name,method='POST',path='/api/cypher',body={'query':q.strip()},phase='query',family=family,**extra))
wrapped=sources[source_paths[0]]
for name,const in [('zeroHitBroadContains','ZERO_HIT_QUERY'),('denseDistributedMethodContains','DENSE_DISTRIBUTED_METHOD_QUERY'),('earlyGraphClassPrefix','EARLY_GRAPH_PREFIX_QUERY'),('middleGraphsClassPrefix','MIDDLE_GRAPHS_PREFIX_QUERY'),('lateGraphClassPrefix','LATE_GRAPH_PREFIX_QUERY'),('broadlyDistributedClassPrefix','BROADLY_DISTRIBUTED_PREFIX_QUERY'),('firstLastGraphBimodalClassPrefix','FIRST_LAST_GRAPH_BIMODAL_QUERY'),('skewedMixedClassMethodOperator','SKEWED_MIXED_OPERATOR_QUERY')]:
 q=re.search(r'\b'+const+r'\s*=\s*"""(.*?)"""',wrapped,re.S).group(1)
 add('wrapped-'+name,q,'all-fixture-wrapped',sourceConstant=const)
props=['n.caller_class','n.caller_name','n.callee_class','n.callee_name']
projections=[', '.join(props),'n.caller_class, n.callee_class','n.caller_name, n.callee_name','n.caller_class','n.callee_class','n.graphId, '+', '.join(props),'n.caller_class AS caller, n.caller_name AS callerMethod, n.callee_class AS callee, n.callee_name AS calleeMethod',', '.join(props),', '.join(props),'DISTINCT '+', '.join(props)]
names=['four-properties','class-pair','name-pair','caller-class','callee-class','provenance','aliased','parameterized','wrapped-case-insensitive','wrapped-case-insensitive-distinct']
def quote(s):return s.replace('\\','\\\\').replace("'","\\'")
def query(term,projection,wrap):
 fields=["toLower(coalesce("+v+", ''))" if wrap else v for v in props]
 term=quote(term.lower() if wrap else term)
 return 'MATCH (n)\nWHERE '+' OR '.join(v+" CONTAINS '"+term+"'" for v in fields)+'\nRETURN '+projection+'\nLIMIT 200'
for i,(name,projection) in enumerate(zip(names,projections)):
 assert '"global-wide-'+name+'"' in sources[source_paths[1]]
 for j,selectivity in enumerate(['zero','targeted','dense']):
  row=rows[0 if selectivity=='dense' else i*63//9]
  extra={'selectivity':selectivity,'termSourceGraph':row[0],'workloadIdentity':row[5]}
  if name=='parameterized':extra['httpAdaptation']='Bind the fixed JMH term as an escaped literal: current main HTTP does not accept parameters maps; original JMH parameter transport remains separately required.'
  add('global-wide-'+name+'-'+selectivity,query(row[2+j],projection,i>=8),'global-wide',**extra)
for line in sorted((a.fixture_root/'graphs.tsv').read_text().splitlines()):
 if line.startswith('# global-wide-distribution-v1\t'):
  _,name,target,term,hit=line.split('\t')
  add('global-wide-distribution-'+name,query(term,', '.join(props),True),'global-wide',distribution=name,termSourceGraph=target,expectedHitGraphIds=hit.split(','))
assert len(cases)==42
manifest={'baselineRevision':REVISION,'graphId':'','graphCount':64,'requiredGraphIds':sorted(r[0] for r in rows),'fixtureRoot':str(a.fixture_root),'fixtureManifestSha256':hashlib.sha256((a.fixture_root/'graphs.tsv').read_bytes()).hexdigest(),'fixtureProvenanceSha256':hashlib.sha256((a.fixture_root/'fixture-provenance.tsv').read_bytes()).hexdigest(),'sourceSha256':{k:hashlib.sha256(v.encode()).hexdigest() for k,v in sources.items()},'purpose':'64 real graphs, root cross-graph HTTP; all8 wrapped + all34 global-wide shapes, three HTTP literal-binding adaptations explicitly recorded','ordering':'HTTP registry sorts graph IDs; terms/placement are retained from original fixture manifest order; original JMH values are not assumed to be HTTP oracles','cases':cases}
a.output.write_text(json.dumps(manifest,indent=2)+'\n');print('Wrote42 root cross-graph cases for64 exact graph IDs')

#!/usr/bin/env python3
"""Derive 36 diagnostic queries and independent expected values from a real fixture64 export.

Memory is bounded by a 4-GiB observed RSS ceiling. All paths and expected hashes
are explicit; no Cypher query is executed to calculate the reference result.
"""
import argparse
import base64
import collections
import csv
import gzip
import hashlib
import json
import pathlib
import resource
import sys

FROZEN_REVISION = '4e328b0109e13c896b74004823fb049fcb19251a'
SOURCE_JARS = {
    'android': '6be2218c6a53fe3c57bc22ebdc723edcb7270a8a6f187545708aa5c0ed813977',
    'tika': '87e06f88c801fcb2beae5f15e707241edb14da468a154ad78be4e31ff982c3da',
    'hive': '232d67c5d2ff54806944bb5b7402eaf1ebb81f11dbe4fd51bc5604a8e0c0bdad',
    'kotlin-compiler': '9fa8cdd1de0dccffe154c997d423ec6b5f53cd6d9177e3a77a9b0de03fb1bc81',
}


def check_memory():
    rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    if sys.platform != 'darwin':
        rss *= 1024
    if rss > 4 * 1024**3:
        raise MemoryError('Derivation exceeded 4-GiB RSS ceiling')


def file_sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def validate_inputs(args):
    if not __debug__:
        raise ValueError('Do not disable the reference assertions with python -O')
    for name in ('jar', 'export', 'census'):
        actual = file_sha(getattr(args, name))
        if actual != getattr(args, 'expected_' + name + '_sha256'):
            raise ValueError(name + ' SHA256 differs from authenticated input')
    rows = [line.split('\t') for line in args.manifest.read_text().splitlines()
            if line and not line.startswith('#')]
    with args.provenance.open() as stream:
        proof = list(csv.DictReader(stream, delimiter='\t'))
    if len(rows) != 64 or len(proof) != 64:
        raise ValueError('Expected real fixture64 manifest and provenance')
    paths, identities = set(), set()
    for index, (row, source) in enumerate(zip(rows, proof)):
        corpus = list(SOURCE_JARS)[index // 16]
        graph_id = f'fixture-{corpus}-{index % 16:02d}'
        if len(row) != 6 or row[0] != graph_id or source['graphId'] != graph_id:
            raise ValueError('Fixture identity or order differs')
        if (source['corpus'] != corpus or int(source['shard']) != index % 16
                or source['sourceJarSha256'] != SOURCE_JARS[corpus]):
            raise ValueError('Fixture provenance does not identify the pinned real corpus')
        path = pathlib.Path(row[1]).resolve(strict=True)
        if (not path.is_dir() or path != pathlib.Path(source['graphPath']).resolve(strict=True)
                or path in paths or row[5] in identities or row[5] != source['workloadIdentity']
                or row[2:5] != [source['zeroTerm'], source['targetedTerm'], source['denseTerm']]):
            raise ValueError('Graph paths, terms, or workload identities differ or repeat')
        paths.add(path)
        identities.add(row[5])
    if args.output_dir.exists():
        raise ValueError('Output already exists; preserve earlier evidence')
    return [int(source['callSiteCount']) for source in proof]


def derive(args):
    expected_counts = validate_inputs(args)
    ROOT = args.output_dir
    MANIFEST, PROVENANCE, JAR = args.manifest, args.provenance, args.jar
    EXPORT, CENSUS = args.export, args.census
    ROOT.mkdir(parents=True)
    lines=MANIFEST.read_text().splitlines(); sources=[x.split('\t') for x in lines if x and not x.startswith('#')]; ids=[x[0] for x in sources]
    assert len(ids)==len(set(ids))==64
    annotation_census=[x.split('\t') for x in CENSUS.read_text().splitlines()[1:]]
    assert len(annotation_census)==64 and [x[0] for x in annotation_census]==ids
    assert all(int(x[2])==0 for x in annotation_census), 'Non-CallSite properties require expanded oracle'
    unlabeled_proof="Unlabeled MATCH (n): frozen-main NodePropertyAccessor.kt:73-102 and 123-132 expose these four fields on CallSiteNode only, except AnnotationNode dynamic values at 216-221. The independent annotation census verified 0 AnnotationNodes across all 64 graphs. All other node types return null; coalesce(null, '') and nonempty CONTAINS keywords are false."
    anchors={}
    for x in lines:
     if x.startswith('# global-wide-distribution-v1\tlocalized-'):
      _,label,gid,term,_=x.split('\t');anchors[label.removeprefix('localized-')]=(ids.index(gid),term.lower())
    assert anchors == {label: (position, anchors[label][1]) for label, position in [('early', 0), ('middle', 31), ('late', 63)]}, 'Required early/middle/late placement differs'
    values={};counts=[0]*64;names={k:collections.Counter() for k in anchors}
    def rows():
     with gzip.open(EXPORT,'rt', encoding='utf-8') as f:
      previous_graph=-1
      for row_index,line in enumerate(f):
       if row_index%10000==0:check_memory()
       p=line.rstrip('\n').split('\t');assert len(p)==5
       gi=int(p[0]);assert 0<=gi<64 and gi>=previous_graph
       previous_graph=gi
       yield gi,p[1:]
    for gi,raw in rows():
     counts[gi]+=1;vs=[v.lower() for v in raw]
     for v in set(vs):values[v]=values.get(v,0)|(1<<gi)
     for label,(target,term) in anchors.items():
      if gi==target and any(term in v for v in vs):
       for v in (vs[1],vs[3]):
        if len(v)>=3 and v not in term and term not in v and "'" not in v:names[label][v]+=1
    assert counts == expected_counts, 'Export per-graph counts differ from real fixture provenance'
    print('unique strings',len(values),'rows',sum(counts),flush=True)
    term_masks={}
    def hitmask(t):
     if t not in term_masks:
      m=0
      for v,graphs in values.items():
       if t in v:m|=graphs
      term_masks[t]=m
     return term_masks[t]
    def compatible(terms):
     return len(set(terms))==len(terms) and all(a not in b and b not in a for i,a in enumerate(terms) for b in terms[i+1:])
    selected={}
    four_single={}
    for label,(gi,a) in anchors.items():
     assert hitmask(a)==1<<gi,(label,'anchor not unique')
     candidates=sorted((v for v,m in values.items() if m==1<<gi and '.' in v and len(v)>=20 and "'" not in v and compatible([a,v])),key=lambda v:(-len(v),v))
     b=next((v for v in candidates if hitmask(v)==1<<gi),None);assert b
     c=next(v for v,n in names[label].most_common() if compatible([a,v]))
     selected[label]=(a,b,c)
     chosen=[a]
     for candidate in candidates:
      if compatible(chosen+[candidate]) and hitmask(candidate)==1<<gi:chosen.append(candidate)
      if len(chosen)==4:break
     assert len(chosen)==4,(label,'Need four independent single-graph keywords')
     four_single[label]=chosen
     print(label,gi,a,b,c,flush=True)
    # AST leaves are keyword indices, each matching ANY of the four wrapped properties on the SAME row.
    cases=[]
    def add(id,terms,ast,expected):
     assert compatible(terms),(id,terms)
     cases.append(dict(id=id,terms=terms,ast=ast,advertisedHitGraphPositions=expected))
    for label,(gi,a) in anchors.items():
     a,b,c=selected[label];add('or-single-'+label,[a,b],['or',0,1],[gi]);add('and-single-'+label,[a,c],['and',0,1],[gi])
    e=selected['early'][0];m=selected['middle'][0];l=selected['late'][0]
    add('or-few-early-late',[e,l],['or',0,1],[0,63]);add('or-few-early-middle',[e,m],['or',0,1],[0,31])
    add('or-broad-all',['get','set'],['or',0,1],list(range(64)))
    add('and-broad-all',['java.lang','<init>'],['and',0,1],list(range(64)))
    add('mixed-four-few',[e,selected['early'][2],l,selected['late'][2]],['or',['and',0,1],['and',2,3]],[0,63])
    add('and-zero-disjoint-graphs',[e,l],['and',0,1],[])
    four_terms=[e,selected['early'][2],l,selected['late'][2]]
    four_hit_mask=0
    for term in four_terms:four_hit_mask |= hitmask(term)
    add('or-four-broad',four_terms,['or',0,1,2,3],[i for i in range(64) if four_hit_mask & (1<<i)])
    for label,(gi,_) in anchors.items():
     add('or-four-single-'+label,four_single[label],['or',0,1,2,3],[gi])
    add('or-four-few-early-late',four_single['early'][:2]+four_single['late'][:2],['or',0,1,2,3],[0,63])
    add('or-four-all',['get','set','read','write'],['or',0,1,2,3],list(range(64)))
    terms=list(dict.fromkeys(t for c in cases for t in c['terms']));bit={t:1<<i for i,t in enumerate(terms)}
    for t in terms:hitmask(t)
    value_bits={v:sum(bit[t] for t in terms if t in v) for v in values}
    del values

    def match(ast,bits,c):
     if isinstance(ast,int):return bool(bits&bit[c['terms'][ast]])
     vals=[match(x,bits,c) for x in ast[1:]]
     return any(vals) if ast[0]=='or' else all(vals)
    for c in cases:
     c['perGraphMatchingCounts']=[0]*64;c['perGraphDistinctMatchingCounts']=[0]*64;c['expectedRows']=[];c['expectedDistinctRows']=[];c['_seen']=set();c['_graphSeen']=set();c['_graph']=-1;c['_selected']={}
    for c in cases:
     if c['id'].startswith('or-four-'):c['termExclusiveMatchCounts']=[0]*4
    for gi,raw in rows():
     bits=0
     for v in raw:bits|=value_bits[v.lower()]
     key=tuple(raw)
     for c in cases:
      if not match(c['ast'],bits,c):continue
      if 'termExclusiveMatchCounts' in c:
       matching_terms=[i for i,t in enumerate(c['terms']) if bits&bit[t]]
       if len(matching_terms)==1:c['termExclusiveMatchCounts'][matching_terms[0]]+=1
      c['perGraphMatchingCounts'][gi]+=1
      if len(c['expectedRows'])<200:c['expectedRows'].append(dict(values=raw,graphIds=[ids[gi]]))
      if c['_graph']!=gi:c['_graphSeen'].clear();c['_graph']=gi
      if key not in c['_graphSeen']:c['_graphSeen'].add(key);c['perGraphDistinctMatchingCounts'][gi]+=1
      if key not in c['_seen']:
       c['_seen'].add(key)
       if len(c['expectedDistinctRows'])<200:
        selected=dict(values=raw,graphIds=[]);c['expectedDistinctRows'].append(selected);c['_selected'][key]=selected
      if key in c['_selected'] and ids[gi] not in c['_selected'][key]['graphIds']:c['_selected'][key]['graphIds'].append(ids[gi])
    for c in cases:
     c['totalDistinctMatches']=len(c.pop('_seen'));c.pop('_graphSeen');c.pop('_graph');c.pop('_selected');actual=[i for i,n in enumerate(c['perGraphMatchingCounts']) if n]
     assert actual==c['advertisedHitGraphPositions'],(c['id'],actual,c['advertisedHitGraphPositions'])
     if 'termExclusiveMatchCounts' in c:assert all(n>0 for n in c['termExclusiveMatchCounts']),(c['id'],'Redundant keyword',c['termExclusiveMatchCounts'])
     c['hitGraphIds']=[ids[i] for i in actual];c['totalMatches']=sum(c['perGraphMatchingCounts'])
     c['termHitGraphIds']={t:[ids[i] for i in range(64) if hitmask(t)&(1<<i)] for t in c['terms']} if all(t in term_masks for t in c['terms']) else {t:[ids[i] for i in range(64) if term_masks.get(t,0)&(1<<i)] for t in c['terms']}
     print(c['id'],len(actual),c['totalMatches'],c['totalDistinctMatches'],flush=True)
    props=['caller_class','caller_name','callee_class','callee_name']
    def predicate(ast,c):
     if isinstance(ast,int):
      t=c['terms'][ast].replace('\\', '\\\\').replace("'", "\\'");return '('+' OR '.join("toLower(coalesce(n."+p+", '')) CONTAINS '"+t+"'" for p in props)+')'
     return '('+(' '+ast[0].upper()+' ').join(predicate(x,c) for x in ast[1:])+')'
    expanded=[]
    for c in cases:
     for distinct in (False,True):
      query='MATCH (n) WHERE '+predicate(c['ast'],c)+' RETURN '+('DISTINCT ' if distinct else '')+', '.join('n.'+p for p in props)+' LIMIT 200'
      expanded.append(dict(id=c['id']+('-distinct' if distinct else '-rows'),logicalId=c['id'],query=query,distinct=distinct,expectedHitGraphIds=c['hitGraphIds'],totalMatches=c['totalMatches'],totalDistinctMatches=c['totalDistinctMatches'],expectedRows=c['expectedDistinctRows'] if distinct else c['expectedRows']))
    sha=lambda p:hashlib.file_digest(open(p,'rb'),'sha256').hexdigest()
    catalog=dict(schema='graphite-wide-query-oracle-v3',provenanceSha256=sha(PROVENANCE),unlabeledPropertyProof=unlabeled_proof,nonCallSiteCensusSha256=sha(CENSUS),frozenRevision=FROZEN_REVISION,manifest=str(MANIFEST),manifestSha256=sha(MANIFEST),jar=str(JAR),jarSha256=sha(JAR),exportSha256=sha(EXPORT),exportCompressedBytes=(EXPORT).stat().st_size,inputGraphs=ids,totalCallSites=sum(counts),perGraphCallSiteCounts=counts,semantics='Each keyword matches any of four lowercased CallSite properties; AND operands bind to the same node. Complete per-graph counts are BEFORE LIMIT. DISTINCT globally deduplicates the four projected strings across graphs; expected rows separately record all contributing graph IDs for each selected distinct tuple. Order is manifest order then Graph.nodes(CallSiteNode) order.',logicalCases=cases,queries=expanded)
    (ROOT/'catalog.json').write_text(json.dumps(catalog,indent=2)+'\n')
    with open(ROOT/'workloads.tsv','w') as f:
     f.write('id\tqueryBase64\tdistinct\texpectedHitGraphIds\ttotalMatches\n')
     for c in expanded:f.write('\t'.join([c['id'],base64.b64encode(c['query'].encode()).decode(),str(c['distinct']).lower(),','.join(c['expectedHitGraphIds']),str(c['totalMatches'])])+'\n')
    md=['# Verified multi-keyword CallSite workload','',unlabeled_proof,'',catalog['semantics'],'',f"Frozen main: `{catalog['frozenRevision']}`. Scanned {sum(counts):,} real CallSite nodes across 64 distinct persisted graph paths; export {catalog['exportCompressedBytes']:,} bytes gzip. No Cypher engine used for derivation or ground truth.",'','18 logical predicates, expanded into 36 queries with DISTINCT/non-DISTINCT and LIMIT 200. Single-hit positions are zero-based 0, 31, 63; each full hit set was asserted before writing this catalog. No primary predicate contains duplicate or substring-related keywords.','', '| Case | Logic | Hit positions | Matches before LIMIT | DISTINCT tuples |','|---|---|---|---:|---:|']
    for c in cases:md.append('| '+c['id']+' | '+str(c['ast'])+' | '+(','.join(map(str,c['advertisedHitGraphPositions'])) or 'none')+' | '+str(c['totalMatches'])+' | '+str(c['totalDistinctMatches'])+' |')
    md+=['','## Keywords and full query text','']
    for c in cases:
     md+=['### '+c['id'],'']+['- `'+t+'`' for t in c['terms']]+['','```cypher',next(q['query'] for q in expanded if q['logicalId']==c['id'] and not q['distinct']),'```','']
    md+=['## Reproduce','','See the versioned wide-query-profile README for authenticated export and derive.py commands. Derivation fails closed if an advertised hit distribution differs. `catalog.json` contains all 64 counts per predicate and ordered expected rows for both projections. `totalMatches` in TSV always means raw matching nodes before LIMIT, including for DISTINCT queries; `totalDistinctMatches` is explicit in JSON.','']
    (ROOT/'catalog.md').write_text('\n'.join(md))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('manifest', 'provenance', 'jar', 'export', 'census', 'output-dir'):
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    for name in ('jar', 'export', 'census'):
        parser.add_argument('--expected-' + name + '-sha256', required=True)
    args = parser.parse_args()
    for name in ('manifest', 'provenance', 'jar', 'export', 'census', 'output_dir'):
        setattr(args, name, getattr(args, name).resolve())
    derive(args)


if __name__ == '__main__':
    main()

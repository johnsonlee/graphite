"""Precompiled full legal DATAFLOW result multisets for continuous request validation.

Compilation is outside request timing and worker replenishment. Each response
still validates every value, JSON type, provenance, duplicate and probe count.
This policy never claims exact encounter order for a query without ORDER BY.
"""
from collections import Counter
import hashlib,json,math
from types import MappingProxyType

def need(condition,message):
    if not condition:raise ValueError(message)
COLUMNS=['source','target','relationship','value','caller','graphId']
NAMES={'dataflowSourceHit':('source','android.permission.INTERNET'),
       'dataflowTargetHit':('target','android.app.Activity'),
       'dataflowSourceMiss':('source','GraphiteSlowShapeAbsent293746X'),
       'dataflowTargetMiss':('target','GraphiteSlowShapeAbsent293746X')}
POLICY='complete-legal-limit-multiset-no-order-by'


def typed(v):
    if v is None:return ['null']
    if type(v) in (bool,int,str):return [type(v).__name__,v]
    if type(v) is float:
        need(math.isfinite(v),'nonfinite JSON scalar');return ['float',v]
    if type(v) is list:return ['array',[typed(x) for x in v]]
    need(type(v) is dict and all(type(k) is str for k in v),'JSON object')
    return ['object',[[k,typed(v[k])] for k in sorted(v)]]


def key(row):return json.dumps(typed(row),ensure_ascii=False,separators=(',',':'),allow_nan=False)


def parse(case):
    name=case['id'].removeprefix('slow-');need(name in NAMES,'exact dataflow case')
    side,needle=NAMES[name];prop='c.value' if side=='source' else 'n.caller_class'
    query=f"MATCH (c)-[r:DATAFLOW]->(n) WHERE {prop} CONTAINS '{needle}' RETURN id(c) AS source, id(n) AS target, type(r) AS relationship, c.value AS value, n.caller_class AS caller, n.graphId AS graphId LIMIT 50"
    need(case['family']=='full-slow-shape-catalog' and case['request']=={'endpoint':'/api/cypher','body':{'query':query}},'unmodified exact query without ORDER BY')
    need(case['querySha256']==hashlib.sha256(query.encode()).hexdigest(),'query bytes')
    ids=case['targetGraphIds'];need(len(ids)>1 and len(ids)==len(set(ids)) and all(type(g) is str for g in ids),'actual unique multi-graph scope')
    return {'id':case['id'],'side':side,'needle':needle,'ids':set(ids),'limit':50,'querySha256':case['querySha256']}


class CompiledDataflowOracle:
    def __init__(self,oracle,case):
        parsed=parse(case)
        need(oracle['schema']=='graphite.dataflow-legal-oracle.v1' and oracle['policy']==POLICY,'explicit legal LIMIT policy')
        need(oracle['querySha256']==parsed['querySha256'] and oracle['limit']==50,'exact expected query')
        need(oracle['allGraphScansComplete'] is True and oracle['exactEncounterOrderClaim'] is False,'full scan and ordering scope')
        need(type(oracle['graphCount']) is int and oracle['graphCount']==len(parsed['ids']) and oracle['graphIds']==sorted(parsed['ids']),'complete expected graph scope')
        need(oracle['columns']==COLUMNS and type(oracle['totalMatches']) is int and oracle['totalMatches']>=0,'expected shape/count')
        allowed=Counter()
        for entry in oracle['rows']:
            need(type(entry) is dict and set(entry)=={'value','multiplicity'} and type(entry['multiplicity']) is int and entry['multiplicity']>0,'positive exact multiplicity')
            row=entry['value'];need(type(row) is dict and row.get('graphId') in parsed['ids'],'row graph scope')
            need(row.get('$metadata')=={'graphIds':[row['graphId']]},'exact single-edge provenance')
            k=key(row);need(k not in allowed,'unique expected row');allowed[k]=entry['multiplicity']
        total=sum(allowed.values());need(total==oracle['totalMatches'],'complete multiset count')
        self.allowed=MappingProxyType(dict(allowed))
        self.total=total
        self.graph_count=oracle['graphCount']
        self.columns=tuple(COLUMNS)

    def validate(self,value):
        need(type(value) is dict and set(value)=={'columns','rows','rowCount','graphCount','total'},'complete response envelope')
        need(type(value['columns']) is list and tuple(value['columns'])==self.columns and type(value['rows']) is list,'complete ordered columns and rows')
        count=min(self.total,50)
        need(type(value['rowCount']) is int and value['rowCount']==len(value['rows'])==count,'exact LIMIT row count')
        need(type(value['graphCount']) is int and value['graphCount']==self.graph_count,'registry count')
        expected_total={'value':min(self.total,51),'relation':'gte' if self.total>50 else 'eq'}
        need(typed(value['total'])==typed(expected_total),'exact probe total')
        actual=Counter(key(row) for row in value['rows'])
        need(all(n<=self.allowed.get(k,0) for k,n in actual.items()),'complete projected rows/provenance/multiplicity mismatch')
        return {'status':'PASS_FULL_LEGAL_LIMIT_MULTISET','rows':count,'completeMatches':self.total,'policy':POLICY,'exactEncounterOrderClaim':False}

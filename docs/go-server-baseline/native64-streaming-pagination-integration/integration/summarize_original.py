"""Keep the original 1048 denominator and every newly captured request."""
import pathlib,json,collections,argparse
h=pathlib.Path('/tmp/graphite-go-b-pagination-root-2ab90cdc/candidate/graphite-server/internal/query/testdata/ordinary-projection');old=h.parent/'indexed-distinct'
a=argparse.ArgumentParser();a.add_argument('--verification',type=pathlib.Path,default=h/'verification');args=a.parse_args();v=args.verification
read=lambda p:json.loads(p.read_text())
native=v/'original-corpus';pairs=[]
def add(suite,expected,actual,oldeligible,ordinary):
 assert len(expected)==len(actual)==len(oldeligible)==len(ordinary)
 for a,b,d,o in zip(expected,actual,oldeligible,ordinary):
  comparison='equal'if a==b else ('both-error-difference'if 'error'in a and 'error'in b else 'main-error-native-success'if 'error'in a else 'main-success-native-error'if 'error'in b else 'both-success-difference')
  pairs.append(dict(suite=suite,name=a['name'],query=a.get('query'),fixture=a.get('fixture',a.get('mutation')),cross=a.get('cross'),sources=a.get('sources'),priorDistinctEligible=d,ordinaryEligible=o,comparison=comparison))
for suite in ['primary','supplementary']:
 flags=read(native/(suite+'-eligibility.json'));add(suite,read(old/('audit-'+suite)/'main.json'),read(native/(suite+'-native.json')),[x['eligible']for x in flags],[x['ordinaryEligible']for x in flags])
a=read(old/'audit-source-pairs/main.json');flags=read(native/'source-pairs-eligibility.json');add('source-pairs',a,read(native/'source-pairs-native.json'),[x['eligible']for x in flags],[x['ordinaryEligible']for x in flags])
for suffix in ['','-missing-index','-missing-identity']:
 a=read(old/('required-main'+suffix+'.json'));add('required'+suffix,a,read(native/('required'+suffix+'-native.json')),[True]*len(a),[False]*len(a))
eligibility=[x['eligible']for x in read(old/'additional-cases.json')for _ in [0,1]]
for name in ['annotation','mixed','annotation-after']:add(name,read(old/(name+'-wire-main.json')),read(native/(name+'-native.json')),eligibility,[False]*len(eligibility))
for name in ['clean','bad-count','bad-tag','bad-callee','missing-index']:
 a=read(old/('split-'+name+'-main.json'));add('split-'+name,a,read(native/('split-'+name+'-native.json')),[True]*len(a),[False]*len(a))
a=read(old/'late-main.json');add('late',a,read(native/'late-native.json'),[True]*len(a),[False]*len(a))
summary=dict(total=len(pairs),comparison=dict(collections.Counter(p['comparison']for p in pairs)),priorDistinct=dict(collections.Counter(p['comparison']for p in pairs if p['priorDistinctEligible'])),ordinary=dict(collections.Counter(p['comparison']for p in pairs if p['ordinaryEligible'])),remaining=[p for p in pairs if p['comparison']!='equal'],pairs=pairs)
assert summary['total']==1048
(v/'original-summary.json').write_text(json.dumps(summary,indent=2)+'\n');print({k:x for k,x in summary.items()if k not in ['pairs','remaining']})

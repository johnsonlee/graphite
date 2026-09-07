import pathlib,json,collections
here=pathlib.Path(__file__).resolve().parent;native=here/'verification-native'
pairs=[]
def add(suite,main,actual,eligible):
 assert len(main)==len(actual)==len(eligible),(suite,len(main),len(actual),len(eligible))
 for a,b,e in zip(main,actual,eligible):
  if a==b:kind='equal'
  elif 'error'in a and 'error'in b:kind='both-error-difference'
  elif 'error'in a:kind='main-error-native-success'
  elif 'error'in b:kind='main-success-native-error'
  else:kind='both-success-difference'
  pairs.append(dict(suite=suite,name=a['name'],fixture=a.get('fixture',a.get('mutation')),cross=a.get('cross'),sources=a.get('sources'),eligible=e,comparison=kind))
read=lambda p:json.loads(p.read_text())
for suite in ['primary','supplementary']:
 add('audit-'+suite,read(here/('audit-'+suite)/'main.json'),read(native/(suite+'-native.json')),[x['eligible']for x in read(native/(suite+'-eligibility.json'))])
a=read(here/'audit-source-pairs'/'main.json');add('audit-source-pairs',a,read(native/'source-pairs-native.json'),[x['name']in ['distinct-direct','distinct-graph']for x in a])
for suffix in ['', '-missing-index','-missing-identity']:
 a=read(here/('required-main'+suffix+'.json'));add('required'+suffix,a,read(native/('required'+suffix+'-native.json')),[True]*len(a))
eligibility=[x['eligible']for x in read(here/'additional-cases.json')for _ in [0,1]]
for fixture in ['annotation','mixed','annotation-after']:add(fixture,read(here/(fixture+'-wire-main.json')),read(native/(fixture+'-native.json')),eligibility)
for mode in ['clean','bad-count','bad-tag','bad-callee','missing-index']:
 a=read(here/('split-'+mode+'-main.json'));add('split-'+mode,a,read(native/('split-'+mode+'-native.json')),[True]*len(a))
a=read(here/'late-main.json');add('late',a,read(native/'late-native.json'),[True]*len(a))
summary=dict(total=len(pairs),comparison=dict(collections.Counter(x['comparison']for x in pairs)),eligible=dict(collections.Counter(x['comparison']for x in pairs if x['eligible'])),declined=dict(collections.Counter(x['comparison']for x in pairs if not x['eligible'])),pairs=pairs)
(here/'verification-summary.json').write_text(json.dumps(summary,indent=2)+'\n');print({k:v for k,v in summary.items()if k!='pairs'})

import pathlib,json,re
r=pathlib.Path(__file__).resolve().parent
a=json.load(open(r/'comparison.json'))
allowed={'base-output/original-corpus/main-string-source-main.json-native.json','base-output/original-corpus/main-string-source-offset-main.json-native.json','base-output/history/rolling-native.json'}
changes=[]
for item in a['records']:
 if item['differences']:
  assert item['base'] in allowed
  for d in item['differences']:
   assert re.fullmatch(r'\$\[\d+\]\.targets\[\d+\]\.(before|after)\[\d+\]\.mappedView',d['path']),d
   case=json.load(open(r/item['base']))[int(d['path'].split(']')[0][2:])]
   assert case.get('count',case.get('spec',{}).get('sources'))==40
   assert type(d['base'])==type(d['candidate'])==bool
   changes.append({'file':item['base'],**d})
source=pathlib.Path('/tmp/graphite-go-a18-on-fb4434df/integration/output')
paths=[]
for group in ['original-corpus','history']:
 parent={str(p.relative_to(source/group)) for p in (source/group).rglob('*.json')}
 base={str(p.relative_to(r/'base-output'/group)) for p in (r/'base-output'/group).rglob('*.json')}
 assert parent==base,(group,parent-base,base-parent)
 paths.append({'group':group,'count':len(base),'paths':sorted(base)})
report={'artifacts':76,'strictRawExact':a['equal'],'fullResponseChanges':0,'stateDifferences':len(changes),'scope':'Only existing count40 mappedView bools; raw artifacts are retained and not called exact','changes':changes,'priorA18CaptureInventory':paths}
(r/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
print('full response verification',report['artifacts'],report['strictRawExact'],report['stateDifferences'])

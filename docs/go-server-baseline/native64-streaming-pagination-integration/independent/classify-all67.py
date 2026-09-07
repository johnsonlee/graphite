from pathlib import Path
import json,re
r=Path(__file__).resolve().parent
report=json.loads((r/'comparison.json').read_text());state=[]
expected={'generic-fault-audit.json','primary-native.json','supplementary-native.json','main-string-source-main.json-native.json','main-string-source-offset-main.json-native.json','rolling-native.json'}
assert {Path(x['base']).name for x in report['records'] if x['differences']}==expected
for item in report['records']:
 if Path(item['base']).name not in {'main-string-source-main.json-native.json','main-string-source-offset-main.json-native.json','rolling-native.json'}:continue
 data=json.loads((r/item['base']).read_text())
 for d in item['differences']:
  assert re.fullmatch(r'\$\[\d+\]\.targets\[\d+\]\.(before|after)\[\d+\]\.mappedView',d['path'])
  index=int(d['path'].split(']')[0][2:]);assert data[index].get('count',data[index].get('spec',{}).get('sources'))==40
  state.append({'artifact':item['base'],**d})
a=json.loads((r/'captured-output/baseline-output/original-corpus/generic-fault-audit.json').read_text());b=json.loads((r/'captured-output/final-output/original-corpus/generic-fault-audit.json').read_text());assert len(a)==len(b)==432
for x,y in zip(a,b):
 assert x['main']==y['main'];assert y['candidate']==y['main'];assert y['mainEqual'] is True
 if x!=y:assert y['base']==y['main']
result={'priorArtifacts':67,'exactArtifacts':61,'functionalChangedArtifacts':3,'speculativeStateArtifacts':3,'stateChangedLeaves':len(state),'genericFault':{'total':432,'baselineMainEqual':408,'candidateMainEqual':432,'changedCandidateBodies':sum(x['candidate']!=y['candidate'] for x,y in zip(a,b)),'changedReferenceControlBodies':sum(x['base']!=y['base'] for x,y in zip(a,b))},'mappedViewStateDifferences':state}
(r/'all67-classification.json').write_text(json.dumps(result,indent=2)+'\n');print({k:v for k,v in result.items() if k!='mappedViewStateDifferences'})

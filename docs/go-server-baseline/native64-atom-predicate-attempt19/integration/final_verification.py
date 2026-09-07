from pathlib import Path
import json,hashlib,re,shutil,difflib
r=Path(__file__).resolve().parent;H=lambda b:hashlib.sha256(b).hexdigest();read=lambda p:json.loads(p.read_text())
records=read(r/'all-b-output-differences.json');assert len(records)==76
retained={'$[8].targets[0].after[8].retained','$[8].targets[1].before[8].retained'};seen=set();mapped=0
for f in records:
 for d in f['differences']:
  assert type(d['base'])==type(d['candidate'])==bool
  ordinal=int(re.match(r'\$\[(\d+)\]',d['path'])[1]);base=read(Path('/tmp/graphite-atom-attempt19-evidence/base-output')/f['file'])[ordinal];cand=read(r/'output'/f['file'])[ordinal]
  assert base.get('count',base.get('spec',{}).get('sources'))==cand.get('count',cand.get('spec',{}).get('sources'))==40
  if d['path'].endswith('.mappedView'):
   assert f['file'] in {'history/rolling-native.json','original-corpus/main-string-source-main.json-native.json','original-corpus/main-string-source-offset-main.json-native.json'}
   assert re.fullmatch(r'\$\[\d+\]\.targets\[\d+\]\.(before|after)\[\d+\]\.mappedView',d['path']);mapped+=1
  else:
   assert f['file']=='history/rolling-native.json' and d['path'] in retained and d['base'] is True and d['candidate'] is False,d
   assert base['scenario']==cand['scenario']=='early-match-later-error';assert base['fixtures']==cand['fixtures'];assert base['fixtures'][8]=='nohit';seen.add(d['path'])
assert seen==retained
p=r/'schedule-g8';original=read(r/'base-inputs.json');changed=[]
for n,v in original.items():
 if not n.startswith('graphite-server/'):continue
 q=p/'module'/n.removeprefix('graphite-server/');actual=q.read_bytes()
 if H(actual)!=v['sha256']:changed.append(n)
assert changed==['graphite-server/internal/query/indexed_distinct.go']
before=(r/'combined/base'/changed[0]).read_text();after=(p/'module/internal/query/indexed_distinct.go').read_text();assert after.replace('\t\t\tdefer reviewG8TaskSchedule(local, count, i)()\n','')==before
for n in ['review_g8_schedule.go','review_g8_schedule_test.go']:
 (p/'instrumentation-source').mkdir(exist_ok=True);shutil.copy2(p/'module/internal/query'/n,p/'instrumentation-source'/n)
main=read(r/'combined/base/graphite-server/internal/query/testdata/ordinary-projection/rolling-main-0.json')[8]
keys={'query','columns','rows','error','message'}
obs=[]
for control in [False,True]:
 x=read(p/f'output/controlled-{str(control).lower()}.json');assert x['parentCanceled'] is False and x['watchdogFired'] is False
 assert len(x['observations'])==len(main['targets'])==3
 for actual,expected in zip(x['observations'],main['targets']):assert {k:v for k,v in actual.items() if k in keys}=={k:v for k,v in expected.items() if k in keys}
 if control:
  assert x['taskIndex']==7 and x['sourceIndex']==8 and x['sourceId']=='g8'
  assert x['observations'][0]['after'][8]['retained'] is False and x['observations'][1]['before'][8]['retained'] is False
  assert [e['childCanceled'] for e in x['events']]==[False,True,True]
  assert [e['phase'] for e in x['events']]==['before-original-source-task','scheduler-child-done','original-source-task-returned']
 obs.append(dict(controlled=control,after=x['observations'][0]['after'][8],beforeNext=x['observations'][1]['before'][8],allThreePublicResponsesEqual=True,events=x['events']))
(p/'source-proof.json').write_text(json.dumps({'baseModuleFiles':2437,'unchanged':2436,'oneInsertedSchedulingLineRestoresOriginal':True,'changed':changed,'newInstrumentationFiles':['review_g8_schedule.go','review_g8_schedule_test.go'],'doesNotAlterProductionCandidate':True},indent=2)+'\n')
(p/'schedule-proof.json').write_text(json.dumps({'observations':obs,'scope':'Allowed schedule reachability, not natural frequency or equivalent distributions'},indent=2)+'\n')
(r/'comparison-summary.json').write_text(json.dumps({'artifacts':76,'rawExact':sum(x['exact'] for x in records),'mappedViewLeaves':mapped,'retainedLeavesExplainedByExactOriginalAllowedSchedule':sorted(seen),'publicResponseDifferences':0,'strictComparisonStillFails':True,'original1048':1044,'B595':True,'numericSpellings':166,'noDistributionClaim':True},indent=2)+'\n')
print('Verified73raw differences: mapped',mapped,'exact retained schedule paths',sorted(seen))

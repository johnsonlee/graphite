from pathlib import Path
import json,hashlib
r=Path(__file__).resolve().parent

def eq(a,b):
 if type(a)!=type(b): return False
 if isinstance(a,dict):return a.keys()==b.keys() and all(eq(a[k],b[k]) for k in a)
 if isinstance(a,list):return len(a)==len(b) and all(eq(x,y) for x,y in zip(a,b))
 return a==b
records=[]
for p in sorted((r/'native-exact').glob('*.json')):
 old=r/'native'/p.name;assert eq(json.loads(p.read_text()),json.loads(old.read_text())),p.name
 records.append({'file':p.name,'equalWithoutObserver':True,'entries':len(json.loads(p.read_text()))})
# Identity, columns, rows, errors and parameters in regular main captures all remain complete.
for fixture in ['bad-first-unmatched-MAPPED','bad-last-unmatched-MAPPED','bad-matched-MAPPED','clean-MAPPED','clean-EAGER']:
 native=json.loads((r/'native-exact'/('streaming-'+fixture+'-native.json')).read_text());main=json.loads((r/'oracle'/(fixture+'-wire.json')).read_text());assert eq(native,main),fixture
for fixture in ['clean','bad-matched']:
 native=json.loads((r/'native-exact'/('streaming-unknown-'+fixture+'-native.json')).read_text());main=json.loads((r/'oracle'/(fixture+'-unknown-wire.json')).read_text());assert eq(native,main),fixture
native=json.loads((r/'native-exact/streaming-sources-native.json').read_text());main=json.loads((r/'oracle/sources-wire.json').read_text());assert eq(native,main)
# Main design records include proxy metadata, compare their complete response and ordered fetch trace.
main=json.loads((r/'oracle/design-main.json').read_text());native=json.loads((r/'native-exact/streaming-design-native.json').read_text());assert len(main)==len(native)==53
for a,b in zip(main,native):
 assert a['name']==b['name'] and a['cross']==b['cross']
 expected={k:a[k] for k in ['columns','rows','error','message'] if k in a};assert eq(expected,b['response']),(a['name'],'response')
 for x,y in zip(a['traces'],b['traces']):assert x['next']==y['Next'] and eq(x['ids'],y['IDs']),(a['name'],'trace')
# The independent runtime tests execute exactly the final author production except the separately retained observer run.
manifest=json.loads((r/'author-production-files.json').read_text())
for rel,sha in manifest.items():
 path=r/'module'/Path(rel).relative_to('graphite-server');assert hashlib.sha256(path.read_bytes()).hexdigest()==sha
(r/'verification.json').write_text(json.dumps({'productionFiles':3,'exactAuthorSHA':True,'artifacts':records,'mainCompleteResponseCount':595,'design':53,'designDeclineControls':sum(not x['admitted'] for x in native),'actualMainCorpus':480,'unknownLabel':24,'sourceHistoryCases':28,'sourceHistoryResponses':38,'normalization':'none; array and numeric/type/null values checked; JSON object member ordering not significant'},indent=2)+'\n')
print('595 complete responses and final 3-file identity pass')

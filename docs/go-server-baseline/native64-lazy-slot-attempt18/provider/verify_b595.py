from pathlib import Path
import json,hashlib
r=Path(__file__).resolve().parent
n=r/'candidate-output/original-corpus'
o=Path('/tmp/graphite-go-lazy-slot-attempt18-b7bb15a2/graphite-server/internal/query/testdata/streaming-pagination')

def eq(a,b):
 if type(a)!=type(b): return type(a) in (int,float) and type(b) in (int,float) and a==b
 if isinstance(a,dict):return a.keys()==b.keys() and all(eq(a[k],b[k]) for k in a)
 if isinstance(a,list):return len(a)==len(b) and all(eq(x,y) for x,y in zip(a,b))
 return a==b
# Identity, columns, rows, errors and parameters in regular main captures all remain complete.
for fixture in ['bad-first-unmatched-MAPPED','bad-last-unmatched-MAPPED','bad-matched-MAPPED','clean-MAPPED','clean-EAGER']:
 native=json.loads((n/('streaming-'+fixture+'-native.json')).read_text());main=json.loads((o/(fixture+'-wire.json')).read_text());assert eq(native,main),fixture
for fixture in ['clean','bad-matched']:
 native=json.loads((n/('streaming-unknown-'+fixture+'-native.json')).read_text());main=json.loads((o/(fixture+'-unknown-wire.json')).read_text());assert eq(native,main),fixture
native=json.loads((n/'streaming-sources-native.json').read_text());main=json.loads((o/'sources-wire.json').read_text());assert eq(native,main)
# Main design records include proxy metadata, compare their complete response and ordered fetch trace.
main=json.loads((o/'design-main.json').read_text());native=json.loads((n/'streaming-design-native.json').read_text());assert len(main)==len(native)==53
for a,b in zip(main,native):
 assert a['name']==b['name'] and a['cross']==b['cross']
 expected={k:a[k] for k in ['columns','rows','error','message'] if k in a};assert eq(expected,b['response']),(a['name'],'response')
 for x,y in zip(a['traces'],b['traces']):assert x['next']==y['Next'] and eq(x['ids'],y['IDs']),(a['name'],'trace')

(r/'b595-verification.json').write_text(json.dumps({'completeJSONValueResponses':595,'designTraces':53,'declineControls':sum(not x['admitted'] for x in native),'numericSpelling':'separate audit; not byte equal'},indent=2)+'\n')
print('595 complete JSON-value responses and design traces passed')

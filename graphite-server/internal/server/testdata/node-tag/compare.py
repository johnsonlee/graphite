"""Compare all HTTP observations; preserve raw input, no excluded cases."""
import argparse,copy,json,pathlib
p=argparse.ArgumentParser();p.add_argument('main');p.add_argument('native');p.add_argument('--out',required=True);a=p.parse_args()
def normalize(response):
 result=copy.deepcopy(response)
 raw=result.pop('rawBody')
 try:body=json.loads(raw)
 except ValueError:body=raw
 if isinstance(body,dict) and isinstance(body.get('graph'),dict):
  body['graph']['path']='<fixture>';body['graph']['loadedAt']='<dynamic>'
 result['body']=body
 return result
main=json.loads(pathlib.Path(a.main).read_text());native=json.loads(pathlib.Path(a.native).read_text())
assert len(main)==len(native)==104
result=[]
for expected,actual in zip(main,native):
 key={k:expected[k] for k in ['mode','tag','name']}
 assert key=={k:actual[k] for k in key}
 assert expected['request']['path']==actual['request']['path']
 assert expected['request']['method']==actual['request']['method']
 left,right=normalize(expected['response']),normalize(actual['response'])
 if left!=right:result.append({'case':key,'main':left,'native':right})
pathlib.Path(a.out).write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'total':len(main),'equal':len(main)-len(result),'different':len(result)}))

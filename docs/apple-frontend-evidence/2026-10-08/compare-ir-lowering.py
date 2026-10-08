import importlib.util,pathlib,collections,json,sys
spec=importlib.util.spec_from_file_location('af','backend/bench/apple-frontend.py');af=importlib.util.module_from_spec(spec);spec.loader.exec_module(af)
def fs(b): return {n:v for n,w,v in af.fields(b)}
def read(path):
 data=pathlib.Path(path).read_bytes();at=0;strings=[];nodes={};edges=[];methods=[]
 while at<len(data):
  size,at=af.read_varint(data,at);chunk=fs(data[at:at+size]);at+=size
  for kind,payload in chunk.items():
   if kind==2: strings.extend(v.decode() for n,w,v in af.fields(payload))
   elif kind==3:
    for n,w,v in af.fields(payload):
     node=fs(v);i=node.pop(1,0);nodes[i]=node
   elif kind==4: edges.extend(fs(v) for n,w,v in af.fields(payload))
   elif kind==5: methods.extend(v for n,w,v in af.fields(payload))
 def st(i): return strings[i]
 def method(b): return st(fs(b).get(2,0)) # owner/type changes deliberately excluded
 def nodekey(i):
  node=nodes[i];k,b=next(iter(node.items()));d=fs(b)
  if k==7: return (k,st(d.get(1,0)))
  if k==9: return (k,st(d.get(2,0)),repr(d.get(3,b'')))
  if k==10: return (k,st(d.get(1,0)),method(d.get(3,b'')))
  if k==11: return (k,st(fs(d.get(1,b'')).get(2,0)),d.get(2,0))
  if k==12:return(k,d.get(1,0),method(d.get(3,b'')))
  if k==13:return(k,method(d.get(1,b'')))
  if k==16:return(k,d.get(3,0),4 in d)
  if k==17:return(k,d.get(1,b'').decode(),d.get(3,b'').decode())
  return(k,repr(sorted(d.items())))
 nk={i:nodekey(i) for i in nodes}
 facts=collections.Counter(nk.values())
 edgefacts=collections.Counter((nk[e.get(1,0)],nk[e.get(2,0)],repr(sorted((k,v)for k,v in e.items()if k>2)))for e in edges)
 return facts,edgefacts
base,final=map(read,sys.argv[1:3]);out={'boundary':'Display-name-independent lowering check: node/edge multiset preserves call source line and receiver presence, literals and other node identities. Caller/callee display names are excluded due Swift-to-ObjC selector resolution; separate index-fact check anchors every old call by actual caller/callee USR and source path+line+column. Signature types/owners also excluded as intentional corrections.'}
for label,a,b in [('nodes',base[0],final[0]),('edges',base[1],final[1])]:
 missing=a-b;added=b-a
 out[label]={'before':sum(a.values()),'after':sum(b.values()),'missing':sum(missing.values()),'added':sum(added.values()),'missingKinds':dict(collections.Counter({str(kind):sum(v for k,v in missing.items() if k[0]==kind) for kind in set(k[0] for k in missing)})) if label=='nodes' else {},'missingExamples':[(repr(k),v)for k,v in missing.most_common(20)]}
print(json.dumps(out,indent=2))

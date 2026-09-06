#!/usr/bin/env python3
"""Add bounded mixed-OR main evidence without rewriting the frozen audit oracle."""
import json,pathlib,subprocess,sys,tempfile,struct,zlib
r=pathlib.Path(__file__).resolve().parent;extra=r/'supplement';extra.mkdir(exist_ok=True);jar=pathlib.Path(sys.argv[1]).resolve();original=json.loads((r/'inputs.json').read_text())
where=[
 "n.callee_name CONTAINS 'abc' OR n.caller_name CONTAINS 'XΟΣ'",
 "n.caller_name CONTAINS 'XΟΣ' OR n.callee_name CONTAINS 'abc'",
 "n.callee_class CONTAINS 'Σ12' OR n.caller_class CONTAINS 'AAA'",
 "toLower(n.caller_name) CONTAINS 'xοσ' OR n.callee_class='AΣ12'",
 "toLower(n.caller_name) CONTAINS 'XΟΣ' OR n.callee_class='AΣ12'",
 "n.callee_name CONTAINS 'aaa' OR n.callee_name STARTS WITH 'a'",
 "n.caller_class CONTAINS 'acc' OR n.callee_name ENDS WITH 'a'",
 "n.caller_class CONTAINS 'absent' OR n.callee_name CONTAINS ''",
 "toLower(coalesce(n.callee_name,'')) CONTAINS 'abc' OR coalesce(n.caller_name,'') CONTAINS ''",
 "toLower(n.caller_class) CONTAINS 'acc' OR toLower(n.callee_class) CONTAINS 'mix'",
 "n.caller_class CONTAINS 'acc' OR n.callee_class CONTAINS 'MiX'",
 "n.caller_class CONTAINS $term OR n.caller_name CONTAINS 'XΟΣ'",
 "n.callee_class CONTAINS $term OR n.caller_class CONTAINS 'acc'",
 "n.callee_class CONTAINS 'absent' OR substring('x','bad')='x'",
 "n.callee_class CONTAINS 'abc' OR n.callee_class CONTAINS 'ABC'",
 "n.callee_class CONTAINS 'absent' OR n.callee_name CONTAINS 'absent'",
]
cases=[]
for i,w in enumerate(where):
 for typed in [False,True]:
  cases.append({'name':f'mixed-{i}-'+('typed' if typed else 'untyped'),'query':'MATCH (n'+(':CallSiteNode' if typed else '')+') WHERE '+w+' RETURN id(n) AS id ORDER BY id','params':{'term':'abc'}})
(extra/'inputs.json').write_text(json.dumps({'values':original['values'],'cases':cases},ensure_ascii=True,indent=2)+'\n')
with tempfile.TemporaryDirectory(prefix='graphite-trigram-mixed-') as classes:
 subprocess.run(['javac','-cp',str(jar),'-d',classes,str(r/'TrigramOracle.java')],check=True)
 subprocess.run(['java','-Dfile.encoding=UTF-8','-cp',classes+':'+str(jar),'TrigramOracle',str(extra)],check=True)
def units(s):
 b=s.encode('utf-16-be','surrogatepass');return [int.from_bytes(b[i:i+2],'big') for i in range(0,len(b),2)]
for c in cases:c['params']={k:units(v) for k,v in c['params'].items()}
(extra/'cases-units.json').write_text(json.dumps(cases,indent=2)+'\n')
# Extra pair deliberately selects a wrong SID for aaa, requiring the exact predicate.
b=(r/'store/graph.callsite-string-index').read_bytes();strings,calls=struct.unpack_from('>ii',b,8);ns=struct.unpack_from('>iiii',b,48);start=76+sum(8*n+4*calls for n in ns)+8*strings
pairs=[struct.unpack_from('>q',b,i)[0] for i in range(start,len(b)-8,8)]
sid=next(s['id'] for s in json.loads((r/'strings.json').read_text()) if s['units']==units('acc'));extraPair=(96321<<32)|sid;assert extraPair not in pairs
out={}
for name,p in [('missing',pairs[1:]),('extra',sorted(pairs+[extraPair]))]:
 data=bytearray(b[:start])+bytearray().join(struct.pack('>q',x) for x in p)+bytearray(8);struct.pack_into('>i',data,64,len(p));struct.pack_into('>q',data,68,len(data)-84+480)
 segments=[]
 def words(lo,hi,w):segments.extend(data[i:i+w][::-1] for i in range(lo,hi,w))
 words(0,16,4);segments.append(data[16:48]);words(48,68,4);words(68,76,8);words(76,start-8*strings,4);words(start-8*strings,len(data)-8,8)
 crc=zlib.crc32(b''.join(segments));struct.pack_into('>q',data,len(data)-8,crc);(r/(name+'.callsite-string-index')).write_bytes(data)
 out[name]={'removedPair':pairs[0] if name=='missing' else None,'addedPair':extraPair if name=='extra' else None,'crc32':crc,'base':'main-created store/graph.callsite-string-index','mutation':'one derived trigram pair only; updated count, retained-byte estimate and typed numeric CRC; core/identity/CSR unchanged'}
(r/'mutations.json').write_text(json.dumps(out,indent=2)+'\n')
print('mixed main observations',len(cases)*2)

#!/usr/bin/env python3
import pathlib,hashlib,json,sys,datetime,os
here=pathlib.Path(__file__).resolve().parent
original=pathlib.Path('/tmp/pr113-exp037-fixture.nXn4fg')
clone=pathlib.Path('/tmp/graphite-ordinary-http-real64-cow-2e2d8b52')
mode=sys.argv[1]
assert mode in ['before','after']
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  while b:=f.read(8*1024*1024):h.update(b)
 return h.hexdigest()
manifest=here/'inputs/fixture-files.json';expected=json.loads(manifest.read_text());assert len(expected)==1152
wanted={str(pathlib.Path(x['graphId'])/x['file']):x for x in expected};graphs={x['graphId']for x in expected};assert len(graphs)==64
allrecords={};changed={}
for name,root in [('original',original),('clone',clone)]:
 records={}
 for p in sorted(root.rglob('*')):
  assert not p.is_symlink(),str(p)
  if p.is_file():
   s=p.stat();records[str(p.relative_to(root))]={'bytes':s.st_size,'sha256':sha(p),'device':s.st_dev,'inode':s.st_ino}
 allrecords[name]=records
 graphpaths={n for n in records if pathlib.Path(n).parts[0]in graphs}
 if mode=='before' or name=='original':
  assert graphpaths==set(wanted),(name,graphpaths.symmetric_difference(wanted))
  for n,w in wanted.items():assert records[n]['sha256']==w['sha256'] and records[n]['bytes']==w['bytes'],(name,n)
 if mode=='after':
  before=json.loads((here/'fixtures-before.json').read_text())['files'][name]
  changed[name]={n:{'before':before.get(n),'after':records.get(n)} for n in set(before)|set(records) if (before.get(n) or {}).get('sha256')!=(records.get(n) or {}).get('sha256') or (before.get(n) or {}).get('bytes')!=(records.get(n) or {}).get('bytes')}
  if name=='original':assert not changed[name],changed[name]
if mode=='before':
 assert set(allrecords['original'])==set(allrecords['clone'])
 for n,a in allrecords['original'].items():
  b=allrecords['clone'][n];assert a['sha256']==b['sha256'] and a['bytes']==b['bytes'],n
  assert (a['device'],a['inode'])!=(b['device'],b['inode']),('hardlink',n)
result={'recordedUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'mode':mode,'originalRoot':str(original),'cloneRoot':str(clone),'frozenManifestSHA256':sha(manifest),'requiredGraphFiles':1152,'requiredGraphBytes':sum(x['bytes']for x in expected),'allOriginalFrozenHashesMatch':True,'files':allrecords,'changes':changed,'purpose':'Read-only fixture identity and Close persistence correctness; no performance claim'}
(here/('fixtures-'+mode+'.json')).write_text(json.dumps(result,indent=2)+'\n')
print(mode,'original+clone files',len(allrecords['original']),len(allrecords['clone']),'changes',{k:len(v)for k,v in changed.items()})

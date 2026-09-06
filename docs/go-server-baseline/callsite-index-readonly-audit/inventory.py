#!/usr/bin/env python3
"""Read only manifest, index headers/footers and identities; never load graphs.
Does not calculate full-file CRC or validate posting contents. No timings.
"""
import pathlib,struct,json,sys,hashlib
root=pathlib.Path(sys.argv[1]);out=pathlib.Path(sys.argv[2]);rows=[]
for line in (root/'graphs.tsv').read_text().splitlines():
 if not line or line.startswith('#'):continue
 graph_id,path,*_=line.split('\t');folder=pathlib.Path(path)
 names=['graph.callsite-string-index','graph.callsite-string-content.identity','graph.strings.identity','graph.strings','graph.nodedata','graph.nodeoffsets','graph.typeindex']
 row={'id':graph_id,'path':path,'files':{}}
 for name in names:
  p=folder/name
  if not p.is_file():row['files'][name]={'exists':False};continue
  st=p.stat();record={'exists':True,'bytes':st.st_size,'mtime_ns':st.st_mtime_ns}
  if name.endswith('.identity'):record['hex']=p.read_bytes().hex()
  row['files'][name]=record
 p=folder/names[0]
 if p.is_file():
  with p.open('rb') as f:header=f.read(76);f.seek(-8,2);footer=f.read(8)
  magic,version,strings,calls=struct.unpack_from('>4i',header);unique=struct.unpack_from('>4i',header,48);trigrams,retained=struct.unpack_from('>iq',header,64)
  expected=76+sum(8*u+4*calls for u in unique)+8*strings+8*trigrams+8
  row['header']={'magic':hex(magic),'version':version,'strings':strings,'callSites':calls,'contentIdentity':header[16:48].hex(),'uniquePropertyStrings':list(unique),'trigramPostingCount':trigrams,'retainedBytes':retained,'expectedFileBytes':expected,'sizeMatches':expected==p.stat().st_size,'storedCRC32asLong':struct.unpack('>q',footer)[0],'identitySidecarMatches':header[16:48].hex()==row['files'][names[1]].get('hex')}
 rows.append(row)
summary={'graphs':len(rows),'indexPresent':sum('header'in r for r in rows),'allMagicGRCS':all(r.get('header',{}).get('magic')=='0x47524353' for r in rows),'versions':sorted({r['header']['version'] for r in rows if 'header'in r}),'indexBytes':sum(r['files'][names[0]].get('bytes',0) for r in rows),'headerCallSites':sum(r['header']['callSites'] for r in rows if 'header'in r),'headerStrings':sum(r['header']['strings'] for r in rows if 'header'in r),'headerTrigramPostings':sum(r['header']['trigramPostingCount'] for r in rows if 'header'in r),'allSizesMatch':all(r.get('header',{}).get('sizeMatches',False) for r in rows),'allIndexIdentitySidecarsMatch':all(r.get('header',{}).get('identitySidecarMatches',False) for r in rows),'fullCRCValidated':False,'postingsValidated':False,'manifestSHA256':hashlib.sha256((root/'graphs.tsv').read_bytes()).hexdigest()}
out.write_text(json.dumps({'summary':summary,'graphs':rows},indent=2)+'\n');print(json.dumps(summary,indent=2))

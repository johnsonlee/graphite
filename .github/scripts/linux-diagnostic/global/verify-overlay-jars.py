#!/usr/bin/env python3
"""Verify exact original CI canonical JAR and all non-harness entry payloads. No Java."""
from pathlib import Path
import argparse,hashlib,importlib.util,json,re,struct,zipfile
p=argparse.ArgumentParser();p.add_argument('--original',required=True);p.add_argument('--overlay',required=True);p.add_argument('--canonical-hasher',required=True);p.add_argument('--expected-original',required=True);p.add_argument('--output',required=True);a=p.parse_args()
spec=importlib.util.spec_from_file_location('canonical',a.canonical_hasher);mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
def entries(path):
 with open(path,'rb') as f,zipfile.ZipFile(f) as z:
  pairs=[(x.filename,mod.read_entry(f,x)) for x in z.infolist()]
 h=hashlib.sha256()
 for n,b in sorted(pairs):mod.frame(h,n.encode());mod.frame(h,b)
 grouped={}
 for name,content in pairs:grouped.setdefault(name,[]).append(content)
 return {name:sorted(values) for name,values in grouped.items()},h.hexdigest()
meta_spec=importlib.util.spec_from_file_location('class_metadata',Path(__file__).with_name('class-metadata.py'))
meta_mod=importlib.util.module_from_spec(meta_spec);meta_spec.loader.exec_module(meta_mod)
def attribution(name,data):
 if not name.endswith('.class'):return None
 m=meta_mod.class_metadata(data);src=m.get('sourceFile')
 if src=='LargeBroadQueryPressureBenchmark.kt':return {'reason':'harness SourceFile','metadata':m}
 if name.startswith('io/johnsonlee/graphite/webgraph/jmh_generated/LargeBroadQueryPressureBenchmark_') and src is not None and src.startswith('LargeBroadQueryPressureBenchmark_') and src.endswith('.java'):
  return {'reason':'specific JMH generated benchmark wrapper','metadata':m}
 # Kotlin sortedBy inlines use Comparisons.kt as SourceFile. Require the actual enclosing
 # benchmark method, descriptor, exact class-name shape and SMAP owner, not a loose prefix.
 owner='io/johnsonlee/graphite/webgraph/LargeBroadQueryPressureBenchmark'
 method=m.get('enclosingMethod');descriptors={'canonical':'(Ljava/lang/Object;)Ljava/lang/String;','canonicalResult':'(Lio/johnsonlee/graphite/cypher/CypherResult;)[B'}
 pattern=re.escape(owner)+r'\$(?:canonical\$|canonicalResult\$lambda\$[0-9]+\$)\$inlined\$sortedBy\$1.class'
 if src=='Comparisons.kt' and re.fullmatch(pattern,name) and m.get('className')+'.class'==name and m.get('enclosingClass')==owner and method in descriptors and m.get('enclosingDescriptor')==descriptors[method] and ('LargeBroadQueryPressureBenchmark.kt\n'+owner+'\n') in m.get('sourceDebugExtension',''):
  return {'reason':'exact benchmark sortedBy inline: EnclosingMethod + descriptor + SMAP + class shape','metadata':m}
 return None
b,bhash=entries(a.original);c,chash=entries(a.overlay)
# Always retain mismatch receipt before refusing capture.
result={'passed':False,'originalCanonicalSha256':bhash,'expectedOriginalCanonicalSha256':a.expected_original,'overlayCanonicalSha256':chash,'originalMatchesExpected':bhash==a.expected_original,'productionPayloadChanges':[],'harnessPayloadChanges':[],'unchangedEntries':0}
for name in sorted(set(b)|set(c)):
 bv,cv=b.get(name),c.get(name)
 if bv==cv:result['unchangedEntries']+=1;continue
 record={'name':name,'before':[hashlib.sha256(v).hexdigest() for v in bv] if bv is not None else None,'after':[hashlib.sha256(v).hexdigest() for v in cv] if cv is not None else None}
 record['beforeAttribution']=[attribution(name,v) for v in bv] if bv is not None else None
 record['afterAttribution']=[attribution(name,v) for v in cv] if cv is not None else None
 if (bv is None or all(record['beforeAttribution'])) and (cv is None or all(record['afterAttribution'])):
  result['harnessPayloadChanges'].append(record)
 else:result['productionPayloadChanges'].append(record)
result['passed']=result['originalMatchesExpected'] and not result['productionPayloadChanges'] and bool(result['harnessPayloadChanges'])
Path(a.output).write_text(json.dumps(result,indent=2)+'\n')
assert result['passed'], 'JAR identity/payload check failed; inspect receipt, do not capture under original146 identity'
print(json.dumps({k:v for k,v in result.items() if k not in ('harnessPayloadChanges','productionPayloadChanges')},indent=2))

from pathlib import Path
import hashlib,json,shutil,xml.etree.ElementTree as E,zipfile
p=Path(__file__).resolve().parent;c=p/'candidate'
assert json.loads((p/'checks-exit.json').read_text())['exitCode']==0
for x in json.loads((p/'checks-inputs.json').read_text()):assert hashlib.sha256((c/x['path']).read_bytes()).hexdigest()==x['sha256'],x['path']
receipt={'tests':{},'jars':{},'sourceUnchangedDuringChecks':True,'checksLog':'checks.log','acceptance':False}
for mod in ['core','cypher','webgraph','explore']:
 counts={k:0 for k in ['tests','failures','errors','skipped']}
 src=c/f'graphite-{mod}'/'build/test-results'
 for xml in src.rglob('TEST-*.xml'):
  a=E.parse(xml).getroot().attrib
  for k in counts:counts[k]+=int(a[k])
 assert counts['tests']>0 and sum(counts[k] for k in ['failures','errors','skipped'])==0,counts
 receipt['tests'][mod]=counts
 shutil.copytree(src,p/'checks-test-results'/mod)
for side,modules,name in [('webgraph',['core','cypher','webgraph'],'candidate-final-jmh.jar'),('explore',['core','cypher','webgraph','explore'],'candidate-final-explore-jmh.jar')]:
 jar=c/f'graphite-{side}'/'build/libs'/f'{side}-1.0.0-SNAPSHOT-jmh.jar';target=p/name;assert not target.exists();shutil.copyfile(jar,target);target.chmod(0o444)
 classes={}
 for module in modules:
  for language in ['kotlin','java']:
   base=c/f'graphite-{module}'/'build/classes'/language/'main'
   for cls in base.rglob('*.class'):
    rel=str(cls.relative_to(base));b=cls.read_bytes()
    if rel in classes:assert classes[rel]==b
    classes[rel]=b
 with zipfile.ZipFile(target) as z:
  names=[i.filename for i in z.infolist()]
  for rel,b in classes.items():assert names.count(rel)==1 and z.read(rel)==b,rel
 receipt['jars'][side]={'path':str(target),'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'uniqueCompiledClassesChecked':len(classes)}
receipt['totalTests']=sum(v['tests'] for v in receipt['tests'].values())
(p/'final-build-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt))

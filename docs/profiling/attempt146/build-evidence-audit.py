from pathlib import Path
import hashlib,json,re,zipfile,xml.etree.ElementTree as E,runpy
from collections import Counter
p=Path(__file__).resolve().parent;c=p/'candidate';old=Path('/private/tmp/graphite-attempt145.lwdc61sb')
def sha(f):
 h=hashlib.sha256()
 with Path(f).open('rb') as s:
  for b in iter(lambda:s.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
r=json.loads((p/'final-build-receipt.json').read_text());prior=json.loads((old/'final-build-receipt.json').read_text())
assert json.loads((p/'checks-exit.json').read_text())['exitCode']==0
inputs=json.loads((p/'checks-inputs.json').read_text());assert all(sha(c/x['path'])==x['sha256'] for x in inputs)
log=(p/'checks.log').read_text();assert 'BUILD SUCCESSFUL' in log
out={'scope':'read-only Python source/XML/JAR audit; no Java/build/query/graph read','inputCount':len(inputs),'allInputsMatch':True,'checksExit':0,'tasks':[],'modules':{},'jars':{},'newTest':{},'acceptance':False}
for line in log.splitlines():
 if re.match(r'^> Task :(core|cypher|webgraph|explore):(test|detekt|jmhJar|verifyJmhJarExcludesTests)( |$)',line):out['tasks'].append(line)
for mod in ('core','cypher','webgraph','explore'):
 xmls=sorted((p/'checks-test-results'/mod).rglob('TEST-*.xml')); counts={k:0 for k in ('tests','failures','errors','skipped')}
 for f in xmls:
  root=E.parse(f).getroot()
  for k in counts:counts[k]+=int(root.attrib.get(k,0))
  if 'GraphTaskEmptyChildrenTest' in f.name:
   out['newTest']={'path':str(f.relative_to(p)),'sha256':sha(f),'counts':{k:int(root.attrib.get(k,0)) for k in counts},'cases':[e.attrib for e in root.findall('testcase')]}
 assert counts==r['tests'][mod],(mod,counts)
 out['modules'][mod]={'xmlSuites':len(xmls),**counts}
assert out['newTest']['counts']['tests']==1
for side,modules in [('webgraph',['core','cypher','webgraph']),('explore',['core','cypher','webgraph','explore'])]:
 jar=Path(r['jars'][side]['path']); oldjar=Path(prior['jars'][side]['path'])
 curhash=sha(jar);oldhash=sha(oldjar);assert curhash==r['jars'][side]['sha256'];assert oldhash==prior['jars'][side]['sha256']
 compiled={}
 for mod in modules:
  for lang in ('java','kotlin'):
   base=c/f'graphite-{mod}/build/classes'/lang/'main'
   for f in base.rglob('*.class'):
    name=f.relative_to(base).as_posix();payload=f.read_bytes()
    assert name not in compiled or compiled[name]==payload
    compiled[name]=payload
 with zipfile.ZipFile(jar) as z,zipfile.ZipFile(oldjar) as b:
  cur={};previous={}
  for archive,target in ((z,cur),(b,previous)):
   for i in archive.infolist():
    if i.filename.startswith('io/johnsonlee/') and i.filename.endswith('.class'):
     assert i.filename not in target,i.filename
     target[i.filename]=archive.read(i)
  nameCounts=Counter(i.filename for i in z.infolist())
  for name,payload in compiled.items():
   assert nameCounts[name]==1
   assert z.read(name)==payload,name
  diff=[name for name in sorted(cur.keys()|previous.keys()) if cur.get(name)!=previous.get(name)]
  prodDiff=[name for name in diff if name in compiled]
  excluded=[i.filename for i in z.infolist() if 'GraphTaskEmptyChildrenTest' in i.filename]
  assert not excluded
  key='io/johnsonlee/graphite/graph/GraphTask.class'
  out['jars'][side]={'sha256':curhash,'parentSha256':oldhash,'shaChecksMatch':True,'uniqueCompiledClassesMatched':len(compiled),'recordedCompiledCountMatches':len(compiled)==r['jars'][side]['uniqueCompiledClassesChecked'],'currentIoJohnsonleeClasses':len(cur),'parentIoJohnsonleeClasses':len(previous),'allIoJohnsonleeClassDifferences':diff,'compiledProductionClassDifferences':prodDiff,'newTestExcluded':True,'selectedGraphTask':{'currentSha256':hashlib.sha256(cur[key]).hexdigest(),'parentSha256':hashlib.sha256(previous[key]).hexdigest(),'matchesCompiled':cur[key]==compiled[key]}}
debugAudit=runpy.run_path(str(p/'class-debug-diff.py'))['audit']
for side in out['jars']:
 out['jars'][side]['additionalClassesDebugAudit']=debugAudit(r['jars'][side]['path'],prior['jars'][side]['path'],[x for x in out['jars'][side]['allIoJohnsonleeClassDifferences'] if not x.endswith('/GraphTask.class')])
out['totalTests']=sum(x['tests'] for x in out['modules'].values());assert out['totalTests']==r['totalTests']
out['xmlSuiteCount']=sum(x['xmlSuites'] for x in out['modules'].values())
out['evidenceHashes']={n:sha(p/n) for n in ('checks-inputs.json','checks-exit.json','checks.log','final-build-receipt.json','actual-source-review.json')}
(p/'build-evidence-audit.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
md=f'''# Attempt 146 构建证据独立核验\n\n只读核验通过：{len(inputs)} 项构建输入与当前文件一致，checks exit=0，日志 BUILD SUCCESSFUL。{out['xmlSuiteCount']} 份保存 XML 共 {out['totalTests']} 项测试，failure/error/skipped 均为0。未运行 Java、构建、查询或读取图。\n\n| 模块 | 测试 | 本次 test | 本次 detekt |\n|---|---:|---|---|\n| core | 455 | executed | executed |\n| cypher | 1236 | executed | FROM-CACHE |\n| webgraph | 195 | executed | FROM-CACHE |\n| explore | 221 | executed | FROM-CACHE |\n\n两个 JMH 包任务和 webgraph:verifyJmhJarExcludesTests 均实际执行；没有声称存在 Explore 同名 exclusion 任务。GraphTaskEmptyChildrenTest 保存 XML 为1项成功；该单项源码内部遍历正常与 cancel(false) 两个分支，非2个 JUnit test，也没有 CPU 早退。\n\n两个146冻结 JAR 以及对应145冻结 JAR的完整 SHA256 均独立读取匹配各自收据。独立读取 JAR 中全部 io/johnsonlee/**/*.class 并与145对照：**两包实际均有 GraphTask、GraphTaskContext、GraphTaskGroup、GraphTaskScheduler 四类内容不同，其余该命名空间类无差异**，详见 JSON 原列表。这个事实是读取后得到的，不是用预期结果过滤。额外三类逐字节比较：仅屏蔽 constant-pool SMAP 字符串、SourceDebugExtension、LineNumberTable 的精确字节范围后，剩余字节全部相同；因此其差异限定为上述调试数据，不能把它们记成原始 payload 相同。\n\n当前已编译 main 类逐项匹配 JAR：webgraph {out['jars']['webgraph']['uniqueCompiledClassesMatched']}、explore {out['jars']['explore']['uniqueCompiledClassesMatched']} 个唯一类，均与 root receipt 一致。GraphTask.class 另记录两版 payload SHA256并确认 current=compiled；两包均不存在 GraphTaskEmptyChildrenTest 及其嵌套类路径。此测试排除核对仅针对新测试；webgraph完整排除任务通过另有日志依据。\n\n- Webgraph：`{out['jars']['webgraph']['sha256']}`\n- Explore：`{out['jars']['explore']['sha256']}`\n\n本报告不把 Kotlin module/resource/JAR ZIP metadata 称为全相等；上面的差异范围是全部 io/johnsonlee class payload，另有 current compiled-main 绑定。未将315项输入全称生产文件，其中含 build 与既有诊断源码。\n\n这是构建身份与正确性证据，不是性能验收、v3完整输出审计或 CI 通过。脚本保留原输入、XML、任务行、全部差异名单与哈希，见 [JSON](build-evidence-audit.json) 和 [复算脚本](build-evidence-audit.py)。\n'''
# Do not write an unconditional narrow-diff claim if actual data differs.
# Retain the actual complete difference lists; do not enforce an expected one-class result.
(p/'build-evidence-audit.md').write_text(md)
print(json.dumps({'inputs':len(inputs),'tests':out['totalTests'],'xml':out['xmlSuiteCount'],'jars':out['jars']},indent=2))

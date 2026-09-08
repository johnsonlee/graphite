"""Bounded correctness cases for Java Pattern and original public Cypher."""
from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
workload_path=REPO/'graphite-server/internal/benchmarkcase/testdata/main64.json'
workload=json.loads(workload_path.read_text())
actual=[dict(index=i,case=c) for i,c in enumerate(workload['cases']) if c['family']=='regex']
assert [c['index'] for c in actual]==[892,893,894]
literals=['GraphitePressureAbsent45zeroX','org.apache.tika.','org']
patterns=[]
def p(name,pattern,literal=None,eligible=False):patterns.append(dict(name=name,pattern=pattern,literal=literal,eligiblePattern=eligible))
for name,literal in zip(['main-zero','main-targeted','main-dense'],literals):p(name,r'.*\Q'+literal+r'\E.*',literal,True)
for i,literal in enumerate(['a.b*+?^$[](){}|','back\\slash','tail\\','double\\\\slash','tab\tvalue','nul\0value','vertical\vform\f','DEL\x7fvalue']):p(f'quoted-ascii-{i}',r'.*\Q'+literal+r'\E.*',literal,True)
for name,pattern in [
 ('empty-quote',r'.*\Q\E.*'),('embedded-end-quote',r'.*\Qfoo\Ebar\E.*'),
 ('embedded-end-valid',r'.*\Qfoo\E.*\Qbar\E.*'),('quoted-embedded-end',r'.*\Qfoo\E\\E\Qbar\E.*'),
 ('case-insensitive',r'(?i).*\Qorg\E.*'),('dotall',r'(?s).*\Qorg\E.*'),
 ('multiline',r'(?m).*\Qorg\E.*'),('unix-lines',r'(?d).*\Qorg\E.*'),
 ('unicode-case',r'(?iu).*\Qorg\E.*'),('unicode-classes',r'(?U).*\Qorg\E.*'),
 ('anchors',r'^.*\Qorg\E.*$'),('alternative',r'.*\Qorg\E.*|x'),
 ('reluctant-suffix',r'.*\Qorg\E.*?'),('prefix-variant',r'.*?\Qorg\E.*'),
 ('missing-end-quote',r'.*\Qorg.*'),('malformed-class','['),('malformed-group','('),
 ('trailing-backslash','\\'),('extra-suffix',r'.*\Qorg\E.*z'),
 ('literal-cr','.*\\Q\r\\E.*'),('literal-lf','.*\\Q\n\\E.*'),
 ('literal-nonascii',r'.*\Qé\E.*'),('literal-supplementary',r'.*\Q😀\E.*'),
 ('literal-high-surrogate','.*\\Q\ud800\\E.*'),('literal-low-surrogate','.*\\Q\udc00\\E.*')]:p(name,pattern)

cases=[]
def direct(name,pattern,text,eligible,literal=None):
 cases.append(dict(name=name,pattern=pattern,text=text,eligiblePattern=eligible,
                   eligibleInput=all(ord(c)<128 and c not in '\r\n' for c in text),literal=literal))
for pattern in patterns:
 literal=pattern['literal'] if pattern['literal'] is not None else 'org'
 inputs=[('empty',''),('miss','XYZ'),('exact',literal),('start',literal+'tail'),('middle','head'+literal+'tail'),('end','head'+literal),('repeated',literal+literal),('upper','ORG')]
 if pattern['name'].startswith('main-'):
  for label,value in [('cr','\r'),('lf','\n'),('crlf','\r\n'),('nel','\u0085'),('ls','\u2028'),('ps','\u2029'),('nonascii','é'),('supplementary','😀'),('high','\ud800'),('low','\udc00'),('tab','\t'),('vertical','\v'),('formfeed','\f'),('nul','\0'),('del','\x7f')]:
   for position,text in [('before',value+literal),('after',literal+value)]:inputs.append((label+'-'+position,text))
  inputs.append(('inserted-lf',literal[:1]+'\n'+literal[1:]));inputs.append(('two-surrogates','\ud800'+literal+'\udc00'))
 elif not pattern['eligiblePattern']:
  inputs += [('cr','\rorg'),('lf','org\n'),('nel','\u0085org'),('ls','org\u2028'),('ps','org\u2029'),('unicode','é😀org'),('surrogate','\ud800org'),('embedded-E','foo\\Ebar'),('foobar','foobar')]
 for label,text in inputs:direct(pattern['name']+'-'+label,pattern['pattern'],text,pattern['eligiblePattern'],pattern['literal'])
public=[]
def cy(name,query,parameters=None,directName=None):public.append(dict(name=name,query=query,parameters=parameters or {},directPatternCase=directName))
# Public parser/evaluator controls reuse exact direct inputs, preserving UTF16 units.
selected=[c for c in cases if c['name'].startswith('main-') and any(c['name'].endswith('-'+tail) for tail in ['empty','exact','middle','repeated','cr-before','lf-after','nel-before','ls-after','ps-after','nonascii-before','supplementary-after','high-before','low-after','vertical-before','nul-after'])]
for c in selected:cy('public-'+c['name'],'RETURN $text =~ $pattern AS matched',dict(text=c['text'],pattern=c['pattern']),c['name'])
for index,query in enumerate([
 'RETURN null =~ (1/0) AS matched','RETURN 17 =~ (1/0) AS matched',
 "RETURN 'org' =~ (1/0) AS matched",'RETURN null =~ unknownRegexFunction() AS matched',
 "RETURN 'org' =~ null AS matched","RETURN 'org' =~ 17 AS matched",
 "RETURN null =~ '[' AS matched","RETURN 'org' =~ '[' AS matched",
 "RETURN true OR ('org' =~ '[') AS matched","RETURN false OR ('org' =~ '[') AS matched",
 "RETURN ('org' =~ '[') OR (1/0=0) AS matched","RETURN (1/0=0) OR ('org' =~ '[') AS matched",
 "RETURN true OR (null =~ (1/0)) AS matched","RETURN null OR ('org' =~ '[') AS matched",
 "RETURN ('org' =~ $pattern) OR (1/0=0) AS matched","RETURN (1/0=0) OR ('org' =~ $pattern) AS matched",
 "MATCH (n:IntConstant) WHERE ('org' =~ $pattern) OR (1/0=0) RETURN n LIMIT 1",
 "MATCH (n:IntConstant) WHERE (1/0=0) OR ('org' =~ $pattern) RETURN n LIMIT 1",
 "MATCH (n:IntConstant) WHERE true OR ('org' =~ '[') RETURN n LIMIT 1",
 "MATCH (n:IntConstant) WHERE null =~ (1/0) RETURN n LIMIT 1",
]):cy(f'public-order-{index}',query,dict(pattern=r'.*\Qorg\E.*'))
for item in actual:cy('actual-main64-'+str(item['index']),item['case']['query'],item['case']['parameters'])
for name,value in [('pattern-cases.json',cases),('public-cases.json',public),('actual-main64.json',dict(workload=str(workload_path),workloadSha256=hashlib.sha256(workload_path.read_bytes()).hexdigest(),cases=actual))]:
 (HERE/name).write_text(json.dumps(value,ensure_ascii=True,indent=2)+'\n')
print('Java Pattern cases',len(cases),'public Cypher cases',len(public))

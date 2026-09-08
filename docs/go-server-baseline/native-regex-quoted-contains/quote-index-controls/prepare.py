"""Independent existing Java quote-rewrite error-index compatibility controls."""
from pathlib import Path
import json
HERE=Path(__file__).resolve().parent
cases=[]
def add(name,pattern,text=''):cases.append(dict(name=name,pattern=pattern,text=text))
bodies=['foo','a.b','1foo','\\foo','é','😀','\ud800','\udc00','!!!','!!!!!!!!']
for i,body in enumerate(bodies):
 for j,tail in enumerate(['bar\\E.*','[','(','\\y']):add(f'body-{i}-tail-{j}','.*\\Q'+body+'\\E'+tail)
for i,prefix in enumerate(['','\\\\','\\.','[a]']):
 for j,body in enumerate(['1foo','a.b','\\foo','😀']):add(f'prefix-{i}-body-{j}',prefix+'\\Q'+body+'\\E[')
for i,pattern in enumerate([
 r'\\Qfoo\E',r'\Q\E[',r'\Q\E\Q1\E[',r'\x\Q1\E[',r'\0\Q1\E[',
 r'(?x)\Q #\E[','\\Q\t.\\E[',r'\Q\t.\E[',
 r'\Qa.b\E\Q1c\E\y',r'.*\Qfoo\Ebar\E.*',r'\Qfoo',r'.*\Qfoo.*',
 r'\Q1\E(',r'\Q!!!\E[',r'\Q!!!!!!!!\E[',r'\Q\E',r'\Q😀\E(',
 r'\Q😀\E\Q1.\E[',r'\Qé\E\Q!\E[',r'\Q\E\Q\E[',r'\Q\\\E[',
]):add(f'focused-{i}',pattern)
assert len(cases)==77 and len({c['name'] for c in cases})==77
(HERE/'cases.json').write_text(json.dumps(cases,ensure_ascii=True,indent=2)+'\n')
(HERE/'empty-public.json').write_text('[]\n')
print('Independent quote-index controls',len(cases))

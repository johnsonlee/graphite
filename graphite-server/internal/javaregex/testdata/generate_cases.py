import json,pathlib
cases=[]
def add(category,pattern,text,gap=False):
 cases.append(dict(name=f'{category}-{len(cases):03}',pattern=pattern,text=text,knownGap=gap))
for p,t in [('abc','abc'),('abc','xabc'),('abc','abcx'),('',''),('a|ab','ab'),('^a$','a\n'),('a$','a\n'),('(?m)^a$\n^b$','a\nb'),('(?m)^a$\r\n^b$','a\r\nb'),('.','\r'),('.','\u0085'),('(?d).','\r'),('(?s).','\n'),('a\\Z','a'),('a\\Z\\r\\n','a\r\n'),('\\Aa\\z','a'),('\\Ga','a'),('(a)(b)\\2\\1','abba'),('(?<x>a)(b)\\1\\2','abab'),('(?<x>a)\\k<x>','aa'),('(a)?\\1',''),('\\1(a)','a'),('(a)\\12','aa2'),('(a|(b))\\2','aa'),('(a(b)?)+\\2','abab'),('(?=ab)ab','ab'),('(?!b)a','a'),('a(?<=a)b','ab'),('a(?<!b)b','ab'),('a+(?<=a+)b','aaab'),('(?=(a+))\\1','aaa'),('(?>a|ab)c','abc'),('(a|ab)c','abc'),('a*+a','aaa'),('a*?a','aaa'),('a{2,4}+a','aaaaa'),('a{2,4}+a','aaaa'),('(?:){2}',''),('(a?)*','a'),('(a?)*?',''),('\\Qabc\\E+','abcc'),('\\Q.*[','.*['),('\\Q\\Ea','a'),('a(?i)b','aB'),('(?i:a)b','Ab'),('(?i:a)b','AB'),('(?i)a|b','B'),('(?i)[a-z]+','HELLO'),('(?i)ä','Ä'),('(?iu)ä','Ä'),('(?iu)k','K'),('(?iu)[K]','k'),('(?i)[^a]','A'),('(?U)\\w+','你好'),('\\w+','你好'),('\\bé\\b','é'),('\\bé́\\b','é́'),('(?U-u)(?i)ä','Ä'),('(?x)a # hi\n b','ab'),('(?x)[a b]',' '),('(?x)[a b]','b'),('(?x)\\Q a # b \\E',' a # b '),('[a-d[m-p]]+','admp'),('[a-z&&[^bc]]+','az'),('[a-z&&[^bc]]+','abc'),('[a-z&&[def]]+','fed'),('[^a-c&&[^b]]','b'),('[]a]+',']a'),('[a-]+','a-'),('[-a]+','a-'),('[\\x41-\\x43]','B'),('\\x{1F600}','😀'),('\\uD83D\\uDE00','😀'),('.','😀'),('.{2}','😀'),('\\101','A'),('\\0101','A'),('\\cA','\x01'),('\\R','\r\n'),('\\R\\n','\r\n'),('\\h','\u180e'),('\\v','\x0b')]:add('semantic',p,t)
for p in ['\\d','(?U)\\d','\\s','(?U)\\s','\\w','(?U)\\w','\\p{Lower}','(?U)\\p{Lower}','\\p{Lu}','(?iu)\\p{Lu}','\\p{L}','\\p{IsLatin}','\\p{sc=Grek}','\\p{InBasic_Latin}','\\p{javaWhitespace}','\\p{IsAlphabetic}','\\p{IsJoin_Control}','\\p{IsHex_Digit}','\\p{IsAssigned}','\\p{Cn}','\\P{L}']:
 for t in ['A','a','é','Ω','١','\u00a0','\u200c','😀','\U0001fae0']:
  add('unicode',p,t)
for p in ['[','(','a)','*a','a**','a{','a{2,1}','a{999999999999}','[z-a]','\\','\\j','\\0','\\xG1','\\u123','\\p{Bogus}','(?z)a','(?<1>a)','(?<a>a)(?<a>b)','\\k<x>','(?P<x>a)','(?#comment)a','(?(1)a|b)','a{1','[\\b]','(?<a>a','[a&&]']:
 add('syntax',p,'')
for p,t in [('(?c)é','é'),('\\X','👩\u200d💻'),('a\\b{g}','a'),('\\N{LATIN CAPITAL LETTER A}','A')]:add('advanced',p,t)

for p,t in [
 ('(?c)é','é'),('(?c)é','é'),('(?c)[é]','é'),('(?c)[e]','é'),('(?c)[é]+','éé'),('(?c)[é]','é'),('(?c)\\p{L}','é'),('(?c)\\w','é'),('(?c)[Å]','Å'),('(?c)[Å]','Å'),('(?c)[가]','가'),('(?c)[é]x','éx'),('(?c)[é]́','é́'),('(?c)[ậ]','ậ'),('(?c)[ậ]','ậ'),
 ('\\X',''),('\\X','á'),('\\X','ab'),('\\X{2}','áb'),('\\X','\r\n'),('\\X','각'),('\\X','🇺🇸'),('\\X{2}','🇺🇸🇨'),('\\X','👩\u200d💻'),('\\X','\u0600a'),('\\X','\u0600👩\u200d💻'),('a\\b{g}́','á'),('á\\b{g}','á'),('\\b{g}',''),('\\b{g}a','a'),('\\b{g}\\X\\b{g}','á'),
 ('\\N{latin capital letter a}','A'),('\\N{ CJK UNIFIED IDEOGRAPHS 4E00 }','一'),('\\N{GRINNING FACE}','😀'),('[\\N{LATIN CAPITAL LETTER A}-Z]','C'),
 ('[a&&]','a'),('[&&a]','a'),('[a&&b]','a'),('[a-z&&[^aeiou]&&[^x]]+','bc'),('[\\Q]a-\\E]+',']a-'),('(?x)[\\Q # \\E]+',' # ')
]:add('advanced',p,t)
for p in ['\\N','\\N{','\\N{BOGUS}','\\b{bad}','[\\X]','[\\R]','(?c','(?-c)a','a{1,999999999999}']:
 add('advanced-syntax',p,'')


# Deterministic small-pattern exhaustive correctness, never performance evidence.
import itertools
texts=['']+[''.join(v) for n in range(1,4) for v in itertools.product('abé\n',repeat=n)]
patterns=['(a|ab)*','(ab|a)*','(a?)*','(a?)+','(a?){2}','(a*)*','(a|)*','(?:a|b){1,3}?','(?:a|b){1,3}+','(?=(a*))a*','(?!(a+))b*','a*(?<=a*)b','a*(?<!a+)b','(a(b)?)+\\2','(a|b)\\1','((a)|b)+\\2','(a|ab)++b','(?>a*)a','(?i)(a)\\1','(?iu)[^é]','[^a-z&&[^b]]','[a&&]','[&&a]','[&&]','[a&&&&b]','[a-d[b-c]e]','[a-z&&[b-d]e]','\\Qab\\E*','[\\Qab\\E]+','(?x)[ a b ]+','(?m)^.*$','(?d).+','\\b.*\\b','\\B.*\\B','(?U)\\w+','(?c)[é]+','(?s).*?','a{0,0}b*','(?:a?){0,2}?','(?=(a|ab))\\1','a*(?<=a{1,2})b']
for p in patterns:
 for text in texts:add('exhaustive',p,text)
for p in ['😀[','😀(','😀a{','(?<a_>x)','(?<a>x)\\k<missing>','(?<=a*)','(?<=(a)\\1)b','(?<=a*b*)c','(?q)','(?i-)','(?-i)','(?-)','[a-\\d]','[\\d-a]','a{,2}','a{2,x}','a{2 3}','\\x{}','\\p{}']:
 add('syntax-extra',p,'')


for p in ['(?i)\\p{Lu}','(?i)\\p{javaLowerCase}','(?i)\\p{IsLowercase}','(?iu)\\w','(?iu)[\\w]','(?iu)\\p{Lower}','(?i)\\p{LC}','\\p{LD}','\\p{L1}','\\p{all}','\\p{javaAlphabetic}','\\p{javaIdeographic}','\\p{IsGraph}','\\p{IsPrint}','\\p{IsAlpha}','\\p{IsLower}','\\p{IsSpace}','\\p{sc=Beng}','\\p{InBasic-Latin}']:
 for text in ['a','é','K','ı','İ','ʰ','\u200d','\ue000','অ','\u2028']:add('property-extra',p,text)
for p in ['(?)','(?--)','(?i--)','(?i-u)','[a&&&&]']:add('syntax-more',p,'')

pathlib.Path(__file__).with_name('cases.json').write_text(json.dumps(cases,ensure_ascii=False,indent=2)+'\n')

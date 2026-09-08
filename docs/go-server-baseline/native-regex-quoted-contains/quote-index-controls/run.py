"""Actual Java Pattern correctness only; original helper's public case list is empty."""
from pathlib import Path
import argparse,hashlib,json,subprocess
HERE=Path(__file__).resolve().parent
JAR=Path('/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def write(p,v):p.write_text(json.dumps(v,indent=2)+'\n')
def run(root):
 out=root.resolve();out.mkdir(parents=True,exist_ok=False);classes=out/'classes';classes.mkdir();(out/'empty-fixtures').mkdir()
 assert sha(JAR)=='91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d'
 settings=subprocess.run(['java','-XshowSettings:properties','-version'],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True).stdout
 home=Path(next(l.split('=',1)[1].strip() for l in settings.splitlines() if l.strip().startswith('java.home =')))
 inputs={str(p):sha(p) for p in [JAR,HERE.parent/'RegexQuotedOracle.java',HERE/'prepare.py',HERE/'run.py',HERE/'cases.json',HERE/'empty-public.json',home/'bin/java',home/'lib/modules',home/'lib/server/libjvm.dylib',home/'release']}
 commands=dict(compile=['javac','-cp',str(JAR),'-d',str(classes),str(HERE.parent/'RegexQuotedOracle.java')],
               run=[str(home/'bin/java'),'-Xmx512m','-cp',str(classes)+':'+str(JAR),'RegexQuotedOracle',str(HERE/'cases.json'),str(HERE/'empty-public.json'),str(out/'empty-fixtures'),str(out/'capture')])
 codes={}
 for phase,command in commands.items():
  with (out/(phase+'.stdout')).open('x') as stdout,(out/(phase+'.stderr')).open('x') as stderr:result=subprocess.run(command,stdout=stdout,stderr=stderr)
  codes[phase]=result.returncode;result.check_returncode()
 assert all(sha(Path(p))==h for p,h in inputs.items())
 assert json.loads((out/'capture/public-main.json').read_text())['cases']==[]
 write(out/'receipt.json',dict(commands=commands,exitCodes=codes,javaVersion=settings,inputs=inputs,inputsUnchanged=True,compiledHelperSha256=sha(classes/'RegexQuotedOracle.class'),performanceMeasurements=0,graphLoads=0,publicQueries=0))
 print('Actual Java quote-index capture complete:',out)
if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('output',type=Path);args=parser.parse_args();run(args.output)

"""Rebuild native sidecars and make the pinned Java reader consume them.
Use --output for a new evidence directory; do not overwrite frozen captures.
"""
import argparse,json,os,pathlib,subprocess,tempfile,hashlib
h=pathlib.Path(__file__).resolve().parent
parser=argparse.ArgumentParser();parser.add_argument('--output',type=pathlib.Path,required=True);args=parser.parse_args();out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
jdk=pathlib.Path('/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home/bin');jar='/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar';commands=[]
with tempfile.TemporaryDirectory(prefix='ordinary-writer-verification-')as tmp:
 work=pathlib.Path(tmp);classes=work/'classes';classes.mkdir();fixtures=work/'fixtures'
 environment=dict(os.environ,ORDINARY_FIXTURE_OUTPUT=str(fixtures))
 command=['go','test','./internal/query','-run','TestOrdinaryProjectionDenseHistoryMain','-count=1']
 with(out/'native-fixture-build.log').open('w')as log:subprocess.run(command,cwd=h.parents[3],env=environment,stdout=log,stderr=log,check=True)
 subprocess.run([str(jdk/'javac'),'-cp',jar,'-d',str(classes),str(h/'HistoryOracle.java'),str(h/'WriterRoundTripOracle.java')],check=True)
 for name in ['clean-missing','return-sid-missing']:
  command=[str(jdk/'java'),'-Xmx256m','-cp',str(classes)+':'+jar,'WriterRoundTripOracle',str(fixtures/name),str(out/(name+'-main.json'))]
  with(out/(name+'-main.log')).open('w')as log:r=subprocess.run(command,stdout=log,stderr=log)
  assert r.returncode==0
  actual=json.loads((out/(name+'-main.json')).read_text());expected=json.loads((h/('dense-'+name+'-main.json')).read_text())[-1]['targets']
  assert actual['loadedFromPersistence'] is True
  assert [actual['queryResult'],actual['rawResult']]==expected
  commands.append(dict(command=command,exitCode=r.returncode,indexSHA256=hashlib.sha256((fixtures/name/'graph.callsite-string-index').read_bytes()).hexdigest(),loadedFromPersistence=True,fullResponsesEqual=True))
(out/'verification.json').write_text(json.dumps(commands,indent=2)+'\n');print('2 native sidecars loaded by pinned main; all 4 complete responses equal')

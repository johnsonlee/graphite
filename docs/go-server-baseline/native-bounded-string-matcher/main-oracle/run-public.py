#!/usr/bin/env python3
from pathlib import Path
import subprocess,json,hashlib,tempfile,shutil,datetime,struct
ROOT=Path(__file__).resolve().parent
REPO=ROOT.parents[3]
JAR=Path('/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar')
SHA='91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def inventory(root):return {str(p.relative_to(root)):sha(p) for p in sorted(root.rglob('*')) if p.is_file()}
commands=[]
def run(argv,name):
    r=subprocess.run(list(map(str,argv)),stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    (ROOT/(name+'.stdout')).write_text(r.stdout);(ROOT/(name+'.stderr')).write_text(r.stderr)
    commands.append({'argv':list(map(str,argv)),'exitCode':r.returncode,'stdout':name+'.stdout','stderr':name+'.stderr'})
    r.check_returncode()
start=datetime.datetime.now(datetime.timezone.utc).isoformat();assert sha(JAR)==SHA
run(['javac','-cp',JAR,'-d',ROOT/'classes',ROOT/'BoundedMatcherPublicOracle.java'],'public-compile')
originals=REPO/'graphite-server/internal/query/testdata/candidate-index'
evidence={}
with tempfile.TemporaryDirectory(prefix='graphite-bounded-public-') as tmp:
    tmp=Path(tmp)
    for count in [2,64]:
        for fixture in ['clean','bad-return-type','caller-name-max','caller-name-negative']:
            name=f'{count}-{fixture}';target=tmp/name
            for i in range(count):shutil.copytree(originals/'clean',target/f'g{i:02}')
            if fixture!='clean':
                offset=98 if fixture=='bad-return-type' else 74
                sid=-1 if fixture=='caller-name-negative' else 2147483647
                f=target/'g00/graph.nodedata';data=bytearray(f.read_bytes())
                assert struct.unpack_from('>i',data,offset)[0]==(8 if offset==98 else 7)
                struct.pack_into('>i',data,offset,sid);f.write_bytes(data)
            evidence[name]={'before':inventory(target)}
    run(['java','-cp',str(ROOT/'classes')+':'+str(JAR),'BoundedMatcherPublicOracle',tmp,ROOT/'public-responses.json'],'public-run')
    for name in evidence:
        evidence[name]['after']=inventory(tmp/name)
        assert evidence[name]['before']==evidence[name]['after']
(ROOT/'public-fixture-hashes.json').write_text(json.dumps(evidence,indent=2)+'\n')
assert sha(JAR)==SHA
responses=json.loads((ROOT/'public-responses.json').read_text());assert len(responses)==16
(ROOT/'public-receipt.json').write_text(json.dumps({'startedUtc':start,'endedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'jar':str(JAR),'jarSha256BeforeAndAfter':SHA,'inputHashes':{str(p.name):sha(p) for p in [ROOT/'BoundedMatcherPublicOracle.java',ROOT/'run-public.py']},'commands':commands,'publicObservations':16,'verified':True,'performanceMeasurement':False},indent=2)+'\n')
print('Actual main public oracle: 16 responses verified, all clone files unchanged.')

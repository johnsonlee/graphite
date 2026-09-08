"""Full native checks and statistical corruption tests before frozen sampling."""
import hashlib
import json
from pathlib import Path
import subprocess
BASE=Path(__file__).resolve().parent
ROOT=BASE.parents[2]
MODULE=ROOT/'graphite-server'
OUT=BASE/'tests'
OUT.mkdir()
paths=[p for p in MODULE.rglob('*') if p.is_file()]+[p for p in BASE.rglob('*.py') if p.is_file()]+list((BASE/'main-tooling').glob('*.java'))
inputs={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
(OUT/'inputs.json').write_text(json.dumps(inputs,indent=2)+'\n')
steps=[]
for command,cwd,logname in [(['go','test','-race','-count=1','./...'],MODULE,'race.log'),(['go','vet','./...'],MODULE,'vet.log'),(['python3',str(BASE/'test_summarize.py')],ROOT,'statistics.log'),(['python3',str(BASE/'main-tooling/build.py'),'--verify-only'],ROOT,'main-build-verification.log')]:
    with (OUT/logname).open('x') as log:
        result=subprocess.run(command,cwd=cwd,stdout=log,stderr=subprocess.STDOUT)
    steps.append(dict(command=command,cwd=str(cwd),exitCode=result.returncode,log=logname))
    unchanged=all(hashlib.sha256(Path(p).read_bytes()).hexdigest()==h for p,h in inputs.items())
    (OUT/'receipt.json').write_text(json.dumps(dict(steps=steps,inputsUnchanged=unchanged,inputCount=len(inputs)),indent=2)+'\n')
    print(steps[-1],flush=True)
    assert unchanged and result.returncode==0

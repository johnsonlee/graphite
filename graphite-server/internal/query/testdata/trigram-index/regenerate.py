#!/usr/bin/env python3
"""Reproduce the archived main evidence and A7 supplement, never a benchmark."""
import json,pathlib,subprocess,sys
r=pathlib.Path(__file__).resolve().parent;jar=str(pathlib.Path(sys.argv[1]).resolve())
subprocess.run([sys.executable,str(r/'run.py'),jar],check=True)
subprocess.run([sys.executable,str(r/'verify.py')],check=True)
def units(s):
 b=s.encode('utf-16-be','surrogatepass');return [int.from_bytes(b[i:i+2],'big') for i in range(0,len(b),2)]
cases=json.loads((r/'inputs.json').read_text())['cases']
for c in cases:c['params']={k:units(v) for k,v in c['params'].items()}
(r/'cases-units.json').write_text(json.dumps(cases,indent=2)+'\n')
subprocess.run([sys.executable,str(r/'supplement.py'),jar],check=True)

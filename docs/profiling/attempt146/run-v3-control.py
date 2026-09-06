from pathlib import Path
import subprocess,json
p=Path(__file__).resolve().parent;cmd=json.loads((p/'v3-control-command.json').read_text())
with (p/'v3-control.log').open('w') as log:r=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT)
(p/'v3-control-exit.json').write_text(json.dumps({'exitCode':r.returncode})+'\n');print(r.returncode)

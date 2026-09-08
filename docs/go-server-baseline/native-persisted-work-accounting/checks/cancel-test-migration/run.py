from pathlib import Path
import subprocess,time,json
p=Path(__file__).parent
r={"command":["go","test","-race","./internal/store","-run","^TestMainIndexCancellationDoesNotPublish$","-count=1","-v"],"cwd":"/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/graphite-server","started":time.time()}
with (p/"test.log").open("w") as f:x=subprocess.run(r["command"],cwd=r["cwd"],stdout=f,stderr=subprocess.STDOUT)
r.update(exit_code=x.returncode,finished=time.time());(p/"receipt.json").write_text(json.dumps(r,indent=2));print(json.dumps(r));raise SystemExit(x.returncode)

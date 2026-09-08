import subprocess,json,hashlib,datetime,pathlib,sys
root=pathlib.Path("/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/graphite-server")
out=pathlib.Path(__file__).parent
files=[root/"internal/query/main_fixed_workers.go",root/"internal/query/main_fixed_workers_test.go"]
before={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
command=["/opt/homebrew/bin/go","test","-race","./internal/query","-run","^TestMainFixed","-count=3","-v"]
receipt={"command":command,"cwd":str(root),"startedAt":datetime.datetime.now(datetime.timezone.utc).isoformat(),"sourceBefore":before}
(out/"focused-start.json").write_text(json.dumps(receipt,indent=2)+"\n")
with (out/"focused.log").open("w") as log:
 result=subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT)
receipt.update(exitCode=result.returncode,finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),sourceAfter={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in files})
receipt["sourcesUnchanged"]=receipt["sourceBefore"]==receipt["sourceAfter"]
(out/"focused-terminal.json").write_text(json.dumps(receipt,indent=2)+"\n")
print(json.dumps(receipt))
sys.exit(result.returncode)

from pathlib import Path
import subprocess,json,time,hashlib
out=Path(__file__).resolve().parent
cmd=['/opt/homebrew/Cellar/go/1.22.0/libexec/bin/go','test','-race','./internal/store','-run','Test(MainDistinctSplit|MainDistinctProjectionStringIDs|MainOrdinary|ProjectionContext|DistinctProjection)','-count=1','-timeout=180s','-v']
start=time.time()
with (out/'store-race.log').open('wb') as f:r=subprocess.run(cmd,cwd=out/'module',stdout=f,stderr=subprocess.STDOUT)
(out/'receipt.json').write_text(json.dumps({'command':cmd,'cwd':str(out/'module'),'exitCode':r.returncode,'elapsedSeconds':time.time()-start,'logSha256':hashlib.sha256((out/'store-race.log').read_bytes()).hexdigest()},indent=2)+'\n')
print((out/'receipt.json').read_text());print((out/'store-race.log').read_text()[-8000:]);raise SystemExit(r.returncode)

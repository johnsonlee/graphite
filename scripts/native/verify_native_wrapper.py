#!/usr/bin/env python3
"""Native HTML profiling through direct and argument-file wrapper routes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import time
import urllib.request


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo',type=Path,default=Path.cwd())
    parser.add_argument('--jar',type=Path,required=True)
    parser.add_argument('--native',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();repo=args.repo.resolve();jar=args.jar.resolve();native=args.native.resolve()
    args.output=args.output.resolve()
    args.output.mkdir(parents=True,exist_ok=True)
    base={k:v for k,v in os.environ.items() if k not in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','GRAPHITE_NATIVE_CPU_PROFILE','GRAPHITE_PROFILE')}
    base.update(JAVA_OPTS='-Xmx128m -XX:ActiveProcessorCount=2',GRAPHITE_SERVER_BINARY=str(native))
    query='UNWIND range(1,1000) AS n RETURN sum(sin(n)+cos(n)+sqrt(n)) AS total'
    records=[]
    try:
        for route in ('direct','nested-argument-file'):
            with tempfile.TemporaryDirectory(prefix='graphite real native profile ') as tmp:
                folder=Path(tmp);launcher=folder/'graphite'
                content=(repo/'scripts/native/graphite-launcher.sh.in').read_text()
                for token,value in {'@JAVA@':shutil.which('java'),'@JAR@':str(jar),'@NATIVE@':str(native)}.items():content=content.replace(token,shlex.quote(value))
                launcher.write_text(content);launcher.chmod(0o755)
                fixture=repo/'graphite-server/internal/store/testdata/callsite-index/store'
                options=['serve','--id','tiny',str(fixture),'--data',str(folder/'data'),'--port','0']
                if route=='nested-argument-file':
                    nested=folder/'nested.args';top=folder/'top.args'
                    nested.write_text(' '.join(shlex.quote(v) for v in options)+'\n')
                    top.write_text(shlex.quote('@'+str(nested))+'\n');options=['@'+str(top)]
                report=args.output/(route+'.html');stderr_path=folder/'stderr'
                with stderr_path.open('w') as stderr, (folder/'stdout').open('w') as stdout:
                    process=subprocess.Popen([str(launcher),'--profile']+options,cwd=folder,env=dict(base,GRAPHITE_PROFILE=str(report)),stdin=subprocess.DEVNULL,stdout=stdout,stderr=stderr)
                    try:
                        deadline=time.monotonic()+30
                        while True:
                            text=stderr_path.read_text();match=re.search(r'http://localhost:(\d+)',text)
                            if match:break
                            if process.poll() is not None or time.monotonic()>=deadline:raise AssertionError((route,process.poll(),text))
                            time.sleep(.05)
                        port=int(match[1]);responses=[]
                        # A bounded CPU-sampling functional exercise. No runtime,
                        # throughput, speedup or fixture performance is reported.
                        for _ in range(30):
                            request=urllib.request.Request(f'http://127.0.0.1:{port}/api/cypher',data=json.dumps({'query':query}).encode(),headers={'Content-Type':'application/json'})
                            with urllib.request.urlopen(request,timeout=20) as response:responses.append(json.load(response))
                        assert all(value==responses[0] for value in responses)
                        process.terminate();process.wait(timeout=15)
                        assert process.returncode==(0 if route=='direct' else 143)
                        data=report.read_text();match=re.search(r'<script id="profile-data" type="application/json">(.*?)</script>',data,re.S)
                        assert match, 'not the native self-contained HTML report'
                        tree=json.loads(match[1]);assert int(tree['root']['value'])>0
                        assert 'Profiling started' not in (folder/'stdout').read_text()
                        records.append(dict(route=route,command=process.args,exitCode=process.returncode,query=query,requestCount=30,response=responses[0],allResponsesEqual=True,reportSHA256=hashlib.sha256(report.read_bytes()).hexdigest(),sampledCPUNanos=tree['root']['value'],stdout=(folder/'stdout').read_text(),stderr=stderr_path.read_text(),onlyNativeHTMLWriter=True))
                    finally:
                        if process.poll() is None:process.kill();process.wait()
        args.output.joinpath('results.json').write_text(json.dumps(dict(passed=True,native=str(native),nativeSHA256=hashlib.sha256(native.read_bytes()).hexdigest(),jar=str(jar),jarSHA256=hashlib.sha256(jar.read_bytes()).hexdigest(),checks=records),indent=2)+'\n')
        print(json.dumps(dict(passed=True,checks=len(records))))
    except BaseException as error:
        args.output.joinpath('results.json').write_text(json.dumps(dict(passed=False,error=repr(error),checks=records),indent=2)+'\n')
        raise


if __name__=='__main__':main()

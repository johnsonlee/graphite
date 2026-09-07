#!/usr/bin/env python3
"""Small process signal contract oracle; no performance or64-graph execution."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import subprocess
import tempfile
import time
import urllib.request


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo',type=Path,default=Path.cwd())
    parser.add_argument('--native',type=Path,required=True)
    parser.add_argument('--main-jar',type=Path,required=True)
    parser.add_argument('--bridge-jar',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--expected-native-signal',choices=['zero','posix'],required=True)
    parser.add_argument('--include-main',action='store_true')
    parser.add_argument('--skip-main-simple',action='store_true')
    args=parser.parse_args();repo=args.repo.resolve();out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
    native=args.native.resolve();mainjar=args.main_jar.resolve();bridgejar=args.bridge_jar.resolve()
    doc=Path(__file__).resolve().parent
    env={k:v for k,v in os.environ.items() if k not in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','GRAPHITE_NATIVE_CPU_PROFILE','GRAPHITE_PROFILE','GRAPHITE_SERVER_BINARY')}
    env['JAVA_OPTS']='-Xmx128m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8'
    fixture=repo/'graphite-server/internal/store/testdata/callsite-index/store'
    java=['java','-Xmx128m','-XX:ActiveProcessorCount=2','-Dfile.encoding=UTF-8']
    observations=[]
    with tempfile.TemporaryDirectory(prefix='graphite signal oracle ') as tmp:
        folder=Path(tmp);wrapper=folder/'graphite'
        script=(repo/'scripts/native/graphite-launcher.sh.in').read_text()
        for key,value in {'@JAVA@':shutil.which('java'),'@JAR@':str(bridgejar),'@NATIVE@':str(native)}.items():script=script.replace(key,shlex.quote(value))
        wrapper.write_text(script);wrapper.chmod(0o755)
        cases=[]
        if args.include_main:
            if not args.skip_main_simple:
                for sig in (signal.SIGTERM,signal.SIGINT):cases.append(('main-direct-'+sig.name,java+['-jar',str(mainjar),'serve'],sig,False,128+sig))
                cases.append(('main-homebrew-SIGTERM',[str(doc/'main-homebrew-wrapper.sh'),'serve'],signal.SIGTERM,False,143))
            cases.append(('main-profile-save-error-SIGTERM',[str(doc/'main-homebrew-wrapper.sh'),'--profile','serve'],signal.SIGTERM,'jvm-error',143))
        for sig in (signal.SIGTERM,signal.SIGINT):
            expected=0 if args.expected_native_signal=='zero' else 128+sig
            cases.append(('native-direct-'+sig.name,[str(native),'serve'],sig,True,expected))
        cases.append(('native-homebrew-SIGTERM',[str(wrapper),'--profile','serve'],signal.SIGTERM,True,0 if args.expected_native_signal=='zero' else 143))
        cases.append(('bridge-homebrew-SIGINT',[str(wrapper),'--profile'],signal.SIGINT,True,130))
        # A failed native report flush has its own explicit exit1 in the old
        # profile wrapper; the observed signal contract is recorded separately.
        cases.append(('native-profile-save-error-SIGTERM',[str(native),'serve'],signal.SIGTERM,'go-error',1 if args.expected_native_signal=='zero' else 143))
        try:
            for name,command,sig,profile,expected in cases:
                case=folder/name;case.mkdir();report=out/(name+'.html');error=case/'stderr';stdout=case/'stdout'
                settings=['--id','tiny',str(fixture),'--data',str(case/'data'),'--port','0']
                if name.startswith('bridge-'):
                    nested=case/'nested.args';top=case/'top.args';nested.write_text(' '.join(shlex.quote(v) for v in ['serve']+settings)+'\n');top.write_text(shlex.quote('@'+str(nested))+'\n')
                    invocation=command+['@'+str(top)]
                else:invocation=command+settings
                childenv=dict(env,GRAPHITE_SERVER_BINARY=str(native))
                if profile:
                    childenv['GRAPHITE_PROFILE']=str(report)
                    if profile!='jvm-error':childenv['GRAPHITE_NATIVE_CPU_PROFILE']='1'
                if profile in ('jvm-error','go-error'):
                    target=out/(name+'-existing-output-directory');target.mkdir(exist_ok=True);(target/'keep').write_text('keep')
                    childenv['GRAPHITE_PROFILE']=str(target)
                with error.open('w') as err,stdout.open('w') as std:
                    process=subprocess.Popen(invocation,cwd=case,env=childenv,stdin=subprocess.DEVNULL,stdout=std,stderr=err,start_new_session=True)
                    try:
                        deadline=time.monotonic()+30
                        while True:
                            text=error.read_text();match=re.search(r'http://localhost:(\d+)',text)
                            if match:break
                            if process.poll() is not None or time.monotonic()>=deadline:raise AssertionError((name,process.poll(),text))
                            time.sleep(.05)
                        with urllib.request.urlopen('http://127.0.0.1:'+match[1]+'/api/graphs',timeout=10) as response:catalog=json.load(response)
                        assert catalog['graphs'][0]['nodes']==4
                        process.send_signal(sig);process.wait(timeout=20)
                        record=dict(name=name,command=invocation,signal=sig.name,signalTarget='parent PID',returncode=process.returncode,expected=expected,stdout=stdout.read_text(),stderr=error.read_text(),catalog=catalog)
                        if profile is True:
                            html=report.read_text();match=re.search(r'<script id="profile-data" type="application/json">(.*?)</script>',html,re.S);assert match
                            json.loads(match[1]);record['profileFlushed']=True;record['reportSHA256']=hashlib.sha256(report.read_bytes()).hexdigest()
                        if profile in ('jvm-error','go-error'):
                            assert (target/'keep').read_text()=='keep';record['existingOutputPreserved']=True
                        observations.append(record)
                        (out/'observations.json').write_text(json.dumps(dict(passed=False,checks=observations),indent=2)+'\n')
                        assert process.returncode==expected,(name,process.returncode,expected,record['stderr'])
                    finally:
                        if process.poll() is None:process.kill();process.wait()
            for name,command,expected in [('normal-help',[str(native),'--help'],0),('invalid-args',[str(native),'--unknown'],2)]:
                report=out/(name+'.html');process=subprocess.run(command,cwd=folder,env=dict(env,GRAPHITE_NATIVE_CPU_PROFILE='1',GRAPHITE_PROFILE=str(report)),capture_output=True,text=True,timeout=20)
                assert process.returncode==expected and '<script id="profile-data"' in report.read_text()
                observations.append(dict(name=name,command=command,returncode=process.returncode,stdout=process.stdout,stderr=process.stderr,profileFlushed=True))
            (out/'observations.json').write_text(json.dumps(dict(passed=True,native=str(native),nativeSHA256=hashlib.sha256(native.read_bytes()).hexdigest(),mainJar=str(mainjar),mainJarSHA256=hashlib.sha256(mainjar.read_bytes()).hexdigest(),checks=observations),indent=2)+'\n')
            print(json.dumps(dict(passed=True,checks=len(observations))))
        except BaseException as error:
            (out/'observations.json').write_text(json.dumps(dict(passed=False,error=repr(error),checks=observations),indent=2)+'\n');raise


if __name__=='__main__':main()

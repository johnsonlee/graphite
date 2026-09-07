#!/usr/bin/env python3
"""Bounded actual Docker signal oracle, including native HTML flush before exit."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.request


def docker(*args):return subprocess.check_output(['docker',*args],text=True).strip()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo',type=Path,default=Path.cwd())
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--candidate-only',action='store_true')
    parser.add_argument('--candidate-image',default='graphite-signal-native:after')
    args=parser.parse_args();repo=args.repo.resolve();out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
    observations=[]
    cases=[('main','graphite-signal-main:4e328b0','SIGTERM',143,False),
           ('main','graphite-signal-main:4e328b0','SIGINT',130,False),
           ('native-before','graphite-native-packaging-audit:arm64','SIGINT',0,False),
           ('native-after',args.candidate_image,'SIGTERM',143,True),
           ('native-after',args.candidate_image,'SIGINT',130,True)]
    if args.candidate_only:cases=cases[-2:]
    try:
        for label,image,sig,expected,profile in cases:
            name=label+'-'+sig
            options=['-e','JAVA_TOOL_OPTIONS=-Xmx128m -XX:ActiveProcessorCount=2'] if label=='main' else []
            if profile:options+=['-e','GRAPHITE_NATIVE_CPU_PROFILE=1','-e','GRAPHITE_PROFILE=/data/profile.html']
            container=docker('create','--platform','linux/arm64','-p','127.0.0.1::8080',*options,image)
            try:
                fixture=repo/'graphite-server/internal/store/testdata/callsite-index/store'
                subprocess.run(['docker','cp',str(fixture)+'/.',container+':/data'],check=True,capture_output=True)
                docker('start',container);deadline=time.monotonic()+30
                while True:
                    state=json.loads(docker('inspect',container))[0]
                    assert state['State']['Running'],docker('logs',container)
                    port=state['NetworkSettings']['Ports']['8080/tcp'][0]['HostPort']
                    try:
                        with urllib.request.urlopen('http://127.0.0.1:'+port+'/api/graphs',timeout=1) as response:catalog=json.load(response)
                        break
                    except OSError:
                        if time.monotonic()>=deadline:raise
                        time.sleep(.05)
                assert catalog['graphs'][0]['nodes']==4
                top=docker('top',container,'-eo','pid,ppid,args')
                if sig=='SIGTERM':docker('stop','--time','20',container)
                else:
                    docker('kill','--signal',sig,container)
                    subprocess.run(['docker','wait',container],check=True,capture_output=True,timeout=20)
                final=json.loads(docker('inspect',container))[0]
                assert final['State']['ExitCode']==expected and not final['State']['Running']
                logs=subprocess.run(['docker','logs',container],capture_output=True,text=True)
                record=dict(name=name,image=image,imageId=state['Image'],signal=sig,exitCode=final['State']['ExitCode'],catalog=catalog,config=state['Config'],top=top,stdout=logs.stdout,stderr=logs.stderr)
                if profile:
                    report=out/(name+'.html')
                    subprocess.run(['docker','cp',container+':/data/profile.html',str(report)],check=True,capture_output=True)
                    match=re.search(r'<script id="profile-data" type="application/json">(.*?)</script>',report.read_text(),re.S)
                    assert match;json.loads(match[1]);record.update(profileFlushed=True,reportSHA256=hashlib.sha256(report.read_bytes()).hexdigest())
                observations.append(record)
            finally:subprocess.run(['docker','rm','-f','-v',container],check=True,capture_output=True)
        (out/'observations.json').write_text(json.dumps(dict(passed=True,checks=observations,dockerVersion=docker('version')),indent=2)+'\n')
        print(json.dumps(dict(passed=True,checks=len(observations))))
    except BaseException as error:
        (out/'observations.json').write_text(json.dumps(dict(passed=False,error=repr(error),checks=observations),indent=2)+'\n');raise


if __name__=='__main__':main()

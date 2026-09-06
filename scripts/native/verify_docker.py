#!/usr/bin/env python3
"""Actual container correctness for the native PID1 and persisted-store mount."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time
import urllib.request


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True).strip()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path.cwd())
    parser.add_argument('--image', required=True)
    parser.add_argument('--platform', default='linux/arm64')
    parser.add_argument('--output', type=Path, required=True)
    args=parser.parse_args();repo=args.repo.resolve()
    fixture=repo/'graphite-server/internal/store/testdata/callsite-index/store'
    container=docker('create','--platform',args.platform,'-p','127.0.0.1::8080',args.image)
    evidence={"image":args.image,"platform":args.platform,"container":container,"passed":False}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    try:
        # docker cp writes only this owned disposable container volume; the real
        # fixture is read-only and does not need to be mounted into the Docker VM.
        subprocess.run(['docker','cp',str(fixture)+'/.',container+':/data'],check=True,capture_output=True)
        docker('start',container)
        deadline=time.monotonic()+30
        while True:
            inspect=json.loads(docker('inspect',container))[0]
            assert inspect['Config']['User']=='1000:1000'
            assert inspect['Config']['Entrypoint']==['/app/graphite-server','serve']
            assert inspect['Config']['Cmd']==['--id','app','/data']
            assert inspect['Config']['WorkingDir']=='/app'
            assert inspect['State']['Running'], docker('logs',container)
            ports=inspect['NetworkSettings']['Ports']['8080/tcp']
            try:
                with urllib.request.urlopen('http://127.0.0.1:'+ports[0]['HostPort']+'/api/graphs',timeout=1) as response:
                    catalog=json.load(response)
                break
            except OSError:
                if time.monotonic()>=deadline: raise
                time.sleep(.05)
        assert catalog['graphs'][0]['id']=='app' and catalog['graphs'][0]['nodes']==4, catalog
        with urllib.request.urlopen('http://127.0.0.1:'+ports[0]['HostPort']+'/',timeout=10) as response: ui=response.read()
        assert ui==(repo/'graphite-server/internal/web/assets/index.html').read_bytes()
        top=docker('top',container,'-eo','pid,ppid,args')
        assert '/app/graphite-server serve --id app /data' in top and 'java' not in top
        evidence.update(catalog=catalog,top=top,uiSHA256=hashlib.sha256(ui).hexdigest(),imageId=inspect['Image'],config=inspect['Config'],mounts=inspect['Mounts'])
        docker('stop','--time','15',container)
        after=json.loads(docker('inspect',container))[0]
        assert after['State']['ExitCode']==0 and not after['State']['Running']
        evidence.update(passed=True,exitCode=after['State']['ExitCode'],logs=docker('logs',container))
        args.output.write_text(json.dumps(evidence,indent=2)+'\n')
        print(json.dumps({'passed':True,'platform':args.platform}))
    except BaseException as error:
        evidence['error']=repr(error)
        evidence['logs']=subprocess.run(['docker','logs',container],capture_output=True,text=True).stdout
        args.output.write_text(json.dumps(evidence,indent=2)+'\n')
        raise
    finally:
        subprocess.run(['docker','rm','-f','-v',container],check=True,capture_output=True)


if __name__=='__main__': main()

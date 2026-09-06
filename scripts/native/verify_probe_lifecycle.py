#!/usr/bin/env python3
"""Kill the wrapper during an actual blocked route child; verify no orphan."""
import argparse
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import tempfile
import time


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo',type=Path,default=Path.cwd())
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();repo=args.repo.resolve();results=[]
    with tempfile.TemporaryDirectory(prefix='graphite route lifecycle ') as tmp:
        folder=Path(tmp);child=folder/'route child'
        command=['go','build','-o',str(child),str(repo/'graphite-explore/src/test/native-launcher/child.go')]
        subprocess.run(command,check=True,env=dict(os.environ,CGO_ENABLED='0'))
        launcher=folder/'graphite';content=(repo/'scripts/native/graphite-launcher.sh.in').read_text()
        for token,value in {'@JAVA@':str(child),'@JAR@':'not-needed.jar','@NATIVE@':str(child)}.items():content=content.replace(token,shlex.quote(value))
        launcher.write_text(content);launcher.chmod(0o755)
        for sig in (signal.SIGTERM,signal.SIGINT):
            record=folder/'record.json';signalfile=folder/'signal.txt';record.unlink(missing_ok=True);signalfile.unlink(missing_ok=True)
            env=dict(os.environ,TMPDIR=str(folder),GRAPHITE_BRIDGE_TEST_MODE='signal',GRAPHITE_BRIDGE_TEST_RECORD=str(record),GRAPHITE_BRIDGE_TEST_SIGNAL=str(signalfile))
            process=subprocess.Popen([str(launcher),'--profile','@args'],env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,start_new_session=True)
            childpid=None
            try:
                deadline=time.monotonic()+15
                while not record.exists():
                    assert process.poll() is None
                    if time.monotonic()>=deadline:raise AssertionError('route child not ready')
                    time.sleep(.02)
                childpid=json.loads(record.read_text())['pid']
                process.send_signal(sig);stdout,stderr=process.communicate(timeout=15)
                assert process.returncode==128+sig
                assert signalfile.read_text()=='terminated'
                assert not list(folder.glob('graphite-route.*'))
                try:os.kill(childpid,0)
                except ProcessLookupError:pass
                else:raise AssertionError('orphan route child')
                results.append(dict(signal=sig.name,exitCode=process.returncode,childSignal=signalfile.read_text(),childGone=True,stdout=stdout,stderr=stderr))
            finally:
                if process.poll() is None:process.kill()
                if childpid:
                    try:os.kill(childpid,signal.SIGKILL)
                    except ProcessLookupError:pass
                process.communicate(timeout=10)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(dict(passed=True,helperCommand=command,checks=results),indent=2)+'\n')
    print(json.dumps(dict(passed=True,checks=len(results))))


if __name__=='__main__':main()

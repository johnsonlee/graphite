#!/usr/bin/env python3
"""Real shell/Picocli routing and JVM profiler checks. No performance measurement."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    repo, jar = args.repo.resolve(), args.jar.resolve()
    results = []
    env = {k: v for k, v in os.environ.items() if k not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "GRAPHITE_PROFILE", "GRAPHITE_NATIVE_CPU_PROFILE", "GRAPHITE_SERVER_BINARY")}
    env["JAVA_OPTS"] = "-Xmx128m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8"
    with tempfile.TemporaryDirectory(prefix="graphite wrapper space ") as tmp:
        folder = Path(tmp); record = folder/"child.json"
        probe = folder/"native probe"
        probe.write_text("#!" + sys.executable + "\n" + '''import json,os,sys
from pathlib import Path
Path(os.environ['NATIVE_RECORD']).write_text(json.dumps({'args':sys.argv[1:],'cwd':os.getcwd(),'stdin':sys.stdin.read(),'nativeProfile':os.environ.get('GRAPHITE_NATIVE_CPU_PROFILE'),'profilePath':os.environ.get('GRAPHITE_PROFILE')}))
print('native stdout')
print('native stderr',file=sys.stderr)
sys.exit(37)
''')
        probe.chmod(0o755)
        launcher = folder/"graphite"
        content=(repo/"scripts/native/graphite-launcher.sh.in").read_text()
        for token, value in {"@JAVA@":shutil.which("java"), "@JAR@":str(jar), "@NATIVE@":str(probe)}.items():
            content=content.replace(token, shlex.quote(value))
        launcher.write_text(content); launcher.chmod(0o755)
        env.update(NATIVE_RECORD=str(record), GRAPHITE_SERVER_BINARY=str(probe))
        data=folder/"must not exist"
        nested=folder/"nested.args"; top=folder/"top.args"
        nested.write_text("serve --data '"+str(data)+"' --port 0\n")
        top.write_text("'@"+str(nested)+"'\n")
        cases = [("direct", ["serve", "--profile", "--data", str(data), "--port", "0"]),
                 ("profile-prefix", ["--profile", "serve", "--data", str(data), "--port", "0"]),
                 ("argument-file", ["--profile", "@"+str(nested)]),
                 ("nested-argument-file", ["--profile", "@"+str(top)])]
        try:
            for name, command in cases:
                record.unlink(missing_ok=True)
                profile=folder/(name+" profile.html"); child_env=dict(env, GRAPHITE_PROFILE=str(profile))
                process=subprocess.run([str(launcher)]+command,cwd=folder,env=child_env,input="literal stdin\n",capture_output=True,text=True,timeout=30)
                child=json.loads(record.read_text())
                assert process.returncode==37, (name,process.returncode,process.stderr)
                assert child['args']==['serve','--data',str(data),'--port','0']
                assert child['nativeProfile']=='1' and child['profilePath']==str(profile)
                assert child['stdin']=='literal stdin\n' and not data.exists()
                assert process.stdout=='native stdout\n'
                # The actual JVM launcher must not start async-profiler on @files.
                assert not profile.exists(), (name, 'unexpected JVM profiling writer')
                results.append(dict(name=name,command=command,exitCode=process.returncode,child=child,stdout=process.stdout,stderr=process.stderr,onlyNativeProfileSelected=True))
            # Exercise the actual installed async-profiler agent, not a fake .html
            # file. This is a bounded correctness check on a compiled tiny class.
            assert shutil.which("asprof"), 'async-profiler is required for JVM profiling correctness checks'
            classes=folder/'classes';classes.mkdir()
            java_source=folder/'Sample.java';java_source.write_text('package sample; public class Sample { public static void main(String[] args) { System.out.println("tiny"); } }\n')
            subprocess.run(['javac','-d',str(classes),str(java_source)],check=True,capture_output=True)
            built=folder/'built-store'
            jvm_cases=[('jvm-build',['build',str(classes),'--output',str(built)]),
                       ('jvm-query',['query',str(repo/'graphite-server/internal/store/testdata/callsite-index/store'),'MATCH (n:CallSite) RETURN count(n) AS count','--format','json']),
                       ('global-help',['--help','@'+str(top)]),
                       ('global-version',['--version','@'+str(top)]),
                       ('parse-failure',['serve','--port','invalid'])]
            # Parse failure must use the JVM branch even when supplied by @file.
            invalid=folder/'invalid.args';invalid.write_text('serve --port invalid\n')
            jvm_cases[-1]=('parse-failure',['@'+str(invalid)])
            for name, command in jvm_cases:
                record.unlink(missing_ok=True); profile=folder/(name+' profile.html')
                process=subprocess.run([str(launcher),'--profile']+command,cwd=folder,env=dict(env,GRAPHITE_PROFILE=str(profile)),capture_output=True,text=True,timeout=45)
                expected=2 if name=='parse-failure' else 0
                assert process.returncode==expected,(name,process.returncode,process.stderr)
                assert not record.exists(), (name,'native child unexpectedly launched')
                html=profile.read_bytes(); assert b'<html' in html.lower() and b'async-profiler' in html
                if name=='jvm-query': assert json.loads(process.stdout.removeprefix('Profiling started\n'))['rows']==[{'count':4}]
                if name=='jvm-build': assert (built/'graph.nodedata').exists()
                args.output.parent.mkdir(parents=True,exist_ok=True)
                (args.output.parent/(args.output.stem+'-'+name+'.html')).write_bytes(html)
                results.append(dict(name=name,command=command,exitCode=process.returncode,stdout=process.stdout,stderr=process.stderr,htmlSHA256=hashlib.sha256(html).hexdigest(),htmlBytes=len(html),onlyJVMProfileSelected=True))
            args.output.parent.mkdir(parents=True,exist_ok=True)
            args.output.write_text(json.dumps(dict(passed=True,jar=str(jar),jarSHA256=hashlib.sha256(jar.read_bytes()).hexdigest(),checks=results),indent=2)+'\n')
            print(json.dumps(dict(passed=True,checks=len(results))))
        except BaseException as error:
            args.output.parent.mkdir(parents=True,exist_ok=True)
            args.output.write_text(json.dumps(dict(passed=False,error=repr(error),checks=results),indent=2)+'\n')
            raise


if __name__=='__main__': main()

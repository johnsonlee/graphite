#!/usr/bin/env python3
"""Setup only: exact original JARs, authenticated Linux export/oracle, one external adapter compile."""
from common import *
import argparse,re,shutil,traceback

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for n in ['base-tree','candidate-tree','fixture-dir','evidence-dir','output']:p.add_argument('--'+n,type=Path,required=True)
    p.add_argument('--java',type=Path,required=True);a=p.parse_args()
    require_linux();pins=check_sources()
    for k,v in vars(a).items():setattr(a,k,v.resolve())
    require(not a.output.exists(),'Fresh setup output required');a.output.mkdir(parents=True)
    out=a.output;state={'status':'running','performanceAcceptance':False,'commands':[],'productionRefs':{s:pins[s] for s in ['base','candidate']}}
    def save():write(out/'prepared.json',state)
    def execute(cmd,label,cwd=None):
        r={'label':label,'command':list(map(str,cmd)),'cwd':str(cwd) if cwd else None};state['commands'].append(r);save()
        try:run_command(cmd,out/(label+'.log'),cwd=cwd);r['exitCode']=0
        except BaseException:r['failed']=True;raise
        finally:save()
    before=None;save()
    try:
        no_java()
        version=subprocess.check_output([str(a.java),'-version'],stderr=subprocess.STDOUT,text=True)
        require(re.search(r'version "17[.]',version),'Java17 required');state['javaVersion']=version
        trees={'base':a.base_tree,'candidate':a.candidate_tree};ev=a.evidence_dir/'provenance.json'
        require(sha(ev)==pins['ciProvenanceSha256'],'Original CI provenance differs');prov=read(ev)
        for s,t in trees.items():
            require(subprocess.check_output(['git','-C',str(t),'rev-parse','HEAD'],text=True).strip()==pins[s],'Wrong '+s+' revision')
            require(not subprocess.check_output(['git','-C',str(t),'status','--porcelain'],text=True).strip(),'Require clean isolated '+s+' checkout')
        harness=Path('graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/LargeBroadQueryPressureBenchmark.kt')
        correctness=Path('graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/QueryCorrectnessManifest.kt')
        for rel,key in [(harness,'harnessSha256'),(correctness,'correctnessSha256')]:
            require(sha(trees['candidate']/rel)==prov[key],'Original candidate source identity differs')
            shutil.copy2(trees['candidate']/rel,trees['base']/rel)
        # Reproduce original CI's reviewed same-harness builds. No marker or engine optimization is added.
        jars={};(out/'jars').mkdir()
        for s,t in trees.items():
            tasks=[':webgraph:jmhJar']+([':webgraph:prepareBenchmarkFixtures'] if s=='candidate' else [])
            execute([t/'gradlew','-p',t,*tasks,'--no-daemon','-Pkotlin.compiler.execution.strategy=in-process'],'build-'+s)
            paths=list((t/'graphite-webgraph/build/libs').glob('*-jmh.jar'));require(len(paths)==1,'Unique JMH JAR required')
            jar=out/'jars'/('original-'+s+'.jar');shutil.copy2(paths[0],jar);jars[s]=jar
            canonical=subprocess.check_output([sys.executable,str(trees['candidate']/'.github/scripts/canonical-zip-sha256.py'),str(jar)],text=True).strip()
            require(canonical==prov[s+'JarContentSha256']==pins[s+'JarContentSha256'],'Original CI canonical JAR mismatch: '+s)
            state.setdefault('jars',{})[s]={'path':str(jar),'sha256':sha(jar),'canonicalContentSha256':canonical};save()
        fixture=a.fixture_dir;manifest=fixture/'graphs/graphs.tsv';provenance=fixture/'graphs/fixture-provenance.tsv'
        execute([trees['candidate']/'.github/scripts/verify-shared-fixture64.sh',jars['candidate'],trees['candidate']/'graphite-webgraph/build/benchmark-fixtures',fixture,pins['candidate']],'authenticate-fixture')
        before=graph_identity(manifest);write(out/'graph-content-before.json',before)
        classes=out/'classes';classes.mkdir()
        execute([a.java.with_name('javac'),'-cp',jars['base'],'-d',classes,*[ROOT/'tools'/n for n in ['MultiKeywordProfileRunner.java','ExportCallSites.java','VerifyNonCallSiteProperties.java']]],'compile-external-tools')
        classfiles=sorted(classes.rglob('*.class'))
        require(classfiles and all(x.parent==classes and x.name.split('$')[0].removesuffix('.class') in ['MultiKeywordProfileRunner','ExportCallSites','VerifyNonCallSiteProperties'] for x in classfiles),'Unexpected class/package may shadow production')
        raw=out/'export';raw.mkdir();classpath=str(classes)+':'+str(jars['base'])
        for name,klass,filename,heap,kind in [('export','ExportCallSites','callsites.tsv.gz','3g','ordered-callsite-tuples'),('census','VerifyNonCallSiteProperties','non-callsite-census.tsv','2g','non-callsite-property-census')]:
            execute([a.java,'-Xmx'+heap,'-XX:ActiveProcessorCount=4','-cp',classpath,klass,'--manifest',manifest,'--provenance',provenance,'--expected-jar-sha256',sha(jars['base']),'--output',raw/filename,'--receipt',raw/(name+'.json')],name)
            r=read(raw/(name+'.json'))
            require(r.get('passed') is True and r['kind']==kind and r['frozenRevision']==pins['base'],'Exporter receipt failed')
            for k,path in [('manifestSha256',manifest),('provenanceSha256',provenance),('jarSha256',jars['base']),('outputSha256',raw/filename)]:require(r[k]==sha(path),'Exporter identity differs: '+k)
        oracle=out/'oracle-v4'
        execute([sys.executable,ROOT/'tools/derive.py','--manifest',manifest,'--provenance',provenance,'--jar',jars['base'],'--export',raw/'callsites.tsv.gz','--census',raw/'non-callsite-census.tsv','--expected-jar-sha256',sha(jars['base']),'--expected-export-sha256',read(raw/'export.json')['outputSha256'],'--expected-census-sha256',read(raw/'census.json')['outputSha256'],'--preserved-v3',ROOT/'frozen-v3/catalog.json','--expected-preserved-v3-sha256',pins['sourceFiles']['frozen-v3/catalog.json'],'--output-dir',oracle],'derive-independent-oracle')
        comparison=compare_catalogs(oracle,ROOT/'frozen-v4');write(out/'linux-local-oracle-comparison.json',comparison)
        bound=[manifest,provenance,ev,*jars.values(),*classfiles,oracle/'catalog.json',oracle/'workloads.tsv',raw/'export.json',raw/'census.json',raw/'callsites.tsv.gz',raw/'non-callsite-census.tsv',out/'linux-local-oracle-comparison.json']
        state.update(java=str(a.java),manifest=str(manifest),provenance=str(provenance),classes=str(classes),oracle=str(oracle),boundInputs=identity(bound),sourcePinsSha256=sha(ROOT/'pins.json'),boundaryNoJava=no_java(),status='complete');save()
    except BaseException:
        state.update(status='failed',error=traceback.format_exc());save();raise
    finally:
        if before is not None:
            try:
                after=graph_identity(manifest);write(out/'graph-content-after.json',after);state['graphInputsUnchanged']=before==after
                require(before==after,'Fixture content changed during preparation')
            except BaseException:
                state['integrityError']=traceback.format_exc()
                if state['status']=='complete':state['status']='failed';save();raise
            finally:save()
if __name__=='__main__':main()

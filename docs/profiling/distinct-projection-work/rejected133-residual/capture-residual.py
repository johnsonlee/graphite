import csv, hashlib, json, pathlib, subprocess, sys

root = pathlib.Path(__file__).parent
out = root/'profiles'; out.mkdir(exist_ok=True)
prior = pathlib.Path('/private/tmp/graphite-main-profiling-n50joikp')
repo = pathlib.Path('/Users/johnsonlee/.codex/worktrees/ac7b5da2-2450-48c5-894c-5fd84ab6cb7d/graphite')
sys.path.insert(0,str(repo/'.github/scripts/wide-query-profile'))
from run import graph_identity
manifest = pathlib.Path('/private/tmp/pr113-attempt131-ascii.JqgmHw/fixture64/graphs.tsv')
template = json.loads((prior/'cpu-3-command.json').read_text())
base = pathlib.Path(template[2]); jars = {'base':base,'rejected133':root/'rejected133-diagnostic-jmh.jar'}
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
hashes = {s:sha(p) for s,p in jars.items()}
assert hashes['base'] == 'a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe'
assert hashes['rejected133'] == '8434d0a137b5dad0cb45df2261c576a1dcf768d30d17e50a76b626a85095f220'
before = graph_identity(manifest)
oracle = pathlib.Path('/private/tmp/graphite-mapped-tuple-evidence.t2461mo1/oracle.correctness')
signatures = oracle.read_text().splitlines(); assert len(signatures)==34
fields = ['id','family','shape','selectivity','operator','boundary','projection','targetGraphId','workloadIdentity','limit','outcome','rowCount','responseBytes','digest']
(out/'input-receipt.json').write_text(json.dumps({'graphFiles':before,'jarHashes':hashes,'oracleSha256':sha(oracle),'diagnosticOnly':True,'rejectedAttemptRemainsRejected':True,'plan':'One CPU/allocation/phase recording per revision of the same original34 replay. Residual-work diagnosis only; no timing acceptance comparison or new candidate.'},indent=2)+'\n')
for side in ['base','rejected133']:
    prefix = out/side
    assert not prefix.with_suffix('.jfr').exists()
    cmd = [s.replace(str(prior/'cpu-3'),str(prefix)).replace(str(prior/'jvm-cpu-3'),str(out/('jvm-'+side))) for s in template]
    cmd[2] = str(jars[side])
    extra = ',trace=io.johnsonlee.graphite.cypher.QueryPipeline.executeIndexedDistinctStringProjection$projectSource,trace=io.johnsonlee.graphite.cypher.QueryPipeline.executeIndexedDistinctStringProjection$lambda$155$lambda$154'
    cmd[-1] = cmd[-1].replace(',file='+str(prefix)+'.jfr',extra+',file='+str(prefix)+'.jfr')
    assert cmd[-1].count('trace=')==3
    (out/(side+'-command.json')).write_text(json.dumps(cmd,indent=2)+'\n')
    print('START',side,flush=True)
    with (out/(side+'.log')).open('w') as log:subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,check=True)
    rows = list(csv.DictReader(prefix.with_suffix('.tsv').open(),delimiter='\t'))
    assert ['|'.join(row[f] for f in fields) for row in rows] == signatures
    analyze = [cmd[0],'-cp',str(prior),'ProfileWindows',str(prefix)+'.jfr',str(prefix)+'.tsv',str(prefix)+'-analysis','--expected-count','34','--catalog','/private/tmp/graphite-distinct-phase-profiling.by0z0asb/expected-query-ids.txt']
    phases = [cmd[0],'-cp','/private/tmp/graphite-distinct-phase-details:'+str(prior),'DistinctPhaseDetails',str(prefix)+'.jfr',str(prefix)+'.tsv',str(prefix)+'-phases.json']
    for number,command in enumerate([analyze,phases]):
        (out/(side+'-analysis-'+str(number)+'-command.json')).write_text(json.dumps(command,indent=2)+'\n')
        subprocess.run(command,check=True)
    print('VERIFIED_AND_ANALYZED',side,flush=True)
assert graph_identity(manifest)==before
assert {s:sha(p) for s,p in jars.items()}==hashes
(out/'completed.json').write_text(json.dumps({'status':'complete','verifiedQueries':68,'recordings':2,'jarHashesUnchanged':True,'graphFilesUnchanged':True,'diagnosticOnly':True,'notP95':True,'rejectedAttemptRemainsRejected':True},indent=2)+'\n')

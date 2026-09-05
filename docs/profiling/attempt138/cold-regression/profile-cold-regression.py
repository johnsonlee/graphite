import csv, hashlib, json, pathlib, subprocess, sys

root = pathlib.Path(__file__).parent
out = root / 'cold-regression-profile'
out.mkdir(exist_ok=True)
prior = pathlib.Path('/private/tmp/graphite-main-profiling-n50joikp')
repo = pathlib.Path('/Users/johnsonlee/.codex/worktrees/ac7b5da2-2450-48c5-894c-5fd84ab6cb7d/graphite')
sys.path.insert(0, str(repo / '.github/scripts/wide-query-profile'))
from run import graph_identity
from verify_run import digest_rows, validate_catalog

manifest = pathlib.Path('/private/tmp/pr113-attempt131-ascii.JqgmHw/fixture64/graphs.tsv')
base = pathlib.Path('/private/tmp/graphite-next-baseline.T2FTs9/graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar')
jars = {'base': base, 'candidate': root / 'candidate-jmh.jar'}
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
hashes = {side: sha(jar) for side, jar in jars.items()}
assert hashes['base'] == 'a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe'
assert hashes['candidate'] == '2c419ac0b9d996af0890d1c857f81fa3479c170f59306fe35517a6e90cf7b5bf'
before = graph_identity(manifest)
catalog = json.loads((prior / 'oracle-v2/catalog.json').read_text())
validate_catalog(catalog)
queries = {q['id']: q for q in catalog['queries'] if q['logicalId'] == 'or-four-broad'}
workload = prior / 'four-or/profile-workloads.tsv'
expected = list(csv.DictReader(workload.open(), delimiter='\t'))
assert len(expected) == 80 and len(queries) == 2
template = json.loads((prior / 'four-or/cpu-command.json').read_text())
(out / 'input-receipt.json').write_text(json.dumps({'graphFiles': before, 'jarHashes': hashes, 'catalogSha256': sha(prior / 'oracle-v2/catalog.json'), 'workloadSha256': sha(workload), 'diagnosticOnly': True, 'notAcceptanceRerun': True, 'plan': 'One CPU/allocation recording per revision, 40 rows and 40 DISTINCT repetitions in one JVM each; explain rejected138 pure-four-OR cold projection costs; rejection remains final.'}, indent=2)+'\n')
for side in ['base', 'candidate']:
    prefix = out / side
    assert not prefix.with_suffix('.jfr').exists()
    cmd = [x.replace(str(prior / 'four-or/cpu'), str(prefix)).replace(str(prior / 'four-or/jvm-cpu'), str(out / ('jvm-'+side))).replace(str(base), str(jars[side])) for x in template]
    (out / (side+'-command.json')).write_text(json.dumps(cmd, indent=2)+'\n')
    print('START', side, flush=True)
    with (out / (side+'.log')).open('w') as log:
        subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, check=True)
    observations = list(csv.DictReader(prefix.with_suffix('.tsv').open(), delimiter='\t'))
    actual = [json.loads(line) for line in (out / (side+'-rows.jsonl')).read_text().splitlines()]
    assert [x['id'] for x in expected] == [x['id'] for x in observations] == [x['id'] for x in actual]
    for w, o, a in zip(expected, observations, actual):
        q = queries[w['id'].rsplit('-', 1)[0]]
        assert a['rows'] == q['expectedRows']
        assert a['columns'] == ['n.caller_class', 'n.caller_name', 'n.callee_class', 'n.callee_name']
        assert o['workloadIdentity'] == hashlib.sha256(q['query'].encode()).hexdigest()
        assert o['digest'] == digest_rows(a['rows']) and int(o['rowCount']) == len(a['rows'])
        assert o['outcome'] == 'success' and o['inputSourceCount'] == '64' and o['resetMode'] == 'per-query-cold'
        assert o['hitGraphIds'] == ','.join(sorted({g for row in a['rows'] for g in row['graphIds']}))
    (out / (side+'-correctness.json')).write_text(json.dumps({'verifiedQueries':80, 'verifiedRows':sum(len(a['rows']) for a in actual), 'valuesOrderProvenanceMatch':True, 'diagnosticOnly':True}, indent=2)+'\n')
    analyze = [cmd[0], '-cp', str(prior), 'ProfileWindows', str(prefix)+'.jfr', str(prefix)+'.tsv', str(prefix)+'-analysis', '--expected-count', '80', '--catalog', str(workload)]
    (out / (side+'-analysis-command.json')).write_text(json.dumps(analyze, indent=2)+'\n')
    subprocess.run(analyze, check=True)
    print('ANALYZED', side, flush=True)
assert graph_identity(manifest) == before
assert {side:sha(jar) for side,jar in jars.items()} == hashes
(out / 'completed.json').write_text(json.dumps({'recordings':2, 'queriesPerRecording':80, 'status':'complete', 'graphFilesUnchanged':True, 'jarHashesUnchanged':True, 'notP95':True, 'notAcceptanceEvidence':True}, indent=2)+'\n')

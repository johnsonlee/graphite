"""Offline orchestration/validator tests; retained controls are read-only, no JVM starts."""
import importlib.util,tempfile,unittest,copy,shutil
from common import *
S=importlib.util.spec_from_file_location('paired',ROOT/'paired-run.py');paired=importlib.util.module_from_spec(S);S.loader.exec_module(paired)
CONTROL=ROOT.parent/'local-oracle-derivation/control-base/fork-001'
class DraftTest(unittest.TestCase):
    def test_workflow_exact_push_guard(self):
        s=(ROOT/"workflow.yml").read_text()
        self.assertIn("on:\n  push:\n    branches: [codex/attempt146-linux-profile]",s)
        self.assertNotIn("workflow_dispatch",s)
        self.assertIn("if: github.repository == 'johnsonlee/graphite' && github.ref == 'refs/heads/codex/attempt146-linux-profile'",s)
        self.assertIn("working-directory: ${{ github.workspace }}",s)
        self.assertIn("-Pkotlin.compiler.execution.strategy=in-process",(ROOT/"prepare.py").read_text())
    def test_bundle_authentication_from_other_cwd(self):
        with tempfile.TemporaryDirectory() as d:
            code="import sys;sys.path.insert(0,sys.argv[1]);from common import check_sources;check_sources()"
            subprocess.run([sys.executable,"-c",code,str(ROOT)],cwd=d,check=True)
    def test_exact_protocol(self):
        plan=paired.protocol();self.assertEqual(len(plan),12)
        for i,mode in enumerate(paired.RESET_STATES):
            part=plan[i*6:i*6+6]
            self.assertEqual([p['side'] for p in part],['base','candidate','candidate','base','base','candidate'])
            self.assertEqual([p['pair'] for p in part],[1,1,2,2,3,3])
            self.assertEqual({p['resetMode'] for p in part},{mode})
    def test_original_controls_still_verify(self):
        for side in ('base','candidate'):
            r=verify_run(ROOT/'frozen-v4/catalog.json',ROOT/'frozen-v4/workloads.tsv',CONTROL.parent.parent/('control-'+side)/'fork-001')
            self.assertTrue(r['passed'],r);self.assertEqual(r['queryCount'],38)
    def test_wrong_or_unknown_reset_fails(self):
        for mode in ['replay-cold','warm','']:
            self.assertFalse(verify_run(ROOT/'frozen-v4/catalog.json',ROOT/'frozen-v4/workloads.tsv',CONTROL,expected_reset=mode)['passed'])
    def test_replay_reset_validator_only_fixture(self):
        # Mutated labels test validator dispatch only; never called a replay/measurement.
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'validator-fixture'
            Path(str(p)+'.tsv').write_text(Path(str(CONTROL)+'.tsv').read_text().replace('\tper-query-cold\t','\treplay-cold\t'))
            shutil.copy2(str(CONTROL)+'-rows.jsonl',str(p)+'-rows.jsonl')
            self.assertTrue(verify_run(ROOT/'frozen-v4/catalog.json',ROOT/'frozen-v4/workloads.tsv',p,expected_reset='replay-cold')['passed'])
            self.assertFalse(verify_run(ROOT/'frozen-v4/catalog.json',ROOT/'frozen-v4/workloads.tsv',p)['passed'])
    def test_local_oracle_identity_and_query_mutation(self):
        self.assertTrue(compare_catalogs(ROOT/'frozen-v4',ROOT/'frozen-v4')['passed'])
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);x=read(ROOT/'frozen-v4/catalog.json');x['queries'][0]['query']+=' '
            write(p/'catalog.json',x);shutil.copy2(ROOT/'frozen-v4/workloads.tsv',p/'workloads.tsv')
            with self.assertRaises(ValueError):compare_catalogs(p,ROOT/'frozen-v4')
    def test_no_p95_and_keep_work_diffs(self):
        rows=read_tsv(str(CONTROL)+'.tsv');observations={p['id']:copy.deepcopy(rows) for p in paired.protocol()}
        observations['replay-cold-pair-2-candidate'][0]['graphWorkUnits']='123'
        result=paired.compare_pairs(observations)
        for mode,records in result.items():
            self.assertEqual(len(records),38)
            self.assertTrue(all(x['empiricalP95LatencyNanos'] is None for x in records))
        self.assertIn('graphWorkUnits',result['replay-cold'][0]['allNonTimeDifferences'][1]['differences'])
if __name__=='__main__':unittest.main()

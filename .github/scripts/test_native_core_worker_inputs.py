"""Real tiny filesystem/Git closure checks; no performance or graph subprocess."""
import copy
import json
from pathlib import Path
import pickle
import unittest
from unittest.mock import patch
import run_native_core_equivalence as r
import test_multigraph_pressure as fixture


class WorkerInputTests(unittest.TestCase):
    def fixture(self):
        f=fixture.ExecutionAuthorityReuse();f.setUp();self.addCleanup(f.doCleanups)
        arm=f.plan['arms']['B'];upstream=r.common.read(f.core/'plan.json')['upstream']
        for name in ('CStrings','BStrings'):
            upstream[name]=f.metadata(f.root/name/'audit.json',{'artifactAudit':arm['artifactAudit']})
        output=f.root/'worker-output';output.mkdir()
        plan={'upstream':upstream,'pins':dict(f.pins),'revisions':{'C':arm['revision'],'B':arm['revision']},
              'referenceRoot':str(f.producer),'actualRoot':str(f.producer),'output':str(output)}
        r.common.save(output/'plan.json',plan)
        replay=patch.object(r,'rebind',side_effect=lambda p:copy.deepcopy(p));mock=replay.start();self.addCleanup(replay.stop)
        return f,plan,mock

    def test_once_full_replay_with_fresh_checks_and_evolving_outputs(self):
        f,plan,replay=self.fixture();authority=r._VerifiedWorkerInputs.open(plan)
        output=Path(plan['output']);(output/'graphs').mkdir();(output/'graphs/new.json').write_text('worker result')
        authority.check(plan);authority.check(plan)
        self.assertEqual(replay.call_count,1)
        with self.assertRaises(TypeError):pickle.dumps(authority)
        with self.assertRaises(TypeError):r._VerifiedWorkerInputs()

    def test_all_input_mutations_fail_and_invalidate(self):
        kinds=('tracked','untracked','writer','runtime','graph','producer','strings','marker','formatter',
               'classpath','absent','config','plan-object','plan-file','helper','phase','symlink')
        for kind in kinds:
            with self.subTest(kind=kind):
                f,plan,_=self.fixture();authority=r._VerifiedWorkerInputs.open(plan)
                if kind=='tracked':(f.checkout/'source.kt').write_text('changed')
                elif kind=='untracked':(f.checkout/'new.kt').write_text('class New')
                elif kind=='writer':(f.writer.parent/'extra-jmh.jar').write_text('extra')
                elif kind=='runtime':(f.producer/'runtime/extra').write_text('extra')
                elif kind=='graph':(f.producer/'graphs/new-empty').mkdir()
                elif kind=='producer':(f.producer/'producer.json').write_text('{}')
                elif kind=='strings':(f.root/'CStrings/new').write_text('extra')
                elif kind=='marker':(f.root/'marker/new-empty').mkdir()
                elif kind=='formatter':(f.root/'formatterTests/results/xml/new.xml').write_text('changed')
                elif kind=='classpath':(f.classpath/'Extra.class').write_text('class')
                elif kind=='absent':f.absent.mkdir()
                elif kind=='config':(Path(f.env['GRADLE_USER_HOME'])/'init.d').mkdir(parents=True)
                elif kind=='plan-object':plan['output']+='changed'
                elif kind=='plan-file':(Path(plan['output'])/'plan.json').write_text('{}')
                elif kind=='helper':(f.root/'BStrings/classes/Helper.class').write_text('changed')
                elif kind=='phase':(f.phase/'extra.log').write_text('extra')
                else:(f.producer/'runtime/link').symlink_to(f.writer)
                with self.assertRaises((ValueError,OSError)):authority.check(plan)
                with self.assertRaisesRegex(ValueError,'invalidated'):authority.check(plan)

    def test_flat_parent_without_formatter_tests_needs_no_classpath_metadata(self):
        # A rebased PR's parent arm ran no production formatter tests (Attempt290): its closure has
        # no test classpath to pin, and the test classpath is not part of what the workers hold.
        f,plan,_=self.fixture()
        ref=f.metadata(f.root/'formatterTests/audit.json',{'productionFormatterTestsApplicable':False})
        plan['upstream']['formatterTests']=ref;plan['pins'][ref['path']]=ref['sha256']
        for module in ('sootup','webgraph'):
            path=f.root/f'formatterTests/results/classpath-{module}-before.json';path.unlink();plan['pins'].pop(str(path))
        (Path(plan['output'])/'plan.json').unlink();r.common.save(Path(plan['output'])/'plan.json',plan)
        authority=r._VerifiedWorkerInputs.open(plan)
        (f.classpath/'Extra.class').write_text('class');authority.check(plan)
        (f.root/'formatterTests/results/xml/new.xml').write_text('changed')
        with self.assertRaises(ValueError):authority.check(plan)

    def test_applicable_formatter_tests_require_their_classpath_metadata(self):
        f,plan,_=self.fixture()
        path=f.root/'formatterTests/results/classpath-sootup-before.json';path.unlink();plan['pins'].pop(str(path))
        (Path(plan['output'])/'plan.json').unlink();r.common.save(Path(plan['output'])/'plan.json',plan)
        with self.assertRaisesRegex(ValueError,'classpath metadata pinned'):r._VerifiedWorkerInputs.open(plan)

    def test_initial_replay_brackets_unpinned_inventory_and_plan(self):
        for kind in ('inventory','plan'):
            with self.subTest(kind=kind):
                f,plan,replay=self.fixture()
                def changed(value):
                    if kind=='inventory':(f.root/'marker/added').mkdir()
                    else:(Path(plan['output'])/'plan.json').write_text('{}')
                    return copy.deepcopy(value)
                replay.side_effect=changed
                with self.assertRaisesRegex(ValueError,'during full replay'):r._VerifiedWorkerInputs.open(plan)

    def test_internal_audit_keeps_graph_results_and_standalone_replays(self):
        f,plan,replay=self.fixture();authority=r._VerifiedWorkerInputs.open(plan)
        row={'id':'one'};result={'id':'one','evidence':'original'}
        with patch.object(r,'check_preparation'),patch.object(r,'shard_rows',return_value=[row]), \
                patch.object(r,'check_worker_graphs'),patch.object(r,'check_shard_record',return_value={'graphs':[result]}), \
                patch.object(r,'graph_result',return_value=result) as graph, \
                patch.object(r,'shard_audit_document',return_value={'pins':{}}):
            r._audit_shard(plan,0,authority)
            self.assertEqual((replay.call_count,graph.call_count),(1,1))
            r.audit_shard(plan,0)
            self.assertEqual((replay.call_count,graph.call_count),(2,2))
            def changed(*args):
                (f.root/'marker/changed').mkdir();return result
            graph.side_effect=changed
            with self.assertRaisesRegex(ValueError,'closure changed'):r._audit_shard(plan,0,authority)
            with self.assertRaisesRegex(ValueError,'invalidated'):authority.check(plan)

    def test_stage_terminal_rejects_mutation_during_phase_and_retains_failure(self):
        f,plan,_=self.fixture();plan['execution']={'shardCount':8}
        output=Path(plan['output']);(output/'plan.json').write_text(json.dumps(plan))
        (output/'preparation.json').write_text('{}')
        receipt=output/'shards/0/record.json';receipt.parent.mkdir(parents=True)
        authority=r._VerifiedWorkerInputs.open(plan)
        def phase(*args):
            directory=output/'owned';directory.mkdir();(directory/'record.json').write_text('{}')
            (f.root/'marker/changed-during-phase').mkdir()
        class FakePhase:  # the stage starts phases through producer.Phase and finishes each as it ends
            def __init__(self,*args):self.args=args
            def done(self):return True
            def finish(self):return phase(*self.args)
        with patch.object(r.producer,'Phase',FakePhase),patch.object(r,'checked_phases'), \
                patch.object(r,'check_preparation'),patch.object(r,'check_worker_graphs'), \
                patch.object(r.raw_local_export,'phase_java',return_value='/java'):
            result=r.execute_stage(plan,[('owned',[],10)],receipt,[],authority)
        self.assertEqual(result['status'],'FAIL')
        self.assertEqual(result['finalIdentity'],'FAIL')
        self.assertIn('closure changed',result['errors'][0])
        self.assertEqual(r.common.read(receipt),result)
        with self.assertRaisesRegex(ValueError,'invalidated'):authority.check(plan)

    def test_initial_semantic_failure_and_untyped_skip_object_rejected(self):
        _,plan,replay=self.fixture();replay.side_effect=ValueError('semantic failure')
        with self.assertRaisesRegex(ValueError,'semantic failure'):r._VerifiedWorkerInputs.open(plan)
        with self.assertRaisesRegex(ValueError,'private worker'):r._check_worker_inputs(plan,object())

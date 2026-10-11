#!/usr/bin/env python3
"""Join immutable real64 lifecycle measurements to complete same-input core proof."""
import argparse
from pathlib import Path
import shutil
import subprocess
import sys
sys.dont_write_bytecode = True
import assemble_native_pressure_producers as bundle
import audit_native_pressure_artifacts as artifacts
import multigraph_pressure as common
import run_real64_construction as construction

require = common.require
PENDING = 'MEASURED_CORE_COMPARABILITY_PENDING'
SCHEMA = 'graphite.real64-lifecycle.core-comparability-join.v1'


def join_inputs(plan, producers):
    """No rewritten plans: reconstruct both authorities over the original input closure."""
    require(plan['engine'] == 'jvm' and plan['operation'] in ('loading', 'construction') and
            plan.get('comparabilityPending') is True and
            plan['pendingComparabilityAuthority'] == plan['producers'], 'explicit pending lifecycle plan')
    pending = common.pinned_authority_metadata(plan, plan['producers'])
    corrected = bundle.verify_bundle(common.read(producers), plan['arms']['A']['revision'], plan['arms']['B']['revision'])
    bundle.corrected_fixture_bindings(corrected)
    current = bundle.assemble(corrected['artifactsRoot'], corrected['baseRevision'],
                              corrected['candidateRevision'], measurement_only=True)
    return _match_inputs(plan, pending, corrected, current)


def _match_inputs(plan, pending, corrected, current):
    require(common.typed(current) == common.typed(pending) and
            common.typed(corrected['arms']) == common.typed(pending['arms']) and
            corrected['sourceInputs'] == pending['sourceInputs'] == plan['sourceInputs'] and
            all(corrected['pins'].get(p) == h for p, h in pending['pins'].items()),
            'complete core must join exactly the original lifecycle producer inputs')
    return pending, corrected


def finalize(output, prefix, producers, operation, *, artifacts_root=None, workspace=None,
             transport_directory=None, base_sha=None, candidate_sha=None, run_id=None, run_attempt=None):
    require((producers is None) != (artifacts_root is None), 'exactly one lifecycle producer input')
    output, prefix = (Path(p).resolve() for p in (output, prefix))
    combined = artifacts_root is not None
    if combined:
        import real64_lifecycle_transport as transport
        require(operation == 'construction', 'owned transport finalization is construction-only')
        require(all(v is not None for v in (workspace, transport_directory, base_sha, candidate_sha, run_id, run_attempt)),
                'complete same-run lifecycle transport identity')
        workspace = transport.canonical(Path(workspace).absolute())
        artifacts_root = transport.canonical(Path(artifacts_root).absolute())
        transport_directory = transport.canonical(Path(transport_directory).absolute())
        selected = transport.roots(workspace, operation)
        require(output == selected[0] and not any(prefix.is_relative_to(p) for p in
                [artifacts_root, transport_directory, *selected]), 'final outputs outside immutable lifecycle inputs')
        producers = Path(str(prefix)+'-corrected-producer.json')
        require(not producers.exists() and not producers.is_symlink(), 'fresh corrected producer output required')
        ident = transport.identity(workspace, operation, candidate_sha, base_sha, run_id, run_attempt)
    else:
        require(all(v is None for v in (workspace, transport_directory, base_sha, candidate_sha, run_id, run_attempt)),
                'transport requires owned artifact finalization')
        producers = Path(producers).resolve()
    require(operation in ('loading', 'construction'), 'explicit lifecycle operation')
    require(not prefix.is_relative_to(output), 'preserve original measurement directory')
    require(not any(prefix.parent.glob(prefix.name+'*')), 'fresh finalization outputs')
    prefix.parent.mkdir(parents=True, exist_ok=True)
    schema = 'graphite.real64-construction.comparison.v1' if operation == 'construction' else 'graphite.jvm64-loading.comparison.v1'
    result = {'schema': 'graphite.real64-lifecycle.finalization.v1', 'engine': 'jvm', 'operation': operation,
              'status': 'RUNNING', 'errors': [], **construction.FALSE}
    try:
        pair = None; closures = {}
        if combined:
            corrected, current = bundle.assemble_finalization_pair(artifacts_root, base_sha, candidate_sha)
            bundle.corrected_fixture_bindings(corrected)
            for arm in current['arms'].values():
                ref = arm['artifactAudit']
                if ref['path'] not in closures:
                    report = common.pinned_authority_metadata(current, ref)
                    closures[ref['path']] = (report, artifacts._ProducerReplayContext._inventories(report))
            common.save(producers, corrected)
            pair = (corrected, current)
            transport.restore(workspace, operation, producers, transport_directory, ident)
        if operation == 'construction':
            from audit_real64_construction import audit
            validate = construction.validate
        else:
            from audit_real64_loading import audit
            from run_real64_loading import validate
        plan_file = output/'plan.json'
        plan = validate(common.read(plan_file))
        require(plan['operation'] == operation, 'operation-specific finalization')
        refs = {name: artifacts.ref(path) for name, path in
                [('plan', plan_file), ('measurementExecution', output/'cells/execution.json'),
                 ('measurementAudit', output/'audit.json'), ('correctedProducer', producers)]}
        # Full raw replay includes construction's six fresh graph byte inventories.
        actual = audit(plan_file, output/'cells')
        require(common.typed(actual) == common.typed(common.read(output/'audit.json')),
                'original lifecycle audit differs from complete raw replay')
        if pair is None:
            pending, corrected = join_inputs(plan, producers)
        else:
            pending = common.pinned_authority_metadata(plan, plan['producers'])
            require(plan['comparabilityPending'] is True and plan['pendingComparabilityAuthority'] == plan['producers'] and
                    pending['artifactsRoot'] == str(artifacts_root), 'exact original pending lifecycle authority')
            pending, corrected = _match_inputs(plan, pending, *pair)
            require(common.typed(common.read(producers)) == common.typed(corrected), 'generated corrected producer drift')
            # The old post-restore assembly supplied a late byte/source/closed-
            # inventory boundary. Preserve it without another semantic replay.
            artifacts.verify_pins(corrected['pins'])
            for report, closure in closures.values(): artifacts._ProducerReplayContext._check(report, closure)
        pins = dict(plan['pins']); bundle.merge_pins(pins, corrected['pins'])
        bundle.merge_pins(pins, {r['path']: r['sha256'] for r in refs.values()})
        metadata_refs = [plan['producers'], refs['correctedProducer']]
        for comparison in corrected['correctedComparisons'].values():
            core_audit = common.pinned_authority_metadata(corrected, comparison['audit'])
            record = common.pinned_authority_metadata(corrected, core_audit['record'])
            metadata_refs.extend([comparison['audit'], core_audit['record'], record['plan'], *record['phases']])
        metadata_dir = Path(str(prefix)+'-comparability-evidence'); metadata_dir.mkdir()
        metadata = []
        for index, ref in enumerate(metadata_refs):
            data = Path(ref['path']).read_bytes()
            require(common.digest_bytes(data) == ref['sha256'], 'core metadata drift')
            target = metadata_dir/f'{index:03d}-{Path(ref["path"]).name}'; target.write_bytes(data)
            metadata.append({**ref, 'relativePath': str(target.relative_to(prefix.parent))})
        receipt = {'schema': SCHEMA, 'engine': 'jvm', 'operation': operation,
                   'status': 'PASS_SAME_INPUT_COMPLETE_CORRECTED_CORE_JOIN', **refs,
                   'measurementProducer': plan['producers'], 'measurementInputsSha256': plan['producers']['sha256'],
                   'coreComparisons': corrected['correctedComparisons'], 'pins': pins, 'metadata': metadata,
                   'coreComparabilityVerified': True, **construction.FALSE}
        receipt_file = Path(str(prefix)+'-comparability.json'); common.save(receipt_file, receipt)
        node = shutil.which('node'); require(node is not None, 'Node executable missing')
        command = [node, str(Path(__file__).with_name(f'benchmark-{operation}.mjs')),
                   str(plan_file), str(output), str(prefix), '--comparability-receipt', str(receipt_file)]
        completed = subprocess.run(command, capture_output=True, text=True, timeout=60, check=False)
        Path(str(prefix)+'-comparison.stdout.log').write_text(completed.stdout)
        Path(str(prefix)+'-comparison.stderr.log').write_text(completed.stderr)
        artifacts.verify_pins(pins)
        for report, closure in closures.values(): artifacts._ProducerReplayContext._check(report, closure)
        require(artifacts.ref(output/'audit.json') == refs['measurementAudit'] and
                artifacts.ref(output/'cells/execution.json') == refs['measurementExecution'], 'original measurement changed')
        verdict = common.read(str(prefix)+'-status.json')
        require(completed.returncode in (0, 1) and verdict['schema'] == schema and
                verdict['planSha256'] == refs['plan']['sha256'] and verdict['engine'] == 'jvm' and
                verdict['operation'] == operation and verdict['passed'] == (completed.returncode == 0) and
                verdict.get('coreComparabilityVerified') is True and verdict.get('comparabilityPending') is False,
                'actual operation-specific joined comparison')
        result.update(status=verdict['status'], performanceAcceptance=verdict['passed'],
                      comparabilityReceipt=artifacts.ref(receipt_file), comparisonCommand=command)
    except BaseException as error:
        result.update(status='FAIL', errors=[repr(error)])
        for suffix in ('status.json', 'report.md'):
            path = Path(str(prefix)+'-'+suffix)
            if path.exists(): path.rename(Path(str(prefix)+'-comparison-original-'+suffix))
        common.save(str(prefix)+'-status.json', {'schema': schema, 'engine': 'jvm', 'operation': operation,
                    'status': 'FAIL', 'passed': False, 'errors': result['errors'], **construction.FALSE})
        Path(str(prefix)+'-report.md').write_text(f'{operation} full core/measurement join failed\n{error!r}\n')
    finally:
        common.save(str(prefix)+'-finalization.json', result)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('output', 'prefix'): parser.add_argument('--'+name, required=True)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument('--producers'); inputs.add_argument('--artifacts', dest='artifacts_root')
    for name in ('workspace', 'transport-directory', 'base-sha', 'candidate-sha', 'run-id', 'run-attempt'):
        parser.add_argument('--'+name)
    parser.add_argument('--operation', choices=('loading', 'construction'), required=True)
    args = parser.parse_args()
    sys.exit(0 if finalize(args.output, args.prefix, args.producers, args.operation,
        **{name: getattr(args, name) for name in ('artifacts_root', 'workspace', 'transport_directory',
           'base_sha', 'candidate_sha', 'run_id', 'run_attempt')})['performanceAcceptance'] else 1)

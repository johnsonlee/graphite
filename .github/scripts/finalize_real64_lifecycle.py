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
    require(common.typed(current) == common.typed(pending) and
            common.typed(corrected['arms']) == common.typed(pending['arms']) and
            corrected['sourceInputs'] == pending['sourceInputs'] == plan['sourceInputs'] and
            all(corrected['pins'].get(p) == h for p, h in pending['pins'].items()),
            'complete core must join exactly the original lifecycle producer inputs')
    return pending, corrected


def finalize(output, prefix, producers, operation):
    output, prefix, producers = (Path(p).resolve() for p in (output, prefix, producers))
    require(operation in ('loading', 'construction'), 'explicit lifecycle operation')
    require(not prefix.is_relative_to(output), 'preserve original measurement directory')
    require(not any(prefix.parent.glob(prefix.name+'*')), 'fresh finalization outputs')
    prefix.parent.mkdir(parents=True, exist_ok=True)
    schema = 'graphite.real64-construction.comparison.v1' if operation == 'construction' else 'graphite.jvm64-loading.comparison.v1'
    result = {'schema': 'graphite.real64-lifecycle.finalization.v1', 'engine': 'jvm', 'operation': operation,
              'status': 'RUNNING', 'errors': [], **construction.FALSE}
    try:
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
        pending, corrected = join_inputs(plan, producers)
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
    for name in ('output', 'prefix', 'producers'): parser.add_argument('--'+name, required=True)
    parser.add_argument('--operation', choices=('loading', 'construction'), required=True)
    args = parser.parse_args()
    sys.exit(0 if finalize(args.output, args.prefix, args.producers, args.operation)['performanceAcceptance'] else 1)

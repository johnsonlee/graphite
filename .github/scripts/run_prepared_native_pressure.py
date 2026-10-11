#!/usr/bin/env python3
"""Execute an already verified query plan, sequentially audit all six cells, then compare.

This does not produce fixtures or invent missing authority. No plan means UNAVAILABLE.
Every failed cell and unissued cell remains in the execution receipt. Construction and
loading are not accepted by this query-only command.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import multigraph_pressure as pressure

SCRIPTS=Path(__file__).resolve().parent
PENDING = 'MEASURED_CORE_COMPARABILITY_PENDING'
JOIN_SCHEMA = 'graphite.native-pressure.core-comparability-join.v1'
JOIN_PASS = 'PASS_SAME_INPUT_COMPLETE_CORRECTED_CORE_JOIN'


def execute(preparation,output,prefix,*,engine='native'):
    pressure.require(engine in ('native','jvm'),'known query engine required')
    preparation=preparation.resolve();output=output.resolve();prefix=prefix.resolve()
    output.mkdir(parents=True,exist_ok=False)
    record={'schema':f'graphite.{engine}-pressure.execution.v1','engine':engine,'status':'RUNNING','errors':[],
            'cells':[],'unissued':[],'performanceAcceptance':False,'otherOperationsEligible':False,
            'controls':{str(p):pressure.sha(p) for p in [Path(__file__).resolve(),SCRIPTS/'multigraph_pressure.py',SCRIPTS/'native_legal_response.py',SCRIPTS/'benchmark-multigraph-pressure.mjs']}}
    if engine=='jvm':
        for name in ('jvm_pressure_oracles.py','jvm_pressure_distinct.py','jvm_pressure_inputs.py'):
            control=SCRIPTS/name;record['controls'][str(control)]=pressure.sha(control)
    def write(path,value):
        path.parent.mkdir(parents=True,exist_ok=True)
        temporary=path.with_name(path.name+'.tmp')
        temporary.write_text(json.dumps(value,indent=2,allow_nan=False)+'\n');temporary.replace(path)
    def save():write(output/'execution.json',record)
    def failed_report(status,errors):
        write(Path(str(prefix)+'-status.json'),{'schema':'graphite.multigraph-pressure.comparison.v1',
            'scope':'multi-graph-pressure','engine':engine,'operation':'query','passed':False,'status':status,
            'errors':errors,'queryEvidenceComplete':False,'otherOperationsEligible':False,
            **({'missingProducers':record['missingProducers']} if 'missingProducers' in record else {})})
        Path(str(prefix)+'-report.md').write_text(f'### {engine.upper()} multi-graph pressure\n\n'+status+'\n\n'+'\n'.join(errors)+'\n')
    save()
    try:
        ready_file=preparation/'preparation-status.json';ready=pressure.read(ready_file)
        record['preparation']={'path':str(ready_file),'sha256':pressure.sha(ready_file)}
        if engine=='jvm':
            pressure.require(ready.get('schema')=='graphite.jvm-pressure.preparation.v1' and
                             ready.get('engine')==engine and ready.get('operation')=='query',
                             'explicit JVM query preparation required')
        if ready['status']!='PLAN_READY_NOT_MEASURED':
            pressure.require(ready['status'] in ('UNAVAILABLE','FAIL'),'unknown preparation status')
            reasons=[]
            for key in ('missingProducers','errors'):
                values=ready.get(key,[])
                pressure.require(isinstance(values,list) and all(isinstance(value,str) and value.strip() for value in values),
                                 'malformed preparation '+key)
                reasons.extend(values)
            if 'missingProducers' in ready:record['missingProducers']=ready['missingProducers']
            record['status']=ready['status']
            record['errors']=['Matched producer plan is not ready: '+ready['status'],*reasons]
            failed_report(record['status'],record['errors']);return record
        plan_file=preparation/'plan.json'
        pressure.require(pressure.sha(plan_file)==ready['planSha256'],'prepared plan digest changed')
        context=pressure._VerifiedPressureExecution.open(plan_file);plan=context.plan
        pressure.require(plan['engine']==engine and plan['operation']=='query','matching query engine required')
        pressure.require([c['arm'] for c in plan['cells']]==list('CABBAC'),'fixed six-cell comparison order')
        record['plan']={'path':str(plan_file),'sha256':ready['planSha256']}
        if 'pendingComparabilityAuthority' in plan:
            record['comparabilityPending'] = True
        record['unissued']=[c['id'] for c in plan['cells']];save()
        for cell in plan['cells']:
            record['unissued'].remove(cell['id'])
            attempt={'id':cell['id'],'status':'RUNNING'};record['cells'].append(attempt);save()
            directory=output/cell['id']
            try:
                result=context.run_cell(cell['id'],str(directory))
                pressure.require(result['status']=='PASS','pressure cell failed '+cell['id'])
                audit=context.audit_cell(str(directory))
                pressure.require(audit['status']=='PASS','pressure audit failed '+cell['id'])
                pressure.save(directory/'audit.json',audit)
                attempt.update(status='PASS',resultSha256=pressure.sha(directory/'result.json'),auditSha256=pressure.sha(directory/'audit.json'))
            except BaseException as error:
                attempt.update(status='FAIL',error=repr(error));raise
            finally:save()
        context._check_unchanged()
        node=shutil.which('node');pressure.require(node is not None,'Node executable missing')
        comparator=SCRIPTS/'benchmark-multigraph-pressure.mjs'
        command=[str(Path(node).resolve()),str(comparator),str(plan_file),str(output),str(prefix)]
        completed=subprocess.run(command,capture_output=True,text=True,timeout=60,check=False)
        (output/'comparison.stdout.log').write_text(completed.stdout);(output/'comparison.stderr.log').write_text(completed.stderr)
        context._check_unchanged()
        record['comparisonCommand']=command;record['comparisonExit']=completed.returncode
        verdict=pressure.read(Path(str(prefix)+'-status.json'))
        pressure.require(completed.returncode in (0,1) and verdict['schema']=='graphite.multigraph-pressure.comparison.v1','comparison failed to produce a verdict')
        pressure.require(verdict.get('planSha256')==ready['planSha256'],'comparison verdict plan binding')
        pressure.require(verdict.get('engine')==engine and verdict.get('operation')=='query','comparison engine binding')
        pressure.require(verdict['passed']==(completed.returncode==0),'comparison exit/verdict mismatch')
        if 'pendingComparabilityAuthority' in plan:
            pressure.require(verdict['passed'] is False and verdict['status'] == PENDING and
                             verdict.get('comparabilityPending') is True,
                             'unjoined measurement cannot become performance acceptance')
        record['status']=verdict['status'];record['performanceAcceptance']=verdict['passed']
        record['comparisonSha256']=pressure.sha(Path(str(prefix)+'-status.json'))
    except BaseException as error:
        record['status']='FAIL';record['errors'].append(repr(error));failed_report('FAIL',record['errors'])
    finally:
        for p,h in record['controls'].items():
            try:unchanged=pressure.sha(p)==h
            except OSError:unchanged=False
            if not unchanged:
                record['status']='FAIL';record['performanceAcceptance']=False;record['errors'].append('execution control changed '+p)
                failed_report('FAIL',record['errors'])
        save()
    return record


def finalize(preparation, output, prefix, producers):
    """Join complete core evidence to immutable measured cells; never rerun a server.

    The original plan and raw cell evidence stay bound to their measurement-time
    input pins. Only a separate receipt/report is created after both independent
    audit chains have been replayed against exactly those same inputs.
    """
    import assemble_native_pressure_producers as bundle
    preparation, output, prefix, producers = (p.resolve() for p in (preparation, output, prefix, producers))
    prefix.parent.mkdir(parents=True, exist_ok=True)
    paths = [Path(str(prefix)+suffix) for suffix in
             ('-status.json', '-report.md', '-comparability.json', '-comparability-evidence',
              '-finalization.json', '-comparison.stdout.log', '-comparison.stderr.log',
              '-comparison-original-status.json', '-comparison-original-report.md')]
    pressure.require(not any(p.exists() for p in paths), 'fresh finalization outputs required')
    pressure.require(not prefix.is_relative_to(output) and not prefix.is_relative_to(preparation),
                     'finalization must preserve original measurement directories')
    result = {'schema': 'graphite.native-pressure.finalization.v1', 'status': 'RUNNING',
              'errors': [], 'performanceAcceptance': False, 'otherOperationsEligible': False}
    def ref(path):
        path = Path(path)
        pressure.require(not path.is_symlink(), 'finalization input symlink')
        return {'path': str(path), 'sha256': pressure.sha(path)}
    try:
        ready_file = preparation/'preparation-status.json'
        ready = pressure.read(ready_file)
        plan_file = preparation/'plan.json'
        pressure.require(ready['status'] == 'PLAN_READY_NOT_MEASURED' and ready.get('comparabilityPending') is True and
                         ready['planSha256'] == pressure.sha(plan_file), 'actual pending measurement preparation')
        context = pressure._VerifiedPressureExecution.open(plan_file)
        plan = context.plan
        pressure.require(plan['engine'] == 'native' and plan.get('comparabilityPending') is True and
                         'pendingComparabilityAuthority' in plan, 'only pending Native39 measurement may be joined')
        measured_ref = ref(output/'execution.json')
        measured = pressure.read(measured_ref['path'])
        pressure.require(measured['schema'] == 'graphite.native-pressure.execution.v1' and measured['engine'] == 'native' and
                         measured['status'] == PENDING and measured.get('comparabilityPending') is True and
                         measured['performanceAcceptance'] is False and measured['otherOperationsEligible'] is False and
                         measured['errors'] == [] and measured['unissued'] == [] and
                         measured['plan'] == ref(plan_file) and measured['preparation'] == ref(ready_file),
                         'complete immutable pending execution required')
        pressure.require(all(pressure.sha(path) == digest for path, digest in measured['controls'].items()),
                         'original measurement controls changed')
        pressure.require([c['id'] for c in measured['cells']] == [c['id'] for c in plan['cells']],
                         'all six original measured cells required')
        cells = []
        for expected, cell in zip(plan['cells'], measured['cells']):
            directory = output/expected['id']
            result_ref, audit_ref = ref(directory/'result.json'), ref(directory/'audit.json')
            pressure.require(cell['status'] == 'PASS' and cell['resultSha256'] == result_ref['sha256'] and
                             cell['auditSha256'] == audit_ref['sha256'], 'original successful cell binding')
            actual = context.audit_cell(str(directory))
            pressure.require(pressure.typed(actual) == pressure.typed(pressure.read(audit_ref['path'])),
                             'original cell audit differs from full raw response/resource replay')
            cells.append({'id': expected['id'], 'result': result_ref, 'audit': audit_ref})
        pending_ref = plan['pendingComparabilityAuthority']
        pending = pressure.pinned_authority_metadata(plan, pending_ref)
        corrected_ref = ref(producers)
        corrected = bundle.verify_bundle(pressure.read(producers), plan['arms']['A']['revision'], plan['arms']['B']['revision'])
        bundle.corrected_fixture_bindings(corrected)
        # Re-assemble only the measured producer scope even when core has since
        # appeared. Neither optional core files nor a new writer may rebind it.
        current = bundle.assemble(corrected['artifactsRoot'], corrected['baseRevision'],
                                  corrected['candidateRevision'], measurement_only=True)
        pressure.require(pressure.typed(current) == pressure.typed(pending) and
                         pressure.typed(corrected['arms']) == pressure.typed(pending['arms']) and
                         all(corrected['pins'].get(p) == h for p, h in pending['pins'].items()),
                         'complete core must join exactly the originally measured producer inputs')
        context._check_unchanged()
        pins = dict(plan['pins'])
        bundle.merge_pins(pins, corrected['pins'])
        refs = [ref(plan_file), measured_ref, ref(ready_file), pending_ref, corrected_ref,
                *[r for cell in cells for r in (cell['result'], cell['audit'])]]
        bundle.merge_pins(pins, {r['path']: r['sha256'] for r in refs})
        # Small original metadata is carried for independent report aggregation;
        # paths inside these byte-identical records are never rewritten.
        metadata_refs = [pending_ref, corrected_ref]
        for comparison in corrected['correctedComparisons'].values():
            audit_ref = comparison['audit']
            audit = pressure.pinned_authority_metadata(corrected, audit_ref)
            raw_record = pressure.pinned_authority_metadata(corrected, audit['record'])
            metadata_refs.extend([audit_ref, audit['record'], raw_record['plan'], *raw_record['phases']])
        metadata_dir = Path(str(prefix)+'-comparability-evidence')
        metadata_dir.mkdir()
        metadata = []
        for index, item in enumerate(metadata_refs):
            raw = Path(item['path']).read_bytes()
            pressure.require(pressure.digest_bytes(raw) == item['sha256'], 'joined metadata changed')
            target = metadata_dir/f'{index:02d}-{Path(item["path"]).name}'
            target.write_bytes(raw)
            metadata.append({**item, 'relativePath': str(target.relative_to(prefix.parent))})
        receipt = {'schema': JOIN_SCHEMA, 'status': JOIN_PASS, 'plan': ref(plan_file),
                   'measurementExecution': measured_ref, 'measurementProducer': pending_ref,
                   'correctedProducer': corrected_ref,
                   'measurementInputsSha256': pending_ref['sha256'],
                   'coreComparisons': corrected['correctedComparisons'], 'cells': cells,
                   'pins': pins, 'metadata': metadata, 'coreComparabilityVerified': True,
                   **{k: False for k in ('completeSemanticEquivalence', 'strictEquivalence',
                       'sourceToDeclarationCompletenessClaim', 'performanceAcceptance', 'otherOperationsEligible')}}
        receipt_file = Path(str(prefix)+'-comparability.json')
        pressure.save(receipt_file, receipt)
        node = shutil.which('node');pressure.require(node is not None, 'Node executable missing')
        command = [str(Path(node).resolve()), str(SCRIPTS/'benchmark-multigraph-pressure.mjs'),
                   str(plan_file), str(output), str(prefix), '--comparability-receipt', str(receipt_file)]
        completed = subprocess.run(command, capture_output=True, text=True, timeout=60, check=False)
        Path(str(prefix)+'-comparison.stdout.log').write_text(completed.stdout)
        Path(str(prefix)+'-comparison.stderr.log').write_text(completed.stderr)
        context._check_unchanged()
        pressure.require(all(pressure.sha(path) == digest for path, digest in pins.items()), 'joined input drift')
        verdict = pressure.read(Path(str(prefix)+'-status.json'))
        pressure.require(completed.returncode in (0, 1) and verdict['schema'] == 'graphite.multigraph-pressure.comparison.v1' and
                         verdict.get('planSha256') == ready['planSha256'] and verdict.get('engine') == 'native' and
                         verdict.get('operation') == 'query' and verdict['passed'] == (completed.returncode == 0),
                         'bound final comparison verdict')
        pressure.require(verdict.get('coreComparabilityVerified') is True and
                         verdict.get('comparabilityPending') is False, 'final comparator must consume the joined proof')
        result.update(status=verdict['status'], performanceAcceptance=verdict['passed'],
                      comparabilityReceipt=ref(receipt_file), comparisonCommand=command,
                      comparisonExit=completed.returncode,
                      comparison=ref(Path(str(prefix)+'-status.json')))
    except BaseException as error:
        result['status'] = 'FAIL'; result['errors'].append(repr(error))
        # Preserve the comparator's raw output if a subsequent identity check
        # fails; only the final status below is the acceptance result.
        for suffix in ('status.json', 'report.md'):
            path = Path(str(prefix)+'-'+suffix)
            if path.exists():
                path.rename(Path(str(prefix)+'-comparison-original-'+suffix))
        pressure.save(Path(str(prefix)+'-status.json'), {
            'schema': 'graphite.multigraph-pressure.comparison.v1', 'scope': 'multi-graph-pressure',
            'engine': 'native', 'operation': 'query', 'passed': False, 'status': 'FAIL',
            'errors': result['errors'], 'queryEvidenceComplete': False, 'otherOperationsEligible': False})
        Path(str(prefix)+'-report.md').write_text('Native core/measurement join failed\n'+repr(error)+'\n')
    finally:
        pressure.save(Path(str(prefix)+'-finalization.json'), result)
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--preparation',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True);p.add_argument('--prefix',type=Path,required=True)
    p.add_argument('--engine',choices=('native','jvm'),default='native')
    p.add_argument('--finalize-producers',type=Path);args=p.parse_args()
    if args.finalize_producers:
        pressure.require(args.engine == 'native', 'core join is native-only')
        result=finalize(args.preparation,args.output,args.prefix,args.finalize_producers)
    else:
        result=execute(args.preparation,args.output,args.prefix,engine=args.engine)
    print(json.dumps({'status':result['status'],'issuedCells':len(result.get('cells',[])),
                      'unissuedCells':result.get('unissued',[])}))
    sys.exit(0 if result['performanceAcceptance'] or
             (not args.finalize_producers and result['status'] == PENDING and
              result.get('comparabilityPending') is True and not result['errors']) else 1)

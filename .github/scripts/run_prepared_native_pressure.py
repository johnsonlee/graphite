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
import time
import multigraph_pressure as pressure

SCRIPTS=Path(__file__).resolve().parent
PENDING = 'MEASURED_CORE_COMPARABILITY_PENDING'
JOIN_SCHEMA = 'graphite.native-pressure.core-comparability-join.v1'
JOIN_PASS = 'PASS_SAME_INPUT_COMPLETE_CORRECTED_CORE_JOIN'


def loading_comparison(plan_file, output, prefix, receipt=None):
    prefix=Path(prefix).resolve()
    node=shutil.which('node');pressure.require(node is not None,'Node executable missing')
    command=[str(Path(node).resolve()),str(SCRIPTS/'benchmark-native-loading.mjs'),
             str(plan_file),str(output),str(prefix)]
    if receipt is not None:command.extend(['--comparability-receipt',str(receipt)])
    completed=subprocess.run(command,capture_output=True,text=True,timeout=60,check=False)
    Path(str(prefix)+'-comparison.stdout.log').write_text(completed.stdout)
    Path(str(prefix)+'-comparison.stderr.log').write_text(completed.stderr)
    value=pressure.read(str(prefix)+'-status.json')
    pressure.require(completed.returncode in (0,1) and value.get('schema')=='graphite.native64-loading.comparison.v1' and
                     value.get('engine')=='native' and value.get('operation')=='loading' and
                     value.get('passed')==(completed.returncode==0),'actual independent Native loading verdict')
    return value


def unavailable_loading(prefix, errors):
    if prefix is None:return
    prefix=Path(prefix).resolve();prefix.parent.mkdir(parents=True,exist_ok=True)
    for suffix in ('status.json','report.md'):
        path=Path(str(prefix)+'-'+suffix)
        if path.exists():path.rename(Path(str(prefix)+'-original-'+suffix))
    pressure.save(str(prefix)+'-status.json',{'schema':'graphite.native64-loading.comparison.v1','engine':'native',
        'operation':'loading','status':'UNAVAILABLE','passed':False,'errors':errors,
        'performanceAcceptance':False,'otherOperationsEligible':False})
    Path(str(prefix)+'-report.md').write_text('Native64 loading unavailable\n'+'\n'.join(errors)+'\n')


def execute(preparation,output,prefix,*,engine='native',loading_prefix=None):
    pressure.require(engine in ('native','jvm'),'known query engine required')
    preparation=preparation.resolve();output=output.resolve();prefix=prefix.resolve()
    output.mkdir(parents=True,exist_ok=False)
    record={'schema':f'graphite.{engine}-pressure.execution.v1','engine':engine,'status':'RUNNING','errors':[],
            'cells':[],'unissued':[],'performanceAcceptance':False,'otherOperationsEligible':False,
            'controls':{str(p):pressure.sha(p) for p in [Path(__file__).resolve(),SCRIPTS/'multigraph_pressure.py',SCRIPTS/'native_legal_response.py',SCRIPTS/'benchmark-multigraph-pressure.mjs']}}
    if loading_prefix is not None:
        pressure.require(engine == 'native', 'Native loading prefix requires Native query processes')
        for name in ('native_loading.py','benchmark-native-loading.mjs'):
            control=SCRIPTS/name;record['controls'][str(control)]=pressure.sha(control)
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
            failed_report(record['status'],record['errors']);unavailable_loading(loading_prefix,record['errors']);return record
        plan_file=preparation/'plan.json'
        pressure.require(pressure.sha(plan_file)==ready['planSha256'],'prepared plan digest changed')
        context_started=time.monotonic()
        print('pressure context: verifying authority and input closure',flush=True)
        context=pressure._VerifiedPressureExecution.open(plan_file);plan=context.plan
        print(f'pressure context: verified elapsed={time.monotonic()-context_started:.3f}s',flush=True)
        pressure.require(plan['engine']==engine and plan['operation']=='query','matching query engine required')
        pressure.require([c['arm'] for c in plan['cells']]==list('CABBAC'),'fixed six-cell comparison order')
        record['plan']={'path':str(plan_file),'sha256':ready['planSha256']}
        if 'pendingComparabilityAuthority' in plan or 'pendingJvmComparabilityAuthority' in plan:
            record['comparabilityPending'] = True
        record['unissued']=[c['id'] for c in plan['cells']];save()
        node=shutil.which('node');pressure.require(node is not None,'Node executable missing')
        comparator=SCRIPTS/'benchmark-multigraph-pressure.mjs'
        for cell in plan['cells']:
            record['unissued'].remove(cell['id'])
            attempt={'id':cell['id'],'status':'RUNNING'};record['cells'].append(attempt);save()
            directory=output/cell['id']
            try:
                result,audit=context.run_and_audit_cell(cell['id'],str(directory))
                pressure.require(result['status']=='PASS','pressure cell failed '+cell['id'])
                pressure.require(audit['status']=='PASS','pressure audit failed '+cell['id'])
                pressure.save(directory/'audit.json',audit)
                attempt.update(status='PASS',resultSha256=pressure.sha(directory/'result.json'),auditSha256=pressure.sha(directory/'audit.json'))
            except BaseException as error:
                attempt.update(status='FAIL',error=repr(error));raise
            finally:save()
            # Report existing fixed pairs as soon as both fully audited cells
            # exist. This never shortens the schedule or produces acceptance.
            if len(record['cells']) in (3,5):
                try:
                    count=len(record['cells']);progress_prefix=output/f'progress-{count}'
                    command=[str(Path(node).resolve()),str(comparator),str(plan_file),str(output),
                             str(progress_prefix),'--progress',str(count)]
                    completed=subprocess.run(command,capture_output=True,text=True,timeout=60,check=False)
                    Path(str(progress_prefix)+'.stdout.log').write_text(completed.stdout)
                    Path(str(progress_prefix)+'.stderr.log').write_text(completed.stderr)
                    progress_file=Path(str(progress_prefix)+'-status.json')
                    progress=pressure.read(progress_file)
                    pressure.require(completed.returncode==0 and
                                     progress.get('schema')=='graphite.multigraph-pressure.progress.v1' and
                                     progress.get('status')=='PROVISIONAL' and progress.get('planSha256')==ready['planSha256'] and
                                     progress.get('completedCells')==[c['id'] for c in record['cells']] and
                                     progress.get('engine')==engine and progress.get('operation')=='query' and
                                     all(progress.get(k) is False for k in
                                         ('passed','performanceAcceptance','queryEvidenceComplete','otherOperationsEligible')),
                                     'provisional comparison must preserve incomplete scope and exact completed cells')
                    record.setdefault('progress',[]).append({'completedCells':count,
                        'path':str(progress_file),'sha256':pressure.sha(progress_file)})
                    save()
                    print(Path(str(progress_prefix)+'-report.md').read_text(),flush=True)
                except Exception as error:
                    # Diagnostics are not a new dependency of the measurement.
                    # Keep the failure visible and preserve the complete schedule.
                    record.setdefault('progressErrors',[]).append({
                        'completedCells':len(record['cells']),'error':repr(error)})
                    save()
                    print(f'pressure provisional report failed: {error!r}; continuing full measurement',flush=True)
        context._check_unchanged()
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
        if 'pendingComparabilityAuthority' in plan or 'pendingJvmComparabilityAuthority' in plan:
            pressure.require(verdict['passed'] is False and verdict['status'] == PENDING and
                             verdict.get('comparabilityPending') is True,
                             'unjoined measurement cannot become performance acceptance')
        if loading_prefix is not None:
            loading=loading_comparison(plan_file,output,loading_prefix)
            unavailable=('loadingMeasurement' not in plan and loading['passed'] is False and loading['status']=='UNAVAILABLE')
            if 'pendingComparabilityAuthority' in plan or 'pendingJvmComparabilityAuthority' in plan:
                pressure.require(unavailable or (loading.get('planSha256')==ready['planSha256'] and loading['passed'] is False and
                                 loading.get('comparabilityPending') is True and loading['status']==PENDING),
                                 'pending Native loading cannot authorize performance acceptance')
            else:
                pressure.require(unavailable or (loading.get('planSha256')==ready['planSha256'] and
                                 loading.get('coreComparabilityVerified') is True and loading.get('comparabilityPending') is False),
                                 'complete Native loading must bind its original plan')
            record['nativeLoadingComparison']={'path':str(Path(str(loading_prefix)+'-status.json').resolve()),
                                               'sha256':pressure.sha(str(loading_prefix)+'-status.json')}
        record['status']=verdict['status'];record['performanceAcceptance']=verdict['passed']
        record['comparisonSha256']=pressure.sha(Path(str(prefix)+'-status.json'))
        if loading_prefix is not None and 'pendingComparabilityAuthority' not in plan:
            record.update(queryPerformanceAcceptance=verdict['passed'],nativeLoadingPerformanceAcceptance=loading['passed'],
                          performanceAcceptance=verdict['passed'] and loading['passed'],
                          status='PASS' if verdict['passed'] and loading['passed'] else 'FAIL')
    except BaseException as error:
        record['status']='FAIL';record['errors'].append(repr(error));failed_report('FAIL',record['errors'])
        unavailable_loading(loading_prefix,record['errors'])
    finally:
        for p,h in record['controls'].items():
            try:unchanged=pressure.sha(p)==h
            except OSError:unchanged=False
            if not unchanged:
                record['status']='FAIL';record['performanceAcceptance']=False;record['errors'].append('execution control changed '+p)
                failed_report('FAIL',record['errors'])
                unavailable_loading(loading_prefix,record['errors'])
        save()
    return record


def finalize(preparation, output, prefix, producers, loading_prefix=None, *, engine="native"):
    """Join complete core evidence to immutable measured cells; never rerun a server.

    The original plan and raw cell evidence stay bound to their measurement-time
    input pins. Only a separate receipt/report is created after both independent
    audit chains have been replayed against exactly those same inputs.
    """
    import assemble_native_pressure_producers as bundle
    pressure.require(engine in ('native','jvm') and (loading_prefix is None or engine=='native'),'exact finalization operation')
    authority_key='pendingJvmComparabilityAuthority' if engine=='jvm' else 'pendingComparabilityAuthority'
    preparation, output, prefix, producers = (p.resolve() for p in (preparation, output, prefix, producers))
    prefix.parent.mkdir(parents=True, exist_ok=True)
    if loading_prefix is not None:
        loading_prefix=Path(loading_prefix).resolve()
        pressure.require(not loading_prefix.is_relative_to(output) and not loading_prefix.is_relative_to(preparation) and
                         loading_prefix != prefix and not any(loading_prefix.parent.glob(loading_prefix.name+'*')),
                         'fresh separate Native loading report prefix')
    paths = [Path(str(prefix)+suffix) for suffix in
             ('-status.json', '-report.md', '-comparability.json', '-comparability-evidence',
              '-finalization.json', '-comparison.stdout.log', '-comparison.stderr.log',
              '-comparison-original-status.json', '-comparison-original-report.md')]
    pressure.require(not any(p.exists() for p in paths), 'fresh finalization outputs required')
    pressure.require(not prefix.is_relative_to(output) and not prefix.is_relative_to(preparation),
                     'finalization must preserve original measurement directories')
    result = {'schema': f'graphite.{engine}-pressure.finalization.v1', 'status': 'RUNNING',
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
        pressure.require(plan['engine'] == engine and plan.get('comparabilityPending') is True and
                         authority_key in plan, 'only explicitly pending matching-engine measurement may be joined')
        measured_ref = ref(output/'execution.json')
        measured = pressure.read(measured_ref['path'])
        pressure.require(measured['schema'] == f'graphite.{engine}-pressure.execution.v1' and measured['engine'] == engine and
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
        pending_ref = plan[authority_key]
        pending = pressure.pinned_authority_metadata(plan, pending_ref)
        corrected_ref = ref(producers)
        if engine=='jvm':
            import jvm_pressure_join as jvm_join
            corrected,current=jvm_join.verify(pressure.read(producers))
        else:
            corrected = bundle.verify_bundle(pressure.read(producers), plan['arms']['A']['revision'], plan['arms']['B']['revision'])
            bundle.corrected_fixture_bindings(corrected)
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
        if engine=='jvm':metadata_refs.extend(jvm_join.metadata(corrected))
        for comparison in corrected['correctedComparisons'].values():
            audit_ref = comparison['audit']
            audit = pressure.pinned_authority_metadata(corrected, audit_ref)
            raw_record = pressure.pinned_authority_metadata(corrected, audit['record'])
            metadata_refs.extend([audit_ref, audit['record'], raw_record['plan'], *raw_record['phases']])
        metadata_dir = Path(str(prefix)+'-comparability-evidence')
        metadata_dir.mkdir()
        metadata = []
        metadata_refs=list({item['path']:item for item in metadata_refs}.values())
        for index, item in enumerate(metadata_refs):
            raw = Path(item['path']).read_bytes()
            pressure.require(pressure.digest_bytes(raw) == item['sha256'], 'joined metadata changed')
            target = metadata_dir/f'{index:02d}-{Path(item["path"]).name}'
            target.write_bytes(raw)
            metadata.append({**item, 'relativePath': str(target.relative_to(prefix.parent))})
        receipt = {'schema': jvm_join.SCHEMA if engine=='jvm' else JOIN_SCHEMA, 'status': JOIN_PASS, 'plan': ref(plan_file),
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
        loading=None
        if loading_prefix is not None:
            loading=loading_comparison(plan_file,output,loading_prefix,receipt_file)
            pressure.require((loading.get('planSha256')==ready['planSha256'] and
                              loading.get('coreComparabilityVerified') is True and loading.get('comparabilityPending') is False) or
                             ('loadingMeasurement' not in plan and loading['passed'] is False and loading['status']=='UNAVAILABLE'),
                             'Native loading requires the same complete core join or remains unavailable')
        context._check_unchanged()
        pressure.require(all(pressure.sha(path) == digest for path, digest in pins.items()), 'joined input drift')
        verdict = pressure.read(Path(str(prefix)+'-status.json'))
        pressure.require(completed.returncode in (0, 1) and verdict['schema'] == 'graphite.multigraph-pressure.comparison.v1' and
                         verdict.get('planSha256') == ready['planSha256'] and verdict.get('engine') == engine and
                         verdict.get('operation') == 'query' and verdict['passed'] == (completed.returncode == 0),
                         'bound final comparison verdict')
        pressure.require(verdict.get('coreComparabilityVerified') is True and
                         verdict.get('comparabilityPending') is False, 'final comparator must consume the joined proof')
        result.update(status=verdict['status'], performanceAcceptance=verdict['passed'],
                      comparabilityReceipt=ref(receipt_file), comparisonCommand=command,
                      comparisonExit=completed.returncode,
                      comparison=ref(Path(str(prefix)+'-status.json')))
        if loading is not None:
            result.update(queryPerformanceAcceptance=verdict['passed'],nativeLoadingPerformanceAcceptance=loading['passed'],
                          nativeLoadingComparison=ref(Path(str(loading_prefix)+'-status.json')),
                          performanceAcceptance=verdict['passed'] and loading['passed'],
                          status='PASS' if verdict['passed'] and loading['passed'] else 'FAIL')
    except BaseException as error:
        result['status'] = 'FAIL'; result['errors'].append(repr(error))
        unavailable_loading(loading_prefix,result['errors'])
        # Preserve the comparator's raw output if a subsequent identity check
        # fails; only the final status below is the acceptance result.
        for suffix in ('status.json', 'report.md'):
            path = Path(str(prefix)+'-'+suffix)
            if path.exists():
                path.rename(Path(str(prefix)+'-comparison-original-'+suffix))
        pressure.save(Path(str(prefix)+'-status.json'), {
            'schema': 'graphite.multigraph-pressure.comparison.v1', 'scope': 'multi-graph-pressure',
            'engine': engine, 'operation': 'query', 'passed': False, 'status': 'FAIL',
            'errors': result['errors'], 'queryEvidenceComplete': False, 'otherOperationsEligible': False})
        Path(str(prefix)+'-report.md').write_text(engine+' core/measurement join failed\n'+repr(error)+'\n')
    finally:
        pressure.save(Path(str(prefix)+'-finalization.json'), result)
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--preparation',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True);p.add_argument('--prefix',type=Path,required=True)
    p.add_argument('--engine',choices=('native','jvm'),default='native')
    p.add_argument('--finalize-producers',type=Path);p.add_argument('--loading-prefix',type=Path);args=p.parse_args()
    if args.finalize_producers:
        result=finalize(args.preparation,args.output,args.prefix,args.finalize_producers,args.loading_prefix,engine=args.engine)
    else:
        result=execute(args.preparation,args.output,args.prefix,engine=args.engine,loading_prefix=args.loading_prefix)
    print(json.dumps({'status':result['status'],'issuedCells':len(result.get('cells',[])),
                      'unissuedCells':result.get('unissued',[])}))
    sys.exit(0 if result['performanceAcceptance'] or
             (not args.finalize_producers and result['status'] == PENDING and
              result.get('comparabilityPending') is True and not result['errors']) else 1)

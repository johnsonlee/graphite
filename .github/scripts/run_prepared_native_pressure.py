#!/usr/bin/env python3
"""Execute an already verified native plan, sequentially audit all six cells, then compare.

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


def execute(preparation,output,prefix):
    preparation=preparation.resolve();output=output.resolve();prefix=prefix.resolve()
    output.mkdir(parents=True,exist_ok=False)
    record={'schema':'graphite.native-pressure.execution.v1','status':'RUNNING','errors':[],
            'cells':[],'unissued':[],'performanceAcceptance':False,'otherOperationsEligible':False,
            'controls':{str(p):pressure.sha(p) for p in [Path(__file__).resolve(),SCRIPTS/'multigraph_pressure.py',SCRIPTS/'native_legal_response.py',SCRIPTS/'benchmark-multigraph-pressure.mjs']}}
    def write(path,value):
        path.parent.mkdir(parents=True,exist_ok=True)
        temporary=path.with_name(path.name+'.tmp')
        temporary.write_text(json.dumps(value,indent=2,allow_nan=False)+'\n');temporary.replace(path)
    def save():write(output/'execution.json',record)
    def failed_report(status,errors):
        write(Path(str(prefix)+'-status.json'),{'schema':'graphite.multigraph-pressure.comparison.v1',
            'scope':'multi-graph-pressure','engine':'native','operation':'query','passed':False,'status':status,
            'errors':errors,'queryEvidenceComplete':False,'otherOperationsEligible':False})
        Path(str(prefix)+'-report.md').write_text('### Native multi-graph pressure\n\n'+status+'\n\n'+'\n'.join(errors)+'\n')
    save()
    try:
        ready_file=preparation/'preparation-status.json';ready=pressure.read(ready_file)
        record['preparation']={'path':str(ready_file),'sha256':pressure.sha(ready_file)}
        if ready['status']!='PLAN_READY_NOT_MEASURED':
            pressure.require(ready['status'] in ('UNAVAILABLE','FAIL'),'unknown preparation status')
            record['status']=ready['status'];record['errors']=['Matched producer plan is not ready: '+ready['status']]
            failed_report(record['status'],record['errors']);return record
        plan_file=preparation/'plan.json'
        pressure.require(pressure.sha(plan_file)==ready['planSha256'],'prepared plan digest changed')
        plan=pressure.validate_plan(pressure.read(plan_file));pressure.verify_inputs(plan)
        pressure.require(plan['engine']=='native' and plan['operation']=='query','native query operation required')
        pressure.require([c['arm'] for c in plan['cells']]==list('CABBAC'),'fixed six-cell comparison order')
        record['plan']={'path':str(plan_file),'sha256':ready['planSha256']}
        record['unissued']=[c['id'] for c in plan['cells']];save()
        for cell in plan['cells']:
            record['unissued'].remove(cell['id'])
            attempt={'id':cell['id'],'status':'RUNNING'};record['cells'].append(attempt);save()
            directory=output/cell['id']
            try:
                result=pressure.run_cell(str(plan_file),cell['id'],str(directory))
                pressure.require(result['status']=='PASS','pressure cell failed '+cell['id'])
                audit=pressure.audit(str(plan_file),str(directory))
                pressure.require(audit['status']=='PASS','pressure audit failed '+cell['id'])
                pressure.save(directory/'audit.json',audit)
                attempt.update(status='PASS',resultSha256=pressure.sha(directory/'result.json'),auditSha256=pressure.sha(directory/'audit.json'))
            except BaseException as error:
                attempt.update(status='FAIL',error=repr(error));raise
            finally:save()
        node=shutil.which('node');pressure.require(node is not None,'Node executable missing')
        comparator=SCRIPTS/'benchmark-multigraph-pressure.mjs'
        command=[str(Path(node).resolve()),str(comparator),str(plan_file),str(output),str(prefix)]
        completed=subprocess.run(command,capture_output=True,text=True,timeout=60,check=False)
        (output/'comparison.stdout.log').write_text(completed.stdout);(output/'comparison.stderr.log').write_text(completed.stderr)
        record['comparisonCommand']=command;record['comparisonExit']=completed.returncode
        verdict=pressure.read(Path(str(prefix)+'-status.json'))
        pressure.require(completed.returncode in (0,1) and verdict['schema']=='graphite.multigraph-pressure.comparison.v1','comparison failed to produce a verdict')
        pressure.require(verdict.get('planSha256')==ready['planSha256'],'comparison verdict plan binding')
        pressure.require(verdict['passed']==(completed.returncode==0),'comparison exit/verdict mismatch')
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


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--preparation',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True);p.add_argument('--prefix',type=Path,required=True);args=p.parse_args()
    result=execute(args.preparation,args.output,args.prefix)
    print(json.dumps({'status':result['status'],'issuedCells':len(result['cells']),'unissuedCells':result['unissued']}))
    sys.exit(0 if result['performanceAcceptance'] else 1)

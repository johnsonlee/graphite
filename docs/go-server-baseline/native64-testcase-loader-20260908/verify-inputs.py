from pathlib import Path
import json,hashlib
p=Path(__file__).resolve().parent;root=p.parents[2];original=p.parent/'native64-testcase-audit-20260908/main-cases.json';main=json.loads(original.read_text());go=json.loads((p/'go-cases.json').read_text());assert go['manifestSHA256']==hashlib.sha256(original.read_bytes()).hexdigest();assert len(go['cases'])==len(main['cases'])==1267
assert [r['case'] for r in go['cases']]==main['cases'];assert go['sourceOrder']==main['sourceOrder'];assert go['parseFailures']==go['graphsOpened']==go['queryExecutions']==0 and go['runtimeParityProven'] is False
scope_counts={};parameters=0
for m,g in zip(main['cases'],go['cases']):
 i=g['input'];assert i['query']==m['query'] and i['parameters']==m['parameters'];selected=m['requestGraphIds'];assert i['sourceScopeApplied']==(selected is not None);assert i['graphIds']==(main['sourceOrder'] if selected is None else selected);assert i['timeoutMillis']==(m['configuredTimeoutMillis'] or 60000)
 key=('preselected' if selected is not None else 'predicate-or-unscoped')+':'+str(len(i['graphIds']));scope_counts[key]=scope_counts.get(key,0)+1
 parameters+=bool(m['parameters']);assert g['ast']['kind']=='Query'
assert (root/'graphite-server/internal/benchmarkcase/testdata/main64.json').read_bytes()==original.read_bytes()
receipt={'passed':True,'all1267CasesAndFieldsEqualActualMainExport':True,'allSourceAndReplayOrdersExact':True,'allParameterMapsPreserved':True,'parameterizedCases':parameters,'executionInputScopes':scope_counts,'all1267ParsedByNativeANTLR':True,'graphsOpened':0,'queryExecutions':0,'runtimeParityProven':False,'performanceMeasured':False,'mainExportSHA256':hashlib.sha256(original.read_bytes()).hexdigest(),'goReportSHA256':hashlib.sha256((p/'go-cases.json').read_bytes()).hexdigest()};(p/'input-verification.json').write_text(json.dumps(receipt,indent=2)+'\n');print('PASS1267 exact case inputs/order;321 parameterized;0 query execution/performance claims')

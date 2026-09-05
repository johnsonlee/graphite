import calendar
import collections
import csv
import datetime
import decimal
import hashlib
import json
import pathlib
import re
import sys

ROOT=pathlib.Path(__file__).parent
FIELDS=['id','family','shape','selectivity','operator','boundary','projection','targetGraphId','workloadIdentity','limit','outcome','rowCount','responseBytes','digest']
PRIOR=pathlib.Path('/private/tmp/graphite-attempt140._5jztd0a/old34-pairs')

def instant_ns(value):
    match=re.fullmatch(r'(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?(Z|[+-]\d\d:\d\d)',value)
    assert match,value
    dt=datetime.datetime.fromisoformat(match[1]+match[3].replace('Z','+00:00'))
    return int(dt.timestamp())*1_000_000_000+int((match[2] or '').ljust(9,'0'))

def duration_ns(value):
    match=re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?',value)
    assert match,value
    return int((decimal.Decimal(match[1] or 0)*3600+decimal.Decimal(match[2] or 0)*60+decimal.Decimal(match[3] or 0))*1_000_000_000)

def verify(name):
    path=ROOT/(name+'.events.json');events=json.loads(path.read_text())['recording']['events']
    markers=sorted([e['values'] for e in events if e['type']=='graphite.diagnostic.QueryExecution'],key=lambda e:e['ordinal'])
    rows=list(csv.DictReader((ROOT/(name+'.tsv')).open(),delimiter='\t'))
    catalog=json.loads((ROOT/'original-catalog.json').read_text())
    oracle=(PRIOR/'oracle.correctness').read_text().splitlines()
    reference=list(csv.DictReader((PRIOR/'base-global-wide-1.tsv').open(),delimiter='\t'))
    assert len(markers)==len(rows)==len(catalog)==len(oracle)==34
    assert [e['ordinal'] for e in markers]==list(range(1,35))
    assert ['|'.join(row[k] for k in FIELDS) for row in rows]==oracle
    windows=[];difference=[]
    for marker,row,expected,old in zip(markers,rows,catalog,reference):
        assert expected['id']==row['id']
        assert marker['success'] and marker['exceptionClass']=='' and marker['markerFailuresBefore']==0
        assert marker['query']==expected['query'] and json.loads(marker['parametersJson'])==expected['parameters']
        assert marker['parameterEncoding']=='sorted-string-null-json-v1'
        assert marker['javaThreadName']==marker['eventThread']['javaName']=='broad-query-pressure-worker'
        assert marker['javaThreadId']==marker['eventThread']['javaThreadId']
        start=instant_ns(marker['startTime']);duration=duration_ns(marker['duration']);end=start+duration
        assert duration>0 and duration<=int(row['latencyNanos']),row['id']
        if windows:assert windows[-1]['endEpochNanos']<=start
        windows.append({'id':row['id'],'ordinal':marker['ordinal'],'startEpochNanos':start,'endEpochNanos':end,'markerDurationNanos':duration,'tsvLatencyNanos':int(row['latencyNanos']),'javaThreadId':marker['javaThreadId'],'query':marker['query'],'parameters':expected['parameters']})
        difference.extend({'id':row['id'],'field':k,'reference':old[k],'actual':row[k]} for k in row if k!='latencyNanos' and row[k]!=old[k])
    assert len({w['javaThreadId'] for w in windows})==1
    loss=[e for e in events if e['type']=='jdk.DataLoss' and (e['values'].get('amount',0)>0 or e['values'].get('total',0)>0)]
    assert not loss
    result={'passed':True,'name':name,'markers':len(markers),'oracleSignatures':len(rows),'queryTextAndParametersBoundToOriginalCatalog':True,'windows':windows,'eventTypeCounts':dict(collections.Counter(e['type'] for e in events)),'recordedDataLossEvents':len(loss),'nonLatencyDifferencesVersusPriorBase':difference,'eventsJsonSha256':hashlib.sha256(path.read_bytes()).hexdigest(),'limits':'Marker excludes queueing, result serialization, metric collection, marker preparation and commit; duration is not end-to-end latency. No performance acceptance or phase attribution.'}
    (ROOT/(name+'-verification.json')).write_text(json.dumps(result,indent=2)+'\n')
    return result

if __name__=='__main__':
    result=verify(sys.argv[1]);print(json.dumps({k:result[k] for k in ['name','passed','markers','oracleSignatures','eventTypeCounts','nonLatencyDifferencesVersusPriorBase']}))

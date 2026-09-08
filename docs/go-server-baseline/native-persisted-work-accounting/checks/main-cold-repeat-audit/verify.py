from pathlib import Path
import gzip,hashlib,json,tarfile
P=Path(__file__).resolve().parent
sha=lambda b:hashlib.sha256(b).hexdigest()
a=json.loads((P/'archive-verification.json').read_text())
for r in a['copied']:
 b=(P/r['file']).read_bytes();assert(len(b),sha(b))==(r['archiveBytes'],r['archiveSHA256'])
 raw=gzip.decompress(b) if r['gzipEncoded'] else b
 assert(len(raw),sha(raw))==(r['rawBytes'],r['rawSHA256'])
 ext=Path(r['source']);assert ext.stat().st_size==r['rawBytes'] and sha(ext.read_bytes())==r['rawSHA256']
ids=json.loads((P/'evidence/input-identities.json').read_text())
with tarfile.open(P/'evidence/input-sources.tar.gz')as t:
 for m in t.getmembers():assert sha(t.extractfile(m).read())==ids['/'+m.name]
c=json.loads((P/'evidence/comparison.json').read_text());assert c['originalMainComparison']['allParsedRecordsExactlyEqual']
assert c['originalMainComparison']['cases']==1267 and c['originalMainComparison']['graphStateObservations']==162304
for r in c['newMainToNativeComparisons']:
 assert r['publicDifferences']==[] and r['stateDifferenceCounts']=={'mappedRangeCount':15162}
assert c['nativeRepeatsAllParsedRecordsExactlyEqual'] and c['mainRuntimeExitCode']==1
for r in a['nativeExternalResponses']:
 b=(Path(r['directory'])/'native-cold/responses.jsonl').read_bytes();assert (len(b),sha(b))==(r['responsesBytes'],r['responsesSHA256'])
print(json.dumps({'verifiedArtifacts':len(a['copied']),'mainRepeatExactlyEqual':True,'nativeStateDifferencesEach':15162,'performanceMeasurement':False}))

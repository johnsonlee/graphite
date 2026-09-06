"""Run with prometheus-client==0.22.1; no server or performance traffic."""
from prometheus_client.parser import text_string_to_metric_families
from pathlib import Path
import json
p=Path(__file__).parent

def samples(name):
    families=list(text_string_to_metric_families((p/name).read_text()))
    app={str((s.name,sorted(s.labels.items()))):s.value for f in families for s in f.samples if s.name.startswith('graphite_cypher_')}
    return families,app

mf,main=samples('main-exposition.txt')
nf,native=samples('native-exposition.txt')
r={'parser':'prometheus-client 0.22.1 text_string_to_metric_families','mainRevision':'4e328b0109e13c896b74004823fb049fcb19251a','mainMetricFamilies':len(mf),'nativeMetricFamilies':len(nf),'mainApplicationSamples':len(main),'nativeApplicationSamples':len(native),'missingNativeApplicationSamples':sorted(main.keys()-native.keys()),'extraNativeApplicationSamples':sorted(native.keys()-main.keys()),'fixedValueMismatches':[{'sample':k,'main':main[k],'native':native[k]} for k in sorted(main.keys()&native.keys()) if not ('_sum' in k or '_max' in k) and main[k]!=native[k]],'note':'One direct recorder event per outcome and two rejections; duration sum/max values intentionally dynamic. No HTTP servers or performance run.'}
(p/'verification.json').write_text(json.dumps(r,indent=2)+'\n')
print(json.dumps(r,indent=2))
assert not r['missingNativeApplicationSamples']
assert not r['extraNativeApplicationSamples']
assert not r['fixedValueMismatches']

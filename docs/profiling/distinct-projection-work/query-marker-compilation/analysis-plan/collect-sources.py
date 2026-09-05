from pathlib import Path
import urllib.request, hashlib, json, zipfile, re, shutil
out=Path(__file__).parent; sd=out/'sources'; manifest=[]
def save(name,data,origin):
 p=sd/name;p.write_bytes(data);manifest.append({'file':str(p.relative_to(out)),'source':origin,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
jdk=Path('/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home')
for name in ['profile.jfc','default.jfc']:
 p=jdk/'lib/jfr'/name;save('local-'+name,p.read_bytes(),str(p))
with zipfile.ZipFile(jdk/'lib/src.zip') as z:
 for name in ['jdk.jfr/jdk/jfr/Event.java','jdk.jfr/jdk/jfr/internal/consumer/TimeConverter.java','jdk.jfr/jdk/jfr/consumer/RecordedFrame.java']:
  save(Path(name).name,z.read(name),str(jdk/'lib/src.zip')+'!/'+name)
paths=['jfr/metadata/metadata.xml','utilities/xmlstream.cpp','utilities/ostream.cpp','runtime/deoptimization.cpp','jfr/periodic/sampling/jfrThreadSampler.cpp']
patterns={
'metadata.xml':r'name="(?:Compilation|Deoptimization|ExecutionSample|NativeMethodSample|JVMInformation)"',
'xmlstream.cpp':r'void xmlStream::stamp',
'ostream.cpp':r'void outputStream::stamp|time_ms=|time_ms',
'deoptimization.cpp':r'post_deoptimization_event|uncommon_trap thread',
'jfrThreadSampler.cpp':r'thread_state_in_java|MAX_NR_OF_JAVA_SAMPLES|EventExecutionSample|set_sampledThread|_thread_in_Java'}
for path in paths:
 url='https://raw.githubusercontent.com/openjdk/jdk17u/jdk-17.0.18-ga/src/hotspot/share/'+path
 data=urllib.request.urlopen(url,timeout=30).read();name=Path(path).name;lines=data.decode().splitlines();inds=set()
 for i,line in enumerate(lines):
  if re.search(patterns[name],line): inds.update(range(max(0,i-4),min(len(lines),i+28)))
 excerpt='Source: '+url+'\nFull response SHA256: '+hashlib.sha256(data).hexdigest()+'\n\n'
 prev=-2
 for i in sorted(inds):
  if i!=prev+1:excerpt+='[...]\n'
  excerpt+=f'{i+1}: {lines[i]}\n';prev=i
 save(name+'.excerpts.txt',excerpt.encode(),url)
for name,p in {
 'QueryExecutionMarker.java':'/private/tmp/graphite-query-marker-diagnostic/src/QueryExecutionMarker.java',
 'root-overlay-audit.json':'/private/tmp/graphite-query-marker-capture/root-overlay-audit.json',
 'original-catalog.json':'/private/tmp/graphite-query-marker-capture/original-catalog.json',
 'catalog-receipt.json':'/private/tmp/graphite-query-marker-capture/catalog-receipt.json',
 'capture-plan.json':'/private/tmp/graphite-query-marker-capture/capture-plan.json',
 'capture-profile.jfc':'/private/tmp/graphite-query-marker-capture/profile.jfc',
 'capture-control.jfc':'/private/tmp/graphite-query-marker-capture/control.jfc',
 'jdk-event-metadata.txt':'/private/tmp/graphite-query-marker-capture/jdk-event-metadata.txt',
 'metadata-command.json':'/private/tmp/graphite-query-marker-capture/metadata-command.json',
}.items(): save(name,Path(p).read_bytes(),p)
(out/'sources-receipt.json').write_text(json.dumps({'purpose':'read-only source capture; no Java, build, profiler or measurement executed by this audit','files':manifest},indent=2)+'\n')
print(json.dumps({'files':len(manifest),'bytes':sum(x['bytes'] for x in manifest)}))

from pathlib import Path
import json,struct,shutil,subprocess
p=Path(__file__).resolve().parent;src=p/'module';fixture=p/'fixture';data=bytearray((fixture/'graph.nodedata').read_bytes());assert data[76]==7 and struct.unpack_from('>i',data,72)[0]==14;data[76]=127;struct.pack_into('>i',data,165,2147483647);(fixture/'graph.nodedata').write_bytes(data)
(p/'mutation.json').write_text(json.dumps({'source':str(src/'internal/query/testdata/main-string-source/all-types'),'changes':[{'node':14,'offset':76,'tag':127},{'node':18,'offset':165,'SID':2147483647}]},indent=2)+'\n')
(p/'setup-note.txt').write_text('Initial optional graph.nodeoffsets read failed before mutation/JVM: actual Java fixture omits derived offsets. A following assertion assuming tag-first record headers failed before mutation/JVM. Actual NodeSerializer has ID int32 then tag byte: enum ID14 at72, tag7 at76, enum_type SID at77; Field type SID at165. Hex and assertions verified before mutation. Unchanged PlannerOracle.ensureNodeIndex generates derived files only in this disposable copy.\n')
queries=[('later-filter-type-head-first',"MATCH (n) WHERE n.name = 'field' OR n.name = 'RED' RETURN DISTINCT n.id AS x LIMIT 1"),('reverse-filter-same-type-head',"MATCH (n) WHERE n.name = 'RED' OR n.name = 'field' RETURN DISTINCT n.id AS x LIMIT 1"),('field-error-without-enum-stream',"MATCH (n:FieldNode) WHERE n.name = 'field' RETURN DISTINCT n.id AS x LIMIT 1"),('bad-enum-unmatched-not-decoded',"MATCH (n:EnumConstant) WHERE n.name = 'missing' RETURN DISTINCT n.id AS x LIMIT 1")]
(p/'cases.json').write_text(json.dumps([{'name':n,'query':q} for n,q in queries],indent=2)+'\n')
shutil.copy2('/tmp/graphite-ordinary-remaining82-audit/PlannerOracle.java',p/'PlannerOracle.java');(p/'classes').mkdir()
java=Path('/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home/bin');jar='/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar';commands=[]
for command,log in [([str(java/'javac'),'-cp',jar,'-d',str(p/'classes'),str(p/'PlannerOracle.java')],p/'javac.log'),([str(java/'java'),'-cp',str(p/'classes')+':'+jar,'PlannerOracle',str(fixture),str(p/'cases.json'),str(p/'main.json')],p/'main.log')]:
 with log.open('w') as out:r=subprocess.run(command,stdout=out,stderr=out)
 commands.append({'command':command,'exitCode':r.returncode,'log':str(log.relative_to(p))});assert r.returncode==0
(p/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
print(json.dumps(json.loads((p/'main.json').read_text()),indent=2))

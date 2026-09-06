import json,re,pathlib
out=pathlib.Path(__file__).parent
def rows(name):
 outputs=[]
 for line in (out/name).read_text().splitlines():
  try:r=json.loads(line)
  except ValueError:continue
  outputs.append(r.get('Output',''))
 return [json.loads(line.split('RECORD ',1)[1]) for line in ''.join(outputs).splitlines() if 'RECORD ' in line]
def normalize(value):
 if isinstance(value,str):return re.sub(r'(io\.johnsonlee\.graphite\.webgraph\.MappedWebGraphBackedGraph)@[0-9a-f]+',r'\1@IDENTITY',value)
 if isinstance(value,dict):return {k:normalize(v) for k,v in value.items()}
 if isinstance(value,list):return [normalize(v) for v in value]
 return value
boxed=rows('boxed-control-queries.jsonl')
for name,file in [('initial','initial-candidate-queries.jsonl'),('final','final-candidate-tests.jsonl')]:
 candidate=rows(file);diff=[{'boxed':a,'candidate':b} for a,b in zip(boxed,candidate) if normalize(a)!=normalize(b)]
 result={'boxed':len(boxed),'candidate':len(candidate),'normalization':'Only MappedWebGraphBackedGraph@hex instance identity text, whose source copy paths differ; member order, graph IDs and all other text retained','different':len(diff),'cases':diff}
 (out/(name+'-normalized-differences.json')).write_text(json.dumps(result,indent=2)+'\n');print(name,len(boxed),len(candidate),len(diff))
 for r in diff:print(r['boxed']['cross'],r['boxed']['query'])

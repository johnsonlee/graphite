"""Apply main's final UTF-8 encoder to recorded Java UTF-16 strings; keep raw originals."""
import json,pathlib
here=pathlib.Path(__file__).resolve().parent
def wire(value):
 if isinstance(value,str):return value.encode('utf-16-le',errors='surrogatepass').decode('utf-16-le',errors='surrogatepass').encode('utf-8',errors='replace').decode('utf-8')
 if isinstance(value,list):return [wire(x)for x in value]
 if isinstance(value,dict):return {wire(k):wire(v)for k,v in value.items()}
 return value
for fixture in ['callsites','bad-first','bad-last']:
 original=here/(fixture+'-main.jsonl');out=here/(fixture+'-main-wire.jsonl')
 out.write_text(''.join(json.dumps(wire(json.loads(line)),ensure_ascii=True)+'\n'for line in original.read_text().splitlines()))

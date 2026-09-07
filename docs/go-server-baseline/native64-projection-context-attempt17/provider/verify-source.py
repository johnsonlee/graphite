import pathlib,hashlib,json,re
r=pathlib.Path(__file__).resolve().parent
results=[]
for name,count in [('distinct_projection.go',3),('projection_node.go',2)]:
 p=r/'source/internal/store'/name;c=p.read_text();old=(r/'original-production'/name).read_text()
 pattern=r'(?m)^(\s*)select \{\n\1case <-ctx.Done\(\):\n\1\tif (err := ctx.Err\(\)|failure = ctx.Err\(\)); (err|failure) != nil \{\n\1\t\t(return [^\n]+)\n\1\t\}\n\1default:\n\1\}'
 def replacement(m):
  indent,assignment,error,ret=m.groups();return f'{indent}if {assignment}; {error} != nil {{\n{indent}\t{ret}\n{indent}}}'
 restored,n=re.subn(pattern,replacement,c)
 assert n==count,(name,n)
 assert restored==old,name
 results.append({'file':str(p.relative_to(r)),'baseSHA256':hashlib.sha256(old.encode()).hexdigest(),'candidateSHA256':hashlib.sha256(c.encode()).hexdigest(),'mechanicalCheckSubstitutions':n,'allOtherBytesEqual':True})
(r/'production-equivalence-proof.json').write_text(json.dumps(results,indent=2)+'\n')
print(json.dumps(results,indent=2))

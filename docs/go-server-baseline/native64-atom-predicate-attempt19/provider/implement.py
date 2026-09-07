from pathlib import Path
import hashlib,json,subprocess

root=Path('/tmp/graphite-go-atom-attempt19-39eedb33');module=root/'graphite-server';evidence=Path('/tmp/graphite-atom-attempt19-evidence')
assert json.loads((evidence/'base-command.json').read_text())['exitCode']==0
for name,want in json.loads((evidence/'base-final-with-tests-source.json').read_text()).items():
    assert hashlib.sha256((module/name).read_bytes()).hexdigest()==want,name
ev=module/'internal/query/eval.go';old=ev.read_text();start=old.index('\t\top := strings.TrimPrefix(x.Op, "NOT ")');end=old.index('\n\tcase "<":',start)
body=old[start:end];assert body.endswith('\t\treturn result')
helper='\n'.join(line[1:] for line in body.splitlines()).replace('x.Op','operator')
new='''package query

import (
    "strings"
    "unicode/utf8"
)

// stringPredicate receives evaluated string operands. Both callers retain the
// original operand checks before entering this unchanged matching algorithm.
func (e evaluator) stringPredicate(l, r, operator string) bool {
'''+helper+'\n}\n'
(module/'internal/query/string_predicate.go').write_text(new)
changed=old[:start]+'\t\treturn e.stringPredicate(l, r, x.Op)'+old[end:]
assert changed.count('utf8.')==0;changed=changed.replace('\n\t"unicode/utf8"','');ev.write_text(changed)
idx=module/'internal/query/indexed_distinct.go';oldidx=idx.read_text();needle='\treturn e.binary(cypher.Binary{Left: cypher.Literal{Value: s}, Op: atom.op, Right: cypher.Literal{Value: atom.term}}, nil) == true'
assert oldidx.count(needle)==1
idx.write_text(oldidx.replace(needle,'''	switch atom.op {
	case "CONTAINS", "STARTS WITH", "ENDS WITH":
		// Preserve the two original literal operand checkpoints without
		// constructing temporary expression interfaces for these strings.
		e.check()
		e.check()
		return e.stringPredicate(s, atom.term, atom.op)
	default:
'''+needle+'\n\t}'))
names=['internal/query/eval.go','internal/query/indexed_distinct.go','internal/query/string_predicate.go']
subprocess.run(['gofmt','-w',*[str(module/n) for n in names]],check=True)
after=(module/'internal/query/string_predicate.go').read_text();actual=after[after.index('\n\top :=')+1:after.rindex('\n}')]
assert actual.replace('operator','x.Op')=='\n'.join(line[1:] for line in body.splitlines())
(evidence/'extraction-proof.json').write_text(json.dumps({'originalMatcherMovedByteExactlyExceptIndentAndParameterName':True,'nonstringAndLowerBeforeSwitchUnchanged':True,'specializedAtoms':['CONTAINS','STARTS WITH','ENDS WITH'],'otherAtomOperatorsKeepOriginalBinaryFallback':True,'originalTwoLiteralCheckpointsExplicitlyRetained':True},indent=2)+'\n')
subprocess.run(['git','add','-N','graphite-server/internal/query/string_predicate.go','graphite-server/internal/query/distinct_atom_predicate_test.go'],cwd=root,check=True)
(evidence/'attempt19.patch').write_bytes(subprocess.check_output(['git','diff','--binary','--','graphite-server'],cwd=root))
files={p.relative_to(module).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in module.rglob('*') if p.is_file()}
(evidence/'candidate-source.json').write_text(json.dumps(files,indent=2)+'\n')
print('Applied one atom-boxing hypothesis to isolated worktree;',len(files),'module inputs.')

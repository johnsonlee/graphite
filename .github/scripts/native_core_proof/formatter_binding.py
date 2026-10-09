"""Fresh writer source rule with exact reviewed formatter/call-site boundaries.

The returned existing rule enables only retained comparator policy. Production
formatter tests and the complete graph proof are separate mandatory authorities.
"""
import json
from pathlib import Path
import re
from .source_bindings import sha, load_rule
from .legacy_wire import need

CONTRACT=Path(__file__).with_name('formatter_source_contract.json')
FOLDING='frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/ConstantFolding.kt'
ADAPTER='frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/SootUpAdapter.kt'


def source_rule(sources, fixtures, control_pins):
    """Bind explicit actual manifests already covered by independent artifact audits.

    The outer runner must replay those artifact audits first. This function
    independently checks the source/fixture chain and exact relevant source bytes;
    it never labels a producer, compiled writer or whole graph independently valid.
    """
    need(set(sources)==set(fixtures)=={'C','B'},'exact source comparison pair')
    need(control_pins.get(str(CONTRACT))==sha(CONTRACT),'reviewed formatter contract pin')
    need(control_pins.get(str(Path(__file__)))==sha(__file__),'reviewed formatter binding pin')
    contract=json.loads(CONTRACT.read_text())
    need(contract['schema']=='graphite.array-formatter-source-contract.v1','formatter contract schema')
    pins={str(CONTRACT):sha(CONTRACT),str(Path(__file__)):sha(__file__)}
    arms={};dependency=[];boundary_digests={}
    for arm in ('C','B'):
        source_ref,fixture_ref=sources[arm],fixtures[arm]
        for ref in (source_ref,fixture_ref):
            path=Path(ref['path']);need(path.is_absolute() and path.is_file() and not path.is_symlink() and
                                      sha(path)==ref['sha256'],'actual source/fixture manifest pin')
            pins[str(path)]=ref['sha256']
        source=json.loads(Path(source_ref['path']).read_text());fixture=json.loads(Path(fixture_ref['path']).read_text())
        need(re.fullmatch('[0-9a-f]{40}',source['revision']) and
             fixture['writerRevision']==source['revision'] and
             fixture['sourceManifestSha256']==source_ref['sha256'],'actual writer source linkage')
        root=Path(source['root']);need(root.is_absolute(),'absolute actual source root')
        def content(relative):
            path=root/relative
            need(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(root.resolve()),
                 'actual source member path')
            digest=sha(path)
            need(source['files'].get(str(path),source['files'].get(relative))==digest,'actual manifested source member')
            pins[str(path)]=digest
            return path.read_text()
        adapter=content(ADAPTER);folding=content(FOLDING)
        expected=contract['formatters'][arm]
        need(folding.count(expected)==1 and len(re.findall(r'(?m)^internal fun graphTypeName\(',folding))==1,
             'exact reviewed formatter source')
        boundaries={}
        for name,fragments in contract['boundaries'].items():
            need(len(re.findall(r'(?m)^    private fun '+re.escape(name)+r'\(',adapter))==len(fragments),
                 'complete reviewed adapter overload set: '+name)
            for fragment in fragments:need(adapter.count(fragment)==1,'reviewed adapter body changed: '+name)
            boundaries[name]=len(fragments)
        for cache in contract['cacheDeclarations']:
            need(adapter.count(cache)==1,'reviewed identity cache declaration changed')
        dependency.append({name:content(name) for name in contract['dependencyFiles']})
        arms[arm]={'revision':source['revision'],'manifest':source_ref['path'],'manifestSha256':source_ref['sha256'],
                   'folding':str(root/FOLDING),'foldingSha256':pins[str(root/FOLDING)],
                   'adapter':str(root/ADAPTER),'adapterSha256':pins[str(root/ADAPTER)]}
        boundary_digests[arm]={'exactBodyCounts':boundaries,'formatterKind':'legacy-flat' if arm=='C' else 'rank-preserving'}
    need(dependency[0]==dependency[1],'formatter dependency versions/build definitions changed')
    rule={'schema':'graphite.local-array-source-rule.v1','arms':arms,'diagnosis':{str(CONTRACT):pins[str(CONTRACT)]}}
    report={'schema':'graphite.array-formatter-source-binding.v1','status':'PASS_EXACT_SOURCE_MODEL_BINDING_ONLY',
            'sourceManifests':sources,'fixtureManifests':fixtures,'boundaries':boundary_digests,'pins':pins,
            'completeSemanticEquivalence':False,'syntheticLocalInferenceOracleClaim':False,
            'productionFormatterTestsVerified':False,'performanceAcceptance':False}
    return rule,report


def save(directory,sources,fixtures,control_pins):
    directory=Path(directory).resolve()
    for ref in sources.values():
        root=Path(json.loads(Path(ref['path']).read_text())['root']).resolve()
        need(not directory.is_relative_to(root),'source rule output outside actual writer checkout')
    rule,report=source_rule(sources,fixtures,control_pins)
    directory.mkdir(parents=True,exist_ok=False)
    rule_path=directory/'local-array-source-rule.json'
    rule_path.write_text(json.dumps(rule,indent=2)+'\n');ref={'path':str(rule_path),'sha256':sha(rule_path)}
    # Exercise the existing consumer's manifest linkage, without graph access or
    # pretending the full classfile authority (which needs per-graph provenance).
    _,linked=load_rule(ref,{'schema':'graphite.classfile-field-authority.v1',
                          'arms':{arm:{'fixtureManifest':fixtures[arm]} for arm in ('C','B')}})
    report['rule']=ref;report['pins'].update(linked)
    need(all(sha(p)==h for p,h in report['pins'].items()),'formatter binding final identity drift')
    (directory/'source-binding.json').write_text(json.dumps(report,indent=2)+'\n')
    return report

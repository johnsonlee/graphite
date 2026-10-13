"""One fresh pair's strict complete checks; CLI only inside owned all64 runner."""
import hashlib
import json
from pathlib import Path
import re
from decimal import Decimal, InvalidOperation
import sys
from . import core_semantics as core
from . import declarations
from . import wire_gty05 as wire
from . import raw_local_export
WIRE=Path(__file__).resolve().parent

def props(raw):
    result={}
    for line in raw.decode('ascii').splitlines():
        line=line.strip()
        if not line or line.startswith(('#','!')):continue
        core.need('\\'not in line and '='in line,'unexpected fresh properties escaping/framing')
        key,value=line.split('=',1);key=key.strip();value=value.strip()
        core.need(re.fullmatch(r'[A-Za-z0-9_.-]+',key)and key not in result,'properties key/duplicate')
        result[key]=value
    return result

ENCODING_INTEGER_KEYS={'bitsforblocks','bitsforintervals','bitsforreferences','bitsforresiduals','copiedarcs','residualarcs'}
# BVGraph 3.6.12 storeInternal derives both successor/residual gap summaries
# from exponential histograms (BVGraph.java:2592-2633). They are statistics,
# not decoder parameters; accepting them still requires complete topology.
ENCODING_DECIMAL_KEYS={'residualavggap','residualavgloggap','successoravggap','successoravgloggap'}
ENCODING_BIN_KEYS={'residualexpstats','successorexpstats'}
ENCODING_KEYS=ENCODING_INTEGER_KEYS|ENCODING_DECIMAL_KEYS|ENCODING_BIN_KEYS

def statistic(key,value):
    core.need(isinstance(value,str),'missing encoding statistic '+key)
    if key in ENCODING_INTEGER_KEYS:
        core.need(re.fullmatch(r'[0-9]+',value),'invalid integer statistic '+key)
    elif key in ENCODING_BIN_KEYS:
        core.need(bool(re.fullmatch(r'[0-9]+(?:,[0-9]+)*',value)),'invalid gap-bin statistic')
    else:
        try: number=Decimal(value)
        except InvalidOperation: raise ValueError('invalid decimal statistic '+key)
        core.need(number.is_finite() and number>=0,'nonfinite/negative statistic '+key)

def properties(actual,reference,type_digest):
    """type_digest is the actual table's SHA-256, or None for an actual graph without a table,
    which may then add no declaration authority of its own."""
    a,b=props(actual),props(reference);key='graphite.declaredTypes.sha256'
    core.need(key not in b,'accepted prefeature unexpected declaration authority')
    core.need(a.pop(key,None)==type_digest,'B actual types authority')
    core.need(set(a)==set(b),'forward.properties key set differs')
    for name in ENCODING_KEYS:
        if name in a:
            statistic(name,a[name]);statistic(name,b[name])
    differences={k:{'actual':a[k],'reference':b[k]}for k in sorted(a)if a[k]!=b[k]}
    semantic={k:v for k,v in differences.items()if k not in ENCODING_KEYS}
    return {'status':'FAIL'if semantic else'PASS_REQUIRES_COMPLETE_TOPOLOGY','differingProperties':differences,'semanticDifferences':semantic,'allowedAddition':key if type_digest is not None else None,
            'unchangedProperties':len(a)-len(differences),'compressionExceptions':sorted(set(differences)&ENCODING_KEYS),
            'independentStatisticsRecomputeClaim':False,'requiresCompleteTopology':True}

def execute(a,b,ae,be,out,export_row,authority,source_rule,raw_local_exports=None):
    out.mkdir(exist_ok=False)
    # The actual graph either binds a declaration table (the candidate) or has none at all (a
    # rebased PR's parent arm, whose writer predates the feature); the outer runner holds the
    # presence to the writer's renderer. Either way nothing but the table may be added.
    shape=declarations.table_shape(a)
    core.need(not(b/'graph.types').exists(),'accepted C unexpectedly contains graph.types')
    declaration={'status':'PASS_ADDITIVE_DECLARATION_WIRE_VALIDITY','declarationTable':shape,'sourceToDeclarationCompletenessClaim':False,'queryExpectedAuthority':False,
        'appendedOriginClaim':False,'readerSha256':core.sha(WIRE/'wire_gty05.py'),'legacyReaderSha256':core.sha(WIRE/'legacy_schema_wire.py')}
    if shape==declarations.PRESENT:
        table=declarations.load(a,export_row)
        types=(a/'graph.types').read_bytes()
        usage=table.usage() if isinstance(table,wire.StructuralTypes) else None
        declaration.update(types=len(table.rows),fields=len(table.fields),methods=len(table.methods),classes=len(table.classes),wireVersion=types[3])
        if usage is not None:
            declaration.update({name:len(usage[key]) for name,key in (('projectionTypes','projectionTypeIds'),('erasedKeyTypes','erasedKeyTypeIds'),('keyOnlyTypes','keyOnlyTypeIds'),('unusedTypes','unusedTypeIds'))})
        type_digest=hashlib.sha256(types).hexdigest()
    else:
        declarations.export_context(a,export_row)
        declaration.update(types=0,fields=0,methods=0,classes=0,wireVersion=None)
        type_digest=None
    (out/'declarations.json').write_text(json.dumps(declaration,indent=2)+'\n')
    property_check=properties((a/'forward.properties').read_bytes(),(b/'forward.properties').read_bytes(),type_digest)
    (out/'properties.json').write_text(json.dumps(property_check,indent=2)+'\n')
    core.need(property_check['status']=='PASS_REQUIRES_COMPLETE_TOPOLOGY','forward.properties differ; exact all differences retained')
    result=core.prove(a,b,ae,be,out/'core',True,field_authority_spec=authority,method_array_corrections=True,source_local_arrays=True,parameter_arrays=True,legacy_overload_collisions=True,synthetic_method_keys=True,inherited_fields=True,source_rule=source_rule,**({"raw_local_exports":raw_local_exports} if raw_local_exports is not None else {}))
    (out/'record.json').write_text(json.dumps({'status':'PASS_CORE_WITH_TYPE_OVERLOAD_SYNTHETIC_AND_INHERITED_FIELD_CORRECTIONS_REQUIRES_TOPOLOGY','core':result,'declarations':declaration,'properties':property_check,'strictEquivalence':False,'fieldCorrectionCount':result['fieldCorrectionCount'],'methodCorrectionCount':result['methodCorrectionCount'],'localArrayCorrectionCount':result['localArrayCorrectionCount'],'parameterArrayCorrectionCount':result['parameterArrayCorrectionCount'],'legacyOrdinalGroupCount':result['legacyOrdinalGroupCount'],'legacyMetadataGroupCount':result['legacyMetadataGroupCount'],'legacyReturnOmittedGroupCount':result['legacyReturnOmittedGroupCount'],'recoveredMetadataMethodCount':result['recoveredMetadataMethodCount'],'legacyOrdinalBindingCount':result['legacyOrdinalBindingCount'],'syntheticMethodKeyCorrectionCount':result['syntheticMethodKeyCorrectionCount'],'syntheticConstructorCollisionGroupCount':result['syntheticConstructorCollisionGroupCount'],'recoveredSyntheticIdentityCount':result['recoveredSyntheticIdentityCount'],'inheritedFieldCorrectionCount':result['inheritedFieldCorrectionCount']},indent=2)+'\n')

def main():
    core.need(__debug__, 'no optimized Python')
    core.need(len(sys.argv)==4, 'pinned plan path, digest and graph ID required')
    path=Path(sys.argv[1]);core.need(core.sha(path)==sys.argv[2], 'owned immutable plan pin')
    plan=json.loads(path.read_text())
    rows=[r for r in plan['graphs'] if r['id']==sys.argv[3]]
    core.need(len(rows)==1, 'one exact plan graph identity')
    row=rows[0]
    execute(Path(row['B']),Path(row['C']),Path(row['BExport']['stringsExport']['path']).parent,
            Path(row['CExport']['stringsExport']['path']).parent,Path(plan['output'])/'graphs'/row['id'],
            row['BExport'],row['fieldAuthority'],plan['sourceRule'],raw_local_export._binding_identity(plan,row) if 'rawLocalExports' in plan else None)

if __name__=='__main__':main()

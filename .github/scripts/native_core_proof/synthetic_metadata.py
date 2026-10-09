"""Narrow GRS01 method-key correction; fingerprints and order stay exact."""
import hashlib
import json
from . import synthetic_collisions
from collections import defaultdict
from .legacy_wire import need


def eligibility(authority,key,proof,allow_return_collision):
    need(proof is not None,'synthetic key requires direct source declaration')
    if proof['method']['accessFlags']&0x1000:
        return {'mode':'EXACT_ACC_SYNTHETIC_DECLARATION','declaration':proof}
    need(allow_return_collision,'synthetic key requires exact ACC_SYNTHETIC source declaration')
    # GRS keys omit return types. A covariant bridge and its implementation have
    # the same key, even when the metadata table retained the implementation.
    klass=authority.klass(key[0]);parameter_descriptor=key[2].split(')',1)[0]+')'
    matches=[m for m in klass['parsed']['methods'] if m['name']==key[1] and
             m['descriptor'].split(')',1)[0]+')'==parameter_descriptor and
             m['descriptor']!=key[2] and m['accessFlags']&0x1000 and m['accessFlags']&0x40]
    need(len(matches)==1,'synthetic key requires one exact return-colliding synthetic bridge')
    return {'mode':'RETURN_OMITTED_KEY_WITH_EXACT_SYNTHETIC_BRIDGE','class':klass['evidence'],
            'bridge':matches[0],'metadataDeclaration':proof,'returnTypeOmittedByKey':True}


def compare(actual,reference,a_spans,b_spans,a_methods,b_methods,authority,occurrences,allow_return_collision=False,collision_groups=None):
    if len(a_spans)!=len(b_spans) and collision_groups is not None:
        actual,a_spans=synthetic_collisions.collapse(actual,reference,a_spans,b_spans,a_methods,b_methods,authority,collision_groups)
    need(len(a_spans)==len(b_spans),'synthetic identity row count')
    lookups=[]
    for methods in (a_methods,b_methods):
        lookup=defaultdict(list)
        for row in methods:
            if row.get('tableRow'):lookup[row['method'].signature].append(row['method'])
        lookups.append(lookup)
    replacements=[]
    for i,(a,b) in enumerate(zip(a_spans,b_spans)):
        need(a['fingerprint']==b['fingerprint'],'synthetic identity fingerprint changed')
        if a['key']==b['key']:continue
        am,bm=lookups[0].get(a['key'],[]),lookups[1].get(b['key'],[])
        need(len(am)==len(bm)==1,'synthetic key needs unique full metadata method')
        am,bm=am[0],bm[0]
        need(authority.pair(am.key,bm.key,'definition','metadata:synthetic:'+str(i)),
             'synthetic key change requires explicit method array correction')
        proof=authority.direct(am.key)
        source_eligibility=eligibility(authority,am.key,proof,allow_return_collision)
        replacements.append((a['start'],a['end'],reference[b['start']:b['end']]))
        occurrences.append({'index':i,'B':a['key'],'C':b['key'],'fingerprint':a['fingerprint'].hex(),
                            'fullBMethod':am.key,'fullCMethod':bm.key,'declaration':proof,
                            'syntheticEligibility':source_eligibility})
    # Synthetic keys follow every parsed method span. Only their UTF8 length and
    # text change, so no earlier method offsets need adjustment.
    for start,end,value in reversed(replacements):actual=actual[:start]+value+actual[end:]
    return actual


class Corrections:
    def __init__(self,out,source_rule,producer_spec):
        self.out=out;self.occurrences=[];self.complete=False;self.collision_groups=[]
        self.source=synthetic_collisions.SourceRules(source_rule,producer_spec)
    def save(self):
        self.source.verify()
        result={'status':'PASS_EXPLICIT_SYNTHETIC_METHOD_KEY_CORRECTIONS' if self.complete else
                        'PARTIAL_SYNTHETIC_METHOD_KEY_CORRECTIONS_NOT_CORE_PASS',
                'strictEquivalence':False,'fingerprintsPreserved':not self.collision_groups,'rowOrderPreserved':not self.collision_groups,
                'survivingFingerprintsPreserved':True,'newFingerprintRecomputed':False,
                'constructorCollisionGroups':self.collision_groups,'constructorCollisionGroupCount':len(self.collision_groups),
                'recoveredIdentityCount':sum(g['recoveredIdentities'] for g in self.collision_groups),
                'inputs':self.source.pins,
                'exceptionCount':len(self.occurrences),'occurrences':self.occurrences,
                'methodCorrectionProofSha256':hashlib.sha256((self.out/'method-corrections.json').read_bytes()).hexdigest()}
        (self.out/'synthetic-method-key-corrections.json').write_text(json.dumps(result,indent=2)+'\n')
        return result

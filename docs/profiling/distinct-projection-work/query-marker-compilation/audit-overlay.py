import hashlib
import json
import pathlib
import struct
import zipfile

ROOT = pathlib.Path(__file__).parent
BASE = pathlib.Path('/private/tmp/graphite-next-baseline.T2FTs9/graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar')
OVERLAY = pathlib.Path('/private/tmp/graphite-query-marker-diagnostic/diagnostic-jmh.jar')
ENTRY = 'io/johnsonlee/graphite/webgraph/LargeBroadQueryPressureBenchmark.class'
METHOD = 'replay$lambda$33$lambda$29'

def digest(data): return hashlib.sha256(data).hexdigest()
def u2(b, p): return struct.unpack_from('>H', b, p)[0]
def u4(b, p): return struct.unpack_from('>I', b, p)[0]

def parse(b):
    assert b[:4] == bytes.fromhex('cafebabe')
    count = u2(b, 8); cp = {}; pos = 10; index = 1
    while index < count:
        tag = b[pos]; pos += 1
        if tag == 1:
            n = u2(b, pos); pos += 2
            # Names/descriptors needed below contain only ASCII; preserve other UTF8 bytes.
            cp[index] = (tag, b[pos:pos+n]); pos += n
        elif tag in (3,4): cp[index] = (tag, b[pos:pos+4]); pos += 4
        elif tag in (5,6): cp[index] = (tag, b[pos:pos+8]); pos += 8; index += 1
        elif tag in (7,8,16,19,20): cp[index] = (tag, u2(b,pos)); pos += 2
        elif tag in (9,10,11,12,17,18): cp[index] = (tag, (u2(b,pos),u2(b,pos+2))); pos += 4
        elif tag == 15: cp[index] = (tag, (b[pos],u2(b,pos+1))); pos += 3
        else: raise ValueError(tag)
        index += 1
    end_cp = pos
    def utf(index): return cp[index][1].decode('ascii')
    pos += 6
    pos += 2 + 2*u2(b,pos)
    def attributes(pos):
        result=[]; n=u2(b,pos);pos+=2
        for _ in range(n):
            name=utf(u2(b,pos));size=u4(b,pos+2)
            result.append((name,pos+6,size));pos+=6+size
        return pos,result
    fields=u2(b,pos);pos+=2
    for _ in range(fields): pos,_=attributes(pos+6)
    nmethods=u2(b,pos);pos+=2; methods={}
    for _ in range(nmethods):
        name=utf(u2(b,pos+2));descriptor=utf(u2(b,pos+4));pos,attrs=attributes(pos+6)
        for kind,offset,size in attrs:
            if kind=='Code': methods[name+descriptor]={'offset':offset+8,'length':u4(b,offset+4)}
    def ref(index):
        tag,(owner,nt)=cp[index]; name,descriptor=cp[nt][1]
        return {'tag':tag,'owner':utf(cp[owner][1]),'name':utf(name),'descriptor':utf(descriptor)}
    return {'cpCount':count,'cpEnd':end_cp,'methods':methods,'reference':ref}

assert digest(BASE.read_bytes()) == 'a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe'
with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(OVERLAY) as overlay:
    old_entries=base.infolist();new_entries=overlay.infolist()
    assert len(new_entries)==len(old_entries)+2
    differences=[]
    for index,(a,b) in enumerate(zip(old_entries,new_entries)):
        assert a.filename==b.filename
        if base.read(a)!=overlay.read(b): differences.append(a.filename)
    assert differences==[ENTRY]
    added=[e.filename for e in new_entries[len(old_entries):]]
    assert set(added)=={'io/johnsonlee/graphite/diagnostic/QueryExecutionMarker.class','io/johnsonlee/graphite/diagnostic/QueryExecutionMarker$QueryWindow.class'}
    a=base.read(ENTRY);b=overlay.read(ENTRY);pa=parse(a);pb=parse(b)
    names=[m for m in pa['methods'] if m.startswith(METHOD+'(')]
    assert len(names)==1; name=names[0]; ma=pa['methods'][name];mb=pb['methods'][name]
    assert ma['length']==mb['length']==40
    oa=ma['offset']+36;ob=mb['offset']+36
    assert a[oa]==0xb6 and b[ob]==0xb8
    old_ref=pa['reference'](u2(a,oa+1));new_ref=pb['reference'](u2(b,ob+1))
    assert old_ref=={'tag':10,'owner':'io/johnsonlee/graphite/cypher/CrossGraphCypherExecutor','name':'execute','descriptor':'(Ljava/lang/String;Ljava/util/Map;)Lio/johnsonlee/graphite/cypher/CypherResult;'}
    assert new_ref=={'tag':10,'owner':'io/johnsonlee/graphite/diagnostic/QueryExecutionMarker','name':'execute','descriptor':'(Lio/johnsonlee/graphite/cypher/CrossGraphCypherExecutor;Ljava/lang/String;Ljava/util/Map;)Lio/johnsonlee/graphite/cypher/CypherResult;'}
    assert b[:8]==a[:8] and b[10:pa['cpEnd']]==a[10:pa['cpEnd']]
    tail=bytearray(b[pb['cpEnd']:]);p=ob-pb['cpEnd'];tail[p:p+3]=a[oa:oa+3]
    assert bytes(tail)==a[pa['cpEnd']:]
    receipt={'baseJarSha256':digest(BASE.read_bytes()),'overlayJarSha256':digest(OVERLAY.read_bytes()),'originalEntriesComparedIncludingDuplicates':len(old_entries),'unchangedOriginalEntries':len(old_entries)-1,'changedEntries':differences,'addedEntries':added,'allProductionEntriesIdentical':True,'modifiedMethod':name,'codeLength':40,'bci':36,'oldReference':old_ref,'newReference':new_ref,'allOtherClassBytesMatchAfterRemovingAppendedConstantPoolAndReversingSingleInvocation':True,'source':str(pathlib.Path(__file__)),'noJavaOrQueryExecution':True}
(ROOT/'root-overlay-audit.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt))

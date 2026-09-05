"""Read tiny graph headers and frozen classfile bytes only; no Java or workload execution."""
from pathlib import Path
import ast,hashlib,json,struct,zipfile
P=Path(__file__).resolve().parent
W=Path('/Users/johnsonlee/.codex/worktrees/ac7b5da2-2450-48c5-894c-5fd84ab6cb7d/graphite')
old_path=W/'docs/profiling/cold-four-or-index-validation/header-census.json'
old=json.loads(old_path.read_text())
def sha(b):return hashlib.sha256(b).hexdigest()
values=[];prefix=None
for entry in old:
 sidecar=Path(entry['path'])
 with sidecar.open('rb') as f:head=f.read(76)
 assert sha(head)==entry['headerSha256']
 count=struct.unpack_from('>i',head,8)[0];assert count==entry['stringCount']
 strings=sidecar.parent/'graph.strings'
 with strings.open('rb') as f:b=f.read(324)
 assert len(b)==324 and b[:4]==bytes.fromhex('aced0005')
 assert b'it.unimi.dsi.util.FrontCodedStringList' in b[:316] and b'it.unimi.dsi.fastutil.chars.CharArrayFrontCodedList' in b[:316]
 assert b[288:316]==bytes.fromhex('4900016e490005726174696f5b000561727261797400035b5b437870')
 prefix=prefix or b[:316];assert b[:316]==prefix
 n,ratio=struct.unpack_from('>ii',b,316);assert n==count and ratio==8
 values.append({'id':entry['id'],'stringsPath':str(strings),'serializedPrefixBytesRead':324,'serializedPrefixSha256':sha(b),'stringCountOffsetInThisVerifiedSchema':316,'stringCount':n,'ratio':ratio,'sidecarBytesRead':76,'sidecarHeaderSha256':sha(head),'sidecarStringCountOffset':8,'bytePerIdPayload':n,'packedFourBitsPerIdPayload':(n+1)//2,'fourLongBitmapsPayload':4*8*((n+63)//64)})
# Reuse only our own previously audited binary-reader definitions, not its executable audit body.
parser_path=Path('/private/tmp/graphite-main-compilation-diagnostic/independent-audit.py')
ast_tree=ast.parse(parser_path.read_text());defs=[x for x in ast_tree.body if isinstance(x,(ast.ClassDef,ast.FunctionDef)) and x.name in ['Reader','class_methods']]
env={};exec(compile(ast.Module(body=defs,type_ignores=[]),str(parser_path),'exec'),env)
jar=Path('/private/tmp/graphite-next-baseline.T2FTs9/graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar');assert sha(jar.read_bytes())=='a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe'
with zipfile.ZipFile(jar) as z:hash_bytes=z.read('it/unimi/dsi/fastutil/HashCommon.class');set_bytes=z.read('it/unimi/dsi/fastutil/ints/IntOpenHashSet.class')
hm,href=env['class_methods'](hash_bytes);sm,sref=env['class_methods'](set_bytes)
def closure(ref):return dict(zip(ref.__code__.co_freevars,[c.cell_contents for c in ref.__closure__]))['cp']
hcp,scp=closure(href),closure(sref)
code=hm[('arraySize','(IF)I')]
assert len(code)==71 and code[4]==135 and code[6]==141 and code[7]==111 # i2d, f2d, ddiv
assert href(int.from_bytes(code[9:11],'big'))==('java/lang/Math','ceil','(D)D')
assert href(int.from_bytes(code[13:15],'big'))==('it/unimi/dsi/fastutil/HashCommon','nextPowerOfTwo','(J)J')
assert href(int.from_bytes(code[16:18],'big'))==('java/lang/Math','max','(JJ)J')
assert int.from_bytes(hcp[int.from_bytes(code[1:3],'big')][1],'big',signed=True)==2
assert int.from_bytes(hcp[int.from_bytes(code[21:23],'big')][1],'big',signed=True)==1<<30
ctor=sm[('<init>','([I)V')];assert len(ctor)==8 and ctor[2]==18
assert struct.unpack('>f',scp[ctor[3]][1])[0]==0.75
assert sref(int.from_bytes(ctor[5:7],'big'))==('it/unimi/dsi/fastutil/ints/IntOpenHashSet','<init>','([IF)V')
ctor_if=sm[('<init>','(IF)V')];assert ctor_if[83:90]==bytes.fromhex('b400200460bc0a') # n; 1; add; newarray int
proof={'jarSha256':sha(jar.read_bytes()),'hashCommonClassSha256':sha(hash_bytes),'intOpenHashSetClassSha256':sha(set_bytes),'arraySizeCodeBytes':71,'arraySizeCodeHex':code.hex(),'defaultLoadFactor':0.75,'initialKeyArrayLengthFormula':'n + 1 where n = max(2, nextPowerOfTwo(ceil(m / 0.75))); reject n > 2^30','intArrayConstructorCodeHex':ctor.hex(),'capacityConstructorCodeHex':ctor_if.hex(),'payloadFormula':'4 * (n + 1), not object/array-header/alignment-inclusive heap size; m is IntArray length, not per-property supported-cardinality','parserDefinitionSourceSha256':sha(parser_path.read_bytes())}
result={'graphCount':len(values),'totalSmallHeaderBytesRead':len(values)*(324+76),'serializedSchemaPrefixSha256':sha(prefix),'allSerializedCountsEqualVerifiedSidecarCounts':True,'sumStringCount':sum(r['stringCount'] for r in values),'minStringCount':min(r['stringCount'] for r in values),'maxStringCount':max(r['stringCount'] for r in values),'sumByteTablePayload':sum(r['bytePerIdPayload'] for r in values),'sumPackedFourBitPayload':sum(r['packedFourBitsPerIdPayload'] for r in values),'sumFourLongBitmapPayload':sum(r['fourLongBitmapsPayload'] for r in values),'largestTwoByteTablePayload':sum(sorted(r['stringCount'] for r in values)[-2:]),'rows':values,'fastutilProof':proof,'noJavaBuildOrMeasurement':True,'limits':['Offset316 is verified only for these64 identical serialized schema prefixes; graph.strings is Java serialization, not a fixed-format count header.','Only payloads are counted, not actual retained heap, cache behavior or allocation zeroing time.','No exact candidate-array cardinalities are inferred from these headers or CallSite-only exports.']}
(P/'census.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:v for k,v in result.items() if k not in ['rows','fastutilProof']},indent=2))

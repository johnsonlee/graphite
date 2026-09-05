"""Offline census audit: no JVM, query execution, export, test or measurement."""
import collections,gzip,hashlib,json,pathlib,re,struct
ROOT=pathlib.Path(__file__).resolve().parent
MANIFEST=pathlib.Path('/private/tmp/pr113-attempt131-ascii.JqgmHw/fixture64/graphs.tsv')
OLD=pathlib.Path('/private/tmp/graphite-query-marker-capture/original-catalog.json')
V3=pathlib.Path('/private/tmp/graphite-main-profiling-n50joikp/oracle-v3/catalog.json')
INPUTS=pathlib.Path('/private/tmp/graphite-query-marker-capture/profile-graph-content-after.json')
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
def exact(f,n):
 b=f.read(n);assert len(b)==n,(n,len(b));return b
def integer(f):return struct.unpack('>i',exact(f,4))[0]
def main():
 original=json.loads((ROOT/'census.json').read_text());receipt=json.loads((ROOT/'string-export-receipt.json').read_text());v3=json.loads(V3.read_text());prior=json.loads(INPUTS.read_text())
 assert all(c['exitCode']==0 for c in receipt['commands'])
 assert receipt['jarSha256Before']==receipt['jarSha256After']=='a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe'
 assert sha(ROOT/'ExportStrings.java')==receipt['sourceSha256'] and sha(ROOT/'ExportStrings.class')==receipt['classSha256']
 assert sha(ROOT/'strings.utf16.bin.gz')==receipt['exportSha256']
 assert sha(MANIFEST)==v3['manifestSha256'];sources=[line.split('\t') for line in MANIFEST.read_text().splitlines() if line and not line.startswith('#')];assert len(sources)==64
 cases=[]
 for c in json.loads(OLD.read_text()):
  if c['id'].endswith(('distinct-targeted','distinct-dense')):cases.append({'id':c['id'],'terms':list(dict.fromkeys(re.findall("CONTAINS '([^']+)'",c['query'])))})
 for c in v3['logicalCases']:
  if c['id'].startswith('or-four'):cases.append({'id':c['id'],'terms':c['terms'],'expectedHits':c['advertisedHitGraphPositions']})
 assert len(cases)==8
 assert [(c['id'],c['terms']) for c in cases]==[(c['id'],c['terms']) for c in original['cases']]
 terms=list(dict.fromkeys(t for c in cases for t in c['terms']));assert all(t.isascii() and t==t.lower() for t in terms)
 logged=[]
 for line in (ROOT/'export-1.log').read_text().splitlines():
  fields=line.split('\t')
  if len(fields)==4:logged.append(fields)
 assert len(logged)==64
 graph_records=[];percase={c['id']:[] for c in cases};total=0;units=0
 with gzip.open(ROOT/'strings.utf16.bin.gz','rb') as f:
  assert integer(f)==0x47535431
  for gi,source in enumerate(sources):
   graph,path=source[:2];path=pathlib.Path(path);assert integer(f)==gi;S=integer(f);assert S>0
   strings=[];nonascii=0;surrogate_units=0
   for sid in range(S):
    length=integer(f);assert length>=0
    raw=exact(f,2*length);value=raw.decode('utf-16-be',errors='surrogatepass');assert value.encode('utf-16-be',errors='surrogatepass')==raw
    strings.append(value);units+=length;nonascii+=not value.isascii();surrogate_units+=sum(0xD800<=ord(x)<=0xDFFF for x in value)
   assert len(set(strings))==S,'String table must map unique strings to IDs for value-set census'
   sidecar=path/'graph.callsite-string-index'
   with sidecar.open('rb') as sf:
    header=exact(sf,76);magic,version,hs,N=struct.unpack_from('>4i',header);U=list(struct.unpack_from('>4i',header,48));postingcount=struct.unpack_from('>i',header,64)[0]
    assert magic==0x47524353 and version==2 and hs==S and N>0 and postingcount>0
    properties=[];used=set()
    for count in U:
     ids=struct.unpack('>'+str(count)+'i',exact(sf,4*count));ends=struct.unpack('>'+str(count)+'i',exact(sf,4*count))
     assert all(0<=sid<S for sid in ids) and all(a<b for a,b in zip(ids,ids[1:]))
     assert ends[-1]==N and ends[0]>0 and all(a<b for a,b in zip(ends,ends[1:]))
     properties.append(set(ids));used.update(ids);sf.seek(4*N,1)
    signaturesoffset=sf.tell();trigramoffset=signaturesoffset+8*S
    assert sidecar.stat().st_size==trigramoffset+8*postingcount+8
   # This is the actual persisted property-directory union, independently mapped through UTF-16 values.
   assert all(strings[sid].isascii() for sid in used),'Non-ASCII used strings require exact JVM Unicode semantics; do not silently assume Python lower parity'
   masks={sid:sum(1<<i for i,t in enumerate(terms) if t in strings[sid].lower()) for sid in used}
   previous=original['headers'][gi];assert previous['graph']==graph and previous['stringCount']==S and previous['nodeCount']==N and previous['uniquePropertyCounts']==U and previous['headerHex']==header.hex()
   assert original['processed'][gi]['uniqueCounts']==U and original['processed'][gi]['rows']==N
   for ci,c in enumerate(cases):
    indexes=[terms.index(t) for t in c['terms']];bits=[1<<i for i in indexes];combined=sum(bits)
    sizes=[[sum(bool(masks[sid]&bit) for sid in prop) for prop in properties] for bit in bits]
    propunion=[sum(bool(masks[sid]&combined) for sid in prop) for prop in properties]
    uniqueperterm=[sum(bool(mask&bit) for mask in masks.values()) for bit in bits]
    unioncount=sum(bool(mask&combined) for mask in masks.values())
    old=original['cases'][ci]['perGraph'][gi]
    assert sizes==old['matchingIdsPerTermProperty'] and propunion==old['unionIdsPerProperty']
    assert sum(map(sum,sizes))==old['matchingEntries']
    assert old['hasHit']==(unioncount>0)
    for count,size in zip(uniqueperterm,sizes):assert max(size)<=count<=sum(size)
    assert old['perPredicateBytePayload']==len(bits)*4*S and old['perPropertyBytePayload']==4*S and old['packedPropertyBytePayload']==S and old['packedPropertyBitPayload']==8*((4*S+63)//64)
    percase[c['id']].append({'graphIndex':gi,'graph':graph,'stringCount':S,'fourPropertyUsedUnionCount':len(used),'propertySupportIdsPerTerm':sizes,'fourPropertyUnionMatchingIdsPerTerm':uniqueperterm,'fourPropertyUnionMatchingIdsAcrossTerms':unioncount,'uniquePerTermArrayElements':sum(uniqueperterm),'predicatePositionsTimesMatchingElements':4*sum(uniqueperterm),'propertySupportEntries':sum(map(sum,sizes)),'hasCallSiteStringHit':unioncount>0})
   # Independent current-file hashes match the exporter (strings) and pre-existing full-fixture receipts (both files).
   entries={x['path']:x for x in prior[gi]['files']};assert prior[gi]['id']==graph
   stringhash=sha(path/'graph.strings');sidehash=sha(sidecar)
   assert logged[gi]==[str(gi),graph,str(S),stringhash]
   assert stringhash==entries['graph.strings']['sha256'] and sidehash==entries['graph.callsite-string-index']['sha256']
   graph_records.append({'graphIndex':gi,'graph':graph,'stringCount':S,'nodeCount':N,'propertyUniqueCounts':U,'usedUnionCount':len(used),'nonAsciiWholeTableStrings':nonascii,'unpairedSurrogateCodeUnits':surrogate_units,'usedUnionAllAscii':True,'trigramPostingCount':postingcount,'headerSha256':hashlib.sha256(header).hexdigest(),'graphStringsSha256':stringhash,'sidecarSha256':sidehash,'matchesExporterAndPriorFixtureHashes':True})
   total+=S
  assert f.read(1)==b'','No trailing records are allowed; also consumes gzip trailer/CRC'
 assert total==2793940 and sum(g['nodeCount'] for g in graph_records)==5046935
 summaries=[]
 for c in cases:
  rows=percase[c['id']];hits=[r['graphIndex'] for r in rows if r['hasCallSiteStringHit']]
  if 'expectedHits' in c:assert hits==c['expectedHits']
  old=next(x for x in original['cases'] if x['id']==c['id']);assert hits==old['observedHitGraphs']
  summaries.append({**c,'hitGraphs':hits,'propertySupportEntries':sum(r['propertySupportEntries'] for r in rows),'uniquePerTermUnionArrayElements':sum(r['uniquePerTermArrayElements'] for r in rows),'fourPropertyPredicatePositionElements':sum(r['predicatePositionsTimesMatchingElements'] for r in rows),'unionDistinctIdCountSummedAcrossGraphs':sum(r['fourPropertyUnionMatchingIdsAcrossTerms'] for r in rows),'perGraph':rows})
 out={'passed':True,'scope':'Independent original UTF-16 export + actual sidecar property-ID-directory census, read-only Python. No Java, queries, build, export or performance measurement.','candidateMeaning':'Property support is per property. Four-property union candidate counts use actual property-used IDs with exact ASCII term matching. These predict semantic mapped matches but do not independently replay trigram-anchor selection; root owns that separate replay. Whole stringTable S is address/memory domain, not actual candidate count.','inputReceipts':{'censusSha256':sha(ROOT/'census.json'),'censusSourceSha256':sha(ROOT/'census.py'),'exportSha256':sha(ROOT/'strings.utf16.bin.gz'),'exportReceiptSha256':sha(ROOT/'string-export-receipt.json'),'priorFixtureReceiptSha256':sha(INPUTS),'catalogSha256':sha(OLD),'v3CatalogSha256':sha(V3),'manifestSha256':sha(MANIFEST)},'exportIntegrity':{'graphs':64,'strings':total,'utf16CodeUnits':units,'nodeCount':sum(g['nodeCount'] for g in graph_records),'fourPropertyUsedUnionIds':sum(g['usedUnionCount'] for g in graph_records),'wholeTableNonAsciiStrings':sum(g['nonAsciiWholeTableStrings'] for g in graph_records),'allUsedStringsAscii':True,'allStringTableValuesUniqueWithinGraph':True,'gst1MagicAndContiguousGraphIndexes':True,'completeDecodeAndNoTrailingBytes':True,'all64StringFileHashesMatchExportAndPriorReceipt':True,'all64SidecarHashesMatchPriorReceipt':True,'jarHashBeforeAfterEqualityVerifiedFromReceiptNotRehashed':True},'graphs':graph_records,'cases':summaries,'limits':['TSV property-support membership is not each predicate mapped exactMatchingStringIds array.','View matchesByPredicate key excludes property; the same term reuses one IntArray across properties, while raw exactMatchSets construction may still create per-position sets.','Mapped candidates come from persisted trigram postings and reusableContains, not an unconditional full-table scan.','Full-table stringCount S is relevant to dense-array address payload; it is not actual retained memory, reservation, peak or runtime allocation.','No runtime graph-work, heap saving, latency, CPU or speedup is inferred.','All used property strings and case terms are ASCII; unused full-table Unicode strings do not enter this candidate proof.']}
 (ROOT/'independent-census-audit.json').write_text(json.dumps(out,indent=2)+'\n')
 lines=['# 独立候选规模审计','','**PASS（限定口径）**：直接读取原 UTF-16 二进制和实际 sidecar 的四属性 ID 目录，独立复算的每 term/每 property 支持集计数，与原 TSV census 的全部 8×64 行一致。没有执行 Java、查询、新导出、构建或性能测量。','','导出验证：GST1 magic、连续 0..63 图号、2,793,940 个字符串、5,046,935 节点、UTF-16 长度/逐字符串往返/EOF/gzip CRC 完整；每图字符串唯一。64 个 graph.strings 现 hash 同 exporter 日志与既有 fixture 收据，64 个 sidecar hash 同既有收据；每个 header 的 S/N/四属性 unique count 与实际目录相同。JAR 前后相等来自原 terminal 收据，本审计未重 hash 大 JAR。','','| Case | Hit graphs | 属性支持 entries | 按 term 去属性重用后的 union IDs 总和 | 四属性 predicate-position 元素和 |','|---|---:|---:|---:|---:|']
 for c in summaries:lines.append(f"| {c['id']} | {len(c['hitGraphs'])} | {c['propertySupportEntries']} | {c['uniquePerTermUnionArrayElements']} | {c['fourPropertyPredicatePositionElements']} |")
 lines+=['','三层必须分开：','','1. **属性支持集**：实际 caller_class/name、callee_class/name 各自使用并匹配的 string IDs。原 census.py 按 TSV 字符串集合正确推导这一层；它是 per-property 内容统计。','2. **四属性 union 的匹配 IDs**：从 sidecar 四属性目录取 union，按 term 去重匹配。View.kt:69 的 key 只有 transform/mode/expected，同词跨属性复用同一个 IntArray。其私有方法（:205）在 trigram anchor 中过滤，未按当前 property 再过滤；因此原 per-property sizes 不能当作其返回数组长度。本审计得到 union 上的语义匹配数；原始 anchor 的完整重放由主分析独立完成。','3. **全 S 地址域**：导出完整 stringTable 是为了验证 ID→字符串、最大索引域和假设稠密数组 payload。trigram 的 usedCallSiteTrigramStringIds（MappedCallSiteStringIndex.kt:2407）只收四属性 union；不能把 S 或全表字符串匹配量写成实际 mapped 候选。','','所有被 sidecar 使用的属性字符串和 terms 均为 ASCII，独立 Python lower 在这个域与现有 lowercase 匹配一致；未用未使用的 Unicode 字符串推断候选。sidecar 目录 ID/末端 offsets 已核查递增/范围和每属性末端 N，文件整体 hash 与既有完整输入凭据相等；没有声称重新运行整个持久化 validator。','','原 census 的 P×S、4×S、S 和 8×ceil(4S/64) 仅是假定数组 payload 算术。表中 predicate-position 元素和说明同词候选被四属性引用的计数，不能同时称作四个独立 IntArray 的实际分配量；当前 raw 路径 exactMatchingStringIds.map(::IntOpenHashSet) 是否建立重复 set 是另一个对象层问题。selected tuple feasibility、early return、未执行图、reservation、对象头、GC 及重叠生命周期都不由此 census 决定。','','源位置：MappedCallSiteStringIndexView.kt:69–82、205–241；MappedCallSiteStringIndex.kt:2407–2422；MappedWebGraphBackedGraph.kt:481–541、3079–3094。全部 per-graph 原值、输入 hash 和导出证明在 independent-census-audit.json；脚本为 independent-census-audit.py。']
 (ROOT/'independent-census-audit.md').write_text('\n'.join(lines)+'\n')
 print(json.dumps({'integrity':out['exportIntegrity'],'cases':[{k:c[k] for k in ['id','propertySupportEntries','uniquePerTermUnionArrayElements','fourPropertyPredicatePositionElements']} for c in summaries]},indent=2))
if __name__=='__main__':main()

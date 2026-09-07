import pathlib,struct,shutil
here=pathlib.Path(__file__).resolve().parent;source=here.parent/'candidate-index'/'annotation';target=here/'annotation-after'
if target.exists():raise SystemExit('fixture already exists; refuse overwrite')
shutil.copytree(source,target)
raw=(source/'graph.nodedata').read_bytes();index=(source/'graph.nodeindex').read_bytes();records=[struct.unpack_from('>ibq',index,8+i*13)for i in range(struct.unpack_from('>i',index,4)[0])]
positions=sorted(records,key=lambda r:r[2]);chunks={}
for i,(nid,tag,offset)in enumerate(positions):chunks[nid]=raw[offset:positions[i+1][2]if i+1<len(positions)else len(raw)]
positions=[r for r in positions if r[0]!=101]+[r for r in positions if r[0]==101]
new=bytearray(raw[:8]);updated={}
for nid,tag,offset in positions:updated[nid]=len(new);new.extend(chunks[nid])
(target/'graph.nodedata').write_bytes(new)
(target/'graph.nodeindex').write_bytes(index[:8]+b''.join(struct.pack('>ibq',nid,tag,updated[nid])for nid,tag,_ in positions))
offsets=bytearray((source/'graph.nodeoffsets').read_bytes())
for nid,offset in updated.items():struct.pack_into('>q',offsets,8+nid*8,offset+1)
(target/'graph.nodeoffsets').write_bytes(offsets)
for name in ['graph.callsite-string-index','graph.callsite-string-content.identity']:(target/name).unlink(missing_ok=True)
print(updated)

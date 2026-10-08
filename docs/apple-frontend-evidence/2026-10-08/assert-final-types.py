import json,pathlib
p=pathlib.Path('/Users/johnsonlee/.codex/benchmarks/apple-validation')
s=json.loads((p/'signal-fixed-types.json').read_text());w=json.loads((p/'swiftpm-fixed-types.json').read_text())
sha={r['m.name']:(r['m.parameter_types'],r['m.return_type']) for r in s['cryptography']['rows']}
assert sha=={
'computeSHA256Digest(_:)':(['Foundation.Data'],'Foundation.Data?'),
'computeSHA256Digest(_:truncatedToBytes:)':(['Foundation.Data','Swift.UInt'],'Foundation.Data?'),
'computeSHA256DigestOfFile(at:)':(['Foundation.URL'],'Foundation.Data'),
'computeSHA256HMAC(_:key:)':(['Foundation.Data','Foundation.Data'],'Foundation.Data?'),
'computeSHA256HMAC(_:key:truncatedToBytes:)':(['Foundation.Data','Foundation.Data','Swift.UInt'],'Foundation.Data?')},sha
assert [r['f.type'] for r in s['private_owner']['rows']]==['SignalUI.ContactShareViewModel']
assert {r['m.class']:r['m.return_type'] for r in s['deinit']['rows']}=={'SignalServiceKit.AccountAttributesUpdaterImpl':'Swift.Void','Signal.CVComponentGenericAttachment':'Swift.Void'}
assert {r['f.name']:r['f.type']for r in s['badge']['rows']}=={'badgeStore':'BadgeStore * _Nonnull','_badgeStore':'BadgeStore * _Nonnull'}
assert s['crossmodule']['rows']==[{'m.class':'SignalServiceKit.OutgoingStoryMessage','m.name':'prepareForMultisending(destinations:state:transaction:)','m.parameter_types':['[SignalUI.MultisendDestination]','SignalUI.MultisendState','SignalServiceKit.SDSAnyWriteTransaction'],'m.return_type':'Swift.Void'}]
assert w['method_empty']['rows']==[{'n':0}],w['empty_methods']
assert w['field_empty']['rows']==[{'n':0}],w['empty_fields']
print('PASS exact Signal SHA/private owner/Swift and ObjC deinit/synthesized badge/crossmodule signatures; SwiftPM source methods+fields all typed')

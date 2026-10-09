import com.google.gson.*;
import it.unimi.dsi.fastutil.io.BinIO;
import it.unimi.dsi.webgraph.*;
import java.nio.file.*;
import java.util.*;
public final class VerifyTopology {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static int mapped(int id,Map<Integer,Integer> mapping){return mapping.getOrDefault(id,id);}
    static Map<String,Object> topologyProof(Path actual, Path expected, Map<Integer,Integer> mapping) throws Exception {
        ImmutableGraph a = BVGraph.load(actual.resolve("forward").toString());
        ImmutableGraph b = BVGraph.load(expected.resolve("forward").toString());
        check(a.numNodes() == b.numNodes() && a.numArcs() == b.numArcs(), "node/edge counts");
        byte[] al = Files.readAllBytes(actual.resolve("graph.labels")), bl = Files.readAllBytes(expected.resolve("graph.labels"));
        int[] ap = BinIO.loadInts(actual.resolve("graph.labelprefix").toString());
        int[] bp = BinIO.loadInts(expected.resolve("graph.labelprefix").toString());
        check(ap.length == a.numNodes()+1 && bp.length == b.numNodes()+1, "label prefix size");
        NodeIterator ai = a.nodeIterator(), bi = b.nodeIterator();
        int apos=0,bpos=0,nodes=0; long edges=0;
        while (ai.hasNext()) {
            int id=ai.nextInt(); check(bi.hasNext() && bi.nextInt()==id, "iterator ids");
            int ad=ai.outdegree(), bd=bi.outdegree(); int[] ase=ai.successorArray(), bse=bi.successorArray();
            check(ap[id]==apos && bp[id]==bpos, "exact prefix offset " + id);
            check(a.outdegree(id)==ad && b.outdegree(id)==bd, "random degree " + id);
            int[] ara=a.successorArray(id), bra=b.successorArray(id);
            for (int i=0;i<ad;i++) check(ara[i]==ase[i], "actual random offset " + id + ":" + i);
            for (int i=0;i<bd;i++) check(bra[i]==bse[i], "reference random offset " + id + ":" + i);
            int target=mapped(id,mapping), td=b.outdegree(target); int[] ts=b.successorArray(target);
            check(ad==td, "mapped outdegree " + id);
            long[] actualEdges=new long[ad];
            for (int i=0;i<ad;i++) actualEdges[i]=((long)mapped(ase[i],mapping)<<8)|(al[apos+i]&255);
            Arrays.sort(actualEdges);
            for (int i=0;i<td;i++) check(actualEdges[i]==(((long)ts[i]<<8)|(bl[bp[target]+i]&255)), "complete mapped labeled edge " + id + ":" + i);
            nodes++; edges+=ad; apos+=ad; bpos+=bd;
        }
        check(!bi.hasNext() && nodes==a.numNodes() && edges==a.numArcs(), "topology end");
        check(apos==al.length && bpos==bl.length && ap[nodes]==apos && bp[nodes]==bpos, "label/prefix end");
        return Map.of("nodeSlots",nodes,"labeledEdges",edges,"allRandomAccessOffsetsChecked",true,"allPrefixOffsetsChecked",true,"unmatchedEdges",0);
    }
    static String hash(Path path)throws Exception {
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        try(var in=Files.newInputStream(path)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
    static void verifyPins(JsonObject pins)throws Exception {
        for(var entry:pins.entrySet())check(hash(Path.of(entry.getKey())).equals(entry.getValue().getAsString()),"input changed: "+entry.getKey());
    }
    public static void main(String[] args)throws Exception {
        check(args.length==5,"expected actual reference mapping output helperRoot");
        Path actual=Path.of(args[0]),reference=Path.of(args[1]),mappingFile=Path.of(args[2]),out=Path.of(args[3]);
        check(!Files.exists(out),"output exists");
        check(mappingFile.getFileName().toString().equals("field-bijection.tsv"),"unexpected mapping file");
        Path receiptFile=mappingFile.getParent().resolve("receipt.json");
        JsonObject receipt=JsonParser.parseString(Files.readString(receiptFile)).getAsJsonObject();
        check(receipt.get("status").getAsString().equals("PASS_WITH_EXPLICIT_TYPE_OVERLOAD_SYNTHETIC_AND_INHERITED_FIELD_CORRECTIONS"),"missing corrected core proof");
        check(receipt.get("role").getAsString().equals("FULL_CORE_WITH_EXPLICIT_TYPE_OVERLOAD_SYNTHETIC_AND_INHERITED_FIELD_CORRECTIONS_NOT_STRICT_EQUIVALENCE"),"wrong core proof role");
        check(Path.of(receipt.get("actual").getAsString()).toRealPath().equals(actual.toRealPath()) && Path.of(receipt.get("reference").getAsString()).toRealPath().equals(reference.toRealPath()),"core graph roots");
        check(hash(mappingFile).equals(receipt.get("mappingSha256").getAsString()),"core mapping binding");
        String receiptHash=hash(receiptFile),mappingHash=hash(mappingFile);
        Path helperRoot=Path.of(args[4]).toRealPath();
        check(hash(helperRoot.resolve("core_semantics.py")).equals(receipt.get("coreParserSha256").getAsString()),"core parser binding");
        check(hash(helperRoot.resolve("legacy_wire.py")).equals(receipt.get("legacyParserSha256").getAsString()),"legacy parser binding");
        check(hash(helperRoot.resolve("callsite_index.py")).equals(receipt.get("callSiteIndexParserSha256").getAsString()),"CallSite index parser binding");
        check(receipt.getAsJsonObject("callSiteIndexProof").get("status").getAsString().equals("PASS"),"missing full CallSite index proof");
        check(receipt.getAsJsonArray("legacyNodeIndexProofs").size()==2,"two per-file nodeindex proofs");
        check(hash(helperRoot.resolve("callsite_ordinals.py")).equals(receipt.get("ordinalParserSha256").getAsString()),"ordinal parser binding");
        check(hash(mappingFile.getParent().resolve("ordinal-equivalence.json")).equals(receipt.get("ordinalProofSha256").getAsString()),"ordinal proof binding");
        check(JsonParser.parseString(Files.readString(mappingFile.getParent().resolve("ordinal-equivalence.json"))).getAsJsonObject().get("status").getAsString().equals("PASS"),"ordinal equivalence must pass");
        check(!receipt.get("strictEquivalence").getAsBoolean(),"correction scope must be explicit");
        check(hash(helperRoot.resolve("field_authority.py")).equals(receipt.get("fieldAuthorityParserSha256").getAsString()),"classfile authority parser binding");
        Path correctionFile=mappingFile.getParent().resolve("field-corrections.json");
        check(hash(correctionFile).equals(receipt.get("fieldCorrectionProofSha256").getAsString()),"correction receipt binding");
        JsonObject correction=JsonParser.parseString(Files.readString(correctionFile)).getAsJsonObject();
        check(correction.get("status").getAsString().equals("PASS_EXPLICIT_CLASSFILE_FIELD_CORRECTIONS") && !correction.get("strictEquivalence").getAsBoolean(),"correction receipt scope");
        int correctionCount=correction.get("exceptionCount").getAsInt();
        check(correctionCount==receipt.get("fieldCorrectionCount").getAsInt() && correctionCount==correction.getAsJsonArray("corrections").size(),"correction count");
        check(correction.get("inheritedFieldLookupAllowed").getAsBoolean() && receipt.get("inheritedFieldLookupAllowed").getAsBoolean(),"explicit inherited field mode");
        int inheritedCount=correction.get("inheritedFieldCorrectionCount").getAsInt(),checkedInherited=0;
        for(var item:correction.getAsJsonArray("corrections")) {
            var row=item.getAsJsonObject();var proof=row.getAsJsonObject("classfileAuthority");
            check(proof.get("symbolicOwner").equals(row.getAsJsonObject("B").getAsJsonArray("key").get(0)),"inherited symbolic owner binding");
            if(!proof.get("symbolicOwner").equals(proof.getAsJsonObject("class").get("owner"))) checkedInherited++;
        }
        check(inheritedCount==checkedInherited && inheritedCount==receipt.get("inheritedFieldCorrectionCount").getAsInt(),"inherited field correction count");
        check(hash(mappingFile.getParent().resolve("raw-field-key-differences.json")).equals(correction.get("rawDifferencesSha256").getAsString()),"raw field differences binding");
        check(hash(helperRoot.resolve("method_authority.py")).equals(receipt.get("methodAuthorityParserSha256").getAsString()),"method authority parser binding");
        check(hash(helperRoot.resolve("method_classfile.py")).equals(receipt.get("methodClassfileParserSha256").getAsString()),"method classfile parser binding");
        Path methodFile=mappingFile.getParent().resolve("method-corrections.json");
        check(hash(methodFile).equals(receipt.get("methodCorrectionProofSha256").getAsString()),"method correction receipt binding");
        JsonObject methodCorrection=JsonParser.parseString(Files.readString(methodFile)).getAsJsonObject();
        check(methodCorrection.get("status").getAsString().equals("PASS_EXPLICIT_METHOD_ARRAY_CORRECTIONS") && !methodCorrection.get("strictEquivalence").getAsBoolean(),"method correction scope");
        int methodCount=methodCorrection.get("exceptionCount").getAsInt();
        check(methodCount==receipt.get("methodCorrectionCount").getAsInt() && methodCount==methodCorrection.getAsJsonArray("occurrences").size(),"method correction count");
        check(hash(mappingFile.getParent().resolve("ordinal-equivalence-raw.json")).equals(receipt.get("rawOrdinalProofSha256").getAsString()),"original ordinal difference proof preserved");
        JsonObject pins=receipt.getAsJsonObject("inputs");
        for(Path root:List.of(actual,reference))for(String name:List.of("forward.graph","forward.offsets","forward.properties","graph.labels","graph.labelprefix"))check(pins.has(root.resolve(name).toString()),"missing topology input pin");
        for(String section:List.of("inputs","rawClassPins"))for(var entry:correction.getAsJsonObject(section).entrySet())check(pins.has(entry.getKey()) && pins.get(entry.getKey()).equals(entry.getValue()),"missing classfile authority pin");
        for(String section:List.of("inputs","rawClassPins"))for(var entry:methodCorrection.getAsJsonObject(section).entrySet())check(pins.has(entry.getKey()) && pins.get(entry.getKey()).equals(entry.getValue()),"missing method authority pin");
        check(hash(helperRoot.resolve("local_array_corrections.py")).equals(receipt.get("localArrayParserSha256").getAsString()),"Local array parser binding");
        Path localFile=mappingFile.getParent().resolve("local-array-corrections.json");
        check(hash(localFile).equals(receipt.get("localArrayCorrectionProofSha256").getAsString()),"Local array proof binding");
        JsonObject local=JsonParser.parseString(Files.readString(localFile)).getAsJsonObject();
        check(local.get("status").getAsString().equals("PASS_EXPLICIT_SOURCE_LOCAL_ARRAY_CORRECTIONS") && !local.get("strictEquivalence").getAsBoolean() && !local.get("syntheticLocalInferenceOracleClaim").getAsBoolean(),"Local source correction scope");
        int localCount=local.get("exceptionCount").getAsInt();
        check(localCount==receipt.get("localArrayCorrectionCount").getAsInt() && localCount==local.getAsJsonArray("occurrences").size(),"Local correction count");
        for(var entry:local.getAsJsonObject("inputs").entrySet())check(pins.has(entry.getKey()) && pins.get(entry.getKey()).equals(entry.getValue()),"missing Local source authority pin");
        check(hash(helperRoot.resolve("parameter_array_corrections.py")).equals(receipt.get("parameterArrayParserSha256").getAsString()),"Parameter parser binding");
        Path parameterFile=mappingFile.getParent().resolve("parameter-array-corrections.json");
        check(hash(parameterFile).equals(receipt.get("parameterArrayCorrectionProofSha256").getAsString()),"Parameter proof binding");
        JsonObject parameter=JsonParser.parseString(Files.readString(parameterFile)).getAsJsonObject();
        check(parameter.get("status").getAsString().equals("PASS_EXPLICIT_METHOD_BOUND_PARAMETER_ARRAY_CORRECTIONS") && !parameter.get("strictEquivalence").getAsBoolean(),"Parameter correction scope");
        check(parameter.get("methodCorrectionProofSha256").equals(receipt.get("methodCorrectionProofSha256")),"Parameter exact method authority binding");
        int parameterCount=parameter.get("exceptionCount").getAsInt();
        check(parameterCount==receipt.get("parameterArrayCorrectionCount").getAsInt() && parameterCount==parameter.getAsJsonArray("occurrences").size(),"Parameter correction count");
        check(hash(helperRoot.resolve("legacy_method_collisions.py")).equals(receipt.get("legacyCollisionParserSha256").getAsString()),"overload parser binding");
        Path collisionFile=mappingFile.getParent().resolve("legacy-method-collisions.json");
        check(hash(collisionFile).equals(receipt.get("legacyCollisionProofSha256").getAsString()),"overload proof binding");
        JsonObject collision=JsonParser.parseString(Files.readString(collisionFile)).getAsJsonObject();
        check(collision.get("status").getAsString().equals("PASS_EXPLICIT_LEGACY_METHOD_COLLISION_CORRECTIONS") && !collision.get("strictEquivalence").getAsBoolean() && !collision.get("independentInvocationOrderOracleClaim").getAsBoolean(),"overload correction scope");
        check(collision.get("methodCorrectionProofSha256").equals(receipt.get("methodCorrectionProofSha256")),"overload exact method authority binding");
        int ordinalGroups=collision.get("ordinalGroupCount").getAsInt(),metadataGroups=collision.get("metadataGroupCount").getAsInt(),bindingCount=collision.getAsJsonArray("ordinalBindingCorrections").size();
        check(ordinalGroups==receipt.get("legacyOrdinalGroupCount").getAsInt() && ordinalGroups==collision.getAsJsonArray("ordinalGroups").size(),"overload ordinal group count");
        check(metadataGroups==receipt.get("legacyMetadataGroupCount").getAsInt() && metadataGroups==collision.getAsJsonArray("metadataGroups").size(),"overload metadata group count");
        check(hash(helperRoot.resolve("metadata_signature_collisions.py")).equals(receipt.get("metadataSignatureCollisionParserSha256").getAsString()),"metadata signature parser binding");
        int returnGroups=collision.get("returnOmittedMetadataGroupCount").getAsInt(),recoveredMethods=0;
        check(returnGroups==receipt.get("legacyReturnOmittedGroupCount").getAsInt() && returnGroups==collision.getAsJsonArray("returnOmittedMetadataGroups").size(),"return-omitted metadata groups");
        check(!collision.get("methodReturnNormalization").getAsBoolean(),"method return types must not be rewritten");
        for(var item:collision.getAsJsonArray("returnOmittedMetadataGroups")) {
            var group=item.getAsJsonObject(); var members=group.getAsJsonArray("B");
            int count=group.get("recoveredMethods").getAsInt();
            check(count>0 && count==members.size()-1 && group.getAsJsonArray("declarations").size()==members.size(),"complete metadata source group");
            check(!group.get("methodReturnNormalization").getAsBoolean() && members.contains(group.get("retainedMethod")),"retained real overload");
            check(group.get("firstTablePosition").getAsInt()<=group.get("retainedOriginalPosition").getAsInt(),"first metadata key position");
            recoveredMethods+=count;
        }
        check(recoveredMethods==collision.get("recoveredMetadataMethods").getAsInt() && recoveredMethods==receipt.get("recoveredMetadataMethodCount").getAsInt(),"recovered metadata count");
        check(bindingCount==receipt.get("legacyOrdinalBindingCount").getAsInt() && bindingCount<=1,"ordinal binding correction count");
        for(var entry:collision.getAsJsonObject("inputs").entrySet())check(pins.has(entry.getKey()) && pins.get(entry.getKey()).equals(entry.getValue()),"missing overload source pin");
        check(hash(helperRoot.resolve("synthetic_metadata.py")).equals(receipt.get("syntheticMethodKeyParserSha256").getAsString()),"synthetic key parser binding");
        Path syntheticFile=mappingFile.getParent().resolve("synthetic-method-key-corrections.json");
        check(hash(syntheticFile).equals(receipt.get("syntheticMethodKeyProofSha256").getAsString()),"synthetic key proof binding");
        JsonObject synthetic=JsonParser.parseString(Files.readString(syntheticFile)).getAsJsonObject();
        check(synthetic.get("status").getAsString().equals("PASS_EXPLICIT_SYNTHETIC_METHOD_KEY_CORRECTIONS") && !synthetic.get("strictEquivalence").getAsBoolean() && synthetic.get("survivingFingerprintsPreserved").getAsBoolean() && !synthetic.get("newFingerprintRecomputed").getAsBoolean(),"synthetic key correction scope");
        check(hash(helperRoot.resolve("synthetic_collisions.py")).equals(receipt.get("syntheticCollisionParserSha256").getAsString()),"synthetic collision parser binding");
        int constructorGroups=synthetic.get("constructorCollisionGroupCount").getAsInt(), recoveredIdentities=0;
        check(constructorGroups==synthetic.getAsJsonArray("constructorCollisionGroups").size() && constructorGroups==receipt.get("syntheticConstructorCollisionGroupCount").getAsInt(),"constructor collision group count");
        check(synthetic.get("fingerprintsPreserved").getAsBoolean()==(constructorGroups==0) && synthetic.get("rowOrderPreserved").getAsBoolean()==(constructorGroups==0),"collision-specific preservation scope");
        for(var item:synthetic.getAsJsonArray("constructorCollisionGroups")) {
            var group=item.getAsJsonObject();var members=group.getAsJsonArray("B");
            int recovered=group.get("recoveredIdentities").getAsInt();
            check(recovered>0 && recovered==members.size()-1 && !group.get("newFingerprintRecomputed").getAsBoolean(),"recovered identity count/scope");
            recoveredIdentities+=recovered; int retained=group.get("retainedIndex").getAsInt();boolean found=false;
            for(var entry:members) {
                var member=entry.getAsJsonObject();var method=member.getAsJsonArray("fullMethod");
                check(method.get(1).getAsString().equals("<init>") && (member.getAsJsonObject("declaration").getAsJsonObject("method").get("accessFlags").getAsInt() & 0x1000)!=0,"synthetic constructor source");
                if(member.get("index").getAsInt()==retained) {check(!found && member.get("fingerprint").equals(group.getAsJsonObject("C").get("fingerprint")),"surviving exact fingerprint");found=true;}
            }
            check(found,"retained constructor missing");
        }
        check(recoveredIdentities==synthetic.get("recoveredIdentityCount").getAsInt() && recoveredIdentities==receipt.get("recoveredSyntheticIdentityCount").getAsInt(),"total recovered identities");
        for(var entry:synthetic.getAsJsonObject("inputs").entrySet())check(pins.has(entry.getKey()) && pins.get(entry.getKey()).equals(entry.getValue()),"missing synthetic source pin");
        check(synthetic.get("methodCorrectionProofSha256").equals(receipt.get("methodCorrectionProofSha256")),"synthetic key method authority binding");
        int syntheticCount=synthetic.get("exceptionCount").getAsInt();
        check(syntheticCount==receipt.get("syntheticMethodKeyCorrectionCount").getAsInt() && syntheticCount==synthetic.getAsJsonArray("occurrences").size(),"synthetic key correction count");
        verifyPins(pins);
        Map<Integer,Integer> mapping=new HashMap<>();
        for(String line:Files.readAllLines(mappingFile)){
            String[] fields=line.split("\\t");check(fields.length==2,"mapping fields");
            check(mapping.put(Integer.valueOf(fields[0]),Integer.valueOf(fields[1]))==null,"duplicate mapped ID");
        }
        check(mapping.keySet().equals(new HashSet<>(mapping.values())),"not closed bijection");
        check(mapping.size()==receipt.get("movedFields").getAsInt(),"mapping count");
        Map<String,Object> result=new LinkedHashMap<>(topologyProof(actual,reference,mapping));
        verifyPins(pins);
        check(hash(receiptFile).equals(receiptHash) && hash(mappingFile).equals(mappingHash),"upstream proof changed");
        result.put("coreReceiptSha256",receiptHash);result.put("mappingSha256",mappingHash);result.put("inputPins",pins);
        check(hash(correctionFile).equals(receipt.get("fieldCorrectionProofSha256").getAsString()),"correction proof changed");
        result.put("fieldCorrectionCount",correctionCount);result.put("strictEquivalence",false);
        result.put("fieldCorrectionProofSha256",receipt.get("fieldCorrectionProofSha256").getAsString());
        check(hash(methodFile).equals(receipt.get("methodCorrectionProofSha256").getAsString()),"method correction proof changed");
        result.put("methodCorrectionCount",methodCount);result.put("methodCorrectionProofSha256",receipt.get("methodCorrectionProofSha256").getAsString());
        check(hash(localFile).equals(receipt.get("localArrayCorrectionProofSha256").getAsString()),"Local proof changed");
        result.put("localArrayCorrectionCount",localCount);
        result.put("localArrayCorrectionProofSha256",receipt.get("localArrayCorrectionProofSha256").getAsString());
        check(hash(parameterFile).equals(receipt.get("parameterArrayCorrectionProofSha256").getAsString()),"Parameter proof changed");
        result.put("parameterArrayCorrectionCount",parameterCount);
        result.put("parameterArrayCorrectionProofSha256",receipt.get("parameterArrayCorrectionProofSha256").getAsString());
        check(hash(collisionFile).equals(receipt.get("legacyCollisionProofSha256").getAsString()),"overload proof changed");
        result.put("legacyOrdinalGroupCount",ordinalGroups);result.put("legacyMetadataGroupCount",metadataGroups);result.put("legacyReturnOmittedGroupCount",returnGroups);result.put("recoveredMetadataMethodCount",recoveredMethods);result.put("legacyOrdinalBindingCount",bindingCount);
        result.put("legacyCollisionProofSha256",receipt.get("legacyCollisionProofSha256").getAsString());
        check(hash(syntheticFile).equals(receipt.get("syntheticMethodKeyProofSha256").getAsString()),"synthetic key proof changed");
        result.put("syntheticMethodKeyCorrectionCount",syntheticCount);
        result.put("syntheticConstructorCollisionGroupCount",constructorGroups);result.put("recoveredSyntheticIdentityCount",recoveredIdentities);result.put("syntheticMethodKeyProofSha256",receipt.get("syntheticMethodKeyProofSha256").getAsString());
        result.put("inheritedFieldCorrectionCount",inheritedCount);
        result.put("status","PASS_TOPOLOGY_WITH_EXPLICIT_TYPE_OVERLOAD_SYNTHETIC_AND_INHERITED_FIELD_CORRECTIONS"); result.put("actual",actual.toString());result.put("reference",reference.toString());
        Files.writeString(out,new GsonBuilder().setPrettyPrinting().create().toJson(result)+"\n",StandardOpenOption.CREATE_NEW);
    }
}

import com.google.gson.*;
import it.unimi.dsi.fastutil.io.BinIO;
import it.unimi.dsi.webgraph.*;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.util.*;

/** Own-writer raw inputs only: no cross-arm mapping or equivalence claim. */
public final class ExportJvmRawEdges {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static String hash(Path file)throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var in=Files.newInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
    static Map<String,String> ref(Path file)throws Exception{return Map.of("path",file.toAbsolutePath().normalize().toString(),"sha256",hash(file));}
    static void pins(JsonObject values)throws Exception{for(var item:values.entrySet())check(hash(Path.of(item.getKey())).equals(item.getValue().getAsString()),"input changed: "+item.getKey());}
    static void export(JsonObject row,Path out,Path source,Path clazz)throws Exception {
        Path root=Path.of(row.get("root").getAsString());
        JsonObject inputs=row.getAsJsonObject("sourceInputs");
        JsonObject hashes=new JsonObject();
        for(String name:List.of("forward.graph","forward.offsets","forward.properties","graph.labels","graph.labelprefix")) {
            var input=inputs.getAsJsonObject(name);check(input.get("path").getAsString().equals(root.resolve(name).toString()),"input root");
            hashes.add(input.get("path").getAsString(),input.get("sha256"));
        }
        pins(hashes);Files.createDirectory(out);
        ImmutableGraph graph=BVGraph.load(root.resolve("forward").toString());
        byte[] labels=Files.readAllBytes(root.resolve("graph.labels"));
        int[] prefix=BinIO.loadInts(root.resolve("graph.labelprefix").toString());
        check(prefix.length==graph.numNodes()+1,"label prefix size");
        var digest=MessageDigest.getInstance("SHA-256");Path edgesFile=out.resolve("edges.bin");
        int nodes=0,pos=0;long edges=0;
        try(var edgesOut=new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(Files.newOutputStream(edgesFile,StandardOpenOption.CREATE_NEW),digest),65536))) {
            edgesOut.writeInt(0x47534501);edgesOut.writeInt(graph.numNodes());edgesOut.writeLong(graph.numArcs());
            NodeIterator iterator=graph.nodeIterator();
            while(iterator.hasNext()) {
                int id=iterator.nextInt();check(id==nodes,"sequential node ID");int degree=iterator.outdegree();
                int[] sequential=iterator.successorArray(),random=graph.successorArray(id);
                check(prefix[id]==pos && graph.outdegree(id)==degree,"prefix/random degree");
                check(pos>=0 && degree>=0 && (long)pos+degree<=labels.length,"label bounds");
                int previous=-1;
                for(int i=0;i<degree;i++) {
                    int target=sequential[i];check(target==random[i] && target>previous && target<graph.numNodes(),"random offset/target order");previous=target;
                    edgesOut.writeInt(id);edgesOut.writeInt(target);edgesOut.writeByte(labels[pos+i]&255);
                }
                nodes++;edges+=degree;pos+=degree;
            }
            check(nodes==graph.numNodes() && edges==graph.numArcs() && pos==labels.length && prefix[nodes]==pos,"complete graph consumption");
        }
        pins(hashes);
        Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("schema","graphite.raw-labeled-edge-export.v1");receipt.put("format","GSE01");
        receipt.put("graphRoot",root.toString());receipt.put("sourceInputs",inputs);
        receipt.put("output",Map.of("path",edgesFile.toString(),"sha256",HexFormat.of().formatHex(digest.digest())));
        receipt.put("nodeSlots",nodes);receipt.put("labeledEdges",edges);receipt.put("helperSource",ref(source));receipt.put("helperClass",ref(clazz));
        for(String name:List.of("allSequentialOffsetsChecked","allRandomAccessOffsetsChecked","allLabelPrefixOffsetsChecked","rawNodeIdsPreserved","fullScanConsumed"))receipt.put(name,true);
        receipt.put("mappingApplied",false);
        Files.writeString(out.resolve("receipt.json"),new GsonBuilder().setPrettyPrinting().create().toJson(receipt)+"\n",StandardOpenOption.CREATE_NEW);
    }
    public static void main(String[] args)throws Exception {
        check(args.length==1,"expected sealed export plan");Path planFile=Path.of(args[0]);String before=hash(planFile);
        JsonObject plan=JsonParser.parseString(Files.readString(planFile)).getAsJsonObject();
        check(plan.get("schema").getAsString().equals("graphite.jvm-raw-edge-export-plan.v1"),"plan schema");
        var rows=plan.getAsJsonArray("graphs");check(rows.size()==64,"all64 required");
        Path out=Path.of(plan.get("output").getAsString()).resolve("exports");
        Path source=Path.of(plan.getAsJsonObject("helperSource").get("path").getAsString());
        check(hash(source).equals(plan.getAsJsonObject("helperSource").get("sha256").getAsString()),"helper source");
        Path clazz=Path.of(ExportJvmRawEdges.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("ExportJvmRawEdges.class");
        Set<String> seen=new HashSet<>();long start=System.nanoTime();int completed=0;
        for(var value:rows){var row=value.getAsJsonObject();String id=row.get("id").getAsString();check(id.matches("[A-Za-z0-9_-]+") && seen.add(id),"graph ID");export(row,out.resolve(id),source,clazz);completed++;System.out.printf(Locale.ROOT,"raw-edge-export graph=%s completed=%d/64 elapsed=%.3fs%n",id,completed,(System.nanoTime()-start)/1e9);System.out.flush();}
        check(hash(planFile).equals(before),"plan drift");
    }
}

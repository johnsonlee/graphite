// Main-generated correctness fixture: sparse IDs, repeated properties, UTF-16
// edge cases. Expected directory membership comes from main's loaded nodes and
// StringTable, not from a native decoder or a hand-written index serializer.
import io.johnsonlee.graphite.core.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.io.*;
import java.util.*;
class GenerateIndex {
 static MethodDescriptor method(String c,String n,List<TypeDescriptor> args){return new MethodDescriptor(new TypeDescriptor(c,List.of()),n,args,new TypeDescriptor("void",List.of()));}
 public static void main(String[] args)throws Exception{
  Path dir=Path.of(args[0]);Files.createDirectories(dir);DefaultGraph.Builder b=new DefaultGraph.Builder();
  MethodDescriptor a=method("example.\u0130D\ud800Caller","caller",List.of(new TypeDescriptor("int",List.of()),new TypeDescriptor("java.lang.String",List.of()))),c=method("example.Other","other",List.of()),x=method("example.Target","invoke",List.of()),y=method("example.Target","other",List.of());
  int[] ids={90,2,17,41};MethodDescriptor[][] pairs={{a,x},{c,y},{a,x},{c,x}};
  for(int i=0;i<ids.length;i++)b.addNode((CallSiteNode)CallSiteNode.class.getConstructors()[0].newInstance(ids[i],pairs[i][0],pairs[i][1],Integer.valueOf(i+1),null,List.of(),null));
  GraphStore.INSTANCE.save(b.build(),dir.resolve("store"),3,true);
  MappedWebGraphBackedGraph g=(MappedWebGraphBackedGraph)GraphStore.INSTANCE.loadMapped(dir.resolve("store"));
  if(!g.prepareCallSiteStringIndex$webgraph(() -> {}))throw new AssertionError("main did not accept persisted index");
  var loaded=MappedWebGraphBackedGraph.class.getDeclaredField("callSiteStringIndexLoadedFromPersistence");loaded.setAccessible(true);if(!loaded.getBoolean(g))throw new AssertionError("main rebuilt instead of reading index");
  StringTable table=StringTable.Companion.load(dir.resolve("store"));
  List<CallSiteNode> nodes=kotlin.sequences.SequencesKt.toList(g.nodes(CallSiteNode.class));
  Map<String,Object> out=new LinkedHashMap<>();List<Object> raw=new ArrayList<>();List<Map<Integer,List<Integer>>> properties=new ArrayList<>();for(int p=0;p<4;p++)properties.add(new TreeMap<>());
  for(CallSiteNode n:nodes){String[] values={n.getCaller().getDeclaringClass().getClassName(),n.getCaller().getName(),n.getCallee().getDeclaringClass().getClassName(),n.getCallee().getName()};int id=(Integer)CallSiteNode.class.getMethod("getId-4bBHiqc").invoke(n);int[] strings=new int[4];for(int p=0;p<4;p++){strings[p]=table.findId$webgraph(values[p]);if(strings[p]<0)throw new AssertionError("string not found");properties.get(p).computeIfAbsent(strings[p],k->new ArrayList<>()).add(id);}raw.add(Map.of("id",id,"stringIds",strings));}
  // Read the JVM-written numeric payload only after proving main accepted it.
  List<String> signatures=new ArrayList<>();Map<Integer,List<Integer>> trigrams=new TreeMap<>();
  try(DataInputStream in=new DataInputStream(Files.newInputStream(dir.resolve("store/graph.callsite-string-index")))){
   in.readInt();in.readInt();int strings=in.readInt(),calls=in.readInt();in.skipNBytes(32);int[] unique=new int[4];for(int i=0;i<4;i++)unique[i]=in.readInt();int postings=in.readInt();in.readLong();for(int n:unique)in.skipNBytes(8L*n+4L*calls);
   for(int i=0;i<strings;i++)signatures.add(Long.toUnsignedString(in.readLong(),16));
   for(int i=0;i<postings;i++){long pair=in.readLong();trigrams.computeIfAbsent((int)(pair>>32),k->new ArrayList<>()).add((int)pair);}
   out.put("crc32",Long.toUnsignedString(in.readLong(),16));if(in.read()!=-1)throw new AssertionError("trailing payload");
  }
  out.put("signatures",signatures);out.put("trigrams",trigrams);
  out.put("nodes",raw);out.put("properties",properties);out.put("mainAcceptedIndex",true);Files.writeString(dir.resolve("expected.json"),new GsonBuilder().setPrettyPrinting().create().toJson(out)+"\n");
 }
}

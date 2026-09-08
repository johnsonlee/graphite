import com.google.gson.*;
import io.johnsonlee.graphite.core.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Actual pinned-main writer and recorded post-write name-SID corruption. No timers. */
public class WorkFixture {
 public static void main(String[] args)throws Exception {
  JsonObject specs=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
  List<Object> mutations=new ArrayList<>();
  var type=new TypeDescriptor("Neutral",List.of());
  var method=new MethodDescriptor(type,"hit-call",List.of(),type);
  for(var entry:specs.entrySet()) {
   var spec=entry.getValue().getAsJsonObject();var nodes=new ArrayList<Node>();var builder=new DefaultGraph.Builder();
   var callMethod=spec.has("matchingCall")&&!spec.get("matchingCall").getAsBoolean()?new MethodDescriptor(type,"neutral-call",List.of(),type):method;
   if(spec.get("call").getAsBoolean())nodes.add((Node)CallSiteNode.class.getConstructors()[0].newInstance(1,callMethod,callMethod,null,null,List.of(),null));
   int i=0;for(var name:spec.getAsJsonArray("locals"))nodes.add((Node)LocalVariable.class.getConstructors()[0].newInstance(10+10*i++,name.getAsString(),type,method,null));
   for(var node:nodes)builder.addNode(node);Graph base=builder.build();
   Graph ordered=(Graph)Proxy.newProxyInstance(Graph.class.getClassLoader(),new Class[]{Graph.class},(p,m,a)->{
    if(m.getName().equals("nodes")){Class<?> t=(Class<?>)a[0];return kotlin.collections.CollectionsKt.asSequence(nodes.stream().filter(t::isInstance).toList());}
    return m.invoke(base,a);
   });
   Path dir=Path.of(args[1],entry.getKey());GraphStore.INSTANCE.save(ordered,dir,3,true);
   var mapped=GraphStore.INSTANCE.loadMapped(dir);((java.io.Closeable)mapped).close();
   if(spec.has("missingOffsets")) {
    byte[] bytes=Files.readAllBytes(dir.resolve("graph.nodeoffsets"));var offsets=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    for(var raw:spec.getAsJsonArray("missingOffsets")){int id=raw.getAsInt();long prior=offsets.getLong(8+8*id);offsets.putLong(8+8*id,0);mutations.add(Map.of("fixture",entry.getKey(),"nodeID",id,"file","graph.nodeoffsets","offset",8+8*id,"before",prior,"after",0));}
    Files.write(dir.resolve("graph.nodeoffsets"),bytes);
   }
   if(spec.has("badIndex")&&!spec.get("badIndex").isJsonNull()) {
    int id=10+10*spec.get("badIndex").getAsInt();
    var offsets=ByteBuffer.wrap(Files.readAllBytes(dir.resolve("graph.nodeoffsets"))).order(ByteOrder.BIG_ENDIAN);
    int offset=(int)offsets.getLong(8+8*id)-1;
    byte[] bytes=Files.readAllBytes(dir.resolve("graph.nodedata"));var data=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    if(data.getInt(offset)!=id||data.get(offset+4)!=8)throw new IllegalStateException("Wrong LocalVariable record");
    int prior=data.getInt(offset+5);data.putInt(offset+5,Integer.MAX_VALUE);Files.write(dir.resolve("graph.nodedata"),bytes);
    mutations.add(Map.of("fixture",entry.getKey(),"nodeID",id,"file","graph.nodedata","offset",offset+5,"before",prior,"after",Integer.MAX_VALUE));
   }
  }
  Files.writeString(Path.of(args[2]),new GsonBuilder().setPrettyPrinting().create().toJson(mutations)+"\n",StandardOpenOption.CREATE_NEW);
 }
}

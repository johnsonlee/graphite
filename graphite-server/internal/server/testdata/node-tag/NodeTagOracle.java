import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.lang.reflect.*;
public class NodeTagOracle {
 static Throwable unwrap(Throwable t){return t instanceof InvocationTargetException?t.getCause():t;}
 static void error(Map<String,Object> out,Throwable t){t=unwrap(t);out.put("error",t.getClass().getSimpleName());out.put("message",t.getMessage());}
 static Map<String,Object> item(int tag,String mode,String phase,String operation){var out=new LinkedHashMap<String,Object>();out.put("tag",tag);out.put("mode",mode);out.put("phase",phase);out.put("operation",operation);return out;}
 static void mutate(Path fixture,int tag)throws Exception{try(FileChannel f=FileChannel.open(fixture.resolve("graph.nodedata"),StandardOpenOption.WRITE)){f.write(ByteBuffer.wrap(new byte[]{(byte)tag}),75);}}
 public static void main(String[]args)throws Exception{
  var observations=new ArrayList<Object>();Path work=Files.createTempDirectory("graphite-node-tag-oracle-");
  for(int tag:List.of(16,127,128,255))for(String mode:List.of("MAPPED","EAGER","AUTO"))for(String phase:List.of("before","after")){
   Path fixture=work.resolve(tag+"-"+mode+"-"+phase);Files.createDirectories(fixture);try(var files=Files.list(Path.of(args[0]))){for(Path f:files.toList())Files.copy(f,fixture.resolve(f.getFileName()));}Files.copy(Path.of(args[1]),fixture.resolve("graph.nodeindex"),StandardCopyOption.REPLACE_EXISTING);
   if(phase.equals("before"))mutate(fixture,tag);Graph graph;var load=item(tag,mode,phase,"load");
   try{graph=GraphStore.INSTANCE.load(fixture,GraphStore.LoadMode.valueOf(mode));load.put("loaded",true);observations.add(load);}catch(Throwable t){error(load,t);observations.add(load);continue;}
   if(phase.equals("after"))mutate(fixture,tag);
   var node=item(tag,mode,phase,"node");try{var value=Graph.class.getMethod("node-NKfh_u4",int.class).invoke(graph,7);node.put("id",7);node.put("value",value.getClass().getMethod("getValue").invoke(value));}catch(Throwable t){error(node,t);}observations.add(node);
   for(boolean cross:List.of(false,true)){var result=item(tag,mode,phase,cross?"cross":"query");try{String query="MATCH (n:IntConstant) RETURN n.id AS id LIMIT 8";var rows=cross?new CrossGraphCypherExecutor(List.of(new CypherGraph("a",graph),new CypherGraph("b",graph))).execute(query):new CypherExecutor(graph).execute(query);result.put("columns",rows.getColumns());result.put("rows",rows.getRows());}catch(Throwable t){error(result,t);}observations.add(result);}
   if(graph instanceof java.io.Closeable)((java.io.Closeable)graph).close();
  }
  Files.writeString(Path.of(args[2]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(observations)+"\n");
 }
}

import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
public class CorruptionOracle {
 public static void main(String[]args)throws Exception {
  Path fixture=Path.of(args[0]);GraphStore.INSTANCE.ensureNodeIndex(fixture);Graph graph=GraphStore.INSTANCE.loadMapped(fixture);
  // Eight fixed-width IntConstants: mutate only the final node tag AFTER load.
  try(FileChannel f=FileChannel.open(fixture.resolve("graph.nodedata"),StandardOpenOption.WRITE)){f.write(ByteBuffer.wrap(new byte[]{127}),75);}
  var json=new GsonBuilder().serializeNulls().setPrettyPrinting().create();var cases=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray();var out=new ArrayList<Object>();
  for(JsonElement element:cases){var spec=element.getAsJsonObject();String query=spec.get("query").getAsString();for(boolean cross:List.of(false,true)){
   var result=new LinkedHashMap<String,Object>();result.put("name",spec.get("name").getAsString());result.put("query",query);result.put("cross",cross);
   Map<String,Object>params=json.fromJson(spec.get("params"),Map.class);
   try{var rows=cross?new CrossGraphCypherExecutor(List.of(new CypherGraph("a",graph),new CypherGraph("b",graph))).execute(query,params):new CypherExecutor(graph).execute(query,params);result.put("columns",rows.getColumns());result.put("rows",rows.getRows());}catch(Throwable error){result.put("error",error.getClass().getSimpleName());result.put("message",error.getMessage());}out.add(result);
  }}
  Files.writeString(Path.of(args[2]),json.toJson(out)+"\n");if(graph instanceof java.io.Closeable)((java.io.Closeable)graph).close();
 }
}

import com.google.gson.*;import io.johnsonlee.graphite.cypher.*;import io.johnsonlee.graphite.graph.*;import io.johnsonlee.graphite.webgraph.*;import java.nio.file.*;import java.util.*;
public class WriterRoundTripOracle{
 public static void main(String[]args)throws Exception{
  var json=new GsonBuilder().serializeNulls().setPrettyPrinting().create();var out=new LinkedHashMap<String,Object>();
  Graph graph=GraphStore.INSTANCE.loadMapped(Path.of(args[0]));var sources=List.of(new CypherGraph("single",graph));
  out.put("queryResult",HistoryOracle.run(sources,false,"MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN n.caller_name AS x LIMIT 4096"));
  var field=graph.getClass().getDeclaredField("callSiteStringIndexLoadedFromPersistence");field.setAccessible(true);out.put("loadedFromPersistence",field.get(graph));
  out.put("rawResult",HistoryOracle.run(sources,false,"MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN n.caller_name AS x LIMIT 1"));
  ((java.io.Closeable)graph).close();Files.writeString(Path.of(args[1]),json.toJson(out)+"\n");
 }
}

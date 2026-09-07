import com.google.gson.*;import io.johnsonlee.graphite.cypher.*;import io.johnsonlee.graphite.graph.*;import io.johnsonlee.graphite.webgraph.*;import java.nio.file.*;import java.util.*;
public class DenseHistoryOracle {
 public static void main(String[]args)throws Exception{
  var json=new GsonBuilder().serializeNulls().setPrettyPrinting().create();var out=new ArrayList<Object>();
  for(String mode:List.of("partial","complete-miss","limit-count")){
   Graph g=GraphStore.INSTANCE.loadMapped(Path.of(args[0]));var sources=List.of(new CypherGraph("single",g));var item=new LinkedHashMap<String,Object>();item.put("mode",mode);item.put("initial",HistoryOracle.state(sources));
   String prefix="MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN n.caller_name AS x LIMIT ",query=mode.equals("complete-miss")?"MATCH (n) WHERE n.caller_name CONTAINS 'absent' RETURN n.caller_name AS x LIMIT 1":prefix+(mode.equals("limit-count")?"4096":"1");
   var targets=new ArrayList<Object>();targets.add(HistoryOracle.run(sources,false,query));targets.add(HistoryOracle.run(sources,false,prefix+"1"));item.put("targets",targets);item.put("indexFileAfter",Files.isRegularFile(Path.of(args[0],"graph.callsite-string-index")));out.add(item);((java.io.Closeable)g).close();
  }
  Files.writeString(Path.of(args[1]),json.toJson(out)+"\n");
 }
}

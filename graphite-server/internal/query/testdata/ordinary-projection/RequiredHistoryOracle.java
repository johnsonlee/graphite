import com.google.gson.*;import io.johnsonlee.graphite.cypher.*;import io.johnsonlee.graphite.graph.*;import io.johnsonlee.graphite.webgraph.*;import java.nio.file.*;import java.util.*;
public class RequiredHistoryOracle{
 public static void main(String[]args)throws Exception{
  var json=new GsonBuilder().serializeNulls().setPrettyPrinting().create();var all=new ArrayList<Object>();
  for(int count:List.of(1,2,40))for(String property:List.of("caller_name","callee_class")){
   var sources=new ArrayList<CypherGraph>();for(int i=0;i<count;i++)sources.add(new CypherGraph(count==1?"single":"g"+i,GraphStore.INSTANCE.loadMapped(Path.of(args[0]))));
   var item=new LinkedHashMap<String,Object>();item.put("count",count);item.put("property",property);var targets=new ArrayList<Object>();
   String query="MATCH (n) WHERE n.caller_name='other' RETURN n."+property+" AS x LIMIT 1";
   for(int repeat=0;repeat<2;repeat++)targets.add(HistoryOracle.run(sources,count>1,query));item.put("targets",targets);all.add(item);for(var source:sources)((java.io.Closeable)source.getGraph()).close();
  }
  Files.writeString(Path.of(args[1]),json.toJson(all)+"\n");
 }
}

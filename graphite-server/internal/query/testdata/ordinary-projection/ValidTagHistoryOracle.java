import com.google.gson.*;import io.johnsonlee.graphite.cypher.*;import io.johnsonlee.graphite.graph.*;import io.johnsonlee.graphite.webgraph.*;import java.nio.file.*;import java.util.*;
public class ValidTagHistoryOracle{
 public static void main(String[]args)throws Exception{
 var all=new ArrayList<Object>();
 for(int count:List.of(1,2,40))for(String operator:List.of("='other'","CONTAINS 'other'")){
  var sources=new ArrayList<CypherGraph>();for(int i=0;i<count;i++)sources.add(new CypherGraph(count==1?"single":"g"+i,GraphStore.INSTANCE.loadMapped(Path.of(args[0]))));
  var row=new LinkedHashMap<String,Object>();row.put("count",count);row.put("operator",operator);var targets=new ArrayList<Object>();String query="MATCH (n) WHERE n.caller_name "+operator+" RETURN n.caller_name AS x LIMIT 1";
  for(int i=0;i<2;i++)targets.add(HistoryOracle.run(sources,count>1,query));row.put("targets",targets);all.add(row);for(var source:sources)((java.io.Closeable)source.getGraph()).close();
 }
 Files.writeString(Path.of(args[1]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(all)+"\n");
 }
}

import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import java.nio.file.*;
import java.util.*;

/** Full executor correctness oracle; fixtures are disposable copies, no timers. */
public class CandidateSourceOracle {
 public static void main(String[] args)throws Exception {
  Gson json=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
  JsonArray specs=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray();
  var output=new ArrayList<Object>();
  for(var element:specs){
   var spec=element.getAsJsonObject();int count=spec.get("sources").getAsInt();
   var sources=new ArrayList<CypherGraph>();var kinds=spec.getAsJsonArray("fixtures");
   for(int i=0;i<count;i++){
    var fixture=kinds.get(Math.min(i,kinds.size()-1)).getAsString();
    sources.add(new CypherGraph(count==1?"single":"g"+i,GraphStore.INSTANCE.loadMapped(Path.of(args[0],fixture))));
   }
   var row=new LinkedHashMap<String,Object>();row.put("spec",json.fromJson(spec,Map.class));
   try {
    if(spec.has("warm"))row.put("warm",HistoryOracle.run(sources,count>1,spec.get("warm").getAsString()));
    var targets=new ArrayList<Object>();
    for(int i=0;i<2;i++)targets.add(HistoryOracle.run(sources,count>1,spec.get("query").getAsString()));
    row.put("targets",targets);output.add(row);
   } finally {for(var source:sources)((java.io.Closeable)source.getGraph()).close();}
  }
  Files.writeString(Path.of(args[2]),json.toJson(output)+"\n");
 }
}

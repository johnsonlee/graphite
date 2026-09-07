import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import java.nio.file.*;
import java.util.*;
public class SourcesOracle {
 public static void main(String[]args)throws Exception {
  GraphStore.INSTANCE.ensureNodeIndex(Path.of(args[0]));
  Graph graph=GraphStore.INSTANCE.loadMapped(Path.of(args[0]));
  Graph late=args.length>3?GraphStore.INSTANCE.loadMapped(Path.of(args[3])):graph;
  Gson json=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
  JsonArray specs=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray(),out=new JsonArray();
  for(JsonElement element:specs){var spec=element.getAsJsonObject();var sources=new ArrayList<CypherGraph>();int count=spec.get("sources").getAsInt();for(int i=0;i<count;i++)sources.add(new CypherGraph("g"+i,i==count-1?late:graph));
   var result=new LinkedHashMap<String,Object>();result.put("name",spec.get("name").getAsString());result.put("query",spec.get("query").getAsString());result.put("sources",count);
   try {var value=new CrossGraphCypherExecutor(sources).execute(spec.get("query").getAsString(),Map.of());result.put("columns",value.getColumns());result.put("rows",value.getRows());}catch(Throwable error){result.put("error",error.getClass().getSimpleName());result.put("message",error.getMessage());}
   out.add(json.toJsonTree(result));
  }
  Files.writeString(Path.of(args[2]),json.toJson(out)+"\n");((java.io.Closeable)graph).close();if(late!=graph)((java.io.Closeable)late).close();
 }
}

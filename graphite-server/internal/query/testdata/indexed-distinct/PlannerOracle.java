import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import java.nio.file.*;
import java.util.*;

/** Tiny correctness-only main oracle; exact full rows/errors, no timers. */
public class PlannerOracle {
 public static void main(String[]args)throws Exception {
  Path fixture=Path.of(args[0]);GraphStore.INSTANCE.ensureNodeIndex(fixture);
  Graph mapped=GraphStore.INSTANCE.loadMapped(fixture),empty=new DefaultGraph.Builder().build();
  Gson json=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
  JsonArray cases=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray(),observations=new JsonArray();
  for(JsonElement input:cases){JsonObject spec=input.getAsJsonObject();String query=spec.get("query").getAsString();Graph graph=spec.has("empty")&&spec.get("empty").getAsBoolean()?empty:mapped;
   Map<String,Object>params=new LinkedHashMap<>();if(spec.has("params"))params=json.fromJson(spec.get("params"),Map.class);
   for(boolean cross:List.of(false,true)){Map<String,Object>result=new LinkedHashMap<>();result.put("name",spec.get("name").getAsString());result.put("query",query);result.put("cross",cross);result.put("empty",graph==empty);
    try {CypherResult rows=cross?new CrossGraphCypherExecutor(List.of(new CypherGraph("a",graph),new CypherGraph("b",graph))).execute(query,params):new CypherExecutor(graph).execute(query,params);result.put("columns",rows.getColumns());result.put("rows",rows.getRows());}
    catch(Throwable error){result.put("error",error.getClass().getSimpleName());result.put("message",error.getMessage());}
    observations.add(json.toJsonTree(result));
   }
  }
  String output=json.toJson(observations);StringBuilder escaped=new StringBuilder();for(int i=0;i<output.length();i++){char c=output.charAt(i);if(Character.isSurrogate(c))escaped.append(String.format("\\u%04x",(int)c));else escaped.append(c);}Files.writeString(Path.of(args[2]),escaped+"\n");if(mapped instanceof java.io.Closeable)((java.io.Closeable)mapped).close();
 }
}

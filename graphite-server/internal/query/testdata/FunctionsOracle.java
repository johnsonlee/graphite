import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.Graph;
import io.johnsonlee.graphite.webgraph.GraphStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Main JVM function/aggregation oracle; only local correctness fixtures. */
public class FunctionsOracle {
 static Object normalize(Object value){
  if(value instanceof Double && !Double.isFinite((Double)value) || value instanceof Float && !Float.isFinite((Float)value))return Map.of("$number",value.toString());
  if(value instanceof Map){Map<String,Object> out=new LinkedHashMap<>();((Map<?,?>)value).forEach((k,v)->out.put(k.toString(),normalize(v)));return out;}
  if(value instanceof List){List<Object> out=new ArrayList<>();for(Object v:(List<?>)value)out.add(normalize(v));return out;}
  return value;
 }
 public static void main(String[] args)throws Exception{
  Graph graph=GraphStore.INSTANCE.load(Path.of(args[0]),GraphStore.LoadMode.EAGER);
  CypherExecutor scoped=new CypherExecutor(graph);
  CrossGraphCypherExecutor cross=new CrossGraphCypherExecutor(List.of(new CypherGraph("orders",graph),new CypherGraph("billing",graph)));
  Gson json=new GsonBuilder().serializeNulls().create();
  BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));String line;
  while((line=input.readLine())!=null){JsonObject spec=JsonParser.parseString(line).getAsJsonObject();String query=spec.get("query").getAsString();boolean qualified=spec.has("cross")&&spec.get("cross").getAsBoolean();Map<String,Object> result=new LinkedHashMap<>();result.put("query",query);result.put("cross",qualified);
   try{CypherResult rows=(qualified?cross.execute(query):scoped.execute(query));result.put("columns",rows.getColumns());result.put("rows",normalize(rows.getRows()));}catch(Throwable error){result.put("error",error.getClass().getSimpleName());result.put("message",error.getMessage());}
   System.out.println(json.toJson(result));
  }
 }
}

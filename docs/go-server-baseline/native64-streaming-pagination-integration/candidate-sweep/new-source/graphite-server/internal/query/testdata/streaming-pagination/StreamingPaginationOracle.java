import com.google.gson.*;import io.johnsonlee.graphite.cypher.*;import io.johnsonlee.graphite.graph.*;import io.johnsonlee.graphite.webgraph.*;import java.nio.file.*;import java.util.*;
/** Actual main graph/serializer; correctness only, no time measurements. */
public class StreamingPaginationOracle {
 public static void main(String[] args)throws Exception {
  System.err.println("java="+System.getProperty("java.version")+" processors="+Runtime.getRuntime().availableProcessors());
  Gson json=new GsonBuilder().serializeNulls().setPrettyPrinting().create();var specs=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray();var out=new ArrayList<Object>();
  for(var raw:specs){var spec=raw.getAsJsonObject();for(boolean cross:List.of(false,true)){
   Graph graph=GraphStore.INSTANCE.load(Path.of(args[0]),GraphStore.LoadMode.valueOf(args[3]));var row=new LinkedHashMap<String,Object>();String query=spec.get("query").getAsString();row.put("name",spec.get("name").getAsString());row.put("query",query);row.put("cross",cross);row.put("mode",args[3]);Map<String,Object>params=spec.has("params")?json.fromJson(spec.get("params"),Map.class):Map.of();row.put("params",params);
   try{var result=cross?new CrossGraphCypherExecutor(List.of(new CypherGraph("a",graph),new CypherGraph("b",graph))).execute(query,params):new CypherExecutor(graph).execute(query,params);row.put("columns",result.getColumns());row.put("rows",result.getRows());}catch(Throwable x){row.put("error",x.getClass().getSimpleName());row.put("message",x.getMessage());}out.add(row);if(graph instanceof java.io.Closeable)((java.io.Closeable)graph).close();
  }}
  String text=json.toJson(out);StringBuilder escaped=new StringBuilder();for(char c:text.toCharArray()){if(Character.isSurrogate(c))escaped.append(String.format("\\u%04x",(int)c));else escaped.append(c);}Files.writeString(Path.of(args[2]),escaped+"\n");Files.write(Path.of(args[2].replace("-main.json","-wire.json")),(text+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
 }
}

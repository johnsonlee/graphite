import com.google.gson.*;import io.johnsonlee.graphite.cypher.*;import io.johnsonlee.graphite.graph.*;import io.johnsonlee.graphite.webgraph.*;import java.nio.file.*;import java.util.*;
/** Independent tiny source histories; every source owns a copied persisted graph. */
public class StreamingSourcesOracle {
 public static void main(String[] args)throws Exception {
  var json=new GsonBuilder().serializeNulls().setPrettyPrinting().create();var out=new ArrayList<Object>();
  for(var raw:JsonParser.parseString(Files.readString(Path.of(args[2]))).getAsJsonArray()){
   var spec=raw.getAsJsonObject();var record=new LinkedHashMap<String,Object>();for(var en:spec.entrySet())record.put(en.getKey(),json.fromJson(en.getValue(),Object.class));
   int count=spec.get("count").getAsInt(), badAt=spec.has("badAt")?spec.get("badAt").getAsInt():-1;Path scratch=Files.createTempDirectory(Path.of(args[1]),"case-");var sources=new ArrayList<CypherGraph>();
   for(int i=0;i<count;i++){
    Path from=Path.of(args[0],i==badAt?spec.get("fixture").getAsString():"clean"),to=scratch.resolve("g"+i);Files.createDirectories(to);try(var files=Files.list(from)){for(Path p:files.toList())if(!(spec.has("missingIndex")&&spec.get("missingIndex").getAsBoolean()&&p.getFileName().toString().equals("graph.callsite-string-index")))Files.copy(p,to.resolve(p.getFileName()));}
    var graph=GraphStore.INSTANCE.load(to,GraphStore.LoadMode.valueOf(spec.has("mode")?spec.get("mode").getAsString():"MAPPED"));sources.add(new CypherGraph(spec.has("reverseIDs")?"g"+(count-1-i):"g"+i,graph));
   }
   try{
    var executor=new CrossGraphCypherExecutor(sources);int repeat=spec.has("repeat")?spec.get("repeat").getAsInt():1;var history=new ArrayList<Object>();
    for(int i=0;i<repeat;i++){var response=new LinkedHashMap<String,Object>();try{var result=executor.execute(spec.get("query").getAsString(),Map.of());response.put("columns",result.getColumns());response.put("rows",result.getRows());}catch(Throwable x){response.put("error",x.getClass().getSimpleName());response.put("message",x.getMessage());}history.add(response);}record.put("history",history);
   }finally{for(var s:sources)if(s.getGraph() instanceof java.io.Closeable)((java.io.Closeable)s.getGraph()).close();}out.add(record);
  }
  String text=json.toJson(out)+"\n";StringBuilder escaped=new StringBuilder();for(char c:text.toCharArray()){if(Character.isSurrogate(c))escaped.append(String.format("\\u%04x",(int)c));else escaped.append(c);}Files.writeString(Path.of(args[3]),escaped);Files.write(Path.of(args[3].replace("-main.json","-wire.json")),text.getBytes(java.nio.charset.StandardCharsets.UTF_8));System.err.println("processors="+Runtime.getRuntime().availableProcessors()+" records="+out.size());
 }
}

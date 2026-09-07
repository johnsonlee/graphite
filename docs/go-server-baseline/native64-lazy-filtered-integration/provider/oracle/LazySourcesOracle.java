import com.google.gson.*;import io.johnsonlee.graphite.cypher.*;import io.johnsonlee.graphite.graph.*;import io.johnsonlee.graphite.webgraph.*;import java.nio.file.*;import java.util.*;
public class LazySourcesOracle{
 public static void main(String[]args)throws Exception{
  var json=new GsonBuilder().serializeNulls().setPrettyPrinting().create();var out=new ArrayList<Object>();
  for(int count:new int[]{2,9,40})for(boolean missing:new boolean[]{false,true})for(String projection:List.of("n.id","id(n)","[n.id,n.caller_name]"))for(boolean distinct:List.of(false,true)){
   var row=new LinkedHashMap<String,Object>();String q="MATCH (n) WHERE n.caller_name CONTAINS 'other' AND true RETURN "+(distinct?"DISTINCT ":"")+projection+" AS x LIMIT 1";row.put("count",count);row.put("missingIndex",missing);row.put("query",q);
   Path scratch=Files.createTempDirectory(Path.of(args[1]),"source-case-");var sources=new ArrayList<CypherGraph>();
   for(int i=0;i<count;i++){Path from=Path.of(args[0],i==1?"bad-matched":"clean"),to=scratch.resolve("g"+i);Files.createDirectories(to);try(var files=Files.list(from)){for(Path p:files.toList())if(!(missing&&p.getFileName().toString().equals("graph.callsite-string-index")))Files.copy(p,to.resolve(p.getFileName()));}sources.add(new CypherGraph("g"+i,GraphStore.INSTANCE.loadMapped(to)));}
   try{var result=new CrossGraphCypherExecutor(sources).execute(q,Map.of());row.put("columns",result.getColumns());row.put("rows",result.getRows());}catch(Throwable x){row.put("error",x.getClass().getSimpleName());row.put("message",x.getMessage());}out.add(row);for(var s:sources)((java.io.Closeable)s.getGraph()).close();
  }
  Files.write(Path.of(args[2]),(json.toJson(out)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));System.err.println("processors="+Runtime.getRuntime().availableProcessors()+" cases="+out.size());
 }
}

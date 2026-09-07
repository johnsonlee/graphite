import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;import com.google.gson.*;import io.johnsonlee.graphite.cypher.*;import io.johnsonlee.graphite.graph.*;import io.johnsonlee.graphite.webgraph.*;
public class TupleProjectionOracle{
 public static void main(String[]args)throws Exception{
  System.err.println("availableProcessors="+Runtime.getRuntime().availableProcessors());var all=new ArrayList<Object>();
  var projections=List.of("n.caller_class AS a,n.caller_name AS b,n.callee_class AS c,n.callee_name AS d","n.callee_name AS d,n.caller_name AS b,n.caller_class AS a,n.callee_class AS c","n.caller_name AS a,n.caller_name AS b","n.caller_name AS a,n.name AS b","n.caller_name AS a,n.callee_name AS a","n.caller_name AS a,n.graphId AS g");
  for(String projection:projections)for(String second:List.of("n4096","bad4096")){
   Graph a=GraphStore.INSTANCE.loadMapped(Path.of(args[0],"n4096")),b=GraphStore.INSTANCE.loadMapped(Path.of(args[0],second));var sources=new ArrayList<CypherGraph>();for(int j=0;j<8;j++)sources.add(new CypherGraph(j==0?"a":"a"+(j+1),a));sources.add(new CypherGraph("b",b));
   for(Graph g:List.of(a,b))new CypherExecutor(g).execute("MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN DISTINCT n.caller_name AS x LIMIT 1");
   String q="MATCH (n) WHERE n.caller_name CONTAINS $term RETURN DISTINCT "+projection+" LIMIT 256";var out=new LinkedHashMap<String,Object>();out.put("query",q);out.put("parameters",Map.of("term","other"));out.put("second",second);try{var r=new CrossGraphCypherExecutor(sources).execute(q,Map.of("term","other"));out.put("columns",r.getColumns());out.put("rows",r.getRows());}catch(Exception x){out.put("errorClass",x.getClass().getSimpleName());out.put("error",x.getMessage());}out.put("b",ExactTupleOracle.state(ExactTupleOracle.field(b,"callSiteStringIndex")));all.add(out);((java.io.Closeable)a).close();((java.io.Closeable)b).close();
  }
  // Java's UTF8 writer replaces an unpaired surrogate with '?', matching the
  // actual main HTTP JSON byte boundary; preserve all complete rows and columns.
  Files.write(Path.of(args[1]),(new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(all)+"\n").getBytes(StandardCharsets.UTF_8));
 }
}

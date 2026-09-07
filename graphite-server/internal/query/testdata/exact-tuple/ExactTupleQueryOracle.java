import java.nio.file.*;import java.util.*;import com.google.gson.*;import io.johnsonlee.graphite.cypher.*;import io.johnsonlee.graphite.graph.*;import io.johnsonlee.graphite.webgraph.*;
public class ExactTupleQueryOracle{
 public static void main(String[] args)throws Exception{
  System.err.println("availableProcessors="+Runtime.getRuntime().availableProcessors());var all=new ArrayList<Object>();for(int limit:List.of(255,256))for(String second:List.of("n4096","bad4096")){
   var a=GraphStore.INSTANCE.loadMapped(Path.of(args[0],"n4096"));var b=GraphStore.INSTANCE.loadMapped(Path.of(args[0],second));int count=args.length>2?Integer.parseInt(args[2]):3;var sources=new ArrayList<CypherGraph>();for(int j=0;j<count-1;j++)sources.add(new CypherGraph(j==0?"a":"a"+(j+1),a));sources.add(new CypherGraph("b",b));
   for(Graph g:List.of(a,b))new CypherExecutor(g).execute("MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN DISTINCT n.caller_name AS x LIMIT 1");
   var out=new LinkedHashMap<String,Object>();String q="MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN DISTINCT n.caller_name AS x LIMIT "+limit;out.put("query",q);out.put("second",second);try{var r=new CrossGraphCypherExecutor(sources).execute(q);out.put("columns",r.getColumns());out.put("rows",r.getRows());}catch(Exception x){out.put("errorClass",x.getClass().getSimpleName());out.put("error",x.getMessage());}out.put("a",ExactTupleOracle.state(ExactTupleOracle.field(a,"callSiteStringIndex")));out.put("b",ExactTupleOracle.state(ExactTupleOracle.field(b,"callSiteStringIndex")));all.add(out);((java.io.Closeable)a).close();((java.io.Closeable)b).close();
  }Files.writeString(Path.of(args[1]),ExactTupleOracle.ascii(new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(all))+"\n");
 }
}

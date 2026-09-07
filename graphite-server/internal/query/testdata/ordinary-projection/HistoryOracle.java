import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
public class HistoryOracle {
 static boolean flag(Graph graph,String prefix)throws Exception{for(Method m:graph.getClass().getDeclaredMethods())if(m.getName().startsWith(prefix)){m.setAccessible(true);return (boolean)m.invoke(graph);}throw new IllegalStateException(prefix);}
 static List<Object> state(List<CypherGraph> sources)throws Exception {var values=new ArrayList<Object>();for(var source:sources)values.add(Map.of("id",source.getId(),"retained",flag(source.getGraph(),"isCallSiteStringIndexInitialized"),"mappedView",flag(source.getGraph(),"isMappedCallSiteStringIndexViewInitialized")));return values;}
 static Map<String,Object> run(List<CypherGraph>sources,boolean cross,String query)throws Exception{var out=new LinkedHashMap<String,Object>();out.put("query",query);out.put("before",state(sources));try{var r=cross?new CrossGraphCypherExecutor(sources).execute(query):new CypherExecutor(sources.get(0).getGraph()).execute(query);out.put("columns",r.getColumns());out.put("rows",r.getRows());}catch(Throwable error){out.put("error",error.getClass().getSimpleName());out.put("message",error.getMessage());}out.put("after",state(sources));return out;}
 public static void main(String[]args)throws Exception{
  var json=new GsonBuilder().serializeNulls().setPrettyPrinting().create();var all=new ArrayList<Object>();
  for(String scenario:List.of("scoped-bad","cross-clean-bad","cross-bad-clean","graphscope-bad-clean","graphscope-clean-bad"))for(String warm:List.of("none","distinct","zero-distinct","ordinary")) {
   boolean cross=!scenario.equals("scoped-bad");boolean cleanFirst=scenario.endsWith("clean-bad");
   Graph a=GraphStore.INSTANCE.loadMapped(Path.of(args[0],cleanFirst?"clean":"bad-matched")),b=GraphStore.INSTANCE.loadMapped(Path.of(args[0],cleanFirst?"bad-matched":"clean"));
   var sources=cross?List.of(new CypherGraph("a",a),new CypherGraph("b",b)):List.of(new CypherGraph("single",a));
   var item=new LinkedHashMap<String,Object>();item.put("scenario",scenario);item.put("warm",warm);item.put("initial",state(sources));
   String prefix="MATCH (n) WHERE n.caller_name CONTAINS 'other'",query=prefix+(scenario.startsWith("graphscope")?" AND n.graphId='a'":"")+" RETURN n.caller_name AS x LIMIT 1";
   if(!warm.equals("none")){String warmQuery=warm.equals("ordinary")?query:prefix.replace("'other'",warm.equals("zero-distinct")?"'absent'":"'other'")+" RETURN DISTINCT n.caller_name AS x LIMIT 1";item.put("warmResult",run(sources,cross,warmQuery));}
   var targets=new ArrayList<Object>();for(int repeat=0;repeat<2;repeat++)targets.add(run(sources,cross,query));item.put("targets",targets);all.add(item);
   ((java.io.Closeable)a).close();((java.io.Closeable)b).close();
  }
  Files.writeString(Path.of(args[1]),json.toJson(all)+"\n");
 }
}

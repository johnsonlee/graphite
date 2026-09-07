import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;

/** Synthetic persisted fixtures for correctness only, never performance. */
public class SourceScopeOracle {
 static boolean flag(Graph graph,String prefix)throws Exception {
  for(Method m:graph.getClass().getDeclaredMethods())if(m.getName().startsWith(prefix)){m.setAccessible(true);return(boolean)m.invoke(graph);}
  throw new IllegalStateException(prefix);
 }
 static List<Object> state(List<CypherGraph> sources)throws Exception {
  var out=new ArrayList<Object>();for(var g:sources)out.add(Map.of("id",g.getId(),"retained",flag(g.getGraph(),"isCallSiteStringIndexInitialized"),"mappedView",flag(g.getGraph(),"isMappedCallSiteStringIndexViewInitialized")));return out;
 }
 public static void main(String[]args)throws Exception {
  var gson=new GsonBuilder().serializeNulls().setPrettyPrinting().create();var all=new ArrayList<Object>();
  var queries=new LinkedHashMap<String,String>();
  queries.put("ordinary","MATCH (n) WHERE n.caller_name CONTAINS $term RETURN n.caller_name AS x LIMIT 1");
  queries.put("wrapped","MATCH (n) WHERE toLower(coalesce(n.caller_name,'')) CONTAINS $term RETURN n.caller_name AS x LIMIT 1");
  queries.put("residual","MATCH (n:CallSiteNode) WHERE n.caller_name CONTAINS $term AND n.id>=0 RETURN [n.caller_name] AS x LIMIT 1");
  queries.put("distinct","MATCH (n) WHERE n.caller_name CONTAINS $term RETURN DISTINCT n.caller_name AS x LIMIT 1");
  queries.put("generic","MATCH (n:CallSiteNode) WHERE toString(toString(n.caller_name)) CONTAINS $term RETURN [n.caller_name] AS x LIMIT 1");
  queries.put("ordered","MATCH (n) WHERE n.caller_name CONTAINS $term RETURN n.caller_name AS x ORDER BY x LIMIT 1");
  queries.put("route","MATCH (n) WHERE n.graphId='g0' AND n.caller_name CONTAINS $term RETURN n.caller_name AS x LIMIT 1");
  queries.put("and-route","MATCH (n) WHERE (n.graphId='g0' OR n.graphId='missing') AND n.caller_name CONTAINS $term RETURN n.caller_name AS x ORDER BY x LIMIT 1");
  for(int count:new int[]{1,2,8,40,64})for(boolean scoped:new boolean[]{false,true})for(boolean badLast:new boolean[]{false,true})for(var q:queries.entrySet()) {
   var sources=new ArrayList<CypherGraph>();var record=new LinkedHashMap<String,Object>();record.put("count",count);record.put("scoped",scoped);record.put("badLast",badLast);record.put("name",q.getKey());record.put("query",q.getValue());record.put("parameters",Map.of("term","other"));
   try {
    for(int i=0;i<count;i++)sources.add(new CypherGraph("g"+i,GraphStore.INSTANCE.loadMapped(Path.of(args[0],badLast&&i==count-1?"bad-matched":"clean"))));
    var repeats=new ArrayList<Object>();record.put("repeats",repeats);
    for(int repeat=0;repeat<2;repeat++) {
     var r=new LinkedHashMap<String,Object>();r.put("before",state(sources));
     try {
      var context=new CypherExecutionContext(new CypherExecutionBudget(Long.MAX_VALUE),new CypherCancellationSignal());
      var result=new CrossGraphCypherExecutor(sources,context,scoped).execute(q.getValue(),Map.of("term","other"));
      r.put("columns",result.getColumns());r.put("rows",result.getRows());
     }catch(Throwable error){r.put("error",error.getClass().getSimpleName());r.put("message",error.getMessage());}
     r.put("after",state(sources));repeats.add(r);
    }
   }finally {for(var source:sources)((java.io.Closeable)source.getGraph()).close();}
   all.add(record);Files.writeString(Path.of(args[1]),gson.toJson(all)+"\n");
  }
  System.out.println("Captured "+all.size()+" scenarios / "+all.size()*2+" complete responses and source histories; no performance measurement");
 }
}

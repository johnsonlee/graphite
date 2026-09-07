import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Actual main lifecycle operations on copied tiny correctness fixtures. */
public class IndexLifecycleOracle {
 static Object invoke(Graph g,String prefix)throws Throwable {
  for(Method m:g.getClass().getDeclaredMethods())if(m.getName().startsWith(prefix)&&m.getParameterCount()==0) {
   m.setAccessible(true);try{return m.invoke(g);}catch(InvocationTargetException e){throw e.getCause();}
  }
  throw new IllegalStateException("missing "+prefix);
 }
 static Object prepare(Graph g)throws Throwable {
  for(Method m:g.getClass().getDeclaredMethods())if(m.getName().startsWith("prepareCallSiteStringIndex")&&m.getName().endsWith("$default")) {
   m.setAccessible(true);try{return m.invoke(null,g,null,1,null);}catch(InvocationTargetException e){throw e.getCause();}
  }
  throw new IllegalStateException("missing default preparation method");
 }
 static Map<String,Object> state(Graph g,Path dir)throws Throwable {
  var s=new LinkedHashMap<String,Object>();
  for(var e:Map.of("retained","isCallSiteStringIndexInitialized","mappedView","isMappedCallSiteStringIndexViewInitialized","trigrams","isCallSiteTrigramIndexInitialized","loadedFromPersistence","isCallSiteStringIndexLoadedFromPersistence","rawMatchCount","rawStringMatchStateCount","rawProjectionCount","rawProjectionMatchCount","mappedRangeCount","mappedPostingRangeValidationCount").entrySet())s.put(e.getKey(),invoke(g,e.getValue()));
  s.put("indexFile",indexFile(dir));return s;
 }
 static Object indexFile(Path dir)throws Exception {
  Path file=dir.resolve("graph.callsite-string-index");
  if(!Files.isRegularFile(file))return null;
  byte[] data=Files.readAllBytes(file);return Map.of("bytes",data.length,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
 }
 static void error(Map<String,Object> record,Throwable e){record.put("error",e.getClass().getSimpleName());record.put("message",e.getMessage());}
 public static void main(String[]args)throws Throwable {
  var gson=new GsonBuilder().serializeNulls().setPrettyPrinting().create();var all=new ArrayList<Object>();
  String property="graphite.webgraph.prepareCallSiteStringIndexOnLoad";String previous=System.getProperty(property);
  try {
   for(boolean startup:new boolean[]{false,true})for(String fixture:new String[]{"clean","missing","bad-matched","bad-missing","corrupt"}) {
    System.setProperty(property,startup?"true":"lazy");
    Path dir=Path.of(args[0],(startup?"startup-":"lazy-")+fixture);
    var record=new LinkedHashMap<String,Object>();record.put("startup",startup);record.put("fixture",fixture);record.put("initialIndexFile",indexFile(dir));
    Graph graph=null;
    try {
     graph=GraphStore.INSTANCE.loadMapped(dir);record.put("loaded",state(graph,dir));
     var steps=new ArrayList<Object>();record.put("steps",steps);
     for(String action:new String[]{"query","clear","query","prepare","clear","query","clear"}) {
      var step=new LinkedHashMap<String,Object>();step.put("action",action);step.put("before",state(graph,dir));
      try {
       if(action.equals("clear"))invoke(graph,"clearStringPropertyIndexes");
       else if(action.equals("prepare"))step.put("prepared",prepare(graph));
       else {
        var context=new CypherExecutionContext(new CypherExecutionBudget(Long.MAX_VALUE),new CypherCancellationSignal());
        var result=new CrossGraphCypherExecutor(List.of(new CypherGraph("g",graph)),context,true).execute("MATCH (n) WHERE n.caller_name CONTAINS $term RETURN n.caller_name AS x LIMIT 1",Map.of("term","other"));
        step.put("columns",result.getColumns());step.put("rows",result.getRows());
       }
      }catch(Throwable e){error(step,e);}
      step.put("after",state(graph,dir));steps.add(step);
     }
    }catch(Throwable e){error(record,e);}
    finally {if(graph!=null)((java.io.Closeable)graph).close();}
    record.put("finalIndexFile",indexFile(dir));all.add(record);
   }
  }finally {if(previous==null)System.clearProperty(property);else System.setProperty(property,previous);}
  Files.writeString(Path.of(args[1]),gson.toJson(all)+"\n");System.out.println("Captured10 actual-main lifecycle scenarios; correctness only");
 }
}

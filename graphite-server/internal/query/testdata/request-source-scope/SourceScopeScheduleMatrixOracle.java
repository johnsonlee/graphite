import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;

/** Force an allowed sibling-cancellation schedule; never cancel from the hook. */
public class SourceScopeScheduleMatrixOracle {
 static void interfaces(Class<?> type,Set<Class<?>> out) {
  if(type==null)return;for(Class<?> i:type.getInterfaces()){out.add(i);interfaces(i,out);}interfaces(type.getSuperclass(),out);
 }
 static Object call(Method m,Object owner,Object[] args)throws Throwable {try{return m.invoke(owner,args);}catch(InvocationTargetException e){throw e.getCause();}}
 static boolean flag(Graph g,String prefix)throws Exception {for(Method m:g.getClass().getDeclaredMethods())if(m.getName().startsWith(prefix)){m.setAccessible(true);return(boolean)m.invoke(g);}throw new IllegalStateException(prefix);}
 public static void main(String[]args)throws Exception {
  var all=new ArrayList<Object>();var gson=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
  for(int count:new int[]{40,64})for(boolean active:new boolean[]{false,true})for(int shape=0;shape<3;shape++) {
   boolean scoped=shape!=0;int waveStart=count-8;int last=count-1;
   var originals=new ArrayList<Graph>();var sources=new ArrayList<CypherGraph>();var events=new ConcurrentLinkedQueue<String>();var ready=new CountDownLatch(7);var never=new CountDownLatch(1);
   String q=shape==2?"MATCH (n) WHERE (n.graphId='g0' OR n.graphId='missing') AND n.caller_name CONTAINS $term RETURN n.caller_name AS x ORDER BY x LIMIT 1":"MATCH (n) WHERE n.caller_name CONTAINS $term RETURN n.caller_name AS x ORDER BY x LIMIT 1";
   var record=new LinkedHashMap<String,Object>();record.put("count",count);record.put("shape",shape);record.put("active",active);record.put("scoped",scoped);record.put("query",q);
   try {
    for(int i=0;i<count;i++) {
     Graph original=GraphStore.INSTANCE.loadMapped(Path.of(args[0],i==last?"bad-matched":"clean"));originals.add(original);int id=i;
     var capabilities=new LinkedHashSet<Class<?>>();interfaces(original.getClass(),capabilities);
     Graph proxy=(Graph)Proxy.newProxyInstance(Graph.class.getClassLoader(),capabilities.toArray(Class<?>[]::new),(p,m,a)->{
      if(active&&m.getName().equals("nodesByStringPropertyDisjunction")&&id>=waveStart) {
       if(id<last) {
        events.add("entered:"+id);ready.countDown();
        try {if(never.await(20,TimeUnit.SECONDS))throw new AssertionError("hook latch unexpectedly released");throw new AssertionError("watchdog timeout");}
        catch(InterruptedException interrupted){events.add("main-interrupted:"+id);Thread.currentThread().interrupt();throw new CypherQueryCancelledException();}
       } else {if(!ready.await(20,TimeUnit.SECONDS))throw new AssertionError("siblings did not enter");events.add("original-bad-source-called:"+id);}
      }
      return call(m,original,a);
     });
     sources.add(new CypherGraph("g"+i,proxy));
    }
    try {var context=new CypherExecutionContext(new CypherExecutionBudget(Long.MAX_VALUE),new CypherCancellationSignal());var result=new CrossGraphCypherExecutor(sources,context,scoped).execute(q,Map.of("term","other"));record.put("columns",result.getColumns());record.put("rows",result.getRows());}
    catch(Throwable e){record.put("error",e.getClass().getSimpleName());record.put("message",e.getMessage());}
    var state=new ArrayList<Object>();for(int i=0;i<originals.size();i++)state.add(Map.of("id","g"+i,"retained",flag(originals.get(i),"isCallSiteStringIndexInitialized"),"mappedView",flag(originals.get(i),"isMappedCallSiteStringIndexViewInitialized")));record.put("after",state);record.put("events",new ArrayList<>(events));record.put("parentInterrupted",Thread.currentThread().isInterrupted());
   }finally{for(Graph g:originals)((java.io.Closeable)g).close();}
   all.add(record);
  }
  Files.writeString(Path.of(args[1]),gson.toJson(all)+"\n");System.out.println("Captured12 actual-main scheduling controls; hooks did not cancel or mutate cache/results");
 }
}

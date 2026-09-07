import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CancellationException;
public class CacheLifecycleOracle {
 static Object field(Object o,String name)throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 static Object call(Object o,String name,Class<?>[] types,Object...args)throws Exception {try{return o.getClass().getMethod(name,types).invoke(o,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw(Exception)e.getCause();throw e;}}
 static Map<String,Object> state(Object i)throws Exception {var out=new LinkedHashMap<String,Object>();out.put("bytes",call(i,"getRetainedBytes",new Class<?>[]{}));out.put("serial",call(i,"getPrefersSerialScan",new Class<?>[]{}));for(String cache:List.of("matchingStringIds","matchingNodeIds","projectedRows")){Map<?,?>m=(Map<?,?>)field(i,cache);out.put(cache,m.keySet().stream().map(Object::toString).toList());}return out;}
 static Object project(Object i,List<StringPropertyPredicate> ps,List<String> props,int limit,GraphWorkConsumer w)throws Exception{return call(i,"projectRows",new Class<?>[]{List.class,List.class,int.class,GraphWorkConsumer.class},ps,props,limit,w);}
 public static void main(String[]args)throws Exception {
  Graph graph=GraphStore.INSTANCE.loadMapped(Path.of(args[0]));var logs=new ArrayList<Map<String,Object>>();
  new CypherExecutor(graph).execute("MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN DISTINCT n.caller_name AS x LIMIT 1");
  Object index=field(graph,"callSiteStringIndex");call(index,"clearQueryCaches",new Class<?>[]{});logs.add(new LinkedHashMap<>(Map.of("phase","structural", "state",state(index))));
  var ps=List.of(new StringPropertyPredicate("caller_name",null,StringMatchMode.CONTAINS,"other"));
  kotlin.sequences.Sequence<?> ids=(kotlin.sequences.Sequence<?>)call(index,"matchingNodeIds",new Class<?>[]{List.class,GraphWorkConsumer.class,int.class},ps,null,2);
  var it=ids.iterator();while(it.hasNext())it.next();logs.add(new LinkedHashMap<>(Map.of("phase","prime-node-cache", "state",state(index))));
  project(index,ps,List.of("caller_name","caller_name","caller_name","caller_name"),2,()->Thread.currentThread().interrupt());
  boolean interrupted=Thread.interrupted();logs.add(new LinkedHashMap<>(Map.of("phase","interrupt-without-throw-on-node-hit", "interrupted",interrupted,"state",state(index))));
  project(index,ps,List.of("callee_name"),2,null);logs.add(new LinkedHashMap<>(Map.of("phase","second-projection", "state",state(index))));
  var error=new LinkedHashMap<String,Object>();error.put("phase","throw-on-cached-projection-hit");try{project(index,ps,List.of("caller_name","caller_name","caller_name","caller_name"),2,()->{throw new CancellationException("AUDIT_CANCEL");});}catch(Exception e){error.put("error",e.getClass().getSimpleName());error.put("message",e.getMessage());}error.put("state",state(index));logs.add(error);
  project(index,ps,List.of("caller_class"),0,()->{throw new AssertionError("zero-limit consumed");});logs.add(new LinkedHashMap<>(Map.of("phase","zero-limit", "state",state(index))));
  project(index,List.of(new StringPropertyPredicate("caller_name",null,StringMatchMode.EQUALS,"absent")),List.of("callee_name"),2,null);logs.add(new LinkedHashMap<>(Map.of("phase","zero-hit", "state",state(index))));
  call(index,"clearQueryCaches",new Class<?>[]{});logs.add(new LinkedHashMap<>(Map.of("phase","clear", "state",state(index))));
  ((java.io.Closeable)graph).close();logs.add(new LinkedHashMap<>(Map.of("phase","close", "indexCleared",field(graph,"callSiteStringIndex")==null)));
  Files.writeString(Path.of(args[1]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(logs)+"\n");
 }
}

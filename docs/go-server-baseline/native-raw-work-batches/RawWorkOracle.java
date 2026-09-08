import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.Graph;
import io.johnsonlee.graphite.webgraph.GraphStore;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual main operations, without substituting tracker or executor implementations. */
public final class RawWorkOracle {
 static final Gson JSON=new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create();
 static final String REV="4e328b0109e13c896b74004823fb049fcb19251a";
 static Object invoke(Object target,String name,Class<?>[] types,Object... args)throws Throwable {
  try{Method method=target.getClass().getMethod(name,types);method.setAccessible(true);return method.invoke(target,args);}
  catch(InvocationTargetException e){throw e.getCause();}
 }
 static Map<String,Object> reason(Throwable t) {
  Map<String,Object> result=new LinkedHashMap<>();result.put("error",t.getClass().getSimpleName());result.put("errorClass",t.getClass().getName());result.put("message",t.getMessage());
  if(t instanceof CypherQueryTimeoutException)result.put("timeoutMillis",Long.toString(((CypherQueryTimeoutException)t).getTimeoutMillis()));
  if(t instanceof CypherBudgetExceededException)result.put("maxWorkUnits",Long.toString(((CypherBudgetExceededException)t).getMaxWorkUnits()));
  return result;
 }
 static void failure(Map<String,Object> result,Throwable t) {
  while(t instanceof InvocationTargetException)t=((InvocationTargetException)t).getCause();
  result.put("outcome","FAILED");result.putAll(reason(t));result.put("stack",Arrays.stream(t.getStackTrace()).map(Object::toString).toList());
 }
 static final class State {
  String mode;long maximum;CypherExecutionBudget budget;CypherCancellationSignal signal;CypherExecutionContext context;Object tracker;Graph graph;Object executor;List<CypherGraph> sources=new ArrayList<>();CypherQueryCancelledException firstReason;
  Map<String,Object> snapshot() {
   Map<String,Object> r=new LinkedHashMap<>();
   if(context==null){r.put("diagnostics",null);r.put("diagnosticsAvailability",mode.equals("budget-only")?"not exposed by budget-only constructor":"no observable request context");r.put("remaining",null);r.put("cancelled",null);r.put("cancellationReason",null);return r;}
   CypherExecutionDiagnostics d=context.getDiagnostics();r.put("diagnostics",d);r.put("remaining",Long.toString(maximum-d.getWorkUnitsConsumed()));r.put("cancelled",signal.isCancelled());r.put("cancellationReason",reason(signal.cancellationException()));r.put("storage",storage());return r;
  }
  List<Object> storage() {
   List<Object> out=new ArrayList<>();
   for(var source:sources){Map<String,Object> row=new LinkedHashMap<>();row.put("graphId",source.getId());
    for(String prefix:List.of("isCallSiteStringIndexInitialized","isMappedCallSiteStringIndexViewInitialized","rawStringMatchStateCount","rawProjectionMatchCount","callSiteParallelScanCount","callSiteStringLookupEntryCount","callSiteStringIndexLookupCount")){
     for(Method m:source.getGraph().getClass().getDeclaredMethods())if(m.getName().startsWith(prefix)&&m.getParameterCount()==0){try{m.setAccessible(true);row.put(prefix,m.invoke(source.getGraph()));}catch(Exception e){throw new RuntimeException(e);}}
    }out.add(row);
   }return out;
  }
  Object freshExecutor() {
   if(sources.size()>1)return new CrossGraphCypherExecutor(sources,context,false);
   if(mode.equals("budget-only"))return new CypherExecutor(graph,budget);
   if(mode.equals("unbudgeted"))return new CypherExecutor(graph);
   return new CypherExecutor(graph,context);
  }
 }
 static Object operation(State s,JsonObject op)throws Throwable {
  switch(op.get("op").getAsString()) {
   case "consume":invoke(s.tracker,"consume",new Class<?>[]{long.class},Long.parseLong(op.get("units").getAsString()));return null;
   case "check":invoke(s.tracker,"checkCancelled",new Class<?>[]{});return null;
   case "fast":invoke(s.tracker,"recordFastPath",new Class<?>[]{});return null;
   case "filtered":invoke(s.tracker,"recordFilteredNodeLimitFastPath",new Class<?>[]{});return null;
   case "fallback":invoke(s.tracker,"recordGeneralFallback",new Class<?>[]{});return null;
   case "selection":invoke(s.tracker,"recordGraphIdSourceSelection",new Class<?>[]{int.class,int.class,boolean.class},op.get("initial").getAsInt(),op.get("selected").getAsInt(),op.get("conflicting").getAsBoolean());return null;
   case "cancel": {
    String kind=op.get("kind").getAsString();boolean accepted;
    if(kind.equals("default"))accepted=s.signal.cancel();
    else if(kind.equals("timeout"))accepted=s.signal.cancel(new CypherQueryTimeoutException(Long.parseLong(op.get("timeoutMillis").getAsString())));
    else accepted=s.signal.cancel(new CypherQueryCancelledException(op.get("message").getAsString()));
    if(accepted)s.firstReason=s.signal.cancellationException();
    return Map.of("accepted",accepted,"firstReasonPreserved",s.signal.cancellationException()==s.firstReason);
   }
   case "reason": {
    Map<String,Object> r=new LinkedHashMap<>(reason(s.signal.cancellationException()));r.put("sameAsFirstCancellationReason",s.firstReason!=null&&s.signal.cancellationException()==s.firstReason);return r;
   }
   case "execute": {
    if(s.executor==null||op.has("freshExecutor")&&op.get("freshExecutor").getAsBoolean())s.executor=s.freshExecutor();
    @SuppressWarnings("unchecked") Map<String,Object> parameters=JSON.fromJson(op.get("parameters"),Map.class);
    CypherResult result=(CypherResult)(op.has("maxRows")?invoke(s.executor,"execute",new Class<?>[]{String.class,Map.class,int.class},op.get("query").getAsString(),parameters,op.get("maxRows").getAsInt()):invoke(s.executor,"execute",new Class<?>[]{String.class,Map.class},op.get("query").getAsString(),parameters));
    Map<String,Object> r=new LinkedHashMap<>();r.put("columns",result.getColumns());r.put("rows",result.getRows());return r;
   }
   case "concurrentConsume": {
    if(s.maximum>1000)throw new IllegalArgumentException("oracle concurrency bounded to 1000 units");
    int count=op.get("workers").getAsInt();ExecutorService pool=Executors.newFixedThreadPool(count);CountDownLatch start=new CountDownLatch(1);AtomicInteger successful=new AtomicInteger();List<Future<Map<String,Object>>> tasks=new ArrayList<>();
    try {
     for(int i=0;i<count;i++){final int worker=i;tasks.add(pool.submit(()->{
      Map<String,Object> r=new LinkedHashMap<>();r.put("worker",worker);int local=0;start.await();
      try{for(int j=0;j<=s.maximum;j++){invoke(s.tracker,"consume",new Class<?>[]{});local++;successful.incrementAndGet();}throw new IllegalStateException("worker failed to exhaust finite tracker");}
      catch(Throwable t){failure(r,t);}
      r.put("successfulConsumes",local);return r;
     }));}
     start.countDown();List<Object> workers=new ArrayList<>();int failures=0;
     for(var task:tasks){var r=task.get(10,TimeUnit.SECONDS);workers.add(r);if("CypherBudgetExceededException".equals(r.get("error")))failures++;}
     return Map.of("successfulConsumes",successful.get(),"budgetExceededWorkers",failures,"workers",workers);
    }finally{pool.shutdownNow();if(!pool.awaitTermination(10,TimeUnit.SECONDS))throw new IllegalStateException("tracker workers did not terminate");}
   }
   default:throw new IllegalArgumentException(op.toString());
  }
 }
 public static void main(String[] args)throws Throwable {
  System.setProperty("graphite.webgraph.prepareCallSiteStringIndexOnLoad","lazy");
  if(!System.getProperty("java.specification.version").equals("17"))throw new IllegalStateException("Java17 required");
  JsonArray cases=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();List<Object> records=new ArrayList<>();
  for(var raw:cases){JsonObject spec=raw.getAsJsonObject();State s=new State();s.mode=spec.get("mode").getAsString();s.maximum=Long.parseLong(spec.get("budget").getAsString());Map<String,Object> record=new LinkedHashMap<>();record.put("name",spec.get("name").getAsString());record.put("spec",spec);Map<String,Object> construction=new LinkedHashMap<>();record.put("construction",construction);List<Object> operations=new ArrayList<>();record.put("operations",operations);
   try {
    s.budget=new CypherExecutionBudget(s.maximum);
    if(!s.mode.equals("budget-only")&&!s.mode.equals("unbudgeted")){
     s.signal=new CypherCancellationSignal();s.context=new CypherExecutionContext(s.budget,s.signal);
     Method getter=Arrays.stream(CypherExecutionContext.class.getDeclaredMethods()).filter(m->m.getName().startsWith("getWorkTracker$")&&m.getParameterCount()==0).findFirst().orElseThrow();getter.setAccessible(true);s.tracker=getter.invoke(s.context);
    }
    construction.put("outcome","SUCCESS");construction.put("maxWorkUnits",Long.toString(s.budget.getMaxWorkUnits()));construction.put("after",s.snapshot());
    if(spec.has("sources")){int i=0;for(var source:spec.getAsJsonArray("sources")){s.sources.add(new CypherGraph(source.getAsJsonObject().get("graphId").getAsString(),GraphStore.INSTANCE.loadMapped(Path.of(args[1],spec.get("name").getAsString(),"store"+i++))));}s.graph=s.sources.get(0).getGraph();}
    for(var input:spec.getAsJsonArray("operations")){JsonObject op=input.getAsJsonObject();Map<String,Object> r=new LinkedHashMap<>();r.put("spec",op);r.put("before",s.snapshot());
     try{r.put("value",operation(s,op));r.put("outcome","SUCCESS");}catch(Throwable t){failure(r,t);}
     r.put("after",s.snapshot());operations.add(r);
    }
   }catch(Throwable t){failure(construction,t);construction.put("after",s.snapshot());}
   finally{for(var source:s.sources)((java.io.Closeable)source.getGraph()).close();}
   record.put("final",s.snapshot());records.add(record);
  }
  Map<String,Object> out=new LinkedHashMap<>();out.put("mainRevision",REV);out.put("javaVersion",System.getProperty("java.version"));out.put("performanceMeasurements",0);out.put("cases",records);
  Files.writeString(Path.of(args[2]),JSON.toJson(out)+"\n",StandardOpenOption.CREATE_NEW);
 }
}

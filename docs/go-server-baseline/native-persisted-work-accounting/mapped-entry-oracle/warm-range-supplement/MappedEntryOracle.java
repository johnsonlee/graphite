import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.core.CallSiteNode;
import io.johnsonlee.graphite.webgraph.GraphStore;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import kotlin.sequences.Sequence;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Actual storage API and actual QueryPipeline consumer factory; not a Cypher query oracle. */
public final class MappedEntryOracle {
 static final Gson JSON=new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create();
 static Object field(Object o,String name){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
 static Method method(Class<?> c,String name,int count){return Arrays.stream(c.getDeclaredMethods()).filter(m->m.getName().startsWith(name)&&m.getParameterCount()==count).peek(m->m.setAccessible(true)).findFirst().orElseThrow();}
 static Object call(Object o,Method m,Object...a)throws Throwable{try{return m.invoke(o,a);}catch(InvocationTargetException e){throw e.getCause();}}
 static Map<String,Object> error(Throwable t){Map<String,Object> r=new LinkedHashMap<>();r.put("errorClass",t.getClass().getName());r.put("message",t.getMessage());r.put("stack",Arrays.stream(t.getStackTrace()).map(Object::toString).toList());return r;}
 static Object storage(Object graph)throws Throwable {
  Map<String,Object> s=new LinkedHashMap<>();
  for(String name:List.of("isCallSiteStringIndexInitialized","isMappedCallSiteStringIndexViewInitialized","mappedPostingRangeValidationCount","rawStringMatchStateCount","rawProjectionMatchCount","callSiteParallelScanCount","callSiteStringLookupEntryCount","callSiteStringIndexLookupCount","isCallSiteTrigramIndexInitialized","isCallSiteStringIndexLoadedFromPersistence"))s.put(name,call(graph,method(graph.getClass(),name,0)));
  s.put("mappedViewUnavailable",field(graph,"mappedCallSiteStringIndexViewUnavailable"));s.put("retainedPreference",((java.util.concurrent.atomic.AtomicBoolean)field(graph,"retainPersistedCallSiteStringIndex")).get());
  Object index=field(graph,"callSiteStringIndex");for(String name:List.of("matchingStringIds","matchingNodeIds","projectedRows"))s.put(name+"Count",index==null?null:((Map<?,?>)field(index,name)).size());return s;
 }
 static class Request {
  CypherExecutionContext context;CypherCancellationSignal signal=new CypherCancellationSignal();GraphWorkBatchConsumer tracker;Object trackerObject;String phase="initial";String action;int callbackNumber;Throwable callbackFailure;CypherQueryCancelledException cause=new CypherQueryCancelledException("mapped-entry callback cancellation");List<Object> callbacks=new ArrayList<>();
  Request(long budget,String action)throws Throwable {this.action=action;context=new CypherExecutionContext(new CypherExecutionBudget(budget),signal);trackerObject=call(context,method(context.getClass(),"getWorkTracker$",0));tracker=(GraphWorkBatchConsumer)trackerObject;}
  Map<String,Object> snapshot(){Map<String,Object> s=new LinkedHashMap<>();s.put("diagnostics",context.getDiagnostics());s.put("remaining",Long.toString(((AtomicLong)field(trackerObject,"remaining")).get()));s.put("signalCancelled",signal.isCancelled());s.put("signalReason",signal.isCancelled()?error(signal.cancellationException()):null);s.put("threadInterrupted",Thread.currentThread().isInterrupted());return s;}
  GraphWorkConsumer consumer(boolean persisted)throws Throwable {
   Function1<Long,Unit> delegate=n->{Map<String,Object> r=new LinkedHashMap<>();r.put("phase",phase);r.put("ordinal",++callbackNumber);r.put("units",n);r.put("before",snapshot());callbacks.add(r);
    try{tracker.consume(n);if(callbackNumber==1){if(action.equals("interrupt-after-first"))Thread.currentThread().interrupt();if(action.equals("signal-after-first"))r.put("signalCancelAccepted",signal.cancel(cause));}r.put("outcome","SUCCESS");}
    catch(Throwable t){callbackFailure=t;r.put("outcome","FAILED");r.putAll(error(t));throw t;}finally{r.put("after",snapshot());}return Unit.INSTANCE;};
   Object value=call(null,method(Class.forName("io.johnsonlee.graphite.cypher.QueryPipelineKt"),"directStringStorageWorkConsumer",7),40,Runtime.getRuntime().availableProcessors(),null,persisted,false,!persisted,delegate);return (GraphWorkConsumer)value;
  }
 }
 interface Action {Object run()throws Throwable;}
 static Object step(Request r,Object graph,List<Object> steps,String phase,Action action)throws Throwable {
  r.phase=phase;Map<String,Object> row=new LinkedHashMap<>();row.put("phase",phase);row.put("before",r.snapshot());row.put("storageBefore",storage(graph));steps.add(row);
  try{Object value=action.run();row.put("outcome","SUCCESS");row.put("value",value);return value;}catch(Throwable t){row.put("outcome","FAILED");row.putAll(error(t));row.put("sameAsCallbackFailure",t==r.callbackFailure);row.put("sameAsSignalReason",r.signal.isCancelled()&&t==r.signal.cancellationException());throw t;}finally{row.put("after",r.snapshot());row.put("storageAfter",storage(graph));}
 }
 static Map<String,Object> lookup(Object graph,String term,int limit,long budget,boolean persisted,boolean interrupt,String action)throws Throwable {
  Thread.interrupted();Request request=new Request(budget,action);GraphWorkConsumer consumer=request.consumer(persisted);Map<String,Object> record=new LinkedHashMap<>();record.put("term",term);record.put("limit",limit);record.put("budget",Long.toString(budget));record.put("preInterrupted",interrupt);record.put("callbackAction",action);record.put("consumerClass",consumer.getClass().getName());record.put("preferredMapped",consumer instanceof PreferredMappedStringIndexViewGraphWorkBatchConsumer);record.put("split",consumer instanceof SplitGraphWorkBatchConsumer);record.put("segmentWorkerCount",consumer instanceof SplitGraphWorkBatchConsumer?((SplitGraphWorkBatchConsumer)consumer).getSegmentWorkerCount():null);record.put("factorySourceCount",40);List<Object> steps=new ArrayList<>();record.put("steps",steps);record.put("callbacks",request.callbacks);record.put("before",request.snapshot());record.put("storageBefore",storage(graph));if(interrupt)Thread.currentThread().interrupt();
  try {
   List<StringPropertyPredicate> predicates=List.of(new StringPropertyPredicate("caller_name",null,StringMatchMode.CONTAINS,term));final Object[] holder=new Object[1];
   step(request,graph,steps,"sequence-construction",()->{holder[0]=call(graph,method(graph.getClass(),"nodesByStringPropertyDisjunction",4),CallSiteNode.class,predicates,limit,consumer);return Map.of("sequenceReturned",holder[0]!=null);});
   if(holder[0]!=null){final Iterator<?>[] iterator=new Iterator<?>[1];step(request,graph,steps,"iterator-construction",()->{iterator[0]=((Sequence<?>)holder[0]).iterator();return Map.of("iteratorReturned",true);});
    for(int at=0;at<8;at++){boolean more=(boolean)step(request,graph,steps,"hasNext-"+at,()->iterator[0].hasNext());if(!more)break;step(request,graph,steps,"next-"+at,()->{Object node=iterator[0].next();return Map.of("nodeClass",node.getClass().getName(),"nodeId",field(node,"id"),"node",JSON.toJsonTree(node));});if(at==7)throw new IllegalStateException("bounded fixture exceeded8 results");}
   }
   record.put("outcome","SUCCESS");
  }catch(Throwable t){record.put("outcome","FAILED");record.putAll(error(t));record.put("sameAsCallbackFailure",t==request.callbackFailure);record.put("sameAsSignalReason",request.signal.isCancelled()&&t==request.signal.cancellationException());}
  finally{record.put("after",request.snapshot());record.put("storageAfter",storage(graph));record.put("clearedThreadInterruptedForIsolation",Thread.interrupted());}return record;
 }
 public static void main(String[] args)throws Throwable {
  System.setProperty("graphite.webgraph.prepareCallSiteStringIndexOnLoad","lazy");JsonArray specs=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();List<Object> records=new ArrayList<>();
  for(JsonElement item:specs){JsonObject spec=item.getAsJsonObject();String name=spec.get("name").getAsString();Map<String,Object> record=new LinkedHashMap<>();record.put("spec",spec);Object graph=GraphStore.INSTANCE.loadMapped(Path.of(args[1],name));
   try {String warmup=spec.get("warmup").getAsString();if(!warmup.equals("none"))record.put("warmup",lookup(graph,warmup.endsWith("-hit")?"hit":"absentzz",warmup.endsWith("-hit")?2:1,1000000,warmup.startsWith("retained"),false,"none"));
    record.put("probe",lookup(graph,spec.get("term").getAsString(),spec.get("limit").getAsInt(),spec.get("budget").getAsLong(),false,spec.get("preInterrupted").getAsBoolean(),spec.get("action").getAsString()));record.put("beforeClose",storage(graph));
   }finally{Thread.interrupted();((java.io.Closeable)graph).close();}record.put("afterClose",storage(graph));records.add(record);
  }
  Files.writeString(Path.of(args[2]),JSON.toJson(Map.of("scope","actual public storage method and actual main consumer factory; not public Cypher or benchmark","mainRevision","4e328b0109e13c896b74004823fb049fcb19251a","javaVersion",System.getProperty("java.version"),"performanceMeasurements",0,"cases",records))+"\n",StandardOpenOption.CREATE_NEW);
 }
}

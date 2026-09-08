import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.webgraph.*;
import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Uses the original benchmark setup/replay. Observation overhead invalidates timings. */
public final class MainReplayCapture {
 static Object field(Object owner,String name)throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
 static void field(Object owner,String name,Object value)throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
 static Object invoke(Object owner,String name,Object...args)throws Exception {
  for(Method m:owner.getClass().getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){m.setAccessible(true);try{return m.invoke(owner,args);}catch(InvocationTargetException e){throw new RuntimeException(e.getCause());}}
  throw new IllegalStateException(name);
 }
 static final class Observer extends AbstractExecutorService {
  final ExecutorService delegate; final Object benchmark;final List<?> cases;final List<CypherGraph> sources;
  final BufferedWriter output;final Gson gson=new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
  final Map<String,Method> stateMethods=new LinkedHashMap<>();int completed;String phase="warmup";
  Observer(ExecutorService delegate,Object benchmark,BufferedWriter output)throws Exception {
   this.delegate=delegate;this.benchmark=benchmark;this.output=output;
   this.cases=(List<?>)field(benchmark,"workload");this.sources=(List<CypherGraph>)field(benchmark,"sources");
   for(var item:Map.of("retained","isCallSiteStringIndexInitialized","mappedView","isMappedCallSiteStringIndexViewInitialized","trigrams","isCallSiteTrigramIndexInitialized","loadedFromPersistence","isCallSiteStringIndexLoadedFromPersistence","mappedRangeCount","mappedPostingRangeValidationCount","rawMatchCount","rawStringMatchStateCount","rawProjectionCount","rawProjectionMatchCount").entrySet()) {
    Method selected=null;
    for(Method m:sources.get(0).getGraph().getClass().getDeclaredMethods())if(m.getName().startsWith(item.getValue())&&m.getParameterCount()==0){if(selected!=null)throw new IllegalStateException("ambiguous state method");selected=m;}
    if(selected==null)throw new IllegalStateException(item.getValue());selected.setAccessible(true);stateMethods.put(item.getKey(),selected);
   }
  }
  List<Object> states()throws Exception {
   var states=new ArrayList<Object>();
   for(CypherGraph source:sources){var state=new LinkedHashMap<String,Object>();state.put("id",source.getId());for(var m:stateMethods.entrySet())state.put(m.getKey(),m.getValue().invoke(source.getGraph()));states.add(state);}return states;
  }
  synchronized void emit(Map<String,Object> record){try{output.write(gson.toJson(record));output.newLine();output.flush();}catch(IOException e){throw new UncheckedIOException(e);}}
  void result(Object value,Throwable failure,List<Object> before) {
   // The original timeout join barrier returns Boolean, not CypherResult.
   if(failure==null&&!(value instanceof CypherResult))return;
   try {
    int index=completed%cases.size();Object c=cases.get(index);
    var r=new LinkedHashMap<String,Object>();r.put("kind","case");r.put("phase",phase);r.put("index",index);r.put("id",field(c,"id"));r.put("before",before);
    if(failure==null){CypherResult result=(CypherResult)value;r.put("columns",result.getColumns());r.put("rows",result.getRows());r.put("canonical",new String((byte[])invoke(benchmark,"canonicalResult",result),StandardCharsets.UTF_8));}
    else {Throwable cause=failure instanceof ExecutionException?failure.getCause():failure;r.put("error",cause.getClass().getSimpleName());r.put("errorClass",cause.getClass().getName());r.put("message",cause.getMessage());}
    // Timeout cancellation/join is owned by the original replay, after get throws.
    r.put("after",failure instanceof TimeoutException?null:states());emit(r);completed++;
    System.out.println(phase+" "+(index+1)+"/"+cases.size()+" "+field(c,"id")+" "+(failure==null?"success":failure.getClass().getSimpleName()));
   }catch(Exception e){throw new IllegalStateException("capture failed",e);}
  }
  @Override public <T> Future<T> submit(Callable<T> task) {
   final List<Object> before;try{before=states();}catch(Exception e){throw new IllegalStateException(e);}
   Future<T> f=delegate.submit(task);
   return new Future<T>() {
    boolean recorded;
    void record(T value,Throwable error){if(!recorded){recorded=true;result(value,error,before);}}
    public boolean cancel(boolean interrupt){return f.cancel(interrupt);}
    public boolean isCancelled(){return f.isCancelled();}public boolean isDone(){return f.isDone();}
    public T get()throws InterruptedException,ExecutionException {
     T v;try{v=f.get();}catch(InterruptedException|ExecutionException e){record(null,e);throw e;}record(v,null);return v;
    }
    public T get(long timeout,TimeUnit unit)throws InterruptedException,ExecutionException,TimeoutException {
     T v;try{v=f.get(timeout,unit);}catch(InterruptedException|ExecutionException|TimeoutException e){record(null,e);throw e;}record(v,null);return v;
    }
   };
  }
  public void execute(Runnable task){delegate.execute(task);}
  public void shutdown(){delegate.shutdown();}public List<Runnable> shutdownNow(){return delegate.shutdownNow();}
  public boolean isShutdown(){return delegate.isShutdown();}public boolean isTerminated(){return delegate.isTerminated();}
  public boolean awaitTermination(long n,TimeUnit u)throws InterruptedException{return delegate.awaitTermination(n,u);}
 }
 public static void main(String[]args)throws Exception {
  if(args.length!=3)throw new IllegalArgumentException("graphs.tsv state new-output-directory");
  Class.forName("io.johnsonlee.graphite.webgraph.QueryCorrectnessManifest");
  String state=args[1];if(!Set.of("cold","warm","startup-prepared").contains(state))throw new IllegalArgumentException(state);
  Path out=Path.of(args[2]);Files.createDirectory(out);
  System.setProperty("graphite.broad.pressure.graphs",args[0]);
  System.setProperty("graphite.broad.pressure.correctness.mode","record");
  System.setProperty("graphite.broad.pressure.output",out.resolve("main-correctness.tsv").toString());
  System.setProperty("graphite.broad.pressure.observations.output",out.resolve("main-observations.tsv").toString());
  var benchmark=new LargeBroadQueryPressureBenchmark();benchmark.setGraphCount(64);benchmark.setTimeoutMillis(60000L);benchmark.setCoverageFamily("all");benchmark.setIndexState(state);
  try(BufferedWriter output=Files.newBufferedWriter(out.resolve("responses.jsonl"),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
   try {
    benchmark.setupTrial();
    Observer observer=new Observer((ExecutorService)field(benchmark,"queryExecutor"),benchmark,output);
    if(observer.cases.size()!=1267||observer.sources.size()!=64)throw new IllegalStateException("incomplete workload");
    field(benchmark,"queryExecutor",observer);
    Files.writeString(out.resolve("actual-cases.json"),ExportBenchmarkCases.json(ExportBenchmarkCases.plain(observer.cases))+"\n",StandardOpenOption.CREATE_NEW);
    observer.emit(new LinkedHashMap<>(Map.of("kind","header","state",state,"caseCount",1267,"graphCount",64,"performanceMeasurement",false,"loaded",observer.states())));
    benchmark.setupInvocation();observer.phase="replay";
    observer.emit(new LinkedHashMap<>(Map.of("kind","prepared","state",state,"sources",observer.states())));
    benchmark.replayBroadQueries(new LargeBroadQueryPressureCounters());
    int expected=state.equals("warm")?2534:1267;if(observer.completed!=expected)throw new IllegalStateException("incomplete capture "+observer.completed);
    observer.emit(new LinkedHashMap<>(Map.of("kind","complete","responses",observer.completed,"performanceMeasurement",false)));
   }finally {benchmark.tearDownTrial();}
  }
 }
}

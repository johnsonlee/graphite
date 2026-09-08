import com.google.gson.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.GraphStore;
import io.johnsonlee.graphite.cypher.CypherBudgetExceededException;
import kotlin.jvm.functions.Function1;
import kotlin.ranges.IntRange;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Calls actual pinned-main storage methods. This is NOT a public query oracle. */
public final class MappedCursorOracle {
 static final Gson JSON=new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create();
 static Object field(Object x,String n)throws Exception {Field f=x.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(x);}
 static Method method(Class<?> c,String name,int arity){return Arrays.stream(c.getDeclaredMethods()).filter(m->m.getName().startsWith(name)&&m.getParameterCount()==arity).peek(m->m.setAccessible(true)).findFirst().orElseThrow();}
 static Object call(Object x,Method m,Object...args)throws Throwable{try{return m.invoke(x,args);}catch(InvocationTargetException e){throw e.getCause();}}
 static class Work implements PreferredMappedStringIndexViewGraphWorkBatchConsumer {
  final List<Long> callbacks=new ArrayList<>();String action="none";final CypherBudgetExceededException cause=new CypherBudgetExceededException(777);
  public int getSegmentWorkerCount(){return 0;} public void consume(){consume(1L);} public void consume(long n){callbacks.add(n);if(action.equals("interrupt"))Thread.currentThread().interrupt();if(action.equals("throw"))throw cause;}
 }
 static class View implements AutoCloseable {
  Object v; Function1<Integer,Long> original;List<Object> reads=new ArrayList<>();String orderMode="normal";int faultNode=-1;
  @SuppressWarnings("unchecked") View(Object template)throws Exception {
   original=(Function1<Integer,Long>)field(template,"nodeOrder");
   Function1<Integer,Long> instrument=id->{Long actual=original.invoke(id);long observed=(orderMode.equals("negative")&&id==faultNode)?-1:actual;reads.add(Map.of("nodeId",id,"actualOrder",actual,"returnedOrder",observed));return observed;};
   Constructor<?> constructor=Arrays.stream(template.getClass().getDeclaredConstructors()).filter(c->c.getParameterCount()==8).findFirst().orElseThrow();constructor.setAccessible(true);
   v=constructor.newInstance(field(template,"propertyStringIds"),field(template,"propertyPostingEnds"),field(template,"propertyPostingNodeIds"),field(template,"trigramPostings"),field(template,"callSiteCount"),field(template,"stringCount"),field(template,"stringTable"),instrument);
  }
  Object cache()throws Exception {
   Object cache=field(v,"validatedPostingRanges");if(cache==null)return null;long[] keys=(long[])field(cache,"keys");byte[] states=(byte[])field(cache,"states");List<Object> entries=new ArrayList<>();for(int i=0;i<keys.length;i++)if(states[i]!=0)entries.add(Map.of("slot",i,"key",Long.toUnsignedString(keys[i]),"state",states[i]));return Map.of("entries",entries,"count",field(cache,"entries"),"closed",field(cache,"closed"));
  }
  IntBuffer postings(int property)throws Exception{return ((IntBuffer[])field(v,"propertyPostingNodeIds"))[property];}
  public void close()throws Exception{((java.io.Closeable)v).close();}
 }
 interface Action {Object run(Work w)throws Throwable;}
 static List<Object> records=new ArrayList<>();static Map<String,Object> current;static List<Object> operations;
 static void start(String name,String fixture){current=new LinkedHashMap<>();current.put("name",name);current.put("fixture",fixture);operations=new ArrayList<>();current.put("operations",operations);records.add(current);}
 static Object op(View v,String name,String callback,boolean interrupted,Map<String,Object> spec,Action action)throws Throwable {
  Thread.interrupted();v.reads.clear();Work w=new Work();w.action=callback;Map<String,Object> r=new LinkedHashMap<>();r.put("name",name);r.put("spec",spec);r.put("callbackAction",callback);r.put("preInterrupted",interrupted);r.put("beforeCache",v.cache());operations.add(r);if(interrupted)Thread.currentThread().interrupt();Object value=null;
  try{value=action.run(w);r.put("outcome","SUCCESS");r.put("value",value);}catch(Throwable t){r.put("outcome","FAILED");r.put("errorClass",t.getClass().getName());r.put("message",t.getMessage());r.put("sameAsCallbackCause",t==w.cause);r.put("stack",Arrays.stream(t.getStackTrace()).map(Object::toString).toList());}
  finally{r.put("callbacks",w.callbacks);r.put("nodeOrderReads",new ArrayList<>(v.reads));r.put("interruptedAfterMethod",Thread.currentThread().isInterrupted());r.put("afterCache",v.cache());r.put("clearedForNextOperation",Thread.interrupted());}
  return value;
 }
 static Object range(View v,String name,int property,int row,int first,int last,String cb,boolean interrupted,boolean drain)throws Throwable {
  return op(v,name,cb,interrupted,Map.of("method","validatedPostingCursor","property",property,"row",row,"firstPosition",first,"lastPosition",last,"drainCursor",drain,"nodeOrderMode",v.orderMode,"faultNode",v.faultNode),w->{
   Object cursor=call(v.v,method(v.v.getClass(),"validatedPostingCursor",5),property,row,v.postings(property),new IntRange(first,last),w);
   if(cursor==null)return null;boolean initialHasCurrent=(boolean)call(cursor,method(cursor.getClass(),"hasCurrent",0));List<Object> yields=new ArrayList<>();if(drain){while((boolean)call(cursor,method(cursor.getClass(),"hasCurrent",0))){yields.add(Map.of("nodeId",field(cursor,"nodeId"),"order",field(cursor,"order")));if(!(boolean)call(cursor,method(cursor.getClass(),"advance",0)))break;}}
   return Map.of("cursorReturned",true,"initialHasCurrent",initialHasCurrent,"yields",yields,"cursorFinalPosition",field(cursor,"position"));
  });
 }
 static Object binary(View v,String name,String cb)throws Throwable {
  return op(v,name,cb,false,Map.of("method","binarySearch","property",0,"targetIndex",0),w->{IntBuffer values=((IntBuffer[])field(v.v,"propertyStringIds"))[0];Class<?> top=Class.forName("io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndexViewKt");return call(null,method(top,"binarySearch",3),values,values.get(0),w);});
 }
 public static void main(String[] args)throws Throwable {
  System.setProperty("graphite.webgraph.prepareCallSiteStringIndexOnLoad","lazy");Map<String,Object> templates=new LinkedHashMap<>();List<Object> loadRecords=new ArrayList<>();List<java.io.Closeable> graphs=new ArrayList<>();
  try {
   for(String name:List.of("large","hit64")){Object graph=GraphStore.INSTANCE.loadMapped(Path.of(args[0],name));graphs.add((java.io.Closeable)graph);Work work=new Work();Object view=call(graph,method(graph.getClass(),"mappedCallSiteStringIndexView",1),work);if(view==null)throw new IllegalStateException("actual mapped load returned null");templates.put(name,view);loadRecords.add(Map.of("fixture",name,"callbacks",work.callbacks,"callSiteCount",field(view,"callSiteCount"),"stringCount",field(view,"stringCount")));}
   Object large=templates.get("large"),hit=templates.get("hit64");
   start("C01-preinterrupted-absolute1023","large");try(View v=new View(large)){range(v,"validate",0,0,1023,1023,"none",true,true);}
   start("C02-preinterrupted-absolute1024","large");try(View v=new View(large)){range(v,"validate",0,0,1024,1024,"none",true,true);}
   start("C03-preinterrupted-cross1024","large");try(View v=new View(large)){range(v,"validate",0,0,1023,1024,"none",true,true);}
   start("C04-binary-finally-interrupt","hit64");try(View v=new View(hit)){binary(v,"binary","interrupt");}
   start("C05-binary-finally-original-budget-cause","hit64");try(View v=new View(hit)){binary(v,"binary","throw");}
   start("C06-range-finally-interrupt-publishes","large");try(View v=new View(large)){range(v,"cold",0,0,1,3,"interrupt",false,true);range(v,"warm",0,0,1,3,"none",false,true);}
   start("C07-range-finally-throw-does-not-publish","large");try(View v=new View(large)){range(v,"failed",0,0,1,3,"throw",false,true);range(v,"retry",0,0,1,3,"none",false,true);range(v,"warm",0,0,1,3,"none",false,true);}
   start("C08-invalid-order-fullrange-cached","large");try(View v=new View(large)){v.orderMode="negative";v.faultNode=v.postings(0).get(1);range(v,"invalid",0,0,1,3,"none",false,true);v.orderMode="normal";range(v,"cached-invalid",0,0,1,3,"none",false,true);}
   start("C09-real-key-collision-failure-preserves","hit64");try(View v=new View(hit)){
    IntBuffer ends=((IntBuffer[])field(v.v,"propertyPostingEnds"))[1];int first=ends.get(0),last=ends.get(1)-1;
    range(v,"seed",0,0,0,127,"none",false,false);range(v,"collision-throw",1,1,first,last,"throw",false,true);range(v,"original-still-cached",0,0,0,127,"none",false,false);
    v.orderMode="negative";v.faultNode=v.postings(1).get(first);range(v,"completed-invalid-collision-publishes",1,1,first,last,"none",false,true);
   }
   start("C10-binary-interrupt-continues-nonpoll-range","large");try(View v=new View(large)){
    op(v,"binary-then-range","interrupt",false,Map.of("method","binarySearch then validatedPostingCursor","firstPosition",1023,"lastPosition",1023),w->{IntBuffer values=((IntBuffer[])field(v.v,"propertyStringIds"))[0];Object found=call(null,method(Class.forName("io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndexViewKt"),"binarySearch",3),values,values.get(0),w);Object cursor=call(v.v,method(v.v.getClass(),"validatedPostingCursor",5),0,0,v.postings(0),new IntRange(1023,1023),w);return Map.of("binaryReturn",found,"cursorReturned",cursor!=null,"nodeId",field(cursor,"nodeId"),"order",field(cursor,"order"));});
   }
   start("C11-empty-range-constructor-after-publication","large");try(View v=new View(large)){range(v,"cold-empty",0,0,1,0,"none",false,true);range(v,"warm-empty",0,0,1,0,"none",false,true);}
  } finally {Thread.interrupted();for(var g:graphs)g.close();}
  Files.writeString(Path.of(args[1]),JSON.toJson(Map.of("scope","actual-main storage-method contract; not public query execution","mainRevision","4e328b0109e13c896b74004823fb049fcb19251a","javaVersion",System.getProperty("java.version"),"performanceMeasurements",0,"loads",loadRecords,"cases",records))+"\n",StandardOpenOption.CREATE_NEW);
 }
}

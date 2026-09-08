import com.google.gson.*;
import io.johnsonlee.graphite.graph.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Invokes the original buffered class. The delegate only observes calls and injects declared failures. */
public final class BufferedOracle {
 static List<Object> calls;static long failCall;static Object buffer;static Field pending;
 static Map<String,Object> snapshot()throws Exception{return Map.of("pending",Long.toString(pending.getLong(buffer)),"delegateCalls",new ArrayList<>(calls));}
 public static void main(String[] args)throws Exception{
  var json=new GsonBuilder().serializeNulls().setPrettyPrinting().create();var specs=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();List<Object> records=new ArrayList<>();
  Class<?> clazz=Class.forName("io.johnsonlee.graphite.webgraph.BufferedGraphWorkConsumer");var constructor=clazz.getDeclaredConstructor(GraphWorkConsumer.class);constructor.setAccessible(true);pending=clazz.getDeclaredField("pending");pending.setAccessible(true);var consume=clazz.getDeclaredMethod("consume");consume.setAccessible(true);var flush=clazz.getDeclaredMethod("flush");flush.setAccessible(true);
  for(var raw:specs){var spec=raw.getAsJsonObject();calls=new ArrayList<>();failCall=spec.has("failCall")?spec.get("failCall").getAsLong():-1;
   GraphWorkConsumer delegate;String mode=spec.get("delegate").getAsString();
   if(mode.equals("null"))delegate=null;
   else if(mode.equals("unit"))delegate=()->{calls.add("1");if(calls.size()==failCall)throw new IllegalStateException("declared delegate failure");};
   else delegate=new GraphWorkBatchConsumer(){public void consume(){consume(1L);}public void consume(long n){calls.add(Long.toString(n));if(calls.size()==failCall)throw new IllegalStateException("declared delegate failure");}};
   buffer=constructor.newInstance(delegate);List<Object> operations=new ArrayList<>();
   for(var input:spec.getAsJsonArray("operations")){var op=input.getAsJsonObject();Map<String,Object> r=new LinkedHashMap<>();r.put("spec",op);r.put("before",snapshot());int completed=0;
    try{if(op.get("op").getAsString().equals("flush"))flush.invoke(buffer);else for(int i=0;i<op.get("count").getAsInt();i++){consume.invoke(buffer);completed++;}r.put("outcome","SUCCESS");}
    catch(Throwable t){while(t instanceof InvocationTargetException)t=((InvocationTargetException)t).getCause();r.put("outcome","FAILED");r.put("error",t.getClass().getSimpleName());r.put("errorClass",t.getClass().getName());r.put("message",t.getMessage());r.put("stack",Arrays.stream(t.getStackTrace()).map(Object::toString).toList());}
    r.put("completedConsumes",completed);r.put("after",snapshot());operations.add(r);
   }
   records.add(Map.of("name",spec.get("name").getAsString(),"spec",spec,"operations",operations,"final",snapshot()));
  }
  Files.writeString(Path.of(args[1]),json.toJson(Map.of("mainRevision","4e328b0109e13c896b74004823fb049fcb19251a","javaVersion",System.getProperty("java.version"),"performanceMeasurements",0,"cases",records))+"\n",StandardOpenOption.CREATE_NEW);
 }
}

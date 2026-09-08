import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.GraphStore;
import it.unimi.dsi.fastutil.io.BinIO;
import it.unimi.dsi.util.FrontCodedStringList;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Actual pinned-main execution. No timers, performance measurements, or inferred expected values. */
public final class GenericDisjunctionOracle {
 static final Gson JSON=new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create();
 static void write(Path p,Object o)throws Exception {String raw=JSON.toJson(o);StringBuilder s=new StringBuilder();for(char c:raw.toCharArray()){if(Character.isSurrogate(c))s.append(String.format("\\u%04x",(int)c));else s.append(c);}Files.writeString(p,s+"\n",StandardOpenOption.CREATE_NEW);}
 static List<Integer> units(String s){List<Integer> a=new ArrayList<>();if(s!=null)for(char c:s.toCharArray())a.add((int)c);return a;}
 static Object utf16(Object o){if(o instanceof String)return units((String)o);if(o instanceof Map){Map<String,Object>a=new LinkedHashMap<>();((Map<?,?>)o).forEach((k,v)->a.put(k.toString(),utf16(v)));return a;}if(o instanceof List){List<Object>a=new ArrayList<>();for(Object v:(List<?>)o)a.add(utf16(v));return a;}return o;}
 static Object state(List<CypherGraph> sources)throws Exception {List<Object>a=new ArrayList<>();for(var source:sources){Map<String,Object> item=new LinkedHashMap<>();item.put("id",source.getId());for(String key:List.of("retained","mappedView")){String prefix=key.equals("retained")?"isCallSiteStringIndexInitialized":"isMappedCallSiteStringIndexViewInitialized";for(Method m:source.getGraph().getClass().getDeclaredMethods())if(m.getName().startsWith(prefix)&&m.getParameterCount()==0){m.setAccessible(true);item.put(key,m.invoke(source.getGraph()));}}a.add(item);}return a;}
 static void prepare(Path fixtures,JsonArray specs,JsonObject mutations)throws Exception {
  for(JsonElement input:specs){JsonObject spec=input.getAsJsonObject();JsonArray variants=spec.getAsJsonArray("fixtures");for(int i=0;i<variants.size();i++){
   Path store=fixtures.resolve(spec.get("name").getAsString()).resolve("store"+i);
   // Generate native index sidecars against original bytes before adversarial mutation.
   Graph graph=GraphStore.INSTANCE.loadMapped(store);((java.io.Closeable)graph).close();
   for(JsonElement change:mutations.getAsJsonArray(variants.get(i).getAsString())){JsonObject m=change.getAsJsonObject();Path file=store.resolve(m.get("file").getAsString());String format=m.get("format").getAsString();
    if(format.equals("string-replace")){FrontCodedStringList f=(FrontCodedStringList)BinIO.loadObject(file.toString());List<String> values=new ArrayList<>();for(int j=0;j<f.size();j++)values.add(f.get(j).toString());for(var e:m.getAsJsonObject("values").entrySet())values.set(Integer.parseInt(e.getKey()),e.getValue().getAsString());BinIO.storeObject(new FrontCodedStringList(values.iterator(),4,false),file.toString());continue;}
    byte[] data=Files.readAllBytes(file);int offset=m.get("offset").getAsInt();long value=m.get("value").getAsLong();ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
    switch(format){case "int":b.putInt(offset,(int)value);break;case "long":b.putLong(offset,value);break;case "byte":b.put(offset,(byte)value);break;case "truncate":data=Arrays.copyOf(data,(int)value);break;default:throw new IllegalArgumentException(format);}Files.write(file,data);
   }
  }}
 }
 static void error(Map<String,Object> r,Throwable t){while(t instanceof InvocationTargetException)t=((InvocationTargetException)t).getCause();r.put("outcome","FAILED");r.put("error",t.getClass().getSimpleName());r.put("errorClass",t.getClass().getName());r.put("message",t.getMessage());r.put("errorUTF16",units(t.getMessage()));r.put("stack",Arrays.stream(t.getStackTrace()).map(Object::toString).toList());}
 public static void main(String[] args)throws Exception {
  System.setProperty("graphite.webgraph.prepareCallSiteStringIndexOnLoad","lazy");
  if(!System.getProperty("java.specification.version").equals("17"))throw new IllegalStateException("Java17 required");
  JsonArray specs=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray();Path fixtures=Path.of(args[2]);
  if(args[0].equals("prepare")){prepare(fixtures,specs,JsonParser.parseString(Files.readString(Path.of(args[3]))).getAsJsonObject());return;}
  List<Object> output=new ArrayList<>();
  for(JsonElement input:specs){JsonObject spec=input.getAsJsonObject();String name=spec.get("name").getAsString();Map<String,Object> r=new LinkedHashMap<>();r.put("name",name);r.put("spec",spec);List<CypherGraph> sources=new ArrayList<>();String phase="load";CypherExecutionContext context=new CypherExecutionContext(new CypherExecutionBudget(Long.MAX_VALUE),new CypherCancellationSignal());
   try{int count=spec.getAsJsonArray("fixtures").size();for(int i=0;i<count;i++)sources.add(new CypherGraph(count==1?"single":i==0?"z-first":"a-second",GraphStore.INSTANCE.loadMapped(fixtures.resolve(name).resolve("store"+i))));r.put("before",state(sources));phase="execute";
    @SuppressWarnings("unchecked") Map<String,Object> parameters=JSON.fromJson(spec.get("parameters"),Map.class);
    if(spec.has("providerType")){
     phase="provider-create";Graph graph=sources.get(0).getGraph();Method method=Arrays.stream(graph.getClass().getDeclaredMethods()).filter(m->m.getName().equals("lookupStringPropertyDisjunction")).findFirst().orElseThrow();method.setAccessible(true);List<StringPropertyPredicate> predicates=new ArrayList<>();for(JsonElement p:spec.getAsJsonArray("providerProperties"))predicates.add(new StringPropertyPredicate(p.getAsString(),StringValueTransform.LOWERCASE,StringMatchMode.CONTAINS,(String)parameters.get("term")));
     Object sequence=method.invoke(graph,Class.forName("io.johnsonlee.graphite.core."+spec.get("providerType").getAsString()),predicates,200,null);r.put("providerSupported",sequence!=null);List<Object> values=new ArrayList<>();r.put("yielded",values);
     if(sequence!=null){phase="provider-consume";Iterator<?> it=((kotlin.sequences.Sequence<?>)sequence).iterator();while(it.hasNext()){Object node=it.next();Map<String,Object> v=new LinkedHashMap<>();v.put("type",node.getClass().getName());Method id=Arrays.stream(node.getClass().getMethods()).filter(m->m.getName().startsWith("getId-")).findFirst().orElseThrow();v.put("id",id.invoke(node));v.put("value",node.toString());v.put("valueUTF16",units(node.toString()));values.add(v);}}
    }else{
     CypherResult result=count==1?new CypherExecutor(sources.get(0).getGraph(),context).execute(spec.get("query").getAsString(),parameters):new CrossGraphCypherExecutor(sources,context,spec.get("scoped").getAsBoolean()).execute(spec.get("query").getAsString(),parameters);
     r.put("columns",result.getColumns());r.put("rows",result.getRows());r.put("rowsUTF16",utf16(result.getRows()));
    }r.put("outcome","SUCCESS");
   }catch(Throwable t){error(r,t);}finally{r.put("diagnostics",context.getDiagnostics());r.put("after",state(sources));for(var source:sources)((java.io.Closeable)source.getGraph()).close();}
   r.put("phase",phase);output.add(r);
  }
  write(Path.of(args[3]),Map.of("javaVersion",System.getProperty("java.version"),"mainRevision","4e328b0109e13c896b74004823fb049fcb19251a","performanceMeasurements",0,"cases",output));
 }
}

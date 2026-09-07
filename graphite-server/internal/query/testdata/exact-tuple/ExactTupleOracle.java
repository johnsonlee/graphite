import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.core.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import java.nio.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import kotlin.jvm.functions.Function2;
public class ExactTupleOracle {
 static Object field(Object o,String n)throws Exception{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static Object call(Object o,String n,Class<?>[] t,Object...a)throws Exception{try{return o.getClass().getMethod(n,t).invoke(o,a);}catch(InvocationTargetException x){if(x.getCause() instanceof Exception)throw(Exception)x.getCause();throw x;}}
 static Map<String,Object> state(Object i)throws Exception{return new LinkedHashMap<>(Map.of("bytes",call(i,"getRetainedBytes",new Class<?>[0]),"enabled",field(i,"exactProjectionTupleIndexEnabled"),"tuple",field(i,"exactProjectionTupleIndex")!=null,"reservationClosed",field(field(i,"reservation"),"closed")));}
 static String ascii(String s){var b=new StringBuilder();for(char c:s.toCharArray())if(c>=128)b.append(String.format("\\u%04x",(int)c));else b.append(c);return b.toString();}
 static final List<String> FOUR=List.of("caller_class","caller_name","callee_class","callee_name");
 static final List<StringPropertyPredicate> PS=List.of(new StringPropertyPredicate("caller_name",null,StringMatchMode.CONTAINS,"other"));
 static CallSiteNode template;
 static void generate(Path root,int n,boolean bad,boolean persisted)throws Exception{
  var b=new DefaultGraph.Builder();var ctor=CallSiteNode.class.getConstructors()[0];
  for(int i=0;i<n;i++){var c=template.getCaller();var caller=new MethodDescriptor(c.getDeclaringClass(),"other"+(i%256),List.of(),c.getReturnType());b.addNode((Node)ctor.newInstance(i,caller,template.getCallee(),null,null,List.of(),null));}
  GraphStore.INSTANCE.save(b.build(),root,3,true);
  if(bad){byte[] data=Files.readAllBytes(root.resolve("graph.nodedata"));var buf=ByteBuffer.wrap(data);var off=ByteBuffer.wrap(Files.readAllBytes(root.resolve("graph.nodeoffsets")));int at=(int)off.getLong(8+(n-1)*8)-1;int callerCount=buf.getInt(at+13);int callee=at+5+(4+callerCount)*4;buf.putInt(callee+4,Integer.MAX_VALUE);Files.write(root.resolve("graph.nodedata"),data);}
  if(!persisted)Files.delete(root.resolve("graph.callsite-string-index"));
 }
 static Set<List<String>> values(List<String> props,int count){Set<List<String>> out=new LinkedHashSet<>();for(int j=0;j<count;j++){var v=new ArrayList<String>();for(String p:props)v.add(switch(p){case "caller_class"->template.getCaller().getDeclaringClass().getClassName();case "caller_name"->"other"+j;case "callee_class"->template.getCallee().getDeclaringClass().getClassName();case "callee_name"->template.getCallee().getName();default->null;});out.add(v);}return out;}
 static Map<String,Object> run(Path path,List<String> props,int count,boolean cancel,boolean repeat)throws Exception{
  if(path.getFileName().toString().equals("built4096"))Files.deleteIfExists(path.resolve("graph.callsite-string-index"));Graph g=GraphStore.INSTANCE.loadMapped(path);var e=new CypherExecutor(g);e.execute("MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN DISTINCT n.caller_name AS x LIMIT 1");Object i=field(g,"callSiteStringIndex");call(i,"clearQueryCaches",new Class<?>[0]);var out=new LinkedHashMap<String,Object>();out.put("fixture",path.getFileName().toString());out.put("properties",props);out.put("selected",count);out.put("cancel",cancel);out.put("before",state(i));
  var f=i.getClass().getDeclaredField("rawStringPropertyId");f.setAccessible(true);var original=(Function2<Integer,Integer,Integer>)f.get(i);var trace=new ArrayList<List<Integer>>();f.set(i,(Function2<Integer,Integer,Integer>)(node,p)->{trace.add(List.of(node,p));return original.invoke(node,p);});
  try{out.put("rows",call(i,"distinctProjection",new Class<?>[]{List.class,List.class,int.class,Set.class,GraphWorkConsumer.class},PS,props,1000,values(props,count),cancel?(GraphWorkConsumer)()->{if(!trace.isEmpty())throw new CancellationException("AUDIT_CANCEL");}:null));}catch(Exception x){out.put("errorClass",x.getClass().getSimpleName());out.put("error",x.getMessage());}
  out.put("after",state(i));out.put("rawReadCount",trace.size());out.put("rawPrefix",new ArrayList<>(trace.subList(0,Math.min(trace.size(),20))));out.put("rawDistinctNodeCount",trace.stream().map(v->v.get(0)).distinct().count());
  call(i,"clearQueryCaches",new Class<?>[0]);out.put("afterClear",state(i));
  if(repeat){int before=trace.size();try{out.put("repeatRows",call(i,"distinctProjection",new Class<?>[]{List.class,List.class,int.class,Set.class,GraphWorkConsumer.class},PS,FOUR,cancel?1000:1,values(FOUR,cancel?256:1),null));}catch(Exception x){out.put("repeatError",x.toString());}out.put("repeatReads",trace.size()-before);out.put("afterRepeat",state(i));}
  ((java.io.Closeable)g).close();out.put("afterClose",state(i));return out;
 }
 public static void main(String[]a)throws Exception{
  Path root=Path.of(a[0]);Files.createDirectories(root);Graph ref=GraphStore.INSTANCE.load(Path.of(a[1]),GraphStore.LoadMode.EAGER);template=kotlin.sequences.SequencesKt.toList(ref.nodes(CallSiteNode.class)).get(0);
  for(String name:List.of("n4095","n4096","bad4096","built4096"))if(!Files.exists(root.resolve(name)))generate(root.resolve(name),name.equals("n4095")?4095:4096,name.equals("bad4096"),!name.equals("built4096"));
  var results=new ArrayList<Object>();
  results.add(run(root.resolve("n4095"),FOUR,256,false,false));
  results.add(run(root.resolve("n4096"),FOUR,255,false,false));
  results.add(run(root.resolve("n4096"),FOUR,256,false,true));
  results.add(run(root.resolve("built4096"),FOUR,256,false,false));
  results.add(run(root.resolve("n4096"),List.of("caller_name"),256,false,true));
  results.add(run(root.resolve("n4096"),List.of("caller_name","caller_name"),256,false,false));
  results.add(run(root.resolve("n4096"),List.of("caller_name","name"),256,false,false));
  results.add(run(root.resolve("bad4096"),List.of("caller_name"),255,false,false));
  results.add(run(root.resolve("bad4096"),List.of("caller_name"),256,false,false));
  results.add(run(root.resolve("n4096"),FOUR,256,true,true));
  Files.writeString(Path.of(a[2]),ascii(new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(results))+"\n");
 }
}
